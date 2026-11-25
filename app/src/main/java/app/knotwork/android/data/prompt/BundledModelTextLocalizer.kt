package app.knotwork.android.data.prompt

import android.content.Context
import app.knotwork.android.data.repositories.AppLanguageRepositoryImpl.Companion.DEFAULT_LANGUAGE
import app.knotwork.android.di.ApplicationScope
import app.knotwork.android.di.IoDispatcher
import app.knotwork.android.domain.prompt.ModelTextLocalizer
import app.knotwork.android.domain.prompt.ModelTextTranslationsJson
import app.knotwork.android.domain.repositories.AppLanguageRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.FileNotFoundException
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [ModelTextLocalizer] over the bundled translations, `assets/model_text/<language>.json`,
 * in the language the interface is shown in.
 *
 * The model is addressed in the interface language: a person who reads the app in
 * Russian gets prompts in Russian, and English everywhere else a translation is
 * missing. The default language has no file — its texts are the English themselves.
 *
 * Each language's file is read once, on the first text asked for in it, and kept for
 * the life of the process: the assets are packaged into the APK and cannot change while
 * it runs. The file of the language the interface is shown in is read ahead, on [io],
 * as soon as the language is known — so the first prompt, or a prompt preview drawn on
 * the main thread, does not pay for the read. A file that cannot be read leaves that language in English — a model
 * addressed in English is worth more than a run that fails — and is logged;
 * `ModelTextTranslationCoverageTest` keeps every shipped file readable, so that is a
 * damaged installation.
 *
 * @property context Application context the assets are opened from.
 * @property appLanguage The language the interface is shown in.
 * @param scope Application-lifetime scope the read-ahead runs in.
 * @param io Dispatcher the read-ahead runs on.
 */
@Singleton
class BundledModelTextLocalizer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appLanguage: AppLanguageRepository,
    @ApplicationScope scope: CoroutineScope,
    @IoDispatcher io: CoroutineDispatcher,
) : ModelTextLocalizer {

    private val byLanguage = ConcurrentHashMap<String, Map<String, String>>()

    init {
        scope.launch(io) {
            appLanguage.interfaceLanguage.collect { tag ->
                val language = languageOf(tag)
                if (language != DEFAULT_LANGUAGE) translations(language)
            }
        }
    }

    override fun localize(text: String): String {
        if (text.isEmpty()) return text
        val language = languageOf(appLanguage.interfaceLanguage.value)
        if (language == DEFAULT_LANGUAGE) return text
        return translations(language)[text] ?: text
    }

    /** The primary subtag of [tag], lower-cased: `ru-RU` and `ru` share one file. */
    private fun languageOf(tag: String): String = tag.substringBefore('-').lowercase(Locale.ROOT)

    private fun translations(language: String): Map<String, String> {
        byLanguage[language]?.let { return it }
        val read = read(language)
        return byLanguage.putIfAbsent(language, read) ?: read
    }

    private fun read(language: String): Map<String, String> {
        val path = "$DIRECTORY/$language.json"
        return try {
            val text = context.assets.open(path).bufferedReader().use { it.readText() }
            ModelTextTranslationsJson.parse(text, language)
        } catch (_: FileNotFoundException) {
            emptyMap()
        } catch (e: IOException) {
            Timber.w(e, "Model text translation %s could not be read", path)
            emptyMap()
        } catch (e: IllegalArgumentException) {
            Timber.w(e, "Model text translation %s is malformed", path)
            emptyMap()
        }
    }

    private companion object {
        /** Directory of the translation files under `assets`. */
        const val DIRECTORY = "model_text"
    }
}
