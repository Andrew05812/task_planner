package com.taskplanner.data.tracking

import com.taskplanner.data.model.TrackingSession
import com.taskplanner.data.model.TrackingState
import com.taskplanner.data.model.WorkSession
import com.taskplanner.data.tracking.WorkTimeMath.MeasuredDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The state machine as a table of decisions, with no database and no clock of its own.
 *
 * What is being enforced here is the set of mistakes the feature would otherwise ship with: a second
 * tap that starts a second timer, a pause that writes a row for time nobody worked, a Stop on a paused
 * session that appends a zero-length interval so the ticker has something to point at, and a restart
 * that reads a phase out of memory that is already gone. Each test names the row-level effect the
 * decision is allowed to have, because that effect is what the repository writes.
 */
class WorkTrackingStateMachineTest {

    private val userId = 1L
    private val otherUserId = 2L
    private val taskId = 10L
    private val otherTaskId = 20L
    private val now = 1_700_000_000_000L

    private val measure: (Long) -> MeasuredDuration = { MeasuredDuration(seconds = (now - it) / 1000L) }

    private fun running(openIntervalSince: Long = now - 600_000L) = TrackingSnapshot(
        taskId = taskId,
        taskTitle = "Write the report",
        phase = TrackingPhase.RUNNING,
        openSince = openIntervalSince,
    )

    private val paused = TrackingSnapshot(
        taskId = taskId,
        taskTitle = "Write the report",
        phase = TrackingPhase.PAUSED,
    )

    private val completed = TrackingSnapshot(
        taskId = taskId,
        taskTitle = "Write the report",
        phase = TrackingPhase.COMPLETED,
    )

    private val idle = TrackingSnapshot(
        taskId = otherTaskId,
        taskTitle = "Review the draft",
        phase = TrackingPhase.IDLE,
    )

    private fun perform(decision: TrackingDecision): TrackingEffect {
        assertTrue("expected a write, got $decision", decision is TrackingDecision.Perform)
        return (decision as TrackingDecision.Perform).effect
    }

    @Test
    fun `start on a task that has never been tracked opens a running session`() {
        val effect = perform(WorkTrackingStateMachine.start(userId, idle, running = null, now = now))

        assertEquals(TrackingState.RUNNING, effect.state)
        assertEquals(IntervalOp.Open(now), effect.interval)
        assertEquals(userId.toString(), effect.runningMarker)
        assertTrue(effect.beginsNewSession)
        assertNull(effect.stoppedAt)
    }

    @Test
    fun `a second start on the running task writes nothing`() {
        val decision = WorkTrackingStateMachine.start(userId, running(), running = null, now = now)

        assertEquals(TrackingDecision.Ignored(TrackingReason.ALREADY_RUNNING), decision)
    }

    @Test
    fun `start is refused rather than duplicated when another task holds the only timer`() {
        val decision = WorkTrackingStateMachine.start(
            userId = userId,
            own = idle,
            running = running(),
            now = now,
        )

        assertEquals(
            "the user has to choose what happens to the running session, the app may not decide",
            TrackingDecision.Conflict(taskId, "Write the report"),
            decision,
        )
    }

    @Test
    fun `start on a paused session of the same task resumes it without abandoning the run`() {
        val effect = perform(
            WorkTrackingStateMachine.start(userId, paused, running = null, now = now)
        )

        assertEquals(TrackingState.RUNNING, effect.state)
        assertEquals(IntervalOp.Open(now), effect.interval)
        assertFalse(
            "the resumed run keeps its uuid, so its intervals stay one session",
            effect.beginsNewSession
        )
    }

    @Test
    fun `starting again after a stop is a new run, not a reopening of history`() {
        val effect = perform(WorkTrackingStateMachine.start(userId, completed, running = null, now = now))

        assertTrue("a stopped session is never written back to RUNNING", effect.beginsNewSession)
        assertEquals(TrackingState.RUNNING, effect.state)
    }

    @Test
    fun `pause closes the open interval and keeps the session resumable`() {
        val effect = perform(WorkTrackingStateMachine.pause(running(), now = now, measure = measure))

        assertEquals(TrackingState.PAUSED, effect.state)
        assertNull(effect.stoppedAt)
        assertNull("a paused session holds no timer", effect.runningMarker)
        assertEquals(
            IntervalOp.Close(startedAt = now - 600_000L, endedAt = now, duration = MeasuredDuration(600L)),
            effect.interval
        )
    }

    @Test
    fun `a second pause writes nothing`() {
        assertEquals(
            TrackingDecision.Ignored(TrackingReason.ALREADY_PAUSED),
            WorkTrackingStateMachine.pause(paused, now = now, measure = measure),
        )
    }

    @Test
    fun `resume opens a fresh interval under the same session instead of lengthening the closed one`() {
        val effect = perform(
            WorkTrackingStateMachine.resume(userId, paused, running = null, now = now)
        )

        assertEquals(TrackingState.RUNNING, effect.state)
        assertEquals(IntervalOp.Open(now), effect.interval)
        assertEquals(userId.toString(), effect.runningMarker)
        assertFalse(effect.beginsNewSession)
    }

    @Test
    fun `resume on a task that is not tracking has nothing to resume`() {
        assertEquals(
            TrackingDecision.Ignored(TrackingReason.NO_LIVE_SESSION),
            WorkTrackingStateMachine.resume(userId, idle, running = null, now = now),
        )
        assertEquals(
            TrackingDecision.Ignored(TrackingReason.NO_LIVE_SESSION),
            WorkTrackingStateMachine.resume(userId, completed, running = null, now = now),
        )
    }

    @Test
    fun `resume of a paused task waits for the user while another task is still running`() {
        val runningElsewhere = running().copy(taskId = 99L, taskTitle = "Fix the build")

        assertEquals(
            TrackingDecision.Conflict(99L, "Fix the build"),
            WorkTrackingStateMachine.resume(userId, paused, running = runningElsewhere, now = now),
        )
    }

    @Test
    fun `stop on a running session closes the interval and the session together`() {
        val effect = perform(WorkTrackingStateMachine.stop(running(), now = now, measure = measure))

        assertEquals(TrackingState.COMPLETED, effect.state)
        assertEquals(now, effect.stoppedAt)
        assertNull(effect.runningMarker)
        assertTrue(effect.interval is IntervalOp.Close)
    }

    @Test
    fun `stop on a paused session adds no interval`() {
        val effect = perform(WorkTrackingStateMachine.stop(paused, now = now, measure = measure))

        assertEquals(TrackingState.COMPLETED, effect.state)
        assertEquals(now, effect.stoppedAt)
        assertNull(
            "the pause was not work; an interval here would be a measurement that never happened",
            effect.interval,
        )
    }

    @Test
    fun `a second stop writes nothing`() {
        assertEquals(
            TrackingDecision.Ignored(TrackingReason.ALREADY_STOPPED),
            WorkTrackingStateMachine.stop(completed, now = now, measure = measure),
        )
    }

    @Test
    fun `pause and stop on a task with no session write nothing`() {
        assertEquals(
            TrackingDecision.Ignored(TrackingReason.NO_LIVE_SESSION),
            WorkTrackingStateMachine.pause(idle, now = now, measure = measure),
        )
        assertEquals(
            TrackingDecision.Ignored(TrackingReason.NO_LIVE_SESSION),
            WorkTrackingStateMachine.stop(idle, now = now, measure = measure),
        )
    }

    @Test
    fun `pausing the active task for the new one releases the timer before taking it`() {
        val decisions = WorkTrackingStateMachine.resolveStart(
            userId = userId,
            active = running(),
            target = idle,
            resolution = ConflictResolution.PAUSE_ACTIVE,
            now = now,
            measure = measure,
        )

        assertEquals(2, decisions.size)
        assertEquals(TrackingState.PAUSED, perform(decisions[0]).state)
        assertNull("the released session must give up the marker before the new one takes it",
            perform(decisions[0]).runningMarker)
        assertEquals(TrackingState.RUNNING, perform(decisions[1]).state)
        assertEquals(userId.toString(), perform(decisions[1]).runningMarker)
    }

    @Test
    fun `stopping the active task for the new one closes it first`() {
        val decisions = WorkTrackingStateMachine.resolveStart(
            userId = userId,
            active = running(),
            target = idle,
            resolution = ConflictResolution.STOP_ACTIVE,
            now = now,
            measure = measure,
        )

        assertEquals(TrackingState.COMPLETED, perform(decisions[0]).state)
        assertEquals(now, perform(decisions[0]).stoppedAt)
        assertEquals(TrackingState.RUNNING, perform(decisions[1]).state)
    }

    @Test
    fun `cancelling a conflict leaves both sessions exactly as they were`() {
        val decisions = WorkTrackingStateMachine.resolveStart(
            userId = userId,
            active = running(),
            target = idle,
            resolution = ConflictResolution.CANCEL,
            now = now,
            measure = measure,
        )

        assertTrue("cancel must not write a single row", decisions.isEmpty())
    }

    @Test
    fun `completing a task that is being tracked stops the tracking instead of orphaning it`() {
        val effect = perform(
            WorkTrackingStateMachine.onTaskCompleted(running(), now = now, measure = measure)
        )

        assertEquals(TrackingState.COMPLETED, effect.state)
        assertTrue(effect.interval is IntervalOp.Close)
    }

    @Test
    fun `completing a task with a paused session closes the session without inventing an interval`() {
        val effect = perform(
            WorkTrackingStateMachine.onTaskCompleted(paused, now = now, measure = measure)
        )

        assertEquals(TrackingState.COMPLETED, effect.state)
        assertNull(effect.interval)
    }

    @Test
    fun `completing a task that is not tracked changes nothing about tracking`() {
        assertEquals(
            TrackingDecision.Ignored(TrackingReason.NO_LIVE_SESSION),
            WorkTrackingStateMachine.onTaskCompleted(idle, now = now, measure = measure),
        )
        assertEquals(
            TrackingDecision.Ignored(TrackingReason.NO_LIVE_SESSION),
            WorkTrackingStateMachine.onTaskCompleted(completed, now = now, measure = measure),
        )
    }

    @Test
    fun `the stored rows are what decide the phase after a restart`() {
        val session = TrackingSession(
            uuid = "session-1",
            userId = userId,
            taskId = taskId,
            taskUuid = "task-1",
            taskTitle = "Write the report",
            state = TrackingState.RUNNING,
            startedAt = now - 600_000L,
            runningMarker = userId.toString(),
        )
        val open = WorkSession(
            uuid = "interval-1",
            userId = userId,
            trackingSessionUuid = session.uuid,
            taskId = taskId,
            taskUuid = "task-1",
            startedAt = now - 600_000L,
            localDay = 20_644L,
            utcOffsetMinutes = -300,
            openMarker = session.uuid,
        )

        val restored = WorkTrackingStateMachine.snapshotOf(session, open, taskId, "Write the report")

        assertEquals(TrackingPhase.RUNNING, restored.phase)
        assertEquals(now - 600_000L, restored.openSince)
        assertEquals("the stored title is what history reads after a rename",
            "Write the report", restored.taskTitle)
    }

    @Test
    fun `a task with no rows is idle, which is what a fresh install and an old database look like`() {
        val restored = WorkTrackingStateMachine.snapshotOf(null, null, taskId, "Write the report")

        assertEquals(TrackingPhase.IDLE, restored.phase)
        assertNull(restored.openSince)
        assertFalse(restored.isLive)
    }

    @Test
    fun `a session marked running with no open interval is read as paused rather than repaired`() {
        val session = TrackingSession(
            uuid = "session-2",
            userId = userId,
            taskId = taskId,
            taskUuid = "task-1",
            taskTitle = "Write the report",
            state = TrackingState.RUNNING,
            startedAt = now - 600_000L,
        )

        val restored = WorkTrackingStateMachine.snapshotOf(session, null, taskId, "Write the report")

        assertEquals(
            "a row lost mid-write must not be read as a timer that is still running",
            TrackingPhase.PAUSED,
            restored.phase,
        )
        assertTrue(restored.isLive)
    }

    @Test
    fun `a stopped session stays stopped however its intervals look`() {
        val session = TrackingSession(
            uuid = "session-3",
            userId = userId,
            taskId = taskId,
            taskUuid = "task-1",
            taskTitle = "Write the report",
            state = TrackingState.COMPLETED,
            startedAt = now - 600_000L,
            stoppedAt = now,
        )
        val open = WorkSession(
            uuid = "interval-2",
            userId = otherUserId,
            trackingSessionUuid = session.uuid,
            taskId = taskId,
            taskUuid = "task-1",
            startedAt = now - 600_000L,
            localDay = 20_644L,
            utcOffsetMinutes = -300,
            openMarker = session.uuid,
        )

        assertEquals(
            TrackingPhase.COMPLETED,
            WorkTrackingStateMachine.snapshotOf(session, open, taskId, "Write the report").phase,
        )
    }

    @Test
    fun `the marker a start takes belongs to the profile that asked, not to whoever is running`() {
        val effect = perform(
            WorkTrackingStateMachine.start(userId = otherUserId, own = idle, running = null, now = now)
        )

        assertEquals(
            "two profiles each keep their own timer, so the marker is the acting profile's id",
            otherUserId.toString(),
            effect.runningMarker,
        )
    }
}
