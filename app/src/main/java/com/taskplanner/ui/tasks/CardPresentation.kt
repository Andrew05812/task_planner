package com.taskplanner.ui.tasks

import android.content.Context

/**
 * What a card says about a task, decided from the row's own data and nothing else.
 *
 * This is the whole of the display layer's arithmetic, deliberately separate from the adapter so the
 * boundary the spec draws can be tested: every field is derived from a field the row already carries,
 * nothing is written anywhere, and `now` arrives from outside rather than from the device clock — which is
 * what lets the test name the one minute where a deadline becomes overdue.
 *
 * The overdue predicate is the one the dashboard already owns (`TaskStatisticsDao.healthCounts` counts
 * OVERDUE as `due_date IS NOT NULL AND due_date < :now`), and it is not restated differently here on
 * purpose: a task the dashboard calls late cannot be a task the list calls on time.
 *
 * The skeleton declares the fields; `of` lands with the tests that pin them.
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
    val showComplete: Boolean
) {
    companion object {
        /** The category name is drawn only when it has this much room; below it the dot stands alone. */
        const val CATEGORY_NAME_MIN_DP = 48

        fun of(context: Context, row: TasksListItem.TaskRow, now: Long): CardPresentation =
            throw UnsupportedOperationException(
                "CardPresentation.of is stage 4.1's implementation; the skeleton exists so its tests compile"
            )
    }
}
