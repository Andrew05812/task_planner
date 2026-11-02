package com.taskplanner.ui.tasks

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.taskplanner.data.hierarchy.HierarchyCollapseRefusal
import com.taskplanner.data.hierarchy.HierarchyCollapseResult
import com.taskplanner.data.model.SyncState
import com.taskplanner.data.model.Task
import com.taskplanner.data.repository.TaskHierarchyRepository
import com.taskplanner.testing.AppFixture
import java.util.concurrent.Executor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The fold reaching the list: Stage 16 §7's collapse, seen as the rows a reader is handed rather than as a column.
 *
 * `HierarchyCollapseTest` proves the state is stored honestly. This file proves the *list* behaves like a folded
 * notebook: the branch is absent while the row that owns it stays exactly where the reader left it, the fold holds
 * two levels down, a filter that drops the row in between cannot uncover it, and opening it again restores the rows
 * in the order they had — because nothing was re-numbered or re-hung on the way in.
 *
 * **One rule the renderer has to choose, stated rather than left to chance.** A fold hides a branch from the tree
 * view, and a search is not the tree view: it is a question about it. So a query in the search box ignores folds,
 * and a reader who closed "Server migration" can still find the subtask they typed the name of — the alternative is
 * a list that says "no tasks found" about a row the reader can see in the editor. A *browsing* filter — category,
 * status — does not uncover a folded branch, because it selects rows from the same notebook rather than asking
 * where something is. Both cases are asserted below.
 */
@RunWith(RobolectricTestRunner::class)
class TasksHierarchyRenderTest {

    private lateinit var fixture: AppFixture
    private lateinit var hierarchy: TaskHierarchyRepository
    private lateinit var viewModel: TasksViewModel
    private var userId: Long = 0

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        fixture = AppFixture(context)
        Dispatchers.setMain(Executor { it.run() }.asCoroutineDispatcher())
        userId = runBlocking { fixture.signIn(email = "render@example.com").id }
        hierarchy = TaskHierarchyRepository(
            fixture.database, fixture.taskDao, fixture.groupDao, fixture.trackingSessionDao, fixture.reminderDao,
            fixture.sessionRepository, fixture.timeBase
        )
        viewModel = TasksViewModel(
            fixture.taskRepository,
            fixture.categoryRepository,
            fixture.groupRepository,
            fixture.recurrenceRepository,
            fixture.reminderRepository,
            fixture.sessionRepository,
            fixture.workTrackingRepository,
            fixture.workStatisticsRepository,
            hierarchy
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        fixture.close()
    }

    private fun row(
        title: String,
        parent: String? = null,
        position: Int,
        group: String? = null,
        categoryId: Long? = null,
        collapsed: Boolean = false
    ): Task = runBlocking {
        val id = fixture.taskDao.insertTask(
            Task(
                title = title, description = "", uuid = title, userId = userId, groupId = group,
                parentUuid = parent, categoryId = categoryId, order = position, childrenCollapsed = collapsed,
                syncState = SyncState.SYNCED, createdAt = FIXTURE_MILLIS, updatedAt = FIXTURE_MILLIS
            )
        )
        fixture.taskDao.getTaskById(id)!!
    }

    private fun settle(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            runBlocking { fixture.awaitIdle() }
            shadowOf(Looper.getMainLooper()).idle()
            if (System.currentTimeMillis() > deadline) throw AssertionError("timed out waiting for $what")
            Thread.sleep(10)
        }
        runBlocking { fixture.awaitIdle() }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun titles() = viewModel.state.value.items.filterIsInstance<TasksListItem.TaskRow>().map { it.task.title }

    private fun rowOf(title: String): TasksListItem.TaskRow =
        viewModel.state.value.items.filterIsInstance<TasksListItem.TaskRow>().first { it.task.title == title }

    private fun awaitTitles(what: String, expected: List<String>) {
        settle(what) { titles() == expected }
        assertEquals(what, expected, titles())
    }

    private fun storedTitles(): List<String> = runBlocking {
        fixture.taskDao.getRowsInStoredOrder(userId).map { it.title }
    }

    private fun fold(task: Task): HierarchyCollapseResult = runBlocking { hierarchy.toggleBranchCollapse(task.id) }

    @Test
    fun a_folded_branch_leaves_the_list_and_its_own_row_stays_where_the_reader_left_it() {
        val topic = runBlocking { fixture.addGroup(userId, "grp-render") }
        row("before", position = 0, group = topic.uuid)
        val migration = row("migration", position = 1, group = topic.uuid)
        row("schema", parent = "migration", position = 2, group = topic.uuid)
        row("after", position = 3, group = topic.uuid)
        awaitTitles("the tree to be drawn open", listOf("before", "migration", "schema", "after"))

        fold(migration)

        awaitTitles("the branch to go", listOf("before", "migration", "after"))
    }

    @Test
    fun a_fold_holds_two_levels_down_not_just_over_the_generation_under_it() {
        val head = row("head", position = 0)
        row("child", parent = "head", position = 1)
        row("grandchild", parent = "child", position = 2)
        awaitTitles("the branch to be drawn", listOf("head", "child", "grandchild"))

        fold(head)

        awaitTitles("both generations to go with it", listOf("head"))
    }

    @Test
    fun opening_the_branch_again_returns_the_rows_in_the_order_the_reader_had_them() {
        val head = row("head", position = 0)
        row("first", parent = "head", position = 1)
        row("second", parent = "head", position = 2)
        row("deep", parent = "first", position = 3)
        fold(head)
        awaitTitles("the fold to reach the list", listOf("head"))

        fold(head)

        awaitTitles(
            "the branch back in the positions the reader had them",
            listOf("head", "first", "second", "deep")
        )
        assertEquals("and the stored tree never changed", listOf("head", "first", "second", "deep"), storedTitles())
    }

    @Test
    fun a_browsing_filter_that_drops_the_row_in_between_cannot_uncover_a_folded_branch() {
        val work = runBlocking { fixture.addCategory(userId, "cat-work") }
        val head = row("head", position = 0, categoryId = work)
        row("child", parent = "head", position = 1, categoryId = null)
        row("grandchild", parent = "child", position = 2, categoryId = work)
        fold(head)

        runBlocking { viewModel.setCategoryFilter(work) }

        awaitTitles(
            "the category filter to leave the fold holding — the hidden set is measured over the profile, so a " +
                "filter that dropped the middle row uncovers nothing",
            listOf("head")
        )
    }

    @Test
    fun a_search_ignores_the_fold_because_a_query_is_a_question_not_a_way_of_browsing() {
        val head = row("head", position = 0)
        row("child", parent = "head", position = 1)
        fold(head)
        awaitTitles("the fold to hold in the tree view", listOf("head"))

        runBlocking { viewModel.setSearchQuery("child") }

        assertEquals(
            "the reader typed a name they know exists; answering \"nothing found\" because a branch happens to be " +
                "closed would be the list lying about their notebook",
            listOf("child"),
            titles()
        )
    }

    @Test
    fun the_row_that_holds_a_fold_carries_the_number_of_rows_it_hides() {
        val head = row("head", position = 0)
        row("child", parent = "head", position = 1)
        row("grandchild", parent = "child", position = 2)
        row("sibling", parent = "head", position = 3)

        fold(head)

        assertEquals("three rows are away behind it", 3, rowOf("head").hiddenDescendants)
    }

    @Test
    fun a_leaf_reports_no_hidden_rows_because_there_is_nothing_for_a_chevron_to_mean() {
        row("lone", position = 0)
        awaitTitles("the list", listOf("lone"))

        assertEquals(0, rowOf("lone").hiddenDescendants)
    }

    @Test
    fun the_screen_command_folds_the_branch_and_the_list_re_renders_from_what_storage_says() {
        val head = row("head", position = 0)
        val child = row("child", parent = "head", position = 1)

        viewModel.toggleBranchCollapse(head.id)
        awaitTitles("the command to reach the list", listOf("head"))

        assertEquals(
            "the column is what the list drew from, not a flag the screen remembered",
            true,
            runBlocking { fixture.taskDao.getTaskById(head.id) }!!.childrenCollapsed
        )
        assertEquals("and the row under it is untouched", "head", child.parentUuid)
    }

    @Test
    fun a_fold_writes_nothing_so_the_rows_that_came_back_arrive_as_they_were_stored() {
        val head = row("head", position = 0)
        row("child", parent = "head", position = 1)
        val before = runBlocking { fixture.taskDao.getAllRowsOnce(userId).map { listOf(it.title, it.parentUuid, it.groupId, it.order) } }

        fold(head)
        fold(head)

        val after = runBlocking { fixture.taskDao.getAllRowsOnce(userId).map { listOf(it.title, it.parentUuid, it.groupId, it.order) } }
        assertEquals("closing and opening a branch edits the tree no more than scrolling does", before, after)
        assertTrue("and both rows are still the profile's", storedTitles().containsAll(listOf("head", "child")))
    }

    @Test
    fun a_folded_topic_still_hides_everything_it_held_and_the_branch_state_is_untouched_by_it() {
        val topic = runBlocking { fixture.addGroup(userId, "grp-folded") }
        val head = row("head", position = 0, group = topic.uuid)
        row("child", parent = "head", position = 1, group = topic.uuid)
        fold(head)
        runBlocking { fixture.groupDao.setCollapsed(topic.uuid, true) }
        awaitTitles("the topic fold to reach the list", emptyList())

        runBlocking { fixture.groupDao.setCollapsed(topic.uuid, false) }

        awaitTitles(
            "the topic to open with its own branch still closed",
            listOf("head")
        )
    }

    private companion object {
        const val FIXTURE_MILLIS = 1_700_000_000_000L
    }
}
