package com.taskplanner.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.taskplanner.data.model.WorkHistoryRow
import com.taskplanner.data.model.WorkIntervalCorrection
import com.taskplanner.data.model.WorkIntervalRow
import com.taskplanner.data.model.WorkSession
import kotlinx.coroutines.flow.Flow

/**
 * The body of the history screen's query: one row per logical session, with the totals SQLite computed
 * for it.
 *
 * Grouping happens here rather than in Kotlin because the screen has to show "how much was worked on
 * this day" without loading the day. `w.is_deleted = 0` sits in the join condition, not in the `WHERE`
 * clause: a session whose intervals were all thrown away still belongs in the list, with zero, and
 * dropping the `LEFT JOIN` would drop the session itself.
 *
 * The session, not the interval, is what a period filters on: a session that started at 23:50 and was
 * stopped at 00:20 belongs to the evening it began, which is the same rule `local_day` applies to the
 * totals in `WorkStatisticsDao`.
 */
internal const val HISTORY_QUERY: String =
    "SELECT s.uuid AS uuid, s.task_id AS taskId, s.task_title AS taskTitle, s.state AS state, " +
        "s.started_at AS startedAt, s.stopped_at AS stoppedAt, " +
        "COALESCE(SUM(CASE WHEN w.ended_at IS NOT NULL THEN w.duration_seconds ELSE 0 END), 0) " +
        "AS closedSeconds, " +
        "COALESCE(SUM(CASE WHEN w.ended_at IS NULL THEN " +
        "(CAST(:now AS INTEGER) - w.started_at) / 1000 ELSE 0 END), 0) AS openSeconds, " +
        "COUNT(w.id) AS intervals, " +
        "COALESCE(SUM(CASE WHEN w.source = 'MANUAL' THEN 1 ELSE 0 END), 0) AS manual " +
        "FROM tracking_sessions s " +
        "LEFT JOIN work_sessions w ON w.tracking_session_uuid = s.uuid AND w.is_deleted = 0 " +
        "LEFT JOIN tasks t ON t.id = s.task_id "

/**
 * The period, plus the two optional filters. `-1` means "do not filter on this": a parameter can only
 * be left out of a prepared statement by spelling it out, and the alternative — building the SQL per
 * call — would leave the screen running a different statement from the one under test.
 */
internal const val HISTORY_FILTER: String =
    "WHERE s.user_id = :userId AND s.is_deleted = 0 AND s.started_at >= :from AND s.started_at < :to " +
        "AND (:taskId = -1 OR s.task_id = :taskId) AND (:categoryId = -1 OR t.category_id = :categoryId) "

/** One statement per sort the screen offers, so each one is a query that can be planned and measured. */
internal const val HISTORY_BY_DATE: String =
    HISTORY_QUERY + HISTORY_FILTER + "GROUP BY s.uuid ORDER BY s.started_at DESC, s.uuid DESC"

internal const val HISTORY_BY_DURATION: String =
    HISTORY_QUERY + HISTORY_FILTER + "GROUP BY s.uuid " +
        "ORDER BY COALESCE(SUM(CASE WHEN w.ended_at IS NOT NULL THEN w.duration_seconds ELSE 0 END), 0) DESC, " +
        "s.started_at DESC, s.uuid DESC"

internal const val HISTORY_BY_TASK: String =
    HISTORY_QUERY + HISTORY_FILTER + "GROUP BY s.uuid " +
        "ORDER BY COALESCE(t.title, s.task_title) COLLATE NOCASE ASC, s.started_at DESC, s.uuid DESC"

@Dao
interface WorkSessionDao {

    /**
     * Aborts on conflict. This is the constraint §5 asks for at the database level: the unique index on
     * `(user_id, open_marker)` rejects a second open interval for the profile, whatever path tried to
     * create it — a double tap, a second screen, a replayed import.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(interval: WorkSession): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrIgnore(interval: WorkSession): Long

    @Update
    suspend fun update(interval: WorkSession)

    /** The interval being measured right now, if any: the first thing the app reads at start-up. */
    @Query("SELECT * FROM work_sessions WHERE user_id = :userId AND open_marker IS NOT NULL LIMIT 1")
    suspend fun getOpenOnce(userId: Long): WorkSession?

    @Query("SELECT * FROM work_sessions WHERE user_id = :userId AND open_marker IS NOT NULL LIMIT 1")
    fun observeOpen(userId: Long): Flow<WorkSession?>

    @Query(
        "SELECT * FROM work_sessions WHERE tracking_session_uuid = :sessionUuid AND is_deleted = 0 " +
            "ORDER BY started_at ASC, id ASC"
    )
    suspend fun getIntervalsOf(sessionUuid: String): List<WorkSession>

    /**
     * The stretches of one logical session, as the history screen shows them.
     *
     * Scoped to the profile as well as to the session: after an import two profiles can hold rows under
     * the same uuid, and the screen that expands one of them has no business reading the other's.
     */
    @Query(
        "SELECT uuid AS uuid, started_at AS startedAt, ended_at AS endedAt, " +
            "duration_seconds AS durationSeconds, source AS source, " +
            "clock_anomaly AS clockAnomaly, local_day AS localDay, " +
            "utc_offset_minutes AS utcOffsetMinutes " +
            "FROM work_sessions WHERE tracking_session_uuid = :sessionUuid AND user_id = :userId " +
            "AND is_deleted = 0 ORDER BY started_at ASC, id ASC"
    )
    suspend fun getIntervalRowsOf(sessionUuid: String, userId: Long): List<WorkIntervalRow>

    /**
     * The stretches last measured on one task, newest first, for the task screen's history.
     *
     * The projection rather than the entity, and a limit rather than the account: the block shows the
     * most recent few, and the rows behind them belong to the history screen.
     */
    @Query(
        "SELECT uuid AS uuid, started_at AS startedAt, ended_at AS endedAt, " +
            "duration_seconds AS durationSeconds, source AS source, " +
            "clock_anomaly AS clockAnomaly, local_day AS localDay, " +
            "utc_offset_minutes AS utcOffsetMinutes " +
            "FROM work_sessions WHERE user_id = :userId AND task_id = :taskId AND is_deleted = 0 " +
            "ORDER BY started_at DESC, id DESC LIMIT :limit"
    )
    fun observeRecentIntervals(userId: Long, taskId: Long, limit: Int): Flow<List<WorkIntervalRow>>

    @Query("SELECT * FROM work_sessions WHERE uuid = :uuid LIMIT 1")
    suspend fun getByUuid(uuid: String): WorkSession?

    @Query("SELECT * FROM work_sessions WHERE uuid = :uuid LIMIT 1")
    suspend fun findIncludingDeleted(uuid: String): WorkSession?

    /** True when the interval is the only thing holding a session open — Stop needs to know it too. */
    @Query(
        "SELECT COUNT(*) FROM work_sessions WHERE tracking_session_uuid = :sessionUuid " +
            "AND is_deleted = 0"
    )
    suspend fun countIntervalsOf(sessionUuid: String): Int

    /**
     * What already occupies a stretch of time for this profile — the check behind §13's rule that a
     * manual interval which overlaps another one is shown as a conflict, not saved quietly.
     *
     * The two shapes of overlap are asked about separately rather than with one `BETWEEN`: a closed
     * interval occupies the stretch it names, while an open one occupies its start and every instant
     * after it, so it conflicts with a window that reaches past that start and with none that ends
     * before it. Folding the open case into `ended_at IS NULL` alone would report every entry as
     * conflicting with a running timer, including last week's.
     *
     * [excludeUuid] leaves the row being corrected out of its own conflict list.
     */
    @Query(
        "SELECT * FROM work_sessions WHERE user_id = :userId AND is_deleted = 0 AND uuid != :excludeUuid " +
            "AND started_at < :endExclusive " +
            "AND (ended_at IS NULL OR ended_at > :startInclusive) " +
            "ORDER BY started_at ASC"
    )
    suspend fun findOverlapping(
        userId: Long,
        startInclusive: Long,
        endExclusive: Long,
        excludeUuid: String
    ): List<WorkSession>

    @Query("SELECT * FROM work_sessions WHERE user_id = :userId")
    suspend fun getAllForUser(userId: Long): List<WorkSession>

    @Query("SELECT * FROM work_sessions WHERE sync_state != 'SYNCED'")
    suspend fun getPendingSync(): List<WorkSession>

    @Query("UPDATE work_sessions SET sync_state = :state WHERE uuid = :uuid")
    suspend fun setSyncState(uuid: String, state: String)

    /**
     * A closed interval is not deleted, it is hidden: the row, its numbers and its correction history
     * stay, because discarding a measurement is the one thing the append-only rule cannot survive.
     */
    @Query(
        "UPDATE work_sessions SET is_deleted = 1, deleted_at = :now, open_marker = NULL, " +
            "sync_state = 'DELETING' WHERE uuid = :uuid"
    )
    suspend fun softDelete(uuid: String, now: Long)

    @Query(HISTORY_BY_DATE)
    suspend fun historyByDate(
        userId: Long, from: Long, to: Long, now: Long, taskId: Long, categoryId: Long
    ): List<WorkHistoryRow>

    @Query(HISTORY_BY_DURATION)
    suspend fun historyByDuration(
        userId: Long, from: Long, to: Long, now: Long, taskId: Long, categoryId: Long
    ): List<WorkHistoryRow>

    @Query(HISTORY_BY_TASK)
    suspend fun historyByTask(
        userId: Long, from: Long, to: Long, now: Long, taskId: Long, categoryId: Long
    ): List<WorkHistoryRow>

    /**
     * The moment this profile last touched a measurement, or 0 when it has none.
     *
     * The history list is a `GROUP BY` over two tables, so Room cannot infer one Flow from the rows it
     * selects and the screen needs a signal to re-run it on. This is that signal: a scalar the
     * invalidation tracker can answer cheaply, so a write anywhere in the history re-runs the grouped
     * query once instead of the list loading intervals to notice a change.
     */
    @Query(
        "SELECT COALESCE(MAX(updated_at), 0) FROM work_sessions " +
            "WHERE user_id = :userId AND is_deleted = 0"
    )
    fun observeChanges(userId: Long): Flow<Long>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCorrection(correction: WorkIntervalCorrection): Long

    /**
     * What one interval was told before, oldest change first.
     *
     * Scoped to the profile for the same reason the expansion above is: the uuid of a row is not a
     * permission to read another profile's account of it.
     */
    @Query(
        "SELECT * FROM work_interval_corrections WHERE interval_uuid = :intervalUuid " +
            "AND user_id = :userId ORDER BY created_at ASC"
    )
    suspend fun getCorrectionsOf(intervalUuid: String, userId: Long): List<WorkIntervalCorrection>

    @Query("SELECT * FROM work_interval_corrections WHERE user_id = :userId")
    suspend fun getCorrectionsForUser(userId: Long): List<WorkIntervalCorrection>

    @Query("SELECT * FROM work_interval_corrections WHERE uuid = :uuid LIMIT 1")
    suspend fun findCorrectionByUuid(uuid: String): WorkIntervalCorrection?

    @Query("UPDATE work_interval_corrections SET sync_state = :state WHERE uuid = :uuid")
    suspend fun setCorrectionSyncState(uuid: String, state: String)
}
