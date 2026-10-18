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
}
