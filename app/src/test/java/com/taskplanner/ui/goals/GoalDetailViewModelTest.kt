package com.taskplanner.ui.goals

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.taskplanner.data.calendar.CalendarMath
import com.taskplanner.data.model.Goal
import com.taskplanner.data.model.GoalType
import com.taskplanner.data.model.GoalValueChange
import com.taskplanner.data.model.Task
import com.taskplanner.data.model.TaskStatus
import com.taskplanner.data.model.User
import com.taskplanner.data.repository.GoalRejection
import com.taskplanner.data.repository.GoalWriteResult
import com.taskplanner.testing.AppFixture
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.Executor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The goal detail screen: what one row's id is allowed to show, and what each of its writes leaves behind.
 *
 * The screen owns no figures, so the reading is what it can get wrong. Three things are checked here that no
 * repository test covers: that the row and its figure come back from *one* read (a screen that asked twice
 * could print "3 of 5" against a denominator of 4), that the goal's set and its target history are keyed on
 * the uuid rather than re-fetched per figure, and that the id this device handed out is not a way into
 * another profile's aim.
 *
 * The writes are checked on the row they touch rather than on the number printed: naming a task, un-naming
 * one and putting a goal away are all references and states, and none of them is allowed to reach into the
 * task's own row. As on the list, the clock is parked at midday of one Wednesday so "is it late yet" cannot
 * change under a test.
 */
@RunWith(RobolectricTestRunner::class)
class GoalDetailViewModelTest {

    private lateinit var fixture: AppFixture
    private lateinit var viewModel: GoalDetailViewModel
    private var userId: Long = -1L

    private val zone: ZoneId = CalendarMath.systemZone()
    private val monday: LocalDate = LocalDate.of(2026, 6, 1)
    private val today: LocalDate = LocalDate.of(2026, 6, 3)

    private fun at(day: LocalDate, hour: Int = 10, minute: Int = 0): Long =
        day.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        fixture = AppFixture(context)
        Dispatchers.setMain(Executor { it.run() }.asCoroutineDispatcher())
        userId = runBlocking { fixture.signIn().id }
        runBlocking { fixture.timeBase.shiftTo(at(today, 12, 0)) }
        viewModel = GoalDetailViewModel(fixture.goalRepository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        fixture.close()
    }

    private fun state() = viewModel.state.value

    private suspend fun task(
        title: String,
        status: TaskStatus = TaskStatus.TODO,
        completedAt: Long? = null
    ): Task {
        val id = fixture.taskDao.insertTask(
            Task(
                uuid = "task-$title", title = title, description = "", userId = userId,
                status = status, completedAt = completedAt,
                createdAt = at(monday, 8, 0), updatedAt = at(today, 8, 0)
            )
        )
        return fixture.taskDao.getTaskById(id)!!
    }

    /** Creates a goal through the repository and hands back the row, so a test can open on its id. */
    private suspend fun goal(
        title: String = "aim",
        type: GoalType = GoalType.TASK_SET,
        target: Long = 0L,
        taskUuids: List<String> = emptyList()
    ): Goal {
        val saved = fixture.goalRepository.create(
            title = title, type = type, targetValue = target, taskUuids = taskUuids
        ) as GoalWriteResult.Saved
        return fixture.goalDao.getGoal(saved.goalUuid, userId)!!
    }

    private fun saved(result: GoalWriteResult): String = when (result) {
        is GoalWriteResult.Saved -> result.goalUuid
        is GoalWriteResult.Rejected -> throw AssertionError("expected a saved goal, got ${result.reason}")
    }

    private fun rejected(result: GoalWriteResult): GoalRejection = when (result) {
        is GoalWriteResult.Saved -> throw AssertionError("expected a refusal, got a Saved goal")
        is GoalWriteResult.Rejected -> result.reason
    }

    /** Opens the screen on [row] and waits for its first frame. */
    private fun open(row: Goal) {
        viewModel.load(row.id)
        awaitUntil("the goal to arrive") { !state().isLoading }
    }

    private fun awaitUntil(what: String, condition: () -> Boolean) {
        fixture.awaitIdle()
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError("timed out waiting for $what")
            }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    /**
     * Issues one write and waits for the answer it hands back.
     *
     * The screen only *issues* the write — the repository finishes it on the database's own threads — so a
     * test that read the callback on the line below the call would be asserting on a variable nobody has
     * assigned yet. What is worth waiting for is the same answer the fragment shows its words from.
     */
    private fun write(what: String, action: ((GoalWriteResult) -> Unit) -> Unit): GoalWriteResult {
        var answer: GoalWriteResult? = null
        action { result -> answer = result }
        awaitUntil(what) { answer != null }
        return requireNotNull(answer)
    }

    @Test
    fun `opening on a row's id reads the row and its figure as one answer`() = runBlocking {
        val report = task("Write the report", TaskStatus.COMPLETED, completedAt = at(today, 9, 0))
        val mail = task("Answer the mail")
        val row = goal(title = "Clear the desk", taskUuids = listOf(report.uuid, mail.uuid))

        open(row)
        assertEquals("Clear the desk", state().goal?.title)
        // The denominator here is the size of the named set, not the 0 the user left in the figure field:
        // one read answered both halves, so the card cannot pair a count with someone else's target.
        assertEquals(1L, state().progress?.counted)
        assertEquals(2L, state().progress?.target)
        assertEquals(2, state().tasks.size)
    }

    @Test
    fun `an id no row holds is reported missing rather than shown as an empty goal`() = runBlocking {
        viewModel.load(4242L)
        awaitUntil("the read to answer") { !state().isLoading }
        assertTrue(state().isMissing)
        assertNull(state().goal)
        assertNull(state().progress)
    }

    @Test
    fun `another profile's goal is not readable through the id this device gave it`() = runBlocking {
        val otherId = fixture.userDao.insertUser(User(email = "other@example.com", name = "Another"))
        fixture.goalDao.insertGoal(
            Goal(
                uuid = "goal-of-other", userId = otherId, title = "Their aim",
                type = GoalType.TASK_COUNT, targetValue = 9,
                createdAt = at(today, 9, 0), updatedAt = at(today, 9, 0)
            )
        )
        val theirs = fixture.goalDao.getGoal("goal-of-other", otherId)!!

        open(theirs)
        assertTrue("a row the account does not hold has no figures to show", state().isMissing)
        assertNull(state().goal)
    }

    @Test
    fun `naming a task from the screen widens the set and the figure with it`() = runBlocking {
        val row = goal(title = "Clear the desk")
        val mail = task("Answer the mail")
        open(row)
        assertEquals(0L, state().progress?.target)

        saved(write("the new link to be written") { cb -> viewModel.link(mail.uuid, cb) })
        awaitUntil("the new link to land in the set") { state().tasks.size == 1 }
        assertEquals(1L, state().progress?.target)
        assertEquals(mail.uuid, state().tasks.single().taskUuid)
    }

    @Test
    fun `taking a task out of the goal leaves the task's own row untouched`() = runBlocking {
        val report = task("Write the report", TaskStatus.COMPLETED, completedAt = at(today, 9, 0))
        val row = goal(title = "Clear the desk", taskUuids = listOf(report.uuid))
        open(row)

        saved(write("the link to be written back") { cb -> viewModel.unlink(report.uuid, cb) })
        awaitUntil("the link to leave the set") { state().tasks.isEmpty() }

        // The task is the app's, not the goal's: un-naming it must not finish, archive or delete anything.
        assertNotNull(fixture.taskDao.getTaskById(report.id))
        assertEquals(TaskStatus.COMPLETED, fixture.taskDao.getTaskById(report.id)?.status)
        assertEquals(0L, state().progress?.target)
    }

    @Test
    fun `a task thrown away stops being counted while the goal still asks for it`() = runBlocking {
        val report = task("Write the report", TaskStatus.COMPLETED, completedAt = at(today, 9, 0))
        val row = goal(title = "Clear the desk", taskUuids = listOf(report.uuid))
        open(row)
        assertEquals(1L, state().progress?.counted)

        // The app's own delete leaves a tombstone, so the goal keeps the name it was given and only the work
        // under it moves: §5 asks the figure to follow a deletion, not the denominator to shrink around it.
        fixture.taskRepository.softDeleteTasks(listOf(report.id))
        awaitUntil("the figure to fall back with the deletion") { state().progress?.counted == 0L }
        assertEquals(1L, state().progress?.target)
        assertFalse(state().progress!!.reached)
        assertEquals(report.title, state().tasks.single().title)
    }

    @Test
    fun `a task deleted for good keeps the goal's denominator and is said to be gone`() = runBlocking {
        val report = task("Write the report", TaskStatus.COMPLETED, completedAt = at(today, 9, 0))
        val mail = task("Answer the mail")
        val row = goal(title = "Clear the desk", taskUuids = listOf(report.uuid, mail.uuid))
        open(row)
        assertEquals(2L, state().progress?.target)

        // The archive's "delete for good" takes the row out from under the link. Dropping the link with it
        // would leave the card reading "1 of 1" — progress the user never made — so the figure keeps counting
        // the aim and the screen names the row whose work has nowhere left to open.
        fixture.taskRepository.hardDeleteTasks(listOf(report.id))
        awaitUntil("the gone row to be reported as missing") { state().tasks.any { it.title == null } }
        assertEquals(1, state().progress?.missing)
        assertEquals(2, state().progress?.linked)
        assertEquals(2L, state().progress?.target)
        assertEquals(0L, state().progress?.counted)
        assertNull(state().tasks.first { it.taskUuid == report.uuid }.title)
        assertEquals(mail.title, state().tasks.first { it.taskUuid == mail.uuid }.title)
    }

    @Test
    fun `the picker offers only this profile's open tasks the goal does not already name`() = runBlocking {
        val named = task("Write the report")
        task("Send the invoice", TaskStatus.COMPLETED, completedAt = at(today, 9, 0))
        val spare = task("Sort the photos")
        val otherId = fixture.userDao.insertUser(User(email = "other@example.com", name = "Another"))
        fixture.taskDao.insertTask(
            Task(
                uuid = "task-theirs", title = "Their task", description = "", userId = otherId,
                status = TaskStatus.TODO, createdAt = at(monday, 8, 0), updatedAt = at(today, 8, 0)
            )
        )
        val row = goal(title = "Clear the desk", taskUuids = listOf(named.uuid))
        open(row)

        var picked: List<String>? = null
        viewModel.linkableTasks { candidates -> picked = candidates.map { it.title } }
        awaitUntil("the picker to answer") { picked != null }
        // The finished task is already answered, the named one is already in the set, and the other profile's
        // work is not this account's to point a goal at.
        assertEquals(listOf(spare.title), picked)
    }

    @Test
    fun `the record of the target arrives with the row and says what moved`() = runBlocking {
        val row = goal(title = "Clear the desk", type = GoalType.TASK_COUNT, target = 5)
        open(row)
        assertTrue(state().changes.isEmpty())

        fixture.goalRepository.edit(
            goalUuid = row.uuid, title = "Clear the desk", type = GoalType.TASK_COUNT,
            targetValue = 2, windowFromDay = null, windowToDay = null, deadlineAt = null,
            reason = "the week is shorter than I thought"
        )
        awaitUntil("the history to arrive") { state().changes.isNotEmpty() }
        val change = state().changes.single()
        assertEquals(GoalValueChange.FIELD_TARGET, change.field)
        assertEquals("5", change.oldValue)
        assertEquals("2", change.newValue)
        assertEquals("the week is shorter than I thought", change.reason)
    }

    @Test
    fun `putting the goal away and bringing it back are read off the stored row`() = runBlocking {
        val row = goal(title = "Clear the desk", type = GoalType.TASK_COUNT, target = 1)
        open(row)

        saved(write("the goal to be put away") { cb -> viewModel.setArchived(cb) })
        // The screen decides which of the two it is from the row it was given, so the row arriving back from
        // the Flow is what makes the next tap bring the goal out rather than put it away a second time.
        awaitUntil("the screen to read the goal as put away") { state().goal?.archivedAt != null }
        assertNotNull(state().goal?.archivedAt)

        saved(write("the goal to be brought back") { cb -> viewModel.setArchived(cb) })
        awaitUntil("the screen to read the goal as back") { state().goal?.archivedAt == null }
        assertNull(fixtureRunBlocking { fixture.goalDao.getGoal(row.uuid, userId) }?.archivedAt)
    }

    @Test
    fun `a goal that is gone cannot be put away or thrown away again`() = runBlocking {
        val row = goal(title = "Clear the desk", type = GoalType.TASK_COUNT, target = 1)
        open(row)
        saved(write("the goal to be thrown away") { cb -> viewModel.remove(cb) })
        awaitUntil("the gone row to leave the screen") { state().isMissing }

        // Both writes read the goal the screen holds, and the screen holds nothing once the row is gone, so
        // the goal cannot be retired or removed a second time behind the reader's back.
        val archive = write("the refusal to archive") { cb -> viewModel.setArchived(cb) }
        assertEquals(GoalRejection.NOT_FOUND, rejected(archive))
        val removal = write("the refusal to remove again") { cb -> viewModel.remove(cb) }
        assertEquals(GoalRejection.NOT_FOUND, rejected(removal))
    }

    /** A read inside a wait loop: the fixture's database answers on its own threads, never on the main one. */
    private fun <T> fixtureRunBlocking(body: suspend () -> T): T = runBlocking { body() }
}
