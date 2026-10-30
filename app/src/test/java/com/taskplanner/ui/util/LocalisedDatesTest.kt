package com.taskplanner.ui.util

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.taskplanner.ui.settings.AppLocale
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The contract [LocalisedDates] exists to keep: a date is ordered by the language, not by a pattern.
 *
 * Each case states what the language says for itself, in words a reader of that language would recognise,
 * rather than comparing two locales against each other — which is the shape of the test that let the defect
 * through. `a date line follows the locale the device is on` in `CardPresentationTest` proved that French and
 * English differ; it never proved that either of them was in the right order.
 *
 * **Why the expectations are loose about the month word and tight about the position.** The abbreviation
 * itself comes from the ICU or JDK locale data on whatever runtime this runs — Android's `ru` medium date is
 * «30 нояб. 2026», the JVM's is the same shape but the punctuation of locale data has changed across
 * releases. The *order* is the claim Stage 14 is making, and the day number is the part of it that cannot
 * move without the locale data contradicting itself.
 */
@RunWith(RobolectricTestRunner::class)
class LocalisedDatesTest {

    /** 2026-11-30T12:00 local, a Monday, and far from midnight so a DST shift cannot move the day. */
    private val day: Long = java.util.Calendar.getInstance().apply {
        clear()
        set(2026, java.util.Calendar.NOVEMBER, 30, 12, 0, 0)
    }.timeInMillis

    @After
    fun handTheLanguageBack() {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
    }

    @Test fun `a russian date opens with the day`() {
        Locale.setDefault(Locale.forLanguageTag("ru"))
        val text = LocalisedDates.date(day)
        assertTrue("a Russian reader writes the number first; got '$text'", text.trim().startsWith("30"))
        assertTrue("and the year is still on it: '$text'", text.contains("2026"))
    }

    @Test fun `an american date opens with the month`() {
        Locale.setDefault(Locale.forLanguageTag("en-US"))
        val text = LocalisedDates.date(day)
        assertTrue("an American reader writes the month first; got '$text'", text.trim().startsWith("Nov"))
    }

    @Test fun `a french date opens with the day`() {
        Locale.setDefault(Locale.forLanguageTag("fr"))
        val text = LocalisedDates.date(day)
        assertTrue("a French reader writes the number first; got '$text'", text.trim().startsWith("30"))
    }

    /**
     * The dates ask [AppLocale], not the process.
     *
     * This is the wiring claim, and it is deliberately small. The larger one — that a language chosen in
     * Settings is the language the arithmetic answers in — is not provable here: `setApplicationLocales` with
     * no AppCompat window on the other end is a silent no-op (the finding `CsvLocaleTest` records, met again
     * when this class first tried it), so a Robolectric test cannot move the delegate and a case that
     * pretended otherwise would be asserting that `Locale.getDefault()` is still `Locale.getDefault()`.
     * What is proven, and where: `AppLocaleTest` for the accessor, `CalendarLocaleTest` and `CsvLocaleTest`
     * on a device for the choice reaching the arithmetic. This case fails if someone puts
     * `Locale.getDefault()` back into the formatters above.
     */
    @Test fun `the dates ask the app locale rather than the process default`() {
        assertEquals(AppLocale.current(), LocalisedDates.locale())
        Locale.setDefault(Locale.forLanguageTag("fr"))
        assertEquals(
            "and they keep asking it after the process moves, because nothing is cached",
            AppLocale.current(), LocalisedDates.locale()
        )
    }

    @Test fun `a weekday header names the day and keeps the locale order`() {
        Locale.setDefault(Locale.forLanguageTag("ru"))
        val text = LocalisedDates.dateWithWeekday(day)
        // Lowercase, and staying lowercase: Russian orthography writes weekdays and months in the small
        // letter, so raising the first one here would be this helper correcting the language rather than
        // reporting it. `ReportFormatting.month` raises a month because a card heading is a different
        // surface, and that choice was made against a photograph.
        assertTrue("the weekday leads a header in either language: '$text'",
            text.startsWith("понедельник", ignoreCase = true))
        assertTrue("and the date behind it is still day-first: '$text'", text.contains("30 нояб"))
    }

    @Test fun `a date with a time carries both in the locale's own shapes`() {
        Locale.setDefault(Locale.forLanguageTag("ru"))
        val text = LocalisedDates.dateTime(day)
        assertTrue("the day leads: '$text'", text.trim().startsWith("30"))
        assertTrue("and a minute reading is on it: '$text'", Regex("\\d{1,2}:\\d{2}").containsMatchIn(text))
    }

    /**
     * A day-and-month label, for the two ends of a calendar week.
     *
     * The one method here that asks the platform for a pattern instead of a style, so it is the one case
     * worth pinning on its own: `getBestDateTimePattern` takes the skeleton letters as a set, and this checks
     * that the answer it gives back really is ordered by the language — «нояб. 30» was the shape the calendar
     * week header carried into Russian.
     */
    @Test fun `a short day-and-month label follows the language that reads it`() {
        Locale.setDefault(Locale.forLanguageTag("ru"))
        assertTrue(
            "a Russian week label opens with the number: '${LocalisedDates.dayAndMonth(day)}'",
            LocalisedDates.dayAndMonth(day).trim().startsWith("30")
        )
        Locale.setDefault(Locale.forLanguageTag("en-US"))
        assertTrue(
            "an American one opens with the month: '${LocalisedDates.dayAndMonth(day)}'",
            LocalisedDates.dayAndMonth(day).trim().startsWith("Nov")
        )
    }

    /**
     * The clock is not a language question.
     *
     * Pinned because a reader of this test could reasonably expect `dateTime` and `time` to be the same kind
     * of locale-following: they are not. The hour system comes from the device owner's setting, and every
     * card width in the Stage 4 budget was measured against `HH:mm`, so the twenty-four-hour form stays for
     * both languages while the date around it moves.
     */
    @Test fun `a clock reading stays twenty-four-hour in either language`() {
        Locale.setDefault(Locale.forLanguageTag("en-US"))
        assertEquals("12:00", LocalisedDates.time(day))
        Locale.setDefault(Locale.forLanguageTag("ru"))
        assertEquals("12:00", LocalisedDates.time(day))
    }
}
