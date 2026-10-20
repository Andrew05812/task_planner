package com.taskplanner.ui.components

/**
 * Where a chart puts its marks, settled before a canvas is touched.
 *
 * A view that works out its own geometry while drawing cannot be tested without one: "how tall is the bar
 * for a day that produced nothing" has to be answered in pixels, and pixels are not an assertion. Keeping
 * the arithmetic here, and in figures that belong to no graphics class, is what lets the promises a chart
 * makes be checked as arithmetic — the same way the day view's blocks are
 * ([com.taskplanner.ui.calendar.CalendarDayLayout]) — and leaves the view with nothing to do but paint
 * what it was given.
 *
 * Three rules, all of them about the same failure: a picture that invents data.
 *
 *  * **Nothing is drawn for nothing.** A bar of height zero, a segment of width zero, a square for a day
 *    that has not happened — none of them is painted. The alternative is a chart whose empty parts carry
 *    as much weight as its full ones.
 *  * **Nothing divides by zero.** A period with no measured work has no tallest value, so every bar comes
 *    back empty rather than as a row of full-height columns, which is what a normalisation against zero
 *    draws.
 *  * **Sizes are chosen from the space, and a floor beats a sub-pixel mark.** An activity grid is a fixed
 *    number of weeks wide; squeezing eighteen or fifty-two columns into one phone width would make each
 *    square smaller than the line that separates it. Past that point the grid keeps its minimum cell and
 *    asks for room to scroll instead of becoming unreadable.
 */
object ChartGeometry {

    /**
     * An area of a chart, in the canvas's own units.
     *
     * A figure of this layout's vocabulary rather than `android.graphics.RectF`, because the arithmetic
     * below has to be testable as arithmetic: a graphics class is only answerable inside a graphics
     * environment, and a bar chart's heights are not.
     */
    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top

        /** Whether the area is too thin or too short to paint: nothing measured is nothing drawn. */
        val isEmpty: Boolean get() = width <= 0f || height <= 0f

        val centerX: Float get() = (left + right) / 2f
        val centerY: Float get() = (top + bottom) / 2f

        /** Whether [x] and [y] fall inside it, which is how a test asks where a mark was put. */
        fun contains(x: Float, y: Float): Boolean =
            x >= left && x <= right && y >= top && y <= bottom
    }

    /** One column of a bar chart, with the day it stands for. */
    data class Bar(val index: Int, val bounds: Box)

    /**
     * The columns for [values], spread across a plot of [plotWidth] by [plotHeight].
     *
     * Heights are shares of the largest value, never of the average and never of a rounded "typical" day:
     * the one claim a bar makes is that this bar is twice as tall as that one. A value that is not a
     * positive number gets no height at all, and a plot that has no height, no width, or no values to
     * place returns nothing rather than an empty bar each.
     */
    fun bars(
        values: List<Long>,
        plotLeft: Float = 0f,
        plotTop: Float = 0f,
        plotWidth: Float,
        plotHeight: Float,
        gapRatio: Float = DEFAULT_GAP_RATIO
    ): List<Bar> {
        if (values.isEmpty() || plotWidth <= 0f || plotHeight <= 0f) return emptyList()

        val tallest = values.maxOf { it.coerceAtLeast(0L) }
        val slot = plotWidth / values.size
        val width = slot * (1f - gapRatio.coerceIn(0f, 0.9f))
        val baseline = plotTop + plotHeight

        return values.mapIndexed { index, value ->
            val height = if (tallest <= 0L || value <= 0L) 0f
            else (value.toDouble() / tallest.toDouble()).toFloat() * plotHeight
            val left = plotLeft + index * slot + (slot - width) / 2f
            Bar(index, Box(left, baseline - height, left + width, baseline))
        }
    }

    /**
     * The edge of one square of an activity grid.
     *
     * A grid is read by columns — a week is a vertical strip, and the eye follows a column to compare
     * Mondays — so a cell is placed by its week and its day within that week, from the top row down.
     */
    fun cellBounds(
        week: Int,
        dayInWeek: Int,
        left: Float = 0f,
        top: Float = 0f,
        cellSize: Float,
        spacing: Float
    ): Box {
        val cellLeft = left + week * (cellSize + spacing)
        val cellTop = top + dayInWeek * (cellSize + spacing)
        return Box(cellLeft, cellTop, cellLeft + cellSize, cellTop + cellSize)
    }

    /**
     * How wide one square of a grid of [columns] columns can be in [availableWidth].
     *
     * The grid fills the space while its squares stay square and stay at least [minimumCell] wide; below
     * that, the number of columns wins over the width, because a chart whose marks are smaller than the
     * gap between them is not a chart. The caller then measures itself to [gridWidth] and lets the screen
     * scroll the overflow.
     */
    fun cellSize(availableWidth: Float, columns: Int, spacing: Float, minimumCell: Float): Float {
        if (columns <= 0) return 0f
        val minimum = minimumCell.coerceAtLeast(0f)
        if (availableWidth <= 0f) return minimum
        val fit = (availableWidth - spacing * (columns - 1)) / columns
        return maxOf(fit, minimum)
    }

    /** The width a grid of [columns] columns needs at [cellSize]. */
    fun gridWidth(columns: Int, cellSize: Float, spacing: Float): Float =
        if (columns <= 0) 0f else columns * cellSize + (columns - 1) * spacing

    /** The height a grid of [rows] rows needs at [cellSize]. */
    fun gridHeight(rows: Int, cellSize: Float, spacing: Float): Float =
        if (rows <= 0) 0f else rows * cellSize + (rows - 1) * spacing

    /** One part of a proportion bar, and which value it came from. */
    data class Segment(val index: Int, val bounds: Box)

    /**
     * The parts of a bar that shows shares of a whole across [width].
     *
     * Shares are of the sum, so the bar says "this kind of task is a third of what is open" and the parts
     * add up to the full bar. A value that is zero or negative has no part — it is not padded with a hair
     * line, because a hairline is a claim that something exists there. When everything is zero the bar has
     * no parts at all, and the caller draws its empty track instead.
     */
    fun segments(
        values: List<Long>,
        left: Float = 0f,
        top: Float = 0f,
        width: Float,
        height: Float
    ): List<Segment> {
        if (values.isEmpty() || width <= 0f || height <= 0f) return emptyList()
        val total = values.sumOf { it.coerceAtLeast(0L) }
        if (total <= 0L) return emptyList()

        val drawn = values.indices.filter { values[it] > 0L }
        val result = ArrayList<Segment>(drawn.size)
        var cursor = left
        drawn.forEachIndexed { position, index ->
            // The part that closes the bar is closed at its right edge rather than by its own share, so the
            // rounding of several fractions cannot leave a seam of background at the end of it.
            val right = if (position == drawn.lastIndex) left + width
            else cursor + (values[index].toDouble() / total.toDouble()).toFloat() * width
            result.add(Segment(index, Box(cursor, top, right, top + height)))
            cursor = right
        }
        return result
    }

    private const val DEFAULT_GAP_RATIO = 0.35f
}
