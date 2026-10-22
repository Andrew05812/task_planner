package com.taskplanner.ui.report

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import com.taskplanner.R
import com.taskplanner.data.report.export.PagedReport
import com.taskplanner.data.report.export.PdfReport
import com.taskplanner.data.report.export.ReportPageGeometry
import com.taskplanner.data.report.export.ReportSurface
import kotlin.math.ceil
import kotlin.math.max

/**
 * Where the sheets of a paginated report go inside a view, in pixels.
 *
 * A report's own coordinates are points on an A4 sheet, decided by
 * [com.taskplanner.data.report.export.ReportPagination] so that the file the reader saves and the screen they
 * are looking at are the same document. Everything the preview has to add is one number and one rule: how big a
 * point is on this screen, and that a sheet keeps the proportions it was laid out in. Both live here rather than
 * in `onDraw` because they are the two things a preview can get wrong invisibly — a page stretched to the width
 * of a phone is a page whose lines no longer match the file it previews, and a page measured against no width at
 * all is an infinity a layout pass would happily ask for.
 *
 * [zoom] is a level, not a multiplier the caller invents: the screen offers a handful and this type names them,
 * so "readable on a small phone" is a decision made once rather than a slider each fragment tunes by eye.
 */
class ReportPageFit(
    sheet: ReportPageGeometry,
    viewWidth: Float,
    zoom: Int,
    gap: Float
) {

    /** The sheet's own size, kept so nothing has to remember which document this fits. */
    val sheetWidth: Float = sheet.width.toFloat()
    val sheetHeight: Float = sheet.height.toFloat()

    /** Pixels per point, for this view, at this level. */
    val scale: Float

    /** The sheet as drawn: as wide as the view at the first level, wider when the reader asks for more. */
    val pageWidth: Float
    val pageHeight: Float

    /** The room between sheets, in pixels. */
    val gap: Float = gap

    /** The width the whole preview asks for — the page, whatever the zoom made of it. */
    val width: Float get() = pageWidth

    init {
        // A view that has not been measured yet reports no width, and dividing a sheet by nothing is how a
        // layout pass ends up with an infinite page. The smallest real width this app runs at is asked for
        // instead, which gives a page that is small but finite and correct, and is replaced the moment the
        // view knows how wide it really is.
        val usable = max(viewWidth, MIN_VIEW_WIDTH)
        scale = usable / sheetWidth * factor(zoom)
        pageWidth = sheetWidth * scale
        pageHeight = sheetHeight * scale
    }

    /** The top of sheet [index], counting the gap that separates it from the one before. */
    fun topOf(index: Int): Float = index * (pageHeight + gap)

    /** How tall a document of [pageCount] sheets is, gaps included and none of them past the bottom. */
    fun heightFor(pageCount: Int): Float =
        if (pageCount <= 0) 0f else pageCount * pageHeight + (pageCount - 1) * gap

    /** A point across the sheet, in pixels from the preview's left edge. */
    fun x(point: Float): Float = point * scale

    /** A point down the sheet [index], in pixels from the preview's top edge. */
    fun y(index: Int, point: Float): Float = topOf(index) + point * scale

    /** A type size asked for in points, stated in the pixels it will be drawn at. */
    fun size(points: Float): Float = points * scale

    /** A rule's thickness, scaled with everything else so a hairline stays a hairline at any level. */
    fun stroke(points: Float): Float = points * scale

    companion object {

        /** Fitting the page to the view: the level a report is opened at. */
        const val MIN_ZOOM = 0

        /** The last level the screen offers — a page big enough to read without pressing on it. */
        const val MAX_ZOOM = 2

        /** A width a sheet can be measured against when the view has not been laid out yet. */
        private const val MIN_VIEW_WIDTH = 240f

        private val FACTORS = floatArrayOf(1f, 1.5f, 2.25f)

        /** The multiplier a level asks for, with anything outside the offered levels brought back into them. */
        fun factor(level: Int): Float = FACTORS[clamp(level)]

        fun clamp(level: Int): Int = level.coerceIn(MIN_ZOOM, MAX_ZOOM)
    }
}

/**
 * A [ReportSurface] that puts a report's marks on the screen.
 *
 * It is the preview's only graphics, and it is a [ReportSurface] rather than a loop over the draws so the
 * preview and the saved file go through the same dispatcher —
 * [com.taskplanner.data.report.export.PdfReport.draw] — with the same typography lookup. What the reader sees on
 * the screen is therefore the document the exporter writes, page for page, and there is no second layout to
 * drift away from the first.
 *
 * The one thing it adds is the paper itself. A sheet of white with a hairline edge is chrome rather than layout:
 * it moves no mark, and it is drawn before the page's own marks so the report's ink always lands on top of it.
 * It is white whatever the app's theme is, because a preview of a printed page that inverted its ink would be a
 * preview of a document nobody would recognise as the one they are about to save.
 */
class CanvasReportSurface(
    private val canvas: Canvas,
    private val fit: ReportPageFit
) : ReportSurface {

    private val paper = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        isAntiAlias = false
    }

    private val edge = Paint().apply {
        color = EDGE
        style = Paint.Style.STROKE
        strokeWidth = 1f
        isAntiAlias = true
    }

    private val paints = HashMap<Triple<Float, Boolean, Int>, Paint>()
    private var sheet = -1

    override fun startPage(width: Int, height: Int) {
        sheet++
        val top = fit.topOf(sheet)
        val right = fit.x(width.toFloat())
        val bottom = fit.y(sheet, height.toFloat())
        canvas.drawRect(0f, top, right, bottom, paper)
        canvas.drawRect(0f, top, right, bottom, edge)
    }

    override fun finishPage() {
        // Nothing to close: a sheet on a screen is the drawing it was given, and the next startPage moves the
        // cursor down by its own height and gap.
    }

    override fun text(x: Float, baseline: Float, sizePt: Float, bold: Boolean, color: Int, text: String) {
        if (text.isEmpty()) return
        canvas.drawText(text, fit.x(x), fit.y(sheet, baseline), paint(sizePt, bold, color))
    }

    override fun rule(left: Float, right: Float, y: Float, thickness: Float, color: Int) {
        val half = fit.stroke(thickness) / 2f
        val line = fit.y(sheet, y)
        canvas.drawRect(fit.x(left), line - half, fit.x(right), line + half, fill(color))
    }

    override fun rect(left: Float, top: Float, right: Float, bottom: Float, color: Int) {
        if (right <= left || bottom <= top) return
        canvas.drawRect(fit.x(left), fit.y(sheet, top), fit.x(right), fit.y(sheet, bottom), fill(color))
    }

    private fun paint(sizePt: Float, bold: Boolean, color: Int): Paint =
        paints.getOrPut(Triple(fit.size(sizePt), bold, color)) {
            Paint().apply {
                textSize = fit.size(sizePt)
                // The same family the PDF is written with, so the widths the pagination measured are the widths
                // the reader is looking at rather than the widths a different font would have needed.
                typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.SANS_SERIF
                isAntiAlias = true
                this.color = color
            }
        }

    private fun fill(color: Int): Paint = Paint().apply {
        this.color = color
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private companion object {
        /** The page's own edge, dark enough to see on a light background and soft enough on a dark one. */
        const val EDGE = 0x33000000
    }
}

/**
 * The report as its pages are laid out, drawn at the size the screen has.
 *
 * The view asks for exactly as much room as the document needs — every sheet, plus the gaps — because it lives
 * inside a scrolling container rather than in a fixed box: a preview that clipped to its measured height would
 * be a preview that hides the end of the report it claims to show.
 */
class ReportPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var document: PagedReport? = null

    /** The width the page is fitted to before the zoom multiplies it. */
    private var viewport: Float = resources.displayMetrics.widthPixels.toFloat()

    /** The gap between sheets, in pixels. */
    private val pageGap: Float = resources.getDimension(R.dimen.report_page_gap)

    var zoom: Int = ReportPageFit.MIN_ZOOM
        set(value) {
            val clamped = ReportPageFit.clamp(value)
            if (clamped == field) return
            field = clamped
            requestLayout()
            invalidate()
        }

    /**
     * The pages to draw, or none.
     *
     * Handing over a new document re-measures, because a report that grew a page grows the view that shows it,
     * and a container that is not told will keep the height it had for the shorter one.
     */
    fun setDocument(paged: PagedReport?) {
        if (paged === document) return
        document = paged
        contentDescription = paged?.let {
            context.getString(R.string.report_preview_description, it.pageCount)
        }
        requestLayout()
        invalidate()
    }

    /**
     * The width a page is fitted to.
     *
     * Set by whoever holds this view once it knows its own size: inside a horizontal scroller the measure spec a
     * child is handed says nothing about the window, so the "fit" has to be told rather than guessed. Until it
     * is, the screen's own width is a reasonable first answer and the next layout pass corrects it.
     */
    fun setViewport(widthPx: Float) {
        if (widthPx <= 0f || kotlin.math.abs(widthPx - viewport) < 0.5f) return
        viewport = widthPx
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val layout = fit()
        val sheets = document?.pageCount ?: 0
        val wanted = ceil(layout.width).toInt() + paddingStart + paddingEnd
        val high = ceil(layout.heightFor(sheets)).toInt() + paddingTop + paddingBottom
        setMeasuredDimension(
            resolveSize(wanted, widthMeasureSpec),
            resolveSize(high, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val paged = document ?: return
        val layout = fit()
        canvas.save()
        canvas.translate(paddingStart.toFloat(), paddingTop.toFloat())
        // The same call the pdf writer is handed. Nothing here decides where a line goes.
        PdfReport.draw(paged, CanvasReportSurface(canvas, layout))
        canvas.restore()
    }

    private fun fit() = ReportPageFit(
        sheet = document?.geometry ?: ReportPageGeometry.A4_PORTRAIT,
        viewWidth = viewport - paddingStart - paddingEnd,
        zoom = zoom,
        gap = pageGap
    )
}
