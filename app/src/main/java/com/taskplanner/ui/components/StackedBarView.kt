package com.taskplanner.ui.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.taskplanner.R

/**
 * One bar split into the shares that make it up.
 *
 * The health panel and the categories both answer the same shape of question — how a whole divides — and a
 * bar that is the whole answers it in one glance where a ring of arcs would ask the reader to compare
 * angles. The widths come from [ChartGeometry.segments], which is where the promises live: the parts add up
 * to the bar, a value of zero has no part, and a total of zero leaves only the track.
 *
 * What the bar cannot say on its own is which colour is which, and this view does not try to. The legend
 * beside it is the screen's, built from the same list in the same order, so the two can only disagree if
 * the screen hands them different lists.
 */
class StackedBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.chart_track)
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val clip = Path()
    private val rect = RectF()

    private var values: List<Long> = emptyList()
    private var colors: IntArray = IntArray(0)

    /**
     * What the screen last asked to be drawn, in the order it asked for it.
     *
     * A read-only seam rather than a test that photographs the canvas: the promise worth keeping is that the
     * bar and the legend beside it were handed the same list, and that is a fact about what arrived here.
     */
    val currentValues: List<Long> get() = values

    /** The colour each part is painted with, index for index with [currentValues]. */
    val currentColors: IntArray get() = colors

    private val barHeight = resources.getDimension(R.dimen.chart_proportion_bar_height)

    /**
     * The parts and their colours, in the order the legend lists them.
     *
     * A colour list shorter than the values is not padded with a guess: the missing parts take the accent,
     * which is the one colour on the screen that does not mean anything else.
     */
    fun setSegments(newValues: List<Long>, newColors: IntArray) {
        values = newValues
        colors = newColors
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = resolveSize(suggestedMinimumWidth, widthMeasureSpec)
        val height = resolveSize(barHeight.toInt() + paddingTop + paddingBottom, heightMeasureSpec)
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val left = paddingLeft.toFloat()
        val top = ((height - barHeight) / 2f).coerceAtLeast(paddingTop.toFloat())
        val width = (width - paddingLeft - paddingRight).toFloat().coerceAtLeast(0f)
        if (width <= 0f) return
        val radius = barHeight / 2f

        rect.set(left, top, left + width, top + barHeight)
        canvas.drawRoundRect(rect, radius, radius, trackPaint)

        val segments = ChartGeometry.segments(
            values = values,
            left = left,
            top = top,
            width = width,
            height = barHeight
        )
        if (segments.isEmpty()) return

        // The ends of the bar stay round where the parts of the first and last segment meet them, so a
        // category that fills the whole period does not square off the card it sits in.
        clip.reset()
        clip.addRoundRect(rect, radius, radius, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clip)
        segments.forEach { segment ->
            fillPaint.color = colors.getOrElse(segment.index) {
                ContextCompat.getColor(context, R.color.chart_bar)
            }
            val box = segment.bounds
            canvas.drawRect(box.left, box.top, box.right, box.bottom, fillPaint)
        }
        canvas.restore()
    }
}
