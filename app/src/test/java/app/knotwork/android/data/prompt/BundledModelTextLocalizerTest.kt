package app.knotwork.android.data.prompt

import android.content.Context
import android.content.res.AssetManager
import app.knotwork.android.architecture.ProductionSources
import app.knotwork.android.domain.constants.DefaultPrompts
import app.knotwork.android.presentation.ui.settings.FakeAppLanguageRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileNotFoundException

/**
 * [BundledModelTextLocalizer]: the model is addressed in the interface language, a
 * text is swapped only when it is exactly the English a translation was written for,
 * the file is read once per language (ahead of the first text, when the language
 * changes), and a missing or broken file leaves the model addressed in English.
 *
 * The read-ahead runs on [Dispatchers.Unconfined], so it has happened by the time
 * the language change that started it returns.
 */
class BundledModelTextLocalizerTest {

    private val assets = mockk<AssetManager>()
    private val context = mockk<Context> { every { assets } returns this@BundledModelTextLocalizerTest.assets }
    private val language = FakeAppLanguageRepository()
    private val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
    private val localizer = BundledModelTextLocalizer(context, language, scope, Dispatchers.Unconfined)

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `given the default language then every text is sent as written and nothing is read`() {
        language.showIn("en")

        assertEquals(DefaultPrompts.EVALUATION_PROMPT, localizer.localize(DefaultPrompts.EVALUATION_PROMPT))
        verify(exactly = 0) { assets.open(any()) }
    }

    @Test
    fun `given Russian then the app's own prompt is swapped and the file is read once`() {
        every { assets.open("model_text/ru.json") } answers { shippedRussian().byteInputStream() }
        language.showIn("ru")

        val first = localizer.localize(DefaultPrompts.LiteRt.SYSTEM_FALLBACK)
        val second = localizer.localize(DefaultPrompts.SYSTEM_PROMPT_PREFIX)

        assertEquals("Ты — полезный ИИ-ассистент.", first)
        assertNotEquals(DefaultPrompts.SYSTEM_PROMPT_PREFIX, second)
        verify(exactly = 1) { assets.open("model_text/ru.json") }
    }

    @Test
    fun `given Russian then a prompt the user edited is sent as written`() {
        every { assets.open("model_text/ru.json") } answers { shippedRussian().byteInputStream() }
        language.showIn("ru")

        val edited = DefaultPrompts.LiteRt.SYSTEM_FALLBACK + " Be brief."

        assertEquals(edited, localizer.localize(edited))
    }

    @Test
    fun `given a region tag then the language's file is used`() {
        every { assets.open("model_text/ru.json") } answers { shippedRussian().byteInputStream() }
        language.showIn("ru-RU")

        assertEquals("Ты — ИИ-ассистент.", localizer.localize(DefaultPrompts.System.SYSTEM_FALLBACK))
    }

    @Test
    fun `given a language with no file then the model is addressed in English`() {
        every { assets.open("model_text/es.json") } throws FileNotFoundException("model_text/es.json")
        language.showIn("es")

        assertEquals(DefaultPrompts.EVALUATION_PROMPT, localizer.localize(DefaultPrompts.EVALUATION_PROMPT))
    }

    @Test
    fun `given a malformed file then the model is addressed in English`() {
        every { assets.open("model_text/ru.json") } answers {
            """{"formatVersion": 1, "language": "es"}""".byteInputStream()
        }
        language.showIn("ru")

        assertEquals(DefaultPrompts.EVALUATION_PROMPT, localizer.localize(DefaultPrompts.EVALUATION_PROMPT))
    }

    @Test
    fun `given the language changes then the next text follows it`() {
        every { assets.open("model_text/ru.json") } answers { shippedRussian().byteInputStream() }
        language.showIn("ru")
        assertNotEquals(DefaultPrompts.EVALUATION_PROMPT, localizer.localize(DefaultPrompts.EVALUATION_PROMPT))

        language.showIn("en")

        assertEquals(DefaultPrompts.EVALUATION_PROMPT, localizer.localize(DefaultPrompts.EVALUATION_PROMPT))
    }

    @Test
    fun `given Russian then a context header reaches the model in Russian and keeps its shape`() {
        every { assets.open("model_text/ru.json") } answers { shippedRussian().byteInputStream() }
        language.showIn("ru")

        val header = localizer.localize(DefaultPrompts.ContextBlocks.HEADER_PREVIOUS_NODE_OUTPUT)

        assertTrue(header.startsWith("--- ") && header.endsWith(" ---"))
        assertNotEquals(DefaultPrompts.ContextBlocks.HEADER_PREVIOUS_NODE_OUTPUT, header)
    }

    private fun shippedRussian(): String =
        File(ProductionSources.moduleDirectory(), "src/main/assets/model_text/ru.json").readText()
}
