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

    /**
     * Inserts a category. It never replaces one, because replacing is a delete here.
     *
     * `tasks.category_id` points at this row `ON DELETE SET NULL`, so an `INSERT OR REPLACE` that lands on an
     * existing id removes the category and then re-adds it — and every task that was in it comes back with no
     * category. The row count looks untouched, which is how this survived every test that only counted
     * categories. A caller that means "change this category" says so with [updateCategory]; the primary key
     * conflict is otherwise a bug, and it is now reported as one instead of silently resolving itself.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCategory(category: Category): Long

    /**
     * Bulk upsert, with every hazard [insertCategory] just described and none of its callers: nothing in this
     * app calls this today. Kept because a future bulk load may want it, but a caller has to know that a row
     * written back with its own id is deleted and re-added first, which un-categorises its tasks — the update
     * belongs in [updateCategory], per row.
     */
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

    /**
     * The signal a report watches so its category section cannot outlive a rename.
     *
     * A category's name and colour are printed rather than computed, and they live only on this table: a task
     * is not touched when its category is renamed, so no task-side signal moves. Without this one, an open
     * report would keep printing the old names beside the same figures.
     */
    @Query("SELECT COALESCE(MAX(updated_at), 0) FROM categories WHERE user_id = :userId")
    fun observeChanges(userId: Long): Flow<Long>
}
