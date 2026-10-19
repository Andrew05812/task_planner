package com.taskplanner.data.repository

import androidx.room.withTransaction
import com.taskplanner.data.local.AppDatabase
import com.taskplanner.data.local.TrackingSessionDao
import com.taskplanner.data.local.WorkSessionDao
import com.taskplanner.data.model.Task
import com.taskplanner.data.model.TrackingSession
import com.taskplanner.data.model.TrackingState
import com.taskplanner.data.model.SyncState
import com.taskplanner.data.model.WorkSession
import com.taskplanner.data.model.WorkSource
import com.taskplanner.data.tracking.ConflictResolution
import com.taskplanner.data.tracking.IntervalOp
import com.taskplanner.data.tracking.MonotonicReference
import com.taskplanner.data.tracking.TimeBase
import com.taskplanner.data.tracking.TrackingDecision
import com.taskplanner.data.tracking.TrackingEffect
import com.taskplanner.data.tracking.TrackingSnapshot
import com.taskplanner.data.tracking.WorkTimeMath
import com.taskplanner.data.tracking.WorkTrackingStateMachine
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What a tracking request ended up doing, so the screen can say so instead of guessing. */
enum class TrackingOutcome {
    STARTED,
    RESUMED,
    PAUSED,
    STOPPED,

    /** The request repeated a state the session was already in — nothing was written. */
    UNCHANGED,

    /** Another task holds the profile's one running timer, and the user has not chosen yet. */
    CONFLICT,

    /** No profile is signed in, so there is nothing to attach the measurement to. */
    NO_PROFILE,
}

/**
 * The tracker's only writer: it turns the state machine's decision into rows, in one transaction.
 *
 * Nothing here remembers a timer. Every request re-reads the stored session and the stored open
 * interval before deciding, which is what makes the feature survive the cases a ViewModel cannot: the
 * process was killed mid-interval, the user signed out and back in, a pull from another device moved
 * the rows, or two screens asked for the same thing at the same moment. The interval row is the proof
 * a timer is running, and the unique index on the running marker is the last line when two writers get
 * past the in-process lock.
 *
 * The pause is never a row, and stopping a paused session closes the session alone, so no statistic is
 * ever built on time nobody worked.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class WorkTrackingRepository @Inject constructor(
    private val database: AppDatabase,
    private val trackingSessionDao: TrackingSessionDao,
    private val workSessionDao: WorkSessionDao,
    private val sessionRepository: SessionRepository,
    private val timeBase: TimeBase,
    /**
     * Captured when this process first needed it — the one point where the two clocks are known to
     * agree. An interval that started before it, across a reboot, has no monotonic reading and is
     * measured by the wall clock alone. Shared with the statistics repository so the number a screen
     * shows while a stretch runs is arbitrated the same way as the number stored when it ends.
     */
    private val monotonic: MonotonicReference
) {

    /**
     * Serialises requests so a double tap cannot have two writers inside the same read-then-write
     * sequence. The database constraint behind it covers what a lock cannot: a second process, a
     * retried transaction, a row that arrived from sync.
     */
    private val trackingMutex = Mutex()

    /** The profile's running session — what the global indicator reads and nothing else. */
    fun observeRunningSession(): Flow<TrackingSession?> =
        sessionRepository.currentUser.flatMapLatest { user ->
            if (user == null) flowOf(null) else trackingSessionDao.observeRunning(user.id)
        }

    /** The profile's open interval, so the ticker measures from the stored start rather than a timer. */
    fun observeOpenInterval(): Flow<WorkSession?> =
        sessionRepository.currentUser.flatMapLatest { user ->
            if (user == null) flowOf(null) else workSessionDao.observeOpen(user.id)
        }

    /** What the stored rows say about tracking on [task]. */
    suspend fun snapshotOf(task: Task): TrackingSnapshot {
        val userId = sessionRepository.currentUserId()
            ?: return WorkTrackingStateMachine.snapshotOf(task, null, null)
        return readState(task, userId).own
    }

    /**
     * Starts measuring work on [task].
     *
     * A repeated call on a running task writes nothing. When a *different* task holds the timer the
     * call answers [TrackingOutcome.CONFLICT] unless the caller passes the resolution the user chose,
     * because quietly pausing someone's running session is a decision about their data that the app
     * has no business making.
     */
    suspend fun start(
        task: Task,
        resolution: ConflictResolution = ConflictResolution.CANCEL
    ): TrackingOutcome = request(task) { state, now, measure ->
        val decision = WorkTrackingStateMachine.start(state.userId, state.own, state.running, now)
        if (decision !is TrackingDecision.Conflict || resolution == ConflictResolution.CANCEL) {
            listOf(state.own to decision)
        } else {
            val active = state.running!!
            WorkTrackingStateMachine
                .resolveStart(state.userId, active, state.own, resolution, now, measure)
                .let { listOf(active to it[0], state.own to it[1]) }
        }
    }

    /** Closes the open interval and leaves the session resumable; a second pause writes nothing. */
    suspend fun pause(task: Task): TrackingOutcome = request(task) { state, now, measure ->
        listOf(state.own to WorkTrackingStateMachine.pause(state.own, now, measure))
    }

    /** Reopens the paused session with a new interval under the same uuid. */
    suspend fun resume(task: Task): TrackingOutcome = request(task) { state, now, _ ->
        listOf(
            state.own to WorkTrackingStateMachine.resume(state.userId, state.own, state.running, now)
        )
    }

    /** Ends the session: the open interval is measured and closed, a paused one is closed alone. */
    suspend fun stop(task: Task): TrackingOutcome = request(task) { state, now, measure ->
        listOf(state.own to WorkTrackingStateMachine.stop(state.own, now, measure))
    }

    /**
     * The task was completed while tracking stood on it.
     *
     * Reached from the one method every local completion passes through, so a timer cannot outlive the
     * task it belongs to: the session is stopped and keeps the title it was measured under, and the
     * completion itself is not a measurement and is never written as one.
     */
    suspend fun onTaskCompleted(task: Task): TrackingOutcome = request(task) { state, now, measure ->
        listOf(state.own to WorkTrackingStateMachine.onTaskCompleted(state.own, now, measure))
    }

    /** The sessions of one task that are not finished — the detail screen's list of runs. */
    suspend fun liveSessionsOf(task: Task): List<TrackingSession> {
        val userId = sessionRepository.currentUserId() ?: return emptyList()
        return trackingSessionDao.getLiveSessionsForTask(userId, task.id)
    }

    /** The intervals of one session, oldest first — the detail screen's history block. */
    suspend fun intervalsOf(sessionUuid: String): List<WorkSession> =
        workSessionDao.getIntervalsOf(sessionUuid)

    /**
     * Reads the stored tracking after a start-up and releases a slot that holds nothing.
     *
     * A session row marked RUNNING with no open interval under it is the one inconsistency this can
     * find — sync delivered the session before its intervals, or an import half-applied — and it takes
     * the profile's single timer slot while measuring nothing, which would lock the user out of
     * starting work at all. It is released to PAUSED: whatever it did measure is already stored, and a
     * session the user can resume is a truer account than a timer that is not running.
     */
    suspend fun recoverAfterStartUp(): TrackingSnapshot? = trackingMutex.withLock {
        val user = sessionRepository.resolveUser() ?: return@withLock null
        val running = trackingSessionDao.getRunningOnce(user.id) ?: return@withLock null
        val open = workSessionDao.getOpenOnce(user.id)
        if (open != null && open.trackingSessionUuid == running.uuid) {
            WorkTrackingStateMachine.snapshotOf(running, open)
        } else {
            val now = timeBase.wallMillis()
            trackingSessionDao.setState(running.uuid, TrackingState.PAUSED.name, null, now)
            WorkTrackingStateMachine.snapshotOf(
                running.copy(state = TrackingState.PAUSED, runningMarker = null), null
            )
        }
    }

    /** The stored state of the task and of the profile's running session, read together under the lock. */
    private data class State(
        val userId: Long,
        val own: TrackingSnapshot,
        val running: TrackingSnapshot?,
    )

    /**
     * The shape every request follows: read the stored state, ask the state machine, write what it
     * allowed inside one transaction.
     *
     * The read is inside the lock on purpose — deciding from a snapshot taken before it would let two
     * taps both see "nothing running" and both write.
     */
    private suspend fun request(
        task: Task,
        decide: (State, Long, (Long) -> WorkTimeMath.MeasuredDuration) -> List<Pair<TrackingSnapshot, TrackingDecision>>
    ): TrackingOutcome {
        val userId = sessionRepository.currentUserId() ?: return TrackingOutcome.NO_PROFILE
        return trackingMutex.withLock {
            val now = timeBase.wallMillis()
            val state = readState(task, userId)
            val measure: (Long) -> WorkTimeMath.MeasuredDuration = { since ->
                WorkTimeMath.measureInterval(since, now, monotonic.elapsedSince(since))
            }
            val plan = decide(state, now, measure)
            if (plan.any { it.second is TrackingDecision.Conflict }) {
                return@withLock TrackingOutcome.CONFLICT
            }
            val writes = plan.mapNotNull { (subject, decision) ->
                (decision as? TrackingDecision.Perform)?.let { subject to it.effect }
            }
            if (writes.isEmpty()) return@withLock TrackingOutcome.UNCHANGED
            database.withTransaction {
                writes.forEach { (subject, effect) -> apply(userId, subject, effect, now) }
            }
            outcomeOf(writes.last().second)
        }
    }

    private suspend fun readState(task: Task, userId: Long): State {
        val ownSession = trackingSessionDao
            .getLiveSessionsForTask(userId, task.id)
            .firstOrNull()
        val ownInterval = ownSession?.let { openIntervalOf(it.uuid) }
        val runningSession = trackingSessionDao.getRunningOnce(userId)
        val runningInterval = runningSession?.let { openIntervalOf(it.uuid) }
        return State(
            userId = userId,
            own = WorkTrackingStateMachine.snapshotOf(task, ownSession, ownInterval),
            running = if (runningSession == null) null
            else WorkTrackingStateMachine.snapshotOf(runningSession, runningInterval),
        )
    }

    /** The open interval of one session, which is the only thing that makes a RUNNING session real. */
    private suspend fun openIntervalOf(sessionUuid: String): WorkSession? =
        workSessionDao.getIntervalsOf(sessionUuid).firstOrNull { it.isOpen }

    /**
     * Writes one accepted effect: the session row, then the interval row it asks for.
     *
     * A new session is inserted rather than an old one reopened, so a stopped run stays stopped in the
     * history; continuing a paused run updates its own row, which is what keeps its intervals under one
     * uuid and the pause out of the totals.
     */
    private suspend fun apply(userId: Long, subject: TrackingSnapshot, effect: TrackingEffect, now: Long) {
        val session = if (effect.beginsNewSession || subject.sessionUuid == null) {
            TrackingSession(
                uuid = UUID.randomUUID().toString(),
                userId = userId,
                taskId = subject.taskId,
                taskUuid = subject.taskUuid,
                taskTitle = subject.taskTitle,
                state = effect.state,
                startedAt = now,
                stoppedAt = effect.stoppedAt,
                zoneId = ZoneId.systemDefault().id,
                runningMarker = effect.runningMarker,
                syncState = SyncState.PENDING,
                createdAt = now,
                updatedAt = now
            ).also { trackingSessionDao.insert(it) }
        } else {
            val existing = trackingSessionDao.getByUuid(subject.sessionUuid)
                ?: return // The session went away between the read and the write; nothing to update.
            existing.copy(
                state = effect.state,
                runningMarker = effect.runningMarker,
                stoppedAt = effect.stoppedAt ?: existing.stoppedAt,
                syncState = SyncState.PENDING,
                updatedAt = now
            ).also { trackingSessionDao.update(it) }
        }

        when (val op = effect.interval) {
            null -> Unit
            is IntervalOp.Open -> workSessionDao.insert(
                WorkSession(
                    uuid = UUID.randomUUID().toString(),
                    userId = session.userId,
                    trackingSessionUuid = session.uuid,
                    taskId = session.taskId,
                    taskUuid = session.taskUuid,
                    startedAt = op.startedAt,
                    localDay = WorkTimeMath.epochDayOf(op.startedAt),
                    utcOffsetMinutes = WorkTimeMath.utcOffsetMinutesOf(op.startedAt),
                    source = WorkSource.TRACKER,
                    openMarker = session.uuid,
                    createdAt = now,
                    updatedAt = now
                )
            )
            is IntervalOp.Close -> {
                val open = openIntervalOf(session.uuid) ?: return
                workSessionDao.update(
                    open.copy(
                        endedAt = op.endedAt,
                        durationSeconds = op.duration.seconds,
                        clockAnomaly = op.duration.clockAnomaly,
                        openMarker = null,
                        updatedAt = now
                    )
                )
            }
        }
    }

    private fun outcomeOf(effect: TrackingEffect): TrackingOutcome = when {
        effect.state == TrackingState.PAUSED -> TrackingOutcome.PAUSED
        effect.state == TrackingState.COMPLETED -> TrackingOutcome.STOPPED
        effect.beginsNewSession -> TrackingOutcome.STARTED
        else -> TrackingOutcome.RESUMED
    }
}
