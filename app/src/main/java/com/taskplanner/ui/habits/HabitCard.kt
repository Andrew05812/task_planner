package com.taskplanner.ui.habits

import android.content.Context
import android.util.TypedValue
import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import com.taskplanner.R
import com.taskplanner.data.habit.HabitMath
import com.taskplanner.data.habit.StreakUnit
import com.taskplanner.data.model.Habit
import com.taskplanner.data.model.HabitCheckChange
import com.taskplanner.data.model.HabitFrequency
import com.taskplanner.data.repository.HabitFigures
import com.taskplanner.data.repository.HabitRejection
import com.taskplanner.data.tracking.WorkTimeMath
import java.text.DateFormat
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

/**
 * The words one habit card prints, decided in one place.
 *
 * The list and the detail screen draw the same card over the same [HabitFigures], and the two have to say the
 * same thing about one row: a streak on the list and a streak under the grid that disagree would be two
 * answers to one question, which is exactly the failure the figures are computed to avoid. Nothing here does
 * arithmetic — the numbers all came from [HabitMath] through the repository — and nothing here is stored.
 *
 * The one window the screens do choose is [figurePeriodDays]: how far back "kept" is read. It is a question
 * the screen is allowed to ask, and the words beside the percentage say it out loud so the figure cannot be
 * read as the habit's whole life.
 */
object HabitCard {

    /** How many days a card looks back for its percentage: four weeks, the name the caption gives it. */
    const val figurePeriodDays: Long = 28L

    /** The window a card's percentage is read over, ending on [today]. */
    fun periodFrom(today: Long): Long = today - (figurePeriodDays - 1)

    /** What the habit is asked for, as one short heading. */
    @StringRes
    fun frequencyLabelRes(frequency: HabitFrequency): Int = when (frequency) {
        HabitFrequency.DAILY -> R.string.habit_type_daily
        HabitFrequency.DAYS_OF_WEEK -> R.string.habit_type_weekdays
        HabitFrequency.TIMES_PER_WEEK -> R.string.habit_type_times
    }

    /**
     * The schedule's own detail under that heading: the weekdays, or how many times a week.
     *
     * Null for a daily habit, which the heading already answers. A weekday habit prints its days because the
     * mask is the whole schedule, and a reader who sees "on chosen weekdays" without the days has not been
     * told anything they can check the grid against.
     */
    fun scheduleText(context: Context, habit: Habit): String? = when (habit.frequency) {
        HabitFrequency.DAILY -> null
        HabitFrequency.DAYS_OF_WEEK -> HabitMath.daysOf(habit.daysMask)
            .joinToString(context.getString(R.string.habit_weekday_joiner)) {
                it.getDisplayName(TextStyle.SHORT, Locale.getDefault())
            }
        HabitFrequency.TIMES_PER_WEEK -> context.resources.getQuantityString(
            R.plurals.habit_times_per_week,
            habit.timesPerWeek ?: 0,
            habit.timesPerWeek ?: 0
        )
    }

    /**
     * "12 days running", "3 weeks running", or the words for a series that has not begun.
     *
     * The unit comes with the streak rather than from the frequency, because it is the arithmetic that
     * decided what the number counts: a "three times a week" series is a run of weeks that each reached
     * their count, and printing it as days would claim a run of consecutive days nobody asked for.
     */
    fun streakText(context: Context, figures: HabitFigures): String {
        val streak = figures.streak
        if (!streak.isAlive) return context.getString(R.string.habit_streak_none)
        val unitRes = if (streak.unit == StreakUnit.WEEKS) R.plurals.habit_streak_weeks else R.plurals.habit_streak_days
        return context.resources.getQuantityString(unitRes, streak.current, streak.current)
    }

    /**
     * This week's "3 of 4", or null when the week has not been asked for yet.
     *
     * A habit that starts next month, and a weekday set that names nothing in this week, both leave the week
     * with no denominator. Printing "0 of 0" would read as a week the user failed rather than a week the
     * schedule never asked about.
     */
    fun weekText(context: Context, figures: HabitFigures): String? {
        val week = figures.thisWeek
        if (week.asked <= 0) return null
        return context.getString(R.string.habit_week_figure, week.kept, week.asked)
    }

    /** The share of the window that was kept, or null when the window asked for nothing. */
    fun keptText(context: Context, figures: HabitFigures): String? =
        figures.period.percent?.let { context.getString(R.string.habit_kept_format, it) }

    /**
     * The window the percentage was read over, said beside it.
     *
     * The figure is scoped by the read rather than by the habit's life, and a card that printed "75% kept"
     * alone would let it be read as a verdict on the whole habit. The words name the span the read answers
     * with in [HabitFigures.periodDays] rather than the length the screen requested: a habit younger than
     * the four weeks the list asks for is judged over the days it lived, and a caption that still said
     * "28 days" beside that percentage would name a window the figure never covered.
     */
    fun keptWindow(context: Context, figures: HabitFigures): String =
        context.resources.getQuantityString(
            R.plurals.habit_kept_window_format, figures.periodDays, figures.periodDays
        )

    /** The longest run the marks contain, or null while there is no run to speak of. */
    fun bestText(context: Context, figures: HabitFigures): String? {
        val best = figures.streak.best
        if (best <= 0) return null
        val unitRes =
            if (figures.streak.unit == StreakUnit.WEEKS) R.plurals.habit_best_weeks else R.plurals.habit_best_days
        return context.resources.getQuantityString(unitRes, best, best)
    }

    /** The days kept inside the window, as its own line — the numerator the percentage was drawn from. */
    fun marksText(context: Context, figures: HabitFigures): String =
        context.resources.getQuantityString(
            R.plurals.habit_marks_format, figures.marks, figures.marks
        )

    /**
     * The bar's two numbers, or null when there is nothing to bar.
     *
     * The same share the caption prints, split into kept and not kept rather than computed again: a bar and a
     * percentage under it have to be the one figure told twice.
     */
    fun share(figures: HabitFigures): Pair<Int, Int>? =
        figures.period.takeIf { it.asked > 0 }?.let { it.kept to (it.asked - it.kept).coerceAtLeast(0) }

    /**
     * The badge beside the streak, or null when the day has nothing to say.
     *
     * Only today is badged, because only today is still open: a kept day is a finished fact, and a day the
     * schedule did not ask for is not a miss. The two states are the ones a reader acts on — the habit is
     * done for today, or it is still owed.
     */
    @StringRes
    fun stateBadgeRes(owedToday: Boolean, markedToday: Boolean): Int? = when {
        markedToday -> R.string.habit_state_kept
        owedToday -> R.string.habit_state_owed
        else -> null
    }

    /** The colour the badge's word is worn in: the app's own done and to-do, borrowed from the task list. */
    @ColorRes
    fun stateColorRes(@StringRes labelRes: Int): Int = when (labelRes) {
        R.string.habit_state_kept -> R.color.status_done
        else -> R.color.priority_high
    }

    /** Whether the day is still owed an answer — the schedule asked for it and it is not kept. */
    fun isOwedOn(habit: Habit, day: Long, marked: Boolean): Boolean =
        habit.isOpen && HabitMath.isAskedOn(habit, day) && !marked

    /**
     * The window the habit covers, and the minute it is reminded at.
     *
     * A habit with no window and no reminder prints nothing: those are the ordinary case, and a line of
     * placeholders under every card would read as fields the user forgot to fill.
     */
    fun meta(context: Context, habit: Habit): String? {
        val parts = listOfNotNull(
            context.getString(R.string.habit_window_format, dayText(context, habit.startDay)),
            habit.endDay?.let { context.getString(R.string.habit_until_format, dayText(context, it)) },
            habit.remindMinuteOfDay?.let {
                context.getString(R.string.habit_reminder_format, timeText(it))
            }
        )
        return parts.joinToString(context.getString(R.string.habit_meta_joiner))
    }

    /** The day the habit began, always worth one word: nothing before it counts. */
    fun dayText(context: Context, day: Long): String = DateFormat.getDateInstance(
        DateFormat.MEDIUM,
        Locale.getDefault()
    ).format(WorkTimeMath.toDate(day).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli())

    /** A minute of the day, written the way the reader's locale writes a time. */
    fun timeText(minuteOfDay: Int): String =
        LocalTime.ofSecondOfDay(minuteOfDay * 60L).format(
            DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.getDefault())
        )

    /**
     * One correction the user made to a mark.
     *
     * The two kinds are different sentences: a day taken back leaves nothing, a day moved leaves the day it
     * landed on. Saying only "changed" would make the second read like the first, and the point of keeping
     * this history is that the grid and the story can be compared day by day.
     */
    fun changeLine(context: Context, change: HabitCheckChange): String =
        if (change.newLocalDay == null) {
            context.getString(R.string.habit_change_taken_back, dayText(context, change.oldLocalDay))
        } else {
            context.getString(
                R.string.habit_change_moved,
                dayText(context, change.oldLocalDay),
                dayText(context, change.newLocalDay)
            )
        }

    /** When the mark was corrected, and why the user said it was — or the words for "they did not say". */
    fun changeWhen(context: Context, change: HabitCheckChange): String = context.getString(
        R.string.habit_change_when_format,
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault())
            .format(change.createdAt),
        change.reason?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.habit_change_no_reason)
    )

    /**
     * The words for a write the repository refused.
     *
     * Every name the enum holds has its own sentence, because they differ in what the user does next — pick
     * another day, bring the habit back, sign in, fill the field in. One "could not save" would make all of
     * them look like the same broken button.
     */
    @StringRes
    fun rejectionRes(reason: HabitRejection): Int = when (reason) {
        HabitRejection.NO_PROFILE -> R.string.habit_no_profile
        HabitRejection.NOT_FOUND -> R.string.habit_reject_not_found
        HabitRejection.ARCHIVED -> R.string.habit_reject_archived
        HabitRejection.BLANK_TITLE -> R.string.habit_reject_blank_title
        HabitRejection.EMPTY_WEEKDAY_SET -> R.string.habit_reject_empty_weekdays
        HabitRejection.WEEKLY_COUNT_OUT_OF_RANGE -> R.string.habit_reject_weekly_count
        HabitRejection.WINDOW_ENDS_BEFORE_START -> R.string.habit_reject_bad_window
        HabitRejection.BAD_REMINDER_MINUTE -> R.string.habit_reject_bad_minute
        HabitRejection.DAY_OUTSIDE_WINDOW -> R.string.habit_reject_day_outside
        HabitRejection.DAY_IN_FUTURE -> R.string.habit_reject_day_future
        HabitRejection.ALREADY_MARKED -> R.string.habit_reject_already_marked
        HabitRejection.NO_SUCH_MARK -> R.string.habit_reject_no_mark
        HabitRejection.TARGET_DAY_TAKEN -> R.string.habit_reject_target_taken
    }

    /** A colour the theme owns, read out of it rather than copied into a resource. */
    fun themedColor(context: Context, attr: Int): Int {
        val value = TypedValue()
        context.theme.resolveAttribute(attr, value, true)
        return value.data
    }
}
