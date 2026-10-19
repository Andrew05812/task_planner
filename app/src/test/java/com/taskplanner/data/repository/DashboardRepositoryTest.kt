package com.taskplanner.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.taskplanner.data.calendar.CalendarMath
import com.taskplanner.data.model.Task
import com.taskplanner.data.model.TaskStatus
import com.taskplanner.data.model.User
import com.taskplanner.data.model.WorkSession
import com.taskplanner.data.model.WorkSource
import com.taskplanner.data.statistics.DashboardMath
import com.taskplanner.data.statistics.TrendDay
import com.taskplanner.data.tracking.WorkTimeMath
import com.taskplanner.testing.AppFixture
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The dashboard's one read, against real storage.
 *
 * What is checked here is not that SQLite can add — `WorkStatisticsDaoTest` and
 * `TaskStatisticsDaoTest` already hold each statement up against a hand-folded oracle — but that the
 * figures the screen is given are those answers, for the window the screen named, and that the arithmetic
 * between an answer and a chart neither invents a day nor loses a row. The repository is where two
 * aggregates written for different screens meet, and that is where a dashboard starts disagreeing with
 * itself.
 *
 * Every test anchors the request on a Wednesday of its own and parks the clock at midday of that day, so
 * "overdue", "today", "this week" and a window's two bounds mean the same instants in the test and in the
 * repository.
 */
@RunWith(RobolectricTestRunner::class)
class DashboardRepositoryTest {

    private lateinit var fixture: AppFixture
    private var userId: Long = -1L
    private val zone: ZoneId = CalendarMath.systemZone()

    private val today: LocalDate = LocalDate.of(2026, 6, 3)
    private val monday: LocalDate = LocalDate.of(2026, 6, 1)
    private val tuesday: LocalDate = LocalDate.of(2026, 6, 2)
    private val lastMonth: LocalDate = LocalDate.of(2026, 5, 20)

    private fun at(day: LocalDate, hour: Int = 10, minute: Int = 0): Long =
        day.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    /** The request the screen makes, anchored on [anchor] rather than on the device's date. */
    private fun request(
        period: WorkPeriod = WorkPeriod.THIS_WEEK,
        anchor: LocalDate = today,
        weeks: Int = 5
    ) = DashboardRequest(
        period = period,
        heatmapWeeks = weeks,
        onDay = anchor,
        zone = zone,
        firstDayOfWeek = DayOfWeek.MONDAY
    )

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        fixture = AppFixture(context)
    }

    /**
     * Four tasks and four measured stretches, spread so that no two figures in the snapshot can be
     * produced by the same window:
     *
     *  * *report* — made Monday, worked Monday in one session of two stretches, finished this morning;
     *  * *plants* — made and finished last month, worked this morning, worked once in early May;
     *  * *mail* — open, late since yesterday, untouched since May, so it is both overdue and stale;
     *  * *brief* — open, made yesterday, due in two days, so it is neither.
     *
     * Monday's work is 30 + 20 minutes, today's is an hour, May's is a minute.
     */
    private suspend fun seed(): User {
        val user = fixture.signIn()
        userId = user.id
        fixture.timeBase.wall = at(today, 12, 0)

        val report = task("report", created = monday)
        val plants = task("plants", created = lastMonth)
        task("mail", created = LocalDate.of(2026, 5, 10), due = at(tuesday, 9, 0),
            updatedAt = at(LocalDate.of(2026, 5, 10), 9, 0))
        task("brief", created = tuesday, due = at(LocalDate.of(2026, 6, 4), 9, 0),
            updatedAt = at(tuesday, 12, 0))

        work("i1", "s1", report, monday, 1_800L)
        work("i2", "s1", report, monday, 1_200L)
        work("i3", "s2", plants, today, 3_600L)
        work("i4", "s3", report, LocalDate.of(2026, 5, 4), 60L)

        fixture.taskDao.updateTask(report.copy(status = TaskStatus.COMPLETED, completedAt = at(today, 9, 0)))
        fixture.taskDao.updateTask(plants.copy(status = TaskStatus.COMPLETED, completedAt = at(lastMonth, 9, 0)))
        return user
    }

    private suspend fun task(
        title: String,
        created: LocalDate,
        due: Long? = null,
        updatedAt: Long = at(created, 12, 0),
        forProfile: Long = userId
    ): Task {
        val id = fixture.taskDao.insertTask(
            Task(
                uuid = "task-$title", title = title, description = "", userId = forProfile,
                dueDate = due, createdAt = at(created, 8, 0), updatedAt = updatedAt
            )
        )
        return fixture.taskDao.getTaskById(id)!!
    }

    private suspend fun work(
        uuid: String,
        sessionUuid: String,
        task: Task,
        day: LocalDate,
        durationSeconds: Long
    ) = fixture.workSessionDao.insert(
        WorkSession(
            uuid = uuid,
            userId = task.userId,
            trackingSessionUuid = sessionUuid,
            taskId = task.id,
            taskUuid = task.uuid,
            startedAt = at(day),
            endedAt = at(day) + durationSeconds * 1_000L,
            durationSeconds = durationSeconds,
            localDay = day.toEpochDay(),
            utcOffsetMinutes = WorkTimeMath.utcOffsetMinutesOf(at(day)),
            source = WorkSource.TRACKER
        )
    )

    @Test
    fun `the period's totals are the aggregates the work screen reads, for the same window`() = runBlocking {
        seed()

        val snapshot = fixture.dashboardRepository.snapshot(request())!!

        // Monday's 50 minutes and today's hour are in the week; May's minute is not. The figure is
        // `WorkStatisticsDao`'s, so the dashboard and the history screen cannot report two different
        // amounts of work for one period, and the two stretches of one session stay one session.
        assertEquals(6_600L, snapshot.measured.seconds)
        assertEquals(3, snapshot.measured.intervals)
        assertEquals(2, snapshot.measured.sessions)
        assertEquals(WorkPeriod.THIS_WEEK, snapshot.request.period)
    }

    @Test
    fun `the bars carry the days that produced something and no others`() = runBlocking {
        seed()

        val days = fixture.dashboardRepository.snapshot(request())!!.trend

        assertEquals(
            listOf(
                TrendDay(monday.toEpochDay(), 3_000L, 0),
                TrendDay(today.toEpochDay(), 3_600L, 1)
            ),
            days
        )
        // Nothing was measured on Sunday, and no bar is drawn for it: the gap between the two bars is the
        // truth about when the work happened.
        assertFalse(days.any { it.localDay == LocalDate.of(2026, 5, 31).toEpochDay() })
    }

    @Test
    fun `the grid is exactly the days the query was asked for`() = runBlocking {
        seed()

        val cells = fixture.dashboardRepository.snapshot(request(weeks = 5))!!.heatmap.weeks.flatten()

        assertEquals(35, cells.size)
        assertEquals(
            "the window the reader asked for is the window the grid shows",
            DashboardMath.heatmapRange(today, 5, DayOfWeek.MONDAY).toList(),
            cells.map { it.localDay }
        )
        // May the 4th is inside five weeks back and outside the period's totals, so the same work is on
        // the grid and not in the header — the two pictures answer different questions by design.
        assertEquals(
            60L, cells.first { it.localDay == LocalDate.of(2026, 5, 4).toEpochDay() }.seconds
        )
        assertEquals(6_600L + 60L, cells.sumOf { it.seconds })
        assertFalse(cells.first { it.localDay == today.toEpochDay() }.isFuture)
        assertEquals(
            "the rest of this week has not happened yet",
            listOf(1L, 2L, 3L, 4L),
            cells.filter { it.isFuture }.map { it.localDay - today.toEpochDay() }
        )
    }

    @Test
    fun `a streak is read over the grid rather than over the period`() = runBlocking {
        seed()

        val snapshot = fixture.dashboardRepository.snapshot(request())!!

        // Worked today, nothing measured on Tuesday: the run is one day, and the period's fifty-some
        // minutes of work have nothing to say about it.
        assertEquals(1, snapshot.streakDays)
    }

    @Test
    fun `the health panel and the completion figures speak about the same rows`() = runBlocking {
        seed()

        val snapshot = fixture.dashboardRepository.snapshot(request())!!

        // Two rows are open. One is late and stale, the other due in two days — so the buckets are
        // mutually exclusive and the total is the pair, not their sum doubled.
        assertEquals(2, snapshot.health.open)
        assertEquals(1, snapshot.health.overdue)
        assertEquals(1, snapshot.health.dueThisWeek)
        assertEquals(0, snapshot.health.dueToday)
        assertEquals(1, snapshot.health.stale)
        assertEquals(0, snapshot.health.withoutDeadline)

        // Both finished tasks are in the account's history whatever the panel says about the open pair.
        assertEquals(2, snapshot.completedEver)
        assertEquals(4, snapshot.totalTasks)
        assertEquals(0.5f, snapshot.completionRate!!)
        // One of them was finished inside the week, and two of the rows were made inside it.
        assertEquals(1, snapshot.completed)
        assertEquals(2, snapshot.created)
    }

    @Test
    fun `a period the account has no work in is empty without being an error`() = runBlocking {
        seed()

        val snapshot = fixture.dashboardRepository.snapshot(request(period = WorkPeriod.YESTERDAY))!!

        // Nothing was measured or finished on Tuesday. The screen still has an account to report on, and
        // the grid still spans its own five weeks — the two are different lengths by design, so a period
        // with no work in it reads as that day being quiet rather than as the account being empty.
        assertEquals(0L, snapshot.measured.seconds)
        assertEquals(0, snapshot.measured.intervals)
        assertEquals(emptyList<TrendDay>(), snapshot.trend)
        assertEquals(0, snapshot.completed)
        assertEquals(3_600L, snapshot.heatmap.busiestDaySeconds)
        assertEquals(35, snapshot.heatmap.weeks.flatten().size)
        assertFalse("the screen still has tasks to report on", snapshot.isEmpty)
    }

    @Test
    fun `an account with nothing in it is an empty screen rather than a crash`() = runBlocking {
        fixture.signIn()
        fixture.timeBase.wall = at(today, 12, 0)

        val snapshot = fixture.dashboardRepository.snapshot(request())!!

        assertTrue(snapshot.isEmpty)
        assertNull("a share of nothing is no figure at all", snapshot.completionRate)
        assertEquals(0L, snapshot.heatmap.busiestDaySeconds)
        assertTrue(snapshot.heatmap.weeks.flatten().all { it.level == 0 })
        assertEquals(emptyList<TrendDay>(), snapshot.trend)
        assertEquals(0, snapshot.streakDays)
        assertEquals(0, snapshot.health.open)
    }

    @Test
    fun `nobody signed in is no snapshot rather than an empty one`() = runBlocking {
        assertNull(fixture.dashboardRepository.snapshot(request()))
    }

    @Test
    fun `another profile's work and tasks stay in the other profile`() = runBlocking {
        val mine = seed()
        val other = fixture.signIn("Other", "other@example.com")
        fixture.timeBase.wall = at(today, 12, 0)
        val theirs = task("their report", created = today, forProfile = other.id)
        work("i9", "s9", theirs, today, 5_000L)

        val theirSnapshot = fixture.dashboardRepository.snapshot(request())!!
        assertEquals(5_000L, theirSnapshot.measured.seconds)
        assertEquals(1, theirSnapshot.totalTasks)
        assertEquals(0, theirSnapshot.completedEver)

        fixture.switchTo(mine)
        val mySnapshot = fixture.dashboardRepository.snapshot(request())!!
        assertEquals(6_600L, mySnapshot.measured.seconds)
        assertEquals(4, mySnapshot.totalTasks)
        assertEquals(1, mySnapshot.completed)
    }

    @Test
    fun `the charts move when a task is finished while the screen is open`() = runBlocking {
        seed()

        val answers = snapshotAroundWrite {
            val open = fixture.taskDao.getTaskByUuid("task-mail")!!
            fixture.taskDao.updateTask(
                open.copy(status = TaskStatus.COMPLETED, completedAt = at(today, 11, 30))
            )
        }
        val (before, after) = answers

        // No timer and no full list: the write moved the profile's last-change signal, the dashboard
        // re-ran its aggregates, and the figures that describe that row moved together — the period's
        // count up, the panel's open and overdue counts down.
        assertEquals(1, before.completed)
        assertEquals(2, after.completed)
        assertEquals(2, before.health.open)
        assertEquals(1, after.health.open)
        assertEquals(1, before.health.overdue)
        assertEquals(0, after.health.overdue)
        assertEquals(
            "measured work is untouched by finishing a task",
            before.measured.seconds,
            after.measured.seconds
        )
    }

    /**
     * Two snapshots, with [write] run only after the first one has been produced.
     *
     * `fixture.awaitIdle()` cannot be used in this test: it blocks the thread the collection runs on, so
     * the watcher would never subscribe and the write would land before anything was listening. Waiting on
     * the first answer is both what makes the test work and the proof it needs — the write is known to
     * have happened while the screen was already watching.
     */
    private suspend fun snapshotAroundWrite(write: suspend () -> Unit): List<DashboardSnapshot> =
        coroutineScope {
            val seen = ArrayList<DashboardSnapshot>()
            val firstAnswer = CompletableDeferred<Unit>()
            val secondAnswer = CompletableDeferred<Unit>()
            val watching = launch {
                fixture.dashboardRepository.observe(request()).take(2).collect { snapshot ->
                    if (snapshot != null) {
                        seen.add(snapshot)
                        if (seen.size == 1) firstAnswer.complete(Unit) else secondAnswer.complete(Unit)
                    }
                }
            }

            withTimeout(10_000L) { firstAnswer.await() }
            write()
            withTimeout(10_000L) { secondAnswer.await() }
            watching.cancel()
            seen.toList()
        }
}
