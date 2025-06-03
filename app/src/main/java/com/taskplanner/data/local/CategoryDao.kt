package com.taskplanner.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.taskplanner.data.model.Category
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories WHERE user_id = :userId ORDER BY name ASC")
    fun getAllCategories(userId: Long): Flow<List<Category>>

    @Query("SELECT * FROM categories WHERE user_id = :userId AND parent_category_id IS NULL ORDER BY name ASC")
    fun getRootCategories(userId: Long): Flow<List<Category>>

    @Query("SELECT * FROM categories WHERE user_id = :userId AND parent_category_id = :parentId ORDER BY name ASC")
    fun getSubcategories(userId: Long, parentId: Long): Flow<List<Category>>

    @Query("SELECT * FROM categories WHERE id = :categoryId")
    suspend fun getCategoryById(categoryId: Long): Category?

    @Query("SELECT * FROM categories WHERE user_id = :userId AND id = :categoryId")
    suspend fun getCategoryById(userId: Long, categoryId: Long): Category?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCategory(category: Category): Long

    @Update
    suspend fun updateCategory(category: Category)

    @Delete
    suspend fun deleteCategory(category: Category)

    @Query("DELETE FROM categories WHERE user_id = :userId")
    suspend fun deleteAllCategories(userId: Long)

    @Query("SELECT COUNT(*) FROM categories WHERE user_id = :userId")
    fun getCategoryCount(userId: Long): Flow<Int>
} 