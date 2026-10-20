package com.taskplanner.data.repository

import com.taskplanner.data.calendar.CalendarMath
import com.taskplanner.data.insight.WorkloadMath
import com.taskplanner.data.insight.WorkloadRead
import com.taskplanner.data.local.TaskDao
import com.taskplanner.data.local.WorkStatisticsDao
import com.taskplanner.data.model.DailyWorkSummary
import com.taskplanner.data.model.Task
import com.taskplanner.data.tracking.TimeBase
import com.taskplanner.data.tracking.WorkTicker
import com.taskplanner.data.tracking.WorkTimeMath
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

/**
 * The overload read (§8): what the days ahead are asked for, against the days behind that were measured.
 *
 * One question, answered in one place, because two screens are about to ask it. The panel that shows a
 * fortnight of load and the rule that calls one of those days heavy must state the same minutes for the same
 * day, and the only way to guarantee that is for both to read the same object rather than run the same
 * arithmetic. [WorkloadMath] folds rows into days; this class decides *which* rows a profile owns and which
 * days count as history, and it is the only place either of those two bounds is written down.
 *
 * Three properties the class is built around, and each is a requirement rather than a taste:
 *
 *  * **It reads and never writes.** §8 forbids automatic rescheduling and automatic priority changes, so the
 *    class holds no update query at all — the strongest form of the promise is that there is nothing in it
 *    that could break the promise. `WorkloadRepositoryTest` still checks it by comparing a signature of the
 *    profile's work rows across a read and a pass.
 *  * **Days are the reader's, not fixed-length spans.** Every bound is an epoch day turned into local
 *    milliseconds through the device zone, so a fortnight ahead is fourteen of the user's days, including the
 *    short and long ones at a daylight-saving change.
 *  * **Nothing is invented about a duration.** A deadline nobody estimated arrives as a task on a day and no
 *    minutes, and the read carries [WorkloadRead.lengthUnknownTasks] so a panel can say how much of its own
 *    total is unmeasured.
 *  * **The screen watches; it does not poll.** [observeUpcoming] holds the same two queries the one-shot
 *    [read] runs and folds their rows through the same code, so a task written on the list screen or an hour
 *    measured by the tracker redraws the fortnight by itself.
 */
@Singleton
class WorkloadRepository @Inject constructor(
    private val taskDao: TaskDao,
    private val workStatisticsDao: WorkStatisticsDao,
    private val sessionRepository: SessionRepository,
    private val ticker: WorkTicker,
    private val timeBase: TimeBase
) {

    /**
     * The load over the inclusive epoch-day range `[fromDay, toDay]`, with the baseline behind its first day.
     *
     * The window is read through the calendar's own query rather than a new one: a task belongs to the plan on
     * any day any part of it touches, and the calendar screen already answers that with four bounded lookups.
     * Asking it the same question is what keeps the panel and the calendar from disagreeing about Thursday.
     *
     * A profile that is not signed in has no rows to fold, and that is not an error — it is an empty window,
     * still naming the days it was asked about, because a screen has to be able to show the right fortnight
     * with nothing in it.
     */
    suspend fun read(fromDay: Long, toDay: Long): WorkloadRead {
        val userId = sessionRepository.currentUserId() ?: return WorkloadRead.empty(fromDay, toDay)
        val zone = CalendarMath.systemZone()
        return workloadOf(
            fromDay = fromDay,
            toDay = toDay,
            tasks = window(userId, fromDay, toDay, zone),
            series = workStatisticsDao.dailySeries(userId, baselineFrom(fromDay), fromDay + 1L),
            zone = zone
        )
    }

    /**
     * The same read, held open: the panel that shows a fortnight and the rule that calls a day heavy both
     * watch this, so the figures on the screen are the figures the insight beside them was written from.
     *
     * The window is the profile's today and the fourteen days after it, re-derived when the day changes — the
     * ticker is what lets an app open past midnight move its fortnight without anyone touching it, the same way
     * a goal's week moves. Both bounds go into both queries at the moment of subscription, and the fold and the
     * baseline below are the ones the one-shot [read] runs, over the rows the watched queries return.
     *
     * A profile that is not signed in is an empty window rather than a silence: the screen has to be able to
     * show the right fortnight with nothing in it, and it cannot do that from a Flow that never answers.
     */
    fun observeUpcoming(): Flow<WorkloadRead> = sessionRepository.currentUser.flatMapLatest { user ->
        val userId = user?.id
        val zone = CalendarMath.systemZone()
        ticker.localDays(zone).flatMapLatest { today ->
            val fromDay = today.toEpochDay()
            val toDay = fromDay + HORIZON_DAYS
            if (userId == null) {
                flowOf(WorkloadRead.empty(fromDay, toDay))
            } else {
                combine(
                    taskDao.observeWindow(
                        userId,
                        CalendarMath.startOfDay(LocalDate.ofEpochDay(fromDay), zone),
                        CalendarMath.startOfDay(LocalDate.ofEpochDay(toDay + 1), zone)
                    ),
                    workStatisticsDao.observeDailySeries(userId, baselineFrom(fromDay), fromDay + 1L)
                ) { tasks, series -> workloadOf(fromDay, toDay, tasks, series, zone) }
            }
        }
    }

    /**
     * The read the overload panel and the heavy-day rule live on: from the profile's today, a fortnight on.
     *
     * "Today" comes from the app's clock rather than the device's, the same as every other dated read in the
     * data layer, so a test that parks the clock can ask the question the user would ask on that afternoon.
     */
    suspend fun upcoming(): WorkloadRead {
        val zone = CalendarMath.systemZone()
        val today = WorkTimeMath.epochDayOf(timeBase.wallMillis(), zone)
        return read(today, today + HORIZON_DAYS)
    }

    private suspend fun window(userId: Long, fromDay: Long, toDay: Long, zone: ZoneId): List<Task> =
        taskDao.getWindowOnce(
            userId,
            CalendarMath.startOfDay(LocalDate.ofEpochDay(fromDay), zone),
            CalendarMath.startOfDay(LocalDate.ofEpochDay(toDay + 1), zone)
        )

    /**
     * The window's rows, the measured days behind it and the two bounds, assembled into the one figure.
     *
     * Both readings end here, which is the point: nothing about a fortnight is decided twice, so the watch and
     * the one-shot read cannot disagree about Thursday, and the only difference between them is which of the
     * two Room paths handed over the rows.
     */
    private fun workloadOf(
        fromDay: Long,
        toDay: Long,
        tasks: List<Task>,
        series: List<DailyWorkSummary>,
        zone: ZoneId
    ): WorkloadRead {
        val fold = WorkloadMath.fold(
            tasks = tasks,
            fromDay = fromDay,
            toDay = toDay,
            zone = zone
        )
        val baseline = baselineOf(series)
        return WorkloadRead(
            fromDay = fromDay,
            toDay = toDay,
            days = fold.days,
            priorities = fold.priorities,
            lengthUnknownTasks = fold.lengthUnknownTasks,
            baselineDailyMinutes = baseline.first,
            measuredDays = baseline.second
        )
    }

    /**
     * The four weeks behind a window's first day, up to and including that day and no further: what was worked
     * today is history the days ahead are judged against, and tomorrow is one of the days being judged.
     */
    private fun baselineFrom(fromDay: Long): Long = fromDay - BASELINE_DAYS

    /**
     * The daily average the profile has actually been measured on, over the days behind the window.
     *
     * Only days carrying intervals count towards the average, which is what keeps a fortnight of rest from
     * being read as a low baseline the reader is being told to beat. No measured days at all is no baseline,
     * and a heavy day without one is a claim with nothing under it.
     */
    private fun baselineOf(series: List<DailyWorkSummary>): Pair<Int, Int> {
        val days = series.count { it.intervals > 0 && it.seconds > 0L }
        if (days == 0) return 0 to 0
        val totalMinutes = series.fold(0L) { acc, day -> acc + day.seconds } / SECONDS_PER_MINUTE
        return (totalMinutes / days).toInt() to days
    }

    companion object {
        /** How far ahead the load is read: two weeks of the reader's own plans. */
        private const val HORIZON_DAYS = 14L

        /**
         * The days behind a window's first day that its pace is taken from — twenty-seven, so counting that
         * first day itself the stretch is four whole weeks, the same span the insights' month is.
         */
        private const val BASELINE_DAYS = 27L

        private const val SECONDS_PER_MINUTE = 60L
    }
}
