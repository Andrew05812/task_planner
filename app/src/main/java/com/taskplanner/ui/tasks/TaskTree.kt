package com.taskplanner.ui.tasks

import com.taskplanner.data.hierarchy.TaskHierarchyPolicy
import com.taskplanner.data.model.Task

/**
 * How deep each of these rows sits inside its topic, measured from the parent links they carry.
 *
 * A task list that draws every row at the margin is a list that lost the reader's structure on the way to the
 * screen, and Stage 15 is the stage that put that structure in the database: a Google Keep export decides what
 * holds what by indentation, the import keeps it in `Task.parent_uuid` (schema v17), and §10 says the imported
 * subtree has to be *visible* in the task list. This is the visible half of that promise, and it is a pure
 * function over the rows on screen so the four cases that matter — a chain, a missing parent, a chain longer than
 * four levels, and a cycle — can be argued without a device.
 *
 * The walk is deliberately budgeted rather than recursive. `parent_uuid` is a plain column with no database
 * foreign key, so a cycle is not impossible: it is written by the import in a way that cannot form one (a parent
 * is always a line above), but a sync pull or a future editor could. A reader's main thread must not be the place
 * that turns out to be wrong.
 */
object TaskTree {

    /**
     * Task id to the level its card indents to.
     *
     * Measured within the rows given: a parent that is not among them is not a level, because the list cannot
     * show a row it is not holding.
     *
     * The bound is not a number this file owns. The tree's contract is topic + three task levels and it lives in
     * [com.taskplanner.data.hierarchy.TaskHierarchyPolicy.MAX_INDENT_LEVEL], so the parser reading a paste, the
     * mapping writing it, the move that re-hangs a row, this renderer and the backup and sync that carry it all
     * stop at the same depth. Stage 15 ended with two components each holding a constant called 3 and meaning
     * different things by it, and the card was the one that could draw a level the import would never write.
     */
    fun depthsByTaskId(tasks: List<Task>): Map<Long, Int> {
        val bound = TaskHierarchyPolicy.MAX_INDENT_LEVEL
        val parentOf = tasks.associateBy({ it.uuid }, { it.parentUuid })
        return tasks.associate { row ->
            var depth = 0
            var walker = row.uuid
            val seen = HashSet<String>()
            while (true) {
                if (!seen.add(walker)) break // a cycle: stop at whatever depth the walk reached
                val parent = parentOf[walker] ?: break
                if (parent == walker || parent !in parentOf) break
                if (depth == bound) break
                depth++
                walker = parent
            }
            row.id to depth.coerceAtMost(bound)
        }
    }
}
