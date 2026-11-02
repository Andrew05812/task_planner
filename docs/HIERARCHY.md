# The task tree: what exists now, and what Stage 16 still owns

Written on 2026-11-02, next to Stage 15 landing. The point of this file is to keep two claims apart: **a nested
tree is stored and drawn**, and **a nested tree cannot yet be edited**. Reading the first as the second is how a
roadmap lies to the person using it.

## What the schema holds

Schema **v17** added the two columns an import needed, and nothing else:

| Column | Table | Notes |
| --- | --- | --- |
| `parent_uuid` | `tasks` | nullable, no database foreign key. The row above this one, or null when a topic (or nothing) holds it |
| `index_tasks_user_id_parent_uuid` | `tasks` | the tree is read one parent at a time; without it every level of an imported subtree scans the account |
| `is_completed` | `task_groups` | a topic's own checkbox, from a Keep export's root line. Never computed from children, never copied onto them |

`group_id` still says which topic a subtree belongs to, and it is set on **every** row of the subtree, so a topic
owns its tree and the tree stays reachable from it. Migration `16 → 17` is additive — two `ALTER`s and a
`CREATE INDEX`, no table rebuild — and `AppMigrationsTest` holds the schema-equality and old-database
round-trip contracts for it.

## What that makes possible today

* **An import keeps the depth a paste had.** A Google Keep export decides structure by indentation and nothing
  else; `KeepTextParser` reads up to four levels (topic, task, subtask, subtask), and every one of them survives
  into `parent_uuid`. Nothing is flattened silently: a line that skips a level or runs past the fourth is
  reported by line number in the preview and placed at the nearest level that exists.
* **The task list draws it.** `TaskTree.depthsByTaskId` measures each row's depth over the tasks on screen and a
  card indents by one step of `@dimen/task_depth_indent` per level, so an imported subtree looks like the tree
  it came from instead of a flat run under a heading.
* **Collapsing a topic hides its whole branch**, in the preview list; the topics themselves never disappear.
* **Nothing about it invents structure.** A parent is only ever a line that was physically above it in the
  source. Titles never decide a parent, and depth is not inferred from indentation *after* the parse.

## What Stage 16 still owns

Everything that **changes** the tree, and it is deliberately not in this build:

* No indent / outdent control in the editor or the list. A tree arrives through an import or not at all.
* No "move this subtree there". Drag-reorder changes a row's position, never its parent — that is pinned by
  `TaskReorderPersistenceTest`, which asserts `group_id`, `parent_uuid` and `user_id` are unchanged across a
  reorder of a filtered subset.
* **The known ordering defect is still open.** `TasksViewModel.saveVisibleOrder` re-indexes `0..n` over the
  *filtered* subset, so a drag while a filter is active can collide with rows outside it.
  `TaskReorderPersistenceTest` asserts the collision as measured behaviour rather than pretending it is fixed;
  fixing it belongs to Stage 16, where arbitrary reparenting would turn it from a sorting bug into a data bug.
* Deleting a parent says nothing yet about its children — there is no path that creates one, so there is no path
  that orphans one. That sentence stops being true the moment indent/outdent ships, and Stage 16 has to answer it
  in the same change.
* Topics cannot be nested inside topics: `TaskGroup` has no `parent_uuid`, and a Keep export has no syntax that
  would ask for one. Categories keep their own `parent_category_id`, which no screen has ever written.
* Depth beyond four levels is refused at the edge and reported, not stored.

## What a reader sees

A task imported under a topic appears under that topic's header, indented by its level, with the completion box
the source drew. A topic whose own line was `[X]` in Keep has that state **stored and synced** —
`TaskGroup.is_completed`, read out of the database by `KeepImportTransactionTest` and carried on the sync document
— and the task list does not yet *draw* it: nothing in `TasksAdapter` reads the column. Saying so is the point: the
two states are independent facts from the file, nothing aggregates one into the other, and the visible mark on a
topic header is Stage 16's to add. There is no control to change any of that by hand yet; see the list above.
