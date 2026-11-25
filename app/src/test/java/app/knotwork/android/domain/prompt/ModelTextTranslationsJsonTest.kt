package app.knotwork.android.domain.prompt

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [ModelTextTranslationsJson]: what a model-text translation file must hold, and that
 * one English text cannot carry two translations.
 */
class ModelTextTranslationsJsonTest {

    @Test
    fun `given a valid file then every entry is read in order`() {
        val entries = ModelTextTranslationsJson.parseEntries(VALID, "ru")

        assertEquals(2, entries.size)
        assertEquals(listOf("code:A", "code:B"), entries[0].sources)
        assertEquals("Hello", entries[0].source)
        assertEquals("Привет", entries[0].text)
        assertEquals(mapOf("Hello" to "Привет", "Bye" to "Пока"), ModelTextTranslationsJson.parse(VALID, "ru"))
    }

    @Test
    fun `given the same English twice with the same translation then it is read`() {
        val json = """
            {"formatVersion": 1, "language": "ru", "texts": [
              {"sources": ["a"], "en": "Hello", "ru": "Привет"},
              {"sources": ["b"], "en": "Hello", "ru": "Привет"}
            ]}
        """.trimIndent()

        assertEquals(mapOf("Hello" to "Привет"), ModelTextTranslationsJson.parse(json, "ru"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `given one English with two translations then the file is refused`() {
        val json = """
            {"formatVersion": 1, "language": "ru", "texts": [
              {"sources": ["a"], "en": "Hello", "ru": "Привет"},
              {"sources": ["b"], "en": "Hello", "ru": "Здравствуйте"}
            ]}
        """.trimIndent()

        ModelTextTranslationsJson.parse(json, "ru")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `given a file for another language then it is refused`() {
        ModelTextTranslationsJson.parse(VALID, "es")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `given an unknown format version then it is refused`() {
        ModelTextTranslationsJson.parse(VALID.replace("\"formatVersion\": 1", "\"formatVersion\": 2"), "ru")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `given an entry without a translation then the file is refused`() {
        val json = """{"formatVersion": 1, "language": "ru", "texts": [{"sources": ["a"], "en": "Hello"}]}"""

        ModelTextTranslationsJson.parse(json, "ru")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `given text that is not JSON then it is refused`() {
        ModelTextTranslationsJson.parse("not json", "ru")
    }

    private companion object {
        val VALID = """
            {
              "formatVersion": 1,
              "language": "ru",
              "texts": [
                {"sources": ["code:A", "code:B"], "en": "Hello", "ru": "Привет"},
                {"sources": ["code:C"], "en": "Bye", "ru": "Пока"}
              ]
            }
        """.trimIndent()
    }
}
