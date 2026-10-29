package com.taskplanner.ui.settings

import android.content.res.Configuration
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.taskplanner.R
import java.util.Locale

/**
 * The three things the Settings screen offers, and what each one asks the framework to do.
 *
 * The whole feature is a mapping between a choice and an app locale list, so the mapping is a pure function of a
 * tag string and nothing else: `AppCompatDelegate` needs a live activity to apply a locale and gives no way to ask
 * what it *would* have applied, which leaves a rule written inside it untestable off a device. `of(tags)` is what
 * [AppLanguageTest] pins down; [current] and [apply] are the two lines that talk to the framework.
 *
 * **System default is an empty list, not a language.** Forcing the device's own language would freeze the app into
 * whatever `Configuration.locales` said at the moment of the choice, and a reader who then changes the system
 * language would find the app refusing to follow it. An empty list is the framework's own way of saying "ask me
 * every time".
 *
 * **The two names are not translated.** "English" and «Русский» are written in the script a reader of that
 * language uses to recognise it, which is why the resource carries `translatable="false"` rather than a Russian
 * "Английский" that would appear in an English interface.
 *
 * **A tag the app does not carry reads as the system default.** Android 13 lets a reader pick a per-app language
 * from system settings for any language the device knows. The app has strings in two of them; the honest answer to
 * the other 400 is "not one of ours", and showing that as English would put the Settings row in disagreement with
 * the system screen above it.
 */
enum class AppLanguage(@StringRes val labelRes: Int, val languageTags: String) {

    SystemDefault(R.string.language_system_default, ""),
    English(R.string.language_english, "en"),
    Russian(R.string.language_russian, "ru");

    /** The locale list this choice asks for: empty means "follow the system", as the framework defines it. */
    fun localeList(): LocaleListCompat =
        if (languageTags.isEmpty()) LocaleListCompat.getEmptyLocaleList()
        else LocaleListCompat.forLanguageTags(languageTags)

    /**
     * The language this build is actually showing.
     *
     * `Configuration.locales` rather than `Locale.getDefault()`, because that is what the resource loader used to
     * pick the strings on screen: the two can differ on API 24–32, where the AppCompat backport applies the stored
     * app locale to the configuration it hands activities without rewriting the process default. A Settings row
     * that read the JVM default could tell a reader the app is Russian while every label on the screen is English.
     */
    fun isAppliedTo(configuration: Configuration): Boolean = when (this) {
        SystemDefault -> configuration.locales.isEmpty ||
            LocaleListCompat.wrap(configuration.locales).toLanguageTags()
                .substringBefore(',')
                .substringBefore('-')
                .lowercase(Locale.ROOT) !in listOf("en", "ru")
        else -> LocaleListCompat.wrap(configuration.locales).toLanguageTags()
            .split(',')
            .map { it.trim().substringBefore('-').lowercase(Locale.ROOT) }
            .firstOrNull() == languageTags
    }

    companion object {

        /** The choice a tag string stands for. Anything the app has no strings for is the system default. */
        fun of(tags: String?): AppLanguage {
            val first = tags?.trim()?.trimEnd(',')?.takeIf { it.isNotEmpty() }?.substringBefore(',')?.trim()
                ?: return SystemDefault
            if (first.isEmpty()) return SystemDefault
            val language = first.substringBefore('-').lowercase(Locale.ROOT)
            return entries.firstOrNull { it != SystemDefault && it.languageTags == language } ?: SystemDefault
        }

        fun of(locales: LocaleListCompat): AppLanguage = of(locales.toLanguageTags())

        /** What the framework holds right now, which is what the row has to show after a restart. */
        fun current(): AppLanguage = of(AppCompatDelegate.getApplicationLocales())

        /**
         * Applies the choice and lets the framework persist it.
         *
         * No preference file of the app's own: on API 33+ the locale belongs to the system's per-app language
         * store, and on API 24–32 the AppCompat backport stores it itself (see the `autoStoreLocales` service
         * declaration in `AndroidManifest.xml`, which is what makes that store survive a process death). A second
         * copy here would be a second answer to the same question, and the two would disagree the first time a
         * reader changed the language from the system screen instead.
         */
        fun apply(language: AppLanguage) {
            AppCompatDelegate.setApplicationLocales(language.localeList())
        }
    }
}
