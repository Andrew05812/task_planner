package com.taskplanner.data.habit

import com.taskplanner.data.calendar.CalendarMath
import com.taskplanner.data.local.HabitDao
import com.taskplanner.data.model.Habit
import com.taskplanner.data.model.HabitFrequency
import com.taskplanner.data.reminder.ReminderNotifier
import com.taskplanner.data.reminder.ReminderReceiver
import com.taskplanner.data.reminder.ReminderScheduler
import com.taskplanner.data.repository.SessionRepository
import com.taskplanner.data.tracking.TimeBase
import com.taskplanner.data.tracking.WorkTimeMath
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The reminder a habit asks for: the same AlarmManager, the same notification bar and the same sweep that
 * carry a task's lead times, driven by a schedule instead of by a row.
 *
 * A task reminder is data — one row per lead time, with its own instant and its own delivered flag. A habit
 * reminder is a rule: "at seven, on the days this habit is asked for". The rule is [HabitMath] and the habit
 * row, so nothing here stores a schedule that could disagree with the grid the habit card draws. What it
 * does store is one delivery note per day ([Habit.remindedOnDay]), because the note is the one fact that
 * cannot be recomputed: an alarm, a reboot, a sync pull and the six-hourly sweep all reach the same rule,
 * and only the first of them may speak.
 *
 * Three rules hold the whole thing together.
 *
 *  * **The slot always holds the next minute the habit is owed.** Every path that wakes this class ends by
 *    putting [HabitMath.nextReminderInstant] back in the habit's alarm slot, whatever it just did with
 *    today. One habit, one pending wakeup, so a repeating schedule can neither stack a week of alarms nor
 *    go silent because one was dropped: the next wake of the process re-derives the same instant from the
 *    row.
 *  * **A day is asked for, not guessed.** Before a habit is announced the pass checks the four things that
 *    can make the ask wrong: that the day is one the schedule names ([HabitMath.isReminderDay]), that its
 *    minute has come, that it has not gone stale beyond
 *    [ReminderScheduler.LATE_GRACE_MILLIS], and that the day is not already kept. A weekly habit goes quiet
 *    for the rest of its week once the marks reach the count.
 *  * **Nothing is announced that the user cannot see.** When the notification bar refuses the post, no note
 *    is written, so the next wake of the app reaches the user once notifications are back on — the same
 *    promise [ReminderNotifier.showHabit] makes and the task reminders keep.
 *
 * Everything is read through the signed-in profile, so an alarm left behind by another account wakes this
 * profile's own schedule and no other's rows: the wakeup broadcast carries nothing, exactly as a task
 * alarm's does.
 */
@Singleton
class HabitReminderCoordinator @Inject constructor(
    private val habitDao: HabitDao,
    private val sessionRepository: SessionRepository,
    private val scheduler: ReminderScheduler,
    private val notifier: ReminderNotifier,
    private val timeBase: TimeBase
) {

    /**
     * Puts one habit's alarm back the way its stored schedule says it should be.
     *
     * The one entry point the habit writes go through — created, edited, archived, restored, deleted — and
     * the reason they can all use the same call: the answer is a function of the row as it now stands, and
     * a row that no longer asks for anything (put away, ended, emptied of a minute, or deleted outright) is
     * answered by taking its wakeup and its notice away instead of arming one. A habit the user has deleted
     * has no row left to read, which is why the identity is keyed on the uuid and this method can still
     * silence it.
     */
    suspend fun onScheduleChanged(habitUuid: String, now: Long = timeBase.wallMillis()) {
        if (habitUuid.isBlank()) return
        val habit = ownedHabit(habitUuid)
        if (habit == null || !habit.isOpen || habit.remindMinuteOfDay == null) {
            silence(habitUuid)
            return
        }
        armNext(habit, now)
    }

    /**
     * Delivers today's asks. Called by the habit's own alarm, by the sweep worker, and by anything else that
     * just woke the process.
     *
     * [HabitDao.reminderCandidates] is the cheap half of the question — a minute set, a live habit, and
     * today's note not yet written — and the schedule rules are decided here rather than in SQL, because
     * they are the same rules the habit card prints its figures from. Each habit leaves this pass with its
     * next owed minute armed, so a day that was skipped (not asked for, already kept, a week already full)
     * cannot leave the habit quiet.
     */
    suspend fun deliverDue(now: Long = timeBase.wallMillis()): Int {
        val userId = sessionRepository.currentUserId() ?: return 0
        val zone = CalendarMath.systemZone()
        val day = WorkTimeMath.epochDayOf(now, zone)
        var delivered = 0
        habitDao.reminderCandidates(userId, day).forEach { habit ->
            if (deliver(habit, userId, day, now, zone)) delivered += 1
            armNext(habit, now)
        }
        return delivered
    }

    /**
     * Puts the habit alarms back the way their schedules say: after a reboot, a clock change, a timezone
     * change, a profile switch or a sync pull.
     *
     * Today's asks come first, so a device that woke up late tells the user what it is still worth telling
     * them before it arms tomorrow.
     */
    suspend fun rescheduleAll(now: Long = timeBase.wallMillis()): Int {
        val delivered = deliverDue(now)
        val userId = sessionRepository.currentUserId() ?: return delivered
        habitDao.habitsWithReminder(userId).forEach { armNext(it, now) }
        return delivered
    }

    /**
     * Defers today's ask: the notice comes down, the day's note is unsaid, and the same slot is armed again
     * a [ReminderScheduler.SNOOZE_MILLIS] later.
     *
     * Clearing the note is what makes the promise keepable rather than optimistic. The deferred wakeup is an
     * alarm like any other, and Android may drop it — a reboot in those ten minutes, a force stop, a
     * foreground-stopped process. Because the day carries no note while it is deferred, the next wake of the
     * app (a sweep, a start-up, a sync) finds the ask still owed and delivers it: late, in the worst case by
     * the length of a sweep, but not gone. A reminder that silently disappears is the failure this app does
     * not allow, and being told about your run ten minutes early is not.
     *
     * [day] is the day the notice was for. A notice the user left from an earlier day is not deferred —
     * that day has stopped being asked about, and promising a wakeup for it would announce today's schedule
     * under yesterday's ask. It is taken away, and the habit's next owed minute stays armed as it was.
     */
    suspend fun snooze(habitUuid: String, day: Long, now: Long = timeBase.wallMillis()): Boolean {
        val userId = sessionRepository.currentUserId() ?: return false
        if (habitUuid.isBlank()) return false
        notifier.cancelHabit(habitUuid)
        val habit = habitDao.getHabit(habitUuid, userId)?.takeIf { it.isOpen } ?: return false
        if (habit.remindMinuteOfDay == null) return false
        if (day != WorkTimeMath.epochDayOf(now, CalendarMath.systemZone())) return false
        habitDao.clearRemindedOn(habitUuid, userId, now)
        return scheduler.armAt(
            HabitReminderIdentity.alarmRequestCodeOf(habitUuid),
            now + ReminderScheduler.SNOOZE_MILLIS,
            ReminderReceiver.ACTION_HABIT_DUE,
            now
        )
    }

    /**
     * The notice a button answered comes down; the schedule is left alone.
     *
     * Only the notification is taken here, because the alarm in this habit's slot is no longer today's — a
     * day that has been announced leaves the pass holding the *next* owed minute, and cancelling that would
     * quiet a habit the user has every intention of keeping. Neither half of the ask is retired: if the day
     * is kept again from the grid, the mark stands; if the user takes it back while the day's minute is
     * still inside its window, the next pass is entitled to ask again, because being shown the notice and
     * answering it is exactly what this call recorded.
     */
    suspend fun onAskAnswered(habitUuid: String) {
        if (habitUuid.isBlank()) return
        notifier.cancelHabit(habitUuid)
    }

    /** Takes everything a habit could still make: its wakeup and its notice. */
    private fun silence(habitUuid: String) {
        scheduler.cancelAt(HabitReminderIdentity.alarmRequestCodeOf(habitUuid), ReminderReceiver.ACTION_HABIT_DUE)
        notifier.cancelHabit(habitUuid)
    }

    /**
     * Announces [habit] on [day] when the schedule, the clock and the marks all agree it is owed.
     *
     * Returns whether the user was shown something. A day whose minute has gone stale is retired by writing
     * the note without posting — the same rule that keeps a phone which was off since Tuesday from dumping
     * Wednesday's asks on Thursday — while every other silence leaves the note unwritten, so "not now" never
     * becomes "never".
     */
    private suspend fun deliver(
        habit: Habit,
        userId: Long,
        day: Long,
        now: Long,
        zone: ZoneId
    ): Boolean {
        val triggerAt = HabitMath.reminderInstant(habit, day, zone)
            ?: return false
        if (!HabitMath.isReminderDay(habit, day)) return false
        if (triggerAt > now) return false
        if (triggerAt < now - ReminderScheduler.LATE_GRACE_MILLIS) {
            // Nothing to show, and nothing to be shown again: the day's ask is over.
            habitDao.markReminded(habit.uuid, userId, day, now)
            return false
        }

        val week = HabitMath.weekRange(day, CalendarMath.firstDayOfWeek())
        val marked = habitDao.liveDays(habit.uuid, userId, week.first, week.last).toSet()
        if (day in marked) return false
        val progress = HabitMath.weekProgress(habit, marked, day, CalendarMath.firstDayOfWeek())
        if (habit.frequency == HabitFrequency.TIMES_PER_WEEK && progress.asked > 0 && progress.kept >= progress.asked) {
            return false
        }
        if (!notifier.showHabit(habit, day, triggerAt, progress)) return false
        habitDao.markReminded(habit.uuid, userId, day, now)
        return true
    }

    /** Arms the next minute this habit's schedule is owed, leaving the slot holding it and nothing else. */
    private suspend fun armNext(habit: Habit, now: Long): Boolean {
        val instant = HabitMath.nextReminderInstant(habit, now, CalendarMath.systemZone())
        if (instant == null) {
            silence(habit.uuid)
            return false
        }
        return scheduler.armAt(
            HabitReminderIdentity.alarmRequestCodeOf(habit.uuid),
            instant,
            ReminderReceiver.ACTION_HABIT_DUE,
            now
        )
    }

    /** The habit [habitUuid] names for this profile, or null when the profile does not hold it. */
    private suspend fun ownedHabit(habitUuid: String): Habit? {
        val userId = sessionRepository.currentUserId() ?: return null
        return habitDao.getHabit(habitUuid, userId)
    }
}
