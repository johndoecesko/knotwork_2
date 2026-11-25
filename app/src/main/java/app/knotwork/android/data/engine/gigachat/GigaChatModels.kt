package app.knotwork.android.data.engine.gigachat

import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel

/**
 * The Koog provider identity of every GigaChat model the app builds. Koog lists no GigaChat
 * provider, so the app names its own; [LLMProvider] is open for exactly this.
 */
object GigaChatLLMProvider : LLMProvider("gigachat", "GigaChat")

/**
 * The GigaChat model descriptors the app sends, built by hand because Koog has no constants for
 * them — and therefore, unlike [app.knotwork.android.data.engine.KoogModelMapper], with no
 * capability Koog's own clients route by. Only [GigaChatLLMClient] reads these.
 *
 * The model is typed (or chosen from the server's list after *Test connection*), as for Ollama:
 * the GigaChat line moves (GigaChat 2 Lite/Pro/Max, GigaChat 3 Ultra in 2026) and which models an
 * account may call depends on its tariff, so a built-in list would go stale or offer models that
 * answer 402/404. [DEFAULT_MODEL_ID] is the one every tariff includes.
 */
object GigaChatModels {

    /**
     * The model asked for when none is chosen: GigaChat 2 Lite, which the freemium and every paid
     * tariff include (tariff pages, 2026: model id `GigaChat` or `GigaChat-2`).
     */
    const val DEFAULT_MODEL_ID: String = "GigaChat-2"

    /** The embedding model asked for when none is named — the API's base embedder. */
    const val DEFAULT_EMBEDDING_MODEL_ID: String = "Embeddings"

    /** Context window of the GigaChat 2 line ("128 тыс." tokens on the model pages). */
    const val CONTEXT_LENGTH: Long = 128_000

    /** `type` of a generation model in the `/models` answer; `embedder` and `aicheck` are the others. */
    const val CHAT_MODEL_TYPE: String = "chat"

    /**
     * The chat model [id] with what the app's client implements for it: completion, temperature and
     * custom functions (sent as GigaChat `functions`). No JSON-schema capability is claimed: the
     * `/v1` endpoint the client uses does not take one, and claiming it would switch off the app's own
     * repair of structured output.
     *
     * @param id The model id, sent to the API as it is.
     * @return The descriptor.
     */
    fun chat(id: String): LLModel = LLModel(
        provider = GigaChatLLMProvider,
        id = id,
        capabilities = listOf(
            LLMCapability.Completion,
            LLMCapability.Temperature,
            LLMCapability.Tools,
            LLMCapability.ToolChoice,
        ),
        contextLength = CONTEXT_LENGTH,
    )

    /**
     * The embedding model [id].
     *
     * @param id `Embeddings`, `Embeddings-2`, `EmbeddingsGigaR`, …
     * @return The descriptor.
     */
    fun embedding(id: String = DEFAULT_EMBEDDING_MODEL_ID): LLModel = LLModel(
        provider = GigaChatLLMProvider,
        id = id,
        capabilities = listOf(LLMCapability.Embed),
    )
}
