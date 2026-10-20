package com.taskplanner.data.local

import androidx.room.Dao
import androidx.room.Query
import com.taskplanner.data.model.CategoryWorkSummary
import com.taskplanner.data.model.DailyWorkSummary
import com.taskplanner.data.model.EstimateComparison
import com.taskplanner.data.model.TaskWorkSummary
import com.taskplanner.data.model.WorkPeriodSummary
import com.taskplanner.data.model.TaskTotalSeconds
import com.taskplanner.data.model.WorkSession
import kotlinx.coroutines.flow.Flow

/**
 * The trend the work screens and the overload read both ask for: one row per local day the user actually
 * worked, with nothing between them invented.
 *
 * Written once and given to both readings of it, because a baseline the panel watches and a baseline a rule
 * computes have to be the same four weeks; two copies of a statement are two answers waiting to drift apart.
 * The bound is half-open (`< :toDay`), as every period in this file is.
 */
internal const val DAILY_SERIES_QUERY: String =
    "SELECT w.local_day AS localDay, COALESCE(SUM(w.duration_seconds), 0) AS seconds, " +
        "COUNT(w.id) AS intervals FROM work_sessions w " +
        "WHERE w.user_id = :userId AND w.local_day >= :fromDay AND w.local_day < :toDay " +
        "AND w.ended_at IS NOT NULL AND w.is_deleted = 0 " +
        "GROUP BY w.local_day ORDER BY w.local_day ASC"

/**
 * Every total the work-tracking screens show, computed by SQLite.
 *
 * Three rules hold throughout, and they are the reason this DAO exists as its own file:
 *
 *  * **A period is a range of `local_day`**, the epoch day each interval was measured on. Day, week and
 *    month differ only in the two bounds the caller passes, which `java.time` derives from the user's
 *    calendar — so a week that crosses a daylight-saving shift is still seven local days, and no query
 *    here does arithmetic on milliseconds.
 *  * **Only closed intervals are summed** (`ended_at IS NOT NULL`). An open interval has no length yet,
 *    and folding one into a total would make history change value every second and let unfinished work
 *    be reported as measured. `getOpenInterval` is the separate, explicitly labelled path.
 *  * **Nothing selects `*`.** A screen that wants one number gets one number, not the profile's history
 *    loaded into a list to be summed in Kotlin — which is the failure mode the whole feature is meant to
 *    replace.
 *
 * `is_deleted = 0` appears in every statement: a row the user threw away is hidden from the totals, but
 * stays in the table, because a measurement is not something to delete.
 */
@Dao
interface WorkStatisticsDao {

    /** The trend, read once. */
    @Query(DAILY_SERIES_QUERY)
    suspend fun dailySeries(userId: Long, fromDay: Long, toDay: Long): List<DailyWorkSummary>

    /** The same trend as a Flow, for the screen that stays on the window while the work moves under it. */
    @Query(DAILY_SERIES_QUERY)
    fun observeDailySeries(userId: Long, fromDay: Long, toDay: Long): Flow<List<DailyWorkSummary>>

    @Query(
        "SELECT COALESCE(SUM(duration_seconds), 0) FROM work_sessions " +
            "WHERE user_id = :userId AND task_id = :taskId AND ended_at IS NOT NULL AND is_deleted = 0"
    )
    suspend fun secondsForTask(userId: Long, taskId: Long): Long

    /** The same total as a Flow, so a task screen updates the moment a stretch is closed. */
    @Query(
        "SELECT COALESCE(SUM(duration_seconds), 0) FROM work_sessions " +
            "WHERE user_id = :userId AND task_id = :taskId AND ended_at IS NOT NULL AND is_deleted = 0"
    )
    fun observeSecondsForTask(userId: Long, taskId: Long): Flow<Long>

    @Query(
        "SELECT COALESCE(SUM(duration_seconds), 0) FROM work_sessions " +
            "WHERE user_id = :userId AND task_id = :taskId AND local_day = :localDay " +
            "AND ended_at IS NOT NULL AND is_deleted = 0"
    )
    suspend fun secondsForTaskOnDay(userId: Long, taskId: Long, localDay: Long): Long

    @Query(
        "SELECT COALESCE(SUM(duration_seconds), 0) FROM work_sessions " +
            "WHERE user_id = :userId AND local_day = :localDay AND ended_at IS NOT NULL AND is_deleted = 0"
    )
    suspend fun secondsOnDay(userId: Long, localDay: Long): Long

    /** The one shape behind "today", "this week", "this month" and any custom range: two day bounds. */
    @Query(
        "SELECT COALESCE(SUM(w.duration_seconds), 0) AS seconds, COUNT(w.id) AS intervals, " +
            "COUNT(DISTINCT w.tracking_session_uuid) AS sessions " +
            "FROM work_sessions w " +
            "WHERE w.user_id = :userId AND w.local_day >= :fromDay AND w.local_day < :toDay " +
            "AND w.ended_at IS NOT NULL AND w.is_deleted = 0"
    )
    suspend fun periodSummary(userId: Long, fromDay: Long, toDay: Long): WorkPeriodSummary

    @Query(
        "SELECT COALESCE(SUM(w.duration_seconds), 0) AS seconds, COUNT(w.id) AS intervals, " +
            "COUNT(DISTINCT w.tracking_session_uuid) AS sessions " +
            "FROM work_sessions w " +
            "WHERE w.user_id = :userId AND w.local_day >= :fromDay AND w.local_day < :toDay " +
            "AND w.ended_at IS NOT NULL AND w.is_deleted = 0"
    )
    fun observePeriodSummary(userId: Long, fromDay: Long, toDay: Long): Flow<WorkPeriodSummary>

    /**
     * Time per task inside a period, most-worked first.
     *
     * The title comes from the task row when it is there and from the session's snapshot when it is not,
     * read as a scalar subquery instead of a join: joining `tracking_sessions` on `task_id` multiplies
     * the interval rows the sum is being computed over, which would turn a task with three sessions into
     * three times the work.
     */
    @Query(
        "SELECT w.task_id AS taskId, " +
            "COALESCE(MAX(t.title), (SELECT ts.task_title FROM tracking_sessions ts " +
            "WHERE ts.task_id = w.task_id ORDER BY ts.started_at DESC LIMIT 1)) AS title, " +
            "COALESCE(SUM(w.duration_seconds), 0) AS seconds, COUNT(w.id) AS intervals " +
            "FROM work_sessions w LEFT JOIN tasks t ON t.id = w.task_id " +
            "WHERE w.user_id = :userId AND w.local_day >= :fromDay AND w.local_day < :toDay " +
            "AND w.ended_at IS NOT NULL AND w.is_deleted = 0 " +
            "GROUP BY w.task_id ORDER BY seconds DESC LIMIT :limit"
    )
    suspend fun workByTask(userId: Long, fromDay: Long, toDay: Long, limit: Int): List<TaskWorkSummary>

    /**
     * Time per category inside a period.
     *
     * Only work whose task carries no category at all — or whose category row is gone — groups under a null
     * name, which the screen labels explicitly instead of dropping the minutes. A category that was deleted
     * but is still stored keeps naming the bucket its work was measured into: the minutes really did go to
     * it, and a chart that re-labelled them as unclassified whenever a category was hidden would be a picture
     * that rewrites the past.
     *
     * `MAX(c.color)` is the colour rather than a bare `c.color` because the row is grouped: every row of the
     * group carries the same category's colour, and SQLite wants the column named in an aggregate before it
     * will put it in the result.
     */
    @Query(
        "SELECT c.id AS categoryId, MAX(c.name) AS categoryName, MAX(c.color) AS color, " +
            "COALESCE(SUM(w.duration_seconds), 0) AS seconds, COUNT(w.id) AS intervals " +
            "FROM work_sessions w " +
            "LEFT JOIN tasks t ON t.id = w.task_id " +
            "LEFT JOIN categories c ON c.id = t.category_id " +
            "WHERE w.user_id = :userId AND w.local_day >= :fromDay AND w.local_day < :toDay " +
            "AND w.ended_at IS NOT NULL AND w.is_deleted = 0 " +
            "GROUP BY c.id ORDER BY seconds DESC"
    )
    suspend fun workByCategory(userId: Long, fromDay: Long, toDay: Long): List<CategoryWorkSummary>

    @Query(
        "SELECT AVG(duration_seconds) FROM work_sessions " +
            "WHERE user_id = :userId AND local_day >= :fromDay AND local_day < :toDay " +
            "AND ended_at IS NOT NULL AND is_deleted = 0"
    )
    suspend fun averageIntervalSeconds(userId: Long, fromDay: Long, toDay: Long): Double?

    @Query(
        "SELECT COUNT(*) FROM tracking_sessions " +
            "WHERE user_id = :userId AND is_deleted = 0 AND started_at >= :from AND started_at < :to"
    )
    suspend fun sessionCount(userId: Long, from: Long, to: Long): Int

    /**
     * Tasks finished inside a period, counted in SQL from the instant this stage added:
     * `tasks.completed_at`. Status alone cannot answer it — a task completed last year still reads
     * `COMPLETED` today.
     *
     * `UPPER` keeps this answer in step with the dashboard's own completion figures, which group the
     * same rows: `Converters` reads a row an import spelled `done` as completed, so a count that only
     * matched the exact uppercase spelling would disagree with the chart next to it.
     */
    @Query(
        "SELECT COUNT(*) FROM tasks " +
            "WHERE user_id = :userId AND is_deleted = 0 AND UPPER(status) IN ('COMPLETED', 'DONE') " +
            "AND completed_at IS NOT NULL AND completed_at >= :from AND completed_at < :to"
    )
    suspend fun completedTasksInPeriod(userId: Long, from: Long, to: Long): Int

    /**
     * The same count as a Flow, for the reads that stay on screen while the work moves: a goal aimed at
     * twenty finishes has to answer "how many now" the instant one of them is completed, un-completed or
     * thrown away, and a signal the caller has to poll would be a figure the user could be shown wrong.
     */
    @Query(
        "SELECT COUNT(*) FROM tasks " +
            "WHERE user_id = :userId AND is_deleted = 0 AND UPPER(status) IN ('COMPLETED', 'DONE') " +
            "AND completed_at IS NOT NULL AND completed_at >= :from AND completed_at < :to"
    )
    fun observeCompletedTasksInPeriod(userId: Long, from: Long, to: Long): Flow<Int>

    /**
     * The finished tasks of a window that carry an estimate, each with the time measured on it.
     *
     * Bounded twice over — by `index_tasks_user_id_completed_at` and by the window the caller names — and
     * carrying four columns rather than a row of a task, because the reading this feeds has to name one
     * example: "you estimate short" is a claim about a habit, and the habit is only checkable through the
     * task that missed by the most. The measured total is a correlated scalar subquery rather than a join
     * for the reason every other grouping here avoids joining `work_sessions`: joining would multiply the
     * estimate rows by the number of intervals on each task, and the sum would then be a sum of pairs.
     *
     * `estimated_minutes > 0` keeps a zero the user typed out of the comparison: a plan of no minutes is not
     * an estimate that was wrong, it is the absence of one, and reading it as a total miss would let the
     * rule report the largest gap in the profile from a field nobody filled in.
     */
    @Query(
        "SELECT t.id AS taskId, t.title AS title, t.estimated_minutes AS estimatedMinutes, " +
            "COALESCE((SELECT SUM(w.duration_seconds) FROM work_sessions w " +
            "WHERE w.task_id = t.id AND w.user_id = :userId AND w.ended_at IS NOT NULL " +
            "AND w.is_deleted = 0), 0) AS measuredSeconds " +
            "FROM tasks t " +
            "WHERE t.user_id = :userId AND t.is_deleted = 0 AND t.estimated_minutes IS NOT NULL " +
            "AND t.estimated_minutes > 0 AND UPPER(t.status) IN ('COMPLETED', 'DONE') " +
            "AND t.completed_at IS NOT NULL AND t.completed_at >= :from AND t.completed_at < :to " +
            "ORDER BY t.completed_at ASC"
    )
    suspend fun estimatedCompletions(userId: Long, from: Long, to: Long): List<EstimateComparison>

    /**
     * The trend for one task: one row per local day it was worked on inside the window.
     *
     * A window rather than a single day because the screen that reads "today" also has to be right at
     * midnight, and a row per day lets it pick the day it means at the moment it reads instead of
     * caching yesterday under today's label.
     */
    @Query(
        "SELECT local_day AS localDay, COALESCE(SUM(duration_seconds), 0) AS seconds, " +
            "COUNT(id) AS intervals FROM work_sessions " +
            "WHERE user_id = :userId AND task_id = :taskId AND local_day >= :fromDay " +
            "AND local_day < :toDay AND ended_at IS NOT NULL AND is_deleted = 0 " +
            "GROUP BY local_day ORDER BY local_day ASC"
    )
    fun observeDailySeriesForTask(
        userId: Long,
        taskId: Long,
        fromDay: Long,
        toDay: Long
    ): Flow<List<DailyWorkSummary>>

    /**
     * The interval being measured right now: the live figure, kept apart from every total above.
     *
     * One row per profile at most, because the unique index on `(user_id, open_marker)` rejects a
     * second one — which is why the task cards read this as a single value instead of a list.
     */
    @Query(
        "SELECT * FROM work_sessions WHERE user_id = :userId AND open_marker IS NOT NULL AND " +
            "is_deleted = 0 LIMIT 1"
    )
    fun observeOpenInterval(userId: Long): Flow<WorkSession?>

    /**
     * All-time measured time per task, for the list screen: one grouped query rather than one query per
     * card. A task with no closed work has no row, which the screen renders as "nothing measured yet".
     */
    @Query(
        "SELECT w.task_id AS taskId, COALESCE(SUM(w.duration_seconds), 0) AS seconds " +
            "FROM work_sessions w " +
            "WHERE w.user_id = :userId AND w.ended_at IS NOT NULL AND w.is_deleted = 0 " +
            "GROUP BY w.task_id"
    )
    fun observeTotalsByTask(userId: Long): Flow<List<TaskTotalSeconds>>
}
