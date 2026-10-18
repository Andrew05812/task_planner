package com.taskplanner.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.taskplanner.data.model.Task
import com.taskplanner.data.model.User
import com.taskplanner.testing.AppFixture
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
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
 * The session owns "who is logged in". These tests cover the failures that used to destroy or
 * misattribute user data: losing the profile after a restart, saving to the fixed fallback id 0,
 * and two profile switches racing each other.
 */
@RunWith(RobolectricTestRunner::class)
class SessionRepositoryTest {

    private lateinit var fixture: AppFixture
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        fixture = AppFixture(context)
    }

    @After
    fun tearDown() {
        fixture.close()
    }

    @Test
    fun `there is no session before anybody signs in`() = runTest {
        assertNull(fixture.sessionRepository.currentUserId())
        assertNull(fixture.sessionRepository.snapshotUserId())
    }

    @Test
    fun `saves are attributed to the signed in profile`() = runTest {
        val user = fixture.signIn()
        assertEquals(user.id, fixture.sessionRepository.currentUserId())
    }

    @Test
    fun `the profile survives an app restart`() = runTest {
        val user = fixture.signIn()
        fixture.addTask(user.id, "keep me")

        val restarted = fixture.newSessionRepository()

        assertEquals(user.id, restarted.currentUserId())
        assertEquals(listOf("keep me"), restarted.currentUserId()?.let {
            fixture.taskDao.getActiveTasksOnce(it).map { task -> task.title }
        })
    }

    @Test
    fun `a crash that left no current flag is repaired from the stored anchor`() = runTest {
        val user = fixture.signIn(email = "anchor@example.com")
        // simulate the crash window: the row exists, but is_current was never committed
        fixture.userDao.clearCurrentUser()

        val restarted = fixture.newSessionRepository()

        assertEquals(user.id, restarted.currentUserId())
        assertEquals(user.id, fixture.userDao.getCurrentUser()?.id)
    }

    @Test
    fun `signing out clears the session but keeps every row`() = runTest {
        val user = fixture.signIn(email = "signout@example.com")
        val taskId = fixture.addTask(user.id, "important thing")
        val categoryId = fixture.addCategory(user.id, "Home")

        fixture.sessionRepository.endSession()

        assertNull(fixture.sessionRepository.currentUserId())
        assertNotNull("tasks must survive sign out", fixture.taskDao.getTaskById(taskId))
        assertNotNull("categories must survive sign out", fixture.categoryDao.getCategoryById(categoryId))
    }

    @Test
    fun `switching profiles resolves the new user instead of the fallback id`() = runTest {
        val first = fixture.signIn(name = "A", email = "a@example.com")
        fixture.addTask(first.id, "task of A")

        val secondId = fixture.userDao.insertUser(User(email = "b@example.com", name = "B"))
        fixture.sessionRepository.setCurrentUser(fixture.userDao.getUserById(secondId)!!)

        assertEquals(secondId, fixture.sessionRepository.currentUserId())
        assertTrue("no data may be saved for user 0", fixture.taskDao.getActiveTasksOnce(0L).isEmpty())
        assertEquals(listOf("task of A"), fixture.taskDao.getActiveTasksOnce(first.id).map { it.title })
        assertEquals(emptyList<Task>(), fixture.taskDao.getActiveTasksOnce(secondId))
    }

    @Test
    fun `concurrent profile switches leave exactly one current profile`() = runTest {
        val ids = (1..5).map {
            fixture.userDao.insertUser(User(email = "u$it@example.com", name = "U$it"))
        }

        coroutineScope {
            ids.forEach { id ->
                launch {
                    fixture.sessionRepository.setCurrentUser(fixture.userDao.getUserById(id)!!)
                }
            }
        }

        val current = fixture.userDao.getAllUsersOnce().filter { it.isCurrent }
        assertEquals("exactly one profile may be current", 1, current.size)
        assertEquals(current.single().id, fixture.sessionRepository.currentUserId())
    }
}
