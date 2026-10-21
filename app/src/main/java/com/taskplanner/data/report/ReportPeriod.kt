package com.taskplanner.data.report

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/**
 * The five periods §6's report is asked in: a day, a week, a month, a year, an arbitrary range.
 *
 * This is a question, not a window. A period names what the reader picked — "that Tuesday", "the week
 * containing this Thursday", "March last year" — and says nothing about milliseconds, because the answer
 * depends on which zone the reader lives in and a daylight-saving shift moves the same calendar day to a
 * different pair of instants there. [ReportWindow] is that second half: one period resolved against one
 * zone gives the bounds every figure in the report is measured between.
 *
 * Three of the five cases cannot be invalid, which is why they are ordinary constructors: a day, a week
 * anchor and a month are each a single value the type system already constrains, and a factory that returned
 * a wrapper around a value that cannot be wrong would only push a `when` onto the caller. A range and a year
 * are the exceptions — two independently chosen dates can disagree, and an `Int` year reaches past what
 * `java.time` will build a date for — so those two constructors are `internal` and the only way to hold one
 * is through the factory that says no to the bad case.
 *
 * [Week.firstDay] is named rather than defaulted, because which day a week starts on is the part of a weekly
 * report a caller must not get by accident. [weekOf] is the convenience for "the reader's own week": it
 * applies `CalendarMath.firstDayOfWeek()`, the same `WeekFields.of(Locale.getDefault())` rule the calendar
 * grid and the habit weeks use, since §5 asks for a correct first day of week rather than for a fixed Monday.
 * A caller building a report for an ISO-named week passes [java.time.DayOfWeek.MONDAY] and gets exactly the
 * Monday-to-Sunday span it asked for; nothing here derives a week from an ISO week-number, because no
 * requirement in the specification asks a report to be identified by one.
 */
sealed class ReportPeriod {

    /** One local calendar day. */
    data class Day(val date: LocalDate) : ReportPeriod()

    /** The whole week the [anchor] falls in, from [firstDay] for seven days. */
    data class Week(val anchor: LocalDate, val firstDay: DayOfWeek) : ReportPeriod()

    /** The whole month, first day to last — February asks its own question of the year it is in. */
    data class Month(val month: YearMonth) : ReportPeriod()

    /**
     * The whole calendar year, January 1 to December 31.
     *
     * Held through [ReportPeriod.year], which is the one that refuses a year `java.time` cannot build.
     */
    data class Year internal constructor(val year: Int) : ReportPeriod()

    /**
     * A range the reader chose, both ends included, which is how a date picker answers "from the 3rd to
     * the 3rd" — one day, not nothing.
     *
     * Built by [ReportPeriod.range], the only public way to hold one: that is where a reversed pair is
     * refused rather than swapped, clamped or silently handed over as some other period.
     */
    data class Range internal constructor(val from: LocalDate, val to: LocalDate) : ReportPeriod()

    companion object {

        /** The week of [anchor], starting on the device's own first day of week. */
        fun weekOf(anchor: LocalDate): Week = Week(anchor, firstDayOfCurrentWeek())

        /**
         * A range from [from] to [to] inclusive, or the reason it is not a period at all.
         *
         * A reversed pair is refused instead of being sorted: a report whose "25–26 December" quietly
         * became "25–26 December of the reader's choosing the other way round" would print figures for a
         * window nobody asked about, and the numbers would look perfectly reasonable on the page.
         */
        fun range(from: LocalDate, to: LocalDate): ReportPeriodRead {
            if (to.isBefore(from)) {
                return ReportPeriodRead.Rejected(ReportPeriodRejection.ENDS_BEFORE_IT_STARTS, from, to)
            }
            return ReportPeriodRead.Accepted(Range(from, to))
        }

        /**
         * A year period, or the reason there is none.
         *
         * `Int` reaches far past what `java.time` will build a date for, and the alternative to refusing
         * is an exception from inside a report's arithmetic.
         *
         * The bound is the calendar's own stated extremes rather than an attempt at its private helper, and
         * the class is spelled out in full because this companion belongs to a type that has a nested `Year`
         * of its own: unqualified, `Year` means the period, which has no calendar limits to ask.
         */
        fun year(year: Int): ReportPeriodRead =
            if (year >= java.time.Year.MIN_VALUE && year <= java.time.Year.MAX_VALUE) {
                ReportPeriodRead.Accepted(Year(year))
            } else {
                ReportPeriodRead.Rejected(ReportPeriodRejection.YEAR_OUT_OF_CALENDAR_RANGE, null, null)
            }

        /** The device's first day of week, so a caller that means "the reader's week" says it once. */
        fun firstDayOfCurrentWeek(): DayOfWeek = com.taskplanner.data.calendar.CalendarMath.firstDayOfWeek()
    }
}

/**
 * What asking for a period returns: either the period, or the reason the question was not one.
 *
 * Named rather than nullable because a caller that receives `null` cannot tell an impossible range from
 * a mistake in its own code, and a screen has to say which.
 */
sealed class ReportPeriodRead {
    data class Accepted(val period: ReportPeriod) : ReportPeriodRead()
    data class Rejected(
        val reason: ReportPeriodRejection,
        val from: LocalDate?,
        val to: LocalDate?
    ) : ReportPeriodRead()
}

/** Why a period could not be built. Each case has exactly one sentence to print. */
enum class ReportPeriodRejection {
    /** The last day was named before the first. Nothing is swapped, clamped or guessed. */
    ENDS_BEFORE_IT_STARTS,

    /** The year is outside the calendar `java.time` covers. */
    YEAR_OUT_OF_CALENDAR_RANGE
}
