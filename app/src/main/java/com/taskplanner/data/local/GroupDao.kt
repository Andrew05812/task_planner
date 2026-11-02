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

    /**
     * Writes topics that must not already be there.
     *
     * [upsertGroup] and [upsertGroups] resolve a primary-key conflict by REPLACE, which in SQLite means the
     * existing row is deleted and the new one takes its place. For an import that derives its uuid from the pasted
     * text, "delete what is there" is the one outcome that cannot be allowed — a uuid belonging to another profile
     * would cost that profile its topic — so a Keep import writes through this insert, which aborts and throws
     * instead. `KeepImportTransactionTest` proves the difference is real by putting a foreign row on the uuid.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertImportedGroups(groups: List<TaskGroup>)

    /**
     * Which profile holds each of [uuids], for the collision and idempotency decision an import has to make.
     *
     * Asked by uuid rather than by reading the whole table, because a reader with ten thousand tasks should not
     * have their entire account loaded to answer a question about 382 uuids.
     */
    @Query("SELECT uuid AS uuid, user_id AS userId FROM task_groups WHERE uuid IN (:uuids)")
    suspend fun getGroupOwners(uuids: List<String>): List<RowOwner>

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
