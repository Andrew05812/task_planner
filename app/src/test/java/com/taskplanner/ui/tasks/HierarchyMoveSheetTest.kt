package com.taskplanner.ui.tasks

import android.content.Context
import android.content.DialogInterface
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.taskplanner.R
import com.taskplanner.data.hierarchy.TaskHierarchyPolicy
import com.taskplanner.data.hierarchy.TaskHierarchyPolicy.MoveDestination
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog

/**
 * §6.3: the "Move to" sheet — the one place the destination list the domain already answers is put under a reader's
 * finger, and the write that list earns.
 *
 * Task 2 put the whole rule in [TaskHierarchyPolicy.destinationsFor] and [TaskHierarchyPolicy.moveRefusal], and
 * `HierarchyDestinationTest` (test/data/hierarchy) pins what those two answer: which rows are admissible, what level
 * the block lands at, how many tasks travel, which path tells two same-named rows apart, and the empty list a head
 * whose own block cannot be named gets. None of that is re-litigated here. What is left for a screen is four things
 * the brief asks for, and they only exist on one:
 *
 *  * **The sheet draws what it is handed, once per admitted destination, in the policy's order** — and nothing else.
 *    No filter, no sort, no depth measured locally: the UI must not repeat a rule, so the only comparison this file
 *    makes is between what the policy returned over the published snapshot and what a row of the dialog says.
 *  * **Every fact the brief requires per option is on the row**: the topic — or the words that say there is none —
 *    the parent, the level the block would land at, and how many tasks move with it. The last one comes out of a
 *    plurals resource, because a count with "task" glued onto it in Kotlin is a sentence that is wrong for every
 *    number but one.
 *  * **A choice is not a write.** The press opens a confirmation that repeats the whole preview — current path, new
 *    path, count, level — and only that dialog's own button reaches [TasksViewModel.moveTaskUnder]. Which is also
 *    where the refusal is earned: the sheet is built from a snapshot, the tree can change underneath it, and when
 *    the domain refuses, no row moves and no line on the editor claims one did.
 *  * **An empty list is no control at all.** [TaskHierarchyPolicy.destinationsFor] can answer `emptyList()`; a
 *    dialog with nothing in it is a dead button, so the editor does not draw the button either.
 *
 * The rows are built by [MoveDestinationAdapter] from `item_move_destination.xml`, on the editor's own published
 * snapshot ([TasksViewModel.hierarchyContext]) — [TaskHierarchyPolicy.factsOf] over `stored`, the whole profile,
 * never the filtered screen — and the button is `hierarchyMoveButton` in `fragment_task_detail.xml`'s
 * `hierarchy_section`, run through [HierarchyEditor] exactly like the two moves §6.2 put there. Robolectric gives
 * real box arithmetic and no glyph widths, so every width claim here is measured and none is about text: whether
 * Russian fits at 320dp with the font at 1.3, what TalkBack really says, and how the sheet reads in light and dark
 * belong to §11's device matrix.
 *
 * **1B turned the panel into a list, and the walking follows.** The sheet now inflates `dialog_move_to.xml` and hands
 * its destinations to a `ListAdapter` over a `RecyclerView`, so a row is one of the recycler's *bound children* rather
 * than one of a stack this file walked: [rowsOf] measures and lays the panel out at the narrow width, then reads the
 * children the list bound. The layout is asked for here because Robolectric gives a dialog no window to inherit a
 * measure spec from — the height the sheet gets from a handset is a §11 frame — and [MoveToDialog.open] submits its
 * list synchronously, so nothing in this file races a background diff. Every test name §6.3 wrote is still below with
 * the assertions it wrote, and the cases under «the sheet as a list» are 1B's own; only the way a row is reached
 * changed.
 */
@RunWith(RobolectricTestRunner::class)
class HierarchyMoveSheetTest {

    private lateinit var fixture: AppFixture
    private lateinit var app: Context
    private lateinit var hierarchy: TaskHierarchyRepository
    private lateinit var scope: CoroutineScope
    private var userId: Long = 0

    private companion object {
        const val STAMP = 1_700_000_000_000L

        /** The narrowest width the app is designed against, and the one the card budget was photographed at. */
        const val NARROW_DP = 320

        /**
         * A sheet height that holds every destination these fixtures can admit, so the row-by-row claims read a list
         * rather than a viewport. The recycling claim measures the opposite height, and says so where it is made.
         */
        const val FULL_SHEET_HEIGHT_PX = 4000

        /**
         * A sheet the height of a small phone screen: enough for a handful of destination rows, nowhere near enough
         * for the long list, which is the point the recycling claim is measured at.
         */
        const val SHEET_VIEWPORT_HEIGHT_PX = 240

        /**
         * A destination list long enough that a panel which inflated it all would be the finding, not the design.
         *
         * Two hundred is not the §14 figure — it is the smallest number that a `LinearLayout` stack would build
         * every row of, while a recycler with a phone-sized viewport binds a handful, which is the property 1B
         * exists to buy.
         */
        const val LONG_SHEET_DESTINATIONS = 200
    }

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext<Context>()
        fixture = AppFixture(app)
        Dispatchers.setMain(Executor { it.run() }.asCoroutineDispatcher())
        scope = CoroutineScope(Dispatchers.Main)
        userId = runBlocking { fixture.signIn(email = "sheet@example.com").id }
        hierarchy = TaskHierarchyRepository(
            fixture.database, fixture.taskDao, fixture.groupDao, fixture.trackingSessionDao, fixture.reminderDao,
            fixture.sessionRepository, fixture.timeBase
        )
    }

    @After
    fun tearDown() {
        // The editor's collector is this test's own, so it goes before the storage it reads; and `fixture.close()`
        // still comes before `Dispatchers.resetMain()` — CI run 81's race, unchanged.
        scope.cancel()
        runBlocking { fixture.awaitIdle() }
        fixture.close()
        Dispatchers.resetMain()
    }

    // ── the fixture, and the screen around it ──────────────────────────────────────────────────────────────

    /** Topics get their own `sort_order`: left at the default, two of them tie and the query planner picks. */
    private var nextSortOrder = 0

    private fun group(name: String): TaskGroup = runBlocking {
        val group = TaskGroup(
            uuid = name, userId = userId, name = name, sortOrder = nextSortOrder++,
            syncState = SyncState.SYNCED, createdAt = STAMP, updatedAt = STAMP
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

    /** Everything a move could damage, in one comparable value: link, topic, position, push state. */
    private fun shape(): List<List<Any?>> = stored().map {
        listOf(it.uuid, it.parentUuid, it.groupId, it.order, it.syncState)
    }

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

    /** Lets the storage threads finish and the main looper run what they handed back. */
    private fun pump() {
        runBlocking { fixture.awaitIdle() }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun themed(): Context = android.view.ContextThemeWrapper(app, R.style.Theme_TaskPlanner)

    private fun inflated(): FragmentTaskDetailBinding =
        FragmentTaskDetailBinding.inflate(LayoutInflater.from(themed()))

    /** The editor, on a screen the test owns: the real binding, the real view model, the real snapshot behind it. */
    private class Bound(val binding: FragmentTaskDetailBinding, val editor: HierarchyEditor)

    private fun bound(model: TasksViewModel, row: Task?): Bound {
        val binding = inflated()
        val editor = HierarchyEditor(binding.root.context, model, binding)
        editor.follow(scope)
        editor.bind(row)
        return Bound(binding, editor)
    }

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
     * `View.isVisible` answers about the view alone, so a button inside a section the editor has taken off the
     * screen still reports itself visible; the claims here are about what a reader can press.
     */
    private fun drawn(view: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (!current.isVisible) return false
            current = current.parent as? View
        }
        return true
    }

    // ── the sheet, read the way the reader reads it ────────────────────────────────────────────────────────

    /** The editor with its Move-to button pressed: the dialog the press put up, and the screen that opened it. */
    private fun openSheet(model: TasksViewModel, head: Task): Pair<Bound, AlertDialog> {
        val shown = bound(model, head)
        val before = ShadowDialog.getLatestDialog()
        assertTrue("the Move-to control is on the screen before it is pressed",
            drawn(shown.binding.hierarchyMoveButton))
        shown.binding.hierarchyMoveButton.performClick()
        pump()
        val dialog = ShadowDialog.getLatestDialog()
        assertNotNull("pressing it puts a sheet on the screen", dialog)
        assertNotSame("and it is the dialog this press opened, not one an earlier claim left up", before, dialog)
        return shown to dialog as AlertDialog
    }

    private fun rowsOf(sheet: AlertDialog): List<View> {
        val list = sheetList(sheet)
        return (0 until list.childCount).map { list.getChildAt(it) }
    }

    /**
     * The sheet's list, laid out at the width the app is designed against, with its bound rows ready to read.
     *
     * 1B made the panel a `RecyclerView`, and a recycler binds on layout rather than on construction: under
     * Robolectric the dialog has no window to inherit a measure spec from, so the height the sheet would get from
     * the handset is asked for here instead — [FULL_SHEET_HEIGHT_PX], tall enough that every destination these
     * fixtures admit fits inside it, which is what lets the row-by-row claims below compare a list against a list.
     * The height the *recycling* claim needs is the opposite one, and `the sheet holds its destinations in the
     * adapter and binds only the rows that fit` measures it on its own.
     *
     * Nothing here waits on a diff: [MoveToDialog.open] submits its list synchronously, so the adapter already holds
     * the destinations by the time the press returns, and the children walked below are the rows the reader is shown.
     */
    private fun sheetList(sheet: AlertDialog): RecyclerView {
        val list = sheet.findViewById<RecyclerView>(R.id.moveDestinationList)
        assertNotNull("the sheet holds a recycler of destinations", list)
        val width = (NARROW_DP * app.resources.displayMetrics.density).toInt()
        list!!.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(FULL_SHEET_HEIGHT_PX, View.MeasureSpec.EXACTLY)
        )
        list.layout(0, 0, list.measuredWidth, list.measuredHeight)
        return list
    }

    /**
     * The heading a dialog paints.
     *
     * The app builds its dialogs through Material, whose controller keeps no title getter a test can ask, so the
     * screen is read the way a reader reads it: out of the view the alert layout puts the title in.
     */
    private fun titleText(dialog: AlertDialog): String =
        dialog.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)?.text?.toString()
            ?: throw AssertionError("the dialog was put up without a title to read")

    /** The message body a dialog paints, from the id the alert layout gives it. */
    private fun messageText(dialog: AlertDialog): String =
        dialog.findViewById<TextView>(android.R.id.message)?.text?.toString()
            ?: throw AssertionError("the dialog was put up without a message to read")

    /** The three lines one row prints. */
    private fun titleLine(row: View): String = words(row.findViewById(R.id.moveDestinationTitle))

    private fun pathLine(row: View): String = words(row.findViewById(R.id.moveDestinationPath))

    private fun metaLine(row: View): String = words(row.findViewById(R.id.moveDestinationMeta))

    // ── the sentences this file expects, assembled out of the app's own resources ──────────────────────────

    /**
     * The arrow, from the resource, between legs.
     *
     * `%1$s → %2$s` is the whole of the app's path punctuation — the `goal_change_format` precedent — so a test that
     * joined legs with a literal arrow would be checking its own copy rather than the screen's.
     */
    private fun pathOf(legs: List<String>): String = when (legs.size) {
        0 -> ""
        1 -> legs.single()
        else -> legs.reduce { left, right -> app.getString(R.string.move_path_format, left, right) }
    }

    private fun pathOf(vararg legs: String): String = pathOf(legs.toList())

    /** The travelling count, out of the plurals block — never a number with a noun pasted onto it. */
    private fun travelling(count: Int): String =
        app.resources.getQuantityString(R.plurals.move_travelling_count, count, count)

    private fun levelText(number: Int): String = app.getString(R.string.move_level_format, number)

    /** The name a row puts at the top: the task the block would hang on, the topic, or the standalone tree. */
    private fun titleOf(destination: MoveDestination): String =
        destination.parent?.title ?: destination.topic?.name ?: app.getString(R.string.move_to_standalone)

    /**
     * The legs a row has to show: the topic's name when there is one — [TaskHierarchyPolicy.destinationsFor] already
     * puts it first in `pathTitles` — and the words that say there is none when there is not.
     */
    private fun legsOf(destination: MoveDestination): List<String> =
        (if (destination.topic == null) listOf(app.getString(R.string.move_topic_none)) else emptyList()) +
            destination.pathTitles

    /** The row's third line: the level the block lands at, and how many tasks travel to it. */
    private fun metaOf(destination: MoveDestination): String = app.getString(
        R.string.move_row_details_format, levelText(destination.resultingLevel), travelling(destination.travellingCount)
    )

    private fun textsOf(destination: MoveDestination): List<String> =
        listOf(titleOf(destination), pathOf(legsOf(destination)), metaOf(destination))

    private fun rowTexts(row: View): List<String> = listOf(titleLine(row), pathLine(row), metaLine(row))

    private fun rowTexts(rows: List<View>): List<List<String>> = rows.map { rowTexts(it) }

    private fun listTexts(destinations: List<MoveDestination>): List<List<String>> =
        destinations.map { textsOf(it) }

    /**
     * The preview the confirmation is required to print.
     *
     * [currentLegs] is handed in as the fixture's own answer — this file knows the tree it seeded — so the claim is
     * about what the dialog says, not about whichever walk the production code happened to take.
     */
    private fun previewOf(currentLegs: List<String>, destination: MoveDestination): String = app.getString(
        R.string.move_confirm_message,
        app.getString(R.string.move_current_path_format, pathOf(currentLegs)),
        app.getString(R.string.move_new_path_format, pathOf(legsOf(destination))),
        travelling(destination.travellingCount),
        levelText(destination.resultingLevel)
    )

    /** The destination the policy admits that hangs the block on the row with this uuid, or the test has no row. */
    private fun admissible(list: List<MoveDestination>, parentUuid: String): MoveDestination =
        list.firstOrNull { it.parent?.uuid == parentUuid }
            ?: throw AssertionError("the policy admits no move onto $parentUuid: $list")

    /**
     * The row of the sheet the reader would press for [destination].
     *
     * Found by what the rows say rather than by a position this file guessed, so a reordered list is a finding about
     * the list instead of an index out of bounds, and a destination drawn twice is caught here too.
     */
    private fun rowFor(rows: List<View>, list: List<MoveDestination>, destination: MoveDestination): View {
        val at = listTexts(list).indexOf(textsOf(destination))
        assertTrue("the sheet draws the destination the policy admitted: $destination", at >= 0)
        assertEquals("and it draws it exactly once", at, rowTexts(rows).indexOf(listTexts(list)[at]))
        return rows[at]
    }

    /** The domain's own list over the published snapshot — the one number and order the sheet is held to. */
    private fun admitted(head: Task, screen: TasksViewModel.HierarchyScreen): List<MoveDestination> =
        TaskHierarchyPolicy.destinationsFor(
            head = head,
            topics = screen.topics,
            profileTopicUuids = screen.topics.map { it.uuid }.toSet(),
            facts = TaskHierarchyPolicy.factsOf(screen.stored)
        )

    // ── what the sheet draws ───────────────────────────────────────────────────────────────────────────────

    /**
     * The sheet is the policy's list, rendered.
     *
     * One row per admitted destination, in the policy's order, with nothing added: the half of «не показывай
     * недопустимые варианты как кликабельные» that is left to a screen once Task 2 settled which variants are
     * admissible. The comparison is against [TaskHierarchyPolicy.destinationsFor] called here over the published
     * snapshot, so a row the screen invented, a row it dropped and a row it re-ordered are all findings.
     */
    @Test
    fun `move to offers every destination the policy admits`() {
        val office = group("office-block")
        val errand = group("errand-block")
        row("planning", position = 0, group = office.uuid)
        row("notes", parent = "planning", position = 1, group = office.uuid)
        row("receipts", position = 2, group = errand.uuid)
        val head = row("draft", position = 3)
        // A second root of the unfiled tree. The head sits on the first one, so `(no topic, no parent)` is its own
        // position and the policy withholds it: before this row the answer was exactly five and the guard below sat on
        // its measured value. This is a place the block genuinely could go, and it gives that floor its slack back —
        // and a sixth row to count, so the exclusion is measured rather than assumed.
        row("figures", position = 4)
        val model = drawn()
        val screen = model.hierarchyContext!!
        val admitted = admitted(head, screen)
        assertTrue("the fixture admits more than one row to draw, or this proves nothing: $admitted",
            admitted.size >= 5)
        // The margin above that floor, stated as a number rather than as a floor: 1A's exclusion left this fixture at
        // exactly five, so the next legitimate place to withhold would have fired the guard instead of shortening a
        // list. Six is what the tree below actually admits — two topic roots, three of their rows, and one more root
        // of the unfiled tree — with the head's own position the only place taken off.
        assertEquals("the sheet is handed six destinations and the guard above has a row of slack: $admitted",
            6, admitted.size)

        val (_, sheet) = openSheet(model, head)
        assertEquals("the heading is the app's own sentence, so the dialog has a title a reader can be told",
            app.getString(R.string.move_to_title), titleText(sheet))

        val rows = rowsOf(sheet)
        assertEquals("one row per admitted destination, and not one more", admitted.size, rows.size)
        assertEquals("each saying the topic, the parent, the level and the count of the destination standing there",
            listTexts(admitted), rowTexts(rows))
    }

    /**
     * «Для каждого варианта заранее показывай: тему; родителя; итоговую глубину.»
     *
     * A bare title would not survive the tree: the same words appear at two depths of one notebook, and a row that
     * only repeats them cannot say which one the block lands on. The nested destinations here are the proof — their
     * path lines carry every ancestor above them, in the order a reader walks down, and end at the row they name.
     */
    @Test
    fun `a path is shown for each row, not a bare title`() {
        val office = group("office-block")
        row("planning", position = 0, group = office.uuid)
        val middle = row("notes", parent = "planning", position = 1, group = office.uuid)
        val head = row("draft", position = 2)
        val model = drawn()
        val list = admitted(head, model.hierarchyContext!!)

        val (_, sheet) = openSheet(model, head)
        val rows = rowsOf(sheet)
        val onPlanning = rowFor(rows, list, admissible(list, "planning"))
        val onNotes = rowFor(rows, list, admissible(list, "notes"))

        assertEquals("the shallow one is named by its topic and by itself",
            pathOf("office-block", "planning"), pathLine(onPlanning))
        assertEquals("and the deeper one carries the ancestor above it too: topic, parent, subtask",
            pathOf("office-block", "planning", "notes"), pathLine(onNotes))
        assertNotEquals("which is exactly what a bare title cannot say — these two rows wear one name",
            titleLine(onNotes), pathLine(onNotes))
        assertEquals("the path ends at the parent the row is about", middle.title, titleLine(onNotes))
        assertTrue("and every row of the sheet has a path line at all, not only the interesting ones — with the " +
            "rows read rather than assumed", rows.isNotEmpty() && rows.all { pathLine(it).isNotBlank() })
    }

    /**
     * «Не показывай одинаковое название без контекста, если оно встречается в разных ветках.»
     *
     * Task 2 proved the domain answers two different `pathTitles` for two rows of one title; a sheet printing only
     * titles would throw that answer away. Both rows are drawn here, both wear the same title, and the lines under
     * them are what tells them apart — so the pair reads as two decisions rather than as one row twice.
     */
    @Test
    fun `two rows with the same title are told apart by their paths`() {
        val office = group("office-block")
        row("planning", position = 0, group = office.uuid)
        row("notes", parent = "planning", position = 1, group = office.uuid, uuid = "notes-under-planning")
        row("receipts", position = 2, group = office.uuid)
        row("notes", parent = "receipts", position = 3, group = office.uuid, uuid = "notes-under-receipts")
        val head = row("figures", position = 4)
        val model = drawn()
        val list = admitted(head, model.hierarchyContext!!)

        val twins = list.filter { it.parent?.title == "notes" }
        assertEquals("both branches admit the block, so both are drawn", 2, twins.size)

        val (_, sheet) = openSheet(model, head)
        val rows = rowsOf(sheet).filter { titleLine(it) == "notes" }
        assertEquals("two rows wearing one title", 2, rows.size)
        assertEquals("and one path each, out of the tree rather than out of the name",
            setOf(
                pathOf("office-block", "planning", "notes"),
                pathOf("office-block", "receipts", "notes")
            ),
            rows.map { pathLine(it) }.toSet()
        )
        assertTrue("not one of them is left to repeat the bare title", rows.all { pathLine(it) != titleLine(it) })
    }

    /**
     * The standalone tree is a destination, and is said as one.
     *
     * `Task.groupId` is nullable and the renderer draws those rows under no header, so "unfiled" is a place the block
     * can live rather than a defect — and the row has to say it without a topic name to print. Its path line carries
     * the app's own «No topic» leg, its title the standalone tree itself.
     */
    @Test
    fun `the standalone tree is offered as a destination without a topic`() {
        val office = group("office-block")
        val head = row("planning", position = 0, group = office.uuid)
        row("draft", position = 1)
        row("notes", parent = "draft", position = 2)
        // A second row of the same topic, for the last claim below: the head sits on the topic's own root, which the
        // policy no longer hands over as a destination (it is where the block already is), so the topic's rows are what
        // proves the sheet has not merged the two trees into one list.
        row("figures", position = 3, group = office.uuid)
        val model = drawn()
        val list = admitted(head, model.hierarchyContext!!)

        val root = list.single { it.topic == null && it.parent == null }
        val underDraft = admissible(list, "draft")
        assertEquals("the unfiled tree's own root is the shallowest place that tree has", 1, root.resultingLevel)
        assertTrue("and its rows are destinations too: $list", list.any { it.topic == null && it.parent != null })

        val (_, sheet) = openSheet(model, head)
        val rows = rowTexts(rowsOf(sheet))
        assertTrue("the unfiled tree is drawn with no topic name and the words that say there is none",
            rows.contains(listOf(app.getString(R.string.move_to_standalone),
                app.getString(R.string.move_topic_none), metaOf(root))))
        assertTrue("and a task of that tree is drawn under the same no-topic leg",
            rows.contains(listOf("draft",
                pathOf(app.getString(R.string.move_topic_none), "draft"), metaOf(underDraft))))
        assertTrue("while the topic's own rows still wear their topic, so the two trees are not merged into one " +
            "undifferentiated list", rows.any { it[1].startsWith("office-block") })
    }

    /**
     * The level a row promises is [MoveDestination.resultingLevel]: where the *head* of the block lands, not where
     * the row it was clicked on sits.
     *
     * A sheet printing the parent's own level would tell the reader the block was going one level shallower than the
     * write makes it — and §10 records that a fourth indent is a card the design has no room for.
     */
    @Test
    fun `each row states the level the block would land at`() {
        val office = group("office-block")
        row("planning", position = 0, group = office.uuid)
        val second = row("notes", parent = "planning", position = 1, group = office.uuid)
        val head = row("figures", position = 2, group = office.uuid)
        val model = drawn()
        val list = admitted(head, model.hierarchyContext!!)
        assertEquals("one topic, three task levels, and a leaf fits at every one of them: $list",
            setOf(1, 2, 3), list.map { it.resultingLevel }.toSet())

        val (_, sheet) = openSheet(model, head)
        val rows = rowsOf(sheet)
        assertEquals("every row states the level of the destination it was handed",
            list.map { metaOf(it) }, rows.map { metaLine(it) })

        val deepest = admissible(list, second.uuid)
        assertEquals("the deepest place the contract allows is on offer", 3, deepest.resultingLevel)
        val deepestLine = metaLine(rowFor(rows, list, deepest))
        assertTrue("and the sheet prints that level for it, not the parent's own: $deepestLine",
            deepestLine.contains(levelText(3)))
        assertFalse("which is a different number from the second level the parent sits at: $deepestLine",
            deepestLine.contains(levelText(2)))
        listOf(1, 2).forEach { wanted ->
            val destination = list.first { it.resultingLevel == wanted }
            assertTrue("and a row promised level $wanted is drawn saying level $wanted",
                metaLine(rowFor(rows, list, destination)).contains(levelText(wanted)))
        }
    }

    /**
     * The count is a plural, on both halves of the claim.
     *
     * «сколько задач переместится вместе с выбранной веткой» is a number a reader can check against their own tree,
     * and the forms a concatenated "task(s)" produces are the ones whoever wrote the joiner guessed. The sheet is
     * held here to [android.content.res.Resources.getQuantityString] for the count its own destination carries —
     * one row for a leaf, three for a branch — and `PluralsContractTest` plus `scripts/check-localization.py` are
     * the gates on the block itself.
     */
    @Test
    fun `each row states how many tasks travel, in a plural that matches the number`() {
        val office = group("office-block")
        // A second topic, so the three-tall branch has somewhere to be offered that is not its own position: its head
        // sits on the office root, the block is too tall to fit under any row of any topic, and the destination that
        // used to make the sheet's row count reachable was the very row the policy now withholds.
        group("errand-block")
        // A third topic, for the same reason one more time: with the block's own position withheld, the sheet was
        // drawing exactly two rows, and a floor sitting on its measured value turns the next legitimate exclusion into
        // a failing guard rather than a shorter list. This one adds a destination the write really would make.
        group("family-block")
        val branch = row("planning", position = 0, group = office.uuid)
        row("notes", parent = "planning", position = 1, group = office.uuid)
        row("figures", parent = "notes", position = 2, group = office.uuid)
        val leaf = row("draft", position = 3, group = office.uuid)
        val model = drawn()

        val branchList = admitted(branch, model.hierarchyContext!!)
        assertTrue("the head carries two rows below it, so three travel: $branchList",
            branchList.isNotEmpty() && branchList.all { it.travellingCount == 3 })
        val (_, branchSheet) = openSheet(model, branch)
        val branchRows = rowsOf(branchSheet)
        assertTrue("the branch has destinations to state its count on, or `all {}` below is vacuous: " +
            "${branchRows.size}", branchRows.size >= 2)
        // The margin again as a number: a block three rows tall fits under no parent in any topic, so only the roots
        // are admissible — the two other topics' and the unfiled tree's own — and 1A's exclusion left this floor with
        // nothing above it.
        assertEquals("and the guard above has a row of slack — three places for a three-tall branch: " +
            "${branchRows.size}", 3, branchRows.size)
        assertTrue("every row of that sheet says it in the form the resource has for three",
            branchRows.all { metaLine(it).contains(travelling(3)) })
        assertTrue("and not in the form for one — the count on the row is the count of the block",
            branchRows.all { !metaLine(it).contains(travelling(1)) })

        val leafList = admitted(leaf, model.hierarchyContext!!)
        assertTrue("a row with nothing under it moves one: $leafList",
            leafList.isNotEmpty() && leafList.all { it.travellingCount == 1 })
        val (_, leafSheet) = openSheet(model, leaf)
        val leafRows = rowsOf(leafSheet)
        assertTrue("and the leaf has rows of its own to read: ${leafRows.size}", leafRows.size >= 2)
        assertTrue("and the sheet says that in the singular the resources carry",
            leafRows.all { metaLine(it).contains(travelling(1)) })
        assertNotEquals("which is a different sentence from the one three tasks get",
            travelling(3), travelling(1))
    }

    /**
     * The head and its own branch are not destinations — and the list is not empty for that reason.
     *
     * Task 2 refuses them at the rule and at the door; what is left for a screen is not to draw them anyway, because
     * a reader who pressed one would watch a dialog refuse a move that same dialog had promised. The positive control
     * is the point of the case: without it "none of these titles is on the sheet" is satisfied by a sheet that draws
     * nothing at all, so a row *outside* the branch that the domain does admit is checked for as well.
     */
    @Test
    fun `the head and its own descendants are not on the sheet`() {
        val office = group("office-block")
        val errand = group("errand-block")
        // The head hangs on a row of its topic rather than sitting on the topic's root: for a head already at a
        // topic's root that root is its own position, which the policy no longer hands the sheet over, and the claim
        // below about *both topics' own roots* would then be a claim about one of them. Seating the head one level
        // down keeps the claim whole and adds a row the sheet has to leave alone as well.
        row("figures", position = 0, group = office.uuid)
        val head = row("planning", parent = "figures", position = 1, group = office.uuid)
        val child = row("notes", parent = "planning", position = 2, group = office.uuid)
        val outside = row("receipts", position = 3, group = errand.uuid)
        val model = drawn()

        val carried = listOf(head.title, child.title)
        val admitted = admitted(head, model.hierarchyContext!!)
        assertEquals("the domain leaves neither of them on the list", emptyList<String>(),
            admitted.mapNotNull { it.parent?.title }.filter { it in carried })

        val (_, sheet) = openSheet(model, head)
        val rows = rowsOf(sheet)
        val titles = rows.map { titleLine(it) }
        assertEquals("and neither does the sheet", emptyList<String>(), titles.filter { it in carried })
        assertTrue("while it still draws the places the block can go — a sheet with no rows at all would pass " +
            "the assertion above: $titles", titles.size >= 3)
        assertEquals("and a row outside the branch, which the domain admits, is drawn exactly once",
            1, rows.count { titleLine(it) == outside.title })
        assertTrue("including both topics' own roots",
            titles.contains("office-block") && titles.contains("errand-block"))
    }

    // ── the sheet as a list ────────────────────────────────────────────────────────────────────────────────

    /**
     * The destinations live in an adapter, and the panel builds only the rows it can show.
     *
     * 1B's own claim, and the one the previous shape could not make: the sheet used to inflate one four-view row per
     * stored destination into a `LinearLayout` inside a `ScrollView` before the dialog was even shown, so the list's
     * length was the reader's profile — the §14 10k case, paid on the main thread for a choice between two of those
     * rows. A `RecyclerView` holds the count instead, and binds what its viewport fits.
     *
     * Both halves are measured, because either alone is vacuous: the adapter has to answer the whole count (a panel
     * that bound *nothing* would also bind "fewer than the list holds"), and the bound children have to be strictly
     * fewer than that count (a stack that inflated everything would bind them all, and would still say the count is
     * 200). The rows that are bound are the *top of the list the sheet was handed*, in the policy's order — so the
     * shrinking is a viewport's doing rather than a list the panel quietly dropped. The destination values are
     * synthesised rather than seeded, because the claim is about views: the sheet owns no rule, so what it does with
     * a list it is handed is the same whatever produced the list.
     */
    @Test
    fun `the sheet holds its destinations in the adapter and binds only the rows that fit`() {
        val office = group("office-block")
        val head = row("planning", position = 0, group = office.uuid)
        val model = drawn()
        val screen = model.hierarchyContext!!
        val destinations = syntheticDestinations(LONG_SHEET_DESTINATIONS)

        val sheet = MoveToDialog.open(themed(), head, screen, destinations) {}
        val list = sheet.findViewById<RecyclerView>(R.id.moveDestinationList)
        assertNotNull("the sheet's destinations sit in a recycler, not in a stack of views", list)
        val adapter = list!!.adapter
        assertTrue("and the app's own list machinery drives it, the way every other list here is driven: $adapter",
            adapter is ListAdapter<*, *>)
        assertEquals("the whole destination list is held by that adapter, which is what the reader scrolls through",
            LONG_SHEET_DESTINATIONS, adapter!!.itemCount)

        // A sheet-height viewport rather than the full list: the measure the dialog's own panel gets from a window,
        // asked for here because Robolectric gives a view no window to inherit one from.
        val width = (NARROW_DP * app.resources.displayMetrics.density).toInt()
        val widthSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY)
        list.measure(
            widthSpec,
            View.MeasureSpec.makeMeasureSpec(SHEET_VIEWPORT_HEIGHT_PX, View.MeasureSpec.EXACTLY)
        )
        list.layout(0, 0, list.measuredWidth, list.measuredHeight)
        val boundInViewport = list.childCount
        assertTrue("the recycler binds the rows that fit — $boundInViewport of them, for " +
            "${destinations.size} destinations", boundInViewport > 0)
        assertTrue("and strictly fewer than the list it holds: $boundInViewport bound of ${destinations.size}",
            boundInViewport < destinations.size)
        assertEquals("the row it bound first is the first destination it was handed, so the count was not met by " +
            "dropping rows on the way", textsOf(destinations.first()), rowTexts(list.getChildAt(0)))

        // The contrast that keeps the two counts above from being satisfied by a panel that binds nothing: the same
        // list, measured with a taller sheet, binds strictly more rows out of the same adapter. A stack that inflated
        // every destination would answer the first measure with all of them and fail `boundInViewport <
        // destinations.size`; a panel that bound nothing would fail the measure above it.
        list.measure(widthSpec, View.MeasureSpec.makeMeasureSpec(FULL_SHEET_HEIGHT_PX, View.MeasureSpec.EXACTLY))
        list.layout(0, 0, list.measuredWidth, list.measuredHeight)
        assertTrue("a taller sheet binds more of the same list — $boundInViewport rows at " +
            "${SHEET_VIEWPORT_HEIGHT_PX}px against ${list.childCount} rows at ${FULL_SHEET_HEIGHT_PX}px",
            list.childCount > boundInViewport)
    }

    /**
     * A destination list long enough to tell a recycler from a stack of views, built without a profile.
     *
     * Each entry is exactly the shape [TaskHierarchyPolicy.destinationsFor] returns — a topic, a row above the block,
     * the level the head lands at, the count that travels and the path that tells the row apart — so the rows the
     * adapter binds state the same four facts a real destination does. The titles are the test's own data, never a
     * resource label, and are read back off the bound row rather than compared to anything the app renders.
     */
    private fun syntheticDestinations(count: Int): List<MoveDestination> = (1..count).map { index ->
        val topicName = "topic-block-$index"
        MoveDestination(
            topic = TaskGroup(uuid = "topic-$index", userId = userId, name = topicName),
            parent = Task(
                title = "row-$index", description = "", uuid = "row-$index", userId = userId,
                groupId = "topic-$index", order = index
            ),
            resultingLevel = 2,
            travellingCount = 1,
            pathTitles = listOf(topicName, "row-$index")
        )
    }

    // ── the diff the list runs on ──────────────────────────────────────────────────────────────────────────

    /**
     * One row per place in the tree — the identity half of the diff the sheet's list runs on.
     *
     * The sheet's list is rebuilt from a fresh measurement of the tree on every press, and the editor does
     * re-measure the destinations on every snapshot it publishes — so the same place regularly arrives as a newly read
     * copy of it. Identity is the pair of uuids (topic, parent) rather than the whole value precisely so that such a
     * re-read is not a rebuild: an adapter that already holds the place keeps its row where it is, and a reader is
     * not left watching a sheet they are aiming at jump. The submission today's dialog makes is one insert into an
     * empty adapter, so this is the callback's contract, held to its letter here rather than inferred from the panel. `(no topic, no parent)` — the unfiled tree's own root — is a place
     * like any other, and a `null` on one side must not match a topic on the other, or the standalone root and a
     * topic's root would become one row wearing two different names.
     */
    @Test
    fun `the list knows one place in the tree from another`() {
        val reRead = place(topic = "office-block", parent = "planning", level = 3)
        assertTrue("the same topic and the same parent are one row, even when the level it promises has moved on: " +
            "a re-read is not a rebuild",
            MoveDestinationAdapter.DIFF.areItemsTheSame(place(topic = "office-block", parent = "planning"), reRead))
        assertFalse("a different row above the block inside the same topic is a different place",
            MoveDestinationAdapter.DIFF.areItemsTheSame(
                place(topic = "office-block", parent = "planning"),
                place(topic = "office-block", parent = "notes")
            ))
        assertFalse("and the unfiled tree's root is not a topic's root, although both have no parent — one `null` " +
            "never matches a topic",
            MoveDestinationAdapter.DIFF.areItemsTheSame(
                place(topic = null, parent = null),
                place(topic = "office-block", parent = null)
            ))
        assertTrue("read twice, the unfiled root is still the one row it is",
            MoveDestinationAdapter.DIFF.areItemsTheSame(
                place(topic = null, parent = null),
                place(topic = null, parent = null)
            ))
    }

    /**
     * The other half of the same question: a row that stayed put but now says something different.
     *
     * Content is the whole [MoveDestination] value, because every field of it is printed — the level the block lands
     * at, how many tasks travel, the path that tells two same-named rows apart. A destination read twice from an
     * unchanged tree must not rebind anything (the sheet is republished while a reader is looking at it), and one
     * whose facts moved has to rebind rather than keep the stale number on screen.
     */
    @Test
    fun `the list knows when the row it holds has stopped saying the same thing`() {
        val shallow = place(topic = "office-block", parent = "planning", level = 2)
        assertFalse("the level a row promises is content, so a deeper answer rebinds the row it belongs to",
            MoveDestinationAdapter.DIFF.areContentsTheSame(shallow, place(topic = "office-block", parent = "planning", level = 3)))
        assertFalse("and so is the count that travels with the block",
            MoveDestinationAdapter.DIFF.areContentsTheSame(shallow, place(topic = "office-block", parent = "planning", travelling = 4)))
        assertTrue("two reads of the same place with the same facts are one value — nothing is rebound for a change " +
            "that is not a change",
            MoveDestinationAdapter.DIFF.areContentsTheSame(
                shallow,
                place(topic = "office-block", parent = "planning", level = 2)
            ))
    }

    /**
     * One destination, built from the two things that make it a place, for the diff's own two questions.
     *
     * The rows and topics are values rather than stored rows because the claim is about the comparison the list
     * runs, not about storage: [TaskHierarchyPolicy.destinationsFor] is already pinned elsewhere for what it admits,
     * and `HierarchyDestinationTest` holds that. Titles are this file's own data.
     */
    private fun place(
        topic: String?,
        parent: String?,
        level: Int = 2,
        travelling: Int = 1
    ): MoveDestination = MoveDestination(
        // The two stamps are pinned rather than defaulted: `createdAt` and `updatedAt` otherwise fall to
        // `System.currentTimeMillis()`, and a value that differs because the clock ticked between the two reads
        // would make the content question below answer at random.
        topic = topic?.let {
            TaskGroup(uuid = it, userId = userId, name = it, createdAt = STAMP, updatedAt = STAMP)
        },
        parent = parent?.let {
            Task(
                title = "row-$it", description = "", uuid = it, userId = userId, groupId = topic,
                createdAt = STAMP, updatedAt = STAMP
            )
        },
        resultingLevel = level,
        travellingCount = travelling,
        pathTitles = listOfNotNull(topic, parent?.let { "row-$it" })
    )

    // ── the confirmation, and the write behind it ──────────────────────────────────────────────────────────

    /**
     * «Прежде чем подтверждать, пользователь должен видеть: текущий путь, новый путь, количество задач, итоговый
     * уровень и есть ли тема.»
     *
     * All of it, in the dialog that asks, in the order the tree gives it — including a current path read out of
     * `parent_uuid` rather than out of a row's place in a sequence, which is why the parent in this fixture is
     * stored *after* the child. And pressing a row is still not a write: storage is exactly the tree the sheet was
     * built from while the confirmation is on screen.
     */
    @Test
    fun `the confirmation names the position the press will write`() {
        val office = group("office-block")
        val errand = group("errand-block")
        // The child is stored above its own parent, so anything deriving a path from the sequence names nothing here.
        row("draft", parent = "planning", position = 0, group = office.uuid)
        val head = stored().first { it.uuid == "draft" }
        row("planning", position = 1, group = office.uuid)
        row("notes", position = 2, group = errand.uuid)
        val model = drawn()
        val list = admitted(head, model.hierarchyContext!!)
        val chosen = admissible(list, "notes")
        val before = shape()

        val (_, sheet) = openSheet(model, head)
        rowFor(rowsOf(sheet), list, chosen).performClick()
        pump()

        val confirm = ShadowDialog.getLatestDialog() as AlertDialog
        assertEquals("a choice opens the second step rather than writing on the spot",
            app.getString(R.string.move_confirm_title), titleText(confirm))
        assertEquals("the preview is the whole sentence: where it sits now, where it goes, how many move, what level",
            previewOf(listOf("office-block", "planning", "draft"), chosen), messageText(confirm))
        assertTrue("and the new path says which topic the press files the block under — the answer to «is there a " +
            "topic» is on the screen before the button is", messageText(confirm).contains("errand-block"))
        assertEquals("choosing is not moving: not one link, topic or position changed before the confirm",
            before, shape())
    }

    /**
     * The confirm's own button, and the tree after it.
     *
     * The write is [TaskHierarchyRepository.moveSubtreeUnder] — the one transaction the stage has — reached through
     * [TasksViewModel.moveTaskUnder], so a refusal would republish the stored order the way indent, outdent and a
     * drag do. Everything the confirmation promised is then checked against storage: the parent link, and the whole
     * block's topic, with no row left behind in the old one. The editor's three lines are waited for rather than
     * redrawn by hand, which is the claim §6.2's `follow` makes about the published snapshot; asserting it here is
     * what proves a move earns it.
     */
    @Test
    fun `confirming moves the branch through the repository and the editor follows`() {
        val office = group("office-block")
        val errand = group("errand-block")
        row("planning", position = 0, group = office.uuid)
        val head = row("draft", position = 1, group = office.uuid)
        row("notes", parent = "draft", position = 2, group = office.uuid)
        row("figures", position = 3, group = errand.uuid)
        val model = drawn()
        val list = admitted(head, model.hierarchyContext!!)
        val chosen = admissible(list, "figures")
        assertEquals("the press is promised two travelling rows", 2, chosen.travellingCount)
        assertEquals("and one level below the row it names", 2, chosen.resultingLevel)

        val (shown, sheet) = openSheet(model, head)
        rowFor(rowsOf(sheet), list, chosen).performClick()
        pump()
        val confirm = ShadowDialog.getLatestDialog() as AlertDialog
        assertTrue("and the preview repeats both",
            messageText(confirm).contains(levelText(chosen.resultingLevel)) &&
                messageText(confirm).contains(travelling(chosen.travellingCount)))
        confirm.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        pump()

        settle("the branch to hang on the row the sheet named") {
            runBlocking { fixture.taskDao.getTaskById(head.id) }?.parentUuid == "figures"
        }
        val after = stored()
        assertEquals("the head's link is the parent that was chosen", "figures",
            after.first { it.uuid == "draft" }.parentUuid)
        assertEquals("and every row of the block was filed in the destination topic — no partial group_id update",
            setOf("errand-block"),
            after.filter { it.uuid in setOf("draft", "notes") }.mapNotNull { it.groupId }.toSet()
        )

        settle("the editor to follow the tree it was moved into") {
            words(shown.binding.hierarchyTopic) ==
                app.getString(R.string.hierarchy_topic_value, "errand-block")
        }
        assertEquals("the topic line reads the header the snapshot drew",
            app.getString(R.string.hierarchy_topic_value, "errand-block"), words(shown.binding.hierarchyTopic))
        assertEquals("the parent line reads the link that was written",
            app.getString(R.string.hierarchy_parent_value, "figures"), words(shown.binding.hierarchyParent))
        assertEquals("and the level is the one the sheet promised, measured on the new tree",
            app.getString(R.string.hierarchy_level_value, chosen.resultingLevel),
            words(shown.binding.hierarchyLevel))
    }

    /**
     * A sheet is built from a snapshot; the tree does not have to agree when the press arrives.
     *
     * The branch under a head can gain a row while the dialog is up — a sync pull, another screen, a reader who opens
     * a second editor — and then the destination the sheet drew would pass the third level. The repository's door
     * refuses it, [TasksViewModel.moveTaskUnder] runs inside the same `runHierarchyCommand` the other hierarchy
     * commands use so the screen is drawn from storage again, and every claim about a refusal is checked there: no
     * link moved, no topic moved, no position moved, no row of the block half-filed — and the editor still prints
     * the tree it is in rather than the one the sheet promised.
     *
     * The case is made non-vacuous at the end: the same sheet, pressed on a destination the domain still takes, does
     * write. A button wired to nothing would pass every assertion above.
     */
    @Test
    fun `a refused move leaves storage and the editor unchanged`() {
        val office = group("office-block")
        val errand = group("errand-block")
        row("planning", position = 0, group = office.uuid)
        row("notes", parent = "planning", position = 1, group = office.uuid)
        val head = row("draft", position = 2, group = office.uuid)
        row("figures", position = 3, group = errand.uuid)
        val model = drawn()
        val list = admitted(head, model.hierarchyContext!!)
        val stale = admissible(list, "notes")
        assertEquals("the sheet was built while the block was one row tall, and level three was admissible",
            3, stale.resultingLevel)

        val (shown, sheet) = openSheet(model, head)
        // The tree changes underneath the open dialog.
        row("receipts", parent = "draft", position = 4, group = office.uuid)
        settle("the new row to reach the published snapshot") {
            model.hierarchyContext!!.stored.any { it.uuid == "receipts" }
        }
        val afterGrowth = shape()
        assertTrue("and the destination still on the sheet is now one the domain would refuse — the block is two " +
            "rows tall under a parent that already sits at the second level",
            admitted(head, model.hierarchyContext!!).none { it.parent?.uuid == "notes" })

        rowFor(rowsOf(sheet), list, stale).performClick()
        pump()
        val confirm = ShadowDialog.getLatestDialog() as AlertDialog
        confirm.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        pump()
        settle("the screen to settle after the refusal") {
            model.hierarchyContext!!.stored.any { it.uuid == "receipts" }
        }

        assertEquals("no link, no topic, no position and no push state moved: the tree is exactly what the growth " +
            "made it", afterGrowth, shape())
        assertEquals("the head still hangs where it hung when the sheet opened", null,
            runBlocking { fixture.taskDao.getTaskById(head.id) }?.parentUuid)
        assertEquals("the editor still names the topic the row is filed in, not the one the sheet promised",
            app.getString(R.string.hierarchy_topic_value, "office-block"), words(shown.binding.hierarchyTopic))
        assertEquals("and the level the tree actually holds, not the third the sheet offered",
            app.getString(R.string.hierarchy_level_value, 1), words(shown.binding.hierarchyLevel))

        // The positive control, on the same screen: a press the domain takes does write, so the assertions above are
        // about a refusal rather than about a button that reaches nothing.
        val fresh = admitted(head, model.hierarchyContext!!)
        val (_, second) = openSheet(model, head)
        rowFor(rowsOf(second), fresh, admissible(fresh, "figures")).performClick()
        pump()
        (ShadowDialog.getLatestDialog() as AlertDialog)
            .getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        pump()
        settle("the admissible press to land") {
            runBlocking { fixture.taskDao.getTaskById(head.id) }?.groupId == "errand-block"
        }
        assertEquals("which it did", "errand-block", stored().first { it.uuid == "draft" }.groupId)
    }

    // ── when there is nothing to draw ──────────────────────────────────────────────────────────────────────

    /**
     * An empty destination list is no control at all.
     *
     * [TaskHierarchyPolicy.destinationsFor] answers `emptyList()` for a head whose own block cannot be named — the
     * door the repository refuses every move of at the transaction — and §6.1's rule for a press that can only die
     * is that no button is drawn for it. A dialog with nothing in it would be exactly that press, which is why this
     * is a claim about the button rather than about the dialog.
     */
    @Test
    fun `no move action is drawn when nothing is admissible`() {
        val office = group("office-block")
        val head = row("draft", position = 0, group = office.uuid)
        row("figures", parent = "draft", position = 1, group = office.uuid)
        // The doubled identity sits inside the branch any press would carry, so "everything under this row" has two
        // answers and the block cannot be named at all.
        row("notes", parent = "figures", position = 2, group = office.uuid, uuid = "twin")
        row("receipts", position = 3, group = office.uuid, uuid = "twin")
        val clean = row("planning", position = 4, group = office.uuid)
        val model = drawn()
        val screen = model.hierarchyContext!!

        assertEquals("the domain has no destination to offer this head",
            emptyList<MoveDestination>(),
            MoveToDialog.destinations(head, screen, TaskHierarchyPolicy.factsOf(screen.stored)))
        val blocked = bound(model, head)
        assertFalse("so no Move-to control is drawn for it", drawn(blocked.binding.hierarchyMoveButton))
        val before = ShadowDialog.getLatestDialog()
        blocked.binding.hierarchyMoveButton.performClick()
        pump()
        assertSame("and a press that reaches the handler anyway opens nothing — an empty dialog is a dead control",
            before, ShadowDialog.getLatestDialog())

        val shown = bound(model, clean)
        assertTrue("while a row whose block can be named does get the control; without this the case above is " +
            "just an unwired button", drawn(shown.binding.hierarchyMoveButton))
        assertEquals("and the control says what it opens, in the app's own words",
            app.getString(R.string.move_action), shown.binding.hierarchyMoveButton.text.toString())
    }

    /**
     * The snapshot the list is built from is the profile, not the screen.
     *
     * A filter decides what the reader sees; it does not decide what their notebook holds. Task 2's agreement tests
     * are written against `factsOf(stored)`, and a sheet built from the rows that survived a filter would answer
     * "nothing admissible" about a topic the profile still owns — which, by the rule above, would take the whole
     * control away. This is the difference between a filtered list and a filtered *tree*, and it is what would let a
     * search box quietly remove destinations.
     *
     * The claim is made twice, on purpose, because only one of them is the code a reader runs. The first half judges
     * the list the dialog is handed through the one entry point 1B left, fed here with facts built over `stored` —
     * which is how any caller holding no tree of its own must build them; the second presses the editor's own button
     * while the filter is up and reads the rows off the sheet, which is the only way to reach the facts
     * [HierarchyEditor] built for the emission being drawn.
     */
    @Test
    fun `the sheet reads the whole stored profile, not the filtered screen`() {
        val office = group("office-block")
        val errand = group("errand-block")
        row("planning", position = 0, group = office.uuid)
        val head = row("draft", parent = "planning", position = 1, group = office.uuid)
        val hidden = row("receipts", position = 2, group = errand.uuid)
        val model = drawn()

        model.setSearchQuery(head.title)
        settle("the filter to leave the head on screen") {
            model.hierarchyContext!!.blocks.flatten().any { it.id == head.id }
        }
        val screen = model.hierarchyContext!!
        val drawnIds = screen.blocks.flatten().map { it.id }.toSet()
        assertFalse("the filter really does hide the other topic's row from the screen", hidden.id in drawnIds)
        assertTrue("while the published snapshot still holds the whole profile",
            screen.stored.any { it.id == hidden.id })

        // 1B left one entry point, the one [HierarchyEditor] calls with the tree it measured for this emission; the
        // facts are handed over the stored profile here for the same reason the editor hands them over from there.
        val list = MoveToDialog.destinations(head, screen, TaskHierarchyPolicy.factsOf(screen.stored))
        assertTrue("and the sheet offers the row the screen is not showing: $list",
            list.any { it.parent?.id == hidden.id })
        assertEquals("which is the policy's own answer over the stored profile, row for row",
            listTexts(admitted(head, screen)), listTexts(list))

        val filtered = TaskHierarchyPolicy.destinationsFor(
            head = head,
            topics = screen.topics,
            profileTopicUuids = screen.topics.map { it.uuid }.toSet(),
            facts = TaskHierarchyPolicy.factsOf(screen.blocks.flatten())
        )
        assertNotEquals("and a sheet built from the filtered screen would have been the shorter list — the exact " +
            "failure this claim is about", listTexts(filtered), listTexts(list))

        // The same claim against the path production actually takes, not against an overload it no longer calls.
        // `HierarchyEditor.refresh` measures the destinations with the [TaskHierarchyPolicy.TreeFacts] it built for
        // this emission (`factsOf(screen.stored)`, one build per snapshot) and the sheet draws that very list, so the
        // rows on screen are the only version of this guarantee a reader can be affected by. Handing that build the
        // filtered blocks instead — `factsOf(screen.blocks.flatten())`, which is the mutation this half is written
        // against — shortens what the editor keeps and the assertions below feel it, while every other fixture in
        // this class runs unfiltered, where the two reads are the same rows and nothing would notice.
        val (_, sheet) = openSheet(model, head)
        val drawn = rowsOf(sheet)
        assertEquals("the sheet the reader really opens under a filter still lists the whole profile, row for row and " +
            "in the policy's order: ${rowTexts(drawn)}",
            listTexts(admitted(head, screen)), rowTexts(drawn))
        assertEquals("and the row the screen is hiding is drawn there rather than dropped with the filter",
            1, drawn.count { pathLine(it) == pathOf("errand-block", "receipts") })
    }

    // ── the row as a target, and as a sentence ─────────────────────────────────────────────────────────────

    /**
     * 48dp and 320dp, measured.
     *
     * The brief's touch floor and the narrowest width the app is designed against are facts about boxes, so they are
     * checkable here: a row's measured height clears `touch_target_min`, and at the narrow width every line it prints
     * still has room — no child measured to zero width, which is the failure Stage 15 photographed on a weighted
     * sibling. What is *not* checkable here is whether the words fit: LEGACY graphics gives real boxes and no glyph
     * widths, so a Russian path at 320dp with the font at 1.3 stays with §11's device frames.
     */
    @Test
    fun `a row is at least the touch floor and the panel survives 320dp`() {
        val office = group("office-block")
        val errand = group("errand-block")
        row("planning", position = 0, group = office.uuid)
        row("notes", parent = "planning", position = 1, group = office.uuid)
        row("receipts", position = 2, group = errand.uuid)
        val head = row("draft", position = 3)
        val model = drawn()
        val (_, sheet) = openSheet(model, head)
        val rows = rowsOf(sheet)
        assertTrue("the sheet has rows to measure", rows.isNotEmpty())

        // The panel's own inset, measured rather than admired: `dialog_move_to.xml` exists partly because the
        // code-built panel had none, so a row of this sheet must sit inside the container by the app's screen margin
        // and not run to the dialog's edge. Robolectric lays this out at the narrowest width the design is held to,
        // which is where a missing inset would be felt most.
        val list = rows.first().parent as RecyclerView
        val panel = list.parent as View
        val narrowSpec = View.MeasureSpec.makeMeasureSpec(
            (NARROW_DP * app.resources.displayMetrics.density).toInt(), View.MeasureSpec.EXACTLY
        )
        panel.measure(narrowSpec, View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        panel.layout(0, 0, panel.measuredWidth, panel.measuredHeight)
        val margin = app.resources.getDimensionPixelSize(R.dimen.screen_margin)
        assertEquals("the container insets its list by the app's own screen margin, on the left", margin, list.left)
        assertEquals("and on the right, so no destination touches the dialog's edge",
            panel.width - margin, list.right)
        assertEquals("which leaves the rows the panel less both insets at ${NARROW_DP}dp: ${list.width}px",
            panel.width - 2 * margin, list.width)

        val density = app.resources.displayMetrics.density
        val width = (NARROW_DP * density).toInt()
        val floor = app.resources.getDimensionPixelSize(R.dimen.touch_target_min)
        assertEquals("the app's own floor is 48dp at this density", (48 * density).toInt(), floor)

        rows.forEach { row ->
            row.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            row.layout(0, 0, row.measuredWidth, row.measuredHeight)
            assertTrue("a destination is a target of at least ${floor}px, and this one measured " +
                "${row.measuredHeight}px", row.measuredHeight >= floor)
            // Declared, not merely arrived at: three wrapped lines would clear 48dp on their own, so a row whose
            // floor has quietly been lowered would still measure tall here. The layout has to say the floor itself.
            assertTrue("the row declares the app's touch floor rather than trusting its content to reach it: " +
                "${row.minimumHeight}px", row.minimumHeight >= floor)
            assertEquals("and it takes the narrow panel's full width rather than overflowing it",
                width, row.measuredWidth)
            listOf<View>(
                row.findViewById(R.id.moveDestinationTitle),
                row.findViewById(R.id.moveDestinationPath),
                row.findViewById(R.id.moveDestinationMeta)
            ).forEach { line ->
                assertTrue("the line ${app.resources.getResourceEntryName(line.id)} kept its own width at " +
                    "${width}px: ${line.width}", line.width in 1..width)
                assertTrue("and stayed inside the row: ${line.left}..${line.right}",
                    line.left >= 0 && line.right <= row.width)
            }
        }
    }

    /**
     * One sentence per choice, so a screen reader does not have to reassemble a row from three fragments.
     *
     * The row carries the whole statement as its `contentDescription` — what it is called, where the block hangs,
     * what level it lands at, how many tasks travel, and the action the row offers — and every leg of it comes out
     * of a resource, so a Russian reader hears Russian. The three text views inside are then taken out of the
     * accessibility tree rather than left to be read twice, which is the condition §6.3's review puts on hiding them
     * (the row description has to cover everything they say; `TopicCompletionTest`'s neighbourhood is where that was
     * failed before). The dialog's title is the heading, so the sentence is what a row is *called*, and the row is
     * focusable: a choice reachable only by touch is not a choice a keyboard or switch-access user has.
     */
    @Test
    fun `the row speaks one sentence to a screen reader`() {
        val office = group("office-block")
        row("planning", position = 0, group = office.uuid)
        val head = row("draft", position = 1, group = office.uuid)
        val model = drawn()
        val list = admitted(head, model.hierarchyContext!!)
        val chosen = admissible(list, "planning")

        val (_, sheet) = openSheet(model, head)
        val rows = rowsOf(sheet)
        val row = rowFor(rows, list, chosen)
        assertEquals("the row is named by everything the reader has to weigh before pressing",
            app.getString(
                R.string.move_row_description_format,
                titleOf(chosen), pathOf(legsOf(chosen)), metaOf(chosen), app.getString(R.string.move_action)
            ),
            row.contentDescription?.toString()
        )
        assertTrue("and no row of the sheet is left without its sentence — rows counted, not assumed",
            rows.isNotEmpty() && rows.all { it.contentDescription?.toString()?.isNotBlank() == true })
        rows.forEach { every ->
            assertTrue("a row a screen reader stops on is also a row a keyboard can reach", every.isFocusable)
            assertNotEquals("and nothing under a row is switched off wholesale rather than spoken for",
                View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, every.importantForAccessibility)
            listOf<View>(
                every.findViewById(R.id.moveDestinationTitle),
                every.findViewById(R.id.moveDestinationPath),
                every.findViewById(R.id.moveDestinationMeta)
            ).forEach { line ->
                assertEquals("the row speaks, so ${app.resources.getResourceEntryName(line.id)} does not say it " +
                    "again as a second stop",
                    View.IMPORTANT_FOR_ACCESSIBILITY_NO, line.importantForAccessibility)
            }
        }
        assertEquals("the heading the dialog was given is the app's own title",
            app.getString(R.string.move_to_title), titleText(sheet))
    }
}
