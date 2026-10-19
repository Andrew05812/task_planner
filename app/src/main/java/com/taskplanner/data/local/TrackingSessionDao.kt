package com.taskplanner.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.taskplanner.data.model.TrackingSession
import kotlinx.coroutines.flow.Flow

/**
 * The state half of work tracking: which session is running, which one is paused, which are finished.
 *
 * Every read here asks the database, never a cached object, because the database is what survives a
 * process kill — see `docs/TIME_TRACKING.md`.
 */
@Dao
interface TrackingSessionDao {

    /**
     * The profile's running session, if it has one.
     *
     * `running_marker IS NOT NULL` rather than `state = 'RUNNING'` because the unique index on the
     * marker is what enforces "at most one", so the same predicate that the constraint uses is the one
     * the read uses — a database that somehow holds two running sessions is read as the newest one
     * rather than as an error, and the writes into it are still rejected.
     */
    @Query("SELECT * FROM tracking_sessions WHERE user_id = :userId AND running_marker IS NOT NULL LIMIT 1")
    suspend fun getRunningOnce(userId: Long): TrackingSession?

    @Query("SELECT * FROM tracking_sessions WHERE user_id = :userId AND running_marker IS NOT NULL LIMIT 1")
    fun observeRunning(userId: Long): Flow<TrackingSession?>

    /**
     * The runs this profile has neither finished nor abandoned, newest first.
     *
     * A list because a paused run takes no slot: a profile can hold several of them, one per task, and
     * each is a card that has to read "Resume" rather than "Start". There is at most one running entry
     * among them, the marker index sees to that.
     */
    @Query(
        "SELECT * FROM tracking_sessions WHERE user_id = :userId AND state != 'COMPLETED' " +
            "AND is_deleted = 0 ORDER BY started_at DESC"
    )
    fun observeLiveForUser(userId: Long): Flow<List<TrackingSession>>

    /** A session left paused on this task — the one Start on the same task continues instead of opening
     *  a second block of work. */
    @Query(
        "SELECT * FROM tracking_sessions WHERE user_id = :userId AND task_id = :taskId " +
            "AND state = 'PAUSED' ORDER BY started_at DESC LIMIT 1"
    )
    suspend fun getPausedForTask(userId: Long, taskId: Long): TrackingSession?

    @Query("SELECT * FROM tracking_sessions WHERE uuid = :uuid LIMIT 1")
    suspend fun getByUuid(uuid: String): TrackingSession?

    /** The title history still shows after the task row is gone, read from the session's own copy. */
    @Query(
        "SELECT * FROM tracking_sessions WHERE user_id = :userId AND task_id = :taskId " +
            "AND state != 'COMPLETED' ORDER BY started_at DESC"
    )
    suspend fun getLiveSessionsForTask(userId: Long, taskId: Long): List<TrackingSession>

    /**
     * Aborts on a conflicting write: two `Start` calls that both got as far as inserting are stopped
     * here, by the unique index, rather than by a check the second caller performed before the first
     * one had committed.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(session: TrackingSession): Long

    @Update
    suspend fun update(session: TrackingSession)

    @Query(
        "UPDATE tracking_sessions SET state = :state, running_marker = :marker, updated_at = :now, " +
            "sync_state = 'PENDING' WHERE uuid = :uuid"
    )
    suspend fun setState(uuid: String, state: String, marker: String?, now: Long)

    @Query(
        "UPDATE tracking_sessions SET state = 'COMPLETED', running_marker = NULL, " +
            "stopped_at = :now, updated_at = :now, sync_state = 'PENDING' WHERE uuid = :uuid"
    )
    suspend fun markStopped(uuid: String, now: Long)

    /** Sync has to see the row by its stable id, including one that was thrown away. */
    @Query("SELECT * FROM tracking_sessions WHERE uuid = :uuid LIMIT 1")
    suspend fun findIncludingDeleted(uuid: String): TrackingSession?

    @Query("SELECT * FROM tracking_sessions WHERE user_id = :userId")
    suspend fun getAllForUser(userId: Long): List<TrackingSession>

    @Query("SELECT * FROM tracking_sessions WHERE sync_state != 'SYNCED'")
    suspend fun getPendingSync(): List<TrackingSession>

    @Query("UPDATE tracking_sessions SET sync_state = :state WHERE uuid = :uuid")
    suspend fun setSyncState(uuid: String, state: String)

    /**
     * The moment this profile last changed a session, or 0 when it has none.
     *
     * Pausing, stopping and correcting a session change nothing about the intervals, so the interval
     * signal alone would leave the history list showing a session that is no longer running. The
     * history screen watches both.
     */
    @Query(
        "SELECT COALESCE(MAX(updated_at), 0) FROM tracking_sessions " +
            "WHERE user_id = :userId AND is_deleted = 0"
    )
    fun observeChanges(userId: Long): Flow<Long>

    @Query(
        "UPDATE tracking_sessions SET is_deleted = 1, deleted_at = :now, running_marker = NULL, " +
            "sync_state = 'DELETING' WHERE uuid = :uuid"
    )
    suspend fun softDelete(uuid: String, now: Long)
}
