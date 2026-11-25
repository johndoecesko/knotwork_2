package app.knotwork.android.data.engine.gigachat

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.http.client.KoogHttpClientException
import ai.koog.http.client.ktor.KtorKoogHttpClient
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.LLMClientException
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.streaming.StreamFrame
import app.knotwork.android.data.engine.CloudClientTimeouts
import app.knotwork.android.domain.engine.CloudStreamEnd
import app.knotwork.android.domain.models.CloudProvider
import app.knotwork.android.domain.models.GigaChatScope
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.UUID

/**
 * [GigaChatLLMClient] on the wire, against a local server standing in for both the OAuth endpoint
 * (`/api/v2/oauth`) and the API (`/v1/...`): the token request, the Bearer token on every call, the
 * shared token cache, the single repeat after a 401, the end of a stream, functions, the model list.
 */
class GigaChatLLMClientTest {

    private val server = MockWebServer().apply { start() }
    private val cache = GigaChatTokenCache()
    private val credentials = GigaChatCredentials(AUTH_KEY, GigaChatScope.BUSINESS_PREPAID)

    @After
    fun tearDown() {
        server.close()
    }

    private fun client(cache: GigaChatTokenCache = this.cache) = GigaChatLLMClient(
        credentials = credentials,
        tokenCache = cache,
        httpClientFactory = KtorKoogHttpClient.Factory(),
        timeouts = CloudClientTimeouts.CONFIG,
        endpoints = GigaChatEndpoints(
            oauthBaseUrl = server.url("/api/v2").toString(),
            apiBaseUrl = server.url("/v1").toString(),
        ),
    )

    private fun token(value: String = "tok-1"): MockResponse = json(
        """{"access_token":"$value","expires_at":${System.currentTimeMillis() + THIRTY_MINUTES_MS}}""",
    )

    private fun json(body: String, code: Int = 200): MockResponse = MockResponse.Builder()
        .code(code)
        .addHeader("Content-Type", "application/json")
        .body(body)
        .build()

    private fun sse(vararg events: String): MockResponse = MockResponse.Builder()
        .code(200)
        .addHeader("Content-Type", "text/event-stream")
        .body(events.joinToString("") { "data: $it\n\n" })
        .build()

    private fun chunk(content: String, finish: String? = null, usage: Boolean = false): String {
        val reason = finish?.let { "\"$it\"" } ?: "null"
        val counts = if (usage) ""","usage":{"prompt_tokens":7,"completion_tokens":3,"total_tokens":10}""" else ""
        return """{"choices":[{"index":0,"delta":{"role":"assistant","content":"$content"},""" +
            """"finish_reason":$reason}],"model":"GigaChat:2.0.28.2","object":"chat.completion"$counts}"""
    }

    private val hello = prompt("gigachat") {
        system("Be brief.")
        user("Привет")
    }

    @Test
    fun `given a key when streaming then a token is bought with Basic, RqUID and the scope, and used as Bearer`() =
        runBlocking {
            server.enqueue(token())
            server.enqueue(sse(chunk("При"), chunk("вет", finish = "stop", usage = true), "[DONE]"))

            val frames = client().executeStreaming(hello, GigaChatModels.chat("GigaChat-2")).toList()

            val oauth = server.takeRequest()
            assertEquals("/api/v2/oauth", oauth.target)
            assertEquals("Basic $AUTH_KEY", oauth.headers["Authorization"])
            UUID.fromString(oauth.headers["RqUID"]) // a UUID, or this throws
            assertTrue(oauth.headers["Content-Type"].orEmpty().startsWith("application/x-www-form-urlencoded"))
            assertEquals("scope=GIGACHAT_API_B2B", oauth.body?.utf8())

            val chat = server.takeRequest()
            assertEquals("/v1/chat/completions", chat.target)
            assertEquals("Bearer tok-1", chat.headers["Authorization"])
            val body = Json.parseToJsonElement(chat.body?.utf8().orEmpty()).jsonObject
            assertEquals("GigaChat-2", body.getValue("model").jsonPrimitive.content)
            assertEquals("true", body.getValue("stream").jsonPrimitive.content)
            val messages = body.getValue("messages").jsonArray
            assertEquals("system", messages[0].jsonObject.getValue("role").jsonPrimitive.content)

            assertEquals("Привет", frames.filterIsInstance<StreamFrame.TextDelta>().joinToString("") { it.text })
            val end = frames.last() as StreamFrame.End
            assertEquals("stop", end.finishReason)
            assertEquals(10, end.metaInfo.totalTokensCount)
            assertEquals(3, end.metaInfo.outputTokensCount)
        }

    @Test
    fun `given a token bought by one client when another client calls then the token is reused`() = runBlocking {
        server.enqueue(token())
        server.enqueue(sse(chunk("a", finish = "stop"), "[DONE]"))
        server.enqueue(sse(chunk("b", finish = "stop"), "[DONE]"))

        client().executeStreaming(hello, GigaChatModels.chat("GigaChat-2")).toList()
        client().executeStreaming(hello, GigaChatModels.chat("GigaChat-2")).toList()

        assertEquals("/api/v2/oauth", server.takeRequest().target)
        assertEquals("/v1/chat/completions", server.takeRequest().target)
        val second = server.takeRequest()
        assertEquals("the second client asks no new token", "/v1/chat/completions", second.target)
        assertEquals("Bearer tok-1", second.headers["Authorization"])
    }

    @Test
    fun `given a token the API refuses with 401 when streaming then a new token is bought and the call repeated`() =
        runBlocking {
            server.enqueue(token("old"))
            server.enqueue(json("""{"status":401,"message":"Token has expired"}""", code = 401))
            server.enqueue(token("new"))
            server.enqueue(sse(chunk("ok", finish = "stop"), "[DONE]"))

            val text = client().executeStreaming(hello, GigaChatModels.chat("GigaChat-2")).toList()
                .filterIsInstance<StreamFrame.TextDelta>().joinToString("") { it.text }

            assertEquals("ok", text)
            assertEquals("/api/v2/oauth", server.takeRequest().target)
            assertEquals("Bearer old", server.takeRequest().headers["Authorization"])
            assertEquals("/api/v2/oauth", server.takeRequest().target)
            assertEquals("Bearer new", server.takeRequest().headers["Authorization"])
        }

    @Test
    fun `given a second 401 when calling then the 401 is the answer and nothing more is sent`() = runBlocking {
        server.enqueue(token("a"))
        server.enqueue(json("""{"status":401,"message":"Unauthorized"}""", code = 401))
        server.enqueue(token("b"))
        server.enqueue(json("""{"status":401,"message":"Unauthorized"}""", code = 401))

        try {
            client().models()
            fail("a second 401 must fail the call")
        } catch (e: Exception) {
            assertTrue(e.causes().any { (it as? KoogHttpClientException)?.statusCode == 401 })
        }
        assertEquals(4, server.requestCount)
    }

    @Test
    fun `given a key the OAuth endpoint refuses when calling then the call fails and the API is never asked`() =
        runBlocking {
            server.enqueue(json("""{"code":6,"message":"credentials doesn't match db data"}""", code = 401))

            try {
                client().executeStreaming(hello, GigaChatModels.chat("GigaChat-2")).toList()
                fail("a refused key must fail the call")
            } catch (e: LLMClientException) {
                assertTrue(e.message.orEmpty().contains("GIGACHAT_API_B2B"))
                assertTrue(e.causes().any { (it as? KoogHttpClientException)?.statusCode == 401 })
                assertFalse("the key never appears in the error", e.causes().any { AUTH_KEY in it.message.orEmpty() })
            }
            assertEquals(1, server.requestCount)
        }

    @Test
    fun `given a stream cut before any finish reason when it ends then End has none and the answer counts as cut`() =
        runBlocking {
            server.enqueue(token())
            server.enqueue(sse(chunk("half an ans")))

            val model = GigaChatModels.chat("GigaChat-2")
            val end = client().executeStreaming(hello, model).toList().last() as StreamFrame.End

            assertNull(end.finishReason)
            assertTrue(CloudStreamEnd.cutOff(CloudProvider.GIGACHAT, model, end.finishReason))
        }

    @Test
    fun `given a stream closed by DONE without a finish reason when it ends then it is whole`() = runBlocking {
        server.enqueue(token())
        server.enqueue(sse(chunk("whole"), "[DONE]"))

        val frames = client().executeStreaming(hello, GigaChatModels.chat("GigaChat-2")).toList()

        assertEquals("stop", (frames.last() as StreamFrame.End).finishReason)
    }

    @Test
    fun `given tools when executing then they go as functions, and the call comes back with its state id`() =
        runBlocking {
            server.enqueue(token())
            server.enqueue(
                json(
                    """{"choices":[{"index":0,"finish_reason":"function_call","message":{"role":"assistant",""" +
                        """"content":"","function_call":{"name":"weather","arguments":{"city":"Манжерок"}},""" +
                        """"functions_state_id":"9b26f2cd-5efc-4005-a156-6914bdb89ad6"}}],""" +
                        """"model":"GigaChat-2-Max",""" +
                        """"usage":{"prompt_tokens":1,"completion_tokens":2,"total_tokens":3}}""",
                ),
            )
            val weather = ToolDescriptor(
                name = "weather",
                description = "Forecast",
                requiredParameters = listOf(ToolParameterDescriptor("city", "City", ToolParameterType.String)),
            )

            val answer = client().execute(hello, GigaChatModels.chat("GigaChat-2-Max"), listOf(weather))

            server.takeRequest()
            val body = Json.parseToJsonElement(server.takeRequest().body?.utf8().orEmpty()).jsonObject
            assertEquals("auto", body.getValue("function_call").jsonPrimitive.content)
            val function = body.getValue("functions").jsonArray.single().jsonObject
            assertEquals("weather", function.getValue("name").jsonPrimitive.content)
            assertTrue(function.getValue("parameters") is JsonObject)
            assertFalse("GigaChat takes functions, not tools", body.containsKey("tools"))

            val call = answer.parts.filterIsInstance<MessagePart.Tool.Call>().single()
            assertEquals("9b26f2cd-5efc-4005-a156-6914bdb89ad6", call.id)
            assertEquals("weather", call.tool)
            assertEquals("Манжерок", call.argsJson.getValue("city").jsonPrimitive.content)
            assertEquals("function_call", answer.finishReason)
        }

    @Test
    fun `given the model list when listed then only chat models are offered`() = runBlocking {
        server.enqueue(token())
        server.enqueue(
            json(
                """{"object":"list","data":[{"id":"GigaChat-2","object":"model","owned_by":"salutedevices",""" +
                    """"type":"chat"},{"id":"GigaChat-2-Max","object":"model","owned_by":"salutedevices",""" +
                    """"type":"chat"},{"id":"Embeddings","object":"model","owned_by":"salutedevices",""" +
                    """"type":"embedder"}]}""",
            ),
        )

        val ids = client().models().map { it.id }

        assertEquals(listOf("GigaChat-2", "GigaChat-2-Max"), ids)
        server.takeRequest()
        val list = server.takeRequest()
        assertEquals("/v1/models", list.target)
        assertEquals("Bearer tok-1", list.headers["Authorization"])
    }

    @Test
    fun `given texts when embedded then the vectors come back in input order`() = runBlocking {
        server.enqueue(token())
        server.enqueue(
            json(
                """{"object":"list","model":"Embeddings","data":[""" +
                    """{"object":"embedding","embedding":[0.2],"index":1},""" +
                    """{"object":"embedding","embedding":[0.1],"index":0}]}""",
            ),
        )

        val vectors = client().embed(listOf("a", "b"), GigaChatModels.embedding())

        assertEquals(listOf(listOf(0.1), listOf(0.2)), vectors)
    }

    private fun Throwable.causes(): Sequence<Throwable> = generateSequence(this) { it.cause }

    private companion object {
        /** Base64 of `client-id:client-secret` — a shape, not a key. */
        const val AUTH_KEY = "Y2xpZW50LWlkOmNsaWVudC1zZWNyZXQ="
        const val THIRTY_MINUTES_MS = 30 * 60 * 1000L
    }
}
