package com.taskplanner.data.keep

/**
 * One line of a Keep export that carries something: a topic, a task, or a task of a task.
 *
 * The five properties are the whole contract, and [KeepTextParserTest] asserts exactly that list. A node has no
 * due date, no priority, no category and no timestamps, because the source text has none — §8 of the stage brief
 * forbids inventing them, and a type with nowhere to put an invented value cannot leak one into the database.
 *
 * @param title the text after the checkbox, trimmed of surrounding whitespace and otherwise verbatim
 * @param check what the box said, or [KeepCheck.UNKNOWN] for a line that had no box
 * @param depth 0 for a root line — a topic — and one more per indentation unit below it
 * @param line the 1-based line number in the source, so an issue can be pointed at
 * @param children the nodes this one holds, in source order
 */
data class KeepNode(
    val title: String,
    val check: KeepCheck,
    val depth: Int,
    val line: Int,
    val children: List<KeepNode>
)

/** What a line said about whether it is done. There is no fourth possibility, and no default to one. */
enum class KeepCheck { CHECKED, UNCHECKED, UNKNOWN }

/**
 * What the parser had to decide that the source did not say.
 *
 * Every issue carries the line it came from, because the reader is being asked to trust a preview with their own
 * notes in it: "one line was ambiguous" is not actionable, "line 118 has no checkbox" is.
 */
sealed class KeepParserIssue {
    abstract val line: Int

    /** A non-empty line with no `[ ]` on it: kept, with an unknown state, and shown. */
    data class NoCheckbox(override val line: Int, val text: String) : KeepParserIssue()

    /** An indented line with no topic above it at all — the file starts with indentation. */
    data class NoParent(override val line: Int, val depth: Int) : KeepParserIssue()

    /** The line jumps past a level the file never opened, so its parent is the deepest one that exists. */
    data class IndentSkipped(override val line: Int, val depth: Int, val attachedToDepth: Int) : KeepParserIssue()

    /** Deeper than the four levels the contract holds; the node is kept and clamped to the last one. */
    data class TooDeep(override val line: Int, val depth: Int) : KeepParserIssue()
}

/**
 * The counts the preview screen shows, all of them derived from the tree rather than counted off the input —
 * so a node that fell between two rules cannot hide in a number.
 */
data class KeepStats(
    val topics: Int,
    val tasks: Int,
    val checked: Int,
    val unchecked: Int,
    val unknownStatus: Int,
    val separatorLines: Int,
    val blankLines: Int,
    val physicalLines: Int,
    val maxDepth: Int,
    val duplicateTitles: Int
)

/**
 * The parse of one paste: the tree, the numbers, and the decisions the parser had to make alone.
 *
 * [topics] is the forest — one node per unindented line — and [allNodes] flattens it in source order, which is
 * what the preview's "what will be imported" list and the importer's loop both need.
 */
data class KeepParsedDocument(
    val topics: List<KeepNode>,
    val stats: KeepStats,
    val issues: List<KeepParserIssue>
) {
    /** Depth-first, in the order the source wrote them. */
    fun allNodes(): List<KeepNode> {
        val out = mutableListOf<KeepNode>()
        fun walk(node: KeepNode) {
            out += node
            node.children.forEach(::walk)
        }
        topics.forEach(::walk)
        return out
    }
}
