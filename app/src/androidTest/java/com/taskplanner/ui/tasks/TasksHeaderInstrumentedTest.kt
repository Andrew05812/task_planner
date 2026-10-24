package com.taskplanner.ui.tasks

import android.content.Context
import android.os.Environment
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.room.Room
import androidx.navigation.fragment.NavHostFragment
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.GeneralLocation
import androidx.test.espresso.action.GeneralSwipeAction
import androidx.test.espresso.action.Press
import androidx.test.espresso.action.Swipe
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isPlatformPopup
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.hasDescendant
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.taskplanner.R
import com.taskplanner.data.local.AppDatabase
import com.taskplanner.data.local.AppMigrations
import com.taskplanner.data.model.Task
import com.taskplanner.data.model.TaskStatus
import com.taskplanner.data.model.User
import com.taskplanner.ui.MainActivity
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.hamcrest.CoreMatchers.allOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Tasks header doing the things it was built to make reachable, on a device.
 *
 * Search, sort, new topic, archive and work history have all been implemented in this app since the stages
 * that added them, and none of them could be reached: they lived in an options menu, the theme is
 * NoActionBar, and no layout carries a Toolbar, so the menu was never drawn. The header is the chrome that
 * was missing, and this is the proof that the features behind it work — read from the effect on the screen,
 * not from the listener attached to a button.
 *
 * The seeded titles are Russian because the query is what a reader of this app would type, and the point of
 * two rows is that one matches and one does not: a search that filters nothing still leaves a displayed
 * `RecyclerView`, which is the false pass to avoid. So the first assertion is a row count read from the
 * adapter, and only then the two texts.
 *
 * The profile is seeded straight into the app's own storage, the way `TaskEditorInstrumentedTest` seeds its
 * series — this is a test of the screen, and the project still has no Hilt test harness. One class, one
 * invocation: a single connected run is a single app process, `SessionRepository` is a Hilt singleton, and
 * a class that seeds its own profile cannot trust the list it is shown if another class seeded one first in
 * the same invocation (plan §20j).
 */
@RunWith(AndroidJUnit4::class)
class TasksHeaderInstrumentedTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var scenario: ActivityScenario<MainActivity>
    private var seededUserId: Long = 0

    @Before
    fun signInAProfileWithFiveTasksOfItsOwn() {
        val database = openDatabase()
        runBlocking {
            seededUserId = database.userDao().insertUser(
                User(email = "header-${System.nanoTime()}@example.test", name = "Мария Левитина")
            )
            database.userDao().setCurrentUser(database.userDao().getUserById(seededUserId)!!)
            listOf(MATCHING, OTHER, YANDEX, AFISHA, NOTEBOOK).forEach { title ->
                database.taskDao().insertTask(
                    Task(
                        uuid = UUID.randomUUID().toString(), title = title, description = "",
                        userId = seededUserId, status = TaskStatus.TODO
                    )
                )
            }
        }
        database.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        assertTrue(
            "every seeded row is drawn before anything is typed",
            waitUntil("five task rows") { rowCount() == SEEDED }
        )
    }

    @After
    fun closeTheAppAndTakeTheSeedAway() {
        scenario.close()
        val database = openDatabase()
        runBlocking {
            database.userDao().getUserById(seededUserId)?.let { database.userDao().deleteUser(it) }
        }
        database.close()
    }

    /** The magnifier, the field, the query, and a list that answers. */
    @Test
    fun typingInTheHeaderFieldNarrowsTheListThroughTheViewModel() {
        onView(withId(R.id.headerSearchButton)).perform(click())
        assertTrue(
            "the press opened the field",
            waitUntil("the search field") { headerField()?.visibility == View.VISIBLE }
        )
        // `replaceText`, not `typeText`: the IME on this image cannot translate Cyrillic into key events
        // ("Failed to get key events for string …"), which is a property of the emulator's keyboard and not
        // of the field. What is being proven is the effect of the text on the list, and `setText` drives the
        // same watcher a typed character would.
        onView(withId(R.id.headerSearchField)).perform(replaceText(QUERY), closeSoftKeyboard())

        assertTrue("one row is left", waitUntil("the filtered list") { rowCount() == 1 })
        onView(withText(MATCHING)).check(matches(isDisplayed()))
        // `doesNotExist()`, not `not(isDisplayed())`: a filtered-out row is not in the list to be hidden,
        // and Espresso cannot evaluate a matcher against a view that is absent — the first draft of this
        // line failed with NoMatchingViewException *because* the filter had worked.
        onView(withText(OTHER)).check(doesNotExist())
        // The count line is the account's open tasks, not the rows the filter left standing — spec §4.1
        // names `activeTaskCount` for that slot. Asserted while filtered so the two meanings cannot be
        // quietly swapped later: on an unfiltered screen they are the same number.
        assertEquals(
            "and the header still says how much is open",
            context.getString(R.string.tasks_open_count_format, SEEDED),
            headerSubtitle()
        )
        shot("30-search-filtered")

        // Clearing is the same feature from the other side: an empty query is not a filter, and a list that
        // kept the filtered count would be holding a question the reader already took back.
        onView(withId(R.id.headerSearchField)).perform(replaceText(""))
        assertTrue("and both rows come back", waitUntil("the unfiltered list") { rowCount() == SEEDED })
    }

    /** The line under the title: how many tasks the list is holding open. */
    @Test
    fun theHeaderSaysHowManyTasksAreOpen() {
        assertTrue(
            "the count line is filled",
            waitUntil("the subtitle") { headerSubtitle().orEmpty().isNotBlank() }
        )
        assertEquals(
            "five seeded tasks, and the subtitle says so",
            context.getString(R.string.tasks_open_count_format, SEEDED),
            headerSubtitle()
        )
    }

    /**
     * The sort button changes the order the list is in.
     *
     * The three titles are seeded in an order that is neither alphabetical nor manual-neutral, so the
     * assertion cannot pass by accident: the reader presses sort, picks "by name", and the rows come out
     * named A to Z. The popup itself is visible to Espresso on this device — unlike the editor's
     * `AutoCompleteTextView` dropdown, which the audit records as unreadable — so the press lands on the
     * menu item a person would press rather than on a private method.
     */
    @Test
    fun theSortButtonReordersTheListThroughTheViewModel() {
        val before = titles()

        onView(withId(R.id.headerSortButton)).perform(click())
        shot("29-sort-open")
        onView(withText(context.getString(R.string.sort_by_name)))
            .inRoot(isPlatformPopup()).perform(click())

        // Written out rather than computed: the app sorts case-insensitively by title, and these five
        // differ in their first letter, so the expected order is a fact about the Russian alphabet that a
        // reader can check by eye — not the production expression copied into the test.
        val byName = listOf(AFISHA, NOTEBOOK, MATCHING, OTHER, YANDEX)
        assertEquals("the press asked for a different order and got it", byName, titles())
        assertTrue("and it is not the order the rows were seeded in", before != byName)
        assertEquals("the count line did not change with the order", SEEDED, rowCount())
        shot("34-sorted-by-name")
    }

    /** The kebab reaches the two screens that had no other entry point anywhere in the app. */
    @Test
    fun theKebabReachesTheArchiveAndTheWorkHistory() {
        onView(withId(R.id.headerMenuButton)).perform(click())
        shot("28-kebab-open")
        assertTrue(
            "the kebab's popup is on the screen — this device has not been able to see a popup's contents " +
                "before, so this is the first thing the test learns",
            popupItemIsShown(context.getString(R.string.action_archive))
        )
        onView(withText(context.getString(R.string.action_archive)))
            .inRoot(isPlatformPopup()).perform(click())
        assertTrue(
            "the archive screen came up",
            waitUntil("the archive") { view(R.id.archiveList) != null }
        )
        shot("31-archive-from-kebab")

        backToTasks()
        onView(withId(R.id.headerMenuButton)).perform(click())
        onView(withText(context.getString(R.string.action_work_history)))
            .inRoot(isPlatformPopup()).perform(click())
        assertTrue(
            "and so did the work history",
            waitUntil("the work history") { view(R.id.historyList) != null }
        )
        shot("32-work-history-from-kebab")
    }

    /**
     * Selection mode replaces the header; it does not stack on top of it.
     *
     * The press is a slow, zero-distance swipe because that is what a held finger is to this row: the card
     * is deliberately not `longClickable` (its own touch handler decides what a press means, and Stage 2b
     * made sure a held press is not also a click), so Espresso's `longClick()` would be refused by its own
     * constraint. The stream arms the row's 400 ms long press, the row enters selection, and the lift does
     * not navigate.
     */
    @Test
    fun selectingARowReplacesTheHeaderWithTheBar() {
        assertEquals("the header is on the screen to begin with", View.VISIBLE, headerView()?.visibility)

        onView(allOf(withId(R.id.taskCard), hasDescendant(withText(MATCHING)))).perform(HOLD)

        assertTrue(
            "the bar came up",
            waitUntil("selection mode") { view(R.id.selectionCount)?.visibility == View.VISIBLE }
        )
        assertEquals(
            "and the header is gone, not merely covered by a card drawn over it",
            View.GONE, headerView()?.visibility
        )
        shot("33-selection-bar")

        onView(withId(R.id.cancelSelectionButton)).perform(click())
        assertTrue(
            "leaving selection brings the header back",
            waitUntil("the header again") { headerView()?.visibility == View.VISIBLE }
        )
    }

    // -- the path ---------------------------------------------------------------------------------

    private fun backToTasks() {
        scenario.onActivity { activity ->
            val host = activity.supportFragmentManager
                .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
            while (host.navController.currentDestination?.id != R.id.navigation_tasks &&
                host.navController.popBackStack()
            ) {
                // Popping the stack the reader would pop: archive and work history are one press each from
                // Tasks, so this walks back over them rather than re-navigating.
            }
        }
        assertTrue("back on the task list", waitUntil("the list again") { rowCount() == SEEDED })
    }

    private fun popupItemIsShown(text: String): Boolean = runCatching {
        onView(withText(text)).inRoot(isPlatformPopup()).check(matches(isDisplayed()))
        true
    }.getOrDefault(false)

    // -- the helpers ------------------------------------------------------------------------------

    private fun tasksRoot(): View? {
        var root: View? = null
        runCatching {
            scenario.onActivity { activity ->
                val host = activity.supportFragmentManager
                    .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
                root = host.childFragmentManager.fragments
                    .filterIsInstance<TasksFragment>().singleOrNull()?.requireView()
            }
        }
        return root
    }

    private fun headerField(): View? = tasksRoot()?.findViewById(R.id.headerSearchField)

    private fun headerView(): View? = view(R.id.tasksHeader)

    /** The titles of the rows the list is holding, in the order it holds them. */
    private fun titles(): List<String> =
        (tasksRoot()?.findViewById<RecyclerView>(R.id.recyclerView)?.adapter as? TasksAdapter)
            ?.currentList?.filterIsInstance<TasksListItem.TaskRow>()?.map { it.task.title } ?: emptyList()

    private fun headerSubtitle(): String? =
        (tasksRoot()?.findViewById<View>(R.id.headerSubtitle) as? TextView)?.text?.toString()

    /**
     * Task rows only. `TasksViewModel.render` also emits a header per topic, so a raw `itemCount` would
     * count topic headings as tasks, and the equality would depend on whether the account on the device
     * happens to use topics.
     */
    private fun rowCount(): Int =
        (tasksRoot()?.findViewById<RecyclerView>(R.id.recyclerView)?.adapter as? TasksAdapter)
            ?.currentList?.count { it is TasksListItem.TaskRow } ?: -1

    private fun view(id: Int): View? {
        var found: View? = null
        runCatching { scenario.onActivity { activity -> found = activity.findViewById<View>(id) } }
        return found
    }

    private fun openDatabase() = Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME)
        .addMigrations(*AppMigrations.ALL).build()

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
                "rows=${runCatching { rowCount() }.getOrNull()} " +
                "subtitle='${runCatching { headerSubtitle() }.getOrNull()}'",
            lastFailure
        )
    }

    /** A screen of this app, photographed where adb can reach it afterwards. */
    private fun shot(name: String) {
        runCatching { instrumentation.waitForIdleSync() }
        val bitmap = runCatching { instrumentation.uiAutomation.takeScreenshot() }.getOrNull()
        if (bitmap == null) {
            throw AssertionError("the platform refused the screenshot '$name' this run cites as evidence")
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
        const val MATCHING = "Написать обзор литературы"
        const val OTHER = "Сформировать цель ВКР"
        const val QUERY = "обзор"
        const val YANDEX = "Яндекс"
        const val AFISHA = "Афиша"
        const val NOTEBOOK = "Блокнот"
        const val SEEDED = 5
        const val TIMEOUT_MILLIS = 60_000L

        /** A finger that comes down and stays: the row's own long press, not a `longClick()` Espresso
         *  would refuse on a card that is deliberately not long-clickable. */
        val HOLD = GeneralSwipeAction(
            Swipe.SLOW, GeneralLocation.CENTER, GeneralLocation.CENTER, Press.FINGER
        )
    }
}
