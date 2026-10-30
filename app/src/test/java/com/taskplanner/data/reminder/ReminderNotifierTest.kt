package com.taskplanner.data.reminder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.text.format.DateFormat
import androidx.test.core.app.ApplicationProvider
import com.taskplanner.R
import com.taskplanner.data.habit.HabitAdherence
import com.taskplanner.data.habit.HabitReminderAction
import com.taskplanner.data.habit.HabitReminderIdentity
import com.taskplanner.data.model.Category
import com.taskplanner.data.model.Habit
import com.taskplanner.data.model.HabitFrequency
import com.taskplanner.data.model.Priority
import com.taskplanner.data.model.Reminder
import com.taskplanner.data.model.Task
import com.taskplanner.data.model.TaskStatus
import java.util.Date
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
import org.robolectric.annotation.Config

/**
 * The notification a reminder arrives as, checked the way the shade shows it: what the line says,
 * which task it belongs to, what the buttons do and where tapping it goes.
 *
 * The manager here is Robolectric's shadow, so posting is real and the posted record can be read
 * back — including the case that matters most, a device on which the user switched notifications off.
 *
 * Habit notices are read the same way, and the two kinds are compared where they must differ: a deadline
 * is loud and a habit is not, and each opens the screen its own row lives on.
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

    /**
     * A channel that already exists is still given the language the app is in now.
     *
     * The name and description of a notification channel are shown by the system's own notification settings,
     * not by this app, and Stage 14 made the language something a reader changes while the app is installed.
     * `ensureChannel` used to answer "the channel exists" and return, so those two lines kept the language of
     * the very first launch forever: a reader who moved to Русский had Russian reminders arriving under an
     * English channel they could switch off by a name they did not recognise. Re-creating a channel with the id
     * it already has is the documented way to refresh the app-defined text; what the user decided for it —
     * importance, sound, notifications allowed — the framework keeps.
     *
     * Robolectric resolves this context's resources from its own configuration, so the Russian qualifier is
     * what puts the Russian words in front of the same code path — not `Locale.setDefault`, which cannot move
     * a resource lookup.
     */
    @Test
    @Config(qualifiers = "ru-rRU")
    fun `an existing channel is re-announced in the language the app is in now`() {
        val englishWords = "Reminders about deadlines"
        manager.createNotificationChannel(
            NotificationChannel(ReminderNotifier.CHANNEL_ID, englishWords, NotificationManager.IMPORTANCE_HIGH)
        )

        notifier.ensureChannel()

        val channel = shadowOf(manager).notificationChannels
            .map { it as NotificationChannel }
            .single { it.id == ReminderNotifier.CHANNEL_ID }
        val expected = context.getString(R.string.reminder_channel_name)
        assertTrue(
            "the test needs the resource to actually be Russian for the claim to mean anything: $expected",
            expected.any { it.code in 0x0400..0x04FF }
        )
        assertEquals("the channel carries the language the app is in, not the one it was first installed in",
            expected, channel.name.toString())
        assertEquals(
            "and the description travels with it",
            context.getString(R.string.reminder_channel_description),
            channel.description
        )
        assertEquals(
            "the user's own decision about loudness is left where it was",
            NotificationManager.IMPORTANCE_HIGH, channel.importance
        )
    }

    /**
     * The habit channel gets the same treatment, and gets it named.
     *
     * A first version of the fix changed [ReminderNotifier.ensureChannel] alone, and the habit channel went on
     * carrying whatever language it was written in — which is the same defect with a different id, in a place
     * nobody looks after one screen has been fixed. The two channels are also created by two different calls,
     * so a reader who has never had a habit reminder has no habit channel to re-announce at all; this test
     * says what happens when there is one.
     */
    @Test
    @Config(qualifiers = "ru-rRU")
    fun `the habit channel is re-announced too`() {
        val englishWords = "Habit reminders"
        manager.createNotificationChannel(
            NotificationChannel(
                ReminderNotifier.HABIT_CHANNEL_ID, englishWords, NotificationManager.IMPORTANCE_LOW
            )
        )

        notifier.ensureHabitChannel()

        val channel = shadowOf(manager).notificationChannels
            .map { it as NotificationChannel }
            .single { it.id == ReminderNotifier.HABIT_CHANNEL_ID }
        assertEquals(
            "the name follows the app, and the importance the reader set does not",
            context.getString(R.string.habit_reminder_channel_name), channel.name.toString()
        )
        assertEquals(
            "a reader who quieted this channel stays quieted",
            NotificationManager.IMPORTANCE_LOW, channel.importance
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

    // region habit notices

    @Test
    fun `the habit channel is created once and is quieter than a deadline`() {
        notifier.ensureHabitChannel()
        notifier.ensureHabitChannel()
        notifier.ensureChannel()

        val channels = shadowOf(manager).notificationChannels.map { it as NotificationChannel }
        assertEquals(2, channels.size)
        val habitChannel = channels.first { it.id == ReminderNotifier.HABIT_CHANNEL_ID }
        val taskChannel = channels.first { it.id == ReminderNotifier.CHANNEL_ID }
        assertEquals(context.getString(R.string.habit_reminder_channel_name), habitChannel.name.toString())
        assertEquals(
            "a daily nudge must be switchable off without muting a deadline, so the two live apart",
            NotificationManager.IMPORTANCE_DEFAULT,
            habitChannel.importance
        )
        assertEquals(
            "and a deadline keeps its louder setting beside it",
            NotificationManager.IMPORTANCE_HIGH,
            taskChannel.importance
        )
    }

    @Test
    fun `a habit notice names the habit, the minute it asked for and the week it stands in`() {
        val target = habit()

        assertTrue(notifier.showHabit(target, DAY, TRIGGER, HabitAdherence(kept = 3, asked = 4)))

        val notification = postedHabit(target)!!
        assertEquals(target.title, notification.extras.getString(Notification.EXTRA_TITLE))
        assertEquals(ReminderNotifier.HABIT_CHANNEL_ID, notification.channelId)
        assertEquals(
            context.getString(R.string.habit_reminder_subtitle),
            notification.extras.getCharSequence(Notification.EXTRA_SUB_TEXT).toString()
        )
        assertEquals(
            context.getString(R.string.habit_reminder_content),
            notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        )
        val clock = DateFormat.getTimeFormat(context).format(Date(TRIGGER))
        assertEquals(
            "the expanded text repeats the header's time rather than the sweep's, and adds the week",
            listOf(
                context.getString(R.string.habit_reminder_body_time_format, clock),
                context.getString(R.string.habit_reminder_body_week_format, 3, 4)
            ).joinToString("\n"),
            notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString()
        )
        assertEquals(Notification.CATEGORY_REMINDER, notification.category)
        assertEquals(
            "a habit asks, it does not demand: it carries no raised priority beside a deadline's",
            Notification.PRIORITY_DEFAULT,
            notification.priority
        )
        assertEquals(ReminderNotifier.GROUP_KEY, notification.group)
    }

    @Test
    fun `a habit that has asked for nothing this week shows no figure`() {
        val target = habit()

        notifier.showHabit(target, DAY, TRIGGER, HabitAdherence(kept = 0, asked = 0))

        val bigText = postedHabit(target)!!.extras
            .getCharSequence(Notification.EXTRA_BIG_TEXT).toString()
        assertEquals(
            "a zero kept of nothing asked would read as a failure the user never had to avoid",
            context.getString(
                R.string.habit_reminder_body_time_format,
                DateFormat.getTimeFormat(context).format(Date(TRIGGER))
            ),
            bigText
        )
    }

    @Test
    fun `a habit notice offers keep it and snooze as separate intents`() {
        val target = habit()

        notifier.showHabit(target, DAY, TRIGGER, HabitAdherence(kept = 1, asked = 5))

        val notification = postedHabit(target)!!
        assertEquals(
            listOf(
                context.getString(R.string.habit_reminder_action_mark),
                context.getString(R.string.reminder_action_snooze)
            ),
            notification.actions.map { it.title.toString() }
        )
        val slots = notification.actions.map { shadowOf(it.actionIntent).requestCode }
        assertEquals(HabitReminderIdentity.requestCodeOf(target.uuid, HabitReminderAction.MARK), slots[0])
        assertEquals(HabitReminderIdentity.requestCodeOf(target.uuid, HabitReminderAction.SNOOZE), slots[1])
        assertEquals(
            "a button may not overwrite the screen the notice opens from",
            HabitReminderIdentity.requestCodeOf(target.uuid, HabitReminderAction.OPEN)
                .let { it != slots[0] && it != slots[1] },
            true
        )

        assertEquals(
            ReminderReceiver.ACTION_HABIT_MARK,
            shadowOf(notification.actions[0].actionIntent).savedIntent.action
        )
        assertEquals(
            ReminderReceiver.ACTION_HABIT_SNOOZE,
            shadowOf(notification.actions[1].actionIntent).savedIntent.action
        )
        notification.actions.forEach { action ->
            val intent = shadowOf(action.actionIntent).savedIntent
            assertEquals(
                "the habit is named by its uuid, so a row deleted since still gets the right answer",
                target.uuid,
                intent.getStringExtra(ReminderReceiver.EXTRA_HABIT_UUID)
            )
            assertEquals(
                "and the day the notice asked about, not the day the tap falls on",
                DAY,
                intent.getLongExtra(ReminderReceiver.EXTRA_HABIT_DAY, ReminderReceiver.NO_DAY)
            )
        }
    }

    @Test
    fun `tapping a habit notice deep links to the habit it came from`() {
        val target = habit()

        notifier.showHabit(target, DAY, TRIGGER, null)

        val content = shadowOf(postedHabit(target)!!.contentIntent)
        assertEquals(HabitReminderIdentity.habitDeepLink(target.id), content.savedIntent.dataString)
        assertEquals(
            HabitReminderIdentity.requestCodeOf(target.uuid, HabitReminderAction.OPEN),
            content.requestCode
        )
        assertEquals(
            "a habit kept from the shade should leave its notice behind",
            true,
            postedHabit(target)!!.flags and Notification.FLAG_AUTO_CANCEL != 0
        )
    }

    @Test
    fun `notifications switched off posts no habit notice and tells the caller`() {
        shadowOf(manager).setNotificationsEnabled(false)
        val target = habit()

        assertFalse(notifier.canPostNotifications())
        assertFalse(
            "a day the user could not see must stay un-announced instead of taking its note",
            notifier.showHabit(target, DAY, TRIGGER, HabitAdherence(kept = 0, asked = 3))
        )
        assertNull(postedHabit(target))
    }

    @Test
    fun `cancelling a habit clears its own notice only`() {
        val first = habit("Stretch", "habit-a")
        val second = habit("Read", "habit-b")
        notifier.showHabit(first, DAY, TRIGGER, null)
        notifier.showHabit(second, DAY, TRIGGER, null)

        notifier.cancelHabit(first.uuid)

        assertNull(postedHabit(first))
        assertNotNull(postedHabit(second))
    }

    private fun habit(title: String = "Run", uuid: String = "habit-notify") = Habit(
        id = 11,
        uuid = uuid,
        userId = 1,
        title = title,
        frequency = HabitFrequency.DAILY,
        startDay = DAY - 30,
        remindMinuteOfDay = 7 * 60
    )

    private fun postedHabit(target: Habit): Notification? =
        shadowOf(manager).getNotification(HabitReminderIdentity.notificationIdOf(target.uuid))

    // endregion

    private companion object {
        const val DUE = 1_800_000_000_000L

        /** An arbitrary epoch day and the instant its scheduled minute falls on. */
        const val DAY = 20_600L
        const val TRIGGER = 1_800_000_000_000L
    }
}
