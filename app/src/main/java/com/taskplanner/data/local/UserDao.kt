package com.taskplanner.data.local

import androidx.room.*
import com.taskplanner.data.model.User
import kotlinx.coroutines.flow.Flow

@Dao
interface UserDao {
    @Query("SELECT * FROM users ORDER BY last_used DESC")
    fun getAllUsers(): Flow<List<User>>

    @Query("SELECT * FROM users WHERE id = :userId")
    suspend fun getUserById(userId: Long): User?

    @Query("SELECT * FROM users WHERE email = :email")
    suspend fun getUserByEmail(email: String): User?

    @Query("SELECT EXISTS(SELECT 1 FROM users WHERE email = :email AND id != :excludeUserId)")
    suspend fun isEmailTaken(email: String, excludeUserId: Long = 0): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUser(user: User): Long

    @Update
    suspend fun updateUser(user: User)

    @Delete
    suspend fun deleteUser(user: User)

    @Query("DELETE FROM users")
    suspend fun deleteAllUsers()

    @Query("SELECT * FROM users WHERE is_current = 1 LIMIT 1")
    suspend fun getCurrentUser(): User?

    @Query("SELECT * FROM users WHERE is_current = 1 LIMIT 1")
    fun getCurrentUserFlow(): Flow<User?>

    @Query("SELECT * FROM users ORDER BY last_used DESC LIMIT 5")
    fun getRecentUsers(): Flow<List<User>>

    @Transaction
    suspend fun setCurrentUser(user: User) {
        // Get the currently active user if any
        val previouslyCurrentUser = getCurrentUser()

        // Reset current user flag for all users
        clearCurrentUser()

        // If there was a previously current user, ensure its isCurrent flag is false in the database
        if (previouslyCurrentUser != null) {
             val updatedPreviouslyUser = previouslyCurrentUser.copy(isCurrent = false)
             updateUser(updatedPreviouslyUser) // Explicitly update the previously current user
        }

        // Set the new current user with updated last used timestamp
        val updatedUser = user.copy(
            isCurrent = true,
            lastUsed = System.currentTimeMillis()
        )
        insertUser(updatedUser)
    }

    @Query("UPDATE users SET is_current = 0")
    suspend fun clearCurrentUser()
} 