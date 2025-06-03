package com.taskplanner.data.repository

import com.taskplanner.data.local.UserDao
import com.taskplanner.data.model.User
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UserRepository @Inject constructor(
    private val userDao: UserDao
) {
    suspend fun getUserByEmail(email: String): User? =
        userDao.getUserByEmail(email)

    suspend fun getUserById(userId: Long): User? =
        userDao.getUserById(userId)

    fun getAllUsers(): Flow<List<User>> =
        userDao.getAllUsers()

    suspend fun insertUser(user: User): Long =
        userDao.insertUser(user)

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