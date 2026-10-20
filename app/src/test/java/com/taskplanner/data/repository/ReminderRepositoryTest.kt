package com.taskplanner.data.repository

import android.app.AlarmManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.taskplanner.data.model.Reminder
import com.taskplanner.data.model.SyncState
import com.taskplanner.data.model.Task
import com.taskplanner.testing.AppFixture
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlarmManager

/**
 * Reminders as stored rows: what a set of lead times becomes in the database, which alarms it puts on
 * the clock, and what happens to both when the deadline moves, the task is archived, deleted, or the
 * user signs in as somebody else.
 *
 * The rows and the alarms are asserted together on purpose. Either one alone can look right while the
 * user gets nothing — a row with no alarm never rings, and an alarm for a row the user unticked rings
 * for a reminder that no longer exists.
 */
@RunWith(RobolectricTestRunner::class)
class ReminderRepositoryTest {

    private lateinit var fixture: AppFixture
    private lateinit var alarmManager: AlarmManager

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        fixture = AppFixture(context)
        alarmManager = context.getSystemService(AlarmManager::class.java)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    @After
    fun tearDown() {
        // The fixture's two database threads have to be finished before the storage goes away. A query
        // still running when this test ends is reported against whichever test runs next, and that test
        // then fails for something this one did.
        fixture.close()
    }

    private fun now(): Long = System.currentTimeMillis()

    private suspend fun addTask(userId: Long, title: String, dueAt: Long?): Task {
        val id = fixture.taskDao.insertTask(
            Task(uuid = "task-$title", title = title, description = "", dueDate = dueAt, userId = userId)
        )
        return fixture.taskDao.getTaskById(id)!!
    }

    /** The alarm slots currently on the clock, one per reminder that was armed. */
    private fun armedSlots(): Set<Int> =
        shadowOf(alarmManager).scheduledAlarms.map { shadowOf(it.operation).requestCode }.toSet()

    @Test
    fun `the lead times a task is given become rows armed for their own minutes`() = runTest {
        val user = fixture.signIn()
        val due = now() + 6 * HOUR
        val task = addTask(user.id, "Standup", due)

        assertTrue(fixture.reminderRepository.applyReminders(task, listOf(0, 15, 1440)))

        val rows = fixture.reminderDao.getLiveForTask(task.uuid)
        assertEquals(
            "a day before rings first, then a quarter before, then the deadline itself",
            listOf(1440, 15, 0),
            rows.map { it.offsetMinutes }
        )
        rows.forEach { row ->
            assertEquals(due - row.offsetMinutes * MINUTE, row.triggerAt)
            assertEquals(due, row.anchorAt)
            assertEquals(user.id, row.userId)
            assertEquals(Reminder.identity(task.uuid, row.offsetMinutes), row.uuid)
            assertEquals(SyncState.PENDING, row.syncState)
        }
        assertEquals(
            "the one that is already in the past is a row to catch up on, not an alarm",
            setOf(
                slotOf(task.uuid, 15),
                slotOf(task.uuid, 0)
            ),
            armedSlots()
        )
    }

    @Test
    fun `pressing the same chip twice cannot make a second reminder`() = runTest {
        val user = fixture.signIn()
        val task = addTask(user.id, "Renew passport", now() + DAY)

        fixture.reminderRepository.applyReminders(task, listOf(15))
        fixture.reminderRepository.applyReminders(task, listOf(15))

        assertEquals(listOf(15), fixture.reminderRepository.offsetsFor(task.uuid))
        assertEquals(1, armedSlots().size)
    }

    @Test
    fun `a lead time the user removed and asked for again comes back pending`() = runTest {
        val user = fixture.signIn()
        val due = now() + DAY
        val task = addTask(user.id, "Back up the laptop", due)
        // What unticking a chip leaves behind: the same identity, thrown away.
        fixture.reminderDao.insertOrIgnore(
            Reminder(
                uuid = Reminder.identity(task.uuid, 30),
                taskUuid = task.uuid,
                userId = user.id,
                offsetMinutes = 30,
                triggerAt = due - 30 * MINUTE,
                anchorAt = due,
                isDeleted = true,
                deletedAt = now(),
                syncState = SyncState.DELETING
            )
        )

        fixture.reminderRepository.applyReminders(task, listOf(30))

        val rows = fixture.reminderDao.getLiveForTask(task.uuid)
        assertEquals("asking again reuses the row instead of making a second one", 1, rows.size)
        val revived = rows.single()
        assertNull("a lead time the user asked for anew is pending once more", revived.notifiedAt)
        assertEquals(SyncState.PENDING, revived.syncState)
        assertEquals(setOf(slotOf(task.uuid, 30)), armedSlots())
    }

    @Test
    fun `a lead time already announced for this deadline is not announced twice`() = runTest {
        val user = fixture.signIn()
        val due = now() + DAY
        val task = addTask(user.id, "Renew the visa", due)
        val announcedAt = now() - MINUTE
        fixture.reminderDao.insertOrIgnore(
            Reminder(
                uuid = Reminder.identity(task.uuid, 30),
                taskUuid = task.uuid,
                userId = user.id,
                offsetMinutes = 30,
                triggerAt = due - 30 * MINUTE,
                anchorAt = due,
                notifiedAt = announcedAt
            )
        )

        // Saving the task a second time with the chip still ticked is not a new request.
        fixture.reminderRepository.applyReminders(task, listOf(30))

        val row = fixture.reminderDao.getLiveForTask(task.uuid).single()
        assertEquals(announcedAt, row.notifiedAt)
        assertTrue(
            "and no alarm is left standing for a minute the user has already been shown",
            armedSlots().isEmpty()
        )
    }

    @Test
    fun `moving the deadline moves every reminder with it`() = runTest {
        val user = fixture.signIn()
        val task = addTask(user.id, "Dentist", now() + DAY)
        fixture.reminderRepository.applyReminders(task, listOf(0, 15))

        val newDue = task.dueDate!! + 3 * DAY
        val moved = task.copy(dueDate = newDue)
        fixture.reminderRepository.applyReminders(moved, null)

        val rows = fixture.reminderDao.getLiveForTask(task.uuid).associateBy { it.offsetMinutes }
        assertEquals(newDue, rows.getValue(0).triggerAt)
        assertEquals(newDue - 15 * MINUTE, rows.getValue(15).triggerAt)
        rows.values.forEach { assertEquals(newDue, it.anchorAt) }
        assertEquals(
            "a moved deadline re-points the alarms, it does not add new ones",
            setOf(
                slotOf(task.uuid, 0),
                slotOf(task.uuid, 15)
            ),
            armedSlots()
        )
    }

    @Test
    fun `unchecking one lead time retires only that row`() = runTest {
        val user = fixture.signIn()
        val task = addTask(user.id, "Team retro", now() + DAY)
        fixture.reminderRepository.applyReminders(task, listOf(15, 60))

        fixture.reminderRepository.applyReminders(task, listOf(60))

        assertEquals(listOf(60), fixture.reminderRepository.offsetsFor(task.uuid))
        val retired = fixture.reminderDao.findByTaskAndOffset(task.uuid, 15)!!
        assertTrue(retired.isDeleted)
        assertEquals(
            "the removal has to reach the other devices too",
            SyncState.DELETING,
            retired.syncState
        )
    }

    @Test
    fun `a task without a deadline cannot hold reminders`() = runTest {
        val user = fixture.signIn()
        val task = addTask(user.id, "Someday", now() + DAY)
        fixture.reminderRepository.applyReminders(task, listOf(15))

        val withoutDeadline = task.copy(dueDate = null)
        assertFalse(fixture.reminderRepository.applyReminders(withoutDeadline, listOf(15)))

        assertTrue(fixture.reminderDao.getLiveForTask(task.uuid).isEmpty())
        assertTrue(
            "and nothing stays armed for a minute that no longer exists",
            armedSlots().isEmpty()
        )
    }

    @Test
    fun `finishing a task silences it but keeps its lead times for the archive`() = runTest {
        val user = fixture.signIn()
        val task = addTask(user.id, "Send invoice", now() + DAY)
        fixture.reminderRepository.applyReminders(task, listOf(15))
        assertEquals(1, armedSlots().size)

        fixture.taskRepository.completeAndArchive(task.id)

        val rows = fixture.reminderDao.getLiveForTask(task.uuid)
        assertEquals("the archived task still owns its reminder", 1, rows.size)
        assertTrue(rows.single().isPending)
        assertTrue(
            "but the alarm is off, so a filed-away task makes no noise",
            armedSlots().isEmpty()
        )
    }

    @Test
    fun `restoring a task from the archive arms its lead times again`() = runTest {
        val user = fixture.signIn()
        val task = addTask(user.id, "Renew insurance", now() + DAY)
        fixture.reminderRepository.applyReminders(task, listOf(30))
        fixture.taskRepository.completeAndArchive(task.id)

        fixture.taskRepository.restoreTask(task.id)

        val restored = fixture.taskDao.getTaskById(task.id)!!
        val rows = fixture.reminderDao.getLiveForTask(task.uuid)
        assertEquals(1, rows.size)
        assertEquals(
            "the same lead time, measured from the deadline the task has now",
            restored.dueDate!! - 30 * MINUTE,
            rows.single().triggerAt
        )
        assertEquals(setOf(rows.single().alarmRequestCode()), armedSlots())
    }

    @Test
    fun `deleting a plain task retires its reminders with a tombstone`() = runTest {
        val user = fixture.signIn()
        val task = addTask(user.id, "Throwaway", now() + DAY)
        fixture.reminderRepository.applyReminders(task, listOf(15, 60))

        fixture.taskRepository.deleteTask(task)

        val rows = fixture.reminderDao.findByTaskAndOffset(task.uuid, 15)!!
        assertTrue("the reminder goes the way of the task", rows.isDeleted)
        assertEquals(SyncState.DELETING, rows.syncState)
        assertTrue(fixture.reminderDao.getLiveForTask(task.uuid).isEmpty())
        assertTrue(armedSlots().isEmpty())
    }

    @Test
    fun `deleting a task forever takes its reminders off the clock and keeps their removal`() = runTest {
        val user = fixture.signIn()
        val task = addTask(user.id, "Temporary", now() + DAY)
        fixture.reminderRepository.applyReminders(task, listOf(15))

        fixture.taskRepository.hardDeleteTasks(listOf(task.id))

        assertNull(fixture.taskDao.getTaskById(task.id))
        val row = fixture.reminderDao.findByTaskAndOffset(task.uuid, 15)!!
        assertTrue(
            "the row its reminder belonged to is gone, but other devices still have to be told",
            row.isDeleted
        )
        assertEquals(SyncState.DELETING, row.syncState)
        assertTrue(fixture.reminderDao.getLiveForTask(task.uuid).isEmpty())
        assertTrue(armedSlots().isEmpty())
    }

    @Test
    fun `only the signed-in profile's reminders are armed`() = runTest {
        val first = fixture.signIn(name = "First", email = "first@example.com")
        val own = addTask(first.id, "My own", now() + DAY)
        fixture.reminderRepository.applyReminders(own, listOf(15))

        // A second profile that shares this storage: its rows must never be woken by this session.
        val otherReminder = Reminder(
            uuid = Reminder.identity("task-foreign", 15),
            taskUuid = "task-foreign",
            userId = 999,
            offsetMinutes = 15,
            triggerAt = now() + HOUR,
            anchorAt = now() + DAY
        )
        fixture.reminderDao.insertOrIgnore(otherReminder)

        fixture.reminderRepository.rescheduleAll()

        assertEquals(
            "the other profile's reminder is not this session's to ring",
            setOf(slotOf(own.uuid, 15)),
            armedSlots()
        )
    }

    @Test
    fun `switching profiles re-arms the new profile's own reminders`() = runTest {
        val first = fixture.signIn(name = "First", email = "first@example.com")
        val firstTask = addTask(first.id, "First profile task", now() + DAY)
        fixture.reminderRepository.applyReminders(firstTask, listOf(60))

        val second = fixture.signIn(name = "Second", email = "second@example.com")
        val secondTask = addTask(second.id, "Second profile task", now() + DAY)
        fixture.reminderRepository.applyReminders(secondTask, listOf(15))

        assertEquals(
            "each reminder owns its own alarm slot",
            setOf(slotOf(firstTask.uuid, 60), slotOf(secondTask.uuid, 15)),
            armedSlots()
        )
        assertEquals(listOf(15), fixture.reminderRepository.offsetsFor(secondTask.uuid))
        assertEquals(listOf(60), fixture.reminderRepository.offsetsFor(firstTask.uuid))
    }

    @Test
    fun `a reminder of a task this profile cannot see is not offered to the editor`() = runTest {
        val user = fixture.signIn()
        val task = addTask(user.id, "Visible", now() + DAY)

        fixture.reminderRepository.applyReminders(task, null)

        assertEquals(emptyList<Int>(), fixture.reminderRepository.offsetsFor("task-nobody"))
        assertEquals(emptyList<Int>(), fixture.reminderRepository.offsetsFor(""))
    }

    /** The alarm slot the reminder of this task and lead time would occupy. */
    private fun slotOf(taskUuid: String, offsetMinutes: Int): Int = Reminder(
        uuid = Reminder.identity(taskUuid, offsetMinutes),
        taskUuid = taskUuid,
        userId = 1,
        offsetMinutes = offsetMinutes,
        triggerAt = 0L,
        anchorAt = 0L
    ).alarmRequestCode()

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val DAY = 24 * HOUR
    }
}
