package com.taskplanner.ui.work

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskplanner.data.model.WorkHistoryRow
import com.taskplanner.data.model.WorkIntervalCorrection
import com.taskplanner.data.model.WorkIntervalRow
import com.taskplanner.data.model.WorkPeriodSummary
import com.taskplanner.data.repository.AdjustmentResult
import com.taskplanner.data.repository.HistorySort
import com.taskplanner.data.repository.WorkPeriod
import com.taskplanner.data.repository.WorkStatisticsRepository
import com.taskplanner.data.repository.WorkTrackingRepository
import com.taskplanner.data.repository.WorkWindow
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What the history screen shows and what the user asked it to show.
 *
 * [period] is null while the screen is on the custom range, because "custom" is not a period the
 * repository can name — it is the range the user picked, held in [customDays]. Everything else is what
 * SQLite answered: [totals] for the labelled header, [rows] with one entry per tracking session, and
 * [intervalsOf] for the entries the user has opened.
 */
data class WorkHistoryState(
    val period: WorkPeriod? = WorkPeriod.TODAY,
    val customDays: LongRange = 0L..0L,
    val sort: HistorySort = HistorySort.DATE,
    val totals: WorkPeriodSummary? = null,
    val rows: List<WorkHistoryRow> = emptyList(),
    val intervalsOf: Map<String, List<WorkIntervalRow>> = emptyMap(),
    val isLoading: Boolean = true,
    val error: String? = null
) {
    /** Nothing was measured in this period — the empty state, as distinct from the loading state. */
    val isEmpty: Boolean get() = !isLoading && rows.isEmpty()
}

/**
 * The work history list: the period the user asked for, in the order they asked for, with the stretches
 * of an entry read only when they open it.
 *
 * The screen never computes a total. The header comes from the same aggregate the statistics screens use
 * and the list comes from one grouped query, so the two figures can only disagree if the database says
 * they do. Corrections and deletions are asked of the tracking repository rather than applied here,
 * because whether a change may land is a question about the stored rows, not about this screen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class WorkHistoryViewModel @Inject constructor(
    private val statisticsRepository: WorkStatisticsRepository,
    private val trackingRepository: WorkTrackingRepository
) : ViewModel() {

    private val _state = MutableStateFlow(WorkHistoryState())
    val state: StateFlow<WorkHistoryState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            _state
                .map { Key(it.period, it.sort, it.customDays.first, it.customDays.last) }
                .distinctUntilChanged()
                .flatMapLatest { key ->
                    val window = windowOf(key)
                    combine(
                        statisticsRepository.observeHistory(window, key.sort),
                        statisticsRepository.observeSummary(window)
                    ) { rows, totals -> rows to totals }
                }
                .collect { (rows, totals) ->
                    _state.update { it.copy(totals = totals, rows = rows, isLoading = false) }
                }
        }
    }

    /**
     * The read the screen is currently asking for.
     *
     * A value key rather than the whole state, so that opening an entry or landing a correction — which
     * changes neither the period nor the order — does not make the list re-subscribe to a query that has
     * not changed.
     */
    private data class Key(
        val period: WorkPeriod?,
        val sort: HistorySort,
        val fromDay: Long,
        val toDay: Long
    )

    private fun windowOf(key: Key): WorkWindow = key.period?.let { statisticsRepository.window(it) }
        ?: statisticsRepository.window(key.fromDay..key.toDay)

    fun setPeriod(period: WorkPeriod) {
        _state.update { it.copy(period = period) }
    }

    /**
     * Switches to the range the user picked.
     *
     * A range whose last day falls before its first is refused with `false` rather than normalised: the
     * screen then says so instead of quietly showing a period nobody asked for.
     */
    fun setCustomRange(from: LocalDate, to: LocalDate): Boolean {
        if (to.isBefore(from)) return false
        _state.update { it.copy(period = null, customDays = from.toEpochDay()..to.toEpochDay()) }
        return true
    }

    fun setSort(sort: HistorySort) {
        _state.update { it.copy(sort = sort) }
    }

    /**
     * Opens or closes one entry.
     *
     * The stretches are loaded on the way in rather than with the list: a month of history is a hundred
     * sessions and only the one being read needs its intervals, so fetching them all would be the
     * aggregate-without-an-index the design rules reject.
     */
    fun toggleExpand(sessionUuid: String) {
        viewModelScope.launch {
            if (_state.value.intervalsOf.containsKey(sessionUuid)) {
                _state.update { it.copy(intervalsOf = it.intervalsOf - sessionUuid) }
                return@launch
            }
            val intervals = statisticsRepository.intervalsOf(sessionUuid)
            _state.update { it.copy(intervalsOf = it.intervalsOf + (sessionUuid to intervals)) }
        }
    }

    /**
     * Changes a closed stretch, and keeps what it said before.
     *
     * The result is handed back rather than folded into the state because the answer has three shapes and
     * only the screen can say which words each one needs: a change that landed, a change that cannot be
     * made, and a stretch that other measurements already occupy — the last of those is shown with the
     * rows it conflicts with, and nothing is written until the user has seen them.
     */
    fun correctInterval(
        intervalUuid: String,
        startedAt: Long,
        endedAt: Long,
        reason: String?,
        onResult: (AdjustmentResult) -> Unit = {}
    ) {
        viewModelScope.launch {
            val result = runCatching {
                trackingRepository.correctInterval(intervalUuid, startedAt, endedAt, reason)
            }
            result.onSuccess { outcome ->
                onResult(outcome)
                if (outcome is AdjustmentResult.Accepted) reloadExpanded()
            }.onFailure { error -> reportError(error) }
        }
    }

    /** Throws a closed stretch away. The row stays in the database; only the history list hides it. */
    fun removeInterval(intervalUuid: String, onResult: (AdjustmentResult) -> Unit = {}) {
        viewModelScope.launch {
            val result = runCatching { trackingRepository.removeInterval(intervalUuid) }
            result.onSuccess { outcome ->
                onResult(outcome)
                if (outcome is AdjustmentResult.Accepted) reloadExpanded()
            }.onFailure { error -> reportError(error) }
        }
    }

    /** What an interval has been told before, oldest first — the editor shows it, so the user edits knowing. */
    fun correctionsOf(intervalUuid: String, onResult: (List<WorkIntervalCorrection>) -> Unit) {
        viewModelScope.launch {
            onResult(statisticsRepository.correctionsOf(intervalUuid))
        }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    /** Re-reads the entries the user has open, so a correction shows in the list it came from. */
    private suspend fun reloadExpanded() {
        val keys = _state.value.intervalsOf.keys.toList()
        if (keys.isEmpty()) return
        val fresh = keys.associateWith { statisticsRepository.intervalsOf(it) }
        _state.update { it.copy(intervalsOf = fresh) }
    }

    private fun reportError(error: Throwable) {
        _state.update { it.copy(error = error.message, isLoading = false) }
    }
}
