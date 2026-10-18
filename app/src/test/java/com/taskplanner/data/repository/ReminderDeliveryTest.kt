package com.taskplanner.data.repository

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.taskplanner.data.model.Reminder
import com.taskplanner.data.model.RepeatUnit
import com.taskplanner.data.model.Task
import com.taskplanner.data.reminder.ReminderScheduler
import com.taskplanner.testing.AppFixture
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlarmManager

/**
 * What a reminder actually does when its minute comes: the row turns into one notification, and only
 * one, and everything that can happen around it — the app being closed, the task being finished,
 * snoozed, deleted or moved, another profile being signed in — decides whether the user hears it.
 *
 * Delivery is driven through the real AlarmManager and notification shadows by passing an explicit
 * `now`, so a test says "this minute arrived" instead of waiting for the clock to catch up.
 */
@RunWith(RobolectricTestRunner::class)
class ReminderDeliveryTest {

    private lateinit var fixture: AppFixture
    private lateinit var context: Context
    private lateinit var alarms: AlarmManager
    private lateinit var notifications: NotificationManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        fixture = AppFixture(context)
        alarms = context.getSystemService(AlarmManager::class.java)
        notifications = context.getSystemService(NotificationManager::class.java)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    private fun now(): Long = System.currentTimeMillis()

    private suspend fun taskWithReminders(
        userId: Long,
        title: String,
        dueAt: Long?,
        vararg offsets: Int
    ): Task {
        val id = fixture.taskDao.insertTask(
            Task(uuid = "task-$title", title = title, description = "", dueDate = dueAt, userId = userId)
        )
        val task = fixture.taskDao.getTaskById(id)!!
        fixture.reminderRepository.applyReminders(task, offsets.toList())
        return task
    }

    private suspend fun reminderOf(task: Task, offsetMinutes: Int): Reminder =
        fixture.reminderDao.findByTaskAndOffset(task.uuid, offsetMinutes)!!

    private fun posted(reminder: Reminder): Notification? =
        shadowOf(notifications).getNotification(reminder.notificationId)

    private fun armedTriggers(): List<Long> =
        shadowOf(alarms).scheduledAlarms.map { it.triggerAtMs }

    @Test
    fun `a reminder whose minute came is announced once and then stays quiet`() = runTest {
        val user = fixture.signIn()
        val due = now() + HOUR
        val task = taskWithReminders(user.id, "Call the vet", due, 15)
        val reminder = reminderOf(task, 15)

        val delivered = fixture.reminderRepository.deliverDue(now = due - 15 * MINUTE + MINUTE)

        assertEquals(1, delivered)
        assertEquals("Call the vet", posted(reminder)!!.extras.getString(Notification.EXTRA_TITLE))
        assertNotNull(fixture.reminderDao.findByUuid(reminder.uuid)!!.notifiedAt)

        assertEquals(
            "the same minute must not produce a second copy of the notification",
            0,
            fixture.reminderRepository.deliverDue(now = due)
        )
    }

    @Test
    fun `saving the same task again does not announce its deadline twice`() = runTest {
        val user = fixture.signIn()
        val due = now() + HOUR
        val task = taskWithReminders(user.id, "Pick up the parcel", due, 0)
        val reminder = reminderOf(task, 0)
        fixture.reminderRepository.deliverDue(now = due + MINUTE)
        assertNotNull(posted(reminder))

        // The user edits the description and presses save with the chip still ticked.
        fixture.reminderRepository.applyReminders(task, listOf(0))

        assertEquals(0, fixture.reminderRepository.deliverDue(now = due + 2 * MINUTE))
        assertTrue(
            "nothing is left armed for a minute that has already been announced",
            armedTriggers().isEmpty()
        )
    }

    @Test
    fun `a reminder that came while the app was away is delivered on the next wake`() = runTest {
        val user = fixture.signIn()
        val due = now() + HOUR
        val task = taskWithReminders(user.id, "Submit the form", due, 0)
        val reminder = reminderOf(task, 0)

        // The alarm was lost with the process: nothing fired at the minute itself.
        assertEquals(0, fixture.reminderRepository.deliverDue(now = due - MINUTE))

        assertEquals(1, fixture.reminderRepository.deliverDue(now = due + 20 * MINUTE))
        assertNotNull(posted(reminder))
    }

    @Test
    fun `a reminder from long ago is retired instead of arriving late`() = runTest {
        val user = fixture.signIn()
        val staleDue = now() - 3 * DAY
        val task = taskWithReminders(user.id, "Missed dentist", staleDue, 0)

        val delivered = fixture.reminderRepository.deliverDue(now = now())

        assertEquals(
            "a phone that was off since Tuesday does not owe the user Tuesday's notification",
            0,
            delivered
        )
        assertNull(posted(reminderOf(task, 0)))
        assertNotNull(
            "and it is closed off, so no later sweep brings it back",
            fixture.reminderDao.findByTaskAndOffset(task.uuid, 0)!!.notifiedAt
        )
    }

    @Test
    fun `a finished task stops reminding even with a row still pending`() = runTest {
        val user = fixture.signIn()
        val due = now() + HOUR
        val task = taskWithReminders(user.id, "Send the invoice", due, 0)

        fixture.taskRepository.completeAndArchive(task.id)

        assertEquals(0, fixture.reminderRepository.deliverDue(now = due + MINUTE))
        assertNull(posted(reminderOf(task, 0)))
    }

    @Test
    fun `a deleted task stops reminding`() = runTest {
        val user = fixture.signIn()
        val due = now() + HOUR
        val task = taskWithReminders(user.id, "Throwaway errand", due, 0)

        fixture.taskRepository.deleteTask(task)

        assertEquals(0, fixture.reminderRepository.deliverDue(now = due + MINUTE))
        assertNull(posted(reminderOf(task, 0)))
    }

    @Test
    fun `one profile is never reminded of another profile's tasks`() = runTest {
        val first = fixture.signIn(name = "First", email = "first@example.com")
        val due = now() + HOUR
        val theirs = taskWithReminders(first.id, "First profile errand", due, 0)
        fixture.signIn(name = "Second", email = "second@example.com")

        val delivered = fixture.reminderRepository.deliverDue(now = due + MINUTE)

        assertEquals(0, delivered)
        assertNull(posted(reminderOf(theirs, 0)))
    }

    @Test
    fun `snoozing puts the same reminder back at a later minute`() = runTest {
        val user = fixture.signIn()
        val due = now() + HOUR
        val task = taskWithReminders(user.id, "Water the plants", due, 0)
        val reminder = reminderOf(task, 0)
        fixture.reminderRepository.deliverDue(now = due + MINUTE)
        assertNotNull(posted(reminder))

        val tappedAt = due + 2 * MINUTE
        assertTrue(fixture.reminderRepository.snooze(reminder.uuid, now = tappedAt))

        assertNull(
            "the notification the user acted on is taken away, not left hanging",
            posted(reminder)
        )
        val snoozed = fixture.reminderDao.findByUuid(reminder.uuid)!!
        assertEquals(tappedAt + ReminderScheduler.SNOOZE_MILLIS, snoozed.triggerAt)
        assertNull("a snoozed reminder is pending again", snoozed.notifiedAt)
        assertEquals(
            "and the alarm for it is the same reminder's own slot",
            listOf(tappedAt + ReminderScheduler.SNOOZE_MILLIS),
            armedTriggers()
        )

        assertEquals(0, fixture.reminderRepository.deliverDue(now = tappedAt + MINUTE))
        assertEquals(1, fixture.reminderRepository.deliverDue(now = tappedAt + SNOOZE + MINUTE))
        assertNotNull(posted(reminder))
    }

    @Test
    fun `using a notification button keeps the next sweep quiet`() = runTest {
        val user = fixture.signIn()
        val due = now() + HOUR
        val task = taskWithReminders(user.id, "Reply to the message", due, 0)
        val reminder = reminderOf(task, 0)
        fixture.reminderRepository.deliverDue(now = due + MINUTE)

        fixture.reminderRepository.markHandledFromNotification(reminder.uuid)

        assertNull(posted(reminder))
        assertTrue("the alarm that was still in flight is cancelled", armedTriggers().isEmpty())
        assertEquals(0, fixture.reminderRepository.deliverDue(now = due + 30 * MINUTE))
    }

    @Test
    fun `moving a task to a later date brings its reminder back for that date`() = runTest {
        val user = fixture.signIn()
        val due = now() + HOUR
        val task = taskWithReminders(user.id, "Move the meeting", due, 15)
        val reminder = reminderOf(task, 15)
        fixture.reminderRepository.deliverDue(now = due - 15 * MINUTE + MINUTE)
        assertNotNull(posted(reminder))

        val later = due + 2 * DAY
        fixture.reminderRepository.applyReminders(task.copy(dueDate = later), null)

        val moved = reminderOf(task, 15)
        assertEquals(later - 15 * MINUTE, moved.triggerAt)
        assertNull(
            "the new date is a deadline the user has not been told about yet",
            moved.notifiedAt
        )
        assertEquals(listOf(later - 15 * MINUTE), armedTriggers())

        assertEquals(0, fixture.reminderRepository.deliverDue(now = later - 20 * MINUTE))
        assertEquals(1, fixture.reminderRepository.deliverDue(now = later - 10 * MINUTE))
        assertNotNull(posted(moved))
    }

    @Test
    fun `moving a task to a minute that has gone by does not repeat the old notice`() = runTest {
        val user = fixture.signIn()
        val due = now() + HOUR
        val task = taskWithReminders(user.id, "Push the deadline back", due, 15)
        fixture.reminderRepository.deliverDue(now = due - 15 * MINUTE + MINUTE)

        val longAgo = now() - 3 * DAY
        fixture.reminderRepository.applyReminders(task.copy(dueDate = longAgo), null)

        val row = reminderOf(task, 15)
        assertEquals(longAgo - 15 * MINUTE, row.triggerAt)
        assertNotNull("the notice the user already had stays had", row.notifiedAt)
        assertEquals(0, fixture.reminderRepository.deliverDue(now = now()))
        assertTrue(armedTriggers().isEmpty())
    }

    @Test
    fun `the next instance of a series reminds in its own right`() = runTest {
        val user = fixture.signIn()
        val due = now() + DAY
        val id = fixture.taskDao.insertTask(
            Task(uuid = "task-pills", title = "Take pills", description = "", dueDate = due, userId = user.id)
        )
        val task = fixture.taskDao.getTaskById(id)!!
        fixture.reminderRepository.applyReminders(task, listOf(15))
        val rule = fixture.recurrenceRepository.startSeries(task, RepeatUnit.DAY)!!
        val instance = fixture.taskDao.findSeriesInstance(rule.uuid, rule.cursorAt)!!

        fixture.taskRepository.completeAndArchive(instance.id)
        val next = fixture.taskDao.getActiveTasksOnce(user.id).first { it.seriesUuid == rule.uuid }

        val nextReminder = reminderOf(next, 15)
        assertEquals(
            "the copy belongs to the new instance, so finishing one does not silence the other",
            next.uuid,
            nextReminder.taskUuid
        )
        assertTrue(nextReminder.isPending)
        assertEquals(listOf(next.dueDate!! - 15 * MINUTE), armedTriggers())

        assertEquals(1, fixture.reminderRepository.deliverDue(now = next.dueDate!!))
        assertNotNull(posted(nextReminder))
    }

    @Test
    fun `waking the device re-arms the rows and catches up what came due`() = runTest {
        val user = fixture.signIn()
        val cameDue = taskWithReminders(user.id, "Morning standup", now() - 30 * MINUTE, 0)
        val stillComing = taskWithReminders(user.id, "Evening review", now() + DAY, 30)

        // Simulate the reboot: the alarms are gone, the rows are not.
        fixture.reminderDao.getLiveForUser(user.id).forEach { fixture.reminderScheduler.cancel(it) }
        assertTrue(armedTriggers().isEmpty())

        val delivered = fixture.reminderRepository.rescheduleAll(now = now())

        assertEquals("the reminder that passed while the device was off still arrives", 1, delivered)
        assertNotNull(posted(reminderOf(cameDue, 0)))
        assertEquals(
            "the one that has not come yet is put back on the clock",
            listOf(stillComing.dueDate!! - 30 * MINUTE),
            armedTriggers()
        )
    }

    @Test
    fun `notifications switched off leaves the reminder pending rather than pretended`() = runTest {
        shadowOf(notifications).setNotificationsEnabled(false)
        val user = fixture.signIn()
        val due = now() + HOUR
        val task = taskWithReminders(user.id, "Buy the tickets", due, 0)

        assertEquals(0, fixture.reminderRepository.deliverDue(now = due + MINUTE))
        val row = fixture.reminderDao.findByUuid(Reminder.identity(task.uuid, 0))!!
        assertNull(
            "a reminder the user could not see was not delivered",
            row.notifiedAt
        )

        shadowOf(notifications).setNotificationsEnabled(true)
        assertEquals(
            "and it is still there for the next sweep once they turn the bar back on",
            1,
            fixture.reminderRepository.deliverDue(now = due + 2 * MINUTE)
        )
    }

    @Test
    fun `a reminder of a task whose row disappeared is never announced`() = runTest {
        val user = fixture.signIn()
        val due = now() + HOUR
        val task = taskWithReminders(user.id, "Errand without a task", due, 0)
        val reminder = reminderOf(task, 0)
        fixture.taskDao.deleteTasksByIds(listOf(task.id))

        assertEquals(0, fixture.reminderRepository.deliverDue(now = due + MINUTE))
        assertNull(posted(reminder))
    }

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val DAY = 24 * HOUR
        const val SNOOZE = ReminderScheduler.SNOOZE_MILLIS
    }
}
