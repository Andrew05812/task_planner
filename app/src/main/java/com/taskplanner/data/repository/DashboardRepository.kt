package com.taskplanner.data.repository

import com.taskplanner.data.calendar.CalendarMath
import com.taskplanner.data.local.TaskStatisticsDao
import com.taskplanner.data.local.TrackingSessionDao
import com.taskplanner.data.local.WorkSessionDao
import com.taskplanner.data.model.CategoryWorkSummary
import com.taskplanner.data.model.TaskHealthCounts
import com.taskplanner.data.model.TaskStatusCount
import com.taskplanner.data.model.TaskWorkSummary
import com.taskplanner.data.model.WorkPeriodSummary
import com.taskplanner.data.model.isCompletedSpelling
import com.taskplanner.data.tracking.TimeBase
import com.taskplanner.data.statistics.DashboardMath
import com.taskplanner.data.statistics.HeatmapGrid
import com.taskplanner.data.statistics.TrendDay
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.WeekFields
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * What the dashboard asks the database for, and the answer it paints.
 *
 * [period] names the window the totals speak about; [heatmapWeeks] is a separate length because the grid
 * is read as a whole habit rather than as a period the user is summing — a month's worth of squares says
 * nothing about a streak, and a year's grid makes a weekly figure unreadable. [onDay] is the day the
 * screen thinks it is showing, which the caller re-reads at midnight rather than caching.
 */
data class DashboardRequest(
    val period: WorkPeriod = WorkPeriod.THIS_WEEK,
    val heatmapWeeks: Int = DEFAULT_HEATMAP_WEEKS,
    val onDay: LocalDate = LocalDate.now(),
    val zone: ZoneId = CalendarMath.systemZone(),
    val firstDayOfWeek: DayOfWeek = WeekFields.of(java.util.Locale.getDefault()).firstDayOfWeek
) {
    companion object {
        /** Roughly a term of work: enough squares to see a habit, few enough to fit the screen. */
        const val DEFAULT_HEATMAP_WEEKS = 18
    }
}

/**
 * Every figure one visit to the dashboard shows, read in one pass.
 *
 * The numbers are the repository's, meaning SQLite produced them: [measured] is a `SUM` over closed
 * intervals, [health] one scan of the profile's open rows, [topTasks] and [categories] grouped reads.
 * The only lists the screen is given are answers, never rows to be folded — the account's tasks are not
 * loaded here to be counted, and its intervals are not loaded here to be summed.
 */
data class DashboardSnapshot(
    val request: DashboardRequest,
    /** Closed work, interval and session counts for [period]. */
    val measured: WorkPeriodSummary,
    /** The days of the period that produced anything, oldest first. */
    val trend: List<TrendDay>,
    /** The squares of the activity grid, as whole weeks ending with the current one. */
    val heatmap: HeatmapGrid,
    val health: TaskHealthCounts,
    /** Tasks finished inside the period — the same rows the period's bars are counted from. */
    val completed: Int,
    /** Tasks created inside the period. */
    val created: Int,
    /** Non-tombstoned rows the profile holds, in the spellings the database stores them under. */
    val statusCounts: List<TaskStatusCount>,
    val categories: List<CategoryWorkSummary>,
    val topTasks: List<TaskWorkSummary>,
    /** Days of measured work running up to today. */
    val streakDays: Int
) {
    /** Rows that are finished, whichever spelling of finished the row carries. */
    val completedEver: Int = statusCounts.filter { isCompletedSpelling(it.status) }.sumOf { it.count }

    /** Every row the profile has that is not a tombstone. */
    val totalTasks: Int = statusCounts.sumOf { it.count }

    /** Share of the account finished, or null when the account has nothing to divide. */
    val completionRate: Float? = DashboardMath.completionRate(completedEver, totalTasks)

    /**
     * Whether the screen has anything to draw.
     *
     * Work measured with no tasks left in the account is still data, and an account with tasks but no
     * measurements is still a screen — so the empty state is both being zero, not either one alone.
     */
    val isEmpty: Boolean get() = totalTasks == 0 && measured.seconds == 0L
}

/**
 * The dashboard's reads, in the order that costs the least.
 *
 * Two things it deliberately does not do:
 *
 *  * **It never loads the profile's rows.** The screen used to call `getAllTasksOnce` and fold the list;
 *    every figure now comes back as a number, so the cost grows with the answer rather than with the
 *    account.
 *  * **It does not try to be a live ticker.** The dashboard shows what has been measured and closed. The
 *    stretch still running belongs to the task card and to the bar at the bottom of the screen, which
 *    re-measure a stored start against the clock; a total that moved every second would make the charts
 *    disagree with the history list next to them.
 *
 * The Flow follows the history screen's precedent: a grouped result is not something Room can derive a
 * Flow from, so the reader watches the three change signals — the last write to a task, to an interval,
 * to a session — and re-runs its own queries when any of them moves. A task completed while the screen
 * is open therefore reaches the charts without a timer and without anything loaded first.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class DashboardRepository @Inject constructor(
    private val taskStatisticsDao: TaskStatisticsDao,
    private val workSessionDao: WorkSessionDao,
    private val trackingSessionDao: TrackingSessionDao,
    private val statisticsRepository: WorkStatisticsRepository,
    private val sessionRepository: SessionRepository,
    private val timeBase: TimeBase
) {

    /** Everything the screen shows, for one [request]. Null while nobody is signed in. */
    suspend fun snapshot(request: DashboardRequest): DashboardSnapshot? {
        val userId = sessionRepository.currentUserId() ?: return null
        val window = statisticsRepository.window(request.period, request.onDay, request.zone)
        val heatmapDays = DashboardMath.heatmapRange(
            request.onDay, request.heatmapWeeks, request.firstDayOfWeek
        )
        val heatmapWindow = statisticsRepository.window(heatmapDays, request.zone)

        // The instants this period's tasks were finished at. One bounded read serves three figures — the
        // period's count, the bars' overlay, and the grid's squares — and it is bounded twice over: by
        // the window's two bounds and by the index that starts with the profile.
        val completedInstants =
            taskStatisticsDao.completedInstantsInPeriod(userId, window.fromMillis, window.toMillis)
        val completions = DashboardMath.completionCounts(completedInstants, request.zone)

        val gridDays = taskStatisticsDao.completedInstantsInPeriod(
            userId, heatmapWindow.fromMillis, heatmapWindow.toMillis
        )

        val health = taskStatisticsDao.healthCounts(
            userId = userId,
            now = timeBase.wallMillis(),
            dayEnd = CalendarMath.startOfDay(request.onDay.plusDays(1), request.zone),
            weekEnd = CalendarMath.startOfDay(request.onDay.plusDays(7), request.zone),
            staleBefore = CalendarMath.startOfDay(
                request.onDay.minusDays(STALE_AFTER_DAYS), request.zone
            )
        )

        // The streak is read over the grid rather than over the period: a run of days is exactly the
        // thing a month's totals cannot show.
        val workedDaily = statisticsRepository.dailySeries(heatmapWindow)

        return DashboardSnapshot(
            request = request,
            measured = statisticsRepository.summary(window) ?: WorkPeriodSummary(0L, 0, 0),
            trend = DashboardMath.trend(
                worked = statisticsRepository.dailySeries(window),
                completed = completions
            ),
            heatmap = DashboardMath.heatmap(
                worked = workedDaily,
                completed = DashboardMath.completionCounts(gridDays, request.zone),
                today = request.onDay,
                weeks = request.heatmapWeeks,
                firstDay = request.firstDayOfWeek
            ),
            health = health,
            completed = completedInstants.size,
            created = taskStatisticsDao.createdInPeriod(userId, window.fromMillis, window.toMillis),
            statusCounts = taskStatisticsDao.statusCounts(userId),
            categories = statisticsRepository.byCategory(window),
            topTasks = statisticsRepository.byTask(window, TOP_TASK_LIMIT),
            streakDays = DashboardMath.streak(workedDaily, request.onDay)
        )
    }

    /**
     * The same snapshot as a Flow that re-reads itself when the account changes.
     *
     * A window is computed once here and handed to [snapshot], so the signals moving cannot make the
     * screen's period drift: a chart re-read under a label that was derived from another window would be
     * the same kind of mistake the aggregates exist to avoid.
     */
    fun observe(request: DashboardRequest): Flow<DashboardSnapshot?> =
        sessionRepository.currentUser.flatMapLatest { user ->
            if (user == null) flowOf(null) else combine(
                taskStatisticsDao.observeChanges(user.id),
                workSessionDao.observeChanges(user.id),
                trackingSessionDao.observeChanges(user.id)
            ) { tasks, intervals, sessions -> tasks + intervals + sessions }
                .map { snapshot(request) }
        }

    private companion object {
        /** An open task untouched for this many days is one the screen calls stale. */
        private const val STALE_AFTER_DAYS = 14L

        /** The busiest tasks the period lists; the rest are the history screen's business. */
        private const val TOP_TASK_LIMIT = 5
    }
}
