package com.taskplanner.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.taskplanner.data.model.TaskGroup
import kotlinx.coroutines.flow.Flow

@Dao
interface GroupDao {

    @Query(
        "SELECT * FROM task_groups WHERE user_id = :userId AND is_deleted = 0 " +
            "ORDER BY sort_order ASC, created_at ASC"
    )
    fun getGroups(userId: Long): Flow<List<TaskGroup>>

    @Query(
        "SELECT * FROM task_groups WHERE user_id = :userId AND is_deleted = 0 " +
            "ORDER BY sort_order ASC, created_at ASC"
    )
    suspend fun getGroupsOnce(userId: Long): List<TaskGroup>

    @Query("SELECT * FROM task_groups WHERE uuid = :uuid")
    suspend fun getGroupByUuid(uuid: String): TaskGroup?

    @Query(
        "SELECT COALESCE(MAX(sort_order), -1) + 1 FROM task_groups " +
            "WHERE user_id = :userId AND is_deleted = 0"
    )
    suspend fun nextSortOrder(userId: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGroup(group: TaskGroup)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGroups(groups: List<TaskGroup>)

    @Update
    suspend fun updateGroup(group: TaskGroup)

    @Query(
        "UPDATE task_groups SET collapsed = :collapsed, updated_at = :now, sync_state = 'PENDING' " +
            "WHERE uuid = :uuid"
    )
    suspend fun setCollapsed(uuid: String, collapsed: Boolean, now: Long = System.currentTimeMillis())

    @Query(
        "UPDATE task_groups SET name = :name, updated_at = :now, sync_state = 'PENDING' " +
            "WHERE uuid = :uuid"
    )
    suspend fun rename(uuid: String, name: String, now: Long = System.currentTimeMillis())

    @Query(
        "UPDATE task_groups SET is_deleted = 1, deleted_at = :now, sync_state = 'DELETING' " +
            "WHERE uuid = :uuid"
    )
    suspend fun softDelete(uuid: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE task_groups SET sync_state = :state WHERE uuid = :uuid")
    suspend fun setSyncState(uuid: String, state: String)

    @Query("SELECT * FROM task_groups WHERE user_id = :userId")
    suspend fun getAllGroupsOnce(userId: Long): List<TaskGroup>

    @Query("DELETE FROM task_groups WHERE user_id = :userId")
    suspend fun deleteAllGroups(userId: Long)

    @Delete
    suspend fun deleteGroup(group: TaskGroup)
}
