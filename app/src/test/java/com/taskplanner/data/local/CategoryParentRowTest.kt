package com.taskplanner.data.local

import android.database.sqlite.SQLiteConstraintException
import androidx.test.core.app.ApplicationProvider
import com.taskplanner.data.model.Category
import com.taskplanner.data.model.Task
import com.taskplanner.data.model.User
import com.taskplanner.testing.AppFixture
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The re-insert trap one table down from the profile.
 *
 * A category is a parent row too: `tasks.category_id` points at it `ON DELETE SET NULL`. `INSERT OR REPLACE`
 * resolves a primary-key conflict by deleting the row first, so a caller that "updates" a category by
 * inserting it with the id it already has leaves the category in place and takes away the one fact this link
 * carries — which category the task is in. Nothing is counted differently afterwards: the category is there,
 * the task is there, and the task belongs to no category.
 *
 * Both the backup merge and the cloud applier had that shape. Both now say `update`, and the DAO refuses the
 * old way instead of resolving it by deletion.
 */
@RunWith(RobolectricTestRunner::class)
class CategoryParentRowTest {

    private lateinit var fixture: AppFixture

    @Before
    fun setUp() {
        fixture = AppFixture(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        fixture.close()
    }

    /**
     * The premise of this file, measured: a deleted category really does un-categorise its tasks.
     *
     * Asserted on a deliberate delete, because that is the only place the behaviour is wanted. If it ever
     * stopped happening, the refusal added below would be protecting nothing and the documentation beside it
     * would be describing an engine that no longer exists.
     */
    @Test
    fun `deleting a category un-categorises the tasks that pointed at it`() {
        val userId = runBlocking { fixture.userDao.insertUser(User(email = "anna@mail.ru", name = "Анна")) }
        val categoryId = runBlocking {
            fixture.categoryDao.insertCategory(
                Category(name = "Диссертация", color = -1, icon = "ic_tag", userId = userId)
            )
        }
        val task = runBlocking {
            fixture.taskDao.insertTask(
                Task(title = "Глава 1", description = "", userId = userId, categoryId = categoryId)
            )
        }

        runBlocking {
            fixture.categoryDao.deleteCategory(
                fixture.categoryDao.getCategoryForUser(userId, categoryId)!!
            )
        }

        assertNull(
            "the link is `ON DELETE SET NULL`, which is why a category may not be deleted by accident",
            runBlocking { fixture.taskDao.getByIds(listOf(task)).first().categoryId }
        )
    }

    @Test
    fun `a category is inserted once and never re-inserted under its own id`() {
        val userId = runBlocking { fixture.userDao.insertUser(User(email = "anna@mail.ru", name = "Анна")) }
        val categoryId = runBlocking {
            fixture.categoryDao.insertCategory(
                Category(name = "Диссертация", color = -1, icon = "ic_tag", userId = userId)
            )
        }
        val task = runBlocking {
            fixture.taskDao.insertTask(
                Task(title = "Глава 1", description = "", userId = userId, categoryId = categoryId)
            )
        }
        val existing = runBlocking { fixture.categoryDao.getCategoryForUser(userId, categoryId) }!!

        // The shape the backup merge and the sync applier used for "the incoming copy is newer".
        val attempt = runCatching {
            runBlocking { fixture.categoryDao.insertCategory(existing.copy(name = "Диссертация (черновик)")) }
        }
        assertTrue(
            "the DAO refuses a re-insert rather than deleting the category behind it: $attempt",
            attempt.exceptionOrNull() is SQLiteConstraintException
        )
        assertEquals(
            "and the task kept its category through the attempt",
            categoryId,
            runBlocking { fixture.taskDao.getByIds(listOf(task)).first().categoryId }
        )

        // What a caller that means "change this category" actually does.
        runBlocking { fixture.categoryDao.updateCategory(existing.copy(name = "Диссертация (черновик)")) }
        assertEquals(
            "the update reached the row the caller named",
            "Диссертация (черновик)",
            runBlocking { fixture.categoryDao.getCategoryForUser(userId, categoryId) }?.name
        )
        assertEquals(
            "and the task is still in it",
            categoryId,
            runBlocking { fixture.taskDao.getByIds(listOf(task)).first().categoryId }
        )
    }
}
