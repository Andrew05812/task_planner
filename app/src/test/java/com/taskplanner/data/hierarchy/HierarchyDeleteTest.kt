package com.taskplanner.data.hierarchy

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.taskplanner.data.local.TaskDao
import com.taskplanner.data.model.GoalTaskLink
import com.taskplanner.data.model.Reminder
import com.taskplanner.data.model.SyncState
import com.taskplanner.data.model.Task
import com.taskplanner.data.model.TrackingSession
import com.taskplanner.data.model.TrackingState
import com.taskplanner.data.model.WorkIntervalCorrection
import com.taskplanner.data.model.WorkSession
import com.taskplanner.data.hierarchy.HierarchyDeleteSummary
import com.taskplanner.data.repository.TaskHierarchyRepository
import com.taskplanner.testing.AppFixture
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Deleting a row that has rows hanging under it — the question Stage 16 has to answer in the same change that lets
 * a reader create one.
 *
 * Until an indent existed there was no path that orphans a subtree, so there was no path that had to say what
 * happens to it. Now there is, and the answer is a choice the reader makes, shown as two operations in the delete
 * dialog (`docs/HIERARCHY.md` records both, §6 draws them):
 *
 *  * **`PROMOTE_CHILDREN`** deletes the chosen row and re-hangs its immediate children on whatever held it — the
 *    topic, or the grandparent. A grandchild still hangs from its own parent, the rows keep their topic, their owner
 *    and their relative order: nothing is flattened to the margin because a line above it disappeared.
 *  * **`DELETE_SUBTREE`** soft-deletes the row *and every descendant*, so the history survives as tombstones and a
 *    second device can be told about it. No row of a reader's notebook is ever physically removed here.
 *
 * **What neither branch may do.** Stop a measurement quietly. A task being timed has an open interval whose seconds
 * are already spent, and deleting the row under it would either throw that work away or leave a timer running with
 * nothing to report to — so the request is refused, and stopping the timer is a separate action the reader takes.
 * And a refusal may not leave a half-tree: each refusal case below re-reads the database and compares every row,
 * link and reminder with the state before.
 *
 * Two more rules the tree inherits from the single-row delete that already existed, pinned here because a subtree
 * delete is where they would be lost: a plain row loses its reminders with it, inside the same transaction, so no
 * alarm can fire for a row the reader cannot reach — while an instance of a running series keeps them, because the
 * next instance is built from this row's content. And a block whose identities are not single-valued is refused
 * rather than guessed at, because "every descendant" is the set being deleted and a doubled `uuid` gives it two
 * answers.
 */
@RunWith(RobolectricTestRunner::class)
class HierarchyDeleteTest {

    private lateinit var fixture: AppFixture
    private lateinit var hierarchy: TaskHierarchyRepository
    private var userId: Long = 0

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        fixture = AppFixture(context)
        userId = runBlocking { fixture.signIn(email = "delete@example.com").id }
        hierarchy = TaskHierarchyRepository(
            fixture.database,
            fixture.taskDao,
            fixture.groupDao,
            fixture.trackingSessionDao,
            fixture.reminderDao,
            fixture.sessionRepository,
            fixture.timeBase
        )
    }

    @After
    fun tearDown() = fixture.close()

    private fun row(
        title: String,
        parent: String? = null,
        position: Int,
        group: String? = null,
        uuid: String = title,
        series: String? = null
    ): Task = runBlocking {
        val id = fixture.taskDao.insertTask(
            Task(
                title = title, description = "", uuid = uuid, userId = userId, groupId = group,
                parentUuid = parent, order = position, seriesUuid = series,
                syncState = SyncState.SYNCED, createdAt = STAMP, updatedAt = STAMP
            )
        )
        fixture.taskDao.getTaskById(id)!!
    }

    /** Every row the profile holds, tombstones included — a soft delete must not lose one. */
    private fun everyRow(): List<Task> = runBlocking { fixture.taskDao.getAllRowsOnce(userId) }

    private fun live(): List<Task> = runBlocking { fixture.taskDao.getRowsInStoredOrder(userId) }

    private fun rowsByName(): Map<String, Task> = everyRow().associateBy { it.title }

    /**
     * The titles of the rows in [rows] whose block is printed apart from them.
     *
     * Every hierarchy write in this app promises the same thing: a row's subtree sits immediately after it, so the
     * stored sequence and the drawn tree say the same shape. A row whose parent is not in [rows] is not judged —
     * this asks about the block a write made, not about rows another profile owns. The answer is the heads that
     * break the promise rather than a bare false, because a failure has to say which branch was left split.
     */
    private fun splitBranches(rows: List<Task>): List<String> {
        val split = ArrayList<String>()
        rows.forEach { head ->
            val block = TaskHierarchyPolicy.subtreeOf(rows, head).filterNot { it.id == head.id }
            if (block.isEmpty()) return@forEach
            val from = rows.indexOfFirst { it.id == head.id } + 1
            val window = rows.subList(from, minOf(from + block.size, rows.size))
            if (window.map { it.id }.toSet() != block.map { it.id }.toSet()) split.add(head.title)
        }
        return split
    }

    private fun reminderFor(task: Task, offset: Int, due: Long) = Reminder(
        uuid = Reminder.identity(task.uuid, offset),
        taskUuid = task.uuid,
        userId = userId,
        offsetMinutes = offset,
        triggerAt = due - offset * 60_000L,
        anchorAt = due,
        syncState = SyncState.SYNCED
    )

    private fun delete(task: Task, policy: HierarchyDeletePolicy?): HierarchyDeleteResult =
        runBlocking { hierarchy.deleteTask(task.id, policy) }

    private fun delete(task: Task): HierarchyDeleteResult = delete(task, null)

    @Test
    fun a_parent_delete_asked_without_a_policy_answers_with_the_choice_and_writes_nothing() {
        val head = row("head", position = 0)
        row("child", parent = "head", position = 1)
        row("grandchild", parent = "child", position = 2)
        val before = everyRow().map { listOf(it.title, it.parentUuid, it.isDeleted, it.syncState) }

        val outcome = delete(head)

        val confirm = outcome as? HierarchyDeleteResult.Confirm
        assertNotNull("a row with rows under it cannot be deleted without saying what happens to them: $outcome", confirm)
        assertEquals("the dialog names the rows hanging directly under it", 1, confirm?.childCount)
        assertEquals("and the size of the branch it would take with them", 3, confirm?.subtreeSize)
        assertEquals(
            "asking the question wrote nothing",
            before,
            everyRow().map { listOf(it.title, it.parentUuid, it.isDeleted, it.syncState) }
        )
    }

    @Test
    fun a_leaf_delete_never_asks_because_there_is_nothing_to_choose() {
        val leaf = row("leaf", position = 0)
        row("other", position = 1)

        val outcome = delete(leaf)

        assertTrue(
            "a row with nothing under it has exactly one delete, so the screen must not be made to ask: $outcome",
            outcome is HierarchyDeleteResult.Deleted
        )
        assertEquals("and the row went", listOf("other"), live().map { it.title })
    }

    @Test
    fun the_number_a_batch_is_asked_about_counts_each_row_once_even_where_two_branches_overlap() {
        val parent = row("parent", position = 0)
        val child = row("child", parent = "parent", position = 1)
        row("grandchild", parent = "child", position = 2)
        val leaf = row("leaf", position = 3)

        val one = runBlocking { hierarchy.deleteBatchSummary(listOf(parent.id)) }
        assertEquals("one of the asked-for rows holds something", 1, one.branchRows)
        assertEquals("one hangs directly under it", 1, one.children)
        assertEquals("and the branch it heads is three rows", 3, one.affectedRows)

        val batch = runBlocking { hierarchy.deleteBatchSummary(listOf(parent.id, child.id, leaf.id)) }
        assertEquals("two of the three selected rows hold a branch", 2, batch.branchRows)
        assertEquals(
            "and every row of the notebook is counted once, though the child sits inside its own branch and the " +
                "parent's — a select-all would otherwise be told a number larger than the notebook",
            4,
            batch.affectedRows
        )
        assertEquals("a leaf alone asks no question", 0, runBlocking { hierarchy.deleteBatchSummary(listOf(leaf.id)) }.branchRows)
        assertEquals(
            "a row this profile does not hold is not in the count at all",
            HierarchyDeleteSummary(0, 0, 0),
            runBlocking { hierarchy.deleteBatchSummary(listOf(-1L)) }
        )
    }

    @Test
    fun deleting_a_parent_hangs_its_children_on_what_held_the_parent() {
        val topic = runBlocking { fixture.addGroup(userId, "grp-work") }
        val grandparent = row("project", position = 0, group = topic.uuid)
        val parent = row("backend", parent = "project", position = 1, group = topic.uuid)
        val first = row("api", parent = "backend", position = 2, group = topic.uuid)
        val second = row("tests", parent = "backend", position = 3, group = topic.uuid)

        delete(parent, HierarchyDeletePolicy.PROMOTE_CHILDREN)

        val byTitle = rowsByName()
        assertEquals("the children now hang from the project", "project", byTitle.getValue("api").parentUuid)
        assertEquals("both of them", "project", byTitle.getValue("tests").parentUuid)
        assertEquals("the parent is a tombstone, not gone", true, byTitle.getValue("backend").isDeleted)
        assertEquals("and the children kept their topic", topic.uuid, byTitle.getValue("api").groupId)
        assertEquals("their owner", userId, byTitle.getValue("api").userId)
        assertEquals("their order among each other", 2, byTitle.getValue("api").order)
        assertEquals("and the row below it", 3, byTitle.getValue("tests").order)
        assertNotNull("the grandparent is untouched", byTitle["project"])
        assertNull("and it still hangs from nothing", byTitle.getValue("project").parentUuid)
        assertEquals("no row was lost", 4, everyRow().size)
        assertEquals("the live list lost exactly one", 3, live().size)
    }

    @Test
    fun promoting_a_roots_children_leaves_them_as_the_topics_own_rows() {
        val topic = runBlocking { fixture.addGroup(userId, "grp-topic") }
        val root = row("theme-root", position = 0, group = topic.uuid)
        val child = row("under-it", parent = "theme-root", position = 1, group = topic.uuid)

        val outcome = delete(root, HierarchyDeletePolicy.PROMOTE_CHILDREN) as HierarchyDeleteResult.Deleted

        val byTitle = rowsByName()
        assertNull("the child becomes the topic's own row", byTitle.getValue("under-it").parentUuid)
        assertEquals("and stays inside the topic it was in", topic.uuid, byTitle.getValue("under-it").groupId)
        assertEquals("one row was deleted, one was re-hung", listOf(root.id), outcome.deletedRows.map { it.id })
        assertEquals(listOf(child.id), outcome.promotedTaskIds)
    }

    @Test
    fun a_grandchild_stays_with_its_own_parent_when_the_row_above_it_goes() {
        row("l1", position = 0)
        val parent = row("l2", parent = "l1", position = 1)
        val child = row("l3", parent = "l2", position = 2)
        row("l4", parent = "l3", position = 3)

        val outcome = delete(parent, HierarchyDeletePolicy.PROMOTE_CHILDREN) as HierarchyDeleteResult.Deleted

        val byTitle = rowsByName()
        assertEquals("the deepest row kept its own parent", "l3", byTitle.getValue("l4").parentUuid)
        assertEquals("and that parent still hangs from the row above the one deleted", "l1", byTitle.getValue("l3").parentUuid)
        assertEquals("only the chosen row went", 3, live().size)
        assertEquals("and the answer names the one row that was re-hung", listOf(child.id), outcome.promotedTaskIds)
    }

    @Test
    fun promoting_a_head_lifts_each_child_together_with_the_rows_under_it() {
        val head = row("head", position = 0)
        row("first", parent = "head", position = 1)
        row("second", parent = "head", position = 2)
        row("under-first", parent = "first", position = 3)

        delete(head, HierarchyDeletePolicy.PROMOTE_CHILDREN)

        assertEquals(
            "a lifted row is read with the rows under it, not after its sibling",
            listOf("first", "under-first", "second"),
            live().map { it.title }
        )
        assertEquals("and the branch it makes is not printed apart from itself", emptyList<String>(), splitBranches(live()))
    }

    @Test
    fun a_promote_leaves_no_row_printed_before_its_own_parent_and_moves_no_neighbour() {
        row("before", position = 0)
        val head = row("head", position = 1)
        row("first", parent = "head", position = 2)
        row("second", parent = "head", position = 3)
        row("under-first", parent = "first", position = 4)
        row("after", position = 5)

        delete(head, HierarchyDeletePolicy.PROMOTE_CHILDREN)

        val after = live()
        assertEquals(
            "the lifted block reads as the head left it, and the rows beside it never moved",
            listOf("before", "first", "under-first", "second", "after"),
            after.map { it.title }
        )
        assertEquals("nothing in the profile is left split", emptyList<String>(), splitBranches(after))
        assertEquals(
            "and the neighbours kept their own positions, so the write is bounded by the block",
            listOf("before" to 0, "after" to 5),
            after.filter { it.title == "before" || it.title == "after" }.map { it.title to it.order }
        )
    }

    @Test
    fun a_promote_that_moves_nobody_writes_no_position() {
        val head = row("head", position = 0)
        row("child", parent = "head", position = 1)
        row("grandchild", parent = "child", position = 2)
        val before = live().filter { it.id != head.id }.map { listOf(it.title, it.order) }

        val outcome = delete(head, HierarchyDeletePolicy.PROMOTE_CHILDREN) as HierarchyDeleteResult.Deleted

        assertEquals(
            "a block that was already whole keeps every number it had",
            before,
            live().filter { it.id != head.id }.map { listOf(it.title, it.order) }
        )
        assertEquals("one row re-hung and one tombstone: no position was rewritten", 2, outcome.rowsWritten)
    }

    @Test
    fun deleting_a_subtree_takes_every_descendant_as_a_tombstone_and_nothing_else() {
        val topic = runBlocking { fixture.addGroup(userId, "grp-deep") }
        val other = row("untouched", position = 0, group = topic.uuid)
        val head = row("head", position = 1, group = topic.uuid)
        row("child", parent = "head", position = 2, group = topic.uuid)
        row("grandchild", parent = "child", position = 3, group = topic.uuid)

        val outcome = delete(head, HierarchyDeletePolicy.DELETE_SUBTREE) as HierarchyDeleteResult.Deleted

        val byTitle = rowsByName()
        assertEquals("the whole branch is marked, head first", listOf("head", "child", "grandchild"), outcome.deletedRows.map { it.title })
        listOf("head", "child", "grandchild").forEach { title ->
            assertEquals("$title is a tombstone", true, byTitle.getValue(title).isDeleted)
            assertEquals("$title is queued for a delete push", SyncState.DELETING, byTitle.getValue(title).syncState)
        }
        assertEquals("the sibling kept its state", SyncState.SYNCED, byTitle.getValue("untouched").syncState)
        assertEquals("and it is the only row left live", listOf(other.title), live().map { it.title })
        assertEquals("nothing physical was removed — history is not deleted, it is retired", 4, everyRow().size)
        assertEquals("the live list lost three", 1, live().size)
    }

    @Test
    fun a_deleted_branch_keeps_everything_but_the_live_flag_and_gains_a_date() {
        row("head", position = 0)
        row("child", parent = "head", position = 1)
        row("grandchild", parent = "child", position = 2)
        // A projection of the whole row, not only the shape: §9 asks about archive flags and `deletedAt` as well,
        // and a delete that flattened a branch's status would look like a successful one.
        val before = everyRow().associate { it.title to listOf(it.parentUuid, it.archived, it.status, it.dueDate, it.priority) }

        delete(rowsByName().getValue("head"), HierarchyDeletePolicy.DELETE_SUBTREE)

        val after = everyRow().associate { it.title to listOf(it.parentUuid, it.archived, it.status, it.dueDate, it.priority) }
        assertEquals(
            "the shape of the branch survives the tombstone — a wipe of these links would make the history " +
                "unreadable, and the topic key is the one column the existing delete rule clears",
            before,
            after
        )
        listOf("head", "child", "grandchild").forEach { title ->
            assertNotNull("$title carries the instant it was retired", rowsByName().getValue(title).deletedAt)
        }
    }

    @Test
    fun deleting_a_task_retires_its_reminders_and_leaves_the_promoted_childrens_alone() {
        val due = fixture.timeBase.wall + 86_400_000L
        val parent = row("parent", position = 0)
        val child = row("child", parent = "parent", position = 1)
        runBlocking {
            fixture.reminderDao.insertOrIgnore(reminderFor(parent, 30, due))
            fixture.reminderDao.insertOrIgnore(reminderFor(child, 15, due))
        }

        delete(parent, HierarchyDeletePolicy.PROMOTE_CHILDREN)

        val parentReminder = runBlocking { fixture.reminderDao.getLiveForTask(parent.uuid) }
        assertTrue("the deleted row's lead times are gone from the live set", parentReminder.isEmpty())
        val childReminder = runBlocking { fixture.reminderDao.getLiveForTask(child.uuid) }
        assertEquals("the child that was promoted keeps its own reminder", 1, childReminder.size)
        assertEquals("and that reminder is still armed against its own task", child.uuid, childReminder.first().taskUuid)
    }

    @Test
    fun a_subtree_delete_retires_the_reminders_of_every_row_in_it() {
        val due = fixture.timeBase.wall + 86_400_000L
        val head = row("head", position = 0)
        val child = row("child", parent = "head", position = 1)
        val grandChild = row("grandchild", parent = "child", position = 2)
        runBlocking {
            fixture.reminderDao.insertOrIgnore(reminderFor(head, 30, due))
            fixture.reminderDao.insertOrIgnore(reminderFor(child, 20, due))
            fixture.reminderDao.insertOrIgnore(reminderFor(grandChild, 10, due))
        }

        delete(head, HierarchyDeletePolicy.DELETE_SUBTREE)

        val stillArmed = runBlocking {
            listOf(head, child, grandChild).flatMap { fixture.reminderDao.getLiveForTask(it.uuid) }
        }
        assertEquals(
            "a row nobody can reach must not keep an alarm that will fire for it",
            emptyList<String>(),
            stillArmed.map { it.taskUuid }
        )
    }

    @Test
    fun a_task_being_measured_refuses_to_be_deleted_and_keeps_everything_where_it_was() {
        val parent = row("measured", position = 0)
        val child = row("helper", parent = "measured", position = 1)
        runBlocking {
            fixture.trackingSessionDao.insert(
                TrackingSession(
                    uuid = "open-session",
                    userId = userId,
                    taskId = parent.id,
                    taskUuid = parent.uuid,
                    taskTitle = parent.title,
                    state = TrackingState.RUNNING,
                    startedAt = fixture.timeBase.wall,
                    runningMarker = userId.toString()
                )
            )
        }
        val before = everyRow().map { listOf(it.title, it.parentUuid, it.groupId, it.isDeleted, it.syncState) }

        val outcome = delete(parent, HierarchyDeletePolicy.DELETE_SUBTREE)

        assertEquals(
            "an open measurement is not something a delete ends quietly",
            HierarchyDeleteRefusal.MEASUREMENT_RUNNING,
            (outcome as HierarchyDeleteResult.Refused).reason
        )
        val after = everyRow().map { listOf(it.title, it.parentUuid, it.groupId, it.isDeleted, it.syncState) }
        assertEquals("and the refusal left the tree alone", before, after)
        assertEquals("the child was not promoted either", "measured", rowsByName().getValue("helper").parentUuid)
    }

    @Test
    fun a_measurement_running_on_a_descendant_refuses_the_whole_subtree_delete() {
        val head = row("head", position = 0)
        row("child", parent = "head", position = 1)
        val grandChild = rowsByName().getValue("child")
        runBlocking {
            fixture.trackingSessionDao.insert(
                TrackingSession(
                    uuid = "deep-session",
                    userId = userId,
                    taskId = grandChild.id,
                    taskUuid = grandChild.uuid,
                    taskTitle = grandChild.title,
                    state = TrackingState.PAUSED,
                    startedAt = fixture.timeBase.wall
                )
            )
        }

        val outcome = delete(head, HierarchyDeletePolicy.DELETE_SUBTREE)

        assertTrue(
            "a branch with a measurement anywhere in it is refused by name: $outcome",
            outcome is HierarchyDeleteResult.Refused
        )
        assertEquals("nothing was deleted", 2, live().size)
    }

    @Test
    fun a_promote_delete_marks_the_rehung_rows_for_a_push_and_the_deleted_row_as_a_tombstone() {
        val parent = row("parent", position = 0)
        val child = row("child", parent = "parent", position = 1)

        delete(parent, HierarchyDeletePolicy.PROMOTE_CHILDREN)

        val byTitle = rowsByName()
        assertEquals("the row that moved is pending", SyncState.PENDING, byTitle.getValue("child").syncState)
        assertEquals("and carries the clock of the edit", fixture.timeBase.wall, byTitle.getValue("child").updatedAt)
        assertEquals("the row that went is a tombstone", SyncState.DELETING, byTitle.getValue("parent").syncState)
        assertNull("its topic is cleared by the existing delete rule", byTitle.getValue("parent").groupId)
        assertEquals("the child keeps its own topic", null, byTitle.getValue("child").groupId)
    }

    @Test
    fun a_row_that_is_not_live_in_this_profile_is_refused_rather_than_invented() {
        val strangerId = runBlocking { fixture.signIn(name = "Stranger", email = "stranger@example.com").id }
        val stranger = runBlocking {
            fixture.taskDao.insertTask(
                Task(title = "theirs", description = "", uuid = "theirs", userId = strangerId, order = 0)
            )
            fixture.taskDao.getAllRowsOnce(strangerId).single()
        }
        runBlocking { fixture.switchTo(fixture.userDao.getUserById(userId)!!) }

        val outcome = delete(stranger, HierarchyDeletePolicy.DELETE_SUBTREE)

        assertEquals(
            "a row of another account cannot be deleted through this one",
            HierarchyDeleteRefusal.ROW_MISSING,
            (outcome as HierarchyDeleteResult.Refused).reason
        )
        assertEquals(
            "and the foreign row is untouched",
            false,
            runBlocking { fixture.taskDao.getAllRowsOnce(strangerId).single().isDeleted }
        )
        assertEquals("my own profile still has nothing of mine to delete", 0, live().size)
    }

    @Test
    fun a_row_that_is_an_instance_of_a_series_keeps_its_lead_times_for_the_next_instance() {
        val due = fixture.timeBase.wall + 86_400_000L
        val instance = row("instance", position = 0, series = "series-1")
        runBlocking { fixture.reminderDao.insertOrIgnore(reminderFor(instance, 30, due)) }

        delete(instance, HierarchyDeletePolicy.PROMOTE_CHILDREN)

        assertEquals(
            "a series is still running, so its lead times are not this delete's to retire — the next instance is " +
                "built from this row's content, reminders included",
            1,
            runBlocking { fixture.reminderDao.getLiveForTask(instance.uuid) }.size
        )
        assertEquals("and the instance row itself is a tombstone", true, rowsByName().getValue("instance").isDeleted)
    }

    @Test
    fun a_doubled_identity_refuses_the_delete_rather_than_choosing_which_rows_the_subtree_holds() {
        val head = row("head", position = 0)
        row("child", parent = "head", position = 1)
        // Two live rows claiming one identity — what a pull or a hand-edited backup can leave behind, since
        // `tasks.uuid` carries no unique index (Stage 16 §4 records why one was not added blind).
        row("twin", parent = "child", position = 2, uuid = "child")
        val before = everyRow().map { listOf(it.title, it.parentUuid, it.isDeleted, it.syncState) }

        val outcome = delete(head, HierarchyDeletePolicy.DELETE_SUBTREE)

        assertEquals(
            "with one identity held twice, \"everything under it\" has two answers, and a delete that picked one " +
                "would retire rows nobody named",
            HierarchyDeleteRefusal.AMBIGUOUS_IDENTITY,
            (outcome as HierarchyDeleteResult.Refused).reason
        )
        val after = everyRow().map { listOf(it.title, it.parentUuid, it.isDeleted, it.syncState) }
        assertEquals("and the refusal wrote nothing", before, after)
        assertEquals("no row went", 3, live().size)
    }

    @Test
    fun a_subtree_delete_leaves_the_branches_measured_time_and_goal_link_where_they_are() {
        val parent = row("parent", position = 0)
        val child = row("child", parent = "parent", position = 1)
        runBlocking {
            fixture.workSessionDao.insert(
                WorkSession(
                    uuid = "ws-1", userId = userId, trackingSessionUuid = "ts-1", taskId = child.id,
                    taskUuid = child.uuid, startedAt = STAMP, endedAt = STAMP + 60_000L,
                    durationSeconds = 60L, localDay = 0L, utcOffsetMinutes = 0
                )
            )
            fixture.workSessionDao.insertCorrection(
                WorkIntervalCorrection(
                    uuid = "corr-1", intervalUuid = "ws-1", userId = userId,
                    oldStartedAt = STAMP, newStartedAt = STAMP + 60_000L
                )
            )
            fixture.goalDao.insertLink(GoalTaskLink(goalUuid = "goal-1", taskUuid = child.uuid, userId = userId))
        }
        val before = listOf(rowCount("work_sessions"), rowCount("work_interval_corrections"), rowCount("goal_tasks"))
        assertTrue("the history was really there to lose", before.first() > 0L)

        delete(parent, HierarchyDeletePolicy.DELETE_SUBTREE)

        assertEquals(
            "a branch is retired, not erased: the intervals, the corrections made to them and the goals that name " +
                "the rows all outlive the card, which is why no delete path here touches those tables",
            before,
            listOf(rowCount("work_sessions"), rowCount("work_interval_corrections"), rowCount("goal_tasks"))
        )
        assertEquals(
            "and the measurement is still readable as the retired row's own — a tombstone with no history beside it " +
                "is how a reader's tracked time disappears from a report",
            listOf("child"),
            runBlocking { fixture.workSessionDao.getIntervalsOf("ts-1") }.map { it.taskUuid }
        )
    }

    @Test
    fun a_delete_that_fails_halfway_leaves_no_promoted_child_behind() {
        val parent = row("parent", position = 0)
        row("child", parent = "parent", position = 1)
        // The storage is the real Room dao; one call is made to fail so the failure lands *between* the two writes
        // this delete performs — the children re-hung first, the row retired second. A mock would let the whole
        // transaction be fake and prove nothing about SQLite's rollback.
        val failing = object : TaskDao by fixture.taskDao {
            override suspend fun softDeleteTasks(ids: List<Long>, now: Long) {
                throw IllegalStateException("the write was refused")
            }
        }
        val halfBuilt = TaskHierarchyRepository(
            fixture.database, failing, fixture.groupDao, fixture.trackingSessionDao, fixture.reminderDao,
            fixture.sessionRepository, fixture.timeBase
        )

        assertThrows(IllegalStateException::class.java) {
            runBlocking { halfBuilt.deleteTask(parent.id, HierarchyDeletePolicy.PROMOTE_CHILDREN) }
        }

        val byTitle = rowsByName()
        assertEquals(
            "the promotion rolled back with the failure, so the branch is exactly where the reader left it",
            "parent",
            byTitle.getValue("child").parentUuid
        )
        assertEquals("and nothing was retired", false, byTitle.getValue("parent").isDeleted)
        assertEquals("the whole tree is still live", 2, live().size)
    }

    /** How many rows a table holds — the history a delete must leave alone is counted, not assumed. */
    private fun rowCount(table: String): Long = runBlocking {
        fixture.database.openHelper.writableDatabase.query("SELECT COUNT(*) FROM $table").use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }
    }

    private companion object {
        const val STAMP = 1_700_000_000_000L
    }
}
