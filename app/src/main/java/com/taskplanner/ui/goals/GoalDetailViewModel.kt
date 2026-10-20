package com.taskplanner.ui.goals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskplanner.data.local.GoalTaskCandidate
import com.taskplanner.data.local.GoalTaskRow
import com.taskplanner.data.model.Goal
import com.taskplanner.data.model.GoalValueChange
import com.taskplanner.data.repository.GoalProgress
import com.taskplanner.data.repository.GoalRejection
import com.taskplanner.data.repository.GoalRepository
import com.taskplanner.data.repository.GoalWithProgress
import com.taskplanner.data.repository.GoalWriteResult
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * One goal: its row, the figure it has reached, the tasks it names, and the story of its targets.
 *
 * [goal] and [progress] are the two halves of one read, which is the only way the card above the lists can
 * be sure its denominator and its bar came from the same query. The two lists are keyed on the goal's uuid
 * rather than on its figure, so a task finishing — which moves the progress and nothing else — does not make
 * the screen re-subscribe to the joins beneath it.
 */
data class GoalDetailState(
    val goal: Goal? = null,
    val progress: GoalProgress? = null,
    val tasks: List<GoalTaskRow> = emptyList(),
    val changes: List<GoalValueChange> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null
) {
    /** The goal this screen was opened on is gone, as distinct from the screen still waiting for it. */
    val isMissing: Boolean get() = !isLoading && error == null && goal == null
}

/**
 * The detail screen's read and every write a goal can take.
 *
 * Archiving, throwing away, and the set are all here rather than on the list, because they are the actions
 * whose words depend on what the goal has reached: a card cannot say "this leaves the list, and its figure
 * moves with it" without the figure.
 *
 * A write's answer goes back to the caller rather than into the state, since the same refusal needs a
 * different sentence on a dialog than on the screen behind it. Only an exception — a read or write that could
 * not be attempted at all — lands in [GoalDetailState.error].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class GoalDetailViewModel @Inject constructor(
    private val goalRepository: GoalRepository
) : ViewModel() {

    /** The row this screen was opened on, by the id this device gave it. Null until the screen picks one. */
    private val goalId = MutableStateFlow<Long?>(null)

    /** Bumped by [retry] so a failed read can be asked for again without changing what is being read. */
    private val attempt = MutableStateFlow(0)

    /** The uuid of the row that arrived, which is what the set and the history are keyed on. */
    private val goalUuid = MutableStateFlow<String?>(null)

    private val _state = MutableStateFlow(GoalDetailState())
    val state: StateFlow<GoalDetailState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(goalId, attempt) { id, tried -> id to tried }
                .distinctUntilChanged()
                .flatMapLatest { (id, _) ->
                    // An id the screen never asked for reads nothing. Null is the answer that leaves the
                    // spinner up, rather than a query for a row number nobody named.
                    if (id == null) {
                        flowOf<GoalWithProgress?>(null)
                    } else {
                        goalRepository.observeGoalWithProgress(id)
                    }
                }
                .catch { error -> _state.update { it.copy(error = error.message, isLoading = false) } }
                .collect { loaded ->
                    _state.update {
                        it.copy(
                            goal = loaded?.goal,
                            progress = loaded?.progress,
                            isLoading = false,
                            error = null
                        )
                    }
                    goalUuid.value = loaded?.goal?.uuid
                }
        }

        viewModelScope.launch {
            goalUuid
                .flatMapLatest { uuid ->
                    if (uuid == null) {
                        flowOf(emptyList<GoalTaskRow>() to emptyList<GoalValueChange>())
                    } else {
                        combine(
                            goalRepository.observeTasksOf(uuid),
                            goalRepository.observeValueChanges(uuid)
                        ) { tasks, changes -> tasks to changes }
                    }
                }
                .catch { error -> _state.update { it.copy(error = error.message) } }
                .collect { (tasks, changes) ->
                    _state.update { it.copy(tasks = tasks, changes = changes) }
                }
        }
    }

    /**
     * Opens the goal the navigation handed over.
     *
     * Asking for the same row twice changes nothing, which matters because a configuration change re-calls
     * this from the saved arguments and a second subscription would show a spinner over figures already on
     * the screen.
     */
    fun load(id: Long) {
        if (goalId.value == id) return
        goalId.value = id
        _state.update { GoalDetailState() }
    }

    fun retry() {
        _state.update { it.copy(isLoading = true, error = null) }
        attempt.update { it + 1 }
    }

    /**
     * Puts the goal away, or brings it back.
     *
     * Which of the two it is comes from the stored row rather than from a button's label, so a screen that
     * was opened before the goal was retired somewhere else cannot write the opposite of what it shows.
     */
    fun setArchived(onResult: (GoalWriteResult) -> Unit = {}) {
        val goal = _state.value.goal ?: return refused(onResult)
        val uuid = goal.uuid
        write(onResult) {
            if (goal.archivedAt != null) goalRepository.unarchive(uuid) else goalRepository.archive(uuid)
        }
    }

    /** Throws the goal away. Its links and its target history stay, and so does the work it counted. */
    fun remove(onResult: (GoalWriteResult) -> Unit = {}) {
        val uuid = _state.value.goal?.uuid ?: return refused(onResult)
        write(onResult) { goalRepository.remove(uuid) }
    }

    /** Stops naming one task. The task itself is untouched, and the goal's figure moves with the link. */
    fun unlink(taskUuid: String, onResult: (GoalWriteResult) -> Unit = {}) {
        val uuid = _state.value.goal?.uuid ?: return refused(onResult)
        write(onResult) { goalRepository.unlink(uuid, taskUuid) }
    }

    /**
     * Names one more task.
     *
     * The link is written by uuid, which is what makes it a reference rather than a copy: the task keeps the
     * one row the rest of the app already reads.
     */
    fun link(taskUuid: String, onResult: (GoalWriteResult) -> Unit = {}) {
        val uuid = _state.value.goal?.uuid ?: return refused(onResult)
        write(onResult) { goalRepository.link(uuid, taskUuid) }
    }

    /**
     * What the goal could still be pointed at, for the picker the button opens.
     *
     * Read once when the dialog is asked for rather than kept in the state: the list is a transient choice,
     * and a screen that held it would have to keep it in step with every task written elsewhere.
     */
    fun linkableTasks(onResult: (List<GoalTaskCandidate>) -> Unit) {
        // Nothing to add to, so nothing to choose from: an empty answer keeps the dialog honest instead of
        // leaving it waiting for a list that never comes.
        val uuid = _state.value.goal?.uuid ?: return onResult(emptyList())
        viewModelScope.launch {
            runCatching { goalRepository.linkableTasks(uuid) }
                .onSuccess(onResult)
                .onFailure { error -> _state.update { it.copy(error = error.message) } }
        }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    /** One write, its answer handed back to the screen that has the words for it. */
    private fun write(onResult: (GoalWriteResult) -> Unit, body: suspend () -> GoalWriteResult) {
        viewModelScope.launch {
            runCatching { body() }
                .onSuccess(onResult)
                .onFailure { error -> _state.update { it.copy(error = error.message) } }
        }
    }

    /**
     * The answer a command gives when this screen holds no goal: the same refusal the repository gives for an
     * id it does not own.
     *
     * A command that returned without an answer would leave the reader with a button that did nothing at all,
     * and the screen's words for "this goal is not here" belong to the caller, as every other write's do.
     */
    private fun refused(onResult: (GoalWriteResult) -> Unit) {
        onResult(GoalWriteResult.Rejected(GoalRejection.NOT_FOUND))
    }
}
