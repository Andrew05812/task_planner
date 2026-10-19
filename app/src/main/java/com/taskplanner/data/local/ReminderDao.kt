package com.taskplanner.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.taskplanner.data.model.Reminder
import kotlinx.coroutines.flow.Flow

@Dao
interface ReminderDao {

    /** Every reminder the user can still see coming, oldest fire first. */
    @Query("SELECT * FROM reminders WHERE task_uuid = :taskUuid AND is_deleted = 0 ORDER BY trigger_at ASC")
    suspend fun getLiveForTask(taskUuid: String): List<Reminder>

    @Query("SELECT * FROM reminders WHERE user_id = :userId AND is_deleted = 0 ORDER BY trigger_at ASC")
    suspend fun getLiveForUser(userId: Long): List<Reminder>

    /**
     * Includes tombstones: the sync queue has to push a deleted reminder too, otherwise the other
     * device keeps firing an alarm the user already cancelled.
     */
    @Query("SELECT * FROM reminders WHERE user_id = :userId")
    suspend fun getAllRowsOnce(userId: Long): List<Reminder>

    /** What the list screen badges: armed reminders of the current profile, including shown ones. */
    @Query("SELECT * FROM reminders WHERE user_id = :userId AND is_deleted = 0")
    fun observeLiveForUser(userId: Long): Flow<List<Reminder>>

    /** Includes tombstones: the identity check must find a deleted row so re-adding revives it. */
    @Query("SELECT * FROM reminders WHERE task_uuid = :taskUuid AND offset_minutes = :offsetMinutes LIMIT 1")
    suspend fun findByTaskAndOffset(taskUuid: String, offsetMinutes: Int): Reminder?

    @Query("SELECT * FROM reminders WHERE uuid = :uuid LIMIT 1")
    suspend fun findByUuid(uuid: String): Reminder?

    /**
     * Reminders whose minute has arrived and that have not been shown yet.
     *
     * The join is what keeps the promise that a finished, archived or deleted task stops producing
     * notifications even when something else — a sync pull, a second device, a crash between the
     * user's tap and the sweep — left the row behind. Filtering on `r.user_id = t.user_id` also means
     * a reminder can only ever fire for the profile that owns its task.
     */
    @Query(
        "SELECT r.* FROM reminders r JOIN tasks t ON t.uuid = r.task_uuid " +
            "WHERE r.user_id = :userId AND r.is_deleted = 0 AND r.notified_at IS NULL " +
            "AND r.trigger_at <= :now AND r.trigger_at >= :notEarlier " +
            "AND t.is_deleted = 0 AND t.archived = 0 AND t.status != 'COMPLETED' " +
            "ORDER BY r.trigger_at ASC"
    )
    suspend fun getDue(userId: Long, now: Long, notEarlier: Long): List<Reminder>

    /**
     * Pending reminders whose time passed long ago. They are retired rather than delivered: a
     * reminder for yesterday's 07:00 arriving now is noise, and leaving it pending means it stays in
     * every later sweep.
     */
    @Query(
        "SELECT r.* FROM reminders r JOIN tasks t ON t.uuid = r.task_uuid " +
            "WHERE r.user_id = :userId AND r.is_deleted = 0 AND r.notified_at IS NULL " +
            "AND r.trigger_at < :notLater ORDER BY r.trigger_at ASC"
    )
    suspend fun getLongOverdue(userId: Long, notLater: Long): List<Reminder>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrIgnore(reminder: Reminder): Long

    @Update
    suspend fun update(reminder: Reminder)

    @Query("UPDATE reminders SET notified_at = :now, updated_at = :now WHERE uuid IN (:uuids)")
    suspend fun markNotified(uuids: List<String>, now: Long = System.currentTimeMillis())

    @Query(
        "UPDATE reminders SET is_deleted = 1, deleted_at = :now, updated_at = :now, " +
            "sync_state = 'DELETING' WHERE task_uuid IN (:taskUuids) AND is_deleted = 0"
    )
    suspend fun softDeleteForTasks(taskUuids: List<String>, now: Long = System.currentTimeMillis())

    /** Unticking one lead time, leaving the task's other reminders alone. */
    @Query(
        "UPDATE reminders SET is_deleted = 1, deleted_at = :now, updated_at = :now, " +
            "sync_state = 'DELETING' WHERE uuid = :uuid"
    )
    suspend fun softDelete(uuid: String, now: Long = System.currentTimeMillis())

    /** Snooze: a new instant, and the row becomes pending again. */
    @Query(
        "UPDATE reminders SET trigger_at = :triggerAt, notified_at = NULL, updated_at = :now, " +
            "sync_state = 'PENDING' WHERE uuid = :uuid"
    )
    suspend fun snooze(uuid: String, triggerAt: Long, now: Long = System.currentTimeMillis())

    @Query("UPDATE reminders SET sync_state = :state WHERE uuid = :uuid")
    suspend fun setSyncState(uuid: String, state: String)

    /**
     * Hard delete of one profile's rows, used only by a replace-import: the file that follows has to
     * be the whole truth, and a tombstone left behind from the discarded data would suppress a
     * reminder the restored backup actually carries.
     */
    @Query("DELETE FROM reminders WHERE user_id = :userId")
    suspend fun deleteAllReminders(userId: Long)
}
