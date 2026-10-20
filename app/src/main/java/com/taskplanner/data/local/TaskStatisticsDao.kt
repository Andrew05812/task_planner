package com.taskplanner.data.local

import androidx.room.Dao
import androidx.room.Query
import com.taskplanner.data.model.PriorityOverdueCounts
import com.taskplanner.data.model.TaskHealthCounts
import com.taskplanner.data.model.TaskStatusCount
import kotlinx.coroutines.flow.Flow

/**
 * Counts the dashboard asks of the `tasks` table, answered by SQLite.
 *
 * The screen used to read this by loading every task the profile holds and folding the list in Kotlin.
 * That works once and fails slowly: the cost grows with the account instead of with the answer, and
 * every figure is recomputed from scratch on each visit. Here each number is one aggregate over an
 * indexed column, so a 10 000-task account pays for the rows that match, not for the rows it has.
 *
 * Two rules run through the file:
 *
 *  * **Nothing selects `*`.** A count is a count.
 *  * **`is_deleted = 0` everywhere.** A tombstone is not a task the user can still do, and counting one
 *    would make the dashboard disagree with every list on the device.
 *
 * The bounds the caller passes are instants, derived by `java.time` from the user's calendar rather
 * than from arithmetic on milliseconds, so "today" and "the coming seven days" mean the days the user
 * lives through — including the short and long ones at a daylight-saving change.
 */
@Dao
interface TaskStatisticsDao {

    /**
     * Tasks per status, tombstones excluded.
     *
     * Archived rows are included: the completion figure is about the account's history, and a task the
     * user put away after finishing it was still finished. The answer is the raw TEXT spelling rather
     * than the enum, because grouping happened in SQLite — `isCompletedSpelling` is the one rule that
     * says which spellings mean finished, shared with `Converters` so the screen and the row cannot
     * drift apart on a legacy `DONE`.
     */
    @Query(
        "SELECT status AS status, COUNT(*) AS count FROM tasks " +
            "WHERE user_id = :userId AND is_deleted = 0 GROUP BY status"
    )
    suspend fun statusCounts(userId: Long): List<TaskStatusCount>

    @Query(
        "SELECT status AS status, COUNT(*) AS count FROM tasks " +
            "WHERE user_id = :userId AND is_deleted = 0 GROUP BY status"
    )
    fun observeStatusCounts(userId: Long): Flow<List<TaskStatusCount>>

    /**
     * The open tasks in one scan, sorted into the states the panel shows.
     *
     * `COALESCE` is not decoration: `SUM` over no matching row answers NULL, and Room reading NULL into
     * a non-null `Int` is a crash rather than a zero — which is exactly what an account with nothing
     * open would hit. A profile with no tasks is a screen full of honest zeroes, not an empty screen.
     */
    @Query(
        "SELECT " +
            "COALESCE(SUM(CASE WHEN due_date IS NOT NULL AND due_date < :now THEN 1 ELSE 0 END), 0) AS overdue, " +
            "COALESCE(SUM(CASE WHEN due_date >= :now AND due_date < :dayEnd THEN 1 ELSE 0 END), 0) AS dueToday, " +
            "COALESCE(SUM(CASE WHEN due_date >= :dayEnd AND due_date < :weekEnd THEN 1 ELSE 0 END), 0) AS dueThisWeek, " +
            "COALESCE(SUM(CASE WHEN due_date IS NULL THEN 1 ELSE 0 END), 0) AS withoutDeadline, " +
            "COALESCE(SUM(CASE WHEN UPPER(status) = 'IN_PROGRESS' THEN 1 ELSE 0 END), 0) AS inProgress, " +
            "COALESCE(SUM(CASE WHEN updated_at < :staleBefore THEN 1 ELSE 0 END), 0) AS stale, " +
            "COUNT(*) AS open " +
            "FROM tasks " +
            // `UPPER` because the reader of a row is lenient about casing — `Converters` calls a
            // hand-edited `done` a completion — and a panel that called the same row still open would
            // contradict the list it sits next to. `status` carries no index, so the function costs the
            // plan nothing: the rows are already narrowed by profile.
            "WHERE user_id = :userId AND is_deleted = 0 AND archived = 0 AND " +
            "UPPER(status) NOT IN ('COMPLETED', 'DONE')"
    )
    suspend fun healthCounts(
        userId: Long,
        now: Long,
        dayEnd: Long,
        weekEnd: Long,
        staleBefore: Long
    ): TaskHealthCounts

    @Query(
        "SELECT " +
            "COALESCE(SUM(CASE WHEN due_date IS NOT NULL AND due_date < :now THEN 1 ELSE 0 END), 0) AS overdue, " +
            "COALESCE(SUM(CASE WHEN due_date >= :now AND due_date < :dayEnd THEN 1 ELSE 0 END), 0) AS dueToday, " +
            "COALESCE(SUM(CASE WHEN due_date >= :dayEnd AND due_date < :weekEnd THEN 1 ELSE 0 END), 0) AS dueThisWeek, " +
            "COALESCE(SUM(CASE WHEN due_date IS NULL THEN 1 ELSE 0 END), 0) AS withoutDeadline, " +
            "COALESCE(SUM(CASE WHEN UPPER(status) = 'IN_PROGRESS' THEN 1 ELSE 0 END), 0) AS inProgress, " +
            "COALESCE(SUM(CASE WHEN updated_at < :staleBefore THEN 1 ELSE 0 END), 0) AS stale, " +
            "COUNT(*) AS open " +
            "FROM tasks " +
            "WHERE user_id = :userId AND is_deleted = 0 AND archived = 0 AND " +
            "UPPER(status) NOT IN ('COMPLETED', 'DONE')"
    )
    fun observeHealthCounts(
        userId: Long,
        now: Long,
        dayEnd: Long,
        weekEnd: Long,
        staleBefore: Long
    ): Flow<TaskHealthCounts>

    /**
     * How much of what is overdue is work the user marked as important.
     *
     * The question the insight rules ask, and it is the one [healthCounts] cannot answer: that panel sorts
     * the open rows by *when* they are due, while a reading about a pile of high-priority work needs the
     * priority split of the same rows. Both are one scan of the same set, so the two figures can never
     * describe two different databases — which is the reason this is a second aggregate rather than a
     * second read of `TaskHealthCounts` folded in Kotlin.
     *
     * `UPPER(priority)` for the same reason the status spellings are folded: a row an import wrote as
     * `high` is a high-priority task to every screen on the device, and a rule that missed it would be
     * wrong about the reader's own week.
     */
    @Query(
        "SELECT " +
            "COALESCE(SUM(CASE WHEN UPPER(priority) = 'HIGH' THEN 1 ELSE 0 END), 0) AS highOpen, " +
            "COALESCE(SUM(CASE WHEN UPPER(priority) = 'HIGH' AND due_date IS NOT NULL " +
            "AND due_date < :now THEN 1 ELSE 0 END), 0) AS highOverdue, " +
            "COALESCE(SUM(CASE WHEN due_date IS NOT NULL AND due_date < :now THEN 1 ELSE 0 END), 0) " +
            "AS overdue, " +
            "COUNT(*) AS open " +
            "FROM tasks " +
            "WHERE user_id = :userId AND is_deleted = 0 AND archived = 0 AND " +
            "UPPER(status) NOT IN ('COMPLETED', 'DONE')"
    )
    suspend fun priorityOverdue(userId: Long, now: Long): PriorityOverdueCounts

    /** Tasks created inside a window — the "new this period" figure, counted rather than listed. */
    @Query(
        "SELECT COUNT(*) FROM tasks " +
            "WHERE user_id = :userId AND is_deleted = 0 AND created_at >= :from AND created_at < :to"
    )
    suspend fun createdInPeriod(userId: Long, from: Long, to: Long): Int

    /**
     * When each task in the window was finished, as instants.
     *
     * This is the one read on the dashboard that returns rows rather than a number, and it is bounded
     * twice over: by the window and by `index_tasks_user_id_completed_at`, and it carries a single
     * column. The reason it is not a `GROUP BY` in SQL is the day itself — `work_sessions` stores the
     * local day each interval was measured on, decided at the moment of measuring, while a completion
     * only has an instant. Bucketing that instant by the zone read now is what keeps a completion at
     * 00:20 local on the day the user finished it, across a daylight-saving boundary, where dividing
     * the epoch milliseconds by a day length in SQL would put it on the previous UTC day. The caller
     * turns these into per-day counts; the count of rows is the number of completions in the window,
     * which is what the screen draws.
     */
    @Query(
        "SELECT completed_at FROM tasks " +
            "WHERE user_id = :userId AND is_deleted = 0 AND UPPER(status) IN ('COMPLETED', 'DONE') " +
            "AND completed_at IS NOT NULL AND completed_at >= :from AND completed_at < :to " +
            "ORDER BY completed_at ASC"
    )
    suspend fun completedInstantsInPeriod(userId: Long, from: Long, to: Long): List<Long>

    /**
     * The signal the dashboard re-reads itself on, in the same shape the history list uses.
     *
     * A `GROUP BY` result is not something Room can watch directly, so the screens that stay open
     * during a write watch the last change to the profile's rows instead: completing a task moves
     * `updated_at`, so the panel refreshes without a timer and without loading anything first.
     */
    @Query("SELECT COALESCE(MAX(updated_at), 0) FROM tasks WHERE user_id = :userId")
    fun observeChanges(userId: Long): Flow<Long>
}
