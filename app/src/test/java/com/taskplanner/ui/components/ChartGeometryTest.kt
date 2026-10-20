package com.taskplanner.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where a chart puts its marks, checked as arithmetic.
 *
 * Every number here is computed by hand rather than read back from the function under test: a bar chart
 * whose tallest column is the plot's height, a grid whose squares stay square, a proportion bar whose
 * parts add up to the whole. Those are the promises a reader relies on, and none of them can be seen in a
 * screenshot — a chart that is quietly half as tall as it should be looks exactly like one that is not.
 */
class ChartGeometryTest {

    private fun bars(
        values: List<Long>,
        plotWidth: Float = 100f,
        plotHeight: Float = 50f,
        gapRatio: Float = 0.5f
    ) = ChartGeometry.bars(values, plotWidth = plotWidth, plotHeight = plotHeight, gapRatio = gapRatio)

    @Test
    fun `the busiest day is as tall as the plot and nothing overshoots it`() {
        val columns = bars(listOf(1_800L, 3_600L, 900L))

        assertEquals(3, columns.size)
        assertEquals("half the busiest is half as tall", 25f, columns[0].bounds.height, 0.001f)
        assertEquals(50f, columns[1].bounds.height, 0.001f)
        assertEquals(12.5f, columns[2].bounds.height, 0.001f)
        columns.forEach { column ->
            assertTrue("a bar cannot start above the plot", column.bounds.top >= 0f)
            assertTrue("a bar stands on the baseline", column.bounds.bottom <= 50f)
        }
    }

    @Test
    fun `days line up one per slot and stay inside the plot`() {
        val columns = bars(listOf(10L, 20L, 30L, 40L))

        assertEquals(listOf(0, 1, 2, 3), columns.map { it.index })
        // Four days across 100 points of width with half of each slot painted: columns 12.5 wide, centred
        // in slots of 25, so the first starts at 6.25 and each next one twenty-five points further on.
        assertEquals(6.25f, columns.first().bounds.left, 0.001f)
        assertEquals(31.25f, columns[1].bounds.left, 0.001f)
        assertEquals(93.75f, columns.last().bounds.right, 0.001f)
        columns.forEach { assertTrue(it.bounds.width <= 25f) }
    }

    @Test
    fun `a period with no work draws no columns`() {
        // Dividing by the tallest value of an empty period is the failure this guards: the answer is that
        // there is no scale, so there is nothing to draw — not a row of full-height bars.
        bars(listOf(0L, 0L, 0L)).forEach { assertTrue("a zero day is not drawn", it.bounds.isEmpty) }
        assertTrue(bars(emptyList()).isEmpty())
        assertTrue(bars(listOf(60L), plotWidth = 0f).isEmpty())
        assertTrue(bars(listOf(60L), plotHeight = 0f).isEmpty())
    }

    @Test
    fun `a negative figure is nothing rather than a bar growing the wrong way`() {
        val columns = bars(listOf(-500L, 1_000L))

        assertTrue("nothing measured downwards is not a claim about work", columns.first().bounds.isEmpty)
        assertEquals(50f, columns[1].bounds.height, 0.001f)
    }

    @Test
    fun `squares fill the room they are given and never go below the size they can be read at`() {
        // Four columns across 103 points with a 1-point gap between them: 100 of grid and 3 of gaps.
        assertEquals(25f, ChartGeometry.cellSize(103f, columns = 4, spacing = 1f, minimumCell = 0f), 0.001f)

        // Fifty-two weeks in a phone's width cannot each have a readable square, so the minimum wins and
        // the grid asks for room instead of becoming a texture.
        val narrow = ChartGeometry.cellSize(200f, columns = 52, spacing = 1f, minimumCell = 9f)
        assertEquals(9f, narrow, 0.001f)
        assertEquals(52f * 9f + 51f, ChartGeometry.gridWidth(52, narrow, 1f), 0.001f)
        assertTrue(
            "the grid then needs more width than it was offered, which is what scrolling is for",
            ChartGeometry.gridWidth(52, narrow, 1f) > 200f
        )
    }

    @Test
    fun `an empty grid needs no room`() {
        assertEquals(0f, ChartGeometry.cellSize(100f, columns = 0, spacing = 1f, minimumCell = 9f), 0.001f)
        assertEquals(0f, ChartGeometry.gridWidth(0, 10f, 1f), 0.001f)
        assertEquals(0f, ChartGeometry.gridHeight(0, 10f, 1f), 0.001f)
        // A container that has not been measured yet must not make the squares negative.
        assertEquals(0f, ChartGeometry.cellSize(-40f, columns = 4, spacing = 1f, minimumCell = 0f), 0.001f)
    }

    @Test
    fun `a week is a column of seven squares and the day walks down it`() {
        assertEquals(
            ChartGeometry.Box(0f, 0f, 10f, 10f),
            ChartGeometry.cellBounds(week = 0, dayInWeek = 0, cellSize = 10f, spacing = 2f)
        )
        assertEquals(
            "the next day is one gap lower down the same column",
            ChartGeometry.Box(0f, 12f, 10f, 22f),
            ChartGeometry.cellBounds(week = 0, dayInWeek = 1, cellSize = 10f, spacing = 2f)
        )
        assertEquals(
            "the next week is a whole column further right",
            ChartGeometry.Box(12f, 0f, 22f, 10f),
            ChartGeometry.cellBounds(week = 1, dayInWeek = 0, cellSize = 10f, spacing = 2f)
        )

        // Seven rows of ten with six gaps of two is the height the card has to give the grid.
        assertEquals(82f, ChartGeometry.gridHeight(7, 10f, 2f), 0.001f)
    }

    @Test
    fun `a square contains the point a reader would aim at and not the gap beside it`() {
        val cell = ChartGeometry.cellBounds(week = 1, dayInWeek = 2, cellSize = 10f, spacing = 2f)

        assertTrue(cell.contains(cell.centerX, cell.centerY))
        assertFalse("the gap between columns belongs to no day", cell.contains(cell.right + 1f, cell.centerY))
    }

    @Test
    fun `the parts of a proportion bar add up to the bar`() {
        val parts = ChartGeometry.segments(listOf(50L, 30L, 20L), width = 100f, height = 10f)

        assertEquals(3, parts.size)
        assertEquals(0f, parts[0].bounds.left, 0.001f)
        assertEquals(50f, parts[0].bounds.right, 0.001f)
        assertEquals(80f, parts[1].bounds.right, 0.001f)
        assertEquals(
            "the last part closes the bar, so no seam of background is left at its end",
            100f, parts[2].bounds.right, 0.001f
        )
        assertEquals(10f, parts.last().bounds.bottom, 0.001f)
    }

    @Test
    fun `a share that is zero has no part`() {
        val parts = ChartGeometry.segments(listOf(0L, 5L, 0L, 5L), width = 60f, height = 8f)

        // A hairline where a bucket holds nothing would be a claim that something is there, so the empty
        // buckets are absent — and the indices still name the row each part belongs to.
        assertEquals(listOf(1, 3), parts.map { it.index })
        assertEquals(30f, parts.first().bounds.right, 0.001f)
        assertEquals(60f, parts.last().bounds.right, 0.001f)
    }

    @Test
    fun `a whole of nothing has no parts`() {
        assertTrue(ChartGeometry.segments(listOf(0L, 0L), width = 100f, height = 10f).isEmpty())
        assertTrue(ChartGeometry.segments(listOf(-3L, 0L), width = 100f, height = 10f).isEmpty())
        assertTrue(ChartGeometry.segments(emptyList(), width = 100f, height = 10f).isEmpty())
        assertTrue(ChartGeometry.segments(listOf(4L), width = 0f, height = 10f).isEmpty())
        assertTrue(ChartGeometry.segments(listOf(4L), width = 40f, height = 0f).isEmpty())
    }

    @Test
    fun `thirds close the bar instead of leaving a rounding seam`() {
        val parts = ChartGeometry.segments(listOf(1L, 1L, 1L), width = 100f, height = 4f)

        // A third of 100 is not a float, and three rounded thirds would stop short of the end.
        assertEquals(100f, parts.last().bounds.right, 0f)
        assertEquals(parts[0].bounds.right, parts[1].bounds.left, 0.001f)
        assertEquals(parts[1].bounds.right, parts[2].bounds.left, 0.001f)
    }
}
