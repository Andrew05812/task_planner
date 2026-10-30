package com.taskplanner.data.statistics

import com.taskplanner.ui.settings.AppLocale
import com.taskplanner.data.model.DailyCompletionCount
import com.taskplanner.data.model.DailyWorkSummary
import com.taskplanner.data.model.TaskHealthCounts
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * The shapes the dashboard's charts draw.
 *
 * Nothing here is stored and nothing here reads a database: these are the figures SQLite answered,
 * arranged the way a canvas needs them. Keeping the arrangement in its own object is what lets the
 * screens be tested by calling a function instead of inflating a view.
 */

/** One day of the activity grid. */
data class HeatmapCell(
    val localDay: Long,
    val seconds: Long,
    val completions: Int,
    /** 0 for a day with no measured work, 1 to 4 for the quartiles above it. */
    val level: Int,
    /** A day that has not happened yet — drawn as nothing rather than as an empty day. */
    val isFuture: Boolean
)

/** The grid as columns of whole weeks, oldest first, each column seven cells top to bottom. */
data class HeatmapGrid(
    val weeks: List<List<HeatmapCell>>,
    val busiestDaySeconds: Long
)

/** One bar of the period trend: a day that produced something, and what it produced. */
data class TrendDay(
    val localDay: Long,
    val seconds: Long,
    val completions: Int
)

/** Which way a figure moved against the same figure of the period before it. */
enum class ChangeDirection { UP, DOWN, FLAT }

/**
 * One period's figure beside the same figure of the period that came before it.
 *
 * The percentage is a `Double?` and not a `Double` because of the one case that has no percentage: a
 * period that follows an empty one. "Twice as much as nothing" and "an infinite increase" are both said
 * by no figure, and a chart that printed 100% or 0% there would be reporting a change the base cannot
 * support — which is the mistake this whole type exists to prevent.
 */
data class FigureChange(
    val current: Long,
    val previous: Long,
    val direction: ChangeDirection,
    /** [current] over [previous] minus one, or null when [previous] has nothing to divide by. */
    val fraction: Double?
)

/**
 * The states a health panel divides its open tasks into, in the order its bar shows them.
 *
 * Four of these are what SQLite counts directly; [DUE_LATER] is what is left of the open rows once the
 * late, today and coming-week buckets have taken theirs, and it exists precisely because a bar needs a
 * part for a task due next month. [WITHOUT_DEADLINE] is a task that is neither late nor on time, and is
 * shown as its own part rather than being counted as either.
 */
enum class HealthBucket { OVERDUE, DUE_TODAY, DUE_THIS_WEEK, DUE_LATER, WITHOUT_DEADLINE }

/** One bucket of the health panel and the open tasks it holds. */
data class HealthBucketCount(val bucket: HealthBucket, val count: Int)

/**
 * The arithmetic between an aggregate and a picture.
 *
 * Three rules, all of them about the same failure: a chart that lies politely.
 *
 *  * **No invented days.** A trend shows the days that were worked, not a zero-filled run across the
 *    period. A gap between two bars is the truth about when work happened; a bar of height zero drawn
 *    every day would turn "nothing measured" into something the eye reads as data.
 *  * **A day that has not happened is not an empty day.** The grid is a rectangle of whole weeks, so the
 *    current week's tail falls after today. Those cells are marked and left unshaded — an empty square
 *    says "you did nothing that day", which is a claim about the future — and they are kept out of the
 *    scale as well, so a row that cannot exist yet cannot make the real days look light.
 *  * **Nothing divides by the busiest day when there isn't one.** Every normalisation answers zeroes when
 *    the account has measured nothing, so a fresh profile sees an empty chart rather than a crash or a
 *    row of full-height bars.
 */
object DashboardMath {

    /** Completions per local day, from the instants the dashboard was given. */
    fun completionCounts(instants: List<Long>, zone: ZoneId): List<DailyCompletionCount> =
        instants.asSequence()
            .map { epochDayOf(it, zone) }
            .groupBy { it }
            .map { (day, values) -> DailyCompletionCount(localDay = day, count = values.size) }
            .sortedBy { it.localDay }
            .toList()

    /** The epoch day [millis] falls on in [zone] — the same translation the tracker used when it stored. */
    fun epochDayOf(millis: Long, zone: ZoneId): Long =
        java.time.Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().toEpochDay()

    /**
     * The bars of a period: the days that produced work or completions, oldest first.
     *
     * A union rather than the work list alone, because a day on which the user finished a task without
     * the tracker running is a day the chart should show — and a day worked without anything being
     * finished is shown too.
     */
    fun trend(
        worked: List<DailyWorkSummary>,
        completed: List<DailyCompletionCount>
    ): List<TrendDay> {
        val days = (worked.map { it.localDay } + completed.map { it.localDay }).toSortedSet()
        val seconds = worked.associate { it.localDay to it.seconds }
        val completions = completed.associate { it.localDay to it.count }
        return days.map { day ->
            TrendDay(
                localDay = day,
                seconds = seconds[day] ?: 0L,
                completions = completions[day] ?: 0
            )
        }
    }

    /**
     * The epoch days a grid of [weeks] whole weeks covers, ending with the week that holds [today].
     *
     * The repository asks SQLite for exactly this range, so the picture and the query cannot drift: a
     * grid drawn from a window the reader did not ask for would show days nobody counted.
     */
    fun heatmapRange(today: LocalDate, weeks: Int, firstDay: DayOfWeek): LongRange {
        val thisWeekStart = weekStart(today, firstDay).toEpochDay()
        val firstCell = thisWeekStart - (weeks - 1).toLong() * 7L
        return firstCell until firstCell + weeks.toLong() * 7L
    }

    /**
     * The window of the same length that ends the day before [days] begins.
     *
     * A period's own range is a whole calendar unit — a week is seven days and a month is the month, not
     * the month so far — so for a day and a week this lands exactly on the period a reader would name: the
     * day before today, the week before this one. For a month it lands on the same *number* of days
     * before, which is not the whole of the month before when the two months differ in length. That is the
     * point: a percentage between a thirty-day month and a thirty-one-day month would be measuring the
     * calendar as much as the work, and the one thing a comparison has to be before its percentage means
     * anything is two windows of the same size. The two share no day and no millisecond.
     */
    fun preceding(days: LongRange): LongRange {
        val length = days.last - days.first + 1L
        return (days.first - length) until days.first
    }

    /**
     * One figure read against the same figure of the window before it.
     *
     * Two cases have no percentage, and printing one would be the dashboard's most convincing kind of lie:
     *
     *  * **An empty period before a full one.** Any increase over zero is an infinite one; the honest
     *    statement is that there is no base, not that the account grew by a figure the arithmetic cannot
     *    produce.
     *  * **Zero before zero.** Two periods with nothing in them did not stay the same — there was nothing
     *    to stay. The direction is flat and the fraction is null, and the screen leaves the line out
     *    rather than reporting a comparison of two absences.
     *
     * A fall to zero *does* have a figure: nothing measured this period against something measured before
     * it is a hundred percent down, which is the whole of the truth rather than part of it.
     */
    fun change(current: Long, previous: Long): FigureChange = FigureChange(
        current = current,
        previous = previous,
        direction = when {
            current > previous -> ChangeDirection.UP
            current < previous -> ChangeDirection.DOWN
            else -> ChangeDirection.FLAT
        },
        fraction = if (previous <= 0L) null
        else (current - previous).toDouble() / previous.toDouble()
    )

    /**
     * The activity grid: [weeks] whole weeks ending with the one that holds [today].
     *
     * Anchored on the calendar rather than on the data, because the picture is a rectangle of days and
     * the user reads it as one: the column on the right is this week whatever was measured in it.
     * [firstDay] is the locale's, so the top row is Monday for the users who count weeks from Monday.
     */
    fun heatmap(
        worked: List<DailyWorkSummary>,
        completed: List<DailyCompletionCount>,
        today: LocalDate,
        weeks: Int,
        firstDay: DayOfWeek = WeekFields.of(AppLocale.current()).firstDayOfWeek
    ): HeatmapGrid {
        val seconds = worked.associate { it.localDay to it.seconds }
        val completions = completed.associate { it.localDay to it.count }
        val todayDay = today.toEpochDay()
        // The scale comes from days that have happened. A cell after today is left blank rather than drawn
        // as an empty day, so a row that cannot legitimately exist yet must not also decide how full every
        // square of the grid looks.
        val busiest = seconds.filterKeys { it <= todayDay }.values.maxOrNull() ?: 0L

        val cells = heatmapRange(today, weeks, firstDay).map { day ->
            val measured = seconds[day] ?: 0L
            val isFuture = day > todayDay
            HeatmapCell(
                localDay = day,
                seconds = measured,
                completions = completions[day] ?: 0,
                level = if (isFuture) 0 else levelOf(measured, busiest),
                isFuture = isFuture
            )
        }

        return HeatmapGrid(
            weeks = cells.chunked(7),
            busiestDaySeconds = busiest
        )
    }

    /** The first day of the week that contains [anchor], in the calendar the user reads. */
    fun weekStart(anchor: LocalDate, firstDay: DayOfWeek): LocalDate {
        val back = (anchor.dayOfWeek.value - firstDay.value + 7) % 7
        return anchor.minusDays(back.toLong())
    }

    /**
     * Which shade of the scale a day's work is: quartiles of the busiest day, and never above 4.
     *
     * A day with any work at all is at least level 1, so a light day stays visible; a grid in which
     * nothing was measured has no busiest day, and every cell is level 0 — an empty picture, correctly.
     * A day that lands exactly on a quartile boundary closes the band below it rather than opening the
     * one above, which is the convention the bounds tests hold: half of the busiest day is a two, and one
     * second more is a three.
     */
    fun levelOf(seconds: Long, busiest: Long): Int = when {
        seconds <= 0L || busiest <= 0L -> 0
        else -> minOf(4, ((seconds.toDouble() / busiest.toDouble()) * 4.0).ceilToInt())
    }

    /** Heights for a bar chart, as fractions of the tallest bar; a chart with no data is all zeroes. */
    fun fractions(values: List<Long>): List<Float> {
        val tallest = values.maxOrNull() ?: 0L
        if (tallest <= 0L) return values.map { 0f }
        return values.map { (it.toDouble() / tallest.toDouble()).toFloat().coerceIn(0f, 1f) }
    }

    /**
     * How many days running the user has been measured, counting back from [today].
     *
     * A streak that stopped yesterday is still alive when the day has barely begun — that is the whole
     * reason the figure is computed from the set of worked days rather than asked to end on today, and
     * why a profile that worked yesterday and not yet today reads 1 rather than 0.
     */
    fun streak(worked: List<DailyWorkSummary>, today: LocalDate): Int {
        val days = worked.filter { it.seconds > 0L }.map { it.localDay }.toSet()
        if (days.isEmpty()) return 0
        val todayDay = today.toEpochDay()
        val start = when {
            days.contains(todayDay) -> todayDay
            days.contains(todayDay - 1) -> todayDay - 1
            else -> return 0
        }
        var count = 0
        var day = start
        while (days.contains(day)) {
            count++
            day--
        }
        return count
    }

    /**
     * Share of the account that is finished, or null when there is nothing to share out.
     *
     * Null rather than 0%: a profile with no tasks has not completed none of anything, and a screen that
     * printed "0%" would be reporting a figure the data does not support.
     */
    fun completionRate(completed: Int, total: Int): Float? =
        if (total <= 0) null else completed.toFloat() / total.toFloat()

    /**
     * The open tasks sorted into the five parts of one health bar.
     *
     * Four of the five are counted by SQLite; [HealthBucket.DUE_LATER] is the remainder, and it is derived
     * here rather than asked for because the question it answers — what is open but not due in the coming
     * week — is exactly the difference between the panel's own total and the buckets it lists. The
     * remainder is floored at zero: a database that somehow reports more in its buckets than in its count
     * should produce a short bar, not a negative part that steals width from the ones beside it.
     *
     * `in progress` and `stale` are deliberately absent. Both cut across the deadline buckets — a task can
     * be late and being worked on at once — and a bar whose parts overlap would add up to more than the
     * whole it claims to divide. The screen shows those two as figures of their own instead.
     */
    fun healthBuckets(counts: TaskHealthCounts): List<HealthBucketCount> {
        val named = counts.overdue + counts.dueToday + counts.dueThisWeek + counts.withoutDeadline
        return listOf(
            HealthBucketCount(HealthBucket.OVERDUE, counts.overdue),
            HealthBucketCount(HealthBucket.DUE_TODAY, counts.dueToday),
            HealthBucketCount(HealthBucket.DUE_THIS_WEEK, counts.dueThisWeek),
            HealthBucketCount(HealthBucket.DUE_LATER, (counts.open - named).coerceAtLeast(0)),
            HealthBucketCount(HealthBucket.WITHOUT_DEADLINE, counts.withoutDeadline)
        )
    }

    /** `ceil` without pulling in `kotlin.math` for one call in a pure object. */
    private fun Double.ceilToInt(): Int {
        val floor = this.toInt()
        return if (this > floor.toDouble()) floor + 1 else floor
    }
}
