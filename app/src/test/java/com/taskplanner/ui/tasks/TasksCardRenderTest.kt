package com.taskplanner.ui.tasks

import android.content.Context
import android.os.Looper
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import com.taskplanner.R
import com.taskplanner.data.model.TaskStatus
import com.taskplanner.data.model.WorkSession
import com.taskplanner.testing.AppFixture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The screen's render path over real data — the state the view model publishes, painted by the real
 * adapter, read back off the views.
 *
 * Spec §8 asks for fragment-level coverage and this is where it lands, with one honest substitution:
 * [TasksFragment] is an `@AndroidEntryPoint` fragment, so attaching it under Robolectric needs
 * `hilt-android-testing` and a test application — a new dependency and a new component, which the brief
 * allows only with cause. The rendering decisions this stage added live in the adapter and in
 * [CardPresentation], not in the fragment (the fragment only forwards state), so the seam tested here is
 * the one that can actually paint a wrong card. What the fragment itself is checked by is the instrumented
 * [TasksHeaderInstrumentedTest], which runs the real screen on a device.
 *
 * The database is real, not mocked, for the same reason the rest of this repository's tests use
 * [AppFixture]: the overdue question is answered by SQL on the dashboard's side and by Kotlin on the
 * card's, and only a real database can show the two disagreeing.
 */
@RunWith(RobolectricTestRunner::class)
class TasksCardRenderTest {

    private lateinit var fixture: AppFixture
    private lateinit var viewModel: TasksViewModel
    private lateinit var context: Context
    private var userId: Long = 0

    /**
     * One instant for the whole test: the card is asked "is it late" against it, and the dashboard's
     * aggregate is asked the same question against the same value. A `get()` here would re-read the wall
     * clock between the two calls and let a card and a panel disagree for no reason but the boundary.
     */
    private val now: Long = System.currentTimeMillis()

    /**
     * The work presses the painted cards answered. A control that is merely constructed with a listener
     * attached is not the same claim as one that reaches the screen's command, so the adapter writes here
     * and a test reads it back after `performClick()`.
     */
    private val workToggles = mutableListOf<Long>()

    @Before
    fun setUp() {
        context = android.view.ContextThemeWrapper(
            ApplicationProvider.getApplicationContext(), R.style.Theme_TaskPlanner
        )
        fixture = AppFixture(context)
        Dispatchers.setMain(Executor { it.run() }.asCoroutineDispatcher())
        userId = runBlocking { fixture.signIn(email = "card@example.com").id }
        viewModel = TasksViewModel(
            fixture.taskRepository, fixture.categoryRepository, fixture.groupRepository,
            fixture.recurrenceRepository, fixture.reminderRepository, fixture.sessionRepository,
            fixture.workTrackingRepository, fixture.workStatisticsRepository
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        fixture.close()
    }

    private fun add(title: String, dueAt: Long? = null, status: TaskStatus = TaskStatus.TODO): Long {
        val id = runBlocking { fixture.addTask(userId, title, dueAt = dueAt) }
        if (status != TaskStatus.TODO) {
            runBlocking {
                val stored = fixture.taskDao.getTaskById(id)!!
                fixture.taskDao.updateTask(stored.copy(status = status))
            }
        }
        return id
    }

    /** Binds the state's rows into real views and returns them in list order. */
    private fun paintedRows(): List<Pair<TasksListItem.TaskRow, View>> {
        val items = viewModel.state.value.items
        val adapter = TasksAdapter(
            onTaskClick = {}, onLongPressTask = {}, onToggleSelection = {}, onEdit = {},
            onDeleteRequest = {}, onStart = {}, onPause = {}, onResume = {}, onComplete = {},
            onWorkToggle = { task -> workToggles += task.id }, onToggleGroup = {}, onGroupMenu = { _, _ -> }, onStartDrag = {},
            timeBase = FixedTimeBase(now)
        )
        adapter.submitList(items)
        awaitUntil("the adapter to hold ${items.size} items") { adapter.itemCount == items.size }
        val parent = android.widget.FrameLayout(context)
        val taskPositions = items.withIndex().filter { it.value is TasksListItem.TaskRow }
        return taskPositions.map { (position, item) ->
            val row = item as TasksListItem.TaskRow
            val holder = adapter.createViewHolder(parent, adapter.getItemViewType(position))
            // Bound at its own position: a fixture that bound every holder at 0 would show the first row
            // three times and call that a list.
            adapter.bindViewHolder(holder, position)
            holder.itemView.also {
                it.measure(
                    View.MeasureSpec.makeMeasureSpec(393.dp(), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                )
                it.layout(0, 0, 393.dp(), it.measuredHeight)
            }.let { row to it }
        }
    }

    private fun Int.dp() = (this * context.resources.displayMetrics.density).toInt()

    private fun awaitUntil(what: String, condition: () -> Boolean) {
        fixture.awaitIdle()
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("timed out on $what")
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    @Test
    fun `a past deadline is painted late on the card and counted late by the dashboard`() {
        add("Yesterday's work", dueAt = now - TimeUnit.DAYS.toMillis(1))
        add("Next week", dueAt = now + TimeUnit.DAYS.toMillis(7))
        awaitUntil("both rows") {
            viewModel.state.value.items.filterIsInstance<TasksListItem.TaskRow>().size == 2
        }

        val rows = paintedRows()
        val late = rows.filter { (_, view) ->
            view.findViewById<TextView>(R.id.taskDueDate).currentTextColor ==
                ContextCompat.getColor(context, R.color.ink_error)
        }
        assertEquals("exactly one card reads as late", 1, late.size)
        assertTrue(
            "and the late card says so in words, not only in colour",
            late.single().second.findViewById<TextView>(R.id.taskDueDate).text
                .toString().contains("late")
        )

        val health = runBlocking {
            fixture.taskStatisticsDao.healthCounts(
                userId = userId, now = now,
                dayEnd = now + TimeUnit.DAYS.toMillis(1),
                weekEnd = now + TimeUnit.DAYS.toMillis(7),
                staleBefore = now - TimeUnit.DAYS.toMillis(30)
            )
        }
        assertEquals(
            "spec §0's no-second-rule: the panel's number and the cards' number are the same fact",
            health.overdue.toLong(), late.size.toLong()
        )
    }

    /**
     * The one row where the card's rule and the dashboard's rule could drift, and the reason the card's
     * predicate carries the status clause at all.
     *
     * A finished task stays in the active list until it is archived, so it is drawn; its deadline is in the
     * past, so a card that asked only `dueDate < now` would paint it red and late. The dashboard's
     * `healthCounts` never selects completed rows, so it would count nothing late — and the two screens
     * would disagree about the same task, which is the drift spec §0 forbids.
     */
    @Test
    fun `a completed task with a past deadline is drawn, and late to nobody`() {
        add("Finished late", dueAt = now - TimeUnit.DAYS.toMillis(2), status = TaskStatus.COMPLETED)
        awaitUntil("the finished row") {
            viewModel.state.value.items.filterIsInstance<TasksListItem.TaskRow>().size == 1
        }

        val (_, view) = paintedRows().single()
        val date = view.findViewById<TextView>(R.id.taskDueDate)
        assertTrue(
            "the row is on the screen and is not painted late: " + date.text,
            date.currentTextColor != ContextCompat.getColor(context, R.color.ink_error)
        )
        val health = runBlocking {
            fixture.taskStatisticsDao.healthCounts(
                userId = userId, now = now,
                dayEnd = now + TimeUnit.DAYS.toMillis(1),
                weekEnd = now + TimeUnit.DAYS.toMillis(7),
                staleBefore = now - TimeUnit.DAYS.toMillis(30)
            )
        }
        assertEquals("and the dashboard agrees that nothing is late", 0, health.overdue)
    }

    /**
     * The work control belongs to `workRow`'s story, and the row's line moved into the action cluster in
     * this stage. The button therefore has to be hidden by the same rule that hides the line — a stopwatch
     * on a card nobody has ever measured is an offer to measure nothing, and a button left out of the bind
     * is a button with no listener. Both halves are asserted: the hidden one, and the one that answers a
     * press.
     */
    @Test
    fun `a card with nothing measured shows no work control, and a card with one wires it`() {
        add("Plain task")
        awaitUntil("the plain row") {
            viewModel.state.value.items.filterIsInstance<TasksListItem.TaskRow>().size == 1
        }
        val plain = paintedRows().single().second
        assertEquals(
            "no stopwatch on a task nobody has measured", View.GONE,
            plain.findViewById<View>(R.id.workControlButton).visibility
        )

        val measuredId = add("Measured task")
        val measuredTask = runBlocking { fixture.taskDao.getTaskById(measuredId) }!!
        runBlocking {
            fixture.workSessionDao.insert(
                WorkSession(
                    uuid = "render-interval", userId = userId,
                    trackingSessionUuid = "render-session", taskId = measuredId,
                    taskUuid = measuredTask.uuid, startedAt = now - 2_400_000L,
                    endedAt = now - 600_000L, durationSeconds = 1800L,
                    localDay = java.time.LocalDate.now().toEpochDay(),
                    utcOffsetMinutes = java.util.TimeZone.getDefault().getOffset(now) / 60_000
                )
            )
        }
        awaitUntil("the measured row to carry its figure") {
            viewModel.state.value.items.filterIsInstance<TasksListItem.TaskRow>()
                .first { it.task.id == measuredId }.work.closedSeconds == 1800L
        }

        val control = paintedRows().first { (row, _) -> row.task.id == measuredId }
            .second.findViewById<ImageButton>(R.id.workControlButton)
        assertEquals("a measured card shows the stopwatch", View.VISIBLE, control.visibility)
        assertEquals("and names it for the phase it is in",
            context.getString(R.string.work_start), control.contentDescription.toString())
        control.performClick()
        assertEquals(
            "and pressing it reaches the screen's work command for that task",
            listOf(measuredId), workToggles
        )
    }

    @Test
    fun `a long title takes two lines and then stops, rather than cutting the card in half`() {
        add("Задача ".repeat(40).trim())
        awaitUntil("the row") {
            viewModel.state.value.items.filterIsInstance<TasksListItem.TaskRow>().size == 1
        }

        val view = paintedRows().single().second
        val title = view.findViewById<TextView>(R.id.taskTitle)
        assertEquals("the title is allowed two lines", 2, title.maxLines)
        assertNotNull("and it has been laid out", title.layout)
        assertTrue("two lines at most, so the card grows by a known amount",
            title.layout!!.lineCount in 1..2)
        assertEquals("the row still shows its status pill", View.VISIBLE,
            view.findViewById<View>(R.id.taskStatus).visibility)
        assertEquals("and its date line", View.VISIBLE,
            view.findViewById<View>(R.id.taskDueDate).visibility)
    }

    @Test
    fun `an empty account paints no rows and the screen says so`() {
        fixture.awaitIdle()
        val state = viewModel.state.value
        assertEquals("nothing to draw", 0, state.items.filterIsInstance<TasksListItem.TaskRow>().size)
        assertTrue(
            "and the state still answers the header's count line",
            state.activeTaskCount == 0
        )
    }

    @Test
    fun `every row the state holds reaches the screen in the same order`() {
        add("First")
        add("Second")
        add("Third")
        awaitUntil("three rows") {
            viewModel.state.value.items.filterIsInstance<TasksListItem.TaskRow>().size == 3
        }
        val expected = viewModel.state.value.items.filterIsInstance<TasksListItem.TaskRow>()
            .map { it.task.title }
        // The title is read off the view, not off the item. Comparing the state's titles with the state's
        // titles asks the list twice about the same paper; this asks what the card actually says, in order.
        val painted = paintedRows().map { (_, view) ->
            view.findViewById<TextView>(R.id.taskTitle).text.toString()
        }
        assertEquals(expected, painted)
    }
}

/** The clock a card measures "late" against, fixed by the test that asks for it. */
private class FixedTimeBase(private val wall: Long) : com.taskplanner.data.tracking.TimeBase {
    override fun wallMillis(): Long = wall
    override fun monotonicMillis(): Long = wall
}
