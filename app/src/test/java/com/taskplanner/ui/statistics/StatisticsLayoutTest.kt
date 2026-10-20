package com.taskplanner.ui.statistics

import android.content.Context
import android.content.res.TypedArray
import android.graphics.Color
import android.text.TextUtils
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.R as MaterialR
import com.google.android.material.chip.Chip
import com.google.android.material.card.MaterialCardView
import com.taskplanner.R
import com.taskplanner.data.statistics.HeatmapCell
import com.taskplanner.data.statistics.HeatmapGrid
import com.taskplanner.databinding.FragmentStatisticsBinding
import com.taskplanner.databinding.ItemDashboardTaskBinding
import com.taskplanner.ui.components.BarChartView
import com.taskplanner.ui.components.ChartLegendView
import com.taskplanner.ui.components.StackedBarView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The dashboard's own layout, inflated under the app's theme the way the fragment uses it.
 *
 * View binding already proves the ids the renderer reads exist, so this file is not a check that a name was
 * typed correctly — it is a check of the shape the user gets: one period chosen at a time, a column of cards
 * that scrolls instead of one that grows past the screen, an activity grid allowed to ask for more room than
 * its card has, a proportion bar never parted from the key that reads it, a chart never parted from its
 * caption, gaps taken from the same scale the rest of the app uses, colours a legend can actually tell
 * apart, and text appearances the theme really answers.
 *
 * Those are decisions a layout can undo silently. Nothing crashes when a chip row wraps onto a second line,
 * when a year of weeks is squeezed into squares thinner than the gaps beside them, when a legend lands in the
 * card above the bar it explains, when a gap is typed as `12dp` instead of taken from the spacing scale, or
 * when `?attr/textAppearanceHeadline5` turns out to be a name the theme does not set — the screen only becomes
 * harder to read, on the smallest phone, without a single error to follow.
 *
 * The fragment itself is not launched: this module has no fragment-testing dependency, so every check is made
 * on an inflated binding and on the properties of the views inside it.
 */
@RunWith(RobolectricTestRunner::class)
class StatisticsLayoutTest {

    private fun themed(): Context {
        val context: Context = ApplicationProvider.getApplicationContext()
        // The dashboard always lives inside the activity's theme; without it ?attr/textAppearanceCaption and
        // the Material 3 filled card styles do not resolve, which is not how the screen is used.
        return ContextThemeWrapper(context, R.style.Theme_TaskPlanner)
    }

    private fun screen(): FragmentStatisticsBinding =
        FragmentStatisticsBinding.inflate(LayoutInflater.from(themed()))

    private fun taskRow(): ItemDashboardTaskBinding =
        ItemDashboardTaskBinding.inflate(LayoutInflater.from(themed()))

    private fun dimen(res: Int): Int = themed().resources.getDimensionPixelSize(res)

    /** The dp of the narrowest screen the app supports, in this display's pixels. */
    private fun narrowWidth(): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, NARROWEST_SCREEN_DP.toFloat(), themed().resources.displayMetrics
    ).toInt()

    /** The cards the scroller holds, in the order the reader meets them. */
    private fun cardViews(binding: FragmentStatisticsBinding): List<View> {
        val column = binding.dashboardScroll.getChildAt(0) as LinearLayout
        return (0 until column.childCount).map { column.getChildAt(it) }
    }

    private fun countOfType(parent: ViewGroup, type: Class<*>): Int =
        (0 until parent.childCount).count { type.isInstance(parent.getChildAt(it)) }

    /**
     * What a card wears when it takes one of the theme's own card styles: elevation, border and surface
     * colour, read out of the style the theme names rather than typed in here. The check is then about the
     * card and not about what this file remembers Material to look like.
     */
    private fun cardStyle(attr: Int): CardLook {
        val context = themed()
        val style = TypedValue()
        assertTrue(
            "the theme has to name the card style the dashboard borrows",
            context.theme.resolveAttribute(attr, style, true)
        )
        val array: TypedArray = context.obtainStyledAttributes(
            null,
            intArrayOf(
                MaterialR.attr.cardElevation,
                MaterialR.attr.strokeWidth,
                MaterialR.attr.cardBackgroundColor
            ),
            0,
            style.resourceId
        )
        try {
            return CardLook(
                elevation = array.getDimension(0, NO_VALUE),
                strokeWidth = array.getDimensionPixelSize(1, NO_VALUE.toInt()),
                background = array.getColorStateList(2)?.defaultColor ?: NO_VALUE.toInt()
            )
        } finally {
            array.recycle()
        }
    }

    /** Every margin that opens a vertical gap below [root], nested views included. */
    private fun verticalGaps(root: ViewGroup): List<Int> {
        val gaps = ArrayList<Int>()
        for (index in 0 until root.childCount) {
            val child = root.getChildAt(index)
            (child.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
                gaps.add(params.topMargin)
                gaps.add(params.bottomMargin)
            }
            if (child is ViewGroup) gaps.addAll(verticalGaps(child))
        }
        return gaps
    }

    private data class CardLook(val elevation: Float, val strokeWidth: Int, val background: Int)

    @Test
    fun `one period is chosen at a time and its row of chips scrolls instead of wrapping`() {
        val binding = screen()
        val chips = binding.statisticsPeriodChips

        assertTrue(
            "the screen reads exactly one period, so the chips cannot disagree with each other",
            chips.isSingleSelection
        )
        assertTrue(
            "and on one line: a ChipGroup that is not single-line wraps the six periods onto a second row " +
                "and pushes the charts off the first screen of a narrow phone",
            chips.isSingleLine
        )
        assertSame(
            "the row that grows sideways is the part inside the scroller, so the last periods are reached by " +
                "sliding rather than by never appearing",
            binding.statisticsPeriodScroll,
            chips.parent
        )
        assertEquals(
            "the chips are the scroller's only child, so nothing else slides off with them",
            1,
            binding.statisticsPeriodScroll.childCount
        )
        assertSame(chips, binding.statisticsPeriodScroll.getChildAt(0))
        assertEquals(
            "the period row is nailed to the top of the screen, above everything that scrolls",
            ConstraintLayout.LayoutParams.PARENT_ID,
            (binding.statisticsPeriodScroll.layoutParams as ConstraintLayout.LayoutParams).topToTop
        )

        // Single selection on its own only promises *at most* one, so the row is asked what a second tap on
        // the period already on leaves it with.
        val context = themed()
        val first = periodChip(context, "This week")
        val second = periodChip(context, "This month")
        chips.addView(first)
        chips.addView(second)
        chips.check(first.id)
        second.performClick()
        assertEquals(
            "choosing another period is a move to it, not a second one beside it",
            second.id,
            chips.checkedChipId
        )
        assertEquals(
            "and the period it moved off is no longer chosen",
            1,
            chips.checkedChipIds.size
        )
    }

    @Test
    fun `the period row always names one period, never none`() {
        val chips = screen().statisticsPeriodChips
        val chosen = periodChip(themed(), "This week")
        chips.addView(chosen)
        chips.check(chosen.id)
        assertEquals("the row starts on one period", chosen.id, chips.checkedChipId)

        // The chips are built the way the fragment builds them, because it is the layout's row that has to
        // keep this promise: a second tap lands on the chip that is already on.
        chosen.performClick()
        assertNotEquals(
            "`singleSelection` without `selectionRequired` lets a tap on the period already chosen empty the " +
                "row: the fragment reads the first of the checked ids, gets nothing, and leaves the cards " +
                "showing one period under a row that names none. The row needs " +
                "`app:selectionRequired=\"true\"` to keep the promise its name makes.",
            View.NO_ID,
            chips.checkedChipId
        )
    }

    /** One period chip, built as [com.taskplanner.ui.statistics.StatisticsFragment] builds them. */
    private fun periodChip(context: Context, label: String): Chip = Chip(context).apply {
        id = View.generateViewId()
        text = label
        isCheckable = true
        isCheckedIconVisible = false
    }

    @Test
    fun `the cards ride one scroller pinned between the chip row and the bottom of the screen`() {
        val binding = screen()
        val scroll = binding.dashboardScroll
        val params = scroll.layoutParams as ConstraintLayout.LayoutParams

        assertEquals(
            "the content is stretched between the periods and the bottom of the screen instead of being as " +
                "tall as its six cards, which is the only way the last card is reachable",
            0,
            params.height
        )
        assertEquals(R.id.statisticsPeriodScroll, params.topToBottom)
        assertEquals(ConstraintLayout.LayoutParams.PARENT_ID, params.bottomToBottom)
        assertEquals(
            "and it ends below the last card, so that card scrolls clear of the edge of the screen instead " +
                "of stopping under it",
            dimen(R.dimen.spacing_large),
            scroll.paddingBottom
        )
        assertFalse(
            "the padding is room the content slides through, not a wall the last card bumps into",
            scroll.clipToPadding
        )
        assertTrue("the column of cards is the scroller's own child", scroll.getChildAt(0) is LinearLayout)

        assertFalse(
            "the spinner waits for a read the screen has not asked for yet",
            binding.dashboardProgress.isVisible
        )
        assertFalse(
            "and the empty state is not a permanent caption: it shows only once a read has answered",
            binding.dashboardEmpty.isVisible
        )
        val emptyParams = binding.dashboardEmpty.layoutParams as ConstraintLayout.LayoutParams
        assertEquals(
            "the empty message is stretched from edge to edge, so its sentence wraps instead of running off " +
                "the screen on a narrow phone",
            0,
            emptyParams.width
        )
        assertEquals(ConstraintLayout.LayoutParams.PARENT_ID, emptyParams.startToStart)
        assertEquals(ConstraintLayout.LayoutParams.PARENT_ID, emptyParams.endToEnd)
        assertEquals(R.id.statisticsPeriodScroll, emptyParams.topToBottom)
        assertEquals(
            "it says what is missing rather than blaming the user",
            themed().getString(R.string.empty_statistics),
            binding.dashboardEmpty.text.toString()
        )
    }

    /**
     * The grid is given a year of weeks and measured the way a horizontal scroller measures its child — with
     * no width constraint at all. That is what this test does rather than only reading the params, because a
     * `wrap_content` in the XML proves nothing if the view answers the unconstrained spec with the width it
     * was handed: the room it asks for is what the scroller then has to slide.
     */
    @Test
    fun `the activity grid can ask for more room than the card has`() {
        val binding = screen()
        val grid = binding.activityGrid

        assertSame(
            "the grid is inside a scroller of its own, inside its card: fifty-two weeks are wider than any " +
                "phone, and the card has to let them slide",
            binding.activityGridScroll,
            grid.parent
        )
        assertSame(
            "the key stays in the card beside the scroller, so it does not slide away with the grid",
            (grid.parent as ViewGroup).parent,
            binding.activityGridLegend.parent
        )
        assertEquals(
            "and the grid takes the width its weeks need rather than the width it was handed",
            ViewGroup.LayoutParams.WRAP_CONTENT,
            grid.layoutParams.width
        )

        grid.setGrid(workedWeeks(WEEKS_IN_A_YEAR))
        grid.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val cell = dimen(R.dimen.chart_heatmap_cell_minimum)
        val gap = dimen(R.dimen.chart_heatmap_spacing)
        assertEquals(
            "a year of weeks at the smallest cell that is still a square, gaps included",
            WEEKS_IN_A_YEAR * cell + (WEEKS_IN_A_YEAR - 1) * gap,
            grid.measuredWidth
        )
        assertEquals("whole weeks are seven rows tall", 7 * cell + 6 * gap, grid.measuredHeight)

        val roomInCard = narrowWidth() - 2 * dimen(R.dimen.spacing_normal) - 2 * dimen(R.dimen.spacing_normal)
        val squeezedCell = (roomInCard - (WEEKS_IN_A_YEAR - 1) * gap) / WEEKS_IN_A_YEAR
        assertTrue(
            "the grid asks for ${grid.measuredWidth}px while the narrowest phone's card offers $roomInCard, " +
                "so the scroller is load-bearing: held to that width the squares would come out " +
                "$squeezedCell px wide, thinner than the $gap px gap between them, and a chart of squares " +
                "smaller than its own gaps is not a chart",
            grid.measuredWidth > roomInCard
        )
    }

    @Test
    fun `a proportion bar is never without its key and a bar chart never without its caption`() {
        val binding = screen()
        val context = themed()

        listOf(
            binding.healthBar to binding.healthLegend,
            binding.categoryBar to binding.categoryLegend
        ).forEach { (bar, legend) ->
            val card = bar.parent as ViewGroup
            assertSame(
                "a bar of five colours means nothing without its key beside it, so bar and key live in the " +
                    "one card and cannot be moved apart",
                card,
                legend.parent
            )
            assertEquals(
                "one bar per card: a second would be read as the same figure drawn twice",
                1,
                countOfType(card, StackedBarView::class.java)
            )
            assertEquals("and exactly one key for it", 1, countOfType(card, ChartLegendView::class.java))
            assertTrue(
                "the key comes under the bar, which is the order the eye follows",
                card.indexOfChild(legend) > card.indexOfChild(bar)
            )
        }

        val trendCard = binding.trendWorkChart.parent as ViewGroup
        assertEquals(
            "the trend is the same days read twice, so the card holds both bars",
            2,
            countOfType(trendCard, BarChartView::class.java)
        )
        assertSame(trendCard, binding.trendCompletionsChart.parent)
        assertEquals(
            "a chart without a caption is a shape rather than a figure, so the card that holds two bars " +
                "holds a line of words for each of them, beside its own title and the one sentence it says " +
                "when the period produced nothing",
            4,
            countOfType(trendCard, TextView::class.java)
        )
        assertFalse(
            "and that last sentence is not on screen while there are bars to read",
            binding.trendEmpty.isVisible
        )

        assertSame(
            "the caption of the work bar sits directly above it",
            binding.trendWorkChart,
            trendCard.getChildAt(trendCard.indexOfChild(binding.trendWorkCaption) + 1)
        )
        assertSame(
            "and the caption of the completions bar directly above that one",
            binding.trendCompletionsChart,
            trendCard.getChildAt(trendCard.indexOfChild(binding.trendCompletionsCaption) + 1)
        )
        assertEquals(
            context.getString(R.string.dashboard_trend_work), binding.trendWorkCaption.text.toString()
        )
        assertEquals(
            context.getString(R.string.dashboard_trend_completions),
            binding.trendCompletionsCaption.text.toString()
        )
        assertNotEquals(
            "the two captions cannot be the same words about two different figures",
            binding.trendWorkCaption.text.toString(),
            binding.trendCompletionsCaption.text.toString()
        )
        assertNotEquals(
            "and the chart of completions has to be told from the chart of work it sits under",
            ContextCompat.getColor(context, R.color.chart_bar),
            ContextCompat.getColor(context, R.color.chart_completions)
        )
    }

    /** The grid a year of whole weeks makes, every day of it having produced something. */
    private fun workedWeeks(weeks: Int): HeatmapGrid = HeatmapGrid(
        weeks = (0 until weeks * 7).map { day ->
            HeatmapCell(
                localDay = day.toLong(),
                seconds = 60L,
                completions = 0,
                level = 1,
                isFuture = false
            )
        }.chunked(7),
        busiestDaySeconds = 60L
    )

    @Test
    fun `every card is a filled material card and every gap inside it is a named spacing`() {
        val binding = screen()
        val filled = cardStyle(MaterialR.attr.materialCardViewFilledStyle)
        val themedDefault = cardStyle(MaterialR.attr.materialCardViewStyle)
        val spacingNormal = dimen(R.dimen.spacing_normal)

        assertNotEquals(
            "the filled look is a choice this layout makes: the card the theme hands out by default is a " +
                "bordered one, and a dashboard of borders reads as a form rather than as figures",
            themedDefault,
            filled
        )

        val cards = cardViews(binding)
        assertEquals("six cards, one per figure the period produced", 6, cards.size)
        cards.forEachIndexed { index, child ->
            assertTrue(
                "card #$index is a MaterialCardView, so it takes the theme's shape, colour and touch " +
                    "instead of a background this layout drew by hand",
                child is MaterialCardView
            )
            val card = child as MaterialCardView
            assertEquals(
                "card #$index wears the filled style the theme names, and that style is flat: a shadow here " +
                    "would be a page of cards floating over each other",
                filled.elevation,
                card.cardElevation,
                0.01f
            )
            assertEquals("card #$index has the filled style's border", filled.strokeWidth, card.strokeWidth)
            assertEquals(
                "card #$index has the filled style's surface colour",
                filled.background,
                card.cardBackgroundColor?.defaultColor
            )

            val content = card.getChildAt(0) as LinearLayout
            assertEquals(
                "the words inside card #$index are inset by the app's own normal spacing, not by a number " +
                    "this layout invented and the rest of the app does not have",
                spacingNormal,
                content.paddingLeft
            )
            assertEquals(spacingNormal, content.paddingTop)
            assertEquals(spacingNormal, content.paddingRight)
            assertEquals(spacingNormal, content.paddingBottom)

            val expectedGap = if (index == 0) dimen(R.dimen.spacing_small) else spacingNormal
            assertEquals(
                "the first card sits close under the periods and the rest are one normal spacing apart, so " +
                    "the rhythm of the page is the scale everything else uses",
                expectedGap,
                (card.layoutParams as ViewGroup.MarginLayoutParams).topMargin
            )
        }

        val column = binding.dashboardScroll.getChildAt(0) as LinearLayout
        assertEquals(
            "the column of cards is inset by the same normal spacing on both sides",
            spacingNormal,
            column.paddingStart
        )
        assertEquals(spacingNormal, column.paddingEnd)

        val namedSpacings = setOf(
            0,
            dimen(R.dimen.spacing_xs),
            dimen(R.dimen.spacing_small),
            dimen(R.dimen.spacing_normal),
            dimen(R.dimen.spacing_large),
            dimen(R.dimen.spacing_xl)
        )
        val offScale = verticalGaps(column).filter { it !in namedSpacings }.distinct()
        assertTrue(
            "every vertical gap between the cards and inside them is a spacing_* value; these are literals " +
                "that would drift from the rest of the app the moment the scale moves: $offScale",
            offScale.isEmpty()
        )
    }

    @Test
    fun `a busiest task row names the task on one line with its figure beneath`() {
        val binding = screen()
        val row = taskRow()
        val context = themed()

        assertEquals(
            "the container starts empty, so a previous period's tasks cannot stay listed under a new one",
            0,
            binding.topTasksContainer.childCount
        )
        assertEquals(
            "and it is a vertical column, because the rows arrive one per task rather than two in a row",
            LinearLayout.VERTICAL,
            binding.topTasksContainer.orientation
        )

        binding.topTasksContainer.addView(row.root)
        assertSame(
            "the row belongs to the card that lists the busiest tasks",
            binding.topTasksContainer,
            row.root.parent
        )

        assertEquals("a name and its figure, nothing else", 2, row.root.childCount)
        assertSame(row.root, row.dashboardTaskTitle.parent)
        assertSame(row.root, row.dashboardTaskMeta.parent)
        assertTrue(row.dashboardTaskTitle.isVisible)
        assertTrue(row.dashboardTaskMeta.isVisible)
        assertEquals(
            "a long task name must not push the measured figure off the card, so the name takes one line",
            1,
            row.dashboardTaskTitle.maxLines
        )
        assertEquals(
            "and it is cut at the end, where the reader stops, rather than across the middle of the words",
            TextUtils.TruncateAt.END,
            row.dashboardTaskTitle.ellipsize
        )
        assertTrue(
            "the figure comes after the name it belongs to",
            row.root.indexOfChild(row.dashboardTaskMeta) > row.root.indexOfChild(row.dashboardTaskTitle)
        )
        assertEquals(
            "the row is inset by the app's small spacing above and below, so rows separate without a height " +
                "nobody can change",
            dimen(R.dimen.spacing_small),
            row.root.paddingTop
        )
        assertEquals(dimen(R.dimen.spacing_small), row.root.paddingBottom)

        val width = narrowWidth()
        row.dashboardTaskTitle.text = "A task whose name goes on for as long as a user is allowed to type one"
        row.dashboardTaskMeta.text = context.getString(R.string.dashboard_task_meta, "12 h 40 min", "37 stretches")
        row.root.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        row.root.layout(0, 0, width, row.root.measuredHeight)
        assertEquals("the row fills the narrowest screen and asks for no more", width, row.root.measuredWidth)
        assertTrue(
            "so the name stays on the card",
            row.dashboardTaskTitle.left >= 0 && row.dashboardTaskTitle.right <= width
        )
        assertTrue(
            "and so does the figure under it",
            row.dashboardTaskMeta.left >= 0 && row.dashboardTaskMeta.right <= width
        )
    }

    @Test
    fun `the colours the cards depend on exist and none of them is another one's twin`() {
        val context = themed()

        val health = listOf(
            R.color.health_overdue,
            R.color.health_due_today,
            R.color.health_due_week,
            R.color.health_due_later,
            R.color.health_without_deadline
        ).map { ContextCompat.getColor(context, it) }
        assertEquals(
            "a health bar whose two buckets wear the same colour shows a figure the reader cannot attribute " +
                "to anything, so the five states need five colours",
            5,
            health.toSet().size
        )

        val shades = listOf(
            R.color.chart_heatmap_none,
            R.color.chart_heatmap_1,
            R.color.chart_heatmap_2,
            R.color.chart_heatmap_3,
            R.color.chart_heatmap_4
        ).map { ContextCompat.getColor(context, it) }
        assertEquals(
            "a scale two of whose levels look alike is not a scale: a quiet week and a busy one must differ",
            5,
            shades.toSet().size
        )
        val accent = shades.last()
        shades.drop(1).forEachIndexed { index, shade ->
            assertEquals(
                "level ${index + 1} is the same hue as the busiest day, only less filled in — the grid is " +
                    "read by how full a square looks, which survives a colour-blind eye and a printed page",
                Color.rgb(Color.red(accent), Color.green(accent), Color.blue(accent)),
                Color.rgb(Color.red(shade), Color.green(shade), Color.blue(shade))
            )
        }
        val alphas = shades.drop(1).map { Color.alpha(it) }
        alphas.zipWithNext().forEachIndexed { index, (lower, higher) ->
            assertTrue(
                "the shades get strictly more solid as the days get busier, but level ${index + 1} is $lower " +
                    "and level ${index + 2} is $higher",
                lower < higher
            )
        }

        val bar = ContextCompat.getColor(context, R.color.chart_bar)
        assertNotEquals(
            "a proportion bar is its parts read against its track, so the two cannot match",
            ContextCompat.getColor(context, R.color.chart_track),
            bar
        )
        assertNotEquals(
            "the axis is where the bars stand, not another bar",
            ContextCompat.getColor(context, R.color.chart_axis),
            bar
        )
        assertNotEquals(
            "work with no category is not the accent, or it reads as a category of its own",
            ContextCompat.getColor(context, R.color.chart_category_none),
            bar
        )
        assertNotEquals(
            "and the grid's quiet end is a shade of its own, so an empty day is a statement rather than a hole",
            ContextCompat.getColor(context, R.color.chart_heatmap_none),
            ContextCompat.getColor(context, R.color.chart_heatmap_1)
        )
    }

    @Test
    fun `the text appearances the dashboard asks for are ones its theme answers`() {
        val context = themed()
        val asked = listOf(
            "textAppearanceHeadline5" to MaterialR.attr.textAppearanceHeadline5,
            "textAppearanceSubtitle1" to MaterialR.attr.textAppearanceSubtitle1,
            "textAppearanceBody1" to MaterialR.attr.textAppearanceBody1,
            "textAppearanceBody2" to MaterialR.attr.textAppearanceBody2,
            "textAppearanceCaption" to MaterialR.attr.textAppearanceCaption
        )

        val styles = asked.map { (name, attr) ->
            val value = TypedValue()
            assertTrue(
                "?attr/$name has to be an attribute Theme.TaskPlanner answers: a reference the theme cannot " +
                    "resolve is not an error the build reports, it is text that comes back at the platform's " +
                    "default size — which is how this screen's headline lost its size once already",
                context.theme.resolveAttribute(attr, value, true)
            )
            assertNotEquals("?attr/$name resolves to a name but to no style", 0, value.resourceId)
            value.resourceId
        }
        assertEquals(
            "five roles need five different styles, or the dashboard has no hierarchy left for a reader to " +
                "follow",
            5,
            styles.toSet().size
        )

        // The same attributes as the inflated screen resolves them, on the views that carry them.
        val binding = screen()
        assertTrue(
            "the headline is the largest thing on the screen, or the figure the page is about is not one",
            binding.dashboardHeadline.paint.textSize > binding.dashboardRate.paint.textSize
        )
        assertTrue(
            "and the sizes step down through the card instead of all landing on one default",
            binding.dashboardRate.paint.textSize > binding.dashboardHeadlineDetails.paint.textSize
        )
        assertTrue(
            "the fine print under a figure is smaller than the figure's own line",
            binding.dashboardHeadlineDetails.paint.textSize > binding.dashboardRateDetails.paint.textSize
        )
        assertTrue(
            "the captions under the bars are among the smallest words the screen uses",
            binding.trendWorkCaption.paint.textSize <= binding.dashboardRateDetails.paint.textSize
        )
    }

    private companion object {
        /** A year of whole weeks: the widest grid the activity card can be asked for. */
        const val WEEKS_IN_A_YEAR = 52

        /** The narrowest screen the app supports, in dp. */
        const val NARROWEST_SCREEN_DP = 320

        /** The value an attribute is given when the style does not set it, so a mismatch shows up. */
        const val NO_VALUE = -1f
    }
}
