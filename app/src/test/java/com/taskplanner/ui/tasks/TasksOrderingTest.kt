package com.taskplanner.ui.tasks

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.taskplanner.data.hierarchy.HierarchyMoveRefusal
import com.taskplanner.data.hierarchy.HierarchyMoveResult
import com.taskplanner.data.hierarchy.TaskHierarchyPolicy
import com.taskplanner.data.model.SyncState
import com.taskplanner.data.model.Task
import com.taskplanner.data.model.TaskStatus
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * What a drag-and-drop is allowed to do to the stored order, and what it is not.
 *
 * Stage 15 left one honest defect on record: `saveVisibleOrder` re-numbered `0..n` over the rows *on screen*, so a
 * drag made while a filter was active put two rows of one profile in the same position and left the list breaking
 * the tie by due date. That was a sorting oddity while nothing could nest. The moment a task can hang under another
 * task it becomes a data bug — two children of one parent claiming the same slot means the order of the reader's
 * notebook has no answer — so the ordering is fixed first, before any indent or outdent exists.
 *
 * **The strategy this pins, and why it was chosen over the alternatives.** A move is planned against the rows the
 * reader can see and *applied* to the profile's whole canonical sequence: the dragged row (with its subtree, as one
 * block) is lifted out of the stored order and set down on the side of the anchor row the drop landed on, then the
 * sequence is numbered densely again and only the rows whose number actually moved are written. So
 *
 *  * hidden rows never disappear and are never moved on their own — each keeps its place against every row the drag
 *    did not cross, which is what the three filter cases below assert;
 *  * no two rows of a profile can share a position, because the numbering is the one the write produces rather than
 *    a re-numbering of a subset of it;
 *  * the write is bounded by the *distance* travelled, not by the size of the account — pinned at one swap in
 *    [onlyTheRowsTheMoveCrossedAreRewritten] and re-measured at 1k/5k/10k in the Stage 16 §14 benchmark. That is
 *    why this strategy was taken instead of re-numbering the whole profile on every drop.
 *
 * **The boundary is part of the same fix.** A reorder changes positions and nothing else: never the owner, the topic
 * or the parent. The code that shipped checked only that both rows were task rows, so a drop between two topics —
 * or between one parent's children and another's — silently reparented a row. The refusal cases below make the
 * sibling set the only place a plain drag may land, and the subtree cases make a parent travel with its children.
 */
@RunWith(RobolectricTestRunner::class)
class TasksOrderingTest {

    private lateinit var fixture: AppFixture
    private lateinit var hierarchy: TaskHierarchyRepository
    private lateinit var viewModel: TasksViewModel
    private var userId: Long = 0

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        fixture = AppFixture(context)
        Dispatchers.setMain(Executor { it.run() }.asCoroutineDispatcher())
        userId = runBlocking { fixture.signIn(email = "ordering@example.com").id }
        hierarchy = TaskHierarchyRepository(
            fixture.database, fixture.taskDao, fixture.sessionRepository, fixture.timeBase
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

    /**
     * A row the reader typed by hand, at the position the reader had it in.
     *
     * Seeded `SYNCED` and at [FIXTURE_MILLIS] so a later move can be told apart from a touch that changed nothing:
     * an untouched row keeps both, and that is what makes the bounded write measurable rather than arguable.
     */
    private fun row(
        title: String,
        group: String? = null,
        position: Int,
        parent: String? = null,
        categoryId: Long? = null,
        status: TaskStatus = TaskStatus.TODO,
        description: String = "",
        owner: Long = userId
    ): Task = runBlocking {
        val id = fixture.taskDao.insertTask(
            Task(
                title = title,
                description = description,
                uuid = title,
                userId = owner,
                groupId = group,
                parentUuid = parent,
                categoryId = categoryId,
                status = status,
                order = position,
                syncState = SyncState.SYNCED,
                createdAt = FIXTURE_MILLIS,
                updatedAt = FIXTURE_MILLIS
            )
        )
        fixture.taskDao.getTaskById(id)!!
    }

    private fun stored(forUser: Long = userId): List<Task> = runBlocking {
        fixture.taskDao.getRowsInStoredOrder(forUser)
    }

    private fun moveBeside(what: Task, anchor: Task, after: Boolean): HierarchyMoveResult =
        runBlocking { hierarchy.moveWithinSiblings(what.id, anchor.id, after) }

    /** Room answers on its own threads and the screen re-renders from the flow, so a test waits for both. */
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

    /** The screen's own path: this is the command the list issues when the reader lets go of a card. */
    private fun dragOnScreen(what: Task, anchor: Task, after: Boolean, storedAs: List<String>) {
        viewModel.moveTaskBeside(what.id, anchor.id, after)
        settle("the drag to be written as $storedAs") { stored().map { it.title } == storedAs }
    }

    /** The rendered list, once a filter change has reached the screen. */
    private fun awaitRendered(what: String, expected: List<String>) {
        settle(what) { renderedTitles() == expected }
        assertEquals(what, expected, renderedTitles())
    }

    private fun renderedTitles() =
        viewModel.state.value.items.filterIsInstance<TasksListItem.TaskRow>().map { it.task.title }

    private fun accepted(outcome: HierarchyMoveResult): HierarchyMoveResult.Accepted {
        assertTrue("the move should have been accepted, and it was not: $outcome", outcome is HierarchyMoveResult.Accepted)
        return outcome as HierarchyMoveResult.Accepted
    }

    @Test
    fun a_move_beside_a_sibling_while_a_category_filter_is_on_leaves_the_hidden_row_where_it_was() {
        val work = runBlocking { fixture.addCategory(userId, "cat-work") }
        val errand = runBlocking { fixture.addCategory(userId, "cat-errand") }
        val first = row("o1", categoryId = work, position = 0)
        row("o2", categoryId = errand, position = 1)
        val second = row("o3", categoryId = work, position = 2)
        row("o4", categoryId = work, position = 3)
        runBlocking { viewModel.setCategoryFilter(work) }
        awaitRendered("the category filter to reach the screen", listOf("o1", "o3", "o4"))

        // The reader sees o1, o3, o4 and drops o3 above o1.
        dragOnScreen(second, first, after = false, storedAs = listOf("o3", "o1", "o2", "o4"))

        assertEquals(
            "the whole profile, with the filter cleared: the moved row sits above the one it was dropped on, and " +
                "the row the filter hid keeps its place against every row the drag did not cross",
            listOf("o3", "o1", "o2", "o4"),
            stored().map { it.title }
        )
        runBlocking { viewModel.setCategoryFilter(null) }
        awaitRendered("the cleared filter to redraw the list", listOf("o3", "o1", "o2", "o4"))
        assertEquals("no row is left claiming a position another row holds", 4, stored().map { it.order }.distinct().size)
    }

    @Test
    fun a_move_beside_a_sibling_while_a_status_filter_is_on_leaves_the_hidden_row_where_it_was() {
        val first = row("s1", position = 0)
        row("s2", position = 1, status = TaskStatus.COMPLETED)
        val second = row("s3", position = 2)
        runBlocking { viewModel.setStatusFilter(TaskStatus.TODO) }
        awaitRendered("the status filter to reach the screen", listOf("s1", "s3"))

        dragOnScreen(second, first, after = false, storedAs = listOf("s3", "s1", "s2"))

        assertEquals(
            "a row the status filter hides is still a row the order has to hold a place for",
            listOf("s3", "s1", "s2"),
            stored().map { it.title }
        )
        assertEquals("and no two of them share a position", 3, stored().map { it.order }.distinct().size)
    }

    @Test
    fun a_move_beside_a_sibling_while_the_reader_is_searching_leaves_the_hidden_row_where_it_was() {
        val first = row("needle one", position = 0)
        row("other two", position = 1)
        val second = row("needle three", position = 2)
        runBlocking { viewModel.setSearchQuery("needle") }
        awaitRendered("the search to reach the screen", listOf("needle one", "needle three"))

        dragOnScreen(second, first, after = false, storedAs = listOf("needle three", "needle one", "other two"))

        assertEquals(
            "the search box narrows what is on screen, not what the order means",
            listOf("needle three", "needle one", "other two"),
            stored().map { it.title }
        )
    }

    @Test
    fun a_drag_between_partly_hidden_siblings_says_which_side_of_the_anchor_the_row_lands_on() {
        val first = row("v1", position = 0)
        row("v2", position = 1)
        val second = row("v3", position = 2)
        val visible = listOf(first, second)

        val upward = TaskHierarchyPolicy.planSiblingDrag(visible, second.id, first.id)
        val downward = TaskHierarchyPolicy.planSiblingDrag(visible, first.id, second.id)

        assertEquals(
            "dragging a row up onto a sibling puts it before that sibling",
            false,
            accepted(upward).placeAfter
        )
        assertEquals(first.id, accepted(upward).anchorTaskId)
        assertEquals("and the block that travels is the row grabbed, head first", listOf(second.id), accepted(upward).movedTaskIds)

        assertEquals("dragging it down puts it after the sibling it crossed", true, accepted(downward).placeAfter)
        assertEquals(second.id, accepted(downward).anchorTaskId)
    }

    @Test
    fun applying_or_clearing_the_filter_cannot_change_where_the_dragged_row_ends_up() {
        val first = row("m1", position = 0)
        val hidden = row("m2", position = 1)
        val second = row("m3", position = 2)

        // The same gesture read two ways: from the filtered list the reader is looking at, and from all three rows.
        val fromFiltered = TaskHierarchyPolicy.planSiblingDrag(listOf(first, second), second.id, first.id)
        val fromUnfiltered = TaskHierarchyPolicy.planSiblingDrag(listOf(first, hidden, second), second.id, first.id)

        // Named rather than compared with each other, so the equality below has something to be equal to: the plan
        // is the row m3, beside m1, on the side nearer the top of the list.
        val expected = HierarchyMoveResult.Accepted(listOf(second.id), first.id, placeAfter = false)
        assertEquals("the plan says the row lands above the sibling it was dropped on", expected, fromFiltered)
        assertEquals("and the plan the unfiltered list derives is the same plan", expected, fromUnfiltered)
        moveBeside(second, first, after = false)
        assertEquals(
            "which is where the stored sequence puts it, with the hidden row untouched below the pair",
            listOf("m3", "m1", "m2"),
            stored().map { it.title }
        )
    }

    @Test
    fun a_drop_on_a_row_of_another_topic_is_refused() {
        val alpha = runBlocking { fixture.addGroup(userId, "grp-alpha") }
        val beta = runBlocking { fixture.addGroup(userId, "grp-beta") }
        val inAlpha = row("t1", group = alpha.uuid, position = 0)
        val inBeta = row("t2", group = beta.uuid, position = 1)

        val outcome = moveBeside(inAlpha, inBeta, after = false)

        assertEquals(
            "a plain drag reorders a row among its own siblings; it does not move it into another topic",
            HierarchyMoveRefusal.DIFFERENT_TOPIC,
            (outcome as HierarchyMoveResult.Refused).reason
        )
        val after = stored().associateBy { it.title }
        assertEquals("and the refusal writes nothing", alpha.uuid, after.getValue("t1").groupId)
        assertEquals(beta.uuid, after.getValue("t2").groupId)
        assertEquals(listOf(0, 1), listOf(after.getValue("t1").order, after.getValue("t2").order))
        assertEquals(SyncState.SYNCED, after.getValue("t1").syncState)
        assertEquals(FIXTURE_MILLIS, after.getValue("t1").updatedAt)
    }

    @Test
    fun a_drop_on_a_row_under_another_parent_is_refused() {
        val alpha = runBlocking { fixture.addGroup(userId, "grp-alpha") }
        val parent = row("p1", group = alpha.uuid, position = 0)
        val child = row("p2", group = alpha.uuid, position = 1, parent = parent.uuid)
        row("p3", group = alpha.uuid, position = 2, parent = child.uuid)

        val outcome = moveBeside(child, parent, after = true)

        assertEquals(
            "the two rows do not share a parent, so this is a reparenting and not a reorder",
            HierarchyMoveRefusal.DIFFERENT_PARENT,
            (outcome as HierarchyMoveResult.Refused).reason
        )
        val after = stored().associateBy { it.title }
        assertEquals("the child keeps its parent", parent.uuid, after.getValue("p2").parentUuid)
        assertEquals("the grandchild keeps its parent", child.uuid, after.getValue("p3").parentUuid)
    }

    @Test
    fun a_drop_inside_the_subtree_being_moved_is_refused() {
        val parent = row("q1", position = 0)
        val child = row("q2", position = 1, parent = parent.uuid)
        row("q3", position = 2)

        // The ordinary shape of "dropped on its own child": the two rows have different parents, so the sibling
        // boundary answers it first. What the subtree check exists for is the shape the boundary cannot see —
        // a row that names itself as its parent, which leaves it and its real child sharing one sibling key.
        val plan = TaskHierarchyPolicy.planSiblingDrag(stored(), parent.id, child.id)
        assertEquals(
            "a row cannot be dropped onto its own child — that is a reparenting wearing a drag gesture",
            HierarchyMoveRefusal.DIFFERENT_PARENT,
            (plan as HierarchyMoveResult.Refused).reason
        )

        val selfParented = row("q4", position = 3, parent = "q4")
        val underIt = row("q5", position = 4, parent = "q4")
        val corrupt = TaskHierarchyPolicy.planSiblingDrag(stored(), selfParented.id, underIt.id)
        assertEquals(
            "and a row that points at itself has a child wearing a sibling, which the subtree check is the only thing that stops",
            HierarchyMoveRefusal.ANCHOR_IN_SUBTREE,
            (corrupt as HierarchyMoveResult.Refused).reason
        )
    }

    @Test
    fun a_parent_moved_by_a_drag_takes_its_whole_subtree_with_it() {
        val alpha = runBlocking { fixture.addGroup(userId, "grp-alpha") }
        val head = row("a1", group = alpha.uuid, position = 0)
        val firstChild = row("a2", group = alpha.uuid, position = 1, parent = head.uuid)
        val grandChild = row("a3", group = alpha.uuid, position = 2, parent = firstChild.uuid)
        val secondChild = row("a4", group = alpha.uuid, position = 3, parent = head.uuid)
        val sibling = row("a5", group = alpha.uuid, position = 4)

        val outcome = accepted(moveBeside(head, sibling, after = true))

        assertEquals(
            "the block that travels is the head with every descendant, head first",
            listOf(head.id, firstChild.id, grandChild.id, secondChild.id),
            outcome.movedTaskIds
        )
        assertEquals(
            "the subtree arrives intact, in the order it already had",
            listOf("a5", "a1", "a2", "a3", "a4"),
            stored().map { it.title }
        )
        val after = stored().associateBy { it.title }
        assertEquals("no descendant was left behind", head.uuid, after.getValue("a2").parentUuid)
        assertEquals("no descendant was re-parented on the way", firstChild.uuid, after.getValue("a3").parentUuid)
        assertEquals("the whole subtree keeps the topic", alpha.uuid, after.getValue("a4").groupId)
    }

    @Test
    fun a_move_never_changes_the_owner_topic_or_parent_of_any_row() {
        val alpha = runBlocking { fixture.addGroup(userId, "grp-alpha") }
        val head = row("n1", group = alpha.uuid, position = 0)
        val child = row("n2", group = alpha.uuid, position = 1, parent = head.uuid)
        val lone = row("n3", group = alpha.uuid, position = 2)
        val before = stored().associateBy { it.title }

        moveBeside(lone, head, after = false)

        val after = stored().associateBy { it.title }
        for (title in before.keys) {
            assertEquals("$title changed owner during a reorder", before.getValue(title).userId, after.getValue(title).userId)
            assertEquals("$title changed topic during a reorder", before.getValue(title).groupId, after.getValue(title).groupId)
            assertEquals("$title changed parent during a reorder", before.getValue(title).parentUuid, after.getValue(title).parentUuid)
        }
        assertEquals("and no row was added or lost", before.size, after.size)
        assertEquals(listOf("n3", "n1", "n2"), after.values.sortedBy { it.order }.map { it.title })
    }

    @Test
    fun the_positions_after_a_move_are_one_dense_sequence_over_the_profile() {
        val alpha = runBlocking { fixture.addGroup(userId, "grp-alpha") }
        val beta = runBlocking { fixture.addGroup(userId, "grp-beta") }
        row("d1", group = alpha.uuid, position = 0)
        row("d2", group = alpha.uuid, position = 1)
        val third = row("d3", group = beta.uuid, position = 2)
        val fourth = row("d4", group = beta.uuid, position = 3)

        // The reader was looking at Beta only and dragged its second row above its first: the code that shipped
        // re-numbered just those two from zero, so d4 landed on d1's position in the other topic.
        moveBeside(fourth, third, after = false)

        val orders = stored().map { it.order }
        assertEquals("every row of the profile holds a position of its own", orders.size, orders.distinct().size)
        assertEquals("and the positions are one continuous sequence", listOf(0, 1, 2, 3), orders.sorted())
        assertEquals(listOf("d4", "d3"), stored().filter { it.groupId == beta.uuid }.map { it.title })
    }

    @Test
    fun only_the_rows_the_move_crossed_are_rewritten() {
        val rows = (1..6).map { row("w$it", position = it - 1) }
        val before = stored().associateBy { it.id }
        val movedTo = fixture.timeBase.wall + 5_000L
        fixture.timeBase.advance(5_000L)

        // A swap of two neighbours: four rows were never involved and must not be written at all.
        val outcome = accepted(moveBeside(rows[4], rows[3], after = false))

        val after = stored().associateBy { it.id }
        val changed = after.values.filter { it.order != before.getValue(it.id).order }.map { it.id }.toSet()
        val pending = after.values.filter { it.syncState == SyncState.PENDING }.map { it.id }.toSet()
        assertEquals(
            "the answer names the rows it wrote, so a caller can tell a drag that moved two rows from one that rewrote six",
            2,
            outcome.rowsRewritten
        )
        assertEquals(
            "a row is marked for sync exactly when its position changed, which is what keeps the write bounded " +
                "by the distance the reader dragged rather than by the size of the account",
            changed,
            pending
        )
        assertEquals("and the two rows that swapped are the only two written", setOf(rows[4].id, rows[3].id), changed)
        after.values.filter { it.id in changed }.forEach { changedRow ->
            assertEquals("the rows that moved carry the clock of the move", movedTo, changedRow.updatedAt)
        }
        after.values.filter { it.id !in changed }.forEach { untouched ->
            assertEquals("a row that did not move keeps its stored instant", FIXTURE_MILLIS, untouched.updatedAt)
            assertEquals(SyncState.SYNCED, untouched.syncState)
        }
        assertEquals("nothing was deleted by the move", before.size, after.size)
    }

    @Test
    fun a_move_onto_a_row_the_profile_does_not_own_is_refused_rather_than_guessed() {
        val stranger = runBlocking { fixture.signIn(name = "Stranger", email = "stranger@example.com").id }
        val foreignId = runBlocking {
            fixture.taskDao.insertTask(
                Task(title = "x1", description = "", userId = stranger, order = 0, updatedAt = FIXTURE_MILLIS)
            )
        }
        runBlocking { fixture.switchTo(fixture.userDao.getUserById(userId)!!) }
        val mine = row("x2", position = 1)

        val outcome = runBlocking { hierarchy.moveWithinSiblings(mine.id, foreignId, placeAfter = false) }

        assertEquals(
            "a foreign row is not a drop target, and the answer is a named refusal rather than a guess",
            HierarchyMoveRefusal.ANCHOR_MISSING,
            (outcome as HierarchyMoveResult.Refused).reason
        )
        assertEquals("and my own rows are untouched", 1, stored().associateBy { it.title }.getValue("x2").order)
    }

    @Test
    fun a_move_leaves_another_profiles_rows_untouched() {
        val stranger = runBlocking { fixture.signIn(name = "Other", email = "other@example.com").id }
        row("theirs", position = 0, owner = stranger)
        runBlocking { fixture.switchTo(fixture.userDao.getUserById(userId)!!) }
        // Both profiles would number their own rows from zero; the move must not reach across.
        val first = row("mine1", position = 0)
        val second = row("mine2", position = 0)

        moveBeside(second, first, after = false)

        val mine = stored().associateBy { it.title }
        assertEquals(listOf("mine2", "mine1"), stored().map { it.title })
        assertEquals("my rows are numbered among themselves", listOf(0, 1), mine.values.map { it.order }.sorted())
        val theirs = stored(forUser = stranger)
        assertEquals("another profile's row keeps its own position", listOf("theirs"), theirs.map { it.title })
        assertEquals(0, theirs.first().order)
        assertEquals(SyncState.SYNCED, theirs.first().syncState)
    }

    @Test
    fun the_sibling_set_is_the_only_place_a_plain_drag_may_land() {
        val alpha = runBlocking { fixture.addGroup(userId, "grp-alpha") }
        val beta = runBlocking { fixture.addGroup(userId, "grp-beta") }
        val parent = row("k1", group = alpha.uuid, position = 0)
        val child = row("k2", group = alpha.uuid, position = 1, parent = "k1")
        val childSibling = row("k5", group = alpha.uuid, position = 2, parent = "k1")
        val otherTopic = row("k3", group = beta.uuid, position = 3)
        val standalone = row("k4", position = 4)
        val otherStandalone = row("k6", position = 5)

        // Each key is written out instead of being compared with its own production, so a change that made every row
        // share one key — dropping the parent from it, say — would fail here rather than agree with itself.
        assertEquals(
            "two rows of one parent are siblings",
            TaskHierarchyPolicy.SiblingKey(userId, alpha.uuid, "k1"),
            TaskHierarchyPolicy.siblingKeyOf(child)
        )
        assertEquals(
            "and the row beside it holds that same key",
            TaskHierarchyPolicy.siblingKeyOf(child),
            TaskHierarchyPolicy.siblingKeyOf(childSibling)
        )
        assertEquals(
            "a topic's root row hangs from nothing, so it is not in its own child's set",
            TaskHierarchyPolicy.SiblingKey(userId, alpha.uuid, null),
            TaskHierarchyPolicy.siblingKeyOf(parent)
        )
        assertNotEquals("root row and its child are not siblings", TaskHierarchyPolicy.siblingKeyOf(parent), TaskHierarchyPolicy.siblingKeyOf(child))
        assertNotEquals("rows of different topics are not", TaskHierarchyPolicy.siblingKeyOf(parent), TaskHierarchyPolicy.siblingKeyOf(otherTopic))
        assertNotEquals("a topic's root row and an unfiled row are not", TaskHierarchyPolicy.siblingKeyOf(parent), TaskHierarchyPolicy.siblingKeyOf(standalone))
        assertEquals(
            "and the unfiled rows do share one set with each other",
            TaskHierarchyPolicy.SiblingKey(userId, null, null),
            TaskHierarchyPolicy.siblingKeyOf(otherStandalone)
        )
    }

    @Test
    fun a_subtree_walk_stops_at_a_cycle_rather_than_running_forever() {
        val head = row("c1", position = 0)
        row("c2", position = 1, parent = "c1")
        row("c3", position = 2, parent = "c2")
        row("c4", position = 3, parent = "nowhere")
        // Close the loop from below: the head now names its own grandchild as its parent, which is the shape a
        // hand-edited backup or a mis-ordered sync pull can write into a column that has no foreign key.
        runBlocking { fixture.taskDao.updateTask(head.copy(parentUuid = "c3")) }
        val rows = stored()

        val block = TaskHierarchyPolicy.subtreeOf(rows, rows.first { it.title == "c1" })

        assertEquals(
            "a cycle in the column is answered by the walk stopping, not by the screen hanging",
            listOf("c1", "c2", "c3"),
            block.map { it.title }
        )
        assertEquals(
            "and a row that points at a parent the profile does not hold is its own subtree",
            listOf("c4"),
            TaskHierarchyPolicy.subtreeOf(rows, rows.first { it.title == "c4" }).map { it.title }
        )
    }

    @Test
    fun a_row_with_a_blank_identity_does_not_adopt_every_other_blank_row() {
        // `tasks.uuid` defaults to '', so legacy rows — and a pull carrying an empty `parent_uuid` — all share that
        // one value. Matching children by a blank identity would let one dragged row take every other blank-parented
        // row of the profile along with it, which is the mistake Stage 16 §4 exists to measure.
        listOf("l1", "l2", "l3").forEachIndexed { index, title ->
            runBlocking {
                fixture.taskDao.insertTask(
                    Task(
                        title = title, description = "", uuid = "", userId = userId,
                        parentUuid = "", order = index, syncState = SyncState.SYNCED
                    )
                )
            }
        }
        val rows = stored()

        val block = TaskHierarchyPolicy.subtreeOf(rows, rows.first { it.title == "l1" })
        assertEquals("the dragged row travels with its own descendants, and it has none", listOf("l1"), block.map { it.title })

        val outcome = moveBeside(rows.first { it.title == "l3" }, rows.first { it.title == "l1" }, after = false)
        assertEquals(
            "and the move lifts the one row the reader grabbed",
            listOf(rows.first { it.title == "l3" }.id),
            accepted(outcome).movedTaskIds
        )
        assertEquals("the three legacy rows reorder among themselves", listOf("l3", "l1", "l2"), stored().map { it.title })
    }

    @Test
    fun a_profile_whose_positions_are_doubled_or_gapped_comes_back_as_one_sequence() {
        // The shape the old filtered re-numbering left behind: two rows on one position and a hole nobody filled.
        // A drag is the reconciliation point — the sequence is numbered again densely, so the profile ends with one
        // position per row. The cost is named in `rowsRewritten`: on legacy data the first drag writes the profile
        // once, and every drag after that writes only the distance it travelled.
        val first = row("g1", position = 0)
        row("g2", position = 0)
        row("g3", position = 7)
        val fourth = row("g4", position = 9)

        val outcome = accepted(moveBeside(fourth, first, after = false))

        val orders = stored().map { it.order }
        assertEquals("every row ends with a position of its own", orders.size, orders.distinct().size)
        assertEquals("and the positions are one dense run", listOf(0, 1, 2, 3), orders.sorted())
        assertEquals("which here means all four rows were re-numbered", 4, outcome.rowsRewritten)
        assertEquals(listOf("g4", "g1", "g2", "g3"), stored().map { it.title })
    }

    @Test
    fun a_task_saved_from_the_editor_does_not_take_a_position_an_archived_row_holds() {
        // An archived row is out of the list but not out of the sequence — the reorder numbers over it — so a new
        // task counting its position from the visible rows alone would land on a number that is already held.
        val archived = row("archived one", position = 0)
        runBlocking { fixture.taskDao.archiveTask(archived.id) }
        row("kept", position = 1)

        runBlocking { viewModel.saveTask(title = "typed later", description = "") }
        settle("the saved task to reach the stored sequence") { stored().map { it.title }.contains("typed later") }

        val orders = stored().map { it.order }
        assertEquals(
            "the new row joins the profile's sequence without colliding with the hidden archived row",
            orders.size,
            orders.distinct().size
        )
        assertEquals("and the sequence stays one dense run", (0 until orders.size).toList(), orders.sorted())
    }

    private companion object {
        /** The instant every seeded row carries, so a later move's timestamp is distinguishable from it. */
        const val FIXTURE_MILLIS = 1_700_000_000_000L
    }
}
