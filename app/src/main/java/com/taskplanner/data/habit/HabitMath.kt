package com.taskplanner.data.habit

import com.taskplanner.data.calendar.CalendarMath
import com.taskplanner.data.model.Habit
import com.taskplanner.data.model.HabitFrequency
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

/** What a habit's streak counts, and therefore what the number on the card is a number *of*. */
enum class StreakUnit {
    /** Consecutive days the habit asked for, each carrying a mark. */
    DAYS,

    /** Consecutive weeks that reached their count — the only series a "3 times a week" can have. */
    WEEKS
}

/**
 * A habit's series: how long it is running, and how long it ever ran.
 *
 * [current] is alive or broken as of the day the read was anchored on; [best] is the longest run the
 * marks in the same window ever produced. Both are computed from the mark days rather than stored, so
 * taking a mark back anywhere in the series changes the streak in the same read that changes the grid.
 */
data class HabitStreak(val current: Int, val best: Int, val unit: StreakUnit) {
    /** Whether anything is running: the screen says "no series yet" rather than "a series of nought". */
    val isAlive: Boolean get() = current > 0
}

/**
 * Share of what was asked for that was actually kept.
 *
 * [asked] counts the days (or weeks, for [HabitFrequency.TIMES_PER_WEEK]) the habit's own schedule
 * required inside the window the read was given, and [kept] those of them that carry a mark. A mark made
 * on a day the habit never asked for is in neither number: it is real work, and it is not an answer to a
 * question nobody asked, which is why it cannot raise a percentage.
 */
data class HabitAdherence(val kept: Int, val asked: Int) {
    /** Whole percent of the schedule kept, or null when the window asked for nothing at all. */
    val percent: Int?
        get() = if (asked <= 0) null else kept * 100 / asked
}

/**
 * The arithmetic of a habit: what its schedule asks for, and what the marks add up to.
 *
 * Everything here is a pure function of a [Habit] row and the set of days it was marked, for two reasons
 * that outlive this file. The first is the same one a goal has for storing no progress: a streak, an
 * adherence percentage and a week's "3 of 4" are consequences of the mark rows, and computing them at the
 * moment of the read is the only way they cannot disagree with the grid they are drawn beside. The second
 * is that these are the rules the user is told — a missed Sunday, an off-day mark, a week that is still
 * running — and a rule that lives in a query or in a screen is a rule nobody can test on its own.
 *
 * Days are epoch days throughout, the unit `habit_checks.local_day` is stored in. Converting them to
 * calendar dates is the caller's business, and it is done in the zone the caller reads the screen in.
 */
object HabitMath {

    /** The mask [Habit.daysMask] stores for these weekdays: one bit each, Monday in the lowest. */
    fun maskOf(days: Collection<DayOfWeek>): Int = days.fold(0) { mask, day -> mask or (1 shl day.ordinal) }

    /** The weekdays a stored mask names, in Monday-first order. */
    fun daysOf(mask: Int): List<DayOfWeek> =
        DayOfWeek.entries.filter { mask and (1 shl it.ordinal) != 0 }

    /** Whether [day] is one of the weekdays in [mask]. */
    fun isWeekdayIn(mask: Int, day: Long): Boolean =
        mask and (1 shl LocalDate.ofEpochDay(day).dayOfWeek.ordinal) != 0

    /**
     * Whether the habit's schedule required [day] at all.
     *
     * A day outside the window is never asked for: nothing before [Habit.startDay] counts, so a backdated
     * start cannot inflate a series, and a day after [Habit.endDay] belongs to a habit that stopped being
     * asked for. [HabitFrequency.TIMES_PER_WEEK] asks about a whole week rather than about a day, and it
     * is answered here as "any day of a covered week will do", which is what its streak and adherence
     * functions then take apart by week rather than by day.
     */
    fun isAskedOn(habit: Habit, day: Long): Boolean {
        if (!habit.covers(day)) return false
        return when (habit.frequency) {
            HabitFrequency.DAILY -> true
            HabitFrequency.DAYS_OF_WEEK -> isWeekdayIn(habit.daysMask, day)
            HabitFrequency.TIMES_PER_WEEK -> true
        }
    }

    /** The first day of the week that contains [anchorDay], in the calendar the user reads. */
    fun weekStart(anchorDay: Long, firstDay: DayOfWeek): Long {
        val back = (LocalDate.ofEpochDay(anchorDay).dayOfWeek.value - firstDay.value + 7) % 7
        return anchorDay - back
    }

    /** The whole week containing [anchorDay], inclusive at both ends. */
    fun weekRange(anchorDay: Long, firstDay: DayOfWeek): LongRange {
        val start = weekStart(anchorDay, firstDay)
        return start..start + DAYS_IN_WEEK - 1
    }

    /** Marks that fall inside the week containing [anchorDay]. */
    fun marksInWeek(marked: Set<Long>, anchorDay: Long, firstDay: DayOfWeek): Int =
        weekRange(anchorDay, firstDay).count { it in marked }

    /**
     * How many days the habit asked for inside `[from, to]`, and how many of those carry a mark.
     *
     * Only for the two day-shaped frequencies; [adherenceByWeek] answers the same question for a weekly
     * count, where a gap between marks is not a miss. The window belongs to the caller, which is what
     * keeps a five-year habit from being read over five years to print this month's figure.
     */
    fun adherenceByDay(habit: Habit, marked: Set<Long>, from: Long, to: Long): HabitAdherence {
        if (to < from) return HabitAdherence(0, 0)
        var asked = 0
        var kept = 0
        var day = maxOf(from, habit.startDay)
        val last = minOf(to, habit.endDay ?: Long.MAX_VALUE)
        while (day <= last) {
            if (isAskedOn(habit, day)) {
                asked++
                if (day in marked) kept++
            }
            day++
        }
        return HabitAdherence(kept, asked)
    }

    /**
     * The same share, counted in weeks: a week is kept when it holds [Habit.timesPerWeek] marks.
     *
     * Weeks that the habit covers at all are counted, including the one it started in and the one the
     * window ends inside — a habit begun on Wednesday still owes four runs that week, because the user
     * asked for four a week rather than for four a week once the calendar happened to turn over. That is
     * the reading a card has to be able to defend, so it is the one taken, and it is the reason the
     * screen labels the figure over the window it was given rather than over a whole number of weeks.
     */
    fun adherenceByWeek(
        habit: Habit,
        marked: Set<Long>,
        from: Long,
        to: Long,
        firstDay: DayOfWeek
    ): HabitAdherence {
        val target = habit.timesPerWeek ?: return HabitAdherence(0, 0)
        if (to < from) return HabitAdherence(0, 0)
        var asked = 0
        var kept = 0
        var week = weekStart(from, firstDay)
        while (week <= to) {
            if (weekAsked(habit, week)) {
                asked++
                if (marksInWeek(marked, week, firstDay) >= target) kept++
            }
            week += DAYS_IN_WEEK
        }
        return HabitAdherence(kept, asked)
    }

    /**
     * The share kept, in whichever unit the frequency measures it in.
     *
     * The one entry point the screens and the repository use, so that neither has to know that a weekly
     * count asks about weeks while the two day-shaped frequencies ask about days — the distinction that
     * makes a five-day gap in a "3 times a week" mean nothing and a five-day gap in a daily habit mean
     * everything.
     */
    fun adherence(
        habit: Habit,
        marked: Set<Long>,
        from: Long,
        to: Long,
        firstDay: DayOfWeek = CalendarMath.firstDayOfWeek()
    ): HabitAdherence = when (habit.frequency) {
        HabitFrequency.TIMES_PER_WEEK -> adherenceByWeek(habit, marked, from, to, firstDay)
        else -> adherenceByDay(habit, marked, from, to)
    }

    /**
     * The series as the caller's window prints it, in the unit the frequency makes it meaningful in.
     *
     * [best] is the longest run the marks in [marked] contain, so the set has to reach back as far as the
     * screen wants the history to; [current] only ever walks back from [today] and stops at the first
     * break, which is why a window shorter than the series can only ever say "at least". A reader's whole
     * history is therefore a wider question than a card's, and it is asked as one elsewhere rather than by
     * widening this window: an achievement reads the same function over the habit's life, while a card names
     * the four weeks it looked at.
     */
    fun streak(
        habit: Habit,
        marked: Set<Long>,
        today: Long,
        firstDay: DayOfWeek = CalendarMath.firstDayOfWeek()
    ): HabitStreak = when (habit.frequency) {
        HabitFrequency.TIMES_PER_WEEK -> weekStreak(habit, marked, today, firstDay)
        else -> HabitStreak(
            current = currentDayStreak(habit, marked, today),
            best = bestDayStreak(habit, marked),
            unit = StreakUnit.DAYS
        )
    }

    /**
     * The series running as of [today], counted in asked days.
     *
     * Two rules do the work. A day the schedule never asked for is skipped rather than treated as a gap,
     * so a Monday-Wednesday-Friday habit reads a run of six over two weeks instead of breaking every
     * weekend. And an asked-for day that is *today* and still unmarked does not break the run either: the
     * day has hours left, and a card that dropped to zero at 00:01 would tell the user they had failed
     * something they had not yet had the chance to keep. Once today is over — the day after, when the
     * walk starts from a day that is not today — the same empty day is the break.
     */
    fun currentDayStreak(habit: Habit, marked: Set<Long>, today: Long): Int {
        var cursor: Long? = lastAskedOnOrBefore(habit, today) ?: return 0
        // The day still open is not a gap: start the walk one asked day earlier and let the loop below
        // decide whether that one was kept.
        if (cursor == today && today !in marked) cursor = lastAskedBefore(habit, today)
        var count = 0
        while (cursor != null && cursor in marked) {
            count++
            cursor = lastAskedBefore(habit, cursor)
        }
        return count
    }

    /**
     * The longest run of consecutive asked days the marks contain.
     *
     * Only asked days are walked, and a day the schedule never named is passed over rather than counted as
     * a break — the same rule that lets [currentDayStreak] read a Monday-Wednesday-Friday run of six across
     * two weekends. A mark made on a day nobody asked for is in neither number here either: it is real work
     * and it is not an answer to this habit's question.
     */
    fun bestDayStreak(habit: Habit, marked: Set<Long>): Int {
        var best = 0
        var run = 0
        for (day in marked.asSequence().filter { isAskedOn(habit, it) }.sorted()) {
            val previous = lastAskedBefore(habit, day)
            run = if (previous != null && previous in marked) run + 1 else 1
            if (run > best) best = run
        }
        return best
    }

    /**
     * The series of weeks that reached their count.
     *
     * A weekly habit has no day series to have: three visits spread over a week keep it and a missed
     * Tuesday among them means nothing, so the only run it can be in is a run of kept weeks. The current
     * week is allowed to be unfinished for the same reason today is allowed to be unmarked in a daily
     * habit — it has not ended yet — and the walk then counts the weeks behind it.
     */
    fun weekStreak(
        habit: Habit,
        marked: Set<Long>,
        today: Long,
        firstDay: DayOfWeek
    ): HabitStreak {
        val target = habit.timesPerWeek ?: return HabitStreak(0, 0, StreakUnit.WEEKS)
        val currentWeek = weekStart(today, firstDay)
        var week = currentWeek
        // This week has days left in it, so an unfinished one is not a break: the walk drops to the week
        // that has already ended and counts back from there, and the current week joins its own run only
        // once it has earned the marks.
        if (!weekKept(habit, marked, week, firstDay, target)) week -= DAYS_IN_WEEK
        var count = 0
        while (weekAsked(habit, week) && weekKept(habit, marked, week, firstDay, target)) {
            count++
            week -= DAYS_IN_WEEK
        }
        return HabitStreak(count, bestWeekRun(habit, marked, currentWeek, firstDay), StreakUnit.WEEKS)
    }

    /**
     * What the week containing [day] asks for, and how many marks it already holds.
     *
     * "3 of 4" is the sentence a habit card prints beside its streak, and the four means a different thing
     * for every frequency: seven days for a daily habit, the weekdays the mask names for a weekday habit,
     * and the count the user typed for [HabitFrequency.TIMES_PER_WEEK] — where which days carry the marks is
     * the user's choice and only the total is asked for. The week is clamped to the habit's own window in
     * the day-shaped cases by [adherenceByDay], and a weekly count only ever counts marks on days the habit
     * covers, so a mark left over from before a backdated start cannot fill this week.
     */
    fun weekProgress(habit: Habit, marked: Set<Long>, day: Long, firstDay: DayOfWeek): HabitAdherence {
        val range = weekRange(day, firstDay)
        val target = habit.timesPerWeek
        if (habit.frequency == HabitFrequency.TIMES_PER_WEEK && target != null) {
            val kept = range.count { it in marked && habit.covers(it) }
            return HabitAdherence(kept, target)
        }
        return adherenceByDay(habit, marked, range.first, range.last)
    }

    /** Whether the week containing [day] holds enough marks for the habit's count. */
    fun weekKept(
        habit: Habit,
        marked: Set<Long>,
        day: Long,
        firstDay: DayOfWeek,
        target: Int = habit.timesPerWeek ?: 0
    ): Boolean {
        if (target <= 0) return false
        return marksInWeek(marked, day, firstDay) >= target
    }

    /**
     * The minute of the day a habit reminder is owed at, as the instant it falls on [day].
     *
     * Resolved as a wall clock rather than as an offset from midnight, because on a day that springs
     * forward the chosen minute can be inside the skipped hour: the first instant after the gap is
     * returned, which is at or after the minute the user asked for rather than an hour before it. The
     * same rule [CalendarMath.moveWallClock] gives a dragged task.
     */
    fun reminderInstant(habit: Habit, day: Long, zone: ZoneId = CalendarMath.systemZone()): Long? {
        val minute = habit.remindMinuteOfDay ?: return null
        val date = LocalDate.ofEpochDay(day)
        val local = date.atTime(minute / MINUTES_IN_HOUR, minute % MINUTES_IN_HOUR)
        val transition = zone.rules.getTransition(local)
        val instant = if (transition != null && transition.isGap) transition.instant else local.atZone(zone).toInstant()
        return instant.toEpochMilli()
    }

    /**
     * The next instant this habit's reminder belongs at, or null when it is not owed one at all.
     *
     * A habit whose minute has passed, or whose day has not arrived yet, still has to be armed for the
     * next time it is asked for, and the search is over days rather than over arithmetic on millis: a
     * week-long scan covers the worst schedule there is — a habit named for one weekday — with a day to
     * spare, and stops at the habit's own end so a habit that finished is never armed again.
     */
    fun nextReminderInstant(
        habit: Habit,
        now: Long,
        zone: ZoneId = CalendarMath.systemZone()
    ): Long? {
        if (habit.remindMinuteOfDay == null || !habit.isOpen) return null
        var day = CalendarMath.toLocalDate(now, zone).toEpochDay()
        repeat(MAX_REMINDER_SCAN_DAYS) {
            val instant = reminderInstant(habit, day, zone)
            if (instant != null && instant > now && isReminderDay(habit, day)) return instant
            day++
        }
        return null
    }

    /**
     * Whether [day] is a day this habit should ask to be reminded on.
     *
     * It is the schedule question only: whether the day is one the habit is asked for and the habit has
     * not ended. Whether a mark already stands there — the other half of "is a reminder still owed" — is
     * answered by the caller, because it needs the marks, and this object keeps to arithmetic that no
     * database can get wrong.
     */
    fun isReminderDay(habit: Habit, day: Long): Boolean =
        habit.isOpen && habit.covers(day) && isAskedOn(habit, day)

    /**
     * The longest run of kept weeks the marks contain.
     *
     * The scan is over weeks rather than marks, because that is the unit the frequency counts in. A week
     * the habit was not asked for at all — before it began or after it ended — neither keeps a run nor
     * breaks one: a habit resumed after a year away does not carry the gap into its best figure the way a
     * daily habit's day walk would, because there was no schedule to miss during the time it did not
     * exist. The far end is capped at the week being read from, so a habit with no [Habit.endDay] does not
     * walk the calendar to the year 9999 to answer a question about the marks that exist now.
     */
    private fun bestWeekRun(
        habit: Habit,
        marked: Set<Long>,
        currentWeek: Long,
        firstDay: DayOfWeek
    ): Int {
        val target = habit.timesPerWeek ?: return 0
        val end = minOf(habit.endDay ?: currentWeek, currentWeek)
        var best = 0
        var run = 0
        var week = weekStart(habit.startDay, firstDay)
        while (week <= end) {
            if (weekAsked(habit, week)) {
                if (weekKept(habit, marked, week, firstDay, target)) {
                    run++
                    if (run > best) best = run
                } else {
                    run = 0
                }
            }
            week += DAYS_IN_WEEK
        }
        return best
    }

    /** Whether the week beginning [weekStartDay] holds any day the habit was asked for. */
    private fun weekAsked(habit: Habit, weekStartDay: Long): Boolean =
        (weekStartDay..weekStartDay + DAYS_IN_WEEK - 1).any { habit.covers(it) }

    /** The latest day at or before [day] the schedule asked for, or null before the habit begins. */
    private fun lastAskedOnOrBefore(habit: Habit, day: Long): Long? {
        val limit = minOf(day, habit.endDay ?: day)
        if (limit < habit.startDay) return null
        var candidate = limit
        while (candidate >= habit.startDay) {
            if (isAskedOn(habit, candidate)) return candidate
            candidate--
        }
        return null
    }

    /** The latest asked day strictly before [day]. */
    private fun lastAskedBefore(habit: Habit, day: Long): Long? =
        if (day <= habit.startDay) null else lastAskedOnOrBefore(habit, day - 1)

    private const val DAYS_IN_WEEK = 7
    private const val MINUTES_IN_HOUR = 60

    /** How far ahead a reminder is looked for: one full week, plus the day that proves it wrapped. */
    private const val MAX_REMINDER_SCAN_DAYS = 8
}
