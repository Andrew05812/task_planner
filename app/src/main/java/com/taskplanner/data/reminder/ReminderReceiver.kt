package com.taskplanner.data.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.taskplanner.data.repository.RecurrenceRepository
import com.taskplanner.data.repository.ReminderRepository
import com.taskplanner.data.repository.TaskRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Every way a reminder reaches the user from outside the app: the alarm itself, the system telling
 * us the device rebooted or the clock moved, and the buttons on the notification.
 *
 * The receiver keeps no state and does no work of its own — it wakes the repositories on the
 * application component and finishes as soon as they have answered. Everything it calls is idempotent
 * (`deliverDue` skips rows it has already posted, `rescheduleAll` replaces the alarms it re-arms), so
 * a broadcast that arrives twice, or one the system redelivers after a killed process, cannot produce
 * two notifications for the same minute.
 */
class ReminderReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ReminderEntryPoint {
        fun reminderRepository(): ReminderRepository
        fun taskRepository(): TaskRepository
        fun recurrenceRepository(): RecurrenceRepository
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in HANDLED) return

        // The broadcast is only a wakeup: the work is database work, and goAsync() is what keeps the
        // process alive long enough for it instead of the receiver being killed half way through.
        val pendingResult = goAsync()
        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            ReminderEntryPoint::class.java
        )

        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                handle(action, intent, entryPoint)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun handle(
        action: String,
        intent: Intent,
        entryPoint: ReminderEntryPoint
    ) = dispatch(
        action,
        intent.getStringExtra(EXTRA_REMINDER_UUID),
        entryPoint.reminderRepository(),
        entryPoint.taskRepository(),
        entryPoint.recurrenceRepository()
    )

    companion object {
        /** Our own alarm and button broadcasts, sent by this app's PendingIntents. */
        const val ACTION_REMINDER_DUE = "com.taskplanner.action.REMINDER_DUE"
        const val ACTION_COMPLETE = "com.taskplanner.action.REMINDER_COMPLETE"
        const val ACTION_SNOOZE = "com.taskplanner.action.REMINDER_SNOOZE"

        /**
         * The system broadcasts that invalidate every armed alarm: a reboot clears them, a clock change
         * makes their instants wrong, a timezone change moves the wall clock they are read on, and an
         * app update kills them. `Intent.ACTION_TIME_CHANGED` is the constant whose action string is
         * `android.intent.action.TIME_SET`, which is what the manifest filter declares.
         */
        private val RESTORE_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED
        )

        const val EXTRA_REMINDER_UUID = "com.taskplanner.extra.REMINDER_UUID"

        private val HANDLED = RESTORE_ACTIONS + setOf(
            ACTION_REMINDER_DUE,
            ACTION_COMPLETE,
            ACTION_SNOOZE
        )

        /**
         * What each broadcast asks for, written against the repositories instead of the receiver so
         * the promises made to the user — a button completes its own task, a reboot puts the alarms
         * back, a redelivered broadcast changes nothing twice — are testable without the app graph.
         */
        internal suspend fun dispatch(
            action: String,
            reminderUuid: String?,
            reminders: ReminderRepository,
            tasks: TaskRepository,
            recurrence: RecurrenceRepository
        ) {
            when {
                action == ACTION_REMINDER_DUE -> reminders.deliverDue()

                action in RESTORE_ACTIONS -> {
                    // A series that was due while the device was off has to produce its instance first:
                    // its reminders belong to the instance, and the row they point at does not exist yet.
                    recurrence.ensureOpenInstances()
                    reminders.rescheduleAll()
                }

                action == ACTION_COMPLETE -> {
                    if (reminderUuid == null) return
                    // getTaskById is scoped to the signed-in profile, so a button pressed after the user
                    // switched away finds no task and drops the notification instead of acting on a row
                    // this profile cannot see.
                    val task = reminders.taskIdOf(reminderUuid)
                        ?.let { tasks.getTaskById(it) }
                    if (task == null) {
                        reminders.markHandledFromNotification(reminderUuid)
                        return
                    }
                    tasks.completeAndArchive(task.id)
                    reminders.markHandledFromNotification(reminderUuid)
                }

                action == ACTION_SNOOZE -> reminderUuid?.let { reminders.snooze(it) }
            }
        }
    }
}
