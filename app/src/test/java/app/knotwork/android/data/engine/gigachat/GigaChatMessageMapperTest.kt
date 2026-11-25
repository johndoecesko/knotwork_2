package app.knotwork.android.data.engine.gigachat

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.AttachmentSource
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.params.LLMParams
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [GigaChatMessageMapper]: Koog's prompt in GigaChat's chat format — one system message first,
 * tools as functions, a call and its result as GigaChat expects them back.
 */
class GigaChatMessageMapperTest {

    private val model = GigaChatModels.chat("GigaChat-2")

    @Test
    fun `given several system messages when mapped then one joined system message comes first`() {
        val conversation = prompt("p") {
            user("Hi")
            system("A")
            system("B")
        }

        val messages = GigaChatMessageMapper.messages(conversation)

        assertEquals(listOf("system", "user"), messages.map { it.role })
        assertEquals("A\n\nB", messages.first().content)
    }

    @Test
    fun `given a tool call and its result in history when mapped then they go back as function_call and function`() {
        val stateId = "9b26f2cd-5efc-4005-a156-6914bdb89ad6"
        val conversation = Prompt(
            messages = listOf(
                Message.User(listOf(MessagePart.Text("Weather?")), RequestMetaInfo.Empty),
                Message.Assistant(
                    MessagePart.Tool.Call(id = stateId, tool = "weather", args = """{"city":"Москва"}"""),
                    ResponseMetaInfo.Empty,
                ),
                Message.User(
                    listOf(MessagePart.Tool.Result(id = stateId, tool = "weather", output = "sunny")),
                    RequestMetaInfo.Empty,
                ),
            ),
            id = "p",
        )

        val (_, call, result) = GigaChatMessageMapper.messages(conversation)

        assertEquals("assistant", call.role)
        assertEquals("weather", call.functionCall?.name)
        assertEquals("Москва", call.functionCall?.arguments?.jsonObject?.getValue("city")?.jsonPrimitive?.content)
        assertEquals(stateId, call.functionsStateId)
        assertEquals("function", result.role)
        assertEquals("weather", result.name)
        assertEquals("""{"result":"sunny"}""", result.content)
    }

    @Test
    fun `given a call id another provider made when mapped then no state id is invented from it`() {
        val conversation = Prompt(
            messages = listOf(
                Message.Assistant(
                    MessagePart.Tool.Call(id = "call_abc", tool = "t", args = "{}"),
                    ResponseMetaInfo.Empty,
                ),
            ),
            id = "p",
        )

        assertNull(GigaChatMessageMapper.messages(conversation).single().functionsStateId)
    }

    @Test
    fun `given a JSON object result when mapped then it is sent as it is`() {
        assertEquals("""{"t":21}""", GigaChatMessageMapper.functionResultContent("""{"t":21}"""))
        assertEquals("""{"result":"[1,2]"}""", GigaChatMessageMapper.functionResultContent("[1,2]"))
    }

    @Test
    fun `given tool choices when mapped then GigaChat's function_call values are sent`() {
        val tool = ToolDescriptor(name = "t", description = "d")
        fun callFor(choice: LLMParams.ToolChoice?) = GigaChatMessageMapper.request(
            prompt("p", params = LLMParams(toolChoice = choice)) { user("x") },
            model,
            listOf(tool),
            stream = false,
        ).functionCall

        assertEquals(JsonPrimitive("auto"), callFor(null))
        assertEquals(JsonPrimitive("auto"), callFor(LLMParams.ToolChoice.Required))
        assertEquals(JsonPrimitive("none"), callFor(LLMParams.ToolChoice.None))
        val named = callFor(LLMParams.ToolChoice.Named("t")) as JsonObject
        assertEquals("t", named.getValue("name").jsonPrimitive.content)
    }

    @Test
    fun `given no tools when mapped then neither functions nor function_call is sent`() {
        val request = GigaChatMessageMapper.request(prompt("p") { user("x") }, model, emptyList(), stream = true)

        assertNull(request.functions)
        assertNull(request.functionCall)
        assertTrue(request.stream)
    }

    @Test
    fun `given a temperature of zero when mapped then none is sent, since the API refuses it`() {
        val zero = GigaChatMessageMapper.request(
            prompt("p", params = LLMParams(temperature = 0.0)) { user("x") },
            model,
            emptyList(),
            stream = false,
        )
        val warm = GigaChatMessageMapper.request(
            prompt("p", params = LLMParams(temperature = 0.7)) { user("x") },
            model,
            emptyList(),
            stream = false,
        )

        assertNull(zero.temperature)
        assertEquals(0.7, warm.temperature!!, 0.0)
    }

    @Test
    fun `given arguments as an object or as a string when read then both give the same JSON text`() {
        val asObject = GigaChatFunctionCall("f", JsonObject(mapOf("a" to JsonPrimitive(1))))
        val asString = GigaChatFunctionCall("f", JsonPrimitive("""{"a":1}"""))

        assertEquals("""{"a":1}""", GigaChatMessageMapper.argumentsText(asObject))
        assertEquals("""{"a":1}""", GigaChatMessageMapper.argumentsText(asString))
        assertEquals("{}", GigaChatMessageMapper.argumentsText(GigaChatFunctionCall("f", null)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `given an attachment when mapped then it is refused, not dropped`() {
        val attachment = MessagePart.Attachment(
            AttachmentSource.Image(
                content = AttachmentContent.URL("https://example.com/a.png"),
                format = "png",
            ),
        )
        GigaChatMessageMapper.messages(
            Prompt(listOf(Message.User(listOf(attachment), RequestMetaInfo.Empty)), id = "p"),
        )
    }
}
