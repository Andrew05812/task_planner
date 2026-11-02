package com.taskplanner.data.hierarchy

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.taskplanner.data.model.SyncState
import com.taskplanner.data.model.Task
import com.taskplanner.data.repository.TaskHierarchyRepository
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
 * Hiding a branch without touching it — Stage 16 §7's per-task collapse.
 *
 * `TaskGroup.collapsed` already lets a reader fold a whole topic away, and the state is real: it lives in Room,
 * travels to the other device, rides the backup, and survives a profile switch, because it is a column on the row.
 * A nested task had no such control, which is the half of the tree a reader cannot manage: an imported notebook of
 * four levels is one long scroll, and folding a branch was only possible by hiding every topic under it.
 *
 * **The rule this pins down is that collapsing is a view state, not an edit.** `children_collapsed` changes what is
 * drawn and nothing else — no parent link, no topic, no position, no status. That is what makes it safe to store on
 * the row rather than in a ViewModel: a reader who collapses a branch, syncs, restores a backup and re-signs in is
 * looking at the same tree they left. Every shape test below re-reads the stored rows and compares them with what
 * was there, with the fold's own two stamps as the only intended difference.
 *
 * **A row with nothing under it cannot be collapsed** (`NO_CHILDREN`), because a control over zero hidden rows is a
 * chevron that lies about the reader's own notebook — and §7 says the field must not be added only to exist. The
 * hidden set is computed over the profile's rows rather than the screen's, so an ancestor folded two levels up still
 * hides what is under it even when a filter dropped the row in between; the row that has no identity at all is not a
 * parent, which is the same trap [TaskHierarchyPolicy.subtreeOf] refuses.
 */
@RunWith(RobolectricTestRunner::class)
class HierarchyCollapseTest {

    private lateinit var fixture: AppFixture
    private lateinit var hierarchy: TaskHierarchyRepository
    private var userId: Long = 0

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        fixture = AppFixture(context)
        userId = runBlocking { fixture.signIn(email = "collapse@example.com").id }
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
        collapsed: Boolean = false,
        uuid: String = title
    ): Task = runBlocking {
        val id = fixture.taskDao.insertTask(
            Task(
                title = title, description = "", uuid = uuid, userId = userId, groupId = group,
                parentUuid = parent, order = position, childrenCollapsed = collapsed,
                syncState = SyncState.SYNCED, createdAt = STAMP, updatedAt = STAMP
            )
        )
        fixture.taskDao.getTaskById(id)!!
    }

    private fun stored(): List<Task> = runBlocking { fixture.taskDao.getAllRowsOnce(userId).sortedBy { it.id } }

    private fun byTitle(): Map<String, Task> = stored().associateBy { it.title }

    /** The shape of the tree, without the two columns a deliberate edit is expected to move. */
    private fun shape(): List<List<Any?>> =
        stored().map { listOf(it.title, it.uuid, it.parentUuid, it.groupId, it.order, it.status, it.archived) }

    private fun toggle(task: Task): HierarchyCollapseResult = runBlocking { hierarchy.toggleBranchCollapse(task.id) }

    /** The titles that would be drawn for the reader: every row except those an ancestor folded away. */
    private fun drawn(rows: List<Task>): List<String> {
        val away = TaskHierarchyPolicy.collapsedAwayIds(rows)
        return rows.filter { it.id !in away }.map { it.title }
    }

    @Test
    fun collapsing_a_branch_writes_one_column_and_stamps_the_row_for_a_push() {
        val topic = runBlocking { fixture.addGroup(userId, "grp-collapse") }
        val parent = row("parent", position = 0, group = topic.uuid)
        row("child", parent = "parent", position = 1, group = topic.uuid)
        val before = shape()

        val outcome = toggle(parent)

        val folded = byTitle().getValue("parent")
        assertEquals("the row is now folded", true, folded.childrenCollapsed)
        assertEquals("and the fold is stamped, not silent", SyncState.PENDING, folded.syncState)
        assertEquals("with the clock of the edit", fixture.timeBase.wall, folded.updatedAt)
        val changed = outcome as HierarchyCollapseResult.Changed
        assertEquals("the answer says what it hides", 1, changed.hiddenDescendants)
        assertEquals("and that one row was written", 1, changed.rowsWritten)
        assertEquals("no other column of any row moved", before, shape())
    }

    @Test
    fun collapsing_twice_expands_and_the_second_write_reports_the_state_it_left() {
        val parent = row("parent", position = 0)
        row("child", parent = "parent", position = 1)

        toggle(parent)
        val opened = toggle(parent) as HierarchyCollapseResult.Changed

        assertEquals("the branch is open again", false, byTitle().getValue("parent").childrenCollapsed)
        assertEquals("and the answer says so", false, opened.collapsed)
        assertEquals("with the same one row rewritten", 1, opened.rowsWritten)
    }

    @Test
    fun a_row_with_nothing_under_it_refuses_to_be_collapsed_and_writes_nothing() {
        val leaf = row("leaf", position = 0)
        row("neighbour", position = 1)
        val before = shape()

        val outcome = toggle(leaf)

        assertEquals(
            "a chevron over zero hidden rows would lie about the notebook",
            HierarchyCollapseRefusal.NO_CHILDREN,
            (outcome as HierarchyCollapseResult.Refused).reason
        )
        assertEquals("nothing was stored", false, byTitle().getValue("leaf").childrenCollapsed)
        assertEquals("and the profile is exactly as it was", before, shape())
    }

    @Test
    fun a_folded_branch_hides_every_descendant_and_keeps_the_row_itself_on_the_list() {
        val head = row("head", position = 0)
        row("child", parent = "head", position = 1)
        row("grandchild", parent = "child", position = 2)
        row("outside", position = 3)

        toggle(head)

        assertEquals(
            "the head stays where the reader left it and its branch goes",
            listOf("head", "outside"),
            drawn(stored())
        )
    }

    @Test
    fun a_fold_two_levels_up_hides_what_is_under_it_even_when_a_filter_drops_the_row_in_between() {
        row("head", position = 0, collapsed = true)
        row("child", parent = "head", position = 1)
        row("grandchild", parent = "child", position = 2)

        val profile = stored()
        val away = TaskHierarchyPolicy.collapsedAwayIds(profile)
        val filtered = profile.filterNot { it.title == "child" }

        assertEquals("the fold reaches the grandchild through the row in between", 2, away.size)
        assertEquals(
            "and a filter that drops the middle row cannot uncover it, because the hidden set is measured over the " +
                "profile rather than over whatever rows the screen was left holding",
            listOf("head"),
            filtered.filter { it.id !in away }.map { it.title }
        )
    }

    @Test
    fun the_count_a_folded_row_reports_is_the_whole_branch_not_the_rows_under_it_alone() {
        val head = row("head", position = 0)
        row("child", parent = "head", position = 1)
        row("grandchild", parent = "child", position = 2)
        row("sibling", parent = "head", position = 3)

        val outcome = toggle(head) as HierarchyCollapseResult.Changed

        assertEquals("three rows would go away, though only two hang directly under it", 3, outcome.hiddenDescendants)
    }

    @Test
    fun a_row_inside_another_ancestors_hidden_branch_can_still_be_folded_or_opened() {
        row("head", position = 0, collapsed = true)
        val child = row("child", parent = "head", position = 1)
        row("grandchild", parent = "child", position = 2)

        val outcome = toggle(child) as HierarchyCollapseResult.Changed

        assertEquals("the state is the row's own", true, byTitle().getValue("child").childrenCollapsed)
        assertEquals("and its count is its own branch", 1, outcome.hiddenDescendants)
        assertEquals(
            "with the outer fold still holding everything away",
            listOf("head"),
            drawn(stored())
        )
    }

    @Test
    fun a_blank_identity_is_never_a_parent_whose_fold_sweeps_up_unrelated_rows() {
        // `tasks.uuid` defaults to '' and a pull can write an empty `parent_uuid`, so "the rows whose parent is the
        // empty string" is a bucket of unrelated legacy rows rather than one branch.
        row("blank-parent", parent = "", position = 0, collapsed = true, uuid = "blank-parent")
        row("also-blank", parent = "", position = 1, uuid = "")
        row("no-link", position = 2, uuid = "")

        assertEquals(
            "a blank link names no branch, so nothing is hidden by it",
            listOf("blank-parent", "also-blank", "no-link"),
            drawn(stored())
        )
    }

    @Test
    fun a_fold_writes_nothing_to_the_rows_it_hides() {
        val head = row("head", position = 0)
        row("child", parent = "head", position = 1)
        row("grandchild", parent = "child", position = 2)
        val before = shape()

        toggle(head)

        assertEquals("collapsing is a view state: the reader's data is the data they had", before, shape())
        assertEquals(
            "and a hidden row is not queued for a push either",
            SyncState.SYNCED,
            byTitle().getValue("child").syncState
        )
    }

    @Test
    fun a_doubled_identity_refuses_the_fold_because_which_branch_it_hides_has_two_answers() {
        val head = row("head", position = 0)
        row("child", parent = "head", position = 1)
        val twin = row("twin", parent = "head", position = 2, uuid = "twin")
        runBlocking { fixture.taskDao.updateTask(twin.copy(uuid = "child")) }
        val before = shape()

        val outcome = toggle(head)

        assertEquals(
            "the hidden set is named by walking parent links, so one identity held twice makes the fold ambiguous",
            HierarchyCollapseRefusal.AMBIGUOUS_IDENTITY,
            (outcome as HierarchyCollapseResult.Refused).reason
        )
        assertEquals("and the refusal stored nothing", false, byTitle().getValue("head").childrenCollapsed)
        assertEquals("with the rows where they were", before, shape())
    }

    @Test
    fun a_row_of_another_profile_is_refused_rather_than_folded_through_this_one() {
        val strangerId = runBlocking { fixture.signIn(name = "Stranger", email = "stranger-collapse@example.com").id }
        val strangerRow = runBlocking {
            fixture.taskDao.insertTask(
                Task(title = "theirs", description = "", uuid = "theirs", userId = strangerId, order = 0)
            )
            fixture.taskDao.insertTask(
                Task(
                    title = "theirs-child", description = "", uuid = "theirs-child", userId = strangerId,
                    parentUuid = "theirs", order = 1
                )
            )
            fixture.taskDao.getAllRowsOnce(strangerId).first { it.title == "theirs" }
        }
        runBlocking { fixture.switchTo(fixture.userDao.getUserById(userId)!!) }

        val outcome = toggle(strangerRow)

        assertEquals(
            "a row of another account is not this profile's to fold",
            HierarchyCollapseRefusal.ROW_MISSING,
            (outcome as HierarchyCollapseResult.Refused).reason
        )
        assertEquals(
            "and the foreign row kept its own state",
            false,
            runBlocking {
                fixture.taskDao.getAllRowsOnce(strangerId).first { it.title == "theirs" }.childrenCollapsed
            }
        )
        assertTrue("my own profile holds nothing of mine to fold", stored().isEmpty())
    }

    @Test
    fun the_fold_is_a_column_on_the_row_so_a_later_read_finds_it_again() {
        val parent = row("parent", position = 0)
        row("child", parent = "parent", position = 1)

        toggle(parent)

        assertEquals(
            "the value is stored, not held by a screen: a fresh read of the profile reports it",
            true,
            runBlocking { fixture.taskDao.getTaskById(parent.id) }!!.childrenCollapsed
        )
    }

    private companion object {
        const val STAMP = 1_700_000_000_000L
    }
}
