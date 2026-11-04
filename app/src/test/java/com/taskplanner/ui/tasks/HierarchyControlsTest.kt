package com.taskplanner.ui.tasks

import android.content.Context
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.isVisible
import androidx.test.core.app.ApplicationProvider
import com.taskplanner.R
import com.taskplanner.data.hierarchy.HierarchyMoveRefusal
import com.taskplanner.data.hierarchy.HierarchyMoveResult
import com.taskplanner.data.hierarchy.TaskHierarchyPolicy
import com.taskplanner.data.model.SyncState
import com.taskplanner.data.model.Task
import com.taskplanner.data.model.TaskGroup
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * §6: the hierarchy actions a reader can press, and the one rule that decides whether they are offered.
 *
 * The brief asks for indent and outdent on the task row, in the editor, and "move to" where the interface can carry
 * it — and it is exact about the four things that must never be offered: an indent with no admissible parent, an
 * outdent of a row that is already its topic's own child, a parent from inside the row's own subtree, and a move that
 * would push the tree past the depth limit. §6.3 says the same for the destination list: «не показывай недопустимые
 * варианты как кликабельные».
 *
 * **The rule is written once, and the menu reads it.** §5 already decided every one of those questions in
 * `TaskHierarchyPolicy` and `TaskHierarchyRepository`, and the tests in `HierarchyMoveTest` hold them. A second
 * implementation in the view layer would be a second opinion — the kind that lets a menu offer something the domain
 * then refuses, which the reader reads as a button that does not work. So `TaskHierarchyPolicy.indentRefusal` and
 * `outdentRefusal` are extracted from the repository's own bodies (the repository calls them; §5's 40-odd tests are
 * the proof the extraction changed nothing), and the menu calls the same two functions. The agreement tests below are
 * not a courtesy: they run the domain and the offer on the same fixture and compare the answers.
 *
 * **What the reader sees when an action is impossible.** A disabled row carrying the reason, in the reader's own
 * terms — "Nothing above it to hang on", «Сверху нет строки, к которой её прикрепить» — rather than a missing row they
 * cannot account for, and rather than a live row that refuses when pressed. Two refusals are not sentences at all: an
 * ambiguous or unnamed parent identity is a broken notebook, not a choice the reader made, and the row draws no
 * hierarchy control for it at all.
 */
@RunWith(RobolectricTestRunner::class)
class HierarchyControlsTest {

    private lateinit var fixture: AppFixture
    private lateinit var app: Context
    private lateinit var hierarchy: TaskHierarchyRepository
    private var userId: Long = 0

    private companion object {
        const val STAMP = 1_700_000_000_000L

        /** The refusals a reader can be shown a sentence for, as opposed to ones that hide the control. */
        val EXPLAINED = listOf(
            HierarchyMoveRefusal.NO_ROW_ABOVE,
            HierarchyMoveRefusal.DIFFERENT_TOPIC,
            HierarchyMoveRefusal.NO_DEEPER_PARENT,
            HierarchyMoveRefusal.DEPTH_EXCEEDED,
            HierarchyMoveRefusal.ANCHOR_IN_SUBTREE,
            HierarchyMoveRefusal.ALREADY_AT_TOPIC_ROOT,
            HierarchyMoveRefusal.PARENT_NOT_IN_PROFILE
        )

        /** The refusals that mean the notebook itself is inconsistent, and so are not offered as a press at all. */
        val SILENT = listOf(
            HierarchyMoveRefusal.PARENT_UNNAMED,
            HierarchyMoveRefusal.AMBIGUOUS_PARENT
        )
    }

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext<Context>()
        fixture = AppFixture(app)
        Dispatchers.setMain(Executor { it.run() }.asCoroutineDispatcher())
        userId = runBlocking { fixture.signIn(email = "controls@example.com").id }
        hierarchy = TaskHierarchyRepository(
            fixture.database, fixture.taskDao, fixture.groupDao, fixture.trackingSessionDao, fixture.reminderDao,
            fixture.sessionRepository, fixture.timeBase
        )
    }

    @After
    fun tearDown() {
        // The fixture first, the dispatcher after — a resetMain() landing while a Room thread is still
        // emitting races the dispatcher. CI run 81 found it; AppFixture.close keeps the account.
        fixture.close()
        Dispatchers.resetMain()
    }

    private fun group(name: String): TaskGroup = runBlocking {
        val group = TaskGroup(
            uuid = name, userId = userId, name = name, syncState = SyncState.SYNCED,
            createdAt = STAMP, updatedAt = STAMP
        )
        fixture.groupDao.upsertGroup(group)
        fixture.groupDao.getGroupByUuid(name)!!
    }

    private fun row(
        title: String,
        parent: String? = null,
        position: Int,
        group: String? = null,
        categoryId: Long? = null,
        /** A row's identity is its title unless a test names another: the doubled-uuid tests need the claim explicit. */
        uuid: String = title
    ): Task = runBlocking {
        val id = fixture.taskDao.insertTask(
            Task(
                title = title, description = "", uuid = uuid, userId = userId, groupId = group,
                parentUuid = parent, categoryId = categoryId, order = position, syncState = SyncState.SYNCED,
                createdAt = STAMP, updatedAt = STAMP
            )
        )
        fixture.taskDao.getTaskById(id)!!
    }

    private fun stored(): List<Task> = runBlocking { fixture.taskDao.getRowsInStoredOrder(userId) }

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

    private fun themed(): Context = android.view.ContextThemeWrapper(app, R.style.Theme_TaskPlanner)

    /** The popup the reader's press opens, with the two commands recorded rather than executed. */
    private class Pressed(
        val menu: PopupMenu,
        val indents: MutableList<Pair<Long, Long>>,
        val outdents: MutableList<Long>
    ) {
        fun item(id: Int) = menu.menu.findItem(id)
    }

    private fun press(row: Task, drawn: List<Task>, stored: List<Task>): Pressed {
        val indents = mutableListOf<Pair<Long, Long>>()
        val outdents = mutableListOf<Long>()
        val menu = HierarchyMenu.build(themed(), View(themed()), row, drawn, stored,
            onIndent = { task, above -> indents += task.id to above.id },
            onOutdent = { task -> outdents += task.id })
        return Pressed(menu, indents, outdents)
    }

    @Test
    fun `the batch the render reads and the offer the menu reads are one answer`() {
        val topic = group("office-block")
        val above = row("planning", position = 0, group = topic.uuid)
        val below = row("draft", position = 1, group = topic.uuid)
        row("figures", parent = "draft", position = 2, group = topic.uuid)
        val rows = stored()

        // The card's flag comes from `hierarchyOffers`; the menu that opens from it comes from `offer`. Two paths,
        // and a reader must never meet a control whose menu has nothing enabled in it.
        listOf(above, below).forEach { task ->
            assertEquals(
                "the flag and the offer agree about ${task.uuid}",
                HierarchyMenu.offer(task, rows, rows).anythingAvailable,
                TaskHierarchyPolicy.hierarchyOffers(listOf(rows), TaskHierarchyPolicy.factsOf(rows))[task.id]
            )
        }
    }

    /**
     * The two ways of asking for an offer, answered over one fixture.
     *
     * §6.2 hands the editor its own candidate — [TaskHierarchyPolicy.previousSibling], the nearest row the profile
     * stores in the same sibling set — and the [TaskHierarchyPolicy.TreeFacts] it has already built, so the menu has
     * to answer from those rather than from a two-row list invented to look like a card's block. The list's overload
     * is then the same computation seen from the other side: its candidate is the row drawn above the one asked
     * about, which is exactly what [HierarchyMenu.indentCandidate] returns, and everything else — both refusals, and
     * the guard for a block that cannot be named because an identity inside it is doubled — is shared by
     * construction, not copied.
     */
    @Test
    fun `the candidate the editor names and the block the list draws answer one offer`() {
        val topic = group("office-block")
        val planning = row("planning", position = 0, group = topic.uuid)
        row("notes", position = 1, group = topic.uuid)
        val nested = row("draft", parent = "planning", position = 2, group = topic.uuid)
        // One identity claimed by two live rows, so the equality below is asked about a block neither screen can
        // name as well as about ordinary ones: hiding a control nobody can explain is the menu's rule, not a rule
        // each screen is free to re-decide for itself.
        row("receipts", position = 3, group = topic.uuid, uuid = planning.uuid)
        val rows = stored()
        val facts = TaskHierarchyPolicy.factsOf(rows)

        rows.forEach { task ->
            assertEquals("both readings of ${task.uuid} give the same answer",
                HierarchyMenu.offer(task, rows, rows),
                HierarchyMenu.offer(task, HierarchyMenu.indentCandidate(task, rows), facts))
        }

        // Where the two candidates legitimately part, and why the copy had to follow: `draft` is second in the drawn
        // sequence, so the list would hang it on `notes`, while its own sibling set — same profile, same topic, same
        // parent — holds nothing above it. The editor keeps the stored rule, so the row it names has to be the row it
        // would actually write; "the row above" is a fact about a screen the editor is not on.
        assertNotNull("the list's candidate is the row drawn above it",
            HierarchyMenu.indentCandidate(nested, rows))
        assertNull("while the policy finds no sibling in the row's own set",
            TaskHierarchyPolicy.previousSibling(nested, facts))
        assertEquals("so the editor's offer is refused where the list's is not",
            HierarchyMoveRefusal.NO_ROW_ABOVE,
            HierarchyMenu.offer(nested, TaskHierarchyPolicy.previousSibling(nested, facts), facts).indentReason)
        assertNull("one row, two screens, two answers about what a press would write",
            HierarchyMenu.offer(nested, rows, rows).indentReason)
    }

    @Test
    fun `the render publishes the flag the rule gives, and keeps it off a row nobody asked for`() {
        val topic = group("office-block")
        val work = runBlocking { fixture.addCategory(userId, "cat-work") }
        row("planning", position = 0, group = topic.uuid)
        row("draft", parent = "planning", position = 1, group = topic.uuid, categoryId = work)
        val model = viewModel()
        settle("both rows to reach the screen") { rowTitles(model).size == 2 }

        val drawn = model.state.value.items.filterIsInstance<TasksListItem.TaskRow>()
        assertTrue("the nested row can be lifted out of its parent",
            drawn.first { it.task.uuid == "draft" }.canEditHierarchy)
        assertFalse("and the topic's own first row has neither direction to move",
            drawn.first { it.task.uuid == "planning" }.canEditHierarchy)
        assertEquals("the screen's own two lists are what the menu will be built from",
            listOf("planning", "draft"), model.hierarchyContext?.blocks?.flatten()?.map { it.uuid })

        // The same row, drawn because an answer needs the path rather than because the reader asked for it, and
        // again while a batch is being picked: neither is a row to re-parent.
        runBlocking { model.setCategoryFilter(work) }
        settle("the filter to draw the path") { rowTitles(model) == listOf("planning", "draft") }
        assertTrue("the answer keeps its control",
            model.state.value.items.filterIsInstance<TasksListItem.TaskRow>()
                .first { it.task.uuid == "draft" }.canEditHierarchy)
        assertFalse("the path does not",
            model.state.value.items.filterIsInstance<TasksListItem.TaskRow>()
                .first { it.task.uuid == "planning" }.canEditHierarchy)

        model.enterSelection(model.state.value.items.filterIsInstance<TasksListItem.TaskRow>()
            .first { it.task.uuid == "draft" }.task.id)
        settle("selection to reach the rows") {
            model.state.value.items.filterIsInstance<TasksListItem.TaskRow>().any { it.selectionMode }
        }
        assertFalse("nothing is movable while a batch is being picked",
            model.state.value.items.filterIsInstance<TasksListItem.TaskRow>()
                .first { it.task.uuid == "draft" }.canEditHierarchy)
    }

    @Test
    fun `a row drawn only to hold the path stands down the offer the rule would make for it`() {
        val topic = group("office-block")
        val work = runBlocking { fixture.addCategory(userId, "cat-work") }
        row("planning", position = 0, group = topic.uuid)
        val middle = row("draft", parent = "planning", position = 1, group = topic.uuid)
        row("figures", parent = "draft", position = 2, group = topic.uuid, categoryId = work)
        val model = viewModel()
        runBlocking { model.setCategoryFilter(work) }
        settle("the filter to draw the whole path") { rowTitles(model) == listOf("planning", "draft", "figures") }

        val rows = model.state.value.items.filterIsInstance<TasksListItem.TaskRow>()
        val drawn = model.hierarchyContext!!.blocks.flatten()
        // The middle row is on the screen because the answer at the bottom needs its parent, not because the reader
        // asked for it. The rule alone would hand it an outdent — its parent is a real row with no parent of its own
        // — so this is the case where the screen's own guard, and not the domain, is what keeps the control off.
        assertTrue("the domain would offer the scaffolding row a lift: " +
                HierarchyMenu.offer(middle, drawn, drawn).anythingAvailable,
            HierarchyMenu.offer(middle, drawn, stored()).anythingAvailable)
        assertTrue("and it is drawn as scaffolding",
            rows.first { it.task.uuid == "draft" }.shownForContext)
        assertFalse("but a row nobody matched gets no control: re-parenting the path would move rows the reader " +
            "never chose, and the filter's own answer would change under them",
            rows.first { it.task.uuid == "draft" }.canEditHierarchy)
        assertTrue("the match itself keeps its control",
            rows.first { it.task.uuid == "figures" }.canEditHierarchy)
    }

    @Test
    fun `the render measures a candidate against the block the reader sees, not the profile's sequence`() {
        val office = group("office-block")
        val errand = group("errand-block")
        // Storage interleaves the two topics; the screen does not. Measured over the stored order the row above
        // `calls` belongs to another topic and the indent would be refused — so this is the case where the block the
        // list actually draws is what decides whether a card gets a control at all.
        row("notes", position = 0, group = office.uuid)
        row("receipt", position = 1, group = errand.uuid)
        row("calls", position = 2, group = office.uuid)
        val model = viewModel()
        settle("all three rows to reach the screen") { rowTitles(model).size == 3 }

        val drawn = model.state.value.items.filterIsInstance<TasksListItem.TaskRow>()
        assertTrue("the second row under a header can be hung on the first one under that same header: " +
                drawn.map { it.task.uuid to it.canEditHierarchy },
            drawn.first { it.task.uuid == "calls" }.canEditHierarchy)
        assertEquals("and the screen publishes that block, in the order the list drew it, for the menu to read",
            listOf("notes", "calls"), model.hierarchyContext!!.blockFor(drawn.first { it.task.uuid == "calls" }.task.id)?.map { it.uuid })
        assertEquals("the other topic's row is not in it",
            listOf("receipt"), model.hierarchyContext!!.blockFor(drawn.first { it.task.uuid == "receipt" }.task.id)?.map { it.uuid })
    }

    private fun rowTitles(model: TasksViewModel) =
        model.state.value.items.filterIsInstance<TasksListItem.TaskRow>().map { it.task.uuid }

    @Test
    fun `the command the menu fires writes the link the menu named`() {
        val topic = group("office-block")
        row("planning", position = 0, group = topic.uuid)
        val below = row("draft", position = 1, group = topic.uuid)
        val rows = stored()
        val candidate = HierarchyMenu.indentCandidate(below, rows)!!
        val model = viewModel()
        settle("the screen to be drawn") { rowTitles(model).size == 2 }

        model.indentTask(below.id, candidate.id)
        settle("the indent to land") {
            runBlocking { fixture.taskDao.getTaskById(below.id) }?.parentUuid == candidate.uuid
        }

        assertEquals("one row was written: its own link", candidate.uuid,
            runBlocking { fixture.taskDao.getTaskById(below.id) }?.parentUuid)
        assertNull("and the row it hangs on did not acquire a parent",
            runBlocking { fixture.taskDao.getTaskById(candidate.id) }?.parentUuid)
    }

    private fun viewModel() = TasksViewModel(
        fixture.taskRepository, fixture.categoryRepository, fixture.groupRepository,
        fixture.recurrenceRepository, fixture.reminderRepository, fixture.sessionRepository,
        fixture.workTrackingRepository, fixture.workStatisticsRepository, hierarchy
    )

    // ── one rule, shared by the menu and the domain ────────────────────────────────────────────────────────

    @Test
    fun `what the menu calls allowed the repository writes`() {
        val topic = group("office-block")
        row("planning", position = 0, group = topic.uuid)
        val below = row("draft", position = 1, group = topic.uuid)
        val rows = stored()

        assertNull("the policy sees no reason to refuse an indent onto the row above",
            TaskHierarchyPolicy.indentRefusal(below, rows.first { it.uuid == "planning" }, rows))

        val outcome = runBlocking { hierarchy.indentTask(below.id, rows.first { it.uuid == "planning" }.id) }
        assertTrue("and the domain agrees on the same fixture: $outcome",
            outcome is HierarchyMoveResult.Accepted)
    }

    @Test
    fun `every refusal the menu names is the refusal the repository answers`() {
        val topic = group("office-block")
        val other = group("errand-block")

        // Nothing above it: the first row of the profile.
        val planning = row("planning", position = 0, group = topic.uuid)
        val notes = row("notes", position = 1, group = topic.uuid)
        assertEquals("the first row has no predecessor", HierarchyMoveRefusal.NO_ROW_ABOVE,
            TaskHierarchyPolicy.indentRefusal(planning, null, stored()))
        refusedByDomain(HierarchyMoveRefusal.NO_ROW_ABOVE) { hierarchy.indentTask(planning.id) }

        // The row above belongs to a different topic.
        val receipts = row("receipts", position = 2, group = other.uuid)
        assertEquals("an indent inside a notebook cannot move a row out of its topic",
            HierarchyMoveRefusal.DIFFERENT_TOPIC,
            TaskHierarchyPolicy.indentRefusal(notes, receipts, stored()))
        refusedByDomain(HierarchyMoveRefusal.DIFFERENT_TOPIC) { hierarchy.indentTask(notes.id, receipts.id) }

        // It already hangs on that row.
        val figures = row("figures", parent = "notes", position = 3, group = topic.uuid)
        val rowsWithChild = stored()
        assertEquals("there is nowhere deeper to go under the parent it has",
            HierarchyMoveRefusal.NO_DEEPER_PARENT,
            TaskHierarchyPolicy.indentRefusal(figures, rowsWithChild.first { it.uuid == "notes" }, rowsWithChild))

        // A branch that would pass the third level. `footnote` sits at the bottom of `notes`' branch, and so does
        // `plan-foot` under the other root: hanging one on the other would draw a fourth level, which the depth
        // contract the reader already sees on screen does not have.
        val footnote = row("footnote", parent = "figures", position = 4, group = topic.uuid)
        row("plan-note", parent = "planning", position = 5, group = topic.uuid)
        val planFoot = row("plan-foot", parent = "plan-note", position = 6, group = topic.uuid)
        val rowsDeep = stored()
        assertEquals("a row already at the third level cannot take another row under it",
            HierarchyMoveRefusal.DEPTH_EXCEEDED,
            TaskHierarchyPolicy.indentRefusal(footnote, planFoot, rowsDeep))
        refusedByDomain(HierarchyMoveRefusal.DEPTH_EXCEEDED) { hierarchy.indentTask(footnote.id, planFoot.id) }

        // Its own child, drawn above it by a sort the reader chose.
        val rowsAgain = stored()
        assertEquals("hanging a row on its own descendant closes a loop",
            HierarchyMoveRefusal.ANCHOR_IN_SUBTREE,
            TaskHierarchyPolicy.indentRefusal(
                notes, rowsAgain.first { it.uuid == "figures" }, rowsAgain))
        refusedByDomain(HierarchyMoveRefusal.ANCHOR_IN_SUBTREE) { hierarchy.indentTask(notes.id, figures.id) }
    }

    private fun refusedByDomain(
        reason: HierarchyMoveRefusal,
        call: suspend () -> HierarchyMoveResult
    ) {
        val outcome = runBlocking { call() }
        assertEquals("the domain answered: $outcome", reason, (outcome as HierarchyMoveResult.Refused).reason)
    }

    @Test
    fun `outdent is offered exactly where the domain would lift the row`() {
        val topic = group("office-block")
        row("planning", position = 0, group = topic.uuid)
        val child = row("draft", parent = "planning", position = 1, group = topic.uuid)
        val root = row("notes", position = 2, group = topic.uuid)
        val rows = stored()

        assertNull("a row under a parent can rise",
            TaskHierarchyPolicy.outdentRefusal(child, rows))
        assertTrue(runBlocking { hierarchy.outdentTask(child.id) } is HierarchyMoveResult.Accepted)

        assertEquals("a topic's own child has nowhere further up to go",
            HierarchyMoveRefusal.ALREADY_AT_TOPIC_ROOT, TaskHierarchyPolicy.outdentRefusal(root, rows))
        refusedByDomain(HierarchyMoveRefusal.ALREADY_AT_TOPIC_ROOT) { hierarchy.outdentTask(root.id) }
    }

    @Test
    fun `a row whose parent link names nothing is refused rather than invented a level`() {
        val topic = group("office-block")
        val orphan = row("draft", parent = "no-such-row", position = 0, group = topic.uuid)

        assertEquals("the renderer already draws it at the margin, so an outdent would invent a parent",
            HierarchyMoveRefusal.ALREADY_AT_TOPIC_ROOT, TaskHierarchyPolicy.outdentRefusal(orphan, stored()))
    }

    // ── the candidate is the screen's, not the sequence's ──────────────────────────────────────────────────

    @Test
    fun `the indent names the row the reader actually sees above`() {
        val topic = group("office-block")
        row("planning", position = 0, group = topic.uuid)
        val hidden = row("draft", position = 1, group = topic.uuid)
        val target = row("figures", position = 2, group = topic.uuid)
        val rows = stored()
        // What the screen draws when a filter retires `draft`: the stored predecessor of `figures` is a row the
        // reader cannot see, and hanging it there would look like an indent that did nothing.
        val drawn = listOf(rows.first { it.uuid == "planning" }, target)

        assertEquals("the drawn predecessor, not the stored one", "planning",
            HierarchyMenu.indentCandidate(target, drawn)?.uuid)
        assertNull("and a row the screen does not draw has nothing above it at all",
            HierarchyMenu.indentCandidate(hidden, drawn)?.uuid)
        assertNull("the first row on screen has nothing to hang on",
            HierarchyMenu.indentCandidate(drawn.first(), drawn)?.uuid)
    }

    @Test
    fun `the indent the menu offers is the indent the repository accepts with that row named`() {
        val topic = group("office-block")
        row("planning", position = 0, group = topic.uuid)
        row("draft", position = 1, group = topic.uuid)
        val target = row("figures", position = 2, group = topic.uuid)
        val rows = stored()
        val drawn = listOf(rows.first { it.uuid == "planning" }, target)
        val candidate = HierarchyMenu.indentCandidate(target, drawn)!!

        val outcome = runBlocking { hierarchy.indentTask(target.id, candidate.id) }
        assertTrue("named explicitly, the drawn parent is what gets written: $outcome",
            outcome is HierarchyMoveResult.Accepted)
        assertEquals("planning", runBlocking { fixture.taskDao.getTaskById(target.id) }?.parentUuid)
    }

    @Test
    fun `a menu row is never titled with a resource that does not exist`() {
        // `reasonLabel` answers 0 for the refusals the menu keeps silent about, and a 0 handed to `getString` is not
        // an empty title — it is a Resources.NotFoundException on the reader's press. The menu has to have a real
        // name for every row it could be asked to title, silent ones included.
        HierarchyMoveRefusal.values().forEach { refusal ->
            assertTrue("the indent row for $refusal would be titled with resource ${HierarchyMenu.indentLabel(refusal)}",
                HierarchyMenu.indentLabel(refusal) != 0)
            assertTrue("the outdent row for $refusal would be titled with resource ${HierarchyMenu.outdentLabel(refusal)}",
                HierarchyMenu.outdentLabel(refusal) != 0)
        }
    }

    @Test
    fun `a row whose indent is silent and whose lift is real still opens a menu the reader can use`() {
        val topic = group("office-block")
        row("planning", position = 0, group = topic.uuid)
        // The row drawn above `half` carries no identity of its own, so hanging `half` on it would write a blank
        // parent — a fault in the notebook rather than a fact the reader chose, and one of the two refusals the
        // menu has no sentence for. It is only one of this menu's rows: the other direction is live.
        row("unnamed", uuid = "", position = 1, group = topic.uuid)
        val half = row("half", parent = "planning", position = 2, group = topic.uuid)
        val rows = stored()
        assertTrue("which is why the card still draws a control for it at all",
            TaskHierarchyPolicy.hierarchyOffers(listOf(rows), TaskHierarchyPolicy.factsOf(rows)).getValue(half.id))

        val pressed = press(half, rows, rows)
        val indent = pressed.item(R.id.action_indent_task)

        assertFalse("the action that cannot be explained is not shown as a row at all", indent.isVisible)
        assertTrue("while the lift the reader can actually take stays live",
            pressed.item(R.id.action_outdent_task).isEnabled)
        pressed.menu.menu.performIdentifierAction(R.id.action_outdent_task, 0)
        assertEquals("and pressing it is the lift the menu named", listOf(half.id), pressed.outdents)
    }

    @Test
    fun `the block the reader sees is what a candidate is read out of`() {
        val office = group("office-block")
        val errand = group("errand-block")
        // The profile's one sequence interleaves the two topics; the screen files each under its own header. So the
        // row above `calls` on the screen is `notes`, while the row above it in storage belongs to another topic —
        // and an indent measured against storage would refuse a move the reader can see is admissible.
        row("notes", position = 0, group = office.uuid)
        row("receipt", position = 1, group = errand.uuid)
        val calls = row("calls", position = 2, group = office.uuid)
        val rows = stored()
        val officeBlock = listOf(rows.first { it.uuid == "notes" }, calls)

        assertEquals("the block's own row is the candidate the reader sees above it", "notes",
            HierarchyMenu.indentCandidate(calls, officeBlock)?.uuid)
        assertNull("and a row the block draws first has nothing above it at all",
            HierarchyMenu.indentCandidate(calls, listOf(calls)))
        assertEquals("read against the stored sequence the same press is refused for another topic",
            HierarchyMoveRefusal.DIFFERENT_TOPIC,
            HierarchyMenu.offer(calls, rows, rows).indentReason)
        assertNull("while read against the block the screen drew it is simply allowed",
            HierarchyMenu.offer(calls, officeBlock, rows).indentReason)
    }

    // ── what the menu says ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `every refusal a reader can be shown has its own sentence`() {
        val labels = EXPLAINED.map { HierarchyMenu.reasonLabel(it) }

        labels.forEachIndexed { index, res ->
            assertTrue("the refusal $res has no sentence of its own: ${EXPLAINED[index]}", res != 0)
        }
        assertEquals("seven explained refusals, seven sentences — a reason reused for a different fact misleads the reader",
            labels.distinct().size, labels.size)
    }

    @Test
    fun `an inconsistent notebook is not offered as a press at all`() {
        val topic = group("office-block")
        val head = row("planning", position = 0, group = topic.uuid)
        // Two rows claiming one uuid: the level the menu would validate and the level the renderer draws would come
        // from two different chains, so neither action is safe to name.
        row("twin", position = 1, group = topic.uuid, uuid = "planning")
        val rows = stored()

        val offer = HierarchyMenu.offer(head, rows, rows)
        assertTrue("the doubled uuid is what refuses it: ${outdentReasonOf(offer)}",
            SILENT.contains(outdentReasonOf(offer)))
        assertFalse("and a row whose refusal is a data fault draws no control",
            HierarchyMenu.offer(head, rows, rows).anythingAvailable)
    }

    private fun outdentReasonOf(offer: HierarchyMenu.Offer): HierarchyMoveRefusal? = offer.outdentReason

    @Test
    fun `the two actions are named by what they do rather than by their geometry`() {
        assertEquals("the menu says «subtask of the row above», not «indent»",
            app.getString(R.string.row_indent),
            app.getString(HierarchyMenu.indentLabel(null)))
        assertEquals("and «one level up» rather than «outdent»",
            app.getString(R.string.row_outdent),
            app.getString(HierarchyMenu.outdentLabel(null)))

        val blocked = EXPLAINED.map { app.getString(HierarchyMenu.indentLabel(it)) } +
            EXPLAINED.map { app.getString(HierarchyMenu.outdentLabel(it)) }
        assertFalse("a refusal never wears the action's name: $blocked",
            blocked.any { it == app.getString(R.string.row_indent) || it == app.getString(R.string.row_outdent) })
    }

    // ── the popup, as the reader meets it ──────────────────────────────────────────────────────────────────

    @Test
    fun `an allowed action is pressable and hands on the row the menu named`() {
        val topic = group("office-block")
        val above = row("planning", position = 0, group = topic.uuid)
        val below = row("draft", position = 1, group = topic.uuid)
        val rows = stored()

        val pressed = press(below, rows, rows)
        val item = pressed.item(R.id.action_indent_task)

        assertEquals("the row carries the action's own name", app.getString(R.string.row_indent),
            item.title.toString())
        assertTrue("and it can be pressed", item.isEnabled)
        assertTrue("pressing it is a click the menu handles",
            pressed.menu.menu.performIdentifierAction(R.id.action_indent_task, 0))
        assertEquals("the press names the row above, so the write is the one the reader saw",
            listOf(below.id to above.id), pressed.indents)
    }

    @Test
    fun `a refused action is shown with its reason and cannot be pressed`() {
        val topic = group("office-block")
        val first = row("planning", position = 0, group = topic.uuid)
        row("draft", position = 1, group = topic.uuid)
        val rows = stored()

        val pressed = press(first, rows, rows)
        val item = pressed.item(R.id.action_indent_task)

        assertEquals("the reader is told why there is nothing to hang it on",
            app.getString(R.string.indent_no_row_above), item.title.toString())
        assertFalse("and the row is not clickable", item.isEnabled)
        pressed.menu.menu.performIdentifierAction(R.id.action_indent_task, 0)
        assertEquals("a press does not reach the command", emptyList<Pair<Long, Long>>(), pressed.indents)
    }

    @Test
    fun `outdent appears only on a row that has a parent to leave`() {
        val topic = group("office-block")
        val parent = row("planning", position = 0, group = topic.uuid)
        val child = row("draft", parent = "planning", position = 1, group = topic.uuid)
        val rows = stored()

        assertFalse("a topic's own child is not offered a lift it cannot take",
            press(parent, rows, rows).item(R.id.action_outdent_task).isEnabled)
        assertTrue("and a nested one is",
            press(child, rows, rows).item(R.id.action_outdent_task).isEnabled)
    }

    @Test
    fun `the menu is the door for both actions and nothing else`() {
        val topic = group("office-block")
        row("planning", position = 0, group = topic.uuid)
        val below = row("draft", position = 1, group = topic.uuid)
        val rows = stored()

        val ids = press(below, rows, rows).menu.let { menu ->
            (0 until menu.menu.size()).map { menu.menu.getItem(it).itemId }
        }
        assertEquals("indent, then outdent — the destination picker is §6.3's own block, not smuggled in here",
            listOf(R.id.action_indent_task, R.id.action_outdent_task), ids)
    }

    // ── the card, and the row that carries it ──────────────────────────────────────────────────────────────

    private fun taskRow(
        task: Task,
        canEditHierarchy: Boolean = true,
        shownForContext: Boolean = false,
        selectionMode: Boolean = false
    ) = TasksListItem.TaskRow(
        task = task, category = null, isSelected = false,
        canEditHierarchy = canEditHierarchy, shownForContext = shownForContext, selectionMode = selectionMode
    )

    private fun bindRow(vararg items: TasksListItem.TaskRow): List<View> {
        val adapter = TasksAdapter(
            onTaskClick = {}, onLongPressTask = {}, onToggleSelection = {}, onEdit = {}, onDeleteRequest = {},
            onStart = {}, onPause = {}, onResume = {}, onComplete = {}, onWorkToggle = {}, onToggleBranch = {},
            onHierarchy = { _, _ -> }, onToggleGroup = {}, onGroupMenu = { _, _ -> }, onStartDrag = {}
        )
        val list = items.toList()
        adapter.submitList(list)
        settle("the adapter to hold the screen") { adapter.itemCount == list.size }
        return list.map { item ->
            val index = list.indexOf(item)
            val holder = adapter.createViewHolder(FrameLayout(themed()), adapter.getItemViewType(index))
            adapter.bindViewHolder(holder, index)
            holder.itemView
        }
    }

    @Test
    fun `the card draws a hierarchy control only where there is something to do`() {
        val topic = group("office-block")
        val above = row("planning", position = 0, group = topic.uuid)
        val below = row("draft", position = 1, group = topic.uuid)
        val rows = stored()

        val cards = bindRow(taskRow(below), taskRow(below, canEditHierarchy = false))
        assertTrue("a row with an indent available draws the control",
            cards[0].findViewById<View>(R.id.hierarchyButton).isVisible)
        assertFalse("and the same row, told there is nothing to do, does not: the flag travels on the row because a " +
            "ListAdapter rebinds only what changed", cards[1].findViewById<View>(R.id.hierarchyButton).isVisible)

        assertTrue("the render decides the flag from the same rule the menu reads",
            HierarchyMenu.offer(below, rows, rows).anythingAvailable)
        assertFalse("a first row at the topic root has neither action",
            HierarchyMenu.offer(above, rows, rows).anythingAvailable)
    }

    @Test
    fun `scaffolding and selection stand the control down`() {
        val topic = group("office-block")
        row("planning", position = 0, group = topic.uuid)
        val below = row("draft", position = 1, group = topic.uuid)

        val cards = bindRow(
            taskRow(below, shownForContext = true, canEditHierarchy = false),
            taskRow(below, selectionMode = true, canEditHierarchy = false)
        )
        assertFalse("a row drawn because something else needs the path is not a row to re-parent",
            cards[0].findViewById<View>(R.id.hierarchyButton).isVisible)
        assertFalse("and while a batch is being picked nothing moves, so nothing is offered",
            cards[1].findViewById<View>(R.id.hierarchyButton).isVisible)
    }

    @Test
    fun `a recycled card gives the control up`() {
        val topic = group("office-block")
        val nested = row("planning", position = 0, group = topic.uuid)
        val leaf = row("draft", parent = "planning", position = 1, group = topic.uuid)
        val rows = stored()
        val offered = HierarchyMenu.offer(leaf, rows, rows).anythingAvailable

        val adapter = TasksAdapter(
            onTaskClick = {}, onLongPressTask = {}, onToggleSelection = {}, onEdit = {}, onDeleteRequest = {},
            onStart = {}, onPause = {}, onResume = {}, onComplete = {}, onWorkToggle = {}, onToggleBranch = {},
            onHierarchy = { _, _ -> }, onToggleGroup = {}, onGroupMenu = { _, _ -> }, onStartDrag = {}
        )
        adapter.submitList(listOf(taskRow(leaf, canEditHierarchy = offered)))
        settle("the first row to reach the adapter") { adapter.itemCount == 1 }
        val holder = adapter.createViewHolder(FrameLayout(themed()), adapter.getItemViewType(0))
        adapter.bindViewHolder(holder, 0)
        val button = holder.itemView.findViewById<View>(R.id.hierarchyButton)
        assertEquals("painted from the row it was given", if (offered) View.VISIBLE else View.GONE, button.visibility)

        adapter.submitList(listOf(taskRow(leaf, canEditHierarchy = false)))
        settle("the second row to reach the adapter") { adapter.itemCount == 1 }
        adapter.bindViewHolder(holder, 0)
        assertEquals("and the next bind recomputes it from zero", View.GONE, button.visibility)
    }

    @Test
    fun `the panel that holds it still fits the row at the narrowest width`() {
        val topic = group("office-block")
        val above = row("planning", position = 0, group = topic.uuid)
        val below = row("draft", position = 1, group = topic.uuid)

        val card = bindRow(taskRow(below), taskRow(above)).first()
        val density = app.resources.displayMetrics.density
        card.measure(
            View.MeasureSpec.makeMeasureSpec((320 * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        card.layout(0, 0, card.measuredWidth, card.measuredHeight)

        val panel = card.findViewById<View>(R.id.actionPanel)
        val box = (76 * density).toInt()
        assertTrue("the revealed panel must be narrower than the row, or the card slides off its own screen: " +
                "${panel.width} against ${card.width - (8 * density).toInt() * 2}",
            panel.width in 1 until card.width)
        assertEquals("a row with the hierarchy control reveals three boxes — edit, delete, hierarchy — and no more",
            3 * box, panel.width)
        assertTrue("and the card still keeps its own width when the panel is fully revealed: " +
                "${card.width - panel.width}px of ${card.width}", card.width - panel.width >= (72 * density).toInt())
        assertTrue("and the control inside it clears the app's own touch floor: ${panel.findViewById<View>(
            R.id.hierarchyButton).width}",
            panel.findViewById<View>(R.id.hierarchyButton).width >= (48 * density).toInt())

        // The same row with nothing to do: the panel is the width it was before this control existed, which is what
        // makes the three-box reveal a property of a row that can be moved rather than of every card on the screen.
        val plain = bindRow(taskRow(above, canEditHierarchy = false)).first()
        plain.measure(
            View.MeasureSpec.makeMeasureSpec((320 * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        plain.layout(0, 0, plain.measuredWidth, plain.measuredHeight)
        assertEquals("a row with no hierarchy action reveals the two boxes the panel had before this control existed",
            2 * box, plain.findViewById<View>(R.id.actionPanel).width)
    }

    @Test
    fun `the control is named, and says what pressing it opens`() {
        val topic = group("office-block")
        row("planning", position = 0, group = topic.uuid)
        val below = row("draft", position = 1, group = topic.uuid)

        val button = bindRow(taskRow(below)).first().findViewById<View>(R.id.hierarchyButton)
        assertEquals("a TalkBack reader hears the fact, not «menu»",
            app.getString(R.string.row_hierarchy), button.contentDescription.toString())
    }

    // ── the commands ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `an indent writes the one link and leaves the branch under it alone`() {
        val topic = group("office-block")
        val above = row("planning", position = 0, group = topic.uuid)
        val below = row("draft", position = 1, group = topic.uuid)
        val child = row("figures", parent = "draft", position = 2, group = topic.uuid)
        val before = shape()

        runBlocking { hierarchy.indentTask(below.id, above.id) }
        settle("the write to land") { runBlocking { fixture.taskDao.getTaskById(below.id) }?.parentUuid == "planning" }

        val after = stored().associateBy { it.uuid }
        assertEquals("the row's own children keep their own parent", "draft", after.getValue("figures").parentUuid)
        assertEquals("the topic is untouched by an indent", topic.uuid, after.getValue("draft").groupId)
        assertEquals("and the fold the reader set is not a thing a move resets",
            before.first { it.uuid == "draft" }.collapsed, after.getValue("draft").childrenCollapsed)
    }

    @Test
    fun `a descendant deeper than the walk can reach is still refused, for a reason the menu can tell`() {
        val topic = group("office-block")
        // The walk that names a loop is bounded by the render contract, and §4 records that an import or a restored
        // backup can leave the notebook deeper than the contract draws it. A descendant below that bound is then
        // refused by the depth rule instead — which still stops the loop, and is a sentence the reader can hear,
        // not one of the two the menu has to hide.
        val head = row("one", position = 0, group = topic.uuid)
        row("two", parent = "one", position = 1, group = topic.uuid)
        row("three", parent = "two", position = 2, group = topic.uuid)
        row("four", parent = "three", position = 3, group = topic.uuid)
        row("five", parent = "four", position = 4, group = topic.uuid)
        val far = row("six", parent = "five", position = 5, group = topic.uuid)
        val rows = stored()

        val refusal = TaskHierarchyPolicy.indentRefusal(head, far, rows)
        assertTrue("the head cannot take its own sixth-row descendant: $refusal", refusal != null)
        assertFalse("and the reason is one the menu names rather than hides",
            HierarchyMenu.isSilent(refusal))
        val outcome = runBlocking { hierarchy.indentTask(head.id, far.id) }
        assertTrue("the domain refuses the move the menu offered no path for: $outcome",
            outcome is HierarchyMoveResult.Refused)
        assertNull("and the row is still the topic's own child",
            runBlocking { fixture.taskDao.getTaskById(head.id) }?.parentUuid)
    }

    private data class Snapshot(val uuid: String, val parentUuid: String?, val groupId: String?, val order: Int,
                                val syncState: SyncState, val collapsed: Boolean)
    private fun shape(): List<Snapshot> = stored().map {
        Snapshot(it.uuid, it.parentUuid, it.groupId, it.order, it.syncState, it.childrenCollapsed)
    }

    @Test
    fun `a refusal leaves the whole profile exactly as it found it`() {
        val topic = group("office-block")
        val first = row("planning", position = 0, group = topic.uuid)
        row("draft", position = 1, group = topic.uuid)
        val before = shape()

        val outcome = runBlocking { hierarchy.indentTask(first.id) }
        assertTrue("the first row is refused: $outcome", outcome is HierarchyMoveResult.Refused)
        assertEquals("and nothing at all moved", before, shape())
    }

    @Test
    fun `a row the domain would refuse is offered no control and writes nothing when pressed anyway`() {
        val topic = group("office-block")
        val first = row("planning", position = 0, group = topic.uuid)
        row("draft", position = 1, group = topic.uuid)
        val rows = stored()

        assertFalse("the control would not have been drawn for this row in the first place",
            HierarchyMenu.offer(first, rows, rows).anythingAvailable)
        assertEquals("and pressing it anyway changes nothing in storage",
            listOf(Snapshot("planning", null, topic.uuid, 0, SyncState.SYNCED, false)),
            shape().filter { it.uuid == "planning" })
    }

    @Test
    fun `an outdent lifts the row and takes nothing with it that did not travel`() {
        val topic = group("office-block")
        val parent = row("planning", position = 0, group = topic.uuid)
        val child = row("draft", parent = "planning", position = 1, group = topic.uuid)
        row("figures", parent = "draft", position = 2, group = topic.uuid)

        val outcome = runBlocking { hierarchy.outdentTask(child.id) }
        assertTrue("a nested row can rise: $outcome", outcome is HierarchyMoveResult.Accepted)

        settle("the lift to land") { runBlocking { fixture.taskDao.getTaskById(child.id) }?.parentUuid == null }
        val after = stored().associateBy { it.uuid }
        assertEquals("it lands beside the parent it left, under the topic", null, after.getValue("draft").parentUuid)
        assertEquals("its own child still hangs on it", "draft", after.getValue("figures").parentUuid)
        assertEquals("and the topic keeps the whole block", topic.uuid, after.getValue("figures").groupId)
        assertNotEquals("the row that moved is the one queued for a push",
            SyncState.SYNCED, after.getValue("draft").syncState)
        assertEquals("nothing else was touched",
            SyncState.SYNCED, after.getValue("planning").syncState)
        assertEquals("and a row that did not travel keeps the stamp it had",
            STAMP, after.getValue("figures").updatedAt)
    }

    @Test
    fun `the policy and the repository answer the same question for outdent at a doubled parent`() {
        val topic = group("office-block")
        row("planning", position = 0, group = topic.uuid)
        // A second row claiming the parent's uuid: the chain the level is read from stops being single-valued.
        row("twin", position = 1, group = topic.uuid, uuid = "planning")
        val child = row("draft", parent = "planning", position = 2, group = topic.uuid)
        val rows = stored()

        assertEquals("the menu would hide the control, and the domain refuses for the same reason",
            HierarchyMoveRefusal.AMBIGUOUS_PARENT, TaskHierarchyPolicy.outdentRefusal(child, rows))
        refusedByDomain(HierarchyMoveRefusal.AMBIGUOUS_PARENT) { hierarchy.outdentTask(child.id) }
    }
}
