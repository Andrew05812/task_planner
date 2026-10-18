package com.taskplanner.data.reminder

import android.app.AlarmManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.taskplanner.data.model.Reminder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlarmManager

/**
 * What arming a reminder actually puts on the device clock.
 *
 * Robolectric's [AlarmManager] shadow records every scheduled alarm, so these tests see the same
 * calls the phone would: whether the minute is exact, whether it survives doze, whether a reminder
 * that is already late is armed at all, and whether cancelling really takes it off.
 */
@RunWith(RobolectricTestRunner::class)
class ReminderSchedulerTest {

    private lateinit var context: Context
    private lateinit var alarmManager: AlarmManager
    private lateinit var scheduler: ReminderScheduler

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        alarmManager = context.getSystemService(AlarmManager::class.java)
        scheduler = ReminderScheduler(context)
    }

    private fun reminder(
        taskUuid: String = "task-1",
        offsetMinutes: Int = 15,
        triggerAt: Long = System.currentTimeMillis() + HOUR
    ): Reminder = Reminder(
        uuid = Reminder.identity(taskUuid, offsetMinutes),
        taskUuid = taskUuid,
        userId = 1,
        offsetMinutes = offsetMinutes,
        triggerAt = triggerAt,
        anchorAt = triggerAt + offsetMinutes * MINUTE
    )

    private fun alarms() = shadowOf(alarmManager).scheduledAlarms

    @Test
    fun `arming a future reminder puts it on the clock for its own minute`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val target = reminder()

        assertTrue(scheduler.arm(target))

        val armed = alarms().single()
        assertEquals(target.triggerAt, armed.triggerAtMs)
        assertEquals(AlarmManager.RTC_WAKEUP, armed.type)
        assertEquals(
            "a deadline reminder has to wake the device out of doze, not wait for a window",
            true,
            armed.isAllowWhileIdle
        )
        assertEquals(
            "and it has to be armed for the minute the user named, not a range around it",
            ShadowAlarmManager.WINDOW_EXACT,
            armed.windowLengthMs
        )
    }

    @Test
    fun `a reminder whose minute already passed is not armed`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)

        assertFalse(scheduler.arm(reminder(triggerAt = System.currentTimeMillis() - MINUTE)))

        assertTrue(
            "nothing to ring, and arming it would deliver it the moment the device wakes",
            alarms().isEmpty()
        )
    }

    @Test
    fun `exact alarms withheld still leave the reminder armed, only later`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        val target = reminder()
        assertFalse(scheduler.canUseExactAlarms())

        assertTrue(
            "the platform refusing an exact alarm must not cost the user the reminder",
            scheduler.arm(target)
        )

        val armed = alarms().single()
        assertEquals(target.triggerAt, armed.triggerAtMs)
        assertTrue(armed.isAllowWhileIdle)
        assertFalse(
            "without exact permission the alarm is a window, which is what the editor warns about",
            armed.windowLengthMs == ShadowAlarmManager.WINDOW_EXACT
        )
    }

    @Test
    fun `cancelling takes the reminder off the clock`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val target = reminder()
        scheduler.arm(target)
        assertEquals(1, alarms().size)

        scheduler.cancel(target)

        assertTrue(alarms().isEmpty())
    }

    @Test
    fun `re-arming one reminder never reuses another reminder's slot`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val first = reminder(offsetMinutes = 15)
        val second = reminder(offsetMinutes = 60)

        scheduler.arm(first)
        scheduler.arm(second)
        scheduler.arm(first.copy(triggerAt = first.triggerAt + MINUTE))

        val slots = alarms().map { shadowOf(it.operation).requestCode }
        assertEquals(
            "each reminder owns exactly one alarm slot, however often it is re-armed",
            slots.size,
            slots.distinct().size
        )
        assertEquals(2, slots.size)
    }

    @Test
    fun `the alarm carries no task data, the receiver reads it from the database`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val target = reminder()

        scheduler.arm(target)

        val intent = shadowOf(alarms().single().operation).savedIntent
        assertEquals(ReminderReceiver.ACTION_REMINDER_DUE, intent.action)
        assertEquals(ReminderReceiver::class.java.name, intent.component?.className)
        assertTrue(
            "an alarm that names no reminder cannot announce the wrong task",
            intent.extras == null || intent.extras!!.isEmpty
        )
    }

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
    }
}
