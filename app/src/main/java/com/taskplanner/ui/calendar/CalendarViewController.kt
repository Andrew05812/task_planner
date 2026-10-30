package com.taskplanner.ui.calendar

import android.content.ClipData
import android.content.res.ColorStateList
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.GridLayoutManager
import com.taskplanner.R
import com.taskplanner.data.calendar.CalendarAggregator.DayBlock
import com.taskplanner.data.calendar.CalendarAggregator.DayCell
import com.taskplanner.data.calendar.CalendarAggregator.DayPlan
import com.taskplanner.data.calendar.CalendarAggregator.MINUTES_PER_DAY
import com.taskplanner.data.calendar.CalendarMath
import com.taskplanner.data.report.export.ReportFormatting
import com.taskplanner.data.model.Task
import com.taskplanner.ui.calendar.CalendarDayLayout.Cluster
import com.taskplanner.databinding.FragmentCalendarBinding
import com.taskplanner.databinding.ItemCalendarBlockBinding
import com.taskplanner.databinding.ItemCalendarCellTaskBinding
import com.taskplanner.databinding.ItemCalendarMarkerBinding
import com.taskplanner.ui.util.LocalisedDates
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Date
import java.util.Locale

/**
 * Everything the calendar screen shows, driven from one [CalendarState].
 *
 * The drawing lives here rather than in the fragment because what it has to get right is arithmetic
 * on views: a block at the minute the user typed, a marker that takes no height at all, two colliding
 * tasks each keeping their own half of the column, a day cell whose chips, counts and highlight all
 * came from one aggregation. Handing it a state and reading back the views it made is what makes that
 * arithmetic testable.
 *
 * The one thing it does not own is the data: it never reloads a grid, never re-queries a day and never
 * recomputes a count, because the repository's flow already does all three.
 */
class CalendarViewController(
    private val binding: FragmentCalendarBinding,
    private val onTaskClick: (Task) -> Unit,
    private val onDropTask: (Long, LocalDate) -> Unit,
    private val onSelect: (LocalDate) -> Unit,
    private val onModeSelected: (CalendarMode) -> Unit,
    private val onPageBack: () -> Unit,
    private val onPageForward: () -> Unit,
    private val onToday: () -> Unit,
    private val onMessage: (CharSequence) -> Unit
) {

    private val context get() = binding.root.context
    private val inflater: LayoutInflater = LayoutInflater.from(context)
    private val locale: Locale get() = LocalisedDates.locale()
    private val timeFormat = DateFormat.getTimeFormat(context)

    /** The grid's adapter, public so a cell can be bound and read the way the grid binds it. */
    val cellAdapter = CalendarCellAdapter(
        onTaskClick = onTaskClick,
        onTaskDragStart = { view, task -> startDrag(view, task) },
        onSelect = onSelect,
        onDrop = { date, event -> dropOnDate(date, event.clipData) }
    )

    private val hourHeightPx = (HOUR_HEIGHT_DP * context.resources.displayMetrics.density).toInt()
    private val laneGapPx = (LANE_GAP_DP * context.resources.displayMetrics.density).toInt()

    private var lastState: CalendarState? = null
    private var dragRefusedShown = false

    /**
     * Prepares the screen once: the grid, the page controls, the mode switch, the weekday header and
     * the fixed parts of the day column.
     *
     * The hour labels and the gridlines are built here instead of per render because they are the same
     * twenty-four lines whatever the day holds; only the tasks in between them ever change.
     */
    fun bindControls() {
        binding.calendarGrid.layoutManager = GridLayoutManager(context, WEEK_COLUMNS)
        binding.calendarGrid.adapter = cellAdapter

        binding.previousButton.setOnClickListener { onPageBack() }
        binding.nextButton.setOnClickListener { onPageForward() }
        binding.todayButton.setOnClickListener { onToday() }

        binding.modeChips.setOnCheckedStateChangeListener { _, checkedIds ->
            val mode = checkedIds.firstOrNull()?.let { modeOf(it) }
                ?: return@setOnCheckedStateChangeListener
            // The chip group is also driven *from* the state, so only a choice the screen has not
            // drawn yet counts as the user's: otherwise a render would feed itself a mode change.
            if (mode != lastState?.mode) onModeSelected(mode)
        }

        buildWeekdayHeader()
        buildHourColumn()
    }

    /**
     * Draws one state.
     *
     * A refused drag is reported the first time it appears and not again: the flag is the screen's to
     * clear, and a message that came back on every later refresh would blame the user for something
     * they had already been told about.
     */
    fun render(state: CalendarState) {
        lastState = state
        binding.calendarTitle.text = titleOf(state)
        syncModeChip(state.mode)

        val dayMode = state.mode == CalendarMode.DAY
        binding.weekdayHeader.isVisible = !dayMode
        binding.calendarGrid.isVisible = !dayMode
        binding.dayScroll.isVisible = dayMode

        cellAdapter.update(state.cells.map { CalendarCellItem(it, state.mode, state.selected) })
        if (dayMode) renderDay(state)

        if (state.dragRefused && !dragRefusedShown) {
            dragRefusedShown = true
            onMessage(context.getString(R.string.calendar_drag_refused))
        }
        if (!state.dragRefused) dragRefusedShown = false
    }

    /**
     * Takes the drop a day cell reported and asks for the move.
     *
     * The task is identified only by what the drag carried and the day only by the cell it landed on;
     * the repository does the rest, so the screen cannot re-do the date maths and get it wrong in a way
     * the data layer's own tests would never see. A clip this screen did not start is refused, which
     * is the same answer the cell gave when it declined the drag.
     */
    fun dropOnDate(date: LocalDate, clip: ClipData?): Boolean {
        val taskId = CalendarDrag.taskIdOf(clip) ?: return false
        onDropTask(taskId, date)
        return true
    }

    /** Hands the system the task id this drag is about; the fade belongs to the cell that started it. */
    private fun startDrag(view: View, task: Task) {
        ViewCompat.startDragAndDrop(
            view,
            CalendarDrag.clipOf(task.id),
            View.DragShadowBuilder(view),
            view,
            0
        )
    }

    private fun renderDay(state: CalendarState) {
        val plan = state.dayPlan ?: return
        val cell = state.selectedCell
        binding.daySummary.text = summaryOf(plan, cell)
        binding.dayEmpty.isVisible = plan.blocks.isEmpty()

        binding.dayLane.layoutParams = binding.dayLane.layoutParams.apply {
            height = CalendarDayLayout.columnHeightPx(hourHeightPx)
        }
        // Child zero is the gridline layer: everything above it is the day's own blocks, and a live
        // refresh replaces those and only those.
        binding.dayLane.removeViews(1, (binding.dayLane.childCount - 1).coerceAtLeast(0))

        CalendarDayLayout.clusters(plan.blocks).forEach { cluster ->
            binding.dayLane.addView(clusterRow(cluster, plan.date, cell))
        }

        binding.noTimeLabel.isVisible = plan.noTimeGiven.isNotEmpty()
        binding.noTimeTasks.removeAllViews()
        plan.noTimeGiven.forEach { task -> binding.noTimeTasks.addView(taskRow(task, cell)) }
    }

    /**
     * One cluster of colliding blocks: a row of the day's column, put where its earliest minute is and
     * split into as many lanes as the collisions need.
     *
     * The lanes take their share of the width from the layout itself, so a colliding task sits beside
     * the one it collides with on the very first drawing of the day rather than only after the column
     * has been measured and painted a second time.
     */
    private fun clusterRow(cluster: Cluster, date: LocalDate, cell: DayCell?): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = CalendarDayLayout.minuteToPx(cluster.startMinute, hourHeightPx)
            }
        }
        cluster.lanes.forEachIndexed { index, lane ->
            val laneView = FrameLayout(context)
            laneView.layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
                // The gap belongs to every lane but the last, so the right edge of the column is never
                // reserved for a lane that does not exist.
                if (index < cluster.lanes.lastIndex) marginEnd = laneGapPx
            }
            lane.forEach { block ->
                if (block.endMinute == null) addMarker(laneView, block, cluster, date, cell)
                else addBlock(laneView, block, cluster, date, cell)
            }
            row.addView(laneView)
        }
        return row
    }

    /**
     * A task the user gave a start and an end: drawn between them, in the lane its collisions left it,
     * wearing the colour of the category it is filed under.
     */
    private fun addBlock(
        lane: FrameLayout,
        block: DayBlock,
        cluster: Cluster,
        date: LocalDate,
        cell: DayCell?
    ) {
        val row = ItemCalendarBlockBinding.inflate(inflater, lane, false)
        row.blockTitle.text = block.task.title
        row.blockTime.text = context.getString(
            R.string.calendar_block_time_format,
            timeLabel(date, block.startMinute),
            timeLabel(date, block.endMinute ?: block.startMinute)
        )
        row.blockTitle.setTextColor(categoryColor(block.task, cell) ?: defaultTextColor())
        // The block is filled with its category colour so a day reads as categories first and titles
        // second; a task nobody filed keeps the plain surface it was drawn with.
        categoryColor(block.task, cell)?.let {
            row.root.backgroundTintList =
                ColorStateList.valueOf(ColorUtils.setAlphaComponent(it, BLOCK_FILL_ALPHA))
        }
        labelOverlap(row.blockOverlap, block)
        row.root.setOnClickListener { onTaskClick(block.task) }

        row.root.layoutParams = laneParams(block, cluster)
        lane.addView(row.root)
    }

    /**
     * A task with no length: a rule at its minute and its name beside it, given no share of the column
     * because no share was ever planned for it.
     */
    private fun addMarker(
        lane: FrameLayout,
        block: DayBlock,
        cluster: Cluster,
        date: LocalDate,
        cell: DayCell?
    ) {
        val row = ItemCalendarMarkerBinding.inflate(inflater, lane, false)
        row.markerTitle.text = block.task.title
        row.markerTime.text = timeLabel(date, block.startMinute)
        row.markerLine.contentDescription = context.getString(R.string.calendar_no_length_given)
        row.markerTitle.setTextColor(categoryColor(block.task, cell) ?: defaultTextColor())
        categoryColor(block.task, cell)?.let {
            row.markerLine.backgroundTintList = ColorStateList.valueOf(it)
        }
        labelOverlap(row.markerOverlap, block)
        row.root.setOnClickListener { onTaskClick(block.task) }

        row.root.layoutParams = laneParams(block, cluster)
        lane.addView(row.root)
    }

    /**
     * Where a block sits inside its lane: at the minute it starts, and as tall as the time it was
     * given — or with no height claim at all when none was given.
     */
    private fun laneParams(block: DayBlock, cluster: Cluster): FrameLayout.LayoutParams {
        val height =
            if (block.endMinute == null) FrameLayout.LayoutParams.WRAP_CONTENT
            else CalendarDayLayout.heightPx(block, hourHeightPx)
        return FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, height).apply {
            topMargin = CalendarDayLayout.offsetPx(block, cluster, hourHeightPx)
        }
    }

    /**
     * Says how many tasks are running at this block's minute, so a lane is not the only sign of a
     * collision.
     *
     * The aggregator lists the *other* tasks a block collides with, so the badge adds the block itself:
     * a meeting with one partner is two tasks at the same time, which is what the user has to read
     * before deciding which one to move.
     */
    private fun labelOverlap(label: TextView, block: DayBlock) {
        val colliding = block.overlappingTaskIds.size
        label.isVisible = colliding > 0
        if (colliding > 0) {
            label.text = context.getString(R.string.calendar_block_overlap_format, colliding + 1)
        }
    }

    /**
     * The colour a task's category gave it, read off the day's own legend.
     *
     * Null is a real answer here: the user never filed the task, so the screen draws it uncoloured
     * instead of picking a colour for it.
     */
    private fun categoryColor(task: Task, cell: DayCell?): Int? =
        task.categoryId?.let { id -> cell?.categories?.firstOrNull { it.categoryId == id }?.color }

    private fun defaultTextColor(): Int = ContextCompat.getColor(context, R.color.on_surface)

    /** A "no time given" row: the same chip a month cell uses, because it is the same kind of fact. */
    private fun taskRow(task: Task, cell: DayCell?): View {
        val row = ItemCalendarCellTaskBinding.inflate(inflater, binding.noTimeTasks, false)
        val color = categoryColor(task, cell)
        row.taskTitle.text = task.title
        row.taskDot.isVisible = color != null
        if (color != null) {
            row.taskDot.backgroundTintList = ColorStateList.valueOf(color)
            row.taskTitle.setTextColor(color)
        } else {
            row.taskTitle.setTextColor(defaultTextColor())
        }
        row.root.setOnClickListener { onTaskClick(task) }
        return row.root
    }

    /**
     * What the day adds up to, in the order a user checks a day: how much is on it, how much of that
     * is time they named, whether any of it collides, and whether any of it is already late.
     */
    private fun summaryOf(plan: DayPlan, cell: DayCell?): String = listOfNotNull(
        cell?.let {
            context.resources.getQuantityString(
                R.plurals.calendar_task_count,
                it.tasks.size,
                it.tasks.size
            )
        },
        context.getString(R.string.calendar_busy_format, plan.busyMinutes),
        if (plan.overlapCount > 0)
            context.getString(R.string.calendar_overlap_format, plan.overlapCount)
        else null,
        if (plan.overdueCount > 0)
            context.getString(R.string.calendar_overdue_format, plan.overdueCount)
        else null,
        if (plan.overloaded) context.getString(R.string.calendar_full_day) else null
    ).joinToString(SEPARATOR)

    private fun titleOf(state: CalendarState): String = when (state.mode) {
        // The shared heading rather than a pattern of its own. `SimpleDateFormat("MMMM yyyy")` gives the
        // genitive in Russian — "октября 2026" — because the year beside the month makes the locale data pick
        // the form a dated phrase uses, and a month title has no date in it. `ReportFormatting.month` explains
        // the choice. The week and day labels below carry a day number, which is exactly where the genitive is
        // right — but they used to carry a written-down American order beside it («нояб. 30», «нояб. 30,
        // 2026»), and Stage 14 gave them [LocalisedDates], which asks the language instead of being told.
        CalendarMode.MONTH -> ReportFormatting.month(state.anchor)

        CalendarMode.WEEK -> {
            val days = CalendarMath.weekGrid(state.anchor)
            context.getString(
                R.string.calendar_week_format,
                LocalisedDates.dayAndMonth(wallClockDate(days.first(), 0).time),
                LocalisedDates.dayAndMonth(wallClockDate(days.last(), 0).time)
            )
        }

        CalendarMode.DAY ->
            LocalisedDates.dateWithWeekday(wallClockDate(state.selected, 0).time)
    }

    /**
     * The wall-clock moment of a minute on [date].
     *
     * Built from the day and the minute the aggregator measured rather than from millis added to a day
     * boundary, so a label says the time the user reads on their own clock even on the two days a year
     * that has no simple arithmetic answer. Minute 1440 is midnight on the day that follows, which is
     * where a block that runs past the end of the day says it finishes.
     */
    private fun wallClockDate(date: LocalDate, minute: Int): Date {
        val rolled = date.plusDays((minute / MINUTES_PER_DAY).toLong())
        val local = rolled.atTime((minute % MINUTES_PER_DAY) / 60, minute % 60)
        return Date.from(local.atZone(CalendarMath.systemZone()).toInstant())
    }

    private fun timeLabel(date: LocalDate, minute: Int): String =
        timeFormat.format(wallClockDate(date, minute))

    /**
     * The weekday letters, opened on the day the device's locale says the week opens on.
     *
     * They are the grid's header, so they come from the same [CalendarMath.firstDayOfWeek] the grid was
     * built with: a header opening on Sunday above a grid opening on Monday is the one calendar mistake
     * every user notices.
     */
    private fun buildWeekdayHeader() {
        binding.weekdayHeader.removeAllViews()
        val first = CalendarMath.firstDayOfWeek()
        repeat(WEEK_COLUMNS) { offset ->
            binding.weekdayHeader.addView(
                headerLabel(context.getString(dayNameRes(first.plus(offset.toLong()))))
            )
        }
    }

    private fun buildHourColumn() {
        val today = LocalDate.now(CalendarMath.systemZone())
        binding.hourLabels.removeAllViews()
        binding.hourRows.removeAllViews()
        (0 until HOURS).forEach { hour ->
            binding.hourLabels.addView(
                TextView(context).apply {
                    text = timeLabel(today, hour * 60)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, hourHeightPx
                    )
                    setTextColor(ContextCompat.getColor(context, R.color.material_grey_600))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, LABEL_SP)
                    gravity = Gravity.TOP or Gravity.END
                }
            )
            binding.hourRows.addView(
                View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, hourHeightPx
                    )
                    setBackgroundResource(R.drawable.calendar_hour_line)
                }
            )
        }
    }

    /** One weekday letter, weighted so the seven of them line up with the seven grid columns. */
    private fun headerLabel(text: String): TextView = TextView(context).apply {
        this.text = text
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        gravity = Gravity.CENTER
        setTextColor(ContextCompat.getColor(context, R.color.material_grey_600))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, LABEL_SP)
    }

    private fun modeOf(chipId: Int): CalendarMode? = when (chipId) {
        R.id.modeMonth -> CalendarMode.MONTH
        R.id.modeWeek -> CalendarMode.WEEK
        R.id.modeDay -> CalendarMode.DAY
        else -> null
    }

    private fun chipIdOf(mode: CalendarMode): Int = when (mode) {
        CalendarMode.MONTH -> R.id.modeMonth
        CalendarMode.WEEK -> R.id.modeWeek
        CalendarMode.DAY -> R.id.modeDay
    }

    private fun syncModeChip(mode: CalendarMode) {
        val id = chipIdOf(mode)
        if (binding.modeChips.checkedChipId != id) binding.modeChips.check(id)
    }

    private fun dayNameRes(day: DayOfWeek): Int = when (day) {
        DayOfWeek.MONDAY -> R.string.day_short_mon
        DayOfWeek.TUESDAY -> R.string.day_short_tue
        DayOfWeek.WEDNESDAY -> R.string.day_short_wed
        DayOfWeek.THURSDAY -> R.string.day_short_thu
        DayOfWeek.FRIDAY -> R.string.day_short_fri
        DayOfWeek.SATURDAY -> R.string.day_short_sat
        DayOfWeek.SUNDAY -> R.string.day_short_sun
    }

    companion object {
        private const val WEEK_COLUMNS = 7
        private const val HOURS = 24
        private const val HOUR_HEIGHT_DP = 44
        private const val LANE_GAP_DP = 2
        private const val LABEL_SP = 9f
        private const val SEPARATOR = " · "
        private const val BLOCK_FILL_ALPHA = 60
    }
}
