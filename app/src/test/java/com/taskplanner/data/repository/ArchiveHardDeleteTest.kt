package com.taskplanner.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.taskplanner.data.hierarchy.TaskDeleteCoordinator
import com.taskplanner.data.model.SyncState
import com.taskplanner.data.model.Task
import com.taskplanner.testing.AppFixture
import java.util.concurrent.Executor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The archive screen's "delete forever", read as the tree sees it.
 *
 * A tombstone keeps the row, so Stage 16 §4b and the category route can each be stated as "no live row names a
 * retired parent". The archive's final delete is the other kind of leaving: [TaskRepository.hardDeleteTasks] removes
 * the row from the table, and `tasks.parent_uuid` is a plain column with no foreign key behind it — the entity's only
 * [androidx.room.ForeignKey] is `category_id`, and it points at a category. So the database does not object when the
 * row a child names stops existing. Nothing else in the app notices either: `TaskTree.depthsByTaskId` treats a parent
 * it cannot find as "no parent" and draws the child at the margin, and `TaskHierarchyPolicy.subtreeOf` can never walk
 * up to it again. The child is silently re-parented to nothing, which is the same defect §4b closed for a promote,
 * arriving by a different door — and the door is open because archiving is not a tree operation:
 * `completeAndArchive` archives the one row it is given, so an archived parent with a live child is a normal state of
 * a notebook, not a corrupted one.
 *
 * **What the cases insist on.** That the profile cannot be left holding a live row whose parent is absent; that the
 * rows under a deleted one go to the nearest ancestor that is still there — the same promise a promote keeps when its
 * head is only tombstoned; and that where no such ancestor exists they become roots rather than hanging off a row
 * that is dust. The reminder retirement the verb already performed is pinned too, because a delete that stops taking
 * its reminders with it would be a worse fix than the bug.
 */
@RunWith(RobolectricTestRunner::class)
class ArchiveHardDeleteTest {

    private lateinit var fixture: AppFixture
    private lateinit var coordinator: TaskDeleteCoordinator
    private var userId: Long = 0

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        fixture = AppFixture(context)
        Dispatchers.setMain(Executor { it.run() }.asCoroutineDispatcher())
        userId = runBlocking { fixture.signIn(email = "archive-hard-delete@example.com").id }
        coordinator = TaskDeleteCoordinator(fixture.taskHierarchyRepository, fixture.taskRepository)
    }

    @After
    fun tearDown() {
        fixture.close()
        Dispatchers.resetMain()
    }

    private fun row(
        title: String,
        parent: String? = null,
        position: Int,
        archived: Boolean = false,
        due: Long? = null
    ): Task = runBlocking {
        val id = fixture.taskDao.insertTask(
            Task(
                title = title, description = "", uuid = title, userId = userId, parentUuid = parent,
                order = position, archived = archived, dueDate = due, syncState = SyncState.SYNCED
            )
        )
        fixture.taskDao.getTaskById(id)!!
    }

    private fun live(): List<Task> = runBlocking { fixture.taskDao.getRowsInStoredOrder(userId) }

    private fun everyUuidInTable(): Set<String> = runBlocking { fixture.taskDao.getAllRowsOnce(userId) }.map { it.uuid }.toSet()

    /** The live rows naming a parent that is not in the table at all — the orphan this verb used to leave. */
    private fun dangling(): List<String> = live()
        .filter { it.parentUuid != null && it.parentUuid!!.isNotBlank() && !everyUuidInTable().contains(it.parentUuid) }
        .map { it.title }

    @Test
    fun a_row_deleted_forever_does_not_leave_a_live_row_naming_nothing() {
        val gone = row("archived parent", position = 0, archived = true)
        row("live child", parent = "archived parent", position = 1)

        runBlocking { coordinator.hardDelete(listOf(gone.id)) }

        assertEquals(
            "no live row may name a row the profile no longer holds",
            emptyList<String>(),
            dangling()
        )
        // The orphan property alone is not the promise: a verb that took the child as well would leave nothing
        // dangling and still be wrong. The rows under a deleted one are kept, and re-hung.
        assertEquals(
            "and the child is still on the list, which the dangling check cannot see on its own",
            listOf("live child"),
            live().map { it.title }
        )
        assertEquals("re-hung on what its parent hung on, which was nothing", null, live().single().parentUuid)
    }

    @Test
    fun the_rows_under_a_deleted_one_move_to_the_nearest_ancestor_still_in_the_profile() {
        val root = row("project", position = 0)
        val archived = row("archived phase", parent = "project", position = 1, archived = true)
        row("work", parent = "archived phase", position = 2)

        runBlocking { coordinator.hardDelete(listOf(archived.id)) }

        assertEquals("the work hangs from the project now", "project", live().first { it.title == "work" }.parentUuid)
        assertEquals("and nothing dangles", emptyList<String>(), dangling())
        assertEquals("the archive's row is gone from the table", 2, live().size)
        assertEquals("the tree reads as one branch, not an invented level", listOf("project", "work"), live().map { it.title })
    }

    @Test
    fun a_row_whose_ancestor_is_gone_too_becomes_a_root_rather_than_hanging_off_dust() {
        val archived = row("archived parent", position = 0, archived = true)
        val deeper = row("middle", parent = "archived parent", position = 1, archived = true)
        row("leaf", parent = "middle", position = 2)

        runBlocking { coordinator.hardDelete(listOf(archived.id, deeper.id)) }

        assertEquals("the leaf names nothing above it", null, live().first { it.title == "leaf" }.parentUuid)
        assertEquals("and no row is left pointing at a row that is not there", emptyList<String>(), dangling())
        assertEquals("two rows gone, one left", listOf("leaf"), live().map { it.title })
    }

    @Test
    fun a_row_deleted_forever_still_takes_its_reminders_with_it() {
        val doomed = row("noisy", position = 0, archived = true, due = fixture.timeBase.wall + 86_400_000L)
        runBlocking { fixture.reminderRepository.applyReminders(doomed, listOf(30)) }
        assertEquals("one reminder row armed", 1, runBlocking { fixture.reminderDao.getLiveForTask(doomed.uuid) }.size)

        runBlocking { coordinator.hardDelete(listOf(doomed.id)) }

        assertEquals("the row is not in the table any more", emptyList<String>(), live().map { it.title })
        assertEquals(
            "and nothing is left that could still ring for it",
            emptyList<String>(),
            runBlocking { fixture.reminderDao.getLiveForTask(doomed.uuid) }.map { it.taskUuid }
        )
        assertTrue(
            "the profile holds no row at all, which is what 'forever' means",
            runBlocking { fixture.taskDao.getAllRowsOnce(userId) }.isEmpty()
        )
    }
}
