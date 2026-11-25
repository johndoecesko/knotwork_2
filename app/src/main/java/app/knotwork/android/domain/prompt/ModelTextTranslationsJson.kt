package app.knotwork.android.domain.prompt

import org.json.JSONException
import org.json.JSONObject

/**
 * Reads a translation file of the app's model-facing texts, `assets/model_text/<language>.json`.
 *
 * The file holds, for one language, a list of entries. Each names where its English is
 * written (`sources`, for the coverage test and for a person reading the file), the
 * English itself (`en`) and its translation (the member named by the file's `language`):
 *
 * ```json
 * {
 *   "formatVersion": 1,
 *   "language": "ru",
 *   "texts": [
 *     {
 *       "sources": ["code:DefaultPrompts.LiteRt.SYSTEM_FALLBACK"],
 *       "en": "You are a helpful AI assistant.",
 *       "ru": "…"
 *     }
 *   ]
 * }
 * ```
 *
 * The same English may be listed by more than one source; it must then carry the same
 * translation every time, or the file is refused — a text cannot mean two things.
 */
object ModelTextTranslationsJson {

    /** The only `formatVersion` this reader understands. */
    const val FORMAT_VERSION: Int = 1

    /**
     * One entry of a translation file.
     *
     * @property sources Where the English is written, e.g. `code:DefaultPrompts.EVALUATION_PROMPT`
     *   or `pipeline:tool_using_react/needs_tool/conditionPrompt`.
     * @property source The English text, exactly as its sources carry it.
     * @property text The translation.
     */
    data class Entry(val sources: List<String>, val source: String, val text: String)

    /**
     * Parses a translation file.
     *
     * @param json The file's text.
     * @param language The language the file is expected to translate into.
     * @return Every entry, in file order.
     * @throws IllegalArgumentException when the file is not a translation into [language]
     *   in a format this reader understands, an entry lacks its English or its translation,
     *   or one English carries two different translations.
     */
    fun parseEntries(json: String, language: String): List<Entry> {
        val root = try {
            JSONObject(json)
        } catch (e: JSONException) {
            throw IllegalArgumentException("not a JSON object", e)
        }
        require(root.optInt("formatVersion") == FORMAT_VERSION) { "unknown formatVersion" }
        require(root.optString("language") == language) { "file translates into ${root.optString("language")}" }
        val texts = root.optJSONArray("texts") ?: throw IllegalArgumentException("no texts")
        val seen = HashMap<String, String>()
        return List(texts.length()) { index ->
            val item = texts.optJSONObject(index) ?: throw IllegalArgumentException("texts[$index] is not an object")
            val source = item.optString("en")
            val text = item.optString(language)
            require(source.isNotEmpty()) { "texts[$index] has no en" }
            require(text.isNotEmpty()) { "texts[$index] has no $language" }
            val previous = seen.put(source, text)
            require(previous == null || previous == text) { "texts[$index]: one English, two translations" }
            val sourcesArray = item.optJSONArray("sources")
            val sources = if (sourcesArray == null) {
                emptyList()
            } else {
                List(sourcesArray.length()) { sourcesArray.optString(it) }
            }
            Entry(sources = sources, source = source, text = text)
        }
    }

    /**
     * Parses a translation file into the lookup [ModelTextLocalizer] needs.
     *
     * @param json The file's text.
     * @param language The language the file is expected to translate into.
     * @return Translation by the exact English it translates.
     * @throws IllegalArgumentException as [parseEntries] does.
     */
    fun parse(json: String, language: String): Map<String, String> =
        parseEntries(json, language).associate { it.source to it.text }
}
