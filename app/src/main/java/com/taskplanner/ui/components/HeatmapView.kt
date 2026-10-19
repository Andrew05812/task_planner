package com.taskplanner.ui.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.taskplanner.R
import com.taskplanner.data.statistics.HeatmapGrid
import kotlin.math.max

/**
 * The activity grid: whole weeks of squares, oldest column on the left, today inside the last one.
 *
 * Two things a habit picture gets wrong when it is drawn in a hurry, and both are answered here rather
 * than in the data. A square for a day that has not happened yet is not drawn at all — an empty one would
 * read as a day the user did nothing on, which is a claim about the future. And the shade of a square is
 * the level the arithmetic chose, not a colour picked at the moment of painting, so the same amount of
 * work looks the same in every corner of the grid.
 *
 * The grid sizes itself to the squares rather than to the screen: [ChartGeometry.cellSize] keeps a cell
 * at least [R.dimen.chart_heatmap_cell_minimum] wide, and when the weeks need more room than the card has,
 * the view asks for that room and the screen scrolls. Squeezing a year of work into squares smaller than
 * the gaps between them would be the chart equivalent of rounding a figure away.
 */
class HeatmapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    /** Shades for level 0 to level 4, read from the theme's palette. */
    private val levelColors = IntArray(LEVEL_COUNT) { index ->
        ContextCompat.getColor(context, LEVEL_COLOR_RESOURCES[index])
    }

    private val spacing = resources.getDimension(R.dimen.chart_heatmap_spacing)
    private val minimumCell = resources.getDimension(R.dimen.chart_heatmap_cell_minimum)
    private val corner = resources.getDimension(R.dimen.chart_heatmap_corner)

    private var grid: HeatmapGrid? = null

    /**
     * Reused for every square, because a grid of a few hundred of them is not worth a few hundred
     * allocations on a drawing pass.
     */
    private val rect = RectF()

    /** Overrides one level's shade, so the screen can make "no work" agree with its card. */
    fun setLevelColor(level: Int, color: Int) {
        if (level in 0 until LEVEL_COUNT) {
            levelColors[level] = color
            invalidate()
        }
    }

    fun setGrid(newGrid: HeatmapGrid?) {
        grid = newGrid
        requestLayout()
        invalidate()
    }

    /** The colour a given level of the scale is drawn in, for a legend that has to match the grid. */
    fun colorForLevel(level: Int): Int = levelColors[level.coerceIn(0, LEVEL_COUNT - 1)]

    private val columns: Int get() = grid?.weeks?.size ?: 0

    private val rows: Int get() = grid?.weeks?.maxOfOrNull { it.size } ?: 0

    /** The size one square has, given the width the view is being measured at. */
    private fun cellSizeFor(availableWidth: Float): Float =
        ChartGeometry.cellSize(
            availableWidth = availableWidth,
            columns = columns,
            spacing = spacing,
            minimumCell = minimumCell
        )

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight)
            .coerceAtLeast(0).toFloat()
        val cell = cellSizeFor(available)
        val needed = ChartGeometry.gridWidth(columns, cell, spacing).toInt() + paddingLeft + paddingRight

        // With an unspecified width — the case inside a horizontal scroller — the grid takes what the
        // weeks need instead of collapsing to nothing.
        val width = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) needed
        else resolveSize(needed, widthMeasureSpec)
        val height = resolveSize(
            ChartGeometry.gridHeight(rows, cell, spacing).toInt() + paddingTop + paddingBottom,
            heightMeasureSpec
        )
        setMeasuredDimension(width, max(height, 0))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val current = grid ?: return
        if (columns == 0 || rows == 0) return

        val size = cellSizeFor((width - paddingLeft - paddingRight).toFloat().coerceAtLeast(0f))
        val radius = minOf(corner, size / 2f)
        current.weeks.forEachIndexed { week, days ->
            days.forEachIndexed { dayOfWeek, value ->
                // A day that has not happened is not painted, not even in the empty shade: the square's
                // absence is the only honest statement about it.
                if (value.isFuture) return@forEachIndexed
                val bounds = ChartGeometry.cellBounds(
                    week = week,
                    dayInWeek = dayOfWeek,
                    left = paddingLeft.toFloat(),
                    top = paddingTop.toFloat(),
                    cellSize = size,
                    spacing = spacing
                )
                paint.color = colorForLevel(value.level)
                rect.set(bounds.left, bounds.top, bounds.right, bounds.bottom)
                canvas.drawRoundRect(rect, radius, radius, paint)
            }
        }
    }

    private companion object {
        const val LEVEL_COUNT = 5

        val LEVEL_COLOR_RESOURCES = intArrayOf(
            R.color.chart_heatmap_none,
            R.color.chart_heatmap_1,
            R.color.chart_heatmap_2,
            R.color.chart_heatmap_3,
            R.color.chart_heatmap_4
        )
    }
}
