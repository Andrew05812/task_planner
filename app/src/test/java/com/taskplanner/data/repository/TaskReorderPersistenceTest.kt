package com.taskplanner.data.repository

import androidx.test.core.app.ApplicationProvider
import com.taskplanner.data.model.Task
import com.taskplanner.testing.AppFixture
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What the drag-to-reorder gesture is allowed to change, pinned against a real database.
 *
 * **Why this exists before Stage 16.** `TasksViewModel.saveVisibleOrder` persists the order of *the rows on
 * screen* — `TasksViewModel.kt:603`, and its own doc comment says "for the currently visible (filtered) subset".
 * Once a task can hang under another task, that becomes a hazard rather than a quirk: the gesture that reorders
 * a filtered list is the same gesture a future indent/outdent UI would live beside, and a reorder that quietly
 * reparented a task would corrupt an imported Keep tree through a path nobody is testing for. So the invariant
 * is written down here, on the day the column appears.
 *
 * **What it does not claim.** The re-densifying itself is the known defect §10 of the Stage 15 brief names: the
 * visible subset is written as `0..n-1` while hidden rows keep whatever they had, so two rows can end up sharing
 * an order value. That behaviour is *asserted*, not fixed — a test that pretended the collision was fine would
 * be the bug, and fixing ordering is Stage 16's work, not a prerequisite of an import.
 */
@RunWith(RobolectricTestRunner::class)
class TaskReorderPersistenceTest {

    private lateinit var fixture: AppFixture

    @Before
    fun setUp() {
        fixture = AppFixture(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() = fixture.close()

    @Test
    fun reordering_a_filtered_subset_never_moves_a_task_to_another_parent() = runBlocking {
        val user = fixture.signIn()
        val alpha = fixture.addGroup(user.id, "Alpha")
        val beta = fixture.addGroup(user.id, "Beta")
        listOf(
            task(user.id, "a1", alpha.uuid, order = 0),
            task(user.id, "a2", alpha.uuid, order = 1, parentUuid = "parent-a1"),
            task(user.id, "a3", alpha.uuid, order = 2),
            task(user.id, "b1", beta.uuid, order = 3),
            task(user.id, "b2", beta.uuid, order = 4, parentUuid = "parent-b1"),
            task(user.id, "b3", beta.uuid, order = 5)
        ).forEach { fixture.taskDao.insertTask(it) }
        val before = stored().associateBy { it.title }

        // The reader is looking at Beta only, drags its three rows into reverse, and lets go. The ids come back
        // from the database rather than from the in-memory rows, whose `id` is still 0 until Room assigns it —
        // a reorder fed those zeroes would change nothing and "pass" by doing nothing at all.
        val betaReversed = before.values.filter { it.groupId == beta.uuid }.sortedByDescending { it.order }
        fixture.taskRepository.reorderTasks(betaReversed.map { it.id })

        val after = stored().associateBy { it.title }
        assertEquals("the reorder has to have actually reordered something", 0, after.getValue("b3").order)
        for (title in before.keys) {
            assertEquals("$title changed topic during a reorder", before.getValue(title).groupId, after.getValue(title).groupId)
            assertEquals(
                "$title changed parent during a reorder",
                before.getValue(title).parentUuid,
                after.getValue(title).parentUuid
            )
            assertEquals("$title changed owner during a reorder", before.getValue(title).userId, after.getValue(title).userId)
        }
    }

    @Test
    fun reordering_the_visible_subset_leaves_hidden_rows_alone_and_can_collide_with_them() = runBlocking {
        val user = fixture.signIn()
        val alpha = fixture.addGroup(user.id, "Alpha")
        val beta = fixture.addGroup(user.id, "Beta")
        fixture.taskDao.insertTask(task(user.id, "a1", alpha.uuid, order = 0))
        fixture.taskDao.insertTask(task(user.id, "a2", alpha.uuid, order = 1))
        fixture.taskDao.insertTask(task(user.id, "b1", beta.uuid, order = 2))
        fixture.taskDao.insertTask(task(user.id, "b2", beta.uuid, order = 3))
        val rows = stored().associateBy { it.title }

        // The reader sees Beta only and drags it into reverse: b2 first, then b1.
        fixture.taskRepository.reorderTasks(listOf(rows.getValue("b2").id, rows.getValue("b1").id))

        val after = stored().associateBy { it.title }
        assertEquals("a row the reader could not see kept its place", 1, after.getValue("a2").order)
        assertEquals(0, after.getValue("b2").order)
        assertEquals(1, after.getValue("b1").order)
        // And here is the defect the roadmap names: the visible subset was re-denseled from 0, so `b2` now
        // claims the same position as `a1` in a different topic. The list query breaks that tie by due date,
        // so two unrelated rows can trade places on the next render. Recorded, not fixed — ordering is
        // Stage 16's own work, and this stage must not silently change it.
        assertEquals(
            "the collision a filtered reorder produces",
            after.getValue("a1").order,
            after.getValue("b2").order
        )
        assertNotEquals(after.getValue("a1").groupId, after.getValue("b2").groupId)
    }

    private suspend fun stored() =
        fixture.taskDao.getAllTasksOnce(fixture.sessionRepository.currentUserId() ?: error("no profile"))

    private fun task(userId: Long, title: String, groupId: String, order: Int, parentUuid: String? = null) = Task(
        title = title,
        description = "",
        userId = userId,
        groupId = groupId,
        parentUuid = parentUuid,
        order = order
    )
}
