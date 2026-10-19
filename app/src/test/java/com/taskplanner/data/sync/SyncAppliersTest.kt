package com.taskplanner.data.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.taskplanner.data.model.RecurrenceRule
import com.taskplanner.data.model.Reminder
import com.taskplanner.data.model.RepeatUnit
import com.taskplanner.data.model.SyncState
import com.taskplanner.data.model.Task
import com.taskplanner.data.model.TaskStatus
import com.taskplanner.data.recurrence.RecurrenceCalculator
import com.taskplanner.testing.AppFixture
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What a document that came out of the cloud does to this device's database.
 *
 * Firebase itself is not reachable from these tests — the repository ships no project and no keys —
 * so the network layer is not proven here. What is proven is the part that decides whether a user's
 * own edit survives a pull, because that is ordinary code over a real Room database.
 */
@RunWith(RobolectricTestRunner::class)
class SyncAppliersTest {

    private lateinit var fixture: AppFixture
    private lateinit var appliers: SyncAppliers

    private val week = 7 * 24 * 3_600_000L

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        fixture = AppFixture(context)
        appliers = fixture.syncAppliers
    }

    @After
    fun tearDown() {
        fixture.close()
    }

    private suspend fun signedIn() = fixture.signIn(email = "appliers@example.com")

    private fun rule(
        userId: Long,
        uuid: String = "series-1",
        unit: RepeatUnit = RepeatUnit.WEEK,
        interval: Int = 2,
        weekDays: String = "1,3",
        anchorAt: Long = 1_700_000_000_000L,
        cursorAt: Long = 1_700_600_000_000L,
        syncState: SyncState = SyncState.SYNCED,
        updatedAt: Long = 100L
    ) = RecurrenceRule(
        uuid = uuid,
        userId = userId,
        unit = unit,
        interval = interval,
        weekDays = weekDays,
        anchorAt = anchorAt,
        zoneId = "Europe/Berlin",
        cursorAt = cursorAt,
        syncState = syncState,
        createdAt = 1L,
        updatedAt = updatedAt
    )

    private fun remoteRule(
        uuid: String = "series-1",
        unit: RepeatUnit = RepeatUnit.DAY,
        interval: Int = 1,
        weekDays: String = "",
        anchorAt: Long = 1_700_000_000_000L,
        cursorAt: Long = 1_700_600_000_000L,
        isDeleted: Boolean = false,
        deletedAt: Long? = null,
        updatedAt: Long = 200L
    ) = SyncedRule(
        uuid = uuid,
        unit = unit,
        interval = interval,
        weekDays = weekDays,
        dayOfMonth = 0,
        anchorAt = anchorAt,
        zoneId = "Europe/Berlin",
        cursorAt = cursorAt,
        paused = false,
        endedAt = null,
        isDeleted = isDeleted,
        deletedAt = deletedAt,
        createdAt = 1L,
        updatedAt = updatedAt
    )

    private suspend fun reminder(
        userId: Long,
        taskUuid: String,
        offset: Int = 15,
        triggerAt: Long = 1_700_500_000_000L,
        notifiedAt: Long? = null,
        syncState: SyncState = SyncState.SYNCED,
        updatedAt: Long = 100L
    ): Reminder {
        val row = Reminder(
            uuid = Reminder.identity(taskUuid, offset),
            taskUuid = taskUuid,
            userId = userId,
            offsetMinutes = offset,
            triggerAt = triggerAt,
            anchorAt = triggerAt + offset * 60_000L,
            notifiedAt = notifiedAt,
            syncState = syncState,
            createdAt = 1L,
            updatedAt = updatedAt
        )
        fixture.reminderDao.insertOrIgnore(row)
        return row
    }

    private fun remoteReminder(
        taskUuid: String,
        offset: Int = 15,
        triggerAt: Long = 1_700_400_000_000L,
        notifiedAt: Long? = null,
        isDeleted: Boolean = false,
        deletedAt: Long? = null,
        updatedAt: Long = 200L
    ) = SyncedReminder(
        uuid = Reminder.identity(taskUuid, offset),
        taskUuid = taskUuid,
        offsetMinutes = offset,
        triggerAt = triggerAt,
        anchorAt = triggerAt + offset * 60_000L,
        notifiedAt = notifiedAt,
        isDeleted = isDeleted,
        deletedAt = deletedAt,
        createdAt = 1L,
        updatedAt = updatedAt
    )

    @Test
    fun `a series the cloud has never seen becomes a local one owned by this profile`() = runTest {
        val user = signedIn()

        assertTrue(appliers.applyRemoteRule(user.id, remoteRule()))

        val stored = fixture.recurrenceDao.findByUuid("series-1")!!
        assertEquals(user.id, stored.userId)
        assertEquals(RepeatUnit.DAY, stored.unit)
        assertEquals(SyncState.SYNCED, stored.syncState)
    }

    @Test
    fun `an unsent local change to a series beats a newer document from the cloud`() = runTest {
        val user = signedIn()
        fixture.recurrenceDao.upsertRule(rule(user.id, syncState = SyncState.PENDING, updatedAt = 50))

        assertFalse(appliers.applyRemoteRule(user.id, remoteRule(unit = RepeatUnit.DAY)))

        val stored = fixture.recurrenceDao.findByUuid("series-1")!!
        assertEquals(
            "an edit made on a train must not be overwritten by a snapshot taken before it",
            RepeatUnit.WEEK, stored.unit
        )
    }

    @Test
    fun `the cursor the other device advanced to is the one this device resumes from`() = runTest {
        val user = signedIn()
        fixture.recurrenceDao.upsertRule(rule(user.id, cursorAt = 1_700_000_000_000L))
        val advanced = remoteRule(cursorAt = 1_700_000_000_000L + 4 * week)

        assertTrue(appliers.applyRemoteRule(user.id, advanced))

        assertEquals(advanced.cursorAt, fixture.recurrenceDao.findByUuid("series-1")!!.cursorAt)
    }

    @Test
    fun `a series deleted on the other device is deleted here and never resurrects`() = runTest {
        val user = signedIn()
        fixture.recurrenceDao.upsertRule(rule(user.id))

        assertTrue(appliers.applyRemoteRule(user.id, remoteRule(isDeleted = true, deletedAt = 210)))
        val tombstone = fixture.recurrenceDao.findByUuid("series-1")!!
        assertTrue(tombstone.isDeleted)
        assertFalse("a tombstone must not keep generating instances", tombstone.isOpen)

        assertFalse(appliers.applyRemoteRule(user.id, remoteRule(isDeleted = false, updatedAt = 220)))
        assertTrue(fixture.recurrenceDao.findByUuid("series-1")!!.isDeleted)
    }

    @Test
    fun `a lead time from the cloud lands on the minute the other device armed it for`() = runTest {
        val user = signedIn()
        val taskId = fixture.addTask(user.id, "Standup", dueAt = 1_700_520_000_000L)
        val taskUuid = fixture.taskDao.getTaskById(taskId)!!.uuid

        assertTrue(appliers.applyRemoteReminder(user.id, remoteReminder(taskUuid, offset = 60)))

        val stored = fixture.reminderDao.getLiveForTask(taskUuid).single()
        assertEquals(60, stored.offsetMinutes)
        assertEquals(
            "the trigger travels with the document, it is not recomputed from a deadline that may differ",
            1_700_400_000_000L, stored.triggerAt
        )
        assertEquals(SyncState.SYNCED, stored.syncState)
    }

    @Test
    fun `a moved deadline updates the same reminder row instead of adding a second one`() = runTest {
        val user = signedIn()
        val taskId = fixture.addTask(user.id, "Standup", dueAt = 1_700_520_000_000L)
        val taskUuid = fixture.taskDao.getTaskById(taskId)!!.uuid
        val original = reminder(user.id, taskUuid, offset = 15)

        val moved = remoteReminder(taskUuid, offset = 15, triggerAt = original.triggerAt + 2 * 3_600_000L)
        assertTrue(appliers.applyRemoteReminder(user.id, moved))

        val rows = fixture.reminderDao.getLiveForTask(taskUuid)
        assertEquals(1, rows.size)
        assertEquals("the identity of a reminder is (task, lead time), so its uuid survives", original.uuid, rows.single().uuid)
        assertEquals(moved.triggerAt, rows.single().triggerAt)
    }

    @Test
    fun `a warning this device already showed is not put back on the clock`() = runTest {
        val user = signedIn()
        val taskId = fixture.addTask(user.id, "Standup", dueAt = 1_700_520_000_000L)
        val taskUuid = fixture.taskDao.getTaskById(taskId)!!.uuid
        reminder(user.id, taskUuid, notifiedAt = 150L)

        assertTrue(appliers.applyRemoteReminder(user.id, remoteReminder(taskUuid, notifiedAt = null)))

        val stored = fixture.reminderDao.getLiveForTask(taskUuid).single()
        assertEquals(
            "a snapshot from another phone must not make the user hear the same minute twice",
            150L, stored.notifiedAt
        )
    }

    @Test
    fun `a lead time cancelled on the other device is cancelled here`() = runTest {
        val user = signedIn()
        val taskId = fixture.addTask(user.id, "Standup", dueAt = 1_700_520_000_000L)
        val taskUuid = fixture.taskDao.getTaskById(taskId)!!.uuid
        reminder(user.id, taskUuid)

        assertTrue(
            appliers.applyRemoteReminder(
                user.id, remoteReminder(taskUuid, isDeleted = true, deletedAt = 210)
            )
        )

        val stored = fixture.reminderDao.findByTaskAndOffset(taskUuid, 15)!!
        assertTrue(stored.isDeleted)
        assertEquals(SyncState.SYNCED, stored.syncState)
        assertTrue("a deleted reminder is not pending any more", fixture.reminderDao.getLiveForTask(taskUuid).isEmpty())
    }

    @Test
    fun `another profile's reminder is never rewritten by a pull`() = runTest {
        val user = signedIn()
        val otherId = fixture.signIn(email = "other@example.com").id
        val taskUuid = "someone-elses-task"
        reminder(otherId, taskUuid)

        assertFalse(appliers.applyRemoteReminder(user.id, remoteReminder(taskUuid)))

        assertEquals(otherId, fixture.reminderDao.findByTaskAndOffset(taskUuid, 15)!!.userId)
    }

    @Test
    fun `a task update carries the planned window and the series link across`() = runTest {
        val user = signedIn()
        val taskId = fixture.addTask(user.id, "Draft", dueAt = 1_700_000_000_000L)
        val task = fixture.taskDao.getTaskById(taskId)!!.copy(syncState = SyncState.SYNCED)
        fixture.taskDao.updateTask(task)
        val remote = SyncedTask.of(
            task.copy(
                title = "Draft v2",
                startTime = 1_700_100_000_000L,
                endTime = 1_700_103_000_000L,
                estimatedMinutes = 50,
                seriesUuid = "series-1",
                occurrenceAt = 1_700_000_000_000L,
                updatedAt = task.updatedAt + 1000
            ),
            categoryUuid = null
        )

        assertTrue(appliers.applyRemoteTask(user.id, remote, emptyMap()))

        val stored = fixture.taskDao.getTaskById(taskId)!!
        assertEquals("Draft v2", stored.title)
        assertEquals(remote.startTime, stored.startTime)
        assertEquals(remote.endTime, stored.endTime)
        assertEquals(remote.estimatedMinutes, stored.estimatedMinutes)
        assertEquals("series-1", stored.seriesUuid)
        assertEquals(remote.occurrenceAt, stored.occurrenceAt)
    }

    @Test
    fun `an instance this device already produced keeps its own row when the same one arrives`() = runTest {
        val user = signedIn()
        val occurrence = 1_700_000_000_000L
        // The first instance of a series keeps the uuid of the task it was made from, while a device
        // that materialises the same occurrence names it after the series and the date. Two uuids,
        // one task on one day — and the (series, occurrence) index is what keeps them one row.
        val mine = Task(
            uuid = "the-task-the-user-started",
            title = "Watered by hand today",
            description = "",
            dueDate = occurrence,
            userId = user.id,
            status = TaskStatus.IN_PROGRESS,
            seriesUuid = "series-1",
            occurrenceAt = occurrence,
            syncState = SyncState.SYNCED,
            updatedAt = 100L
        )
        fixture.taskDao.insertTask(mine)

        val incoming = SyncedTask.of(
            mine.copy(
                uuid = RecurrenceCalculator.occurrenceUuid("series-1", occurrence),
                title = "Water plants",
                status = TaskStatus.TODO,
                updatedAt = 300L
            ),
            categoryUuid = null
        )
        assertFalse(
            "a document that changed nothing here is not counted as applied",
            appliers.applyRemoteTask(user.id, incoming, emptyMap())
        )

        val rows = fixture.taskDao.getAllRowsOnce(user.id)
        assertEquals("the same occurrence is one task, not two", 1, rows.size)
        assertEquals(
            "an instance already on this device may have been edited, so the copy is dropped",
            "Watered by hand today", rows.single().title
        )
        assertEquals(TaskStatus.IN_PROGRESS, rows.single().status)
    }

    @Test
    fun `a pulled instance finds its series so the repeating pattern stays intact`() = runTest {
        val user = signedIn()
        appliers.applyRemoteRule(user.id, remoteRule())
        val occurrence = 1_700_600_000_000L
        val remote = SyncedTask.of(
            Task(
                uuid = RecurrenceCalculator.occurrenceUuid("series-1", occurrence),
                title = "Water plants",
                description = "",
                dueDate = occurrence,
                userId = user.id,
                seriesUuid = "series-1",
                occurrenceAt = occurrence,
                updatedAt = 150L
            ),
            categoryUuid = null
        )

        assertTrue(appliers.applyRemoteTask(user.id, remote, emptyMap()))

        val stored = fixture.taskDao.getTaskByUuid(remote.uuid)!!
        val series = fixture.recurrenceDao.findByUuid(stored.seriesUuid!!)!!
        assertEquals("the pattern arrives as this profile's own", user.id, series.userId)
    }
}
