package com.taskplanner.data.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.taskplanner.data.model.Category
import com.taskplanner.data.model.RepeatUnit
import com.taskplanner.data.model.Task
import com.taskplanner.data.model.TaskStatus
import com.taskplanner.data.model.User
import com.taskplanner.testing.AppFixture
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The explicit local → Google handover, tested against the same Room database the app uses.
 *
 * This is the path that used to move rows by rewriting their numeric ids and then delete the source
 * profile: a second sign-in duplicated every uuid, and asking for the current profile itself wiped
 * it. The tests below pin the guarantees the handover has to keep.
 */
@RunWith(RobolectricTestRunner::class)
class LocalDataMigrationTest {

    private lateinit var fixture: AppFixture
    private lateinit var local: User

    @Before
    fun signInALocalProfileWithData() {
        val context: Context = ApplicationProvider.getApplicationContext()
        fixture = AppFixture(context)
        local = runBlocking { fixture.signIn(name = "Local Ivan", email = "ivan@local") }
    }

    @After
    fun tearDown() {
        fixture.close()
    }

    @Test
    fun `tasks categories and topics move onto the account that is signed in`() = runTest {
        val categoryId = fixture.addCategory(local.id, "Home")
        val group = fixture.addGroup(local.id, "HOUSEWORK")
        fixture.addTask(local.id, "Pay rent", categoryId = categoryId, groupId = group.uuid)
        fixture.addTask(local.id, "Vacuum")

        val account = signInAsGoogleAccount()
        fixture.authRepository.attachLocalDataToAccount(local.id, account)

        val moved = fixture.taskDao.getAllTasksOnce(account.id)
        assertEquals(setOf("Pay rent", "Vacuum"), moved.map { it.title }.toSet())
        assertEquals("the local profile must be empty after a move", 0, fixture.taskDao.getAllTasksOnce(local.id).size)

        val movedRent = moved.first { it.title == "Pay rent" }
        val linkedCategory = movedRent.categoryId?.let { fixture.categoryDao.getCategoryForUser(account.id, it) }
        assertNotNull("the task must still point at its category", linkedCategory)
        assertEquals("Home", linkedCategory!!.name)
        val movedGroup = fixture.groupDao.getGroupByUuid(group.uuid)
        assertEquals(account.id, movedGroup?.userId)
    }

    @Test
    fun `a repeating series and its lead times move onto the account too`() = runTest {
        val taskId = fixture.addTask(local.id, "Water the plants", dueAt = System.currentTimeMillis() + 6 * 3_600_000L)
        val series = fixture.recurrenceRepository.startSeries(
            fixture.taskDao.getTaskById(taskId)!!, RepeatUnit.WEEK, weekDays = listOf(2, 5)
        )
        assertNotNull(series)
        fixture.reminderRepository.applyReminders(fixture.taskDao.getTaskById(taskId)!!, listOf(15, 60))

        val account = signInAsGoogleAccount()
        fixture.authRepository.attachLocalDataToAccount(local.id, account)

        val movedRule = fixture.recurrenceDao.getAllRowsOnce(account.id).single()
        assertEquals(series!!.uuid, movedRule.uuid)
        assertEquals(account.id, movedRule.userId)
        assertEquals("the pattern must not restart where it began", series.cursorAt, movedRule.cursorAt)
        assertEquals(emptyList<String>(), fixture.recurrenceDao.getAllRowsOnce(local.id).map { it.uuid })

        val movedReminders = fixture.reminderDao.getAllRowsOnce(account.id)
        assertEquals(setOf(15, 60), movedReminders.map { it.offsetMinutes }.toSet())
        assertTrue("every lead time must belong to the account now", movedReminders.all { it.userId == account.id })
        assertEquals(
            "a reminder must still name the task it warns for",
            fixture.taskDao.getAllRowsOnce(account.id).map { it.uuid }.toSet(),
            movedReminders.map { it.taskUuid }.toSet()
        )
        assertEquals(emptyList<String>(), fixture.reminderDao.getAllRowsOnce(local.id).map { it.uuid })
    }

    @Test
    fun `a task the account already has is not copied a second time`() = runTest {
        val sharedUuid = "00000000-0000-0000-0000-0000000000a1"
        val account = signInAsGoogleAccount()
        // The account already holds this task, newer than the local one.
        fixture.taskDao.insertTask(
            Task(
                uuid = sharedUuid, title = "Pay rent (from cloud)", description = "",
                userId = account.id, updatedAt = 5_000
            )
        )
        fixture.switchTo(local)
        fixture.taskDao.insertTask(
            Task(
                uuid = sharedUuid, title = "Pay rent (old local copy)", description = "",
                userId = local.id, updatedAt = 1_000
            )
        )

        fixture.switchTo(account)
        fixture.authRepository.attachLocalDataToAccount(local.id, account)

        val rows = fixture.taskDao.getAllRowsOnce(account.id)
        assertEquals("one uuid must mean exactly one row", listOf("Pay rent (from cloud)"), rows.map { it.title })
    }

    /**
     * The merge that keeps the account's copy of a category must not take the task's category with it.
     *
     * When both profiles hold the same category uuid, the local copy has to go away — and `tasks.category_id`
     * points at categories `ON DELETE SET NULL`. Deleting it while the tasks are still sitting in the source
     * profile leaves each of them with no category, so the map the handover builds in order to re-point them
     * has nothing left to read: the task arrives, and it arrives uncategorised.
     */
    @Test
    fun `a task whose category merged into the account's copy keeps a category`() = runTest {
        val sharedUuid = "00000000-0000-0000-0000-0000000000c1"
        val account = signInAsGoogleAccount()
        val accountCategory = fixture.categoryDao.insertCategory(
            Category(
                uuid = sharedUuid, name = "Home (account)", color = -1,
                icon = "ic_tag", userId = account.id
            )
        )
        fixture.switchTo(local)
        val localCategory = fixture.categoryDao.insertCategory(
            Category(
                uuid = sharedUuid, name = "Home (local)", color = -1,
                icon = "ic_tag", userId = local.id
            )
        )
        fixture.addTask(local.id, "Pay rent", categoryId = localCategory)

        fixture.switchTo(account)
        fixture.authRepository.attachLocalDataToAccount(local.id, account)

        val moved = fixture.taskDao.getAllTasksOnce(account.id).first { it.title == "Pay rent" }
        assertNotNull(
            "the task still names a category — the merge may not quietly un-categorise it",
            moved.categoryId
        )
        assertEquals(
            "and it is the account's copy of the category that merged",
            accountCategory,
            moved.categoryId
        )
        assertEquals(
            "one uuid means one category",
            1,
            fixture.categoryDao.getAllRowsOnce(account.id).count { it.uuid == sharedUuid }
        )
    }

    @Test
    fun `a deletion marker travels with the data so the cloud cannot resurrect it`() = runTest {
        val doomed = fixture.addTask(local.id, "Already thrown away")
        fixture.taskDao.softDeleteTasks(listOf(doomed))

        val account = signInAsGoogleAccount()
        fixture.authRepository.attachLocalDataToAccount(local.id, account)

        assertEquals(
            "a deleted task must not reappear as an active one",
            emptyList<String>(), fixture.taskDao.getAllTasksOnce(account.id).map { it.title }
        )
        val tombstone = fixture.taskDao.getAllRowsOnce(account.id).single()
        assertTrue("the account needs the deletion marker to push it upward", tombstone.isDeleted)
        assertEquals("Already thrown away", tombstone.title)
    }

    @Test
    fun `moving a profile onto itself keeps every row`() = runTest {
        fixture.addTask(local.id, "Keep me")
        val account = fixture.userDao.getUserById(local.id)!!

        fixture.authRepository.attachLocalDataToAccount(local.id, account)

        assertEquals(listOf("Keep me"), fixture.taskDao.getAllTasksOnce(local.id).map { it.title })
    }

    @Test
    fun `the handover refuses to write onto a profile that is not the signed-in one`() = runTest {
        fixture.addTask(local.id, "Mine")
        val google = User(email = "g@example.com", name = "Google Ivan", firebaseUid = "uid-1")
        val otherId = fixture.userDao.insertUser(google)
        val other = fixture.userDao.getUserById(otherId)!!

        val refused = runCatching {
            fixture.authRepository.attachLocalDataToAccount(local.id, other)
        }.exceptionOrNull()

        assertTrue(
            "expected a refusal, got $refused",
            refused is IllegalArgumentException
        )
        assertEquals(
            "a refusal must leave the local data exactly where it was",
            listOf("Mine"), fixture.taskDao.getAllTasksOnce(local.id).map { it.title }
        )
    }

    /**
     * Signing out closes the cloud session and nothing else.
     *
     * This method used to assert `assertNull(sessionRepository.currentUserId())` — that after a sign-out the
     * app resolves to nobody. It was written as a guard against deleting data, and it passed, because the code
     * did keep every row; what it silently accepted was that the rows became unreachable: `endSession()` also
     * cleared `is_current` and dropped the repair anchor, so the next start-up showed `NoUser` and offered a
     * fresh profile while the reader's own work sat in the database underneath. Stage 13's whole subject is
     * that a sign-in must not cost a person their data, and an unreachable one costs them the same thing.
     *
     * The assertion is therefore replaced with a stronger one, not a weaker: the reader is still on the profile
     * they signed into, the rows are readable *through that session*, and the link that makes a second sign-in
     * a return rather than a duplicate is still on the row.
     */
    @Test
    fun `signing out leaves the reader on their profile with the data reachable`() = runTest {
        val account = signInAsGoogleAccount()
        fixture.addTask(account.id, "After the move")

        fixture.authRepository.signOut()

        val still = fixture.sessionRepository.currentUserId()
        assertEquals("signing out of the cloud leaves the profile selected", account.id, still)
        assertEquals(
            "signing out is not deleting",
            listOf("After the move"), fixture.taskDao.getAllTasksOnce(still!!).map { it.title }
        )
        assertEquals(
            "and the profile still knows which Google account it belongs to, so signing in again returns here",
            account.firebaseUid, fixture.userDao.getUserById(account.id)?.firebaseUid
        )
        assertEquals(account.id, fixture.userDao.getUserById(account.id)?.id)
    }

    @Test
    fun `an archived task keeps its archive flag through the move`() = runTest {
        val done = fixture.addTask(local.id, "Fixed the sink")
        fixture.taskDao.updateTask(
            fixture.taskDao.getTaskById(done)!!.copy(
                status = TaskStatus.COMPLETED, archived = true, archivedAt = 2_000
            )
        )

        val account = signInAsGoogleAccount()
        fixture.authRepository.attachLocalDataToAccount(local.id, account)

        val moved = fixture.taskDao.getAllTasksOnce(account.id).single()
        assertEquals("Fixed the sink", moved.title)
        assertTrue("the archive must survive the handover", moved.archived)
        assertEquals(TaskStatus.COMPLETED, moved.status)
    }

    /** Signs in a Google-shaped profile: identity is the uid, and it becomes the active session. */
    private suspend fun signInAsGoogleAccount(): User = fixture.signInAs(
        User(email = "ivan@gmail.com", name = "Ivan", firebaseUid = "uid-${System.nanoTime()}")
    )
}
