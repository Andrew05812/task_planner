package com.taskplanner.ui.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskplanner.data.calendar.CalendarAggregator
import com.taskplanner.data.calendar.CalendarMath
import com.taskplanner.data.calendar.CalendarRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * How much of the time line the calendar shows at once.
 *
 * The three levels share one anchor and one aggregation, so switching between them cannot change
 * which tasks a day contains: only how many days are on screen and whether the selected day is
 * drawn as a column of blocks.
 */
enum class CalendarMode { MONTH, WEEK, DAY }

/**
 * Everything the calendar screen draws, in one immutable value.
 *
 * [cells] is the visible grid exactly as the data layer computed it, so a cell's counts, colours and
 * overload flag are the repository's answer rather than something the screen re-derived. [dayPlan]
 * is the selected day's column, kept live even in the month view because tapping a cell is how the
 * user reads a day without leaving the month.
 */
data class CalendarState(
    val mode: CalendarMode,
    val anchor: LocalDate,
    val selected: LocalDate,
    val cells: List<CalendarAggregator.DayCell> = emptyList(),
    val dayPlan: CalendarAggregator.DayPlan? = null,
    /**
     * A drag the repository would not perform, waiting for the screen to say so once.
     *
     * It is a flag and not an exception or a flow of its own because the refusal is a fact about the
     * render the user is looking at: it must not be shown twice, and it must not come back on the
     * next emission.
     */
    val dragRefused: Boolean = false
) {
    /** The cell the day view is drawn from: the selected day, which is the only day that grid holds. */
    val selectedCell: CalendarAggregator.DayCell?
        get() = cells.firstOrNull { it.date == selected }
}

/**
 * The calendar screen's state: which page and which day are shown, the grid's cells, the selected
 * day's column, and the one write the screen performs by itself.
 *
 * The screen's choices and the data they read are kept apart on purpose. `screen` is the only place
 * a mode, a page or a selection lives, and the state flow is built from it with `flatMapLatest`, so
 * changing the page swaps the repository flow the grid comes from instead of adding a second source
 * of days the screen could disagree with. Nothing on the fragment can ask for a reload: a drag, an
 * editor save, a sync pull or a recurring series producing its next instance all land in the same
 * bounded window query the grid is already observing, and its re-emission is what repaints the
 * screen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CalendarViewModel @Inject constructor(
    private val calendarRepository: CalendarRepository
) : ViewModel() {

    private data class Screen(
        val mode: CalendarMode,
        val anchor: LocalDate,
        val selected: LocalDate
    )

    private data class Rendered(
        val screen: Screen,
        val cells: List<CalendarAggregator.DayCell>,
        val dayPlan: CalendarAggregator.DayPlan
    )

    /** The day the calendar opened on, taken once: a screen does not silently slide sideways at midnight. */
    private val today: LocalDate = LocalDate.now(CalendarMath.systemZone())

    private val screen = MutableStateFlow(Screen(CalendarMode.MONTH, today, today))

    private val _state = MutableStateFlow(CalendarState(CalendarMode.MONTH, today, today))
    val state: StateFlow<CalendarState> = _state.asStateFlow()

    init {
        // One collector for the whole screen, keyed by the screen's own choices: every mode, page and
        // selection change re-subscribes the two window queries, and every write refreshes them.
        viewModelScope.launch {
            screen.flatMapLatest { current ->
                combine(
                    calendarRepository.observeCells(visibleGrid(current), displayedMonth(current)),
                    calendarRepository.observeDay(current.selected)
                ) { cells, dayPlan -> Rendered(current, cells, dayPlan) }
            }.collect { rendered ->
                _state.update {
                    it.copy(
                        mode = rendered.screen.mode,
                        anchor = rendered.screen.anchor,
                        selected = rendered.screen.selected,
                        cells = rendered.cells,
                        dayPlan = rendered.dayPlan
                    )
                }
            }
        }
    }

    /**
     * Zooms to another level without losing the day the user is reading.
     *
     * A month, a week and a day are three magnifications of one place on the time line, so switching
     * must not re-pick a day. It does move the page when the day being read is not on it: a week that
     * does not hold the day the user was reading is a page they would have to find again by hand.
     */
    fun setMode(mode: CalendarMode) {
        screen.update { current ->
            val anchor = when (mode) {
                CalendarMode.MONTH ->
                    if (YearMonth.from(current.anchor) == YearMonth.from(current.selected)) current.anchor
                    else current.selected

                CalendarMode.WEEK ->
                    if (CalendarMath.weekGrid(current.anchor).contains(current.selected)) current.anchor
                    else current.selected

                CalendarMode.DAY -> current.selected
            }
            Screen(mode, anchor, current.selected)
        }
    }

    /**
     * Reads [date]. In the day view this is also how the column pages, because there the selected day
     * is the whole screen, so the page follows it instead of drifting out from under it.
     */
    fun select(date: LocalDate) {
        screen.update { current ->
            if (current.mode == CalendarMode.DAY) Screen(current.mode, date, date)
            else current.copy(selected = date)
        }
    }

    /** One page backwards in the current mode: a month, a week, a day. */
    fun previous() = shift(-1)

    /** One page forwards in the current mode: a month, a week, a day. */
    fun next() = shift(1)

    /** Back to the day the user lives on, page and selection both. */
    fun goToday() {
        screen.update { Screen(it.mode, today, today) }
    }

    /**
     * Drag-and-drop: the task lands on [date] and the grid repaints from the repository's own flow.
     *
     * All of the date maths — wall-clock preserving move, reminders re-pointed, the row marked for
     * sync — belongs to [CalendarRepository.moveToDay]; the screen only reports a refusal, because a
     * drag that silently did nothing would look like a drag that worked.
     */
    fun moveTask(taskId: Long, date: LocalDate) {
        viewModelScope.launch {
            if (!calendarRepository.moveToDay(taskId, date)) {
                _state.update { it.copy(dragRefused = true) }
            }
        }
    }

    /** Takes the refused-drag message off the state once the screen has shown it. */
    fun clearDragRefused() {
        _state.update { it.copy(dragRefused = false) }
    }

    private fun shift(steps: Int) {
        screen.update { current ->
            when (current.mode) {
                // Paging a month or a week moves the page and leaves the selected day where it was:
                // the day the user was reading stays readable while they look around the calendar.
                CalendarMode.MONTH -> current.copy(anchor = current.anchor.plusMonths(steps.toLong()))
                CalendarMode.WEEK -> current.copy(anchor = current.anchor.plusWeeks(steps.toLong()))
                CalendarMode.DAY -> current.selected.plusDays(steps.toLong())
                    .let { Screen(current.mode, it, it) }
            }
        }
    }

    /** The days on screen for these choices, in the order the grid lays them out. */
    private fun visibleGrid(screen: Screen): List<LocalDate> = when (screen.mode) {
        CalendarMode.MONTH -> CalendarMath.monthGrid(YearMonth.from(screen.anchor))
        CalendarMode.WEEK -> CalendarMath.weekGrid(screen.anchor)
        // The day view holds exactly the day it shows; its cell feeds the counts above the column.
        CalendarMode.DAY -> listOf(screen.selected)
    }

    /**
     * The month the cells are judged against for "this month's own day".
     *
     * Only the month view borrows neighbouring days for the edges of the grid, so only there should
     * they be de-emphasised: a week page has no month to belong to.
     */
    private fun displayedMonth(screen: Screen): YearMonth? =
        if (screen.mode == CalendarMode.MONTH) YearMonth.from(screen.anchor) else null
}
