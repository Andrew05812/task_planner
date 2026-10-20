package com.taskplanner.ui.goals

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.taskplanner.data.calendar.CalendarMath
import com.taskplanner.data.model.GoalType
import com.taskplanner.data.model.Task
import com.taskplanner.data.model.Goal
import com.taskplanner.data.model.TaskStatus
import com.taskplanner.data.model.User
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The goal list: which read the screen holds, and what it shows while the work underneath a card moves.
 *
 * The list does no arithmetic and stores no total, so what is its own to get wrong is the reading. A screen
 * that filtered the open list for the retired switch would show a retired aim a figure the profile no longer
 * asks for; a screen that folded its own count of completions would be a second author of a number SQLite
 * already answers, and would drift the moment a task is thrown away. So the tests below hold the state open
 * and write through the *task* side of the app, then ask whether the card moved by itself — §5's promise
 * that finishing, cancelling and deleting are the same event to a goal.
 *
 * Every test anchors on one Wednesday and parks the fixture's clock at midday of it, so a run near a real
 * midnight cannot make "is it late yet" mean two different things in one test.
 */
@RunWith(RobolectricTestRunner::class)
class GoalsViewModelTest {

    private lateinit var fixture: AppFixture
    private lateinit var viewModel: GoalsViewModel
    private var userId: Long = -1L

    private val zone: ZoneId = CalendarMath.systemZone()
    private val monday: LocalDate = LocalDate.of(2026, 6, 1)
    private val today: LocalDate = LocalDate.of(2026, 6, 3)
    private val mayDay: LocalDate = LocalDate.of(2026, 5, 20)

    private fun at(day: LocalDate, hour: Int = 10, minute: Int = 0): Long =
        day.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        fixture = AppFixture(context)
        // viewModelScope needs Dispatchers.Main; an inline executor makes every collector run on the test
        // thread rather than wait for a looper nothing pumps.
        Dispatchers.setMain(Executor { it.run() }.asCoroutineDispatcher())
        userId = runBlocking { fixture.signIn().id }
        runBlocking { fixture.timeBase.shiftTo(at(today, 12, 0)) }
        viewModel = GoalsViewModel(fixture.goalRepository)
    }

    @After
    fun tearDown() {
        // The main dispatcher goes before the storage does: the screen's watcher holds Room flows open, and
        // taking the looper away first keeps a late invalidation from being blamed on the next test.
        Dispatchers.resetMain()
        fixture.close()
    }

    private fun state() = viewModel.state.value

    private fun titles() = state().goals.map { it.goal.title }

    private fun figure(title: String): Long =
        state().goals.first { it.goal.title == title }.progress.counted

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

    /** Waits for the list to answer, since Room publishes its first frame on its own thread. */
    private fun awaitLoaded() = awaitUntil("the goal list to arrive") { !state().isLoading }

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

    @Test
    fun `the list arrives with each goal's figure already beside it`() = runBlocking {
        task("report", TaskStatus.COMPLETED, completedAt = at(today, 9, 0))
        task("plants", TaskStatus.COMPLETED, completedAt = at(mayDay, 9, 0))
        fixture.goalRepository.create(
            "Finish the report chapters",
            GoalType.TASK_COUNT,
            targetValue = 5,
            windowFromDay = monday.toEpochDay(),
            windowToDay = today.toEpochDay()
        )

        awaitLoaded()
        assertEquals(listOf("Finish the report chapters"), titles())
        // One of the two finished tasks falls in the window the goal names and one falls a fortnight before
        // it, so a card that counted the profile rather than the window would print 2 here.
        assertEquals(1L, figure("Finish the report chapters"))
        assertEquals(5L, state().goals.single().progress.target)
    }

    @Test
    fun `a task finished elsewhere moves the card without the screen asking again`() = runBlocking {
        val mail = task("Answer the mail")
        val report = task("Write the report")
        fixture.goalRepository.create(
            "Clear the desk",
            GoalType.TASK_SET,
            taskUuids = listOf(mail.uuid, report.uuid)
        )
        awaitLoaded()
        assertEquals(0L, figure("Clear the desk"))

        // Through the task side of the app, not through the goal: the whole promise is that nothing on this
        // screen has to be told a task finished.
        fixture.taskRepository.completeAndArchive(report.id)
        awaitUntil("the finished task to move the figure") { figure("Clear the desk") == 1L }
        assertEquals(2L, state().goals.single().progress.target)
    }

    @Test
    fun `a completion taken back takes its share of the card with it`() = runBlocking {
        val report = task("Write the report")
        fixture.goalRepository.create("Count", GoalType.TASK_SET, taskUuids = listOf(report.uuid))
        awaitLoaded()
        fixture.taskRepository.completeAndArchive(report.id)
        awaitUntil("the goal to read as reached") { state().goals.single().progress.reached }

        val stored = fixture.taskDao.getTaskById(report.id)!!
        fixture.taskDao.updateTask(
            stored.copy(status = TaskStatus.TODO, completedAt = null, updatedAt = at(today, 11, 0))
        )
        awaitUntil("the un-finished task to leave the figure") { figure("Count") == 0L }
        assertFalse("a goal whose only task is open again cannot be reached",
            state().goals.single().progress.reached)
    }

    @Test
    fun `the retired switch reads the retired list rather than filtering the open one`() = runBlocking {
        fixture.goalRepository.create("Live aim", GoalType.TASK_COUNT, targetValue = 3)
        val retired = fixture.goalRepository.create("Retired aim", GoalType.TASK_COUNT, targetValue = 3)
            as GoalWriteResult.Saved
        fixture.goalRepository.archive(retired.goalUuid)
        awaitLoaded()
        assertEquals(listOf("Live aim"), titles())

        viewModel.setShowArchived(true)
        awaitUntil("the retired list") { state().showArchived && !state().isLoading }
        assertEquals(listOf("Retired aim"), titles())

        viewModel.setShowArchived(false)
        awaitUntil("the open list back") { !state().showArchived && !state().isLoading }
        assertEquals(listOf("Live aim"), titles())
    }

    @Test
    fun `an aim thrown away leaves the list and its figure is not mourned`() = runBlocking {
        val saved = fixture.goalRepository.create("Throwaway", GoalType.TASK_COUNT, targetValue = 1)
            as GoalWriteResult.Saved
        awaitLoaded()
        assertEquals(1, state().goals.size)

        fixture.goalRepository.remove(saved.goalUuid)
        awaitUntil("the goal to leave the list") { state().goals.isEmpty() }
        assertFalse(state().isLoading)
        assertTrue("an emptied list is an answer, not a screen still waiting", state().isEmpty)
    }

    @Test
    fun `a profile with no aims is an empty list, not a screen still waiting`() {
        awaitLoaded()
        assertTrue(state().isEmpty)
        assertEquals(emptyList<Any>(), state().goals)
    }

    @Test
    fun `another profile's aims are not in this list`() = runBlocking {
        fixture.goalRepository.create("My aim", GoalType.TASK_COUNT, targetValue = 2)
        // A row written under a different account, put on disk directly: the isolation the list has to show
        // is the one the read enforces, and a screen that asked for every goal would print this one under
        // the wrong profile.
        val otherId = fixture.userDao.insertUser(
            User(email = "other@example.com", name = "Another")
        )
        fixture.goalDao.insertGoal(
            Goal(
                uuid = "goal-of-other", userId = otherId, title = "Their aim",
                type = GoalType.TASK_COUNT, targetValue = 9,
                createdAt = at(today, 9, 0), updatedAt = at(today, 9, 0)
            )
        )

        awaitLoaded()
        assertEquals(listOf("My aim"), titles())
        assertEquals(2L, state().goals.single().progress.target)
    }
}
