package com.taskplanner.data.repository

import com.taskplanner.data.calendar.CalendarMath
import com.taskplanner.data.local.WorkSessionDao
import com.taskplanner.data.local.WorkStatisticsDao
import com.taskplanner.data.model.CategoryWorkSummary
import com.taskplanner.data.model.DailyWorkSummary
import com.taskplanner.data.model.Task
import com.taskplanner.data.model.TaskWorkSummary
import com.taskplanner.data.model.WorkPeriodSummary
import com.taskplanner.data.tracking.MonotonicReference
import com.taskplanner.data.tracking.TimeBase
import com.taskplanner.data.tracking.WorkTimeMath
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

/** The periods the history screen offers, named the way the user reads them. */
enum class WorkPeriod {
    TODAY,
    YESTERDAY,
    THIS_WEEK,
    LAST_WEEK,
    THIS_MONTH,
    LAST_MONTH;

    /** The half-open epoch-day range this period covers, anchored on [onDay]. */
    fun dayRange(onDay: LocalDate): LongRange = when (this) {
        TODAY -> WorkTimeMath.dayRange(onDay)
        YESTERDAY -> WorkTimeMath.dayRange(onDay.minusDays(1))
        THIS_WEEK -> WorkTimeMath.weekDayRange(onDay)
        LAST_WEEK -> WorkTimeMath.weekDayRange(onDay.minusDays(7))
        THIS_MONTH -> WorkTimeMath.monthDayRange(onDay)
        LAST_MONTH -> WorkTimeMath.monthDayRange(onDay.withDayOfMonth(1).minusMonths(1))
    }
}

/**
 * A period expressed both ways: the epoch-day range the interval totals group on, and the instant
 * range the session and completion counts need.
 *
 * The two are not interchangeable. `local_day` is where an interval was measured, which is what makes
 * a day's total stable after the user travels; `started_at` and `completed_at` are instants, and a
 * session that started at 23:50 belongs to the evening it began. Keeping both bounds in one object is
 * what stops a screen from comparing a day count against an instant filter.
 */
data class WorkWindow(val fromDay: Long, val toDay: Long, val fromMillis: Long, val toMillis: Long) {
    val days: LongRange = fromDay until toDay
}

/**
 * Every number the tracking screens show, read from SQLite rather than from a list.
 *
 * The repository's job is deliberately small: name the periods, hold the profile, and add the one
 * figure SQL cannot know — the interval that is still running, which is measured against the clock at
 * the moment of reading and is never mixed into a stored total. The sums themselves are the DAO's, so
 * "how much did I work this month" costs one query instead of loading a month of rows.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class WorkStatisticsRepository @Inject constructor(
    private val statisticsDao: WorkStatisticsDao,
    private val workSessionDao: WorkSessionDao,
    private val sessionRepository: SessionRepository,
    private val timeBase: TimeBase,
    private val monotonic: MonotonicReference
) {

    /** Time that is already a fact, plus the stretch still being measured. */
    data class WorkTotals(val closedSeconds: Long, val liveSeconds: Long?) {
        /** What the ticker shows; equals [closedSeconds] while nothing is running. */
        val totalSeconds: Long get() = closedSeconds + (liveSeconds ?: 0L)

        val isLive: Boolean get() = liveSeconds != null
    }

    /** The window for [period] in [zone], anchored on the profile's today. */
    fun window(
        period: WorkPeriod,
        onDay: LocalDate = LocalDate.now(),
        zone: ZoneId = CalendarMath.systemZone()
    ): WorkWindow = window(period.dayRange(onDay), zone)

    /** The window for an arbitrary epoch-day range — the custom filter. */
    fun window(days: LongRange, zone: ZoneId = CalendarMath.systemZone()): WorkWindow = WorkWindow(
        fromDay = days.first,
        toDay = days.last + 1L,
        fromMillis = CalendarMath.startOfDay(LocalDate.ofEpochDay(days.first), zone),
        toMillis = CalendarMath.startOfDay(LocalDate.ofEpochDay(days.last + 1L), zone)
    )

    /** Closed work, interval and session counts for one window. */
    suspend fun summary(window: WorkWindow): WorkPeriodSummary? =
        userIdOrNull()?.let { statisticsDao.periodSummary(it, window.fromDay, window.toDay) }

    /** The same summary as a Flow, so a screen watching today updates when work is closed. */
    fun observeSummary(window: WorkWindow): Flow<WorkPeriodSummary?> =
        sessionRepository.currentUser.flatMapLatest { user ->
            if (user == null) flowOf(null)
            else statisticsDao.observePeriodSummary(user.id, window.fromDay, window.toDay)
        }

    /** The most-worked tasks in a window, titles falling back to what history recorded. */
    suspend fun byTask(window: WorkWindow, limit: Int): List<TaskWorkSummary> =
        userIdOrNull()?.let { statisticsDao.workByTask(it, window.fromDay, window.toDay, limit) }
            ?: emptyList()

    /** Time per category, including the bucket for work on unclassified and deleted categories. */
    suspend fun byCategory(window: WorkWindow): List<CategoryWorkSummary> =
        userIdOrNull()?.let { statisticsDao.workByCategory(it, window.fromDay, window.toDay) }
            ?: emptyList()

    /** One point per day actually worked — the trend, with no gaps invented between them. */
    suspend fun dailySeries(window: WorkWindow): List<DailyWorkSummary> =
        userIdOrNull()?.let { statisticsDao.dailySeries(it, window.fromDay, window.toDay) }
            ?: emptyList()

    suspend fun averageIntervalSeconds(window: WorkWindow): Double? =
        userIdOrNull()?.let { statisticsDao.averageIntervalSeconds(it, window.fromDay, window.toDay) }

    /** Sessions started inside the window — how often work was begun, as distinct from how long it ran. */
    suspend fun sessionCount(window: WorkWindow): Int =
        userIdOrNull()?.let { statisticsDao.sessionCount(it, window.fromMillis, window.toMillis) } ?: 0

    /** Tasks completed inside the window, counted from `tasks.completed_at` rather than from status. */
    suspend fun completedTaskCount(window: WorkWindow): Int =
        userIdOrNull()?.let { statisticsDao.completedTasksInPeriod(it, window.fromMillis, window.toMillis) }
            ?: 0

    /** Total measured work on [task] across the whole account, closed intervals only. */
    suspend fun totalOnTask(task: Task): Long =
        userIdOrNull()?.let { statisticsDao.secondsForTask(it, task.id) } ?: 0L

    /** Work measured on [task] on one local day — the "today" figure beside the total. */
    suspend fun onTaskToday(task: Task, onDay: LocalDate = LocalDate.now()): Long =
        userIdOrNull()?.let { statisticsDao.secondsForTaskOnDay(it, task.id, onDay.toEpochDay()) } ?: 0L

    /**
     * What has been spent on [task], and what is still being spent on it right now.
     *
     * The live part is the only figure not already stored: it is the open interval measured against the
     * clock at the moment of reading, from the start the database holds. It is kept separate from the
     * closed total so a screen can show a running number without ever writing an unfinished stretch into
     * history, and so no aggregate above is contaminated by it.
     *
     * There is at most one open interval per profile — the unique marker index guarantees it — so the
     * live figure is one row read by that marker rather than the task's interval history.
     */
    suspend fun totalsOnTask(task: Task): WorkTotals {
        val userId = sessionRepository.currentUserId() ?: return WorkTotals(0L, null)
        val closed = statisticsDao.secondsForTask(userId, task.id)
        val open = workSessionDao.getOpenOnce(userId)?.takeIf { it.taskId == task.id }
            ?: return WorkTotals(closed, null)
        val live = WorkTimeMath.openSeconds(
            openSince = open.startedAt,
            nowMillis = timeBase.wallMillis(),
            monotonicElapsedMillis = monotonic.elapsedSince(open.startedAt)
        )
        return WorkTotals(closed, live)
    }

    /** Estimate against measured work — the detail screen's "remaining" or "overrun". */
    suspend fun balanceOf(task: Task): WorkTimeMath.WorkBalance =
        WorkTimeMath.balance(task.estimatedMinutes, totalsOnTask(task).totalSeconds)

    private suspend fun userIdOrNull(): Long? = sessionRepository.currentUserId()
}
