package com.taskplanner.data.repository

import com.taskplanner.data.local.TaskDao
import com.taskplanner.data.model.Task
import com.taskplanner.data.model.TaskStatus
import kotlinx.coroutines.flow.Flow
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton
import com.taskplanner.data.repository.UserRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.ExperimentalCoroutinesApi

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class TaskRepository @Inject constructor(
    private val taskDao: TaskDao,
    private val userRepository: UserRepository
) {
    fun getAllTasks(): Flow<List<Task>> = userRepository.getCurrentUserFlow().flatMapLatest { user ->
        taskDao.getAllTasks(user?.id ?: 0)
    }

    fun getTasksByStatus(status: TaskStatus): Flow<List<Task>> = userRepository.getCurrentUserFlow().flatMapLatest { user ->
        taskDao.getTasksByStatus(user?.id ?: 0, status)
    }

    fun getTasksByCategory(categoryId: Long): Flow<List<Task>> = userRepository.getCurrentUserFlow().flatMapLatest { user ->
        taskDao.getTasksByCategory(user?.id ?: 0, categoryId)
    }

    fun getTasksByDateRange(startDate: Date, endDate: Date): Flow<List<Task>> = userRepository.getCurrentUserFlow().flatMapLatest { user ->
        taskDao.getTasksByDateRange(user?.id ?: 0, startDate, endDate)
    }

    suspend fun getTaskById(taskId: Long): Task? {
        val currentUser = userRepository.getCurrentUser()
        return if (currentUser != null) {
            taskDao.getTaskById(currentUser.id, taskId)
        } else {
            null
        }
    }

    suspend fun insertTask(task: Task): Long {
        val currentUser = userRepository.getCurrentUser()
        return if (currentUser != null) {
            taskDao.insertTask(task.copy(userId = currentUser.id))
        } else {
            -1 // Indicate insertion failed
        }
    }

    suspend fun updateTask(task: Task) = taskDao.updateTask(task)

    suspend fun deleteTask(task: Task) = taskDao.deleteTask(task)

    suspend fun deleteAllTasks() {
        val currentUser = userRepository.getCurrentUser()
        if (currentUser != null) {
            taskDao.deleteAllTasks(currentUser.id)
        }
    }

    fun getTaskCountByStatus(status: TaskStatus): Flow<Int> = userRepository.getCurrentUserFlow().flatMapLatest { user ->
        taskDao.getTaskCountByStatus(user?.id ?: 0, status)
    }
} 