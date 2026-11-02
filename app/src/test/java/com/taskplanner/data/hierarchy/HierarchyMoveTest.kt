package com.taskplanner.data.hierarchy

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.taskplanner.data.keep.KeepParserIssue
import com.taskplanner.data.keep.KeepTextParser
import com.taskplanner.data.model.SyncState
import com.taskplanner.data.model.Task
import com.taskplanner.data.repository.TaskHierarchyRepository
import com.taskplanner.testing.AppFixture
import com.taskplanner.ui.tasks.TaskTree
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Changing the shape of the tree: indent, outdent, and moving a subtree from one parent or topic to another.
 *
 * Stage 15 could only bring a tree in; Stage 16 lets the reader change one that is already there. Every operation
 * here is one transaction over the profile's stored rows, and every one answers with a named result — a refusal is
 * not a silent return, because the screen has to say what the reader tried and what stopped it.
 *
 * **The shape the contract allows** (Stage 16 §1, settled 2026-11-02): level 0 is the topic, level 1 is a task under
 * the topic, and the deepest task is level 3 — four levels counting the topic, and one indent step fewer on the
 * card. One number now governs the parser that reads a Keep paste, the mapping that writes it, the move validation
 * below, the renderer that indents a card, and the backup and sync that carry it. The last test in this file is the
 * guard against those drifting apart again: Stage 15 ended with the parser and the renderer each calling their bound
 * 3 and meaning something different by it.
 *
 * **What a refusal owes the database.** Nothing. Every refusal case below re-reads the whole profile and compares it
 * with what was there before — same rows, same parent links, same topics, same positions, no row newly marked for a
 * push. A half-applied tree is the failure this stage cannot afford, because the reader does not see it until the
 * structure is already wrong.
 */
@RunWith(RobolectricTestRunner::class)
class HierarchyMoveTest {

    private lateinit var fixture: AppFixture
    private lateinit var hierarchy: TaskHierarchyRepository
    private var userId: Long = 0

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        fixture = AppFixture(context)
        userId = runBlocking { fixture.signIn(email = "hierarchy@example.com").id }
        hierarchy = TaskHierarchyRepository(
            fixture.database, fixture.taskDao, fixture.groupDao, fixture.trackingSessionDao, fixture.reminderDao,
            fixture.sessionRepository, fixture.timeBase
        )
    }

    @After
    fun tearDown() = fixture.close()

    private fun row(
        title: String,
        parent: String? = null,
        position: Int,
        group: String? = null,
        uuid: String = title
    ): Task = runBlocking {
        val id = fixture.taskDao.insertTask(
            Task(
                title = title, description = "", uuid = uuid, userId = userId, groupId = group,
                parentUuid = parent, order = position, syncState = SyncState.SYNCED,
                createdAt = STAMP, updatedAt = STAMP
            )
        )
        fixture.taskDao.getTaskById(id)!!
    }

    private fun stored(): List<Task> = runBlocking { fixture.taskDao.getRowsInStoredOrder(userId) }

    private fun storedByUuid(): Map<String, Task> = stored().associateBy { it.uuid }

    /**
     * Everything any of these edits could damage, in one comparable value: the identity, the link, the topic, the
     * position and whether the row is waiting for a push. A refusal is only proven harmless if all five are unchanged.
     */
    private fun shape(): List<List<Any?>> = stored().map {
        listOf(it.uuid, it.parentUuid, it.groupId, it.order, it.syncState)
    }

    private fun positions(): List<Pair<String, Int>> = stored().map { it.uuid to it.order }

    private fun accepted(outcome: HierarchyMoveResult): HierarchyMoveResult.Accepted {
        assertTrue(
            "the operation should have been accepted, and it was not: $outcome",
            outcome is HierarchyMoveResult.Accepted
        )
        return outcome as HierarchyMoveResult.Accepted
    }

    private fun refused(outcome: HierarchyMoveResult, reason: HierarchyMoveRefusal) {
        assertEquals(
            "the operation should have been refused as $reason, and it answered: $outcome",
            reason,
            (outcome as HierarchyMoveResult.Refused).reason
        )
    }

    @Test
    fun indent_hangs_a_task_on_the_row_above_it_and_changes_nothing_else() {
        val topic = runBlocking { fixture.addGroup(userId, "grp-work") }
        val project = row("project", position = 0, group = topic.uuid)
        val task = row("task", position = 1, group = topic.uuid)

        val outcome = accepted(runBlocking { hierarchy.indentTask(task.id) })

        assertEquals("an indent writes the one row whose link changed, and no positions", 1, outcome.rowsRewritten)
        val after = storedByUuid()
        assertEquals("the row above became its parent", project.uuid, after.getValue("task").parentUuid)
        assertEquals("the topic is unchanged by an indent", topic.uuid, after.getValue("task").groupId)
        assertNull("and the row above did not acquire a parent", after.getValue("project").parentUuid)
        assertEquals("the order the reader had is the order still on screen",
            listOf("project", "task"), stored().map { it.uuid })
    }

    @Test
    fun indent_takes_the_subtree_with_it_and_leaves_internal_links_alone() {
        val a = row("a", position = 0)
        val b = row("b", position = 1)
        val b1 = row("b-one", parent = "b", position = 2)
        val after = row("after", position = 3)

        val outcome = accepted(runBlocking { hierarchy.indentTask(b.id) })

        assertEquals(
            "the block that moved is the head and everything under it, head first",
            listOf(b.id, b1.id),
            outcome.movedTaskIds
        )
        val afterRows = storedByUuid()
        assertEquals("the row above became the parent of the head", a.uuid, afterRows.getValue("b").parentUuid)
        assertEquals("and the grandchild still hangs from its own parent, not from the new one",
            "b", afterRows.getValue("b-one").parentUuid)
        assertNull("the row below the block was not touched", afterRows.getValue("after").parentUuid)
        assertEquals("the sequence the reader had is the sequence still",
            listOf("a", "b", "b-one", "after"), stored().map { it.uuid })
    }

    @Test
    fun indent_is_refused_where_there_is_no_row_to_hang_from() {
        val first = row("first", position = 0)
        row("second", position = 1)
        val before = shape()
        val beforePositions = positions()

        refused(runBlocking { hierarchy.indentTask(first.id) }, HierarchyMoveRefusal.NO_ROW_ABOVE)

        assertEquals("the first row of the profile has no predecessor, and the refusal wrote nothing", before, shape())
        assertEquals("no position moved", beforePositions, positions())
    }

    @Test
    fun indent_is_refused_when_the_row_already_hangs_from_the_row_above_it() {
        row("parent", position = 0)
        val child = row("child", parent = "parent", position = 1)
        val before = shape()

        refused(runBlocking { hierarchy.indentTask(child.id) }, HierarchyMoveRefusal.NO_DEEPER_PARENT)

        assertEquals("a second indent of an already-nested row changes nothing", before, shape())
    }

    @Test
    fun indent_across_a_topic_boundary_is_refused() {
        val alpha = runBlocking { fixture.addGroup(userId, "grp-alpha") }
        val beta = runBlocking { fixture.addGroup(userId, "grp-beta") }
        row("a1", position = 0, group = alpha.uuid)
        val inBeta = row("b1", position = 1, group = beta.uuid)
        val before = shape()

        refused(runBlocking { hierarchy.indentTask(inBeta.id) }, HierarchyMoveRefusal.DIFFERENT_TOPIC)

        assertEquals("a topic's row never becomes another topic's child", before, shape())
    }

    @Test
    fun the_depth_bound_is_measured_over_the_whole_block_and_not_the_grabbed_row() {
        // A leaf can be indented onto a level-two row and land on level three, the deepest the contract holds. The
        // same indent for a row carrying a child would put that child on level four, so the block's height is what
        // decides — and the row with the child is refused while the leaf is not.
        row("l1", position = 0)
        row("mid", parent = "l1", position = 1)
        val carry = row("carry", position = 2)
        row("carried", parent = "carry", position = 3)

        refused(runBlocking { hierarchy.indentTask(carry.id) }, HierarchyMoveRefusal.DEPTH_EXCEEDED)
        assertEquals("the refused indent left the tree as it was", null, storedByUuid().getValue("carry").parentUuid)
    }

    @Test
    fun a_leaf_reaches_the_deepest_allowed_level_and_the_next_row_is_refused_at_the_door() {
        row("l1", position = 0)
        row("mid", parent = "l1", position = 1)
        val leaf = row("leaf", position = 2)
        val next = row("next", position = 3)

        accepted(runBlocking { hierarchy.indentTask(leaf.id) })
        assertEquals("a leaf onto a level-two row lands on level three", "mid", storedByUuid().getValue("leaf").parentUuid)
        assertEquals(3, TaskHierarchyPolicy.levelsByUuid(stored()).getValue("leaf"))

        refused(runBlocking { hierarchy.indentTask(next.id) }, HierarchyMoveRefusal.DEPTH_EXCEEDED)
        assertEquals("and the row below the full stack has nowhere deeper to go",
            null, storedByUuid().getValue("next").parentUuid)
    }

    @Test
    fun outdent_lifts_a_task_to_a_sibling_of_its_parent_and_sets_it_after_their_subtree() {
        row("root", position = 0)
        row("mid", parent = "root", position = 1)
        val deep = row("deep", parent = "mid", position = 2)
        row("after", parent = "mid", position = 3)

        accepted(runBlocking { hierarchy.outdentTask(deep.id) })

        val byUuid = storedByUuid()
        assertEquals("the row rose one level, to hang from its grandparent", "root", byUuid.getValue("deep").parentUuid)
        assertEquals("its sibling stayed where it was", "mid", byUuid.getValue("after").parentUuid)
        assertEquals(
            "it lands after the subtree it left, so the list still reads as branches and not as a shuffle",
            listOf("root", "mid", "after", "deep"),
            stored().map { it.uuid }
        )
    }

    @Test
    fun outdent_carries_a_subtree_and_is_refused_at_the_topic_root() {
        row("root", position = 0)
        val kid = row("kid", parent = "root", position = 1)
        row("kid's own", parent = "kid", position = 2)

        accepted(runBlocking { hierarchy.outdentTask(kid.id) })
        val byUuid = storedByUuid()
        assertNull("the child of a topic's own row rises to the topic root", byUuid.getValue("kid").parentUuid)
        assertEquals("and its own child travels with it", "kid", byUuid.getValue("kid's own").parentUuid)

        val before = shape()
        refused(runBlocking { hierarchy.outdentTask(byUuid.getValue("kid").id) }, HierarchyMoveRefusal.ALREADY_AT_TOPIC_ROOT)
        assertEquals("a topic's own child cannot be lifted out of the topic by an outdent", before, shape())
    }

    @Test
    fun a_topic_takes_a_subtree_and_every_row_of_it_changes_topic_together() {
        val alpha = runBlocking { fixture.addGroup(userId, "grp-alpha") }
        val beta = runBlocking { fixture.addGroup(userId, "grp-beta") }
        val head = row("head", position = 1, group = alpha.uuid)
        val child = row("child", parent = "head", position = 2, group = alpha.uuid)
        val inBeta = row("beta-root", position = 0, group = beta.uuid)

        val outcome = accepted(
            runBlocking { hierarchy.moveSubtreeUnder(head.id, newParentId = inBeta.id, destinationGroupUuid = beta.uuid) }
        )

        assertEquals("the block that moved is the whole subtree, head first",
            listOf(head.id, child.id), outcome.movedTaskIds)
        val byUuid = storedByUuid()
        assertEquals("the head hangs from the destination now", inBeta.uuid, byUuid.getValue("head").parentUuid)
        assertEquals("the topic moved with the block", beta.uuid, byUuid.getValue("head").groupId)
        assertEquals("and every descendant's topic moved with it", beta.uuid, byUuid.getValue("child").groupId)
        assertEquals("internal links are untouched", "head", byUuid.getValue("child").parentUuid)
        assertEquals(
            "the block sits under its new parent, in the order it already had",
            listOf("beta-root", "head", "child"),
            stored().filter { it.groupId == beta.uuid }.map { it.uuid }
        )
        stored().filter { it.uuid in listOf("head", "child") }.forEach { moved ->
            assertEquals("every row of the moved tree is marked for a push", SyncState.PENDING, moved.syncState)
        }
        assertEquals("the row it hung from was not itself moved",
            SyncState.SYNCED, storedByUuid().getValue("beta-root").syncState)
        assertEquals("nothing was added or lost", 3, stored().size)
    }

    @Test
    fun a_move_that_would_push_a_subtree_past_the_depth_bound_is_refused() {
        // The block is three rows deep already, so the destination parent's own level leaves no room under it: the
        // grandchild would land on level four, which the contract does not have. Refused before the write, not
        // flattened by it.
        val beta = runBlocking { fixture.addGroup(userId, "grp-beta") }
        val head = row("head", position = 1, group = beta.uuid)
        row("child", parent = "head", position = 2, group = beta.uuid)
        row("grandchild", parent = "child", position = 3, group = beta.uuid)
        val inBeta = row("beta-root", position = 0, group = beta.uuid)
        val before = shape()
        val beforePositions = positions()

        refused(
            runBlocking { hierarchy.moveSubtreeUnder(head.id, newParentId = inBeta.id, destinationGroupUuid = beta.uuid) },
            HierarchyMoveRefusal.DEPTH_EXCEEDED
        )

        assertEquals("the tree is where it was", before, shape())
        assertEquals("and no position was rewritten on the way to saying no", beforePositions, positions())
    }

    @Test
    fun a_move_naming_a_topic_that_is_not_the_destination_parent_s_topic_is_refused() {
        val alpha = runBlocking { fixture.addGroup(userId, "grp-alpha") }
        val beta = runBlocking { fixture.addGroup(userId, "grp-beta") }
        val loose = row("loose", position = 0)
        val underBeta = row("under-beta", position = 1, group = beta.uuid)
        val before = shape()

        refused(
            runBlocking {
                hierarchy.moveSubtreeUnder(loose.id, newParentId = underBeta.id, destinationGroupUuid = alpha.uuid)
            },
            HierarchyMoveRefusal.TOPIC_MISMATCH
        )
        assertEquals("one name or the other, never a tree split across both topics", before, shape())
    }

    @Test
    fun a_move_cannot_put_a_row_under_its_own_descendant_or_under_itself() {
        val head = row("head", position = 0)
        row("child", parent = "head", position = 1)
        val grandChild = row("grandchild", parent = "child", position = 2)
        val before = shape()
        val beforePositions = positions()

        refused(
            runBlocking {
                hierarchy.moveSubtreeUnder(head.id, newParentId = grandChild.id, destinationGroupUuid = head.groupId)
            },
            HierarchyMoveRefusal.ANCHOR_IN_SUBTREE
        )
        refused(
            runBlocking {
                hierarchy.moveSubtreeUnder(head.id, newParentId = head.id, destinationGroupUuid = head.groupId)
            },
            HierarchyMoveRefusal.SAME_ROW
        )
        assertEquals("no cycle was created", before, shape())
        assertEquals("no position moved", beforePositions, positions())
    }

    @Test
    fun a_move_onto_a_row_from_another_profile_is_refused_not_guessed() {
        val stranger = runBlocking { fixture.signIn(name = "Stranger", email = "stranger@example.com").id }
        val foreignTask = runBlocking {
            fixture.taskDao.insertTask(
                Task(title = "theirs", description = "", uuid = "theirs", userId = stranger, order = 0)
            )
        }
        runBlocking { fixture.switchTo(fixture.userDao.getUserById(userId)!!) }
        val mine = row("mine", position = 0)

        refused(
            runBlocking { hierarchy.moveSubtreeUnder(mine.id, newParentId = foreignTask, destinationGroupUuid = null) },
            HierarchyMoveRefusal.PARENT_NOT_IN_PROFILE
        )
        assertNull("my row kept its own parent link", storedByUuid().getValue("mine").parentUuid)
    }

    @Test
    fun a_move_to_a_topic_root_keeps_the_topic_and_clears_only_the_parent_link() {
        val alpha = runBlocking { fixture.addGroup(userId, "grp-alpha") }
        row("head", position = 0, group = alpha.uuid)
        val child = row("child", parent = "head", position = 1, group = alpha.uuid)

        accepted(runBlocking { hierarchy.moveSubtreeUnder(child.id, newParentId = null, destinationGroupUuid = alpha.uuid) })

        val byUuid = storedByUuid()
        assertNull("the row is now a topic's own child", byUuid.getValue("child").parentUuid)
        assertEquals("and it never left the topic", alpha.uuid, byUuid.getValue("child").groupId)
    }

    @Test
    fun a_move_into_a_topic_that_does_not_exist_is_refused() {
        val orphan = row("orphan", position = 0)

        refused(
            runBlocking { hierarchy.moveSubtreeUnder(orphan.id, newParentId = null, destinationGroupUuid = "no-such-group") },
            HierarchyMoveRefusal.TOPIC_MISSING
        )
        assertNull("the unfiled row stays unfiled", storedByUuid().getValue("orphan").groupId)
    }

    @Test
    fun a_standalone_tree_moves_into_a_topic_and_out_of_it_with_its_child() {
        val topic = runBlocking { fixture.addGroup(userId, "grp-topic") }
        val standalone = row("loose", position = 0)
        row("under", parent = "loose", position = 1)

        accepted(runBlocking { hierarchy.moveSubtreeUnder(standalone.id, newParentId = null, destinationGroupUuid = topic.uuid) })
        val intoTopic = storedByUuid()
        assertEquals("the whole tree took the topic", topic.uuid, intoTopic.getValue("loose").groupId)
        assertEquals("and its child took it too", topic.uuid, intoTopic.getValue("under").groupId)

        accepted(runBlocking { hierarchy.moveSubtreeUnder(standalone.id, newParentId = null, destinationGroupUuid = null) })
        val outAgain = storedByUuid()
        assertNull("back to unfiled, both rows together", outAgain.getValue("loose").groupId)
        assertNull("the child follows its parent out", outAgain.getValue("under").groupId)
        assertEquals("the link between them survived both trips", "loose", outAgain.getValue("under").parentUuid)
    }

    @Test
    fun every_moved_row_is_stamped_for_the_push_and_nothing_else_changes() {
        row("head", position = 0)
        row("child", parent = "head", position = 1)
        val bystander = row("bystander", position = 2)
        fixture.timeBase.advance(4_000L)

        accepted(runBlocking { hierarchy.indentTask(bystander.id) })

        val byUuid = storedByUuid()
        val moved = byUuid.getValue("bystander")
        assertEquals("the row whose parent changed is pending", SyncState.PENDING, moved.syncState)
        assertEquals("and carries the clock of the edit", fixture.timeBase.wall, moved.updatedAt)
        assertEquals("a row that did not change keeps its stored state",
            SyncState.SYNCED, byUuid.getValue("child").syncState)
        assertNull("the edit invented no deadline", moved.dueDate)
        assertNull("and no completion", moved.completedAt)
    }

    @Test
    fun one_depth_contract_holds_for_the_parser_the_renderer_and_the_moves() {
        assertEquals(3, TaskHierarchyPolicy.MAX_TASK_LEVELS)
        assertEquals(2, TaskHierarchyPolicy.MAX_INDENT_LEVEL)
        assertEquals(
            "the Keep parser accepts exactly the task levels the contract allows",
            TaskHierarchyPolicy.MAX_TASK_LEVELS,
            KeepTextParser.MAX_DEPTH
        )

        // A chain the renderer is handed that is deeper than the contract — which a sync pull or a hand-edited
        // backup can write — must clamp at the level the contract stops at, not one step past it.
        val chain = (0 until 6).map { index ->
            Task(
                id = index.toLong(), uuid = "n$index", title = "n$index", description = "", userId = 1L,
                parentUuid = if (index == 0) null else "n${index - 1}"
            )
        }
        assertEquals(
            "the card never indents past the level the tree is allowed to reach",
            TaskHierarchyPolicy.MAX_INDENT_LEVEL,
            TaskTree.depthsByTaskId(chain).values.maxOrNull()
        )

        row("l1", position = 0)
        row("leaf", position = 1)
        row("l2", parent = "l1", position = 2)
        row("l3", parent = "l2", position = 3)
        val rows = stored()
        assertEquals(
            "the levels a profile describes: a topic's own child is level 1 and the deepest task is level 3",
            mapOf("l1" to 1, "l2" to 2, "l3" to 3, "leaf" to 1),
            TaskHierarchyPolicy.levelsByUuid(rows)
        )
        val byUuid = rows.associateBy { it.uuid }
        assertEquals("a row with nothing under it is one level tall", 1, TaskHierarchyPolicy.heightOf(rows, byUuid.getValue("leaf")))
        assertEquals("a row with a child is two", 2, TaskHierarchyPolicy.heightOf(rows, byUuid.getValue("l2")))
        assertEquals("and the top of a full chain is three", 3, TaskHierarchyPolicy.heightOf(rows, byUuid.getValue("l1")))
    }

    @Test
    fun a_paste_deeper_than_the_contract_cannot_arrive_past_it_in_either_number() {
        // The equality between the two constants is definitional, so it proves nothing. What has to hold is that a
        // source deeper than the contract stops at the contract in the parser's own count, says so, and the same
        // tree could therefore never reach the renderer with an indent the card has no room for.
        val pasted = "topic\n  one\n    two\n      three\n        four\n          five"
        val document = KeepTextParser.parse(pasted)

        assertTrue(
            "the paste says which lines ran past the bound",
            document.issues.any { it is KeepParserIssue.TooDeep }
        )
        val depths = document.allNodes().map { it.depth }
        assertTrue("and nothing is stored deeper than level 3", depths.all { it <= TaskHierarchyPolicy.MAX_TASK_LEVELS })
        assertEquals(
            "the deepest row of the clamped tree is exactly at the bound, not past it",
            TaskHierarchyPolicy.MAX_TASK_LEVELS,
            depths.maxOrNull()
        )
    }

    @Test
    fun a_row_moving_onto_an_identity_two_rows_claim_is_refused_like_a_block_built_on_one() {
        val twinOne = row("twin one", uuid = "dup", position = 0)
        row("twin two", uuid = "dup", position = 1)
        val loose = row("loose", position = 2)
        val before = shape()

        refused(
            runBlocking { hierarchy.moveSubtreeUnder(loose.id, newParentId = twinOne.id, destinationGroupUuid = null) },
            HierarchyMoveRefusal.AMBIGUOUS_PARENT
        )
        refused(runBlocking { hierarchy.indentTask(loose.id, underTaskId = twinOne.id) }, HierarchyMoveRefusal.AMBIGUOUS_PARENT)

        assertEquals("neither attempt left a link that could be read two ways", before, shape())
    }

    @Test
    fun a_row_with_no_identity_of_its_own_cannot_take_a_child() {
        val anonymous = row("no uuid", uuid = "", position = 0)
        val loose = row("loose", position = 1)
        val before = shape()

        refused(
            runBlocking { hierarchy.moveSubtreeUnder(loose.id, newParentId = anonymous.id, destinationGroupUuid = null) },
            HierarchyMoveRefusal.PARENT_UNNAMED
        )
        refused(runBlocking { hierarchy.indentTask(loose.id, underTaskId = anonymous.id) }, HierarchyMoveRefusal.PARENT_UNNAMED)

        assertEquals("the refusal wrote nothing", before, shape())
    }

    @Test
    fun a_row_arriving_as_a_topic_root_lands_after_the_last_roots_whole_branch() {
        // The root of a topic is drawn above its own subtree; putting a new root *between* them would make the
        // existing children read as the newcomer's, and would rewrite their positions in a move that never touched
        // them. The arrival goes to the end of the last branch, not the end of the last row.
        val topic = runBlocking { fixture.addGroup(userId, "grp-arrivals") }
        row("r", position = 0, group = topic.uuid)
        row("r's child", parent = "r", position = 1, group = topic.uuid)
        val outside = row("outsider", position = 2)

        accepted(runBlocking { hierarchy.moveSubtreeUnder(outside.id, newParentId = null, destinationGroupUuid = topic.uuid) })

        val byUuid = storedByUuid()
        assertEquals("the newcomer is a root of the topic now", topic.uuid, byUuid.getValue("outsider").groupId)
        assertNull("and it hangs from nothing", byUuid.getValue("outsider").parentUuid)
        assertEquals(
            "it arrived after the branch, so no child ends up under a row that is not its parent",
            listOf("r", "r's child", "outsider"),
            stored().map { it.uuid }
        )
        assertEquals("the existing child kept its parent and its place", "r", byUuid.getValue("r's child").parentUuid)
        assertEquals(SyncState.SYNCED, byUuid.getValue("r's child").syncState)
    }

    @Test
    fun a_deleted_topic_refuses_to_take_a_subtree() {
        val doomed = runBlocking { fixture.addGroup(userId, "grp-doomed") }
        row("kept", position = 0)
        val loose = row("loose", position = 1)
        runBlocking { fixture.groupDao.softDelete(doomed.uuid) }

        refused(
            runBlocking { hierarchy.moveSubtreeUnder(loose.id, newParentId = null, destinationGroupUuid = doomed.uuid) },
            HierarchyMoveRefusal.TOPIC_MISSING
        )
        assertNull("a topic that is gone cannot adopt a tree", storedByUuid().getValue("loose").groupId)
    }

    @Test
    fun the_screen_can_name_the_row_it_actually_drew_above() {
        // Under a filter the stored predecessor can be a card the reader never saw; the list then says which row the
        // indent was asked for, and the repository honors it instead of hanging the row on an invisible parent.
        val hidden = row("hidden between", position = 0)
        val visible = row("what the reader sees", position = 1)
        val moved = row("the row being indented", position = 2)

        accepted(runBlocking { hierarchy.indentTask(moved.id, underTaskId = visible.id) })

        val byUuid = storedByUuid()
        assertEquals("it hangs from the row the reader was shown", visible.uuid, byUuid.getValue("the row being indented").parentUuid)
        assertNull("the row nobody saw stayed a root", byUuid.getValue("hidden between").parentUuid)
    }

    @Test
    fun one_edit_stamps_every_row_it_writes_with_the_same_instant() {
        // A device comparing two rows of one tree reads `updated_at` as the order of events; an operation that
        // stamped the head, the block's topic and the crossed positions with three separate clock reads would tell
        // it a story about which change came first that never happened.
        val topic = runBlocking { fixture.addGroup(userId, "grp-stamp") }
        row("other", position = 0, group = topic.uuid)
        val head = row("head", position = 1)
        row("child", parent = "head", position = 2)
        val anchor = row("anchor", position = 3, group = topic.uuid)
        fixture.timeBase.advance(9_000L)
        val stamp = fixture.timeBase.wall

        accepted(
            runBlocking { hierarchy.moveSubtreeUnder(head.id, newParentId = anchor.id, destinationGroupUuid = topic.uuid) }
        )

        val written = stored().filter { it.syncState == SyncState.PENDING }
        assertTrue("the edit wrote more than one row", written.size > 1)
        assertEquals("and every row it wrote carries the same instant", listOf(stamp), written.map { it.updatedAt }.distinct())
    }

    private companion object {
        const val STAMP = 1_700_000_000_000L
    }
}
