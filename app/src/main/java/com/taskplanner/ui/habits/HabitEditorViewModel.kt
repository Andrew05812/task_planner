package com.taskplanner.ui.habits

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskplanner.data.model.Habit
import com.taskplanner.data.model.HabitFrequency
import com.taskplanner.data.repository.HabitRejection
import com.taskplanner.data.repository.HabitRepository
import com.taskplanner.data.repository.HabitWriteResult
import com.taskplanner.ui.util.WorkInstantInput
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.DayOfWeek
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The form's view of the habit it was opened on.
 *
 * [habit] is the stored row and the only figure the screen reads from it: the series and the percentage are
 * not fields the user can set, and a form that showed them would look like a field that had been left wrong.
 * [remindMinuteOfDay] is the one field the screen holds by itself rather than in a widget, because the time
 * is picked by a dialog that returns two numbers and forgets them — a rotation would otherwise drop the minute
 * the user just chose while the button beside it still said seven o'clock.
 */
data class HabitEditorState(
    val habit: Habit? = null,
    val isEditing: Boolean = false,
    val isMissing: Boolean = false,
    val remindMinuteOfDay: Int? = null,
    val isLoading: Boolean = true,
    val error: String? = null
) {
    /** The empty form: no row was asked for, or the screen was opened on an id no row holds. */
    val isNew: Boolean get() = !isLoading && habit == null && !isMissing

    /** Whether the habit is still being asked for, which is the only state a reminder is armed from. */
    val isRemindable: Boolean get() = habit?.isOpen != false
}

/** One of the form's own fields, named for the case where its text reads as no day or no count. */
enum class HabitEditorField { START_DAY, END_DAY, TIMES_PER_WEEK }

/**
 * What asking the editor to save produced.
 *
 * Two answers, and the screen keeps them apart. [Unreadable] is the form's fault: a field holds text that
 * names no day or no count, so nothing was written and what the user typed is not in the database. [Written]
 * is the repository's answer to a request it could read, and carries its own refusals — a blank title, a
 * weekday set with no weekday in it, a window that ends before it starts — in the same words the detail
 * screen uses for the same reasons.
 */
sealed class HabitSaveOutcome {
    data class Written(val result: HabitWriteResult) : HabitSaveOutcome()
    data class Unreadable(val field: HabitEditorField) : HabitSaveOutcome()
}

/**
 * The form over one habit: the read of the row being edited, and the two writes it stands for.
 *
 * The texts are read here rather than in the fragment because reading them is the part worth testing. An
 * off-by-one in the start day, a count dropped from a weekly habit, or a reminder minute stored as an instant
 * all land silently in the stored row and show on the card as a schedule that is simply wrong; a fragment that
 * parsed its own fields could only be caught by pressing the screen.
 *
 * [save] keeps the marks out of the picture. Keeping, taking back and moving a day are the detail screen's
 * work — the editor moves the schedule, not the days the user already stood behind.
 */
@HiltViewModel
class HabitEditorViewModel @Inject constructor(
    private val habitRepository: HabitRepository
) : ViewModel() {

    /** The id this screen was opened on, so a configuration change does not read the row a second time. */
    private var askedId: Long? = null

    private val _state = MutableStateFlow(HabitEditorState())
    val state: StateFlow<HabitEditorState> = _state.asStateFlow()

    /**
     * Opens the habit the navigation handed over, or the empty form.
     *
     * A non-positive id is how the graph says "a new habit": the list's add button and the detail screen's
     * edit button both come here, and a number this device never gave a row is the one answer that cannot be
     * mistaken for a habit that was thrown away.
     */
    fun load(habitId: Long) {
        if (askedId == habitId) return
        askedId = habitId
        if (habitId <= 0L) {
            _state.update { it.copy(isLoading = false) }
            return
        }
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            runCatching { habitRepository.habitById(habitId) }
                .onSuccess { habit ->
                    _state.update {
                        it.copy(
                            habit = habit,
                            isEditing = true,
                            isMissing = habit == null,
                            // The minute the row carries, so the button beside the field says what the alarm
                            // is armed for rather than what the last dialog happened to show.
                            remindMinuteOfDay = habit?.remindMinuteOfDay,
                            isLoading = false,
                            error = null
                        )
                    }
                }
                .onFailure { error -> _state.update { it.copy(error = error.message, isLoading = false) } }
        }
    }

    /** Stores the minute the picker answered with, for the save to write and the button to show. */
    fun setReminder(minuteOfDay: Int) {
        _state.update { it.copy(remindMinuteOfDay = minuteOfDay) }
    }

    /** Says plainly that this habit is not to be reminded at all. */
    fun clearReminder() {
        _state.update { it.copy(remindMinuteOfDay = null) }
    }

    /**
     * Writes the form, or says which field it could not read.
     *
     * Every field is read before anything is written, so a mistyped end day cannot leave a new title already
     * stored. A blank start day is today rather than an absent one: a habit begun from the day the user chose
     * it is the ordinary case, and a row with no first day is a schedule nothing can be asked from.
     */
    fun save(
        title: String,
        frequency: HabitFrequency,
        daysOfWeek: List<DayOfWeek>,
        timesPerWeekText: String?,
        startDayText: String?,
        endDayText: String?,
        onResult: (HabitSaveOutcome) -> Unit
    ) {
        val current = _state.value
        if (current.isMissing) {
            onResult(HabitSaveOutcome.Written(HabitWriteResult.Rejected(HabitRejection.NOT_FOUND)))
            return
        }
        val today = habitRepository.todayEpochDay()
        // A blank start is today and a blank end is no end at all; text that names no day is the third case,
        // and the only one nothing is written for. The three have to stay distinguishable, which is why the
        // form reads its optional fields through the same three-shaped answer the goal editor uses.
        val startDay = when (val read = read(startDayText, HabitEditorField.START_DAY) {
            WorkInstantInput.parseDay(it)?.toEpochDay()
        }) {
            is Field.Blank -> today
            is Field.Filled -> read.value
            is Field.Bad -> return onResult(HabitSaveOutcome.Unreadable(read.field))
        }
        val endDay = when (val read = read(endDayText, HabitEditorField.END_DAY) {
            WorkInstantInput.parseDay(it)?.toEpochDay()
        }) {
            is Field.Blank -> null
            is Field.Filled -> read.value
            is Field.Bad -> return onResult(HabitSaveOutcome.Unreadable(read.field))
        }
        val timesPerWeek = when (frequency) {
            HabitFrequency.TIMES_PER_WEEK -> {
                val count = timesPerWeekText?.trim()?.takeIf { it.isNotEmpty() }?.toIntOrNull()
                    ?.takeIf { it in 1..DAYS_IN_WEEK }
                    ?: return onResult(
                        HabitSaveOutcome.Unreadable(HabitEditorField.TIMES_PER_WEEK)
                    )
                count
            }
            else -> null
        }

        val habitUuid = current.habit?.uuid
        val remindMinuteOfDay = current.remindMinuteOfDay
        viewModelScope.launch {
            val body = if (habitUuid == null) {
                habitRepository.create(
                    title = title,
                    frequency = frequency,
                    startDay = startDay,
                    daysOfWeek = daysOfWeek,
                    timesPerWeek = timesPerWeek,
                    endDay = endDay,
                    remindMinuteOfDay = remindMinuteOfDay
                )
            } else {
                habitRepository.edit(
                    habitUuid = habitUuid,
                    title = title,
                    frequency = frequency,
                    startDay = startDay,
                    daysOfWeek = daysOfWeek,
                    timesPerWeek = timesPerWeek,
                    endDay = endDay,
                    remindMinuteOfDay = remindMinuteOfDay
                )
            }
            runCatching { body }
                .onSuccess { onResult(HabitSaveOutcome.Written(it)) }
                .onFailure { error -> _state.update { it.copy(error = error.message) } }
        }
    }

    /** Asks for the row again after a read that could not be attempted. */
    fun retry() {
        val habitId = askedId ?: return
        askedId = null
        _state.update { it.copy(isLoading = true, error = null) }
        load(habitId)
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    /**
     * One optional field, read.
     *
     * [Blank] and [Bad] have to stay distinct: a habit left open at one end is what the user asked for, while
     * text that names no day is a mistake the save must refuse. A plain null cannot carry both, and a form that
     * guessed would close a window the user meant to leave open.
     */
    private sealed class Field<out T> {
        object Blank : Field<Nothing>()
        data class Filled<T>(val value: T) : Field<T>()
        data class Bad(val field: HabitEditorField) : Field<Nothing>()
    }

    /** [parse] answers null for text that names no value, which is the only thing this cannot read. */
    private fun <T : Any> read(text: String?, field: HabitEditorField, parse: (String) -> T?): Field<T> {
        val value = text?.trim()?.takeIf { it.isNotEmpty() } ?: return Field.Blank
        return parse(value)?.let { Field.Filled(it) } ?: Field.Bad(field)
    }

    private companion object {
        private const val DAYS_IN_WEEK = 7
    }
}
