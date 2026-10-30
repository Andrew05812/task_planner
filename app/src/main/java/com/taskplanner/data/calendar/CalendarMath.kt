package com.taskplanner.data.calendar

import com.taskplanner.ui.settings.AppLocale
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Month
import java.time.Year
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * Calendar window maths: which local days a grid shows and which instants a query must cover.
 *
 * The calendar screen counts in local days, the database stores epoch millis, and the two only
 * agree through a zone, so every translation between them runs through this one object on
 * java.time instead of through hand-converted hour counts scattered over view code. That is what
 * makes the grid survive the cases that break naive date maths: the two DST jumps a year, a month
 * that does not start on the week's first day, and a dragged task whose wall-clock time of day
 * must not drift by an hour every time the clock changes.
 *
 * No state, no Android API: the whole file is testable as plain maths.
 */
object CalendarMath {

    /**
     * The zone the calendar thinks in: the wall clock the user reads their deadlines on.
     *
     * Grids, windows and instants are all built against this one zone, so a day boundary and a
     * stored deadline can never disagree because two call sites picked their own clock.
     */
    fun systemZone(): ZoneId = ZoneId.systemDefault()

    /**
     * The week's first column as the device locale defines it, Monday in most of the world and
     * Sunday in the US.
     *
     * Hardcoding either opening day looks wrong to the other half of users, and the locale is the
     * setting that already knows the answer. Every grid builder takes it as a parameter defaulted
     * here, so a test or a future per-user preference can override one month grid without touching
     * the maths.
     */
    fun firstDayOfWeek(): DayOfWeek = WeekFields.of(AppLocale.current()).firstDayOfWeek

    /**
     * How many days a month has.
     *
     * Asked of java.time rather than a memorised table, because the century rule (2100 is not a
     * leap year) is exactly what a hand-written February length gets wrong, and the month grid's
     * cell count is derived from this.
     */
    fun daysInMonth(year: Int, month: Month): Int = month.length(Year.isLeap(year.toLong()))

    /**
     * Whole weeks covering the month: opening on the [firstDay] on or before the 1st and closing
     * on the day before the [firstDay] after the last day. Always a multiple of 7 cells, and the
     * neighbour months' leading and trailing days are part of the result.
     *
     * A grid that hid the neighbour days or cut the first and last weeks short would shift the
     * rows of days sideways between months, and the weekday columns would stop matching the
     * header. Callers that only show the current month filter these cells visually, never by
     * changing the geometry.
     */
    fun monthGrid(yearMonth: YearMonth, firstDay: DayOfWeek = firstDayOfWeek()): List<LocalDate> {
        val start = yearMonth.atDay(1).with(TemporalAdjusters.previousOrSame(firstDay))
        val endExclusive = yearMonth.atEndOfMonth().with(TemporalAdjusters.next(firstDay))
        val cells = ChronoUnit.DAYS.between(start, endExclusive).toInt()
        return (0 until cells).map { start.plusDays(it.toLong()) }
    }

    /**
     * The 7 days of the week containing [anchor], opening on [firstDay].
     *
     * The week view shows a whole week even when the user opens it on a Wednesday, so the screen
     * can be scrolled week by week without days appearing or vanishing mid-scroll.
     */
    fun weekGrid(anchor: LocalDate, firstDay: DayOfWeek = firstDayOfWeek()): List<LocalDate> {
        val start = anchor.with(TemporalAdjusters.previousOrSame(firstDay))
        return (0 until 7).map { start.plusDays(it.toLong()) }
    }

    /**
     * The first instant of a local day in [zone], as epoch millis.
     *
     * Delegates to java.time's atStartOfDay rather than assembling midnight from offsets, which
     * also settles the awkward case for us: when a spring-forward jump swallows the midnight hour
     * that day has no midnight, and the first instant after the gap is returned. Every window
     * built on this start then stays contiguous, so no day ends up with an empty or overlapping
     * window twice a year.
     */
    fun startOfDay(date: LocalDate, zone: ZoneId = systemZone()): Long =
        date.atStartOfDay(zone).toInstant().toEpochMilli()

    /**
     * Half-open window [start of day, start of next day) for [date], as the inclusive
     * [LongRange] the DAO range queries speak.
     *
     * A task due exactly at midnight belongs to the day that begins, not the one that ends, so the
     * end bound is written one millisecond before the next day's start. That makes `in window`
     * over consecutive days partition the whole time line: every instant falls in exactly one
     * day's window. On a shortened or lengthened DST day the span is 23 or 25 hours, which is
     * correct — the window covers what the user experiences as that day.
     */
    fun dayWindow(date: LocalDate, zone: ZoneId = systemZone()): LongRange =
        LongRange(startOfDay(date, zone), startOfDay(date.plusDays(1), zone) - 1)

    /**
     * Window covering every cell of [monthGrid] for [yearMonth], the neighbour months' days
     * included, so one range query fills a whole month grid.
     *
     * Deriving the bounds from the grid itself keeps the query and the screen in agreement even if
     * the week's first day changes: the window can be wider than the month but never narrower than
     * what is displayed, so no visible cell is ever filled from a stale range.
     */
    fun monthWindow(yearMonth: YearMonth, firstDay: DayOfWeek = firstDayOfWeek()): LongRange {
        val grid = monthGrid(yearMonth, firstDay)
        return LongRange(
            startOfDay(grid.first()),
            startOfDay(grid.last().plusDays(1)) - 1
        )
    }

    /**
     * The local day an instant falls on, in [zone].
     *
     * This is the only correct way to bucket a stored deadline onto a grid cell: comparing millis
     * or reading a UTC date would file a late-evening deadline under the wrong day for every user
     * whose zone is not the one the millis were taken in.
     */
    fun toLocalDate(millis: Long, zone: ZoneId = systemZone()): LocalDate =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

    /**
     * Minutes from the start of the local day, 0..1439 — the height a task sits at in the day
     * view's column.
     *
     * Read off the converted local time, never as millis since the day window's start: on a day
     * that began at 01:00 after a spring-forward, subtracting the window start would slide every
     * task up by the skipped hour and a 09:00 deadline would not line up with the 09:00 gridline.
     */
    fun minutesOfDay(millis: Long, zone: ZoneId = systemZone()): Int {
        val local = Instant.ofEpochMilli(millis).atZone(zone)
        return local.hour * 60 + local.minute
    }

    /**
     * The instant [millis] falls on once its wall-clock time of day is moved onto [target].
     *
     * Drag-and-drop between days is about the appointment, not the duration: a task at 21:30 on
     * Monday belongs at 21:30 on Thursday even across a DST change, where adding whole days of
     * millis lands at 20:30 or 22:30. If that exact minute sits inside a spring-forward gap it
     * does not exist on the target day, and the first instant after the gap is returned: the task
     * is then at least not earlier than the appointment the user dragged to. The question is not
     * delegated to LocalDateTime.atZone, which would slide the clock time itself by the length of
     * the gap and put a 00:30 task at 01:30 instead of at the 01:00 the day opens with. The
     * ambiguous hour of a fall-back day resolves to the earlier of its two instants, the first
     * pass of that clock time.
     */
    fun moveWallClock(millis: Long, target: LocalDate, zone: ZoneId = systemZone()): Long {
        val timeOfDay = Instant.ofEpochMilli(millis).atZone(zone).toLocalTime()
        val local = LocalDateTime.of(target, timeOfDay)
        val transition = zone.rules.getTransition(local)
        val instant = if (transition != null && transition.isGap) transition.instant
        else local.atZone(zone).toInstant()
        return instant.toEpochMilli()
    }
}
