package com.taskplanner.data.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.taskplanner.data.model.Reminder
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The AlarmManager side of reminders: one wakeup per pending reminder, and its mirror-image cancel.
 *
 * Alarms do not survive a reboot and the system may cancel them, so an alarm is only ever an
 * acceleration of the real rule: what is due is decided from the database by
 * [com.taskplanner.data.repository.ReminderRepository.deliverDue], which runs again on start-up, on a
 * profile switch, after a sync pull and from [ReminderSweepWorker]. Losing an alarm therefore delays a
 * reminder; it cannot lose one silently.
 */
@Singleton
class ReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val alarmManager: AlarmManager?
        get() = context.getSystemService(AlarmManager::class.java)

    /**
     * Arms [reminder]. Returns false when the instant is already in the past and nothing was armed.
     *
     * Exact alarms are the difference between "7:00" and "somewhere in the next window", and Android
     * 12+ lets the user take them away per app. When that permission is not granted the reminder is
     * still armed as an allow-while-idle alarm — late, possibly by up to a window, but not dropped —
     * and the editor says so instead of promising a minute it cannot keep.
     */
    fun arm(reminder: Reminder, now: Long = System.currentTimeMillis()): Boolean {
        if (reminder.triggerAt <= now) return false
        val manager = alarmManager ?: return false
        val intent = alarmIntent(reminder)
        if (canUseExactAlarms()) {
            // allow-while-idle is the variant that survives doze; the plain exact alarm does not.
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.triggerAt, intent)
        } else {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.triggerAt, intent)
        }
        return true
    }

    fun cancel(reminder: Reminder) {
        alarmManager?.cancel(alarmIntent(reminder))
    }

    /** Whether this app may set alarms that fire at the minute the user chose. */
    fun canUseExactAlarms(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager?.canScheduleExactAlarms() ?: false
        } else {
            // Below Android 12 exact alarms are a normal capability of any app.
            true
        }

    /**
     * The alarm carries no task title, no id and nothing else: the receiver reads the database and
     * delivers whatever is due. A broadcast that could not be forged into a wrong notification is not
     * a broadcast worth protecting, and the receiver is not exported, so only this app's own
     * PendingIntents and the system can reach it.
     */
    private fun alarmIntent(reminder: Reminder): PendingIntent = PendingIntent.getBroadcast(
        context,
        reminder.alarmRequestCode(),
        Intent(context, ReminderReceiver::class.java).setAction(ReminderReceiver.ACTION_REMINDER_DUE),
        // Immutable: the system must not be able to rewrite what the alarm delivers; update-current
        // keeps one reminder to one alarm when it is re-armed after a snooze or a moved deadline.
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    companion object {
        /**
         * How long past its minute a reminder may still be delivered.
         *
         * A device that was off, in deep doze, or simply not opened since yesterday does not owe the
         * user a pile of late notifications; it owes them the ones that are still useful. Older rows
         * are retired instead of staying pending forever.
         */
        const val LATE_GRACE_MILLIS = 60 * 60 * 1000L

        /** Snooze length offered by the notification's own button. */
        const val SNOOZE_MILLIS = 10 * 60 * 1000L

        /**
         * Enqueues the periodic sweep that rebuilds the alarms from the database.
         *
         * No constraints: a reminder must survive being offline, and WorkManager's own schedule is
         * flexible enough that the battery cost of a six-hourly pass is not worth a missed deadline.
         * KEEP means calling this on every start-up leaves the existing schedule alone instead of
         * restarting the clock, and a periodic schedule outlives a reboot on its own.
         */
        fun ensureSweep(context: Context) {
            PeriodicWorkRequestBuilder<ReminderSweepWorker>(6, TimeUnit.HOURS)
                .build()
                .let {
                    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                        ReminderSweepWorker.UNIQUE_PERIODIC,
                        ExistingPeriodicWorkPolicy.KEEP,
                        it
                    )
                }
        }
    }
}
