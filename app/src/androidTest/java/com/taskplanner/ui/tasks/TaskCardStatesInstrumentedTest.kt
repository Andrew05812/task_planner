package com.taskplanner.ui.tasks

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.GeneralLocation
import androidx.test.espresso.action.GeneralSwipeAction
import androidx.test.espresso.action.Press
import androidx.test.espresso.action.Swipe
import androidx.test.espresso.matcher.ViewMatchers.hasDescendant
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.taskplanner.R
import com.taskplanner.data.local.AppDatabase
import com.taskplanner.data.local.AppMigrations
import com.taskplanner.data.model.Category
import com.taskplanner.data.model.Priority
import com.taskplanner.data.model.RecurrenceRule
import com.taskplanner.data.model.Reminder
import com.taskplanner.data.model.RepeatUnit
import com.taskplanner.data.model.Task
import com.taskplanner.data.model.TaskStatus
import com.taskplanner.data.model.User
import com.taskplanner.data.model.WorkSession
import com.taskplanner.ui.MainActivity
import com.taskplanner.ui.util.WorkTimeFormat
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Calendar
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.hamcrest.CoreMatchers.allOf
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every state the card is claimed to draw, read off the painted row and photographed on the device.
 *
 * Stage 4's other classes decide the arithmetic in a JVM ([CardPresentationTest], [TasksCardRenderTest])
 * and measure the boxes ([TaskCardWidthBudgetTest], [TaskCardWidthBudgetInstrumentedTest]). Neither can say
 * that a row the database holds in `IN_PROGRESS` reaches the screen with the pause control shown and the
 * resume control hidden, or that the stopwatch line appears when a stretch is running. This class seeds one
 * row per state into the app's own storage, reads the painted card back, and photographs it.
 *
 * The seeds are chosen so each assertion can only be satisfied by the row it names: a LOW priority that is
 * late (its red edge can only come from the deadline, because the priority alone would have drawn nothing),
 * a completed task whose deadline also passed (finished, so nobody calls it late), one row with two
 * reminders and a series (the marks on line A have to have words), and one with a closed interval plus an
 * open one (the figure and the ticker at once).
 *
 * **One profile for the whole class, written before the first screen opens.** A connected run is one app
 * process, and `SessionRepository` is a Hilt singleton inside it: the user the process reads at its first
 * launch is the user every later launch of that run serves, however many rows `setCurrentUser` has moved
 * underneath it. The first draft of this class seeded a fresh profile per test, so every test after the
 * first was reading the *first* test's rows — rows whose series, reminders and work intervals had since been
 * cascade-deleted with their profile. The symptoms looked like card defects and were not: a repeat mark
 * missing from a row whose state said it had a series, a work line missing from a row the database said was
 * being measured, and a due line one minute away from the deadline the test had actually written. One seed
 * per class and one cleanup at the end, and every screen in the run looks at the seven rows the run owns.
 *
 * Change animations are off for the same reason: a row whose work figure ticks is a row under permanent
 * change animation, and a crossfade keeps the previous binding of that position on the screen beside the
 * live one. Both answer to the same ids; only one holds the state. The photographs are of a settled screen,
 * where an animation would have been a blur rather than evidence.
 */
@RunWith(AndroidJUnit4::class)
class TaskCardStatesInstrumentedTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun openTheScreenTheProfileWasSeededFor() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity ->
            val list = activity.findViewById<RecyclerView>(R.id.recyclerView)
            (list.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        }
        assertTrue(
            "every seeded state is drawn before anything is read",
            waitUntil("the seeded rows") { rowCount() == SEEDED }
        )
    }

    @After
    fun closeTheScreen() {
        scenario.close()
    }

    /** The ordinary card: what a TODO with no deadline, no category and no timer is allowed to say. */
    @Test
    fun aPlainTodoRowShowsItsTitleItsWordAndNoInvention() {
        assertEquals("the pill says the stored status",
            context.getString(R.string.status_todo), pillText(PLAIN))
        assertEquals("a task with no deadline is not given one",
            context.getString(R.string.no_due_date), dueText(PLAIN))
        assertEquals("and the date line stays a secondary colour",
            ContextCompat.getColor(context, R.color.ink_text_secondary), dueView(PLAIN).currentTextColor)
        assertEquals("no category means no dot",
            View.GONE, part<View>(PLAIN, R.id.categoryDot).visibility)
        assertEquals("and the words the spec puts in its place",
            context.getString(R.string.no_category), textOf(PLAIN, R.id.taskCategory))
        // LOW on time is the one rule that draws nothing, and it keeps its 4dp of column so the titles of a
        // mixed list still line up: INVISIBLE, not GONE.
        assertEquals("a LOW row on time shows no rule",
            View.INVISIBLE, part<View>(PLAIN, R.id.priorityIndicator).visibility)
        assertEquals("nothing measured means no work line",
            View.GONE, part<View>(PLAIN, R.id.workRow).visibility)
        assertEquals("and no work control",
            View.GONE, part<View>(PLAIN, R.id.workControlButton).visibility)
        shot("s4-card-todo")
    }

    /** IN_PROGRESS: the accent pill, a relative "Today", and the category back as a dot plus a word. */
    @Test
    fun anInProgressRowSaysTodayNamesItsCategoryAndOffersPause() {
        assertEquals(context.getString(R.string.status_in_progress), pillText(PROGRESS))
        assertEquals("the accent is the pill's own text colour",
            ContextCompat.getColor(context, R.color.ink_accent),
            part<TextView>(PROGRESS, R.id.taskStatus).currentTextColor)
        assertEquals("line B says the relative day with the hour, never a bare date",
            relativeLine(progressDue), dueText(PROGRESS))
        assertEquals("the dot is drawn", View.VISIBLE, part<View>(PROGRESS, R.id.categoryDot).visibility)
        assertEquals("and carries the category's own colour, not the word's",
            CATEGORY_COLOR, part<View>(PROGRESS, R.id.categoryDot).backgroundTintList?.defaultColor)
        assertEquals(CATEGORY, textOf(PROGRESS, R.id.taskCategory))
        assertLifecycleRow(PROGRESS, pause = true, complete = true)
        shot("s4-card-inprogress")
    }

    /** PAUSED: the warning pill, "Tomorrow", and the resume control that replaced the second play icon. */
    @Test
    fun aPausedRowOffersResumeAndNobodyElse() {
        assertEquals(context.getString(R.string.status_paused), pillText(PAUSED))
        assertEquals("the paused word is coloured with the warning token",
            ContextCompat.getColor(context, R.color.ink_warning),
            part<TextView>(PAUSED, R.id.taskStatus).currentTextColor)
        assertEquals("a deadline tomorrow is said as tomorrow",
            context.getString(R.string.task_due_tomorrow_format, "09:00"), dueText(PAUSED))
        assertLifecycleRow(PAUSED, resume = true, complete = true)
        assertEquals("and the control says resume in words as well as in shape",
            context.getString(R.string.resume_task),
            part<ImageButton>(PAUSED, R.id.resumeTaskButton).contentDescription)
        shot("s4-card-paused")
    }

    /**
     * Late is a word, a colour and a rule — never a colour alone (spec §4.1's fourth rule, audit §4.3).
     *
     * The row is seeded LOW on purpose: the red edge can only have come from the deadline, because the
     * priority by itself would have drawn nothing at all.
     */
    @Test
    fun aLateRowSaysHowLateItIsAndPaintsTheRuleRed() {
        assertEquals(context.resources.getQuantityString(R.plurals.task_late_days, 3, 3), dueText(LATE))
        assertTrue("the sentence carries it, so colour is not the only signal: '${dueText(LATE)}'",
            dueText(LATE).contains("late"))
        assertEquals(ContextCompat.getColor(context, R.color.ink_error), dueView(LATE).currentTextColor)
        val rule = part<View>(LATE, R.id.priorityIndicator)
        assertEquals("a LOW priority, so the rule is red for one reason only", View.VISIBLE, rule.visibility)
        assertEquals("tinted through the rounded shape rather than painted over it",
            ContextCompat.getColor(context, R.color.ink_error), rule.backgroundTintList?.defaultColor)
        assertLifecycleRow(LATE, start = true, complete = true)
        shot("s4-card-overdue")
    }

    /** A finished task whose deadline also passed is not late: lateness is a claim about open work. */
    @Test
    fun aFinishedTaskWithAPastDeadlineIsNotPaintedLate() {
        assertTrue("the date is a date, not an accusation: '${dueText(DONE)}'",
            !dueText(DONE).contains("late"))
        assertEquals("and it is not coloured as one either",
            ContextCompat.getColor(context, R.color.ink_text_secondary), dueView(DONE).currentTextColor)
        assertLifecycleRow(DONE)
    }

    /** Line A's marks: a series and two reminders, each with the word the eye cannot see. */
    @Test
    fun aSeriesWithRemindersMarksLineAAndSaysWhatTheMarksMean() {
        val layout = part<TextView>(LONG, R.id.taskTitle).layout
        assertTrue("the title has a layout to inspect", layout != null)
        assertEquals("120 characters take the two lines the card allows", 2, layout!!.lineCount)
        assertTrue("and the rest goes under an ellipsis, not a hard clip",
            layout.getEllipsisCount(1) > 0)
        val repeat = part<View>(LONG, R.id.repeatIcon)
        assertEquals("the series marks line A", View.VISIBLE, repeat.visibility)
        assertEquals("and says the rate the due line used to print",
            context.getString(R.string.repeats_weekly), repeat.contentDescription)
        val bell = part<View>(LONG, R.id.reminderBadge)
        assertEquals("both lead times count", View.VISIBLE, bell.visibility)
        assertEquals(context.resources.getQuantityString(R.plurals.reminder_count, 2, 2),
            bell.contentDescription)
        assertNotEquals("the bell is not left on its generic label",
            context.getString(R.string.reminder_badge_description), bell.contentDescription)
        shot("s4-card-longtitle")
    }

    /** The measured row: the figure it has cost, the seconds still running, and the one control. */
    @Test
    fun aRunningStretchPutsTheFigureTheTickerAndTheStopwatchOnLineC() {
        assertEquals("the line is drawn", View.VISIBLE, part<View>(RUNNING, R.id.workRow).visibility)
        val measured = WorkTimeFormat.compact(context, CLOSED_SECONDS)
        assertEquals("the figure is the stored interval", measured, textOf(RUNNING, R.id.workTime))
        assertEquals("and it says what it is a count of",
            context.getString(R.string.work_card_description, measured),
            part<TextView>(RUNNING, R.id.workTime).contentDescription)
        val figures = part<ViewGroup>(RUNNING, R.id.workRow)
        val group = part<ViewGroup>(RUNNING, R.id.buttonGroup)
        // The rule's own measure: a LinearLayout's right edge is its own box, while its children can be laid
        // out past it — so the check that means something is the last figure that is still drawn.
        val lastFigure = (0 until figures.childCount).map { figures.getChildAt(it) }
            .lastOrNull { it.visibility == View.VISIBLE }
        if (lastFigure != null) {
            assertTrue("the figures never reach into the action cluster: the last one ends at " +
                "${lastFigure.right}, the cluster starts at ${group.left}", lastFigure.right <= group.left)
        }
        assertEquals("the dot says running",
            View.VISIBLE, part<View>(RUNNING, R.id.workDot).visibility)
        assertEquals("one button, named for the phase",
            context.getString(R.string.work_pause),
            part<ImageButton>(RUNNING, R.id.workControlButton).contentDescription)

        // Which side of the give-way rule a row sits on is the budget's own arithmetic: spec §4.1 leaves the
        // measured-time figures 56dp at 320dp and 88dp at 360dp, and a total beside a running ticker needs
        // more than the first. Measured at each width this stage is verified at — 320dp gives way, 360dp
        // and 393dp draw both — and either way the two figures move together and the controls stay.
        val drawn = listOf(R.id.workTime, R.id.workTicker).map { part<View>(RUNNING, it).visibility }
        if (widthDp() <= 320) {
            assertEquals("at ${widthDp()}dp the figures collapse to their icon together",
                listOf(View.GONE, View.GONE), drawn)
        } else {
            assertEquals("at ${widthDp()}dp the total and the live seconds are both drawn",
                listOf(View.VISIBLE, View.VISIBLE), drawn)
        }
        shot("s4-card-running")
    }

    /**
     * The icons the audit found interchangeable are distinguishable in the renderer, not only in XML.
     *
     * Two `ic_play` triangles meaning different things was audit §4.6. Shape is compared by drawing each
     * vector into a bitmap under one flat tint, so a colour difference cannot be mistaken for a geometry
     * difference and a geometry difference cannot hide behind a colour. Every drawing is then checked to
     * have put ink down at all, because two blank canvases are identical and comparing them proves nothing —
     * which is not hypothetical here: these vectors take their colour from a theme attribute, so loaded on a
     * context that has no theme they render nothing, and the first version of this test "passed" on four
     * empty bitmaps.
     */
    @Test
    fun theStopwatchAndTheResumeMarkAreNotAnotherPlayTriangle() {
        fun shape(id: Int) = shapeOf(themedDrawable(id))
        val play = shape(R.drawable.ic_play)
        val work = shape(R.drawable.ic_work)
        val resume = shape(R.drawable.ic_resume)
        val pause = shape(R.drawable.ic_pause)
        listOf("play" to play, "work" to work, "resume" to resume, "pause" to pause).forEach {
            (name, pixels) ->
            assertTrue(
                "$name drew ${coverage(pixels)} pixels — a shape that draws nothing compares equal to " +
                    "every other shape that draws nothing",
                coverage(pixels) >= MIN_COVERED_PIXELS
            )
        }
        assertTrue("the timer control is its own shape", !play.contentEquals(work))
        assertTrue("and so is the resume control", !play.contentEquals(resume))
        assertTrue("pause is not play either", !play.contentEquals(pause))
        assertTrue("and the stopwatch is not the resume mark", !work.contentEquals(resume))

        val onRunningCard = shapeOf(part<ImageButton>(RUNNING, R.id.workControlButton).drawable)
        assertTrue("the running card draws the stopwatch", work.contentEquals(onRunningCard))
        val onPausedCard = shapeOf(part<ImageButton>(PAUSED, R.id.resumeTaskButton).drawable)
        assertTrue("the paused card draws the resume mark", resume.contentEquals(onPausedCard))
        assertTrue("two controls on one card are never the same picture",
            !shapeOf(part<ImageButton>(PLAIN, R.id.startTaskButton).drawable).contentEquals(onRunningCard))
    }

    /** One lifecycle button per card, and completion only while the task is still open (spec §4.1 line C). */
    @Test
    fun everyRowCarriesExactlyOneLifecycleButton() {
        assertLifecycleRow(PLAIN, start = true, complete = true)
        assertLifecycleRow(LATE, start = true, complete = true)
        assertLifecycleRow(PROGRESS, pause = true, complete = true)
        assertLifecycleRow(PAUSED, resume = true, complete = true)
        assertLifecycleRow(LONG, pause = true, complete = true)
        assertLifecycleRow(RUNNING, pause = true, complete = true)
        assertLifecycleRow(DONE)
    }

    /**
     * Every control a row shows is a full target that says what it does, and completion is the only one with
     * a fill. The baseline had 40dp targets and no emphasis at all; both are pinned here across every state
     * rather than on the one row the budget class happens to seed.
     */
    @Test
    fun everyVisibleControlIsATargetThatSpeaksAndCompletionIsTheOneFilled() {
        val inkAccent = ContextCompat.getColor(context, R.color.ink_accent)
        ALL.forEach { title ->
            val group = part<ViewGroup>(title, R.id.buttonGroup)
            val visible = (0 until group.childCount).map { group.getChildAt(it) }
                .filter { it.visibility == View.VISIBLE }
            visible.forEach { control ->
                val name = resources.getResourceEntryName(control.id)
                assertTrue("$name on '$title' is ${control.width}x${control.height}, and a target is " +
                    "${48.dp()}x${48.dp()}", control.width >= 48.dp() && control.height >= 48.dp())
                val label = (control as ImageButton).contentDescription?.toString().orEmpty()
                assertTrue("$name on '$title' says nothing ('$label')", label.isNotBlank())
                if (control.id != R.id.completeTaskButton) {
                    assertNotEquals("$name must not copy the filled treatment", inkAccent,
                        fillColourOf(control))
                }
            }
            val complete = part<ImageButton>(title, R.id.completeTaskButton)
            if (complete.visibility == View.VISIBLE) {
                assertEquals("the only emphasis on a card belongs to completion", inkAccent,
                    fillColourOf(complete))
            }
        }
    }

    /** The swipe panel still opens on the rewritten card, and the two actions it hides are reachable. */
    @Test
    fun swipingARowOpensThePanelTheCardSlidesOver() {
        revealRow(PLAIN)
        onView(allOf(withId(R.id.taskCard), hasDescendant(withText(PLAIN)))).perform(REVEAL_SWIPE)
        assertTrue("the card sits exactly over its own panel",
            waitUntil("the revealed panel") { openState(PLAIN).slidOver })
        // Read from the row's own panel: with seven rows laid out, `onView(withId(editTaskButton))` matches
        // seven views and reports an ambiguity instead of an answer. The slide above is what proves the
        // panel is open; these two prove the actions it hides are where a thumb can reach them.
        assertTrue("edit is laid out where a thumb can reach it",
            shownOnScreen(part<View>(PLAIN, R.id.editTaskButton)))
        assertTrue("and delete beside it", shownOnScreen(part<View>(PLAIN, R.id.deleteTaskButton)))
        shot("s4-swiped")
    }

    /** Selection mode: the check takes the handle's place at the same width, and the bar comes up. */
    @Test
    fun holdingTwoRowsSelectsBothAndReplacesTheHandleWithTheCheck() {
        revealRow(PLAIN)
        onView(allOf(withId(R.id.taskCard), hasDescendant(withText(PLAIN)))).perform(HOLD)
        revealRow(LATE)
        onView(allOf(withId(R.id.taskCard), hasDescendant(withText(LATE)))).perform(HOLD)
        assertTrue("the bar came up",
            waitUntil("the selection bar") { view(R.id.selectionCount)?.visibility == View.VISIBLE })
        assertEquals("the first row holds a check", View.VISIBLE,
            part<View>(PLAIN, R.id.selectionCheck).visibility)
        assertEquals("and no handle", View.GONE, part<View>(PLAIN, R.id.dragHandle).visibility)
        assertEquals("the second row agrees", View.VISIBLE,
            part<View>(LATE, R.id.selectionCheck).visibility)
        assertEquals("the check is a full target too", 48.dp(),
            part<View>(PLAIN, R.id.selectionCheck).width)
        shot("s4-selection")
    }

    // -- the row, read off the screen ---------------------------------------------------------------

    private fun pillText(title: String) = textOf(title, R.id.taskStatus)

    private fun dueText(title: String) = textOf(title, R.id.taskDueDate)

    private fun dueView(title: String) = part<TextView>(title, R.id.taskDueDate)

    private fun textOf(title: String, id: Int): String = part<TextView>(title, id).text.toString()

    private fun fillColourOf(view: View): Int? =
        (view.background as? GradientDrawable)?.color?.defaultColor

    /**
     * The lifecycle claim for one row: exactly one of start / pause / resume, and completion only while the
     * task is open. The named arguments keep the promise readable at the call site.
     */
    private fun assertLifecycleRow(
        title: String,
        start: Boolean = false,
        pause: Boolean = false,
        resume: Boolean = false,
        complete: Boolean = false
    ) {
        assertEquals("'$title' offers exactly one lifecycle button",
            listOf(start, pause, resume).count { it }, lifecycleVisible(title))
        assertEquals("start on '$title'", if (start) View.VISIBLE else View.GONE,
            part<View>(title, R.id.startTaskButton).visibility)
        assertEquals("pause on '$title'", if (pause) View.VISIBLE else View.GONE,
            part<View>(title, R.id.pauseTaskButton).visibility)
        assertEquals("resume on '$title'", if (resume) View.VISIBLE else View.GONE,
            part<View>(title, R.id.resumeTaskButton).visibility)
        assertEquals("complete on '$title'", if (complete) View.VISIBLE else View.GONE,
            part<View>(title, R.id.completeTaskButton).visibility)
    }

    private fun lifecycleVisible(title: String): Int {
        val group = part<ViewGroup>(title, R.id.buttonGroup)
        val lifecycle = listOf(R.id.startTaskButton, R.id.pauseTaskButton, R.id.resumeTaskButton)
        return (0 until group.childCount).map { group.getChildAt(it) }
            .count { it.visibility == View.VISIBLE && it.id in lifecycle }
    }

    /** The row's own slide, measured against the panel it is supposed to be sitting on. */
    private data class OpenState(val slidOver: Boolean, val translation: Float, val panelWidth: Int)

    private fun openState(title: String): OpenState {
        val row = rowView(title) ?: return OpenState(false, 0f, -1)
        val card = row.findViewById<View>(R.id.taskCard)
        val panel = row.findViewById<View>(R.id.actionPanel)
        return OpenState(
            slidOver = panel.width > 0 && card.translationX == -panel.width.toFloat(),
            translation = card.translationX,
            panelWidth = panel.width
        )
    }

    /** Whether this view is where a thumb could hit it: laid out, shown, and inside the window. */
    private fun shownOnScreen(view: View): Boolean {
        val where = IntArray(2).also { view.getLocationInWindow(it) }
        return view.isShown && view.width > 0 && view.height > 0 &&
            where[0] >= 0 && where[0] < resources.displayMetrics.widthPixels
    }

    /** A child of the row that carries this title, brought onto the screen first if the list parked it. */
    private fun <V : View> part(title: String, id: Int): V {
        findPart<V>(title, id)?.let { return it }
        scroll { it.scrollToPosition(0) }
        repeat(SCROLL_ATTEMPTS) {
            findPart<V>(title, id)?.let { return it }
            scroll { it.scrollBy(0, SCROLL_STEP_DP.dp()) }
        }
        throw AssertionError(
            "the row '$title' with '${resources.getResourceEntryName(id)}' never reached the screen"
        )
    }

    private fun <V : View> findPart(title: String, id: Int): V? = rowView(title)?.findViewById<V>(id)

    /** The row currently laid out for this title, or null while the list is holding it off screen. */
    private fun rowView(title: String): View? {
        var found: View? = null
        scenario.onActivity { activity ->
            val list = activity.findViewById<RecyclerView>(R.id.recyclerView)
            for (i in 0 until list.childCount) {
                val child = list.getChildAt(i)
                if (child.findViewById<TextView>(R.id.taskTitle)?.text?.toString() == title) found = child
            }
        }
        return found
    }

    private fun scroll(action: (RecyclerView) -> Unit) {
        scenario.onActivity { activity -> action(activity.findViewById<RecyclerView>(R.id.recyclerView)) }
        instrumentation.waitForIdleSync()
    }

    /**
     * Brings the named row to the top of the list and waits for it to be laid out.
     *
     * Espresso acts on what the screen is holding, and the list's own order — deadlines first, tasks with no
     * deadline last — puts the plain row below the fold at 393dp. `part()` scrolls on demand for the
     * assertions that only read; a gesture needs the row placed rather than merely present, so the two
     * injected gestures call this first.
     */
    private fun revealRow(title: String) {
        val position = adapterPositionOf(title)
        scroll { list ->
            (list.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(position, 0)
        }
        assertTrue("the row '$title' came to the top", waitUntil("the row on screen") {
            val row = rowView(title)
            row != null && row.top >= 0 && (row.findViewById<View>(R.id.taskCard)?.height ?: 0) > 0
        })
    }

    private fun adapterPositionOf(title: String): Int {
        var position = -1
        scenario.onActivity { activity ->
            val list = activity.findViewById<RecyclerView>(R.id.recyclerView)
            val adapter = list.adapter as? TasksAdapter ?: return@onActivity
            adapter.currentList.forEachIndexed { index, item ->
                if (item is TasksListItem.TaskRow && item.task.title == title && position == -1) {
                    position = index
                }
            }
        }
        if (position < 0) throw AssertionError("the list holds no row titled '$title'")
        return position
    }

    // -- the helpers ------------------------------------------------------------------------------

    private val resources get() = context.resources

    /** The width the configuration this invocation ran under reports, in dp. */
    private fun widthDp(): Int = resources.configuration.screenWidthDp

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

    /**
     * One flat tint, one canvas, the pixels the platform made.
     *
     * The tint is set here rather than read from the card so the comparison is about geometry: the same path
     * under two colours stays one path, and two paths under one colour stay two paths.
     */
    private fun shapeOf(drawable: Drawable): IntArray {
        val flat = drawable.mutate()
        flat.setTint(Color.WHITE)
        val width = flat.intrinsicWidth
        val height = flat.intrinsicHeight
        assertTrue("a shape sized $width x $height cannot be compared with another shape",
            width > 0 && height > 0)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        flat.setBounds(0, 0, width, height)
        flat.draw(Canvas(bitmap))
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        bitmap.recycle()
        return pixels
    }

    /** Pixels this shape actually covers, so a blank render is a failure rather than a match. */
    private fun coverage(pixels: IntArray): Int = pixels.count { Color.alpha(it) != 0 }

    /**
     * A card's drawable, from the context a card has.
     *
     * These vectors take their `fillColor`/`strokeColor` from a theme attribute, and the instrumentation's
     * target context carries none: loaded there a vector resolves to nothing and draws nothing, which makes
     * every pair of shapes equal to every other. The activity is the context the card itself inflates under,
     * so its rendering is the one worth comparing.
     */
    private fun themedDrawable(id: Int): Drawable {
        var drawable: Drawable? = null
        scenario.onActivity { activity -> drawable = ContextCompat.getDrawable(activity, id) }
        return requireNotNull(drawable) { "the theme offers no ${resources.getResourceEntryName(id)}" }
    }

    /**
     * The line the card should print for a deadline the seed chose: today or tomorrow, with the hour.
     *
     * The bucket is derived from the instant that was written rather than from a literal, because which word
     * applies depends on when the run started — a run begun at 23:00 with a deadline two hours later is a
     * "Tomorrow 01:00" card. What is *not* derived here is the rule: the two format strings are the spec's
     * own copy, and the row seeded three days in the past tests the third branch.
     */
    private fun relativeLine(due: Long): String {
        val which = if (startOfDay(due) == startOfDay(seedNow)) R.string.task_due_today_format
        else R.string.task_due_tomorrow_format
        return context.getString(which,
            java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(java.util.Date(due)))
    }

    private fun rowCount(): Int {
        val adapter = (view(R.id.recyclerView) as? RecyclerView)?.adapter as? TasksAdapter
        return adapter?.currentList?.count { it is TasksListItem.TaskRow } ?: -1
    }

    private fun view(id: Int): View? {
        var found: View? = null
        runCatching { scenario.onActivity { activity -> found = activity.findViewById<View>(id) } }
        return found
    }

    private fun waitUntil(description: String, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
        var lastFailure: Throwable? = null
        while (System.currentTimeMillis() < deadline) {
            val outcome = runCatching(condition)
            if (outcome.getOrNull() == true) return true
            lastFailure = outcome.exceptionOrNull()
            Thread.sleep(50)
        }
        throw AssertionError(
            "$description did not happen within $TIMEOUT_MILLIS ms. " +
                "rows=${runCatching { rowCount() }.getOrNull()}",
            lastFailure
        )
    }

    /**
     * A screen of this app, photographed where adb can reach it afterwards.
     *
     * Two things make a frame unusable as evidence, and both were seen on this run: the window can be
     * photographed before it has focus, and the compositor can answer with a frame in which the list has
     * not drawn its card yet — a screenshot of a header over an empty screen proves nothing about a card. So
     * the capture waits for the row to be laid out and shown, takes the frame, counts the ink inside the
     * card's own rectangle, and takes the frame again when there is none. A sleep alone would not do: the
     * bad frames arrived seconds after the row was measured, so the check is on the pixels.
     */
    private fun shot(name: String) {
        assertTrue("the row is laid out before it is photographed",
            waitUntil("the card drawn") {
                val view = view(R.id.taskCard)
                view != null && view.width > 0 && view.isShown
            })
        instrumentation.waitForIdleSync()
        // The rectangle is read after the wait, not before it: a card measured before the list settled has
        // the position it is leaving, not the one it is photographed in.
        val card = view(R.id.taskCard)
        val where = IntArray(2).also { card?.getLocationInWindow(it) }
        val bounds = card?.let {
            android.graphics.Rect(where[0], where[1], where[0] + it.width, where[1] + it.height)
        }
        var attempt = 0
        var frame: Bitmap? = null
        while (attempt < CAPTURE_ATTEMPTS) {
            attempt++
            val taken = runCatching { instrumentation.uiAutomation.takeScreenshot() }.getOrNull()
                ?: throw AssertionError("the platform refused the screenshot '$name' this run cites")
            if (bounds == null || hasContrastInside(taken, bounds)) { frame = taken; break }
            taken.recycle()
            instrumentation.waitForIdleSync()
            Thread.sleep(SETTLE_MILLIS)
        }
        val bitmap = frame ?: throw AssertionError(
            "the screen never drew the card into a photographed frame in $CAPTURE_ATTEMPTS attempts"
        )
        val directory = File(context.filesDir, "shots").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        }
        bitmap.recycle()
        println("screenshot written $name.png at ${resources.configuration.screenWidthDp}dp " +
            "fontScale=${resources.configuration.fontScale} after $attempt frame(s)")
    }

    /**
     * Whether this frame really has a card where the card should be.
     *
     * "Any dark pixel" was the first attempt, and it is wrong in the dark theme, where the window background
     * is itself dark: every frame passed, including the empty ones this check exists to catch. What a drawn
     * card has and an undrawn list does not is contrast — near-black text on a near-white surface, or the
     * same pair inverted — so the test is the luminance range inside the card's own rectangle. A flat frame
     * spans a few points; a card with a title, a rule and a filled button in it spans hundreds.
     */
    private fun hasContrastInside(frame: Bitmap, bounds: android.graphics.Rect): Boolean {
        val left = bounds.left.coerceIn(0, frame.width - 1)
        val top = bounds.top.coerceIn(0, frame.height - 1)
        val right = bounds.right.coerceIn(left + 1, frame.width)
        val bottom = bounds.bottom.coerceIn(top + 1, frame.height)
        var darkest = Int.MAX_VALUE
        var lightest = Int.MIN_VALUE
        var y = top
        while (y < bottom) {
            var x = left
            while (x < right) {
                val pixel = frame.getPixel(x, y)
                val luminance = (Color.red(pixel) * 299 + Color.green(pixel) * 587 +
                    Color.blue(pixel) * 114) / 1000
                if (luminance < darkest) darkest = luminance
                if (luminance > lightest) lightest = luminance
                x += 3
            }
            y += 3
        }
        return lightest - darkest >= MIN_CONTRAST_SPREAD
    }

    companion object {
        const val PLAIN = "Позвонить в деканат"
        const val LATE = "Отправить главу на проверку"
        const val PROGRESS = "Собрать источники для второй главы"
        const val PAUSED = "Записаться в архив"
        const val RUNNING = "Разобрать замечания руководителя"
        const val DONE = "Сдать черновик"

        /** The worst legal title: as long as a task is allowed to be, and Cyrillic, as this account's are. */
        val LONG: String = "Т".repeat(120)
        val ALL = listOf(PLAIN, LATE, PROGRESS, PAUSED, RUNNING, DONE, LONG)

        const val CATEGORY = "Диссертация"
        val CATEGORY_COLOR: Int = 0xFF2F6F5E.toInt()
        const val CLOSED_SECONDS = 1800L

        const val SEEDED = 7
        const val DAY = 86_400_000L
        const val HOUR = 3_600_000L

        /** Fewer covered pixels than this means the vector drew nothing, not a different shape. */
        const val MIN_COVERED_PIXELS = 20

        const val SCROLL_STEP_DP = 90
        const val SCROLL_ATTEMPTS = 16
        const val TIMEOUT_MILLIS = 60_000L

        /** How many frames a photograph may be retaken for, and how long it waits between them. */
        const val CAPTURE_ATTEMPTS = 6
        const val SETTLE_MILLIS = 250L

        /**
         * Luminance points the card's own rectangle has to span for a frame to be kept. A flat screen spans
         * a handful; a card with text and a filled button in it spans hundreds.
         */
        const val MIN_CONTRAST_SPREAD = 120

        val REVEAL_SWIPE = GeneralSwipeAction(
            Swipe.FAST, GeneralLocation.CENTER, GeneralLocation.CENTER_LEFT, Press.FINGER
        )
        val HOLD = GeneralSwipeAction(
            Swipe.SLOW, GeneralLocation.CENTER, GeneralLocation.CENTER, Press.FINGER
        )

        /** The profile every screen in this run reads, and the two instants two assertions quote. */
        private var seededUserId: Long = 0
        private var progressDue = 0L
        private var seedNow = 0L

        private val targetContext: Context
            get() = InstrumentationRegistry.getInstrumentation().targetContext

        /**
         * The seven rows, written once, before any screen opens.
         *
         * `occurrenceAt` on the series row is the series' own cursor: that is what a real instance looks
         * like, and it keeps `ensureOpenInstances` — which runs when a profile becomes active — from
         * answering "no live instance at the cursor" by adding an eighth row to a list this class counts.
         * The open work interval respects the unique `open_marker` index, one running stretch per profile in
         * SQL, and the IN_PROGRESS deadline is two hours out rather than a fixed evening hour, so the
         * relative wording holds whenever the run happens.
         */
        @JvmStatic
        @BeforeClass
        fun signInTheProfileThisClassReads() {
            val database = openDatabase()
            val now = System.currentTimeMillis()
            seedNow = now
            runBlocking {
                seededUserId = database.userDao().insertUser(
                    User(email = "states-${System.nanoTime()}@example.test", name = "Аня Родина")
                )
                database.userDao().setCurrentUser(database.userDao().getUserById(seededUserId)!!)
                val categoryId = database.categoryDao().insertCategory(
                    Category(id = 0, uuid = UUID.randomUUID().toString(), name = CATEGORY,
                        color = CATEGORY_COLOR, icon = "", userId = seededUserId)
                )

                database.taskDao().insertTask(
                    Task(uuid = UUID.randomUUID().toString(), title = PLAIN, description = "",
                        userId = seededUserId, status = TaskStatus.TODO, priority = Priority.LOW)
                )
                database.taskDao().insertTask(
                    Task(uuid = UUID.randomUUID().toString(), title = LATE, description = "Жду ответа",
                        userId = seededUserId, status = TaskStatus.TODO, priority = Priority.LOW,
                        dueDate = now - 3 * DAY, categoryId = categoryId)
                )
                progressDue = now + 2 * HOUR
                database.taskDao().insertTask(
                    Task(uuid = UUID.randomUUID().toString(), title = PROGRESS, description = "",
                        userId = seededUserId, status = TaskStatus.IN_PROGRESS,
                        dueDate = progressDue, categoryId = categoryId)
                )
                database.taskDao().insertTask(
                    Task(uuid = UUID.randomUUID().toString(), title = PAUSED, description = "",
                        userId = seededUserId, status = TaskStatus.PAUSED, dueDate = tomorrowAt(9, 0))
                )

                // Line A's row: a series and two lead times, on a title long enough to need both lines.
                val longUuid = UUID.randomUUID().toString()
                val longDue = now + 30 * DAY
                val rule = RecurrenceRule(
                    uuid = "states-series-$seededUserId", userId = seededUserId,
                    unit = RepeatUnit.WEEK, interval = 1, weekDays = "", dayOfMonth = 0,
                    anchorAt = longDue, cursorAt = longDue
                )
                database.recurrenceDao().upsertRule(rule)
                database.taskDao().insertTask(
                    Task(uuid = longUuid, title = LONG, description = "",
                        userId = seededUserId, status = TaskStatus.IN_PROGRESS, dueDate = longDue,
                        categoryId = categoryId, seriesUuid = rule.uuid, occurrenceAt = longDue)
                )
                listOf(15, 60).forEach { minutes ->
                    database.reminderDao().insertOrIgnore(
                        Reminder(uuid = UUID.randomUUID().toString(), taskUuid = longUuid,
                            userId = seededUserId, offsetMinutes = minutes,
                            triggerAt = longDue - minutes * 60_000L, anchorAt = longDue)
                    )
                }

                // The measured row: one closed interval, so the card has a figure, and one open, so it has a
                // ticker and the running phase.
                val runningUuid = UUID.randomUUID().toString()
                val runningId = database.taskDao().insertTask(
                    Task(uuid = runningUuid, title = RUNNING, description = "",
                        userId = seededUserId, status = TaskStatus.IN_PROGRESS, categoryId = categoryId)
                )
                val session = "states-session-$seededUserId"
                database.workSessionDao().insert(closedInterval(session, runningId, runningUuid, now))
                database.workSessionDao().insert(
                    WorkSession(
                        uuid = "states-open-${System.nanoTime()}", userId = seededUserId,
                        trackingSessionUuid = session, taskId = runningId, taskUuid = runningUuid,
                        startedAt = now - 45 * 60_000L, localDay = LocalDate.now().toEpochDay(),
                        utcOffsetMinutes = zoneOffsetMinutes(), openMarker = session
                    )
                )

                database.taskDao().insertTask(
                    Task(uuid = UUID.randomUUID().toString(), title = DONE, description = "",
                        userId = seededUserId, status = TaskStatus.COMPLETED, dueDate = now - DAY,
                        completedAt = now - 2 * 60_000L)
                )
            }
            database.close()
        }

        /**
         * The rows go first, then the profile.
         *
         * A task carries no foreign key to its user — the same decision the sync and import paths make, so a
         * row can arrive before the profile that owns it — which means deleting the user alone would leave
         * these seven rows on the device forever.
         */
        @JvmStatic
        @AfterClass
        fun takeTheProfileAndItsRowsAway() {
            val database = openDatabase()
            runBlocking {
                database.taskDao().getActiveTasksOnce(seededUserId)
                    .forEach { database.taskDao().deleteTask(it) }
                database.userDao().getUserById(seededUserId)?.let { database.userDao().deleteUser(it) }
            }
            database.close()
        }

        private fun openDatabase() = Room
            .databaseBuilder(targetContext, AppDatabase::class.java, AppDatabase.NAME)
            .addMigrations(*AppMigrations.ALL).build()

        private fun closedInterval(session: String, taskId: Long, taskUuid: String, now: Long) =
            WorkSession(
                uuid = "states-closed-${System.nanoTime()}", userId = seededUserId,
                trackingSessionUuid = session, taskId = taskId, taskUuid = taskUuid,
                startedAt = now - (CLOSED_SECONDS + 600) * 1000L, endedAt = now - 600_000L,
                durationSeconds = CLOSED_SECONDS,
                localDay = LocalDate.now().toEpochDay(), utcOffsetMinutes = zoneOffsetMinutes()
            )

        private fun zoneOffsetMinutes(): Int =
            ZoneId.systemDefault().rules.getOffset(Instant.now()).totalSeconds / 60

        private fun tomorrowAt(hour: Int, minute: Int): Long = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        private fun startOfDay(millis: Long): Long = Calendar.getInstance().apply {
            timeInMillis = millis
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}
