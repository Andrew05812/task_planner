package com.taskplanner.data.habit

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.taskplanner.data.calendar.CalendarMath
import com.taskplanner.data.model.Habit
import com.taskplanner.data.model.HabitFrequency
import com.taskplanner.data.model.User
import com.taskplanner.data.reminder.ReminderReceiver
import com.taskplanner.data.repository.HabitWriteResult
import com.taskplanner.testing.AppFixture
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.runBlocking
import org.junit.After
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
import org.robolectric.shadows.ShadowAlarmManager

/**
 * A habit's reminder, driven end to end: the schedule the row carries, the alarm that wakes the app for it,
 * the notice that arrives, and the note that keeps the day from being announced twice.
 *
 * The tests are written against the real AlarmManager and notification shadows, because the whole design is
 * a claim about the device rather than about the database — that a habit has at most one wakeup pending,
 * that a day is asked for at the minute the user named, that being told is what stops the asking, and that
 * a wake of the app puts the alarms back the way the rows say. A fake scheduler would let every one of
 * those promises be broken silently.
 *
 * The week is pinned Monday-first and the zone to one that jumps at the spring weekend, so "this week" and
 * "at seven" mean the same seven days and the same instant here as they do wherever the suite runs. The
 * clock is parked at a named minute of a named day before each habit is created, because a habit born at
 * 06:00 and one born at 08:00 are owed different things.
 */
@RunWith(RobolectricTestRunner::class)
class HabitReminderCoordinatorTest {

    private lateinit var defaultZone: TimeZone
    private lateinit var defaultLocale: Locale
    private lateinit var context: Context
    private lateinit var fixture: AppFixture
    private lateinit var alarmsService: AlarmManager
    private lateinit var notifications: NotificationManager
    private lateinit var zone: ZoneId
    private var userId: Long = -1L

    private val repository get() = fixture.habitRepository
    private val coordinator get() = fixture.habitReminders
    private val habitDao get() = fixture.habitDao

    private val monday: LocalDate = LocalDate.of(2026, 6, 1)
    private val tuesday: LocalDate = LocalDate.of(2026, 6, 2)
    private val wednesday: LocalDate = LocalDate.of(2026, 6, 3)
    private val thursday: LocalDate = LocalDate.of(2026, 6, 4)
    private val friday: LocalDate = LocalDate.of(2026, 6, 5)

    /** Seven in the morning — the minute nearly every test below asks to be reminded at. */
    private val morning: Int = 7 * 60

    private fun at(day: LocalDate, hour: Int, minute: Int = 0): Long =
        day.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun dayOf(day: LocalDate): Long = day.toEpochDay()

    @Before
    fun setUp() {
        defaultZone = TimeZone.getDefault()
        defaultLocale = Locale.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"))
        Locale.setDefault(Locale.GERMANY)
        zone = CalendarMath.systemZone()
        context = ApplicationProvider.getApplicationContext()
        alarmsService = context.getSystemService(AlarmManager::class.java)
        notifications = context.getSystemService(NotificationManager::class.java)
        // Exact alarms are the norm these tests describe; the limited-alarm case belongs to the scheduler.
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        fixture = AppFixture(context)
    }

    @After
    fun tearDown() {
        fixture.close()
        TimeZone.setDefault(defaultZone)
        Locale.setDefault(defaultLocale)
    }

    /** Signs the profile in and puts the whole timeline at [hour]:[minute] on Wednesday. */
    private suspend fun signInAt(hour: Int, minute: Int = 0): User {
        val user = fixture.signIn()
        userId = user.id
        fixture.timeBase.shiftTo(at(wednesday, hour, minute))
        return user
    }

    private suspend fun habit(
        title: String = "Run",
        frequency: HabitFrequency = HabitFrequency.DAILY,
        daysOfWeek: List<DayOfWeek> = emptyList(),
        timesPerWeek: Int? = null,
        startDay: Long = dayOf(monday),
        endDay: Long? = null,
        remindMinuteOfDay: Int? = morning,
        profile: User? = null
    ): Habit {
        val saved = when (
            val result = repository.create(title, frequency, startDay, daysOfWeek, timesPerWeek, endDay, remindMinuteOfDay)
        ) {
            is HabitWriteResult.Saved -> result.habitUuid
            is HabitWriteResult.Rejected -> throw AssertionError("creation refused: ${result.reason}")
        }
        val owner = profile?.id ?: userId
        return habitDao.getHabit(saved, owner)!!
    }

    private suspend fun mark(habitUuid: String, day: Long) {
        if (repository.mark(habitUuid, day) !is HabitWriteResult.Saved) {
            throw AssertionError("mark refused")
        }
    }

    /** The state of the stored row, so a note written by the pass can be read back. */
    private suspend fun stored(habit: Habit): Habit = habitDao.getHabit(habit.uuid, habit.userId) ?: habit

    private fun alarms() = shadowOf(alarmsService).scheduledAlarms

    private fun alarmCount(): Int = alarms().size

    private fun armedInstant(habit: Habit): Long? = alarms()
        .firstOrNull { it.operation?.let { op -> shadowOf(op).requestCode } ==
            HabitReminderIdentity.alarmRequestCodeOf(habit.uuid) }
        ?.triggerAtMs

    private fun notice(habit: Habit) = shadowOf(notifications)
        .getNotification(HabitReminderIdentity.notificationIdOf(habit.uuid))

    /** Simulates the reboot that clears every alarm: the rows stay, the wakeups do not. */
    private fun loseEveryAlarm() {
        alarms().toList().forEach { alarmsService.cancel(it.operation!!) }
        assertEquals(0, alarmCount())
    }

    // region arming

    @Test
    fun `a habit born after its minute is armed for the next morning it asks for`() = runBlocking {
        signInAt(12, 0)

        val habit = habit()

        val armed = alarms().single()
        assertEquals(at(thursday, 7), armed.triggerAtMs)
        val intent = shadowOf(armed.operation).savedIntent
        assertEquals(ReminderReceiver.ACTION_HABIT_DUE, intent.action)
        assertEquals(
            "the wakeup names nothing: what is owed is read from the signed-in profile's rows",
            0,
            intent.extras?.size() ?: 0
        )
    }

    @Test
    fun `a habit born before its minute is armed for the same morning`() = runBlocking {
        signInAt(6, 0)

        val habit = habit()

        assertEquals(at(wednesday, 7), armedInstant(habit))
    }

    @Test
    fun `a weekday habit waits for the next day its mask names`() = runBlocking {
        signInAt(12, 0)

        val habit = habit(
            frequency = HabitFrequency.DAYS_OF_WEEK,
            daysOfWeek = listOf(DayOfWeek.FRIDAY)
        )

        assertEquals(
            "a habit named for Fridays is not owed a Monday wakeup",
            at(friday, 7),
            armedInstant(habit)
        )
    }

    @Test
    fun `a habit with no minute is armed with nothing`() = runBlocking {
        signInAt(6, 0)

        habit(remindMinuteOfDay = null)

        assertEquals(0, alarmCount())
    }

    @Test
    fun `a habit whose window has ended stops being armed`() = runBlocking {
        signInAt(12, 0)

        val habit = habit(endDay = dayOf(tuesday))

        assertEquals(
            "the schedule stopped on Tuesday, so nothing wakes the app for it any more",
            0,
            alarmCount()
        )
        assertNull(stored(habit).remindedOnDay)
    }

    @Test
    fun `rewriting the schedule keeps one alarm, at the newest minute`() = runBlocking {
        signInAt(12, 0)
        val habit = habit()

        val moved = when (
            val result = repository.edit(habit.uuid, "Run", HabitFrequency.DAILY, dayOf(monday), remindMinuteOfDay = 9 * 60)
        ) {
            is HabitWriteResult.Saved -> result.habitUuid
            is HabitWriteResult.Rejected -> throw AssertionError("edit refused: ${result.reason}")
        }

        assertEquals(
            "one habit owns one slot, so an edit replaces its wakeup instead of stacking a second",
            1,
            alarmCount()
        )
        assertEquals(at(thursday, 9), armedInstant(habitDao.getHabit(moved, userId)!!))
    }

    @Test
    fun `a habit emptied of its minute loses its wakeup`() = runBlocking {
        signInAt(12, 0)
        val habit = habit()

        repository.edit(habit.uuid, "Run", HabitFrequency.DAILY, dayOf(monday), remindMinuteOfDay = null)

        assertEquals(0, alarmCount())
    }

    @Test
    fun `an archived habit makes no noise and a restored one asks again`() = runBlocking {
        signInAt(12, 0)
        val habit = habit()

        repository.archive(habit.uuid)
        assertEquals(0, alarmCount())

        repository.unarchive(habit.uuid)
        assertEquals(at(thursday, 7), armedInstant(stored(habit)))
    }

    @Test
    fun `a deleted habit loses its alarm and the notice it left behind`() = runBlocking {
        signInAt(7, 30)
        val habit = habit()
        assertEquals(1, coordinator.deliverDue())
        assertNotNull(notice(habit))

        repository.remove(habit.uuid)

        assertEquals("nothing is left that could still ring", 0, alarmCount())
        assertNull("and the ask the user has thrown away does not stay on screen", notice(habit))
    }

    // endregion

    // region the daily ask

    @Test
    fun `the pass announces a habit whose minute has come, and only once that day`() = runBlocking {
        signInAt(7, 30)
        val habit = habit()

        assertEquals(1, coordinator.deliverDue())
        assertEquals(
            "the note says the day was shown, so an alarm and a sweep cannot both speak",
            dayOf(wednesday),
            stored(habit).remindedOnDay
        )
        assertNotNull(notice(habit))

        assertEquals(0, coordinator.deliverDue())
        assertEquals("and the same day is never announced twice", 1, alarmCount())
    }

    @Test
    fun `announcing a day leaves the slot holding the next one it asks for`() = runBlocking {
        signInAt(7, 30)
        val habit = habit()

        coordinator.deliverDue()

        assertEquals(
            "the pass never leaves a repeating habit without a wakeup",
            at(thursday, 7),
            armedInstant(habit)
        )
    }

    @Test
    fun `a minute more than an hour gone is retired without a notice`() = runBlocking {
        signInAt(8, 30)
        val habit = habit()

        assertEquals(
            "a phone that was asleep since seven does not owe the user a stale nudge",
            0,
            coordinator.deliverDue()
        )
        assertNull(notice(habit))
        assertEquals(dayOf(wednesday), stored(habit).remindedOnDay)
        assertEquals(at(thursday, 7), armedInstant(habit))
    }

    @Test
    fun `a day the schedule does not name is left unannounced and un-noted`() = runBlocking {
        signInAt(7, 30)
        val habit = habit(frequency = HabitFrequency.DAYS_OF_WEEK, daysOfWeek = listOf(DayOfWeek.FRIDAY))

        assertEquals(0, coordinator.deliverDue())
        assertNull(notice(habit))
        assertNull(
            "Wednesday asked for nothing, so there is no delivery note to write for it",
            stored(habit).remindedOnDay
        )
        assertEquals(at(friday, 7), armedInstant(habit))
    }

    @Test
    fun `a day already kept is not announced, and taking the mark back lets it be asked again`() = runBlocking {
        signInAt(7, 30)
        val habit = habit()

        mark(habit.uuid, dayOf(wednesday))
        assertEquals(0, coordinator.deliverDue())
        assertNull(stored(habit).remindedOnDay)

        val taken = repository.takeBack(habit.uuid, dayOf(wednesday))
        assertTrue(taken is HabitWriteResult.Saved)
        assertEquals(
            "the ask was never shown, so the day is owed once more the moment it stops being kept",
            1,
            coordinator.deliverDue()
        )
        assertNotNull(notice(habit))
    }

    @Test
    fun `a weekly habit goes quiet once its week holds the count`() = runBlocking {
        signInAt(7, 30)
        val habit = habit(frequency = HabitFrequency.TIMES_PER_WEEK, timesPerWeek = 2)
        mark(habit.uuid, dayOf(monday))
        mark(habit.uuid, dayOf(tuesday))

        assertEquals(
            "two of two kept says nothing left to ask for this week, even on a day that has no mark",
            0,
            coordinator.deliverDue()
        )

        repository.takeBack(habit.uuid, dayOf(tuesday))
        assertEquals(
            "with one kept of the two it asked for, today is worth asking about again",
            1,
            coordinator.deliverDue()
        )
    }

    @Test
    fun `notifications switched off announces nothing and writes no note`() = runBlocking {
        signInAt(7, 30)
        val habit = habit()
        shadowOf(notifications).setNotificationsEnabled(false)

        assertEquals(0, coordinator.deliverDue())
        assertNull(notice(habit))
        assertNull(
            "a day the user could not see is not a day that was shown",
            stored(habit).remindedOnDay
        )

        shadowOf(notifications).setNotificationsEnabled(true)
        assertEquals(
            "and the next wake of the app reaches them once notifications are back on",
            1,
            coordinator.deliverDue()
        )
    }

    @Test
    fun `marking today from the grid takes the notice down and leaves the schedule armed`() = runBlocking {
        signInAt(7, 30)
        val habit = habit()
        coordinator.deliverDue()
        assertNotNull(notice(habit))

        mark(habit.uuid, dayOf(wednesday))

        assertNull(notice(habit))
        assertEquals(
            "the alarm in the habit's slot is tomorrow's ask, not the one that was just answered",
            at(thursday, 7),
            armedInstant(habit)
        )
    }

    @Test
    fun `a mark for another day leaves today's ask on screen`() = runBlocking {
        signInAt(7, 30)
        val habit = habit()
        coordinator.deliverDue()

        mark(habit.uuid, dayOf(tuesday))

        assertNotNull(
            "yesterday's run says nothing about today's, and the notice belongs to today",
            notice(habit)
        )
    }

    // endregion

    // region snooze

    @Test
    fun `snoozing takes the notice down and promises the ask again`() = runBlocking {
        signInAt(7, 30)
        val habit = habit()
        coordinator.deliverDue()

        val atSnooze = at(wednesday, 7, 35)
        assertTrue(coordinator.snooze(habit.uuid, dayOf(wednesday), atSnooze))

        assertNull(notice(habit))
        assertNull(
            "the deferred ask is un-said: the day is owed again, and the note would silence it",
            stored(habit).remindedOnDay
        )
        assertEquals(at(wednesday, 7, 45), armedInstant(habit))

        assertEquals(1, coordinator.deliverDue(at(wednesday, 7, 46)))
        assertEquals(dayOf(wednesday), stored(habit).remindedOnDay)
    }

    @Test
    fun `a sweep that reaches the habit before its snooze brings the ask back rather than losing it`() = runBlocking {
        signInAt(7, 30)
        val habit = habit()
        coordinator.deliverDue()
        coordinator.snooze(habit.uuid, dayOf(wednesday), at(wednesday, 7, 35))

        // The alarm may be dropped by a reboot in those ten minutes; the sweep is the net under it, and
        // it delivers early rather than not at all.
        assertEquals(1, coordinator.deliverDue(at(wednesday, 7, 40)))
        assertEquals(at(thursday, 7), armedInstant(habit))
    }

    @Test
    fun `a snooze of a notice from an earlier day only takes the notice down`() = runBlocking {
        signInAt(6, 0)
        val habit = habit()

        assertFalse(coordinator.snooze(habit.uuid, dayOf(tuesday), at(wednesday, 6, 5)))
        assertEquals(
            "the schedule's own wakeup is untouched: yesterday's ask is not deferred onto today's clock",
            at(wednesday, 7),
            armedInstant(habit)
        )
        assertNull(stored(habit).remindedOnDay)
    }

    @Test
    fun `a habit with no minute has nothing to snooze`() = runBlocking {
        signInAt(7, 30)
        val habit = habit(remindMinuteOfDay = null)

        assertFalse(coordinator.snooze(habit.uuid, dayOf(wednesday), at(wednesday, 7, 35)))
        assertEquals(0, alarmCount())
    }

    // endregion

    // region rebuilding the alarms

    @Test
    fun `a wake of the process re-arms every habit of the profile and delivers what came due`() = runBlocking {
        signInAt(7, 30)
        val daily = habit()
        val fridayHabit = habit(
            title = "Swim",
            frequency = HabitFrequency.DAYS_OF_WEEK,
            daysOfWeek = listOf(DayOfWeek.FRIDAY)
        )
        loseEveryAlarm()

        assertEquals(1, coordinator.rescheduleAll())

        assertEquals(
            "the day that was owed while the alarms were gone is told, not skipped",
            dayOf(wednesday),
            stored(daily).remindedOnDay
        )
        assertEquals(at(thursday, 7), armedInstant(daily))
        assertEquals(
            "and a habit whose minute has not come is armed for the day it does ask",
            at(friday, 7),
            armedInstant(fridayHabit)
        )
    }

    @Test
    fun `a rebuild re-arms a habit that was already announced today for its next day`() = runBlocking {
        signInAt(7, 30)
        val habit = habit()
        coordinator.deliverDue()
        loseEveryAlarm()

        coordinator.rescheduleAll()

        assertEquals(at(thursday, 7), armedInstant(habit))
    }

    @Test
    fun `another profile's habits stay quiet`() = runBlocking {
        val first = signInAt(7, 30)
        val firstHabit = habit()
        val second = fixture.signIn(name = "Other", email = "other@example.com")
        val secondHabit = habit(title = "Read", profile = second)

        assertEquals(1, coordinator.deliverDue())
        assertEquals(dayOf(wednesday), stored(secondHabit).remindedOnDay)
        assertNotNull(notice(secondHabit))
        assertNull(
            "the pass reads the signed-in profile's rows, so another account's habit is never announced",
            stored(firstHabit).remindedOnDay
        )
        assertNull(notice(firstHabit))

        fixture.switchTo(first)
        assertEquals(
            "and signing back in finds the ask still owed, because nothing wrote a note for it",
            1,
            coordinator.deliverDue()
        )
        assertEquals(dayOf(wednesday), stored(firstHabit).remindedOnDay)
    }
}
