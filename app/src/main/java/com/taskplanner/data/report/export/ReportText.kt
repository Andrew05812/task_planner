package com.taskplanner.data.report.export

/**
 * How wide a piece of this document's text is.
 *
 * One method, and it is here as a seam for a reason that is about correctness rather than convenience. A page
 * of a report is decided by measurement: whether a heading is last on the sheet, whether a table row fits, how
 * tall a paragraph runs. The only honest measurement of a glyph string on Android is a `Paint`, and the
 * `Paint` a JVM test gets measures a ten-character string at a 12 pt size as ten units — one per character,
 * whatever was asked for. A layout driven straight from `Paint` would therefore be tested against numbers no
 * device has ever produced, and would pass while its real pages overflowed.
 *
 * So the arithmetic of pages asks this interface how wide a string is, and the answer comes from the platform's
 * own text engine when the document is really printed and from a modelled one when a test wants a page whose
 * lines it can count. The wrapping, the breaking and the packing below are the same code either way; only the
 * ruler changes, which is the whole point of measuring through a seam rather than around one.
 */
interface ReportMeasurer {

    /** The width [text] occupies in [style], in the same points the page geometry is stated in. */
    fun width(text: String, style: ReportStyle): Float
}

/**
 * Text laid out in lines that fit.
 *
 * Three promises, and each of them exists because the alternative is a document that loses part of a
 * reader's own words:
 *
 *  * **Nothing is dropped and nothing is cut.** A word too long for a line is broken across it rather than
 *    clipped, and a character too wide for a line still gets a line of its own. A task titled with a sixty
 *    character paste is the user's title, not an obstacle in the page's way.
 *  * **A line break the writer asked for is kept.** Achievements and insights store finished sentences, and
 *    some of them carry a newline. Those survive as line breaks, which is what §2's "исторические тексты
 *    используются без пересчёта" means for layout as much as for figures.
 *  * **The same text wraps the same way twice.** No randomness, no locale lookups, no dependency on what the
 *    page happened to hold before: [wrap] is a function of its four arguments, which is what lets a test
 *    assert on the lines rather than on a picture.
 */
object ReportWrapping {

    /**
     * The lines [text] needs at [style] within [maxWidth], in the order they are to be drawn.
     *
     * Blank input gives no lines rather than one empty one: an empty paragraph has nothing to say, and a page
     * that reserved a line for it would show a gap where a sentence was supposed to be.
     */
    fun wrap(text: String, measurer: ReportMeasurer, style: ReportStyle, maxWidth: Float): List<String> {
        if (maxWidth <= 0f) return emptyList()
        val lines = ArrayList<String>()
        // Written breaks first: a stored sentence that asked for a new line keeps it, and each of its parts
        // is then wrapped on its own rather than as one long run the reader never intended.
        text.split('\n').forEach { hardLine ->
            lines += wrapOne(hardLine, measurer, style, maxWidth)
        }
        return lines
    }

    /** The one line [text] makes when it is expected to fit on a single row, shortened only if it must be. */
    private fun wrapOne(text: String, measurer: ReportMeasurer, style: ReportStyle, maxWidth: Float): List<String> {
        if (text.isBlank()) return emptyList()
        if (measurer.width(text, style) <= maxWidth) return listOf(text)

        val lines = ArrayList<String>()
        val current = StringBuilder()
        var currentWidth = 0f
        text.split(WORD_BREAK).forEach { word ->
            if (word.isEmpty()) return@forEach
            val wordWidth = measurer.width(word, style)
            val gapWidth = if (current.isEmpty()) 0f else measurer.width(GAP, style)

            if (current.isNotEmpty() && currentWidth + gapWidth + wordWidth > maxWidth) {
                // The space a wrap swallows is the space that was between two words on the sheet already;
                // a continuation line that began with it would only move the measure by a fraction of a word.
                lines += current.toString()
                current.setLength(0)
                currentWidth = 0f
            }
            if (wordWidth > maxWidth) {
                // A word on its own wider than the column: break it rather than let it run off the page. This
                // is the case a category named by a pasted url produces, and clipping it would be the report
                // deciding to forget part of the user's own name for it.
                if (current.isNotEmpty()) {
                    lines += current.toString()
                    current.setLength(0)
                    currentWidth = 0f
                }
                lines += breakWord(word, measurer, style, maxWidth)
            } else {
                if (current.isNotEmpty()) {
                    current.append(GAP)
                    currentWidth += gapWidth
                }
                current.append(word)
                currentWidth += wordWidth
            }
        }
        if (current.isNotEmpty()) lines += current.toString()
        return lines
    }

    /**
     * The pieces of [word] that each fit inside [maxWidth].
     *
     * The prefix is measured as it grows rather than assumed from a per-character width, because the two
     * disagree in the one place it matters: a long word of wide Cyrillic glyphs or of digits fills a column
     * sooner than a long word of Latin ones. The loop stops at the last prefix that still fits, so every piece
     * it returns is a piece the page can actually hold.
     */
    private fun breakWord(word: String, measurer: ReportMeasurer, style: ReportStyle, maxWidth: Float): List<String> {
        val pieces = ArrayList<String>()
        var start = 0
        while (start < word.length) {
            var end = start + 1
            var lastFit = end
            while (end <= word.length && measurer.width(word.substring(start, end), style) <= maxWidth) {
                lastFit = end
                end++
            }
            // lastFit is never equal to start: the first character is taken whether or not it fits, since a
            // page that could not show it would have to lose it, and losing text is the failure this whole
            // function exists to prevent.
            pieces += word.substring(start, lastFit.coerceAtLeast(start + 1))
            start = lastFit.coerceAtLeast(start + 1)
        }
        return pieces
    }

    private const val GAP = " "

    /** Where a line may end: any whitespace, so a tab in pasted text is a place to break rather than a gap. */
    private val WORD_BREAK = Regex("\\s+")
}
