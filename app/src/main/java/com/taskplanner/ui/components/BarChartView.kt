package com.taskplanner.ui.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.taskplanner.R

/**
 * A column per day of the period, tallest bar first in the account's own scale.
 *
 * The bars are the trend the repository answered: the days that produced something, in order. Nothing
 * here is summed, averaged or filled in — the view is given the numbers it draws and lays them out with
 * [ChartGeometry], so the only claim a bar makes is that this day was taller than that one by exactly as
 * much as the measurements say.
 *
 * A period with no work in it draws its axis and no bars, which is the truth about it. An axis with
 * nothing on it is a picture of a quiet week; an empty canvas would be a picture of a broken screen.
 */
class BarChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.chart_bar)
    }

    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.getDimension(R.dimen.chart_axis_thickness)
        color = ContextCompat.getColor(context, R.color.chart_axis)
    }

    private var values: List<Long> = emptyList()

    /**
     * Reused for every mark, because a drawing pass that allocates makes the screen it paints on stutter.
     */
    private val rect = RectF()

    /** The height the plot gets when the screen does not name one. */
    private val desiredHeight = resources.getDimensionPixelSize(R.dimen.chart_bar_height)

    /**
     * One value per day, oldest first.
     *
     * The list is the day count the caller was given, not a fixed seven: a period whose account produced
     * work on two days shows two bars, and the gap between them stays the reader's to see.
     */
    fun setValues(newValues: List<Long>) {
        values = newValues
        requestLayout()
        invalidate()
    }

    /** The colour of the columns, so a chart of completions can be told from a chart of work. */
    fun setBarColor(color: Int) {
        barPaint.color = color
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = resolveSize(suggestedMinimumWidth, widthMeasureSpec)
        val height = resolveSize(desiredHeight, heightMeasureSpec)
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val inset = paddingLeft.toFloat()
        val plotWidth = (width - paddingLeft - paddingRight).toFloat().coerceAtLeast(0f)
        // Half the axis's thickness comes off the bottom of the plot so the line the bars stand on is
        // painted inside the view rather than with half of it clipped away.
        val plotHeight = ((height - paddingTop - paddingBottom).toFloat() - axisPaint.strokeWidth / 2f)
            .coerceAtLeast(0f)
        val baseline = paddingTop + plotHeight

        // The axis is drawn whatever the data says, because it is the part of the picture that tells the
        // reader where a bar of a given height would sit.
        canvas.drawRect(inset, baseline, inset + plotWidth, baseline, axisPaint)

        val corner = resources.getDimension(R.dimen.chart_bar_corner)
        ChartGeometry.bars(
            values = values,
            plotLeft = inset,
            plotTop = paddingTop.toFloat(),
            plotWidth = plotWidth,
            plotHeight = plotHeight
        ).forEach { column ->
            val box = column.bounds
            if (box.isEmpty) return@forEach
            val radius = minOf(corner, box.width / 2f, box.height / 2f)
            rect.set(box.left, box.top, box.right, box.bottom)
            canvas.drawRoundRect(rect, radius, radius, barPaint)
        }
    }
}
