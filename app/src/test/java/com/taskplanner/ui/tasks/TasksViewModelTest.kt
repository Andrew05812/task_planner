package com.taskplanner.ui.tasks

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.taskplanner.data.model.Priority
import com.taskplanner.data.model.TaskStatus
import com.taskplanner.testing.AppFixture
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
 * The list screen's behaviour, tested against a real Room database instead of mocks: grouping,
 * filters, sorting, multi-select and the lifecycle rules are exactly what the user sees, so they
 * are asserted on the rendered item list.
 */
@RunWith(RobolectricTestRunner::class)
class TasksViewModelTest {

    private lateinit var fixture: AppFixture
    private lateinit var viewModel: TasksViewModel
    private var userId: Long = 0

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        fixture = AppFixture(context)
        // viewModelScope needs Dispatchers.Main. In a JVM test it is pinned to an inline executor,
        // so every collector runs to completion on the test thread instead of waiting for a looper.
        Dispatchers.setMain(Executor { it.run() }.asCoroutineDispatcher())
        userId = runBlocking { fixture.signIn(email = "ui@example.com").id }
        viewModel = newModel()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        fixture.close()
    }

    private fun newModel() = TasksViewModel(
        fixture.taskRepository,
        fixture.categoryRepository,
        fixture.groupRepository,
        fixture.recurrenceRepository,
        fixture.reminderRepository,
        fixture.sessionRepository,
        fixture.workTrackingRepository,
        fixture.workStatisticsRepository
    )

    private fun addTask(
        title: String,
        description: String = "",
        categoryId: Long? = null,
        groupId: String? = null,
        priority: Priority? = null
    ): Long = runBlocking {
        val id = fixture.addTask(userId, title, description, categoryId, groupId)
        if (priority != null) {
            val stored = fixture.taskDao.getTaskById(id)!!
            fixture.taskDao.updateTask(stored.copy(priority = priority))
        }
        id
    }

    private fun titlesOf(model: TasksViewModel) =
        model.state.value.items.filterIsInstance<TasksListItem.TaskRow>().map { it.task.title }

    /** Room notifies flow collectors from a background executor, so the test has to wait for it. */
    private fun awaitUntil(what: String, condition: () -> Boolean) {
        // Room answers on its own threads, so the wait starts by letting them settle: without that the
        // first check can be satisfied by the state a previous command published.
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

    private fun awaitTitles(model: TasksViewModel, expected: List<String>) =
        awaitUntil("titles $expected") { titlesOf(model) == expected }

    @Test
    fun `grouped tasks sit under their topic header and ungrouped ones after it`() {
        val group = runBlocking { fixture.addGroup(userId, "ПРОЕКТ") }
        addTask("Inside topic", groupId = group.uuid)
        addTask("Standalone")

        awaitTitles(viewModel, listOf("Inside topic", "Standalone"))

        val headers = viewModel.state.value.items.filterIsInstance<TasksListItem.GroupHeader>()
        assertEquals(1, headers.size)
        assertEquals("ПРОЕКТ", headers.single().group.name)
        assertEquals(1, headers.single().taskCount)
        val items = viewModel.state.value.items
        assertTrue(
            "the topic header must come before its own task",
            items.indexOfFirst { it is TasksListItem.GroupHeader } <
                items.indexOfFirst { it is TasksListItem.TaskRow && it.task.title == "Inside topic" }
        )
    }

    @Test
    fun `a collapsed topic keeps its header but hides its tasks`() {
        val group = runBlocking { fixture.addGroup(userId, "HIDDEN") }
        addTask("Inside topic", groupId = group.uuid)
        awaitTitles(viewModel, listOf("Inside topic"))

        viewModel.toggleGroupCollapsed(group)

        awaitUntil("the topic to collapse its rows") { titlesOf(viewModel).isEmpty() }
        val headers = viewModel.state.value.items.filterIsInstance<TasksListItem.GroupHeader>()
        assertEquals(1, headers.size)
        assertEquals("a collapsed header still reports how many tasks it holds", 1, headers.single().taskCount)
    }

    @Test
    fun `category filter keeps one category, the no-category filter the rest`() {
        val work = runBlocking { fixture.addCategory(userId, "Работа") }
        addTask("Report", categoryId = work)
        addTask("Unassigned")

        awaitTitles(viewModel, listOf("Report", "Unassigned"))

        viewModel.setCategoryFilter(work)
        awaitTitles(viewModel, listOf("Report"))

        viewModel.setNoCategoryFilter()
        awaitTitles(viewModel, listOf("Unassigned"))

        viewModel.clearFilters()
        awaitTitles(viewModel, listOf("Report", "Unassigned"))
    }

    @Test
    fun `search matches title and description and ignores case`() {
        addTask("Prepare report")
        addTask("Buy milk", description = "including the REPORT template")

        awaitTitles(viewModel, listOf("Prepare report", "Buy milk"))

        viewModel.setSearchQuery("report")
        awaitTitles(viewModel, listOf("Prepare report", "Buy milk"))

        viewModel.setSearchQuery("milk")
        awaitTitles(viewModel, listOf("Buy milk"))
    }

    @Test
    fun `status filter shows only tasks in that lifecycle state`() {
        val inProgressId = addTask("Started")
        addTask("Not started")
        runBlocking {
            val task = fixture.taskDao.getTaskById(inProgressId)!!
            fixture.taskDao.updateTask(task.copy(status = TaskStatus.IN_PROGRESS))
        }

        viewModel.setStatusFilter(TaskStatus.IN_PROGRESS)
        awaitTitles(viewModel, listOf("Started"))

        viewModel.setStatusFilter(TaskStatus.TODO)
        awaitTitles(viewModel, listOf("Not started"))
    }

    @Test
    fun `sorting by name and by priority reorders the same rows`() {
        addTask("b task", priority = Priority.LOW)
        addTask("a task", priority = Priority.HIGH)

        viewModel.setSortOrder(SortOrder.NAME_ASC)
        awaitTitles(viewModel, listOf("a task", "b task"))

        viewModel.setSortOrder(SortOrder.PRIORITY_DESC)
        awaitTitles(viewModel, listOf("a task", "b task"))

        viewModel.setSortOrder(SortOrder.PRIORITY_ASC)
        awaitTitles(viewModel, listOf("b task", "a task"))
    }

    @Test
    fun `long press starts selection mode and marks only that row`() {
        val first = addTask("First")
        addTask("Second")
        awaitTitles(viewModel, listOf("First", "Second"))

        viewModel.enterSelection(first)

        assertTrue(viewModel.state.value.selectionMode)
        assertEquals(setOf(first), viewModel.state.value.selectedIds)
        assertTrue(rows(viewModel).first { it.task.id == first }.isSelected)
        assertFalse(rows(viewModel).first { it.task.id != first }.isSelected)

        viewModel.exitSelection()
        awaitUntil("selection mode to end") { !viewModel.state.value.selectionMode }
        assertTrue(viewModel.state.value.selectedIds.isEmpty())
    }

    private fun rows(model: TasksViewModel) =
        model.state.value.items.filterIsInstance<TasksListItem.TaskRow>()

    @Test
    fun `select all visible covers every rendered row and mass delete keeps the rest`() {
        val keep = addTask("Keep me")
        val doomed = addTask("Delete me")
        awaitTitles(viewModel, listOf("Keep me", "Delete me"))

        viewModel.selectAllVisible()
        assertEquals(setOf(keep, doomed), viewModel.state.value.selectedIds)

        // the mass action bar deletes the selection; here only the doomed row is still selected
        viewModel.deleteSelectedFor(listOf(doomed))

        awaitTitles(viewModel, listOf("Keep me"))
        assertEquals(
            "a deleted task stays in the database as a tombstone so sync can push it",
            2,
            runBlocking { fixture.taskDao.getAllRowsOnce(userId).size }
        )
    }

    @Test
    fun `toggling a topic header selects every task of that topic only`() {
        val group = runBlocking { fixture.addGroup(userId, "TOPIC") }
        val inside = addTask("Inside topic", groupId = group.uuid)
        addTask("Standalone")
        awaitTitles(viewModel, listOf("Inside topic", "Standalone"))

        viewModel.toggleGroupSelection(group)
        assertEquals(setOf(inside), viewModel.state.value.selectedIds)

        viewModel.toggleGroupSelection(group)
        awaitUntil("the topic to be deselected") { viewModel.state.value.selectedIds.isEmpty() }
    }

    @Test
    fun `completing a task archives it and it leaves the active list`() {
        val id = addTask("Finish slide")
        awaitTitles(viewModel, listOf("Finish slide"))
        val task = runBlocking { fixture.taskDao.getTaskById(id)!! }

        viewModel.completeTask(task)

        awaitTitles(viewModel, emptyList())
        val stored = runBlocking { fixture.taskDao.getTaskById(id) }
        assertEquals("the completed row moves to the archive instead of vanishing", true, stored?.archived)
        assertEquals(TaskStatus.COMPLETED, stored?.status)
    }

    @Test
    fun `the lifecycle moves a task through in progress and paused`() {
        val id = addTask("Long task")
        val task = runBlocking { fixture.taskDao.getTaskById(id)!! }

        viewModel.startTask(task)
        assertEquals(TaskStatus.IN_PROGRESS, statusOf(id))

        viewModel.pauseTask(task)
        assertEquals(TaskStatus.PAUSED, statusOf(id))

        viewModel.resumeTask(task)
        assertEquals(TaskStatus.IN_PROGRESS, statusOf(id))
    }

    private fun statusOf(id: Long): TaskStatus? =
        runBlocking { fixture.taskDao.getTaskById(id) }?.status

    @Test
    fun `a second profile starts with an empty list`() {
        addTask("Private to the first profile")
        awaitTitles(viewModel, listOf("Private to the first profile"))

        val other = runBlocking {
            fixture.signIn(name = "Second", email = "second@example.com")
            newModel()
        }
        awaitUntil("the new profile to render") { !other.state.value.isLoading }

        assertEquals(emptyList<String>(), titlesOf(other))
        assertEquals(0, other.state.value.activeTaskCount)
    }
}
