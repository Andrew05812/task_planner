package com.taskplanner.data.report.export

/**
 * The marks a page can be drawn with, and nothing else.
 *
 * This seam exists because the only way to make a PDF on Android is through classes the test JVM cannot run:
 * the platform's own document writer constructs a native renderer, and under Robolectric it accepts every
 * drawing call a report makes and then produces a file of no bytes at all. A report whose layout was *inside*
 * that class could only ever be checked on a handset, which would leave every promise in this stage about
 * overflowed tables and repeated headers untested until somebody looked at a screen.
 *
 * So the page is decided in [ReportPagination], and this interface is what a page costs to draw: five calls, no
 * decisions. The production implementation is `PdfDocumentSurface` and is about thirty lines of graphics; the
 * one in the test sources records what was asked for and can be asked whether any of it fell off the sheet.
 * Neither of them can move a line of text, break a table, or choose a page number, because none of those
 * questions reaches them.
 */
interface ReportSurface {

    /** A new sheet of [width] by [height] points, the first coordinate at its top-left. */
    fun startPage(width: Int, height: Int)

    /** The sheet is finished. Nothing may be drawn between this and the next [startPage]. */
    fun finishPage()

    /**
     * [text] with its baseline at [baseline].
     *
     * The size and the weight arrive with every call rather than through a mode the surface is put into, so a
     * drawing order that forgot to reset a style cannot leak one block's typography into the next.
     */
    fun text(x: Float, baseline: Float, sizePt: Float, bold: Boolean, color: Int, text: String)

    /** A horizontal line from [left] to [right] at [y]. */
    fun rule(left: Float, right: Float, y: Float, thickness: Float, color: Int)

    /** A filled rectangle. A chart's bars and a share chart's parts are the only things drawn with it. */
    fun rect(left: Float, top: Float, right: Float, bottom: Float, color: Int)
}

/**
 * Draws an already-paginated report onto a [ReportSurface].
 *
 * Two jobs, both of them translation rather than decision: a page becomes a sheet, and a [ReportDraw] becomes
 * the call its shape asks for, with the size and weight of its typographic role looked up in
 * [ReportTypography] — the same table the pagination measured with, which is the only way a line that was
 * counted as 13 points tall can be drawn 13 points tall.
 */
object PdfReport {

    fun draw(paged: PagedReport, surface: ReportSurface) {
        paged.pages.forEach { page ->
            surface.startPage(paged.geometry.width, paged.geometry.height)
            page.draws.forEach { draw -> dispatch(draw, surface) }
            surface.finishPage()
        }
    }

    private fun dispatch(draw: ReportDraw, surface: ReportSurface) {
        when (draw) {
            is ReportDraw.Text -> {
                val spec = ReportTypography.spec(draw.style)
                surface.text(
                    x = draw.x,
                    baseline = draw.baseline,
                    sizePt = spec.size,
                    bold = draw.style.bold,
                    color = draw.color,
                    text = draw.text
                )
            }

            is ReportDraw.Rule -> surface.rule(draw.left, draw.right, draw.y, draw.thickness, draw.color)
            is ReportDraw.Rect -> surface.rect(draw.left, draw.top, draw.right, draw.bottom, draw.color)
        }
    }
}
