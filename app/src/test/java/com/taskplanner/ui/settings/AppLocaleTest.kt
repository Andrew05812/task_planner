package com.taskplanner.ui.settings

import androidx.core.os.LocaleListCompat
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which locale the app's arithmetic is allowed to use.
 *
 * The rule is two lines, and the reason it is worth a file is that the alternative reads correctly on every
 * device this project is tested on and fails on the ones it is not. `Locale.getDefault()` is the phone's
 * language; Stage 14 gave the app a language of its own, and the AndroidX backport that carries per-app locales
 * down to API 24 applies that choice to the `Configuration` every screen resolves its words from without
 * rewriting the process default. On API 33 the framework does rewrite it — measured, not assumed, by
 * `CalendarLocaleTest`, which found `Locale.getDefault()` answering `ru` after Russian was chosen.
 *
 * So a calculation that asks the process default is right on a 2023 handset and wrong on a 2016 one, in a way
 * nobody looking at a screenshot can see: the labels say "Понедельник" while the calendar still opens the week
 * on Sunday. [AppLocale.of] is where that difference stops mattering, and these tests are the rule stated as
 * data rather than as an observation about one API level.
 */
class AppLocaleTest {

    @Test
    fun aChosenLanguageWinsOverTheProcessDefault() {
        val device = Locale("en", "US")
        val chosen = LocaleListCompat.forLanguageTags("ru")

        assertEquals("ru", AppLocale.of(chosen, device).language)
    }

    /**
     * The System-default row, in code.
     *
     * An empty list is the framework's way of saying the reader delegated the decision to the handset, so the
     * answer has to be the process default — not the app's own English, which is what a `Locale("en")` here
     * would quietly become and what would make "System default" mean "English" on a German phone.
     */
    @Test
    fun noChoiceLeavesTheDeviceAnswering() {
        val device = Locale("de", "DE")

        assertEquals(
            "an empty app locale list hands the question back",
            device, AppLocale.of(LocaleListCompat.getEmptyLocaleList(), device)
        )
    }

    @Test
    fun aListWithMoreThanOneLanguageIsDecidedByItsFirstEntry() {
        val chosen = LocaleListCompat.forLanguageTags("ru,en")

        assertEquals(
            "the reader's first preference is the language the app is in",
            "ru", AppLocale.of(chosen, Locale.ENGLISH).language
        )
    }

    /**
     * A region survives.
     *
     * It matters for the two decisions the app takes from a locale that a bare language cannot answer: which
     * day a week opens on in the odd corner where a language has two conventions, and what Excel expects a CSV
     * to look like in a region that writes its decimals with a comma.
     */
    @Test
    fun theRegionTagTravelsWithTheLanguage() {
        val chosen = LocaleListCompat.forLanguageTags("ru-RU")
        val resolved = AppLocale.of(chosen, Locale.ENGLISH)

        assertEquals("ru", resolved.language)
        assertEquals("RU", resolved.country)
    }

    @Test
    fun theAppHasNoLocaleOfItsOwnInAFirstBuild() {
        // The rule read against the real framework state, so a change in what `getApplicationLocales` answers
        // for an untouched build is caught here rather than in a screen test three layers away.
        assertEquals(
            Locale.getDefault(),
            AppLocale.of(LocaleListCompat.getEmptyLocaleList(), Locale.getDefault())
        )
    }
}
