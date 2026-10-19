package com.taskplanner.ui.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextUtils
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.taskplanner.R

/**
 * The list beside a proportion bar or an activity grid that says what its colours mean.
 *
 * A chart that cannot be read is a decoration, and the reading is the part a canvas cannot do on its own:
 * a square of orange is "due today" only because something beside it says so. The rows are built here
 * rather than in each screen because the health panel and the categories both need the same thing, and a
 * legend that drifts between the two is how one screen starts describing one figure twice.
 *
 * Rows come in the order they were given and no other order is invented, so the screen keeps the right to
 * sort by size, by name, or by what it wants the reader to see first.
 */
class ChartLegendView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    /**
     * One row: the colour the chart paints with, the name the account gives it, and the figure beside it.
     *
     * [detail] is what the reader compares across rows — a count, a duration, a share — and it is text the
     * caller formatted, because "2 hours" is the screen's language rather than this view's.
     */
    data class Entry(val label: CharSequence, val detail: CharSequence?, val color: Int)

    private val chipSize = resources.getDimensionPixelSize(R.dimen.chart_legend_chip_size)
    private val chipRadius = resources.getDimension(R.dimen.chart_heatmap_corner)
    private val chipOffset = resources.getDimensionPixelSize(R.dimen.spacing_xs)
    private val rowGap = resources.getDimensionPixelSize(R.dimen.spacing_small)

    init {
        orientation = VERTICAL
    }

    /** The rows this legend shows, top to bottom. */
    var entries: List<Entry> = emptyList()
        private set

    fun setEntries(newEntries: List<Entry>) {
        entries = newEntries
        removeAllViews()
        newEntries.forEachIndexed { index, entry ->
            addView(rowOf(entry), firstGap(index))
        }
    }

    /** The space above a row: the list is tight against the card's own padding, so only a gap between. */
    private fun firstGap(index: Int): LayoutParams =
        LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = if (index == 0) 0 else rowGap
        }

    private fun rowOf(entry: Entry): View {
        val row = LinearLayout(context).apply { orientation = HORIZONTAL }
        row.addView(
            ChipView(context, entry.color, chipRadius),
            LayoutParams(chipSize, chipSize).apply {
                topMargin = chipOffset
                marginEnd = rowGap
            }
        )
        row.addView(label(entry.label, R.style.TextAppearance_TaskPlanner_ChartLegend))
        // A row with no figure gets no label for it: an empty text view is space belonging to nothing, and
        // a legend that reserves a column for blanks reads as a list with holes in it.
        val detail = entry.detail?.takeIf { it.isNotBlank() }
        if (detail != null) {
            row.addView(
                label(detail, R.style.TextAppearance_TaskPlanner_ChartLegendDetail),
                LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = rowGap }
            )
        }
        return row
    }

    private fun label(text: CharSequence, style: Int): TextView = TextView(context).apply {
        this.text = text
        setTextAppearance(style)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
    }

    /**
     * The swatch before a legend row: a filled square of the colour, carrying no data of its own.
     *
     * A plain [View] would need a background drawable, and a drawable's colour is awkward to ask back when
     * a test checks that the legend and the chart were handed the same one. Painting it here keeps the
     * colour somewhere a test can read.
     */
    private class ChipView(
        context: Context,
        swatch: Int,
        private val radius: Float
    ) : View(context) {

        private val rect = RectF()

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = swatch
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            rect.set(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawRoundRect(rect, radius, radius, paint)
        }
    }
}
