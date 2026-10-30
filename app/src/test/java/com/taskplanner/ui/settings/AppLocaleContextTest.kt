package com.taskplanner.ui.settings

import android.content.Context
import androidx.core.os.LocaleListCompat
import androidx.test.core.app.ApplicationProvider
import com.taskplanner.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [AppLocale.localized] — the same rule as [AppLocale.of], applied to a context instead of a locale.
 *
 * The case this exists for is a notification. A channel's name and description, a body in the shade and the
 * three action labels under it are all read from the **application** context, and the application context is
 * the one context AppCompat does not wrap: on API 24 to 32 it resolves resources in the language the device
 * shipped with, however the app was set. The result is an app that is Russian everywhere a window is, and
 * English everywhere the system speaks for it — the reader meets it in Settings → Apps → Notifications, where
 * they go to switch reminders off.
 *
 * **Why these run under Robolectric and not on a device.** The claim is about a `Configuration` being carried
 * into resource resolution, which the JVM can answer honestly: the base context and the wrapped one are asked
 * the same key, and the two answers are compared. What the device classes prove is different and harder to
 * fake — that a language chosen in a window is still chosen after a force-stop
 * (`LanguageSwitchInstrumentedTest`). What this file proves is that the seam between the choice and a
 * non-window context exists and holds the right way round.
 */
@RunWith(RobolectricTestRunner::class)
class AppLocaleContextTest {

    private val base: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun aChosenLanguageReachesAContextThatNeverShowsAWindow() {
        val russian = AppLocale.localized(base, LocaleListCompat.forLanguageTags("ru"))

        val englishText = base.getString(R.string.reminder_channel_name)
        val russianText = russian.getString(R.string.reminder_channel_name)
        assertNotEquals(
            "one key, two contexts, one answer each — the wrapped context is reading its own configuration",
            englishText, russianText
        )
        assertTrue(
            "and the answer is Cyrillic rather than a different English: '$russianText'",
            russianText.any { it.code in 0x0400..0x04FF }
        )
        assertEquals(
            "the configuration carries the choice, which is what the framework reads for a channel name",
            "ru", russian.resources.configuration.locales[0].language
        )
    }

    /**
     * "System default" is the absence of a choice, not a second language.
     *
     * Returning the base context here is not an optimisation. A wrapper would be a second `Resources` to keep
     * in step for the rest of the process's life, and it would answer the same question; and a test that let
     * the wrapper through would not notice the day the two stop agreeing — which is the shape of the bug this
     * whole class is about.
     */
    @Test
    fun noChoiceReturnsTheContextItWasGiven() {
        assertSame(
            "an empty locale list means the device decides, so there is nothing to wrap",
            base, AppLocale.localized(base, LocaleListCompat.getEmptyLocaleList())
        )
    }

    @Test
    fun aRegionInTheChoiceReachesTheContextToo() {
        val russian = AppLocale.localized(base, LocaleListCompat.forLanguageTags("ru-RU"))

        assertEquals("ru", russian.resources.configuration.locales[0].language)
        assertEquals(
            "the region survives, because the delimiter a report's csv uses is a regional question",
            "RU", russian.resources.configuration.locales[0].country
        )
    }

    /**
     * The list, not the first tag.
     *
     * `forLanguageTags("ru,en")` is what a reader with two preferences set gets from the framework, and a
     * `Configuration` built from only the first letter of that string would drop the fallback the system uses
     * when a string has not been translated yet.
     */
    @Test
    fun aReaderWithTwoPreferencesKeepsBoth() {
        val wrapped = AppLocale.localized(base, LocaleListCompat.forLanguageTags("ru,en"))
        val locales = wrapped.resources.configuration.locales

        assertEquals(
            "the whole list travels, not just the winner",
            2, locales.size()
        )
        assertEquals("ru", locales[0]?.language)
        assertEquals("en", locales[1]?.language)
    }
}
