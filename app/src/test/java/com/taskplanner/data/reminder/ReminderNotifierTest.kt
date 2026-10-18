package com.taskplanner.data.reminder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.taskplanner.R
import com.taskplanner.data.model.Category
import com.taskplanner.data.model.Priority
import com.taskplanner.data.model.Reminder
import com.taskplanner.data.model.Task
import com.taskplanner.data.model.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The notification a reminder arrives as, checked the way the shade shows it: what the line says,
 * which task it belongs to, what the buttons do and where tapping it goes.
 *
 * The manager here is Robolectric's shadow, so posting is real and the posted record can be read
 * back — including the case that matters most, a device on which the user switched notifications off.
 */
@RunWith(RobolectricTestRunner::class)
class ReminderNotifierTest {

    private lateinit var context: Context
    private lateinit var manager: NotificationManager
    private lateinit var notifier: ReminderNotifier

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        manager = context.getSystemService(NotificationManager::class.java)
        notifier = ReminderNotifier(context)
    }

    private fun task(title: String = "Call the vet", categoryId: Long? = null) = Task(
        id = 7,
        uuid = "task-notify",
        title = title,
        description = "Ask about the vaccination",
        priority = Priority.HIGH,
        status = TaskStatus.TODO,
        dueDate = DUE,
        categoryId = categoryId,
        userId = 1
    )

    private fun reminder(offsetMinutes: Int = 15) = Reminder(
        uuid = Reminder.identity("task-notify", offsetMinutes),
        taskUuid = "task-notify",
        userId = 1,
        offsetMinutes = offsetMinutes,
        triggerAt = DUE - offsetMinutes * 60_000L,
        anchorAt = DUE
    )

    private fun posted(target: Reminder): Notification? =
        shadowOf(manager).getNotification(target.notificationId)

    @Test
    fun `the reminder channel is created once and is loud enough to be seen`() {
        notifier.ensureChannel()
        notifier.ensureChannel()

        val channels = shadowOf(manager).notificationChannels.map {
            it as NotificationChannel
        }
        assertEquals(1, channels.size)
        val channel = channels.single()
        assertEquals(ReminderNotifier.CHANNEL_ID, channel.id)
        assertEquals(context.getString(R.string.reminder_channel_name), channel.name.toString())
        assertEquals(
            "a deadline reminder has to appear on screen, not only in the shade",
            NotificationManager.IMPORTANCE_HIGH,
            channel.importance
        )
    }

    @Test
    fun `the notification says what is due, when, and in which category`() {
        val categoryId = 3L
        val category = Category(id = categoryId, name = "Pets", color = -0xFF0000, icon = "ic_tag", userId = 1)
        val target = reminder()

        assertTrue(notifier.show(task(categoryId = categoryId), category, target))

        val notification = posted(target)
        assertNotNull(notification)
        notification!!
        assertEquals("Call the vet", notification.extras.getString(Notification.EXTRA_TITLE))
        assertEquals(ReminderNotifier.CHANNEL_ID, notification.channelId)
        assertEquals(
            "the category is shown where the shade keeps it visible",
            "Pets",
            notification.extras.getCharSequence(Notification.EXTRA_SUB_TEXT)
        )
        assertEquals(
            "the category tells the shade how to rank this notification",
            Notification.CATEGORY_REMINDER,
            notification.category
        )
        val text = notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        assertTrue("the line must name the deadline, was: $text", text.contains("Due"))
        val bigText = notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString()
        assertTrue(bigText.contains("Ask about the vaccination"))
        assertTrue(bigText.contains("Pets"))
    }

    @Test
    fun `a task with no category still gets a readable reminder`() {
        val target = reminder()

        assertTrue(notifier.show(task(categoryId = null), null, target))

        val notification = posted(target)!!
        assertNull(notification.extras.getCharSequence(Notification.EXTRA_SUB_TEXT))
        assertEquals("Call the vet", notification.extras.getString(Notification.EXTRA_TITLE))
    }

    @Test
    fun `the notification offers complete, snooze and edit as separate intents`() {
        val target = reminder()

        notifier.show(task(), null, target)

        val notification = posted(target)!!
        val titles = notification.actions.map { it.title.toString() }
        assertEquals(
            listOf(
                context.getString(R.string.reminder_action_complete),
                context.getString(R.string.reminder_action_snooze),
                context.getString(R.string.reminder_action_edit)
            ),
            titles
        )
        val slots = notification.actions.map { shadowOf(it.actionIntent).requestCode }
        assertEquals(
            "no button may overwrite another reminder's action",
            slots.size,
            slots.distinct().size
        )
        assertEquals(target.requestCodeFor(Reminder.Action.COMPLETE), slots[0])
        assertEquals(target.requestCodeFor(Reminder.Action.SNOOZE), slots[1])
        assertEquals(target.requestCodeFor(Reminder.Action.EDIT), slots[2])

        // Complete and snooze are handled by the app's own receiver; edit opens the task.
        assertEquals(
            ReminderReceiver.ACTION_COMPLETE,
            shadowOf(notification.actions[0].actionIntent).savedIntent.action
        )
        assertEquals(
            ReminderReceiver.ACTION_SNOOZE,
            shadowOf(notification.actions[1].actionIntent).savedIntent.action
        )
        assertEquals(
            "the reminder's own uuid, so the button acts on the task it belongs to",
            target.uuid,
            shadowOf(notification.actions[0].actionIntent).savedIntent
                .getStringExtra(ReminderReceiver.EXTRA_REMINDER_UUID)
        )
    }

    @Test
    fun `tapping the reminder deep links to the task it came from`() {
        val target = reminder()

        notifier.show(task(), null, target)

        val content = shadowOf(posted(target)!!.contentIntent)
        assertEquals(
            ReminderNotifier.taskDeepLink(7),
            content.savedIntent.dataString
        )
        assertEquals(
            "a reminder that is tapped and forgotten must not sit in the shade",
            true,
            posted(target)!!.flags and Notification.FLAG_AUTO_CANCEL != 0
        )
    }

    @Test
    fun `notifications switched off means nothing is posted and the caller is told`() {
        shadowOf(manager).setNotificationsEnabled(false)
        val target = reminder()

        assertFalse(notifier.canPostNotifications())
        assertFalse(
            "a reminder the user cannot see must stay pending instead of being marked as shown",
            notifier.show(task(), null, target)
        )
        assertNull(posted(target))
    }

    @Test
    fun `cancelling a reminder clears its own notification only`() {
        val first = reminder(15)
        val second = reminder(60)
        notifier.show(task(), null, first)
        notifier.show(task(), null, second)

        notifier.cancel(first)

        assertNull(posted(first))
        assertNotNull(posted(second))
    }

    @Test
    fun `reminders are gathered under one group so the shade stays readable`() {
        val target = reminder()

        notifier.show(task(), null, target)

        assertEquals(
            ReminderNotifier.GROUP_KEY,
            posted(target)!!.group
        )
    }

    private companion object {
        const val DUE = 1_800_000_000_000L
    }
}
