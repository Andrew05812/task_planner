package com.taskplanner.data.tracking

import com.taskplanner.data.model.TrackingState
import com.taskplanner.data.model.TrackingSession
import com.taskplanner.data.model.WorkSession
import com.taskplanner.data.tracking.WorkTimeMath.MeasuredDuration

/**
 * Where tracking stands, including the state that has no row.
 *
 * [IDLE] is not stored anywhere — it is the absence of a session for the task — precisely so that a
 * fresh install, a signed-out profile, a crash, or a database written before this feature can only be
 * read as "nothing is running". A phase that had to be written to be seen would be a phase that can
 * be lost.
 */
enum class TrackingPhase { IDLE, RUNNING, PAUSED, COMPLETED }

/** What the user chose to do about the task that already holds the one running timer. */
enum class ConflictResolution { PAUSE_ACTIVE, STOP_ACTIVE, CANCEL }

/** The state as the stored rows describe it, read back after a restart or a sync. */
data class TrackingSnapshot(
    val taskId: Long,
    val taskTitle: String,
    val phase: TrackingPhase,
    /** `started_at` of this session's still-open interval, null when every interval is closed. */
    val openSince: Long? = null,
) {
    /** A session the user can still act on: not absent, not stopped. */
    val isLive: Boolean get() = phase == TrackingPhase.RUNNING || phase == TrackingPhase.PAUSED
}

/** The row changes one accepted request causes. Everything is inside a single transaction. */
sealed interface IntervalOp {
    /** Append a new interval: `ended_at` NULL, `open_marker` set. */
    data class Open(val startedAt: Long) : IntervalOp

    /** Close the interval that is open — the only rewrite of a row this feature ever performs. */
    data class Close(
        val startedAt: Long,
        val endedAt: Long,
        val duration: MeasuredDuration
    ) : IntervalOp
}

/** The effect of a request that the state machine accepted. */
data class TrackingEffect(
    val state: TrackingState,
    val stoppedAt: Long?,
    val interval: IntervalOp?,
    /** The profile id as text while a timer runs, null otherwise — the one-running-per-profile index. */
    val runningMarker: String?,
    /**
     * False when the request continues a PAUSED session, true when it begins a new one.
     *
     * A stopped session is never reopened: the user starting work on that task again after a Stop is a
     * new run, and its intervals must carry a new uuid so the history can tell the two apart. The
     * repository reads this to choose between updating the existing row and inserting one.
     */
    val beginsNewSession: Boolean,
)

enum class TrackingReason {
    /** The request repeats a state the session is already in; a repeated tap writes nothing. */
    ALREADY_RUNNING,
    ALREADY_PAUSED,
    ALREADY_STOPPED,

    /** There is no session to act on — the task was never started, or its history is all closed. */
    NO_LIVE_SESSION,

    /** Another task holds the profile's single running timer. */
    ANOTHER_TASK_IS_RUNNING,
}

/** The outcome of a request: what to write, or why nothing will be written. */
sealed interface TrackingDecision {
    data class Perform(val effect: TrackingEffect) : TrackingDecision
    data class Ignored(val reason: TrackingReason) : TrackingDecision
    data class Conflict(
        val runningTaskId: Long,
        val runningTaskTitle: String
    ) : TrackingDecision
}

/**
 * The state machine of the tracker: given what the rows say, what may a request change?
 *
 * Deliberately free of databases and clocks of its own. It reads a [TrackingSnapshot] and returns the
 * row-level effects it wants, which keeps the two things this feature is most likely to get wrong
 * testable as plain decisions: idempotency — a second Start, Pause or Stop must not add a row — and
 * the append-only rule — a closed interval is never rewritten, and Stop on a paused session must not
 * invent a zero-length one just to have something to point at.
 *
 * The repository owns the transaction, the real instants, and the monotonic arbitration; the durations
 * it needs come back in the effect, measured through the `measure` seam the caller passes in.
 */
object WorkTrackingStateMachine {

    /**
     * The phase a task's stored rows describe.
     *
     * The rows are the only source of truth here: a ViewModel field or a coroutine cannot survive a
     * process death, and this function is what a restart calls to find out that a timer is still
     * running. An interval left open under a session that is no longer RUNNING is reported as
     * PAUSED rather than repaired silently — the work up to that instant is already stored, and the
     * user decides whether to resume it.
     */
    fun snapshotOf(
        session: TrackingSession?,
        openInterval: WorkSession?,
        fallbackTaskId: Long,
        fallbackTitle: String,
    ): TrackingSnapshot {
        if (session == null) {
            return TrackingSnapshot(fallbackTaskId, fallbackTitle, TrackingPhase.IDLE)
        }
        val phase = when {
            session.state == TrackingState.RUNNING && openInterval == null -> TrackingPhase.PAUSED
            else -> when (session.state) {
                TrackingState.RUNNING -> TrackingPhase.RUNNING
                TrackingState.PAUSED -> TrackingPhase.PAUSED
                TrackingState.COMPLETED -> TrackingPhase.COMPLETED
            }
        }
        return TrackingSnapshot(
            taskId = session.taskId,
            taskTitle = session.taskTitle,
            phase = phase,
            openSince = openInterval?.startedAt,
        )
    }

    /**
     * Start tracking [own]'s task.
     *
     * A repeat request is refused rather than duplicated: the session already RUNNING on this task
     * produces no row, so a double tap, a retried click, or two screens asking at once cannot leave
     * two timers running. Starting a task while a *different* task is running is a conflict the user
     * has to decide ([ConflictResolution]) — it is never resolved silently.
     */
    fun start(
        userId: Long,
        own: TrackingSnapshot,
        running: TrackingSnapshot?,
        now: Long,
    ): TrackingDecision {
        if (own.phase == TrackingPhase.RUNNING) return TrackingDecision.Ignored(TrackingReason.ALREADY_RUNNING)
        if (running != null && running.taskId != own.taskId) {
            return TrackingDecision.Conflict(running.taskId, running.taskTitle)
        }
        return TrackingDecision.Perform(
            TrackingEffect(
                state = TrackingState.RUNNING,
                stoppedAt = null,
                interval = IntervalOp.Open(now),
                runningMarker = userId.toString(),
                beginsNewSession = own.phase != TrackingPhase.PAUSED,
            )
        )
    }

    /**
     * Pause: the open interval is closed and kept, the session stays resumable.
     *
     * The pause itself is not a row and is never measured — that is the whole point of the split
     * between the session and its intervals.
     */
    fun pause(
        own: TrackingSnapshot,
        now: Long,
        measure: (Long) -> MeasuredDuration,
    ): TrackingDecision = when (own.phase) {
        TrackingPhase.RUNNING -> {
            val since = own.openSince
            TrackingDecision.Perform(
                TrackingEffect(
                    state = TrackingState.PAUSED,
                    stoppedAt = null,
                    interval = if (since == null) null else IntervalOp.Close(since, now, measure(since)),
                    runningMarker = null,
                    beginsNewSession = false,
                )
            )
        }
        TrackingPhase.PAUSED -> TrackingDecision.Ignored(TrackingReason.ALREADY_PAUSED)
        TrackingPhase.COMPLETED -> TrackingDecision.Ignored(TrackingReason.ALREADY_STOPPED)
        TrackingPhase.IDLE -> TrackingDecision.Ignored(TrackingReason.NO_LIVE_SESSION)
    }

    /**
     * Resume: a new interval under the same session uuid.
     *
     * The closed interval from before the pause is not touched, and no interval spans the break, so
     * 09:00–09:30 plus 10:00–10:45 stays 75 minutes however many times the user pauses.
     */
    fun resume(
        userId: Long,
        own: TrackingSnapshot,
        running: TrackingSnapshot?,
        now: Long,
    ): TrackingDecision {
        if (own.phase == TrackingPhase.RUNNING) return TrackingDecision.Ignored(TrackingReason.ALREADY_RUNNING)
        if (own.phase != TrackingPhase.PAUSED) return TrackingDecision.Ignored(TrackingReason.NO_LIVE_SESSION)
        if (running != null && running.taskId != own.taskId) {
            return TrackingDecision.Conflict(running.taskId, running.taskTitle)
        }
        return TrackingDecision.Perform(
            TrackingEffect(
                state = TrackingState.RUNNING,
                stoppedAt = null,
                interval = IntervalOp.Open(now),
                runningMarker = userId.toString(),
                beginsNewSession = false,
            )
        )
    }

    /**
     * Stop: the session is closed for good.
     *
     * On a running session the open interval is closed with a measured duration. On a paused one only
     * the session changes — writing an interval there would add a zero-length stretch that never
     * happened and inflate the interval count of every statistic built on it.
     */
    fun stop(
        own: TrackingSnapshot,
        now: Long,
        measure: (Long) -> MeasuredDuration,
    ): TrackingDecision = when (own.phase) {
        TrackingPhase.RUNNING -> {
            val since = own.openSince
            TrackingDecision.Perform(
                TrackingEffect(
                    state = TrackingState.COMPLETED,
                    stoppedAt = now,
                    interval = if (since == null) null else IntervalOp.Close(since, now, measure(since)),
                    runningMarker = null,
                    beginsNewSession = false,
                )
            )
        }
        TrackingPhase.PAUSED -> TrackingDecision.Perform(
            TrackingEffect(
                state = TrackingState.COMPLETED,
                stoppedAt = now,
                interval = null,
                runningMarker = null,
                beginsNewSession = false,
            )
        )
        TrackingPhase.COMPLETED -> TrackingDecision.Ignored(TrackingReason.ALREADY_STOPPED)
        TrackingPhase.IDLE -> TrackingDecision.Ignored(TrackingReason.NO_LIVE_SESSION)
    }

    /**
     * What Start means when another task already holds the timer, once the user has chosen.
     *
     * The list is ordered: the active session is released first, then the new one begins, so a
     * failure partway leaves one timer running rather than two. Cancelling writes nothing at all.
     */
    fun resolveStart(
        userId: Long,
        active: TrackingSnapshot,
        target: TrackingSnapshot,
        resolution: ConflictResolution,
        now: Long,
        measure: (Long) -> MeasuredDuration,
    ): List<TrackingDecision> = when (resolution) {
        ConflictResolution.CANCEL -> emptyList()
        ConflictResolution.PAUSE_ACTIVE -> listOf(
            pause(active, now, measure),
            start(userId, target, running = null, now = now),
        )
        ConflictResolution.STOP_ACTIVE -> listOf(
            stop(active, now, measure),
            start(userId, target, running = null, now = now),
        )
    }

    /**
     * The task is being marked complete while tracking stands on it.
     *
     * Handled explicitly rather than left running: a session with no task to show for is a timer the
     * user cannot find, and letting it run past completion would charge the elapsed time to work that
     * is already reported as done. A paused or absent session needs nothing — the measured time is
     * already closed and stored.
     */
    fun onTaskCompleted(
        own: TrackingSnapshot,
        now: Long,
        measure: (Long) -> MeasuredDuration,
    ): TrackingDecision = when (own.phase) {
        TrackingPhase.IDLE, TrackingPhase.COMPLETED ->
            TrackingDecision.Ignored(TrackingReason.NO_LIVE_SESSION)
        else -> stop(own, now, measure)
    }
}
