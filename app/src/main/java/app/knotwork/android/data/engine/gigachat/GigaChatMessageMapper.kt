package app.knotwork.android.data.engine.gigachat

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.clients.openai.base.OpenAICompatibleToolDescriptorSchemaGenerator
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.params.LLMParams
import ai.koog.utils.time.KoogClock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * Translates between Koog's prompt model and GigaChat's chat format — pure functions, no I/O.
 *
 * What differs from OpenAI, and how each difference is bridged:
 * - **One system message, first.** GigaChat accepts a single `system` message at the head of the
 *   list; Koog may carry several. They are joined, in order, into one.
 * - **Tools are `functions`.** A [ToolDescriptor] becomes a [GigaChatFunction] whose parameters are
 *   the JSON Schema Koog's OpenAI client would send ([OpenAICompatibleToolDescriptorSchemaGenerator]).
 *   `ToolChoice.Required` has no GigaChat counterpart and is sent as `auto`.
 * - **One call per answer.** The model's call is `function_call` with `functions_state_id`; it
 *   becomes a [MessagePart.Tool.Call] whose id *is* that state id, so a later request can send it
 *   back. An assistant message holding several calls is sent as one message per call.
 * - **Results are `function` messages.** A [MessagePart.Tool.Result] becomes a message of role
 *   `function` naming the function; its content must be JSON, so a result that is not a JSON object
 *   is wrapped as `{"result": "…"}`.
 * - **No attachments.** The app never sends one to a cloud provider (`CloudLlmNodeExecutor`), and
 *   GigaChat would need them uploaded first; one arriving here is refused rather than dropped.
 */
internal object GigaChatMessageMapper {

    private const val ROLE_SYSTEM = "system"
    private const val ROLE_USER = "user"
    private const val ROLE_ASSISTANT = "assistant"
    private const val ROLE_FUNCTION = "function"
    private const val CALL_AUTO = "auto"
    private const val CALL_NONE = "none"
    private const val RESULT_KEY = "result"
    private const val NAME_KEY = "name"
    private const val EMPTY_ARGUMENTS = "{}"

    private val schemaGenerator = OpenAICompatibleToolDescriptorSchemaGenerator()

    /**
     * The request for [prompt] on [model].
     *
     * @param prompt The conversation and its parameters.
     * @param model The model to ask.
     * @param tools Functions the model may call.
     * @param stream Whether the answer is to be streamed.
     * @return The request body.
     * @throws IllegalArgumentException when the prompt carries an attachment.
     */
    fun request(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>, stream: Boolean): GigaChatChatRequest {
        val functions = tools.map(::function).takeIf { it.isNotEmpty() }
        return GigaChatChatRequest(
            model = model.id,
            messages = messages(prompt),
            // The API refuses a temperature of zero or below; such a request is sent without one.
            temperature = prompt.params.temperature?.takeIf { it > 0.0 },
            maxTokens = prompt.params.maxTokens,
            stream = stream,
            functions = functions,
            functionCall = functions?.let { functionCall(prompt.params.toolChoice) },
        )
    }

    /** The messages of [prompt] in GigaChat's order: the joined system text first, then the rest. */
    fun messages(prompt: Prompt): List<GigaChatMessage> {
        val system = prompt.messages.filterIsInstance<Message.System>()
            .joinToString("\n\n") { it.textContent() }
            .takeIf { it.isNotBlank() }
        val rest = prompt.messages.flatMap { message ->
            when (message) {
                is Message.System -> emptyList()
                is Message.User -> userMessages(message)
                is Message.Assistant -> assistantMessages(message)
            }
        }
        return listOfNotNull(system?.let { GigaChatMessage(role = ROLE_SYSTEM, content = it) }) + rest
    }

    private fun userMessages(message: Message.User): List<GigaChatMessage> {
        require(message.parts.none { it is MessagePart.Attachment }) {
            "GigaChat client sends no attachments; the app never hands one to a cloud provider"
        }
        val results = message.parts.filterIsInstance<MessagePart.Tool.Result>().map { result ->
            GigaChatMessage(role = ROLE_FUNCTION, name = result.tool, content = functionResultContent(result.output))
        }
        val text = message.parts.filterIsInstance<MessagePart.Text>().joinToString("\n") { it.text }
        return results + listOfNotNull(text.takeIf { it.isNotEmpty() }?.let { GigaChatMessage(ROLE_USER, it) })
    }

    private fun assistantMessages(message: Message.Assistant): List<GigaChatMessage> {
        val text = message.parts.filterIsInstance<MessagePart.Text>().joinToString("") { it.text }
        val calls = message.parts.filterIsInstance<MessagePart.Tool.Call>()
        if (calls.isEmpty()) return listOf(GigaChatMessage(role = ROLE_ASSISTANT, content = text))
        return calls.mapIndexed { index, call ->
            GigaChatMessage(
                role = ROLE_ASSISTANT,
                // The text the model wrote before its call belongs to the first message only.
                content = text.takeIf { index == 0 }.orEmpty(),
                functionCall = GigaChatFunctionCall(name = call.tool, arguments = argumentsObject(call.args)),
                functionsStateId = call.id?.takeIf(::isStateId),
            )
        }
    }

    /** [output] when it is a JSON object, otherwise `{"result": output}` — a `function` message holds JSON. */
    fun functionResultContent(output: String): String =
        if (parseOrNull(output) is JsonObject) output else buildJsonObject { put(RESULT_KEY, output) }.toString()

    private fun function(tool: ToolDescriptor): GigaChatFunction = GigaChatFunction(
        name = tool.name,
        description = tool.description.takeIf { it.isNotBlank() },
        parameters = schemaGenerator.generate(tool),
    )

    private fun functionCall(choice: LLMParams.ToolChoice?): JsonElement = when (choice) {
        null, LLMParams.ToolChoice.Auto, LLMParams.ToolChoice.Required -> JsonPrimitive(CALL_AUTO)
        LLMParams.ToolChoice.None -> JsonPrimitive(CALL_NONE)
        is LLMParams.ToolChoice.Named -> buildJsonObject { put(NAME_KEY, choice.name) }
    }

    /**
     * The Koog answer of a whole (not streamed) completion.
     *
     * @param completion The decoded answer.
     * @param clock Stamps the answer's metadata.
     * @return The assistant message: its text, and the function call when the model made one.
     */
    fun assistant(completion: GigaChatCompletion, clock: KoogClock): Message.Assistant {
        val choice = completion.choices.firstOrNull()
        val message = choice?.message
        val parts = buildList<MessagePart.ResponsePart> {
            message?.content?.takeIf { it.isNotEmpty() }?.let { add(MessagePart.Text(it)) }
            message?.functionCall?.let { add(toolCall(it, message.functionsStateId)) }
        }
        return Message.Assistant(
            parts = parts.ifEmpty { listOf(MessagePart.Text("")) },
            metaInfo = metaInfo(completion, clock),
            finishReason = choice?.finishReason,
        )
    }

    /** The Koog tool call for the model's [call], identified by its [stateId] (a fresh id without one). */
    fun toolCall(call: GigaChatFunctionCall, stateId: String?): MessagePart.Tool.Call =
        MessagePart.Tool.Call(
            id = stateId ?: UUID.randomUUID().toString(),
            tool = call.name,
            args = argumentsText(call),
        )

    /** The arguments of [call] as JSON text: an object as it is, a string holding JSON unwrapped. */
    fun argumentsText(call: GigaChatFunctionCall): String = when (val arguments = call.arguments) {
        null, JsonNull -> EMPTY_ARGUMENTS
        is JsonPrimitive -> if (arguments.isString) arguments.content.ifBlank { EMPTY_ARGUMENTS } else EMPTY_ARGUMENTS
        else -> arguments.toString()
    }

    /** Metadata of [completion]: the token counts the API reported, and the model that answered. */
    fun metaInfo(completion: GigaChatCompletion, clock: KoogClock): ResponseMetaInfo = ResponseMetaInfo.create(
        clock = clock,
        totalTokensCount = completion.usage?.totalTokens,
        inputTokensCount = completion.usage?.promptTokens,
        outputTokensCount = completion.usage?.completionTokens,
        modelId = completion.model,
    )

    private fun argumentsObject(args: String): JsonElement = parseOrNull(args) as? JsonObject ?: JsonObject(emptyMap())

    private fun parseOrNull(text: String): JsonElement? = try {
        GigaChatJson.parseToJsonElement(text)
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    /** Whether [id] can be a `functions_state_id` (a UUID) rather than an id another provider made. */
    private fun isStateId(id: String): Boolean = runCatching { UUID.fromString(id) }.isSuccess
}
