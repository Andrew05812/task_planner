package com.taskplanner.ui.theme

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.R as MaterialR
import com.taskplanner.R
import com.taskplanner.databinding.BarSelectionBinding
import com.taskplanner.databinding.HeaderScreenBinding
import com.taskplanner.databinding.RowListBinding
import com.taskplanner.databinding.StateEmptyBinding
import com.taskplanner.databinding.StateErrorBinding
import com.taskplanner.databinding.StateLoadingBinding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Every component style, read back under both themes.
 *
 * A style nobody inflates is a comment in XML. These are the numbers spec §3.4 states — the radius of a
 * card, the one point of elevation, the 48dp floor under anything a finger hits — asserted against the
 * shipped resources, so a later stage that edits one of them either means to or fails.
 */
@RunWith(RobolectricTestRunner::class)
class ComponentStyleTest {

    private fun themed(night: Boolean): Context {
        RuntimeEnvironment.setQualifiers(if (night) "night" else "notnight")
        return ContextThemeWrapper(ApplicationProvider.getApplicationContext(), R.style.Theme_TaskPlanner)
    }

    private fun dp(c: Context, name: String): Float =
        c.resources.getDimension(R.dimen::class.java.getField(name).getInt(null))

    /** The dimension an attribute carries when a view is built with `styleRes` as its default style. */
    private fun size(c: Context, styleRes: Int, attr: Int): Float {
        val a = c.obtainStyledAttributes(null, intArrayOf(attr), 0, styleRes)
        try {
            return a.getDimension(0, -1f)
        } finally {
            a.recycle()
        }
    }

    private fun bothThemes(body: (Context) -> Unit) = listOf(false, true).forEach { body(themed(it)) }

    @Test fun `a card is radius_card with one point of elevation and no border`() {
        bothThemes { c ->
            assertEquals(dp(c, "radius_card"), size(c, R.style.Widget_TaskPlanner_Card, MaterialR.attr.cardCornerRadius), 0.5f)
            assertEquals(1f * c.resources.displayMetrics.density,
                size(c, R.style.Widget_TaskPlanner_Card, MaterialR.attr.cardElevation), 0.5f)
            assertEquals(0f, size(c, R.style.Widget_TaskPlanner_Card, MaterialR.attr.strokeWidth), 0.5f)
        }
    }

    @Test fun `every button is a control radius and never smaller than the touch floor`() {
        val buttons = listOf(
            R.style.Widget_TaskPlanner_Button_Filled,
            R.style.Widget_TaskPlanner_Button_Tonal,
            R.style.Widget_TaskPlanner_Button_Outlined,
            R.style.Widget_TaskPlanner_Button_Text,
            R.style.Widget_TaskPlanner_Button_Destructive
        )
        bothThemes { c ->
            buttons.forEachIndexed { index, style ->
                assertEquals("button #$index", dp(c, "radius_control"), size(c, style, MaterialR.attr.cornerRadius), 0.5f)
                assertTrue(
                    "button #$index must clear the 48dp touch floor",
                    size(c, style, android.R.attr.minHeight) >= dp(c, "touch_target_min") - 0.5f
                )
            }
        }
    }

    /** A boolean an attribute carries when a view is built with `styleRes` as its default style. */
    private fun flag(c: Context, styleRes: Int, attr: Int): Boolean {
        val a = c.obtainStyledAttributes(null, intArrayOf(attr), 0, styleRes)
        try {
            return a.getBoolean(0, false)
        } finally {
            a.recycle()
        }
    }

    /**
     * A chip never rewrites the reader's words. `chipStyle` reaches every chip that does not name one,
     * and the first ones it reached were the category rail's — where a category called "Диссертация"
     * came back as "ДИССЕРТАЦИЯ" the moment the label role was applied to it. Section labels are written
     * by the screen and stay in caps; a value the user typed is not a heading.
     */
    @Test fun `a chip carries its text in the case the reader wrote it`() {
        bothThemes { c ->
            assertTrue(
                "chips do not force caps",
                !flag(c, R.style.Widget_TaskPlanner_Chip_Filter, android.R.attr.textAllCaps)
            )
            assertTrue(
                "chips do not force caps",
                !flag(c, R.style.Widget_TaskPlanner_Chip_Category, android.R.attr.textAllCaps)
            )
            // The role itself still caps, because that is what §3.3 asks of a section label. Read off the
            // TextAppearance directly: `textAllCaps` is an attribute of that style, not something the
            // widget style inherits into its own slot.
            assertTrue(
                "the label role keeps its caps, so a screen writing its own heading still gets them",
                flag(c, R.style.TextAppearance_TaskPlanner_Label, android.R.attr.textAllCaps)
            )
            assertTrue(
                "and the pill role does not, which is what keeps the 82dp line-B budget of §4.1",
                !flag(c, R.style.TextAppearance_TaskPlanner_Pill, android.R.attr.textAllCaps)
            )
        }
    }

    @Test fun `a filter chip is a one-point outline at the pill radius and the touch floor`() {
        bothThemes { c ->
            assertEquals(1f * c.resources.displayMetrics.density,
                size(c, R.style.Widget_TaskPlanner_Chip_Filter, MaterialR.attr.chipStrokeWidth), 0.5f)
            assertTrue(
                size(c, R.style.Widget_TaskPlanner_Chip_Filter, MaterialR.attr.chipMinHeight) >=
                    dp(c, "touch_target_min") - 0.5f
            )
        }
    }

    @Test fun `a field and its dropdown share the control radius`() {
        bothThemes { c ->
            assertEquals(dp(c, "radius_control"),
                size(c, R.style.Widget_TaskPlanner_Field, MaterialR.attr.boxCornerRadiusTopStart), 0.5f)
            assertEquals(dp(c, "radius_control"),
                size(c, R.style.Widget_TaskPlanner_Field_Dropdown, MaterialR.attr.boxCornerRadiusTopStart), 0.5f)
        }
    }

    /** A bar that shadows itself is a bar that is not attached to anything. */
    @Test fun `the bottom bar carries no elevation`() {
        bothThemes { c ->
            assertEquals(0f, size(c, R.style.Widget_TaskPlanner_BottomBar, MaterialR.attr.elevation), 0.5f)
        }
    }
}

/**
 * The six includes stage 1 ships, inflated and reached through their own generated binding.
 *
 * `row_list` is included eight times by Profile in stage 5, which is the reason its children carry ids at
 * all. ViewBinding scopes an include's binding to that include's own view tree — but only if the ids are
 * reachable from it, so the eighth copy is proven here rather than at the point where two of them would
 * silently resolve against the first.
 */
@RunWith(RobolectricTestRunner::class)
class IncludeBindingTest {

    private fun inflater(night: Boolean = false): LayoutInflater {
        RuntimeEnvironment.setQualifiers(if (night) "night" else "notnight")
        return LayoutInflater.from(
            ContextThemeWrapper(ApplicationProvider.getApplicationContext(), R.style.Theme_TaskPlanner)
        )
    }

    @Test fun `the screen header reaches its title and its subtitle`() {
        listOf(false, true).forEach { night ->
            val b = HeaderScreenBinding.inflate(inflater(night))
            assertNotNull(b.headerTitle)
            assertNotNull(b.headerSubtitle)
            assertEquals(R.id.headerTitle, b.headerTitle.id)
        }
    }

    @Test fun `a list row reaches its icon container, title, value and chevron`() {
        val b = RowListBinding.inflate(inflater())
        assertNotNull(b.rowIconContainer)
        assertNotNull(b.rowTitle)
        assertNotNull(b.rowValue)
        assertNotNull(b.rowChevron)
        // Stage 5 put the glyph itself inside the plate: the container alone could not carry an icon, which
        // is why Profile's eight rows had to reach for `app:icon` on a button before. Declared empty, on
        // purpose — which picture a row shows is the screen's decision, and the device test is where
        // `drawable != null` is asserted. A freshly inflated view is VISIBLE anyway, so that would have been
        // a test of the default.
        assertNotNull(b.rowIcon)
        org.junit.Assert.assertNull("the include ships a place for a glyph, not a glyph", b.rowIcon.drawable)
        // The value line is hidden until a screen gives the row one, and `gone` rather than `invisible`
        // so a row without a value takes the width back for its title.
        assertEquals(android.view.View.GONE, b.rowValue.visibility)
    }

    @Test fun `two rows in one parent keep their own children`() {
        val parent = android.widget.LinearLayout(inflater().context)
        val first = RowListBinding.inflate(inflater(), parent, false)
        val second = RowListBinding.inflate(inflater(), parent, false)
        first.rowTitle.text = "first"
        second.rowTitle.text = "second"
        parent.addView(first.root)
        parent.addView(second.root)
        // The whole point of the ids surviving inside an include: two copies resolve against each other,
        // not against whichever was inflated first.
        assertEquals("first", first.rowTitle.text.toString())
        assertEquals("second", second.rowTitle.text.toString())
        assertNotEquals(first.root, second.root)
        assertEquals(2, parent.childCount)
    }

    @Test fun `the three states reach the view a screen has to act on`() {
        val empty = StateEmptyBinding.inflate(inflater())
        assertNotNull(empty.stateEmptyTitle)
        assertNotNull(empty.stateEmptyAction)
        assertEquals(android.view.View.GONE, empty.stateEmptyAction.visibility)

        val loading = StateLoadingBinding.inflate(inflater())
        assertNotNull(loading.stateLoadingProgress)
        assertTrue(loading.stateLoadingProgress.isIndeterminate)

        val error = StateErrorBinding.inflate(inflater())
        assertNotNull(error.stateErrorText)
        assertNotNull(error.stateErrorRetry)
    }

    @Test fun `the selection bar reaches the four views TasksFragment binds today`() {
        val b = BarSelectionBinding.inflate(inflater())
        assertNotNull(b.selectionCount)
        assertNotNull(b.selectAllButton)
        assertNotNull(b.deleteSelectedButton)
        assertNotNull(b.cancelSelectionButton)
        // Every control in the bar is a 48dp target before it is anything else.
        val floor = b.root.resources.getDimension(R.dimen.touch_target_min)
        listOf(b.selectAllButton, b.deleteSelectedButton, b.cancelSelectionButton).forEach { button ->
            assertTrue(
                "the bar's buttons are sized by layout params, not by a literal",
                (button.layoutParams as ViewGroup.LayoutParams).width >= floor.toInt() - 1
            )
        }
        // And every one of them says what it is. These three buttons were drawn with labels while the bar
        // was written inline in `fragment_tasks.xml`; an include that reaches for `@null` would hand the
        // screen reader three anonymous glyphs, and the bar is where the destructive action lives.
        mapOf(
            "selectAllButton" to b.selectAllButton,
            "deleteSelectedButton" to b.deleteSelectedButton,
            "cancelSelectionButton" to b.cancelSelectionButton
        ).forEach { (name, button) ->
            assertTrue(
                "$name shows a glyph and nothing else, so it has to name itself " +
                    "(contentDescription='${button.contentDescription}')",
                !button.contentDescription.isNullOrEmpty()
            )
        }
    }
}
