package com.taskplanner.ui.insights

import android.content.Context
import android.text.TextUtils
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.isVisible
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.R as MaterialR
import com.google.android.material.chip.Chip
import com.taskplanner.R
import com.taskplanner.databinding.FragmentInsightsBinding
import com.taskplanner.databinding.ItemInsightBinding
import com.taskplanner.databinding.ItemInsightsEmptyBinding
import com.taskplanner.databinding.ItemWorkloadDayBinding
import com.taskplanner.databinding.ItemWorkloadPanelBinding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The insights screen's own shape, inflated under the app's theme the way the fragment uses it.
 *
 * View binding already proves the ids the renderers read exist, so none of this is a check that a name was
 * typed correctly. What is checked are the decisions a layout can undo without an error and without a crash: a
 * scope row that wraps onto a second line, a card whose long basis is clipped by its neighbour instead of
 * wrapping under it, a list that scrolls its own switch away with it, a legend that lands under the wrong bar,
 * a day row whose bar overruns the count beside it, a button that appears on a card with nowhere to go, and a
 * text appearance the theme does not answer — which is text that quietly comes back at the platform's default
 * size.
 *
 * The fragment is not launched, because this module has no fragment-testing dependency; every assertion is made
 * on an inflated binding and on the properties of the views inside it. What those rows *say* is the business of
 * [WorkloadPanelTest] and [InsightsScreenTest].
 */
@RunWith(RobolectricTestRunner::class)
class InsightsLayoutTest {

    private lateinit var themed: Context

    @Before
    fun setUp() {
        val app: Context = ApplicationProvider.getApplicationContext()
        // The screen always lives inside the activity's theme; without it ?attr/textAppearance* and the
        // Material card and chip styles do not resolve, which is not how it is used.
        themed = ContextThemeWrapper(app, R.style.Theme_TaskPlanner)
    }

    private fun screen(): FragmentInsightsBinding = FragmentInsightsBinding.inflate(LayoutInflater.from(themed))

    private fun card(): ItemInsightBinding = ItemInsightBinding.inflate(LayoutInflater.from(themed))

    private fun panel(): ItemWorkloadPanelBinding = ItemWorkloadPanelBinding.inflate(LayoutInflater.from(themed))

    /**
     * A day row as the panel actually inflates it — into the list it will be added to.
     *
     * The parent is not decoration: a root view only gets its own [android.view.ViewGroup.LayoutParams] from
     * the container it is inflated against, so the width of the row is unobservable without one.
     */
    private fun dayRow(): ItemWorkloadDayBinding =
        ItemWorkloadDayBinding.inflate(LayoutInflater.from(themed), container(), false)

    private fun container(): ViewGroup = LinearLayout(themed)

    private fun Int.dp(): Int = (this * themed.resources.displayMetrics.density).toInt()

    private fun topBelow(view: android.view.View): Int =
        (view.layoutParams as ConstraintLayout.LayoutParams).topToBottom

    @Test
    fun `the switch offers one choice at a time, on one line, and never none`() {
        val binding = screen()
        val chips = binding.insightsScope

        assertTrue(
            "the screen reads one list at a time, so the live readings and the retired ones cannot be on the " +
                "screen together",
            chips.isSingleSelection
        )
        assertTrue(
            "and one of them always is: a row that lets both go off would leave the list holding neither",
            chips.isSelectionRequired
        )
        assertTrue(
            "one line, or a wrapped second row of chips takes the first card off the screen of a narrow phone",
            chips.isSingleLine
        )
        assertEquals("two scopes and nothing else to choose", 2, chips.childCount)
        assertTrue(
            "the row starts on the live readings, which is the state the screen itself starts in",
            binding.insightsScopeCurrent.isChecked
        )
        assertFalse(binding.insightsScopeHistory.isChecked)
        assertEquals(
            "the switch is nailed to the top of the screen, above everything that scrolls",
            ConstraintLayout.LayoutParams.PARENT_ID,
            (binding.insightsScope.layoutParams as ConstraintLayout.LayoutParams).topToTop
        )
    }

    @Test
    fun `the panel, the cards and the admitted-empty ride one scroller pinned under the switch`() {
        val binding = screen()
        val params = binding.insightsList.layoutParams as ConstraintLayout.LayoutParams

        assertEquals("the list starts under the switch it scrolls beneath", binding.insightsScope.id, params.topToBottom)
        assertEquals(
            "and reaches the bottom of the screen, so a fortnight of cards is scrolled to rather than grown " +
                "past the device",
            ConstraintLayout.LayoutParams.PARENT_ID,
            params.bottomToBottom
        )
        assertEquals(
            "its height is the space between those two, not wrap_content: a list sized to its own rows would " +
                "push its bottom off the screen and stop scrolling",
            0,
            params.height
        )
        assertEquals(
            "three views and no more: the switch, the list the panel and the cards ride in, and the mark the " +
                "state shows while the list has nothing to draw",
            3,
            binding.root.childCount
        )
        assertEquals(
            "the loading mark is placed inside the area it covers, never over the switch the reader is still " +
                "entitled to press",
            binding.insightsScope.id,
            topBelow(binding.insightsProgress)
        )
        assertFalse(
            "and the mark is drawn by the state rather than by the layout, so a loaded screen is not greyed out",
            binding.insightsProgress.isVisible
        )
    }

    @Test
    fun `a card's four answers are one answer, and no answer is one of them`() {
        val answers = card().insightAnswers

        assertTrue(
            "the row stores one answer, so the group cannot show two of them as given",
            answers.isSingleSelection
        )
        assertFalse(
            "and none is required: a reading nobody has answered is the ordinary state of a card, and a group " +
                "that forced a choice would make the reader pick one to be readable",
            answers.isSelectionRequired
        )
        assertEquals(
            "four answers, in the order the repository's four values are shown",
            listOf(R.id.answerAccepted, R.id.answerUseful, R.id.answerHidden, R.id.answerRejected),
            (0 until answers.childCount).map { answers.getChildAt(it).id }
        )
        (0 until answers.childCount).forEach { index ->
            val chip = answers.getChildAt(index) as Chip
            assertTrue(
                "answer #$index is a checkable filter chip, because the tick is what carries the row's answer",
                chip.isCheckable && chip.isClickable
            )
        }
    }

    @Test
    fun `a card that leads nowhere is the layout's default, not an afterthought`() {
        val binding = card()

        assertFalse(
            "the action button starts gone: a reading that is worth knowing and leads nowhere is the ordinary " +
                "case, and a button that opens nothing is worse than no button",
            binding.insightAction.isVisible
        )
        assertEquals(
            "and it belongs under the framing line rather than beside it, so a card never puts two tap targets " +
                "in the same row of thumb space",
            binding.insightMeta.id,
            topBelow(binding.insightAction)
        )
    }

    @Test
    fun `the sentences a card was handed get the width of the card and only the title is capped`() {
        val binding = card()

        listOf(binding.insightTitle, binding.insightBody, binding.insightBasis, binding.insightMeta)
            .forEachIndexed { index, view ->
                val params = view.layoutParams as ConstraintLayout.LayoutParams
                assertEquals(
                    "sentence #$index is width 0dp between the card's edges, so a long basis wraps inside the " +
                        "card instead of running under its neighbour",
                    0,
                    params.width
                )
                assertEquals(ConstraintLayout.LayoutParams.PARENT_ID, params.startToStart)
                assertEquals(ConstraintLayout.LayoutParams.PARENT_ID, params.endToEnd)
            }

        assertEquals(
            "the title is held to two lines and told to say so, because a title that grows without limit would " +
                "push the panel and every card under it down",
            2,
            binding.insightTitle.maxLines
        )
        assertEquals(TextUtils.TruncateAt.END, binding.insightTitle.ellipsize)
        listOf(binding.insightBody, binding.insightBasis, binding.insightMeta).forEach {
            assertEquals(
                "the claim, its evidence and its window are never cut short — a reader who cannot see the basis " +
                    "cannot check the reading",
                Integer.MAX_VALUE,
                it.maxLines
            )
        }
    }

    @Test
    fun `the panel keeps each figure under the sentence that explains it`() {
        val binding = panel()

        assertEquals(
            "the window sits under the title it is the window of",
            binding.workloadTitle.id,
            topBelow(binding.workloadWindow)
        )
        assertEquals(
            "the total under the window it was summed over",
            binding.workloadWindow.id,
            topBelow(binding.workloadTotal)
        )
        assertEquals(
            "the caveat directly under the total it is a caveat about; one line away and it explains nothing",
            binding.workloadTotal.id,
            topBelow(binding.workloadCaveat)
        )
        assertEquals(
            "the bar under the caveat, so the reader is told the hours are approximate before they are shown " +
                "them",
            binding.workloadCaveat.id,
            topBelow(binding.workloadPriority)
        )
        assertEquals(
            "and the key directly under the bar it reads",
            binding.workloadPriority.id,
            topBelow(binding.workloadPriorityLegend)
        )
        assertEquals(
            "the day rows under the baseline, which is the pace they are compared against",
            binding.workloadBaseline.id,
            topBelow(binding.workloadDays)
        )
        assertFalse(
            "the caveat starts hidden: a window of measured minutes has nothing to hedge, and the sentence " +
                "must not be visible until a figure earns it",
            binding.workloadCaveat.isVisible
        )
    }

    @Test
    fun `a day row keeps its bar clear of its count and its badge clear of its name`() {
        val row = dayRow()

        assertEquals(
            "the bar stops where the count begins, so a crowded fortnight cannot make the two overlap",
            row.dayTasks.id,
            (row.dayBar.layoutParams as ConstraintLayout.LayoutParams).endToStart
        )
        assertEquals(
            "the count is written on the same line as the bar it belongs to",
            row.dayBar.id,
            (row.dayTasks.layoutParams as ConstraintLayout.LayoutParams).baselineToBaseline
        )
        assertEquals(
            "and the busiest badge starts after the day's name instead of landing on top of it",
            row.dayName.id,
            (row.dayBusiest.layoutParams as ConstraintLayout.LayoutParams).startToEnd
        )
        assertFalse(
            "the badge and the estimate marker are drawn by the render, so a plain day's row is plain",
            row.dayBusiest.isVisible || row.dayApprox.isVisible
        )
        assertEquals(
            "the row is as wide as the panel, so every day's bar is measured against the same track",
            ViewGroup.LayoutParams.MATCH_PARENT,
            row.root.layoutParams.width
        )
    }

    @Test
    fun `the empty list is a row of the list, not a message laid over it`() {
        val line = ItemInsightsEmptyBinding.inflate(LayoutInflater.from(themed), container(), false).insightsEmpty

        assertEquals(
            "it takes the width of the list it sits in",
            ViewGroup.LayoutParams.MATCH_PARENT,
            line.layoutParams.width
        )
        assertTrue(
            "and inset enough that a two-line sentence does not touch the screen edges on a narrow phone",
            line.paddingLeft >= 24.dp() && line.paddingRight >= 24.dp()
        )
        assertTrue(
            "centred, because the line stands alone under the panel rather than beginning a paragraph",
            line.gravity and Gravity.CENTER_HORIZONTAL != 0
        )
    }

    @Test
    fun `the text appearances the screen asks for are ones its theme answers`() {
        val asked = listOf(
            "textAppearanceSubtitle1" to MaterialR.attr.textAppearanceSubtitle1,
            "textAppearanceBody1" to MaterialR.attr.textAppearanceBody1,
            "textAppearanceBody2" to MaterialR.attr.textAppearanceBody2,
            "textAppearanceCaption" to MaterialR.attr.textAppearanceCaption
        )

        val styles = asked.map { (name, attr) ->
            val value = TypedValue()
            assertTrue(
                "?attr/$name has to be an attribute Theme.TaskPlanner answers; a reference it cannot resolve is " +
                    "not a build error but text that comes back at the platform's default size",
                themed.theme.resolveAttribute(attr, value, true)
            )
            assertNotEquals("?attr/$name resolves to a name with no style behind it", 0, value.resourceId)
            value.resourceId
        }
        assertEquals(
            "four roles need four different styles, or the card has no hierarchy left for a reader to follow",
            asked.size,
            styles.toSet().size
        )

        val binding = card()
        assertTrue(
            "the title is the largest line on the card, or the reading is not what the card is about",
            binding.insightTitle.paint.textSize > binding.insightBody.paint.textSize
        )
        assertTrue(
            "the claim is larger than the evidence under it",
            binding.insightBody.paint.textSize > binding.insightBasis.paint.textSize
        )
        assertTrue(
            "and the framing line is among the smallest words the screen uses",
            binding.insightMeta.paint.textSize <= binding.insightBasis.paint.textSize
        )
    }
}
