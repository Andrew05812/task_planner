package com.taskplanner.data.report.export

/**
 * The sizes, the leading and the colours a report is printed with.
 *
 * Three things live in this one file because they are three halves of the same decision, and a page layout
 * that had to consult three of them could not be tested against a ruler. The scale is the app's own — the
 * five steps in `dimens.xml` are 12/14/16/20/24 sp — narrowed for print, where 14 sp of body text is a
 * billboard and a table of ten columns does not fit behind it. The relationship is what carries over, not the
 * absolute numbers: a section heading is to its body as a card title is to a list line, and the two-step
 * difference between a table's header and its rows is the same difference the dashboard's chart legend makes.
 *
 * The colours are named for what they say rather than for their hex value, and every one of them is a colour
 * the app already uses for the same claim — the accent for a heading, green for finished work, the grey of
 * "nothing to hang this on" for the bucket that is not a category. A report whose palette was chosen for the
 * report would be a document that disagreed with the screen it summarised.
 *
 * Nothing here reaches for a resource. [ReportPalette] is filled in by the caller that has a context, which is
 * what lets a page of this document be laid out, measured and asserted on a machine with no Android at all.
 */
enum class ReportStyle {
    DocumentTitle,
    MetaLine,
    SectionHeading,
    Paragraph,
    KeyValueLabel,
    KeyValueValue,
    TableCell,
    TableHeader,
    ChartTitle,
    ChartLabel,
    Note;

    /** Whether the glyphs of this role are drawn heavier, which is the PDF's only emphasis besides size. */
    val bold: Boolean
        get() = this == DocumentTitle || this == SectionHeading || this == KeyValueValue ||
            this == TableHeader || this == ChartTitle
}

/**
 * One role's measurement rules.
 *
 * [size] and [leading] are points, and a line's height is the leading rather than the size: two lines of
 * 10.5 pt set with no gaps are unreadable, and a page that packed lines by their glyph height would be a page
 * whose text touched itself. [spaceBefore] is the room a role asks for above it — a heading needs more air
 * than a table row because the heading starts a subject.
 */
data class TypeSpec(
    val size: Float,
    val leading: Float,
    val spaceBefore: Float,
    val spaceAfter: Float
)

object ReportTypography {

    /**
     * The spec for [style].
     *
     * A table's leading is tighter than a paragraph's because its rows are compared line by line, and a grid
     * with generous gaps makes a reader follow a row across the page with their finger.
     */
    fun spec(style: ReportStyle): TypeSpec = when (style) {
        ReportStyle.DocumentTitle -> TypeSpec(22f, 27f, 0f, 6f)
        ReportStyle.MetaLine -> TypeSpec(10f, 13f, 0f, 2f)
        ReportStyle.SectionHeading -> TypeSpec(13.5f, 17f, 16f, 4f)
        ReportStyle.Paragraph -> TypeSpec(10.5f, 14.5f, 0f, 4f)
        ReportStyle.KeyValueLabel -> TypeSpec(10.5f, 14f, 0f, 0f)
        ReportStyle.KeyValueValue -> TypeSpec(10.5f, 14f, 0f, 0f)
        ReportStyle.TableHeader -> TypeSpec(9.5f, 13f, 2f, 2f)
        ReportStyle.TableCell -> TypeSpec(9.5f, 12.5f, 0f, 0f)
        ReportStyle.ChartTitle -> TypeSpec(11f, 15f, 4f, 2f)
        ReportStyle.ChartLabel -> TypeSpec(8.5f, 11f, 0f, 0f)
        ReportStyle.Note -> TypeSpec(9f, 12.5f, 2f, 6f)
    }

    /** The gap between two blocks of the document, beyond whatever their own specs asked for. */
    const val BLOCK_GAP: Float = 3f

    /** The room a table's header asks for above the first row of a continuation page. */
    const val TABLE_HEADER_GAP: Float = 3f
}

/**
 * The four ink colours a report draws with.
 *
 * Four, and no more, because a printed page loses its argument when it gains a fifth hue: the accent carries
 * the headings and the accent bar, ink carries the text, the muted tone carries the qualifications and the
 * rules, and green is reserved for finished work — the meaning the list has given it since the first stage.
 * A chart's own shades come from the data (a category wears the colour the user picked), so they are not in
 * this palette and travel inside the blocks instead.
 */
data class ReportPalette(
    val ink: Int,
    val accent: Int,
    val muted: Int,
    val finished: Int,
    /** The shade a rule, an axis or a chart's empty track is drawn in: ink, mostly let through. */
    val hairline: Int
)

/**
 * The page a report is laid out for.
 *
 * A4 portrait in points — 595 by 842 of the 595.28 by 841.89 a printer agrees to, rounded because a twelfth of
 * a point is not a decision anyone can see and `PdfDocument` takes integer dimensions. The margins are the
 * ones a reader can hold: 44 pt at the sides is about 15 mm, enough for a thumb and not enough for the text
 * to look stranded.
 *
 * [footerHeight] is reserved rather than used: the page number goes there, and a layout that measured its
 * content against the full height would have its last line under the footer it forgot to leave room for.
 */
data class ReportPageGeometry(
    val width: Int = A4_WIDTH,
    val height: Int = A4_HEIGHT,
    val marginLeft: Float = 44f,
    val marginRight: Float = 44f,
    val marginTop: Float = 48f,
    val marginBottom: Float = 40f,
    val footerHeight: Float = 22f
) {

    /** The width every block is wrapped to. One number, so nothing can wrap to a different measure twice. */
    val contentWidth: Float get() = width - marginLeft - marginRight

    /** The vertical band a page's content may occupy, from the top margin to where the footer reserve starts. */
    val contentTop: Float get() = marginTop

    val contentBottom: Float get() = height - marginBottom - footerHeight

    /** The height available to one block. A block taller than this is broken rather than pushed off. */
    val contentHeight: Float get() = contentBottom - contentTop

    /** Where a page's first line begins. */
    fun firstBaseline(spec: TypeSpec): Float = contentTop + spec.leading

    companion object {
        const val A4_WIDTH = 595
        const val A4_HEIGHT = 842

        /** The one geometry a report is printed with: A4 portrait, the size the stage's document asks for. */
        val A4_PORTRAIT = ReportPageGeometry()
    }
}
