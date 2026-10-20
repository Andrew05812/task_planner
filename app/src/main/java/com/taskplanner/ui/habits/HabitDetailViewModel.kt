package com.taskplanner.ui.habits

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskplanner.data.calendar.CalendarMath
import com.taskplanner.data.habit.HabitMath
import com.taskplanner.data.model.Habit
import com.taskplanner.data.model.HabitCheckChange
import com.taskplanner.data.repository.HabitFigures
import com.taskplanner.data.repository.HabitRejection
import com.taskplanner.data.repository.HabitRepository
import com.taskplanner.data.repository.HabitWriteResult
import com.taskplanner.ui.util.WorkInstantInput
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * One day in the grid, as the screen draws it.
 *
 * The four questions are the ones a cell has to answer before it can be touched: whether the schedule asked
 * for this day ([asked]), whether a mark stands on it ([marked]), whether this screen may put one there
 * ([canMark]), and whether it is the day the habit is being read on ([isToday]). [canMark] is deliberately not
 * [asked] with the mark subtracted — a Sunday mark on a weekday habit is real work the repository accepts, and
 * a grid that disabled the day would refuse the user something they are allowed to assert. What it does
 * exclude is the two days the repository refuses: one outside the habit's window and one that has not arrived.
 */
data class HabitDayCell(
    val day: Long,
    val asked: Boolean,
    val marked: Boolean,
    val canMark: Boolean,
    val isToday: Boolean
)

/**
 * One habit: its row, its figures, the week the grid is showing, and the record of its corrections.
 *
 * [cells] are built here rather than in the fragment because which days a week holds is a date question with
 * an answer the tests can check — the schedule, the window and today all decide it, and a grid that got the
 * window wrong would invite marks the repository has to refuse.
 */
data class HabitDetailState(
    val habit: Habit? = null,
    val figures: HabitFigures? = null,
    val cells: List<HabitDayCell> = emptyList(),
    val weekFrom: Long? = null,
    val weekTo: Long? = null,
    val isCurrentWeek: Boolean = true,
    val changes: List<HabitCheckChange> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null
) {
    /** The habit this screen was opened on is gone, as distinct from the screen still waiting for it. */
    val isMissing: Boolean get() = !isLoading && error == null && habit == null

    /** Whether the habit this card is anchored on has been kept today. */
    val isTodayMarked: Boolean get() = figures?.markedToday == true
}

/** One day asked for a correction, and the two ways the screen can answer. */
sealed class HabitCorrectionOutcome {
    data class Written(val result: HabitWriteResult) : HabitCorrectionOutcome()

    /** The day typed names no date, so nothing was written and the mark still stands where it was. */
    data object UnreadableDay : HabitCorrectionOutcome()
}

/**
 * The detail screen's read, and every write a habit and its days can take.
 *
 * The screen is the only place a mark is made, taken back, or moved, and each of those is a correction as
 * much as an entry: §6 asks for a mark's history to be kept, and the words that explain a moved day belong to
 * the screen showing the grid the day moved out of. Archiving and deleting are here for the same reason —
 * both need the figures on screen to say what stops being asked for.
 *
 * A write's answer goes back to the caller rather than into the state, since the same refusal needs a
 * different sentence on a dialog than on the screen behind it. Only an exception — a read or write that could
 * not be attempted at all — lands in [HabitDetailState.error].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HabitDetailViewModel @Inject constructor(
    private val habitRepository: HabitRepository
) : ViewModel() {

    /** The row this screen was opened on, by the id this device gave it. Null until the screen picks one. */
    private val habitId = MutableStateFlow<Long?>(null)

    /** Bumped by [retry] so a failed read can be asked for again without changing what is being read. */
    private val attempt = MutableStateFlow(0)

    /** The uuid of the row that arrived, which is what the marks and the corrections are keyed on. */
    private val habitUuid = MutableStateFlow<String?>(null)

    /** The kept days of the habit, over its whole life — the grid reads its marks out of this. */
    private val markedDays = MutableStateFlow<Set<Long>>(emptySet())

    /** The anchor day of the week the grid shows, or null for the week the habit is being read in. */
    private val weekAnchor = MutableStateFlow<Long?>(null)

    private val _state = MutableStateFlow(HabitDetailState())
    val state: StateFlow<HabitDetailState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(habitId, attempt) { id, tried -> id to tried }
                .distinctUntilChanged()
                .flatMapLatest { (id, _) ->
                    // An id the screen never asked for reads nothing. Null is the answer that leaves the
                    // spinner up, rather than a query for a row number nobody named.
                    if (id == null) {
                        flowOf(null)
                    } else {
                        val today = habitRepository.todayEpochDay()
                        habitRepository.observeHabitWithFigures(
                            id, HabitCard.periodFrom(today), today
                        )
                    }
                }
                .catch { error -> _state.update { it.copy(error = error.message, isLoading = false) } }
                .collect { loaded ->
                    _state.update {
                        it.copy(
                            habit = loaded?.habit,
                            figures = loaded?.figures,
                            isLoading = false,
                            error = null
                        )
                    }
                    habitUuid.value = loaded?.habit?.uuid
                    redraw(loaded?.habit, loaded?.figures)
                }
        }

        viewModelScope.launch {
            habitUuid
                .flatMapLatest { uuid ->
                    if (uuid == null) {
                        flowOf(emptyList<Long>() to emptyList<HabitCheckChange>())
                    } else {
                        combine(
                            habitRepository.observeAllMarkedDays(uuid),
                            habitRepository.observeCheckChanges(uuid)
                        ) { days, changes -> days to changes }
                    }
                }
                .catch { error -> _state.update { it.copy(error = error.message) } }
                .collect { (days, changes) ->
                    markedDays.value = days.toSet()
                    _state.update { it.copy(changes = changes) }
                    redraw(_state.value.habit, _state.value.figures)
                }
        }

        viewModelScope.launch {
            weekAnchor.collect { redraw(_state.value.habit, _state.value.figures) }
        }
    }

    /**
     * Opens the habit the navigation handed over.
     *
     * Asking for the same row twice changes nothing, which matters because a configuration change re-calls
     * this from the saved arguments — and because the notification's deep link lands here with the same id the
     * screen is already showing.
     */
    fun load(id: Long) {
        if (habitId.value == id) return
        habitId.value = id
        weekAnchor.value = null
        markedDays.value = emptySet()
        _state.update { HabitDetailState() }
    }

    fun retry() {
        _state.update { it.copy(isLoading = true, error = null) }
        attempt.update { it + 1 }
    }

    /**
     * Walks the grid to another week.
     *
     * Negative steps look back through the habit's life; the current week is as far forward as the screen
     * goes, because every day after it is one the repository will refuse a mark for. A step that overshoots
     * that edge lands *on* the current week rather than staying where it was: the walk has an end, and a grid
     * that refused to move at all would leave the user tapping a button that does nothing. Snapping the
     * anchor back onto a week start is what keeps the seven cells a week rather than a window sliding one day
     * at a time — the same reasoning that makes the repository count a weekly habit's marks by whole weeks.
     */
    fun shiftWeek(steps: Int) {
        if (steps == 0) return
        val figures = _state.value.figures ?: return
        val firstDay = CalendarMath.firstDayOfWeek()
        val anchor = weekAnchor.value ?: figures.today
        val wanted = anchor + steps.toLong() * DAYS_IN_WEEK
        val currentWeekStart = HabitMath.weekStart(figures.today, firstDay)
        val targetWeekStart = HabitMath.weekStart(wanted, firstDay)
        val clamped = if (targetWeekStart > currentWeekStart) currentWeekStart else wanted
        weekAnchor.value = HabitMath.weekStart(clamped, firstDay)
    }

    /** Back to the week the habit is being read in, which is the week [figures] are anchored on. */
    fun showCurrentWeek() {
        weekAnchor.value = null
    }

    /** Keeps the day the card is anchored on — the day the reminder's own action stands for. */
    fun markToday(onResult: (HabitWriteResult) -> Unit = {}) {
        val habit = _state.value.habit ?: return refused(onResult)
        val day = _state.value.figures?.today ?: return refused(onResult)
        write(habit.uuid, onResult) { habitRepository.mark(it, day) }
    }

    /**
     * Keeps one day the grid offers.
     *
     * The day comes from the cell the user touched rather than from today, because the point of a grid is
     * that a habit is kept for the day the work happened on, and the repository's two refusals — outside the
     * window, in the future — are the only limits a screen may apply here. A day the schedule did not name is
     * accepted: real work is never refused for falling outside the plan.
     */
    fun mark(day: Long, onResult: (HabitWriteResult) -> Unit = {}) {
        val habit = _state.value.habit ?: return refused(onResult)
        write(habit.uuid, onResult) { habitRepository.mark(it, day) }
    }

    /**
     * Applies the correction form to one kept day.
     *
     * An empty day leaves the mark where it stands and takes it back; a written day moves it. That is the
     * form's one rule, and it is read here rather than in the fragment because the difference is a date
     * question with a database consequence: a form that guessed would silently retire a mark the user meant
     * to move.
     */
    fun correctMark(
        fromDay: Long,
        moveToText: String?,
        reason: String?,
        onResult: (HabitCorrectionOutcome) -> Unit
    ) {
        val habit = _state.value.habit ?: run {
            onResult(HabitCorrectionOutcome.Written(rejection(HabitRejection.NOT_FOUND)))
            return
        }
        val note = reason?.trim()?.takeIf { it.isNotEmpty() }
        val text = moveToText?.trim()?.takeIf { it.isNotEmpty() }
        val toDay = if (text == null) {
            null
        } else {
            WorkInstantInput.parseDay(text)?.toEpochDay()
                ?: return onResult(HabitCorrectionOutcome.UnreadableDay)
        }
        val answer: (HabitWriteResult) -> Unit = { onResult(HabitCorrectionOutcome.Written(it)) }
        write(habit.uuid, answer) { uuid ->
            if (toDay == null) habitRepository.takeBack(uuid, fromDay, note)
            else habitRepository.moveMark(uuid, fromDay, toDay, note)
        }
    }

    /**
     * Puts the habit away, or brings it back.
     *
     * Which of the two it is comes from the stored row rather than from a button's label, so a screen that was
     * opened before the habit was retired somewhere else cannot write the opposite of what it shows. The marks
     * stay either way — putting a habit away stops the asking and erases nothing the user did.
     */
    fun setArchived(onResult: (HabitWriteResult) -> Unit = {}) {
        val habit = _state.value.habit ?: return refused(onResult)
        write(habit.uuid, onResult) {
            if (habit.archivedAt != null) habitRepository.unarchive(it) else habitRepository.archive(it)
        }
    }

    /** Throws the habit away, and retires its live marks with it, as the repository defines the pair. */
    fun remove(onResult: (HabitWriteResult) -> Unit = {}) {
        val habit = _state.value.habit ?: return refused(onResult)
        write(habit.uuid, onResult) { habitRepository.remove(it) }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    /**
     * Rebuilds the week the grid shows.
     *
     * The marks, the schedule and the anchored week are three reads that land at three different moments, and
     * the seven cells are a question only all three can answer — so every one of them calls this, and the
     * cells are always drawn from the state as it stands rather than from a snapshot of the moment the last
     * one arrived.
     */
    private fun redraw(habit: Habit?, figures: HabitFigures?) {
        if (habit == null || figures == null) {
            _state.update { it.copy(cells = emptyList(), weekFrom = null, weekTo = null) }
            return
        }
        val firstDay = CalendarMath.firstDayOfWeek()
        val anchor = weekAnchor.value ?: figures.today
        val range = HabitMath.weekRange(anchor, firstDay)
        val marked = markedDays.value
        _state.update {
            it.copy(
                weekFrom = range.first,
                weekTo = range.last,
                isCurrentWeek = range.first == HabitMath.weekStart(figures.today, firstDay),
                cells = range.map { day ->
                    HabitDayCell(
                        day = day,
                        asked = HabitMath.isAskedOn(habit, day),
                        marked = day in marked,
                        canMark = habit.isOpen && habit.covers(day) && day <= figures.today,
                        isToday = day == figures.today
                    )
                }
            )
        }
    }

    /** One write against the habit this screen holds, its answer handed back to the screen that has the words. */
    private fun write(
        habitUuid: String,
        onResult: (HabitWriteResult) -> Unit,
        body: suspend (String) -> HabitWriteResult
    ) {
        viewModelScope.launch {
            runCatching { body(habitUuid) }
                .onSuccess(onResult)
                .onFailure { error -> _state.update { it.copy(error = error.message) } }
        }
    }

    /**
     * The answer a command gives when this screen holds no habit: the same refusal the repository gives for an
     * id it does not own.
     *
     * A command that returned without an answer would leave the reader with a button that did nothing at all,
     * and the screen's words for "this habit is not here" belong to the caller, as every write's do.
     */
    private fun refused(onResult: (HabitWriteResult) -> Unit) {
        onResult(rejection(HabitRejection.NOT_FOUND))
    }

    private fun rejection(reason: HabitRejection): HabitWriteResult = HabitWriteResult.Rejected(reason)

    private companion object {
        private const val DAYS_IN_WEEK = 7L
    }
}
