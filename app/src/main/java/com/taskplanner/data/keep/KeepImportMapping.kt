package com.taskplanner.data.keep

import com.taskplanner.data.model.Task
import com.taskplanner.data.model.TaskGroup
import com.taskplanner.data.model.TaskStatus

/**
 * A uuid the plan wanted to write and may not.
 *
 * @param uuid the colliding primary key
 * @param title the source line that produced it. This is the reader's own text: it belongs on the preview screen
 *   and nowhere else — not in a log, not in a crash report, not in a commit message.
 * @param owner the profile that already holds that row
 * @param kind which table the collision is in
 */
data class KeepImportConflict(
    val uuid: String,
    val title: String,
    val owner: Long,
    val kind: KeepNodeKind
)

/**
 * What this plan writes, counted from the rows rather than from the text.
 *
 * [KeepStats] describes the *source* — blank lines, separators, physical lines. These numbers describe the
 * *rows*, and they have to: the preview is about to ask for confirmation, and a summary that says "3 tasks"
 * while the plan contains none would get an import approved on the strength of the wrong arithmetic.
 *
 * Every figure here is therefore about the *write*. [nestedTasks] counts the rows this plan inserts under a task,
 * so it is 0 for a paste the profile already owns — which is why the preview's shape line reads
 * [KeepImportPlan.nestedRows] instead, and why a device frame is the reason both exist.
 */
data class KeepImportStats(
    val topics: Int,
    val tasks: Int,
    val completedTopics: Int,
    val completedTasks: Int,
    val nestedTasks: Int,
    val unknownStatus: Int,
    val maxDepth: Int
)

/**
 * The rows an import would write, or the reason it writes nothing.
 *
 * [alreadyImported] is not a failure — it is the honest answer to "I pasted the same text again", and the screen
 * has to be able to say it, because "0 topics, 0 tasks" with no explanation reads as a broken parser.
 *
 * [rowsToWrite] and [rowsAlreadyHere] are the two figures the preview prints beside its tree, and they are counted
 * here rather than off the list the screen is drawing. Measured on a device: with one topic folded, «rows to
 * write» had dropped from 8 to 5 — the three rows of the branch the reader had just hidden — which presents a
 * scroll position as a promise about the database. Folding is a way of looking; the import is the same size either
 * way. [candidates] is every line the paste named, so the two figures and [conflicts] split that count three ways,
 * in the refused case as in the ordinary one.
 */
data class KeepImportPlan(
    val candidates: List<KeepRowCandidate>,
    val groups: List<TaskGroup>,
    val tasks: List<Task>,
    val stats: KeepImportStats,
    val conflicts: List<KeepImportConflict>,
    val alreadyImported: Boolean,
    val rowsAlreadyHere: Int
) {
    /** What the transaction would insert if the reader confirmed this plan as it stands. */
    val rowsToWrite: Int get() = groups.size + tasks.size

    /**
     * How many of the paste's lines hang under a *task* rather than under a topic.
     *
     * A shape figure, so it is read off [candidates] rather than off the rows being written: a re-paste of text the
     * profile already owns writes nothing, and on a device its preview was seen to report «nested under a task 0»
     * beside a tree still drawing its grandchild indented. The paste did not stop nesting because the import had
     * nothing left to do.
     */
    val nestedRows: Int get() = candidates.count { !it.isTopic && it.parentUuid != null }
}

/**
 * A parsed Keep tree turned into the rows a Room transaction will insert.
 *
 * Pure again — no database, no Android, no clock-dependent decision — which is what makes §13's idempotency and
 * collision rules arguable on the JVM before any of them can touch a reader's notes.
 *
 * ## The rules
 *
 * **A topic becomes a [TaskGroup]; a line under it becomes a [Task].** Not a task inside one shared group, and
 * not a group per task. A topic's own `[X]` goes to [TaskGroup.isCompleted] (schema v17) and is neither computed
 * from its children nor copied onto them.
 *
 * **Indentation becomes [Task.parentUuid].** A level-1 line has no task parent; deeper lines chain through
 * `parentUuid`, and every row of the subtree carries the same `groupId`, so the topic still owns the whole tree
 * and the tree is still reachable from it one parent at a time.
 *
 * **Nothing is invented.** No due date, no start or end time, no category, no series, no estimate, and no
 * `completedAt` for a task the source says is finished — the export carries no completion time, and stamping the
 * moment of import would record a fact the reader never had. Where the model needs a value it gets the model's
 * neutral default ([TaskStatus.TODO] for an unchecked or box-less line), which §8 allows and the preview states.
 *
 * **Identity comes from [KeepImportIdentity]**, so a second paste is recognised as the rows it already wrote and
 * skipped, and a line the reader edited becomes a new row instead of silently rewriting the old one.
 *
 * **A uuid somebody else owns stops the import.** [com.taskplanner.data.local.GroupDao.upsertGroup] is an
 * `OnConflictStrategy.REPLACE` insert, so a database-level "conflict" there means the reader's topic gets deleted
 * and ours lands where it was. The plan reports the collision and returns *no rows at all*, so the importer never
 * has a half-plan to write and never reaches for an upsert.
 *
 * ## The tripwires
 *
 * Two conditions throw instead of degrading, because each means the parser handed this file a tree the contract
 * says it cannot produce, and quietly flattening one would be a wrong import rather than a loud one: a node
 * deeper than the four levels [KeepTextParser.MAX_DEPTH] allows, and a task whose parent is neither in this plan
 * nor already owned by the importing profile. Both are `IllegalStateException` carrying a line number and no
 * title; §14's error path catches them and shows a failed import with nothing written.
 */
/**
 * One line of the paste with its derived identity, its place in the tree and the topic that owns it — the unit
 * both [KeepImportMapping.candidates] and [KeepImportMapping.plan] work on.
 *
 * @param parentUuid the task above it, or null when a topic (or nothing) holds it
 * @param groupUuid the topic this row's whole subtree belongs to; for a topic row, its own uuid
 */
data class KeepRowCandidate(
    val node: KeepNode,
    val uuid: String,
    val parentUuid: String?,
    val groupUuid: String,
    val key: String,
    val parentKey: String?
) {
    val isTopic: Boolean get() = node.depth == 0
}

object KeepImportMapping {

    /**
     * The rows this paste names, in source order, before anybody says whether they may be written.
     *
     * Public because the question "is this uuid already taken?" can only be asked once the identities exist:
     * [com.taskplanner.data.keep.KeepImportRepository] derives them here, reads the owners of exactly those uuids,
     * and hands both to [plan]. Recomputing them is cheap and cannot disagree with itself — that is the property
     * §13 asked for.
     */
    fun candidates(document: KeepParsedDocument, userId: Long): List<KeepRowCandidate> {
        val out = ArrayList<KeepRowCandidate>()
        val occurrences = HashMap<String, Int>()
        document.topics.forEachIndexed { index, topic ->
            collect(topic, "", null, null, index.toString(), null, userId, occurrences, out)
        }
        return out
    }

    /**
     * @param document what [KeepTextParser.parse] produced from the paste
     * @param userId the profile asking; every row is owned by it and no other profile's rows are read or touched
     * @param existingGroups uuid to owning profile, for the group uuids this plan could land on
     * @param existingTasks uuid to owning profile, likewise for tasks. A map rather than a set because the two
     *   cases look identical from a uuid alone and must not be treated alike: a row this profile wrote from this
     *   same text is skipped, a row another profile owns is a conflict that refuses the import.
     * @param firstGroupSortOrder where in the reader's group list the imported topics start. The screen passes
     *   `GroupDao.nextSortOrder(userId)`, because [com.taskplanner.data.local.GroupDao] orders topics by
     *   `sort_order` and an import that numbered from zero would reorder topics the reader already arranged.
     * @param firstTaskOrder likewise for [Task.order], which this app treats as a profile-wide manual-list
     *   position ([com.taskplanner.ui.tasks.TasksViewModel.nextOrder] is `max(order) + 1`), not as an index
     *   inside a parent. Source order is preserved either way: a parent's children keep their relative order
     *   because the numbering runs in source order.
     */
    fun plan(
        document: KeepParsedDocument,
        userId: Long,
        existingGroups: Map<String, Long>,
        existingTasks: Map<String, Long>,
        firstGroupSortOrder: Int = 0,
        firstTaskOrder: Int = 0
    ): KeepImportPlan {
        val candidates = candidates(document, userId)

        val groups = ArrayList<TaskGroup>()
        val tasks = ArrayList<Task>()
        val conflicts = ArrayList<KeepImportConflict>()
        // Every uuid this profile already has or is writing now, which is what a child's parent has to be found in.
        val reachable = HashSet<String>()
        var groupSort = firstGroupSortOrder
        var taskOrder = firstTaskOrder
        var completedTopics = 0
        var completedTasks = 0
        var nestedTasks = 0
        var unknownStatus = 0
        var maxDepth = 0
        // Counted as the loop goes, because it is the only place that sees each candidate's owner. `else` is a
        // conflict and `null` is a write; this is the third answer, and the preview has to be able to give it.
        var alreadyHere = 0

        for (candidate in candidates) {
            val node = candidate.node
            if (node.depth > KeepTextParser.MAX_DEPTH) {
                throw IllegalStateException(
                    "Keep import refused: line ${node.line} is level ${node.depth + 1}, past the " +
                        "${KeepTextParser.MAX_DEPTH + 1} levels the contract holds."
                )
            }

            if (candidate.isTopic) {
                when (val owner = existingGroups[candidate.uuid]) {
                    null -> {
                        groups += TaskGroup(
                            uuid = candidate.uuid,
                            userId = userId,
                            name = node.title,
                            sortOrder = groupSort++,
                            isCompleted = node.check == KeepCheck.CHECKED
                        )
                        if (node.check == KeepCheck.CHECKED) completedTopics++
                        if (node.check == KeepCheck.UNKNOWN) unknownStatus++
                        reachable += candidate.uuid
                    }
                    userId -> {
                        reachable += candidate.uuid
                        alreadyHere++
                    }
                    else -> conflicts += KeepImportConflict(candidate.uuid, node.title, owner, KeepNodeKind.TOPIC)
                }
            } else {
                // The position is consumed by every task in source order, including the ones that turn out to
                // already exist, so a re-paste never leaves a hole in the reader's manual list.
                val position = taskOrder++
                when (val owner = existingTasks[candidate.uuid]) {
                    null -> {
                        requireParentKnown(candidate, reachable, existingTasks, existingGroups, userId)
                        tasks += Task(
                            uuid = candidate.uuid,
                            title = node.title,
                            description = "",
                            status = if (node.check == KeepCheck.CHECKED) TaskStatus.COMPLETED else TaskStatus.TODO,
                            groupId = candidate.groupUuid,
                            parentUuid = candidate.parentUuid,
                            userId = userId,
                            order = position
                        )
                        if (node.check == KeepCheck.CHECKED) completedTasks++
                        if (node.check == KeepCheck.UNKNOWN) unknownStatus++
                        if (candidate.parentUuid != null) nestedTasks++
                        reachable += candidate.uuid
                    }
                    userId -> {
                        reachable += candidate.uuid
                        alreadyHere++
                    }
                    else -> conflicts += KeepImportConflict(candidate.uuid, node.title, owner, KeepNodeKind.TASK)
                }
            }
            maxDepth = maxOf(maxDepth, node.depth)
        }

        if (conflicts.isNotEmpty()) {
            // Refuse the whole import: no subset of the rows is safe to write, because the rows left out are the
            // ones the reader would notice missing.
            return KeepImportPlan(
                candidates = candidates,
                groups = emptyList(),
                tasks = emptyList(),
                stats = KeepImportStats(0, 0, 0, 0, 0, 0, 0),
                conflicts = conflicts.toList(),
                alreadyImported = false,
                rowsAlreadyHere = alreadyHere
            )
        }

        return KeepImportPlan(
            candidates = candidates,
            groups = groups.toList(),
            tasks = tasks.toList(),
            stats = KeepImportStats(
                topics = groups.size,
                tasks = tasks.size,
                completedTopics = completedTopics,
                completedTasks = completedTasks,
                nestedTasks = nestedTasks,
                unknownStatus = unknownStatus,
                maxDepth = maxDepth
            ),
            conflicts = emptyList(),
            alreadyImported = candidates.isNotEmpty() && groups.isEmpty() && tasks.isEmpty(),
            rowsAlreadyHere = alreadyHere
        )
    }

    /**
     * Depth-first, in source order, so a parent is always collected before the children that name it — which is
     * why a cycle cannot reach the database from here and why the `reachable` check below is a tripwire rather
     * than the mechanism.
     *
     * @param path this node's structural position: `"2"` for the third topic, `"2/0/1"` for the second child of
     *   the first child of it. It is derived from indentation, never from a title, so two lines with the same
     *   words are two positions.
     * @param parentTaskUuid the task above this one, or null when a topic holds it — [Task.parentUuid] stays null
     *   for a topic's direct children, because those belong to the group rather than to a task.
     * @param groupUuid the enclosing topic's uuid, carried by every row of the subtree.
     */
    private fun collect(
        node: KeepNode,
        parentKey: String,
        parentTaskUuid: String?,
        groupUuid: String?,
        key: String,
        parentRowKey: String?,
        userId: Long,
        occurrences: MutableMap<String, Int>,
        out: MutableList<KeepRowCandidate>
    ) {
        val isTopic = node.depth == 0
        val occurrence = occurrence(occurrences, parentKey, node.title)
        val uuid = KeepImportIdentity.uuidOf(
            if (isTopic) KeepNodeKind.TOPIC else KeepNodeKind.TASK, userId, parentKey, node.title, occurrence
        )
        // A topic owns its own subtree; below a topic the group link stays the topic's uuid all the way down,
        // while the parent link chains task to task.
        val ownGroup = groupUuid ?: uuid
        out += KeepRowCandidate(node, uuid, parentTaskUuid, ownGroup, key, parentRowKey)
        node.children.forEachIndexed { index, child ->
            collect(child, uuid, if (isTopic) null else uuid, ownGroup, "$key/$index", key,
                userId, occurrences, out)
        }
    }

    /** Which copy of an identical title under the same parent this is, counted as the walk meets them. */
    private fun occurrence(counts: MutableMap<String, Int>, parentPath: String, title: String): Int {
        val key = "$parentPath|$title"
        val seen = counts[key] ?: 0
        counts[key] = seen + 1
        return seen
    }

    private fun requireParentKnown(
        candidate: KeepRowCandidate,
        reachable: Set<String>,
        existingTasks: Map<String, Long>,
        existingGroups: Map<String, Long>,
        userId: Long
    ) {
        val parent = candidate.parentUuid ?: return
        if (parent in reachable) return
        if (existingTasks[parent] == userId || existingGroups[parent] == userId) return
        throw IllegalStateException(
            "Keep import refused: the task at line ${candidate.node.line} names a parent this profile does not " +
                "hold, so its place in the tree is unknown."
        )
    }
}
