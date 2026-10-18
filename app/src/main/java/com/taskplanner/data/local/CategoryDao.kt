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

    @Query("SELECT * FROM categories WHERE user_id = :userId AND is_deleted = 0 ORDER BY name ASC")
    fun getAllCategories(userId: Long): Flow<List<Category>>

    @Query("SELECT * FROM categories WHERE user_id = :userId AND is_deleted = 0 ORDER BY name ASC")
    suspend fun getAllCategoriesOnce(userId: Long): List<Category>

    /** Includes tombstones so deletions can be pushed to the cloud. */
    @Query("SELECT * FROM categories WHERE user_id = :userId")
    suspend fun getAllRowsOnce(userId: Long): List<Category>

    @Query(
        "SELECT * FROM categories WHERE user_id = :userId AND parent_category_id IS NULL " +
            "AND is_deleted = 0 ORDER BY name ASC"
    )
    fun getRootCategories(userId: Long): Flow<List<Category>>

    @Query(
        "SELECT * FROM categories WHERE user_id = :userId AND parent_category_id = :parentId " +
            "AND is_deleted = 0 ORDER BY name ASC"
    )
    fun getSubcategories(userId: Long, parentId: Long): Flow<List<Category>>

    @Query("SELECT * FROM categories WHERE id = :categoryId")
    suspend fun getCategoryById(categoryId: Long): Category?

    @Query("SELECT * FROM categories WHERE user_id = :userId AND id = :categoryId")
    suspend fun getCategoryForUser(userId: Long, categoryId: Long): Category?

    @Query("SELECT * FROM categories WHERE uuid = :uuid LIMIT 1")
    suspend fun getCategoryByUuid(uuid: String): Category?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCategory(category: Category): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCategories(categories: List<Category>)

    @Update
    suspend fun updateCategory(category: Category)

    @Delete
    suspend fun deleteCategory(category: Category)

    @Query(
        "UPDATE categories SET is_deleted = 1, deleted_at = :now, sync_state = 'DELETING' " +
            "WHERE id = :categoryId"
    )
    suspend fun softDelete(categoryId: Long, now: Long = System.currentTimeMillis())

    @Query("DELETE FROM categories WHERE user_id = :userId")
    suspend fun deleteAllCategories(userId: Long)

    @Query("UPDATE categories SET sync_state = :state WHERE uuid = :uuid")
    suspend fun setSyncState(uuid: String, state: String)

    @Query("UPDATE categories SET uuid = :uuid WHERE id = :id")
    suspend fun setUuid(id: Long, uuid: String)

    @Query("SELECT COUNT(*) FROM categories WHERE user_id = :userId AND is_deleted = 0")
    fun getCategoryCount(userId: Long): Flow<Int>
}
