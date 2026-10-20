package com.taskplanner.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.taskplanner.data.model.Task
import com.taskplanner.data.model.TrackingSession
import com.taskplanner.data.model.TrackingState
import com.taskplanner.data.model.SyncState
import com.taskplanner.data.model.WorkSession
import com.taskplanner.data.model.WorkSource
import com.taskplanner.data.tracking.ConflictResolution
import com.taskplanner.data.tracking.TrackingPhase
import com.taskplanner.data.tracking.WorkTimeMath
import com.taskplanner.testing.AppFixture
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The tracker as stored rows: what a Start leaves in the database, what a Pause closes, what a second
 * tap refuses to write, and what a brand new repository instance reads back after a process death.
 *
 * Everything is asserted against the rows rather than against a returned value, because the rows are
 * what the history screen, the statistics, the backup and the next app start all read. The clock is the
 * fixture's fake, so a duration in a test is a duration the test chose — including the case where the
 * user moves the system clock mid-interval, which is the one a device test could never pin down.
 */
@RunWith(RobolectricTestRunner::class)
class WorkTrackingRepositoryTest {

    private lateinit var fixture: AppFixture
    private var userId: Long = -1L

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        fixture = AppFixture(context)
    }

    /** Signs in and creates the two tasks the conflict tests need. */
    private suspend fun signInWithTwoTasks(): Pair<Task, Task> {
        val user = fixture.signIn()
        userId = user.id
        return addTask(user.id, "Write the report") to addTask(user.id, "Review the draft")
    }

    private suspend fun addTask(forUserId: Long, title: String): Task {
        val id = fixture.taskDao.insertTask(
            Task(uuid = "task-$title", title = title, description = "", userId = forUserId)
        )
        return fixture.taskDao.getTaskById(id)!!
    }

    private suspend fun sessions(): List<TrackingSession> =
        fixture.trackingSessionDao.getAllForUser(userId)

    private suspend fun intervals(): List<WorkSession> =
        fixture.workSessionDao.getAllForUser(userId).sortedBy { it.startedAt }

    private suspend fun openIntervals(): List<WorkSession> = intervals().filter { it.isOpen }

    /** The correction history of one interval, as this fixture's own profile may read it. */
    private suspend fun correctionsOf(intervalUuid: String) =
        fixture.workSessionDao.getCorrectionsOf(intervalUuid, userId)

    /** Starts work on [task] after [forMillis] of real time has passed since the fixture began. */
    private suspend fun startAfter(task: Task, forMillis: Long): TrackingOutcome {
        fixture.timeBase.advance(forMillis)
        return fixture.workTrackingRepository.start(task)
    }

    /**
     * Puts the fixture's wall clock at noon of the day that clock is already on.
     *
     * The fixture takes the machine's instant as its starting point, and the scenario below runs for a
     * quarter of an hour of clock time. A suite that happens to start within that quarter before midnight
     * therefore measures the first stretch on one calendar day and asks for the figure on the next — and
     * the read is right to answer that the earlier work is yesterday's. Parking at noon is the only way the
     * test gets to assert the day it began on rather than the time of day the runner woke up at.
     */
    private fun parkAtNoon() {
        val zone = ZoneId.systemDefault()
        val day = Instant.ofEpochMilli(fixture.timeBase.wall).atZone(zone).toLocalDate()
        fixture.timeBase.wall = day.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
    }

    @Test
    fun `start leaves one running session and one open interval behind`() = runTest {
        val (report, _) = signInWithTwoTasks()

        val outcome = startAfter(report, forMillis = 1_000L)

        assertEquals(TrackingOutcome.STARTED, outcome)
        val session = sessions().single()
        assertEquals(TrackingState.RUNNING, session.state)
        assertEquals(report.id, session.taskId)
        assertEquals("Write the report", session.taskTitle)
        assertEquals(userId.toString(), session.runningMarker)
        val open = openIntervals().single()
        assertEquals(session.uuid, open.trackingSessionUuid)
        assertEquals(session.startedAt, open.startedAt)
        assertNull("the interval has not ended, so its length is not yet a fact", open.endedAt)
        assertNull(open.durationSeconds)
    }

    @Test
    fun `a second start writes no session and no interval`() = runTest {
        val (report, _) = signInWithTwoTasks()
        startAfter(report, 1_000L)
        val before = sessions().size to intervals().size

        val outcome = fixture.workTrackingRepository.start(report)

        assertEquals(TrackingOutcome.UNCHANGED, outcome)
        assertEquals(before, sessions().size to intervals().size)
        assertEquals("still the one timer", 1, openIntervals().size)
    }

    @Test
    fun `pause closes the open interval with a measured duration and frees the running slot`() = runTest {
        val (report, _) = signInWithTwoTasks()
        startAfter(report, 1_000L)

        fixture.timeBase.advance(600_000L)
        val outcome = fixture.workTrackingRepository.pause(report)

        assertEquals(TrackingOutcome.PAUSED, outcome)
        val session = sessions().single()
        assertEquals(TrackingState.PAUSED, session.state)
        assertNull("a paused session holds no timer", session.runningMarker)
        assertNull(session.stoppedAt)
        val closed = intervals().single()
        assertNotNull(closed.endedAt)
        assertNull(closed.openMarker)
        assertEquals(600L, closed.durationSeconds)
        assertNull("the clock was not moved, so there is nothing to explain", closed.clockAnomaly)
    }

    @Test
    fun `a second pause adds no row`() = runTest {
        val (report, _) = signInWithTwoTasks()
        startAfter(report, 1_000L)
        fixture.workTrackingRepository.pause(report)
        val before = sessions().size to intervals().size

        val outcome = fixture.workTrackingRepository.pause(report)

        assertEquals(TrackingOutcome.UNCHANGED, outcome)
        assertEquals(before, sessions().size to intervals().size)
    }

    @Test
    fun `a start-pause-resume-stop run stores two intervals and never counts the break`() = runTest {
        val (report, _) = signInWithTwoTasks()
        // 09:00–09:30 worked, half an hour paused, 10:00–10:45 worked: 75 minutes in two stretches.
        startAfter(report, 0L)
        fixture.timeBase.advance(30 * 60_000L)
        fixture.workTrackingRepository.pause(report)
        fixture.timeBase.advance(30 * 60_000L)
        fixture.workTrackingRepository.resume(report)
        fixture.timeBase.advance(45 * 60_000L)
        val outcome = fixture.workTrackingRepository.stop(report)

        assertEquals(TrackingOutcome.STOPPED, outcome)
        val session = sessions().single()
        assertEquals("one run is one session, however many stretches it has", 1, sessions().size)
        assertEquals(TrackingState.COMPLETED, session.state)
        assertEquals(2, intervals().size)
        assertEquals(listOf(1800L, 2700L), intervals().map { it.durationSeconds })
        assertEquals(
            "the pause between them is not time anyone worked",
            75L * 60L,
            intervals().sumOf { it.durationSeconds!! }
        )
        assertEquals(
            "both stretches belong to the same logical session",
            setOf(session.uuid),
            intervals().map { it.trackingSessionUuid }.toSet()
        )
        assertTrue(openIntervals().isEmpty())
    }

    @Test
    fun `resume after a pause reopens the same session instead of starting a second run`() = runTest {
        val (report, _) = signInWithTwoTasks()
        startAfter(report, 1_000L)
        fixture.workTrackingRepository.pause(report)

        val outcome = fixture.workTrackingRepository.resume(report)

        assertEquals(TrackingOutcome.RESUMED, outcome)
        assertEquals(1, sessions().size)
        assertEquals(TrackingState.RUNNING, sessions().single().state)
        assertEquals("the closed stretch stays exactly as it was written", 1, intervals().count { !it.isOpen })
        assertEquals(1, openIntervals().size)
    }

    @Test
    fun `stop on a paused session closes the session without inventing an interval`() = runTest {
        val (report, _) = signInWithTwoTasks()
        startAfter(report, 1_000L)
        fixture.workTrackingRepository.pause(report)
        val intervalsBefore = intervals().size

        val outcome = fixture.workTrackingRepository.stop(report)

        assertEquals(TrackingOutcome.STOPPED, outcome)
        assertEquals(
            "a zero-length interval would be a measurement of nothing",
            intervalsBefore,
            intervals().size
        )
        val session = sessions().single()
        assertEquals(TrackingState.COMPLETED, session.state)
        assertNotNull(session.stoppedAt)
    }

    @Test
    fun `pause and stop on a task nobody tracked write nothing`() = runTest {
        val (_, draft) = signInWithTwoTasks()

        assertEquals(TrackingOutcome.UNCHANGED, fixture.workTrackingRepository.pause(draft))
        assertEquals(TrackingOutcome.UNCHANGED, fixture.workTrackingRepository.stop(draft))
        assertTrue(sessions().isEmpty())
        assertTrue(intervals().isEmpty())
    }

    @Test
    fun `starting another task while one runs is the user's decision, not the app's`() = runTest {
        val (report, draft) = signInWithTwoTasks()
        startAfter(report, 1_000L)
        val before = sessions().size to intervals().size

        val outcome = fixture.workTrackingRepository.start(draft)

        assertEquals(TrackingOutcome.CONFLICT, outcome)
        assertEquals(before, sessions().size to intervals().size)
        assertEquals("the running session is untouched", report.id, sessions().single().taskId)
    }

    @Test
    fun `pausing the active task for a new one moves the single running slot in one step`() = runTest {
        val (report, draft) = signInWithTwoTasks()
        startAfter(report, 1_000L)

        fixture.timeBase.advance(60_000L)
        val outcome = fixture.workTrackingRepository.start(draft, ConflictResolution.PAUSE_ACTIVE)

        assertEquals(TrackingOutcome.STARTED, outcome)
        assertEquals(2, sessions().size)
        val running = sessions().single { it.state == TrackingState.RUNNING }
        assertEquals(draft.id, running.taskId)
        assertEquals(
            "one profile, one running marker",
            1,
            sessions().count { it.runningMarker != null }
        )
        assertEquals(1, openIntervals().size)
        assertEquals(running.uuid, openIntervals().single().trackingSessionUuid)
        assertEquals(
            "the released session kept the minute it had measured",
            60L,
            intervals().single { !it.isOpen }.durationSeconds
        )
    }

    @Test
    fun `stopping the active task for a new one closes the first run completely`() = runTest {
        val (report, draft) = signInWithTwoTasks()
        startAfter(report, 1_000L)

        fixture.timeBase.advance(120_000L)
        fixture.workTrackingRepository.start(draft, ConflictResolution.STOP_ACTIVE)

        val stopped = sessions().single { it.taskId == report.id }
        assertEquals(TrackingState.COMPLETED, stopped.state)
        assertNotNull(stopped.stoppedAt)
        assertNull(stopped.runningMarker)
        assertTrue(intervals().none { it.isOpen && it.taskId == report.id })
        assertEquals(1, openIntervals().size)
    }

    @Test
    fun `cancelling a conflict leaves the running session exactly as it was`() = runTest {
        val (report, draft) = signInWithTwoTasks()
        startAfter(report, 1_000L)
        val before = sessions().size to intervals().size

        val outcome = fixture.workTrackingRepository.start(draft, ConflictResolution.CANCEL)

        assertEquals(TrackingOutcome.CONFLICT, outcome)
        assertEquals(before, sessions().size to intervals().size)
    }

    @Test
    fun `ten requests for the same timer arriving at once produce exactly one`() = runTest {
        val (report, _) = signInWithTwoTasks()
        val repository = fixture.workTrackingRepository

        // Each of these suspends at the same "read the rows, see nothing running" point, so this is the
        // double tap and the parallel request at once: only the in-process lock plus the unique index
        // can keep it down to one row.
        val outcomes = (1..10).map { async { repository.start(report) } }.awaitAll()

        assertEquals(1, sessions().size)
        assertEquals(1, openIntervals().size)
        assertEquals(
            "one accepted start, nine repeats that wrote nothing",
            1,
            outcomes.count { it == TrackingOutcome.STARTED }
        )
        assertEquals(
            "and the repeats are reported as repeats, not as failures the UI would have to guess at",
            9,
            outcomes.count { it == TrackingOutcome.UNCHANGED }
        )
    }

    @Test
    fun `a new repository instance reads the running timer back out of the database`() = runTest {
        val (report, _) = signInWithTwoTasks()
        startAfter(report, 1_000L)
        fixture.timeBase.advance(90_000L)

        val restarted = fixture.newWorkTrackingRepository()
        val snapshot = restarted.snapshotOf(report)

        assertEquals(
            "the stored start is the source of truth, not a ViewModel that died",
            TrackingPhase.RUNNING,
            snapshot.phase
        )
        assertEquals(fixture.timeBase.wall - 90_000L, snapshot.openSince)
        assertEquals(
            "the ticker measures from the stored instant",
            90L,
            WorkTimeMath.openSeconds(snapshot.openSince!!, fixture.timeBase.wall, null)
        )
        assertEquals(
            "the interval survives the process",
            1,
            restarted.intervalsOf(snapshot.sessionUuid!!).size
        )
    }

    @Test
    fun `start-up recovery releases a running slot that measures nothing`() = runTest {
        val (report, _) = signInWithTwoTasks()
        // What a half-applied import or a pull that delivered the session before its intervals leaves
        // behind: a RUNNING row holding the profile's single slot with no open interval under it.
        fixture.trackingSessionDao.insert(
            TrackingSession(
                uuid = "orphan-session",
                userId = userId,
                taskId = report.id,
                taskUuid = report.uuid,
                taskTitle = report.title,
                state = TrackingState.RUNNING,
                startedAt = fixture.timeBase.wall,
                runningMarker = userId.toString()
            )
        )

        val recovered = fixture.newWorkTrackingRepository().recoverAfterStartUp()

        assertEquals(TrackingPhase.PAUSED, recovered?.phase)
        assertNull(
            "the slot has to be free or the profile could never start work again",
            fixture.trackingSessionDao.getRunningOnce(userId)
        )
        val draftTask = addTask(userId, "Review the draft")
        assertEquals(
            TrackingOutcome.STARTED,
            fixture.newWorkTrackingRepository().start(draftTask)
        )
    }

    @Test
    fun `a clock moved forward mid-interval is stored as the time actually worked`() = runTest {
        val (report, _) = signInWithTwoTasks()
        startAfter(report, 1_000L)

        fixture.timeBase.shiftWall(60 * 60_000L)
        fixture.timeBase.advance(5 * 60_000L)
        fixture.workTrackingRepository.stop(report)

        val closed = intervals().single()
        assertEquals("only the five minutes were worked", 300L, closed.durationSeconds)
        assertNotNull("and the row says why", closed.clockAnomaly)
        assertTrue(closed.clockAnomaly!!.contains("3600"))
    }

    @Test
    fun `completing a task that is being tracked stops the tracking and keeps the measurement`() = runTest {
        val (report, _) = signInWithTwoTasks()
        startAfter(report, 1_000L)
        fixture.timeBase.advance(300_000L)

        fixture.taskRepository.completeAndArchive(report.id)

        val session = sessions().single()
        assertEquals(TrackingState.COMPLETED, session.state)
        assertNotNull(session.stoppedAt)
        assertEquals(300L, intervals().single().durationSeconds)
        assertEquals(
            "the archived task's history still reads as the title it was worked under",
            "Write the report",
            session.taskTitle
        )
    }

    @Test
    fun `completing a task that is not tracked writes no tracking rows`() = runTest {
        val (_, draft) = signInWithTwoTasks()

        fixture.taskRepository.completeAndArchive(draft.id)

        assertTrue(sessions().isEmpty())
        assertTrue(intervals().isEmpty())
    }

    @Test
    fun `another profile keeps its own timer and is not locked out by the first one`() = runTest {
        val (report, _) = signInWithTwoTasks()
        startAfter(report, 1_000L)
        val other = fixture.signIn(name = "Other Person", email = "other@example.com")
        val otherTask = addTask(other.id, "Somebody else's work")

        val outcome = fixture.workTrackingRepository.start(otherTask)

        assertEquals("one running timer per profile, not per device", TrackingOutcome.STARTED, outcome)
        assertEquals(1, openIntervals().size)
        assertEquals(1, fixture.workSessionDao.getAllForUser(other.id).count { it.isOpen })
        assertEquals(
            "the first profile's run is untouched",
            report.id,
            fixture.trackingSessionDao.getRunningOnce(userId)?.taskId
        )
    }

    @Test
    fun `tracking without a signed-in profile writes nothing`() = runTest {
        val (report, _) = signInWithTwoTasks()
        fixture.sessionRepository.endSession()

        val outcome = fixture.workTrackingRepository.start(report)

        assertEquals(TrackingOutcome.NO_PROFILE, outcome)
        assertTrue(fixture.trackingSessionDao.getAllForUser(userId).isEmpty())
    }

    @Test
    fun `every tracking write is left pending for sync rather than claiming to be synced`() = runTest {
        val (report, _) = signInWithTwoTasks()
        startAfter(report, 1_000L)
        fixture.timeBase.advance(60_000L)
        fixture.workTrackingRepository.pause(report)

        assertEquals(SyncState.PENDING, sessions().single().syncState)
        assertEquals(SyncState.PENDING, intervals().single().syncState)
    }

    // ---------------------------------------------------------------- what the screens read back

    @Test
    fun `the board names the task being measured and leaves every other task idle`() = runTest {
        val (report, draft) = signInWithTwoTasks()
        startAfter(report, 1_000L)
        fixture.timeBase.advance(30_000L)

        val board = fixture.workStatisticsRepository.observeWorkBoard().first()

        assertEquals(report.id, board.active?.taskId)
        assertEquals(
            "the bar has to say what it is measuring, from the title the row recorded",
            "Write the report",
            board.active?.taskTitle
        )
        assertEquals(TrackingPhase.RUNNING, board.active?.phase)
        assertEquals(report.id, board.openTaskId)
        assertEquals(
            "the running figure is measured from the stored start, not remembered by a timer",
            30L,
            board.liveSeconds
        )
        assertEquals(TrackingPhase.RUNNING, board.phaseOf(report.id))
        assertEquals(TrackingPhase.IDLE, board.phaseOf(draft.id))
        assertNull("no other card gets a moving number", board.liveSecondsOf(draft.id))
    }

    @Test
    fun `a paused session keeps its task on the board but has nothing to measure`() = runTest {
        val (report, _) = signInWithTwoTasks()
        startAfter(report, 1_000L)
        fixture.timeBase.advance(60_000L)
        fixture.workTrackingRepository.pause(report)

        val board = fixture.workStatisticsRepository.observeWorkBoard().first()

        assertEquals(TrackingPhase.PAUSED, board.active?.phase)
        assertEquals(report.id, board.active?.taskId)
        assertNull("a pause closes the stretch, so no row is open", board.openTaskId)
        assertNull(board.liveSeconds)
        assertEquals(
            "what was measured before the pause is already a stored fact",
            60L,
            board.closedSecondsOf(report.id)
        )
    }

    @Test
    fun `the board is empty for a profile that has never measured anything`() = runTest {
        signInWithTwoTasks()

        val board = fixture.workStatisticsRepository.observeWorkBoard().first()

        assertNull(board.active)
        assertTrue(board.closedByTask.isEmpty())
        assertNull(board.liveSeconds)
    }

    @Test
    /**
     * The tracking block's own read, on a real clock: the day label sleeps until tomorrow, and a
     * virtual-time scheduler skips that sleep instantly, so the flow would re-anchor its query forever
     * instead of answering once. What is asserted stays the same — and the clock is parked at noon first,
     * because the quarter of an hour this scenario runs for must stay inside the day whose figure it claims.
     */
    fun `the task view carries the total, today, and the stretches, with the pause left out`() = runBlocking {
        parkAtNoon()
        val (report, _) = signInWithTwoTasks()
        startAfter(report, 1_000L)
        fixture.timeBase.advance(120_000L)
        fixture.workTrackingRepository.pause(report)
        fixture.timeBase.advance(600_000L)
        fixture.workTrackingRepository.resume(report)
        fixture.timeBase.advance(30_000L)
        fixture.workTrackingRepository.stop(report)

        val view = fixture.workStatisticsRepository.observeTaskWork(report).first()

        assertEquals(150L, view.closedSeconds)
        assertEquals(
            "ten minutes of pause is not work, and it is in none of these figures",
            150L,
            view.totalSeconds
        )
        assertEquals(150L, view.todaySeconds)
        assertEquals(TrackingPhase.IDLE, view.phase)
        assertNull(view.liveSeconds)
        assertEquals(2, view.intervals.size)
        assertEquals(
            "the list is newest first, which is the order the screen prints",
            30L,
            view.intervals.first().durationSeconds
        )
    }

    @Test
    /**
     * The tracking block's own read, on a real clock: the day label sleeps until tomorrow, and a
     * virtual-time scheduler skips that sleep instantly, so the flow would re-anchor its query forever
     * instead of answering once. What is asserted stays the same.
     */
    fun `a task that is not the one being measured gets no live figure`() = runBlocking {
        val (report, draft) = signInWithTwoTasks()
        startAfter(report, 1_000L)
        fixture.timeBase.advance(45_000L)

        val other = fixture.workStatisticsRepository.observeTaskWork(draft).first()
        val measured = fixture.workStatisticsRepository.observeTaskWork(report).first()

        assertEquals(TrackingPhase.IDLE, other.phase)
        assertNull(other.liveSeconds)
        assertEquals(0L, other.closedSeconds)
        assertEquals(TrackingPhase.RUNNING, measured.phase)
        assertEquals(45L, measured.liveSeconds)
        assertEquals(0L, measured.closedSeconds)
    }

    // ---------------------------------------------------------------- time the user types in

    /**
     * A stretch of wall time the fixture's clock puts in the past, so an entered interval is not confused
     * with the run a test may have started at "now".
     */
    private fun entered(beforeNow: Long, seconds: Long): Pair<Long, Long> {
        val startedAt = fixture.timeBase.wall - beforeNow
        return startedAt to startedAt + seconds * 1_000L
    }

    @Test
    fun `a manual entry stores a completed session and one closed interval marked as hand-entered`() = runTest {
        val (report, _) = signInWithTwoTasks()
        val (start, end) = entered(beforeNow = 3_600_000L, seconds = 1_800L)

        val result = fixture.workTrackingRepository.recordManualInterval(report, start, end)

        val intervalUuid = (result as AdjustmentResult.Accepted).intervalUuid
        val session = sessions().single()
        assertEquals(TrackingState.COMPLETED, session.state)
        assertEquals(report.id, session.taskId)
        assertNull("an entered stretch holds no timer", session.runningMarker)
        assertEquals(start, session.startedAt)
        assertEquals(end, session.stoppedAt)
        val stored = fixture.workSessionDao.getByUuid(intervalUuid)!!
        assertEquals(session.uuid, stored.trackingSessionUuid)
        assertEquals(WorkSource.MANUAL, stored.source)
        assertNull("a hand-entered stretch is never the open one", stored.openMarker)
        assertEquals(end, stored.endedAt)
        assertEquals(1_800L, stored.durationSeconds)
        assertEquals(WorkTimeMath.epochDayOf(start), stored.localDay)
        assertTrue("the entry is on the board the history screen reads", stored.taskId == report.id)
    }

    @Test
    fun `an entry that spans no time is refused and writes nothing`() = runTest {
        val (report, _) = signInWithTwoTasks()
        val start = fixture.timeBase.wall - 600_000L

        assertEquals(
            "an interval that ends where it begins measures nothing",
            AdjustmentResult.Rejected(AdjustmentRejection.START_NOT_BEFORE_END),
            fixture.workTrackingRepository.recordManualInterval(report, start, start)
        )
        assertEquals(
            "and neither does one that ends before it starts",
            AdjustmentResult.Rejected(AdjustmentRejection.START_NOT_BEFORE_END),
            fixture.workTrackingRepository.recordManualInterval(report, start, start - 1_000L)
        )
        assertTrue(sessions().isEmpty())
        assertTrue(intervals().isEmpty())
    }

    @Test
    fun `an entry over work that is already measured comes back with the rows in the way and nothing written`() = runTest {
        val (report, draft) = signInWithTwoTasks()
        val (start, end) = entered(beforeNow = 3_600_000L, seconds = 1_800L)
        fixture.workTrackingRepository.recordManualInterval(report, start, end)

        // Half of the entered stretch, asked for under another task.
        val result = fixture.workTrackingRepository.recordManualInterval(
            draft, end - 900_000L, end + 900_000L
        )

        val conflict = result as AdjustmentResult.Overlapping
        assertEquals(listOf(start to end), conflict.intervals.map { it.startedAt to it.endedAt })
        assertEquals("the first entry is not joined by a second account of it", 1, intervals().size)
        assertEquals(1, sessions().size)
    }

    @Test
    fun `a stretch ending exactly when the timer began does not conflict with the running one`() = runTest {
        val (report, draft) = signInWithTwoTasks()
        startAfter(report, 1_000L)
        val open = openIntervals().single()

        val result = fixture.workTrackingRepository.recordManualInterval(
            draft, open.startedAt - 1_800_000L, open.startedAt
        )

        assertTrue("touching at one instant is not sharing time", result is AdjustmentResult.Accepted)
        assertEquals(2, intervals().size)
        assertEquals("the run is still the only open stretch", 1, openIntervals().size)
    }

    @Test
    fun `an entry reaching into a running stretch is refused as a conflict`() = runTest {
        val (report, draft) = signInWithTwoTasks()
        startAfter(report, 1_000L)
        val open = openIntervals().single()

        val result = fixture.workTrackingRepository.recordManualInterval(
            draft, open.startedAt, open.startedAt + 600_000L
        )

        val conflict = result as AdjustmentResult.Overlapping
        assertEquals(
            "the conflict names the stretch the user has to end first",
            listOf(open.uuid),
            conflict.intervals.map { it.uuid }
        )
        assertEquals(1, intervals().size)
    }

    @Test
    fun `ten identical entries asked for at once leave exactly one of them`() = runTest {
        val (report, _) = signInWithTwoTasks()
        val (start, end) = entered(beforeNow = 3_600_000L, seconds = 1_800L)
        val repository = fixture.workTrackingRepository

        val results = (1..10).map { async { repository.recordManualInterval(report, start, end) } }.awaitAll()

        assertEquals(1, results.count { it is AdjustmentResult.Accepted })
        assertEquals(
            "the nine that came second are conflicts the user can see, not silent no-ops",
            9,
            results.count { it is AdjustmentResult.Overlapping }
        )
        assertEquals(1, intervals().size)
        assertEquals(1, sessions().size)
    }

    @Test
    fun `a correction stores the new numbers and remembers what they were`() = runTest {
        val (report, _) = signInWithTwoTasks()
        startAfter(report, 1_000L)
        fixture.timeBase.advance(600_000L)
        fixture.workTrackingRepository.stop(report)
        val measured = intervals().single()

        val (start, end) = entered(beforeNow = 7_200_000L, seconds = 900L)
        val result = fixture.workTrackingRepository.correctInterval(
            measured.uuid, start, end, "started the timer late"
        )

        assertEquals(AdjustmentResult.Accepted(measured.uuid), result)
        val corrected = fixture.workSessionDao.getByUuid(measured.uuid)!!
        assertEquals(start, corrected.startedAt)
        assertEquals(end, corrected.endedAt)
        assertEquals(900L, corrected.durationSeconds)
        assertEquals(
            "the day the work is filed under follows the corrected start",
            WorkTimeMath.epochDayOf(start),
            corrected.localDay
        )
        assertEquals(
            "a correction does not change how the stretch was obtained",
            WorkSource.TRACKER,
            corrected.source
        )
        val correction = correctionsOf(measured.uuid).single()
        assertEquals(measured.startedAt, correction.oldStartedAt)
        assertEquals(measured.endedAt, correction.oldEndedAt)
        assertEquals(start, correction.newStartedAt)
        assertEquals(end, correction.newEndedAt)
        assertEquals("started the timer late", correction.reason)
        assertEquals(userId, correction.userId)
    }

    @Test
    fun `a correction keeps the anomaly the measurement recorded and the blank reason is not stored`() = runTest {
        val (report, _) = signInWithTwoTasks()
        startAfter(report, 1_000L)
        fixture.timeBase.shiftWall(3_600_000L)
        fixture.timeBase.advance(300_000L)
        fixture.workTrackingRepository.stop(report)
        val measured = intervals().single()
        assertNotNull(measured.clockAnomaly)

        val (start, end) = entered(beforeNow = 1_800_000L, seconds = 300L)
        fixture.workTrackingRepository.correctInterval(measured.uuid, start, end, "   ")

        val corrected = fixture.workSessionDao.getByUuid(measured.uuid)!!
        assertEquals(
            "the user's opinion about the numbers does not undo what the clocks said",
            measured.clockAnomaly,
            corrected.clockAnomaly
        )
        assertNull(
            "and an empty reason is left empty rather than turned into words nobody typed",
            correctionsOf(measured.uuid).single().reason
        )
    }

    @Test
    fun `a stretch still being measured is not the user's to correct`() = runTest {
        val (report, _) = signInWithTwoTasks()
        startAfter(report, 1_000L)
        val open = openIntervals().single()
        val (start, end) = entered(beforeNow = 1_800_000L, seconds = 300L)

        val result = fixture.workTrackingRepository.correctInterval(open.uuid, start, end, null)

        assertEquals(AdjustmentResult.Rejected(AdjustmentRejection.STILL_BEING_MEASURED), result)
        assertEquals("the timer keeps running", open.uuid, openIntervals().single().uuid)
        assertNull(fixture.workSessionDao.getByUuid(open.uuid)!!.endedAt)
        assertTrue(correctionsOf(open.uuid).isEmpty())
    }

    @Test
    fun `a correction that would land on other work is refused and the row keeps its numbers`() = runTest {
        val (report, draft) = signInWithTwoTasks()
        val (ownStart, ownEnd) = entered(beforeNow = 7_200_000L, seconds = 600L)
        fixture.workTrackingRepository.recordManualInterval(report, ownStart, ownEnd)
        val (otherStart, otherEnd) = entered(beforeNow = 3_600_000L, seconds = 600L)
        fixture.workTrackingRepository.recordManualInterval(draft, otherStart, otherEnd)
        val movable = fixture.workSessionDao.getByUuid(
            intervals().first { it.startedAt == ownStart }.uuid
        )!!

        val result = fixture.workTrackingRepository.correctInterval(
            movable.uuid, otherStart - 300_000L, otherEnd, "wrong task"
        )

        val conflict = result as AdjustmentResult.Overlapping
        assertEquals(listOf(otherStart to otherEnd), conflict.intervals.map { it.startedAt to it.endedAt })
        val unchanged = fixture.workSessionDao.getByUuid(movable.uuid)!!
        assertEquals(ownStart, unchanged.startedAt)
        assertEquals(ownEnd, unchanged.endedAt)
        assertTrue(
            "a refused correction leaves no history of itself",
            correctionsOf(movable.uuid).isEmpty()
        )
    }

    @Test
    fun `a correction of a row another profile owns, or of no row at all, is refused`() = runTest {
        val (report, _) = signInWithTwoTasks()
        val (start, end) = entered(beforeNow = 3_600_000L, seconds = 600L)
        val mine = fixture.workTrackingRepository.recordManualInterval(report, start, end) as
            AdjustmentResult.Accepted

        // A second profile, with its own entry, and then back to the first.
        val other = fixture.signIn(name = "Other Person", email = "other@example.com")
        val theirStart = start - 86_400_000L
        val theirEnd = start - 82_800_000L
        val theirs = fixture.workTrackingRepository.recordManualInterval(
            addTask(other.id, "Somebody else's work"), theirStart, theirEnd
        ) as AdjustmentResult.Accepted
        fixture.switchTo(fixture.userDao.getUserById(userId)!!)

        assertEquals(
            "the uuid is not a permission; the row belongs to the profile that wrote it",
            AdjustmentResult.Rejected(AdjustmentRejection.NOT_FOUND),
            fixture.workTrackingRepository.correctInterval(theirs.intervalUuid, start, end, null)
        )
        assertEquals(
            AdjustmentResult.Rejected(AdjustmentRejection.NOT_FOUND),
            fixture.workTrackingRepository.correctInterval("no-such-interval", start, end, null)
        )
        val untouched = fixture.workSessionDao.getByUuid(theirs.intervalUuid)!!
        assertEquals(theirStart, untouched.startedAt)
        assertEquals(theirEnd, untouched.endedAt)
        assertTrue(correctionsOf(theirs.intervalUuid).isEmpty())
        assertNotNull(fixture.workSessionDao.getByUuid(mine.intervalUuid))
    }

    @Test
    fun `throwing an interval away hides it and keeps the corrections it had`() = runTest {
        val (report, _) = signInWithTwoTasks()
        startAfter(report, 1_000L)
        fixture.timeBase.advance(600_000L)
        fixture.workTrackingRepository.stop(report)
        val measured = intervals().single()
        val (start, end) = entered(beforeNow = 1_800_000L, seconds = 300L)
        fixture.workTrackingRepository.correctInterval(measured.uuid, start, end, "the run began early")

        val result = fixture.workTrackingRepository.removeInterval(measured.uuid)

        assertEquals(AdjustmentResult.Accepted(measured.uuid), result)
        val kept = fixture.workSessionDao.findIncludingDeleted(measured.uuid)!!
        assertTrue("the history hides the row rather than erasing it", kept.isDeleted)
        assertNotNull(kept.deletedAt)
        assertEquals(
            "the session it belonged to is untouched",
            1,
            sessions().size
        )
        assertEquals(
            "and the run it came from now reads as having no stretches left to show",
            0,
            fixture.workSessionDao.countIntervalsOf(kept.trackingSessionUuid)
        )
        assertEquals(
            "what it was corrected from is still there to be audited",
            1,
            correctionsOf(measured.uuid).size
        )
        assertEquals(
            "a row that was thrown away is not the user's to correct either",
            AdjustmentResult.Rejected(AdjustmentRejection.NOT_FOUND),
            fixture.workTrackingRepository.correctInterval(measured.uuid, start, end, null)
        )
    }

    @Test
    fun `an open stretch cannot be thrown away`() = runTest {
        val (report, _) = signInWithTwoTasks()
        startAfter(report, 1_000L)
        val open = openIntervals().single()

        val result = fixture.workTrackingRepository.removeInterval(open.uuid)

        assertEquals(AdjustmentResult.Rejected(AdjustmentRejection.STILL_BEING_MEASURED), result)
        assertEquals(1, openIntervals().size)
        assertEquals(TrackingState.RUNNING, sessions().single().state)
    }

    @Test
    fun `an adjustment without a signed-in profile writes nothing`() = runTest {
        val (report, _) = signInWithTwoTasks()
        val (start, end) = entered(beforeNow = 1_800_000L, seconds = 300L)
        fixture.sessionRepository.endSession()

        assertEquals(
            AdjustmentResult.Rejected(AdjustmentRejection.NO_PROFILE),
            fixture.workTrackingRepository.recordManualInterval(report, start, end)
        )
        assertTrue(sessions().isEmpty())
        assertTrue(intervals().isEmpty())
    }

    @Test
    fun `a manual entry, a correction and a removal are each left pending for sync`() = runTest {
        val (report, _) = signInWithTwoTasks()
        val (start, end) = entered(beforeNow = 3_600_000L, seconds = 600L)
        val accepted = fixture.workTrackingRepository.recordManualInterval(report, start, end) as
            AdjustmentResult.Accepted

        val (newStart, newEnd) = entered(beforeNow = 7_200_000L, seconds = 600L)
        fixture.workTrackingRepository.correctInterval(accepted.intervalUuid, newStart, newEnd, null)
        val row = fixture.workSessionDao.getByUuid(accepted.intervalUuid)!!
        val correction = correctionsOf(accepted.intervalUuid).single()

        assertEquals(SyncState.PENDING, row.syncState)
        assertEquals(SyncState.PENDING, correction.syncState)
        assertEquals(SyncState.PENDING, sessions().single().syncState)

        fixture.workTrackingRepository.removeInterval(accepted.intervalUuid)

        assertEquals(
            "a row the user threw away still has to reach the other devices",
            SyncState.DELETING,
            fixture.workSessionDao.findIncludingDeleted(accepted.intervalUuid)!!.syncState
        )
    }
}
