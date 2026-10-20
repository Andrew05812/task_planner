package com.taskplanner.ui.habits

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.taskplanner.data.calendar.CalendarMath
import com.taskplanner.data.model.Habit
import com.taskplanner.data.model.HabitFrequency
import com.taskplanner.data.model.User
import com.taskplanner.data.repository.HabitWriteResult
import com.taskplanner.testing.AppFixture
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The habit list: which read the screen holds, and what it shows while the days under a card move.
 *
 * The list keeps no counts and does no arithmetic, so the reading is all it has left to get wrong. A screen
 * that filtered the open list for the retired switch would print a put-away habit as still being asked for;
 * one that folded its own mark rows into a percentage would be a second author of a number SQLite already
 * answers, and would drift the first time a day is corrected elsewhere. So the tests below hold the list open
 * and write through the *repository*, then ask whether the card moved by itself.
 *
 * The window a card is read over is the screen's one figure question, and it is checked as a promise rather
 * than as a constant: the words beside a percentage name the four weeks the percentage counted, so the read
 * the state carries has to end on the day the state says it is.
 *
 * Two settings are pinned for the whole class, because both belong to the reader and the tests are about
 * which days fall in which week: the week is Monday-first, and the zone is one with a daylight-saving jump
 * inside the year, so "today" is one fixed day rather than whatever the host clock is on.
 */
@RunWith(RobolectricTestRunner::class)
class HabitsViewModelTest {

    private lateinit var defaultZone: TimeZone
    private lateinit var defaultLocale: Locale
    private lateinit var fixture: AppFixture
    private lateinit var viewModel: HabitsViewModel
    private var userId: Long = -1L

    private lateinit var zone: ZoneId
    private val monday: LocalDate = LocalDate.of(2026, 6, 1)
    private val tuesday: LocalDate = LocalDate.of(2026, 6, 2)
    private val wednesday: LocalDate = LocalDate.of(2026, 6, 3)
    private val today: LocalDate = wednesday

    /** A day inside the habit's own window but far outside the card's four weeks. */
    private val longAgo: LocalDate = LocalDate.of(2026, 4, 24)

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
        // viewModelScope needs Dispatchers.Main; an inline executor makes every collector run on the test
        // thread rather than wait for a looper nothing pumps.
        Dispatchers.setMain(Executor { it.run() }.asCoroutineDispatcher())
        userId = runBlocking { fixture.signIn().id }
        runBlocking { fixture.timeBase.shiftTo(at(today, 12, 0)) }
        viewModel = HabitsViewModel(fixture.habitRepository)
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

    private fun titles() = state().habits.map { it.habit.title }

    private fun card(title: String) = state().habits.first { it.habit.title == title }

    /** Waits for the list to answer, since Room publishes its first frame on its own thread. */
    private fun awaitLoaded() = awaitUntil("the habit list to arrive") { !state().isLoading }

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

    private fun saved(result: HabitWriteResult): String = when (result) {
        is HabitWriteResult.Saved -> result.habitUuid
        is HabitWriteResult.Rejected -> throw AssertionError("expected a saved habit, got ${result.reason}")
    }

    private suspend fun habit(
        title: String,
        frequency: HabitFrequency = HabitFrequency.DAILY,
        start: LocalDate = monday,
        days: List<DayOfWeek> = emptyList()
    ): Habit {
        val uuid = saved(
            fixture.habitRepository.create(
                title = title, frequency = frequency, startDay = start.toEpochDay(), daysOfWeek = days
            )
        )
        return fixture.habitRepository.habit(uuid)!!
    }

    private suspend fun mark(habit: Habit, day: LocalDate) {
        val result = fixture.habitRepository.mark(habit.uuid, day.toEpochDay())
        if (result is HabitWriteResult.Rejected) {
            throw AssertionError("marking $day refused: ${result.reason}")
        }
        // Two marks made in one parked millisecond would be indistinguishable in any order-sensitive read,
        // and the clock is parked for the whole test.
        fixture.timeBase.advance(1_000L)
    }

    @Test
    fun `the list arrives with each habit's figures already beside it`() = runBlocking {
        val run = habit("Run")
        mark(run, monday)
        mark(run, tuesday)

        awaitLoaded()
        assertEquals(listOf("Run"), titles())
        assertEquals(2, card("Run").figures.marks)
        assertEquals(2, card("Run").figures.thisWeek.kept)
        // Each card carries the day its figures were anchored on, because the caption beside a percentage has
        // to name the days the percentage actually covered.
        assertEquals(today.toEpochDay(), card("Run").figures.today)
    }

    @Test
    fun `a mark made elsewhere moves the card without the screen asking again`() = runBlocking {
        val run = habit("Run")
        awaitLoaded()
        assertEquals(0, card("Run").figures.marks)

        // Through the repository, not through the screen: the whole promise is that nothing on this list has
        // to be told a day was kept.
        mark(run, wednesday)
        awaitUntil("the kept day to reach the card") { card("Run").figures.markedToday }
        assertEquals(1, card("Run").figures.marks)
    }

    @Test
    fun `a mark taken back elsewhere takes its figure with it`() = runBlocking {
        val run = habit("Run")
        mark(run, tuesday)
        awaitLoaded()
        assertEquals(1, card("Run").figures.period.kept)

        fixture.habitRepository.takeBack(run.uuid, tuesday.toEpochDay(), "I did not go")
        awaitUntil("the walked-back day to leave the figure") { card("Run").figures.period.kept == 0 }
        assertEquals(0, card("Run").figures.marks)
    }

    @Test
    fun `the percentage is read over the card's window, not the habit's whole life`() = runBlocking {
        // A habit older than the window the list asks for, with one mark back in that older stretch and one
        // in the last four weeks: the card has to say "1 of 28" for the window it names rather than quietly
        // averaging in a month the caption does not mention.
        val run = habit("Long run", start = LocalDate.of(2026, 4, 4))
        mark(run, longAgo)
        mark(run, tuesday)

        awaitLoaded()
        assertEquals(1, card("Long run").figures.marks)
        assertEquals(1, card("Long run").figures.period.kept)
        assertEquals(28, card("Long run").figures.period.asked)
        assertEquals(28, card("Long run").figures.periodDays)
    }

    @Test
    fun `the window the figures name is the window the percentage was read over`() = runBlocking {
        // A habit begun two days before the day the list is read on cannot be judged over four weeks it never
        // lived through: the read cuts its own window at the habit's start, and the span it ended up covering
        // has to travel with the figures. A caption built from the length the screen *asked* for would print
        // "over the last 28 days" beside a percentage drawn from two.
        habit("Newest", start = tuesday)

        awaitLoaded()
        assertEquals(2, card("Newest").figures.periodDays)
        assertEquals(2, card("Newest").figures.period.asked)
    }

    @Test
    fun `the retired switch reads the retired list rather than filtering the open one`() = runBlocking {
        habit("Live routine")
        val retired = habit("Put-away routine")
        fixture.habitRepository.archive(retired.uuid)
        awaitLoaded()
        assertEquals(listOf("Live routine"), titles())

        viewModel.setShowArchived(true)
        awaitUntil("the retired list") { state().showArchived && !state().isLoading }
        assertEquals(listOf("Put-away routine"), titles())

        viewModel.setShowArchived(false)
        awaitUntil("the open list back") { !state().showArchived && !state().isLoading }
        assertEquals(listOf("Live routine"), titles())
    }

    @Test
    fun `a habit thrown away leaves the list and the empty state answers`() = runBlocking {
        val uuid = saved(fixture.habitRepository.create("Throwaway", HabitFrequency.DAILY, monday.toEpochDay()))
        awaitLoaded()
        assertEquals(1, state().habits.size)

        fixture.habitRepository.remove(uuid)
        awaitUntil("the habit to leave the list") { state().habits.isEmpty() }
        assertFalse(state().isLoading)
        assertTrue("an emptied list is an answer, not a screen still waiting", state().isEmpty)
    }

    @Test
    fun `a profile with no habits is an empty list, not a screen still waiting`() {
        awaitLoaded()
        assertTrue(state().isEmpty)
        assertEquals(emptyList<Any>(), state().habits)
    }

    @Test
    fun `another profile's habits are not in this list`() = runBlocking {
        habit("My routine")
        // A row written under a different account, put on disk directly: the isolation the list has to show
        // is the one the read enforces, and a screen that asked for every habit would print this one under
        // the wrong profile.
        val otherId = fixture.userDao.insertUser(User(email = "other@example.com", name = "Another"))
        fixture.habitDao.insertHabit(
            Habit(
                uuid = "habit-of-other", userId = otherId, title = "Their routine",
                frequency = HabitFrequency.DAILY, startDay = monday.toEpochDay(),
                createdAt = at(today, 9, 0), updatedAt = at(today, 9, 0)
            )
        )

        awaitLoaded()
        assertEquals(listOf("My routine"), titles())
        assertEquals(1, state().habits.size)
    }

    @Test
    fun `an off-schedule day is kept without pretending the habit asked for it`() = runBlocking {
        // The list reads both questions apart: the mark is work that happened, while the day the schedule
        // never named cannot be added to what was owed. A screen that counted rows for the percentage would
        // report a Sunday run as a kept weekday.
        val stretch = habit("Stretch", frequency = HabitFrequency.DAYS_OF_WEEK, days = listOf(DayOfWeek.MONDAY))
        mark(stretch, monday)
        mark(stretch, tuesday)

        awaitLoaded()
        assertEquals(2, card("Stretch").figures.marks)
        assertEquals(1, card("Stretch").figures.period.kept)
        assertEquals(1, card("Stretch").figures.period.asked)
    }
}
