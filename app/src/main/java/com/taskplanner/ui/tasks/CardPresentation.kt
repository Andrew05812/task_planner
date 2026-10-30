package com.taskplanner.ui.tasks

import android.content.Context
import com.taskplanner.R
import com.taskplanner.data.model.Priority
import com.taskplanner.data.model.RecurrenceRule
import com.taskplanner.data.model.TaskStatus
import com.taskplanner.ui.util.LocalisedDates
import java.util.Calendar
import kotlin.math.abs

/**
 * What a card says about a task, decided from the row's own data and nothing else.
 *
 * This is the whole of the display layer's arithmetic, deliberately separate from the adapter so the
 * boundary the spec draws can be tested: every field here is derived from a field the row already carries,
 * nothing is written anywhere, and `now` arrives from outside rather than from the device clock — which is
 * what lets a test name the one minute where a deadline becomes late.
 *
 * The overdue predicate is the one the dashboard already owns — `TaskStatisticsDao.healthCounts` counts the
 * OVERDUE bucket as `due_date IS NOT NULL AND due_date < :now` over rows that are not completed — and it is
 * not restated differently here on purpose: a task the dashboard calls late cannot be a task the list calls
 * on time. The `status != COMPLETED` clause is that same population rule, and it is why a finished task
 * with a past deadline is not painted late.
 *
 * Line B's date is relative ("Today 18:00", "3 days late", "No due date") and carries no series wording:
 * spec §4.1 moves "this repeats" to the icon on line A. The rate is not lost, only relocated — it is what
 * the icon's `contentDescription` says, in the same words the due line used to print.
 */
data class CardPresentation(
    val dueLine: CharSequence,
    val isOverdue: Boolean,
    val statusLabelRes: Int,
    val pillBackgroundRes: Int,
    val pillTextRes: Int,
    /** null means no rule is drawn at all, which is how LOW reads: not urgent, not blank. */
    val priorityRuleColorRes: Int?,
    val showStart: Boolean,
    val showPause: Boolean,
    val showResume: Boolean,
    val showComplete: Boolean,
    /** null when the row is not an instance of a series, which is most rows. */
    val repeatDescriptionRes: Int?
) {
    companion object {
        /** The category name is drawn only when it has this much room; below it the dot stands alone. */
        const val CATEGORY_NAME_MIN_DP = 48

        fun of(context: Context, row: TasksListItem.TaskRow, now: Long): CardPresentation {
            val task = row.task
            // Read once into a local: `task.dueDate != null && task.dueDate < now` smart-casts only while
            // the property and its getter are in the same module, and a local makes the predicate read the
            // way the DAO's SQL reads — one value, compared once.
            val due = task.dueDate
            val overdue = due != null && due < now && task.status != TaskStatus.COMPLETED
            return CardPresentation(
                dueLine = dueLine(context, due, now, overdue),
                isOverdue = overdue,
                statusLabelRes = when (task.status) {
                    TaskStatus.TODO -> R.string.status_todo
                    TaskStatus.IN_PROGRESS -> R.string.status_in_progress
                    TaskStatus.PAUSED -> R.string.status_paused
                    TaskStatus.COMPLETED -> R.string.status_done
                },
                pillBackgroundRes = when (task.status) {
                    TaskStatus.TODO -> R.drawable.bg_pill_neutral
                    TaskStatus.IN_PROGRESS -> R.drawable.bg_pill_accent
                    TaskStatus.PAUSED -> R.drawable.bg_pill_warning
                    TaskStatus.COMPLETED -> R.drawable.bg_pill_success
                },
                pillTextRes = when (task.status) {
                    TaskStatus.TODO -> R.color.ink_text_secondary
                    TaskStatus.IN_PROGRESS -> R.color.ink_accent
                    TaskStatus.PAUSED -> R.color.ink_warning
                    TaskStatus.COMPLETED -> R.color.ink_success
                },
                priorityRuleColorRes = when (task.priority) {
                    Priority.HIGH -> R.color.ink_error
                    Priority.MEDIUM -> R.color.ink_warning
                    Priority.LOW -> null
                },
                showStart = task.status == TaskStatus.TODO,
                showPause = task.status == TaskStatus.IN_PROGRESS,
                showResume = task.status == TaskStatus.PAUSED,
                showComplete = task.status != TaskStatus.COMPLETED,
                repeatDescriptionRes = row.series?.let { seriesLabelRes(it) }
            )
        }

        /**
         * The words the repeat icon says when asked: the rate the series runs at, or its own interruption.
         *
         * These are the same strings the due line printed before line A took the icon over, so moving the
         * marker does not move the information — a reader who asks the icon hears "weekly", exactly as a
         * reader of the old card read it beside the date.
         */
        fun seriesLabelRes(rule: RecurrenceRule): Int = when {
            rule.paused -> R.string.series_short_paused
            rule.endedAt != null -> R.string.series_short_ended
            else -> RepeatPreset.of(rule).shortLabelRes
        }

        /**
         * The line under a title that says when the task is due.
         *
         * Neither a pattern nor a formatter is held here, and both omissions are load-bearing. A
         * `SimpleDateFormat` kept in a static field goes on printing the locale the process started in after
         * the reader moves to another one — the finding lint names `ConstantLocale`. The `"MMM dd, yyyy"`
         * literal that used to sit here was the worse half of the same mistake: not a formatter gone stale,
         * but an American word order written into every language, which survived the Stage 14 translation
         * because the month *word* did change. A Russian card read «нояб. 01, 2026» until a device photograph
         * caught it. [LocalisedDates] asks the reader's language for a date pattern on every call.
         */
        private fun dueLine(context: Context, dueDate: Long?, now: Long, overdue: Boolean): CharSequence {
            if (dueDate == null) return context.getString(R.string.no_due_date)
            val days = daysBetween(now, dueDate)
            val time = LocalisedDates.time(dueDate)
            return when {
                // A deadline that passed at 09:00 and is read at 18:00 is nine hours late and zero calendar
                // days late, and "0 days late" is a sentence about nothing. The day count only starts to
                // mean something once the date has actually rolled over.
                overdue && days == 0L -> context.getString(R.string.task_late_today)
                overdue -> context.resources.getQuantityString(
                    R.plurals.task_late_days, abs(days).toInt(), abs(days).toInt()
                )
                days == 0L -> context.getString(R.string.task_due_today_format, time)
                days == 1L -> context.getString(R.string.task_due_tomorrow_format, time)
                else -> LocalisedDates.date(dueDate)
            }
        }

        /**
         * Whole days between two instants, counted as calendar days rather than as 24-hour blocks.
         *
         * A DST transition makes one local day 23 or 25 hours long, and dividing a raw millisecond gap by
         * 86_400_000 would then call "tomorrow" either today or the day after, twice a year. Both instants
         * are reduced to local midnight and the quotient is rounded, so the short day and the long one
         * both land on one.
         */
        private fun daysBetween(from: Long, to: Long): Long =
            Math.round((startOfDay(to) - startOfDay(from)) / MILLIS_PER_DAY.toDouble())

        private const val MILLIS_PER_DAY = 86_400_000L

        private fun startOfDay(millis: Long): Long = Calendar.getInstance().apply {
            timeInMillis = millis
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}
