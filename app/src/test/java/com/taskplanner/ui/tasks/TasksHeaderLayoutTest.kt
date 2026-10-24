package com.taskplanner.ui.tasks

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.PopupMenu
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import androidx.test.core.app.ApplicationProvider
import com.taskplanner.R
import com.taskplanner.data.model.TaskStatus
import com.taskplanner.databinding.FragmentTasksBinding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The Tasks header, inflated the way the screen inflates it.
 *
 * This file exists because four features of this app have been unreachable since the stage that added them:
 * `menu_tasks.xml` declared search, sort, new group, archive and work history, `TasksFragment.setupMenu`
 * wired all five, and the app runs a NoActionBar theme with no Toolbar in any layout — so the menu was
 * never drawn, and `setSearchQuery`, `setSortOrder`, `actionTasksToArchive` and `actionTasksToWorkHistory`
 * each had exactly one caller behind chrome that did not exist. A header that has the controls but not the
 * wiring would still look finished in a photograph, so what is asserted here is that each control is
 * present, names itself, is big enough to press, is a real field where text goes, and is wired to
 * something — and that the two popups offer exactly the actions the view model answers.
 */
@RunWith(RobolectricTestRunner::class)
class TasksHeaderLayoutTest {

    private val context: Context by lazy {
        ContextThemeWrapper(
            ApplicationProvider.getApplicationContext<Context>(), R.style.Theme_TaskPlanner
        )
    }

    private fun inflated(): FragmentTasksBinding =
        FragmentTasksBinding.inflate(LayoutInflater.from(context)).also { binding ->
            // Measured and laid out, so the touch-target assertions read real sizes rather than the zeros a
            // view holds before a parent has asked it how big it is.
            binding.root.measure(
                View.MeasureSpec.makeMeasureSpec(360.dp(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(640.dp(), View.MeasureSpec.EXACTLY)
            )
            binding.root.layout(0, 0, 360.dp(), 640.dp())
        }

    private fun Int.dp() = (this * context.resources.displayMetrics.density).toInt()

    @Test fun `the header carries the title, the count line, the field and the three actions`() {
        val binding = inflated()
        val header = binding.tasksHeader.root
        listOf(
            R.id.headerTitle, R.id.headerSubtitle, R.id.headerSearchField,
            R.id.headerSearchButton, R.id.headerSortButton, R.id.headerMenuButton
        ).forEach { id ->
            assertNotNull(
                "${context.resources.getResourceEntryName(id)} is on the header",
                header.findViewById<View>(id)
            )
        }
        assertTrue(
            "and the header is inside the Tasks screen, not merely beside it",
            isDescendantOf(header, binding.root)
        )
    }

    private fun isDescendantOf(node: View, root: View): Boolean {
        var current: View? = node.parent as? View
        while (current != null) {
            if (current === root) return true
            current = current.parent as? View
        }
        return false
    }

    @Test fun `every icon-only control says what it is`() {
        val header = inflated().tasksHeader.root
        actions().forEach { id ->
            val button = header.findViewById<ImageButton>(id)
            assertTrue(
                "${name(id)} shows a glyph and nothing else, so it has to name itself",
                !button.contentDescription.isNullOrBlank()
            )
        }
    }

    @Test fun `the actions are big enough to press`() {
        val header = inflated().tasksHeader.root
        val minimum = context.resources.getDimensionPixelSize(R.dimen.touch_target_min)
        actions().forEach { id ->
            val button = header.findViewById<View>(id)
            assertTrue(
                "${name(id)} is ${button.width}x${button.height} and a touch target is $minimum px",
                button.width >= minimum && button.height >= minimum
            )
        }
    }

    @Test fun `the search field is a field, and it is away until asked for`() {
        val binding = inflated()
        val field = binding.tasksHeader.headerSearchField
        assertTrue("a box that cannot hold text is decoration, not search", field is EditText)
        assertEquals("the field starts empty", "", field.text.toString())
        assertEquals(
            "and the whole row is away: the magnifier opens it",
            View.GONE, binding.tasksHeader.headerSearchLayout.visibility
        )
    }

    @Test fun `the sort menu offers the four orders the view model knows`() {        assertEquals(
            "manual, date, priority, name",
            listOf(
                context.getString(R.string.sort_manual),
                context.getString(R.string.sort_by_date),
                context.getString(R.string.sort_by_priority),
                context.getString(R.string.sort_by_name)
            ),
            offeredIn(R.menu.menu_tasks_sort)
        )
    }

    @Test fun `the kebab offers the topic, the archive and the work history`() {
        assertEquals(
            "the three actions that were stranded in the menu that was never drawn",
            listOf(
                context.getString(R.string.new_group),
                context.getString(R.string.action_archive),
                context.getString(R.string.action_work_history)
            ),
            offeredIn(R.menu.menu_tasks_overflow)
        )
    }

    /**
     * The two filter rows, and why they scroll differently.
     *
     * The category row's length is whatever the reader's categories add up to, so it scrolls. The three
     * status chips do not, because `TasksScreenInstrumentedTest` asserts all three are displayed and a chip
     * scrolled off the end of a `HorizontalScrollView` is laid out but not displayed — putting them in the
     * same scroller would either fail that test or invite someone to weaken it, and weakening it is not on
     * the table.
     */
    @Test fun `the categories scroll, the statuses do not, and there is no fourth chip`() {
        val root = inflated().root
        val rail = root.findViewById<HorizontalScrollView>(R.id.categoryScroll)
        val categories = root.findViewById<ChipGroup>(R.id.chipGroupCategory)
        val statuses = root.findViewById<ChipGroup>(R.id.chipGroupStatus)

        assertSame(
            "the category group sits inside the scroller's band",
            rail, categories.parent.parent
        )
        var ancestor: View? = statuses.parent as? View
        var insideScroller = false
        while (ancestor != null) {
            if (ancestor === rail) insideScroller = true
            ancestor = ancestor.parent as? View
        }
        assertFalse(
            "the status chips are not inside the scroller, so all three stay on screen",
            insideScroller
        )
        assertEquals(
            "the app has four statuses and the active list can hold three of them",
            listOf(R.id.chipTodo, R.id.chipInProgress, R.id.chipPaused),
            (0 until statuses.childCount).map { statuses.getChildAt(it).id }
        )
    }

    @Test fun `the filters chip heads the category band and names itself`() {
        val root = inflated().root
        val chip = root.findViewById<Chip>(R.id.filtersChip)
        assertNotNull("the chip that clears the filters exists", chip)
        assertSame(
            "and it is the first thing in the scrolling band, before the categories",
            root.findViewById<View>(R.id.categoryBand), chip.parent
        )
        assertEquals("it is away until a filter is on", View.GONE, chip.visibility)
    }

    @Test fun `an active filter counts one, three, or nothing, and never the empty query`() {
        assertEquals(1, TasksState(filterStatus = null, searchQuery = "x").activeFilterCount)
        assertEquals(1, TasksState(filterCategoryId = 4L).activeFilterCount)
        assertEquals(0, TasksState(searchQuery = "   ").activeFilterCount)
        assertEquals(
            3,
            TasksState(
                filterCategoryId = 4L, filterStatus = TaskStatus.TODO, searchQuery = "x"
            ).activeFilterCount
        )
    }

    @Test fun `the count and the boolean the header already reads never disagree`() {
        listOf(
            TasksState(), TasksState(searchQuery = "x"), TasksState(filterCategoryId = 2L),
            TasksState(filterStatus = TaskStatus.PAUSED),
            TasksState(filterCategoryId = 2L, searchQuery = "x")
        ).forEach { state ->
            assertEquals(
                "hasActiveFilters and the chip's number are the same fact",
                state.hasActiveFilters, state.activeFilterCount > 0
            )
        }
    }

    /**
     * The titles a popup would show, read from the menu the fragment inflates into the same kind of object.
     *
     * `PopupMenu` is what `showSortMenu` and `showOverflowMenu` build, and its `menu` is a real `Menu`
     * before `show()` is ever called — so this checks the resource the user's press opens rather than a
     * copy of its contents written into the test.
     */
    private fun offeredIn(menuRes: Int): List<String> {
        val header = inflated().tasksHeader
        val popup = PopupMenu(context, header.headerMenuButton)
        popup.menuInflater.inflate(menuRes, popup.menu)
        val menu = popup.menu
        return (0 until menu.size()).map { menu.getItem(it).title.toString() }
    }

    private fun actions() =
        listOf(R.id.headerSearchButton, R.id.headerSortButton, R.id.headerMenuButton)

    private fun name(id: Int) = context.resources.getResourceEntryName(id)
}
