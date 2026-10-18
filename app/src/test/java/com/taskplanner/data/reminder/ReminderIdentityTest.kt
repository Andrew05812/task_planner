package com.taskplanner.data.reminder

import com.taskplanner.data.model.Reminder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The identity rules a reminder is built on.
 *
 * Everything about a reminder has to be derived from what the user chose — its task and its lead time
 * — because nothing else is stable across a re-install, an import, a sync round trip or a second
 * device. If the uuid, the notification slot or the alarm slot depended on a row id, replaying the
 * same data would produce a second notification for a minute that was already announced.
 */
class ReminderIdentityTest {

    private fun reminder(
        taskUuid: String = "task-1",
        offsetMinutes: Int = 15,
        triggerAt: Long = 100_000L
    ): Reminder = Reminder(
        uuid = Reminder.identity(taskUuid, offsetMinutes),
        taskUuid = taskUuid,
        userId = 1,
        offsetMinutes = offsetMinutes,
        triggerAt = triggerAt,
        anchorAt = triggerAt + offsetMinutes * 60_000L
    )

    @Test
    fun `the same task and lead time always produce the same reminder identity`() {
        assertEquals(
            Reminder.identity("task-1", 15),
            Reminder.identity("task-1", 15)
        )
        assertNotEquals(Reminder.identity("task-1", 15), Reminder.identity("task-1", 30))
        assertNotEquals(Reminder.identity("task-1", 15), Reminder.identity("task-2", 15))
    }

    @Test
    fun `the trigger is the lead time measured back from the deadline`() {
        val due = 2_000_000L
        assertEquals(due, Reminder.triggerAt(due, 0))
        assertEquals(due - 15 * 60_000L, Reminder.triggerAt(due, 15))
        assertEquals(due - 24L * 60 * 60 * 1000, Reminder.triggerAt(due, 1440))
        assertEquals(
            "a task without a deadline never rings before it",
            due,
            Reminder.triggerAt(due, -30)
        )
    }

    @Test
    fun `one reminder owns one notification slot, and it never moves`() {
        val first = reminder()
        assertEquals(first.notificationId, reminder().notificationId)
        assertTrue(
            "a notification id must be usable as-is: ${first.notificationId}",
            first.notificationId >= 0
        )
        assertNotEquals(
            "two different lead times are two notifications the user can tell apart",
            first.notificationId,
            reminder(offsetMinutes = 30).notificationId
        )
    }

    @Test
    fun `every action and the alarm of one reminder get their own pending intent slot`() {
        val target = reminder()
        val codes = Reminder.Action.values().map { target.requestCodeFor(it) } +
            target.alarmRequestCode()

        assertEquals(
            "five intents per reminder, none sharing a code with another",
            codes.size,
            codes.distinct().size
        )
        assertNotEquals(
            "a second reminder of the same task cannot steal the first one's slot",
            target.alarmRequestCode(),
            reminder(offsetMinutes = 60).alarmRequestCode()
        )
    }

    @Test
    fun `a reminder is only pending while it is alive and not yet announced`() {
        assertTrue(reminder().isPending)
        assertFalse(reminder().copy(notifiedAt = 1L).isPending)
        assertFalse(reminder().copy(isDeleted = true).isPending)
    }
}
