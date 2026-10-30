package com.taskplanner.ui.calendar

import android.content.res.ColorStateList
import android.view.DragEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.taskplanner.R
import com.taskplanner.data.calendar.CalendarAggregator
import com.taskplanner.data.model.Task
import com.taskplanner.databinding.ItemCalendarCellTaskBinding
import com.taskplanner.databinding.ItemCalendarDayBinding
import com.taskplanner.ui.util.LocalisedDates
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * One day of the grid together with the two screen choices that change how it is drawn.
 *
 * The mode and the selection travel with the cell instead of living beside it so that the cells a
 * page shows and the cells the same page shows after the user taps one are different values: the
 * highlight has to move when the selection moves, and a cell that could be re-bound from a stale
 * screen choice would keep the old day lit.
 */
data class CalendarCellItem(
    val cell: CalendarAggregator.DayCell,
    val mode: CalendarMode,
    val selected: LocalDate
)

/**
 * The month and week grids.
 *
 * A plain adapter over the whole list rather than a diffing one, and that is not a shortcut: a page
 * here is at most six weeks of cells, and any live refresh of a day the user is looking at has to
 * repaint that cell's chips, counts and highlight anyway. Repainting the handful of visible cells is
 * cheaper than reasoning about which of them a diff decided to keep.
 *
 * The chip list is deliberately short — three titles in the column a month gives a narrow phone — and
 * what does not fit is announced as "+N more" instead of being cut off, because the count the user
 * reads has to match the day they are about to open.
 */
class CalendarCellAdapter(
    private val onTaskClick: (Task) -> Unit,
    private val onTaskDragStart: (View, Task) -> Unit,
    private val onSelect: (LocalDate) -> Unit,
    private val onDrop: (LocalDate, DragEvent) -> Boolean
) : RecyclerView.Adapter<CalendarCellAdapter.CellViewHolder>() {

    /** The page on screen, in the order the grid lays it out. */
    var items: List<CalendarCellItem> = emptyList()
        private set

    /** Replaces the whole page. Called for every live emission of the grid's flow. */
    fun update(items: List<CalendarCellItem>) {
        this.items = items
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CellViewHolder =
        CellViewHolder(
            ItemCalendarDayBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        )

    override fun onBindViewHolder(holder: CellViewHolder, position: Int) {
        holder.bind(items[position])
    }

    inner class CellViewHolder internal constructor(
        val binding: ItemCalendarDayBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        /** The day this cell is showing, or null before the first bind. */
        val date: LocalDate? get() = boundCell?.date

        private var boundCell: CalendarAggregator.DayCell? = null

        init {
            binding.root.setOnClickListener { boundCell?.let { onSelect(it.date) } }
            // Every day cell is a drop target: a drag started on any chip can end here, and the cell
            // it lands on is the day the task moves to.
            binding.root.setOnDragListener { _, event -> handleDrag(event) }
        }

        /**
         * Consumes the drag phases this cell cares about.
         *
         * A cell accepts a drag only when it can read a task out of its clip, so the system offers
         * this day as a target for a calendar drag and not for a clip from another app, which would
         * have a task id this screen must not act on.
         */
        private fun handleDrag(event: DragEvent): Boolean {
            val day = date ?: return false
            return when (event.action) {
                DragEvent.ACTION_DRAG_STARTED -> CalendarDrag.taskIdOf(event.clipData) != null
                DragEvent.ACTION_DRAG_ENTERED -> {
                    binding.root.isActivated = true
                    true
                }
                DragEvent.ACTION_DRAG_EXITED -> {
                    binding.root.isActivated = false
                    true
                }
                DragEvent.ACTION_DROP -> {
                    binding.root.isActivated = false
                    onDrop(day, event)
                }
                DragEvent.ACTION_DRAG_ENDED -> {
                    binding.root.isActivated = false
                    // A chip was faded when its drag started. Whatever the drop did, the cell is drawn
                    // from the grid again, so nothing may be left half-visible.
                    (0 until binding.cellTasks.childCount)
                        .forEach { binding.cellTasks.getChildAt(it).alpha = 1f }
                    true
                }
                else -> false
            }
        }

        fun bind(item: CalendarCellItem) {
            val cell = item.cell
            boundCell = cell
            val context = binding.root.context
            val density = binding.root.resources.displayMetrics.density
            val maxChips = maxChips(item.mode)
            val shown = minOf(cell.tasks.size, maxChips)

            binding.root.layoutParams.height = cellHeightPx(item.mode, density)
            binding.root.isSelected = cell.date == item.selected
            binding.root.foreground = if (cell.isToday) {
                ContextCompat.getDrawable(context, R.drawable.calendar_cell_today)
            } else {
                null
            }
            // A borrowed day from the neighbouring month stays readable but steps back: the month the
            // user opened is the one they are counting.
            binding.root.alpha = if (cell.inDisplayedMonth) 1f else NEIGHBOUR_ALPHA

            binding.cellDayNumber.text = cell.date.dayOfMonth.toString()
            binding.cellMonthLabel.isVisible = item.mode == CalendarMode.WEEK
            binding.cellMonthLabel.text = cell.date.month.getDisplayName(
                TextStyle.SHORT, LocalisedDates.locale()
            )

            bindChips(cell, shown)

            val hidden = cell.tasks.size - shown
            binding.cellMore.isVisible = hidden > 0
            if (hidden > 0) {
                binding.cellMore.text = context.getString(R.string.calendar_more_format, hidden)
            }

            // The counts a cell cannot show as colours: the tasks nobody filed, and the ones already
            // past their deadline.
            val notes = listOfNotNull(
                if (cell.overdueCount > 0)
                    context.getString(R.string.calendar_overdue_format, cell.overdueCount)
                else null,
                if (cell.unassignedCount > 0)
                    context.getString(R.string.calendar_unassigned_format, cell.unassignedCount)
                else null
            )
            binding.cellNote.isVisible = notes.isNotEmpty()
            binding.cellNote.text = notes.joinToString(NOTE_SEPARATOR)

            binding.cellFullDay.isVisible = cell.overloaded
        }

        private fun bindChips(cell: CalendarAggregator.DayCell, shown: Int) {
            binding.cellTasks.removeAllViews()
            cell.tasks.take(shown).forEach { task ->
                val chip = ItemCalendarCellTaskBinding.inflate(
                    LayoutInflater.from(binding.root.context), binding.cellTasks, false
                )
                // A task with no category has no colour to borrow, so it is drawn in plain text and
                // counted on the note line instead of being filed under a colour it never had.
                val color = colorOf(cell, task)
                chip.taskTitle.text = task.title
                chip.taskDot.isVisible = color != null
                if (color != null) {
                    chip.taskDot.backgroundTintList = ColorStateList.valueOf(color)
                    chip.taskTitle.setTextColor(color)
                } else {
                    chip.taskTitle.setTextColor(
                        ContextCompat.getColor(chip.root.context, R.color.on_surface)
                    )
                }
                chip.root.setOnClickListener { onTaskClick(task) }
                chip.root.setOnLongClickListener {
                    chip.root.alpha = FADED_WHILE_DRAGGING
                    onTaskDragStart(chip.root, task)
                    true
                }
                binding.cellTasks.addView(chip.root)
            }
        }

        /**
         * The category colour a chip wears, read off the cell's own legend instead of looked up again:
         * a chip may not disagree with the counts printed in the same cell.
         */
        private fun colorOf(cell: CalendarAggregator.DayCell, task: Task): Int? =
            task.categoryId?.let { categoryId ->
                cell.categories.firstOrNull { it.categoryId == categoryId }?.color
            }
    }

    /**
     * A week cell gets more room than a month cell because it is the only row on screen: the same
     * seven days, drawn tall instead of packed into six rows.
     */
    private fun maxChips(mode: CalendarMode): Int = when (mode) {
        CalendarMode.MONTH -> MONTH_CHIPS
        CalendarMode.WEEK, CalendarMode.DAY -> WEEK_CHIPS
    }

    private fun cellHeightPx(mode: CalendarMode, density: Float): Int =
        ((if (mode == CalendarMode.WEEK) WEEK_CELL_DP else MONTH_CELL_DP) * density).toInt()

    companion object {
        private const val MONTH_CHIPS = 3
        private const val WEEK_CHIPS = 6
        // Tall enough for the day number, the chips, the "+N more" line, the counts line and the full
        // day badge at the text sizes the layouts use: a cell that clips its own badge is a cell that
        // hides the thing it was trying to say.
        private const val MONTH_CELL_DP = 124
        private const val WEEK_CELL_DP = 178
        private const val NOTE_SEPARATOR = " · "
        private const val NEIGHBOUR_ALPHA = 0.45f
        private const val FADED_WHILE_DRAGGING = 0.5f
    }
}
