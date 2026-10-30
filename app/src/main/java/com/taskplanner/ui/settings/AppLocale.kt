package com.taskplanner.ui.settings

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

/**
 * The language the app's arithmetic should use, read from the app's own locale list rather than the process default.
 *
 * Thirty-odd production sites ask `Locale.getDefault()`: which day a week opens on, whether a CSV separates its
 * columns with a comma or a semicolon, how a number, a percent and a month name are written, what order two
 * Russian titles fall in. Before Stage 14 that was the same question as "what language is the phone in", which
 * is why it worked. Now the language is a choice the app makes for itself, and on API 33+ the framework keeps the
 * process default in step with that choice — measured on API 35 by `CalendarLocaleTest`, which found
 * `Locale.getDefault()` answering `ru` the moment the reader chose Russian.
 *
 * Below API 33 the same measurement does not hold. The AppCompat backport applies the stored app locale to the
 * `Configuration` it hands every activity — that is what makes the *words on screen* Russian — without rewriting
 * the JVM default for the process. A calculation that reads `Locale.getDefault()` there keeps answering in the
 * handset's language, so a Russian reader on API 24 to 32 gets Russian labels over a calendar whose week still
 * starts on Sunday and a CSV whose columns are still split by a comma. That is the half-translated app the stage
 * was told not to ship, and it is invisible on any modern test device.
 *
 * So the rule is written down once here: if the app has chosen a locale, that is the locale; otherwise the
 * process default, which is the device's. [of] is split from [current] so the rule can be tested without a
 * framework state, and the calls that decide a week, a delimiter or an alphabet order go through it.
 */
object AppLocale {

    /** The locale the app's own choice asks for, or the process default when the app has made no choice. */
    fun current(): Locale = of(AppCompatDelegate.getApplicationLocales(), Locale.getDefault())

    /**
     * A context that reads its resources in the language the app chose.
     *
     * Activities get this for free — AppCompat wraps them — but a notification channel, a `WorkManager`
     * worker or a `BroadcastReceiver` holds the *application* context, and that one is built from the
     * device's configuration on every release below 33. `ReminderNotifier` is the case that matters: its
     * channel name and description are what a reader sees in the system's own notification settings, and
     * without this they stayed in the language the handset shipped in, beside a Russian app.
     *
     * An empty choice returns [base] itself rather than a wrapper, because "the system's language" is not a
     * locale to apply — it is the absence of one, and a wrapper would be a second context to keep in step
     * for no change in answer.
     */
    fun localized(base: Context, appLocales: LocaleListCompat): Context {
        if (appLocales.isEmpty) return base
        val tags = appLocales.toLanguageTags()
        if (tags.isEmpty()) return base
        val configuration = Configuration(base.resources.configuration)
        // The setter, not the property: `Configuration.setLocales` does not pair with `getLocales` in the
        // compile stub, so Kotlin sees a read-only `locales` and refuses the assignment.
        configuration.setLocales(LocaleList.forLanguageTags(tags))
        return base.createConfigurationContext(configuration)
    }

    /** [localized] with the app's current choice. */
    fun localized(base: Context): Context = localized(base, AppCompatDelegate.getApplicationLocales())

    /**
     * The rule, with its two inputs handed in.
     *
     * The first entry of the app's locale list wins — that is what "the language of this app" means to the
     * framework, and what `Configuration.locales` gives the screen. An empty list is not "no language" but
     * "the reader delegated the decision", which is the device's own default.
     */
    fun of(appLocales: LocaleListCompat, processDefault: Locale): Locale {
        if (appLocales.isEmpty) return processDefault
        val first = appLocales[0] ?: return processDefault
        return Locale.forLanguageTag(first.toLanguageTag())
    }
}
