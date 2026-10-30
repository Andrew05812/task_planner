package com.taskplanner.ui.goals

import android.content.Context
import androidx.annotation.StringRes
import com.taskplanner.R
import com.taskplanner.data.model.GoalType
import com.taskplanner.data.model.GoalValueChange
import com.taskplanner.data.repository.GoalProgress
import com.taskplanner.data.repository.GoalRejection
import com.taskplanner.data.tracking.WorkTimeMath
import com.taskplanner.ui.util.WorkTimeFormat
import com.taskplanner.ui.util.LocalisedDates
import java.text.DateFormat
import java.time.ZoneId
import java.util.Locale

/**
 * The words one goal card prints, decided in one place.
 *
 * The card and the detail screen underneath it have to say the same thing about the same row: a list that
 * reads "3 of 5 finished" over a bar and a detail screen that explains the fifth task differently is two
 * answers to one question. Nothing here is stored — every sentence is read off the [GoalProgress] the
 * repository assembled, so a card cannot outlive the figure it was built from.
 *
 * The four types differ only in the unit the sentence names. That is the whole reason they are one row: the
 * figure and the target already came out of the aggregate the type asks for, so this object chooses the words
 * and never does arithmetic.
 */
object GoalCard {

    /** What the goal counts, in the heading above its figure. */
    @StringRes
    fun typeLabelRes(type: GoalType): Int = when (type) {
        GoalType.TASK_COUNT -> R.string.goal_type_task_count
        GoalType.TASK_SET -> R.string.goal_type_task_set
        GoalType.TIME_SPENT -> R.string.goal_type_time_spent
        GoalType.DEADLINE -> R.string.goal_type_deadline
    }

    /**
     * "3 of 5 finished", "1 h 20 m of 3 h measured".
     *
     * The target the repository hands over is the one the type actually answers to — for the two types that
     * name tasks it is the size of the named set when the user typed no figure — so the denominator printed
     * here is always the one the bar is drawn against.
     */
    fun figure(context: Context, progress: GoalProgress): String =
        if (progress.goal.type == GoalType.TIME_SPENT) {
            context.getString(
                R.string.goal_time_figure,
                WorkTimeFormat.compact(context, progress.counted),
                WorkTimeFormat.compact(context, progress.target)
            )
        } else {
            val pluralRes = if (progress.goal.type == GoalType.TASK_COUNT) {
                R.plurals.goal_count_figure
            } else {
                R.plurals.goal_set_figure
            }
            context.resources.getQuantityString(
                pluralRes,
                asCount(progress.target),
                asCount(progress.counted),
                asCount(progress.target)
            )
        }

    /**
     * The badge beside the figure, or null when the goal has nothing to badge.
     *
     * Three states are worth a word of their own, and the data keeps them apart: an aim with no figure set
     * yet (which is neither achieved nor behind), an aim reached, and an aim whose date passed while it was
     * still short. Everything else is a goal making progress, which is the ordinary case and needs no badge.
     */
    @StringRes
    fun stateLabelRes(progress: GoalProgress): Int? = when {
        progress.target <= 0L -> R.string.goal_state_no_target
        progress.reached -> R.string.goal_state_reached
        progress.late -> R.string.goal_state_late
        else -> null
    }

    /**
     * The window and the date a goal was set for, as one line.
     *
     * A goal with no window and no date prints nothing: the profile-wide case is the ordinary one, and a line
     * of dashes under every card would read as a field the user forgot to fill.
     */
    fun meta(context: Context, progress: GoalProgress): String? {
        val goal = progress.goal
        val parts = listOfNotNull(
            if (goal.windowFromDay != null || goal.windowToDay != null) {
                context.getString(
                    R.string.goal_window_format,
                    dayText(context, goal.windowFromDay),
                    dayText(context, goal.windowToDay)
                )
            } else {
                null
            },
            goal.deadlineAt?.let { context.getString(R.string.goal_deadline_format, dateText(it)) }
        )
        return parts.takeIf { it.isNotEmpty() }
            ?.joinToString(context.getString(R.string.goal_meta_joiner))
    }

    /**
     * What the named set is missing: tasks the goal still points at whose rows are gone.
     *
     * Reported rather than dropped, because the denominator counts them — a set of five with two deleted rows
     * reads "3 of 5", and a card that hid the two would leave the user looking for work that is not there to
     * do.
     */
    fun missingNote(context: Context, progress: GoalProgress): String? =
        progress.missing.takeIf { it > 0 }
            ?.let { context.resources.getQuantityString(R.plurals.goal_missing_note, it, it) }

    /** The share of the bar. Null means "no bar", which is what a goal with no figure yet needs. */
    fun fraction(progress: GoalProgress): Float? = progress.fraction

    /**
     * The words for a write the repository refused.
     *
     * Every refusal the enum can name has its own sentence, because the four differ in what the user has to
     * do next: sign in, go back to the list, fill the field in, or pick a different date. A single "could not
     * save" would make all four look like the same broken button.
     */
    @StringRes
    fun rejectionRes(reason: GoalRejection): Int = when (reason) {
        GoalRejection.NO_PROFILE -> R.string.goal_no_profile
        GoalRejection.NOT_FOUND -> R.string.goal_reject_not_found
        GoalRejection.BLANK_TITLE -> R.string.goal_reject_blank_title
        GoalRejection.NEGATIVE_TARGET -> R.string.goal_bad_target
        GoalRejection.WINDOW_ENDS_BEFORE_START -> R.string.goal_bad_dates
        GoalRejection.DEADLINE_WITHOUT_A_DATE ->
            R.string.goal_deadline_required
        GoalRejection.TASK_NOT_IN_PROFILE -> R.string.goal_reject_task
    }

    /**
     * One recorded move of one of the goal's own figures: "Figure: 40 → 20".
     *
     * The two values are printed as the words that were stored when the change was written, which is what
     * lets a row about a window or a date read back years later without knowing what type each field had at
     * the time. A value the history holds as null prints as the word for nothing rather than as an empty
     * space, because "Figure:  → 20" looks like a row that failed to print.
     */
    fun changeLine(context: Context, change: GoalValueChange): String = context.getString(
        R.string.goal_change_format,
        fieldLabel(context, change.field),
        valueText(context, change.field, change.oldValue),
        valueText(context, change.field, change.newValue)
    )

    /** When the aim moved, and why the user said it did — or the words for "they did not say". */
    fun changeWhen(context: Context, change: GoalValueChange): String = context.getString(
        R.string.goal_change_when_format,
        dateText(change.createdAt),
        change.reason?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.goal_change_no_reason)
    )

    /**
     * Which of the goal's figures a history row speaks about.
     *
     * A field this build has no name for prints the word that is stored. The history is append-only and an
     * older row can name a field a newer build renamed: showing the stored word is the only answer that
     * cannot quietly mean something else.
     */
    @StringRes
    private fun fieldLabelRes(field: String): Int? = when (field) {
        GoalValueChange.FIELD_TITLE -> R.string.goal_field_title
        GoalValueChange.FIELD_TYPE -> R.string.goal_field_type
        GoalValueChange.FIELD_TARGET -> R.string.goal_field_target
        GoalValueChange.FIELD_WINDOW -> R.string.goal_field_window
        GoalValueChange.FIELD_DEADLINE -> R.string.goal_field_deadline
        else -> null
    }

    private fun fieldLabel(context: Context, field: String): String {
        val labelRes = fieldLabelRes(field)
        return if (labelRes == null) field else context.getString(labelRes)
    }

    private fun valueText(context: Context, field: String, value: String?): String = when {
        value == null -> context.getString(R.string.goal_change_unset)
        field == GoalValueChange.FIELD_TYPE -> context.getString(typeLabelRes(GoalType.fromName(value)))
        value.isEmpty() -> context.getString(R.string.goal_change_unset)
        else -> value
    }

    /** One of the goal's own days in the reader's calendar, or the word for "any time up to now". */
    private fun dayText(context: Context, day: Long?): String = day?.let {
        dateFormat().format(
            WorkTimeMath.toDate(it).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        )
    } ?: context.getString(R.string.goal_window_open)

    private fun dateText(instant: Long): String = dateFormat().format(instant)

    private fun dateFormat(): DateFormat =
        DateFormat.getDateInstance(DateFormat.MEDIUM, LocalisedDates.locale())

    /** A count the platform can pluralise over; the ceiling is a figure no screen will reach. */
    private fun asCount(value: Long): Int = value.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
}
