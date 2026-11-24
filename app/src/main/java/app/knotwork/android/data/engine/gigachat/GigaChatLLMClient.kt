package app.knotwork.android.data.engine.gigachat

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.http.client.KoogHttpClient
import ai.koog.http.client.KoogHttpClientException
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.clients.ConnectionTimeoutConfig
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.LLMClientException
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.utils.time.KoogClock
import app.knotwork.android.data.engine.retry.causeChain
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.UUID

/**
 * The Koog [LLMClient] of the GigaChat API, written by the app because Koog has none and its
 * OpenAI client cannot stand in for one:
 * - **Authentication is OAuth, not a key.** The saved authorization key is exchanged at
 *   `ngw.devices.sberbank.ru` for an access token that lives 30 minutes; a client whose
 *   `Authorization` header is fixed when it is built (as Koog's are) would fail half an hour into
 *   a long session. Tokens come from [tokenCache], which every client of the process shares.
 * - **Functions are not tools.** See [GigaChatMessageMapper].
 *
 * Everything else is the platform the other providers run on: requests go out on the
 * [KoogHttpClient.Factory] the factory passes in — [app.knotwork.android.data.engine.KoogTransportFactory],
 * which applies the hop rule to every hop (the OAuth request included) and records `Retry-After` —
 * with [CloudClientTimeouts][app.knotwork.android.data.engine.CloudClientTimeouts]; an HTTP failure
 * surfaces as Koog's [KoogHttpClientException] carrying its status, which is what the retry policy
 * judges (429 and 5xx are retried by `RetryingCloudLlmClient`, not here).
 *
 * **401.** An expired or revoked token answers 401. The client drops that token, asks for a new
 * one and repeats the request **once**; a second 401 is the answer. A streamed request is repeated
 * only if the 401 came before its first frame, which is always the case for an authentication
 * failure. A 401 of the OAuth endpoint itself means the authorization key or the scope is wrong,
 * and is never repeated.
 *
 * **End of a stream.** The client emits [StreamFrame.End] with the `finish_reason` of the last
 * chunk (`stop` when only `data: [DONE]` closed it); a stream that stopped before either ends with
 * `End(finishReason = null)`, which `CloudStreamEnd` reads as a cut-off answer.
 *
 * @property credentials The authorization key and scope.
 * @property tokenCache The process's access tokens.
 * @param httpClientFactory The transport.
 * @param timeouts The deadlines; always `CloudClientTimeouts.CONFIG` in production.
 * @property endpoints Where to send; replaced only by tests.
 * @property requestId A fresh `RqUID` (UUID v4) per token request.
 * @property clock Stamps answers' metadata.
 */
class GigaChatLLMClient(
    private val credentials: GigaChatCredentials,
    private val tokenCache: GigaChatTokenCache,
    httpClientFactory: KoogHttpClient.Factory,
    timeouts: ConnectionTimeoutConfig,
    private val endpoints: GigaChatEndpoints = GigaChatEndpoints.PRODUCTION,
    private val requestId: () -> String = { UUID.randomUUID().toString() },
    private val clock: KoogClock = KoogClock.System,
) : LLMClient() {

    override val clientName: String = CLIENT_NAME

    private val oauth: KoogHttpClient = httpClientFactory.create(
        clientName = CLIENT_NAME,
        baseUrl = endpoints.oauthBaseUrl,
        requestTimeoutMillis = timeouts.requestTimeoutMillis,
        connectTimeoutMillis = timeouts.connectTimeoutMillis,
        socketTimeoutMillis = timeouts.socketTimeoutMillis,
    )

    private val api: KoogHttpClient = httpClientFactory.create(
        clientName = CLIENT_NAME,
        baseUrl = endpoints.apiBaseUrl,
        requestTimeoutMillis = timeouts.requestTimeoutMillis,
        connectTimeoutMillis = timeouts.connectTimeoutMillis,
        socketTimeoutMillis = timeouts.socketTimeoutMillis,
    )

    override fun llmProvider(): LLMProvider = GigaChatLLMProvider

    override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant {
        val body = encode(GigaChatMessageMapper.request(prompt, model, tools, stream = false))
        val answer = authorized { token ->
            api.post(
                path = GigaChatEndpoints.CHAT_PATH,
                requestBody = body,
                requestBodyType = String::class,
                responseType = String::class,
                headers = jsonHeaders(token),
            )
        }
        val completion = GigaChatJson.decodeFromString(GigaChatCompletion.serializer(), answer)
        return GigaChatMessageMapper.assistant(completion, clock)
    }

    override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> =
        flow {
            val body = encode(GigaChatMessageMapper.request(prompt, model, tools, stream = true))
            var token = accessToken()
            var repeated = false
            while (true) {
                var emitted = false
                try {
                    streamOnce(body, token).collect { frame ->
                        emitted = true
                        emit(frame)
                    }
                    return@flow
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (emitted || repeated || !e.isUnauthorized()) throw e
                    tokenCache.invalidate(credentials, token)
                    token = accessToken()
                    repeated = true
                }
            }
        }

    /** One streamed request with [token]: its text and function-call frames, then [StreamFrame.End]. */
    private fun streamOnce(body: String, token: String): Flow<StreamFrame> = flow {
        val state = StreamState()
        api.sse(
            path = GigaChatEndpoints.CHAT_PATH,
            requestBody = body,
            requestBodyType = String::class,
            dataFilter = { !it.isNullOrBlank() },
            decodeStreamingResponse = ::decodeEvent,
            processStreamingChunk = { it },
            // Only the token: the transport sets `Accept: text/event-stream`, which a JSON Accept
            // would replace.
            headers = bearer(token),
        ).collect { event ->
            when (event) {
                StreamEvent.Done -> state.done = true
                is StreamEvent.Chunk -> state.read(event.completion).forEach { emit(it) }
            }
        }
        // `[DONE]` is as strong a sign of a whole answer as a finish reason; a stream that has
        // neither was cut, and its `End` says so with no finish reason.
        val finishReason = state.finishReason ?: DONE_FINISH_REASON.takeIf { state.done }
        emit(StreamFrame.End(finishReason = finishReason, metaInfo = state.metaInfo()))
    }

    /** What one stream has said so far. */
    private inner class StreamState {
        var finishReason: String? = null
        var done = false
        private var usage: GigaChatCompletion? = null

        /** The frames of one chunk; remembers its finish reason and token counts. */
        fun read(chunk: GigaChatCompletion): List<StreamFrame> {
            if (chunk.usage != null) usage = chunk
            val choice = chunk.choices.firstOrNull() ?: return emptyList()
            choice.finishReason?.let { finishReason = it }
            val delta = choice.delta ?: choice.message ?: return emptyList()
            return listOfNotNull(
                delta.content?.takeIf { it.isNotEmpty() }?.let { StreamFrame.TextDelta(it) },
                delta.functionCall?.let { call ->
                    val part = GigaChatMessageMapper.toolCall(call, delta.functionsStateId)
                    StreamFrame.ToolCallComplete(id = part.id, name = part.tool, content = part.args)
                },
            )
        }

        fun metaInfo(): ResponseMetaInfo =
            usage?.let { GigaChatMessageMapper.metaInfo(it, clock) } ?: ResponseMetaInfo.create(clock)
    }

    /**
     * The chat models the account may call — the `chat` entries of `GET /models` (embedders and the
     * AI-text checker are left out). A connection check lists these.
     */
    override suspend fun models(): List<LLModel> {
        val answer = authorized { token ->
            api.get(
                path = GigaChatEndpoints.MODELS_PATH,
                responseType = String::class,
                headers = jsonHeaders(token),
            )
        }
        return GigaChatJson.decodeFromString(GigaChatModelList.serializer(), answer).data
            .filter { it.type == null || it.type == GigaChatModels.CHAT_MODEL_TYPE }
            .map { GigaChatModels.chat(it.id) }
    }

    override suspend fun embed(text: String, model: LLModel): List<Double> = embed(listOf(text), model).single()

    override suspend fun embed(inputs: List<String>, model: LLModel): List<List<Double>> {
        val body = GigaChatJson.encodeToString(
            GigaChatEmbeddingRequest.serializer(),
            GigaChatEmbeddingRequest(model = model.id, input = inputs),
        )
        val answer = authorized { token ->
            api.post(
                path = GigaChatEndpoints.EMBEDDINGS_PATH,
                requestBody = body,
                requestBodyType = String::class,
                responseType = String::class,
                headers = jsonHeaders(token),
            )
        }
        return GigaChatJson.decodeFromString(GigaChatEmbeddingResponse.serializer(), answer).data
            .sortedBy { it.index }
            .map { it.embedding }
    }

    /** GigaChat has no moderation endpoint of the shape Koog asks for. */
    override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult =
        throw LLMClientException(CLIENT_NAME, "GigaChat offers no moderation endpoint")

    override fun close() {
        oauth.close()
        api.close()
    }

    /**
     * Runs [request] with a token; on a 401 drops that token and runs it once more with a new one.
     */
    private suspend fun <T> authorized(request: suspend (token: String) -> T): T {
        val token = accessToken()
        return try {
            request(token)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!e.isUnauthorized()) throw e
            tokenCache.invalidate(credentials, token)
            request(accessToken())
        }
    }

    /** A token that stays valid for a minute at least, from the cache or the OAuth endpoint. */
    private suspend fun accessToken(): String = tokenCache.token(credentials, ::requestToken)

    /**
     * Asks the OAuth endpoint for a token: `Authorization: Basic <key>`, a fresh `RqUID`, and the
     * scope as a form field. A failure is wrapped to say which step failed — the HTTP status stays
     * in the cause, so a 401 here reads as a rejected key, and a 429 is retried like any other.
     */
    private suspend fun requestToken(): GigaChatAccessToken {
        val answer = try {
            oauth.post(
                path = GigaChatEndpoints.OAUTH_PATH,
                requestBody = "$SCOPE_FIELD=${credentials.scope.wire}",
                requestBodyType = String::class,
                responseType = String::class,
                headers = mapOf(
                    AUTHORIZATION to "$BASIC ${credentials.authorizationKey}",
                    RQ_UID to requestId(),
                    CONTENT_TYPE to FORM_CONTENT_TYPE,
                    ACCEPT to JSON_CONTENT_TYPE,
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw LLMClientException(
                CLIENT_NAME,
                "GigaChat did not issue an access token for scope ${credentials.scope.wire} — " +
                    "check the authorization key and that the scope matches your project",
                e,
            )
        }
        return GigaChatAccessToken.of(GigaChatJson.decodeFromString(GigaChatTokenResponse.serializer(), answer))
    }

    private fun bearer(token: String): Map<String, String> = mapOf(AUTHORIZATION to "$BEARER $token")

    private fun jsonHeaders(token: String): Map<String, String> = bearer(token) + (ACCEPT to JSON_CONTENT_TYPE)

    private fun encode(request: GigaChatChatRequest): String =
        GigaChatJson.encodeToString(GigaChatChatRequest.serializer(), request)

    /** One server-sent event: `[DONE]`, or a chunk of the answer. */
    private sealed interface StreamEvent {
        /** `data: [DONE]` — the stream is complete. */
        data object Done : StreamEvent

        /** One chunk. */
        data class Chunk(val completion: GigaChatCompletion) : StreamEvent
    }

    private fun decodeEvent(data: String): StreamEvent = if (data == DONE) {
        StreamEvent.Done
    } else {
        StreamEvent.Chunk(GigaChatJson.decodeFromString(GigaChatCompletion.serializer(), data))
    }

    /** Whether [this] or a cause is an HTTP 401 answer. */
    private fun Throwable.isUnauthorized(): Boolean =
        causeChain(this).any { (it as? KoogHttpClientException)?.statusCode == HTTP_UNAUTHORIZED }

    private companion object {
        const val CLIENT_NAME = "GigaChatLLMClient"
        const val AUTHORIZATION = "Authorization"
        const val RQ_UID = "RqUID"
        const val CONTENT_TYPE = "Content-Type"
        const val ACCEPT = "Accept"
        const val BASIC = "Basic"
        const val BEARER = "Bearer"
        const val SCOPE_FIELD = "scope"
        const val FORM_CONTENT_TYPE = "application/x-www-form-urlencoded"
        const val JSON_CONTENT_TYPE = "application/json"
        const val DONE = "[DONE]"
        const val DONE_FINISH_REASON = "stop"
        const val HTTP_UNAUTHORIZED = 401
    }
}
