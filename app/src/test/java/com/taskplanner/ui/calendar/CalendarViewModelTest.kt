package com.taskplanner.ui.calendar

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.taskplanner.data.calendar.CalendarAggregator
import com.taskplanner.data.calendar.CalendarMath
import com.taskplanner.data.model.SyncState
import com.taskplanner.data.model.Task
import com.taskplanner.testing.AppFixture
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The calendar screen's behaviour, against real storage.
 *
 * It answers the questions a calendar can only answer with the rows a profile actually has: whether
 * the page on screen is the page the user asked for, whether the grid comes back by itself after a
 * write, and whether a drag that was refused changed nothing at all. The screen never reloads
 * anything, so every refresh asserted here has to have arrived through the repository's own flow.
 */
@RunWith(RobolectricTestRunner::class)
class CalendarViewModelTest {

    private lateinit var fixture: AppFixture
    private lateinit var viewModel: CalendarViewModel
    private val zone: ZoneId = CalendarMath.systemZone()
    private val today: LocalDate = LocalDate.now(zone)
    private var userId: Long = 0

    @Before
    fun setUp() {
        fixture = AppFixture(ApplicationProvider.getApplicationContext())
        // viewModelScope needs Dispatchers.Main; an inline executor keeps every collector on the test
        // thread instead of waiting for a looper that nothing pumps.
        Dispatchers.setMain(Executor { it.run() }.asCoroutineDispatcher())
        userId = runBlocking { fixture.signIn(email = "calendar-screen@example.com").id }
        viewModel = CalendarViewModel(fixture.calendarRepository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        fixture.close()
    }

    private val state: CalendarState get() = viewModel.state.value

    private fun instant(date: LocalDate, wallClock: String): Long =
        LocalDateTime.parse("${date}T$wallClock").atZone(zone).toInstant().toEpochMilli()

    private fun insert(
        title: String,
        due: Pair<LocalDate, String>? = null,
        start: Pair<LocalDate, String>? = null,
        end: Pair<LocalDate, String>? = null,
        estimate: Int? = null,
        categoryId: Long? = null,
        asProfile: Long = userId
    ): Long = runBlocking {
        fixture.taskDao.insertTask(
            Task(
                uuid = "uuid-$title",
                title = title,
                description = "",
                dueDate = due?.let { instant(it.first, it.second) },
                startTime = start?.let { instant(it.first, it.second) },
                endTime = end?.let { instant(it.first, it.second) },
                estimatedMinutes = estimate,
                categoryId = categoryId,
                userId = asProfile
            )
        )
    }

    private fun cellOn(date: LocalDate): CalendarAggregator.DayCell =
        state.cells.first { it.date == date }

    private fun titlesOn(date: LocalDate): List<String> =
        state.cells.firstOrNull { it.date == date }?.tasks?.map { it.title }.orEmpty()

    private fun stored(title: String, profile: Long = userId): Task = runBlocking {
        fixture.taskDao.getAllRowsOnce(profile).first { it.title == title }
    }

    /** Room notifies flow collectors from an executor, so a test waits for the emission it asked for. */
    private fun awaitUntil(what: String, condition: () -> Boolean) {
        // Room answers on its own threads, so the wait starts by letting them settle: without that the
        // first check can be satisfied by the state a previous command published.
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
     * Waits for the mode's own grid to have arrived, sized the way that mode draws it. A screen that
     * had settled on the wrong page would otherwise pass by counting whatever it was left holding.
     */
    private fun awaitMode(mode: CalendarMode) {
        awaitUntil("the screen to settle on $mode") {
            val current = state
            val expected = when (mode) {
                CalendarMode.MONTH -> CalendarMath.monthGrid(YearMonth.from(current.anchor)).size
                CalendarMode.WEEK -> CalendarMath.weekGrid(current.anchor).size
                CalendarMode.DAY -> 1
            }
            current.mode == mode && current.cells.size == expected
        }
    }

    // ---- the page on screen -------------------------------------------------

    @Test
    fun `the month page is whole weeks and knows which days it borrowed`() {
        awaitMode(CalendarMode.MONTH)

        val cells = state.cells
        assertEquals(
            "the grid opens on the day the device's locale says the week opens on",
            CalendarMath.firstDayOfWeek(),
            cells.first().date.dayOfWeek
        )
        assertEquals("whole weeks only", 0, cells.size % 7)
        assertEquals(YearMonth.from(today), YearMonth.from(state.anchor))
        assertEquals(
            "every day of the month is on it, and no borrowed day is claimed as its own",
            today.lengthOfMonth(),
            cells.count { it.inDisplayedMonth }
        )
        assertTrue(
            cells.filter { !it.inDisplayedMonth }
                .all { YearMonth.from(it.date) != YearMonth.from(today) }
        )
        assertEquals(today, cells.single { it.isToday }.date)
    }

    @Test
    fun `the week page holds the seven days around the anchor and has no month to belong to`() {
        viewModel.setMode(CalendarMode.WEEK)
        awaitMode(CalendarMode.WEEK)

        assertEquals(CalendarMath.weekGrid(state.anchor), state.cells.map { it.date })
        assertEquals(CalendarMath.firstDayOfWeek(), state.cells.first().date.dayOfWeek)
        assertTrue(
            "a week is not a month's neighbourhood, so no day of it is a background day",
            state.cells.all { it.inDisplayedMonth }
        )
    }

    @Test
    fun `the day page holds the day it is reading and that day's column`() {
        insert("Dentist", start = today to "09:00", end = today to "09:45")
        viewModel.setMode(CalendarMode.DAY)
        awaitMode(CalendarMode.DAY)

        assertEquals(listOf(today), state.cells.map { it.date })
        assertEquals(today, state.dayPlan?.date)
        assertEquals(
            listOf(9 * 60 to 9 * 60 + 45),
            state.dayPlan?.blocks?.map { it.startMinute to it.endMinute }
        )
        assertEquals(
            "the cell the day view counts from is the selected day's own",
            today,
            state.selectedCell?.date
        )
    }

    @Test
    fun `zooming keeps the day being read and moves the page onto it`() {
        awaitMode(CalendarMode.MONTH)
        val farDay = state.cells.last { it.inDisplayedMonth }.date
        viewModel.select(farDay)
        awaitUntil("the tap to be picked up") { state.selected == farDay }

        viewModel.setMode(CalendarMode.WEEK)
        awaitMode(CalendarMode.WEEK)
        assertEquals(
            "a week that did not hold the day being read would leave the user looking for it",
            farDay,
            state.selected
        )
        assertTrue(state.cells.map { it.date }.contains(farDay))

        viewModel.setMode(CalendarMode.DAY)
        awaitMode(CalendarMode.DAY)
        assertEquals(listOf(farDay), state.cells.map { it.date })

        viewModel.setMode(CalendarMode.MONTH)
        awaitMode(CalendarMode.MONTH)
        assertEquals(farDay, state.selected)
        assertEquals(YearMonth.from(farDay), YearMonth.from(state.anchor))
    }

    @Test
    fun `tapping a day moves the day being read without paging the month`() {
        awaitMode(CalendarMode.MONTH)
        val day = state.cells.first { it.inDisplayedMonth && it.date != today }.date

        viewModel.select(day)
        awaitUntil("the column to follow the tap") {
            state.selected == day && state.dayPlan?.date == day
        }

        assertEquals(
            "the user tapped a day, not a page",
            YearMonth.from(today),
            YearMonth.from(state.anchor)
        )
        assertEquals(day, cellOn(day).date)
    }

    @Test
    fun `paging a month replaces its grid instead of stacking a second one on it`() {
        awaitMode(CalendarMode.MONTH)
        val before = state.cells.map { it.date }

        viewModel.next()
        awaitUntil("the page to move") { state.cells.map { it.date } != before }

        val after = state.cells.map { it.date }
        assertEquals("the same shape, one month along", before.size, after.size)
        assertEquals(after.size, after.distinct().size)
        assertTrue(after.first().isAfter(before.first()))
        assertEquals(YearMonth.from(today).plusMonths(1), YearMonth.from(state.anchor))
    }

    // ---- navigation ---------------------------------------------------------

    @Test
    fun `next and today page a month by whole months`() {
        awaitMode(CalendarMode.MONTH)
        val selected = state.selected

        viewModel.next()
        awaitUntil("the next month to arrive") { YearMonth.from(state.anchor) == YearMonth.from(today).plusMonths(1) }
        assertEquals(
            "the month on screen is the one the cells belong to",
            YearMonth.from(today).plusMonths(1),
            YearMonth.from(state.cells.first { it.inDisplayedMonth }.date)
        )
        assertEquals(
            "the day being read stays readable while the pages move",
            selected,
            state.selected
        )

        viewModel.previous()
        awaitUntil("the page to come back") { YearMonth.from(state.anchor) == YearMonth.from(today) }

        viewModel.next()
        viewModel.goToday()
        awaitUntil("today to be on screen again") { state.anchor == today && state.selected == today }
        assertEquals(1, state.cells.count { it.isToday })
    }

    @Test
    fun `next pages a week by seven days`() {
        viewModel.setMode(CalendarMode.WEEK)
        awaitMode(CalendarMode.WEEK)
        val anchor = state.anchor

        viewModel.next()
        awaitUntil("the next week to arrive") { state.anchor == anchor.plusWeeks(1) }
        assertEquals(CalendarMath.weekGrid(anchor.plusWeeks(1)), state.cells.map { it.date })

        viewModel.previous()
        awaitUntil("the week to come back") { state.anchor == anchor }
    }

    @Test
    fun `next pages a day one day at a time and takes the selection with it`() {
        viewModel.setMode(CalendarMode.DAY)
        awaitMode(CalendarMode.DAY)

        viewModel.next()
        awaitUntil("the next day's column") { state.selected == today.plusDays(1) }
        assertEquals(
            "in the day view the day being read is the page, so they move together",
            listOf(today.plusDays(1)),
            state.cells.map { it.date }
        )
        assertEquals(
            "and the column is the new day's, not the one it came from",
            today.plusDays(1),
            state.dayPlan?.date
        )

        viewModel.previous()
        viewModel.previous()
        awaitUntil("the day before today") { state.selected == today.minusDays(1) }
    }

    // ---- live refresh -------------------------------------------------------

    @Test
    fun `a row that arrives from anywhere shows up in the grid by itself`() {
        awaitMode(CalendarMode.MONTH)
        val day = state.cells.first { it.inDisplayedMonth }.date

        insert("Pulled from the cloud", due = day to "10:00")

        awaitUntil("the cell to fill without a reload") { titlesOn(day) == listOf("Pulled from the cloud") }
        val cell = cellOn(day)
        assertEquals(
            "a task nobody filed stays unfiled: no category is invented to fill the cell",
            1,
            cell.unassignedCount
        )
        assertEquals(emptyList<String>(), cell.categories.map { it.name })
    }

    @Test
    fun `the grid repaints itself after a drag instead of the screen reloading the month`() {
        viewModel.setMode(CalendarMode.WEEK)
        awaitMode(CalendarMode.WEEK)
        val days = state.cells.map { it.date }
        val to = days.first { it != today }
        val id = insert("Standup", due = today to "09:00")

        viewModel.moveTask(id, to)

        awaitUntil("the day to change") { titlesOn(to) == listOf("Standup") }
        assertEquals("and the day it left is empty again", emptyList<String>(), titlesOn(today))
    }

    @Test
    fun `the day column re-emits when a task is dragged into it`() {
        viewModel.setMode(CalendarMode.WEEK)
        awaitMode(CalendarMode.WEEK)
        val to = state.cells.map { it.date }.first { it != today }
        val id = insert("Shift", start = today to "20:00", end = today to "21:00")

        awaitUntil("the column to show the block") {
            state.selected == today && state.dayPlan?.blocks?.singleOrNull()?.task?.title == "Shift"
        }

        viewModel.select(to)
        awaitUntil("the column to follow the selection") { state.dayPlan?.date == to }
        assertTrue(
            "the day it belongs to now is empty of it",
            state.dayPlan?.blocks.orEmpty().none { it.task.title == "Shift" }
        )

        viewModel.moveTask(id, to)
        awaitUntil("the block to land in the column") {
            state.dayPlan?.blocks?.singleOrNull()?.task?.title == "Shift"
        }
        assertEquals(
            "at the clock time it left with, not the minute a day of milliseconds would have put it",
            20 * 60,
            state.dayPlan?.blocks?.single()?.startMinute
        )
    }

    // ---- drag and drop ------------------------------------------------------

    @Test
    fun `a drag through the screen moves the row it showed instead of planting a second task`() {
        viewModel.setMode(CalendarMode.WEEK)
        awaitMode(CalendarMode.WEEK)
        val days = state.cells.map { it.date }
        val id = insert("Doctor", due = days.first() to "14:00")

        viewModel.moveTask(id, days.last())

        awaitUntil("the day to change") { titlesOn(days.last()) == listOf("Doctor") }
        val rows = runBlocking { fixture.taskDao.getAllRowsOnce(userId) }
        assertEquals("exactly one row, the one that was already there", 1, rows.size)
        assertEquals(id, rows.single().id)
        assertEquals(
            "the deadline moved to the day it was dropped on and kept its clock time",
            listOf(days.last(), 14 * 60),
            listOf(
                CalendarMath.toLocalDate(rows.single().dueDate!!, zone),
                CalendarMath.minutesOfDay(rows.single().dueDate!!, zone)
            )
        )
        assertEquals(
            "and the row is queued for the cloud rather than being a fresh row nobody asked for",
            SyncState.PENDING,
            rows.single().syncState
        )
        assertEquals("Doctor", rows.single().title)
        assertEquals("uuid-Doctor", rows.single().uuid)
    }

    @Test
    fun `a drag carries the lead times to the day the task moved to`() {
        viewModel.setMode(CalendarMode.WEEK)
        awaitMode(CalendarMode.WEEK)
        val days = state.cells.map { it.date }
        val id = insert("Renew passport", due = days.first() to "10:00")
        val before = stored("Renew passport")
        runBlocking { fixture.reminderRepository.applyReminders(before, listOf(60)) }

        viewModel.moveTask(id, days.last())

        awaitUntil("the reminder to follow the deadline") {
            val moved = stored("Renew passport")
            moved.dueDate == instant(days.last(), "10:00") &&
                runBlocking { fixture.reminderDao.getLiveForTask(moved.uuid) }
                    .singleOrNull()?.triggerAt == moved.dueDate!! - MINUTE * 60
        }
        assertEquals(
            "one reminder row, re-pointed rather than duplicated",
            1,
            runBlocking { fixture.reminderDao.getLiveForTask(before.uuid) }.size
        )
    }

    @Test
    fun `a refused drag says so once and leaves the day exactly as it was`() {
        viewModel.setMode(CalendarMode.WEEK)
        awaitMode(CalendarMode.WEEK)
        val days = state.cells.map { it.date }
        val otherProfile = runBlocking { fixture.signIn(email = "someone-else@example.com").id }
        val foreign = insert("Theirs", due = days.first() to "10:00", asProfile = otherProfile)
        runBlocking { fixture.switchTo(fixture.userDao.getUserById(userId)!!) }
        awaitUntil("the grid to be the current profile's again") {
            titlesOn(days.first()) == emptyList<String>()
        }

        viewModel.moveTask(foreign, days.last())

        awaitUntil("the screen to be told it did not move") { state.dragRefused }
        assertEquals(
            "the other profile's task stays on the day it was on",
            days.first(),
            CalendarMath.toLocalDate(stored("Theirs", otherProfile).dueDate!!, zone)
        )
        assertEquals(emptyList<String>(), titlesOn(days.last()))

        viewModel.clearDragRefused()
        assertFalse(state.dragRefused)
    }

    @Test
    fun `a task with no instant is nowhere on the calendar and cannot be dragged`() {
        val id = insert("Someday maybe")
        awaitMode(CalendarMode.MONTH)

        assertEquals(
            "a task nobody placed in time does not get a day handed to it",
            0,
            state.cells.sumOf { it.tasks.size }
        )

        viewModel.moveTask(id, state.cells.last().date)

        awaitUntil("the refusal") { state.dragRefused }
        assertNull(stored("Someday maybe").dueDate)
        assertEquals(0, state.cells.sumOf { it.tasks.size })
    }

    @Test
    fun `the grid a signed-out session sees holds nobody's plans`() {
        insert("Belonging to someone", due = today to "09:00")
        awaitUntil("the grid to hold it") { state.cells.sumOf { it.tasks.size } == 1 }

        runBlocking { fixture.sessionRepository.endSession() }

        awaitUntil("the grid to empty") {
            state.cells.isNotEmpty() && state.cells.sumOf { it.tasks.size } == 0
        }
        assertEquals(
            "the page is still drawn, and there is nothing in it",
            CalendarMath.monthGrid(YearMonth.from(today)).size,
            state.cells.size
        )
        assertTrue(
            "the column is the day's own, empty",
            state.dayPlan?.blocks?.isEmpty() == true
        )
    }

    private companion object {
        const val MINUTE = 60_000L
    }
}
