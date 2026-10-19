package com.taskplanner.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.taskplanner.data.model.RecurrenceRule
import kotlinx.coroutines.flow.Flow

@Dao
interface RecurrenceDao {

    @Query("SELECT * FROM recurrence_rules WHERE uuid = :uuid LIMIT 1")
    suspend fun findByUuid(uuid: String): RecurrenceRule?

    /** Live series of a profile: neither paused, finished nor deleted — the ones that generate. */
    @Query(
        "SELECT * FROM recurrence_rules WHERE user_id = :userId AND is_deleted = 0 " +
            "AND paused = 0 AND ended_at IS NULL ORDER BY cursor_at ASC"
    )
    suspend fun getOpenRules(userId: Long): List<RecurrenceRule>

    @Query(
        "SELECT * FROM recurrence_rules WHERE user_id = :userId AND is_deleted = 0 " +
            "ORDER BY cursor_at ASC"
    )
    fun observeRules(userId: Long): Flow<List<RecurrenceRule>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRule(rule: RecurrenceRule): Long

    /**
     * A profile's series as a backup sees them: a paused or finished series is still something the
     * user set up, and dropping it would restart the pattern on the next device.
     */
    @Query(
        "SELECT * FROM recurrence_rules WHERE user_id = :userId AND is_deleted = 0 " +
            "ORDER BY cursor_at ASC"
    )
    suspend fun getAllRulesOnce(userId: Long): List<RecurrenceRule>

    /** Includes tombstones: the sync queue has to push a deleted series too. */
    @Query("SELECT * FROM recurrence_rules WHERE user_id = :userId")
    suspend fun getAllRowsOnce(userId: Long): List<RecurrenceRule>

    @Query("UPDATE recurrence_rules SET sync_state = :state WHERE uuid = :uuid")
    suspend fun setSyncState(uuid: String, state: String)

    /**
     * Hard delete for one profile, used by a destructive replace import: the tasks the series
     * produced are being replaced in the same pass, so leaving the rule behind would make it produce
     * an instance the restored backup knows nothing about.
     */
    @Query("DELETE FROM recurrence_rules WHERE user_id = :userId")
    suspend fun deleteAllRules(userId: Long)
}
