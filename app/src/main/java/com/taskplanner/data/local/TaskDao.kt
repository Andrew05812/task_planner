package com.taskplanner.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.taskplanner.data.model.Task
import com.taskplanner.data.model.TaskStatus
import kotlinx.coroutines.flow.Flow
import java.util.Date

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks WHERE user_id = :userId ORDER BY `order` ASC, due_date ASC")
    fun getAllTasks(userId: Long): Flow<List<Task>>

    @Query("SELECT * FROM tasks WHERE user_id = :userId AND status = :status ORDER BY `order` ASC, due_date ASC")
    fun getTasksByStatus(userId: Long, status: TaskStatus): Flow<List<Task>>

    @Query("SELECT * FROM tasks WHERE user_id = :userId AND category_id = :categoryId ORDER BY `order` ASC, due_date ASC")
    fun getTasksByCategory(userId: Long, categoryId: Long): Flow<List<Task>>

    @Query("SELECT * FROM tasks WHERE user_id = :userId AND due_date BETWEEN :startDate AND :endDate ORDER BY due_date ASC")
    fun getTasksByDateRange(userId: Long, startDate: Date, endDate: Date): Flow<List<Task>>

    @Query("SELECT * FROM tasks WHERE id = :taskId")
    suspend fun getTaskById(taskId: Long): Task?

    @Query("SELECT * FROM tasks WHERE user_id = :userId AND id = :taskId")
    suspend fun getTaskById(userId: Long, taskId: Long): Task?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: Task): Long

    @Update
    suspend fun updateTask(task: Task)

    @Delete
    suspend fun deleteTask(task: Task)

    @Query("DELETE FROM tasks WHERE user_id = :userId")
    suspend fun deleteAllTasks(userId: Long)

    @Query("SELECT COUNT(*) FROM tasks WHERE user_id = :userId AND status = :status")
    fun getTaskCountByStatus(userId: Long, status: TaskStatus): Flow<Int>
} 