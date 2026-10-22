package com.taskplanner.data.report.export

import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * Reading a report's PDF bytes from the outside, the way a reader's viewer will.
 *
 * §4 asks two things of a report file: that its Cyrillic is drawn with a font carried inside the document, and
 * that the text on the page is text — selectable, searchable, not a picture of words. Both are checkable from the
 * bytes, and neither is checkable the way this project first tried: the platform's writer (`Skia/PDF`) puts its
 * glyph outlines in the file as `Type3` fonts with `CharProcs` programs rather than as a `FontFile2` TrueType
 * subset, so a search for that one key reports "no font" about a document that does have one. The measured reason
 * for that is in `docs/REPORT_EXPORT.md`; the rule here is the general one.
 *
 * Object dictionaries may sit inside compressed streams, so the file is read both ways: the raw bytes and the
 * inflation of every `stream` section in it. This is not a PDF parser — nothing resolves an object graph or
 * follows an xref — it is a search for the markers that can only mean what they mean.
 */
object ReportPdfInspection {

    /**
     * What the file says about itself.
     *
     * [glyphPrograms] counts the `Type3` character programs and [fontFiles] the embedded TrueType or CFF programs;
     * a document that carries its own outlines has one or the other, and having neither means the reader's
     * machine is asked to supply the font. [toUnicodeMaps] counts the maps that turn those outlines back into
     * characters, which is what makes the text in a report findable.
     */
    data class Inspection(
        val glyphPrograms: Int,
        val fontFiles: Int,
        val toUnicodeMaps: Int,
        val producer: String
    ) {
        /** Either way a file can carry its own letterforms counts; the platform chooses which. */
        val carriesItsOwnLetterforms: Boolean get() = glyphPrograms > 0 || fontFiles > 0
        val hasTextLayer: Boolean get() = toUnicodeMaps > 0

        override fun toString(): String =
            "letterforms=glyphPrograms:$glyphPrograms/fontFiles:$fontFiles textLayer=$toUnicodeMaps producer=$producer"
    }

    fun inspect(bytes: ByteArray): Inspection = inspect(String(bytes, Charsets.ISO_8859_1))

    fun inspect(pdf: String): Inspection {
        val text = pdf + inflatedStreams(pdf)
        return Inspection(
            glyphPrograms = countOf(text, "/CharProcs") + countOf(text, "/Subtype /Type3"),
            fontFiles = countOf(text, "/FontFile2") + countOf(text, "/FontFile3"),
            toUnicodeMaps = countOf(text, "/ToUnicode"),
            producer = producer(pdf)
        )
    }

    private fun countOf(text: String, marker: String): Int = text.split(marker).size - 1

    /** The producer line, which names the library the platform wrote the file with. */
    private fun producer(pdf: String): String {
        val marker = "/Producer ("
        val at = pdf.indexOf(marker)
        if (at < 0) return "unknown"
        return pdf.substring(at + marker.length).substringBefore(")")
    }

    /**
     * Every `stream` section, unpacked where it will unpack.
     *
     * `stream` is followed by CRLF or a bare line feed, and the body starts after whichever of the two it is. A
     * stream that refuses to inflate is skipped rather than failed: the question is whether a marker appears
     * anywhere in this document, and an image or a font program the inflater will not take is not evidence that a
     * font is missing.
     */
    private fun inflatedStreams(pdf: String): String {
        val bytes = pdf.toByteArray(Charsets.ISO_8859_1)
        val keyword = "stream".toByteArray(Charsets.ISO_8859_1)
        val terminator = "endstream".toByteArray(Charsets.ISO_8859_1)
        val out = StringBuilder()
        var from = 0
        while (true) {
            val start = indexOf(bytes, keyword, from)
            if (start < 0) break
            val after = start + keyword.size
            val bodyStart = if (bytes.getOrNull(after) == 13.toByte()) after + 2 else after + 1
            val bodyEnd = indexOf(bytes, terminator, bodyStart)
            if (bodyEnd < 0) break
            if (bodyEnd > bodyStart) out.append(unzip(bytes, bodyStart, bodyEnd - bodyStart))
            from = bodyEnd + 1
        }
        return out.toString()
    }

    private fun unzip(bytes: ByteArray, offset: Int, length: Int): String {
        val inflater = Inflater()
        try {
            inflater.setInput(bytes, offset, length)
            val buffer = ByteArray(8_192)
            val piece = ByteArrayOutputStream()
            while (!inflater.finished()) {
                val read = try {
                    inflater.inflate(buffer)
                } catch (e: DataFormatException) {
                    return ""
                }
                if (read <= 0) return ""
                piece.write(buffer, 0, read)
            }
            return String(piece.toByteArray(), Charsets.ISO_8859_1)
        } finally {
            inflater.end()
        }
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int): Int {
        if (from < 0 || haystack.size - from < needle.size) return -1
        for (i in from..haystack.size - needle.size) {
            var matched = true
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) {
                    matched = false
                    break
                }
            }
            if (matched) return i
        }
        return -1
    }
}
