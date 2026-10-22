package com.taskplanner.data.repository

import android.database.sqlite.SQLiteConstraintException
import com.taskplanner.data.local.UserDao
import com.taskplanner.data.model.User
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What asking for a new profile produced.
 *
 * Two answers, because there are exactly two honest ones: a profile was created, or this address already
 * belongs to one. There is no third where the existing profile is quietly rewritten, because that is what
 * used to happen and it cost people their data.
 */
sealed class ProfileCreation {
    data class Created(val user: User) : ProfileCreation()
    object EmailTaken : ProfileCreation()
}

@Singleton
class UserRepository @Inject constructor(
    private val userDao: UserDao
) {
    suspend fun getUserByEmail(email: String): User? =
        userDao.getUserByEmail(email)

    suspend fun getUserByFirebaseUid(uid: String): User? =
        userDao.getUserByFirebaseUid(uid)

    suspend fun getAllUsersOnce(): List<User> = userDao.getAllUsersOnce()

    suspend fun getUserById(userId: Long): User? =
        userDao.getUserById(userId)

    fun getAllUsers(): Flow<List<User>> =
        userDao.getAllUsers()

    suspend fun insertUser(user: User): Long =
        userDao.insertUser(user)

    /**
     * Creates a profile, or says that the address is taken.
     *
     * The unique index is the check, so there is no window between looking and writing in which a second
     * request could slip the same address in: the write itself is what refuses, and a refusal removes nothing.
     * A caller that looked first and inserted second would still have to answer this exception, because two
     * screens can pass the same check at once.
     */
    suspend fun createUser(user: User): ProfileCreation = try {
        ProfileCreation.Created(user.copy(id = userDao.insertUser(user)))
    } catch (e: SQLiteConstraintException) {
        ProfileCreation.EmailTaken
    }

    suspend fun updateUser(user: User) =
        userDao.updateUser(user)

    suspend fun deleteUser(user: User) =
        userDao.deleteUser(user)

    suspend fun isEmailTaken(email: String, excludeUserId: Long = 0): Boolean =
        userDao.isEmailTaken(email, excludeUserId) > 0

    suspend fun setCurrentUser(user: User) =
        userDao.setCurrentUser(user)

    suspend fun getCurrentUser(): User? =
        userDao.getCurrentUser()

    fun getCurrentUserFlow(): Flow<User?> =
        userDao.getCurrentUserFlow()
} 