package com.taskplanner.data.model

/**
 * The shapes SQL aggregation results come back in.
 *
 * None of these is stored: they are what a `GROUP BY` produces, typed. Keeping them here rather than
 * inside the DAO file is what lets the screens and the repository pass a measured number around without
 * ever loading interval rows — the point of §10 being that a total is computed by SQLite, not by a
 * Kotlin loop over the history.
 */

/** One profile's measured work inside one period, closed intervals only. */
data class WorkPeriodSummary(
    val seconds: Long,
    val intervals: Int,
    val sessions: Int
)

/** Work measured against a task inside a period. `task_id` is the join key, so a task deleted
 *  physically keeps the rows that were measured on it and shows up without a title. */
data class TaskWorkSummary(
    val taskId: Long,
    val title: String?,
    val seconds: Long,
    val intervals: Int
)

/**
 * Work measured against a category inside a period; a null name means "no category".
 *
 * [color] travels with the total because the dashboard paints the category's own colour rather than one it
 * picked for the occasion: a user who made "Home" green sees green in the chart, and a chart that coloured
 * the same category differently from the list beside it would be two screens describing one thing in two
 * languages. The ungrouped bucket has no category row to borrow from, so it comes back null and the screen
 * gives it the neutral shade.
 */
data class CategoryWorkSummary(
    val categoryId: Long?,
    val categoryName: String?,
    val seconds: Long,
    val intervals: Int,
    val color: Int? = null
)

/** One bucket of the trend: a local day and what was measured on it. */
data class DailyWorkSummary(
    val localDay: Long,
    val seconds: Long,
    val intervals: Int
)

/**
 * One session as the history screen lists them: what was worked on, when, and how much of it is a
 * finished measurement.
 *
 * [openSeconds] is kept apart from [closedSeconds] on purpose. A running session has an interval with
 * no end, so its length is not a fact yet; folding it into the total would make a history entry change
 * every second and would let an unfinished measurement be summed as if it were one. Screens that show
 * both label them differently.
 */
data class WorkHistoryRow(
    val uuid: String,
    val taskId: Long,
    val taskTitle: String,
    val state: String,
    val startedAt: Long,
    val stoppedAt: Long?,
    val closedSeconds: Long,
    val openSeconds: Long,
    val intervals: Int,
    val manual: Int
)

/** The same session expanded into the intervals that make it up. */
data class WorkIntervalRow(
    val uuid: String,
    val startedAt: Long,
    val endedAt: Long?,
    val durationSeconds: Long?,
    val source: String,
    val clockAnomaly: String?,
    val localDay: Long,
    val utcOffsetMinutes: Int
)

/** Measured time on one task across the whole account — the figure a task card prints. */
data class TaskTotalSeconds(
    val taskId: Long,
    val seconds: Long
)
