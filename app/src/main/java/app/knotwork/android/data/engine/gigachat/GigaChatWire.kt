package app.knotwork.android.data.engine.gigachat

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/*
 * The JSON shapes of the GigaChat REST API (`/v1`), as the OpenAPI description published on
 * developers.sber.ru gives them (api.yml, checked November 2026). Only the fields the client
 * reads or sends are declared; everything else is ignored on decode ([GigaChatJson]).
 *
 * GigaChat's chat format is close to OpenAI's but not the same, which is why the app does not
 * reach it through Koog's OpenAI client: custom functions are `functions` / `function_call`
 * (one call per answer, `arguments` an object), a function result is a message of role
 * `function`, and a model's call carries `functions_state_id`, which the next request must send
 * back with that call.
 */

/** The JSON configuration of every GigaChat request and answer. */
internal val GigaChatJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = false
    isLenient = true
}

/**
 * Answer of `POST /api/v2/oauth`.
 *
 * @property accessToken The Bearer token for the API.
 * @property expiresAt When it stops working — Unix time in **milliseconds** per the schema
 *   ("unix timestamp в миллисекундах"); [GigaChatTokenCache] also accepts seconds.
 */
@Serializable
internal data class GigaChatTokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("expires_at") val expiresAt: Long,
)

/**
 * Body of `POST /chat/completions`.
 *
 * @property model The model id.
 * @property messages The conversation; a `system` message, if any, comes first and once.
 * @property temperature Sampling temperature; the API takes only values above zero.
 * @property maxTokens Upper bound of the answer.
 * @property stream Whether the answer is streamed as server-sent events.
 * @property functions Custom functions the model may call.
 * @property functionCall `"auto"`, `"none"` or `{"name": …}`.
 */
@Serializable
internal data class GigaChatChatRequest(
    val model: String,
    val messages: List<GigaChatMessage>,
    val temperature: Double? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val stream: Boolean = false,
    val functions: List<GigaChatFunction>? = null,
    @SerialName("function_call") val functionCall: JsonElement? = null,
)

/**
 * One message, sent or received (`message` of an answer, `delta` of a stream chunk).
 *
 * @property role `system`, `user`, `assistant` or `function`; absent on most stream chunks.
 * @property content The text.
 * @property name The function a `function` message answers.
 * @property functionCall The model's call of a custom function.
 * @property functionsStateId Identifier of the model's function call, sent back with it.
 */
@Serializable
internal data class GigaChatMessage(
    val role: String? = null,
    val content: String? = null,
    val name: String? = null,
    @SerialName("function_call") val functionCall: GigaChatFunctionCall? = null,
    @SerialName("functions_state_id") val functionsStateId: String? = null,
)

/**
 * A call of a custom function.
 *
 * @property name The function.
 * @property arguments Its arguments: a JSON object in every example, though the schema's text calls
 *   it "JSON wrapped in a string" — both are read ([GigaChatMessageMapper.argumentsText]).
 */
@Serializable
internal data class GigaChatFunctionCall(val name: String, val arguments: JsonElement? = null)

/**
 * A custom function the model may call.
 *
 * @property name Its name.
 * @property description What it does.
 * @property parameters Its parameters as JSON Schema (`type: object`).
 */
@Serializable
internal data class GigaChatFunction(val name: String, val description: String? = null, val parameters: JsonObject)

/**
 * An answer of `POST /chat/completions`, whole or one stream chunk.
 *
 * @property choices The choices; the client asks for one.
 * @property model The model that answered, with its version (`GigaChat:2.0.28.2`).
 * @property usage Token counts; on the last chunk of a stream.
 */
@Serializable
internal data class GigaChatCompletion(
    val choices: List<GigaChatChoice> = emptyList(),
    val model: String? = null,
    val usage: GigaChatUsage? = null,
)

/**
 * One choice.
 *
 * @property index Its position.
 * @property message The whole message, in an answer that is not streamed.
 * @property delta The part of the message in one stream chunk.
 * @property finishReason `stop`, `length`, `function_call`, `blacklist` or `error`; on the last chunk.
 */
@Serializable
internal data class GigaChatChoice(
    val index: Int = 0,
    val message: GigaChatMessage? = null,
    val delta: GigaChatMessage? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
)

/**
 * Token counts as the API reports them.
 *
 * @property promptTokens Tokens of the request.
 * @property completionTokens Tokens of the answer.
 * @property totalTokens Both.
 */
@Serializable
internal data class GigaChatUsage(
    @SerialName("prompt_tokens") val promptTokens: Int? = null,
    @SerialName("completion_tokens") val completionTokens: Int? = null,
    @SerialName("total_tokens") val totalTokens: Int? = null,
)

/**
 * Answer of `GET /models`.
 *
 * @property data The models.
 */
@Serializable
internal data class GigaChatModelList(val data: List<GigaChatModelEntry> = emptyList())

/**
 * One model of the list.
 *
 * @property id The id to send as `model`.
 * @property type `chat`, `embedder` or `aicheck`; absent in older answers.
 */
@Serializable
internal data class GigaChatModelEntry(val id: String, val type: String? = null)

/**
 * Body of `POST /embeddings`.
 *
 * @property model `Embeddings`, `Embeddings-2`, `EmbeddingsGigaR`.
 * @property input The texts.
 */
@Serializable
internal data class GigaChatEmbeddingRequest(val model: String, val input: List<String>)

/**
 * Answer of `POST /embeddings`.
 *
 * @property data One vector per input text, each with the index of its text.
 */
@Serializable
internal data class GigaChatEmbeddingResponse(val data: List<GigaChatEmbedding> = emptyList())

/**
 * One vector.
 *
 * @property embedding The vector.
 * @property index Position of its text in the request.
 */
@Serializable
internal data class GigaChatEmbedding(val embedding: List<Double>, val index: Int)
