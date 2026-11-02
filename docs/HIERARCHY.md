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

## The depth, in one place

Level 0 is the topic. A task directly under it is level 1, its child level 2, that child's child level 3, and the
tree stops there: **four levels counting the topic, three levels of task, two steps of indent on the card.** That is
what `TaskHierarchyPolicy.MAX_TASK_LEVELS` holds, and what everything else now reads instead of keeping its own copy:

| Component | What it uses | What it means there |
| --- | --- | --- |
| `KeepTextParser.MAX_DEPTH` | `MAX_TASK_LEVELS` | a pasted line deeper than this is reported and clamped, never stored |
| `TaskHierarchyPolicy.fitsUnder` | `MAX_TASK_LEVELS` | an indent/outdent/move that would push any row of the block past level 3 is refused |
| `TaskTree.depthsByTaskId` | `MAX_INDENT_LEVEL` = `MAX_TASK_LEVELS - 1` | the deepest indent a card may draw |

Stage 15 ended with both components holding a constant called `3` and meaning different things by it — the parser
counted the topic as level 0, the renderer counted indents — so the card could draw a fourth level the import would
never have written, and no test connected the two. `HierarchyMoveTest >
one_depth_contract_holds_for_the_parser_the_renderer_and_the_moves` is the guard that they are one number again, and
`TaskTree` no longer owns a depth constant at all.

The bound is measured over the **whole block** a move carries, not over the row that was grabbed: a leaf can reach
level 3, while the same row with a child under it cannot be indented at all, because its child would land on level 4.

## What Stage 16 has changed since

**The tree can be edited, at the repository.** `TaskHierarchyRepository` now answers `indentTask`, `outdentTask`,
`moveSubtreeUnder` and `moveWithinSiblings`, each in one transaction, each answering with a named
`HierarchyMoveResult` — and no screen offers the controls yet (§6). What the operations guarantee:

* **An indent hangs a row on the row above it** — the predecessor in the profile's own sequence, or the row the screen
  names when a filter means the reader's neighbour is not the stored one. Never a row picked for its title, never one
  from another topic. The row becomes its last child and keeps its own children; a destination whose identity two rows
  claim is refused, because the level validated and the level drawn would then come from two different chains.
* **An outdent lifts a row one level** and sets it after the subtree it came out of, so the branch it leaves still
  reads as one branch. A row that is already a topic's own child has nowhere further up to go, and neither has one
  whose grandparent is gone: lifting onto a link that names nothing would hide where the row really sits.
* **A move takes the whole subtree and its topic with it**: `group_id` is rewritten on every row of the block in the
  same transaction, so a parent never ends up in one topic with its children in another, and the destination topic
  must be one this profile owns *and has not deleted*. Moving into the unfiled tree, or out of it, is the same
  operation with a different destination.
* **A row arriving as a topic root lands after the last root's whole branch**, not after the root row itself — the
  alternative would draw a reader's existing children under a row that is not their parent, and renumber them for a
  move that never touched them.
* **A cycle is refused by name.** A row cannot be hung on its own descendant, on itself, on a row from another
  profile, on a row that carries no identity, or on a row whose identity two rows claim. `Task.parent_uuid`'s old
  comment — that a cycle is impossible because a parent is always a line above — was written when an import was the
  only writer, and it stopped being true the moment these edits shipped; the comment now says who refuses what.
* **A refusal writes nothing.** Every refusal case in `HierarchyMoveTest` re-reads the profile afterwards and compares
  the whole shape — rows, parent links, topics, positions and whether each row is waiting for a push — with what was
  there before. One edit also stamps every row it wrote with the same instant, so two rows of one tree cannot disagree
  about when the change happened.

The depth, the parent's owner and topic, and the knowability of the block are checked before any row is written, and
the check reads the same rows the write does, inside the same transaction.

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

## Can a parent be ambiguous? measured, and what the write does about it

`tasks.parent_uuid` names a parent by `tasks.uuid`, and nothing in the schema makes that name unique: `uuid` carries
`@ColumnInfo(defaultValue = "''")` and `TaskDao.getTaskByUuid` is a `LIMIT 1` with no owner. Stage 16 §4 asks what
that costs before a move control trusts it, so the question was measured rather than argued.

**What the device's own database holds** (read 2026-11-02 from the emulator's `taskplanner.db`, `user_version` 17,
58 live rows over 18 profile ids; the `-wal` was copied alongside so the read saw the live state, and nothing on the
device was modified — the measurement is reproducible with `py scripts/audit-task-identity.py <copy-of-the-db>`):

| Question | Measured |
| --- | --- |
| rows with an empty `uuid` | 0 |
| duplicate `uuid` inside one profile | 0 groups |
| one `uuid` held by two profiles | 0 |
| rows carrying a `parent_uuid` at all | 0 |
| parents that resolve to no row, to another profile, or to another topic | 0 — there is nothing to resolve |
| cycles | 0 |
| live rows sharing a position within one profile | 50 rows across 10 groups, in the ten 5-row profiles instrumented classes create |
| `reminders` whose `task_uuid` matches no row in `tasks` | **8 of 8** |
| a unique index over `(user_id, uuid)` | none; the only unique index on `tasks` is `(series_uuid, occurrence_at)` |

The honest limit on that table: **this device has no user data.** `users` is empty, `task_groups` is empty, every row
is `TODO`/`PENDING`, and the doubled positions sit in the profiles that instrumented fixtures insert with the default
`order` — they never went through the drag code at all. So the audit proves the *shape* of the hazard, not its size in
anyone's notebook.

And one row of that table is the proof that matters for Stage 16: **eight reminders point at tasks that do not exist.**
A key without a foreign key does dangle in this app's real storage, in a column that shipped three stages before the
tree did. `parent_uuid` is the same kind of column, so the code that reads it behaves as if dangling and doubled links
were ordinary — because on this device, one of the two already is.

**No unique index was added, and why that is a blocker rather than a preference.** `UNIQUE(user_id, uuid)` for
non-empty uuids would make an ambiguous parent impossible by construction, and on the data above it would build
cleanly. But the `CREATE UNIQUE INDEX` runs inside a migration on databases nobody here has measured, and an index
that meets a duplicate fails — which leaves a reader with an app that will not open, an outcome worse than the
ambiguity it was added to prevent. There is no safe automatic backfill either: re-issuing a uuid means rewriting every
table that references it (`reminders.task_uuid`, `goal_tasks.task_uuid`, `work_sessions.task_uuid`,
`tracking_sessions.task_uuid`), and the device measurement above shows those references already point at rows that are
gone. So no migration was attempted and nothing was repaired with `INSERT OR REPLACE`.

**What guarantees an unambiguous parent instead** — in the write path, where it can be tested:

* every read that decides a tree is scoped to the signed-in profile (`getRowsInStoredOrder(userId)`), so another
  account's row sharing an identity is not a candidate parent and not part of a block;
* `TaskHierarchyPolicy.ambiguousUuids` names the identities more than one live row claims, and `blockIsKnowable`
  refuses a move whose head or block carries one: `HierarchyMoveRefusal.AMBIGUOUS_PARENT`. "Everything under this
  uuid" is a question with two answers, and the app does not pick one;
* a blank uuid and a blank `parent_uuid` are treated as "no parent" in both directions, so legacy rows are not
  silently adopted into one giant subtree;
* the refusal is deliberately narrow: two children of a doubled parent are still siblings, and reordering them needs
  no answer about who their parent is. Freezing the whole profile would turn one bad row into a reader who cannot use
  their list;
* a link to a deleted row is left alone. A reorder neither clears it nor invents a new parent.

`TaskIdentityAuditTest` pins all five, including the cross-profile case and the tombstone case.

**What is still open, named rather than hidden.** `ReminderRepository` resolves a reminder's task through the
unscoped `getTaskByUuid` (`:211`, `:253`, `:265`). With the device's eight dangling links that code path already
returns null today, and with two profiles sharing an identity it could answer with the wrong account's row. The
hierarchy no longer depends on it; the reminder path is not Stage 16's subject, so it is recorded here for the stage
that owns reminders rather than quietly rewired in a hierarchy commit.

## What Stage 16 still owns

Everything that **changes the shape** of the tree, and it is deliberately not in this build:

* No indent / outdent control in the editor or on the row, and no "move to" sheet. The operations exist and are
  tested at the repository; nothing offers them yet, so a tree still arrives through an import and is edited by
  dragging within one sibling set.
* Deleting a parent says nothing yet about its children — there is no path that creates one, so there is no path
  that orphans one. That sentence stops being true the moment indent/outdent ships, and Stage 16 has to answer it
  in the same change.
* Topics cannot be nested inside topics: `TaskGroup` has no `parent_uuid`, and a Keep export has no syntax that
  would ask for one. Categories keep their own `parent_category_id`, which no screen has ever written.
* Depth past the third task level is refused — at the edge of an import, reported and clamped, and now at every move
  that would push a block past it — and it is never stored by either path.

## What a reader sees

A task imported under a topic appears under that topic's header, indented by its level, with the completion box
the source drew. A topic whose own line was `[X]` in Keep has that state **stored and synced** —
`TaskGroup.is_completed`, read out of the database by `KeepImportTransactionTest` and carried on the sync document
— and the task list does not yet *draw* it: nothing in `TasksAdapter` reads the column. Saying so is the point: the
two states are independent facts from the file, nothing aggregates one into the other, and the visible mark on a
topic header is Stage 16's to add. There is no control to change any of that by hand yet; see the list above.
