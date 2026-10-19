package com.taskplanner.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.taskplanner.data.model.Task
import com.taskplanner.data.model.TaskStatus
import kotlinx.coroutines.flow.Flow

/**
 * The calendar's window query, spelled once so the live flow and the one-shot read the screens use
 * cannot drift apart — and so the benchmark in `CalendarWindowBenchmarkTest` measures the statement
 * that actually ships instead of a copy of it.
 *
 * See [TaskDao.observeWindow] for what a window contains and why this is four lookups.
 */
internal const val WINDOW_QUERY: String =
    "SELECT * FROM tasks WHERE id IN (" +
        "SELECT id FROM tasks WHERE user_id = :userId AND is_deleted = 0 AND archived = 0 " +
        "AND due_date >= :from AND due_date < :to UNION " +
        "SELECT id FROM tasks WHERE user_id = :userId AND is_deleted = 0 AND archived = 0 " +
        "AND start_time >= :from AND start_time < :to UNION " +
        "SELECT id FROM tasks WHERE user_id = :userId AND is_deleted = 0 AND archived = 0 " +
        "AND end_time >= :from AND end_time < :to UNION " +
        "SELECT id FROM tasks WHERE user_id = :userId AND is_deleted = 0 AND archived = 0 " +
        "AND start_time < :from AND end_time >= :to" +
        ") ORDER BY COALESCE(start_time, due_date, end_time) ASC, id ASC"

@Dao
interface TaskDao {

    @Query(
        "SELECT * FROM tasks WHERE user_id = :userId AND archived = 0 AND is_deleted = 0 " +
            "ORDER BY `order` ASC, due_date ASC"
    )
    fun getActiveTasks(userId: Long): Flow<List<Task>>

    @Query(
        "SELECT * FROM tasks WHERE user_id = :userId AND archived = 0 AND is_deleted = 0 " +
            "ORDER BY `order` ASC, due_date ASC"
    )
    suspend fun getActiveTasksOnce(userId: Long): List<Task>

    @Query(
        "SELECT * FROM tasks WHERE user_id = :userId AND archived = 1 AND is_deleted = 0 " +
            "ORDER BY archived_at DESC"
    )
    fun getArchivedTasks(userId: Long): Flow<List<Task>>

    @Query(
        "SELECT * FROM tasks WHERE user_id = :userId AND status = :status " +
            "AND archived = 0 AND is_deleted = 0 ORDER BY `order` ASC, due_date ASC"
    )
    fun getTasksByStatus(userId: Long, status: TaskStatus): Flow<List<Task>>

    @Query(
        "SELECT * FROM tasks WHERE user_id = :userId AND category_id = :categoryId " +
            "AND is_deleted = 0 ORDER BY `order` ASC, due_date ASC"
    )
    fun getTasksByCategory(userId: Long, categoryId: Long): Flow<List<Task>>

    @Query(
        "SELECT * FROM tasks WHERE user_id = :userId AND due_date BETWEEN :startMillis AND :endMillis " +
            "AND is_deleted = 0 ORDER BY due_date ASC"
    )
    fun getTasksByDateRange(userId: Long, startMillis: Long, endMillis: Long): Flow<List<Task>>

    @Query("SELECT * FROM tasks WHERE id = :taskId AND is_deleted = 0")
    suspend fun getTaskById(taskId: Long): Task?

    /**
     * The tasks one calendar window has to draw, live: the flow re-emits after an edit, a drag and
     * drop, a sync pull or a series producing its next instance, so the screen never reloads
     * anything it did not ask for.
     *
     * The window is half-open, `[from, to)`, and a task belongs to it if *any* part of the task lands
     * inside: NULL in these columns means "the user never entered that", and a NULL satisfies no
     * comparison, so each of the four lookups asks only for the one column that bounds it — a task
     * with only a deadline is matched by the first, and a block that starts before the window and
     * ends after it by the last, instead of vanishing from both neighbouring screens.
     *
     * Four lookups rather than one OR of four clauses because that is the shape the planner can use:
     * an OR across three different columns leaves it nothing but the user_id index, so the read walks
     * the whole profile — measured at 3.3 ms for a thousand tasks, 5.4 ms for five thousand and 8.3 ms
     * for ten thousand. With one seekable range per lookup the same window reads in about 2.8 ms at
     * every one of those sizes, so what sets the cost is what the window holds, not how much the user
     * has written. `CalendarWindowBenchmarkTest` measures it and keeps it that way.
     */
    @Query(WINDOW_QUERY)
    fun observeWindow(userId: Long, from: Long, to: Long): Flow<List<Task>>

    /** The same window read once, for callers that are computing rather than observing. */
    @Query(WINDOW_QUERY)
    suspend fun getWindowOnce(userId: Long, from: Long, to: Long): List<Task>

    /** The rows behind a bulk action, before it happens: deleting tasks also has to tell reminders. */
    @Query("SELECT * FROM tasks WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<Task>

    /** The uuids of a series' instances in any state, so the whole series can be cleaned up at once. */
    @Query("SELECT uuid FROM tasks WHERE series_uuid = :seriesUuid")
    suspend fun getSeriesTaskUuids(seriesUuid: String): List<String>

    @Query("SELECT * FROM tasks WHERE user_id = :userId AND id = :taskId AND is_deleted = 0")
    suspend fun getTaskForUser(userId: Long, taskId: Long): Task?

    @Query("SELECT * FROM tasks WHERE uuid = :uuid AND is_deleted = 0 LIMIT 1")
    suspend fun getTaskByUuid(uuid: String): Task?

    /**
     * The row a series materialised for one scheduled instant, tombstones and archive included:
     * a series has to tell "the instance is still open" apart from "there is no instance yet".
     */
    @Query(
        "SELECT * FROM tasks WHERE series_uuid = :seriesUuid AND occurrence_at = :occurrenceAt LIMIT 1"
    )
    suspend fun findSeriesInstance(seriesUuid: String, occurrenceAt: Long): Task?

    /** Newest row of a series in any state — the content a future instance is built from. */
    @Query("SELECT * FROM tasks WHERE series_uuid = :seriesUuid ORDER BY occurrence_at DESC LIMIT 1")
    suspend fun getNewestSeriesInstance(seriesUuid: String): Task?

    /** Open instances of a series: what "delete the series" takes with it. */
    @Query(
        "SELECT * FROM tasks WHERE series_uuid = :seriesUuid AND is_deleted = 0 AND archived = 0 " +
            "AND status != 'COMPLETED'"
    )
    suspend fun getOpenSeriesInstances(seriesUuid: String): List<Task>

    /** Sync needs to see tombstones too, otherwise a deleted row could be re-inserted. */
    @Query("SELECT * FROM tasks WHERE uuid = :uuid LIMIT 1")
    suspend fun findTaskByUuid(uuid: String): Task?

    @Query("SELECT * FROM tasks WHERE user_id = :userId AND is_deleted = 0")
    suspend fun getAllTasksOnce(userId: Long): List<Task>

    /** Includes tombstones: the sync queue has to push deletions too. */
    @Query("SELECT * FROM tasks WHERE user_id = :userId")
    suspend fun getAllRowsOnce(userId: Long): List<Task>

    @Query("SELECT * FROM tasks WHERE sync_state != 'SYNCED' OR is_deleted = 1")
    suspend fun getPendingSyncTasks(): List<Task>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: Task): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTasks(tasks: List<Task>)

    /**
     * Materialises a series instance. Conflicts are ignored on purpose: an instance that already
     * exists (from an earlier retry, a restart, or a pull from another device) must never be
     * overwritten, because the user may have edited it in the meantime.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSeriesInstance(task: Task): Long

    @Update
    suspend fun updateTask(task: Task)

    @Update
    suspend fun updateTasks(tasks: List<Task>)

    @Delete
    suspend fun deleteTask(task: Task)

    @Query("DELETE FROM tasks WHERE id IN (:ids)")
    suspend fun deleteTasksByIds(ids: List<Long>)

    @Query("DELETE FROM tasks WHERE user_id = :userId")
    suspend fun deleteAllTasks(userId: Long)

    @Query("UPDATE tasks SET `order` = :order WHERE id = :id")
    suspend fun updateTaskOrder(id: Long, order: Int)

    @Transaction
    suspend fun updateTaskOrders(orderedIds: List<Long>) {
        orderedIds.forEachIndexed { index, id -> updateTaskOrder(id, index) }
    }

    @Query(
        "UPDATE tasks SET archived = 1, archived_at = :now, status = 'COMPLETED', " +
            "updated_at = :now, sync_state = 'PENDING' WHERE id = :taskId"
    )
    suspend fun archiveTask(taskId: Long, now: Long = System.currentTimeMillis())

    @Query(
        "UPDATE tasks SET archived = 0, archived_at = NULL, status = 'TODO', " +
            "updated_at = :now, sync_state = 'PENDING' WHERE id = :taskId"
    )
    suspend fun restoreTask(taskId: Long, now: Long = System.currentTimeMillis())

    @Query(
        "UPDATE tasks SET is_deleted = 1, deleted_at = :now, sync_state = 'DELETING', " +
            "group_id = NULL WHERE id IN (:ids)"
        )
    @Transaction
    suspend fun softDeleteTasks(ids: List<Long>, now: Long = System.currentTimeMillis())

    @Query("UPDATE tasks SET sync_state = :state WHERE uuid = :uuid")
    suspend fun setSyncState(uuid: String, state: String)

    @Query("UPDATE tasks SET uuid = :uuid WHERE id = :id")
    suspend fun setUuid(id: Long, uuid: String)

    /** Re-points an instance at the scheduled instant the series now uses for it. */
    @Query(
        "UPDATE tasks SET occurrence_at = :occurrenceAt, updated_at = :now, sync_state = 'PENDING' " +
            "WHERE id = :taskId"
    )
    suspend fun setInstanceOccurrence(taskId: Long, occurrenceAt: Long, now: Long = System.currentTimeMillis())

    @Query(
        "SELECT COUNT(*) FROM tasks WHERE user_id = :userId AND status = :status " +
            "AND is_deleted = 0 AND archived = 0"
    )
    fun getTaskCountByStatus(userId: Long, status: TaskStatus): Flow<Int>

    @Query("SELECT COUNT(*) FROM tasks WHERE uuid IN (:uuids) AND is_deleted = 0")
    suspend fun countExistingUuids(uuids: List<String>): Int
}
