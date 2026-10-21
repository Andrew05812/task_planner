package com.taskplanner.data.report

import com.taskplanner.data.calendar.CalendarMath
import com.taskplanner.data.statistics.DashboardMath
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

/**
 * One [ReportPeriod] answered in one zone: the bounds every figure of one report is measured between.
 *
 * The period and the window are apart because they answer different questions. A period is what the reader
 * picked; a window is what that picking costs in the two units the stored rows are actually keyed in, and
 * the rows are keyed in two of them. `work_sessions` sums on `local_day` — the epoch day decided at the
 * moment the interval was measured — while `tasks.completed_at`, `tracking_sessions.started_at` and an
 * insight's `created_at` are instants. A window that only carried milliseconds would have to derive a day
 * from an instant again in every one of its reads; one that only carried days would silently cut the first
 * and last day short. So both pairs are computed here, once, from the same zone, and every section of the
 * report takes its bound off this type instead of re-deriving it and drifting.
 *
 * **Neither bound is produced by adding a duration to an instant.** Both come from `java.time` resolving a
 * calendar day in a zone — `LocalDate.atStartOfDay(zone)`, which is `CalendarMath.startOfDay` — the only
 * way a spring-forward day comes out 23 hours long, a fall-back day 25, and a period spanning either comes
 * out covering the days the reader lived through rather than the days a fixed 86 400 000 ms would have cut
 * the account into. The same holds across a month or a year boundary: [toDayExclusive] is the last day plus
 * one *day*, never the first instant plus *n* milliseconds.
 *
 * Both pairs half-open, `[from, to)`. An event at the exact first instant of the period is inside it; an
 * event at the exact instant the period's last day ends belongs to the next period. The day pair is carried
 * with an exclusive upper bound because that is the shape the interval totals already filter on, and a
 * reader that needs inclusive days — the marks, the achievements, the insights, which are all keyed on
 * epoch days with both ends inside — takes [toDayInclusive], which names the same window and is not a wider
 * one.
 */
data class ReportWindow(
    /** The question this window answers, kept so a report can print what it was asked for. */
    val period: ReportPeriod,

    /** The zone the calendar days were resolved in. Nothing here assumes UTC or the device's zone. */
    val zone: ZoneId,

    /**
     * The first day of week the window was read with.
     *
     * For a [ReportPeriod.Week] it is the period's own rule; for any other period it is the device's,
     * recorded so a weekly section cut out of a longer report — the day-by-day dynamics, the seven-day
     * streaks — re-resolves to the same weeks it was built with rather than to whatever locale the reader
     * has switched to since.
     */
    val firstDay: DayOfWeek,

    /** The period's first day, as an epoch day. Inclusive. */
    val fromDay: Long,

    /** The day after the period's last day, as an epoch day. Exclusive, the bound the interval sums use. */
    val toDayExclusive: Long,

    /** The instant the period's first day starts in [zone]. Inclusive. */
    val fromMillis: Long,

    /** The instant the period's last day ends in [zone], that is, the next day's start. Exclusive. */
    val toMillis: Long,

    /**
     * The day the read is anchored on: what "this week" and "this month" were measured from, and the day
     * the habit and goal figures are asked to stand on.
     */
    val today: LocalDate,

    /**
     * The instant the report was assembled, kept apart from the window on purpose.
     *
     * The report contract separates four things that look alike: the moment an event was stored, the local
     * day that event falls on, the period being asked about, and the moment of answering. Collapsing the
     * last two would let a re-read of a window from last March claim it had been printed in March.
     */
    val generatedAt: Long
) {

    /** The last day inside the window as an inclusive epoch day: the bound a `BETWEEN` wants. */
    val toDayInclusive: Long get() = toDayExclusive - 1

    /** Whole local days the window covers. A 23-hour daylight-saving day counts once, as the reader counts it. */
    val days: Int get() = (toDayExclusive - fromDay).toInt()

    val firstDate: LocalDate get() = LocalDate.ofEpochDay(fromDay)
    val lastDate: LocalDate get() = LocalDate.ofEpochDay(toDayInclusive)

    /** Whether [instant] is inside this window: the first instant in, the closing instant out. */
    fun containsInstant(instant: Long): Boolean = instant in fromMillis until toMillis

    /** Whether the epoch day [day] is inside this window, both ends of the period included. */
    fun containsDay(day: Long): Boolean = day in fromDay..toDayInclusive

    /**
     * The window of the same length that ends the day before this one starts — the one §6.9 compares against.
     *
     * The length is matched in days by [DashboardMath.preceding], the rule the dashboard's own period
     * comparison already uses, and the two dates are then resolved as calendar days rather than by subtracting
     * a duration from an instant: two weeks before a period that spans a daylight-saving change are still
     * fourteen local days, and a millisecond subtraction would put the boundary an hour off and hand the
     * comparison a first or last day the reader never lived through.
     *
     * The zone, the anchor day, the generation instant and the week rule carry over, so both halves of a
     * comparison are measured in one calendar by one set of weeks. Equal lengths in days, and deliberately
     * unequal in milliseconds when only one of the two holds a shift: §4.1 compares weeks and months, not
     * durations, and a percentage between two windows of different day counts would be partly a measurement
     * of the calendar.
     */
    fun preceding(zone: ZoneId = this.zone): ReportWindow {
        val days = DashboardMath.preceding(fromDay..toDayInclusive)
        val period = ReportPeriod.Range(LocalDate.ofEpochDay(days.first), LocalDate.ofEpochDay(days.last))
        return ReportWindow.of(period, zone, today = today, generatedAt = generatedAt)
            .copy(firstDay = firstDay)
    }

    companion object {

        /**
         * The window [period] names, resolved in [zone], anchored on [today], stamped [generatedAt].
         *
         * Both of the last two are required rather than defaulted. A report's "now" comes from the injected
         * `TimeBase`, the seam every screen that has to be testable to the second shares, and a value object
         * that reached for `System.currentTimeMillis()` or `LocalDate.now()` behind its caller's back would
         * put a second clock in an application that has agreed on one — and would let a caller forget to say
         * which moment it meant.
         */
        fun of(
            period: ReportPeriod,
            zone: ZoneId,
            today: LocalDate,
            generatedAt: Long
        ): ReportWindow {
            val first = period.firstDateOf()
            val last = period.lastDateOf()
            return ReportWindow(
                period = period,
                zone = zone,
                firstDay = when (period) {
                    is ReportPeriod.Week -> period.firstDay
                    else -> CalendarMath.firstDayOfWeek()
                },
                fromDay = first.toEpochDay(),
                toDayExclusive = last.toEpochDay() + 1,
                fromMillis = CalendarMath.startOfDay(first, zone),
                // The day after the last one resolved as a day, not as a duration added to an instant: on a
                // 25-hour day the next start really is 25 hours away, and `startOfDay` is what knows that.
                toMillis = if (last == LocalDate.MAX) Long.MAX_VALUE
                else CalendarMath.startOfDay(last.plusDays(1), zone),
                today = today,
                generatedAt = generatedAt
            )
        }
    }
}

/**
 * The period's first day.
 *
 * Every case names its own start, and none of them needs the day the report is anchored on: the reader's
 * "this week" arrives already resolved to a [ReportPeriod.Week] with an anchor inside it, which is what
 * keeps a report built at 23:59 and re-read at 00:01 asking about the same seven days.
 *
 * A week is resolved through [DashboardMath.weekStart] — the same rule the dashboard's own weeks and the
 * calendar grid use — so a report and the screen beside it cannot disagree about which seven days "that
 * week" was.
 */
internal fun ReportPeriod.firstDateOf(): LocalDate = when (this) {
    is ReportPeriod.Day -> date
    is ReportPeriod.Week -> DashboardMath.weekStart(anchor, firstDay)
    is ReportPeriod.Month -> month.atDay(1)
    is ReportPeriod.Year -> LocalDate.of(year, 1, 1)
    is ReportPeriod.Range -> from
}

/** The period's last day. A range includes both ends, which is what a date picker that chose one day means. */
internal fun ReportPeriod.lastDateOf(): LocalDate = when (this) {
    is ReportPeriod.Day -> date
    is ReportPeriod.Week -> DashboardMath.weekStart(anchor, firstDay).plusDays(6)
    is ReportPeriod.Month -> month.atEndOfMonth()
    is ReportPeriod.Year -> LocalDate.of(year, 12, 31)
    is ReportPeriod.Range -> to
}
