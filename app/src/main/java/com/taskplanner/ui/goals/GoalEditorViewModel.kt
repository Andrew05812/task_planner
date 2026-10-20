package com.taskplanner.ui.goals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskplanner.data.model.Goal
import com.taskplanner.data.model.GoalType
import com.taskplanner.data.repository.GoalRejection
import com.taskplanner.data.repository.GoalRepository
import com.taskplanner.data.repository.GoalWriteResult
import com.taskplanner.ui.util.WorkInstantInput
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The form's view of the goal it was opened on.
 *
 * [goal] is the stored row, and the only figure the screen reads from it; nothing here is derived, because
 * what a goal has reached is not a field the user can set. [isMissing] is the case the screen cannot write
 * its way out of: the row it opened on is gone, and saving would create an aim the user believes they were
 * editing.
 */
data class GoalEditorState(
    val goal: Goal? = null,
    val isEditing: Boolean = false,
    val isMissing: Boolean = false,
    val isLoading: Boolean = true,
    val error: String? = null
) {
    /** The empty form: no row was asked for, or the screen was opened on an id no row holds. */
    val isNew: Boolean get() = !isLoading && goal == null && !isMissing

    /** Whether the reason field has anything to do; a new aim changes nothing, so it explains nothing. */
    val asksForReason: Boolean get() = goal != null
}

/** One of the form's own fields, named for the case where its text reads as no figure or no day. */
enum class GoalEditorField { TARGET, WINDOW_FROM, WINDOW_TO, DEADLINE }

/**
 * What asking the editor to save produced.
 *
 * Two answers, and the screen keeps them apart. [Unreadable] is the form's fault: a field holds text that
 * names no figure or no day, so nothing was written and the number the user typed is not in the database.
 * [Written] is the repository's answer to a request it could read, and carries its own refusals — a blank
 * title, a window that ends before it starts — in the same words the detail screen uses for the same reasons.
 */
sealed class GoalSaveOutcome {
    data class Written(val result: GoalWriteResult) : GoalSaveOutcome()
    data class Unreadable(val field: GoalEditorField) : GoalSaveOutcome()
}

/**
 * The form over one goal: the read of the row being edited, and the two writes it stands for.
 *
 * The texts are read here rather than in the fragment because reading them is the part worth testing. An
 * off-by-one in a window day or a unit dropped from a time target lands silently in the stored row and shows
 * on the card as a figure that is simply wrong; a fragment that parsed its own fields could only be caught by
 * pressing the screen.
 *
 * [save] keeps the goal's set out of the picture. Naming and un-naming tasks is the detail screen's work —
 * the editor moves the aim, not the list of things it asks for.
 */
@HiltViewModel
class GoalEditorViewModel @Inject constructor(
    private val goalRepository: GoalRepository
) : ViewModel() {

    /**
     * One optional field, read.
     *
     * [Blank] and [Bad] have to stay distinct: a window left open at one end is what the user asked for,
     * while text that names no day is a mistake the save must refuse. A plain null cannot carry both, and a
     * screen that guessed would write a closed window over an open one.
     */
    private sealed class Field<out T> {
        object Blank : Field<Nothing>()
        data class Filled<T>(val value: T) : Field<T>()
        data class Bad(val field: GoalEditorField) : Field<Nothing>()
    }

    /** The id this screen was opened on, so a configuration change does not read the row a second time. */
    private var askedId: Long? = null

    private val _state = MutableStateFlow(GoalEditorState())
    val state: StateFlow<GoalEditorState> = _state.asStateFlow()

    /**
     * Opens the goal the navigation handed over, or the empty form.
     *
     * A non-positive id is how the graph says "a new aim": the list and the detail screen both navigate here,
     * and a number this device never gave a row is the one answer that cannot be mistaken for a goal that was
     * thrown away.
     */
    fun load(goalId: Long) {
        if (askedId == goalId) return
        askedId = goalId
        if (goalId <= 0L) {
            _state.update { GoalEditorState(isLoading = false) }
            return
        }
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            runCatching { goalRepository.goalById(goalId) }
                .onSuccess { goal ->
                    _state.update {
                        it.copy(
                            goal = goal,
                            isEditing = true,
                            isMissing = goal == null,
                            isLoading = false,
                            error = null
                        )
                    }
                }
                .onFailure { error -> _state.update { it.copy(error = error.message, isLoading = false) } }
        }
    }

    /**
     * Writes the form, or says which field it could not read.
     *
     * Every field is read before anything is written, so a mistyped deadline cannot leave a new title already
     * stored. An empty figure is 0 rather than an absent one: the two types that name tasks take their
     * denominator from the set itself, so a goal saved before the user typed a number is a goal with nothing
     * reached yet, not a half-filled row.
     */
    fun save(
        title: String,
        type: GoalType,
        targetText: String?,
        windowFromText: String?,
        windowToText: String?,
        deadlineText: String?,
        reason: String?,
        onResult: (GoalSaveOutcome) -> Unit
    ) {
        val current = _state.value
        if (current.isMissing) {
            onResult(GoalSaveOutcome.Written(GoalWriteResult.Rejected(GoalRejection.NOT_FOUND)))
            return
        }
        val target = when (val read = read(targetText, GoalEditorField.TARGET) { it.toLongOrNull() }) {
            is Field.Blank -> 0L
            is Field.Filled -> read.value
            is Field.Bad -> {
                onResult(GoalSaveOutcome.Unreadable(read.field))
                return
            }
        }
        val windowFrom = when (val read = read(windowFromText, GoalEditorField.WINDOW_FROM) {
            WorkInstantInput.parseDay(it)?.toEpochDay()
        }) {
            is Field.Blank -> null
            is Field.Filled -> read.value
            is Field.Bad -> {
                onResult(GoalSaveOutcome.Unreadable(read.field))
                return
            }
        }
        val windowTo = when (val read = read(windowToText, GoalEditorField.WINDOW_TO) {
            WorkInstantInput.parseDay(it)?.toEpochDay()
        }) {
            is Field.Blank -> null
            is Field.Filled -> read.value
            is Field.Bad -> {
                onResult(GoalSaveOutcome.Unreadable(read.field))
                return
            }
        }
        val deadline = when (val read = read(deadlineText, GoalEditorField.DEADLINE) {
            WorkInstantInput.parse(it)
        }) {
            is Field.Blank -> null
            is Field.Filled -> read.value
            is Field.Bad -> {
                onResult(GoalSaveOutcome.Unreadable(read.field))
                return
            }
        }

        val goalUuid = current.goal?.uuid
        val note = reason?.trim()?.takeIf { it.isNotEmpty() }
        viewModelScope.launch {
            val body = if (goalUuid == null) {
                goalRepository.create(
                    title = title,
                    type = type,
                    targetValue = target,
                    windowFromDay = windowFrom,
                    windowToDay = windowTo,
                    deadlineAt = deadline
                )
            } else {
                goalRepository.edit(
                    goalUuid = goalUuid,
                    title = title,
                    type = type,
                    targetValue = target,
                    windowFromDay = windowFrom,
                    windowToDay = windowTo,
                    deadlineAt = deadline,
                    reason = note
                )
            }
            runCatching { body }
                .onSuccess { onResult(GoalSaveOutcome.Written(it)) }
                .onFailure { error -> _state.update { it.copy(error = error.message) } }
        }
    }

    /** Asks for the row again after a read that could not be attempted. */
    fun retry() {
        val goalId = askedId ?: return
        askedId = null
        _state.update { it.copy(isLoading = true, error = null) }
        load(goalId)
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    /** [parse] answers null for text that names no value, which is the only thing this cannot read. */
    private fun <T : Any> read(text: String?, field: GoalEditorField, parse: (String) -> T?): Field<T> {
        val value = text?.trim()?.takeIf { it.isNotEmpty() } ?: return Field.Blank
        return parse(value)?.let { Field.Filled(it) } ?: Field.Bad(field)
    }
}
