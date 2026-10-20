package com.taskplanner.ui.goals

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.taskplanner.data.calendar.CalendarMath
import com.taskplanner.data.model.Goal
import com.taskplanner.data.model.GoalType
import com.taskplanner.data.model.GoalValueChange
import com.taskplanner.data.repository.GoalRejection
import com.taskplanner.data.repository.GoalWriteResult
import com.taskplanner.testing.AppFixture
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

/**
 * The goal form: what its texts become on the way into the row, and what it refuses to write.
 *
 * The editor is the only place in this feature where a number the user typed is read out of a text field, so
 * it is the only place a unit can be dropped or a day shifted without anything complaining. A window stored
 * one day off prints as a figure that quietly excludes a task that was finished inside it, and a deadline
 * parsed in the wrong zone is a goal that reads as late a day early. The tests below therefore check the
 * stored row rather than the callback: the answer the screen got is already the repository's, and only the
 * database says what the form actually sent.
 *
 * The other half is the refusals. A field that names no day and a goal that is no longer on disk are two
 * different sentences — one tells the user which box to fix, the other that there is nothing to fix — and
 * both must leave the database exactly as it was.
 */
@RunWith(RobolectricTestRunner::class)
class GoalEditorViewModelTest {

    private lateinit var fixture: AppFixture
    private lateinit var viewModel: GoalEditorViewModel
    private var userId: Long = -1L

    private val zone: ZoneId = CalendarMath.systemZone()
    private val monday: LocalDate = LocalDate.of(2026, 6, 1)
    private val today: LocalDate = LocalDate.of(2026, 6, 3)

    private fun at(day: LocalDate, hour: Int = 10, minute: Int = 0): Long =
        day.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        fixture = AppFixture(context)
        Dispatchers.setMain(Executor { it.run() }.asCoroutineDispatcher())
        userId = runBlocking { fixture.signIn().id }
        runBlocking { fixture.timeBase.shiftTo(at(today, 12, 0)) }
        viewModel = GoalEditorViewModel(fixture.goalRepository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        fixture.close()
    }

    private fun state() = viewModel.state.value

    private suspend fun stored(uuid: String): Goal? = fixture.goalDao.getGoal(uuid, userId)

    private fun goalsOnDisk(): List<Goal> = runBlocking { fixture.goalDao.getAllForUser(userId) }

    private suspend fun openNew() {
        viewModel.load(-1L)
    }

    /**
     * Fills the form the way the fields hand their texts over and waits for the answer.
     *
     * The dates stay as text on purpose: reading them is the thing under test.
     */
    private fun save(
        title: String = "Clear the desk",
        type: GoalType = GoalType.TASK_COUNT,
        target: String? = "5",
        from: String? = "2026-06-01",
        to: String? = "2026-06-03",
        deadline: String? = null,
        reason: String? = null
    ): GoalSaveOutcome {
        var answer: GoalSaveOutcome? = null
        viewModel.save(
            title = title, type = type, targetText = target, windowFromText = from,
            windowToText = to, deadlineText = deadline, reason = reason
        ) { answer = it }
        // The write goes through Room's own threads, so the answer is not in hand when save() returns.
        val deadlineMillis = System.currentTimeMillis() + 5_000
        while (answer == null) {
            if (System.currentTimeMillis() > deadlineMillis) throw AssertionError("the save never answered")
            Thread.sleep(10)
        }
        return answer!!
    }

    private fun written(outcome: GoalSaveOutcome): GoalWriteResult = when (outcome) {
        is GoalSaveOutcome.Unreadable -> throw AssertionError("the form could not read ${outcome.field}")
        is GoalSaveOutcome.Written -> outcome.result
    }

    private fun unreadable(outcome: GoalSaveOutcome): GoalEditorField = when (outcome) {
        is GoalSaveOutcome.Unreadable -> outcome.field
        is GoalSaveOutcome.Written ->
            throw AssertionError("expected an unreadable field, got ${outcome.result}")
    }

    @Test
    fun `a new aim is stored with the window and figure the fields name`() = runBlocking {
        openNew()
        assertTrue(state().isNew)
        assertFalse("a new aim has nothing to explain", state().asksForReason)

        val saved = written(
            save(
                title = "Read every chapter", target = "12",
                from = "2026-06-01", to = "2026-06-03", deadline = "2026-06-03 18:30"
            )
        ) as GoalWriteResult.Saved

        val row = stored(saved.goalUuid)
        assertNotNull(row)
        assertEquals("Read every chapter", row!!.title)
        assertEquals(12L, row.targetValue)
        // The two days the form printed as yyyy-MM-dd are stored as the epoch days the aggregates filter by,
        // and the deadline as the instant of the time typed in the reader's own zone.
        assertEquals(monday.toEpochDay(), row.windowFromDay)
        assertEquals(today.toEpochDay(), row.windowToDay)
        assertEquals(at(today, 18, 30), row.deadlineAt)
    }

    @Test
    fun `an empty figure is nothing reached yet, not a number the form invented`() = runBlocking {
        openNew()
        val saved = written(save(target = "  ")) as GoalWriteResult.Saved
        assertEquals(0L, stored(saved.goalUuid)!!.targetValue)
    }

    @Test
    fun `a window left open at one end is stored open at that end`() = runBlocking {
        openNew()
        val saved = written(save(from = "2026-06-01", to = "")) as GoalWriteResult.Saved
        val row = stored(saved.goalUuid)!!
        assertEquals(monday.toEpochDay(), row.windowFromDay)
        assertNull("an empty field is an open window, not a zero day", row.windowToDay)
    }

    @Test
    fun `a field that names no day stops the save and writes nothing`() = runBlocking {
        openNew()
        val outcome = save(from = "03/06/2026")
        assertEquals(GoalEditorField.WINDOW_FROM, unreadable(outcome))
        assertTrue("the form stopped before the database", goalsOnDisk().isEmpty())
    }

    @Test
    fun `a mistyped deadline is the field named rather than a date left out`() = runBlocking {
        openNew()
        // A day with no time on it: valid for the window fields, and no instant at all for this one.
        assertEquals(GoalEditorField.DEADLINE, unreadable(save(deadline = "2026-06-03")))
        assertTrue(goalsOnDisk().isEmpty())

        assertEquals(GoalEditorField.TARGET, unreadable(save(target = "twelve")))
        assertEquals(GoalEditorField.WINDOW_TO, unreadable(save(to = "2026-13-01")))
        assertTrue(goalsOnDisk().isEmpty())
    }

    @Test
    fun `a goal that asks for everything by a date refuses without one`() = runBlocking {
        openNew()
        val result = written(save(type = GoalType.DEADLINE, deadline = null))
        assertEquals(GoalRejection.DEADLINE_WITHOUT_A_DATE, (result as GoalWriteResult.Rejected).reason)
        assertTrue(goalsOnDisk().isEmpty())
    }

    @Test
    fun `the form opened on a stored row shows what is on disk`() = runBlocking {
        val created = written(save(title = "Clear the desk", target = "5")) as GoalWriteResult.Saved
        val row = stored(created.goalUuid)!!

        viewModel.load(row.id)
        val waited = awaitLoaded()
        assertTrue("the reason field only explains a change", waited.asksForReason)
        assertFalse(waited.isNew)
        assertEquals("Clear the desk", waited.goal?.title)
        assertEquals(5L, waited.goal?.targetValue)
    }

    @Test
    fun `editing the stored row records the move with the reason typed`() = runBlocking {
        val created = written(save(title = "Clear the desk", target = "5")) as GoalWriteResult.Saved
        val row = stored(created.goalUuid)!!
        viewModel.load(row.id)
        awaitLoaded()

        val saved = written(save(title = "Clear the desk", target = "2", reason = "  "))
        assertTrue(saved is GoalWriteResult.Saved)
        assertEquals(2L, stored(created.goalUuid)!!.targetValue)

        val change = runBlocking { fixture.goalDao.valueChangesOf(created.goalUuid, userId) }.single()
        assertEquals(GoalValueChange.FIELD_TARGET, change.field)
        assertEquals("5", change.oldValue)
        assertEquals("2", change.newValue)
        // A reason field left blank stays blank rather than becoming an empty string the history would
        // print as a space.
        assertNull(change.reason)
    }

    @Test
    fun `a form opened on a goal that is gone refuses to make a new one`() = runBlocking {
        val created = written(save(title = "Clear the desk")) as GoalWriteResult.Saved
        val row = stored(created.goalUuid)!!
        viewModel.load(row.id)
        awaitLoaded()
        fixture.goalRepository.remove(created.goalUuid)

        viewModel.load(row.id + 9_999)
        val waited = awaitLoaded()
        assertTrue(waited.isMissing)
        assertFalse("an empty form is not what this screen was opened for", waited.isNew)

        val result = written(save(title = "Clear the desk", target = "5"))
        assertEquals(GoalRejection.NOT_FOUND, (result as GoalWriteResult.Rejected).reason)
        // The tombstone is still the only row the profile holds: a form that saved "the new aim it was told
        // to edit" would leave the user with a goal they never set, wearing the title of one they deleted.
        assertEquals(listOf(created.goalUuid), goalsOnDisk().map { it.uuid })
    }

    /** Waits for the form's read to answer, and hands back the state it settled on. */
    private fun awaitLoaded(): GoalEditorState {
        val deadline = System.currentTimeMillis() + 5_000
        while (state().isLoading) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("the form never loaded")
            Thread.sleep(10)
        }
        return state()
    }
}
