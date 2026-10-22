package com.taskplanner.data.report.export

/**
 * A report as a document: what it is called, what it was asked about, and the blocks that answer it.
 *
 * [meta] is kept apart from [blocks] because the running header a second page carries is made of the title
 * alone, and because the two lines it holds — the period and the moment of writing — are the parts of a report
 * that must be readable before its first figure is. A document that buried them inside the flow could lose
 * them to a page break, and a report whose reader cannot tell which month it describes is not a report.
 */
data class ReportDocument(
    val title: String,
    val meta: List<String>,
    val blocks: List<ReportBlock>
)

/**
 * What a report is made of once its figures have been turned into sentences.
 *
 * [com.taskplanner.data.report.Report] answers questions — how many, how long, which habit. A reader does
 * not receive answers, they receive a document, and the distance between the two is all the decisions this
 * model holds: which figure is a heading and which is a line of prose, how wide a column may be, what a page
 * is allowed to end with. Those decisions belong in their own type because they are made once and shared by
 * every format that could ever want the report: today the page layout and the PDF, later whatever else prints
 * the same ten sections.
 *
 * What is deliberately *not* here:
 *
 *  * **Figures.** Nothing in a block is computed. A block holds text already chosen from
 *    [com.taskplanner.data.report.Report] by [ReportLayout], so a page and a csv can never disagree about a
 *    number, and a second implementation of "how tall is this bar" can never drift from the first.
 *  * **File formats.** No commas, no quotes, no byte-order marks, no `PdfDocument`. A block does not know
 *    whether it is being wrapped for a page or written as a row.
 *  * **Positions.** A block says what it is; where it lands is [ReportPagination]'s answer, because the same
 *    paragraph lands differently on a page that has three lines left and on one that has thirty.
 *
 * Colours arrive as plain ARGB integers rather than resource ids for the same reason: the layout is built
 * with a context in hand, the pagination is not, and a page break that had to look anything up would be a
 * page break that could not be tested on its own.
 */
sealed class ReportBlock {

    /** The document's own heading — one per report, drawn largest, and the line the header repeats. */
    data class Title(val text: String) : ReportBlock()

    /**
     * One short line under a heading: the period, the zone, the moment the report was assembled.
     *
     * Separate lines rather than one wrapped paragraph because each is a fact a reader may want to point at,
     * and because the second page's header carries the title alone — a meta line that said "and also the
     * timezone" would have to be cut in half to fit.
     */
    data class MetaLine(val text: String) : ReportBlock()

    /** A section's heading. Kept with what follows it, never last on a page. */
    data class Section(val text: String) : ReportBlock()

    /** Prose. Wrapped at the content width, and the only block allowed to be split mid-sentence. */
    data class Paragraph(val text: String) : ReportBlock()

    /** A figure and the words around it, printed as label left and value right. */
    data class KeyValue(val label: String, val value: String) : ReportBlock()

    /**
     * A grid with a header row.
     *
     * Cells are text, never numbers: a table that carried a `Long` would have to decide its own unit and its
     * own locale, which is [ReportLayout]'s job. [ReportColumn.weight] is a share of the content width rather
     * than an absolute measure so one table can be laid out for A4 and for whatever page the reader prints.
     */
    data class Table(val columns: List<ReportColumn>, val rows: List<List<String>>) : ReportBlock()

    /** A picture of series the report already holds, described rather than drawn. */
    data class Chart(val chart: ReportChart) : ReportBlock()

    /** A caption-sized qualification: what a figure does not cover, or why a section says nothing. */
    data class Note(val text: String) : ReportBlock()
}

/** One column of a [ReportBlock.Table]. */
data class ReportColumn(
    val title: String,
    /** Share of the content width this column may use; the weights of a table are normalised, not trusted. */
    val weight: Float,
    /** Whether the column's text hugs its left edge or its right one — figures read right-aligned. */
    val align: ReportAlignment = ReportAlignment.START
)

enum class ReportAlignment { START, END }

/**
 * A chart as the report can vouch for it.
 *
 * Two shapes, because those are the two claims a report's own figures support. [Bars] compares one measure
 * across the days of the period, and [Shares] splits one total across the categories that hold it. A chart
 * that wanted a third shape would need a figure the report does not carry, and drawing one anyway is the thing
 * the stage forbids: a picture whose numbers were chosen for the picture.
 *
 * Every value here is a whole number from the report plus the words that name its unit, and the parts listed
 * are the parts the layout chose to show — a report with thirty categories says so in its caption rather than
 * printing thirty swatches. The scaling, the heights and the axis marks are computed by [ReportPagination]
 * through [com.taskplanner.ui.components.ChartGeometry], the same arithmetic the dashboard's own charts use,
 * so a bar in the PDF and the bar beside it on the screen cannot mean different things.
 */
sealed class ReportChart {

    /**
     * One measure across the days of the period, one bar per day.
     *
     * A single series, because that is the shape that stays honest: the dashboard puts its two questions —
     * how much was measured, how much was finished — beside each other as two charts rather than over each
     * other as one, and a report with a merged picture would be a report whose heights compare seconds with
     * counts.
     */
    data class Bars(
        val title: String,
        val unit: String,
        val columns: List<BarColumn>,
        val color: Int,
        /** The sentence under the plot: the tallest day stated in figures, so a relative picture has a number. */
        val caption: String?
    ) : ReportChart()

    /**
     * One total cut into named parts, largest first.
     *
     * [totalText] and each [ChartPart.valueText] are the words the layout chose for those figures — the bar
     * is scaled by the numbers, and read in the units the reader's own report states them in, which is the
     * same division of labour the tables keep.
     */
    data class Shares(
        val title: String,
        val totalText: String,
        val parts: List<ChartPart>,
        /** What is not a part on this page — the rest of the total, stated rather than left out. */
        val omitted: String?
    ) : ReportChart()
}

/** One column of a bar chart: the day's label under the bar and the figure the bar stands for. */
data class BarColumn(val label: String?, val value: Long)

/** One part of a share chart: the figure the bar is scaled by, the words it is read in, and its own colour. */
data class ChartPart(val label: String, val value: Long, val valueText: String, val color: Int)
