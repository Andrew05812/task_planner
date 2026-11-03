package com.taskplanner.ui.tasks

import android.content.Context
import android.graphics.Paint
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.isVisible
import androidx.test.core.app.ApplicationProvider
import com.taskplanner.R
import com.taskplanner.data.model.SyncState
import com.taskplanner.data.model.Task
import com.taskplanner.data.model.TaskGroup
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * §8: a topic's own completed mark — displayed, switchable, and independent of everything under it.
 *
 * Stage 15 saved `task_groups.is_completed` (the `[X]` a Keep export puts on a root line) and sent it to the cloud,
 * and nothing in the app ever drew it: a reader who imported 56 topics, 34 of them checked, got a notebook whose
 * topics were all identical. §8 asks for the mark on the `GroupHeader`, visually distinguished without relying on
 * colour alone, with a TalkBack-readable statement of the state, and switchable by the user — and names four things
 * the write must never do:
 *
 *  * completing a topic must not complete its tasks;
 *  * clearing a topic must not reopen its children;
 *  * `is_completed` must not be computed as an AND/OR over the children;
 *  * a topic must not grow a `completed_at`, because the column does not exist and the time does not exist in
 *    Keep either.
 *
 * The first three are one fact seen from three sides — **a topic and its tasks have independent statuses** — so they
 * get three tests rather than one "independence" test that could pass on two of them. The fourth is a schema claim,
 * asserted against the live table with a `PRAGMA`, because a field added to the model later is exactly what the rest
 * of this file would not notice.
 *
 * The drawing is asserted on the header view the adapter actually paints: the state line's words, its glyph, the
 * strike on the name (this app's own convention for finished — `ArchiveAdapter` does the same to an archived title),
 * what a recycled header gives up, and the width the whole thing costs at 320dp.
 */
@RunWith(RobolectricTestRunner::class)
class TopicCompletionTest {

    private lateinit var fixture: AppFixture
    private lateinit var app: Context
    private lateinit var viewModel: TasksViewModel
    private var userId: Long = 0

    private companion object {
        /** Seeded `SYNCED` at a fixed instant, so the mark's write is tellable from a write that changed nothing. */
        const val FIXTURE_MILLIS = 1_700_000_000_000L
    }

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext<Context>()
        fixture = AppFixture(app)
        Dispatchers.setMain(Executor { it.run() }.asCoroutineDispatcher())
        userId = runBlocking { fixture.signIn(email = "topics@example.com").id }
        viewModel = TasksViewModel(
            fixture.taskRepository, fixture.categoryRepository, fixture.groupRepository,
            fixture.recurrenceRepository, fixture.reminderRepository, fixture.sessionRepository,
            fixture.workTrackingRepository, fixture.workStatisticsRepository,
            TaskHierarchyRepository(
                fixture.database, fixture.taskDao, fixture.groupDao, fixture.trackingSessionDao,
                fixture.reminderDao, fixture.sessionRepository, fixture.timeBase
            )
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        fixture.close()
    }

    /**
     * A topic written out rather than through `fixture.addGroup`, because that helper cannot say what this file is
     * about: a *known* `is_completed`, a `collapsed` the mark must leave alone, and a `SYNCED` state the write is
     * required to knock over.
     */
    private fun group(
        name: String,
        completed: Boolean = false,
        collapsed: Boolean = false,
        sortOrder: Int = 0
    ): TaskGroup = runBlocking {
        val group = TaskGroup(
            uuid = name, userId = userId, name = name, collapsed = collapsed, sortOrder = sortOrder,
            syncState = SyncState.SYNCED, createdAt = FIXTURE_MILLIS, updatedAt = FIXTURE_MILLIS,
            isCompleted = completed
        )
        fixture.groupDao.upsertGroup(group)
        fixture.groupDao.getGroupByUuid(name)!!
    }

    private fun row(
        title: String,
        group: String? = null,
        position: Int,
        status: TaskStatus = TaskStatus.TODO,
        parent: String? = null
    ): Task = runBlocking {
        val id = fixture.taskDao.insertTask(
            Task(
                title = title, description = "", uuid = title, userId = userId, groupId = group,
                parentUuid = parent, status = status, order = position, syncState = SyncState.SYNCED,
                createdAt = FIXTURE_MILLIS, updatedAt = FIXTURE_MILLIS
            )
        )
        fixture.taskDao.getTaskById(id)!!
    }

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

    private fun headers() = viewModel.state.value.items.filterIsInstance<TasksListItem.GroupHeader>()

    private fun headerOf(name: String) = headers().first { it.group.name == name }

    /**
     * Waits for the screen to show the topic [name] in a state where [ready] holds.
     *
     * This is the non-throwing form of a wait, and [headerOf] is not: `first{}` on a list Room's Flow has not
     * re-emitted yet raises `NoSuchElementException` — a harness race reported as a product failure. The sibling
     * class precedes every `first{}` with a titles-await for the same reason (`TasksFilterContextTest.awaitTitles`).
     */
    private fun awaitHeader(name: String, what: String, ready: (TasksListItem.GroupHeader) -> Boolean) {
        settle(what) { headers().any { it.group.name == name && ready(it) } }
        headerOf(name)
    }

    private fun stored(name: String): TaskGroup =
        runBlocking { fixture.groupDao.getGroupByUuid(name) }
            ?: throw AssertionError("the topic $name is not in storage")

    private fun storedRows(): List<Task> = runBlocking { fixture.taskDao.getRowsInStoredOrder(userId) }

    private fun adapter() = TasksAdapter(
        onTaskClick = {}, onLongPressTask = {}, onToggleSelection = {}, onEdit = {}, onDeleteRequest = {},
        onStart = {}, onPause = {}, onResume = {}, onComplete = {}, onWorkToggle = {}, onToggleBranch = {},
        onToggleGroup = {}, onGroupMenu = { _, _ -> }, onStartDrag = {}
    )

    /**
     * The screen's own path: the real adapter painting the headers the view model published, so every assertion
     * below is about the view a reader meets rather than about the column behind it. The header inflates Material
     * components and theme attributes, which the bare application context cannot resolve — hence `themed()`, the
     * same wrapper every card test in this package uses.
     */
    private fun bindHeaders(): List<Pair<TasksListItem.GroupHeader, View>> {
        val adapter = adapter()
        val items = viewModel.state.value.items
        adapter.submitList(items)
        settle("the adapter to hold the screen") { adapter.itemCount == items.size }
        return items.indices.filter { items[it] is TasksListItem.GroupHeader }.map { index ->
            val holder = adapter.createViewHolder(FrameLayout(themed()), adapter.getItemViewType(index))
            adapter.bindViewHolder(holder, index)
            items[index] as TasksListItem.GroupHeader to holder.itemView
        }
    }

    private fun themed(): Context =
        android.view.ContextThemeWrapper(app, R.style.Theme_TaskPlanner)

    /** The popup a reader's press opens, with the screen's three actions stood in for recording lambdas. */
    private fun menuFor(
        group: TaskGroup,
        onRename: (TaskGroup) -> Unit = {},
        onCompletion: (Boolean) -> Unit = {},
        onDelete: (TaskGroup) -> Unit = {}
    ): PopupMenu = GroupHeaderMenu.build(themed(), View(themed()), group,
        onRename = onRename, onCompletion = onCompletion, onDelete = onDelete)

    private fun titles(menu: PopupMenu): List<String> =
        (0 until menu.menu.size()).map { menu.menu.getItem(it).title.toString() }

    private fun ids(menu: PopupMenu): List<Int> = (0 until menu.menu.size()).map { menu.menu.getItem(it).itemId }

    // ── the mark, on the header ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a completed topic shows its own mark and says so in words`() {
        group("office-block", completed = true)
        row("planning", group = "office-block", position = 0)
        awaitHeader("office-block", "the completed topic to reach the screen") { it.group.isCompleted }

        val header = bindHeaders().first { (item, _) -> item.group.name == "office-block" }.second
        val state = header.findViewById<TextView>(R.id.groupState)

        assertEquals("the second line names the state in words",
            app.getString(R.string.topic_completed), state.text.toString())
        assertTrue("and the words are drawn, not merely set", state.isVisible)
        assertTrue("with a glyph beside them, so the fact is not only a sentence: " +
            "${state.compoundDrawablesRelative.toList()}", state.compoundDrawablesRelative[0] != null)
    }

    @Test
    fun `an open topic shows no state line at all`() {
        group("office-block")
        row("planning", group = "office-block", position = 0)
        settle("the topic to reach the screen") { headers().isNotEmpty() }

        val header = bindHeaders().first { (item, _) -> item.group.name == "office-block" }.second
        val state = header.findViewById<TextView>(R.id.groupState)
        // GONE rather than merely not-shown: `isVisible` is true only for VISIBLE, so an `INVISIBLE` line would pass
        // that test while still reserving a caption row on every open header in the notebook — and "pays nothing for
        // it" is a claim about the space, not only about the pixels.
        assertEquals("an open topic is the common case and pays nothing for it — no «not completed» note, and no " +
            "line kept for one", View.GONE, state.visibility)
        assertNull("and it carries no mark either: the glyph is put on by the bind, not left standing in the " +
            "layout for a header to inherit", state.compoundDrawablesRelative[0])
    }

    @Test
    fun `the completed name is struck through and an open one is not`() {
        group("office-block", completed = true)
        group("errand-block")
        row("planning", group = "office-block", position = 0)
        row("fuel", group = "errand-block", position = 1)
        settle("both topics to reach the screen") { headers().size == 2 }

        val bound = bindHeaders().associate { (item, view) -> item.group.name to view }
        val done = bound.getValue("office-block").findViewById<TextView>(R.id.groupName)
        val open = bound.getValue("errand-block").findViewById<TextView>(R.id.groupName)

        assertTrue("the name carries the app's own finished shape — the strike `ArchiveAdapter` uses: " +
            done.paintFlags, done.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG != 0)
        assertFalse("and an open topic's name does not: ${open.paintFlags}",
            open.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG != 0)
    }

    @Test
    fun `a recycled header gives the mark and the strike up`() {
        val adapter = adapter()
        val done = TasksListItem.GroupHeader(group("office-block", completed = true), taskCount = 1, allSelected = false)
        val open = TasksListItem.GroupHeader(group("errand-block"), taskCount = 1, allSelected = false)

        adapter.submitList(listOf(done))
        settle("the first header to reach the adapter") { adapter.itemCount == 1 }
        val holder = adapter.createViewHolder(FrameLayout(themed()), adapter.getItemViewType(0))
        adapter.bindViewHolder(holder, 0)

        val name = holder.itemView.findViewById<TextView>(R.id.groupName)
        val state = holder.itemView.findViewById<TextView>(R.id.groupState)
        assertTrue("painted as finished: ${name.paintFlags}", name.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG != 0)
        assertTrue("with the state line drawn", state.isVisible)

        // The same holder, the next topic. A header that kept the previous row's mark would tell the reader a topic
        // was finished when it is not — the reuse failure §7 tested for in the other direction, and the reason the
        // mark is recomputed from zero on every bind rather than toggled.
        adapter.submitList(listOf(open))
        settle("the second header to reach the adapter") { adapter.currentList == listOf(open) }
        adapter.bindViewHolder(holder, 0)

        assertFalse("the strike is cleared, not inherited: ${name.paintFlags}",
            name.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG != 0)
        assertEquals("the state line is taken back down, not hidden", View.GONE, state.visibility)
        assertEquals("and no sentence is left standing in it", "", state.text.toString())
        assertNull("and the glyph itself is taken off the second header, not left there invisible",
            state.compoundDrawablesRelative[0])
    }

    @Test
    fun `the words are the channel a screen reader gets`() {
        group("office-block", completed = true)
        row("planning", group = "office-block", position = 0)
        awaitHeader("office-block", "the completed topic to reach the screen") { it.group.isCompleted }

        val header = bindHeaders().first { (item, _) -> item.group.name == "office-block" }.second
        val state = header.findViewById<TextView>(R.id.groupState)

        assertEquals("the sentence is spoken, in words, and says the whole fact rather than one word",
            app.getString(R.string.topic_completed), state.text.toString())
        // Two ways a header can stop telling a screen reader the state at all. Neither is the default — which is
        // precisely why this names both instead of one `!= NO`, the form that could only ever pass.
        assertNotEquals("the line is not hidden from accessibility", View.IMPORTANT_FOR_ACCESSIBILITY_NO,
            state.importantForAccessibility)
        assertNotEquals("nor is everything under it", View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
            state.importantForAccessibility)
        // §8's TalkBack clause covers the row's own control too: the kebab opens three actions and used to name
        // itself with the card's word for opening a single task.
        assertEquals("the button that opens the topic's menu says that is what it does",
            app.getString(R.string.topic_actions), header.findViewById<View>(R.id.groupMenu).contentDescription.toString())
    }

    @Test
    fun `a completed header does not take width from its own name`() {
        group("office-block", completed = true)
        group("errand-block")
        row("planning", group = "office-block", position = 0)
        row("fuel", group = "errand-block", position = 1)
        settle("both topics to reach the screen") { headers().size == 2 }

        // Two topics, same-length names, one finished and one not. The mark and its sentence live *under* the name,
        // in the column the header already weights, so the finished one cannot be narrower — the Stage 15 lesson
        // that a sibling added to a row constrains the identifier away, applied before it happens.
        val bound = bindHeaders().associate { (item, view) -> item.group.name to view }
        val doneHeader = bound.getValue("office-block")
        val openHeader = bound.getValue("errand-block")
        val doneName = doneHeader.findViewById<TextView>(R.id.groupName)
        val openName = openHeader.findViewById<TextView>(R.id.groupName)
        val doneState = doneHeader.findViewById<TextView>(R.id.groupState)

        measure(doneHeader)
        measure(openHeader)

        assertEquals("a finished topic's name keeps the width an open one has", openName.width, doneName.width)
        // What this establishes is a placement fact, not a fit fact: the line lives in the column the header already
        // weights, so it is measured there. Put it beside the name instead — a sibling in the horizontal row — and
        // this reads 0, which is the Stage 15 defect ("a weighted view is the one that disappears") in the other
        // direction. Whether the Russian sentence *fits* its box at 320dp and font scale 1.3 is a glyph question,
        // and LEGACY `measureText` answers those at one pixel per character: §19 photographs that, and says so.
        assertTrue("and the state line is measured inside the column it belongs to: ${doneState.width}",
            doneState.width > 0)
    }

    private fun measure(view: View) {
        val width = (app.resources.displayMetrics.density * 320).toInt()
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    // ── independence from the tasks under the topic ────────────────────────────────────────────────────────

    @Test
    fun `a topic whose every task is finished is not finished itself`() {
        val topic = group("office-block")
        row("planning", group = topic.uuid, position = 0, status = TaskStatus.COMPLETED)
        row("draft", group = topic.uuid, position = 1, status = TaskStatus.COMPLETED)
        awaitHeader("office-block", "both tasks to reach the header") { it.taskCount == 2 }

        assertFalse("no aggregation: an AND over the children would mark this topic done",
            headerOf("office-block").group.isCompleted)
        val state = bindHeaders().first { (item, _) -> item.group.name == topic.name }
            .second.findViewById<TextView>(R.id.groupState)
        assertFalse("and the header draws no mark for it", state.isVisible)
    }

    @Test
    fun `a topic marked finished keeps its mark while its tasks stay open`() {
        val topic = group("office-block", completed = true)
        row("planning", group = topic.uuid, position = 0, status = TaskStatus.TODO)
        awaitHeader("office-block", "the open task under the finished topic to reach the screen") { it.taskCount == 1 }

        assertTrue("nothing below clears it, and nothing about it is inferred from below",
            headerOf("office-block").group.isCompleted)
        val done = bindHeaders().first { (item, _) -> item.group.name == topic.name }.second
        assertTrue("and the mark is drawn anyway", done.findViewById<TextView>(R.id.groupState).isVisible)
    }

    @Test
    fun `marking a topic does not complete the tasks under it`() {
        val topic = group("office-block")
        val before = row("planning", group = topic.uuid, position = 0)
        val branch = row("draft", group = topic.uuid, parent = before.uuid, position = 1)
        awaitHeader(topic.name, "the branch to reach the screen") { it.taskCount == 2 }

        viewModel.setGroupCompleted(topic.uuid, true)
        settle("the topic to be written as finished") { stored(topic.name).isCompleted }

        val after = storedRows().associateBy { it.uuid }
        listOf(before, branch).forEach { task ->
            val now = after.getValue(task.uuid)
            assertEquals("«${task.title}» keeps the status it had — the topic's mark is not a way of closing a " +
                    "branch behind the reader's back", task.status, now.status)
            assertEquals("and the row was not touched at all", task.updatedAt, now.updatedAt)
            assertEquals("nor queued for sync", task.syncState, now.syncState)
        }
    }

    @Test
    fun `clearing a topic does not reopen a child that was finished`() {
        val topic = group("office-block", completed = true)
        val finished = row("planning", group = topic.uuid, position = 0, status = TaskStatus.COMPLETED)
        settle("the topic to reach the screen") { headers().isNotEmpty() }

        viewModel.setGroupCompleted(topic.uuid, false)
        settle("the topic to be written as open") { !stored(topic.name).isCompleted }

        val now = storedRows().first { it.uuid == finished.uuid }
        assertEquals("a task the reader finished stays finished — the write opens the topic and nothing else",
            TaskStatus.COMPLETED, now.status)
        assertEquals("and the reader's own write is not rewritten by it", FIXTURE_MILLIS, now.updatedAt)
    }

    @Test
    fun `the topic count does not move with the mark`() {
        val topic = group("office-block")
        row("planning", group = topic.uuid, position = 0)
        row("draft", group = topic.uuid, position = 1)
        awaitHeader(topic.name, "two rows under the topic") { it.taskCount == 2 }

        val countBefore = headerOf(topic.name).taskCount
        viewModel.setGroupCompleted(topic.uuid, true)
        settle("the mark to be written") { stored(topic.name).isCompleted }
        // Read the count after the screen has drawn the marked topic: sampling a render that has not happened yet
        // would let a regression that *does* move the count win a race against the assertion.
        awaitHeader(topic.name, "the marked topic to be redrawn") { it.group.isCompleted }

        assertEquals("the number answers how many tasks the topic holds, which no mark changes",
            countBefore, headerOf(topic.name).taskCount)
    }

    // ── what the write does ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the mark writes the column the time and the pending state and nothing else`() {
        val topic = group("office-block", collapsed = true, sortOrder = 4)
        settle("the topic to reach the screen") { headers().isNotEmpty() }

        viewModel.setGroupCompleted(topic.uuid, true)
        settle("the write to land") { stored(topic.name).isCompleted }

        val now = stored(topic.name)
        assertTrue("the mark", now.isCompleted)
        assertNotEquals("the row is touched, so a sync pull and a merge can order it", FIXTURE_MILLIS, now.updatedAt)
        assertEquals("and the profile's copy is queued", SyncState.PENDING, now.syncState)
        assertEquals("its name is its own business", topic.name, now.name)
        assertEquals("its creation time does not move", topic.createdAt, now.createdAt)
        assertEquals("and its place in the reader's list is untouched", topic.sortOrder, now.sortOrder)
    }

    @Test
    fun `a fold stays exactly where it was when the mark changes`() {
        val topic = group("office-block", collapsed = true)
        row("planning", group = topic.uuid, position = 0)
        awaitHeader(topic.name, "the folded topic to reach the screen") { it.group.collapsed }

        viewModel.setGroupCompleted(topic.uuid, true)
        settle("the mark to be written") { stored(topic.name).isCompleted }
        assertTrue("§7's fold and §8's mark are two columns, and one write does not reach the other",
            stored(topic.name).collapsed)

        viewModel.setGroupCompleted(topic.uuid, false)
        settle("the mark to be cleared") { !stored(topic.name).isCompleted }
        assertTrue("and in both directions", stored(topic.name).collapsed)
    }

    @Test
    fun `writing the other way is a real write rather than a no-op dressed as one`() {
        val topic = group("office-block")
        settle("the topic to reach the screen") { headers().isNotEmpty() }
        val first = stored(topic.name)

        viewModel.setGroupCompleted(topic.uuid, true)
        settle("the mark to be written") { stored(topic.name).isCompleted }
        val marked = stored(topic.name)

        viewModel.setGroupCompleted(topic.uuid, false)
        settle("the mark to be cleared") { !stored(topic.name).isCompleted }
        val back = stored(topic.name)

        assertFalse("the second write takes the mark off rather than inventing a third state", back.isCompleted)
        assertNotEquals("and it moves the row's time again", marked.updatedAt, back.updatedAt)
        assertEquals("leaving the topic as it began, other than the time", first.name, back.name)
    }

    @Test
    fun `a topic has no completed_at and the write invents none`() {
        val topic = group("office-block")
        settle("the topic to reach the screen") { headers().isNotEmpty() }

        val columns = runBlocking {
            fixture.database.openHelper.readableDatabase
                .query("PRAGMA table_info(task_groups)", emptyArray()).use { cursor ->
                    val name = cursor.getColumnIndex("name")
                    buildList {
                        while (cursor.moveToNext()) add(cursor.getString(name))
                    }
                }
        }
        assertFalse("a Keep export carries no completion time for a root line, and the app does not make one up: " +
            columns.joinToString(), columns.any { it.contains("completed_at") })
        assertTrue("the table's own column list is what this argues about: ${columns.joinToString()}",
            columns.contains("is_completed"))

        viewModel.setGroupCompleted(topic.uuid, true)
        settle("the mark to be written") { stored(topic.name).isCompleted }

        val after = stored(topic.name)
        assertEquals("the only time that moved is the row's own update time", topic.createdAt, after.createdAt)
        assertNotEquals(FIXTURE_MILLIS, after.updatedAt)
    }

    // ── how the reader changes it ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `the topic menu offers the state the topic does not hold`() {
        // Read honestly: the *open* topic's sentence is also the one the menu XML carries, so this half would still
        // pass if `build` forgot to set the title. The teeth are in the finished half below and in
        // `the sentence a reader reads is the state the press writes`, which drives the same popup's click path.
        val open = group("office-block")
        val done = group("errand-block", completed = true)
        settle("both topics to reach the screen") { headers().size == 2 }

        assertEquals("an open topic is offered the mark",
            listOf(app.getString(R.string.rename_group), app.getString(R.string.mark_topic_completed),
                app.getString(R.string.delete_group)), titles(menuFor(open)))
        assertEquals("a finished topic is offered taking it off — never a second way to finish it",
            listOf(app.getString(R.string.rename_group), app.getString(R.string.mark_topic_open),
                app.getString(R.string.delete_group)), titles(menuFor(done)))
    }

    @Test
    fun `the sentence a reader reads is the state the press writes`() {
        val open = group("office-block")
        val done = group("errand-block", completed = true)
        settle("both topics to reach the screen") { headers().size == 2 }

        // The bug a menu with two labels invites is the sentence saying "Mark topic completed" while the handler
        // writes `false`. Reading the title and calling the pure function is not enough to catch that — the file
        // exists to make the pairing real, so this drives the popup's own click path and reads what it hands on.
        listOf(open to false, done to true).forEach { (topic, wasFinished) ->
            val written = mutableListOf<Boolean>()
            val others = mutableListOf<String>()
            val popup = menuFor(topic, onCompletion = { written += it },
                onRename = { others += "rename" }, onDelete = { others += "delete" })
            val item = popup.menu.findItem(R.id.action_toggle_group_completion)

            assertEquals("the ${if (wasFinished) "finished" else "open"} topic's row promises the other state",
                app.getString(if (wasFinished) R.string.mark_topic_open else R.string.mark_topic_completed),
                item.title.toString())
            // `performIdentifierAction` is the menu's own click path — `MenuItem` has no `performAction`, and the
            // difference matters because the path under test is the one a finger takes, not a call a test invents.
            assertTrue("and pressing it is a click the popup handles",
                popup.menu.performIdentifierAction(R.id.action_toggle_group_completion, 0))
            assertEquals("hands on exactly the state the sentence promised", listOf(!wasFinished), written)
            assertEquals("without reaching past it for either of the other two actions", emptyList<String>(), others)
        }
    }

    @Test
    fun `the menu's other two rows still reach the screen`() {
        val topic = group("office-block")
        val renamed = mutableListOf<TaskGroup>()
        val deleted = mutableListOf<TaskGroup>()
        val popup = menuFor(topic, onRename = { renamed += it }, onDelete = { deleted += it },
            onCompletion = { throw AssertionError("the state row was pressed, and this is not it") })

        assertTrue(popup.menu.performIdentifierAction(R.id.action_rename_group, 0))
        assertTrue(popup.menu.performIdentifierAction(R.id.action_delete_group, 0))

        assertEquals("rename arrives with the topic the menu was built for", listOf(topic), renamed)
        assertEquals("and so does delete", listOf(topic), deleted)
    }

    @Test
    fun `the topic menu keeps the destructive action last`() {
        val menu = ids(menuFor(group("office-block")))

        assertEquals("rename, then the state, then delete — the irreversible action stays at the bottom of the " +
                "list the reader scrolls", listOf(R.id.action_rename_group, R.id.action_toggle_group_completion,
            R.id.action_delete_group), menu)
    }

    @Test
    fun `the mark reaches the screen from storage rather than from the press`() {
        val topic = group("office-block")
        settle("the topic to reach the screen") { headers().isNotEmpty() }

        viewModel.setGroupCompleted(topic.uuid, GroupHeaderMenu.completionTarget(stored(topic.name)))
        awaitHeader(topic.name, "the header to redraw from the written column") { it.group.isCompleted }

        val header = bindHeaders().first { (item, _) -> item.group.name == topic.name }.second
        assertTrue("the state line the reader now sees was painted from the row, not from a flag the click " +
            "carried in", header.findViewById<TextView>(R.id.groupState).isVisible)
    }
}
