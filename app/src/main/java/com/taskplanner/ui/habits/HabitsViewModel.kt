package com.taskplanner.ui.habits

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskplanner.data.repository.HabitRepository
import com.taskplanner.data.repository.HabitWithFigures
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
 * The habits the profile holds, each with the figures its days produced.
 *
 * [habits] arrives already carrying its [HabitWithFigures], so the screen keeps no arithmetic of its own and
 * marking a day somewhere else moves the card without a reload: the marks are their own bounded read, and a
 * habit whose figures are asleep is not woken by them. Each card also carries the day its figures were
 * anchored on, which is the day the words beside its percentage are speaking about.
 */
data class HabitsState(
    val showArchived: Boolean = false,
    val habits: List<HabitWithFigures> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null
) {
    val isEmpty: Boolean get() = !isLoading && error == null && habits.isEmpty()
}

/**
 * The list screen's read, and nothing else.
 *
 * A habit list writes nothing. Marking a day, correcting one, putting a habit away and changing its schedule
 * all need the grid and the correction history that only the detail screen shows, and a card that offered them
 * from a long-press would have to explain the window it counted over again there. What is left for the list is
 * which habits, and how they stand — with [retry] for the one case where even that could not be read.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HabitsViewModel @Inject constructor(
    private val habitRepository: HabitRepository
) : ViewModel() {

    private val showArchived = MutableStateFlow(false)

    /** Bumped by [retry] so a failed read can be asked for again without touching the switch. */
    private val attempt = MutableStateFlow(0)

    private val _state = MutableStateFlow(HabitsState())
    val state: StateFlow<HabitsState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            // A value key rather than the whole state, so a mark landing on one card does not make the screen
            // re-subscribe to a list query that has not changed.
            combine(showArchived, attempt) { archived, tried -> archived to tried }
                .distinctUntilChanged()
                .flatMapLatest { (archived, _) ->
                    // The window is cut when the read is asked for rather than once for the life of the
                    // screen: the day the repository anchors its figures on moves at midnight, and a window
                    // that stayed where it was opened would report last four weeks while the cards beside it
                    // had already re-anchored on today.
                    val today = habitRepository.todayEpochDay()
                    val source = if (archived) {
                        habitRepository.observeArchivedHabits(HabitCard.periodFrom(today), today)
                    } else {
                        habitRepository.observeOpenHabits(HabitCard.periodFrom(today), today)
                    }
                    source.map { habits -> archived to habits }
                }
                .catch { error -> _state.update { it.copy(error = error.message, isLoading = false) } }
                .collect { (archived, habits) ->
                    _state.update {
                        it.copy(
                            showArchived = archived,
                            habits = habits,
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
