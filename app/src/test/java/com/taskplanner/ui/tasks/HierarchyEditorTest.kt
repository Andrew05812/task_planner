package com.taskplanner.ui.tasks

import android.content.Context
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.test.core.app.ApplicationProvider
import com.taskplanner.R
import com.taskplanner.data.hierarchy.HierarchyMoveRefusal
import com.taskplanner.data.hierarchy.TaskHierarchyPolicy
import com.taskplanner.data.model.SyncState
import com.taskplanner.data.model.Task
import com.taskplanner.data.model.TaskGroup
import com.taskplanner.data.repository.TaskHierarchyRepository
import com.taskplanner.databinding.FragmentTaskDetailBinding
import com.taskplanner.testing.AppFixture
import java.util.concurrent.Executor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * §6.2: the hierarchy block inside the task editor — what it says about the row on screen, and what it lets the
 * reader press.
 *
 * The list screen answers this from a card's menu (Block A, §6.1). The editor is the other half of the stage's goal:
 * a reader who opens one task has to see where that task sits and be able to move it from there. Three values and two
 * verbs, and every one of them has to be the answer the tree already gives rather than an opinion the screen formed
 * on its own:
 *
 *  * the values come out of the snapshot the render publishes ([TasksViewModel.hierarchyContext]) — the topics the
 *    headers were drawn from, the profile's stored rows, the blocks on screen — and from nowhere else. The parent is
 *    the row `tasks.parent_uuid` names, never the row's place in the sequence; the level is
 *    [TaskHierarchyPolicy.levelOf]'s number; the topic is the group the row is filed under.
 *  * the verbs are the list's own: [HierarchyMenu]'s offer, labels and hide rule, over the candidate
 *    [TaskHierarchyPolicy.previousSibling] names. A second implementation in the editor is how a button ends up
 *    offering what the domain then refuses, and the reader learns not to trust the app with their tree.
 *  * the presses go through [TasksViewModel.indentTask] and [TasksViewModel.outdentTask] — the same two commands the
 *    card's menu fires, into the same repository transaction, and the section re-reads what that write left behind.
 *
 * The section is driven by [HierarchyEditor], the production object the fragment hands its binding to, so the
 * availability, the copy, the write, the refresh and the rebuild after a recreation are the real ones. What a JVM
 * test cannot host here is the fragment's own observer wiring (the project has no Hilt test component), so the
 * unsaved-fields claim is proved at the level the editor can reach: the press moves the section and leaves the typed
 * text standing.
 */
@RunWith(RobolectricTestRunner::class)
class HierarchyEditorTest {

    private lateinit var fixture: AppFixture
    private lateinit var app: Context
    private lateinit var hierarchy: TaskHierarchyRepository
    private lateinit var scope: CoroutineScope
    private var userId: Long = 0

    private companion object {
        const val STAMP = 1_700_000_000_000L
    }

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext<Context>()
        fixture = AppFixture(app)
        Dispatchers.setMain(Executor { it.run() }.asCoroutineDispatcher())
        scope = CoroutineScope(Dispatchers.Main)
        userId = runBlocking { fixture.signIn(email = "editor@example.com").id }
        hierarchy = TaskHierarchyRepository(
            fixture.database, fixture.taskDao, fixture.groupDao, fixture.trackingSessionDao, fixture.reminderDao,
            fixture.sessionRepository, fixture.timeBase
        )
    }

    @After
    fun tearDown() {
        // The collector this class installs is the editor's own, so it goes before the storage it reads; and
        // `fixture.close()` still comes before `Dispatchers.resetMain()` — CI run 81's race, unchanged.
        scope.cancel()
        runBlocking { fixture.awaitIdle() }
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

    private fun settle(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            runBlocking { fixture.awaitIdle() }
            shadowOf(Looper.getMainLooper()).idle()
            if (System.currentTimeMillis() > deadline) throw AssertionError("timed out waiting for $what")
            Thread.sleep(10)
        }
        pump()
    }

    /** Let the storage threads finish and the main looper run what they handed back. */
    private fun pump() {
        runBlocking { fixture.awaitIdle() }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun themed(): Context = android.view.ContextThemeWrapper(app, R.style.Theme_TaskPlanner)

    private fun inflated(): FragmentTaskDetailBinding = FragmentTaskDetailBinding.inflate(LayoutInflater.from(themed()))

    /** The section, on a screen the test owns: the real binding and the real view model behind it. */
    private class Bound(val binding: FragmentTaskDetailBinding, val editor: HierarchyEditor)

    private fun bound(model: TasksViewModel, row: Task?): Bound {
        val binding = inflated()
        val editor = HierarchyEditor(binding.root.context, model, binding)
        editor.follow(scope)
        editor.bind(row)
        return Bound(binding, editor)
    }

    /** A screen the profile has already drawn, so the snapshot the section reads is the one the list published. */
    private fun drawn(): TasksViewModel {
        val model = viewModel()
        settle("the render to publish its snapshot") { model.hierarchyContext != null }
        return model
    }

    private fun viewModel() = TasksViewModel(
        fixture.taskRepository, fixture.categoryRepository, fixture.groupRepository,
        fixture.recurrenceRepository, fixture.reminderRepository, fixture.sessionRepository,
        fixture.workTrackingRepository, fixture.workStatisticsRepository, hierarchy
    )

    private fun words(view: View): String = (view as TextView).text.toString()

    /**
     * Whether the reader is actually shown this view — it and every block above it have to be visible.
     *
     * `View.isVisible` answers about the view alone, so a button inside a block the editor has taken off the screen
     * still reports itself visible. The claim below is about what a reader can press, so it has to be measured the
     * way the screen is drawn.
     */
    private fun drawn(view: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (!current.isVisible) return false
            current = current.parent as? View
        }
        return true
    }

    // ── the three values ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the editor names the topic the row is filed under`() {
        val topic = group("office-block")
        val planning = row("planning", position = 0, group = topic.uuid)
        val shown = bound(drawn(), planning)

        assertEquals(
            "the topic row prints the header the screen draws, out of the published snapshot",
            app.getString(R.string.hierarchy_topic_value, "office-block"),
            words(shown.binding.hierarchyTopic)
        )
        assertTrue("and it is a line the reader is shown, not an empty one",
            shown.binding.hierarchySection.isVisible)
    }

    @Test
    fun `a task under no topic says so rather than showing a blank`() {
        val loose = row("planning", position = 0)
        val shown = bound(drawn(), loose)

        assertEquals(
            "a row filed under nothing is told so, in words the app owns",
            app.getString(R.string.hierarchy_topic_value, app.getString(R.string.hierarchy_no_topic)),
            words(shown.binding.hierarchyTopic)
        )
        assertTrue("and the section is on the screen to say it", shown.binding.hierarchySection.isVisible)
    }

    @Test
    fun `the parent row names the parent task, never the row's position`() {
        val topic = group("office-block")
        // The child is stored *above* its own parent, so anything that read the parent out of the sequence — the row
        // before this one, the first row of a block — would name the wrong task here. This is «не выводить родителя
        // из названия или порядка строки» as a fixture rather than as a comment.
        val child = row("draft", parent = "planning", position = 0, group = topic.uuid)
        val parent = row("planning", position = 1, group = topic.uuid)
        assertTrue("the parent is stored later than the row hanging on it: ${parent.order} against ${child.order}",
            parent.order > child.order)

        val shown = bound(drawn(), child)
        assertEquals(
            "and the parent row still names the task the link points at",
            app.getString(R.string.hierarchy_parent_value, "planning"),
            words(shown.binding.hierarchyParent)
        )
    }

    @Test
    fun `a topic's own child says it is the topic's root`() {
        val topic = group("office-block")
        val planning = row("planning", position = 0, group = topic.uuid)
        val shown = bound(drawn(), planning)

        assertEquals(
            "no parent link plus a topic is the topic's own row, said in words",
            app.getString(R.string.hierarchy_parent_value, app.getString(R.string.hierarchy_topic_root)),
            words(shown.binding.hierarchyParent)
        )
    }

    @Test
    fun `a standalone task says it is standalone`() {
        val loose = row("planning", position = 0)
        val shown = bound(drawn(), loose)

        assertEquals(
            "no parent and no topic is a different fact from being a topic's root, and gets its own sentence",
            app.getString(R.string.hierarchy_parent_value, app.getString(R.string.hierarchy_standalone)),
            words(shown.binding.hierarchyParent)
        )
    }

    @Test
    fun `the level the editor prints is the level the policy measures`() {
        val topic = group("office-block")
        val top = row("planning", position = 0, group = topic.uuid)
        val middle = row("draft", parent = "planning", position = 1, group = topic.uuid)
        val deep = row("figures", parent = "draft", position = 2, group = topic.uuid)
        val model = drawn()
        val facts = TaskHierarchyPolicy.factsOf(stored())

        val measured = listOf(top, middle, deep).map { TaskHierarchyPolicy.levelOf(facts, it) }
        assertEquals("one topic and three task levels, counted from one: $measured", listOf(1, 2, 3), measured)

        listOf(top, middle, deep).forEachIndexed { index, task ->
            val shown = bound(model, task)
            assertEquals(
                "the editor prints the level the policy measures for ${task.uuid}, never a number of its own",
                app.getString(R.string.hierarchy_level_value, measured[index]),
                words(shown.binding.hierarchyLevel)
            )
            assertTrue("and a saved task is never printed at level zero: ${task.uuid}", measured[index] >= 1)
        }
    }

    // ── the two verbs ──────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `indent is offered only with a stored sibling to hang on`() {
        val topic = group("office-block")
        val first = row("planning", position = 0, group = topic.uuid)
        val second = row("notes", position = 1, group = topic.uuid)
        val onlyChild = row("draft", parent = "planning", position = 2, group = topic.uuid)
        val model = drawn()

        val head = bound(model, first)
        assertFalse("the set's first row has no stored sibling to hang on",
            head.binding.hierarchyIndentButton.isEnabled)
        assertEquals("and it is told why, in the list's own sentence",
            app.getString(R.string.indent_no_row_above), head.binding.hierarchyIndentButton.text.toString())

        val next = bound(model, second)
        assertTrue("a row with a stored sibling in its own set is offered the move",
            next.binding.hierarchyIndentButton.isEnabled)
        assertEquals("titled by the row it would actually hang the task on — this screen draws no rows, so " +
                "«the row above» would name a place the reader cannot see",
            app.getString(R.string.hierarchy_indent_action, "planning"),
            next.binding.hierarchyIndentButton.text.toString())

        // Two rows of the profile sit above this one and neither is its sibling: the candidate is read out of the
        // row's own set, which is what makes an indent in the editor name the move the domain would write.
        val nested = bound(model, onlyChild)
        assertFalse("a row that is first under its own parent has nothing to hang on",
            nested.binding.hierarchyIndentButton.isEnabled)
        assertEquals(app.getString(R.string.indent_no_row_above),
            nested.binding.hierarchyIndentButton.text.toString())
    }

    /**
     * A stored sibling the profile cannot name is a refusal with no sentence, and so an action the editor hides.
     *
     * `tasks.uuid` defaults to the empty string and a pull, a restored backup or a legacy row can leave it there, so
     * the nearest sibling is a real row the reader sees nothing wrong with — and yet nothing can hang on it, because
     * every parent link is resolved by uuid. [HierarchyMoveRefusal.PARENT_UNNAMED] is one of the two refusals the app
     * has no sentence for, and §6.1's rule is that such an action is drawn nowhere rather than titled with the
     * action's own name; the editor is bound by the same rule, because a disabled button whose reason the app cannot
     * say is a button a reader learns to stop trusting.
     */
    @Test
    fun `a stored sibling the profile cannot name hides the indent rather than leaving a dead button`() {
        val topic = group("office-block")
        val unnamed = row("planning", position = 0, group = topic.uuid, uuid = "")
        val second = row("notes", position = 1, group = topic.uuid)
        val model = drawn()
        val facts = TaskHierarchyPolicy.factsOf(stored())

        assertEquals("the row's nearest stored sibling is the unnamed one", unnamed.id,
            TaskHierarchyPolicy.previousSibling(second, facts)?.id)
        assertEquals("and the domain refuses a press that would write a link to no identity",
            HierarchyMoveRefusal.PARENT_UNNAMED,
            TaskHierarchyPolicy.indentRefusal(second, unnamed, facts))
        assertTrue("which is a refusal the app has no sentence for: a broken notebook, not a choice",
            HierarchyMenu.isSilent(HierarchyMoveRefusal.PARENT_UNNAMED))

        val shown = bound(model, second)
        assertFalse("so the editor takes the action off the screen",
            shown.binding.hierarchyIndentButton.isVisible)
        assertFalse("rather than showing it disabled where a keyboard or switch-access still reaches it",
            drawn(shown.binding.hierarchyIndentButton))
        assertTrue("while the three values the reader can check stay on the screen",
            shown.binding.hierarchySection.isVisible)
        assertEquals("and the lift, which does have a sentence of its own, stays put and explains itself",
            app.getString(R.string.outdent_at_topic_root),
            shown.binding.hierarchyOutdentButton.text.toString())
    }

    @Test
    fun `outdent is refused at the first level under the topic, with a reason the reader can read`() {
        val topic = group("office-block")
        val root = row("planning", position = 0, group = topic.uuid)
        val nested = row("draft", parent = "planning", position = 1, group = topic.uuid)
        val model = drawn()

        val shown = bound(model, root)
        assertFalse("a topic's own row has nowhere further up to go",
            shown.binding.hierarchyOutdentButton.isEnabled)
        assertTrue("and the refusal is a sentence rather than a missing button",
            shown.binding.hierarchyOutdentButton.isVisible && shown.binding.hierarchyOutdentButton.text.isNotBlank())
        assertEquals(app.getString(R.string.outdent_at_topic_root),
            shown.binding.hierarchyOutdentButton.text.toString())

        val lifted = bound(model, nested)
        assertTrue("while a row that does have a parent to leave is offered the lift",
            lifted.binding.hierarchyOutdentButton.isEnabled)
        assertEquals(app.getString(R.string.row_outdent), lifted.binding.hierarchyOutdentButton.text.toString())
    }

    @Test
    fun `indent is refused when the block would pass the third level`() {
        val topic = group("office-block")
        row("planning", position = 0, group = topic.uuid)
        row("notes", parent = "planning", position = 1, group = topic.uuid)
        row("figures", parent = "notes", position = 2, group = topic.uuid)
        val head = row("draft", parent = "planning", position = 3, group = topic.uuid)
        row("receipts", parent = "draft", position = 4, group = topic.uuid)
        val model = drawn()

        // `draft` carries a branch of its own and its stored sibling `notes` already sits at the second level, so
        // hanging the block there would draw a fourth one — which is not the tree the reader was given.
        val shown = bound(model, head)
        assertFalse("the move the depth contract forbids is not pressable",
            shown.binding.hierarchyIndentButton.isEnabled)
        assertEquals("and the reason is the sentence about depth, not a silent button",
            app.getString(R.string.indent_too_deep), shown.binding.hierarchyIndentButton.text.toString())

        // A disabled button is still reachable by `performClick`, so the guard has to be the handler's own.
        shown.binding.hierarchyIndentButton.performClick()
        pump()
        assertEquals("a press that reaches a disabled action still writes nothing",
            "planning", stored().first { it.uuid == "draft" }.parentUuid)
    }

    /**
     * The two screens asked the same question about the same row.
     *
     * What they must agree about is the *refusal*: the domain's reason does not care which screen read it out, and a
     * reader who opens the card's menu and then the editor would otherwise be told two different things about one
     * move. Their titles deliberately differ where the move is allowed, and that is not drift — it is §6.2's ruling
     * on the copy. The list hangs a row on the neighbour drawn above it and says "the row above", which is a fact
     * about its own screen; the editor has no rows on screen at all, so it hangs the row on the nearest stored
     * sibling [TaskHierarchyPolicy.previousSibling] names — which under a filter, or for a row that is first in its
     * own set, is not the same task the list would name. A button on the editor therefore says the name of the task
     * it would write (`hierarchy_indent_action`), and the availability both screens offer stays one computation.
     */
    @Test
    fun `the editor and the list menu name the same refusal for the same row`() {
        val topic = group("office-block")
        row("planning", position = 0, group = topic.uuid)
        val notes = row("notes", position = 1, group = topic.uuid)
        row("draft", parent = "planning", position = 2, group = topic.uuid)
        val sibling = row("receipts", parent = "planning", position = 3, group = topic.uuid)
        row("figures", parent = "draft", position = 4, group = topic.uuid)
        val model = drawn()
        val screen = model.hierarchyContext!!

        // These rows are asked the same question by both screens: the row above them in the block the list draws is
        // also their nearest stored sibling. Where the two legitimately part — a drawn neighbour that is not a
        // sibling at all, which `draft` is in this fixture — the editor keeps the stored rule, and that is held by
        // `indent is offered only with a stored sibling to hang on` and by
        // `the candidate the editor names and the block the list draws answer one offer`.
        val facts = TaskHierarchyPolicy.factsOf(screen.stored)
        listOf(stored().first { it.uuid == "planning" }, notes, sibling).forEach { task ->
            val block = screen.blockFor(task.id).orEmpty()
            assertEquals("the row drawn above ${task.uuid} is the sibling the editor names",
                HierarchyMenu.indentCandidate(task, block)?.id, TaskHierarchyPolicy.previousSibling(task, facts)?.id)

            val offer = HierarchyMenu.offer(task, block, screen.stored)
            val shown = bound(model, task)
            val candidate = TaskHierarchyPolicy.previousSibling(task, facts)
            if (offer.indentReason != null) {
                assertEquals("a refused indent is named by the same sentence on both screens, for ${task.uuid}",
                    app.getString(HierarchyMenu.indentLabel(offer.indentReason)),
                    shown.binding.hierarchyIndentButton.text.toString())
            } else {
                assertEquals("and an allowed one is titled by the task this screen would hang it on: ${task.uuid}",
                    app.getString(R.string.hierarchy_indent_action, candidate?.title),
                    shown.binding.hierarchyIndentButton.text.toString())
                assertNotEquals("which is deliberately not the list's words, whose «row above» this screen never drew",
                    app.getString(HierarchyMenu.indentLabel(offer.indentReason)),
                    shown.binding.hierarchyIndentButton.text.toString())
            }
            assertEquals("the lift needs no such distinction, so both screens name it alike",
                app.getString(HierarchyMenu.outdentLabel(offer.outdentReason)),
                shown.binding.hierarchyOutdentButton.text.toString())
            assertEquals("and the two agree about whether the indent can be pressed for ${task.uuid}",
                offer.canIndent, shown.binding.hierarchyIndentButton.isEnabled)
            assertEquals("and the lift", offer.canOutdent, shown.binding.hierarchyOutdentButton.isEnabled)
        }
    }

    // ── the commands, and the state they leave behind ──────────────────────────────────────────────────────

    @Test
    fun `pressing indent writes through the repository and the section follows the new state`() {
        val topic = group("office-block")
        row("planning", position = 0, group = topic.uuid)
        val notes = row("notes", position = 1, group = topic.uuid)
        val model = drawn()
        val shown = bound(model, notes)
        val candidate = TaskHierarchyPolicy.previousSibling(notes, TaskHierarchyPolicy.factsOf(stored()))

        assertEquals("the press hangs the row on the row the policy named", "planning", candidate?.uuid)
        shown.binding.hierarchyIndentButton.performClick()
        settle("the write to land") {
            runBlocking { fixture.taskDao.getTaskById(notes.id) }?.parentUuid == candidate?.uuid
        }
        assertEquals("and it is the tree that moved, not only the screen",
            "planning", stored().first { it.uuid == "notes" }.parentUuid)

        // The section moved with the tree rather than with a guess made before the write.
        settle("the section to follow the snapshot") {
            words(shown.binding.hierarchyParent) == app.getString(R.string.hierarchy_parent_value, "planning")
        }
        assertEquals("the parent row names the parent that was written",
            app.getString(R.string.hierarchy_parent_value, "planning"), words(shown.binding.hierarchyParent))
        assertEquals("the level is the one the new tree measures",
            app.getString(R.string.hierarchy_level_value, 2), words(shown.binding.hierarchyLevel))
        assertTrue("and the lift the new level allows is offered without another look at storage",
            shown.binding.hierarchyOutdentButton.isEnabled)
    }

    @Test
    fun `an unsaved task gets no hierarchy section at all`() {
        val topic = group("office-block")
        val saved = row("planning", position = 0, group = topic.uuid)
        val model = drawn()
        val fresh = Task(title = "draft", description = "", userId = userId)
        assertEquals("a task with no row yet has no id either command could name", 0L, fresh.id)

        val shown = bound(model, fresh)
        assertFalse("so the whole block stands down rather than speaking of a tree it is not in",
            shown.binding.hierarchySection.isVisible)
        assertFalse("and neither action is left where a reader could press it",
            drawn(shown.binding.hierarchyIndentButton) || drawn(shown.binding.hierarchyOutdentButton))

        val waiting = bound(model, null)
        assertFalse("the same holds before the task has reached the screen at all",
            waiting.binding.hierarchySection.isVisible)

        shown.editor.bind(saved)
        assertTrue("and this editor does show the block the moment a saved row is on screen",
            shown.binding.hierarchySection.isVisible)
    }

    /**
     * A row the published tree no longer holds stands the whole section down.
     *
     * The editor is open on a task and the reader finishes it from the card, or a sync archives it, or it is deleted:
     * the row leaves [TasksViewModel.hierarchyContext] — which is the active list's snapshot — while the fragment is
     * still holding the task it loaded. Every value the block could print then comes from a tree the task is no
     * longer part of: the topic and the parent would be read out of rows that are still there, the level would be
     * measured against them, and both actions would be refused by the same read the domain makes when the write
     * arrives. Saying nothing is the only honest answer left, which is what §6.2's rule for a task with no row yet
     * already decides for an unsaved task; this is the same branch reached from the other side.
     */
    @Test
    fun `a row the published snapshot no longer holds stands the whole section down`() {
        val topic = group("office-block")
        row("planning", position = 0, group = topic.uuid)
        val notes = row("notes", position = 1, group = topic.uuid)
        val model = drawn()
        val shown = bound(model, notes)
        assertTrue("while the row is in the snapshot, the section speaks for it",
            shown.binding.hierarchySection.isVisible)

        runBlocking { fixture.taskRepository.completeAndArchive(notes.id) }
        settle("the archive to leave the published snapshot") {
            model.hierarchyContext!!.stored.none { it.id == notes.id }
        }

        assertFalse("the block stands down rather than printing a topic, a parent and a level it cannot check",
            shown.binding.hierarchySection.isVisible)
        assertFalse("and neither action is left where a reader could press it",
            drawn(shown.binding.hierarchyIndentButton) || drawn(shown.binding.hierarchyOutdentButton))

        // The row is found again by the same editor with nothing re-bound: `held` stayed as the fragment left it, so
        // the next emission is enough — which is the difference between hiding a section and losing one.
        runBlocking { fixture.taskRepository.restoreTask(notes.id) }
        settle("the row to come back into the snapshot") {
            model.hierarchyContext!!.stored.any { it.id == notes.id }
        }
        assertTrue("and the section speaks for the row again", shown.binding.hierarchySection.isVisible)
        assertEquals("with the values the tree holds, not the ones it printed before the archive",
            app.getString(R.string.hierarchy_parent_value, app.getString(R.string.hierarchy_topic_root)),
            words(shown.binding.hierarchyParent))
    }

    @Test
    fun `the editor keeps typed field values across a hierarchy press`() {
        val topic = group("office-block")
        row("planning", position = 0, group = topic.uuid)
        val notes = row("notes", position = 1, group = topic.uuid)
        val model = drawn()
        val shown = bound(model, notes)

        shown.binding.taskTitle.setText("written by hand")
        shown.binding.taskDescription.setText("and not saved yet")
        val levelBefore = words(shown.binding.hierarchyLevel)

        shown.binding.hierarchyIndentButton.performClick()
        settle("the section to move under the typed text") {
            words(shown.binding.hierarchyParent) == app.getString(R.string.hierarchy_parent_value, "planning")
        }

        assertEquals("the indent rewrote the tree, not the title the reader is typing",
            "written by hand", shown.binding.taskTitle.text.toString())
        assertEquals("nor the description", "and not saved yet", shown.binding.taskDescription.text.toString())
        assertNotEquals("and the section really did refresh, so the two lines above are not both just absence",
            levelBefore, words(shown.binding.hierarchyLevel))
    }

    @Test
    fun `the section restores itself after the editor is recreated`() {
        val topic = group("office-block")
        row("planning", position = 0, group = topic.uuid)
        val notes = row("notes", position = 1, group = topic.uuid)
        val shown = bound(drawn(), notes)
        shown.binding.hierarchyIndentButton.performClick()
        settle("the indent to land") {
            runBlocking { fixture.taskDao.getTaskById(notes.id) }?.parentUuid == "planning"
        }
        val topicLine = words(shown.binding.hierarchyTopic)
        val parentLine = words(shown.binding.hierarchyParent)
        val levelLine = words(shown.binding.hierarchyLevel)

        // A recreated Activity is a new fragment, a new view model and a new binding: nothing is handed across, so
        // the block has to rebuild itself from storage. The row passed in is the stale copy on purpose — the same
        // one a recreated screen would be holding before the emission arrives.
        val restoredModel = viewModel()
        settle("the new screen to read the tree it was given") {
            restoredModel.hierarchyContext?.stored?.any { it.id == notes.id && it.parentUuid == "planning" } == true
        }
        val restored = bound(restoredModel, notes)

        assertEquals("the topic comes back", topicLine, words(restored.binding.hierarchyTopic))
        assertEquals("the parent comes back from the written link, not from the row in hand",
            parentLine, words(restored.binding.hierarchyParent))
        assertEquals("the level comes back measured from the new tree",
            levelLine, words(restored.binding.hierarchyLevel))
        assertEquals("and the line it restores is the one the write produced",
            app.getString(R.string.hierarchy_parent_value, "planning"), parentLine)
        assertTrue("and the action the restored state allows is drawn again",
            restored.binding.hierarchyOutdentButton.isEnabled)
    }

    @Test
    fun `the silent refusal draws no action the reader cannot explain`() {
        val topic = group("office-block")
        val doubled = row("planning", position = 0, group = topic.uuid)
        // Two live rows on one identity: which block a press would move has two answers, so neither direction is
        // something a screen can put a sentence on. The list draws no control for such a row at all, and neither may
        // the editor — a button the app cannot explain is a button the reader will stop trusting.
        row("twin", position = 1, group = topic.uuid, uuid = "planning")
        val underDoubled = row("draft", parent = "planning", position = 2, group = topic.uuid)
        val model = drawn()
        val screen = model.hierarchyContext!!

        val head = bound(model, doubled)
        val headOffer = HierarchyMenu.offer(doubled, screen.blockFor(doubled.id).orEmpty(), screen.stored)
        assertTrue("the doubled row's lift has no sentence at all: ${headOffer.outdentReason}",
            HierarchyMenu.isSilent(headOffer.outdentReason))
        assertFalse("so the editor hides it rather than titling it", head.binding.hierarchyOutdentButton.isVisible)
        assertFalse("and the same for its indent", head.binding.hierarchyIndentButton.isVisible)
        assertTrue("while the three values the reader can check are still on the screen",
            head.binding.hierarchySection.isVisible)
        assertEquals(app.getString(R.string.hierarchy_parent_value, app.getString(R.string.hierarchy_topic_root)),
            words(head.binding.hierarchyParent))

        val child = bound(model, underDoubled)
        val facts = TaskHierarchyPolicy.factsOf(screen.stored)
        assertEquals("a lift through a doubled parent is the other refusal nobody can be told a reason for",
            HierarchyMoveRefusal.AMBIGUOUS_PARENT, TaskHierarchyPolicy.outdentRefusal(underDoubled, facts))
        assertFalse("hidden rather than named", child.binding.hierarchyOutdentButton.isVisible)

        val indentReason = TaskHierarchyPolicy.indentRefusal(
            underDoubled, TaskHierarchyPolicy.previousSibling(underDoubled, facts), facts
        )
        assertFalse("and every action left standing is one the app can explain: $indentReason",
            HierarchyMenu.isSilent(indentReason))
        assertTrue(child.binding.hierarchyIndentButton.isVisible)
        assertEquals("titled by the same sentence the menu would put on the row",
            app.getString(HierarchyMenu.indentLabel(indentReason)),
            child.binding.hierarchyIndentButton.text.toString())
    }
}
