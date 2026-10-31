package com.taskplanner.ui.statistics

import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskplanner.R
import com.taskplanner.data.calendar.CalendarMath
import com.taskplanner.data.repository.DashboardSnapshot
import com.taskplanner.data.repository.DashboardRepository
import com.taskplanner.data.repository.DashboardRequest
import com.taskplanner.data.repository.WorkPeriod
import com.taskplanner.data.statistics.DashboardMath
import com.taskplanner.data.tracking.TimeBase
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
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
 * What the dashboard shows, and the window it speaks about.
 *
 * [snapshot] is the whole answer of one read: the period's figures, the days that produced something, the
 * activity grid, the health counts, the categories and the busiest tasks. Nothing in it is computed by the
 * screen, so a card and the chart beneath it cannot disagree — they are the same query's output, arranged
 * twice rather than measured twice.
 */
data class DashboardState(
    val period: WorkPeriod = WorkPeriod.THIS_WEEK,
    val onDay: LocalDate = LocalDate.now(),
    val snapshot: DashboardSnapshot? = null,
    val isLoading: Boolean = true,
    /** A resource, not a sentence: see `CategoriesState.Error`. */
    @StringRes val error: Int? = null
) {
    /**
     * Whether the screen has nothing to draw.
     *
     * A snapshot that exists but holds no work and no tasks is the empty state; so is no snapshot at all,
     * which is what a signed-out profile produces. Both are "nothing to report", and neither is loading.
     */
    val isEmpty: Boolean get() = !isLoading && (snapshot == null || snapshot.isEmpty)
}

/**
 * The dashboard's one command: name the period, and the figures come from SQLite.
 *
 * The screen used to load the profile's whole task list and fold it in Kotlin to make two of these figures.
 * That is the pattern this ViewModel exists to replace — the read is the repository's, the arithmetic of the
 * charts is `DashboardMath`'s, and this class only decides which window was asked for and hands the answer
 * to the fragment.
 *
 * The Flow follows the history screen's shape: the period and the day are combined into one request, and a
 * change to either re-subscribes. A re-draw of the same request does not: the day is refreshed on the way
 * back to the screen, which is what makes the grid's last column right after midnight without a ticker.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class StatisticsViewModel @Inject constructor(
    private val dashboardRepository: DashboardRepository,
    private val timeBase: TimeBase
) : ViewModel() {

    private val period = MutableStateFlow(WorkPeriod.THIS_WEEK)
    private val onDay = MutableStateFlow(today())

    /** Bumped by [retry] so a failed read can be asked for again without changing the period or the day. */
    private val attempt = MutableStateFlow(0)

    private val _state = MutableStateFlow(DashboardState())
    val state: StateFlow<DashboardState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(period, onDay, attempt) { chosen, day, tries ->
                Key(period = chosen, onDay = day, attempt = tries)
            }
                .distinctUntilChanged()
                .flatMapLatest { key ->
                    val request = DashboardRequest(period = key.period, onDay = key.onDay)
                    // The catch belongs to this request's flow rather than to the whole subscription: one
                    // read that failed is one period that has no figures, and it must not end the screen's
                    // ability to answer the next period the user picks.
                    dashboardRepository.observe(request)
                        .map { snapshot ->
                            DashboardState(
                                period = request.period,
                                onDay = request.onDay,
                                snapshot = snapshot,
                                isLoading = false
                            )
                        }
                        .catch { failure ->
                            // The reader is told the dashboard could not be read, in their language; the reason
                            // it could not is a storage complaint, and it goes to the log.
                            Log.w(TAG, "the dashboard could not be read", failure)
                            emit(
                                DashboardState(
                                    period = request.period,
                                    onDay = request.onDay,
                                    // A read that failed has no figures to show, so it hands back none
                                    // rather than the last ones that succeeded under a heading that has
                                    // since moved on.
                                    snapshot = null,
                                    isLoading = false,
                                    error = R.string.error_dashboard_unread                                )
                            )
                        }
                }
                .collect { fresh ->
                    // A snapshot for a period the user has already left is dropped rather than painted: the
                    // read was cancelled, but its answer can still arrive, and one screen showing two
                    // periods at once is the mistake the labelled header exists to prevent.
                    if (fresh.period == period.value) _state.value = fresh
                }
        }
    }

    /** The three things that decide which read the screen is subscribed to. */
    private data class Key(
        val period: WorkPeriod,
        val onDay: LocalDate,
        val attempt: Int
    )

    /**
     * Names the period the figures speak about.
     *
     * The previous snapshot is thrown away rather than kept on screen while the new one is read: a card
     * that shows last week's total under a "This month" chip, even for a moment, is a figure with the wrong
     * label — and the screen has a spinner that means exactly this.
     */
    fun setPeriod(newPeriod: WorkPeriod) {
        if (period.value == newPeriod) return
        _state.update { it.copy(period = newPeriod, isLoading = true, snapshot = null) }
        period.value = newPeriod
    }

    /**
     * Re-reads which day it is.
     *
     * The grid's last column, the streak and the "due today" bucket all hang on today's date. Called when
     * the screen becomes visible again, this is the difference between a dashboard that is right at
     * midnight and one that is right when the app starts.
     */
    fun refreshDay() {
        onDay.value = today()
    }

    /**
     * Asks for the same period again.
     *
     * The period and the day are what the request is keyed on, so neither of them can be the way back from
     * a failed read — the screen's retry button therefore bumps the attempt, and the read is made again over
     * the period the user is still looking at.
     */
    fun retry() {
        _state.update { it.copy(isLoading = true, snapshot = null, error = null) }
        attempt.update { it + 1 }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    /**
     * The day the screen believes it is showing.
     *
     * Read from the app's [TimeBase] rather than from [LocalDate.now] because that is the same clock the
     * tracker stamped its measurements with: the grid's last column, the streak and "due today" have to be
     * answered by the one clock, or a profile whose clock moved would get a dashboard anchored on a day its
     * own intervals do not agree with — and a test could not move the day at all to check what happens at
     * midnight.
     */
    private fun today(): LocalDate =
        LocalDate.ofEpochDay(DashboardMath.epochDayOf(timeBase.wallMillis(), CalendarMath.systemZone()))

    private companion object {
        private const val TAG = "StatisticsViewModel"
    }
}
