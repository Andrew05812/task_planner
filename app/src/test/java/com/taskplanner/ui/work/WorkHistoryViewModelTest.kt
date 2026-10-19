package com.taskplanner.ui.work

import android.content.Context
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.taskplanner.data.calendar.CalendarMath
import com.taskplanner.data.local.AppDatabase
import com.taskplanner.data.model.Task
import com.taskplanner.data.model.WorkIntervalCorrection
import com.taskplanner.data.model.WorkPeriodSummary
import com.taskplanner.data.repository.AdjustmentRejection
import com.taskplanner.data.repository.AdjustmentResult
import com.taskplanner.data.repository.HistorySort
import com.taskplanner.data.repository.TrackingOutcome
import com.taskplanner.data.repository.WorkPeriod
import com.taskplanner.data.repository.WorkTrackingRepository
import com.taskplanner.testing.AppFixture
import java.time.LocalDate
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
 * The history screen: which read each of its commands asks for, and what the user is told when a write
 * lands, is refused, or fails.
 *
 * The ViewModel computes no figures of its own — §10 makes SQLite the only author of a total, and
 * `WorkStatisticsDaoTest` is where the figures are cross-checked against rows summed by hand. What is
 * this screen's own to get wrong is the *reading*: the period the user named, the order they named, and
 * the rule that an entry's stretches are fetched only once somebody opens it. A screen that filtered
 * the list it already had would look correct while showing a history shorter than the one stored, and
 * a screen that loaded every stretch to draw the list is exactly the aggregate-without-an-index the
 * design rules refuse.
 *
 * The second thing the screen owns is the answer it gives to a write that did not happen. §13's
 * conflict, a stretch the timer still holds, a row the profile does not own, and a storage failure are
 * four genuinely different sentences, and the first three of them must reach the user with nothing
 * written behind them.
 */
@RunWith(RobolectricTestRunner::class)
class WorkHistoryViewModelTest {

    private lateinit var fixture: AppFixture
    private lateinit var viewModel: WorkHistoryViewModel
    private var userId: Long = 0

    private lateinit var report: Task
    private lateinit var plants: Task
    private lateinit var mail: Task

    /** Two days back: the screen opens on *today*, so the seeded history needs a yesterday too. */
    private val day0: Long = LocalDate.now().minusDays(2).toEpochDay()
    private val todayDay: Long get() = LocalDate.now().toEpochDay()

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        fixture = AppFixture(context)
        // viewModelScope needs Dispatchers.Main; an inline executor makes every collector run on the
        // test thread rather than wait for a looper nothing pumps.
        Dispatchers.setMain(Executor { it.run() }.asCoroutineDispatcher())
        userId = runBlocking { fixture.signIn(email = "history@example.com").id }
        report = addTask("Write the report")
        plants = addTask("Water the plants")
        mail = addTask("Answer the mail")
    }

    @After
    fun tearDown() {
        // The main dispatcher goes before the storage does: the screen's watcher holds Room flows open,
        // and taking the looper away first is what keeps a late invalidation from being blamed on the
        // next test in the class.
        Dispatchers.resetMain()
        fixture.close()
    }

    private fun addTask(title: String): Task = runBlocking {
        val id = fixture.addTask(userId, title)
        fixture.taskDao.getTaskById(id)!!
    }

    /** An instant at [hour]:00 of [day], the day the screen has to be able to name. */
    private fun instantIn(day: Long, hour: Int): Long =
        LocalDate.ofEpochDay(day).atTime(hour, 0).atZone(CalendarMath.systemZone())
            .toInstant().toEpochMilli()

    private fun state() = viewModel.state.value

    private fun uuids() = state().rows.map { it.uuid }

    /** The stretches of one entry, oldest first, straight from the database the screen reads. */
    private fun storedIntervals(sessionUuid: String): List<String> = runBlocking {
        fixture.workSessionDao.getIntervalRowsOf(sessionUuid, userId).map { it.uuid }
    }

    private fun awaitUntil(what: String, condition: () -> Boolean) {
        // Room answers on its own threads, so the wait starts by letting them settle: without that the
        // first check can be satisfied by the list a previous command published.
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

    /**
     * A run the timer measured on [day] at [hour]:00 — one closed stretch per entry of [stretches], with
     * a minute of pause between them, and a Stop at the end.
     *
     * Through the production tracker rather than by inserting rows, because the pause is the point: an
     * entry whose figures include the minute the user spent not working is a figure the screen never had
     * a right to print.
     */
    private fun measured(task: Task, day: Long, hour: Int, vararg stretches: Long): String {
        val startedAt = instantIn(day, hour)
        runBlocking {
            fixture.timeBase.wall = startedAt
            assertEquals(TrackingOutcome.STARTED, fixture.workTrackingRepository.start(task))
            stretches.forEachIndexed { index, seconds ->
                fixture.timeBase.advance(seconds * 1_000L)
                fixture.workTrackingRepository.pause(task)
                if (index < stretches.size - 1) {
                    fixture.timeBase.advance(60_000L)
                    fixture.workTrackingRepository.resume(task)
                }
            }
            fixture.workTrackingRepository.stop(task)
        }
        return runBlocking {
            fixture.workSessionDao.getAllForUser(userId).first { it.startedAt == startedAt }
                .trackingSessionUuid
        }
    }

    /**
     * Work the user typed in afterwards: a session of its own with one closed MANUAL stretch. Returns
     * the session uuid the history lists.
     *
     * The clock moves on the way, so the write's `updated_at` is later than anything before it — the
     * change signal the screen watches is a maximum, and a test that left it equal would be testing
     * nothing.
     */
    private fun entered(task: Task, day: Long, hour: Int, minutes: Int): String {
        val startedAt = instantIn(day, hour)
        fixture.timeBase.advance(60_000L)
        val result = runBlocking {
            fixture.workTrackingRepository.recordManualInterval(
                task, startedAt, startedAt + minutes * 60_000L
            )
        }
        val intervalUuid = (result as AdjustmentResult.Accepted).intervalUuid
        return runBlocking { fixture.workSessionDao.getByUuid(intervalUuid)!! }.trackingSessionUuid
    }

    private fun newModel(): WorkHistoryViewModel = WorkHistoryViewModel(
        fixture.workStatisticsRepository, fixture.workTrackingRepository
    )

    /** A screen that has been opened, with the list it opens on already in place. */
    private fun openScreen(): WorkHistoryViewModel {
        viewModel = newModel()
        awaitUntil("the first list") { !state().isLoading }
        return viewModel
    }

    @Test
    fun `the screen opens on today and lists what was measured there`() {
        val yesterdays = measured(report, day0 + 1, 9, 600)
        val mine = entered(plants, todayDay, 11, 30)

        openScreen()

        assertEquals(
            "yesterday is not today's list, however short today looks",
            listOf(mine),
            uuids()
        )
        assertFalse(yesterdays in uuids())
        assertEquals("and the header is the same window the list read", 1_800L, state().totals!!.seconds)
        assertEquals(WorkPeriod.TODAY, state().period)
        assertEquals(1_800L, state().rows.single().closedSeconds)
        assertEquals("the screen can tell typed-in work from measured work", 1, state().rows.single().manual)
    }

    @Test
    fun `nothing measured is an empty list rather than a screen that keeps waiting`() {
        measured(report, day0, 9, 600)

        openScreen()

        assertTrue("the work exists, but not in the period the screen opened on", uuids().isEmpty())
        assertFalse(state().isLoading)
        assertTrue("and that is the empty state, not the loading one", state().isEmpty)
        assertEquals(
            "nothing measured totals zero — the header answers, it does not go quiet. null is reserved " +
                "for the case where there is no profile to ask about",
            WorkPeriodSummary(seconds = 0, intervals = 0, sessions = 0),
            state().totals
        )
    }

    @Test
    fun `the period the user names is the period the list reads`() {
        val yesterdays = measured(report, day0 + 1, 14, 900)
        val mine = measured(plants, todayDay, 9, 60)
        openScreen()
        assertEquals(listOf(mine), uuids())

        viewModel.setPeriod(WorkPeriod.YESTERDAY)
        awaitUntil("yesterday's list") { uuids() == listOf(yesterdays) }

        assertEquals(
            "a period switch is a fresh read, not a trim of what the screen had",
            900L,
            state().rows.single().closedSeconds
        )
        assertEquals(900L, state().totals!!.seconds)
        assertEquals(WorkPeriod.YESTERDAY, state().period)
    }

    @Test
    fun `the range the user picked is the window the screen reads`() {
        val oldest = measured(report, day0, 9, 600)
        val middle = measured(plants, day0 + 1, 9, 600)
        val newest = measured(mail, todayDay, 9, 600)
        openScreen()

        assertTrue(viewModel.setCustomRange(LocalDate.ofEpochDay(day0), LocalDate.ofEpochDay(day0 + 1)))
        awaitUntil("the two days the user named") { uuids().size == 2 }

        assertEquals("newest first, and nothing from the third day", listOf(middle, oldest), uuids())
        assertEquals(1_200L, state().totals!!.seconds)
        assertNull("a range the user picked is not one of the named periods", state().period)
        assertEquals(listOf(day0, day0 + 1), listOf(state().customDays.first, state().customDays.last))
        assertFalse(newest in uuids())
    }

    @Test
    fun `a range the user reversed is refused and the screen keeps what it showed`() {
        val mine = measured(report, todayDay, 9, 600)
        openScreen()

        assertFalse(
            "a last day before its first is nobody's period; the screen says so instead of swapping them",
            viewModel.setCustomRange(LocalDate.ofEpochDay(todayDay), LocalDate.ofEpochDay(day0))
        )

        assertEquals(listOf(mine), uuids())
        assertEquals("the period it was already on is still the read it is making", WorkPeriod.TODAY, state().period)
        assertEquals(0L, state().customDays.first)
        assertNull("a refused ask is not an error either", state().error)
    }

    @Test
    fun `the order the user asked for is the order the list arrives in`() {
        val report9 = measured(report, todayDay, 9, 600)
        val mail11 = entered(mail, todayDay, 11, 30)
        val plants13 = measured(plants, todayDay, 13, 5)
        openScreen()

        assertEquals(
            "what the screen opens with is the newest run first",
            listOf(plants13, mail11, report9),
            uuids()
        )

        viewModel.setSort(HistorySort.DURATION)
        awaitUntil("the longest run at the top") { uuids() == listOf(mail11, report9, plants13) }

        viewModel.setSort(HistorySort.TASK)
        awaitUntil("the list grouped by task") { uuids() == listOf(mail11, plants13, report9) }

        viewModel.setSort(HistorySort.DATE)
        awaitUntil("the date order back") { uuids() == listOf(plants13, mail11, report9) }
        assertEquals(
            "an order changes where the rows sit, never what they say",
            mapOf(plants13 to 5L, mail11 to 1_800L, report9 to 600L),
            state().rows.associate { it.uuid to it.closedSeconds }
        )
        assertEquals(2_405L, state().totals!!.seconds)
    }

    @Test
    fun `opening an entry is what reads its stretches and closing it stops carrying them`() {
        val session = measured(report, todayDay, 9, 600, 300)
        openScreen()

        assertTrue(
            "the list arrives with no stretch of it loaded: opening is the read",
            state().intervalsOf.isEmpty()
        )

        viewModel.toggleExpand(session)
        awaitUntil("the entry to open") { state().intervalsOf.containsKey(session) }

        assertEquals(
            "oldest first, the order the user reads a run in",
            storedIntervals(session),
            state().intervalsOf.getValue(session).map { it.uuid }
        )
        assertEquals(listOf(600L, 300L), state().intervalsOf.getValue(session).map { it.durationSeconds })
        assertEquals(
            "the minute between them was paused, so it is in no figure here",
            900L,
            state().rows.single().closedSeconds
        )

        viewModel.toggleExpand(session)
        awaitUntil("the entry to close") { !state().intervalsOf.containsKey(session) }
        assertEquals("the list itself was never re-read", listOf(session), uuids())
    }

    @Test
    fun `a correction lands in the entry it was made from and in the figures above it`() {
        val session = measured(report, todayDay, 9, 600, 300)
        openScreen().toggleExpand(session)
        awaitUntil("the entry to open") { state().intervalsOf.containsKey(session) }
        val second = state().intervalsOf.getValue(session).last().uuid

        var answer: AdjustmentResult? = null
        viewModel.correctInterval(
            second, instantIn(todayDay, 10), instantIn(todayDay, 11), "stopped the timer late",
            { answer = it }
        )
        awaitUntil("the correction to be answered") { answer != null }

        assertEquals(AdjustmentResult.Accepted(second), answer)
        awaitUntil("the entry and the header to follow") {
            state().totals!!.seconds == 4_200L &&
                state().intervalsOf.getValue(session).last().durationSeconds == 3_600L
        }
        assertEquals(4_200L, state().rows.single().closedSeconds)
        assertEquals(
            "the stretch moved to where the user says the work happened",
            instantIn(todayDay, 10),
            state().intervalsOf.getValue(session).last().startedAt
        )
        assertEquals("the entry kept the order the screen asked for", listOf(session), uuids())
        assertNull("a write that landed leaves no error behind", state().error)
    }

    @Test
    fun `what a stretch was told before comes back oldest first when the editor asks`() {
        val session = measured(report, todayDay, 9, 600)
        val interval = storedIntervals(session).single()
        runBlocking {
            fixture.workTrackingRepository.correctInterval(
                interval, instantIn(todayDay, 9), instantIn(todayDay, 10) + 30 * 60_000L, "ended late"
            )
        }
        fixture.timeBase.advance(1_000L)
        runBlocking {
            fixture.workTrackingRepository.correctInterval(
                interval, instantIn(todayDay, 9), instantIn(todayDay, 11), "and later still"
            )
        }

        openScreen()
        var read: List<WorkIntervalCorrection> = emptyList()
        viewModel.correctionsOf(interval) { read = it }
        awaitUntil("the corrections the editor was told") { read.size == 2 }

        assertEquals(
            "each change records the figure it replaced, so the chain reads forwards",
            listOf("ended late", "and later still"),
            read.map { it.reason }
        )
        assertEquals(read.first().newEndedAt, read.last().oldEndedAt)
        assertEquals(
            "and the list itself shows the last word, not the first",
            7_200L,
            state().rows.single().closedSeconds
        )
    }

    @Test
    fun `a stretch the user throws away leaves the entry and the total`() {
        val session = measured(report, todayDay, 9, 600, 300)
        openScreen().toggleExpand(session)
        awaitUntil("the entry to open") { state().intervalsOf.containsKey(session) }
        val second = state().intervalsOf.getValue(session).last().uuid

        var answer: AdjustmentResult? = null
        viewModel.removeInterval(second) { answer = it }
        awaitUntil("the removal to be answered") { answer != null }

        assertEquals(AdjustmentResult.Accepted(second), answer)
        awaitUntil("the entry and the header to follow") {
            state().intervalsOf.getValue(session).size == 1 && state().totals!!.seconds == 600L
        }
        assertEquals(
            "the session is still history with one stretch left in it",
            listOf(600L),
            state().intervalsOf.getValue(session).map { it.durationSeconds }
        )
        assertEquals(1, state().rows.single().intervals)
        assertEquals(listOf(session), uuids())
        assertTrue(
            "hidden, not destroyed: the row is still there with its numbers",
            runBlocking { fixture.workSessionDao.getByUuid(second) }!!.isDeleted
        )
    }

    @Test
    fun `a write that would land on measured work is shown as the conflict and nothing changes`() {
        val first = entered(report, todayDay, 9, 10)
        val second = entered(plants, todayDay, 10, 10)
        openScreen().toggleExpand(first)
        awaitUntil("the entry to open") { state().intervalsOf.containsKey(first) }
        val interval = state().intervalsOf.getValue(first).single().uuid

        var answer: AdjustmentResult? = null
        viewModel.correctInterval(
            interval, instantIn(todayDay, 9), instantIn(todayDay, 10) + 300_000L,
            "it went longer than I thought", { answer = it }
        )
        awaitUntil("the conflict to be answered") { answer != null }

        val conflict = answer as AdjustmentResult.Overlapping
        assertEquals(
            "the screen is handed the very rows in the way, so it can name them",
            storedIntervals(second),
            conflict.intervals.map { it.uuid }
        )
        assertEquals(
            "and the history is exactly what it was before the ask",
            mapOf(first to 600L, second to 600L),
            state().rows.associate { it.uuid to it.closedSeconds }
        )
        assertEquals(1_200L, state().totals!!.seconds)
        assertNull("a conflict is an answer, not a failure", state().error)
    }

    @Test
    fun `a stretch the timer still holds cannot be corrected here`() {
        val startedAt = instantIn(todayDay, 9)
        runBlocking {
            fixture.timeBase.wall = startedAt
            fixture.workTrackingRepository.start(report)
        }
        val open = runBlocking { fixture.workSessionDao.getAllForUser(userId).single { it.isOpen } }
        openScreen().toggleExpand(open.trackingSessionUuid)
        awaitUntil("the running entry to open") { state().intervalsOf.containsKey(open.trackingSessionUuid) }

        var answer: AdjustmentResult? = null
        viewModel.correctInterval(
            open.uuid, startedAt, startedAt + 60_000L, "make it a minute", { answer = it }
        )
        awaitUntil("the refusal to be answered") { answer != null }

        assertEquals(
            "until it closes that stretch belongs to the timer, not to the editor",
            AdjustmentResult.Rejected(AdjustmentRejection.STILL_BEING_MEASURED),
            answer
        )
        assertEquals(
            "nothing was written: the same row is still the open one",
            open.uuid,
            runBlocking { fixture.workSessionDao.getAllForUser(userId).single { it.isOpen } }.uuid
        )
        assertEquals(
            "the entry is listed with its unfinished figure kept out of the total",
            0L,
            state().rows.single().closedSeconds
        )
        assertNull("and a refusal the screen can explain needs no error of its own", state().error)
    }

    @Test
    fun `a row nobody holds is refused rather than edited into somebody else's history`() {
        val mine = entered(report, todayDay, 9, 10)
        openScreen()

        var answer: AdjustmentResult? = null
        viewModel.correctInterval(
            "a-uuid-nobody-holds", instantIn(todayDay, 12), instantIn(todayDay, 13), null,
            { answer = it }
        )
        awaitUntil("the refusal to be answered") { answer != null }

        assertEquals(AdjustmentResult.Rejected(AdjustmentRejection.NOT_FOUND), answer)
        assertEquals(listOf(mine), uuids())
        assertEquals(600L, state().totals!!.seconds)
        assertNull(state().error)
    }

    @Test
    fun `work measured while the screen is open reaches the list without anybody refreshing it`() {
        val first = measured(report, todayDay, 9, 600)
        openScreen()
        assertEquals(listOf(first), uuids())

        val second = entered(plants, todayDay, 11, 30)
        awaitUntil("the new entry to show up") { uuids().size == 2 }

        assertEquals("newest first, the order the screen already had", listOf(second, first), uuids())
        assertEquals(2_400L, state().totals!!.seconds)
    }

    @Test
    fun `a write the storage itself refused is the screen's to say out loud`() {
        val session = measured(report, todayDay, 9, 600)
        val interval = storedIntervals(session).single()
        // Storage that cannot answer for the history table — the failure a phone produces when the
        // database goes away under a write, which the screen cannot tell from a refusal and must not
        // guess. A dropped table is used rather than a closed database because a closed one reopens as a
        // fresh empty copy: it answers the read happily, so the test would end up proving that a missing
        // row is reported instead of proving that a missing answer is.
        val brokenBase = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AppDatabase::class.java
        ).allowMainThreadQueries().build()
        brokenBase.openHelper.writableDatabase.execSQL("DROP TABLE work_sessions")
        val broken = WorkTrackingRepository(
            brokenBase, brokenBase.trackingSessionDao(), brokenBase.workSessionDao(),
            fixture.sessionRepository, fixture.timeBase, fixture.monotonicReference
        )
        viewModel = WorkHistoryViewModel(fixture.workStatisticsRepository, broken)
        awaitUntil("the list the healthy reader still gives") { uuids() == listOf(session) }

        var answered = false
        viewModel.correctInterval(
            interval, instantIn(todayDay, 10), instantIn(todayDay, 11), "late", { answered = true }
        )
        awaitUntil("the screen to explain itself") { state().error != null }

        assertFalse("a failure is not a result the user can act on", answered)
        assertNotNull(state().error)
        assertFalse(state().isLoading)
        assertEquals(
            "and the history it had is still on screen, unchanged",
            600L,
            state().rows.single().closedSeconds
        )

        viewModel.clearError()
        assertNull(state().error)
        brokenBase.close()
    }

    @Test
    fun `signing out leaves the screen with nothing rather than the last profile's history`() {
        measured(report, todayDay, 9, 600)
        openScreen()
        assertEquals(1, uuids().size)

        runBlocking { fixture.sessionRepository.endSession() }
        awaitUntil("the list to go with the profile") { uuids().isEmpty() }
        awaitUntil("the header to go with it") { state().totals == null }

        assertFalse(state().isLoading)
        assertTrue(state().isEmpty)
        assertNull(
            "the counterpart of an empty period: nothing measured answers with zeroes, while nobody " +
                "signed in has no figures to answer with at all",
            state().totals
        )
    }
}
