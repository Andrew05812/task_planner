package com.taskplanner.data.report.export

/**
 * The characters a printed report can show, and what it does with the ones it cannot.
 *
 * A report's letters come from the font the platform draws text with, and that font carries Latin, Cyrillic
 * down to `ё` and `Ё`, and the punctuation and dashes a report is written with. What it does not carry is the
 * pictures: emoji live in a separate colour font whose glyphs are bitmaps, and a bitmap cannot join a
 * document's text layer — it either comes out as an empty square or does not come out at all. The stage names
 * that case and says what to do about it: a readable stand-in rather than an empty square, which is why a
 * marker exists here instead of a silent removal.
 *
 * Which characters the device's own font really reaches is settled on the device, not in this file: the ranges
 * below are the ones that are *not* letters — the surrogate halves of the astral planes where the pictographs
 * live, the two BMP blocks the platform keeps with them, and the joiners and variation selectors that only ever
 * appear inside a picture sequence. A manual check on a real handset is listed among the parts of this feature
 * that unit tests cannot reach.
 *
 * The marker is a decision about one medium. A csv has no font and no text layer, so a title with an emoji in
 * it is written there exactly as the reader typed it — [CsvReport] never asks this object anything.
 */
object ReportGlyphs {

    /**
     * [text] with every run the report's font cannot draw replaced by one [marker].
     *
     * A run rather than a character, because an emoji that takes several code points — a family, a flag, a
     * picture with a skin tone on it — is one thing the reader wrote, and three markers would be a report that
     * had invented two pictures nobody can see.
     */
    fun show(text: String, marker: String): String {
        if (text.none(::isUnsupported)) return text
        val result = StringBuilder(text.length)
        var inside = false
        text.forEach { character ->
            if (isUnsupported(character)) {
                if (!inside) {
                    result.append(marker)
                    inside = true
                }
            } else {
                result.append(character)
                inside = false
            }
        }
        return result.toString()
    }

    /** Whether [text] holds anything this report would have to mark. */
    fun needsMarker(text: String): Boolean = text.any(::isUnsupported)

    /**
     * Whether a character is one the letters of this document's font do not reach.
     *
     * Surrogate halves count: Kotlin hands a string out as UTF-16, so a pictograph above the Basic Multilingual
     * Plane arrives as a pair of them, and a run of two halves is one picture to the reader and one marker to
     * the page. Dashes, curly quotes and ellipses are deliberately *not* in here — those are letters of a
     * different kind, the font has them, and marking them would be the report damaging its own typography out
     * of caution.
     */
    fun isUnsupported(character: Char): Boolean {
        val code = character.code
        return code in SURROGATES || code in PICTOGRAPHS || code in SELECTORS || code in JOINERS
    }
}

/** The two halves of every code point above the Basic Multilingual Plane, where the pictographs live. */
private val SURROGATES = 0xD800..0xDFFF

/** The BMP blocks the platform keeps with the emoji rather than with the letters. */
private val PICTOGRAPHS = 0x2600..0x27BF

/** The variation selectors that pick which drawing of a picture is meant. */
private val SELECTORS = 0xFE00..0xFE0F

/** The joiners that hold a picture sequence together, and are invisible on their own. */
private val JOINERS = 0x200C..0x200D
