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

    @Query("SELECT * FROM users WHERE firebase_uid = :uid LIMIT 1")
    suspend fun getUserByFirebaseUid(uid: String): User?

    @Query("SELECT * FROM users")
    suspend fun getAllUsersOnce(): List<User>

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

    /**
     * Marks [user] as current.
     *
     * The profile is updated in place, never re-inserted: INSERT OR REPLACE resolves a primary key
     * conflict by deleting the row first, and that delete cascades to everything a user owns with a
     * foreign key — topics and recurrence series would vanish on a plain profile switch.
     */
    @Transaction
    suspend fun setCurrentUser(user: User) {
        val previouslyCurrentUser = getCurrentUser()

        clearCurrentUser()

        if (previouslyCurrentUser != null) {
            updateUser(previouslyCurrentUser.copy(isCurrent = false))
        }

        updateUser(user.copy(isCurrent = true, lastUsed = System.currentTimeMillis()))
    }

    @Query("UPDATE users SET is_current = 0")
    suspend fun clearCurrentUser()
} 