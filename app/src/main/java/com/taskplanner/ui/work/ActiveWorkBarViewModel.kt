package com.taskplanner.ui.work

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskplanner.data.repository.WorkStatisticsRepository
import com.taskplanner.data.tracking.TrackingPhase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * The measurement the whole app is making, shown wherever the user is.
 *
 * One profile has one timer, so one bar suffices: it names the task, says whether the stretch is open
 * or held, and counts only the open one. The figure comes from the same board the task list reads, so
 * the bar and the card can never disagree about what has been measured.
 */
@HiltViewModel
class ActiveWorkBarViewModel @Inject constructor(
    workStatisticsRepository: WorkStatisticsRepository
) : ViewModel() {

    /** What the bar holds: the task being measured, or null while the profile measures nothing. */
    data class Bar(
        val taskId: Long,
        val taskTitle: String,
        val phase: TrackingPhase,
        val liveSeconds: Long?
    ) {
        val isRunning: Boolean get() = phase == TrackingPhase.RUNNING
    }

    val bar: StateFlow<Bar?> = workStatisticsRepository.observeWorkBoard()
        .map { board ->
            board.active?.let { Bar(it.taskId, it.taskTitle, it.phase, board.liveSeconds) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_DELAY_MILLIS), null)

    companion object {
        /** Long enough to survive a rotation, short enough not to keep a ticker running in the background. */
        private const val STOP_DELAY_MILLIS = 5_000L
    }
}
