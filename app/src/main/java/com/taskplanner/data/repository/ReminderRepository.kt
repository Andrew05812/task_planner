package com.taskplanner.data.repository

import com.taskplanner.data.local.CategoryDao
import com.taskplanner.data.local.ReminderDao
import com.taskplanner.data.local.TaskDao
import com.taskplanner.data.model.Reminder
import com.taskplanner.data.model.SyncState
import com.taskplanner.data.model.Task
import com.taskplanner.data.reminder.ReminderNotifier
import com.taskplanner.data.reminder.ReminderScheduler
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

/**
 * Reminders: the lead times a user asks for, the alarms that carry them, and the notifications they
 * arrive as.
 *
 * Two rules shape everything below.
 *
 * **The database is the truth, the alarm is only a hint.** A reminder is a row; AlarmManager says
 * "come and look now". So a lost alarm, a reboot, a force-stop or a device that was switched off
 * costs a delay, never a reminder: the rows are re-armed on start-up, on a profile switch, after a
 * sync pull and from a periodic sweep, and each of those delivers whatever minute has come.
 *
 * **A reminder is (task, lead time).** Both its uuid and its unique index say so, which is what makes
 * a chip pressed twice, an import replayed over the same file, and a pull that already holds the row
 * all land on one row instead of producing a second notification for the same minute.
 *
 * Nothing here invents a deadline: a task with no due instant has nothing to count back from, so it
 * cannot have reminders.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class ReminderRepository @Inject constructor(
    private val reminderDao: ReminderDao,
    private val taskDao: TaskDao,
    private val categoryDao: CategoryDao,
    private val sessionRepository: SessionRepository,
    private val scheduler: ReminderScheduler,
    private val notifier: ReminderNotifier
) {

    /** The lead times stored on one task, soonest fire first — what the editor ticks its chips from. */
    suspend fun offsetsFor(taskUuid: String): List<Int> =
        if (taskUuid.isBlank()) emptyList()
        else reminderDao.getLiveForTask(taskUuid).map { it.offsetMinutes }.sorted()

    /** Every live reminder of the current profile — what the list screen badges itself from. */
    fun observeLiveForCurrentUser(): Flow<List<Reminder>> =
        sessionRepository.currentUser.flatMapLatest { user ->
            if (user == null) flowOf(emptyList()) else reminderDao.observeLiveForUser(user.id)
        }

    /** Whether notifications can reach the user at all; the editor asks for the permission when not. */
    fun canPostNotifications(): Boolean = notifier.canPostNotifications()

    /** Whether the platform lets this app fire at the exact minute, or only inside a window. */
    fun canScheduleExactAlarms(): Boolean = scheduler.canUseExactAlarms()

    /**
     * Makes the task's reminders match the lead times the user chose and points every row that stayed
     * at the deadline the task has *now*.
     *
     * [offsets] is the editor's selection, or null for a caller that is only moving the deadline and
     * has no opinion about lead times. Returns false when the task has no deadline: there is nothing to
     * count back from, and the editor has to say that instead of accepting chips that would never ring.
     */
    suspend fun applyReminders(task: Task, offsets: List<Int>?): Boolean {
        val subject = task.withIdentity()
        val dueAt = subject.dueDate ?: run {
            // The deadline went away, so the lead times measured from it have no meaning left.
            retireTasks(listOf(subject.uuid))
            return false
        }
        val now = System.currentTimeMillis()
        val live = reminderDao.getLiveForTask(subject.uuid)

        val kept = if (offsets == null) {
            live.map { it.offsetMinutes }
        } else {
            val desired = offsets.map { it.coerceAtLeast(0) }.distinct()
            live.map { it.offsetMinutes }.filter { it !in desired }.forEach { retireOffset(subject.uuid, it) }
            desired
        }
        // Only the lead times the user kept are written, so a row retired a moment ago can never be
        // put back on the clock by the same save.
        kept.forEach { upsert(subject, dueAt, it, now) }
        return true
    }

    /**
     * Gives a task the stable identity its lead times are keyed on.
     *
     * A reminder names its task by uuid, and a row that predates uuids has none. Writing the lead time
     * against a blank key would leave it firing on this device only — an export has to drop it, because
     * the file's task carries a uuid the reminder does not name. So the row is given its identity at
     * the first reminder, the same way an import or an account handover gives it.
     */
    private suspend fun Task.withIdentity(): Task {
        if (uuid.isNotBlank()) return this
        val identity = UUID.randomUUID().toString()
        taskDao.setUuid(id, identity)
        return copy(uuid = identity)
    }

    /**
     * Cancels the reminders of a task the user finished or filed away, without throwing them out.
     *
     * Restoring from the archive has to bring the same lead times back, so an archived task keeps its
     * rows and loses only the alarms. The delivery query filters on the task being active as well,
     * which keeps a completed task silent even when an alarm for it was already in flight.
     */
    suspend fun onTaskArchived(task: Task) {
        if (task.uuid.isBlank()) return
        reminderDao.getLiveForTask(task.uuid).forEach { scheduler.cancel(it) }
    }

    /** Undoing the above: the lead times come back on the deadline the task has now. */
    suspend fun onTaskRestored(task: Task) {
        val dueAt = task.dueDate ?: return
        val now = System.currentTimeMillis()
        // The same rule as everywhere else: a minute the user has already been shown for this date
        // stays shown, so restoring a task does not repeat a notice they acted on.
        reminderDao.getLiveForTask(task.uuid).forEach { upsert(task, dueAt, it.offsetMinutes, now) }
    }

    /**
     * Deleting tasks.
     *
     * A plain task loses its reminders completely — the rows go, with a tombstone for sync. An instance
     * of a series loses only its alarms: the series is still running, and the next instance is built
     * from this row's content, lead times included.
     */
    suspend fun onTasksDeleted(tasks: List<Task>) {
        val plain = tasks.filter { it.uuid.isNotBlank() && it.seriesUuid == null }
        val instances = tasks.filter { it.uuid.isNotBlank() && it.seriesUuid != null }
        instances.forEach { task -> reminderDao.getLiveForTask(task.uuid).forEach { scheduler.cancel(it) } }
        val plainUuids = plain.map { it.uuid }
        if (plainUuids.isEmpty()) return
        plainUuids.forEach { uuid -> reminderDao.getLiveForTask(uuid).forEach { scheduler.cancel(it) } }
        reminderDao.softDeleteForTasks(plainUuids, System.currentTimeMillis())
    }

    /** Retires the reminders of the listed tasks whatever their state — used when a series is deleted. */
    suspend fun retireTasks(taskUuids: List<String>) {
        val uuids = taskUuids.filter { it.isNotBlank() }
        if (uuids.isEmpty()) return
        uuids.forEach { uuid -> reminderDao.getLiveForTask(uuid).forEach { scheduler.cancel(it) } }
        reminderDao.softDeleteForTasks(uuids, System.currentTimeMillis())
    }

    /**
     * Gives a freshly materialised series instance the lead times of the instance it was built from.
     *
     * Repeating a task means repeating its reminders: "every day at nine, warn me a quarter before"
     * must not go quiet after the first day. The copies are pending again — a new occurrence is a new
     * minute — and they carry the new task's uuid, so finishing one instance never marks the next
     * instance's reminder as already shown.
     */
    suspend fun inheritForInstance(templateTaskUuid: String, instance: Task) {
        if (templateTaskUuid.isBlank() || instance.uuid.isBlank()) return
        val dueAt = instance.dueDate ?: return
        val now = System.currentTimeMillis()
        reminderDao.getLiveForTask(templateTaskUuid).forEach { source ->
            val created = Reminder(
                uuid = Reminder.identity(instance.uuid, source.offsetMinutes),
                taskUuid = instance.uuid,
                userId = instance.userId,
                offsetMinutes = source.offsetMinutes,
                triggerAt = Reminder.triggerAt(dueAt, source.offsetMinutes),
                anchorAt = dueAt,
                createdAt = now,
                updatedAt = now
            )
            if (reminderDao.insertOrIgnore(created) >= 0) scheduler.arm(created, now)
        }
    }

    /**
     * Delivers what is due. Called by the alarm, by the sweep worker, and by anything else that just
     * woke the process.
     *
     * Reminders whose minute passed more than [ReminderScheduler.LATE_GRACE_MILLIS] ago are retired
     * rather than posted: a phone that was off since Tuesday does not owe the user Wednesday's
     * notifications on Thursday, and leaving them pending would put them back in front of every later
     * sweep.
     */
    suspend fun deliverDue(now: Long = System.currentTimeMillis()): Int {
        val userId = sessionRepository.currentUserId() ?: return 0
        val earliest = now - ReminderScheduler.LATE_GRACE_MILLIS
        val stale = reminderDao.getLongOverdue(userId, earliest)
        if (stale.isNotEmpty()) reminderDao.markNotified(stale.map { it.uuid }, now)

        var delivered = 0
        reminderDao.getDue(userId, now, earliest).forEach { reminder ->
            val task = taskDao.getTaskByUuid(reminder.taskUuid) ?: return@forEach
            val category = task.categoryId?.let { categoryDao.getCategoryById(it) }
            if (notifier.show(task, category, reminder)) {
                reminderDao.markNotified(listOf(reminder.uuid), now)
                delivered += 1
            }
        }
        return delivered
    }

    /**
     * Puts the alarms back the way the database says they should be: after a reboot, a clock change, a
     * timezone change, or a profile switch.
     *
     * Anything whose minute came while the alarms were gone is delivered first, so waking the device
     * catches the user up instead of silently skipping the reminder that was due at three in the
     * morning.
     */
    suspend fun rescheduleAll(now: Long = System.currentTimeMillis()): Int {
        val delivered = deliverDue(now)
        val userId = sessionRepository.currentUserId() ?: return delivered
        reminderDao.getLiveForUser(userId).forEach { reminder ->
            if (reminder.isPending) scheduler.arm(reminder, now)
        }
        return delivered
    }

    /**
     * Snoozes a reminder: the same row, moved to a new minute, quietly pending again.
     *
     * The notification id comes from the reminder's uuid, which does not change, so the snoozed
     * reminder replaces the one on screen instead of leaving a second copy of it behind.
     */
    suspend fun snooze(reminderUuid: String, now: Long = System.currentTimeMillis()): Boolean {
        val reminder = reminderDao.findByUuid(reminderUuid) ?: return false
        taskDao.getTaskByUuid(reminder.taskUuid) ?: return false
        val trigger = now + ReminderScheduler.SNOOZE_MILLIS
        reminderDao.snooze(reminderUuid, trigger, now)
        notifier.cancel(reminder)
        scheduler.arm(reminder.copy(triggerAt = trigger, notifiedAt = null, updatedAt = now), now)
        return true
    }

    /** The task row a reminder belongs to, for the notification's own buttons. */
    suspend fun taskIdOf(reminderUuid: String): Long? =
        reminderDao.findByUuid(reminderUuid)
            ?.taskUuid
            ?.let { taskDao.getTaskByUuid(it) }
            ?.id

    /**
     * Takes a notification away after its button was used.
     *
     * Marking the row handled is what stops a later sweep — or the alarm that was already in flight
     * when the user tapped Complete — from putting the same reminder back on screen.
     */
    suspend fun markHandledFromNotification(reminderUuid: String) {
        if (reminderUuid.isBlank()) return
        val reminder = reminderDao.findByUuid(reminderUuid) ?: return
        reminderDao.markNotified(listOf(reminderUuid), System.currentTimeMillis())
        scheduler.cancel(reminder)
        notifier.cancel(reminder)
    }

    /**
     * Writes one lead time of one task and its alarm together.
     *
     * A notification belongs to a (deadline, lead time) pair, and [upsert] is the only place that
     * rule lives:
     *  - a row the user threw away comes back announced-again, because asking for it twice is a new
     *    intention;
     *  - a row that follows a moved deadline is announced again only when that deadline has not come
     *    yet — moving a meeting to Thursday is a Thursday the user has not been warned about;
     *  - a row that already announced *this* deadline stays announced, so saving a task for the second
     *    time cannot put the same notification on the screen twice.
     */
    private suspend fun upsert(task: Task, dueAt: Long, offsetMinutes: Int, now: Long) {
        val trigger = Reminder.triggerAt(dueAt, offsetMinutes)
        val existing = reminderDao.findByTaskAndOffset(task.uuid, offsetMinutes)
        if (existing == null) {
            val created = Reminder(
                uuid = Reminder.identity(task.uuid, offsetMinutes),
                taskUuid = task.uuid,
                userId = task.userId,
                offsetMinutes = offsetMinutes,
                triggerAt = trigger,
                anchorAt = dueAt,
                createdAt = now,
                updatedAt = now
            )
            // Another device may already own this identity; the unique index then keeps the row that
            // arrived first and arming it is enough.
            if (reminderDao.insertOrIgnore(created) >= 0 && trigger > now) {
                scheduler.arm(created, now)
            }
            return
        }
        val notifiedAt = when {
            existing.isDeleted -> null
            existing.anchorAt != dueAt -> if (trigger > now) null else existing.notifiedAt
            else -> existing.notifiedAt
        }
        val stored = existing.copy(
            userId = task.userId,
            triggerAt = trigger,
            anchorAt = dueAt,
            notifiedAt = notifiedAt,
            isDeleted = false,
            deletedAt = null,
            syncState = SyncState.PENDING,
            updatedAt = now
        )
        reminderDao.update(stored)
        if (stored.isPending) {
            scheduler.arm(stored, now)
        } else {
            // Nothing to ring for a minute the user has already been shown, and its alarm has to be
            // taken away rather than left for a later sweep to deliver again.
            scheduler.cancel(stored)
        }
    }

    private suspend fun retireOffset(taskUuid: String, offsetMinutes: Int) {
        val row = reminderDao.findByTaskAndOffset(taskUuid, offsetMinutes) ?: return
        reminderDao.softDelete(row.uuid, System.currentTimeMillis())
        scheduler.cancel(row)
    }
}
