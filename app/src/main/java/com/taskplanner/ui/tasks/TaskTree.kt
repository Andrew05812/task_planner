package com.taskplanner.ui.tasks

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
     * The deepest indent a card may draw, counted from zero at a topic's own child.
     *
     * A four-level paste — topic, task, subtask, subtask, which is what
     * [com.taskplanner.data.keep.KeepTextParser.MAX_DEPTH] allows — reaches indent 2, because the topic is not a
     * level of indentation. So this bound is not the paste's own limit; it is the ceiling on whatever else can
     * write `parent_uuid`, and the reason it is 3 rather than 2 is that the indent a reader sees must never exceed
     * the width the 320dp frames prove, whatever a sync pull or a future editor decides to chain.
     */
    const val MAX_INDENT_LEVEL: Int = 3

    /**
     * Task id to the level its card indents to.
     *
     * Measured within the rows given: a parent that is not among them is not a level, because the list cannot
     * show a row it is not holding.
     */
    fun depthsByTaskId(tasks: List<Task>): Map<Long, Int> {
        val parentOf = tasks.associateBy({ it.uuid }, { it.parentUuid })
        return tasks.associate { row ->
            var depth = 0
            var walker = row.uuid
            val seen = HashSet<String>()
            while (true) {
                if (!seen.add(walker)) break // a cycle: stop at whatever depth the walk reached
                val parent = parentOf[walker] ?: break
                if (parent == walker || parent !in parentOf) break
                if (depth == MAX_INDENT_LEVEL) break
                depth++
                walker = parent
            }
            row.id to depth.coerceAtMost(MAX_INDENT_LEVEL)
        }
    }
}
