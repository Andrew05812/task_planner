package com.taskplanner.data.repository

import androidx.room.withTransaction
import com.taskplanner.data.local.AppDatabase
import com.taskplanner.data.local.TrackingSessionDao
import com.taskplanner.data.local.WorkSessionDao
import com.taskplanner.data.model.Task
import com.taskplanner.data.model.TrackingSession
import com.taskplanner.data.model.TrackingState
import com.taskplanner.data.model.SyncState
import com.taskplanner.data.model.WorkIntervalCorrection
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

/** Why an interval the user asked for was not written. */
enum class AdjustmentRejection {
    NO_PROFILE,

    /** An interval that ends before it starts is not a measurement of anything. */
    START_NOT_BEFORE_END,

    /** The stretch is still being measured; the correction waits until it is closed. */
    STILL_BEING_MEASURED,

    /** The row the request named is not in this profile's history. */
    NOT_FOUND,
}

/**
 * What a manual interval or a correction ended up doing.
 *
 * A sealed type rather than a null or a boolean because the screen has three genuinely different answers
 * to give and each needs different words: a write landed, a write is impossible, or the stretch the user
 * described is already occupied by measurements they have to look at first. [Overlapping] is the §13
 * rule made explicit — a conflicting entry is shown as a conflict and never saved quietly.
 */
sealed class AdjustmentResult {

    /** The uuid of the interval that is now in history. */
    data class Accepted(val intervalUuid: String) : AdjustmentResult()

    data class Rejected(val reason: AdjustmentRejection) : AdjustmentResult()

    /** The intervals that already cover the requested stretch; nothing was written. */
    data class Overlapping(val intervals: List<WorkSession>) : AdjustmentResult()
}

/**
 * The only writer of work history: it turns the state machine's decision into rows, in one transaction,
 * and it writes the entries and corrections the user asks for without a timer.
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
 *
 * Manual work enters as a session and one closed interval rather than as an interval on its own, and a
 * correction appends a record of what was changed instead of erasing it: both keep the history the
 * screens read honest about which figure was measured and which was declared afterwards.
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
     * Records work the user says they did without the timer measuring it.
     *
     * The entry becomes a session and one closed interval with [WorkSource.MANUAL], rather than a bare
     * interval row: the history screen lists sessions, so an interval with no session under it would be
     * invisible in the very list the entry was made for. The session opens and closes at the instants
     * the user named, and no pause is invented around it.
     *
     * Two things are refused rather than repaired. An interval that ends at or before its start measures
     * nothing, and clamping it to zero would record work that did not happen. An entry whose stretch is
     * already occupied by other measurements comes back as [AdjustmentResult.Overlapping] with those rows
     * attached — a second account of the same half hour would inflate every total built on this table,
     * and only the user can say which of the two is theirs.
     */
    suspend fun recordManualInterval(
        task: Task,
        startedAt: Long,
        endedAt: Long
    ): AdjustmentResult = adjustment { userId ->
        if (endedAt <= startedAt) {
            return@adjustment AdjustmentResult.Rejected(AdjustmentRejection.START_NOT_BEFORE_END)
        }
        val overlapping = workSessionDao.findOverlapping(userId, startedAt, endedAt, NO_UUID)
        if (overlapping.isNotEmpty()) {
            return@adjustment AdjustmentResult.Overlapping(overlapping)
        }
        val now = timeBase.wallMillis()
        val sessionUuid = UUID.randomUUID().toString()
        val intervalUuid = UUID.randomUUID().toString()
        database.withTransaction {
            trackingSessionDao.insert(
                TrackingSession(
                    uuid = sessionUuid,
                    userId = userId,
                    taskId = task.id,
                    taskUuid = task.uuid,
                    taskTitle = task.title,
                    state = TrackingState.COMPLETED,
                    startedAt = startedAt,
                    stoppedAt = endedAt,
                    zoneId = ZoneId.systemDefault().id,
                    runningMarker = null,
                    syncState = SyncState.PENDING,
                    createdAt = now,
                    updatedAt = now
                )
            )
            workSessionDao.insert(
                WorkSession(
                    uuid = intervalUuid,
                    userId = userId,
                    trackingSessionUuid = sessionUuid,
                    taskId = task.id,
                    taskUuid = task.uuid,
                    startedAt = startedAt,
                    endedAt = endedAt,
                    durationSeconds = (endedAt - startedAt) / 1000L,
                    localDay = WorkTimeMath.epochDayOf(startedAt),
                    utcOffsetMinutes = WorkTimeMath.utcOffsetMinutesOf(startedAt),
                    source = WorkSource.MANUAL,
                    openMarker = null,
                    createdAt = now,
                    updatedAt = now
                )
            )
        }
        AdjustmentResult.Accepted(intervalUuid)
    }

    /**
     * Changes what a closed interval says, and keeps what it said before.
     *
     * The row is updated because the user's corrected account is the figure every total should use from
     * now on. The correction row is appended because that is the only place the distinction between "this
     * was measured" and "this is what the user now believes" survives; a rewrite without it would make the
     * history untraceable, and §3.2 forbids it.
     *
     * The day the interval is filed under follows the corrected start, since the point of a correction is
     * that the work happened elsewhere in time. What the stretch was measured *with* is left alone: a
     * recorded clock anomaly is a fact about the measurement, and the user's opinion about the numbers
     * does not undo it.
     */
    suspend fun correctInterval(
        intervalUuid: String,
        startedAt: Long,
        endedAt: Long,
        reason: String?
    ): AdjustmentResult = adjustment { userId ->
        val existing = ownInterval(userId, intervalUuid)
            ?: return@adjustment AdjustmentResult.Rejected(AdjustmentRejection.NOT_FOUND)
        if (existing.isOpen) {
            return@adjustment AdjustmentResult.Rejected(AdjustmentRejection.STILL_BEING_MEASURED)
        }
        if (endedAt <= startedAt) {
            return@adjustment AdjustmentResult.Rejected(AdjustmentRejection.START_NOT_BEFORE_END)
        }
        val overlapping = workSessionDao.findOverlapping(userId, startedAt, endedAt, intervalUuid)
        if (overlapping.isNotEmpty()) {
            return@adjustment AdjustmentResult.Overlapping(overlapping)
        }
        val now = timeBase.wallMillis()
        database.withTransaction {
            workSessionDao.insertCorrection(
                WorkIntervalCorrection(
                    uuid = UUID.randomUUID().toString(),
                    intervalUuid = intervalUuid,
                    userId = userId,
                    oldStartedAt = existing.startedAt,
                    oldEndedAt = existing.endedAt,
                    newStartedAt = startedAt,
                    newEndedAt = endedAt,
                    reason = reason?.takeIf { it.isNotBlank() },
                    syncState = SyncState.PENDING,
                    createdAt = now,
                    updatedAt = now
                )
            )
            workSessionDao.update(
                existing.copy(
                    startedAt = startedAt,
                    endedAt = endedAt,
                    durationSeconds = (endedAt - startedAt) / 1000L,
                    localDay = WorkTimeMath.epochDayOf(startedAt),
                    utcOffsetMinutes = WorkTimeMath.utcOffsetMinutesOf(startedAt),
                    syncState = SyncState.PENDING,
                    updatedAt = now
                )
            )
        }
        AdjustmentResult.Accepted(intervalUuid)
    }

    /**
     * Throws an interval away.
     *
     * Softly, and only when it is closed: the row and its correction history stay in the database, because
     * an interval the user later restores or syncs to another device has to carry the same account, and
     * the append-only rule cannot survive a delete that erases what was corrected. An open row is not the
     * user's to throw away while it is being measured — it is the timer, and Stop is the way to end it.
     */
    suspend fun removeInterval(intervalUuid: String): AdjustmentResult = adjustment { userId ->
        val existing = ownInterval(userId, intervalUuid)
            ?: return@adjustment AdjustmentResult.Rejected(AdjustmentRejection.NOT_FOUND)
        if (existing.isOpen) {
            return@adjustment AdjustmentResult.Rejected(AdjustmentRejection.STILL_BEING_MEASURED)
        }
        database.withTransaction {
            workSessionDao.softDelete(existing.uuid, timeBase.wallMillis())
        }
        AdjustmentResult.Accepted(existing.uuid)
    }

    /**
     * The shape every adjustment follows: take the profile, then the lock, then read what the history
     * already says before writing.
     *
     * The same lock the tracker uses, because an adjustment and a Start are writers on the same rows: a
     * manual entry that lands while an interval is being closed has to be checked against the state the
     * close leaves behind, not against the state before it.
     */
    private suspend fun adjustment(
        block: suspend (userId: Long) -> AdjustmentResult
    ): AdjustmentResult = trackingMutex.withLock {
        val userId = sessionRepository.currentUserId()
            ?: return@withLock AdjustmentResult.Rejected(AdjustmentRejection.NO_PROFILE)
        block(userId)
    }

    /**
     * The interval [intervalUuid] names, when this profile may change it, and null when it may not.
     *
     * Two profiles can hold the same uuid after an import, and the one that does not own the row has no
     * business editing it — that is §16's isolation, asked here rather than trusted of the screen that
     * happens to hold the uuid. A row that was thrown away is no longer part of the history the user is
     * reading either. Whether what comes back is still being measured is the caller's to answer, since it
     * reports that case differently from a row that is not there.
     */
    private suspend fun ownInterval(userId: Long, intervalUuid: String): WorkSession? {
        val existing = workSessionDao.getByUuid(intervalUuid) ?: return null
        return existing.takeIf { it.userId == userId && !it.isDeleted }
    }

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

    companion object {
        /**
         * The exclude value that excludes nothing.
         *
         * The overlap query leaves the row being corrected out by its uuid, and every stored row has a
         * generated one, so an empty uuid leaves the whole history in the comparison — which is what a
         * brand new manual entry needs.
         */
        private const val NO_UUID = ""
    }
}
