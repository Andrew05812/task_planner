package com.taskplanner.ui.util

import com.taskplanner.ui.settings.AppLocale
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A day, written the way the reader's own language writes it.
 *
 * Stage 14 found this defect in a photograph rather than in a test: the Russian task card read «нояб. 01,
 * 2026» while everything around it was Russian. The month word had been translated and the order had not —
 * because the order was never the locale's to begin with. Five screens carried a literal
 * `SimpleDateFormat("MMM dd, yyyy")`, and a literal pattern is a claim about one language's word order
 * printed into every language: French got «nov. 22, 2026», Russian got «нояб. 22, 2026», and the test that
 * existed for exactly this (`a date line follows the locale the device is on`) passed, because it compared
 * one locale's output to another's and never asked either what its own order is.
 *
 * So there is one place, and it holds no pattern of its own for a date. `DateFormat.getDateInstance` asks
 * the locale's data how a medium date is written and answers «Nov 30, 2026» in English and «30 нояб. 2026» in
 * Russian. The English form loses a leading zero it used to carry; that is the locale's answer, and the
 * Stage 4 width budget was measured against a placeholder rather than against this string.
 *
 * **Why the locale is read per call.** Every method here is called while a window is on screen, and the
 * alternative — a formatter built once and held — is what made the five call sites wrong on the devices where
 * the process default does not follow the app's chosen language (API 24 to 32; see [AppLocale]). A
 * `SimpleDateFormat` is also not thread-safe, so a shared instance would be a second defect waiting for a
 * background read. These objects are cheap, and correctness is not.
 */
object LocalisedDates {

    /** The locale the reader chose, which is what every format below is asked in. */
    fun locale(): Locale = AppLocale.current()

    /** «Nov 30, 2026» / «30 нояб. 2026» — the locale's own medium date, order included. */
    fun date(millis: Long): String =
        DateFormat.getDateInstance(DateFormat.MEDIUM, locale()).format(Date(millis))

    /** The same date with the weekday in front, for a header that names the day it is showing. */
    fun dateWithWeekday(millis: Long): String {
        val weekday = SimpleDateFormat("EEEE", locale()).format(Date(millis))
        // The comma is the separator both `ru` and `en` CLDR put between a weekday and a date, and the only
        // thing a locale's medium pattern can say about a weekday is nothing — `FULL` brings the weekday but
        // lengthens the month to its unconstrained form («November», «ноября»), which is a different question
        // from the one the calendar header asks.
        return "$weekday, ${date(millis)}"
    }

    /**
     * A day and its month, short enough to sit inside a range: "Nov 30" / "30 нояб.".
     *
     * The only method here that asks the platform for a pattern rather than a style, because there is no
     * style for it — `MEDIUM` brings the year and a calendar week label has no room for one.
     * `getBestDateTimePattern` is the question "how does this language write a day and a month, given this
     * skeleton", and it answers in the language's own order; the skeleton letters are a set, not a sequence,
     * which is exactly what makes it safe to ask.
     */
    fun dayAndMonth(millis: Long): String {
        val locale = locale()
        return SimpleDateFormat(
            android.text.format.DateFormat.getBestDateTimePattern(locale, "dMMM"), locale
        ).format(Date(millis))
    }

    /** The locale's own date and time, for a row that says when something happened. */
    fun dateTime(millis: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale()).format(Date(millis))

    /**
     * A clock reading, in the twenty-four-hour form this app has always used.
     *
     * Deliberately not `DateFormat.getTimeInstance`: the hour system is the device owner's setting rather
     * than the reader's language, and a Russian and an English reader of the same handset should see the same
     * clock. `HH:mm` is also what every card in the Stage 4 width budget was measured with.
     */
    fun time(millis: Long): String = SimpleDateFormat("HH:mm", locale()).format(Date(millis))
}
