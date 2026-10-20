package com.taskplanner.data.reminder

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.taskplanner.data.calendar.CalendarMath
import com.taskplanner.data.habit.HabitReminderIdentity
import com.taskplanner.data.model.Habit
import com.taskplanner.data.model.HabitFrequency
import com.taskplanner.data.model.Reminder
import com.taskplanner.data.model.RepeatUnit
import com.taskplanner.data.model.Task
import com.taskplanner.data.model.TaskStatus
import com.taskplanner.data.model.User
import com.taskplanner.data.recurrence.RecurrenceCalculator
import com.taskplanner.data.repository.HabitWriteResult
import com.taskplanner.data.tracking.WorkTimeMath
import com.taskplanner.testing.AppFixture
import java.time.LocalDate
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlarmManager

/**
 * The other half of a reminder: the broadcast that wakes the app.
 *
 * Every entry point the system can call — the alarm itself, the reboot and clock broadcasts, and the
 * buttons on the notification — is driven here against the real repositories, the real AlarmManager
 * shadow and the real notification manager, so what is proven is the promise the user gets: one
 * announcement per minute, a button that finishes only its own task, and a device that wakes up with
 * its alarms put back the way the database says they should be.
 *
 * The habit broadcasts are checked the same way, because they arrive over the same receiver: a habit's
 * own alarm and a habit's two buttons, and the one reboot pass that puts both kinds of schedule back.
 *
 * The receiver has no clock of its own — it asks the repositories for what is due now — so the rows
 * are placed relative to the wall clock instead of a faked one. A habit is scheduled by a minute of the
 * day rather than by an instant, so those tests park the app's clock where their schedule is readable.
 */
@RunWith(RobolectricTestRunner::class)
class ReminderReceiverTest {

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

    @After
    fun tearDown() {
        // The fixture's two database threads have to be finished before the storage goes away. A query
        // still running when this test ends is reported against whichever test runs next, and that test
        // then fails for something this one did.
        fixture.close()
    }

    private fun now(): Long = System.currentTimeMillis()

    private suspend fun send(
        action: String,
        reminderUuid: String? = null,
        habitUuid: String? = null,
        habitDay: Long = ReminderReceiver.NO_DAY
    ) = ReminderReceiver.dispatch(
        action,
        reminderUuid,
        habitUuid,
        habitDay,
        fixture.reminderRepository,
        fixture.taskRepository,
        fixture.recurrenceRepository,
        fixture.habitRepository,
        fixture.habitReminders
    )

    /**
     * A reminder whose minute went by a couple of minutes ago — the state an alarm broadcast is sent
     * for. It is never armed (its instant has passed) and is still inside the late grace, so delivering
     * it is what the broadcast has to do.
     */
    private suspend fun dueReminder(userId: Long, title: String): Pair<Task, Reminder> {
        val task = taskWithLeadTime(userId, title, now() - 2 * MINUTE, 0)
        return task to reminderOf(task, 0)
    }

    private suspend fun taskWithLeadTime(
        userId: Long,
        title: String,
        dueAt: Long,
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

    /** The state a reboot leaves the device in: the rows are there, the alarms are not. */
    private suspend fun disarmEverything(userId: Long) {
        fixture.reminderDao.getLiveForUser(userId).forEach { fixture.reminderScheduler.cancel(it) }
    }

    @Test
    fun `the alarm broadcast announces the reminder whose minute came`() = runTest {
        val user = fixture.signIn()
        val (_, reminder) = dueReminder(user.id, "Feed the fish")

        send(ReminderReceiver.ACTION_REMINDER_DUE)

        assertEquals("Feed the fish", posted(reminder)!!.extras.getString(Notification.EXTRA_TITLE))
        assertNotNull(fixture.reminderDao.findByUuid(reminder.uuid)!!.notifiedAt)
    }

    @Test
    fun `a redelivered alarm broadcast makes no second copy`() = runTest {
        val user = fixture.signIn()
        val (_, reminder) = dueReminder(user.id, "Collect the laundry")

        send(ReminderReceiver.ACTION_REMINDER_DUE)
        // The system may hand the same broadcast over twice after restarting a process.
        send(ReminderReceiver.ACTION_REMINDER_DUE)

        assertEquals(1, shadowOf(notifications).allNotifications.size)
        assertNotNull(posted(reminder))
    }

    @Test
    fun `the complete button finishes its task and takes the reminder away`() = runTest {
        val user = fixture.signIn()
        val (task, reminder) = dueReminder(user.id, "Sign the contract")
        send(ReminderReceiver.ACTION_REMINDER_DUE)
        assertNotNull(posted(reminder))

        send(ReminderReceiver.ACTION_COMPLETE, reminder.uuid)

        val stored = fixture.taskDao.getTaskById(task.id)!!
        assertEquals(TaskStatus.COMPLETED, stored.status)
        assertTrue("and files it away", stored.archived)
        assertNull("the notification the user acted on is gone", posted(reminder))
        assertNotNull(fixture.reminderDao.findByUuid(reminder.uuid)!!.notifiedAt)

        send(ReminderReceiver.ACTION_REMINDER_DUE)
        assertEquals(
            "no later alarm or sweep puts it back",
            0,
            shadowOf(notifications).allNotifications.size
        )
    }

    @Test
    fun `a complete button whose task has disappeared only clears the notification`() = runTest {
        val user = fixture.signIn()
        val (task, reminder) = dueReminder(user.id, "Errand since forgotten")
        send(ReminderReceiver.ACTION_REMINDER_DUE)
        fixture.taskDao.deleteTasksByIds(listOf(task.id))

        send(ReminderReceiver.ACTION_COMPLETE, reminder.uuid)

        assertNull(posted(reminder))
        assertNotNull(
            "the row is closed off instead of staying pending for a task that is gone",
            fixture.reminderDao.findByUuid(reminder.uuid)!!.notifiedAt
        )
    }

    @Test
    fun `a button pressed by another profile does not touch the first profile's task`() = runTest {
        val first = fixture.signIn(name = "First", email = "first@example.com")
        val (task, reminder) = dueReminder(first.id, "First profile errand")
        send(ReminderReceiver.ACTION_REMINDER_DUE)

        fixture.signIn(name = "Second", email = "second@example.com")
        send(ReminderReceiver.ACTION_COMPLETE, reminder.uuid)

        val stored = fixture.taskDao.getTaskById(task.id)!!
        assertEquals(TaskStatus.TODO, stored.status)
        assertTrue("the other profile's task is left exactly as it was", !stored.archived)
        assertNull("only the notification goes away", posted(reminder))
    }

    @Test
    fun `the snooze button moves the same reminder ten minutes ahead`() = runTest {
        val user = fixture.signIn()
        val (task, reminder) = dueReminder(user.id, "Answer the call")
        send(ReminderReceiver.ACTION_REMINDER_DUE)
        val tappedAt = now()
        // The tap is placed on the clock the app reads, which is the only clock the promised delay can be
        // measured from: the fixture's clock stands still while the suite runs, so a window written against
        // the real wall clock would be a window on a clock this repository no longer asks.
        fixture.timeBase.shiftTo(tappedAt)

        send(ReminderReceiver.ACTION_SNOOZE, reminder.uuid)

        val snoozed = fixture.reminderDao.findByUuid(reminder.uuid)!!
        assertEquals(task.uuid, snoozed.taskUuid)
        assertEquals(
            "the new minute is the tap plus the promised delay",
            tappedAt + SNOOZE,
            snoozed.triggerAt
        )
        assertNull("pending again", snoozed.notifiedAt)
        assertNull(posted(reminder))
        assertEquals(listOf(snoozed.triggerAt), armedTriggers())
    }

    @Test
    fun `a reboot re-arms what is pending and delivers what passed while the device was off`() =
        runTest {
            val user = fixture.signIn()
            val (_, missed) = dueReminder(user.id, "Missed while asleep")
            val upcoming = taskWithLeadTime(user.id, "Still coming", now() + DAY, 30)
            disarmEverything(user.id)
            assertTrue(armedTriggers().isEmpty())

            send(Intent.ACTION_BOOT_COMPLETED)

            assertNotNull(posted(missed))
            val nextTrigger = upcoming.dueDate!! - 30 * MINUTE
            assertEquals(
                "the alarm is the row's own minute, once",
                listOf(nextTrigger),
                armedTriggers()
            )

            send(Intent.ACTION_BOOT_COMPLETED)
            assertEquals(
                "a second boot changes nothing: the reminder's own slot is replaced, not doubled",
                listOf(nextTrigger),
                armedTriggers()
            )
            assertEquals(1, shadowOf(notifications).allNotifications.size)
        }

    @Test
    fun `every broadcast that clears the alarms puts them back`() = runTest {
        val user = fixture.signIn()
        val actions = listOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED
        )
        val triggers = actions.mapIndexed { index, _ ->
            val due = now() + (index + 2) * DAY
            taskWithLeadTime(user.id, "Row for $index", due, 0)
            due
        }

        actions.forEach { action ->
            disarmEverything(user.id)
            assertTrue("$action starts with nothing armed", armedTriggers().isEmpty())

            send(action)

            assertEquals(
                "$action has to put every pending reminder back on the clock",
                triggers,
                armedTriggers().sorted()
            )
        }
    }

    @Test
    fun `a reboot first produces the instance the series owes and then arms its reminders`() =
        runTest {
            val user = fixture.signIn()
            val anchor = now() + 2 * DAY
            val task = taskWithLeadTime(user.id, "Water the plants", anchor, 15)
            val rule = fixture.recurrenceRepository.startSeries(task, RepeatUnit.DAY)!!
            val owed = RecurrenceCalculator.nextOccurrence(rule, rule.cursorAt)!!
            // The cursor had already moved when the process died: the row for the next occurrence,
            // and the reminder that belongs to it, were never written.
            fixture.recurrenceDao.upsertRule(rule.copy(cursorAt = owed))
            assertNull(fixture.taskDao.findSeriesInstance(rule.uuid, owed))
            disarmEverything(user.id)

            send(Intent.ACTION_BOOT_COMPLETED)

            val instance = fixture.taskDao.findSeriesInstance(rule.uuid, owed)!!
            assertEquals(owed, instance.dueDate)
            val inherited = reminderOf(instance, 15)
            assertNull(inherited.notifiedAt)
            assertEquals(
                "the lead time repeats with the task it was set on",
                owed - 15 * MINUTE,
                inherited.triggerAt
            )
            val armed = armedTriggers()
            assertTrue(
                "and it is on the clock, together with the live reminder of the open instance",
                armed.contains(owed - 15 * MINUTE) && armed.contains(anchor - 15 * MINUTE)
            )
            assertEquals(
                "nothing of the series was announced twice",
                0,
                shadowOf(notifications).allNotifications.size
            )
        }

    @Test
    fun `a broadcast the app never asked for asks for nothing`() = runTest {
        val user = fixture.signIn()
        val (_, reminder) = dueReminder(user.id, "Undisturbed task")

        send("com.other.app.ACTION_WEIRD")

        assertNull(posted(reminder))
        assertNull(
            "the row is still waiting for its own alarm",
            fixture.reminderDao.findByUuid(reminder.uuid)!!.notifiedAt
        )
    }

    @Test
    fun `a button broadcast with no reminder attached changes nothing`() = runTest {
        val user = fixture.signIn()
        val (task, reminder) = dueReminder(user.id, "Task with nothing to act on")
        send(ReminderReceiver.ACTION_REMINDER_DUE)

        send(ReminderReceiver.ACTION_COMPLETE, null)
        send(ReminderReceiver.ACTION_SNOOZE, null)

        assertNotNull(posted(reminder))
        assertEquals(TaskStatus.TODO, fixture.taskDao.getTaskById(task.id)!!.status)
        assertEquals(reminder.triggerAt, fixture.reminderDao.findByUuid(reminder.uuid)!!.triggerAt)
    }

    // region habit broadcasts

    /**
     * Parks the app's clock five minutes past seven on the day the suite runs, and returns that instant.
     *
     * A habit is asked for at a minute of the *day*, so unlike the task rows above — which are placed
     * relative to the real wall clock — a habit needs the clock parked where its schedule is readable.
     * Everything a habit test then compares against is derived from this same instant.
     */
    private fun parkJustAfterSeven(): Long {
        val zone = CalendarMath.systemZone()
        val today = LocalDate.ofEpochDay(WorkTimeMath.epochDayOf(System.currentTimeMillis(), zone))
        val parked = today.atTime(7, 5).atZone(zone).toInstant().toEpochMilli()
        fixture.timeBase.shiftTo(parked)
        return parked
    }

    private fun dayOf(instant: Long): Long = WorkTimeMath.epochDayOf(instant, CalendarMath.systemZone())

    /** The same minute on the day after [instant], which is what a re-armed habit slot must hold. */
    private fun nextMorning(instant: Long): Long =
        LocalDate.ofEpochDay(dayOf(instant) + 1).atTime(7, 0)
            .atZone(CalendarMath.systemZone()).toInstant().toEpochMilli()

    private suspend fun habitOwnedBy(owner: User, title: String): Habit {
        val uuid = when (
            val result = fixture.habitRepository.create(
                title = title,
                frequency = HabitFrequency.DAILY,
                startDay = dayOf(fixture.timeBase.wallMillis()) - 7,
                remindMinuteOfDay = MORNING
            )
        ) {
            is HabitWriteResult.Saved -> result.habitUuid
            is HabitWriteResult.Rejected -> throw AssertionError("creation refused: ${result.reason}")
        }
        return fixture.habitDao.getHabit(uuid, owner.id)!!
    }

    private fun postedHabit(target: Habit): Notification? =
        shadowOf(notifications).getNotification(HabitReminderIdentity.notificationIdOf(target.uuid))

    private fun habitTrigger(target: Habit): Long? = shadowOf(alarms).scheduledAlarms
        .firstOrNull { it.operation?.let { op -> shadowOf(op).requestCode } ==
            HabitReminderIdentity.alarmRequestCodeOf(target.uuid) }
        ?.triggerAtMs

    /** The state a reboot leaves the whole device in: the rows are there, the wakeups are not. */
    private fun loseEveryAlarm() {
        shadowOf(alarms).scheduledAlarms.toList()
            .forEach { alarms.cancel(it.operation!!) }
    }

    @Test
    fun `the habit broadcast announces today and moves its alarm to tomorrow`() = runTest {
        val user = fixture.signIn()
        val parked = parkJustAfterSeven()
        val day = dayOf(parked)
        val habit = habitOwnedBy(user, "Run")

        send(ReminderReceiver.ACTION_HABIT_DUE)

        assertEquals("Run", postedHabit(habit)!!.extras.getString(Notification.EXTRA_TITLE))
        assertEquals(
            "the day that was told about is the day that stops being asked for",
            day,
            fixture.habitDao.getHabit(habit.uuid, user.id)!!.remindedOnDay
        )
        assertEquals(
            "and the habit's one slot already holds tomorrow's minute",
            nextMorning(parked),
            habitTrigger(habit)
        )
    }

    @Test
    fun `the keep button marks the day the notice asked about and takes the notice down`() = runTest {
        val user = fixture.signIn()
        val parked = parkJustAfterSeven()
        val day = dayOf(parked)
        val habit = habitOwnedBy(user, "Stretch")
        send(ReminderReceiver.ACTION_HABIT_DUE)
        assertNotNull(postedHabit(habit))

        send(ReminderReceiver.ACTION_HABIT_MARK, habitUuid = habit.uuid, habitDay = day)

        assertEquals(
            "the day the notice named, not the day the tap fell on",
            listOf(day),
            fixture.habitDao.liveDays(habit.uuid, user.id, day, day)
        )
        assertNull(postedHabit(habit))
        assertEquals(habitTrigger(habit), nextMorning(parked))

        send(ReminderReceiver.ACTION_HABIT_DUE)
        assertEquals(
            "a kept day is not asked about again by the next sweep",
            0,
            shadowOf(notifications).allNotifications.size
        )
    }

    @Test
    fun `the snooze button defers the habit's own slot and owes the day again`() = runTest {
        val user = fixture.signIn()
        val parked = parkJustAfterSeven()
        val day = dayOf(parked)
        val habit = habitOwnedBy(user, "Read")
        send(ReminderReceiver.ACTION_HABIT_DUE)

        send(ReminderReceiver.ACTION_HABIT_SNOOZE, habitUuid = habit.uuid, habitDay = day)

        assertNull(postedHabit(habit))
        assertNull(
            "the note comes down, because the ask was postponed rather than answered",
            fixture.habitDao.getHabit(habit.uuid, user.id)!!.remindedOnDay
        )
        assertEquals(
            "the same slot now holds the promised ten minutes, not a second alarm beside it",
            parked + SNOOZE,
            habitTrigger(habit)
        )

        fixture.timeBase.shiftTo(parked + SNOOZE + MINUTE)
        send(ReminderReceiver.ACTION_HABIT_DUE)
        assertNotNull("and the deferred ask does arrive", postedHabit(habit))
    }

    @Test
    fun `a habit button broadcast with nothing attached changes nothing`() = runTest {
        val user = fixture.signIn()
        val parked = parkJustAfterSeven()
        val day = dayOf(parked)
        val habit = habitOwnedBy(user, "Walk")
        send(ReminderReceiver.ACTION_HABIT_DUE)

        send(ReminderReceiver.ACTION_HABIT_MARK, habitUuid = null, habitDay = day)
        send(ReminderReceiver.ACTION_HABIT_MARK, habitUuid = habit.uuid, habitDay = ReminderReceiver.NO_DAY)
        send(ReminderReceiver.ACTION_HABIT_SNOOZE, habitUuid = habit.uuid)

        assertNotNull(postedHabit(habit))
        assertEquals(day, fixture.habitDao.getHabit(habit.uuid, user.id)!!.remindedOnDay)
        assertTrue(fixture.habitDao.liveDays(habit.uuid, user.id, day, day).isEmpty())
    }

    @Test
    fun `a habit button pressed by another profile only takes the notice down`() = runTest {
        val first = fixture.signIn(name = "First", email = "first@example.com")
        val parked = parkJustAfterSeven()
        val day = dayOf(parked)
        val habit = habitOwnedBy(first, "First profile habit")
        send(ReminderReceiver.ACTION_HABIT_DUE)
        assertNotNull(postedHabit(habit))

        fixture.signIn(name = "Second", email = "second@example.com")
        send(ReminderReceiver.ACTION_HABIT_MARK, habitUuid = habit.uuid, habitDay = day)

        assertNull("only the notification goes away", postedHabit(habit))
        assertTrue(
            "the other profile's habit keeps its unmarked day",
            fixture.habitDao.liveDays(habit.uuid, first.id, day, day).isEmpty()
        )
    }

    @Test
    fun `a reboot puts the habit schedules back beside the task alarms`() = runTest {
        val user = fixture.signIn()
        val parked = parkJustAfterSeven()
        val habit = habitOwnedBy(user, "Meditate")
        taskWithLeadTime(user.id, "Feed the fish", parked + DAY, 30)
        loseEveryAlarm()
        assertTrue(armedTriggers().isEmpty())

        send(Intent.ACTION_BOOT_COMPLETED)

        assertEquals(
            "one rebuild, both kinds of schedule",
            nextMorning(parked),
            habitTrigger(habit)
        )
        assertTrue(
            "the task lead time came back in the same pass",
            armedTriggers().contains(parked + DAY - 30 * MINUTE)
        )
    }

    // endregion

    private companion object {
        const val MINUTE = 60_000L
        const val DAY = 24 * 60 * MINUTE
        const val SNOOZE = ReminderScheduler.SNOOZE_MILLIS

        /** Seven in the morning, in the minutes-after-midnight unit a habit row stores. */
        const val MORNING = 7 * 60
    }
}
