package com.taskplanner.ui.statistics

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.taskplanner.data.calendar.CalendarMath
import com.taskplanner.data.model.Task
import com.taskplanner.data.model.TaskStatus
import com.taskplanner.data.model.WorkSession
import com.taskplanner.data.model.WorkSource
import com.taskplanner.data.repository.AdjustmentResult
import com.taskplanner.data.repository.DashboardRepository
import com.taskplanner.data.repository.DashboardRequest
import com.taskplanner.data.repository.DashboardSnapshot
import com.taskplanner.data.repository.WorkPeriod
import com.taskplanner.data.statistics.HeatmapCell
import com.taskplanner.data.statistics.HeatmapGrid
import com.taskplanner.data.tracking.TimeBase
import com.taskplanner.data.tracking.WorkTimeMath
import com.taskplanner.testing.AppFixture
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.Executor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The dashboard screen: which read each of its commands asks for, and what it does with the answer.
 *
 * The figures themselves are `DashboardRepositoryTest`'s and the DAO tests' — this file holds up the screen's
 * own promises, which are about *reading* rather than about arithmetic. A dashboard is a screen that can only
 * be right all at once: a period that stays half-changed, a heading that outlives its window, a day that has
 * rolled over at midnight while the grid still shows yesterday's week, and a failed read left as a spinner are
 * four ways the same correct SQL produces a wrong screen.
 *
 * Everything runs on real Room and on the app's own clock seam, so a test that expects a figure has to make
 * SQLite produce it, and a test that expects tomorrow's picture has to bring tomorrow about.
 */
@RunWith(RobolectricTestRunner::class)
class StatisticsViewModelTest {

    private lateinit var fixture: AppFixture
    private lateinit var viewModel: StatisticsViewModel
    private var userId: Long = 0

    private val zone: ZoneId = CalendarMath.systemZone()

    /** The day the app's clock is on — not the device's, because the test can move that one. */
    private fun day(): LocalDate = Instant.ofEpochMilli(fixture.timeBase.wall).atZone(zone).toLocalDate()

    private fun at(anchor: LocalDate, hour: Int = 10, minute: Int = 0): Long =
        anchor.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        fixture = AppFixture(context)
        // viewModelScope needs Dispatchers.Main; an inline executor makes every collector run on the test
        // thread rather than wait for a looper nothing pumps.
        Dispatchers.setMain(Executor { it.run() }.asCoroutineDispatcher())
        userId = runBlocking { fixture.signIn(email = "dashboard@example.com").id }
        // Midday, so a period that ends at the next midnight has room on both sides of the work seeded here.
        fixture.timeBase.shiftTo(at(LocalDate.now(), 12, 0))
    }

    @After
    fun tearDown() {
        // The main dispatcher goes before the storage does: the screen's watcher holds Room flows open, and
        // taking the looper away first is what keeps a late invalidation from being blamed on the next test.
        Dispatchers.resetMain()
        fixture.close()
    }

    private fun addTask(title: String, dueAt: Long? = null): Task = runBlocking {
        val id = fixture.addTask(userId, title, dueAt = dueAt)
        fixture.taskDao.getTaskById(id)!!
    }

    /** A closed stretch of [seconds] measured on [task] on [anchor] — the unit the dashboard sums. */
    private fun measured(task: Task, anchor: LocalDate, seconds: Long, uuid: String) {
        runBlocking {
            fixture.workSessionDao.insert(
                WorkSession(
                    uuid = uuid,
                    userId = task.userId,
                    trackingSessionUuid = "s-$uuid",
                    taskId = task.id,
                    taskUuid = task.uuid,
                    startedAt = at(anchor),
                    endedAt = at(anchor) + seconds * 1_000L,
                    durationSeconds = seconds,
                    localDay = anchor.toEpochDay(),
                    utcOffsetMinutes = WorkTimeMath.utcOffsetMinutesOf(at(anchor)),
                    source = WorkSource.TRACKER
                )
            )
        }
    }

    private fun state() = viewModel.state.value

    /** The snapshot once the screen has one; the tests below all speak about a read that has landed. */
    private fun figures(): DashboardSnapshot {
        val snapshot = state().snapshot
        assertNotNull("the screen has answered", snapshot)
        return snapshot!!
    }

    private fun newModel(repository: DashboardRepository = fixture.dashboardRepository) =
        StatisticsViewModel(repository, fixture.timeBase)

    /** A screen opened on the period it is asked to show, with that period's answer already in place. */
    private fun openOn(period: WorkPeriod): StatisticsViewModel {
        viewModel = newModel()
        if (period != WorkPeriod.THIS_WEEK) viewModel.setPeriod(period)
        awaitUntil("the figures of $period") {
            state().snapshot?.request?.period == period && !state().isLoading
        }
        return viewModel
    }

    private fun awaitUntil(what: String, condition: () -> Boolean) {
        // Room answers on its own threads, so the wait starts by letting them settle: without that the first
        // check can be satisfied by the snapshot a previous command published.
        fixture.awaitIdle()
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError("timed out waiting for $what")
            }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    /** One square of the activity grid, by the day it stands for. */
    private fun cellOf(grid: HeatmapGrid, anchor: LocalDate): HeatmapCell =
        grid.weeks.flatten().firstOrNull { it.localDay == anchor.toEpochDay() }
            ?: throw AssertionError("the grid does not cover $anchor")

    @Test
    fun `the dashboard opens on this week and shows what SQLite measured in it`() {
        val report = addTask("Write the report")
        measured(report, day(), 3_600L, uuid = "i-week")

        openOn(WorkPeriod.THIS_WEEK)

        assertEquals(WorkPeriod.THIS_WEEK, state().period)
        assertFalse(state().isLoading)
        assertFalse("there is a figure to look at, so this is not the empty state", state().isEmpty)
        val snapshot = figures()
        assertEquals(3_600L, snapshot.measured.seconds)
        assertEquals(1, snapshot.measured.intervals)
        assertEquals(
            "the bar chart gets the day that produced something, and only that day",
            listOf(day().toEpochDay()),
            snapshot.trend.map { it.localDay }
        )
        assertEquals(
            "a grid is whole weeks however the period reads",
            DashboardRequest.DEFAULT_HEATMAP_WEEKS,
            snapshot.heatmap.weeks.size
        )
        assertEquals("work today is a run of one", 1, snapshot.streakDays)
        assertEquals("the read is anchored on the day the screen asked about", day(), snapshot.request.onDay)
    }

    @Test
    fun `an account with nothing in it is an empty screen rather than one that keeps waiting`() {
        openOn(WorkPeriod.THIS_WEEK)

        val snapshot = figures()
        assertEquals(0L, snapshot.measured.seconds)
        assertEquals(0, snapshot.totalTasks)
        assertNull(
            "a share needs something to be a share of, and this account has nothing to divide",
            snapshot.completionRate
        )
        assertFalse(state().isLoading)
        assertTrue(state().isEmpty)
        assertTrue(snapshot.trend.isEmpty())
        assertEquals(0L, snapshot.heatmap.busiestDaySeconds)
        assertEquals(
            "an empty grid is still the whole window it was asked for: the squares are the calendar, and " +
                "the picture is what was measured in it",
            DashboardRequest.DEFAULT_HEATMAP_WEEKS * 7,
            snapshot.heatmap.weeks.sumOf { it.size }
        )
    }

    @Test
    fun `naming a period throws away the figures of the one before it`() {
        val report = addTask("Write the report")
        measured(report, day(), 600L, uuid = "i-today")
        measured(report, day().minusDays(1), 900L, uuid = "i-yesterday")

        openOn(WorkPeriod.TODAY)
        assertEquals(600L, figures().measured.seconds)

        viewModel.setPeriod(WorkPeriod.YESTERDAY)

        // Before the new read has even landed: the old window's totals must not stay on screen a moment
        // longer than they are correctly labelled.
        assertTrue(
            "a period switch is a read on its way, not a trim of what the screen had",
            state().isLoading
        )
        assertNull("and until it lands the screen has no figure to show", state().snapshot)

        awaitUntil("yesterday's figures") { state().snapshot?.measured?.seconds == 900L }
        assertEquals(WorkPeriod.YESTERDAY, state().period)
        assertEquals(900L, figures().measured.seconds)
        assertEquals(
            "the heading and the window are two halves of one read",
            WorkPeriod.YESTERDAY,
            figures().request.period
        )
        assertEquals(listOf(day().minusDays(1).toEpochDay()), figures().trend.map { it.localDay })
    }

    @Test
    fun `the period already chosen is not a new read`() {
        val report = addTask("Write the report")
        measured(report, day(), 600L, uuid = "i-same")
        openOn(WorkPeriod.TODAY)
        val snapshot = figures()

        viewModel.setPeriod(WorkPeriod.TODAY)

        assertEquals(
            "asking for the period the screen is already on changes nothing, figures included",
            snapshot.measured.seconds,
            figures().measured.seconds
        )
        assertFalse("and it does not put a spinner over a screen that has its answer", state().isLoading)
    }

    @Test
    fun `work measured while the dashboard is open reaches the charts without anybody refreshing`() {
        val report = addTask("Write the report")
        measured(report, day(), 600L, uuid = "i-seeded")
        openOn(WorkPeriod.TODAY)

        val plants = addTask("Water the plants")
        val answer = runBlocking {
            fixture.timeBase.shiftTo(at(day(), 11, 0))
            // Through the production tracker, so the stretch carries the source and the day the reader groups
            // by rather than whatever a test found convenient to write.
            fixture.workTrackingRepository.recordManualInterval(plants, at(day(), 11, 0), at(day(), 11, 30))
        }
        assertTrue("expected an accepted write, got $answer", answer is AdjustmentResult.Accepted)

        awaitUntil("the new minute and a half to show up") {
            state().snapshot?.measured?.seconds == 2_400L
        }
        val snapshot = figures()
        assertEquals(2, snapshot.measured.intervals)
        assertEquals(
            "the busiest list is the same window read again, so it cannot lag the bar above it",
            listOf(plants.id, report.id),
            snapshot.topTasks.map { it.taskId }
        )
        assertEquals(listOf(day().toEpochDay()), snapshot.trend.map { it.localDay })
        assertEquals(2_400L, snapshot.trend.single().seconds)
        assertEquals(
            "a square is a day, not a stretch: both writes landed on today, so exactly one square lights up",
            1,
            snapshot.heatmap.weeks.sumOf { week -> week.count { it.seconds > 0L } }
        )
        assertEquals(
            "and the square carries the day's whole figure rather than the last write's, which is the one " +
                "way the grid cannot disagree with the bar above it",
            2_400L,
            cellOf(snapshot.heatmap, day()).seconds
        )
    }

    @Test
    fun `finishing a task while the dashboard is open moves it from the health panel into the figures`() {
        val report = addTask("Write the report")
        addTask("Water the plants")
        openOn(WorkPeriod.TODAY)

        assertEquals(2, figures().health.open)
        assertEquals(0, figures().completed)
        assertEquals(0f, figures().completionRate!!, 0f)

        runBlocking {
            fixture.timeBase.shiftTo(at(day(), 15, 0))
            fixture.taskDao.updateTask(
                report.copy(status = TaskStatus.COMPLETED, completedAt = at(day(), 15, 0))
            )
        }

        awaitUntil("the finished task to leave the bar") { state().snapshot?.health?.open == 1 }
        val snapshot = figures()
        assertEquals("it is counted in the period it was finished in", 1, snapshot.completed)
        assertEquals("and in the account-wide share beside the bar", 1, snapshot.completedEver)
        assertEquals(2, snapshot.totalTasks)
        assertEquals(0.5f, snapshot.completionRate!!, 0.0001f)
        assertEquals(
            "the parts of the health bar still add up to the open tasks it divides",
            1,
            snapshot.health.open
        )
    }

    @Test
    fun `the day the screen reads comes from the app's clock and moving past midnight moves the window`() {
        val report = addTask("Write the report", dueAt = at(day(), 18, 0))
        val worked = day()
        measured(report, worked, 600L, uuid = "i-midnight")
        openOn(WorkPeriod.TODAY)

        val before = figures()
        assertEquals(worked, before.request.onDay)
        assertEquals("a deadline later today is due today, not late", 1, before.health.dueToday)
        assertEquals(0, before.health.overdue)
        assertEquals(600L, before.measured.seconds)
        assertEquals(1, before.streakDays)

        // The clock crosses midnight while the screen is asleep in somebody's pocket, and the fragment asks
        // again on the way back — the two calls onResume makes.
        fixture.timeBase.shiftTo(at(worked.plusDays(1), 0, 5))
        viewModel.refreshDay()

        awaitUntil("the new day's window") { state().snapshot?.request?.onDay == day() }
        val after = figures()
        assertEquals(worked.plusDays(1), after.request.onDay)
        assertEquals("yesterday's deadline is now a late one", 1, after.health.overdue)
        assertEquals(0, after.health.dueToday)
        assertEquals(
            "and nothing has been measured yet on the day that has just begun",
            0L,
            after.measured.seconds
        )
        assertEquals(
            "a run that stopped yesterday is still a run, so the streak reads one rather than zero",
            1,
            after.streakDays
        )
        assertEquals(
            "the grid did not lose the day it had already drawn — only the period's window moved",
            600L,
            cellOf(after.heatmap, worked).seconds
        )
        assertFalse(
            "the day that has begun is a day, not a promise",
            cellOf(after.heatmap, worked.plusDays(1)).isFuture
        )
    }

    @Test
    fun `a read the storage refused is said out loud and retrying asks for the same period again`() {
        val report = addTask("Write the report")
        measured(report, day(), 600L, uuid = "i-flaky")
        val flaky = FlakyTimeBase(fixture.timeBase)
        viewModel = newModel(
            DashboardRepository(
                fixture.taskStatisticsDao, fixture.workSessionDao, fixture.trackingSessionDao,
                fixture.workStatisticsRepository, fixture.sessionRepository, flaky
            )
        )
        viewModel.setPeriod(WorkPeriod.TODAY)
        awaitUntil("the figures of today") { state().snapshot?.request?.period == WorkPeriod.TODAY }
        assertEquals(600L, figures().measured.seconds)

        flaky.failing = true
        viewModel.retry()

        awaitUntil("the screen to explain itself") { state().error != null }
        assertNull(
            "a read that failed has no figures, and the last ones that succeeded sit under a heading the " +
                "user has no way to trust",
            state().snapshot
        )
        assertFalse("a failure is an answer, so the spinner stops", state().isLoading)
        assertTrue("and a screen with nothing to show is empty rather than half-drawn", state().isEmpty)

        flaky.failing = false
        viewModel.retry()

        awaitUntil("the retry to land") { state().snapshot?.measured?.seconds == 600L }
        assertNull("a read that came back leaves no complaint behind", state().error)
        assertEquals(
            "retrying asks for the period the user is still looking at, not for a fresh default",
            WorkPeriod.TODAY,
            state().period
        )
    }

    @Test
    fun `signing out leaves the screen with no figures rather than the last profile's`() {
        val report = addTask("Write the report")
        measured(report, day(), 600L, uuid = "i-signout")
        openOn(WorkPeriod.TODAY)
        assertEquals(600L, figures().measured.seconds)

        runBlocking { fixture.sessionRepository.endSession() }

        awaitUntil("the figures to go with the profile") { state().snapshot == null }
        assertFalse(state().isLoading)
        assertTrue("nobody signed in is the empty state, and it is not an error either", state().isEmpty)
        assertNull(state().error)
        assertEquals(
            "the period the user last named is still what the screen is asking for, for whoever signs in " +
                "next",
            WorkPeriod.TODAY,
            state().period
        )
    }

    /**
     * Both clocks of the app, with one of them able to fail.
     *
     * The dashboard asks the clock for the instant its health counts are measured against, so a clock the
     * storage cannot read is a genuine way for one read to fail — and being flippable, it is also the way to
     * watch the same screen succeed again afterwards.
     */
    private class FlakyTimeBase(private val delegate: TimeBase) : TimeBase {
        var failing = false

        override fun wallMillis(): Long {
            if (failing) throw IllegalStateException("the clock could not be read")
            return delegate.wallMillis()
        }

        override fun monotonicMillis(): Long = delegate.monotonicMillis()
    }
}
