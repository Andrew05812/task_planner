package com.taskplanner.ui.report

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.taskplanner.R
import com.taskplanner.data.report.export.PagedReport
import com.taskplanner.data.report.export.ReportDraw
import com.taskplanner.data.report.export.ReportPage
import com.taskplanner.data.report.export.ReportPageGeometry
import com.taskplanner.data.report.export.ReportStyle
import com.taskplanner.testing.AppFixture
import kotlin.math.ceil
import kotlin.math.max
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode

/**
 * The room the preview asks for, and how it answers a document that is not there.
 *
 * What a JVM can honestly say about this view is measured: that it asks for exactly the height of the sheets it
 * holds plus the gaps between them — a preview that clipped to its container would hide the end of the report it
 * claims to show — and that a zoom multiplies both dimensions by the same number, which is what makes a page
 * wider than the screen scroll sideways rather than stretch out of shape.
 *
 * What this file deliberately does not claim is anything about ink. Robolectric's native graphics runtime ships
 * no `windows/x86_64` library in the environment this project is developed in, so a `Bitmap` drawn here stays
 * empty whatever was painted, and an assertion that read its pixels would be a test of a shadow rather than of a
 * screen. Whether the report really appears — Cyrillic and all, page after page — is answered by the emulator run
 * in this sub-stage, whose screenshots are in the PR and in `docs/REPORT_EXPORT.md`, and not pretended about here.
 */
@RunWith(RobolectricTestRunner::class)
// Legacy graphics on purpose, and it is worth saying why: the native runtime Robolectric 4.11 prefers has no
// windows/x86_64 library in this environment, so creating a View at all fails before a test can ask anything of
// it. The legacy shadows answer measure and layout faithfully, which is what is claimed here — and nothing here
// claims anything about pixels, because that would be a claim about a shadow.
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class ReportPreviewViewTest {

    private lateinit var fixture: AppFixture
    private val sheet = ReportPageGeometry(width = 400, height = 300, footerHeight = 10f)

    private val gapPx: Float get() = fixture.context.resources.getDimension(R.dimen.report_page_gap)

    @Before
    fun setUp() {
        fixture = AppFixture(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        fixture.close()
    }

    private fun document(pages: Int): PagedReport = PagedReport(
        pages = (0 until pages).map { index ->
            ReportPage(
                number = index + 1,
                total = pages,
                draws = listOf(
                    ReportDraw.Text(
                        x = 20f,
                        baseline = 40f,
                        style = ReportStyle.DocumentTitle,
                        color = 0xFF000000.toInt(),
                        text = "Отчёт $index"
                    ),
                    ReportDraw.Rect(
                        left = 20f,
                        top = 60f,
                        right = 120f,
                        bottom = 90f,
                        color = 0xFF6750A4.toInt()
                    ),
                    ReportDraw.Rule(
                        left = 20f,
                        right = 380f,
                        y = 120f,
                        color = 0xFF757575.toInt(),
                        thickness = 0.7f
                    )
                )
            )
        },
        geometry = sheet
    )

    /**
     * Measured the way the scroller measures it.
     *
     * A horizontal scroller hands its child an unspecified width — that is the whole point of putting a view
     * inside one — so a page wider than the screen reports that width and the reader scrolls it. Asking for an
     * exactly-fitted width here would be asking the view to lie about what its container does.
     */
    private fun laidOut(width: Int, pages: Int, zoom: Int = ReportPageFit.MIN_ZOOM): ReportPreviewView =
        ReportPreviewView(fixture.context).apply {
            setViewport(width.toFloat())
            setDocument(document(pages))
            this.zoom = zoom
            measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            layout(0, 0, measuredWidth, measuredHeight)
            shadowOf(Looper.getMainLooper()).idle()
        }

    @Test
    fun `the view asks for exactly every sheet and the gaps between them`() {
        val one = laidOut(400, 1)
        assertEquals(300, one.measuredHeight)

        val three = laidOut(400, 3)
        assertEquals(3 * 300 + ceil(2 * gapPx).toInt(), three.measuredHeight)
    }

    @Test
    fun `a zoomed sheet asks for more room both ways and keeps its shape`() {
        val fitted = laidOut(400, 2)
        val zoomed = laidOut(400, 2, zoom = ReportPageFit.MAX_ZOOM)
        val multiple = ReportPageFit.factor(ReportPageFit.MAX_ZOOM)

        assertTrue("wider than the view, which is what makes it scroll sideways", zoomed.measuredWidth > 400)
        assertEquals((400 * multiple).toInt(), zoomed.measuredWidth)
        assertEquals(
            "every sheet is the same multiple taller, and only the sheets are",
            (fitted.measuredHeight - gapPx) * multiple,
            zoomed.measuredHeight - gapPx,
            1f
        )
        assertTrue(
            "the gap between sheets belongs to the screen, not to the document",
            zoomed.measuredHeight < fitted.measuredHeight * multiple
        )
    }

    @Test
    fun `a document with no sheets asks for no room`() {
        val empty = ReportPreviewView(fixture.context).apply {
            setViewport(400f)
            setDocument(PagedReport(emptyList(), sheet))
            measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY), 0)
        }

        assertEquals(0, empty.measuredHeight)
    }

    /**
     * A view with nothing to draw still draws itself.
     *
     * The state holds no pages before the first read lands, and a report that refused to be read has none at all;
     * either way the screen is painted every frame, so a view that needed a document to exist would crash the
     * very case the reader needs help with.
     */
    @Test
    fun `a view with nothing to draw still draws itself`() {
        val bare = ReportPreviewView(fixture.context).apply {
            setViewport(400f)
            measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY), 0)
            layout(0, 0, 400, max(1, measuredHeight))
        }

        bare.draw(Canvas(Bitmap.createBitmap(400, 1, Bitmap.Config.ARGB_8888)))

        assertEquals(0, bare.measuredHeight)
    }

    @Test
    fun `handing the view the same document again changes nothing`() {
        val pages = document(2)
        val view = ReportPreviewView(fixture.context).apply {
            setViewport(400f)
            setDocument(pages)
            measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY), 0)
        }
        val high = view.measuredHeight

        view.setDocument(pages)
        view.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY), 0)

        assertEquals("the same sheets keep the same room", high, view.measuredHeight)
        assertTrue(view.measuredHeight > 0)
    }
}
