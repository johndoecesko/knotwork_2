package app.knotwork.android.domain.prompt

/**
 * Puts a text the app itself wrote for a model into the language the model is addressed in.
 *
 * The app's own model-facing texts — the default and fallback system prompts, the
 * wrap-templates of the executors, the context-block headers, the built-in tools'
 * descriptions, and the system prompts of the bundled pipelines, templates and skills —
 * are written in English. A translation is kept beside them, one file per language,
 * and is applied **only to the exact English it was written for**:
 *
 * - a prompt the user wrote or edited is never touched — no translation names it;
 * - a pipeline made from a bundled preset keeps its English prompt in storage, and the
 *   model meets it in the language of the moment, so switching the language back
 *   switches the prompts back, with no migration;
 * - a reworded English text silently loses its stale translation and runs in English
 *   until the translation is updated (`ModelTextTranslationCoverageTest` turns that
 *   into a failing build).
 *
 * What stays English inside a translated text is part of the contract, not of the
 * wording: routing keywords, verdict tokens, JSON keys, tool names, `$VARIABLE`
 * placeholders, and the rule that a machine-facing step writes its output in English
 * (decisions 0002).
 *
 * Callers apply it to the **unrendered** text — before `$VARIABLE` substitution and
 * before any value is spliced in — because a rendered prompt never equals the English
 * it was written for.
 */
fun interface ModelTextLocalizer {

    /**
     * Returns [text] in the language the model is addressed in now.
     *
     * @param text A model-facing text exactly as the app or a bundled asset carries it.
     * @return Its translation, or [text] itself when none was written for exactly this English.
     */
    fun localize(text: String): String

    /** Constants. */
    companion object {
        /** Addresses every model in English, the language every text is written in. */
        val ENGLISH: ModelTextLocalizer = ModelTextLocalizer { it }
    }
}
