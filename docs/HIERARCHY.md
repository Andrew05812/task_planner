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

## What Stage 16 has changed since

**The ordering is fixed, and a drag has a boundary.** Both were listed below as Stage 16's owed work; phase 1 of
§16.8 delivered them first, before any indent control existed, because a deeper tree makes each of them worse than a
sorting bug.

- One sequence, one position. `TaskHierarchyRepository.moveWithinSiblings` reads the profile's live rows in the order
  they are stored, lifts the dragged row *and its whole subtree* out of that sequence, sets the block down on the
  side of the row it was dropped on, numbers the result densely again, and writes **only the rows whose position
  moved** — inside one transaction, so a row created or pulled while a finger is down cannot be handed a number the
  re-numbering also just gave somebody. `TaskDao.updateTaskOrders`, which re-numbered `0..n` over whatever list it
  was passed, is gone, and so is `TaskRepository.reorderTasks` and `TasksViewModel.saveVisibleOrder`.
- A row hidden by a filter is not absent from the order. It keeps its place against every row the drag did not cross;
  the screen sends an anchor and a side rather than a list of ids, which is what makes applying and clearing a filter
  unable to change where a dropped row ends up. Measured in `TasksOrderingTest`.
- The boundary is the sibling set: same profile, same topic, same parent. A cross-topic or cross-parent drop is
  refused by name (`HierarchyMoveRefusal`) and writes nothing, so an ordinary drag can never reparent a row. The
  screen refuses it before the list moves, and the repository re-checks against the stored rows.
- A parent never travels alone. `TaskHierarchyPolicy.subtreeOf` gives the block in tree order, and the screen and the
  write use the same function, so what the reader sees drop is what the database stores.
- The first drag on a profile whose positions the old code left doubled or gapped renumbers the whole profile — the
  reconciliation, named in the result as `rowsRewritten`. Every later drag costs the distance it travelled: a swap of
  neighbours writes two rows and leaves the other four holding their stored `updated_at` and `sync_state`.

A new task takes its position from `TaskRepository.nextFreeOrder()`, which counts **every** live row of the profile,
archived ones included: the sequence a drag renumbers includes archived rows, so counting only the visible ones would
land a new task on a number an archived row already holds.

## What Stage 16 still owns

Everything that **changes the shape** of the tree, and it is deliberately not in this build:

* No indent / outdent control in the editor or the list. A tree arrives through an import or is edited by dragging
  within one sibling set; nothing yet moves a row under another row.
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
