package com.taskplanner.data.repository

import com.taskplanner.data.local.TaskDao
import com.taskplanner.data.model.SyncState
import com.taskplanner.data.model.Task
import com.taskplanner.data.model.TaskStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class TaskRepository @Inject constructor(
    private val taskDao: TaskDao,
    private val sessionRepository: SessionRepository,
    private val recurrenceRepository: RecurrenceRepository,
    private val reminderRepository: ReminderRepository,
    private val workTrackingRepository: WorkTrackingRepository
) {
    /** Convenience alias kept for statistics/categories screens. */
    fun getAllTasks(): Flow<List<Task>> = observeActiveTasks()

    fun observeActiveTasks(): Flow<List<Task>> =
        sessionRepository.currentUser.flatMapLatest { user ->
            if (user == null) flowOf(emptyList()) else taskDao.getActiveTasks(user.id)
        }

    fun observeArchivedTasks(): Flow<List<Task>> =
        sessionRepository.currentUser.flatMapLatest { user ->
            if (user == null) flowOf(emptyList()) else taskDao.getArchivedTasks(user.id)
        }

    fun observeTasksByStatus(status: TaskStatus): Flow<List<Task>> =
        sessionRepository.currentUser.flatMapLatest { user ->
            if (user == null) flowOf(emptyList()) else taskDao.getTasksByStatus(user.id, status)
        }

    fun observeTasksByCategory(categoryId: Long): Flow<List<Task>> =
        sessionRepository.currentUser.flatMapLatest { user ->
            if (user == null) flowOf(emptyList()) else taskDao.getTasksByCategory(user.id, categoryId)
        }

    fun observeTaskCountByStatus(status: TaskStatus): Flow<Int> =
        sessionRepository.currentUser.flatMapLatest { user ->
            if (user == null) flowOf(0) else taskDao.getTaskCountByStatus(user.id, status)
        }

    suspend fun getActiveTasksOnce(): List<Task> {
        val userId = sessionRepository.currentUserId() ?: return emptyList()
        return taskDao.getActiveTasksOnce(userId)
    }

    suspend fun getAllTasksOnce(): List<Task> {
        val userId = sessionRepository.currentUserId() ?: return emptyList()
        return taskDao.getAllTasksOnce(userId)
    }

    suspend fun getTaskById(taskId: Long): Task? {
        val userId = sessionRepository.currentUserId() ?: return null
        return taskDao.getTaskForUser(userId, taskId)
    }

    suspend fun insertTask(task: Task): Long {
        val userId = sessionRepository.currentUserId() ?: return -1
        val withIdentity = task.copy(
            userId = userId,
            uuid = task.uuid.ifBlank { UUID.randomUUID().toString() },
            syncState = SyncState.PENDING
        )
        return taskDao.insertTask(withIdentity)
    }

    /** Inserts without forcing the current user — used by import/sync paths. */
    suspend fun insertRaw(task: Task): Long = taskDao.insertTask(task)

    suspend fun updateTask(task: Task) =
        taskDao.updateTask(task.copy(updatedAt = System.currentTimeMillis(), syncState = SyncState.PENDING))

    suspend fun startTask(taskId: Long) = transition(taskId) { task ->
        task.copy(
            status = TaskStatus.IN_PROGRESS,
            startedAt = task.startedAt ?: System.currentTimeMillis()
        )
    }

    suspend fun pauseTask(taskId: Long) = transition(taskId) { task ->
        task.copy(status = TaskStatus.PAUSED)
    }

    suspend fun resumeTask(taskId: Long) = transition(taskId) { task ->
        task.copy(status = TaskStatus.IN_PROGRESS)
    }

    /**
     * DONE: completed + physically kept in the archive, hidden from the active list.
     *
     * Finishing an instance of a series is what tells the series to produce the next one, so the
     * advance happens here rather than in the screen: swipe, editor, notification and sync all
     * reach the task through this method. The same holds for its reminders — a task the user is done
     * with stops making noise, whoever it was that finished it.
     */
    suspend fun completeAndArchive(taskId: Long) {
        val task = taskDao.getTaskById(taskId)
        // A timer left running on a finished task measures nothing anyone will ever read, so it is
        // stopped here — the one place every local completion passes through — and the work it had
        // already measured stays in the history under the task's title.
        task?.let { workTrackingRepository.onTaskCompleted(it) }
        taskDao.archiveTask(taskId)
        recurrenceRepository.ensureOpenInstances()
        task?.let { reminderRepository.onTaskArchived(it) }
    }

    /** Undoing the above: the lead times the task still owns come back on its current deadline. */
    suspend fun restoreTask(taskId: Long) {
        val task = taskDao.getTaskById(taskId)
        taskDao.restoreTask(taskId)
        task?.let { reminderRepository.onTaskRestored(it) }
    }

    suspend fun softDeleteTasks(ids: List<Long>) {
        val rows = taskDao.getByIds(ids)
        taskDao.softDeleteTasks(ids)
        // Deleting the instance a series points at must not strand the series.
        recurrenceRepository.ensureOpenInstances()
        reminderRepository.onTasksDeleted(rows)
    }

    /** Physically dropping a row takes its reminders with it: nothing is left that could still ring. */
    suspend fun hardDeleteTasks(ids: List<Long>) {
        val rows = taskDao.getByIds(ids)
        reminderRepository.retireTasks(rows.map { it.uuid })
        taskDao.deleteTasksByIds(ids)
    }

    /** Single visible delete path: soft delete keeps a tombstone for sync. */
    suspend fun deleteTask(task: Task) = softDeleteTasks(listOf(task.id))

    suspend fun deleteAllTasksForCurrentUser() = softDeleteTasks(getAllTasksOnce().map { it.id })

    suspend fun moveTaskToGroup(taskId: Long, groupUuid: String?) {
        val task = taskDao.getTaskById(taskId) ?: return
        taskDao.updateTask(
            task.copy(
                groupId = groupUuid,
                updatedAt = System.currentTimeMillis(),
                syncState = SyncState.PENDING
            )
        )
    }

    /** Persists a manual drag-and-drop ordering transactionally. */
    suspend fun reorderTasks(orderedIds: List<Long>) = taskDao.updateTaskOrders(orderedIds)

    private suspend fun transition(taskId: Long, transform: (Task) -> Task) {
        val task = taskDao.getTaskById(taskId) ?: return
        taskDao.updateTask(
            transform(task).copy(
                updatedAt = System.currentTimeMillis(),
                syncState = SyncState.PENDING
            )
        )
    }
}
