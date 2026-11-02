package com.taskplanner.data.keep

import com.taskplanner.data.hierarchy.TaskHierarchyPolicy

/**
 * Reads a Google Keep text export into a tree.
 *
 * Pure Kotlin on purpose: no Android context, no Room, no clock. Everything that decides what a reader's notes
 * will look like after an import should be arguable from a string, on the JVM, before it can touch a database.
 *
 * ## The rules, and where each one comes from
 *
 * **An unindented line is a topic of its own.** Not "a task in one big group": the user asked for this twice,
 * and the previous attempt at this import put 382 elements into one group and was wrong for it.
 *
 * **An indented line belongs to the nearest thing above it that can hold it**, found by depth rather than by
 * name — the source's own indentation is the only hierarchy there is.
 *
 * **The indentation unit is measured from the file, not assumed.** The real export uses one space; a hand
 * written example often uses two or four or a tab. The unit is the smallest positive indent the file contains,
 * so the same parser reads all three, and an indent that is not a whole multiple of the unit rounds down to the
 * level it is inside of.
 *
 * **Four levels are the contract** (`MAX_DEPTH` = 3, since a topic is level 0). A fifth is reported and clamped
 * to a sibling of the deepest one rather than dropped, because losing a line is worse than a slightly flatter
 * tree — and the issue says which line was.
 *
 * **Nothing that carries text disappears.** An empty checkbox row (`[ ]` with no title) is a visual separator in
 * the export and is counted apart from blank physical lines; a line with no checkbox at all is kept with
 * [KeepCheck.UNKNOWN] and reported, never quietly marked done.
 *
 * **Titles are verbatim.** Cyrillic, emoji, times, dates, punctuation, duplicates. Whitespace around a title is
 * trimmed — the real file ends several titles with a space before the CR, and that space is not part of a name —
 * and nothing else is touched. There is no field on [KeepNode] for a due date or a priority, so neither can be
 * invented here.
 */
object KeepTextParser {

    /**
     * The deepest level the contract holds: topic, task, subtask, subtask — four levels, indexed from 0.
     *
     * The number is the tree's, not the parser's: [TaskHierarchyPolicy.MAX_TASK_LEVELS] is what the move validation,
     * the card renderer, the backup and the sync stop at too, so a paste that arrives through this file can never
     * write a tree that another component refuses to draw.
     */
    const val MAX_DEPTH: Int = TaskHierarchyPolicy.MAX_TASK_LEVELS

    private val BOX = Regex("^([ \t]*)\\[([ xX])\\](.*)$")

    fun parse(text: String): KeepParsedDocument {
        val body = text.removePrefix("\uFEFF")
        val lines = body.split("\r\n", "\n", "\r").let {
            // A file that ends in a line break does not also have an empty last line.
            if (it.size > 1 && it.last().isEmpty() && (body.endsWith("\n") || body.endsWith("\r"))) it.dropLast(1) else it
        }
        val blankLines = lines.count { it.isBlank() }

        var separators = 0
        val issues = ArrayList<KeepParserIssue>()
        val raw = ArrayList<RawLine>(lines.size)
        lines.forEachIndexed { index, line ->
            val number = index + 1
            if (line.isBlank()) return@forEachIndexed
            val box = BOX.matchEntire(line)
            if (box != null) {
                val (indent, mark, rest) = box.destructured
                if (rest.isBlank()) {
                    separators++
                    return@forEachIndexed
                }
                raw += RawLine(number, indent.length, check(mark), rest.trim())
            } else {
                // A line the syntax does not cover is still the reader's text: kept with an unknown state, and
                // reported here rather than at the end, so the issue carries the line the source numbered.
                val indent = line.takeWhile { it == ' ' || it == '\t' }.length
                issues += KeepParserIssue.NoCheckbox(number, line.trim())
                raw += RawLine(number, indent, KeepCheck.UNKNOWN, line.trim())
            }
        }

        val unit = raw.map { it.indent }.filter { it > 0 }.minOrNull() ?: 1
        val roots = ArrayList<MutableNode>()
        val stack = ArrayList<Level>()

        for (entry in raw) {
            val wanted = entry.indent / unit
            if (wanted == 0) {
                val node = MutableNode(entry, depth = 0)
                roots += node
                stack.clear()
                stack += Level(0, node)
                continue
            }
            while (stack.isNotEmpty() && stack.last().depth >= wanted) stack.removeAt(stack.size - 1)
            val parent = stack.lastOrNull()
            if (parent == null) {
                // The paste began with indentation: there is no topic to hold it, so it becomes one and says so.
                issues += KeepParserIssue.NoParent(entry.line, wanted)
                val node = MutableNode(entry, depth = 0)
                roots += node
                stack += Level(0, node)
                continue
            }
            var effective = parent.depth + 1
            var holder = parent.node
            if (wanted > effective) {
                issues += KeepParserIssue.IndentSkipped(entry.line, wanted, effective)
            }
            if (effective > MAX_DEPTH) {
                issues += KeepParserIssue.TooDeep(entry.line, wanted)
                effective = MAX_DEPTH
                holder = stack.last { it.depth == MAX_DEPTH - 1 }.node
            }
            val node = MutableNode(entry, effective)
            holder.children += node
            stack += Level(effective, node)
        }

        val tree = roots.map(::freeze)
        val all = KeepParsedDocument(tree, emptyStats(), issues).allNodes()
        val counts = all.groupingBy { it.title }.eachCount().values.filter { it > 1 }.sumOf { it - 1 }
        return KeepParsedDocument(
            topics = tree,
            stats = KeepStats(
                topics = tree.size,
                tasks = all.size - tree.size,
                checked = all.count { it.check == KeepCheck.CHECKED },
                unchecked = all.count { it.check == KeepCheck.UNCHECKED },
                unknownStatus = all.count { it.check == KeepCheck.UNKNOWN },
                separatorLines = separators,
                blankLines = blankLines,
                physicalLines = lines.size,
                maxDepth = all.maxOfOrNull { it.depth } ?: 0,
                duplicateTitles = counts
            ),
            issues = issues
        )
    }

    private fun check(mark: String): KeepCheck = if (mark == " ") KeepCheck.UNCHECKED else KeepCheck.CHECKED

    private fun emptyStats() = KeepStats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0)

    private class RawLine(val line: Int, val indent: Int, val check: KeepCheck, val title: String)

    private class MutableNode(val raw: RawLine, val depth: Int) {
        val children: MutableList<MutableNode> = ArrayList()
    }

    private class Level(val depth: Int, val node: MutableNode)

    private fun freeze(node: MutableNode): KeepNode = KeepNode(
        title = node.raw.title,
        check = node.raw.check,
        depth = node.depth,
        line = node.raw.line,
        children = node.children.map(::freeze)
    )
}
