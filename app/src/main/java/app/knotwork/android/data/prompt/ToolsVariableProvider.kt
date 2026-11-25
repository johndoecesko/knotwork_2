package app.knotwork.android.data.prompt

import app.knotwork.android.domain.prompt.ChatTranscript
import app.knotwork.android.domain.prompt.ModelTextLocalizer
import app.knotwork.android.domain.prompt.PromptVariableProvider
import app.knotwork.android.domain.repositories.ToolRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Provides the value for the `$TOOLS` placeholder.
 *
 * Resolves to a newline-separated list of currently active tools in the form
 * `name — description`, one tool per line. The list is fetched from
 * [ToolRepository.getAvailableTools] on every render so disabled tools, MCP
 * connection changes, etc. are reflected without restarting the agent.
 *
 * When no tools are available the placeholder resolves to an empty string —
 * this matches the behaviour expected by [app.knotwork.android.domain.prompt.PromptTemplateEngine]
 * for "no value" cases and keeps the rendered prompt clean instead of showing
 * "No tools".
 *
 * A built-in tool's description comes in the language the model is addressed in;
 * a server's or another app's comes as its author wrote it.
 *
 * @property toolRepository Domain abstraction over the active tool registry;
 * its `getAvailableTools()` already filters out user-disabled local tools.
 * @property modelTextLocalizer Puts a built-in tool's description into the language
 * the model is addressed in.
 */
@Singleton
class ToolsVariableProvider @Inject constructor(
    private val toolRepository: ToolRepository,
    private val modelTextLocalizer: ModelTextLocalizer = ModelTextLocalizer.ENGLISH,
) : PromptVariableProvider {

    override fun key(): String = KEY

    /**
     * Builds the formatted tools listing.
     *
     * Each entry is rendered as `${tool.name} — ${tool.description}`. The
     * en-dash (`—`) is intentional: it avoids ambiguity when a tool description
     * contains `-` characters (e.g. CLI flags) and reads naturally inside an
     * LLM prompt.
     *
     * @return Newline-joined `name — description` lines, or an empty string
     * when no tools are available.
     */
    override suspend fun resolve(): String {
        val tools = toolRepository.getAvailableTools()
        if (tools.isEmpty()) return ""
        // A description is the tool's own text — an MCP server's, for a server
        // tool — so its further lines are indented and cannot pose as a tool entry.
        return tools.joinToString(separator = "\n") { tool ->
            ChatTranscript.entry("${tool.name} — ", modelTextLocalizer.localize(tool.description))
        }
    }

    private companion object {
        const val KEY = "TOOLS"
    }
}
