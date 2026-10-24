package com.taskplanner.ui.theme

import android.content.Context
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.taskplanner.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The nine roles of spec 3.3, read back off the styles the layouts will actually use.
 *
 * Asserting the style rather than a dimension is the point: a `type_title` dimen of 16sp that no style
 * refers to would satisfy a dimens test and change nothing on screen.
 */
@RunWith(RobolectricTestRunner::class)
class TypeScaleTest {

    private val themed: Context =
        ContextThemeWrapper(ApplicationProvider.getApplicationContext(), R.style.Theme_TaskPlanner)

    /** TypedValue.px2sp is @hide in the public SDK; scaledDensity is the documented route. */
    private fun sizeSp(styleRes: Int): Float =
        TextView(themed, null, 0, styleRes).textSize / themed.resources.displayMetrics.scaledDensity

    @Test fun `each role is the size the scale says it is`() {
        assertEquals(28f, sizeSp(R.style.TextAppearance_TaskPlanner_Display), 0.2f)
        assertEquals(22f, sizeSp(R.style.TextAppearance_TaskPlanner_Headline), 0.2f)
        assertEquals(16f, sizeSp(R.style.TextAppearance_TaskPlanner_Title), 0.2f)
        assertEquals(15f, sizeSp(R.style.TextAppearance_TaskPlanner_Body), 0.2f)
        assertEquals(13f, sizeSp(R.style.TextAppearance_TaskPlanner_Meta), 0.2f)
        assertEquals(12f, sizeSp(R.style.TextAppearance_TaskPlanner_Caption), 0.2f)
        assertEquals(11f, sizeSp(R.style.TextAppearance_TaskPlanner_Pill), 0.2f)
        assertEquals(11f, sizeSp(R.style.TextAppearance_TaskPlanner_Label), 0.2f)
        assertEquals(20f, sizeSp(R.style.TextAppearance_TaskPlanner_Metric), 0.2f)
    }

    /**
     * Tracking is asserted in `android:letterSpacing` units, which are multiples of an em and not dp.
     * A label written as `0.4` instead of `0.045` is 40 % of the font size added between every glyph, and
     * the widths §4.1 budgets stop being true — which is the exact mistake this test exists to prevent.
     */
    @Test fun `tracking is expressed in ems, not in dp typed into the em slot`() {
        listOf(
            R.style.TextAppearance_TaskPlanner_Meta to 0.019f,
            R.style.TextAppearance_TaskPlanner_Caption to 0.021f,
            R.style.TextAppearance_TaskPlanner_Pill to 0.045f,
            R.style.TextAppearance_TaskPlanner_Label to 0.045f
        ).forEach { (style, expected) ->
            val actual = TextView(themed, null, 0, style).letterSpacing
            assertEquals(
                themed.resources.getResourceEntryName(style), expected, actual, 0.0015f
            )
        }
    }

    /** The theme is what hands the roles to the attributes the not-yet-migrated layouts already use. */
    @Test fun `the five legacy attributes the dashboard asks for resolve to five different styles`() {
        val asked = listOf(
            com.google.android.material.R.attr.textAppearanceHeadline5,
            com.google.android.material.R.attr.textAppearanceSubtitle1,
            com.google.android.material.R.attr.textAppearanceBody1,
            com.google.android.material.R.attr.textAppearanceBody2,
            com.google.android.material.R.attr.textAppearanceCaption
        )
        val styles = asked.map { attr ->
            val value = TypedValue()
            assertTrue(
                "the theme answers ?attr/${themed.resources.getResourceName(attr)}",
                themed.theme.resolveAttribute(attr, value, true)
            )
            // The non-zero check is not decoration: `StatisticsLayoutTest` asserts it because a
            // resolveAttribute can succeed by falling through to the M3 parent, returning 0 or the
            // parent's style, while the map below still sees five distinct ids. Without this line an
            // attribute this stage forgot to map is invisible to the test that claims to catch it.
            assertNotEquals(
                "the theme names a resource for ${themed.resources.getResourceName(attr)}",
                0, value.resourceId
            )
            value.resourceId
        }
        assertEquals("five roles, five styles, or the dashboard has no hierarchy", 5, styles.toSet().size)
        // And the sizes really are the roles: a resolved id that points at the wrong TextAppearance would
        // satisfy the distinctness check above while drawing 12sp where 13sp is promised.
        assertEquals(
            "Headline5 maps to display", sizeSp(R.style.TextAppearance_TaskPlanner_Display),
            sizeSpOf(TextView(themed, null, asked[0])), 0.2f
        )
    }

    @Test fun `a layout asking for the old body2 attribute gets the meta role`() {
        // Three arguments, not four: `TextView(context, attrs, defStyleAttr)` takes the THEME ATTRIBUTE and
        // resolves it through the theme, while the fourth slot is `defStyleRes`, a style resource id. The
        // earlier draft put `R.attr.textAppearanceBody2` in the defStyleRes slot, where no default style is
        // ever found, so the view came back at the platform default size and this test could not pass.
        val tv = TextView(themed, null, com.google.android.material.R.attr.textAppearanceBody2)
        assertEquals(sizeSp(R.style.TextAppearance_TaskPlanner_Meta), sizeSpOf(tv), 0.2f)
    }

    private fun sizeSpOf(tv: TextView): Float =
        tv.textSize / themed.resources.displayMetrics.scaledDensity
}
