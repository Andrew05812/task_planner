package com.taskplanner.ui.report

import com.taskplanner.data.report.export.ReportPageGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the pages of a report go inside a view.
 *
 * The point of [ReportPageFit] being a value rather than a bunch of arithmetic inside `onDraw` is that the two
 * things a preview can get wrong are both checkable without a screen: that a sheet keeps the proportions it was
 * laid out in — a page stretched to the width of a phone is a page whose lines no longer match the file it
 * previews — and that everything the view asks to be drawn is finite, including the degenerate cases where the
 * view has no width yet or the document has no pages yet.
 *
 * Coordinates here are pixels; the report's are points on an A4 sheet. The one number that connects them is the
 * scale, and the tests name it so a reader can see what a 10.5 pt line becomes on the smallest screen this app
 * supports.
 */
class ReportPageFitTest {

    private val sheet = ReportPageGeometry(width = 595, height = 842)

    private fun fit(viewWidth: Float, zoom: Int = ReportPageFit.MIN_ZOOM, gap: Float = 12f) =
        ReportPageFit(sheet = sheet, viewWidth = viewWidth, zoom = zoom, gap = gap)

    @Test
    fun `a page fitted to the view is as wide as the view and no wider`() {
        val layout = fit(1080f)
        assertEquals(1080f, layout.pageWidth, 0.01f)
        assertEquals(1080f, layout.width, 0.01f)
    }

    @Test
    fun `a fitted page keeps the sheet's proportions`() {
        val layout = fit(1080f)
        val ratio = sheet.height.toFloat() / sheet.width
        assertEquals(ratio, layout.pageHeight / layout.pageWidth, 0.0001f)
    }

    @Test
    fun `the scale is points of the sheet turned into pixels of the screen`() {
        val layout = fit(1080f)
        assertEquals(1080f / 595f, layout.scale, 0.0001f)
        assertEquals(10.5f * layout.scale, layout.size(10.5f), 0.0001f)
    }

    @Test
    fun `pages stack downward with the gap between them and none of them moves`() {
        val layout = fit(600f, gap = 20f)
        assertEquals(0f, layout.topOf(0), 0.01f)
        assertEquals(layout.pageHeight + 20f, layout.topOf(1), 0.01f)
        assertEquals(2f * (layout.pageHeight + 20f), layout.topOf(2), 0.01f)
        assertEquals(3f * layout.pageHeight + 2f * 20f, layout.heightFor(3), 0.01f)
    }

    @Test
    fun `a document with no pages asks for no room`() {
        val layout = fit(600f)
        assertEquals(0f, layout.heightFor(0), 0.01f)
        assertTrue("and every number stays finite", layout.pageHeight.isFinite())
    }

    @Test
    fun `a view that has no width yet cannot divide the sheet by nothing`() {
        val layout = fit(0f)
        assertTrue("no infinite page", layout.pageWidth.isFinite())
        assertTrue("no infinite height", layout.heightFor(4).isFinite())
        assertTrue("a page still has some width to draw into", layout.pageWidth > 0f)
    }

    @Test
    fun `a mark is placed at its own point on its own page`() {
        val layout = fit(595f * 2f)
        val scale = layout.scale
        assertEquals(44f * scale, layout.x(44f), 0.001f)
        assertEquals(layout.topOf(2) + 120f * scale, layout.y(2, 120f), 0.001f)
        // A rule spans the sheet's own margins, and its thickness scales with everything else, or a hairline
        // drawn on a zoomed page would be the only thing on it that kept its paper weight.
        assertEquals((595f - 44f) * scale, layout.x(595f - 44f), 0.001f)
        assertEquals(0.7f * scale, layout.stroke(0.7f), 0.001f)
        assertEquals(layout.topOf(0), layout.y(0, 0f), 0.001f)
    }

    @Test
    fun `zooming multiplies the fit without changing the sheet's ratio`() {
        val fitted = fit(1080f)
        val zoomed = fit(1080f, zoom = ReportPageFit.MAX_ZOOM)

        assertTrue("a zoomed page is wider than the view, which is what makes it scrollable",
            zoomed.pageWidth > fitted.pageWidth)
        assertTrue("and it holds its view's height too",
            zoomed.pageHeight > fitted.pageHeight)
        assertEquals(
            "the ratio belongs to the sheet, not to the zoom",
            fitted.pageHeight / fitted.pageWidth,
            zoomed.pageHeight / zoomed.pageWidth,
            0.0001f
        )
    }

    @Test
    fun `the zoom the screen offers is bounded by the levels it names`() {
        assertEquals(ReportPageFit.MIN_ZOOM, ReportPageFit.clamp(ReportPageFit.MIN_ZOOM - 5))
        assertEquals(ReportPageFit.MAX_ZOOM, ReportPageFit.clamp(ReportPageFit.MAX_ZOOM + 5))
        assertEquals(
            "the first level is the page as wide as the view",
            1f,
            ReportPageFit.factor(ReportPageFit.MIN_ZOOM),
            0.0001f
        )
        assertTrue(
            "the last one is big enough to read on a small phone",
            ReportPageFit.factor(ReportPageFit.MAX_ZOOM) >= 2f
        )
    }

    @Test
    fun `an unknown zoom level lays out the page the nearest real level would`() {
        val clamped = fit(800f, zoom = ReportPageFit.clamp(99))
        val highest = fit(800f, zoom = ReportPageFit.MAX_ZOOM)
        val lowest = fit(800f, zoom = ReportPageFit.clamp(-1))

        assertEquals(highest.pageWidth, clamped.pageWidth, 0.001f)
        assertEquals(fit(800f).pageWidth, lowest.pageWidth, 0.001f)
    }
}
