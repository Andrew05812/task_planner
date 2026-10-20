package com.taskplanner.ui.calendar

import android.content.ClipData
import android.content.Context
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.test.core.app.ApplicationProvider
import com.taskplanner.R
import com.taskplanner.data.calendar.CalendarAggregator
import com.taskplanner.data.calendar.CalendarMath
import com.taskplanner.data.model.Task
import com.taskplanner.databinding.FragmentCalendarBinding
import com.taskplanner.databinding.ItemCalendarDayBinding
import com.taskplanner.testing.AppFixture
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.runBlocking
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
 * The calendar as it is drawn, on the narrowest screen the app supports and against the rows a profile
 * really has.
 *
 * A calendar lies in pixels before it lies in numbers: a day that shows three of five tasks without
 * saying so, a borrowed day from the neighbouring month drawn as though it belonged, a task that was
 * never given a length drawn as a block, two meetings stacked so one hides the other. Each of those is
 * an assertion about a view here, made from a state built by the repository itself rather than from a
 * hand-written fake, because the drawing has to be right about the cells the data layer actually hands
 * out.
 *
 * Days ahead of today are used wherever a test could otherwise read the clock: a task due at eight in
 * the morning is overdue in the afternoon and not in the morning, and a screen test that passes only
 * before lunch is not a test.
 */
@RunWith(RobolectricTestRunner::class)
class CalendarScreenTest {

    private lateinit var context: Context
    private lateinit var fixture: AppFixture
    private lateinit var binding: FragmentCalendarBinding
    private lateinit var controller: CalendarViewController
    private val zone: ZoneId = CalendarMath.systemZone()
    private val today: LocalDate = LocalDate.now(zone)
    private val later: LocalDate = today.plusDays(5)
    private val locale: Locale = Locale.getDefault()

    private val opened = mutableListOf<Task>()
    private val dropped = mutableListOf<Pair<Long, LocalDate>>()
    private val selected = mutableListOf<LocalDate>()
    private val modes = mutableListOf<CalendarMode>()
    private val messages = mutableListOf<CharSequence>()
    private var pages = 0
    private var todayClicks = 0
    private var userId: Long = 0

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        fixture = AppFixture(context)
        userId = runBlocking { fixture.signIn(email = "calendar-drawn@example.com").id }
        // The fragment always lives inside the activity's theme; without it ?attr/colorPrimary and the
        // chip styles do not resolve, which is not how the screen is used.
        val themed = ContextThemeWrapper(context, R.style.Theme_TaskPlanner)
        binding = FragmentCalendarBinding.inflate(LayoutInflater.from(themed))
        controller = CalendarViewController(
            binding = binding,
            onTaskClick = { opened.add(it) },
            onDropTask = { taskId, date -> dropped.add(taskId to date) },
            onSelect = { selected.add(it) },
            onModeSelected = { modes.add(it) },
            onPageBack = { pages-- },
            onPageForward = { pages++ },
            onToday = { todayClicks++ },
            onMessage = { messages.add(it) }
        )
        controller.bindControls()
    }

    @After
    fun tearDown() {
        fixture.close()
    }

    // ---- building the screen's world ----------------------------------------

    private fun instant(date: LocalDate, wallClock: String): Long =
        LocalDateTime.parse("${date}T$wallClock").atZone(zone).toInstant().toEpochMilli()

    private fun insert(
        title: String,
        due: Pair<LocalDate, String>? = null,
        start: Pair<LocalDate, String>? = null,
        end: Pair<LocalDate, String>? = null,
        estimate: Int? = null,
        categoryId: Long? = null
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
                userId = userId
            )
        )
    }

    /** The state as the repository computes it for one screen choice: the drawing is tested on real cells. */
    private fun state(
        mode: CalendarMode,
        anchor: LocalDate = today,
        selected: LocalDate = today
    ): CalendarState = runBlocking {
        val grid = when (mode) {
            CalendarMode.MONTH -> CalendarMath.monthGrid(YearMonth.from(anchor))
            CalendarMode.WEEK -> CalendarMath.weekGrid(anchor)
            CalendarMode.DAY -> listOf(selected)
        }
        val month = if (mode == CalendarMode.MONTH) YearMonth.from(anchor) else null
        CalendarState(
            mode = mode,
            anchor = anchor,
            selected = selected,
            cells = fixture.calendarRepository.cellsOnce(grid, month),
            dayPlan = fixture.calendarRepository.dayOnce(selected)
        )
    }

    private fun cellFor(date: LocalDate): CalendarAggregator.DayCell =
        runBlocking { fixture.calendarRepository.cellsOnce(listOf(date), null).single() }

    private fun monthCells(): List<CalendarAggregator.DayCell> = runBlocking {
        fixture.calendarRepository.cellsOnce(
            CalendarMath.monthGrid(YearMonth.from(today)),
            YearMonth.from(today)
        )
    }

    // ---- reading what was drawn ---------------------------------------------

    /** Binds one cell the way the grid does, and hands back its views. */
    private fun bindCell(
        cell: CalendarAggregator.DayCell,
        mode: CalendarMode = CalendarMode.MONTH,
        selected: LocalDate = cell.date
    ): ItemCalendarDayBinding {
        val holder = controller.cellAdapter.onCreateViewHolder(binding.calendarGrid, 0)
        holder.bind(CalendarCellItem(cell, mode, selected))
        return holder.binding
    }

    private fun chipsIn(cellBinding: ItemCalendarDayBinding): List<View> =
        (0 until cellBinding.cellTasks.childCount).map { cellBinding.cellTasks.getChildAt(it) }

    private fun titleOf(chip: View): String =
        chip.findViewById<TextView>(R.id.taskTitle).text.toString()

    private fun colorOf(chip: View): Int = chip.findViewById<TextView>(R.id.taskTitle).currentTextColor

    private fun dotVisible(chip: View): Boolean =
        chip.findViewById<View>(R.id.taskDot).isVisible

    /**
     * The 320dp screen the app has to survive.
     *
     * Measured and laid out twice because the second pass is the one that reads back what the first
     * put on screen — a live repaint from a layout listener would otherwise be invisible here.
     */
    private fun laidOut(): Int {
        val metrics = binding.root.resources.displayMetrics
        val width = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 320f, metrics).toInt()
        val height = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 640f, metrics).toInt()
        repeat(2) {
            binding.root.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
            )
            binding.root.layout(0, 0, width, height)
        }
        return width
    }

    private fun render(state: CalendarState): Int {
        controller.render(state)
        return laidOut()
    }

    private fun dp(value: Int): Int = (value * binding.root.resources.displayMetrics.density).toInt()

    /**
     * Every block and marker the day column drew, read from the structure the screen is required to
     * build: the column holds one row per cluster of colliding tasks, and each row is split into the
     * lanes those collisions need.
     */
    private fun blockViews(): List<View> =
        (1 until binding.dayLane.childCount).flatMap { row ->
            val cluster = binding.dayLane.getChildAt(row) as ViewGroup
            (0 until cluster.childCount).flatMap { lane ->
                val laneView = cluster.getChildAt(lane) as ViewGroup
                (0 until laneView.childCount).map { laneView.getChildAt(it) }
            }
        }

    /** A block of the day column, found by the task it draws. */
    private fun dayView(title: String): View =
        blockViews().first {
            it.findViewById<TextView>(R.id.blockTitle)?.text?.toString() == title ||
                it.findViewById<TextView>(R.id.markerTitle)?.text?.toString() == title
        }

    private fun paramsOf(view: View): FrameLayout.LayoutParams =
        view.layoutParams as FrameLayout.LayoutParams

    /** A view's own x range in the window, because a nested child's `left` is only relative to its parent. */
    private fun absoluteLeft(view: View): Int {
        var total = view.left
        var current: Any? = view.parent
        while (current is View) {
            total += current.left - current.scrollX
            current = current.parent
        }
        return total
    }

    private fun absoluteTop(view: View): Int {
        var total = view.top
        var current: Any? = view.parent
        while (current is View) {
            total += current.top - current.scrollY
            current = current.parent
        }
        return total
    }

    private fun absoluteRight(view: View): Int = absoluteLeft(view) + view.width

    /** How far down the hour column a block begins, which is the only place a minute means anything. */
    private fun topInColumn(view: View): Int = absoluteTop(view) - absoluteTop(binding.dayLane)

    /** How far across the hour column a block begins, which is the only place a lane means anything. */
    private fun leftInColumn(view: View): Int = absoluteLeft(view) - absoluteLeft(binding.dayLane)

    private fun assertInside(width: Int, vararg views: View) {
        views.forEach { view ->
            assertTrue(
                "${name(view)} is off the 320dp screen",
                absoluteLeft(view) >= 0 && absoluteRight(view) <= width
            )
        }
    }

    private fun name(view: View): String =
        if (view.id == View.NO_ID) "unnamed ${view.javaClass.simpleName}"
        else view.resources.getResourceName(view.id)

    private fun timeText(date: LocalDate, hour: Int, minute: Int): String =
        DateFormat.getTimeFormat(context).format(
            Date.from(date.atTime(hour, minute).atZone(zone).toInstant())
        )

    private fun dayShort(day: LocalDate): String =
        "${day.month.getDisplayName(TextStyle.SHORT, locale)} ${day.dayOfMonth}"

    private fun dayRes(day: DayOfWeek): Int = when (day) {
        DayOfWeek.MONDAY -> R.string.day_short_mon
        DayOfWeek.TUESDAY -> R.string.day_short_tue
        DayOfWeek.WEDNESDAY -> R.string.day_short_wed
        DayOfWeek.THURSDAY -> R.string.day_short_thu
        DayOfWeek.FRIDAY -> R.string.day_short_fri
        DayOfWeek.SATURDAY -> R.string.day_short_sat
        DayOfWeek.SUNDAY -> R.string.day_short_sun
    }

    // ---- a day cell's contents ----------------------------------------------

    @Test
    fun `a month cell colours its tasks by category and counts what it left out`() {
        val home = runBlocking { fixture.addCategory(userId, "Home", color = HOME_COLOR) }
        val work = runBlocking { fixture.addCategory(userId, "Work", color = WORK_COLOR) }
        insert("Bin bags", due = later to "08:00", categoryId = home)
        insert("Hoover", due = later to "09:00", categoryId = home)
        insert("Report", due = later to "10:00", categoryId = work)
        insert("Unfiled", due = later to "11:00")
        insert("Also unfiled", due = later to "12:00")

        val cellBinding = bindCell(cellFor(later))
        val chips = chipsIn(cellBinding)

        assertEquals(
            "a month column is narrow, so three titles is the most it can promise",
            3,
            chips.size
        )
        assertEquals(
            "in the order the day's column reads them, earliest first",
            listOf("Bin bags", "Hoover", "Report"),
            chips.map { titleOf(it) }
        )
        assertEquals(
            "each chip wears the colour of the category its count came from",
            listOf(HOME_COLOR, HOME_COLOR, WORK_COLOR),
            chips.map { colorOf(it) }
        )
        assertTrue(cellBinding.cellMore.isVisible)
        assertEquals("+2 more", cellBinding.cellMore.text.toString())
        assertTrue(cellBinding.cellNote.isVisible)
        assertEquals(
            "the two nobody filed are counted rather than quietly left off",
            context.getString(R.string.calendar_unassigned_format, 2),
            cellBinding.cellNote.text.toString()
        )
        assertFalse(
            "nothing on a day five days away has passed its deadline, so the cell does not say otherwise",
            cellBinding.cellNote.text.contains(context.getString(R.string.calendar_overdue_format, 1))
        )
        assertFalse(cellBinding.cellFullDay.isVisible)
    }

    @Test
    fun `a task with no category gets no colour of its own`() {
        insert("Unfiled", due = later to "09:00")

        val chip = chipsIn(bindCell(cellFor(later))).single()

        assertFalse("no dot, because no category promised one", dotVisible(chip))
        assertEquals(context.getColor(R.color.on_surface), colorOf(chip))
    }

    @Test
    fun `a cell counts the tasks already past their deadline`() {
        val home = runBlocking { fixture.addCategory(userId, "Home", color = HOME_COLOR) }
        insert("Missed", due = today.minusDays(1) to "10:00", categoryId = home)
        insert("Also missed", due = today.minusDays(1) to "11:00", categoryId = home)

        val cellBinding = bindCell(cellFor(today.minusDays(1)))

        assertEquals(
            context.getString(R.string.calendar_overdue_format, 2),
            cellBinding.cellNote.text.toString()
        )
        assertTrue(cellBinding.cellNote.isVisible)
    }

    @Test
    fun `a full day says so and a borrowed day from the neighbouring month steps back`() {
        (0 until 7).forEach { index ->
            insert(
                "Shift $index",
                start = later to "%02d:00".format(index + 9),
                end = later to "%02d:00".format(index + 10)
            )
        }

        assertTrue(
            "a day of seven hours of work is announced as full rather than left to the count",
            bindCell(cellFor(later)).cellFullDay.isVisible
        )

        val month = monthCells()
        val borrowed = month.first { !it.inDisplayedMonth }
        val own = month.first { it.inDisplayedMonth && it.date != today }
        assertEquals(NEIGHBOUR_ALPHA, bindCell(borrowed).root.alpha, 0f)
        assertEquals(1f, bindCell(own).root.alpha, 0f)
    }

    @Test
    fun `today is ringed and the day being read is the day that is filled`() {
        val cells = monthCells()

        val todayCell = bindCell(cells.first { it.isToday })
        assertNotNull(
            "the ring is drawn as a foreground so it survives whatever else the cell wears",
            todayCell.root.foreground
        )
        assertNull(bindCell(cells.first { !it.isToday }).root.foreground)

        val read = cells.first { it.inDisplayedMonth && it.date != today }
        assertTrue(bindCell(read, selected = read.date).root.isSelected)
        assertFalse(bindCell(read, selected = today).root.isSelected)
    }

    @Test
    fun `a week cell names the month it belongs to and a month cell does not`() {
        val cell = cellFor(later)

        assertEquals(
            "a week page shows one week, so the month has to be written where the day number cannot",
            cell.date.month.getDisplayName(TextStyle.SHORT, locale),
            bindCell(cell, CalendarMode.WEEK).cellMonthLabel.text.toString()
        )
        assertTrue(bindCell(cell, CalendarMode.WEEK).cellMonthLabel.isVisible)
        assertFalse(bindCell(cell, CalendarMode.MONTH).cellMonthLabel.isVisible)
    }

    // ---- the day column -----------------------------------------------------

    @Test
    fun `the day column puts a block at its own minutes and a task with no length on a line`() {
        insert("Dentist", start = later to "09:00", end = later to "09:45")
        insert("Call the bank", due = later to "10:00")

        val width = render(state(CalendarMode.DAY, anchor = later, selected = later))
        assertTrue(width > 0)

        val hour = dp(HOUR_HEIGHT_DP)
        val dentist = dayView("Dentist")
        assertEquals(
            "a block sits at the minute its user named",
            9 * hour,
            topInColumn(dentist)
        )
        assertEquals(
            "and is as tall as the time it was given, the forty-five minutes and not one more",
            45 * hour / 60,
            paramsOf(dentist).height
        )
        assertEquals(
            "which is what the column actually drew, not only what was asked of it",
            45 * hour / 60,
            dentist.height
        )
        assertEquals(
            context.getString(
                R.string.calendar_block_time_format,
                timeText(later, 9, 0),
                timeText(later, 9, 45)
            ),
            dentist.findViewById<TextView>(R.id.blockTime).text.toString()
        )
        assertEquals(
            "a task nobody filed is drawn in the plain text, not in a borrowed colour",
            context.getColor(R.color.on_surface),
            dentist.findViewById<TextView>(R.id.blockTitle).currentTextColor
        )

        val marker = dayView("Call the bank")
        assertEquals(10 * hour, topInColumn(marker))
        assertEquals(
            "no end and no estimate is no height at all: the marker takes its own rule and label",
            ViewGroup.LayoutParams.WRAP_CONTENT,
            paramsOf(marker).height
        )
        assertEquals(
            context.getString(R.string.calendar_no_length_given),
            marker.findViewById<View>(R.id.markerLine).contentDescription
        )
    }

    @Test
    fun `tasks that collide are drawn side by side and both say how many share their time`() {
        insert("Design session", start = later to "11:00", end = later to "12:00")
        insert("Standup", start = later to "11:30", end = later to "12:30")

        render(state(CalendarMode.DAY, anchor = later, selected = later))

        val first = dayView("Design session")
        val second = dayView("Standup")
        val column = binding.dayLane.width

        assertEquals(
            "one cluster row above the gridlines, because the two tasks compete for the same hour",
            2,
            binding.dayLane.childCount
        )
        assertTrue(
            "two lanes, so neither hides the other: ${leftInColumn(first)}, ${leftInColumn(second)}, $column",
            leftInColumn(first) < leftInColumn(second)
        )
        assertTrue(
            "and both of them are inside the column",
            leftInColumn(first) >= 0 && leftInColumn(second) + second.width <= column &&
                first.width < column
        )
        assertEquals("each of them gets half of the day", first.width, second.width)
        assertTrue("a half of the column is wide enough to read", first.width > 0)
        assertEquals(
            "the earlier task is the one on the left",
            "Design session",
            first.findViewById<TextView>(R.id.blockTitle).text.toString()
        )

        val badge = context.getString(R.string.calendar_block_overlap_format, 2)
        assertEquals(badge, first.findViewById<TextView>(R.id.blockOverlap).text.toString())
        assertEquals(badge, second.findViewById<TextView>(R.id.blockOverlap).text.toString())
        assertTrue(first.findViewById<View>(R.id.blockOverlap).isVisible)
        assertTrue(second.findViewById<View>(R.id.blockOverlap).isVisible)
    }

    @Test
    fun `the day adds up its own time and keeps the tasks with no time in a section of their own`() {
        val rent = runBlocking { fixture.addCategory(userId, "Home", color = HOME_COLOR) }
        insert("Dentist", start = later to "09:00", end = later to "09:45")
        insert("Design session", start = later to "11:00", end = later to "12:00")
        insert("Standup", start = later to "11:30", end = later to "12:30")
        insert("Call the bank", due = later to "10:00")
        insert("Pay rent", due = later to "00:00", categoryId = rent)

        render(state(CalendarMode.DAY, anchor = later, selected = later))

        assertEquals(
            "the counts the day view states are the ones the aggregation made, not a rounded story",
            listOf("5 tasks", "165 min planned", "1 overlapping"),
            binding.daySummary.text.toString().split(SEPARATOR)
        )
        assertFalse(
            "one hundred sixty-five minutes is not a full day, and the screen does not call it one",
            binding.daySummary.text.contains(context.getString(R.string.calendar_full_day))
        )
        assertEquals(
            "a deadline stored at midnight belongs to the day, not to 00:00 inside it",
            listOf("Pay rent"),
            (0 until binding.noTimeTasks.childCount)
                .map { binding.noTimeTasks.getChildAt(it) }
                .map { titleOf(it) }
        )
        assertTrue(binding.noTimeLabel.isVisible)
        assertFalse(binding.dayEmpty.isVisible)
    }

    @Test
    fun `a day with nothing at a set time says so instead of drawing an empty column`() {
        insert("Pay rent", due = later to "00:00")

        render(state(CalendarMode.DAY, anchor = later, selected = later))

        assertTrue(binding.dayEmpty.isVisible)
        assertEquals(
            "the column keeps its gridlines and nothing in between them",
            1,
            binding.dayLane.childCount
        )
        assertEquals(
            "a day of no planned time is a real answer, and it adds up to zero minutes",
            listOf("1 task", "0 min planned"),
            binding.daySummary.text.toString().split(SEPARATOR)
        )
    }

    @Test
    fun `the day column is twenty-four hours of labelled gridlines`() {
        render(state(CalendarMode.DAY, selected = today))

        assertEquals(24, binding.hourLabels.childCount)
        assertEquals(24, binding.hourRows.childCount)
        assertEquals(dp(HOUR_HEIGHT_DP), binding.hourRows.getChildAt(0).layoutParams.height)
        assertEquals(
            "the column is exactly as tall as the twenty-four hours it labels",
            dp(HOUR_HEIGHT_DP) * 24,
            binding.dayLane.layoutParams.height
        )
        assertEquals(
            "and its last label is the last hour, not an invented one",
            timeText(today, 23, 0),
            (binding.hourLabels.getChildAt(23) as TextView).text.toString()
        )
    }

    // ---- taps, drops and refusals -------------------------------------------

    @Test
    fun `tapping a chip opens the task behind it and tapping the day reads it`() {
        val id = insert("Report", due = later to "10:00")

        val chip = chipsIn(bindCell(cellFor(later))).single()
        chip.performClick()
        assertEquals(listOf(id), opened.map { it.id })

        val cellBinding = bindCell(cellFor(today), selected = later)
        cellBinding.root.performClick()
        assertEquals(listOf(today), selected)
    }

    @Test
    fun `a block tap opens the task it is drawn from`() {
        val dentist = insert("Dentist", start = later to "09:00", end = later to "09:45")
        val rent = insert("Pay rent", due = later to "00:00")

        render(state(CalendarMode.DAY, anchor = later, selected = later))
        dayView("Dentist").performClick()
        binding.noTimeTasks.getChildAt(0).performClick()

        assertEquals(
            "the column and the no-time section each open the task they drew, and never one another's",
            listOf(dentist, rent),
            opened.map { it.id }
        )
    }

    @Test
    fun `a drop on a day asks for the move of exactly the task the drag carried`() {
        val id = insert("Doctor", due = today to "14:00")

        assertTrue(controller.dropOnDate(later, CalendarDrag.clipOf(id)))
        assertEquals(listOf(id to later), dropped)
    }

    @Test
    fun `a drop of something this screen did not start is refused without a move`() {
        assertFalse(controller.dropOnDate(today, ClipData.newPlainText("note", "Buy milk")))
        assertFalse(controller.dropOnDate(today, null))
        assertEquals(emptyList<Pair<Long, LocalDate>>(), dropped)
    }

    @Test
    fun `a refused drag is announced once and not again on the next refresh`() {
        val plain = state(CalendarMode.MONTH)

        controller.render(plain.copy(dragRefused = true))
        controller.render(plain.copy(dragRefused = true))
        assertEquals(
            listOf(context.getString(R.string.calendar_drag_refused)),
            messages
        )

        controller.render(plain)
        controller.render(plain.copy(dragRefused = true))
        assertEquals(2, messages.size)
    }

    // ---- the controls -------------------------------------------------------

    @Test
    fun `the page controls and the mode switch ask for what they are labelled`() {
        binding.nextButton.performClick()
        assertEquals(1, pages)
        binding.previousButton.performClick()
        assertEquals(0, pages)
        binding.todayButton.performClick()
        assertEquals(1, todayClicks)

        binding.modeWeek.performClick()
        assertEquals(listOf(CalendarMode.WEEK), modes)

        controller.render(state(CalendarMode.WEEK))
        modes.clear()
        controller.render(state(CalendarMode.WEEK))
        assertEquals(
            "a render that repaints the mode chip is not the user choosing a mode",
            emptyList<CalendarMode>(),
            modes
        )
    }

    @Test
    fun `the title says what the page holds`() {
        val shown = today.plusMonths(1).withDayOfMonth(15)

        controller.render(state(CalendarMode.MONTH, anchor = shown))
        assertEquals(
            "${shown.month.getDisplayName(TextStyle.FULL, locale)} ${shown.year}",
            binding.calendarTitle.text.toString()
        )

        controller.render(state(CalendarMode.WEEK, anchor = shown))
        val week = CalendarMath.weekGrid(shown)
        assertEquals(
            context.getString(R.string.calendar_week_format, dayShort(week.first()), dayShort(week.last())),
            binding.calendarTitle.text.toString()
        )

        controller.render(state(CalendarMode.DAY, anchor = shown, selected = shown))
        assertEquals(
            "${shown.dayOfWeek.getDisplayName(TextStyle.FULL, locale)}, " +
                "${dayShort(shown)}, ${shown.year}",
            binding.calendarTitle.text.toString()
        )
    }

    @Test
    fun `the weekday header opens on the day the device's week opens on`() {
        val labels = (0 until binding.weekdayHeader.childCount)
            .map { (binding.weekdayHeader.getChildAt(it) as TextView).text.toString() }

        assertEquals(7, labels.size)
        assertEquals(context.getString(dayRes(CalendarMath.firstDayOfWeek())), labels.first())
        assertEquals(labels.size, labels.distinct().size)
    }

    // ---- 320dp ---------------------------------------------------------------

    @Test
    fun `the month page fits a 320dp screen`() {
        insert("Report", due = later to "10:00")

        val width = render(state(CalendarMode.MONTH))

        assertTrue(binding.calendarGrid.isVisible)
        assertTrue(binding.weekdayHeader.isVisible)
        assertFalse(binding.dayScroll.isVisible)
        assertEquals("a seven column grid, so the columns line up with the header", 7, binding.weekdayHeader.childCount)
        assertEquals(width, binding.calendarGrid.measuredWidth)
        assertInside(
            width,
            binding.calendarTitle,
            binding.previousButton,
            binding.todayButton,
            binding.nextButton,
            binding.modeChips,
            binding.weekdayHeader,
            binding.calendarGrid
        )
    }

    @Test
    fun `the week page fits a 320dp screen`() {
        insert("Report", due = later to "10:00")

        val width = render(state(CalendarMode.WEEK, anchor = later, selected = later))

        assertTrue(binding.calendarGrid.isVisible)
        assertFalse(binding.dayScroll.isVisible)
        assertEquals(width, binding.calendarGrid.measuredWidth)
        assertEquals(
            "the week draws its seven days",
            7,
            binding.calendarGrid.layoutManager!!.childCount
        )
        assertInside(width, binding.calendarGrid, binding.weekdayHeader, binding.modeWeek)
    }

    @Test
    fun `the day page fits a 320dp screen with its blocks inside the column`() {
        insert("Design session", start = later to "11:00", end = later to "12:00")
        insert("Standup", start = later to "11:30", end = later to "12:30")
        insert("Call the bank", due = later to "10:00")
        insert("Pay rent", due = later to "00:00")

        val width = render(state(CalendarMode.DAY, anchor = later, selected = later))

        assertTrue(binding.dayScroll.isVisible)
        assertFalse(binding.calendarGrid.isVisible)
        assertInside(width, binding.daySummary, binding.hourLabels, binding.dayLane, binding.noTimeTasks)
        assertTrue(
            "the hour column and the day column together are the screen, never wider",
            binding.hourLabels.width + binding.dayLane.width <= width
        )
        blockViews().forEach { block ->
            assertTrue(
                "a block that reached past the column would be unreachable on a narrow phone",
                absoluteLeft(block) >= 0 && absoluteRight(block) <= width
            )
            assertTrue(
                "and a block sits inside the twenty-four hours, not below them",
                topInColumn(block) >= 0 && topInColumn(block) + block.height <= binding.dayLane.height
            )
        }
    }

    @Test
    fun `a crowded month cell keeps its titles inside its own column`() {
        val home = runBlocking { fixture.addCategory(userId, "Home", color = HOME_COLOR) }
        insert("Bin bags for the kitchen and the study", due = later to "08:00", categoryId = home)
        insert("Hoover the whole flat before the landlord visits", due = later to "09:00", categoryId = home)
        insert("Report", due = later to "10:00", categoryId = home)
        insert("Standup", due = later to "11:00", categoryId = home)

        val width = render(state(CalendarMode.MONTH))
        val cells = (0 until binding.calendarGrid.childCount).map { binding.calendarGrid.getChildAt(it) }

        assertTrue("the month page is drawn as whole weeks, seven cells to a row", cells.isNotEmpty())
        assertEquals("seven columns, so a cell lines up under its weekday letter", 0, cells.size % WEEK_COLUMNS)
        cells.forEach { cell ->
            assertInside(width, cell)
            assertTrue(
                "a cell takes one of the seven columns and never a word's worth more",
                cell.width * WEEK_COLUMNS <= width + WEEK_COLUMNS
            )
        }

        // The crowded day is bound on its own and laid out at one column's width, rather than picked out of
        // the grid above. A RecyclerView binds only the rows its viewport fits, so which days of a month are
        // on screen at all is a fact about the date the test runs on: a cell that was never drawn has no
        // width to measure, and a test that reads it passes on one day of the month and fails on the next.
        val crowded = bindCell(cellFor(later))
        val column = laidOutColumn(crowded.root, width)

        val chips = chipsIn(crowded)
        assertEquals("four tasks on the day, and a month cell promises three of them", 3, chips.size)
        assertTrue("the fourth is said rather than vanished", crowded.cellMore.isVisible)
        chips.forEach { chip ->
            assertTrue(
                "a long title is cut short inside its cell rather than widening the column",
                chip.left + chip.width <= column
            )
        }
    }

    /**
     * Lays a detached cell out at the width one month column gets and the height its own bind asked for,
     * so the geometry the test reads is the geometry the grid would have given it.
     */
    private fun laidOutColumn(cell: View, screenWidth: Int): Int {
        val column = screenWidth / WEEK_COLUMNS
        val height = cell.layoutParams.height
        assertTrue("the month bind sizes its own cell before the screen lays it out", height > 0)
        cell.measure(
            View.MeasureSpec.makeMeasureSpec(column, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        )
        cell.layout(0, 0, column, height)
        return cell.width
    }

    private companion object {
        const val HOME_COLOR = -12345
        const val WORK_COLOR = -16711936
        const val HOUR_HEIGHT_DP = 44
        const val NEIGHBOUR_ALPHA = 0.45f
        const val SEPARATOR = " · "
        const val WEEK_COLUMNS = 7
    }
}
