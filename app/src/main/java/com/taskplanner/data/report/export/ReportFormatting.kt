package com.taskplanner.data.report.export

import com.taskplanner.ui.settings.AppLocale
import android.content.Context
import com.taskplanner.R
import com.taskplanner.data.report.ReportPeriod
import com.taskplanner.ui.util.WorkTimeFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.text.NumberFormat
import java.util.Locale

/**
 * The ways a figure becomes a word in a report.
 *
 * One object because the same number appears in a summary line, in a table cell and in a chart's caption, and
 * three spellings of it in one document is a document that contradicts itself. Two rules decide everything
 * here, and both of them come from the promise a report makes rather than from taste:
 *
 *  * **A measured duration is spelled by the app's own rule.** `WorkTimeFormat.compact` is what a task card, a
 *    history row and the dashboard already print, so the hours a report states are the hours the reader saw on
 *    the screen the day they did the work.
 *  * **A number is printed the way the reader reads numbers.** Grouping separators, a decimal comma or a point,
 *    a percent sign before or after — these belong to the locale, and the report is written for a person, not
 *    for a parser. The machine-readable export is the csv, which deliberately uses none of this.
 *
 * A date is the one thing given two spellings. A calendar date is written the way the reader's locale writes
 * dates; the moment a report was assembled is written with a time beside it, because that is a timestamp and a
 * report that said only "22 October" of the minute it was written would be a report that could not tell two
 * runs of the same day apart.
 */
object ReportFormatting {

    /** A day as the reader's locale spells it: "3 Jun 2026", "3 июня 2026 г.". */
    fun day(context: Context, date: LocalDate): String =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(AppLocale.current()).format(date)

    /** A day short enough to sit under one bar of a chart. */
    fun shortDay(date: LocalDate): String =
        DateTimeFormatter.ofPattern("d MMM", AppLocale.current()).format(date)

    /**
     * A month as a heading: "June 2026", "Октябрь 2026".
     *
     * Not `ofPattern("MMMM yyyy")`, which is what this was. In Russian that pattern answers "октября 2026" —
     * the genitive, the form a date *with a day* takes ("5 октября 2026"), chosen by the locale data because a
     * year sits in the pattern beside the month. A heading has no day, so the genitive reads as a sentence with
     * the beginning missing, and the Calendar screen showed it on every Russian month until Stage 14 measured
     * it on a device. `FULL_STANDALONE` is the nominative the reader would write — the name Android's `java.time` uses, which is the Java 8 spelling and the one the platform still carries, and the first letter is
     * raised with the same locale rather than `capitalize()`, which would fold to the title case of whichever
     * language happened to be the default.
     */
    fun month(date: LocalDate): String {
        val locale = AppLocale.current()
        val name = date.month.getDisplayName(java.time.format.TextStyle.FULL_STANDALONE, locale)
        return name.replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() } +
            " " + date.year
    }

    /**
     * The moment the report was written, in the locale's own date-and-time style.
     *
     * [zone] is the zone the instant is resolved into — the report's, not the device's — so a report of a
     * Berlin week rebuilt in Tokyo says when it was written in the calendar it was written for. The zone
     * itself is named by its own line above this one, which is why it does not appear here: a caption that
     * repeated it twice would be a caption that could disagree with itself.
     */
    fun instant(context: Context, millis: Long, zone: ZoneId): String {
        val text = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withLocale(AppLocale.current())
            .format(Instant.ofEpochMilli(millis).atZone(zone))
        return context.getString(R.string.report_instant_format, text)
    }

    fun count(value: Int): String = integer().format(value.toLong())

    fun count(value: Long): String = integer().format(value)

    /** The measured seconds, in the words the rest of the app uses for them. */
    fun seconds(context: Context, value: Long): String = WorkTimeFormat.compact(context, value)

    /**
     * A ratio, printed with as many decimals as it carries meaning for and no more.
     *
     * "1,7 tasks a day" is a figure a reader can hold; "1,7000000000000002" is the arithmetic underneath it
     * showing through, which is what a float division does and is not the reader's business.
     */
    fun decimal(value: Double, digits: Int): String =
        NumberFormat.getNumberInstance(AppLocale.current()).apply {
            minimumFractionDigits = digits
            maximumFractionDigits = digits
        }.format(value)

    /** A share of 0f to 1f as the locale's percent, with no decimals: the exact counts are printed beside it. */
    fun percent(fraction: Float): String = NumberFormat.getPercentInstance(AppLocale.current()).format(fraction)

    /**
     * A whole percentage the repository already worked out, in the words the reader's locale uses.
     *
     * [percent] is a figure from 0 to 100 — an adherence, computed as integers where the division is exact by
     * design — and it is divided back rather than printed with a hand-typed sign, because which side of the
     * number a percent belongs on is the locale's decision and not this report's.
     */
    fun wholePercent(percent: Int): String = NumberFormat.getPercentInstance(AppLocale.current())
        .format(percent.toDouble() / 100.0)

    /** The way this report shows that it does not know: a dash, not a zero and not an empty cell. */
    fun missing(context: Context): String = context.getString(R.string.report_value_missing)

    /** The period as the one or two words that say which of the five kinds of report this is. */
    fun periodWord(context: Context, period: ReportPeriod): String = context.getString(
        when (period) {
            is ReportPeriod.Day -> R.string.report_period_day
            is ReportPeriod.Week -> R.string.report_period_week
            is ReportPeriod.Month -> R.string.report_period_month
            is ReportPeriod.Year -> R.string.report_period_year
            is ReportPeriod.Range -> R.string.report_period_range
        }
    )

    /** A weekday's own name, which is what a reader needs to see to trust a week's bounds. */
    fun weekday(day: java.time.DayOfWeek): String = day.getDisplayName(java.time.format.TextStyle.FULL, AppLocale.current())

    private fun integer(): NumberFormat = NumberFormat.getIntegerInstance(AppLocale.current())
}
