package app.knotwork.android.architecture

import app.knotwork.android.data.repositories.ToolRepositoryImpl
import app.knotwork.android.data.tools.local.SearchTool
import app.knotwork.android.data.tools.local.executors.AppendFileExecutor
import app.knotwork.android.data.tools.local.executors.DeleteFileExecutor
import app.knotwork.android.data.tools.local.executors.EditFileExecutor
import app.knotwork.android.data.tools.local.executors.FindFilesExecutor
import app.knotwork.android.data.tools.local.executors.HttpRequestExecutor
import app.knotwork.android.data.tools.local.executors.ListFilesExecutor
import app.knotwork.android.data.tools.local.executors.ReadFileExecutor
import app.knotwork.android.data.tools.local.executors.WriteFileExecutor
import app.knotwork.android.domain.constants.DefaultPrompts
import app.knotwork.android.domain.engine.LiveNodeStep
import app.knotwork.android.domain.engine.RunWalk
import app.knotwork.android.domain.prompt.ModelTextTranslationsJson
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every text the app writes for a model has a translation in every language whose
 * model-text file exists, the translation was written for the English the source
 * carries now, and it keeps what the code compares against.
 *
 * The model meets the app's own texts — default prompts, wrap-templates, context
 * headers, built-in tool descriptions, and the prompts of the bundled pipelines,
 * templates and skills — through `ModelTextLocalizer`, which swaps an exact English
 * text for its translation from `assets/model_text/<language>.json`. A text whose
 * English changed since it was translated runs in English, silently; this test is
 * what turns that into a failing build. It holds, for every file:
 * - the language is one the app ships (`values-<language>` exists);
 * - every entry names its sources, and each source still carries exactly the entry's
 *   English;
 * - every model-facing text of every bundled pipeline, template and skill, and every
 *   code text in [CODE_TEXTS], is translated;
 * - a translation keeps every `$VARIABLE` of its English, the English language rule
 *   of a machine-facing step (decisions 0002), every routing keyword and JSON key the
 *   English names, and the edges (a leading line break, a trailing space) a template
 *   is spliced by;
 * - a translated parameter schema is valid JSON with the same properties and the
 *   same required list.
 *
 * Reads `app/src/main/assets` and `app/src/main/res`, both under `src/main`.
 */
class ModelTextTranslationCoverageTest {

    @Test
    fun `every model text file is a language the app ships, and Russian has one`() {
        val files = translationFiles()
        assertTrue("assets/model_text holds no translation", files.isNotEmpty())
        assertEquals(
            "assets/model_text holds a translation for a language the app does not ship",
            emptySet<String>(),
            files.keys - shippedLanguages(),
        )
        assertTrue("ru" in files.keys)
    }

    @Test
    fun `every entry translates the English its sources carry now`() {
        forEachLanguage { language, entries ->
            val problems = entries.flatMap { entry ->
                if (entry.sources.isEmpty()) return@flatMap listOf("an entry names no source: ${entry.source.take(60)}")
                entry.sources.mapNotNull { source ->
                    when (val current = currentEnglish(source)) {
                        null -> "$source: names nothing"
                        entry.source -> null
                        else -> "$source: the English changed since it was translated"
                    }
                }
            }
            assertTrue(problems.report(language), problems.isEmpty())
        }
    }

    @Test
    fun `every model-facing text of the app and its bundled assets is translated`() {
        val required = CODE_TEXTS.keys + bundledSources().keys
        forEachLanguage { language, entries ->
            val translated = entries.flatMap { it.sources }.toSet()
            val missing = (required - translated).sorted().map { "$it: no translation" }
            assertTrue(missing.report(language), missing.isEmpty())
        }
    }

    @Test
    fun `a translation keeps the placeholders, literals and language rule of its English`() {
        forEachLanguage { language, entries ->
            val problems = entries.flatMap { entry -> contractProblems(entry) }
            assertTrue(problems.report(language), problems.isEmpty())
        }
    }

    @Test
    fun `a translated parameter schema is the same schema`() {
        forEachLanguage { language, entries ->
            val problems = entries
                .filter { entry -> entry.sources.any { it.endsWith("PARAMETERS") || it.endsWith(".parameters") } }
                .mapNotNull { entry ->
                    val english = JSONObject(entry.source)
                    val translated = runCatching { JSONObject(entry.text) }.getOrNull()
                        ?: return@mapNotNull "${entry.sources.first()}: the translation is not JSON"
                    val sameProperties = english.getJSONObject("properties").keySet() ==
                        translated.getJSONObject("properties").keySet()
                    val sameRequired = english.optJSONArray("required")?.toString() ==
                        translated.optJSONArray("required")?.toString()
                    if (sameProperties && sameRequired) null else "${entry.sources.first()}: another schema"
                }
            assertTrue(problems.report(language), problems.isEmpty())
        }
    }

    // ─── The contract a translation keeps ────────────────────────────────────

    private fun contractProblems(entry: ModelTextTranslationsJson.Entry): List<String> {
        val where = entry.sources.first()
        val english = entry.source
        val text = entry.text
        return buildList {
            val englishVariables = VARIABLE.findAll(english).map { it.value }.toSet()
            val textVariables = VARIABLE.findAll(text).map { it.value }.toSet()
            val lost = englishVariables - textVariables
            val added = textVariables - englishVariables
            if (lost.isNotEmpty()) add("$where: lost $lost")
            if (added.isNotEmpty()) add("$where: added $added")
            if (DefaultPrompts.INTERNAL_ENGLISH_RULE in english && !text.contains(RUSSIAN_ENGLISH_RULE_MARKER)) {
                add("$where: a machine-facing step no longer asks for English output")
            }
            if (KEYWORD_PROMPT.containsMatchIn(english)) {
                KEYWORDS.filter { keyword -> keyword.containsMatchIn(english) && !keyword.containsMatchIn(text) }
                    .forEach { add("$where: lost the keyword ${it.pattern}") }
            }
            JSON_LITERALS.filter { it in english && it !in text }.forEach { add("$where: lost $it") }
            if (english.startsWith("\n") != text.startsWith("\n")) add("$where: leading line break differs")
            if (english.endsWith(" ") != text.endsWith(" ")) add("$where: trailing space differs")
            if (english.endsWith("\n") != text.endsWith("\n")) add("$where: trailing line break differs")
        }
    }

    // ─── Where each source's English is written ──────────────────────────────

    private fun currentEnglish(source: String): String? = CODE_TEXTS[source] ?: bundledSources()[source]

    /** Every model-facing text of the bundled pipelines, templates and skills, by source id. */
    private fun bundledSources(): Map<String, String> = buildMap {
        jsonFiles("presets/pipelines").forEach { file ->
            val pipeline = JSONObject(file.readText())
            val id = pipeline.getString("id")
            val nodes = pipeline.getJSONArray("nodes")
            for (index in 0 until nodes.length()) {
                val node = nodes.getJSONObject(index)
                val config = node.optJSONObject("config") ?: continue
                PIPELINE_FIELDS.forEach { field ->
                    val value = config.optString(field).takeUnless { config.isNull(field) }
                    if (!value.isNullOrEmpty()) put("pipeline:$id/${node.getString("id")}/$field", value)
                }
            }
        }
        jsonFiles("presets/prompts").forEach { file ->
            val prompt = JSONObject(file.readText())
            put("prompt:${prompt.getString("id")}/systemPrompt", prompt.getString("systemPrompt"))
        }
        jsonFiles("presets/skills").forEach { file ->
            val skill = JSONObject(file.readText())
            put("skill:${skill.getString("id")}/instruction", skill.getString("instruction"))
        }
    }

    // ─── Reading the files ───────────────────────────────────────────────────

    private fun forEachLanguage(block: (String, List<ModelTextTranslationsJson.Entry>) -> Unit) {
        val files = translationFiles()
        assertTrue("no model text translation files", files.isNotEmpty())
        files.forEach { (language, file) ->
            block(language, ModelTextTranslationsJson.parseEntries(file.readText(), language))
        }
    }

    private fun List<String>.report(language: String): String =
        "assets/model_text/$language.json:\n" + joinToString("\n")

    private fun assets(): File = File(ProductionSources.moduleDirectory(), "src/main/assets")

    private fun translationFiles(): Map<String, File> = File(assets(), "model_text").listFiles().orEmpty()
        .filter { it.isFile && it.extension == "json" }
        .associateBy { it.nameWithoutExtension }

    private fun shippedLanguages(): Set<String> = File(ProductionSources.moduleDirectory(), "src/main/res")
        .listFiles().orEmpty()
        .filter { it.isDirectory && LANGUAGE_FOLDER.matches(it.name) }
        .map { it.name.removePrefix("values-") }
        .toSet()

    private fun jsonFiles(directory: String): List<File> = File(assets(), directory).listFiles().orEmpty()
        .filter { it.isFile && it.extension == "json" }
        .sortedBy { it.name }

    private companion object {
        val LANGUAGE_FOLDER = Regex("values-[a-z]{2,3}(-r[A-Z]{2})?")
        val VARIABLE = Regex("\\$[A-Z_][A-Z0-9_]*")

        /** The model-facing fields of a pipeline node's `config`. */
        val PIPELINE_FIELDS = listOf("systemPrompt", "conditionPrompt")

        /**
         * What every Russian rendering of [DefaultPrompts.INTERNAL_ENGLISH_RULE] says.
         * A new language adds its own marker here.
         */
        const val RUSSIAN_ENGLISH_RULE_MARKER = "на английском"

        /** A prompt that asks for a keyword or a verdict, whose words the code compares against. */
        val KEYWORD_PROMPT = Regex("keyword|verdict|exactly one word", RegexOption.IGNORE_CASE)

        /**
         * Routing keywords and verdict tokens: when a keyword prompt's English names one
         * as a word of its own (not as the start of a name like "Task Evaluator"), its
         * translation must name it too, unchanged.
         */
        val KEYWORDS: List<Regex> = listOf(
            "Simple", "Data", "Complex", "Task", "Chat", "Factual", "Clarify", "Lookup", "Act", "Process",
            "PASS", "RETRY", "FAIL", "Pass", "Retry", "Fail", "SAME", "UPDATE", "DIFFERENT",
            "Affectionate", "Intimate", "Supportive", "Playful", "Casual",
        ).map { Regex("(?<![A-Za-z])" + Regex.escape(it) + "(?![A-Za-z])(?! [A-Z][a-z])") }

        /** JSON keys, tool names and paths the code parses or the workspace resolves. */
        val JSON_LITERALS: List<String> = listOf(
            "\"question\"", "\"options\"", "\"type\"", "\"text\"", "\"tool\"", "\"arguments\"",
            "\"answer\"", "\"confidence\"", "\"sources\"", "write_file", "edit_file", "inbox/captures.md",
        )

        /**
         * The English of every code text a model reads, by source id. A code text added
         * for a model is added here, and the build then asks for its translations.
         */
        val CODE_TEXTS: Map<String, String> = mapOf(
            "code:DefaultPrompts.SYSTEM_PROMPT_PREFIX" to DefaultPrompts.SYSTEM_PROMPT_PREFIX,
            "code:DefaultPrompts.AUDIO_TRANSCRIPTION_INSTRUCTION" to DefaultPrompts.AUDIO_TRANSCRIPTION_INSTRUCTION,
            "code:DefaultPrompts.INTENT_ROUTER_PROMPT" to DefaultPrompts.INTENT_ROUTER_PROMPT,
            "code:DefaultPrompts.DECOMPOSITION_PROMPT" to DefaultPrompts.DECOMPOSITION_PROMPT,
            "code:DefaultPrompts.EVALUATION_PROMPT" to DefaultPrompts.EVALUATION_PROMPT,
            "code:DefaultPrompts.SUMMARY_PROMPT" to DefaultPrompts.SUMMARY_PROMPT,
            "code:DefaultPrompts.OUTPUT_FORMAT_PROMPT" to DefaultPrompts.OUTPUT_FORMAT_PROMPT,
            "code:DefaultPrompts.CLARIFICATION_PROMPT" to DefaultPrompts.CLARIFICATION_PROMPT,
            "code:DefaultPrompts.LiteRt.SYSTEM_FALLBACK" to DefaultPrompts.LiteRt.SYSTEM_FALLBACK,
            "code:DefaultPrompts.Cloud.SYSTEM_FALLBACK" to DefaultPrompts.Cloud.SYSTEM_FALLBACK,
            "code:DefaultPrompts.System.SYSTEM_FALLBACK" to DefaultPrompts.System.SYSTEM_FALLBACK,
            "code:DefaultPrompts.System.IMAGE_PRESENT_NOTE" to DefaultPrompts.System.IMAGE_PRESENT_NOTE,
            "code:DefaultPrompts.Output.SYSTEM_FALLBACK" to DefaultPrompts.Output.SYSTEM_FALLBACK,
            "code:DefaultPrompts.Output.FORMATTING_TEMPLATE" to DefaultPrompts.Output.FORMATTING_TEMPLATE,
            "code:DefaultPrompts.Summary.SYSTEM_FALLBACK" to DefaultPrompts.Summary.SYSTEM_FALLBACK,
            "code:DefaultPrompts.Summary.SYNTHESIS_TEMPLATE" to DefaultPrompts.Summary.SYNTHESIS_TEMPLATE,
            "code:DefaultPrompts.IntentRouter.SYSTEM_FALLBACK" to DefaultPrompts.IntentRouter.SYSTEM_FALLBACK,
            "code:DefaultPrompts.Decomposition.SYSTEM_FALLBACK" to DefaultPrompts.Decomposition.SYSTEM_FALLBACK,
            "code:DefaultPrompts.Evaluation.SYSTEM_FALLBACK" to DefaultPrompts.Evaluation.SYSTEM_FALLBACK,
            "code:DefaultPrompts.Clarification.SYSTEM_FALLBACK" to DefaultPrompts.Clarification.SYSTEM_FALLBACK,
            "code:DefaultPrompts.Clarification.CONTEXT_TEMPLATE" to DefaultPrompts.Clarification.CONTEXT_TEMPLATE,
            "code:DefaultPrompts.Tool.CATALOG_TOOL_LABEL" to DefaultPrompts.Tool.CATALOG_TOOL_LABEL,
            "code:DefaultPrompts.Tool.CATALOG_DESCRIPTION_LABEL" to DefaultPrompts.Tool.CATALOG_DESCRIPTION_LABEL,
            "code:DefaultPrompts.Tool.CATALOG_PARAMETERS_LABEL" to DefaultPrompts.Tool.CATALOG_PARAMETERS_LABEL,
            "code:DefaultPrompts.Tool.AUTO_SELECT_TEMPLATE" to DefaultPrompts.Tool.AUTO_SELECT_TEMPLATE,
            "code:DefaultPrompts.Tool.ARGUMENT_GENERATION_TEMPLATE" to DefaultPrompts.Tool.ARGUMENT_GENERATION_TEMPLATE,
            "code:DefaultPrompts.IfCondition.EVALUATION_TEMPLATE" to DefaultPrompts.IfCondition.EVALUATION_TEMPLATE,
            "code:DefaultPrompts.MemoryExtraction.SYSTEM_FALLBACK" to DefaultPrompts.MemoryExtraction.SYSTEM_FALLBACK,
            "code:DefaultPrompts.MemoryCompaction.SYSTEM_FALLBACK" to DefaultPrompts.MemoryCompaction.SYSTEM_FALLBACK,
            "code:DefaultPrompts.MemorySupersede.INSTRUCTION" to DefaultPrompts.MemorySupersede.INSTRUCTION,
            "code:DefaultPrompts.HistoryCompression.SYSTEM_FALLBACK" to
                DefaultPrompts.HistoryCompression.SYSTEM_FALLBACK,
            "code:DefaultPrompts.QueueProcessor.SUBTASK_INSTRUCTION" to
                DefaultPrompts.QueueProcessor.SUBTASK_INSTRUCTION,
            "code:DefaultPrompts.QueueProcessor.PREVIOUS_RESULTS_HEADER" to
                DefaultPrompts.QueueProcessor.PREVIOUS_RESULTS_HEADER,
            "code:DefaultPrompts.QueueProcessor.RESULT_HEADER_TEMPLATE" to
                DefaultPrompts.QueueProcessor.RESULT_HEADER_TEMPLATE,
            "code:DefaultPrompts.QueueProcessor.CURRENT_SUBTASK_HEADER" to
                DefaultPrompts.QueueProcessor.CURRENT_SUBTASK_HEADER,
            "code:DefaultPrompts.QueueProcessor.SUBTASK_FAILED_PREFIX" to
                DefaultPrompts.QueueProcessor.SUBTASK_FAILED_PREFIX,
            "code:DefaultPrompts.QueueProcessor.COMPLETED_HEADER" to DefaultPrompts.QueueProcessor.COMPLETED_HEADER,
            "code:DefaultPrompts.StructuredOutput.REPAIR_TEMPLATE" to DefaultPrompts.StructuredOutput.REPAIR_TEMPLATE,
            "code:DefaultPrompts.ContextBlocks.HEADER_ORIGINAL_TASK" to
                DefaultPrompts.ContextBlocks.HEADER_ORIGINAL_TASK,
            "code:DefaultPrompts.ContextBlocks.HEADER_EARLIER_SUMMARY" to
                DefaultPrompts.ContextBlocks.HEADER_EARLIER_SUMMARY,
            "code:DefaultPrompts.ContextBlocks.HEADER_CHAT_HISTORY" to DefaultPrompts.ContextBlocks.HEADER_CHAT_HISTORY,
            "code:DefaultPrompts.ContextBlocks.HEADER_LONG_TERM_MEMORY" to
                DefaultPrompts.ContextBlocks.HEADER_LONG_TERM_MEMORY,
            "code:DefaultPrompts.ContextBlocks.HEADER_TOOL_RESULTS" to DefaultPrompts.ContextBlocks.HEADER_TOOL_RESULTS,
            "code:DefaultPrompts.ContextBlocks.HEADER_PREVIOUS_NODE_OUTPUT" to
                DefaultPrompts.ContextBlocks.HEADER_PREVIOUS_NODE_OUTPUT,
            "code:DefaultPrompts.ContextBlocks.TOOL_RESULT_CUT_NOTE_TEMPLATE" to
                DefaultPrompts.ContextBlocks.TOOL_RESULT_CUT_NOTE_TEMPLATE,
            "code:ReadFileExecutor.DESCRIPTION" to ReadFileExecutor.DESCRIPTION,
            "code:ReadFileExecutor.PARAMETERS" to ReadFileExecutor.PARAMETERS,
            "code:ListFilesExecutor.DESCRIPTION" to ListFilesExecutor.DESCRIPTION,
            "code:ListFilesExecutor.PARAMETERS" to ListFilesExecutor.PARAMETERS,
            "code:FindFilesExecutor.DESCRIPTION" to FindFilesExecutor.DESCRIPTION,
            "code:FindFilesExecutor.PARAMETERS" to FindFilesExecutor.PARAMETERS,
            "code:WriteFileExecutor.DESCRIPTION" to WriteFileExecutor.DESCRIPTION,
            "code:WriteFileExecutor.PARAMETERS" to WriteFileExecutor.PARAMETERS,
            "code:EditFileExecutor.DESCRIPTION" to EditFileExecutor.DESCRIPTION,
            "code:EditFileExecutor.PARAMETERS" to EditFileExecutor.PARAMETERS,
            "code:AppendFileExecutor.DESCRIPTION" to AppendFileExecutor.DESCRIPTION,
            "code:AppendFileExecutor.PARAMETERS" to AppendFileExecutor.PARAMETERS,
            "code:DeleteFileExecutor.DESCRIPTION" to DeleteFileExecutor.DESCRIPTION,
            "code:DeleteFileExecutor.PARAMETERS" to DeleteFileExecutor.PARAMETERS,
            "code:HttpRequestExecutor.DESCRIPTION" to HttpRequestExecutor.DESCRIPTION,
            "code:HttpRequestExecutor.PARAMETERS" to HttpRequestExecutor.PARAMETERS,
            "code:SearchTool.TOOL_DESCRIPTION" to SearchTool.TOOL_DESCRIPTION,
            "code:SearchTool.TOOL_PARAMETERS" to SearchTool.TOOL_PARAMETERS.trimIndent(),
            "code:ToolRepositoryImpl.schedule_task.description" to ToolRepositoryImpl.SCHEDULE_TASK_DESCRIPTION,
            "code:ToolRepositoryImpl.schedule_task.parameters" to ToolRepositoryImpl.SCHEDULE_TASK_PARAMETERS,
            "code:ToolRepositoryImpl.delegate_task.description" to ToolRepositoryImpl.DELEGATE_TASK_DESCRIPTION,
            "code:RunWalk.STUCK_CONTEXT_NOTE" to RunWalk.STUCK_CONTEXT_NOTE,
            "code:LiveNodeStep.SOFT_CEILING_CONTEXT_NOTE" to LiveNodeStep.SOFT_CEILING_CONTEXT_NOTE,
        )
    }
}
