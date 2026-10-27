package com.taskplanner.ui.tasks

import android.content.Context
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import org.xmlpull.v1.XmlPullParser
import androidx.test.core.app.ApplicationProvider
import com.taskplanner.R
import com.taskplanner.databinding.FragmentTaskDetailBinding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What the editor's narrowest screen can be asked to hold, measured rather than eyeballed.
 *
 * The task card has carried a width budget since Stage 4 for the reason this file exists: a form checked
 * only by eye shows its overflow in the one place nobody looks — a button drawn at a negative x, half off
 * the left edge of the screen, still "visible" in a screenshot and untappable at its leading half. Two rows
 * on this screen are built that way, and both were photographed at 393dp, where they happen to fit.
 *
 * What a JVM layout can answer, and what it cannot:
 *
 *  * **the plan's estimate box**, which used to share a line with `Clear the plan times`. That is a layout
 *    fact — a field sharing a row with a `wrap_content` button loses, and a squeezed box says nothing out
 *    loud, because its floating hint simply stops fitting. Weights and order belong to the container, so
 *    the container is measured here.
 *  * **whether the work commands fit their row** — `Pause measuring`, `Stop and save`, `Enter time by hand`,
 *    11sp all-caps label type, up to three of them on screen at once. That is a question about the width of
 *    *words*, and Robolectric lays text out with legacy metrics that ignore the type size: measured here,
 *    the widest of the three comes back at 143px and the row "fits" with room to spare on a screen where
 *    the real device puts them on two lines. Stage 4 met the same wall with the card and took the
 *    measurement to the emulator (`TaskCardWidthBudgetInstrumentedTest`); the editor follows it, so the
 *    commands are walked on the device by
 *    `TaskEditorFramesInstrumentedTest.theWorkCommandsKeepTheirWordsOnTheScreen` — at 320dp, at 393dp and
 *    at a font scale of 1.3, inside a stretch that is really running.
 *
 * What is left here is therefore the part that does not depend on a glyph's width, plus one structural
 * claim that does not need measuring at all: the commands are held by a container that *can* wrap. That
 * one is worth pinning, because a plain horizontal `LinearLayout` has no second line to put anything on,
 * which is exactly how the overflow happened in the first place.
 */
@RunWith(RobolectricTestRunner::class)
class TaskEditorCommandBudgetTest {

    private fun inflated(): FragmentTaskDetailBinding {
        val context: Context = ApplicationProvider.getApplicationContext()
        val themed = ContextThemeWrapper(context, R.style.Theme_TaskPlanner)
        return FragmentTaskDetailBinding.inflate(LayoutInflater.from(themed))
    }

    private fun dp(value: Float): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value,
        ApplicationProvider.getApplicationContext<Context>().resources.displayMetrics
    ).toInt()

    /**
     * Lays the editor out at [widthDp] as if it were the whole window, after [configure] has put it in the
     * state being measured.
     *
     * Configure first, measure second, and the ordering belongs to the test's integrity: a view made
     * visible after the layout has run was never measured, so it reports 0x0 and any bounds check on it
     * passes by being nowhere. The first run of this file did exactly that and called the result a pass.
     */
    private fun laidOutAt(
        widthDp: Int,
        configure: (FragmentTaskDetailBinding) -> Unit = {}
    ): FragmentTaskDetailBinding {
        val binding = inflated()
        // The tracking block is hidden for a task nobody has saved, and both rows measured here are inside
        // blocks the editor opens only once there is a stored task to open.
        binding.workSection.visibility = View.VISIBLE
        configure(binding)
        val width = dp(widthDp.toFloat())
        val height = dp(500f)
        binding.root.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        )
        binding.root.layout(0, 0, width, height)
        return binding
    }

    @Test
    fun `the estimate box keeps the width the form has and the clear command sits under it`() {
        val binding = laidOutAt(320)
        val planRow = binding.planSection.getChildAt(1) as ViewGroup  // the start date / start time pair
        val blockWidth = planRow.width
        assertTrue("the plan block was never measured: $blockWidth", blockWidth > 0)

        assertEquals(
            "the estimate box spans the plan block instead of sharing a line with a button",
            blockWidth,
            binding.planEstimateLayout.width
        )
        assertTrue(
            "Clear the plan times is below the estimate, not beside it: clearTop=${binding.planClear.top} " +
                "estimateBottom=${binding.planEstimateLayout.top + binding.planEstimateLayout.height}",
            binding.planClear.top >= binding.planEstimateLayout.top + binding.planEstimateLayout.height
        )
        assertTrue(
            "and it stays inside the block: left=${binding.planClear.left} " +
                "right=${binding.planClear.right} of $blockWidth",
            binding.planClear.left >= 0 && binding.planClear.right <= blockWidth
        )
    }

    @Test
    fun `the work commands are held by a flow that can wrap them`() {
        val binding = laidOutAt(320)
        val row = binding.workAddButton.parent as ViewGroup

        assertTrue(
            "the commands live in a ${row.javaClass.simpleName}, and only a ConstraintLayout can host the " +
                "flow that wraps them",
            row is androidx.constraintlayout.widget.ConstraintLayout
        )
        assertTrue(
            "the commands have no flow to place them in: without one there is nothing that could move a " +
                "command onto a second line",
            (0 until row.childCount).any { child ->
                row.getChildAt(child) is androidx.constraintlayout.helper.widget.Flow
            }
        )
    }

    /**
     * The flow's own two settings, read out of the shipped layout.
     *
     * `Flow` keeps its mode and its referenced ids private — 2.1.4 has no `getWrapMode()` or
     * `getReferencedIds()` to ask, which is why this parses the compiled resource instead of the view tree.
     * Be clear about what that proves: it pins that the layout says `chain` and names the five commands, so
     * deleting either one fails here. It does not prove a second line appears; that is measured on the
     * device by `TaskEditorFramesInstrumentedTest.theWorkCommandsKeepTheirWordsOnTheScreen`, and pretending a
     * resource read could see it is how a budget stops being a budget.
     */
    @Test
    fun `the flow is told to wrap these five commands in a chain`() {
        val context: Context = ApplicationProvider.getApplicationContext()
        var flowAttributes: Map<String, String>? = null
        context.resources.getXml(R.layout.fragment_task_detail).use { parser ->
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG &&
                    parser.name == "androidx.constraintlayout.helper.widget.Flow"
                ) {
                    flowAttributes = (0 until parser.attributeCount).associate { index ->
                        parser.getAttributeName(index) to parser.getAttributeValue(index)
                    }
                }
                parser.next()
            }
        }
        val flow = flowAttributes ?: throw AssertionError("the editor layout holds no Flow at all")
        // `flow_wrapMode` is an enum attribute, so the parser hands back the resolved value rather than the
        // word the XML spells; comparing against the library's own constant is the honest version of
        // "the layout says chain".
        assertEquals(
            "the commands are laid out in a chain that can wrap, not on a line that clips: $flow",
            androidx.constraintlayout.helper.widget.Flow.WRAP_CHAIN.toString(),
            flow["flow_wrapMode"]
        )
        val named = flow["constraint_referenced_ids"].orEmpty().split(",").map { it.trim() }
        listOf(
            "workStartButton", "workPauseButton", "workResumeButton", "workStopButton", "workAddButton"
        ).forEach { command ->
            assertTrue("$command is not one of the ids the flow places: $named", command in named)
        }
    }
}
