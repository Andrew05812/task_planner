package com.taskplanner.ui.insights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskplanner.data.insight.WorkloadRead
import com.taskplanner.data.model.Insight
import com.taskplanner.data.model.InsightFeedback
import com.taskplanner.data.repository.InsightRepository
import com.taskplanner.data.repository.WorkloadRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What the app noticed about the profile, and the fortnight of load those readings were taken against.
 *
 * The screen holds two reads and no arithmetic. The cards come from [InsightRepository], whose rows already
 * carry their title, body and basis as finished text — so a card cannot be redrawn with words the day it was
 * raised would not have used. The panel comes from [WorkloadRepository], which is the same read the heavy-day
 * rule is handed, so the day the panel calls crowded is the day an insight names.
 *
 * [showHistory] switches which list is read rather than filtering the one already loaded, exactly as the goal
 * list switches between open and retired aims: the readings the newest pass no longer stands behind are not in
 * the live list to be found in.
 */
data class InsightsState(
    val showHistory: Boolean = false,
    val insights: List<Insight> = emptyList(),
    val workload: WorkloadRead? = null,
    /**
     * Whether the list on screen answers the question on screen.
     *
     * It goes false again when the switch moves, because until the other list lands the cards being shown are
     * the ones the reader did not ask for — and false while the first frame is still on its way from Room.
     */
    val readingsArrived: Boolean = false,
    /** Whether the pass this screen started is over. See [isLoading]. */
    val passFinished: Boolean = false,
    val error: String? = null,
    /**
     * The complaint about the analysis, kept apart from [error]'s complaint about the read.
     *
     * They are two questions with two answers, and one of them can fail while the other succeeds: a pass that
     * threw leaves the rows on disk exactly as the last pass left them, so the list that arrives beside it is
     * real and has to stay on screen. Merged into one field, the good frame that follows would wipe the
     * failure out and the reader would be shown yesterday's notebook with no hint it did not update today.
     */
    val passError: String? = null,
    /**
     * A tap the repository would not record, waiting for the screen to say so once.
     *
     * A flag rather than a message because "there was nothing here to answer" is a fact about the render the
     * reader is looking at: it must not be shown twice, and it must not come back on the next emission. The
     * same shape the calendar uses for a refused drag.
     */
    val feedbackRefused: Boolean = false
) {
    /**
     * Nothing drawn yet that the reader should trust.
     *
     * Both halves are needed. An empty list before the rules have run is not the app having nothing to say —
     * it is the screen answering its own question too early, and a card that appears a second later teaches the
     * reader that the empty state was a lie.
     */
    val isLoading: Boolean get() = error == null && (!readingsArrived || !passFinished)

    /**
     * The screen's answer is "there is nothing to say", rather than "nothing has arrived" or "something failed".
     *
     * A read that refused has a reason to show and no list to show beside it, so the error is the answer there;
     * this is the sentence for the case where the question was answered and the answer was none.
     */
    val isEmpty: Boolean get() = !isLoading && error == null && insights.isEmpty()
}

/**
 * The screen's reads, the pass that fills them, and the one write it can make.
 *
 * The pass runs when the reader opens the screen rather than on a schedule: an insight is a reading of rows
 * that only this screen asks for, and a background timer would compute readings nobody is about to look at.
 * §7's "no repeats" is what makes this cheap — a second pass over the same window refreshes the rows it finds
 * again instead of raising new ones.
 *
 * The only write is [answer]. §8 forbids the app acting on its own reading, so nothing here moves a deadline,
 * a priority or a task: the cards take the reader somewhere and the reader decides.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class InsightsViewModel @Inject constructor(
    private val insightRepository: InsightRepository,
    private val workloadRepository: WorkloadRepository
) : ViewModel() {

    private val showHistory = MutableStateFlow(false)

    /** Bumped by [retry] so a failed read or a failed pass can be asked for again without touching the switch. */
    private val attempt = MutableStateFlow(0)

    private val _state = MutableStateFlow(InsightsState())
    val state: StateFlow<InsightsState> = _state.asStateFlow()

    init {
        viewModelScope.launch { collectReadings() }
        // A pass per question the screen asks: the first emission is the screen being opened, and a retry asks
        // the same question again after a failure.
        viewModelScope.launch {
            attempt.collect { runPass() }
        }
    }

    private suspend fun collectReadings() {
        // A value key rather than the whole state, so a write landing on one card does not make the screen
        // re-subscribe to queries that have not changed.
        combine(showHistory, attempt) { history, tried -> history to tried }
            .distinctUntilChanged()
            .flatMapLatest { (history, _) ->
                val readings = if (history) {
                    insightRepository.observeHistory()
                } else {
                    insightRepository.observeCurrent()
                }
                // The two reads are combined rather than collected one after the other: the panel and the cards
                // are two accounts of one fortnight, and a screen that drew one before the other would show a
                // day an insight has already called heavy beside a panel still holding yesterday's window.
                val together: Flow<Frame> =
                    combine(readings, workloadRepository.observeUpcoming()) { insights, load ->
                        Frame.Drawn(history, insights, load)
                    }
                together.catch { failure ->
                    // The catch belongs to this question's flow rather than to the whole subscription: one read
                    // that failed is one list the screen cannot show, and it must not end the screen's ability
                    // to answer the next thing the reader switches to or asks for again.
                    emit(Frame.Failed(history, failure.message ?: READ_FAILED))
                }
            }
            .collect { frame ->
                when (frame) {
                    is Frame.Drawn -> _state.update {
                        // A frame that arrived is the read answering again, so its own complaint goes — but only
                        // its own. A pass that threw an instant earlier is still a pass that threw, whatever the
                        // rows on disk now read as.
                        it.copy(
                            showHistory = frame.history,
                            insights = frame.insights,
                            workload = frame.workload,
                            readingsArrived = true,
                            error = null
                        )
                    }

                    is Frame.Failed -> _state.update {
                        // A read that failed has no cards to show, so it hands back none rather than the ones
                        // that last succeeded under a switch the reader may already have moved.
                        it.copy(
                            showHistory = frame.history,
                            insights = emptyList(),
                            workload = null,
                            readingsArrived = true,
                            error = frame.message
                        )
                    }
                }
            }
    }

    private suspend fun runPass() {
        val outcome = runCatching { insightRepository.refresh() }
        outcome.fold(
            onSuccess = { _state.update { it.copy(passFinished = true, passError = null) } },
            // A pass that threw is a complaint about the analysis, not about the rows: what is on disk is still
            // what is on screen, and the cards it left there stay drawn. The message lands in its own field so
            // the next good frame cannot erase it the way it erases a read that has since succeeded.
            onFailure = { failure ->
                _state.update { it.copy(passFinished = true, passError = failure.message ?: PASS_FAILED) }
            }
        )
    }

    fun setShowHistory(show: Boolean) {
        if (show == showHistory.value) return
        showHistory.value = show
        // The switch is the one command that has to show a spinner: the answer it asks for is a different query,
        // and until it lands the screen would otherwise be holding the other list.
        _state.update { it.copy(showHistory = show, readingsArrived = false) }
    }

    /**
     * Records what the reader said about one reading.
     *
     * Whether the card then leaves the list is decided in SQL by the read that fed it, so the answer takes
     * effect in the same frame it was given and this method has to do nothing but report a tap that found no
     * row — which is what [InsightsState.feedbackRefused] is for.
     */
    fun answer(uuid: String, feedback: InsightFeedback) {
        viewModelScope.launch {
            if (!insightRepository.giveFeedback(uuid, feedback)) {
                _state.update { it.copy(feedbackRefused = true) }
            }
        }
    }

    fun retry() {
        // Both questions are asked again, so both complaints go: leaving a failed pass's message up while a new
        // pass runs would have the screen contradict its own spinner.
        _state.update { it.copy(readingsArrived = false, passFinished = false, error = null, passError = null) }
        attempt.update { it + 1 }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    /**
     * Takes the analysis complaint off the state once the screen has said it.
     *
     * It is cleared separately from [clearError] because it outlives the frame it was raised in by design — the
     * cards beside it stay, and the complaint stays with them until the reader either retries or gets a pass
     * that finished.
     */
    fun clearPassError() {
        _state.update { it.copy(passError = null) }
    }

    fun clearFeedbackRefused() {
        _state.update { it.copy(feedbackRefused = false) }
    }

    /**
     * One frame of the two reads the screen holds open, with the question it was asked under.
     *
     * A failure is a frame rather than an exception on the collector, because the collector is the screen's
     * only way of hearing about the next frame: a read that threw has to be reported and then get out of the
     * way.
     */
    private sealed interface Frame {
        data class Drawn(
            val history: Boolean,
            val insights: List<Insight>,
            val workload: WorkloadRead
        ) : Frame

        data class Failed(val history: Boolean, val message: String) : Frame
    }

    private companion object {
        // A screen has no resources to read, so the words it can fall back to when a failure carries no
        // message of its own are written here; the fragment prints the exception's own text whenever there is
        // one, which is what the reader of a storage complaint wants to see.
        private const val READ_FAILED = "Insights could not be read"
        private const val PASS_FAILED = "The reading could not be finished"
    }
}
