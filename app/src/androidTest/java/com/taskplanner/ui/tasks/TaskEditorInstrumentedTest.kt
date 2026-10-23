package com.taskplanner.ui.tasks

import android.content.Context
import android.os.Environment
import android.view.View
import android.widget.AutoCompleteTextView
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.lifecycle.Lifecycle
import androidx.navigation.fragment.NavHostFragment
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.textfield.TextInputLayout
import com.taskplanner.R
import com.taskplanner.data.local.AppDatabase
import com.taskplanner.data.local.AppMigrations
import com.taskplanner.data.model.RecurrenceRule
import com.taskplanner.data.model.RepeatUnit
import com.taskplanner.data.model.Task
import com.taskplanner.data.model.User
import com.taskplanner.ui.MainActivity
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The task editor as a person opens it — the first test in this project that runs a real Fragment.
 *
 * Everything else in `src/test` stops one step short of the screen: it inflates the layout, or it drives
 * the view model, and neither of those executes `onViewCreated`. That gap is how a crash survived five
 * stages. [TaskDetailFragment.setupRepeat] resolved the repeat unit's label for the preset the editor
 * opens with, and the presets that repeat at a fixed rate have no unit to name — an absence that used to
 * be spelled `0`, which is not a string resource. `getString(0)` threw, and the editor came down before
 * a single field was drawn: pressing `+` was enough to crash the app, and so was opening a saved task.
 *
 * Three things are checked here, all of them on the screen itself:
 *
 *  * the editor opens — from the `+` button, from a task with no series, and from a task that belongs to
 *    a series, which are three different ways into the same crashing line;
 *  * every preset the dropdown offers is rendered by the real Fragment, by opening a stored series that
 *    reads back as that preset — the same `RepeatPreset.of` → `applyPreset` path the picker takes;
 *  * the interval field appears exactly where the preset asks for a number and carries the words naming
 *    what the number counts, and stays away — naming nothing — where it does not.
 *
 * The wording is asserted alongside the survival on purpose: a "fix" that stopped the exception by never
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

    @Before
    fun signInAProfileWithOneTaskPerPreset() {
        val database = openDatabase()
        runBlocking {
            // The address is this test's own: users.email is unique, and a second test signing in under
            // the same one would take the first test's profile — and its tasks — away with it.
            val userId = database.userDao().insertUser(
                User(email = "editor-${System.nanoTime()}@example.test", name = "Анна Ковалёва")
            )
            database.userDao().setCurrentUser(database.userDao().getUserById(userId)!!)
            RepeatPreset.values().forEach { preset ->
                val seriesUuid = preset.unit?.let {
                    val rule = ruleFor(preset)
                    database.recurrenceDao().upsertRule(rule)
                    rule.uuid
                }
                taskOf[preset] = database.taskDao().insertTask(
                    Task(
                        uuid = UUID.randomUUID().toString(),
                        title = "Редактор: ${preset.name}",
                        description = "",
                        userId = userId,
                        seriesUuid = seriesUuid,
                        occurrenceAt = seriesUuid?.let { ANCHOR }
                    )
                )
            }
        }
        database.close()
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
     * The four fixed-rate ones are what crashed; the counted ones are here so the labels that do exist
     * are seen to exist on the screen rather than only in a JVM; `MONTHLY` and `WEEKDAYS` because each
     * hides the interval field while showing a different one.
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

    /**
     * The series a preset is recognised from, built the way the editor's own round trip describes it:
     * the interval the preset stands for, the weekday block if it asks for one, the day of the month if
     * it does. `RepeatPreset.of` is then what decides which hint the screen resolves.
     */
    private fun ruleFor(preset: RepeatPreset): RecurrenceRule = RecurrenceRule(
        uuid = "series-${preset.name.lowercase()}",
        userId = 1,
        unit = preset.unit ?: RepeatUnit.DAY,
        interval = preset.intervalOf(STORED_INTERVAL),
        weekDays = if (preset.showsWeekDays) "1,3" else "",
        dayOfMonth = if (preset.showsDayOfMonth) 15 else 0,
        anchorAt = ANCHOR,
        cursorAt = ANCHOR
    )

    private fun openDatabase() = Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME)
        .addMigrations(*AppMigrations.ALL)
        .build()

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

    private fun isInFront(): Boolean = scenario.state == Lifecycle.State.RESUMED

    private fun view(id: Int): View? {
        var found: View? = null
        runCatching { scenario.onActivity { activity -> found = activity.findViewById<View>(id) } }
        return found
    }

    private fun text(id: Int): String = (view(id) as? TextView)?.text?.toString().orEmpty()

    private fun waitUntil(description: String, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            if (runCatching(condition).getOrDefault(false)) return true
            Thread.sleep(50)
        }
        throw AssertionError(
            "$description did not happen within $TIMEOUT_MILLIS ms. state=${scenario.state} " +
                "preset='${text(R.id.repeat_preset)}' hint='${runCatching { intervalHint() }.getOrNull()}' " +
                "interval='${text(R.id.repeat_interval)}' " +
                "field=${runCatching { intervalLayout().visibility }.getOrNull()}"
        )
    }

    private fun shot(name: String) {
        runCatching { instrumentation.waitForIdleSync() }
        val bitmap = runCatching { instrumentation.uiAutomation.takeScreenshot() }.getOrNull()
        if (bitmap == null) {
            System.err.println("screenshot $name was refused by the platform")
            return
        }
        val directory = File(
            context.getExternalFilesDir(null) ?: Environment.getExternalStorageDirectory(), "shots"
        ).apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { stream ->
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
        }
        bitmap.recycle()
        println("screenshot written $name.png")
    }

    private companion object {
        const val TIMEOUT_MILLIS = 60_000L

        /** An interval above 1, so a counted preset and a fixed one cannot be confused with each other. */
        const val STORED_INTERVAL = 3

        /** A series needs an anchor and a cursor; the editor only reads them to describe the series. */
        const val ANCHOR = 1_730_000_000_000L
    }
}
