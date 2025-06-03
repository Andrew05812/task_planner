package com.taskplanner.data.repository

import com.taskplanner.data.local.CategoryDao
import com.taskplanner.data.model.Category
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton
import com.taskplanner.data.repository.UserRepository
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.ExperimentalCoroutinesApi

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class CategoryRepository @Inject constructor(
    private val categoryDao: CategoryDao,
    private val userRepository: UserRepository
) {
    fun getAllCategories(): Flow<List<Category>> = userRepository.getCurrentUserFlow().flatMapLatest { user ->
        categoryDao.getAllCategories(user?.id ?: 0)
    }

    fun getRootCategories(): Flow<List<Category>> = userRepository.getCurrentUserFlow().flatMapLatest { user ->
        categoryDao.getRootCategories(user?.id ?: 0)
    }

    fun getSubcategories(parentId: Long): Flow<List<Category>> = userRepository.getCurrentUserFlow().flatMapLatest { user ->
        categoryDao.getSubcategories(user?.id ?: 0, parentId)
    }

    suspend fun getCategoryById(categoryId: Long): Category? {
        val currentUser = userRepository.getCurrentUser()
        return if (currentUser != null) {
            categoryDao.getCategoryById(currentUser.id, categoryId)
        } else {
            null
        }
    }

    suspend fun insertCategory(category: Category): Long {
        val currentUser = userRepository.getCurrentUser()
        return if (currentUser != null) {
            categoryDao.insertCategory(category.copy(userId = currentUser.id))
        } else {
            -1 // Indicate insertion failed
        }
    }

    suspend fun updateCategory(category: Category) = categoryDao.updateCategory(category)

    suspend fun deleteCategory(category: Category) = categoryDao.deleteCategory(category)

    suspend fun deleteAllCategories() {
        val currentUser = userRepository.getCurrentUser()
        if (currentUser != null) {
            categoryDao.deleteAllCategories(currentUser.id)
        }
    }

    fun getCategoryCount(): Flow<Int> = userRepository.getCurrentUserFlow().flatMapLatest { user ->
        categoryDao.getCategoryCount(user?.id ?: 0)
    }
} 