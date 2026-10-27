package com.taskplanner.ui.tasks

import android.content.Context
import android.graphics.Rect
import android.os.Environment
import android.view.View
import android.view.WindowManager
import android.widget.AutoCompleteTextView
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.lifecycle.Lifecycle
import androidx.navigation.fragment.NavHostFragment
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.textfield.TextInputLayout
import com.taskplanner.R
import com.taskplanner.data.local.AppDatabase
import com.taskplanner.data.model.RecurrenceRule
import com.taskplanner.data.model.RepeatUnit
import com.taskplanner.data.model.Task
import com.taskplanner.testing.SeededProfile
import com.taskplanner.ui.MainActivity
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The task editor as a person opens it — the first test in this project that runs a real Fragment.
 *
 * Everything else in `src/test` stops one step short of the screen: it inflates the layout, or it drives
 * the view model, and neither of those executes `onViewCreated`. That gap is how a crash survived five
 * stages. [TaskDetailFragment.setupRepeat] resolved the repeat unit's label for the preset the editor
 * opens with, and the six presets that repeat at a fixed rate — `NONE`, `DAILY`, `WEEKLY`, `BIWEEKLY`,
 * `MONTHLY`, `YEARLY` — have no unit to name, an absence that used to be spelled `0`, which is not a
 * string resource. `getString(0)` threw, and the editor came down before a single field was drawn:
 * pressing `+` was enough to crash the app, and so was opening a saved task.
 *
 * Five things are checked here, all of them on the screen itself:
 *
 *  * the editor opens — from the `+` button, from a task with no series, and from a task that belongs to
 *    a series, which are three different ways into the same crashing line;
 *  * every preset the dropdown offers is rendered by the real Fragment, by opening a stored series that
 *    reads back as that preset — the same `RepeatPreset.of` → `applyPreset` path the picker takes;
 *  * the interval field appears exactly where the preset asks for a number and carries the words naming
 *    what the number counts, and stays away — naming nothing — where it does not;
 *  * the editor refuses a task with no title on the title's own field, keeps the reader on the screen and
 *    writes no row — and saves the one they did type, which comes back out of the app's own storage with
 *    the same words in it. These two are Stage 6's, and before them the editor had never been asked to
 *    save anything in a test at all;
 *  * the footer with Save and Cancel in it is still on the window with the keyboard up. The window's own
 *    visible frame is asked for that, because "the button exists" is not the claim a sticky footer makes.
 *
 * The wording is asserted alongside the survival on purpose, and against the resource the preset was
 * given rather than against the same helper the screen used: a "fix" that stopped the exception by never
 * labelling anything would keep the app alive and fail here.
 *
 * The presets reach the screen through stored series rather than through the picker because the picker's
 * list is a popup window that neither Espresso's root matchers nor the accessibility API exposes on this
 * device — it opens, and nothing above the window can see it. What is checked instead is the list the
 * field is actually holding, and then the same rendering through the path a saved series takes. The
 * presses themselves are made by hand, photographed, and the photographs are kept beside this file.
 *
 * The profile is seeded straight into the app's own storage, the way the report test seeds its reader —
 * this is a test of the editor, and the project still has no Hilt test harness.
 */
@RunWith(AndroidJUnit4::class)
class TaskEditorInstrumentedTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var scenario: ActivityScenario<MainActivity>

    /** The task written for each preset, so the editor can be opened the way a saved task opens. */
    private val taskOf = mutableMapOf<RepeatPreset, Long>()

    /** The profile the app is signed into for this class — the one a saved row has to land under. */
    private val userId: Long get() = seededUserId

    @Before
    fun launchTheAppOnTheSeededProfile() {
        taskOf.putAll(seededTasks)
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun closeTheApp() {
        scenario.close()
    }

    /** The press that used to crash the app: a reader wanting a new task. */
    @Test
    fun theEditorOpensFromThePlusButtonAndNamesNoUnit() {
        assertTrue(
            "the list is on the screen",
            waitUntil("the task list") { view(R.id.fabAddTask) != null }
        )
        onView(withId(R.id.fabAddTask)).perform(click())
        assertTrue(
            "the editor opened",
            waitUntil("the editor") { view(R.id.repeat_preset) != null && isInFront() }
        )
        assertEquals(
            "and a task nobody has saved yet does not repeat",
            context.getString(R.string.repeat_none),
            text(R.id.repeat_preset)
        )
        assertTrue(
            "its interval field is away",
            waitUntil("the interval field away") { intervalLayout().visibility != View.VISIBLE }
        )
        assertEquals("and names no unit", "", intervalHint())
        shot("00-new-task")
    }

    /** The second way in, for a task that has no series at all. */
    @Test
    fun theEditorOpensForASavedTaskThatNeverRepeats() {
        openTheEditorFor(RepeatPreset.NONE)
        assertEquals(
            "a saved task with no series says it does not repeat",
            context.getString(R.string.repeat_none),
            text(R.id.repeat_preset)
        )
        assertEquals("and the hidden field names nothing", "", intervalHint())
        shot("01-saved-plain-task")
    }

    /**
     * Every preset, rendered by the real Fragment.
     *
     * The six fixed-rate ones are what crashed — `NONE`, `DAILY`, `WEEKLY`, `BIWEEKLY`, `MONTHLY` and
     * `YEARLY` — and the counted ones are here so the labels that do exist are seen to exist on the
     * screen rather than only in a JVM. `MONTHLY` and `WEEKDAYS` each hide or show a different field
     * beside the interval, so they are checked for that too.
     */
    @Test
    fun theEditorOpensForAStoredSeriesOfEveryKind() {
        RepeatPreset.values().forEach { preset ->
            openTheEditorFor(preset)
            val label = context.getString(preset.labelRes)
            assertTrue(
                "${preset.name}: the editor is on the screen and the app is still in front",
                waitUntil("${preset.name} rendered as '$label'") {
                    isInFront() && text(R.id.repeat_preset) == label
                }
            )
            assertEquals(
                "${preset.name}: the interval field is shown exactly where a unit is named",
                preset.editableInterval,
                intervalLayout().visibility == View.VISIBLE
            )
            assertEquals("${preset.name}: the hint under the field", preset.hint(), intervalHint())
            if (preset.editableInterval) {
                assertTrue(
                    "${preset.name}: a field the reader can type in is never left unnamed",
                    intervalHint().isNotBlank()
                )
                assertEquals(
                    "${preset.name}: the field carries the unit resource this preset was given",
                    context.getString(preset.unitLabelRes!!),
                    intervalHint()
                )
            } else {
                assertEquals("${preset.name}: a fixed rate names no unit", "", intervalHint())
            }
            if (preset.unit != null) {
                assertEquals(
                    "${preset.name}: the number the series runs on",
                    preset.intervalOf(STORED_INTERVAL).toString(),
                    text(R.id.repeat_interval)
                )
            } else {
                assertEquals(
                    "${preset.name}: a task with no series has no number on the field",
                    "",
                    text(R.id.repeat_interval)
                )
            }
            shot("10-preset-${preset.name.lowercase()}")
            leaveTheEditor()
        }
    }

    /** The list the picker holds, read off the field the editor built. */
    @Test
    fun theRepeatPickerOffersEveryPresetInTheWordsTheEditorUses() {
        openTheEditorFor(RepeatPreset.NONE)
        val rows = RepeatPreset.values().map { context.getString(it.labelRes) }
        val offered = adapterRows()
        assertEquals("the picker carries one row per preset", rows.size, offered.size)
        rows.forEach { assertTrue("'$it' is offered", it in offered) }
        assertTrue(
            "and none of them is an enum name",
            offered.none { row -> RepeatPreset.values().any { row == it.name } }
        )
    }

    /**
     * A task with nothing in its title is not saved, and the editor says so on the field.
     *
     * Three things are checked, because a form can satisfy any one of them and still be wrong: the error
     * text is on the title's own layout (a Snackbar would say it somewhere else, and a Toast would say it
     * and vanish); the editor has not navigated away, so the reader is still where they were; and the
     * database still holds exactly the rows it held before, which is the only proof that nothing was
     * written. A test that asserted only the first two would pass on a screen that showed the message
     * *and* saved an empty task.
     */
    @Test
    fun aTaskWithNoTitleIsNotSavedAndSaysWhichFieldIsWrong() {
        openTheEditorFromThePlusButton()
        val rowsBefore = taskCount()

        onView(withId(R.id.saveTask)).perform(click())

        assertTrue(
            "and the editor did not close: the reader is still where they were, with the field in front " +
                "of them",
            waitUntil("the editor still open") { view(R.id.repeat_preset) != null && isInFront() }
        )
        assertEquals(
            "the title's own field carries the reason, in the words the app has always used for it",
            context.getString(R.string.error_title_required),
            titleError()
        )
        assertEquals(
            "and nothing was written: an editor that complains and saves anyway is worse than one that " +
                "says nothing",
            rowsBefore,
            taskCount()
        )
        shot("30-validation-error")
    }

    /**
     * The whole point of the footer: the reader never has to hunt for Save, and the keyboard does not take
     * it away.
     *
     * The claim is measured twice because `isDisplayed()` alone would pass on a form that happened to fit.
     * First the window is asked whether the keyboard really took a part of it — if the IME never came up,
     * the rest of the test would be checking an ordinary screen and saying it was a hard one. Then Save's
     * bottom edge, in the same screen coordinates the visible frame is stated in, is asked to sit inside the
     * part of the display the window still has: under `adjustResize` a footer that had stayed put would now
     * be outside it, clipped by the window itself rather than by the keyboard, and a button clipped by its
     * window is exactly the defect the sticky footer exists to end.
     */
    @Test
    fun theFooterStaysOnTheWindowWithTheKeyboardUp() {
        openTheEditorFromThePlusButton()
        assertEquals(
            "the window is in resize mode, so what follows is a claim about a mode the app declared in " +
                "AndroidManifest.xml and not one the platform happened to pick for this window",
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
            softInputMode() and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST
        )
        val frameWithoutKeyboard = visibleFrame()

        onView(withId(R.id.task_title)).perform(click(), replaceText("Футер поверх клавиатуры"))

        assertTrue(
            "the keyboard is really up: the window lost ${frameWithoutKeyboard.bottom} minus " +
                "${visibleFrame().bottom} of its height before this claim means anything. When this fails " +
                "while a keyboard is on the screen, look at its shape first: this device was found with " +
                "Gboard in floating (widget) mode, whose window does not resize the app at all, and the " +
                "claim below is about a docked keyboard — dock it by dragging the floating bar to the " +
                "bottom edge and run again",
            waitUntil("the window to shrink") { visibleFrame().bottom < frameWithoutKeyboard.bottom }
        )
        onView(withId(R.id.saveTask)).check(matches(isDisplayed()))
        onView(withId(R.id.cancelButton)).check(matches(isDisplayed()))
        assertTrue(
            "Save ends above the top of the keyboard, not underneath it: bottom=${saveBottomOnScreen()} " +
                "visible to=${visibleFrame().bottom}",
            saveBottomOnScreen() <= visibleFrame().bottom
        )
        assertTrue(
            "and the title field the reader is typing into is still on the window as well",
            bottomOnScreen(R.id.task_title) <= visibleFrame().bottom
        )
        shot("31-footer-with-keyboard")
        // The keyboard this test raised belongs to the field it typed into. Left up, it is what the next
        // test's first tap closes rather than what that tap aims at.
        runCatching { Espresso.closeSoftKeyboard() }
    }

    /** Type a title, press Save on the footer, and read the row back out of the app's own storage. */
    @Test
    fun aTaskTheReaderTypesIsSavedAndComesBackWithWhatWasTyped() {
        openTheEditorFromThePlusButton()
        val rowsBefore = taskCount()
        val title = "Сохранить через футер ${System.nanoTime() % 10000}"
        val description = "Задача, созданная инструментальным тестом редактора"

        onView(withId(R.id.task_title)).perform(replaceText(title), closeSoftKeyboard())
        onView(withId(R.id.task_description)).perform(replaceText(description), closeSoftKeyboard())
        onView(withId(R.id.saveTask)).perform(click())

        assertTrue(
            "the editor closed itself after saving — the reader is back on the list",
            waitUntil("the list again") { view(R.id.fabAddTask) != null }
        )
        val saved = readSavedTask(title)
        assertNotNull("the row the reader saved is in the database under the profile they signed in with", saved)
        assertEquals("and it came back with the description that was typed into it", description, saved?.description)
        assertEquals(
            "with no deadline, because none was entered: the editor must not invent an instant",
            null,
            saved?.dueDate
        )
        assertEquals(
            "and one row more than the seed left behind, which is what proves this was a save and not a " +
                "reload of the same task",
            rowsBefore + 1,
            taskCount()
        )
    }

    // -- the path ---------------------------------------------------------------------------------

    /** The app's own NavHost, with the argument a reminder's deep link uses. */
    private fun openTheEditorFor(preset: RepeatPreset) {
        val taskId = taskOf.getValue(preset)
        scenario.onActivity { activity ->
            val host = activity.supportFragmentManager
                .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
            host.navController.navigate(R.id.navigation_task_detail, bundleOf("taskId" to taskId))
        }
        assertTrue(
            "the editor opened for ${preset.name}",
            waitUntil("the editor") { view(R.id.repeat_preset) != null && isInFront() }
        )
    }

    private fun leaveTheEditor() {
        scenario.onActivity { activity ->
            val host = activity.supportFragmentManager
                .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
            host.navController.popBackStack()
        }
        assertTrue(
            "back where the reader came from",
            waitUntil("the list again") { view(R.id.fabAddTask) != null }
        )
    }

    // -- the helpers ------------------------------------------------------------------------------

    private fun openDatabase() = SeededProfile.open(context)

    private fun intervalLayout(): TextInputLayout =
        view(R.id.repeat_interval_layout) as TextInputLayout

    private fun intervalHint(): String = intervalLayout().hint?.toString().orEmpty()

    /** What the preset says under the interval field, in the language the device speaks. */
    private fun RepeatPreset.hint(): String {
        var answer = ""
        scenario.onActivity { activity -> answer = intervalHint(activity::getString).orEmpty() }
        return answer
    }

    private fun adapterRows(): List<String> {
        val rows = mutableListOf<String>()
        scenario.onActivity { activity ->
            val field = activity.findViewById<AutoCompleteTextView>(R.id.repeat_preset)
            val adapter = field.adapter
            for (index in 0 until adapter.count) rows += adapter.getItem(index).toString()
        }
        return rows
    }

    /** The way a reader starts a task: the `+` on the list. */
    private fun openTheEditorFromThePlusButton() {
        assertTrue(
            "the list is on the screen",
            waitUntil("the task list") { view(R.id.fabAddTask) != null }
        )
        onView(withId(R.id.fabAddTask)).perform(click())
        assertTrue(
            "the editor opened",
            waitUntil("the editor") { view(R.id.task_title) != null && isInFront() }
        )
    }

    /** How many rows this profile owns right now — the before/after a save is measured against. */
    private fun taskCount(): Int {
        val database = openDatabase()
        val count = runBlocking { database.taskDao().getAllTasksOnce(userId) }.size
        database.close()
        return count
    }

    private fun readSavedTask(title: String): Task? {
        val database = openDatabase()
        val found = runBlocking { database.taskDao().getAllTasksOnce(userId) }
            .singleOrNull { it.title == title }
        database.close()
        return found
    }

    /** The error the title's own field is showing, read off the layout rather than off a toast. */
    private fun titleError(): String? {
        var error: String? = null
        scenario.onActivity { activity ->
            error = activity.findViewById<TextInputLayout>(R.id.task_title_layout).error?.toString()
        }
        return error
    }

    /**
     * What the window can actually show, in screen coordinates — the part left after the keyboard.
     *
     * `getWindowVisibleDisplayFrame` is the system's own answer to "how much of the window is not taken by
     * the status bar and the IME", which is the quantity a sticky footer has to live inside. Reading it off
     * the decor view rather than guessing a keyboard height is what makes the claim survive a different
     * IME on a different device.
     */
    /** The window's soft-input mode, as the screen left it. */
    private fun softInputMode(): Int {
        var mode = -1
        scenario.onActivity { activity -> mode = activity.window.attributes.softInputMode }
        return mode
    }

    private fun visibleFrame(): Rect {
        val frame = Rect()
        scenario.onActivity { activity -> activity.window.decorView.getWindowVisibleDisplayFrame(frame) }
        return frame
    }

    /**
     * How far down the screen the bottom of this view sits — the coordinate the keyboard is measured in.
     *
     * Two things this had wrong before. `getLocationInWindow` and `getWindowVisibleDisplayFrame` are not the
     * same space: the visible frame is absolute to the display, while a window offset by a status bar or a
     * shrunk IME starts below its zero, so the two only line up for a full-screen window at (0,0) — and this
     * is a claim about a window the IME has just resized. And a missing view used to measure as its own top
     * (`view(id)?.height ?: 0`), so a screen with no Save on it passed "Save is above the keyboard". Both
     * are fixed by asking the view for its screen position and requiring it to exist and have a height.
     */
    private fun bottomOnScreen(id: Int): Int {
        val where = IntArray(2)
        var height = -1
        scenario.onActivity { activity ->
            val measured = activity.findViewById<View>(id)
                ?: throw AssertionError(
                    "there is no ${activity.resources.getResourceName(id)} on the screen to measure"
                )
            measured.getLocationOnScreen(where)
            height = measured.height
        }
        assertTrue(
            "${context.resources.getResourceName(id)} has never been laid out, so where it sits is not a " +
                "fact about this screen",
            height > 0
        )
        return where[1] + height
    }

    private fun saveBottomOnScreen(): Int = bottomOnScreen(R.id.saveTask)

    private fun isInFront(): Boolean = scenario.state == Lifecycle.State.RESUMED

    private fun view(id: Int): View? {
        var found: View? = null
        runCatching { scenario.onActivity { activity -> found = activity.findViewById<View>(id) } }
        return found
    }

    private fun text(id: Int): String = (view(id) as? TextView)?.text?.toString().orEmpty()

    /**
     * Waiting for the screen, carrying away what it threw if it never answered.
     *
     * The condition is polled through `runCatching` because the thing being waited for is a view that may
     * not exist yet — but the failure mode this file exists for is a crash, so the last exception is kept
     * and reported as the cause. Without that, a screen that dies on the first frame reads as a timeout
     * and sends the next reader looking for a slow device.
     */
    private fun waitUntil(description: String, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
        var lastFailure: Throwable? = null
        while (System.currentTimeMillis() < deadline) {
            val outcome = runCatching(condition)
            // The answer, not the call: every condition below is a question ("is the view there", "did the
            // window shrink") and `false` is a successful call. Returning on `isSuccess` made every gate in
            // this class open on the first poll, which is how "the keyboard is really up" could be true with
            // no keyboard at all — the defect `TaskEditorFramesInstrumentedTest` already gets right.
            if (outcome.getOrDefault(false)) return true
            lastFailure = outcome.exceptionOrNull()
            Thread.sleep(50)
        }
        throw AssertionError(
            "$description did not happen within $TIMEOUT_MILLIS ms. state=${scenario.state} " +
                "preset='${text(R.id.repeat_preset)}' hint='${runCatching { intervalHint() }.getOrNull()}' " +
                "interval='${text(R.id.repeat_interval)}' " +
                "field=${runCatching { intervalLayout().visibility }.getOrNull()}",
            lastFailure
        )
    }

    /**
     * A screen of this app, photographed where adb can reach it afterwards.
     *
     * A refused screenshot fails the run rather than passing quietly: the photographs are cited as
     * evidence in the README, and a green suite that wrote none would make that claim untrue.
     *
     * Asked for up to [SCREENSHOT_ATTEMPTS] times, because this emulator refuses under load: see
     * [TaskEditorFramesInstrumentedTest.photographed], which takes the same picture of the same window and
     * keeps the same failure if the platform never answers.
     */
    private fun shot(name: String) {
        runCatching { instrumentation.waitForIdleSync() }
        var refusal: Throwable? = null
        repeat(SCREENSHOT_ATTEMPTS) {
            val outcome = runCatching { instrumentation.uiAutomation.takeScreenshot() }
            val bitmap = outcome.getOrNull()
            if (bitmap != null) {
                val directory = File(
                    context.getExternalFilesDir(null) ?: Environment.getExternalStorageDirectory(), "shots"
                ).apply { mkdirs() }
                File(directory, "$name.png").outputStream().use { stream ->
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
                }
                bitmap.recycle()
                println("screenshot written $name.png")
                return
            }
            refusal = outcome.exceptionOrNull()
            Thread.sleep(SCREENSHOT_RETRY_MILLIS)
        }
        throw AssertionError(
            "the platform refused the screenshot '$name' this run cites as evidence, on all " +
                "$SCREENSHOT_ATTEMPTS attempts${refusal?.let { ": $it" } ?: ""}"
        )
    }

    /**
     * The one profile this class signs the app into, and the tasks it owns.
     *
     * Seeded once for the class rather than once per test, and that is not a speed decision. The app holds
     * its session in a Hilt singleton: the process reads `is_current` the first time a view model needs it
     * and keeps that answer for the rest of its life. A per-test seed therefore makes every test after the
     * first one write under the *previous* test's profile — invisible while the editor only reads a task by
     * id, and fatal the moment a test asks whether the row it saved landed under the reader who is signed
     * in. `ProfileScreenInstrumentedTest` learned this the same way in Stage 5, and
     * [aTaskTheReaderTypesIsSavedAndComesBackWithWhatWasTyped] is the test that taught it here.
     */
    private companion object {
        const val TIMEOUT_MILLIS = 60_000L

        /**
         * How many times the screen is asked for a picture before the run calls it refused — the same
         * allowance, and for the same observed reason, as [TaskEditorFramesInstrumentedTest]'s frames.
         */
        const val SCREENSHOT_ATTEMPTS = 5
        const val SCREENSHOT_RETRY_MILLIS = 1_000L

        /** An interval above 1, so a counted preset and a fixed one cannot be confused with each other. */
        const val STORED_INTERVAL = 3

        /** A series needs an anchor and a cursor; the editor only reads them to describe the series. */
        const val ANCHOR = 1_730_000_000_000L

        var seededUserId: Long = 0
        val seededTasks = mutableMapOf<RepeatPreset, Long>()

        /**
         * Whoever the device was signed into before this class arrived, and whether they were the current one.
         * `setCurrentUser` clears the flag on every other row, so signing in — which an editor test has to do
         * — signs the previous reader out, and deleting this class's profile afterwards would leave the app
         * with no current user at all. That is somebody else's session on a shared device, so it is put back;
         * the capture and the restore live in [SeededProfile] because three classes on this device have now
         * needed the same rule.
         */
        lateinit var signedIn: SeededProfile.SignedIn

        /** The same database the app has open, reached the way the app reaches it: by name, with migrations. */
        fun database(): AppDatabase = SeededProfile.open(
            InstrumentationRegistry.getInstrumentation().targetContext
        )

        /**
         * The series a preset is recognised from, built the way the editor's own round trip describes it:
         * the interval the preset stands for, the weekday block if it asks for one, the day of the month if
         * it does. `RepeatPreset.of` is then what decides which hint the screen resolves.
         */
        fun ruleFor(preset: RepeatPreset, userId: Long): RecurrenceRule = RecurrenceRule(
            uuid = "series-${preset.name.lowercase()}-$userId",
            userId = userId,
            unit = preset.unit ?: RepeatUnit.DAY,
            interval = preset.intervalOf(STORED_INTERVAL),
            weekDays = if (preset.showsWeekDays) "1,3" else "",
            dayOfMonth = if (preset.showsDayOfMonth) 15 else 0,
            anchorAt = ANCHOR,
            cursorAt = ANCHOR
        )

        @JvmStatic
        @BeforeClass
        fun signInOneProfileForTheWholeClass() {
            val database = database()
            try {
                // Sweep before seeding, never after: a run this device has been killed half way through leaves
                // its fixture signed in, and a class that seeds on top of that writes under a stranger.
                val swept = SeededProfile.sweepLeftovers(database)
                if (swept > 0) println("swept $swept leftover fixture profile(s) before seeding")
                signedIn = SeededProfile.signIn(
                    database,
                    // The address is this class's own: `users.email` is unique, and a second profile signing
                    // in under the same one would take the first one's tasks away with it.
                    email = "editor-${System.nanoTime()}@example.test",
                    name = "Анна Ковалёва"
                )
                seededUserId = signedIn.seededUserId
                runBlocking {
                    RepeatPreset.values().forEach { preset ->
                        // The series belongs to the profile seeded above, not to whoever happens to own rowid
                        // 1: recurrences cascade from users, and an open rule on another profile is a series
                        // that profile's start-up will catch up and materialise.
                        val seriesUuid = preset.unit?.let {
                            val rule = ruleFor(preset, seededUserId)
                            database.recurrenceDao().upsertRule(rule)
                            rule.uuid
                        }
                        seededTasks[preset] = database.taskDao().insertTask(
                            Task(
                                uuid = UUID.randomUUID().toString(),
                                title = "Редактор: ${preset.name}",
                                description = "",
                                userId = seededUserId,
                                seriesUuid = seriesUuid,
                                occurrenceAt = seriesUuid?.let { ANCHOR }
                            )
                        )
                    }
                }
            } finally {
                database.close()
            }
        }

        @JvmStatic
        @AfterClass
        fun takeTheProfileAndEverythingUnderItAway() {
            // Seeding died before it signed in: there is nothing of ours to take away, and touching the
            // session now would only displace somebody who was never asked to move.
            if (!::signedIn.isInitialized) return
            val database = database()
            try {
                // Deleting the profile alone is not enough — `tasks`, `categories` and `reminders` carry no
                // foreign key to `users`, so their rows outlive it. `SeededProfileCleanupTest` asks SQLite
                // which tables that is on every run instead of trusting this sentence.
                SeededProfile.takeAway(database, signedIn)
            } finally {
                database.close()
            }
        }
    }
}
