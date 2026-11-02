package com.taskplanner.data.repository

import androidx.test.core.app.ApplicationProvider
import com.taskplanner.data.hierarchy.HierarchyMoveRefusal
import com.taskplanner.data.hierarchy.HierarchyMoveResult
import com.taskplanner.data.model.Task
import com.taskplanner.testing.AppFixture
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What the drag-to-reorder gesture is allowed to change, pinned against a real database.
 *
 * A reorder changes positions and nothing else: never the owner, never the topic, never the parent. Stage 15 wrote
 * that invariant down on the day the `parent_uuid` column appeared, because a gesture that quietly reparented a row
 * would corrupt an imported Keep tree through a path nobody was testing for. Stage 16 keeps it and adds the other
 * half: a row now travels with the rows hanging under it, and it may only be set down beside another row of the
 * same parent in the same topic.
 *
 * **The defect this replaced, and why the check is still here.** `TasksViewModel.saveVisibleOrder` used to re-index
 * `0..n` over the rows on screen, so a drag made under a filter handed the visible subset the positions of rows the
 * filter was hiding: two rows of one profile ended up claiming the same place, and the list broke the tie by due
 * date. That collision was asserted here as measured behaviour until Stage 16 fixed the ordering. It is now
 * asserted the other way — the whole profile comes back with one position per row — which is the same check with
 * the honest expectation, and the reason [thePositionsOfTheWholeProfileStayUnique] is not allowed to be dropped.
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
            task(user.id, "a2", alpha.uuid, order = 1, parentUuid = "a1"),
            task(user.id, "a3", alpha.uuid, order = 2, parentUuid = "a2"),
            task(user.id, "b1", beta.uuid, order = 3),
            task(user.id, "b2", beta.uuid, order = 4),
            task(user.id, "b3", beta.uuid, order = 5)
        ).forEach { fixture.taskDao.insertTask(it) }
        val before = stored().associateBy { it.title }

        // The reader is looking at Beta only and drags its three rows into reverse: b3 above b1, then b2 above b1.
        // The ids come back from the database rather than from the in-memory rows, whose `id` is still 0 until Room
        // assigns it — a reorder fed those zeroes would change nothing and "pass" by doing nothing at all.
        val rows = stored().associateBy { it.title }
        assertTrue(
            "the first move should be accepted: b3 and b1 are siblings",
            move(rows.getValue("b3"), rows.getValue("b1"), placeAfter = false) is HierarchyMoveResult.Accepted
        )
        val afterFirst = stored().associateBy { it.title }
        assertTrue(
            "the second move should be accepted: b2 and b1 are siblings",
            move(afterFirst.getValue("b2"), afterFirst.getValue("b1"), placeAfter = false) is HierarchyMoveResult.Accepted
        )

        val after = stored().associateBy { it.title }
        assertEquals("the reorder has to have actually reversed the visible rows", listOf("b3", "b2", "b1"),
            stored().filter { it.groupId == beta.uuid }.map { it.title })
        for (title in before.keys) {
            assertEquals("$title changed topic during a reorder", before.getValue(title).groupId, after.getValue(title).groupId)
            assertEquals(
                "$title changed parent during a reorder",
                before.getValue(title).parentUuid,
                after.getValue(title).parentUuid
            )
            assertEquals("$title changed owner during a reorder", before.getValue(title).userId, after.getValue(title).userId)
        }
        assertEquals("and no row appeared or vanished", before.size, after.size)
    }

    @Test
    fun a_move_onto_a_row_that_is_not_a_sibling_is_refused_and_writes_nothing() = runBlocking {
        val user = fixture.signIn()
        val alpha = fixture.addGroup(user.id, "Alpha")
        val beta = fixture.addGroup(user.id, "Beta")
        fixture.taskDao.insertTask(task(user.id, "a1", alpha.uuid, order = 0))
        fixture.taskDao.insertTask(task(user.id, "b1", beta.uuid, order = 1))
        fixture.taskDao.insertTask(task(user.id, "b2", beta.uuid, order = 2, parentUuid = "b1"))
        val rows = storedByTitle()
        val moved = rows.getValue("a1")

        val outcome = move(moved, rows.getValue("b1"), placeAfter = false)

        assertEquals(
            "the drag crossed into another topic, which a reorder is not allowed to do",
            HierarchyMoveRefusal.DIFFERENT_TOPIC,
            (outcome as HierarchyMoveResult.Refused).reason
        )
        // The parent link is the other half of the boundary: b1 is a root of Beta while b2 hangs under b1, so a
        // drag that stays inside one topic still has nowhere legitimate to land.
        assertEquals(
            "a root row and a row under a parent are not siblings",
            HierarchyMoveRefusal.DIFFERENT_PARENT,
            (move(rows.getValue("b2"), rows.getValue("b1"), placeAfter = false) as HierarchyMoveResult.Refused).reason
        )
        val after = stored().associateBy { it.title }
        assertEquals(
            "and both refusals changed nothing at all",
            listOf(0, 1, 2),
            after.values.sortedBy { it.order }.map { it.order }
        )
        assertEquals(alpha.uuid, after.getValue("a1").groupId)
    }

    @Test
    fun the_positions_of_the_whole_profile_stay_unique_when_only_part_of_it_is_on_screen() = runBlocking {
        val user = fixture.signIn()
        val alpha = fixture.addGroup(user.id, "Alpha")
        val beta = fixture.addGroup(user.id, "Beta")
        fixture.taskDao.insertTask(task(user.id, "a1", alpha.uuid, order = 0))
        fixture.taskDao.insertTask(task(user.id, "a2", alpha.uuid, order = 1))
        fixture.taskDao.insertTask(task(user.id, "b1", beta.uuid, order = 2))
        fixture.taskDao.insertTask(task(user.id, "b2", beta.uuid, order = 3))
        val rows = stored().associateBy { it.title }

        // The reader sees Beta only and drags it into reverse: b2 first, then b1.
        assertTrue(
            "the move is between two siblings, so it is accepted",
            move(rows.getValue("b2"), rows.getValue("b1"), placeAfter = false) is HierarchyMoveResult.Accepted
        )

        val after = stored().associateBy { it.title }
        assertEquals(
            "a row the reader could not see keeps its place against every row the drag did not cross",
            listOf("a1", "a2", "b2", "b1"),
            stored().map { it.title }
        )
        assertNotEquals(
            "the collision a filtered re-numbering used to produce: b2 landing on a1's position",
            after.getValue("a1").order,
            after.getValue("b2").order
        )
        val orders = stored().map { it.order }
        assertEquals("no two rows of one profile claim the same position", orders.size, orders.distinct().size)
        assertEquals("and the positions are one dense sequence", (0 until orders.size).toList(), orders.sorted())
    }

    private suspend fun stored(): List<Task> =
        fixture.taskDao.getRowsInStoredOrder(fixture.sessionRepository.currentUserId() ?: error("no profile"))

    private suspend fun storedByTitle() = stored().associateBy { it.title }

    private suspend fun move(what: Task, anchor: Task, placeAfter: Boolean) =
        fixture.taskHierarchyRepository.moveWithinSiblings(what.id, anchor.id, placeAfter)

    private fun task(userId: Long, title: String, groupId: String, order: Int, parentUuid: String? = null) = Task(
        title = title,
        description = "",
        uuid = title,
        userId = userId,
        groupId = groupId,
        parentUuid = parentUuid,
        order = order
    )
}
