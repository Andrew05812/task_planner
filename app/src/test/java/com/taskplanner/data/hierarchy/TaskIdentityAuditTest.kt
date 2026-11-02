package com.taskplanner.data.hierarchy

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.taskplanner.data.model.SyncState
import com.taskplanner.data.model.Task
import com.taskplanner.testing.AppFixture
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Whether a parent link can be trusted, measured against a real database.
 *
 * `tasks.parent_uuid` is a plain column with no foreign key, and the identity it points at is `tasks.uuid`, which
 * has no unique index either — `@ColumnInfo(defaultValue = "''")` makes the empty string a legal value, and
 * `TaskDao.getTaskByUuid` is a `LIMIT 1` over no owner. Stage 16 §4 asks what that costs a tree, and the answer this
 * file pins is a rule rather than a hope: **a move is allowed only when the block it would carry is knowable.**
 *
 * **What the device holds, measured 2026-11-02.** The database the emulator's app carries (`user_version` 17, 58 task
 * rows over 17 profile ids) has zero empty uuids, zero duplicate uuids inside a profile, zero uuids shared across
 * profiles and zero parent links at all — it is instrumented-test residue, and `users` is empty, so no row here is
 * reachable by a signed-in reader. What it does show is the shape of the hazard one table over: eight reminder rows
 * whose `task_uuid` matches nothing in `tasks`. A link without a foreign key does dangle in this app's real storage,
 * so the hierarchy code resolves it owner-scoped and refuses what it cannot determine, instead of trusting the
 * column it was handed.
 *
 * **Why no unique index was added.** `UNIQUE(user_id, uuid)` on non-empty uuids would make the parent unambiguous by
 * construction, and on the data above it would build cleanly. It cannot be shipped on that evidence: the migration
 * runs on databases nobody here has measured, and a `CREATE UNIQUE INDEX` that meets a duplicate fails the migration
 * — which leaves the reader with an app that will not open, the one outcome worse than an ambiguous parent. The
 * blocker is concrete (no user database available to audit), so the guarantee moves into the write path instead:
 * an ambiguous parent is refused by name, and every read that chooses a parent is scoped to the signed-in profile.
 */
@RunWith(RobolectricTestRunner::class)
class TaskIdentityAuditTest {

    private lateinit var fixture: AppFixture
    private lateinit var hierarchy: com.taskplanner.data.repository.TaskHierarchyRepository
    private var userId: Long = 0

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        fixture = AppFixture(context)
        userId = runBlocking { fixture.signIn(email = "identity@example.com").id }
        hierarchy = com.taskplanner.data.repository.TaskHierarchyRepository(
            fixture.database, fixture.taskDao, fixture.groupDao, fixture.sessionRepository, fixture.timeBase
        )
    }

    @After
    fun tearDown() = fixture.close()

    private fun row(
        title: String,
        uuid: String = title,
        parent: String? = null,
        position: Int,
        owner: Long = userId,
        group: String? = null
    ): Task = runBlocking {
        val id = fixture.taskDao.insertTask(
            Task(
                title = title, description = "", uuid = uuid, userId = owner, groupId = group,
                parentUuid = parent, order = position, syncState = SyncState.SYNCED
            )
        )
        fixture.taskDao.getTaskById(id)!!
    }

    private fun stored(forUser: Long = userId) = runBlocking { fixture.taskDao.getRowsInStoredOrder(forUser) }

    private fun move(what: Task, anchor: Task, after: Boolean) =
        runBlocking { hierarchy.moveWithinSiblings(what.id, anchor.id, after) }

    @Test
    fun a_head_whose_identity_two_rows_claim_has_an_unknowable_subtree_and_is_refused() {
        // Both rows carry uuid "dup", so "everything under dup" is a question with two answers: either row's
        // children would travel, and the app cannot tell which reader's subtree it is moving.
        val firstHead = row("head one", uuid = "dup", position = 0)
        row("head two", uuid = "dup", position = 1)
        row("child of the first", parent = "dup", position = 2)
        val sibling = row("root three", position = 3)

        val outcome = move(firstHead, sibling, after = true)

        assertEquals(
            "a move that cannot name the block it carries is refused rather than guessed",
            HierarchyMoveRefusal.AMBIGUOUS_PARENT,
            (outcome as HierarchyMoveResult.Refused).reason
        )
        assertEquals(
            "and the refusal writes nothing, so the reader's list stays where it was",
            listOf("head one", "head two", "child of the first", "root three"),
            stored().map { it.title }
        )
        assertEquals("nothing was reparented by the attempt", "dup", stored()[2].parentUuid)
    }

    @Test
    fun an_ambiguous_parent_still_lets_its_children_reorder_among_themselves() {
        // The refusal above is narrow on purpose. Two rows hanging from an identity two parents claim are still
        // siblings of each other, and reordering them needs no answer about who their parent is. Freezing the whole
        // profile because one uuid is doubled would turn a data defect into a reader who cannot use their list.
        val childOne = row("kid one", uuid = "k1", parent = "dup", position = 0)
        val childTwo = row("kid two", uuid = "k2", parent = "dup", position = 1)
        row("parent copy", uuid = "dup", position = 2)

        val outcome = accepted(move(childTwo, childOne, after = false))

        assertEquals("the block is the row the reader grabbed", listOf(childTwo.id), outcome.movedTaskIds)
        assertEquals(
            "and the stored order says so",
            listOf("kid two", "kid one", "parent copy"),
            stored().map { it.title }
        )
        assertEquals("no parent link was rewritten", "dup", stored()[0].parentUuid)
        assertEquals("nor the second child's", "dup", stored()[1].parentUuid)
    }

    @Test
    fun a_parent_identity_is_resolved_inside_the_signed_in_profile_only() {
        val stranger = runBlocking { fixture.signIn(name = "Stranger", email = "stranger@example.com").id }
        row("their head", uuid = "shared", position = 0, owner = stranger)
        row("their child", parent = "shared", position = 1, owner = stranger)
        runBlocking { fixture.switchTo(fixture.userDao.getUserById(userId)!!) }
        val myHead = row("my head", uuid = "shared", position = 0)
        val mySibling = row("my other", position = 1)

        val outcome = accepted(move(myHead, mySibling, after = true))

        assertEquals(
            "the block holds this profile's rows and nobody else's — another account's child sharing the uuid stays put",
            listOf(myHead.id),
            outcome.movedTaskIds
        )
        assertEquals(listOf("my other", "my head"), stored().map { it.title })
        assertEquals(
            "and the other profile keeps its own tree",
            listOf("their head", "their child"),
            stored(forUser = stranger).map { it.title }
        )
        assertEquals("their child keeps its parent", "shared", stored(forUser = stranger)[1].parentUuid)
    }

    @Test
    fun a_child_whose_parent_row_is_a_tombstone_keeps_its_link_and_its_place() {
        // The device measurement above is this case: links to rows that no longer exist. Two children of a deleted
        // parent are still siblings of each other, so the reorder has an answer; what it must not do is invent a new
        // parent, clear the dangling one, or freeze the pair because the row they hang from is gone.
        val parent = row("gone", position = 0)
        val childOne = row("orphan one", parent = "gone", position = 1)
        val childTwo = row("orphan two", parent = "gone", position = 2)
        runBlocking { fixture.taskDao.softDeleteTasks(listOf(parent.id)) }

        val outcome = accepted(move(childTwo, childOne, after = false))

        assertEquals(
            "the two live children reordered, and only the row grabbed travelled",
            listOf(childTwo.id),
            outcome.movedTaskIds
        )
        assertEquals(listOf("orphan two", "orphan one"), stored().map { it.title })
        stored().forEach { live ->
            assertEquals(
                "the link to the deleted row is left exactly as it was — clearing it would rewrite history nobody asked about",
                "gone",
                live.parentUuid
            )
        }
        assertEquals("the tombstone itself is out of the sequence", 2, stored().size)
    }

    @Test
    fun blank_links_are_roots_and_a_blank_identity_adopts_nothing() {
        // `parent_uuid = ''` is what a pull or a legacy row writes for "no parent", and `uuid = ''` is the column's
        // own default. Treated as a real identity they would make every blank-parented row of the profile the child
        // of every blank-uuid row: one drag, a dozen unrelated rows.
        val first = row("blank one", uuid = "", parent = "", position = 0)
        val second = row("blank two", uuid = "", parent = "", position = 1)
        val third = row("blank three", uuid = "", parent = "", position = 2)

        val outcome = accepted(move(third, first, after = false))

        assertEquals(
            "the block is the one row the reader grabbed, not every row sharing the blank identity",
            listOf(third.id),
            outcome.movedTaskIds
        )
        assertEquals(
            "so the three unfiled rows reorder among themselves like any other siblings",
            listOf("blank three", "blank one", "blank two"),
            stored().map { it.title }
        )
        assertEquals("and the blank links survive the move untouched", "", stored()[1].parentUuid)
        assertEquals("as does the second one", "", stored()[2].parentUuid)
        assertEquals("the rows are still this profile's", listOf(userId, userId, userId), stored().map { it.userId })
        assertEquals("with their blank identities intact", listOf("", "", ""), stored().map { it.uuid })
        assertTrue("nothing here belongs to another account", stored().all { it.userId == first.userId })
        assertTrue("and the row the reader left behind kept its own link", stored()[2].id == second.id)
    }

    private fun accepted(outcome: HierarchyMoveResult): HierarchyMoveResult.Accepted {
        assertTrue("the move should have been accepted, and it was not: $outcome", outcome is HierarchyMoveResult.Accepted)
        return outcome as HierarchyMoveResult.Accepted
    }
}
