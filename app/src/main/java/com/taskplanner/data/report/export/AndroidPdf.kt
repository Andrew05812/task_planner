package com.taskplanner.data.report.export

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.OutputStream

/**
 * The platform's own text engine, asked how wide a line is.
 *
 * This is the ruler the pagination measures with when a report is really being printed, and the reason the
 * measurement is an interface at all: the `Paint` a JVM test is handed measures a ten-character string at a
 * 12 pt size as ten units, one per character, whatever the size — so the lines that page layout counts on a
 * test run are not the lines a handset counts. The arithmetic of pages is the same code in both places; only
 * the ruler differs, and the ruler is chosen by whoever asks for the pages.
 */
class PaintReportMeasurer : ReportMeasurer {

    private val paints = HashMap<ReportStyle, Paint>()

    override fun width(text: String, style: ReportStyle): Float = paint(style).measureText(text)

    private fun paint(style: ReportStyle): Paint = paints.getOrPut(style) {
        Paint().apply {
            textSize = ReportTypography.spec(style).size
            typeface = if (style.bold) Typeface.DEFAULT_BOLD else Typeface.create(SANS_SERIF, Typeface.NORMAL)
            isAntiAlias = true
            // Letterspacing and subtle styling are off by default and stay off: a measurement of the same
            // string twice has to give the same width, or a page would be laid out for one drawing and
            // printed with another.
        }
    }
}

/**
 * The family the platform's Latin and Cyrillic letters live in, named rather than defaulted.
 *
 * `Typeface.DEFAULT` would have been the shorter line, and it is the same family today; naming it is the
 * difference between a report that follows the app's typography and one that follows whichever font the
 * handset decided a default is. Roboto carries the whole Cyrillic range including `ё` and `Ё`, which is what
 * makes a report's Russian text part of its searchable text layer rather than a row of empty boxes.
 */
private const val SANS_SERIF = "sans-serif"

/**
 * A [ReportSurface] that writes a real PDF with the platform's document writer.
 *
 * The library §6.1 asks for is the one the operating system ships: `android.graphics.pdf.PdfDocument`, present
 * since API 21 and therefore below this app's minimum of 24. A third-party document builder was the other
 * candidate, and it was set aside for reasons worth writing down: a report is drawn, not typeset by a layout
 * engine, so the library would have replaced the thirty lines of graphics below while adding a dependency, an
 * APK size, a licence and a font-embedding question that the platform writer has already answered — it
 * subsetting-embeds the glyphs it draws, which is what makes a PDF whose Cyrillic can be read by a search.
 *
 * No decisions of its own, and it cannot have any: every coordinate it is given has already been measured,
 * wrapped and page-broken by [ReportPagination].
 */
class PdfDocumentSurface : ReportSurface {

    private val document = PdfDocument()
    private val paints = HashMap<Pair<Float, Boolean>, Paint>()
    private var page: PdfDocument.Page? = null
    private var canvas: Canvas? = null
    private var sheets = 0

    override fun startPage(width: Int, height: Int) {
        sheets++
        val info = PdfDocument.PageInfo.Builder(width, height, sheets).create()
        val started = document.startPage(info)
        page = started
        canvas = started.canvas
    }

    override fun finishPage() {
        val started = page ?: return
        document.finishPage(started)
        page = null
        canvas = null
    }

    override fun text(x: Float, baseline: Float, sizePt: Float, bold: Boolean, color: Int, text: String) {
        if (text.isEmpty()) return
        holder().drawText(text, x, baseline, paint(sizePt, bold, color))
    }

    override fun rule(left: Float, right: Float, y: Float, thickness: Float, color: Int) {
        holder().drawRect(left, y - thickness / 2f, right, y + thickness / 2f, paint(color))
    }

    override fun rect(left: Float, top: Float, right: Float, bottom: Float, color: Int) {
        if (right <= left || bottom <= top) return
        holder().drawRect(left, top, right, bottom, paint(color))
    }

    /**
     * The document as bytes in [out], and how many of them there were.
     *
     * The count is returned rather than ignored because a caller that reports success has to be able to say
     * what it saw: the platform's writer is a native renderer, and a file that came out with no bytes is not a
     * saved report. Counting the bytes the stream was given is how the caller finds that out instead of
     * promising the reader something it did not check.
     */
    fun finish(out: OutputStream): Long {
        finishPage()
        val counted = CountingStream(out)
        document.writeTo(counted)
        document.close()
        return counted.written
    }

    private fun holder(): Canvas = canvas ?: throw IllegalStateException("no page has been started")

    private fun paint(color: Int): Paint = Paint().apply {
        this.color = color
        isAntiAlias = true
        style = Paint.Style.FILL
    }

    private fun paint(sizePt: Float, bold: Boolean, color: Int): Paint =
        paints.getOrPut(sizePt to bold) {
            Paint().apply {
                textSize = sizePt
                typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.create(SANS_SERIF, Typeface.NORMAL)
                isAntiAlias = true
            }
        }.also { it.color = color }
}

/** The few bytes of plumbing a stream needs to be able to say how much it was handed. */
private class CountingStream(private val delegate: OutputStream) : OutputStream() {

    var written: Long = 0L
        private set

    override fun write(byte: Int) {
        delegate.write(byte)
        written++
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        delegate.write(bytes, offset, length)
        written += length
    }

    override fun flush() {
        delegate.flush()
    }

    override fun close() {
        delegate.close()
    }
}
