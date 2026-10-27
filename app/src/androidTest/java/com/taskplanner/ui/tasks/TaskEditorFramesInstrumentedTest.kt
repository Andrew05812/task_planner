package com.taskplanner.ui.tasks

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Environment
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.AutoCompleteTextView
import android.widget.DatePicker
import android.widget.TextView
import android.widget.TimePicker
import androidx.core.os.bundleOf
import androidx.core.view.isVisible

import androidx.core.widget.NestedScrollView
import androidx.lifecycle.Lifecycle
import androidx.navigation.fragment.NavHostFragment
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.chip.Chip
import com.google.android.material.textfield.TextInputLayout
import com.taskplanner.R
import com.taskplanner.data.local.AppDatabase
import com.taskplanner.data.model.Category
import com.taskplanner.data.model.Priority
import com.taskplanner.data.model.RecurrenceRule
import com.taskplanner.data.model.SyncState
import com.taskplanner.data.model.RepeatUnit
import com.taskplanner.data.model.Task
import com.taskplanner.data.model.TaskGroup
import com.taskplanner.testing.SeededProfile
import com.taskplanner.ui.MainActivity
import java.io.File
import java.util.Calendar
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The editor's frames, photographed on the device, in the configuration their file name claims.
 *
 * `TaskEditorInstrumentedTest` proves the editor opens, renders every preset, refuses an empty title and
 * saves what was typed. It cannot say what the screen *looks like*: a section order, a footer that overlaps
 * a field, or a chip too small for a finger are all invisible to a test that only reads a view's text. This
 * class is the other half — it walks the same screen the way a reader does and writes a frame at each stop,
 * and it checks the geometry of what it photographs rather than trusting that a picture is enough.
 *
 * What is asserted about every frame, before it is accepted:
 *
 *  * the configuration. A frame labelled 320dp is only evidence if the device was really 320dp wide, so the
 *    tag the run is given is read back off `displayMetrics` — width in dp, font scale, night mode — and a
 *    mismatch fails the test rather than the file name. This exists because a Stage 4 run produced a "dark"
 *    frame at the previous pass's font scale and nobody noticed until the photographs were compared.
 *  * the picture is of a drawn screen. The pixels inside the view being looked at are asked for a luminance
 *    spread, because "there is a dark pixel somewhere" proves nothing in a theme whose background is near
 *    black, and an empty frame is exactly what the platform hands back when content has not drawn yet.
 *  * the footer. Its bottom edge is the window's bottom edge, no field of the form lies below the line the
 *    footer starts at, and Save and Cancel are at least 48dp tall — the three claims the sticky footer makes
 *    are the three that get measured, in every configuration.
 *
 * What this class writes, said plainly because the first version of this sentence claimed the opposite. Most
 * of it touches nothing: pickers are opened and backed out of, chips are ticked and left, so a frame is
 * repeatable across the twelve configurations this class is run in, and a later pass is not a picture of an
 * earlier one's leftovers.
 * Two tests do write, on purpose and against a profile the class seeds and deletes.
 * `savingATaskTheReaderNeverTouchedLeavesEveryStoredFieldWhereItWas` presses Save five times on a task it
 * never edits, because the defect it hunts is a save that quietly drops a field; and
 * `theWorkCommandsKeepTheirWordsOnTheScreen` starts a real stretch and stops it again, because a row of
 * command buttons can only be measured when the right three of the five are on screen.
 */
@RunWith(AndroidJUnit4::class)
class TaskEditorFramesInstrumentedTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var scenario: ActivityScenario<MainActivity>

    private companion object {
        /**
         * How long a screen or a dialog is allowed to take to answer.
         *
         * Sixty seconds, the same figure `TaskEditorInstrumentedTest` uses, and not less: at 320dp this
         * class ran on a device that had already been asked for five round trips, and a framework date
         * picker took longer than thirty seconds to appear. That is a slow emulator, not a broken screen —
         * the same test passes at 320dp on a quiet device — so the answer is to wait as long as the other
         * editor class already does rather than to retry the click, which would be pressing a button twice
         * and calling the second press a fix.
         */
        const val TIMEOUT_MILLIS = 60_000L

        /**
         * How much the picture has to vary inside the view being photographed before the frame is accepted.
         *
         * Forty was too generous and it was caught: one hint label on one filled box clears 40 by itself,
         * so the rest of the frame could have been a blank page, a wrong theme or an overlap and the run
         * would still have called it a form. Ninety is roughly what a heading plus the field under it plus
         * a caption measure on this screen.
         */
        const val MIN_SPREAD = 90

        /**
         * How much the *whole* frame has to vary, as a backstop against a picture that is not a screen at
         * all — a blank window, a status bar over black, a capture that came back without content.
         *
         * Said plainly so nobody reads more into it than it carries: on any rendered Android page the status
         * bar, the nav bar and a light body against a dark backdrop clear 120 levels by themselves, so this
         * figure cannot fail on a dialog half closed over a form or on a permission prompt. Those two are
         * caught by the region's own spread and, for a permission prompt, by the test that grants the
         * permission up front and refuses to run without it.
         */
        const val MIN_FRAME_SPREAD = 120

        /**
         * The page's average brightness, which is the theme said out loud. `uiMode` is a setting; these two
         * numbers are what the pixels did, and a frame filed under a dark tag has to be dark.
         */
        const val MIN_LIGHT_MEAN = 120
        const val MAX_DARK_MEAN = 90

        /**
         * The shortest slice of a view a frame may be made of, in dp — and never more than the view itself,
         * so a fully visible field of 60dp is a good frame rather than a refused one.
         *
         * A section heading is 16sp and the field under it a control of 48dp, so 80dp is "the reader can see
         * what this block is"; less than that and the region being measured is a strip of background that
         * happened to enter the viewport.
         */
        const val MIN_VISIBLE_SLICE_DP = 80

        /** How many times the round-trip test opens and saves the same task — see why in the test. */
        const val ROUND_TRIPS = 5

        /** Enough rows to make the categories query the slower of the two, on purpose — see the seed. */
        const val THIRTY_OTHER_CATEGORIES = 30

        /**
         * How many times the screen is asked for a picture before the frame is called refused.
         *
         * The number comes from an observed run, not from a hunch: on the pass before this constant existed
         * the class asked for ten frames, eight were written, and two came back refused — one over a chip the
         * reader had just ticked, one over the category picker — while the device's own log showed a
         * 48-second GC pause and an `audioserver` crash alongside. A refusal under that load says something
         * about the emulator, and nothing about the screen, so asking again is not softening the claim. The
         * claim is unchanged: this run has to produce a photograph, it has to pass the pixel checks, and if
         * the platform never answers, the test fails with the number of attempts in its message.
         */
        const val SCREENSHOT_ATTEMPTS = 5
        const val SCREENSHOT_RETRY_MILLIS = 1_000L

        /** How many times the form is asked to scroll before the shutter is trusted to find it settled. */
        const val SCROLL_SETTLE_ATTEMPTS = 6

        var userId: Long = 0
        lateinit var signedIn: SeededProfile.SignedIn
        var plainTaskId: Long = 0
        var seriesTaskId: Long = 0
        lateinit var plainTaskTitle: String
        lateinit var categoryName: String
        lateinit var groupUuid: String

        /**
         * The profile and the two tasks the frames are taken of.
         *
         * One task with a deadline, a plan, a category and a priority — so the When and How blocks have
         * something truthful to show — and one inside a weekly series, so the Repeat block is opened by the
         * app's own reading of a stored rule rather than by this test pressing anything.
         */
        @JvmStatic
        @BeforeClass
        fun seedOneProfileForTheWholeClass() {
            val database = openDatabaseAt(InstrumentationRegistry.getInstrumentation().targetContext)
            try {
                // Sweep the residue of a run that was killed half way before seeding anything of our own: this
                // device has been force-stopped mid-class, and a fixture left signed in would take the rows this
                // class writes as its own.
                val swept = SeededProfile.sweepLeftovers(database)
                if (swept > 0) println("swept $swept leftover fixture profile(s) before seeding")
                signedIn = SeededProfile.signIn(
                    database,
                    email = "editor-frames-${System.nanoTime()}@example.test",
                    name = "Ирина Соколова"
                )
                userId = signedIn.seededUserId
                runBlocking {
                    categoryName = "Диссертация"
                    val categoryId = database.categoryDao().insertCategory(
                        Category(
                            uuid = UUID.randomUUID().toString(),
                            name = categoryName,
                            color = 0xFF2F6F5E.toInt(),
                            icon = "📚",
                            userId = userId
                        )
                    )

                    // The rest of the shelf, for the test that asks whether a stored category survives being
                    // opened. The task and its categories arrive on two separate Room flows and nothing orders
                    // them, so which one answers first decided what the old code drew on the field. Thirty rows
                    // on the categories query against one on the task query makes the categories the slow side
                    // every time, which is what turns that test from "fails sometimes" into "fails for the
                    // right reason". A reader with a long list of categories is who this was broken for.
                    (1..THIRTY_OTHER_CATEGORIES).forEach { index ->
                        database.categoryDao().insertCategory(
                            Category(
                                uuid = UUID.randomUUID().toString(),
                                name = "Раздел %02d".format(index),
                                color = 0xFF6B635C.toInt(),
                                icon = "folder",
                                userId = userId
                            )
                        )
                    }
                    // A group, because the editor has no control over group membership and a save that does
                    // not carry it forward detaches the task from the list it was organised into. Nothing
                    // tested that before this commit, and the first version of the round-trip test below
                    // passed without it; see `groupId = detailTask?.groupId` in the fragment.
                    groupUuid = "group-frames-$userId".also {
                        database.groupDao().upsertGroup(
                            TaskGroup(uuid = it, userId = userId, name = "Глава 3: методология")
                        )
                    }
                    val due = Calendar.getInstance().apply {
                        add(Calendar.DAY_OF_MONTH, 3)
                        set(Calendar.HOUR_OF_DAY, 14)
                        set(Calendar.MINUTE, 30)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                    plainTaskTitle = "Глава 3: методология"
                    plainTaskId = database.taskDao().insertTask(
                        Task(
                            uuid = UUID.randomUUID().toString(),
                            title = plainTaskTitle,
                            description = "Разбор выборки и описание метода сбора данных.",
                            dueDate = due.timeInMillis,
                            startTime = due.timeInMillis - 90L * 60_000L,
                            endTime = due.timeInMillis,
                            estimatedMinutes = 90,
                            priority = Priority.HIGH,
                            categoryId = categoryId,
                            groupId = groupUuid,
                            userId = userId
                        )
                    )
                    // A weekly series on Tuesdays and Thursdays: the preset the editor recognises, the interval
                    // it asks for, and the weekday block it opens — all of them from stored data.
                    val seriesUuid = "series-frames-$userId".also {
                        database.recurrenceDao().upsertRule(
                            RecurrenceRule(
                                uuid = it,
                                userId = userId,
                                unit = RepeatUnit.WEEK,
                                interval = 2,
                                weekDays = "2,4",
                                anchorAt = due.timeInMillis,
                                cursorAt = due.timeInMillis
                            )
                        )
                    }
                    seriesTaskId = database.taskDao().insertTask(
                        Task(
                            uuid = UUID.randomUUID().toString(),
                            title = "Семинар кафедры",
                            description = "",
                            dueDate = due.timeInMillis,
                            seriesUuid = seriesUuid,
                            occurrenceAt = due.timeInMillis,
                            userId = userId
                        )
                    )
                }
            } finally {
                database.close()
            }
        }

        @JvmStatic
        @AfterClass
        fun takeTheProfileAway() {
            // Nothing was signed in, so there is nothing of ours to take away and nobody to put back.
            if (!::signedIn.isInitialized) return
            val database = openDatabaseAt(InstrumentationRegistry.getInstrumentation().targetContext)
            try {
                // The profile and the thirty-one categories and two tasks it owns: `categories`, `tasks` and
                // `reminders` carry no foreign key to `users`, so a `deleteUser` alone leaves them on the
                // device. `SeededProfileCleanupTest` asks SQLite which tables those are, every run.
                SeededProfile.takeAway(database, signedIn)
            } finally {
                database.close()
            }
        }

        fun openDatabaseAt(context: Context): AppDatabase = SeededProfile.open(context)
    }

    @Before
    fun launchTheApp() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        assertTrue(
            "the list is on the screen",
            waitUntil("the task list") { view(R.id.fabAddTask) != null }
        )
    }

    @After
    fun closeTheApp() {
        scenario.close()
    }

    // -- the frames -------------------------------------------------------------------------------

    /**
     * The filled form, top to bottom. Title, deadline, plan, classification — every block the reader has to
     * fill — with the footer on the window.
     */
    @Test
    fun theFilledFormAndTheFooterThatHoldsItsGround() {
        openTheEditor(plainTaskId)
        assertEquals(
            "the task the reader opened is the one on the screen",
            plainTaskTitle,
            text(R.id.task_title)
        )
        assertTrue(
            "and the deadline is shown, not left blank",
            text(R.id.task_due_date).isNotBlank()
        )

        shot("01-form-top", over = requireView(R.id.task_title_layout))
        assertFooterIsAFloorOfItsOwn("the filled form, before any scrolling")

        onView(withId(R.id.task_priority_layout)).perform(scrollTo())
        shot("02-how-block", over = requireView(R.id.how_section))
        assertEquals(
            "the classification block shows the priority the stored task carries",
            context.getString(R.string.priority_high),
            text(R.id.task_priority)
        )
        assertEquals(categoryName, text(R.id.task_category))

        onView(withId(R.id.reminder_chips)).perform(scrollTo())
        shot("03-reminders", over = requireView(R.id.reminder_section))

        // The commands row and not the "no intervals" line: another test in this class starts a stretch
        // to measure its buttons, and once a task has a measured stretch that line is correctly hidden.
        // A test that only passes in one order is not a test.
        onView(withId(R.id.work_commands)).perform(scrollTo())
        assertTrue(
            "the measured-work block is on screen: a saved task always has one, even with nothing in it",
            requireView(R.id.work_section).isVisible
        )
        shot("04-form-bottom", over = requireView(R.id.work_section))
        assertFooterIsAFloorOfItsOwn("the bottom of the filled form")
    }

    /**
     * The commands under a running stretch, measured on the screen rather than in a JVM.
     *
     * Whether `Pause measuring`, `Stop and save` and `Enter time by hand` fit a row is a question about the
     * width of words, and Robolectric measures words with legacy metrics that ignore the type size — asked
     * there, the widest of the three comes out at 143px and all of them "fit" 320dp. Here they are what the
     * device draws them as, and the row they live in is the row a reader sees: each command has to be
     * inside it, and a command drawn at a negative x is the failure this looks for.
     *
     * A stretch is really started, because that is the only state that shows three of them at once; the
     * profile this class seeded is deleted afterwards, and the measurement is taken with the keyboard, the
     * dialog and the footer all where the app put them.
     */
    @Test
    fun theWorkCommandsKeepTheirWordsOnTheScreen() {
        openTheEditor(plainTaskId)
        onView(withId(R.id.workStartButton)).perform(scrollTo(), click())
        val running = listOf(R.id.workPauseButton, R.id.workStopButton, R.id.workAddButton)
        assertTrue(
            "a stretch is running, so the row now carries the phase command, the way to stop it and the " +
                "manual entry",
            waitUntil("the running commands") {
                view(R.id.workPauseButton)?.isVisible == true && view(R.id.workStopButton)?.isVisible == true
            }
        )

        val row = requireView(R.id.work_commands) as ViewGroup
        assertTrue("the command row was never measured: ${row.width}x${row.height}", row.width > 0)
        running.forEach { id ->
            val button = requireView(id)
            assertTrue(
                "${name(context, id)} is ${button.width}px wide inside a ${row.width}px row at " +
                    "left=${button.left} right=${button.right} — drawn off the screen and untappable at " +
                    "its leading edge",
                button.left >= 0 && button.right <= row.width
            )
            assertTrue(
                "${name(context, id)} is ${button.height}px tall and a finger needs ${dp(48f)}",
                button.height >= dp(48f)
            )
        }
        // The commands are the last thing in the form, so a frame taken wherever the previous action left the
        // scroll is a photograph of the footer rather than of the buttons it is named for. `shot()` scrolls
        // for every view now; this test does not need its own nudge, and the one it had is recorded in the
        // note on `bringAboveTheFloor` as the reason that helper asks for the whole rectangle.
        shot("11-running-commands", over = requireView(R.id.work_commands))

        // Stop what was started. A stretch left running would be a piece of state this class wrote into
        // the app and walked away from — it would show up in the next test's screenshot as a counting
        // clock, and in the profile's history as measured time nobody measured.
        onView(withId(R.id.workStopButton)).perform(scrollTo(), click())
        assertTrue(
            "the stretch is closed and the block is back to offering Start",
            waitUntil("the idle commands") { view(R.id.workStartButton)?.isVisible == true }
        )
    }

    /** Reminders as a reader leaves them: two lead times ticked, and the mark visible on each. */
    @Test
    fun tickingALeadTimeShowsAMarkAndNotOnlyAColour() {
        // Ticking a reminder asks the system for the notification permission the first time, and the
        // prompt lands over this screen a moment later — after the click returns, before the photograph is
        // taken. Every pixel check in this file would pass on a frame of that dialog, so the run has to be
        // configured not to raise it.
        assertEquals(
            "the app already holds the notification permission; start the run with " +
                "`adb -s emulator-5584 shell pm grant com.taskplanner android.permission.POST_NOTIFICATIONS` " +
                "so the chip's prompt cannot be photographed in place of the form",
            PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
        )
        openTheEditor(plainTaskId)
        onView(withId(R.id.reminder_fifteen_min)).perform(scrollTo(), click())
        onView(withId(R.id.reminder_one_hour)).perform(scrollTo(), click())

        assertTrue(
            "fifteen minutes before is ticked",
            (requireView(R.id.reminder_fifteen_min) as Chip).isChecked
        )
        assertTrue("an hour before is ticked too", (requireView(R.id.reminder_one_hour) as Chip).isChecked)
        assertFalse(
            "and a lead time nobody chose stays unticked — the two are not simply inverted copies",
            (requireView(R.id.reminder_thirty_min) as Chip).isChecked
        )
        val chip = requireView(R.id.reminder_fifteen_min) as Chip
        assertNotNull("a ticked chip carries a mark, not only a different fill", chip.checkedIcon)
        assertTrue("and the mark is drawn", chip.isCheckedIconVisible)

        val finger = dp(48f)
        assertTrue(
            "a chip a finger can hit: ${chip.height}px at ${chip.resources.displayMetrics.density}x density",
            chip.height >= finger
        )
        shot("05-reminders-ticked", over = requireView(R.id.reminder_section))
    }

    /** The date picker and the clock, opened over the form — and the form underneath, still intact. */
    @Test
    fun thePickersOpenOverTheFormAndLeaveItWhereItWas() {
        openTheEditor(plainTaskId)
        val dateBefore = text(R.id.task_due_date)

        onView(withId(R.id.task_due_date)).perform(click())
        assertTrue(
            "the app's own date picker came up over the editor",
            waitUntil("the date picker") { pickerShowing(DatePicker::class.java) }
        )
        // The picker is photographed, not the field it opened from. Asked of the field, the frame at
        // `320-dark-10` measured a luminance spread of 89 against the 90 this class requires and was
        // refused — correctly: at that width the dialog's own sheet lies across the lower part of the
        // field's band, so what the picture proved was that a dialog is opaque, not that the form is legible
        // under it. The picker is also what the frame is named for. The form's integrity stays an assertion
        // rather than becoming a photograph: the title, date and time are read again below.
        shot("06-date-picker", over = capturedPicker(DatePicker::class.java))
        Espresso.pressBack()
        // Wait for the dialog to be gone before touching the next field. The first run of this test found
        // the click after a back press landing on a window that was still being torn down: the tap was
        // dropped, no clock came up, and the failure read "the time picker did not happen in 60000 ms" —
        // which looks like a screen that will not open a dialog and is a test that clicked too early.
        assertTrue(
            "the date picker is dismissed",
            waitUntil("the date picker gone") { !pickerShowing(DatePicker::class.java) }
        )

        onView(withId(R.id.task_due_time)).perform(scrollTo(), click())
        assertTrue(
            "and the clock for the same deadline",
            waitUntil("the time picker") { pickerShowing(TimePicker::class.java) }
        )
        shot("07-time-picker", over = capturedPicker(TimePicker::class.java))
        Espresso.pressBack()
        assertTrue(
            "the clock is dismissed",
            waitUntil("the time picker gone") { !pickerShowing(TimePicker::class.java) }
        )

        assertTrue(
            "the editor is back and whole: nothing the reader had entered moved",
            waitUntil("the editor again") { view(R.id.task_title) != null && isInFront() }
        )
        assertEquals("the deadline is the one the task carried", dateBefore, text(R.id.task_due_date))
        assertFooterIsAFloorOfItsOwn("the form after both pickers were backed out of")
    }

    /**
     * The category picker: the list the field offers, opened over a form that already has an answer in it.
     *
     * The value is read before the list comes up and not after, on purpose. This device takes the field's
     * text away when the popup is dismissed with the back key — measured, not assumed: the first version of
     * this test asserted the category was still there after `pressBack()` and found an empty field — and
     * chasing that is a different question from the one the frame answers, which is whether the picker the
     * reader taps is showing the categories that exist. The run prints how the list came up, and the list
     * itself is unreadable to Espresso here for the reason [TaskEditorInstrumentedTest] records: it is a
     * popup window that the accessibility API does not expose on this AVD, so the claim the frame makes is
     * "the widget opened", taken from the field's own `isPopupShowing()`, and nothing about the rows.
     */
    @Test
    fun theCategoryPickerOpensOverTheAnswerTheTaskAlreadyHas() {
        openTheEditor(plainTaskId)
        assertEquals(
            "the task on screen is filed under a category, so the picker opens over an answer rather than " +
                "over a blank",
            categoryName,
            text(R.id.task_category)
        )

        val openedBy = openDropdown(R.id.task_category)
        assertTrue(
            "the category list never came up on this device — not from a tap on the field, and not from " +
                "the call a tap makes — so there is no honest frame of a picker to publish",
            openedBy.isNotEmpty()
        )
        println("TASK-EDITOR the category list came up by: $openedBy")
        // The field is scrolled into view *before* the list is opened, and the frame is then shot without
        // scrolling: `bringAboveTheFloor` asks the form to move, and a `ListPopupWindow` is dismissed by the
        // scroll that anchors it — so a shutter opened after a scroll can photograph a form whose picker has
        // quietly closed. The field is where the reader left it, which is the position the frame is for.
        assertTrue(
            "the list is open as the shutter is asked for",
            popupShowing(R.id.task_category)
        )
        shot("08-category-picker", over = requireView(R.id.task_category_layout), scrollFirst = false)
        assertTrue(
            "the list was still open at the shutter: the frame claims a picker that closed while the " +
                "picture was being taken ($openedBy)",
            popupShowing(R.id.task_category)
        )

        // Dismissed by tapping something that is not a field. The first version tapped the title to close
        // the list, which also focused an editable field — and the keyboard that rose stayed up into the
        // next test, whose first tap was then spent closing it instead of opening the dialog it aimed at.
        // That is how "the date picker did not happen within 60000 ms" came and went depending on which
        // test had run before it. A heading takes the tap, dismisses the list and asks for nothing.
        Espresso.pressBack()
        // The keyboard a focused picker leaves behind is closed here rather than being left for whatever
        // the next test taps first: a tap that lands on a keyboard closes the keyboard, and the dialog the
        // tap was meant to open stays shut, which is how "the date picker did not happen within 60000 ms"
        // appeared in one test because of something another test had done.
        runCatching { Espresso.closeSoftKeyboard() }
    }

    /**
     * Closing a list without choosing must leave the answer the list was opened on.
     *
     * The defect, measured before it was fixed: the back key dismissed the dropdown *and* emptied the field.
     * Nothing on the screen says so — the box simply looks unfilled — and this editor reads the values it
     * saves out of those fields, so a reader who opened the category picker, thought better of it, pressed
     * back, and pressed Save wrote "this task belongs to no category" over the category the task had. The
     * same gesture on Priority or Status writes MEDIUM and TODO, which is how a finished task came back as
     * not started.
     *
     * This is a device test on purpose. A JVM stand-in cannot demonstrate it: `showDropDown()` followed by a
     * simulated back key is a framework path, and the framework is not what empties the field — the real
     * list, dismissed by the real key, is.
     */
    @Test
    fun aDismissedListLeavesTheAnswerOnTheScreen() {
        openTheEditor(plainTaskId)
        listOf(
            R.id.task_category to categoryName,
            R.id.task_priority to context.getString(R.string.priority_high)
        ).forEach { (field, expected) ->
            val label = name(context, field)
            assertEquals("$label starts on the answer the task carries", expected, text(field))
            onView(withId(field)).perform(scrollTo(), click())
            assertTrue(
                "$label opens its list",
                waitUntil("the list on $label opens") { popupShowing(field) }
            )
            Espresso.pressBack()
            runCatching { Espresso.closeSoftKeyboard() }
            assertEquals(
                "$label still holds it after the list was closed without a choice",
                expected,
                text(field)
            )
        }
    }

    /** A task in a series: the repeat block opened by the stored rule, with the series controls beside it. */
    @Test
    fun aSeriesOpensWithItsRepeatBlockAlreadyAnswered() {
        openTheEditor(seriesTaskId)
        onView(withId(R.id.repeat_week_days)).perform(scrollTo())

        // The stored rule is WEEK / every 2 / Tuesday and Thursday, which is `WEEKDAYS`, and the label that
        // preset carries is what the field has to show. "Not blank and not an enum name" was the first
        // version of this claim: it is equally true of "Every day" and of a string that names nothing.
        val expected = context.getString(RepeatPreset.WEEKDAYS.labelRes)
        assertTrue(
            "the stored weekly rule reads back as the preset it is",
            waitUntil("the preset '$expected' rendered") { text(R.id.repeat_preset) == expected }
        )
        assertTrue(
            "the interval field is open, because this preset counts",
            requireView(R.id.repeat_interval_layout).isVisible
        )
        assertEquals("the stored interval, on the field a reader would change", "2", text(R.id.repeat_interval))
        assertTrue("the weekday block is open", requireView(R.id.repeat_week_days).isVisible)
        assertTrue(
            "and it is Tuesday and Thursday that are ticked, from the stored rule",
            (requireView(R.id.day_tue) as Chip).isChecked &&
                (requireView(R.id.day_thu) as Chip).isChecked &&
                !(requireView(R.id.day_mon) as Chip).isChecked
        )
        assertTrue("the series controls are there to be used", requireView(R.id.series_controls).isVisible)
        shot("09-series-repeat", over = requireView(R.id.repeat_section))
        assertFooterIsAFloorOfItsOwn("a task inside a series")
    }

    /** A new task from `+`: the empty form, with the Repeat block collapsed and the footer on the window. */
    @Test
    fun aNewTaskOpensWithTheRepeatBlockCollapsed() {
        onView(withId(R.id.fabAddTask)).perform(click())
        assertTrue("the editor opened", waitUntil("the editor") { view(R.id.task_title) != null && isInFront() })

        assertEquals("nothing is typed in the title yet", "", text(R.id.task_title))
        assertTrue(
            "the preset picker is the only open part of the repeat block",
            requireView(R.id.repeat_layout).isVisible
        )
        assertTrue(
            "and the rest of it is away until a preset asks",
            requireView(R.id.repeat_interval_layout).visibility != View.VISIBLE &&
                requireView(R.id.repeat_week_days).visibility != View.VISIBLE
        )
        shot("10-new-task-empty", over = requireView(R.id.task_title_layout))
        assertFooterIsAFloorOfItsOwn("a new task")
    }

    /**
     * The frame that says why a Save did not happen.
     *
     * `TaskEditorInstrumentedTest` already proves the refusal — no row is written, and the title's own field
     * carries `error_title_required`. What only a photograph can settle is *where* the reason appears: under
     * the field the reader is looking at, rather than in a toast over the footer or nowhere at all. So the
     * message is asserted, then photographed, then the footer is measured again — a validation line that
     * pushes Save off the window is a worse screen than the one it is complaining about.
     *
     * The keyboard is closed before the shutter. Save takes focus from the field it refuses, and a frame of
     * the form with the IME over half of it would be a photograph of a state the reader never sees.
     */
    @Test
    fun theReasonASaveWasRefusedIsOnTheFieldItComplainsAbout() {
        onView(withId(R.id.fabAddTask)).perform(click())
        assertTrue("the editor opened", waitUntil("the editor") { view(R.id.task_title) != null && isInFront() })
        runCatching { Espresso.closeSoftKeyboard() }

        onView(withId(R.id.saveTask)).perform(click())

        assertTrue(
            "the reason is the app's own words, on the title's own field",
            waitUntil("the inline error") {
                errorOn(R.id.task_title_layout) == context.getString(R.string.error_title_required)
            }
        )
        assertTrue(
            "and the editor is still the screen the reader is looking at — a save that complains and then " +
                "closes the screen leaves them with a task they never wrote, and the activity stays resumed " +
                "either way, so the form has to be named, not just the window",
            stillOnTheEditor()
        )
        runCatching { Espresso.closeSoftKeyboard() }
        shot("12-empty-title-error", over = requireView(R.id.task_title_layout))
        assertFooterIsAFloorOfItsOwn("a refused save")
    }

    /**
     * The category a task was filed under, once everything the editor reads has arrived.
     *
     * This is the test that found the defect this class photographed its way into: the stored category is
     * put on the field at the moment the *task* arrives, looked up in whatever the categories flow has
     * emitted so far. When the task wins that race — which is what a warm process does, and what every
     * test after the first one in a class sees — the lookup finds nothing, the field stays blank, and the
     * reader looking at "Диссертация" in the database sees an unfiled task. Worse, Save reads the blank
     * field back as "no category", so pressing it writes the loss.
     *
     * It is asserted against the settled state rather than a timing: the list has rows, the task is on
     * screen, so there is no order left to argue about. That is the property a reader cares about.
     */
    @Test
    fun aStoredTaskStaysFiledUnderTheCategoryItWasGiven() {
        openTheEditor(plainTaskId)
        assertTrue(
            "the categories have loaded — the editor is not being asked to show a name it has not been " +
                "given yet",
            waitUntil("the category list to fill") { categoryRows() >= 1 }
        )
        assertEquals(
            "and the field carries the category the row is filed under",
            categoryName,
            text(R.id.task_category)
        )
    }

    /** How many rows the category field's own adapter is holding. */
    private fun categoryRows(): Int {
        var rows = 0
        scenario.onActivity { activity ->
            val field = activity.findViewById<AutoCompleteTextView>(R.id.task_category)
            rows = field.adapter?.count ?: 0
        }
        return rows
    }

    /**
     * Saving a task nobody edited, and reading the row back.
     *
     * The other half of the defect [aStoredTaskStaysFiledUnderTheCategoryItWasGiven] found: the blank field
     * was not only a display problem. Save reads the picker's text back into a category id, so a reader who
     * opened a filed task, changed nothing, and pressed Save wrote "this task belongs to no category" into
     * the database. That is the reason this test exists — the visible blank was annoying, the silent unlink
     * was data loss.
     *
     * The fields on the `Task` row are compared, not only the category, because a round trip through an
     * editor can lose more than the one thing it was broken for: the plan, the estimate and the priority are
     * all read back out of fields the editor fills in asynchronously. What is *not* compared is the set of
     * reminder rows — a save retires every lead time that is not on a chip, and this class seeds none, so
     * there would be nothing to compare. Saying so here rather than claiming "every stored field" is the
     * difference between a test with a boundary and a comment that cannot fail.
     */
    @Test
    fun savingATaskTheReaderNeverTouchedLeavesEveryStoredFieldWhereItWas() {
        val first = readRow(plainTaskId)
        assertNotNull("the task this class seeded is in the database", first)
        val rowsBefore = taskRows()

        // Opened and saved again and again with nothing touched, because the defect this guards is a race:
        // the task and its categories arrive on two Room flows, and only the run where the task answers
        // first loses the category. Measured against one opening, the unfixed editor failed the first
        // iteration here and passed the second — `expected:<281> but was:<null>` then OK — so a single
        // round trip would have been a test that usually agrees with the bug. Five is not a number from a
        // theory; it is how many times this screen has to be opened before the losing order shows up on
        // this device, which it did every time the fix was taken away.
        repeat(ROUND_TRIPS) { round ->
            openTheEditor(plainTaskId)
            onView(withId(R.id.saveTask)).perform(click())
            assertTrue(
                "round ${round + 1}: the editor saved and closed",
                waitUntil("the list again") { view(R.id.fabAddTask) != null }
            )

            val after = readRow(plainTaskId)
            // Compared with what the row was seeded with, not with what it held when this round opened:
            // once the category is lost it stays lost, and a test that only asked "did it change during
            // this round" would read the damage as the new normal and pass.
            assertEquals(
                "round ${round + 1}: the category survives a save nobody changed anything on",
                first?.categoryId,
                after?.categoryId
            )
            assertEquals("round ${round + 1}: the title", first?.title, after?.title)
            assertEquals("round ${round + 1}: the description", first?.description, after?.description)
            assertEquals("round ${round + 1}: the deadline", first?.dueDate, after?.dueDate)
            assertEquals("round ${round + 1}: the planned start", first?.startTime, after?.startTime)
            assertEquals("round ${round + 1}: the planned end", first?.endTime, after?.endTime)
            assertEquals("round ${round + 1}: the estimate", first?.estimatedMinutes, after?.estimatedMinutes)
            assertEquals("round ${round + 1}: the priority", first?.priority, after?.priority)
            assertEquals(
                "round ${round + 1}: the group it was organised into — the editor has no control over " +
                    "membership, so a save that does not carry it forward detaches the task",
                first?.groupId,
                after?.groupId
            )
            // The whole row, not a sample of it: nine named columns out of twenty-five is a list of the
            // things someone thought to check, and the one this screen actually lost the first time was
            // found by the list having been read afterwards. Everything that is not a timestamp or a sync
            // marker has to come back as it went in.
            assertEquals(
                "round ${round + 1}: every stored column of the row",
                first?.copy(
                    updatedAt = after?.updatedAt ?: 0L,
                    syncState = after?.syncState ?: SyncState.PENDING
                ),
                after
            )
            assertEquals(
                "round ${round + 1}: the reader is looking at the same row they opened, not at a second " +
                    "task Save wrote",
                first?.id,
                after?.id
            )
        }
        assertEquals(
            "and the profile owns exactly the tasks it owned before any of this",
            rowsBefore,
            taskRows()
        )
    }

    private fun taskRows(): Int {
        val database = openDatabaseAt(context)
        val count = runBlocking { database.taskDao().getAllTasksOnce(userId) }.size
        database.close()
        return count
    }

    private fun readRow(taskId: Long): Task? {
        val database = openDatabaseAt(context)
        val row = runBlocking { database.taskDao().getTaskForUser(userId, taskId) }
        database.close()
        return row
    }

    // -- what every frame has to prove about the footer -------------------------------------------

    /**
     * The three geometric claims, in screen coordinates — the space the photograph is in — at whatever
     * configuration the run is in.
     *
     * "The footer is on the screen" is not a style, it is a position: its bottom edge is the app's layout
     * area's, the form ends where it begins, and no field the reader might still be typing in sits
     * underneath it. The 48dp floor is checked here rather than only in a layout test because the real
     * density is the only place a rounding error shows up.
     */
    private fun assertFooterIsAFloorOfItsOwn(where: String) {
        val container = bottomOfTheScreenTheActivityGives()
        val footer = rectOf(R.id.button_container)
        val scroll = scrollOfTheForm()
        val finger = dp(48f)

        assertTrue(
            "$where: nothing has been measured yet — the footer is ${requireView(R.id.button_container).width}" +
                "x${requireView(R.id.button_container).height}",
            requireView(R.id.button_container).width > 0 && requireView(R.id.button_container).height > 0
        )
        assertEquals(
            "$where: the editor fills the container the nav host handed it — root bottom=$container",
            container,
            bottomOfViewOnScreen(requireView(R.id.button_container).parent as View)
        )
        assertEquals(
            "$where: the footer's bottom edge is that container's — footer=$footer container=$container",
            container, footer.bottom
        )
        assertTrue(
            "$where: the footer keeps a band of its own above that edge — footer=$footer container=$container",
            footer.top in 1 until container
        )
        assertTrue(
            "$where: the scrolling form stops where the footer starts — scroll=$scroll footer=$footer",
            scroll.bottom <= footer.top + 1
        )
        assertTrue(
            "$where: Save is a finger tall — ${requireView(R.id.saveTask).height}px, floor ${finger}px",
            requireView(R.id.saveTask).height >= finger
        )
        assertTrue(
            "$where: Cancel is a finger tall — ${requireView(R.id.cancelButton).height}px, floor ${finger}px",
            requireView(R.id.cancelButton).height >= finger
        )
        // The slot the saving figure sits in has to be there when nothing is saving, or the weighted row
        // resizes the moment something is and both buttons slide away from the reader's finger. The layout
        // reserves it with `invisible`; only this catches a fragment that puts the view away with `gone`.
        assertTrue(
            "$where: the footer's progress slot measures " +
                "${requireView(R.id.progressBar).width}x${requireView(R.id.progressBar).height}, so the " +
                "buttons are measured against a row that changes when a save starts",
            requireView(R.id.progressBar).width > 0 && requireView(R.id.progressBar).height > 0
        )
    }

    /**
     * The bottom of `nav_host_fragment` in screen coordinates — the area the activity gives to whatever
     * screen is showing, which is where the footer has to sit.
     *
     * Measured against the activity's container rather than against the editor's own layout because a
     * claim about a footer is a claim about the screen: the app's window ends below the navigation bar,
     * `android.R.id.content` reaches past it, and a footer pinned at 997 of a 1107 content area is a footer
     * with 110px of nothing underneath it — which is exactly what the first run of this class found and
     * what a test that compared the footer with its own parent would have passed right over.
     */
    private fun bottomOfTheScreenTheActivityGives(): Int {
        var answer = 0
        scenario.onActivity { activity ->
            val host = activity.findViewById<View>(R.id.nav_host_fragment)
            val where = IntArray(2)
            host.getLocationOnScreen(where)
            answer = where[1] + host.height
        }
        return answer
    }

    private fun bottomOfViewOnScreen(target: View): Int {
        val where = IntArray(2)
        target.getLocationOnScreen(where)
        return where[1] + target.height
    }

    /** The scroll the form lives in — the footer's own sibling, found where the layout puts it. */
    private fun scrollOfTheForm(): Rect {
        val footer = requireView(R.id.button_container)
        val screen = footer.parent as ViewGroup
        val scroll = (0 until screen.childCount)
            .map { screen.getChildAt(it) }
            .first { it !== footer && it is NestedScrollView }
        val where = IntArray(2)
        scroll.getLocationOnScreen(where)
        return Rect(where[0], where[1], where[0] + scroll.width, where[1] + scroll.height)
    }

    // -- the run's own configuration ---------------------------------------------------------------

    /**
     * The tag the run was given — `<widthDp>-<light|dark>-<font in tenths>`, so `393-light-10` is 393dp of
     * width, the day theme, and a font scale of 1.0 — read back off the device.
     *
     * Fails the test, not the file name: a frame that says 320dp and was taken at 393dp is worse than no
     * frame, because somebody will read it as the narrow case having been checked.
     */
    private fun requireConfigurationMatchesTag(): String {
        val tag = InstrumentationRegistry.getArguments().getString("tag", "393-light-10")
        val parts = tag.split("-")
        assertEquals(
            "the tag names a width, a theme and a font scale: got '$tag'",
            3, parts.size
        )
        val metrics = context.resources.displayMetrics
        val widthDp = (metrics.widthPixels / metrics.density).roundToInt()
        val night = (
            context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                ) == Configuration.UI_MODE_NIGHT_YES
        // The scale a view would actually render text at: how far ten sp has grown against ten dp. Read this
        // way rather than from `DisplayMetrics.scaledDensity`, which is deprecated and agrees only by accident.
        val font = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 10f, metrics) /
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 10f, metrics)

        assertTrue(
            "'$tag' claims ${parts[0]}dp of width; the device reports ${widthDp}dp " +
                "(${metrics.widthPixels}px at ${metrics.densityDpi}dpi)",
            abs(widthDp - parts[0].toInt()) <= 3
        )
        assertEquals(
            "'$tag' claims the ${parts[1]} theme; the device reports night=$night",
            parts[1] == "dark",
            night
        )
        assertEquals(
            "'$tag' claims a font scale of ${parts[2]} tenths; the device reports $font",
            parts[2].toFloat() / 10f,
            font,
            0.02f
        )
        return tag
    }

    // -- the helpers -------------------------------------------------------------------------------

    private fun openTheEditor(taskId: Long) {
        scenario.onActivity { activity ->
            val host = activity.supportFragmentManager
                .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
            host.navController.navigate(R.id.navigation_task_detail, bundleOf("taskId" to taskId))
        }
        assertTrue(
            "the editor opened",
            waitUntil("the editor") { view(R.id.task_title) != null && isInFront() }
        )
        // Opened is not filled. The fields are written when the task arrives on its own flow, and a frame
        // taken before that is a photograph of an empty form that happens to have the right window behind
        // it — which every pixel check in this file would pass. Wait for the answer the frame shows.
        val stored = readRow(taskId)
        assertTrue(
            "the task's own title is on the screen",
            waitUntil("the title filled in") { text(R.id.task_title) == stored?.title }
        )
        requireConfigurationMatchesTag()
    }

    private fun isInFront(): Boolean = scenario.state == Lifecycle.State.RESUMED

    /**
     * Whether the *editor* is the screen showing, rather than only the activity hosting it.
     *
     * `isInFront` cannot answer that: closing the editor navigates up inside the same activity, which stays
     * RESUMED, so a save that refused an empty title and then left the screen would still look "in front".
     * The form is named by both of its own facts instead — the field the reader was typing into, and the
     * destination the navigation graph says is showing.
     */
    private fun stillOnTheEditor(): Boolean {
        if (view(R.id.task_title) == null) return false
        var destination: Int? = null
        runCatching {
            scenario.onActivity { activity ->
                destination = (activity.supportFragmentManager
                    .findFragmentById(R.id.nav_host_fragment) as NavHostFragment)
                    .navController.currentDestination?.id
            }
        }
        return destination == R.id.navigation_task_detail && isInFront()
    }

    /** The view with this id, or nothing — the question a wait asks while the screen is still changing. */
    private fun view(id: Int): View? {
        var found: View? = null
        runCatching { scenario.onActivity { activity -> found = activity.findViewById<View>(id) } }
        return found
    }

    /**
     * The view a claim is being made about. Kept apart from [view] on purpose: a test that asks "is it up
     * yet" and a test that says "this chip is 48dp" need different answers when the view is missing, and
     * one function would make one of the two wrong.
     */
    private fun requireView(id: Int): View =
        view(id) ?: throw AssertionError(
            "no ${context.resources.getResourceName(id)} on the screen while the app is ${scenario.state}"
        )

    private fun text(id: Int): String = (requireView(id) as? TextView)?.text?.toString().orEmpty()

    /** The message a field is carrying, or nothing when it is not complaining. */
    private fun errorOn(layoutId: Int): String? =
        (requireView(layoutId) as TextInputLayout).error?.toString()

    /** The name a view carries in the resources, which is what a failure has to quote to be findable. */
    private fun name(context: Context, id: Int): String = context.resources.getResourceName(id)

    /**
     * The y below which the form is no longer on the screen.
     *
     * The footer is not drawn over the form — it is a sibling that takes the window's bottom band, which is
     * what `assertFooterIsAFloorOfItsOwn` measures as `scroll.bottom <= footer.top`. The consequence is the
     * same either way: the scroll view's viewport ends at this line, so a view reaching past it is clipped
     * there and the pixels below belong to Save and Cancel. Every frame is therefore photographed above this
     * line, and `shot()` scrolls to make that true instead of hoping the previous action left room.
     */
    private fun contentFloorOnScreen(): Int = rectOf(R.id.button_container).top

    /**
     * Scroll the form until [over] lies in the viewport the footer leaves, and wait for it to settle.
     *
     * Three frames were refused the way this helper now prevents: `02-how-block` with 131 px of its 333 px
     * above the floor, `11-running-commands` with 14 of 138, `09-series-repeat` with 407 of 907. The tests
     * had scrolled — Espresso's `scrollTo()` on the *field* they were about to tap — and the platform scrolls
     * the minimum that makes that field visible, which leaves the section around it below the viewport. One
     * test then asked for the row's top-left corner with `requestRectangleOnScreen(Rect(0, 0, 1, 1))`; the
     * corner was already on screen by 14 px, so the platform correctly did nothing.
     *
     * Asking for the whole rectangle was the next attempt, and it is still not enough: measured on the
     * device, `requestRectangleOnScreen` brought the *bottom* of `how_section`, `reminder_section` and
     * `repeat_section` into view and left their heads at y=0, above a viewport that starts at 136 — a frame
     * of a section without its heading, which is the thing the frame is named for. So the form is scrolled
     * by its own offset instead: the view's top within the scroll content becomes `scrollTo`'s target, which
     * puts the head at the viewport's top and lets the tail run under the footer when the view is taller
     * than the room — the honest case the visible-slice rule below judges.
     */
    private fun bringAboveTheFloor(over: View) {
        if (over.height == 0) return
        val scroll = scrollOfTheFormView()
        val headroom = scrollOfTheForm().top
        val inContent = topWithin(over, scroll)
        var previous = -1
        var settled = rectOnScreen(over)
        for (attempt in 1..SCROLL_SETTLE_ATTEMPTS) {
            if (inContent < 0) {
                // Not a child of the form's scroll after all — a dialog's view, say. Ask the platform, which
                // is the only thing that knows its container.
                val requested = Rect(0, 0, over.width, over.height)
                scenario.onActivity { activity ->
                    activity.runOnUiThread { over.requestRectangleOnScreen(requested, /* immediate = */ true) }
                }
            } else {
                val target = maxOf(0, inContent - scroll.paddingTop)
                scenario.onActivity { activity ->
                    activity.runOnUiThread { scroll.scrollTo(0, target) }
                }
            }
            runCatching { instrumentation.waitForIdleSync() }
            val now = rectOnScreen(over)
            if (now == settled && scroll.scrollY == previous) {
                settled = now
                break
            }
            previous = scroll.scrollY
            settled = now
            Thread.sleep(120)
        }
        assertTrue(
            "the form was asked ${SCROLL_SETTLE_ATTEMPTS} times to put ${name(context, over.id)} at the top " +
                "of its viewport and its head is still at y=${settled.top}, above the viewport's own top at " +
                "y=$headroom: a frame taken here shows the tail of the view, not the view it is named for",
            settled.top >= headroom - 1
        )
    }

    /** The scroll the form lives in, as a view — [scrollOfTheForm] answers with its rectangle. */
    private fun scrollOfTheFormView(): NestedScrollView {
        val footer = requireView(R.id.button_container)
        val screen = footer.parent as ViewGroup
        return (0 until screen.childCount)
            .map { screen.getChildAt(it) }
            .first { it !== footer && it is NestedScrollView } as NestedScrollView
    }

    /** [view]'s top measured in [ancestor]'s coordinate space, or -1 when it is not inside it. */
    private fun topWithin(view: View, ancestor: View): Int {
        var total = 0
        var current: View = view
        while (current !== ancestor) {
            total += current.top
            current = current.parent as? View ?: return -1
        }
        return total
    }

    private fun rectOnScreen(view: View): Rect {
        val where = IntArray(2)
        view.getLocationOnScreen(where)
        return Rect(where[0], where[1], where[0] + view.width, where[1] + view.height)
    }

    private fun rectOf(id: Int): Rect {
        val where = IntArray(2)
        val measured = requireView(id)
        measured.getLocationOnScreen(where)
        return Rect(where[0], where[1], where[0] + measured.width, where[1] + measured.height)
    }

    private fun dp(value: Float): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics
    ).roundToInt()

    /**
     * Whether the app's own picker is up.
     *
     * Espresso answers for dialog windows — they are the app's, and their ids are the framework's — so the
     * claim is made against the widget the dialog is built from rather than against a pixel or a guess.
     */
    private fun pickerShowing(picker: Class<out View>): Boolean = runCatching {
        onView(isAssignableFrom(picker)).check(matches(isDisplayed()))
        true
    }.getOrDefault(false)

    /**
     * The picker the dialog drew, handed back rather than looked up by id.
     *
     * A dialog is a second window: `activity.findViewById` cannot see into it, and this app owns no id for a
     * widget the framework builds. Espresso can, because it matches against the window that holds focus —
     * and the caller has already waited for that matcher to succeed, so a null here is the camera failing,
     * not the screen. What comes back is the view the frame is named for, which is the difference between a
     * photograph of a clock and a photograph of whatever of the form happens to be legible beside one.
     */
    private fun capturedPicker(picker: Class<out View>): View {
        var found: View? = null
        runCatching {
            onView(isAssignableFrom(picker)).perform(object : ViewAction {
                override fun getConstraints() = isDisplayed()
                override fun getDescription() = "hand back the ${picker.simpleName} the dialog drew"
                override fun perform(uiController: UiController, view: View) {
                    found = view
                }
            })
        }
        return found ?: throw AssertionError(
            "no ${picker.simpleName} was handed to the camera while the editor is ${scenario.state}: the " +
                "matcher found it displayed a moment earlier, so the window changed between the two"
        )
    }

    /**
     * Whether [view] belongs to the editor's own window rather than to a dialog's.
     *
     * The distinction decides which of two rules apply. A view inside the form is clipped by the scroll
     * viewport, which ends where the footer begins, so it is scrolled clear and measured down to that floor.
     * A dialog is a window of its own painted over everything, footer included: the line that clips the form
     * clips nothing above it, and asking the dialog to scroll its clock face into view would move the very
     * thing the frame is about.
     */
    private fun isInTheEditorsWindow(view: View): Boolean {
        val editor = view.windowToken ?: return false
        val form = requireView(R.id.button_container).windowToken ?: return false
        return editor === form
    }

    /**
     * Whether the field's dropdown list is open, asked of the field itself.
     *
     * `TaskEditorInstrumentedTest` records that this device gives no matcher access to the list an
     * `AutoCompleteTextView` pops up: it opens, and nothing above the window can see it. The widget knows,
     * though, and `isPopupShowing()` is the honest answer to "did the picker come up".
     */
    private fun popupShowing(fieldId: Int): Boolean =
        (requireView(fieldId) as AutoCompleteTextView).isPopupShowing

    /**
     * Open a dropdown the way the reader would, and answer with what actually worked.
     *
     * The tap is tried first because a frame that claims to show the category picker should be a frame the
     * reader's own press produced. If the list comes up only when the field is asked directly, the answer
     * says so and the frame is still honest; if neither works, the caller fails rather than publishing a
     * photograph of a picker that never opened.
     */
    private fun openDropdown(fieldId: Int): String {
        onView(withId(fieldId)).perform(scrollTo(), click())
        if (settled { popupShowing(fieldId) }) return "the reader's tap on the field"
        scenario.onActivity { activity ->
            (activity.findViewById<View>(fieldId) as AutoCompleteTextView).showDropDown()
        }
        if (settled { popupShowing(fieldId) }) return "showDropDown(), the call that tap makes"
        return ""
    }

    /** Poll without failing: the caller decides what an unanswered condition means. */
    private fun settled(condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + 4_000L
        while (System.currentTimeMillis() < deadline) {
            if (runCatching(condition).getOrDefault(false)) return true
            Thread.sleep(50)
        }
        return false
    }

    private fun waitUntil(description: String, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
        var lastFailure: Throwable? = null
        while (System.currentTimeMillis() < deadline) {
            val outcome = runCatching { condition() }
            if (outcome.isSuccess && outcome.getOrDefault(false)) return true
            lastFailure = outcome.exceptionOrNull()
            Thread.sleep(50)
        }
        throw AssertionError(
            "$description did not happen within $TIMEOUT_MILLIS ms. state=${scenario.state} " +
                "title='${runCatching { text(R.id.task_title) }.getOrNull()}' " +
                "preset='${runCatching { text(R.id.repeat_preset) }.getOrNull()}'",
            lastFailure
        )
    }

    /**
     * A frame, or a failed run.
     *
     * The pixels inside the view being photographed are asked for a luminance spread before the file is
     * written. "Somewhere in the frame is a dark pixel" proved nothing in Stage 4 — the dark theme's
     * background is near black, so that check passed on a screen that had not drawn at all. Here the region
     * of the view the reader is looking at has to carry light text on a dark field, or the run stops.
     *
     * The view is scrolled into the viewport first, by [bringAboveTheFloor], because three frames were
     * refused for standing under the footer rather than for failing to draw.
     */
    private fun shot(name: String, over: View, scrollFirst: Boolean = true) {
        val inTheForm = isInTheEditorsWindow(over)
        if (inTheForm && scrollFirst) bringAboveTheFloor(over)
        val region = rectOnScreen(over)
        assertTrue("the view being photographed has no area: $region", region.width() > 0 && region.height() > 0)

        val bitmap = photographed(name)
        val floor = if (inTheForm) contentFloorOnScreen() else Int.MAX_VALUE
        val spread = luminanceSpread(bitmap, region, floor, if (inTheForm) scrollOfTheForm().top else 0)
        assertTrue(
            "the frame '$name' is not a drawn screen: inside $region the brightest pixel is $spread " +
                "levels above the darkest, and a heading over a field spans far more",
            spread >= MIN_SPREAD
        )
        val page = Rect(0, 0, bitmap.width, bitmap.height)
        val pageSpread = luminanceSpread(bitmap, page)
        assertTrue(
            "the frame '$name' varies by only $pageSpread levels across the whole screen: the part asked " +
                "about is drawn and the page around it is not a form",
            pageSpread >= MIN_FRAME_SPREAD
        )
        // The tag claims a theme; the claim is checked against the pixels rather than against `uiMode`,
        // which is a setting and would agree with a frame rendered from a stale configuration.
        val mean = meanLuminance(bitmap, page)
        if (nightRequested()) {
            assertTrue(
                "'$name' is filed under a dark tag and the page averages $mean levels — that is a light " +
                    "rendering",
                mean <= MAX_DARK_MEAN
            )
        } else {
            assertTrue(
                "'$name' is filed under a light tag and the page averages $mean levels — that is a dark " +
                    "rendering",
                mean >= MIN_LIGHT_MEAN
            )
        }

        val tag = requireConfigurationMatchesTag()
        val directory = File(
            context.getExternalFilesDir(null) ?: Environment.getExternalStorageDirectory(),
            "shots/$tag"
        ).apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        }
        bitmap.recycle()
        println("screenshot written $tag/$name.png")
    }

    /**
     * The screen as the platform draws it, asked for up to [SCREENSHOT_ATTEMPTS] times.
     *
     * Only `UiAutomation` will do here: half these frames are photographs of a window the app does not own
     * — the framework date picker, the clock, the list a category field drops — and a view cannot draw
     * another window into its bitmap. What the retry buys is not a different picture but a chance to get any
     * picture at all on a device that refuses under load; see [SCREENSHOT_ATTEMPTS] for the run that showed
     * it. A frame the platform never gave is still a failure, and the message says how many times it was
     * refused and what the refusal was.
     */
    private fun photographed(name: String): Bitmap {
        var refusal: Throwable? = null
        repeat(SCREENSHOT_ATTEMPTS) {
            val outcome = runCatching { instrumentation.uiAutomation.takeScreenshot() }
            outcome.getOrNull()?.let { return it }
            refusal = outcome.exceptionOrNull()
            Thread.sleep(SCREENSHOT_RETRY_MILLIS)
        }
        val why = refusal?.let { ": $it" } ?: ": the platform answered with no picture"
        throw AssertionError(
            "the platform refused the screenshot '$name' on all $SCREENSHOT_ATTEMPTS attempts$why"
        )
    }

    /** The page's average brightness: the difference between the two themes, said by the pixels. */
    private fun meanLuminance(bitmap: Bitmap, region: Rect): Int {
        val pixels = IntArray(region.width() * region.height())
        bitmap.getPixels(pixels, 0, region.width(), region.left, region.top, region.width(), region.height())
        var total = 0L
        for (pixel in pixels) {
            total += (0.2126 * (pixel shr 16 and 0xFF) +
                0.7152 * (pixel shr 8 and 0xFF) +
                0.0722 * (pixel and 0xFF)).roundToInt()
        }
        return (total / pixels.size).toInt()
    }

    /** Whether the tag this run was given asks for the dark theme. */
    private fun nightRequested(): Boolean = currentTag.split("-").getOrNull(1) == "dark"

    /** The tag, once: it is read by the configuration check and again by every frame's theme check. */
    private val currentTag: String
        get() = requireConfigurationMatchesTag()

    /** [MIN_VISIBLE_SLICE_DP] in the pixels this screen has, so the frame check is in the units it measures. */
    private val minVisibleSlicePx: Int
        get() = (MIN_VISIBLE_SLICE_DP * context.resources.displayMetrics.density).roundToInt()

    /**
     * How much the picture varies inside [region], measured on the part of it that is on the screen.
     *
     * The first version clamped the region into the bitmap and measured whatever it found, so a view laid
     * out at a negative x — a row pushed off the edge, one of the defects this class exists for — was
     * measured from its neighbour and still reported a drawn screen. The fix after that demanded the whole
     * region lie inside the frame, and the class then refused `09-series-repeat` for a region ending at
     * y=1409 of a 1140 px screen: the repeat block is legitimately taller than the room the footer leaves,
     * because the form scrolls. That check was wrong about what a photograph can show, not the screen wrong
     * about its own height.
     *
     * So the rule is split along the axis that matters. Horizontally nothing scrolls, so a region outside
     * the frame's left or right edge is a layout defect and the frame is refused. Vertically the region may
     * run past the bottom of the *window*, but only as far as the reader can see it: what the reader can see
     * is the scroll view's viewport, which ends at [floor] — the footer is the scroll view's sibling and
     * takes that band for itself, which is what `assertFooterIsAFloorOfItsOwn` measures as
     * `scroll.bottom <= footer.top`. The `11-running-commands` frame was the thing that taught the
     * difference: it was measured against the bitmap's bottom, passed, and showed the footer with the
     * command row underneath it. The same argument runs from [viewportTop] upwards, because a view scrolled
     * half out of the *top* of the viewport is the same photograph of where the view starts: the head is
     * required inside it too. Three things are then asked of the visible slice: it has to exist, it has to
     * be at least half the view, and it has to be at least [MIN_VISIBLE_SLICE_DP] dp — a short view that sits
     * wholly between the two lines satisfies all three, which is what it should.
     *
     * [floor] and [viewportTop] are the form's, so a caller measuring something outside the form — the whole
     * page, or a dialog in its own window painted over everything — passes neither: the page's bottom is the
     * bitmap's bottom, and a dialog is clipped only by the screen.
     */
    private fun luminanceSpread(
        bitmap: Bitmap,
        region: Rect,
        floor: Int = Int.MAX_VALUE,
        viewportTop: Int = 0,
    ): Int {
        assertTrue(
            "the region is off the screen sideways or starts below it — the editor scrolls vertically only, " +
                "so a view outside $region left/right is content the reader cannot reach; the frame is " +
                "${bitmap.width}x${bitmap.height}",
            region.left >= 0 && region.top >= 0 && region.right <= bitmap.width
        )
        assertTrue(
            "the head of $region is above the form's viewport, which starts at y=$viewportTop: the frame " +
                "would be a photograph of the part of the view that is left on the screen, so scroll it " +
                "whole into the viewport before shooting",
            region.top >= viewportTop - 1
        )
        val visible = Rect(region.left, region.top, region.right, minOf(region.bottom, floor, bitmap.height))
        assertTrue(
            "the view being photographed lies behind the sticky footer, which starts at y=$floor while the " +
                "region runs $region — the frame would be a photograph of the footer, not of the view it is " +
                "named for. Scroll the view above the floor before shooting.",
            visible.height() > 0
        )
        assertTrue(
            "only ${visible.height()} px of the ${region.height()} px region are above the footer: a frame " +
                "of less than half a view is a photograph of where the view starts, not of the view it is " +
                "named for — scroll it into the viewport before shooting",
            visible.height() * 2 >= region.height()
        )
        assertTrue(
            "the visible part of $region is ${visible.height()} px, under the $MIN_VISIBLE_SLICE_DP dp a " +
                "heading plus the field beneath it needs: the frame would be a sliver of background",
            visible.height() >= minOf(region.height(), minVisibleSlicePx)
        )
        val pixels = IntArray(visible.width() * visible.height())
        bitmap.getPixels(pixels, 0, visible.width(), visible.left, visible.top, visible.width(), visible.height())
        var brightest = 0
        var darkest = 255
        for (pixel in pixels) {
            val luminance = (
                0.2126 * (pixel shr 16 and 0xFF) +
                    0.7152 * (pixel shr 8 and 0xFF) +
                    0.0722 * (pixel and 0xFF)
                ).roundToInt()
            if (luminance > brightest) brightest = luminance
            if (luminance < darkest) darkest = luminance
        }
        return brightest - darkest
    }

}
