package com.taskplanner.ui.habits

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.taskplanner.data.calendar.CalendarMath
import com.taskplanner.data.model.Habit
import com.taskplanner.data.model.HabitFrequency
import com.taskplanner.data.model.User
import com.taskplanner.data.repository.HabitRejection
import com.taskplanner.data.repository.HabitWriteResult
import com.taskplanner.testing.AppFixture
import com.taskplanner.ui.util.WorkInstantInput
import java.time.DayOfWeek
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
 * The habit's own screen: the week it draws, the day the user keeps, and the account a correction leaves.
 *
 * This is the one place a mark is made, walked back or moved, so the grid is the risk. A cell that offered a
 * day the repository must refuse would make the screen look like it had written something it did not, and a
 * cell that disabled an off-schedule day would refuse the user work they are allowed to assert. Both halves
 * are checked here against real storage: which days the schedule asks for, which days the screen may write,
 * and what the seven cells say once a mark has moved.
 *
 * The corrections are checked on the rows they touch rather than on the figures printed, because §6's promise
 * is that a fix is history: the day that stops being kept has to stay readable as the day the user stood
 * behind, and the day it moved to has to become the kept one in the same read that redraws the grid.
 *
 * The week is pinned Monday-first and the zone is one that jumps, because every cell in the grid is a answer
 * about a week — which seven days the screen shows, and which of them is "today".
 */
@RunWith(RobolectricTestRunner::class)
class HabitDetailViewModelTest {

    private lateinit var defaultZone: TimeZone
    private lateinit var defaultLocale: Locale
    private lateinit var fixture: AppFixture
    private lateinit var viewModel: HabitDetailViewModel
    private var userId: Long = -1L

    private lateinit var zone: ZoneId
    private val previousFriday: LocalDate = LocalDate.of(2026, 5, 29)
    private val previousMonday: LocalDate = LocalDate.of(2026, 5, 25)
    private val previousWednesday: LocalDate = LocalDate.of(2026, 5, 27)
    private val monday: LocalDate = LocalDate.of(2026, 6, 1)
    private val tuesday: LocalDate = LocalDate.of(2026, 6, 2)
    private val wednesday: LocalDate = LocalDate.of(2026, 6, 3)
    private val thursday: LocalDate = LocalDate.of(2026, 6, 4)
    private val sunday: LocalDate = LocalDate.of(2026, 6, 7)
    private val nextMonday: LocalDate = LocalDate.of(2026, 6, 8)
    private val today: LocalDate = wednesday

    private fun at(day: LocalDate, hour: Int = 10, minute: Int = 0): Long =
        day.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    @Before
    fun setUp() {
        defaultZone = TimeZone.getDefault()
        defaultLocale = Locale.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"))
        Locale.setDefault(Locale.GERMANY)
        zone = CalendarMath.systemZone()
        val context: Context = ApplicationProvider.getApplicationContext()
        fixture = AppFixture(context)
        Dispatchers.setMain(Executor { it.run() }.asCoroutineDispatcher())
        userId = runBlocking { fixture.signIn().id }
        runBlocking { fixture.timeBase.shiftTo(at(today, 12, 0)) }
        viewModel = HabitDetailViewModel(fixture.habitRepository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        fixture.close()
        TimeZone.setDefault(defaultZone)
        Locale.setDefault(defaultLocale)
    }

    private fun state() = viewModel.state.value

    private fun cell(day: LocalDate): HabitDayCell =
        state().cells.first { it.day == day.toEpochDay() }

    private fun markedCells() = state().cells.filter { it.marked }.map { LocalDate.ofEpochDay(it.day) }

    private fun askedCells() = state().cells.filter { it.asked }.map { LocalDate.ofEpochDay(it.day) }

    private fun markableCells() = state().cells.filter { it.canMark }.map { LocalDate.ofEpochDay(it.day) }

    private fun saved(result: HabitWriteResult): String = when (result) {
        is HabitWriteResult.Saved -> result.habitUuid
        is HabitWriteResult.Rejected -> throw AssertionError("expected a saved habit, got ${result.reason}")
    }

    private fun rejected(result: HabitWriteResult): HabitRejection = when (result) {
        is HabitWriteResult.Saved -> throw AssertionError("expected a refusal, got a Saved habit")
        is HabitWriteResult.Rejected -> result.reason
    }

    /** One correction, waited for the way the screen's words are handed back. */
    private fun correct(
        fromDay: LocalDate,
        moveTo: LocalDate?,
        reason: String?
    ): HabitCorrectionOutcome {
        var answer: HabitCorrectionOutcome? = null
        viewModel.correctMark(
            fromDay.toEpochDay(),
            moveTo?.let { WorkInstantInput.formatDay(it) },
            reason
        ) { outcome -> answer = outcome }
        awaitUntil("the correction to answer") { answer != null }
        return requireNotNull(answer)
    }

    private fun written(outcome: HabitCorrectionOutcome): HabitWriteResult = when (outcome) {
        is HabitCorrectionOutcome.Written -> outcome.result
        is HabitCorrectionOutcome.UnreadableDay ->
            throw AssertionError("expected a written correction, got an unreadable day")
    }

    /** Creates a habit through the repository and hands back the row, so a test can open on its id. */
    private suspend fun habit(
        title: String = "run",
        frequency: HabitFrequency = HabitFrequency.DAILY,
        start: LocalDate = monday,
        end: LocalDate? = null,
        days: List<DayOfWeek> = emptyList()
    ): Habit {
        val uuid = saved(
            fixture.habitRepository.create(
                title = title,
                frequency = frequency,
                startDay = start.toEpochDay(),
                daysOfWeek = days,
                endDay = end?.toEpochDay()
            )
        )
        return fixture.habitRepository.habit(uuid)!!
    }

    /** Keeps a day through the repository rather than the screen, for a grid that opens already marked. */
    private suspend fun mark(habit: Habit, day: LocalDate) {
        val result = fixture.habitRepository.mark(habit.uuid, day.toEpochDay())
        if (result is HabitWriteResult.Rejected) {
            throw AssertionError("marking $day refused: ${result.reason}")
        }
        fixture.timeBase.advance(1_000L)
    }

    /** Opens the screen on [row] and waits for its first frame. */
    private fun open(row: Habit) {
        viewModel.load(row.id)
        awaitUntil("the habit to arrive") { !state().isLoading }
    }

    private fun awaitUntil(what: String, condition: () -> Boolean) {
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
     * Issues one write and waits for the answer it hands back.
     *
     * The screen only *issues* the write — the repository finishes it on the database's own threads — so a
     * test that read the callback on the line below the call would be asserting on a variable nobody has
     * assigned yet. What is worth waiting for is the same answer the fragment shows its words from.
     */
    private fun write(what: String, action: ((HabitWriteResult) -> Unit) -> Unit): HabitWriteResult {
        var answer: HabitWriteResult? = null
        action { result -> answer = result }
        awaitUntil(what) { answer != null }
        return requireNotNull(answer)
    }

    @Test
    fun `opening on a row's id reads the habit, its figures and the week it stands in`() = runBlocking {
        val run = habit("Run")
        mark(run, monday)
        mark(run, tuesday)

        open(run)
        assertEquals("Run", state().habit?.title)
        assertEquals(2, state().figures?.marks)
        assertEquals(monday.toEpochDay(), state().weekFrom)
        assertEquals(sunday.toEpochDay(), state().weekTo)
        assertTrue(state().isCurrentWeek)
        assertEquals(7, state().cells.size)
        assertTrue(cell(monday).marked)
        assertTrue("the day the card is anchored on is the day the keep button stands for", cell(wednesday).isToday)
        assertFalse(cell(wednesday).marked)
        assertTrue(cell(wednesday).canMark)
        // A Sunday that has not arrived is drawn — the schedule does ask for it — but the screen never offers
        // a mark SQLite would have to refuse.
        assertTrue(cell(sunday).asked)
        assertFalse(cell(sunday).canMark)
    }

    @Test
    fun `the grid asks on the days the schedule names and offers every day that has already arrived`() = runBlocking {
        val stretch = habit("Stretch", frequency = HabitFrequency.DAYS_OF_WEEK, days = listOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY))
        open(stretch)
        assertEquals(listOf(monday, wednesday), askedCells())
        // Tuesday is not asked for and is still markable: the repository's rule is that real work made on a
        // day the plan skipped is never refused, so a grid that greyed it out would say no for the user.
        assertEquals(listOf(monday, tuesday, wednesday), markableCells())
        assertFalse(cell(tuesday).asked)
        assertTrue(cell(tuesday).canMark)
    }

    @Test
    fun `a habit that has not begun yet shows a week nothing can be kept on`() = runBlocking {
        val future = habit("Starts later", start = nextMonday)
        open(future)
        assertEquals(7, state().cells.size)
        assertTrue(askedCells().isEmpty())
        assertTrue(markableCells().isEmpty())
        assertFalse(state().isMissing)
    }

    @Test
    fun `an id no row holds is reported missing rather than shown as an empty grid`() = runBlocking {
        viewModel.load(4242L)
        awaitUntil("the read to answer") { !state().isLoading }
        assertTrue(state().isMissing)
        assertNull(state().habit)
        assertNull(state().figures)
        assertTrue(state().cells.isEmpty())
    }

    @Test
    fun `another profile's habit is not readable through the id this device gave it`() = runBlocking {
        val otherId = fixture.userDao.insertUser(User(email = "other@example.com", name = "Another"))
        val theirs = fixture.habitDao.insertHabit(
            Habit(
                uuid = "habit-of-other", userId = otherId, title = "Their routine",
                frequency = HabitFrequency.DAILY, startDay = monday.toEpochDay(),
                createdAt = at(today, 9, 0), updatedAt = at(today, 9, 0)
            )
        )

        viewModel.load(theirs)
        awaitUntil("the read to answer") { !state().isLoading }
        assertTrue("a row the account does not hold has no grid to draw", state().isMissing)
        assertNull(state().habit)
        assertTrue(state().cells.isEmpty())
    }

    @Test
    fun `keeping a day from the grid lands on that day and redraws the cell`() = runBlocking {
        val run = habit("Run")
        open(run)
        assertEquals(emptyList<LocalDate>(), markedCells())

        saved(write("the kept day to be written") { cb -> viewModel.mark(tuesday.toEpochDay(), cb) })
        awaitUntil("the grid to show the kept day") { cell(tuesday).marked }
        assertEquals(listOf(tuesday), markedCells())
        assertEquals(1, state().figures?.marks)
        assertFalse("a day marked for Tuesday says nothing about today", state().isTodayMarked)
    }

    @Test
    fun `keeping today is the same write on the day the card is anchored on`() = runBlocking {
        val run = habit("Run")
        open(run)

        saved(write("today to be kept") { cb -> viewModel.markToday(cb) })
        awaitUntil("the card to read today as kept") { state().isTodayMarked }
        assertTrue(cell(wednesday).marked)
        assertTrue(state().figures!!.markedToday)
    }

    @Test
    fun `a day that has not arrived refuses itself the way the repository refuses it`() = runBlocking {
        val run = habit("Run")
        open(run)
        assertFalse(cell(sunday).canMark)

        val answer = rejected(write("the future day to be refused") { cb -> viewModel.mark(sunday.toEpochDay(), cb) })
        assertEquals(HabitRejection.DAY_IN_FUTURE, answer)
        assertFalse(cell(sunday).marked)
    }

    @Test
    fun `a day before the habit began refuses itself the same way`() = runBlocking {
        val run = habit("Run")
        open(run)

        val answer = rejected(
            write("the day outside the window to be refused") { cb -> viewModel.mark(previousFriday.toEpochDay(), cb) }
        )
        assertEquals(HabitRejection.DAY_OUTSIDE_WINDOW, answer)
        assertEquals(0, state().figures?.marks)
    }

    @Test
    fun `a day already kept refuses a second mark instead of standing twice`() = runBlocking {
        val run = habit("Run")
        open(run)
        saved(write("the first tap to be written") { cb -> viewModel.mark(tuesday.toEpochDay(), cb) })
        awaitUntil("the first tap to reach the grid") { cell(tuesday).marked }

        // The double tap this app's own screen can produce, answered by the unique index rather than by a
        // check that can race it.
        val second = rejected(write("the second tap to be refused") { cb -> viewModel.mark(tuesday.toEpochDay(), cb) })
        assertEquals(HabitRejection.ALREADY_MARKED, second)
        assertEquals(listOf(tuesday), markedCells())
        assertEquals(1, state().figures?.marks)
    }

    @Test
    fun `walking the grid back one week shows the earlier week and its marks`() = runBlocking {
        val run = habit("Run", start = previousMonday)
        mark(run, previousWednesday)
        open(run)
        assertTrue(markedCells().isEmpty())

        viewModel.shiftWeek(-1)
        awaitUntil("the earlier week to draw") { state().weekFrom == previousMonday.toEpochDay() }
        assertFalse(state().isCurrentWeek)
        assertEquals(listOf(previousWednesday), markedCells())
    }

    @Test
    fun `the grid cannot walk into a week that has not arrived`() = runBlocking {
        val run = habit("Run")
        open(run)

        // Forward past the current week is the one direction a grid must refuse: every day after it is a day
        // the repository will not accept a mark for, so the week would be drawn full of cells that all fail.
        viewModel.shiftWeek(1)
        fixture.awaitIdle()
        assertEquals(monday.toEpochDay(), state().weekFrom)
        assertTrue(state().isCurrentWeek)

        viewModel.shiftWeek(-1)
        awaitUntil("the week to step back") { !state().isCurrentWeek }
        assertEquals(previousMonday.toEpochDay(), state().weekFrom)

        viewModel.shiftWeek(1)
        awaitUntil("the step forward to return") { state().isCurrentWeek }
        assertEquals(monday.toEpochDay(), state().weekFrom)

        // Overshooting the edge lands on it rather than doing nothing: a walk with an end has to stop at the
        // end, not freeze where the user happened to be standing.
        viewModel.shiftWeek(-1)
        awaitUntil("the week to step back again") { !state().isCurrentWeek }
        viewModel.shiftWeek(3)
        awaitUntil("the clamp to bring the current week back") { state().isCurrentWeek }
        assertEquals(monday.toEpochDay(), state().weekFrom)
    }

    @Test
    fun `a correction with no new day takes the mark back and records why`() = runBlocking {
        val run = habit("Run")
        mark(run, tuesday)
        open(run)
        assertEquals(listOf(tuesday), markedCells())

        written(correct(tuesday, null, "I did not go"))
        awaitUntil("the grid to stop counting the day") { !cell(tuesday).marked }
        assertEquals(emptyList<LocalDate>(), markedCells())
        assertEquals(0, state().figures?.marks)

        val change = state().changes.single()
        assertEquals(tuesday.toEpochDay(), change.oldLocalDay)
        assertNull(change.newLocalDay)
        assertEquals("I did not go", change.reason)
    }

    @Test
    fun `a correction that names a day moves the mark and both days read as the user said`() = runBlocking {
        val run = habit("Run")
        mark(run, tuesday)
        open(run)

        written(correct(tuesday, wednesday, "it was Wednesday"))
        awaitUntil("the moved day to reach the grid") { cell(wednesday).marked }
        assertEquals(listOf(wednesday), markedCells())
        assertEquals(1, state().figures?.marks)

        val change = state().changes.single()
        assertEquals(tuesday.toEpochDay(), change.oldLocalDay)
        assertEquals(wednesday.toEpochDay(), change.newLocalDay)
        assertEquals("it was Wednesday", change.reason)
    }

    @Test
    fun `text that names no day is the form's mistake and the mark stands where it was`() = runBlocking {
        val run = habit("Run")
        mark(run, tuesday)
        open(run)

        var answer: HabitCorrectionOutcome? = null
        viewModel.correctMark(tuesday.toEpochDay(), "not a date", "an afterthought") { answer = it }
        assertTrue(answer is HabitCorrectionOutcome.UnreadableDay)

        // Nothing was written, so the day the user stood behind is still the day the grid shows and the
        // history holds no account of a correction that never happened.
        assertEquals(listOf(tuesday), markedCells())
        assertTrue(state().changes.isEmpty())
    }

    @Test
    fun `taking back a day that was never kept says so rather than pretending`() = runBlocking {
        val run = habit("Run")
        open(run)

        assertEquals(
            HabitRejection.NO_SUCH_MARK,
            rejected(written(correct(monday, null, null)))
        )
        assertTrue(state().changes.isEmpty())
    }

    @Test
    fun `a day moved onto a mark that already stands refuses the swap instead of destroying one`() = runBlocking {
        val run = habit("Run")
        mark(run, tuesday)
        mark(run, wednesday)
        open(run)
        assertEquals(listOf(tuesday, wednesday), markedCells())

        assertEquals(
            HabitRejection.TARGET_DAY_TAKEN,
            rejected(written(correct(tuesday, wednesday, null)))
        )
        // The refused swap left both marks exactly where the user put them.
        assertEquals(listOf(tuesday, wednesday), markedCells())
    }

    @Test
    fun `an off-schedule day kept on the grid is shown as kept without the week owing it`() = runBlocking {
        val stretch = habit(
            "Stretch",
            frequency = HabitFrequency.DAYS_OF_WEEK,
            days = listOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY)
        )
        open(stretch)

        saved(write("the Sunday-work day to be kept") { cb -> viewModel.mark(tuesday.toEpochDay(), cb) })
        awaitUntil("the unasked day to show as kept") { cell(tuesday).marked }
        assertFalse(cell(tuesday).asked)
        assertEquals(1, state().figures?.marks)
        // The percentage asks only about the days the plan named — Monday and Wednesday of the three days the
        // window covers — so the extra mark is work rather than a debt the schedule never carried.
        assertEquals(2, state().figures?.period?.asked)
        assertEquals(0, state().figures?.period?.kept)
    }

    @Test
    fun `a put-away habit stops being offered on the grid and refuses a fresh mark`() = runBlocking {
        val run = habit("Run")
        mark(run, monday)
        open(run)

        saved(write("the habit to be put away") { cb -> viewModel.setArchived(cb) })
        awaitUntil("the screen to read the habit as put away") { state().habit?.archivedAt != null }
        assertNotNull(state().habit?.archivedAt)
        assertTrue(markableCells().isEmpty())

        val refused = rejected(write("the fresh mark to be refused") { cb -> viewModel.mark(tuesday.toEpochDay(), cb) })
        assertEquals(HabitRejection.ARCHIVED, refused)

        // A correction is still allowed: the user reviewing an archived grid is fixing something they asserted
        // while the habit was live, and refusing that would leave a wrong mark where it can no longer be fixed.
        written(correct(monday, null, "that was a different walk"))
        awaitUntil("the archived grid to accept the correction") { !cell(monday).marked }
        assertEquals(listOf(monday), state().changes.map { LocalDate.ofEpochDay(it.oldLocalDay) })
    }

    @Test
    fun `putting the habit back brings the grid's days with it`() = runBlocking {
        val run = habit("Run")
        open(run)
        saved(write("the habit to be put away") { cb -> viewModel.setArchived(cb) })
        awaitUntil("the habit to be away") { state().habit?.archivedAt != null }
        assertTrue(markableCells().isEmpty())

        saved(write("the habit to be brought back") { cb -> viewModel.setArchived(cb) })
        awaitUntil("the grid to offer days again") { markableCells().isNotEmpty() }
        assertNull(state().habit?.archivedAt)
        assertTrue(cell(wednesday).canMark)
    }

    @Test
    fun `a habit that is gone cannot be kept, corrected, put away or thrown away again`() = runBlocking {
        val run = habit("Run")
        open(run)
        saved(write("the habit to be thrown away") { cb -> viewModel.remove(cb) })
        awaitUntil("the gone row to leave the screen") { state().isMissing }

        // Every command reads the row the screen holds, and the screen holds nothing once it is gone, so none
        // of them can write behind the reader's back.
        assertEquals(HabitRejection.NOT_FOUND, rejected(write("the refusal to keep") { cb -> viewModel.markToday(cb) }))
        assertEquals(
            HabitRejection.NOT_FOUND,
            rejected(written(correct(tuesday, null, null)))
        )
        assertEquals(HabitRejection.NOT_FOUND, rejected(write("the refusal to archive") { cb -> viewModel.setArchived(cb) }))
        assertEquals(HabitRejection.NOT_FOUND, rejected(write("the refusal to remove again") { cb -> viewModel.remove(cb) }))
    }

    @Test
    fun `opening the habit again leaves the week the user walked to where it was`() = runBlocking {
        val run = habit("Run", start = previousMonday)
        open(run)
        viewModel.shiftWeek(-1)
        awaitUntil("the earlier week to draw") { state().weekFrom == previousMonday.toEpochDay() }

        // A configuration change and a reminder's link both hand over the same id the screen is already
        // showing; re-asking for it must not yank the grid back to the current week mid-read.
        viewModel.load(run.id)
        fixture.awaitIdle()
        assertEquals(previousMonday.toEpochDay(), state().weekFrom)
        assertFalse(state().isCurrentWeek)
    }
}
