package com.taskplanner.data.keep

/** What will happen to this one line if the reader confirms. */
enum class KeepRowStatus {
    /** A row the import writes. */
    WILL_WRITE,

    /** A row this profile already holds from this same text, which the import leaves alone. */
    ALREADY_IMPORTED,

    /** A row whose identity belongs to another profile, which is why a conflict refuses the whole paste. */
    CONFLICT
}

/**
 * One line of the paste as the preview shows it: the title the reader wrote, where it sits in the tree, and what
 * the import would do with it.
 *
 * @param key this line's position in the parsed tree (`"0/1/0"`), used only to open and close a branch on screen.
 *   It is *not* what a row is keyed on in the database — see [KeepImportIdentity] for that, and for why the
 *   position is deliberately absent from it.
 * @param parentKey the line above it, or null for a topic
 * @param checked what the box said, or null when the line carried none — the screen draws a third mark for that,
 *   and the importer writes an open task rather than guessing a completed one
 * @param descendants how many lines hang below this one, so a closed topic can say what it is hiding
 */
data class KeepPreviewRow(
    val key: String,
    val parentKey: String?,
    val title: String,
    val depth: Int,
    val isTopic: Boolean,
    val checked: Boolean?,
    val status: KeepRowStatus,
    val expanded: Boolean,
    val descendants: Int,
    val line: Int
)

/**
 * The tree flattened into the rows a list draws, with each row labelled by what the plan does with it.
 *
 * This is the part of §11 that keeps a preview honest: the rows come from the [KeepImportPlan] the importer would
 * execute, not from a second reading of the text. A screen that walked the document on its own could disagree with
 * the importer about what "3 topics" means, and the reader would only find out afterwards.
 *
 * Closing a topic hides its whole branch and says how many lines are hidden; the topics themselves always stay
 * visible, because a preview whose top level can vanish is not a preview.
 */
object KeepPreviewRows {

    fun of(plan: KeepImportPlan, expandedTopics: Set<String>): List<KeepPreviewRow> {
        val writes = (plan.groups.map { it.uuid } + plan.tasks.map { it.uuid }).toSet()
        val conflicts = plan.conflicts.map { it.uuid }.toSet()
        val children = plan.candidates.groupBy { it.parentKey }

        fun depthOf(candidate: KeepRowCandidate): Int =
            children[candidate.key]?.sumOf { 1 + depthOf(it) } ?: 0

        fun row(candidate: KeepRowCandidate, expanded: Boolean): KeepPreviewRow {
            val node = candidate.node
            val status = when {
                candidate.uuid in conflicts -> KeepRowStatus.CONFLICT
                candidate.uuid in writes -> KeepRowStatus.WILL_WRITE
                else -> KeepRowStatus.ALREADY_IMPORTED
            }
            return KeepPreviewRow(
                key = candidate.key,
                parentKey = candidate.parentKey,
                title = node.title,
                depth = node.depth,
                isTopic = candidate.isTopic,
                checked = when (node.check) {
                    KeepCheck.CHECKED -> true
                    KeepCheck.UNCHECKED -> false
                    KeepCheck.UNKNOWN -> null
                },
                status = status,
                expanded = expanded,
                descendants = depthOf(candidate),
                line = node.line
            )
        }

        val out = ArrayList<KeepPreviewRow>()
        fun open(candidate: KeepRowCandidate) = !candidate.isTopic || candidate.key in expandedTopics
        fun walk(candidate: KeepRowCandidate) {
            out += row(candidate, expanded = open(candidate))
            if (open(candidate)) children[candidate.key]?.forEach(::walk)
        }
        // Topics in source order, each with its branch underneath — which is the order the importer writes too,
        // so the list a reader confirms is the list they read.
        plan.candidates.filter { it.isTopic }.forEach(::walk)
        return out
    }
}
