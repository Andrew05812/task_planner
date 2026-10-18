package com.taskplanner.data.repository

import com.taskplanner.data.local.CategoryDao
import com.taskplanner.data.model.Category
import com.taskplanner.data.model.SyncState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class CategoryRepository @Inject constructor(
    private val categoryDao: CategoryDao,
    private val sessionRepository: SessionRepository
) {
    fun getAllCategories(): Flow<List<Category>> =
        sessionRepository.currentUser.flatMapLatest { user ->
            if (user == null) flowOf(emptyList()) else categoryDao.getAllCategories(user.id)
        }

    fun getRootCategories(): Flow<List<Category>> =
        sessionRepository.currentUser.flatMapLatest { user ->
            if (user == null) flowOf(emptyList()) else categoryDao.getRootCategories(user.id)
        }

    fun getSubcategories(parentId: Long): Flow<List<Category>> =
        sessionRepository.currentUser.flatMapLatest { user ->
            if (user == null) flowOf(emptyList()) else categoryDao.getSubcategories(user.id, parentId)
        }

    suspend fun getCategoryById(categoryId: Long): Category? {
        val userId = sessionRepository.currentUserId() ?: return null
        return categoryDao.getCategoryForUser(userId, categoryId)
    }

    suspend fun getAllCategoriesOnce(): List<Category> {
        val userId = sessionRepository.currentUserId() ?: return emptyList()
        return categoryDao.getAllCategoriesOnce(userId)
    }

    suspend fun insertCategory(category: Category): Long {
        val userId = sessionRepository.currentUserId() ?: return -1
        val withIdentity = category.copy(
            userId = userId,
            uuid = category.uuid.ifBlank { UUID.randomUUID().toString() },
            syncState = SyncState.PENDING
        )
        return categoryDao.insertCategory(withIdentity)
    }

    suspend fun insertRaw(category: Category): Long = categoryDao.insertCategory(category)

    suspend fun updateCategory(category: Category) = categoryDao.updateCategory(
        category.copy(updatedAt = System.currentTimeMillis(), syncState = SyncState.PENDING)
    )

    suspend fun deleteCategory(category: Category) = categoryDao.softDelete(category.id)

    fun getCategoryCount(): Flow<Int> =
        sessionRepository.currentUser.flatMapLatest { user ->
            if (user == null) flowOf(0) else categoryDao.getCategoryCount(user.id)
        }
}
