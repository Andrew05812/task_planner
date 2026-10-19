package com.taskplanner.data.repository

import com.taskplanner.data.calendar.CalendarMath
import com.taskplanner.data.local.TrackingSessionDao
import com.taskplanner.data.local.WorkSessionDao
import com.taskplanner.data.local.WorkStatisticsDao
import com.taskplanner.data.model.CategoryWorkSummary
import com.taskplanner.data.model.DailyWorkSummary
import com.taskplanner.data.model.Task
import com.taskplanner.data.model.TaskTotalSeconds
import com.taskplanner.data.model.TaskWorkSummary
import com.taskplanner.data.model.TrackingSession
import com.taskplanner.data.model.WorkIntervalRow
import com.taskplanner.data.model.WorkPeriodSummary
import com.taskplanner.data.model.WorkSession
import com.taskplanner.data.tracking.MonotonicReference
import com.taskplanner.data.tracking.TimeBase
import com.taskplanner.data.tracking.TrackingPhase
import com.taskplanner.data.tracking.WorkTicker
import com.taskplanner.data.tracking.WorkTimeMath
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

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
    private val trackingSessionDao: TrackingSessionDao,
    private val workSessionDao: WorkSessionDao,
    private val sessionRepository: SessionRepository,
    private val timeBase: TimeBase,
    private val monotonic: MonotonicReference,
    private val ticker: WorkTicker
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

    /**
     * What the profile is measuring now, next to what each task has already cost.
     *
     * One snapshot for the whole list, so a screen of fifty cards runs three queries — the per-task
     * totals, the profile's live runs, the one open stretch — rather than three queries per card. The
     * live figure is the only field that moves without a write, and it moves because [WorkTicker]
     * re-measures the stored start, never because anything remembered a number.
     */
    data class WorkBoard(
        val closedByTask: Map<Long, Long> = emptyMap(),
        val phaseByTask: Map<Long, TrackingPhase> = emptyMap(),
        val openTaskId: Long? = null,
        val openStartedAt: Long? = null,
        val liveSeconds: Long? = null,
        /** The profile's one timer, whichever task holds it — what the global bar shows. */
        val active: ActiveWork? = null
    ) {
        fun closedSecondsOf(taskId: Long): Long = closedByTask[taskId] ?: 0L

        fun phaseOf(taskId: Long): TrackingPhase = phaseByTask[taskId] ?: TrackingPhase.IDLE

        /** Seconds for the card that is being measured now, null for every other card. */
        fun liveSecondsOf(taskId: Long): Long? = if (taskId == openTaskId) liveSeconds else null
    }

    /**
     * The task holding the profile's single timer, named by the title recorded when it started.
     *
     * The title comes from the stored row rather than from a lookup: a task archived or deleted while
     * its measurement is still open has to remain readable, which is what §9 asks history for.
     */
    data class ActiveWork(
        val taskId: Long,
        val taskTitle: String,
        val phase: TrackingPhase
    )

    fun observeWorkBoard(): Flow<WorkBoard> =
        sessionRepository.currentUser.flatMapLatest { user ->
            if (user == null) flowOf(WorkBoard()) else combine(
                statisticsDao.observeTotalsByTask(user.id),
                trackingSessionDao.observeLiveForUser(user.id),
                workSessionDao.observeOpen(user.id)
            ) { totals, live, open -> boardOf(totals, live, open) }
                .flatMapLatest { board ->
                    ticker.secondsSince(board.openStartedAt).map { board.copy(liveSeconds = it) }
                }
        }

    private fun boardOf(
        totals: List<TaskTotalSeconds>,
        live: List<TrackingSession>,
        open: WorkSession?
    ): WorkBoard {
        val phases = live.associate { it.taskId to TrackingPhase.PAUSED }.toMutableMap()
        // The open stretch is the evidence, not the state column: a row with no end is being measured
        // whatever its session claims, and the ticker is already counting it.
        open?.taskId?.let { phases[it] = TrackingPhase.RUNNING }
        val active = live.firstOrNull { open != null && it.taskId == open.taskId }
            ?.let { ActiveWork(it.taskId, it.taskTitle, TrackingPhase.RUNNING) }
            ?: live.firstOrNull()?.let { ActiveWork(it.taskId, it.taskTitle, TrackingPhase.PAUSED) }
        return WorkBoard(
            closedByTask = totals.associate { it.taskId to it.seconds },
            phaseByTask = phases,
            openTaskId = open?.taskId,
            openStartedAt = open?.startedAt,
            active = active
        )
    }

    /**
     * The task screen's whole tracking block: what the task has cost, what it has cost today, whether a
     * stretch is being measured on it now, and the stretches themselves.
     *
     * The stored figures come from Room's invalidation tracker, so a screen open while work is being
     * written sees the total move when the interval closes rather than on a timer; the live figure and
     * the day the "today" label belongs to come from [WorkTicker]. A task with no tracking data is a
     * zeroed view rather than a null, because the card still has to offer Start.
     */
    data class TaskWorkView(
        val closedSeconds: Long = 0L,
        val todaySeconds: Long = 0L,
        val liveSeconds: Long? = null,
        val openStartedAt: Long? = null,
        val phase: TrackingPhase = TrackingPhase.IDLE,
        val intervals: List<WorkIntervalRow> = emptyList()
    ) {
        /** Everything measured on the task, finished or not — the figure the ticker prints. */
        val totalSeconds: Long get() = closedSeconds + (liveSeconds ?: 0L)

        /** Estimate against measured work: the screen's remaining or overrun line. */
        fun balanceAgainst(estimatedMinutes: Int?): WorkTimeMath.WorkBalance =
            WorkTimeMath.balance(estimatedMinutes, totalSeconds)
    }

    fun observeTaskWork(
        task: Task,
        recentIntervalLimit: Int = RECENT_INTERVAL_LIMIT,
        zone: ZoneId = CalendarMath.systemZone()
    ): Flow<TaskWorkView> = sessionRepository.currentUser.flatMapLatest { user ->
        if (user == null) flowOf(TaskWorkView()) else {
            val stored = combine(
                statisticsDao.observeSecondsForTask(user.id, task.id),
                // Re-anchored on every day change, so a screen held open across midnight moves its
                // "today" instead of holding yesterday's total under today's label.
                ticker.localDays(zone).flatMapLatest { day ->
                    statisticsDao.observeDailySeriesForTask(
                        user.id,
                        task.id,
                        day.minusDays(TREND_BACKFILL_DAYS).toEpochDay(),
                        day.plusDays(1).toEpochDay()
                    ).map { day to it }
                },
                workSessionDao.observeRecentIntervals(user.id, task.id, recentIntervalLimit)
            ) { total, series, intervals -> Triple(total, series, intervals) }

            val live = combine(
                trackingSessionDao.observeLiveForUser(user.id),
                workSessionDao.observeOpen(user.id)
            ) { sessions, open -> sessions to open }

            combine(stored, live) { (total, dayAndSeries, intervals), (sessions, open) ->
                val (day, series) = dayAndSeries
                TaskWorkView(
                    closedSeconds = total,
                    todaySeconds = series.firstOrNull { it.localDay == day.toEpochDay() }?.seconds ?: 0L,
                    openStartedAt = open?.startedAt?.takeIf { open.taskId == task.id },
                    phase = when {
                        open != null && open.taskId == task.id -> TrackingPhase.RUNNING
                        sessions.any { it.taskId == task.id } -> TrackingPhase.PAUSED
                        else -> TrackingPhase.IDLE
                    },
                    intervals = intervals
                )
            }.flatMapLatest { view ->
                ticker.secondsSince(view.openStartedAt).map { view.copy(liveSeconds = it) }
            }
        }
    }

    private suspend fun userIdOrNull(): Long? = sessionRepository.currentUserId()

    companion object {
        /** Stretches the task screen lists before sending the user to the history. */
        const val RECENT_INTERVAL_LIMIT = 12

        /** Days of per-task trend the task screen keeps in view, enough to cover a weekend's work. */
        private const val TREND_BACKFILL_DAYS = 30L
    }
}
