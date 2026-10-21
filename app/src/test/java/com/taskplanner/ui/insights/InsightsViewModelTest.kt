package com.taskplanner.ui.insights

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.taskplanner.data.calendar.CalendarMath
import com.taskplanner.data.model.Insight
import com.taskplanner.data.model.InsightFeedback
import com.taskplanner.data.model.InsightKind
import com.taskplanner.data.model.Priority
import com.taskplanner.data.model.Task
import com.taskplanner.data.model.User
import com.taskplanner.data.repository.InsightRepository
import com.taskplanner.data.repository.WorkloadRepository
import com.taskplanner.data.tracking.TimeBase
import com.taskplanner.data.tracking.WorkTicker
import com.taskplanner.testing.AppFixture
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone
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
 * The insights screen: which readings it holds, the window it shows beside them, and what a tap does.
 *
 * The screen keeps no figure of its own and writes nothing but an answer, so what is its own to get wrong is
 * the reading. Three claims the tests below hold.
 *
 *  * **A card is the row, with the words the row carries.** The title, body and basis arrive as finished text
 *    from the repository, so a screen cannot reformat yesterday's reading into today's phrasing — the list
 *    state has to hand the row through untouched.
 *  * **The pass belongs to the screen.** An empty list before the rules have run is the screen answering its
 *    own question too early, so the state stays busy until both the readings and the pass it started have
 *    arrived, and a profile with nothing to say is an answer rather than a spinner.
 *  * **The panel and the cards are one window.** Both come from reads the screen holds open, so a task written
 *    on the task side of the app moves the fortnight without the screen asking again — the same promise the
 *    goal list makes about a finished task.
 *
 * A clock the test can switch off proves the two failure paths, because they are two different screens. A read
 * that refused has nothing to draw and has to say so, and the collector that reported it must survive long
 * enough to answer the retry the message offers. A pass that refused has nothing to say about the rows: the
 * notebook the last pass wrote is still what is on disk and still what is on screen, beside a complaint that
 * today's reading did not finish. Those two complaints are separate fields for that reason, and the second
 * test keeps the first from wiping it out.
 *
 * Every test anchors on one Wednesday and parks the fixture's clock at midday of it, so a run near a real
 * midnight cannot make "which fortnight" mean two different things in one test.
 */
@RunWith(RobolectricTestRunner::class)
class InsightsViewModelTest {

    private lateinit var defaultZone: TimeZone
    private lateinit var defaultLocale: Locale
    private lateinit var context: Context
    private lateinit var fixture: AppFixture
    private lateinit var viewModel: InsightsViewModel
    private lateinit var zone: ZoneId
    private var userId: Long = -1L

    private val wednesday = LocalDate.of(2026, 6, 3)
    private val tuesday = LocalDate.of(2026, 6, 2)
    private val today get() = wednesday.toEpochDay()

    private fun at(day: LocalDate, hour: Int = 10, minute: Int = 0): Long =
        day.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    @Before
    fun setUp() {
        defaultZone = TimeZone.getDefault()
        defaultLocale = Locale.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"))
        Locale.setDefault(Locale.GERMANY)
        zone = CalendarMath.systemZone()
        context = ApplicationProvider.getApplicationContext()
        fixture = AppFixture(context)
        // viewModelScope needs Dispatchers.Main; an inline executor makes every collector run on the test
        // thread rather than wait for a looper nothing pumps.
        Dispatchers.setMain(Executor { it.run() }.asCoroutineDispatcher())
        userId = runBlocking { fixture.signIn().id }
        runBlocking { fixture.timeBase.shiftTo(at(wednesday, 12, 0)) }
        viewModel = InsightsViewModel(fixture.insightRepository, fixture.workloadRepository)
    }

    @After
    fun tearDown() {
        // The main dispatcher goes before the storage does: the screen's watcher holds Room flows open, and
        // taking the looper away first keeps a late invalidation from being blamed on the next test.
        Dispatchers.resetMain()
        fixture.close()
        TimeZone.setDefault(defaultZone)
        Locale.setDefault(defaultLocale)
    }

    private fun state() = viewModel.state.value

    private fun keys() = state().insights.map { it.ruleKey }

    /** Three open, high-priority tasks whose deadlines have passed: the smallest pile the rule calls a pile. */
    private suspend fun overduePile() {
        listOf("late one", "late two", "late three").forEach { title ->
            fixture.taskDao.insertTask(
                Task(
                    title = title, description = "", userId = userId,
                    priority = Priority.HIGH, dueDate = at(tuesday, 17, 0)
                )
            )
        }
    }

    /** Waits for the screen to answer, since the pass and Room's first frame both land on their own threads. */
    private fun awaitLoaded() = awaitUntil("the readings to arrive") { !state().isLoading }

    /**
     * Waits for the screen to be loaded *and* to be holding the reading the seeded pile produces.
     *
     * [awaitLoaded] is not enough for a test that then names a card, and CI found the difference: `!isLoading`
     * says the pass finished and a frame arrived, and the frame Room delivers first can be the one it read
     * before that pass wrote. The screen catches up a frame later, so a test asserting at the earlier one was
     * racing its own fixture — which is why this waits for the card itself. The assertions that follow stay
     * exactly as they were; only the moment they are allowed to run at has become the one they describe.
     */
    private fun awaitPileRead() = awaitUntil("the notebook to hold the reading the pile raised") {
        !state().isLoading && state().insights.any { it.ruleKey == "high-priority-overload" }
    }

    private fun awaitUntil(what: String, condition: () -> Boolean) {
        fixture.awaitIdle()
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError("timed out waiting for $what")
            }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    @Test
    fun `the screen's own pass fills the list it is reading`() = runBlocking {
        overduePile()

        awaitPileRead()
        assertTrue("the rule the pile raises", keys().contains("high-priority-overload"))
        val card = state().insights.single { it.ruleKey == "high-priority-overload" }
        assertEquals(InsightKind.FACT, card.kind)
        assertTrue("the body states its numbers: ${card.body}", card.body.contains("3"))
        assertTrue("the basis says what was counted: ${card.basis}", card.basis.isNotBlank())
        assertFalse(state().isEmpty)
    }

    @Test
    fun `a profile with nothing to say is an empty list, not a screen still waiting`() {
        awaitLoaded()
        assertTrue(state().isEmpty)
        assertEquals(emptyList<Any>(), state().insights)
        assertNull(state().error)
    }

    @Test
    fun `the panel holds the same fortnight the heavy-day rule was measured on`() = runBlocking {
        overduePile()

        awaitPileRead()
        val shown = requireNotNull(state().workload)
        val computed = fixture.workloadRepository.upcoming().also { fixture.awaitIdle() }

        assertEquals(
            "one window, stated once, whatever path the rows reached the screen on",
            computed,
            shown
        )
        assertEquals(today, shown.fromDay)
        assertEquals(today + 14, shown.toDay)
    }

    @Test
    fun `a task written on the other side of the app moves the panel by itself`() = runBlocking {
        awaitLoaded()
        assertEquals(0, state().workload!!.totalTasks)

        // Through the task repository, with no reload and no refresh in the way.
        fixture.taskRepository.insertTask(
            Task(
                title = "Answer the mail", description = "", userId = userId,
                dueDate = at(wednesday.plusDays(2), 17, 0), estimatedMinutes = 30
            )
        )
        awaitUntil("the panel to draw the task") { state().workload!!.totalTasks == 1 }

        assertEquals(1, state().workload!!.totalTasks)
        assertEquals(
            "and the day it landed on is the day the read names",
            wednesday.plusDays(2).toEpochDay(),
            state().workload!!.heaviestDay?.epochDay
        )
    }

    @Test
    fun `the history switch reads the retired readings rather than filtering the live ones`() = runBlocking {
        overduePile()
        awaitPileRead()
        assertTrue(keys().contains("high-priority-overload"))

        // The pile is worked off through the task side of the app, and the retry runs the pass the reader
        // starting the screen again would run: the rule stops firing, so its row stops being current. The clock
        // moves an hour first, because a pass only retakes a reading it was later than — a row seen at midday
        // and a pass at midday are the same moment, and nothing has been shown to have changed.
        fixture.taskDao.getAllRowsOnce(userId).forEach { task ->
            fixture.taskRepository.deleteTask(task)
        }
        fixture.timeBase.advance(3_600_000L)
        viewModel.retry()
        awaitUntil("the live list to empty") { state().insights.isEmpty() }
        assertFalse(state().isLoading)

        viewModel.setShowHistory(true)
        awaitUntil("the retired list") { state().showHistory && !state().isLoading }
        assertTrue("what the app said about a window it no longer stands behind",
            keys().contains("high-priority-overload"))

        viewModel.setShowHistory(false)
        awaitUntil("the live list back") { !state().showHistory && !state().isLoading }
        assertEquals(emptyList<Any>(), state().insights)
    }

    @Test
    fun `a rejected reading leaves the list the answer takes effect in`() = runBlocking {
        overduePile()
        awaitPileRead()
        val card = state().insights.single { it.ruleKey == "high-priority-overload" }

        viewModel.answer(card.uuid, InsightFeedback.REJECTED)
        awaitUntil("the card to leave the list") { state().insights.isEmpty() }

        assertFalse(state().isLoading)
        assertTrue("an emptied list is an answer, not a screen still waiting", state().isEmpty)
        val stored = runBlocking { fixture.insightDao.getByUuid(card.uuid) }
        assertEquals(
            "the row is kept, with what the reader said on it",
            InsightFeedback.REJECTED,
            requireNotNull(stored).feedback
        )
    }

    @Test
    fun `an accepted reading stays where it is, marked`() = runBlocking {
        overduePile()
        awaitPileRead()
        val card = state().insights.single { it.ruleKey == "high-priority-overload" }

        viewModel.answer(card.uuid, InsightFeedback.ACCEPTED)
        // The mark has to reach the card itself, not only the row underneath it: "you said this was true" is
        // what keeps the same reading from looking like one nobody has answered.
        awaitUntil("the answer to be marked on the card") {
            state().insights.any { it.uuid == card.uuid && it.feedback == InsightFeedback.ACCEPTED }
        }

        assertFalse(state().isLoading)
        assertTrue("a thing the reader means to do is still worth saying", keys().contains(card.ruleKey))
    }

    @Test
    fun `an answer with nothing to answer is said once and then not again`() = runBlocking {
        awaitLoaded()

        viewModel.answer("insight-nobody-raised", InsightFeedback.USEFUL)
        awaitUntil("the refusal") { state().feedbackRefused }

        viewModel.clearFeedbackRefused()
        assertFalse("a refusal is a fact about one render, not a flag that outlives it",
            state().feedbackRefused)
    }

    @Test
    fun `another profile's readings are not in this list`() = runBlocking {
        overduePile()
        awaitPileRead()
        // A row written under a different account, put on disk directly: the isolation the list has to show is
        // the one the read enforces, and a screen that asked for every insight would print this one under the
        // wrong profile.
        val otherId = fixture.userDao.insertUser(User(email = "other@example.com", name = "Another"))
        fixture.insightDao.insert(
            Insight(
                uuid = Insight.uuidFor(otherId, "their-rule", today - 6, today),
                userId = otherId,
                ruleKey = "their-rule",
                kind = InsightKind.FACT,
                title = "Their reading",
                body = "Nothing of yours.",
                basis = "Counted over their own rows.",
                periodFromDay = today - 6,
                periodToDay = today,
                sampleSize = 1,
                confidence = 60,
                firstSeenAt = at(wednesday, 9),
                lastSeenAt = at(wednesday, 9)
            )
        )
        assertTrue(
            "the row really is on disk, under the other account",
            fixture.insightDao.allOf(otherId).any { it.ruleKey == "their-rule" }
        )

        assertFalse("the screen reads this account's notebook and no other", keys().contains("their-rule"))
        assertTrue(keys().contains("high-priority-overload"))
    }

    @Test
    fun `the screen writes nothing but the answer it was given`() = runBlocking {
        overduePile()
        awaitPileRead()
        val card = state().insights.single { it.ruleKey == "high-priority-overload" }
        val before = workSignature()

        viewModel.answer(card.uuid, InsightFeedback.HIDDEN)
        awaitUntil("the reading to leave the list") { state().insights.isEmpty() }

        assertEquals(
            "an app that notices a pile is not an app that moves it",
            before,
            workSignature()
        )
    }

    /** Every row the screen could be accused of changing, as one string. */
    private suspend fun workSignature(): String = with(fixture) {
        val tasks = taskDao.getAllRowsOnce(userId).joinToString(";") { task ->
            "${task.dueDate}/${task.startTime}/${task.endTime}/${task.priority}/${task.status}" +
                "/${task.estimatedMinutes}/${task.updatedAt}/${task.completedAt}"
        }
        val intervals = workSessionDao.getAllForUser(userId).joinToString(";") { interval ->
            "${interval.startedAt}/${interval.endedAt}/${interval.durationSeconds}"
        }
        "tasks=$tasks|intervals=$intervals"
    }

    @Test
    fun `a read the clock refused is said out loud and retrying asks the same question again`() = runBlocking {
        overduePile()
        awaitPileRead()
        assertTrue(keys().contains("high-priority-overload"))

        val flaky = FlakyTimeBase(fixture.timeBase)
        flaky.failing = true
        viewModel = InsightsViewModel(insightRepositoryOn(flaky), workloadRepositoryOn(flaky))

        awaitUntil("the screen to explain itself") { state().error != null }
        assertEquals("a read that failed has no cards to show", emptyList<Any>(), state().insights)
        assertNull("and no window either", state().workload)
        assertFalse("a failure is an answer, so the spinner stops", state().isLoading)
        assertFalse(
            "and the complaint is the answer here, not the empty state the reader would get with nothing wrong",
            state().isEmpty
        )

        flaky.failing = false
        viewModel.retry()
        awaitUntil("the retry to land with the reading back") { keys().contains("high-priority-overload") }
        assertNull("a read that came back leaves no complaint behind", state().error)
        assertNull("and neither does the pass that was asked for again", state().passError)
        assertTrue(keys().contains("high-priority-overload"))
        assertNotNull("the panel comes back with the list it was read beside", state().workload)
    }

    @Test
    fun `a pass the clock refused keeps the cards it could not refresh and says so`() = runBlocking {
        overduePile()
        awaitPileRead()
        assertTrue(keys().contains("high-priority-overload"))

        // The window is read off a sound clock: only the analysis is asked for on the clock that refuses, which
        // is the case the two complaints have to be kept apart for.
        val flaky = FlakyTimeBase(fixture.timeBase)
        flaky.failing = true
        viewModel = InsightsViewModel(insightRepositoryOn(flaky), fixture.workloadRepository)

        awaitUntil("the screen to complain about the analysis") { state().passError != null }
        assertNull("the list the screen was given was read, not refused", state().error)
        assertTrue(
            "a pass that threw leaves the notebook the last pass wrote, rather than an empty screen",
            state().insights.isNotEmpty()
        )
        assertNotNull("and the panel is still the window it holds open", state().workload)
        assertFalse("the pass reported back, so the spinner stops", state().isLoading)
        assertFalse("a screen holding yesterday's readings is not an empty one", state().isEmpty)

        flaky.failing = false
        viewModel.retry()
        awaitUntil("the retry to finish the analysis with the notebook intact") {
            !state().isLoading && state().passError == null &&
                keys().contains("high-priority-overload")
        }
        assertNull("a pass that came back takes its complaint with it", state().passError)
        assertTrue("and the notebook is still the one the reader was looking at",
            keys().contains("high-priority-overload"))
    }

    /**
     * The same window, read on a clock the test can switch off.
     *
     * The ticker is rebuilt over that clock rather than borrowed from the fixture, because the watched read
     * asks it what today is before it asks Room anything: a window on a refused clock has to fail the way the
     * screen's real first question fails, not answer from a clock the test left running. The monotonic line
     * stays the fixture's, because it is a different clock — elapsed real time since boot — and nothing on the
     * load path reads it. Building a second one would read the wall clock at construction and turn the test's
     * own wiring into the failure it is meant to observe.
     */
    private fun workloadRepositoryOn(timeBase: TimeBase) = WorkloadRepository(
        fixture.taskDao, fixture.workStatisticsDao, fixture.sessionRepository,
        WorkTicker(timeBase, fixture.monotonicReference), timeBase
    )

    private fun insightRepositoryOn(timeBase: TimeBase) = InsightRepository(
        fixture.database, fixture.insightDao, workloadRepositoryOn(timeBase), fixture.taskStatisticsDao,
        fixture.workStatisticsDao, fixture.goalDao, fixture.habitDao, fixture.habitRepository,
        fixture.goalRepository, fixture.sessionRepository, timeBase, context
    )

    /**
     * A clock that can be made to refuse, so the screen's failure paths are reads that actually failed.
     *
     * Both the pass and the watched window ask the clock what today is, so switching it off is the smallest way
     * to make either of them disagree with the screen — and switching it back on is the proof that the collector
     * survived the refusal rather than ending with it.
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
