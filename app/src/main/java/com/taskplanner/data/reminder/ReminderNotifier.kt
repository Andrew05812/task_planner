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
import com.taskplanner.data.habit.HabitAdherence
import com.taskplanner.data.habit.HabitReminderAction
import com.taskplanner.data.habit.HabitReminderIdentity
import com.taskplanner.data.model.Category
import com.taskplanner.data.model.Habit
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

    /**
     * Creates the channel habit reminders post to, once, the same way [ensureChannel] does the task one.
     *
     * A second channel rather than a second group, because the two asks are silenced differently: a
     * deadline is a promise about a moment and the habit is a nudge about a day, and the user has to be
     * able to switch one of them off in the system settings without losing the other.
     */
    fun ensureHabitChannel() {
        val notificationManager = manager ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (notificationManager.getNotificationChannel(HABIT_CHANNEL_ID) != null) return
        notificationManager.createNotificationChannel(
            NotificationChannel(
                HABIT_CHANNEL_ID,
                context.getString(R.string.habit_reminder_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getString(R.string.habit_reminder_channel_description)
            }
        )
    }

    /**
     * Posts the ask to keep [habit] on [day]. Returns false when the bar could not show it, and the
     * caller then leaves the day un-announced rather than writing a delivery note the user never saw —
     * the same promise [show] makes for a task reminder.
     *
     * [triggerAt] is the minute the schedule asked for, which is what the notification shows as its own
     * timestamp: a habit is asked for at seven in the morning wherever the user happens to be, so a
     * notice delivered late still reads as the morning it was owed to rather than as the sweep that
     * finally reached it. [week] is this week's "3 of 4" where the schedule has a number to be against,
     * or null when the window asks for nothing — and a habit with nothing asked shows no figure rather
     * than a zero that would read as a failure.
     */
    fun showHabit(habit: Habit, day: Long, triggerAt: Long, week: HabitAdherence?): Boolean {
        ensureHabitChannel()
        if (!canPostNotifications()) return false

        val clock = DateFormat.getTimeFormat(context).format(Date(triggerAt))
        val notification = NotificationCompat.Builder(context, HABIT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_reminder_notification)
            .setContentTitle(habit.title)
            .setContentText(context.getString(R.string.habit_reminder_content))
            .setStyle(NotificationCompat.BigTextStyle().bigText(buildHabitBody(clock, week)))
            .setSubText(context.getString(R.string.habit_reminder_subtitle))
            .setColor(DEFAULT_ACCENT)
            .setWhen(triggerAt)
            .setShowWhen(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setGroup(GROUP_KEY)
            .setContentIntent(habitDeepLinkIntent(habit))
            .setAutoCancel(true)
            // A habit has one slot, so a re-announce of the same day updates the notice on screen instead
            // of sounding again beside it.
            .setOnlyAlertOnce(true)
            .addAction(
                R.drawable.ic_reminder_complete,
                context.getString(R.string.habit_reminder_action_mark),
                habitActionIntent(
                    habit, day, ReminderReceiver.ACTION_HABIT_MARK, HabitReminderAction.MARK
                )
            )
            .addAction(
                R.drawable.ic_reminder_snooze,
                // The same ten minutes a task reminder's button offers, said in the same words.
                context.getString(R.string.reminder_action_snooze),
                habitActionIntent(
                    habit, day, ReminderReceiver.ACTION_HABIT_SNOOZE, HabitReminderAction.SNOOZE
                )
            )
            .build()

        return try {
            NotificationManagerCompat.from(context).notify(
                HabitReminderIdentity.notificationIdOf(habit.uuid), notification
            )
            true
        } catch (e: SecurityException) {
            // The permission went away between the check and the post, so the day stays un-announced and
            // the next sweep can still reach the user once notifications are back on.
            false
        }
    }

    /**
     * Takes a habit's own notice away — its one slot, so the day's ask cannot linger after it is kept.
     *
     * Keyed on the uuid rather than the row, because the caller that needs this most is the one standing
     * over a habit the user has just deleted: the row is gone, and the notification it left behind still
     * has to come down.
     */
    fun cancelHabit(habitUuid: String) {
        NotificationManagerCompat.from(context).cancel(HabitReminderIdentity.notificationIdOf(habitUuid))
    }

    /**
     * [clock] is the minute the schedule asked for, already in the device's own format: the header line
     * and the body have to say the same time, and a body that re-formatted the trigger would be a second
     * place the locale is read.
     */
    private fun buildHabitBody(clock: CharSequence, week: HabitAdherence?): String {
        return listOfNotNull(
            context.getString(R.string.habit_reminder_body_time_format, clock),
            week?.takeIf { it.asked > 0 }
                ?.let { context.getString(R.string.habit_reminder_body_week_format, it.kept, it.asked) }
        ).joinToString("\n")
    }

    /**
     * Tapping a habit's notice opens the habit. The link is the habit's own row, in the same shape the
     * task link is: the screen on this device resolves a local id, and the uuid is what travels.
     */
    private fun habitDeepLinkIntent(habit: Habit): PendingIntent = PendingIntent.getActivity(
        context,
        HabitReminderIdentity.requestCodeOf(habit.uuid, HabitReminderAction.OPEN),
        Intent(Intent.ACTION_VIEW, Uri.parse(HabitReminderIdentity.habitDeepLink(habit.id)))
            .setClass(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    /**
     * A habit button names its habit and the day it was asked for.
     *
     * The day travels because the button answers a question about a day: marking done from a notice that
     * fired at 07:00 for the day it was owed to must mark *that* day, even if a sweep delivered it after
     * midnight, and a snooze has to clear the note from the same day rather than from the one the tap
     * happens to fall on.
     */
    private fun habitActionIntent(
        habit: Habit,
        day: Long,
        action: String,
        slot: HabitReminderAction
    ): PendingIntent = PendingIntent.getBroadcast(
        context,
        HabitReminderIdentity.requestCodeOf(habit.uuid, slot),
        Intent(context, ReminderReceiver::class.java)
            .setAction(action)
            .putExtra(ReminderReceiver.EXTRA_HABIT_UUID, habit.uuid)
            .putExtra(ReminderReceiver.EXTRA_HABIT_DAY, day),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

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

        /**
         * Habits get their own channel rather than sharing [CHANNEL_ID] with task reminders, because the
         * two ask for different loudness: a deadline must interrupt (HIGH), while a daily nudge that
         * arrives at the same minute forever should be quietly visible (DEFAULT) — and the user has to be
         * able to turn one down without silencing the other.
         */
        const val HABIT_CHANNEL_ID = "habit_reminders"

        const val GROUP_KEY = "com.taskplanner.reminders"

        /** Fallback accent for a task with no category to borrow a colour from. */
        private val DEFAULT_ACCENT = 0xFF6750A4.toInt()

        fun taskDeepLink(taskId: Long): String = "taskplanner://task/$taskId"
    }
}
