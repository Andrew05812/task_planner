package com.taskplanner.ui.goals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskplanner.data.repository.GoalRepository
import com.taskplanner.data.repository.GoalWithProgress
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The goal list: the aims the profile holds and how far each has reached right now.
 *
 * [goals] arrives already carrying its figures. The screen keeps no total of its own: every card is one
 * `GoalWithProgress` from the repository, whose aggregate re-runs when the work under it moves, which is
 * what makes finishing, cancelling or deleting a task change the card in the same frame rather than on the
 * next refresh.
 *
 * [showArchived] switches which list is read rather than filtering the one already loaded. A retired aim is
 * a different question — "what was I working toward" — and its rows are not in the open list to begin with.
 */
data class GoalsState(
    val showArchived: Boolean = false,
    val goals: List<GoalWithProgress> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null
) {
    val isEmpty: Boolean get() = !isLoading && error == null && goals.isEmpty()
}

/**
 * The list screen's read, and nothing else.
 *
 * The screen writes no goal. Putting a goal away and throwing one away need the figures and the target
 * history that only the detail screen shows, and a card that offered both from a dialog would have to
 * explain them again there. What is left for the list is the question it can answer on its own: which goals,
 * and how far — with [retry] for the one case where even that could not be read.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class GoalsViewModel @Inject constructor(
    private val goalRepository: GoalRepository
) : ViewModel() {

    private val showArchived = MutableStateFlow(false)

    /** Bumped by [retry] so a failed read can be asked for again without touching the switch. */
    private val attempt = MutableStateFlow(0)

    private val _state = MutableStateFlow(GoalsState())
    val state: StateFlow<GoalsState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            // A value key rather than the whole state, so a write landing on one card does not make the
            // screen re-subscribe to a list query that has not changed.
            combine(showArchived, attempt) { archived, tried -> archived to tried }
                .distinctUntilChanged()
                .flatMapLatest { (archived, _) ->
                    val source = if (archived) {
                        goalRepository.observeArchivedGoals()
                    } else {
                        goalRepository.observeOpenGoals()
                    }
                    source.map { goals -> archived to goals }
                }
                .catch { error -> _state.update { it.copy(error = error.message, isLoading = false) } }
                .collect { (archived, goals) ->
                    _state.update {
                        it.copy(
                            showArchived = archived,
                            goals = goals,
                            isLoading = false,
                            error = null
                        )
                    }
                }
        }
    }

    fun setShowArchived(show: Boolean) {
        if (show == showArchived.value) return
        showArchived.value = show
        // The switch is the one command that has to show a spinner: the answer it asks for is a different
        // query, and until it lands the screen would otherwise be holding the other list.
        _state.update { it.copy(showArchived = show, isLoading = true) }
    }

    fun retry() {
        _state.update { it.copy(isLoading = true, error = null) }
        attempt.update { it + 1 }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }
}
