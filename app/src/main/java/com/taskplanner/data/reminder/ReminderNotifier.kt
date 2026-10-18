package com.taskplanner.data.reminder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.text.format.DateFormat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.taskplanner.R
import com.taskplanner.data.model.Category
import com.taskplanner.data.model.Reminder
import com.taskplanner.data.model.Task
import com.taskplanner.ui.MainActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns a due reminder into the notification the user asked for: what is due, when, in which
 * category, and what they can do about it without leaving the shade.
 */
@Singleton
class ReminderNotifier @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val manager: NotificationManager?
        get() = context.getSystemService(NotificationManager::class.java)

    /**
     * Creates the channel every reminder notification is posted to.
     *
     * A channel is created once and then belongs to the user: Android ignores a later call that tries
     * to change its importance, so this only ever *ensures* the channel exists and is called at
     * start-up rather than tuning it on every notification. The importance is HIGH so a reminder that
     * arrives while the phone is in use actually appears on screen instead of only landing in the
     * shade — which is what a deadline notification is for.
     */
    fun ensureChannel() {
        val notificationManager = manager ?: return
        // Channels only exist from API 26; below that the notification posts against the legacy bar and
        // the channel id in the builder is ignored, so there is nothing to create.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (notificationManager.getNotificationChannel(CHANNEL_ID) != null) return
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.reminder_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.reminder_channel_description)
            }
        )
    }

    /** The Settings switch, or Android 13's runtime permission, said no. */
    fun canPostNotifications(): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

    /**
     * Posts [reminder] for [task]. Returns false when the notification bar could not show it, in which
     * case the caller leaves the reminder pending rather than recording a notification the user never
     * got.
     */
    fun show(task: Task, category: Category?, reminder: Reminder): Boolean {
        ensureChannel()
        if (!canPostNotifications()) return false

        val deadline = task.dueDate ?: reminder.triggerAt
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_reminder_notification)
            .setContentTitle(task.title)
            .setContentText(context.getString(R.string.reminder_content_format, formatDeadline(deadline)))
            .setStyle(NotificationCompat.BigTextStyle().bigText(buildBody(task, category, deadline)))
            .setSubText(category?.name)
            .setColor(category?.color ?: DEFAULT_ACCENT)
            .setWhen(reminder.triggerAt)
            .setShowWhen(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setGroup(GROUP_KEY)
            .setContentIntent(deepLinkIntent(task, reminder, Reminder.Action.OPEN))
            .setAutoCancel(true)
            // Re-arming the same reminder after a snooze updates its own notification quietly.
            .setOnlyAlertOnce(true)
            .addAction(
                R.drawable.ic_reminder_complete,
                context.getString(R.string.reminder_action_complete),
                actionIntent(reminder, ReminderReceiver.ACTION_COMPLETE, Reminder.Action.COMPLETE)
            )
            .addAction(
                R.drawable.ic_reminder_snooze,
                context.getString(R.string.reminder_action_snooze),
                actionIntent(reminder, ReminderReceiver.ACTION_SNOOZE, Reminder.Action.SNOOZE)
            )
            .addAction(
                R.drawable.ic_reminder_edit,
                context.getString(R.string.reminder_action_edit),
                deepLinkIntent(task, reminder, Reminder.Action.EDIT)
            )
            .build()

        return try {
            NotificationManagerCompat.from(context).notify(reminder.notificationId, notification)
            true
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS was taken away between the check and the post. The reminder stays
            // pending, so a sweep after the user re-enables notifications can still deliver it.
            false
        }
    }

    fun cancel(reminder: Reminder) {
        NotificationManagerCompat.from(context).cancel(reminder.notificationId)
    }

    private fun buildBody(task: Task, category: Category?, deadline: Long): String =
        listOf(
            task.description.takeIf { it.isNotBlank() },
            category?.name?.takeIf { it.isNotBlank() }
                ?.let { context.getString(R.string.reminder_body_category_format, it) },
            context.getString(R.string.reminder_body_due_format, formatDeadline(deadline))
        ).filterNotNull().joinToString("\n")

    /** The device's own date and clock formats, so 24-hour and 12-hour locales read naturally. */
    private fun formatDeadline(millis: Long): String {
        val date = Date(millis)
        return context.getString(
            R.string.reminder_deadline_format,
            DateFormat.getDateFormat(context).format(date),
            DateFormat.getTimeFormat(context).format(date)
        )
    }

    /**
     * Deep link into the task's own editor. Tapping the body and tapping Edit arrive at the same
     * place — the app has one screen per task — but they are separate PendingIntents with separate
     * request codes, so neither can replace the other.
     */
    private fun deepLinkIntent(task: Task, reminder: Reminder, action: Reminder.Action): PendingIntent =
        PendingIntent.getActivity(
            context,
            reminder.requestCodeFor(action),
            Intent(Intent.ACTION_VIEW, Uri.parse(taskDeepLink(task.id)))
                .setClass(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun actionIntent(reminder: Reminder, action: String, slot: Reminder.Action): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            reminder.requestCodeFor(slot),
            Intent(context, ReminderReceiver::class.java)
                .setAction(action)
                .putExtra(ReminderReceiver.EXTRA_REMINDER_UUID, reminder.uuid),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    companion object {
        const val CHANNEL_ID = "task_reminders"
        const val GROUP_KEY = "com.taskplanner.reminders"

        /** Fallback accent for a task with no category to borrow a colour from. */
        private val DEFAULT_ACCENT = 0xFF6750A4.toInt()

        fun taskDeepLink(taskId: Long): String = "taskplanner://task/$taskId"
    }
}
