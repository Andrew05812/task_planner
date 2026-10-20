package com.taskplanner.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import com.taskplanner.R
import com.taskplanner.data.statistics.HeatmapCell
import com.taskplanner.data.statistics.HeatmapGrid
import kotlin.math.max
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowCanvas

/**
 * The chart views, drawn and asked what they put on the canvas.
 *
 * [ChartGeometryTest] settles where a mark goes as arithmetic. What only a drawn view can settle is the
 * part the view owns: that a bar of nothing is not painted anyway, that a square of level three wears the
 * colour level three was given, that a day which has not happened leaves no mark at all, and that a chart
 * with no data still draws the line its bars would have stood on. Pixels are not an assertion in this
 * environment — the canvas records the marks it was handed rather than the bits they would have made — so
 * every check below is made against that history, in the order the marks were painted.
 */
@RunWith(RobolectricTestRunner::class)
class ChartViewDrawTest {

    private lateinit var themed: Context

    @Before
    fun setUp() {
        val app: Context = ApplicationProvider.getApplicationContext()
        // The screen always builds these inside the activity's theme: the chart colours are theme
        // attributes, and a view constructed without one is a view nobody can see.
        themed = ContextThemeWrapper(app, R.style.Theme_TaskPlanner)
    }

    /** Measures, lays out and draws [view] at [width] by [height], and returns what its canvas recorded. */
    private fun draw(view: View, width: Int, height: Int): ShadowCanvas {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)

        val canvas = Canvas(
            Bitmap.createBitmap(
                max(view.measuredWidth, 1),
                max(view.measuredHeight, 1),
                Bitmap.Config.ARGB_8888
            )
        )
        view.draw(canvas)
        return shadowOf(canvas)
    }

    /**
     * Two whole weeks. The first starts with a day that produced nothing and is level 1 after it; the
     * second runs one cell of every level, and its last two days are after today.
     */
    private fun twoWeekGrid(): HeatmapGrid {
        val first = (0 until 7).map { day ->
            HeatmapCell(localDay = day.toLong(), seconds = if (day == 0) 0L else 600L, completions = 0,
                level = if (day == 0) 0 else 1, isFuture = false)
        }
        val second = (0 until 7).map { day ->
            val isFuture = day >= 5
            HeatmapCell(
                localDay = (7 + day).toLong(),
                seconds = if (isFuture) 0L else (day + 1) * 900L,
                completions = 0,
                level = if (isFuture) 0 else day,
                isFuture = isFuture
            )
        }
        return HeatmapGrid(listOf(first, second), busiestDaySeconds = 4_500L)
    }

    private fun labelsOf(row: View): List<String> {
        val group = row as ViewGroup
        return (0 until group.childCount)
            .map { group.getChildAt(it) }
            .filterIsInstance<TextView>()
            .map { it.text.toString() }
    }

    // region bars

    @Test
    fun `the tallest bar fills the plot and half the work is half the height`() {
        val chart = BarChartView(themed)
        chart.setValues(listOf(4_000L, 2_000L))

        val canvas = draw(chart, width = 300, height = 140)

        assertEquals(2, canvas.roundRectPaintHistoryCount)
        // The plot is the view's 140 less half the axis's thickness, so a full day is 139.5 tall.
        val tallest = canvas.getDrawnRoundRect(0)
        val half = canvas.getDrawnRoundRect(1)
        assertEquals(0f, tallest.top, 0.01f)
        assertEquals(139.5f, tallest.bottom, 0.01f)
        assertEquals(69.75f, half.top, 0.01f)
        assertEquals("a bar stands on the axis, not below it", tallest.bottom, half.bottom, 0.01f)
        assertTrue(
            "the second column sits to the right of the first",
            half.left > tallest.right
        )
    }

    @Test
    fun `a day that produced nothing is not painted`() {
        val chart = BarChartView(themed)
        chart.setValues(listOf(4_000L, 0L, 2_000L))

        val canvas = draw(chart, width = 300, height = 140)

        assertEquals(
            "an empty day is a gap the reader sees, not a mark of its own",
            2,
            canvas.roundRectPaintHistoryCount
        )
        assertEquals(0f, canvas.getDrawnRoundRect(0).top, 0.01f)
        assertEquals(69.75f, canvas.getDrawnRoundRect(1).top, 0.01f)
    }

    @Test
    fun `a period with no work at all draws its axis and no bars`() {
        val chart = BarChartView(themed)
        chart.setValues(listOf(0L, 0L, 0L))

        val canvas = draw(chart, width = 300, height = 140)

        assertEquals("nothing was measured, so nothing is drawn", 0, canvas.roundRectPaintHistoryCount)
        assertEquals(
            "the axis is what tells the reader this chart is empty rather than broken",
            1,
            canvas.rectPaintHistoryCount
        )
        val axis = canvas.getDrawnRect(0)
        assertEquals(
            "a height normalised against no tallest day would be a full bar",
            axis.top,
            axis.bottom,
            0.01f
        )
        assertEquals(0f, axis.left, 0.01f)
        assertEquals(300f, axis.right, 0.01f)
    }

    @Test
    fun `a chart given no days still shows the line they would have stood on`() {
        val canvas = draw(BarChartView(themed), width = 300, height = 140)

        assertEquals(0, canvas.roundRectPaintHistoryCount)
        assertEquals(1, canvas.rectPaintHistoryCount)
    }

    @Test
    fun `a bar recoloured for completions is painted in the colour it was given`() {
        val chart = BarChartView(themed)
        chart.setBarColor(Color.rgb(10, 20, 30))
        chart.setValues(listOf(1L))

        val canvas = draw(chart, width = 100, height = 40)

        assertEquals(Color.rgb(10, 20, 30), canvas.getDrawnRoundRect(0).paint.color)
    }

    @Test
    fun `the chart names its own height when the screen does not`() {
        val chart = BarChartView(themed)
        chart.measure(
            View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )

        assertEquals(
            "the height a bar chart asks for is the one the dimens name",
            chart.resources.getDimensionPixelSize(R.dimen.chart_bar_height),
            chart.measuredHeight
        )
    }

    // endregion

    // region activity grid

    @Test
    fun `a day that has not happened leaves no square at all`() {
        val grid = HeatmapView(themed)
        grid.setGrid(twoWeekGrid())

        val canvas = draw(grid, width = 100, height = 400)

        // Fourteen squares of two whole weeks, two of them after today: absent rather than shaded empty.
        assertEquals(12, canvas.roundRectPaintHistoryCount)
    }

    @Test
    fun `a day that happened and produced nothing is still painted`() {
        val grid = HeatmapView(themed)
        grid.setGrid(twoWeekGrid())

        val canvas = draw(grid, width = 100, height = 400)

        assertEquals(
            "level zero is a quiet day, which is a statement; a future day is not a statement",
            grid.colorForLevel(0),
            canvas.getDrawnRoundRect(0).paint.color
        )
    }

    @Test
    fun `a square wears the colour its level was given`() {
        val grid = HeatmapView(themed)
        grid.setGrid(twoWeekGrid())

        val canvas = draw(grid, width = 100, height = 400)

        // The second week runs levels 0 to 4 across its five real days, so those squares are the scale.
        val colours = (0 until 5).map { day ->
            canvas.getDrawnRoundRect(7 + day).paint.color
        }
        assertEquals(
            listOf(
                grid.colorForLevel(0), grid.colorForLevel(1), grid.colorForLevel(2),
                grid.colorForLevel(3), grid.colorForLevel(4)
            ),
            colours
        )
    }

    @Test
    fun `every level of the scale is a colour the eye can tell from the others`() {
        val grid = HeatmapView(themed)

        assertEquals(
            "a quiet day and a busy one cannot look the same",
            5,
            (0 until 5).map { grid.colorForLevel(it) }.toSet().size
        )
        assertNotEquals(
            ContextCompat.getColor(themed, R.color.chart_heatmap_none),
            ContextCompat.getColor(themed, R.color.chart_heatmap_4)
        )
    }

    @Test
    fun `a year of weeks keeps readable squares and asks to be scrolled`() {
        val grid = HeatmapView(themed)
        val weeks = (0 until 52).map { week ->
            (0 until 7).map { day ->
                HeatmapCell((week * 7 + day).toLong(), 60L, 0, level = 1, isFuture = false)
            }
        }
        grid.setGrid(HeatmapGrid(weeks, busiestDaySeconds = 60L))

        // The width a horizontal scroller gives its child: no constraint at all.
        grid.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )

        val cell = grid.resources.getDimensionPixelSize(R.dimen.chart_heatmap_cell_minimum)
        val spacing = grid.resources.getDimensionPixelSize(R.dimen.chart_heatmap_spacing)
        assertEquals(52 * cell + 51 * spacing, grid.measuredWidth)
        assertEquals(7 * cell + 6 * spacing, grid.measuredHeight)
        assertTrue(
            "a square smaller than the gap between squares is not a chart, so the grid takes the room it needs",
            grid.measuredWidth > 200
        )
    }

    @Test
    fun `a grid with nothing in it draws nothing rather than a field of empty squares`() {
        val canvas = draw(HeatmapView(themed), width = 200, height = 200)

        assertEquals(0, canvas.roundRectPaintHistoryCount)
    }

    @Test
    fun `a grid taken away leaves no squares behind`() {
        val grid = HeatmapView(themed)
        grid.setGrid(twoWeekGrid())
        draw(grid, width = 100, height = 400)

        grid.setGrid(null)
        val cleared = draw(grid, width = 100, height = 400)

        assertEquals(
            "the marks of a grid that is gone must not stay on the screen",
            0,
            cleared.roundRectPaintHistoryCount
        )
    }

    @Test
    fun `weeks are columns and days are rows`() {
        val grid = HeatmapView(themed)
        grid.setGrid(twoWeekGrid())

        val canvas = draw(grid, width = 100, height = 400)

        // Two weeks across 100dp with a 3dp gap: each square is 48.5 wide, and the next column starts a gap
        // later while the next day of the same week starts a gap lower down.
        val monday = canvas.getDrawnRoundRect(0)
        val tuesday = canvas.getDrawnRoundRect(1)
        val nextWeek = canvas.getDrawnRoundRect(7)
        assertEquals(0f, monday.left, 0.01f)
        assertEquals(0f, monday.top, 0.01f)
        assertEquals(51.5f, tuesday.top, 0.01f)
        assertEquals(0f, tuesday.left, 0.01f)
        assertEquals(51.5f, nextWeek.left, 0.01f)
        assertEquals(0f, nextWeek.top, 0.01f)
        assertEquals(48.5f, monday.right - monday.left, 0.01f)
    }

    // endregion

    // region proportion bar

    @Test
    fun `the parts of a bar add up to the whole bar`() {
        val bar = StackedBarView(themed)
        bar.setSegments(listOf(1L, 1L, 2L), intArrayOf(Color.RED, Color.GREEN))

        val canvas = draw(bar, width = 100, height = 30)

        // One track under three parts, the last of which closes at the right edge rather than by its share.
        assertEquals(1, canvas.roundRectPaintHistoryCount)
        assertEquals(3, canvas.rectPaintHistoryCount)
        assertEquals(0f, canvas.getDrawnRect(0).left, 0.01f)
        assertEquals(25f, canvas.getDrawnRect(0).right, 0.01f)
        assertEquals(100f, canvas.getDrawnRect(2).right, 0.01f)
        assertEquals(Color.RED, canvas.getDrawnRect(0).paint.color)
        assertEquals(Color.GREEN, canvas.getDrawnRect(1).paint.color)
    }

    @Test
    fun `a share with nothing in it has no part`() {
        val bar = StackedBarView(themed)
        bar.setSegments(listOf(0L, 5L), intArrayOf(Color.RED, Color.GREEN))

        val canvas = draw(bar, width = 100, height = 30)

        assertEquals(
            "a hairline would be a claim that this category exists",
            1,
            canvas.rectPaintHistoryCount
        )
        assertEquals(100f, canvas.getDrawnRect(0).right, 0.01f)
        assertEquals(Color.GREEN, canvas.getDrawnRect(0).paint.color)
    }

    @Test
    fun `a whole made of nothing is only its track`() {
        val bar = StackedBarView(themed)
        bar.setSegments(listOf(0L, 0L), intArrayOf(Color.RED, Color.GREEN))

        val canvas = draw(bar, width = 100, height = 30)

        assertEquals(0, canvas.rectPaintHistoryCount)
        assertEquals(
            "the track is what tells the reader this is a bar with nothing in it, not a missing view",
            1,
            canvas.roundRectPaintHistoryCount
        )
    }

    @Test
    fun `a part with no colour of its own takes the accent`() {
        val bar = StackedBarView(themed)
        bar.setSegments(listOf(1L, 1L), intArrayOf(Color.RED))

        val canvas = draw(bar, width = 100, height = 30)

        assertEquals(Color.RED, canvas.getDrawnRect(0).paint.color)
        assertEquals(
            ContextCompat.getColor(themed, R.color.chart_bar),
            canvas.getDrawnRect(1).paint.color
        )
    }

    // endregion

    // region legend

    @Test
    fun `the legend lists one row per entry, in the order it was given`() {
        val legend = ChartLegendView(themed)
        legend.setEntries(
            listOf(
                ChartLegendView.Entry("Late", "2 tasks", Color.RED),
                ChartLegendView.Entry("Due today", null, Color.GREEN),
                ChartLegendView.Entry("No date", "9 tasks", Color.BLUE)
            )
        )

        assertEquals(3, legend.childCount)
        assertEquals(listOf("Late", "2 tasks"), labelsOf(legend.getChildAt(0)))
        assertEquals(listOf("Due today"), labelsOf(legend.getChildAt(1)))
        assertEquals(listOf("No date", "9 tasks"), labelsOf(legend.getChildAt(2)))
    }

    @Test
    fun `a legend chip carries the colour of the row it belongs to`() {
        val legend = ChartLegendView(themed)
        legend.setEntries(
            listOf(
                ChartLegendView.Entry("Late", null, Color.RED),
                ChartLegendView.Entry("Today", null, Color.GREEN)
            )
        )

        val canvas = draw(legend, width = 200, height = 60)

        assertEquals("one swatch per row", legend.childCount, canvas.roundRectPaintHistoryCount)
        assertEquals(Color.RED, canvas.getDrawnRoundRect(0).paint.color)
        assertEquals(Color.GREEN, canvas.getDrawnRoundRect(1).paint.color)
    }

    @Test
    fun `a legend with nothing to explain shows nothing`() {
        val legend = ChartLegendView(themed)
        legend.setEntries(
            listOf(ChartLegendView.Entry("Late", null, Color.RED), ChartLegendView.Entry("Today", null, Color.GREEN))
        )
        legend.setEntries(emptyList())

        assertEquals(0, legend.childCount)
        assertTrue(legend.entries.isEmpty())
    }

    @Test
    fun `only a row that was given somewhere to go can be tapped`() {
        val opened = mutableListOf<String>()
        val legend = ChartLegendView(themed)
        legend.setEntries(
            listOf(
                ChartLegendView.Entry("Home", "2 h", Color.RED, onClick = { opened.add("Home") }),
                ChartLegendView.Entry("No category", "1 h", Color.GRAY)
            )
        )

        val row = legend.getChildAt(0)
        assertTrue("a row with a destination asks to be tapped", row.isClickable)
        row.performClick()
        assertEquals(listOf("Home"), opened)

        assertFalse(
            "a row that is only a key to the chart does not press",
            legend.getChildAt(1).isClickable
        )
    }

    // endregion
}
