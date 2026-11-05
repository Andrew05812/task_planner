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

Schema **v18** added one column, for folding a branch:

| Column | Table | Notes |
| --- | --- | --- |
| `children_collapsed` | `tasks` | `INTEGER NOT NULL DEFAULT 0`. Whether the reader hid this row's descendants from the list. A view state, not an edit: no parent link, topic, position or status moves with it |

One column, no index — the flag is never a lookup key, only a fact about rows the profile read already holds — and
`AppMigrationsTest` holds the two contracts §7 asks of a migration: that the result equals the schema Room exports
for v18, and that the `ALTER` moved no row and changed no column the tree is built from. The default is open, because
a notebook written before the column existed never asked to hide anything.

## A branch can be folded, and the fold lives on the row

`TaskHierarchyRepository.toggleBranchCollapse(taskId)` flips that one column and answers
`HierarchyCollapseResult.Changed(taskId, collapsed, hiddenDescendants, rowsWritten)`; a row with nothing under it is
refused `NO_CHILDREN`, and one whose branch cannot be named — an identity two rows claim — is refused
`AMBIGUOUS_IDENTITY`. [TaskHierarchyPolicy](../app/src/main/java/com/taskplanner/data/hierarchy/TaskHierarchyPolicy.kt)
answers the two questions the screen needs: `collapsedAwayIds(rows)` for what is hidden, and `foldDeltas(rows, drawn)`
for what a press would move, both walking the ancestor chain once and bounded against a cycle.
`TasksHierarchyRenderTest` is where the list's side of it is pinned.

**Why it is a column rather than a screen's memory.** `TaskGroup.collapsed` has always been stored, and the reason
holds for a branch: a reader who folds something, turns the phone, restarts the app or switches profile expects the
branch to still be folded. In a `ViewModel` it would be lost, and the loss would look like the list rearranging
itself. §7 asked for the state to be deterministic after leaving the screen and after a restart, and a column is the
only answer that is not a synchronised cache.

**The rule a renderer has to choose, chosen.** A fold hides a branch from the *tree view*, and a search is not the
tree view — it is a question about it. So a query in the search box ignores folds: a reader who closed "Server
migration" can still find the subtask whose name they typed, instead of being told "no tasks found" about a row the
editor can open. A browsing filter — category, status — uncovers nothing, because it selects rows out of the same
notebook rather than asking where something is. Both are asserted, in
`a_search_ignores_the_fold_because_a_query_is_a_question_not_a_way_of_browsing` and
`a_browsing_filter_that_drops_the_row_in_between_cannot_uncover_a_folded_branch`.

**The card has the control now, and line C is where it sits.** `item_task.xml` carries `branchFold` — a digit and a
chevron — as the first child of the action cluster, and `TasksAdapter.bindBranch` paints it from two facts it does not
compute: `TaskRow.foldRows`, which is what a press moves on this screen, and `Task.childrenCollapsed`, which is the
column the list already obeyed. Line A is where a tree control would normally go, and it is where this card cannot
afford one: at 320dp the text column is 208dp, of which 16 is the dot and its gap and 40 is the repeat mark and the bell
(16 + 4 twice), leaving the title the 152dp `TaskCardWidthBudgetTest` measures. A 48dp target bolted in front of the
title takes a fully-marked parent row to about 100dp — twelve characters a line. So the fold is paid for out of line C,
where the app already has a rule for a column that runs out: the measured figures give way before the buttons do. On a
running parent at the narrowest widths that means the work line goes — now the whole line rather than its two numbers
and an orphaned dot — and `a parent row spends its figures on the fold, and never its title` in
`TaskCardWidthBudgetTest` states the cost as arithmetic instead of leaving it to a paragraph.

Four decisions are asserted in [TaskCardBranchTest](../app/src/test/java/com/taskplanner/ui/tasks/TaskCardBranchTest.kt),
and each was chosen rather than fallen into:

* the control is drawn only when `foldRows > 0`, and recomputed from zero on every bind, so a recycled card that drew a
  parent and then a leaf gives the chevron up instead of offering a fold that refuses;
* the number is the screen's, not the notebook's. The first cut counted every descendant of the profile, which is a
  fact about storage and a lie about the press: with a fold *inside* a fold, the outer card promised rows a press would
  not move, and under a filter that had dropped the branch it offered a chevron that changed nothing the reader could
  see. `foldDeltas` therefore answers two questions separately — rows drawn under this one, and rows this one's own
  fold is holding back — and attributes a hidden row to the *nearest* folded ancestor, because opening a row above that
  ancestor does not reveal it. A row drawn closed prints the second number, which is what keeps a folded branch
  openable at all;
* the mode travels on the row. A `ListAdapter` rebinds only what changed, and entering selection mode changes one row's
  tick mark, so a flag read off the adapter at bind time would leave every *other* parent offering a fold that hides
  rows the reader has already committed to deleting. `TaskRow.selectionMode` makes every row differ when the mode
  flips, which is also what finally refreshes the drag handle;
* the whole wrapper is one control: click, ripple and accessible name on `branchFold`, the chevron and the digit marked
  decorative. With the click on the chevron alone the digit was a dead zone inside the reader's aim — a press there fell
  through to the card's own touch handler and opened the task they were reaching to fold.

The state is said twice on purpose: the chevron turns (90° open, 0° closed, the topic header's convention) and the
accessible name changes with it (`branch_collapse` / `branch_expand`), each naming both the state and what the press
does — "Свернуть" and "Развернуть" differ by one unstressed prefix, and TalkBack does not re-announce after a press, so
a label that carries only the verb asks the reader to remember which way they last went. The count rides inside the name
as a plural, so the digit is not also spoken.

**A filter that hides a parent draws the parent anyway, and says so.** §7 is explicit that a filter may not present
somebody's subtask as a root task («нельзя показывать его потомка так, будто он стал корневой задачей»), and offers two
answers to test: keep the ancestor rows as context, or mark the missing parent while holding the real depth. The app
takes the first, because it is the one the brief calls preferred and the one the reader can act on — a row you can see
is a row you can open, and a marker for a parent you cannot reach teaches the reader that something is missing without
letting them get to it. `TaskHierarchyPolicy.missingAncestorsBy(rows, drawnIds)` walks the *profile*, not the screen,
because a partial path leaves the same false root one level down: two generations can be dropped by one filter. It
answers for every drawn row in one pass, because the same walk done per row rebuilds the uuid index per row, and the
tasks screen re-renders every second a timer runs. The renderer emits each dropped ancestor once, topmost first,
immediately above the first answer that needs it, and marks it with `TaskRow.shownForContext` — and an ancestor that
matched is never drawn twice, which a `ListAdapter` keyed on task id would reject as loudly as a reader would.

What a context row is *not* is the part that keeps this safe:

* it is not an answer. The topic header counts `drawnMatches`, not the rows on screen, so "3" still means three rows
  the filter found;
* it cannot join a batch — not by a long press, not by a tick, not by "select all", and not by a topic header's long
  press, which sweeps a topic's answers. The refusals live in the view model, which is the only place that knows which
  rows those were (`contextIds`, written by the last render). One direction stays open on purpose: a row the reader
  selected *while it was an answer*, and which a later filter demoted to scaffolding, keeps its tick and can be
  unticked. A batch the reader cannot see the edge of is worse than a control that adds nothing;
* it offers no fold and no drag handle, and the card's long-press path does not fire on it, and a move cannot be
  anchored on one: `moveTaskBeside` answers a scaffolding anchor the way it answers any other refusal, by drawing the
  stored order again. A fold control that hides rows the reader cannot see is a control that lies about the screen, and
  a row whose place in the list is derived from whichever answer needs it is not a place to drop anything. What stays
  live is deliberate: the row is a real task, so opening it, editing it and the actions on it work as they always did
  — §7's clause is about not presenting a child as a root, not about making an unreachable-by-filter parent untouchable,
  and a delete from that card goes through §9's branch question like any other;
* it is marked in three channels, because alpha alone is a colour claim: a shape at the head of its title
  (`ic_context_branch`, a corner arrow in `ink_text_secondary` — the same glyph as the fold chevron would have meant two
  opposite facts with one picture, and the decorative ink would have been a third of the claim lost under the card's own
  0.78), the category slot on line B reading «показано для контекста» rather than a category name, and the card sitting
  back. The text channel is the one a screen reader and a magnified reader both get. The category *dot* still carries
  the row's own colour, and that is not a fourth channel but a fact: the dot says which category the row belongs to,
  which is true of a scaffolding row exactly as it is true of an answer.

A fold above the path still holds. The chain is inserted *after* the fold has been applied and the fold's hidden set is
still measured over the profile, so a filter cannot uncover a branch the reader closed — which is the same rule §7's
first half pinned, now pinned against the renderer that could break it
(`a_fold_above_the_path_still_holds_the_branch_from_a_filter`). A search gets the path too: the brief asks a hit to make
clear which topic and which parent task it lives under, and drawing the parent above the hit answers that without
inventing a breadcrumb the 320dp card has no room for.

**Three things are still owed a frame.** The mark is 16dp at the head of a title whose padding is the depth indent, so
a *nested* context row spends about 20dp of its own text measure — a compound drawable does not narrow the box, it
narrows the room inside it, which is exactly the arithmetic Robolectric's LEGACY graphics cannot see
(`TaskCardWidthBudgetTest`'s standing note). Russian «показано для контекста» is longer than the English in the slot
that already gives way by §4.1's 48dp rule. And the corner arrow's shape at 16dp, inside a card drawn at 0.78, is a
legibility question the box arithmetic cannot answer. All three are §19's frames; what they can embarrass is the
drawing, never the behaviour.

One more decision lives here rather than in a test name: a context row is filed under the header of the *answer it
leads*, not under the header its own `group_id` names. A subtree is supposed to keep one topic, and where a pull or a
restore left a row whose ancestor names another, filing the ancestor under its own topic would draw a parent below the
child it exists to lead — the false root again, in a shape §7's clause does not cover. `TaskHierarchyRenderTest`'s
sibling, `TasksFilterContextTest`, holds that case.

## A topic's own completed mark, drawn and switchable

Stage 15 saved `task_groups.is_completed` — the `[X]` a Keep export puts on a root line — and carried it to the cloud,
and the tasks screen never drew it. A reader who imported 56 topics, 34 of them checked, got a notebook whose topics
were all identical. §8 is that mark and the press that changes it, and its first rule is that the mark is *only* about
the topic.

**Three channels, and colour is the last of them.** A finished topic's header shows a caption line under its name — a
16dp check in `status_done` beside the words «тема выполнена» / "topic completed" — and the name is struck through,
which is this app's own shape for finished: `ArchiveAdapter` strikes an archived title the same way, so the two screens
say "done" with one vocabulary. An open topic draws neither the line nor the strike. The words are the channel a screen
reader and a magnified reader both get, and the glyph rides inside the same text element as its compound drawable,
which is what keeps the fact spoken once rather than twice — the drawable carries no name of its own, deliberately. And
because §8 asks the header for a TalkBack description, the button that opens the topic's menu is now named «Действия с
темой» / "Topic actions" instead of borrowing the card's word for editing one task. `TopicCompletionTest` reads all of
this off the header the adapter actually paints, including what a recycled holder gives up: the sentence, the glyph and
the strike are each written on both branches, the rule §7 set for the fold control.

**The state lives under the name, not beside it.** A horizontal row gives its weighted child the leftover, so a new
sibling would constrain the topic's name — and this header has no title floor like the card's 152dp to make that
measurable. Put the line beside the name and the JVM width case fails, with the state itself measuring 0px wide: that is
Stage 15's «a weighted view is the one that disappears» in the other direction. Under the name it costs the header
nothing it cannot take back, and an open topic pays nothing at all. Whether the Russian sentence *fits* at 320dp and
font scale 1.3 is a glyph question, and no JVM test in this repository can answer it — §19 photographs it.

**The menu is the door, and it owns the pairing.** The header already had three gestures — the tap folds, a long press
selects the topic's rows, the kebab opens its actions — so the mark joins the kebab rather than buying a fourth gesture
on a row that has spent its width, and a checkbox drawn on the header would compete with the fold chevron standing in
the same place. `GroupHeaderMenu` builds that popup, names the state row for the state the topic does *not* have
(«Отметить тему выполненной» / «Снять отметку о выполнении» — two sentences, because the verbs govern different cases
and share no usable stem), and hands the fragment the state its own sentence promised. That is the part worth naming:
a menu whose label and write live in two files is how «Mark topic completed» ends up setting the flag to `false`. The
fragment keeps what the screen owns — the rename prompt, the delete confirmation, the commands — and a test drives the
popup the app builds through `performIdentifierAction`, so the promise is checked rather than asserted.

**The write is one UPDATE and it cannot reach anything else.** `GroupDao.setCompleted` sets `is_completed`,
`updated_at` and `sync_state = 'PENDING'` for one uuid. `collapsed` is not in the clause, so §7's fold survives the
press; the name, the order and the creation time are not in it either; and the `tasks` table is not mentioned at all,
which is what makes "completing a topic completes its tasks" impossible rather than discouraged. A test reads every
child row back from storage after the write and compares status, time and sync state, and another reads the sibling
columns of the topic itself.

**Four refusals, each with a test.** The mark is never computed from the children: a topic whose every task is done
stays open, and a topic marked done while its tasks are open stays marked. Clearing a topic does not reopen a child the
reader finished. Nothing writes a completion *time* for a topic — `PRAGMA table_info(task_groups)` is what that test
reads rather than the model, because a field added later is exactly what the rest of the file would miss, and a Keep
export carries no timestamp for a root line's checkbox. And the mark is not a permission: the topic's rows stay in the
list, in the same order, under the same fold.

**One hazard removed on the way.** `GroupDao` used to carry `@Update updateGroup(group)` with no caller anywhere in
`app/src` — main, test or instrumented. A whole-row update writes back whatever `is_completed` the caller happened to
be holding, which is the one path that could revert a topic's mark without the reader asking. The two whole-row writers
that are real — `upsertGroup` for sync and restore, `insertImportedGroups` for an import — each carry the column
deliberately, so the dead one is gone rather than documented.

## A row's place in the tree is editable from the row itself

§6.1 asks for indent and outdent on the task row and is exact about the shape it will accept: «не загромождай карточку
постоянными четырьмя кнопками. Допустимы компактное contextual menu, editor actions или раскрываемые controls». One
control does it. It lives in the swipe panel — where this screen already keeps the row's other verbs — so the card
itself spends no width on a shape only some rows can use, and it opens a two-row menu: *Subtask of the row above* and
*One level up*.

**The rule is written once, and the menu reads it.** Whether a press would write anything is
`TaskHierarchyPolicy.indentRefusal` and `TaskHierarchyPolicy.outdentRefusal`'s answer, over one `TreeFacts` value built
from the profile's stored rows. Those two functions were extracted out of `TaskHierarchyRepository`'s own transaction —
the repository now calls them — so a menu that offered what the domain refuses is not a thing that can be
mis-programmed later; `HierarchyMoveTest` (§5) and `HierarchyControlsTest`'s agreement cases are the evidence the
extraction changed what nothing decides.

**The candidate is the row drawn above, inside the same topic block.** An indent hangs a row on what the reader sees
over it, and the screen draws one block per topic header while storage keeps a single sequence in which two topics'
rows alternate. Measuring over the stored order would judge a topic's first row by the last row of the block before it
— refusing a move the reader can plainly see is admissible, and explaining it as "another topic" about a row that is
not above it at all. So `TaskHierarchyPolicy.hierarchyOffers` takes a list of blocks, the render builds exactly the
blocks it emits (a collapsed topic contributes none, and a row the screen does not draw is not a candidate), and the
menu reads the same block back from `TasksViewModel.HierarchyScreen.blockFor`. `HierarchyControlsTest` has one case
per half of that: a row whose stored neighbour belongs to another topic still gets its control, and the screen
publishes the block the menu will use.

**A refusal is a sentence, not an absence.** §6 forbids offering what cannot be done, and §6.3 forbids showing an
impossible destination as clickable. A row that simply vanished is a fact the reader cannot ask about, so the disabled
row carries the reason in the reader's own terms — «Сверху нет строки, к которой её прикрепить», «Ветка не может быть
глубже третьего уровня» — and cannot be pressed. Seven refusals have sentences. Two do not, and they are silent on purpose: a blank or
doubled parent identity is a fault in the notebook rather than a choice anyone made, and the row draws no control at
all when both directions are silent. When only one is — a row that can rise, sitting under a neighbour with no
identity to hang it on — the control is drawn for the direction that works and the unexplainable row is *not shown*.
`HierarchyMenu.isSilent` is derived from `reasonLabel` answering "no sentence exists", so the pair cannot drift apart,
and `a menu row is never titled with a resource that does not exist` walks every refusal in the enum: the two silent
cases used to hand `getString(0)` to the popup, which is not an empty title but a `Resources.NotFoundException` on an
ordinary press.

**Two facts of the screen, not of the tree, also stand the control down.** A scaffolding row (drawn because some other
answer needs the path) and any row while a batch is being picked get nothing: re-parenting would move a reader's real
rows from behind a row nobody pointed at, and a moved row is a row whose children moved with it. Both guards are tested
at the render rather than at the adapter — `a row drawn only to hold the path stands down the offer the rule would make
for it` seeds a three-level chain, filters to the leaf, and asserts the middle row gets no control *even though the
domain would hand it a lift*.

**A refused press leaves the screen on storage.** `runHierarchyCommand` is one handler for all three commands, and its
refusal branch draws the stored order again. That is load-bearing for a drag — the gesture has already `submitList`ed
the rows as the finger left them, so without it the screen would keep an order no write ever produced — and courteous
for an indent, where nothing moved. The device matrix still owes the frame of a refused drag snapping back.

## What that makes possible today

* **An import keeps the depth a paste had.** A Google Keep export decides structure by indentation and nothing
  else; `KeepTextParser` reads up to four levels (topic, task, subtask, subtask), and every one of them survives
  into `parent_uuid`. Nothing is flattened silently: a line that skips a level or runs past the fourth is
  reported by line number in the preview and placed at the nearest level that exists.
* **The task list draws it.** `TaskTree.depthsByTaskId` measures each row's depth over the tasks on screen and a
  card indents by one step of `@dimen/task_depth_indent` per level, so an imported subtree looks like the tree
  it came from instead of a flat run under a heading.
* **Collapsing a topic hides its whole branch**, in the preview list; the topics themselves never disappear.
* **A parent's own card folds its branch.** The chevron and the count on line C write
  `tasks.children_collapsed` through `toggleBranchCollapse`, and the list redraws from the stored column, so the fold
  survives a rotation, a restart and a profile switch without a screen carrying it.
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

## A parent's delete is a choice, and the domain asks for it

`TaskHierarchyRepository.deleteTask(taskId, policy)` answers with a `HierarchyDeleteResult`, and the two operations
are the ones Stage 16 §9 names:

* **`PROMOTE_CHILDREN`** deletes the chosen row and re-hangs its immediate children on whatever held it — the
  grandparent, or the topic. A grandchild keeps hanging from its own parent, and every row keeps its topic, its owner
  and its place among its siblings, so a branch does not fall to the margin because a line above it disappeared. The
  lifted rows are then *re-numbered in the same transaction*: each child takes a place inside the block its head
  headed, with everything under it behind it, so the stored sequence still says the shape the parent links say. A
  promote that wrote parent links and left the numbers alone printed a grandchild below its parent's sibling — the
  indent comes from the links and the order from the sequence, and the two disagreed. The re-arrangement therefore
  reaches no further than the head's own block, and a branch whose child already sat with its own rows moves nothing
  and writes no position. What it does not promise is a small write in every profile: positions are assigned by place,
  so a profile whose numbers hold a gap — one that any earlier delete leaves — is densified by this write exactly as
  the first drag of such a profile densifies it, and rows after the gap are written though they did not move.
* **`DELETE_SUBTREE`** marks the row and every descendant as tombstones. Nothing is physically removed: history is
  retired, so a second device can be told about it and a restore can still read the shape of what the reader had.

Called with **no policy** on a row that has something under it, it writes nothing and answers
`Confirm(childCount, subtreeSize)`. That is the point of the shape: the question is the reader's to answer, and a
repository that guessed would be a repository that could not be undone. `HierarchyDeleteCommandTest` and
`HierarchyDeleteTest` both pin it. The batch case is answered by `deleteBatchSummary(taskIds)`, which reads the whole
selection in one pass and is why the dialog can ask about a *batch* before any of it is written — the rows on screen
are not evidence, because a filter can hide exactly the children that make the question worth asking. It counts each
row once, so a reader who selected both a parent and its own child is told the size of the notebook, not the sum of two
overlapping branches, and the dialog speaks two numbers because the two operations differ by exactly that: the rows
hanging directly under it, and everything the branch would take with it.

Three rules the tree inherits rather than invents, each with a test that fails if it is dropped:

* **A row being measured refuses the whole request** (`MEASUREMENT_RUNNING`). An open interval has seconds already
  spent on it; a delete that stopped the timer on its own would throw that work away or leave the reader believing
  they deleted a task that is still running. Stopping a measurement is an action, not a side effect.
* **A block whose identities are not single-valued is refused** (`AMBIGUOUS_IDENTITY`) rather than guessed at, because
  the set being deleted is named by walking parent links and a doubled `uuid` gives that two answers — the same row
  `docs/HIERARCHY.md`'s audit measured, and the same reason `tasks.uuid` still has no unique index.
* **Tombstoned rows lose their reminder rows in the same transaction**, except an instance of a running series, which
  keeps its lead times because the next instance is built from this row's content. The split is the one the single-row
  delete already made; writing it inside one transaction is what stops a crash leaving an alarm for a card nobody can
  reach. It costs something, though, and the cost is worth stating: the follow-up that unmakes the *wakeups* finds the
  rows already gone, so the delete returns them — `Deleted.retiredReminders` is the set it took out of the live table,
  and `ReminderRepository.cancelRetired` works from that copy. Without it a reader gets no notification for a deleted
  branch (the delivery query filters tombstones) and still leaves an `AlarmManager` wakeup scheduled for it, which is
  the half the old single-row path guaranteed and this one had to re-earn.

The non-database half of the old delete survives as `TaskRepository.finishDelete(rows, retiredReminders)`: the alarms
are cancelled and a stranded series re-materialises. A branch therefore costs exactly the follow-up a single row costs,
and the refusal
reaches the reader as a resource id (`delete_blocked_running`, `delete_row_missing`) rather than as a sentence frozen
in the language the reader has just left.

## One delete command, and every door that can reach a Task

Stage 16 §5 moved that sequence — read the branch, ask when it holds one, write through the tree rules, then perform
the follow-ups that are not database state — out of `TasksViewModel.deleteRows` and into
`TaskDeleteCoordinator` (`data/hierarchy/TaskDeleteCoordinator.kt`), because a sequence that lives in a ViewModel is
reachable by one screen. It answers with `DeleteOutcome(tombstones, refusal, asks)`: a record rather than a sum,
because a batch really can retire two branches, refuse a third that is being measured, and meet a row that grew a
child since the pre-read. It knows no strings, no dialogs and no view models — each screen maps its numbers to its own
state — and both screens now answer through it, which is why the category screen shows the same window rather than a
narrower one.

The doors, each checked at its call site:

| Door | Resolves the tree? | Evidence |
| --- | --- | --- |
| A task card's delete, and the selection bar's | yes — `requestDelete` → `DeleteDialog` → `resolveDelete` → the coordinator | `UnifiedTaskDeleteTest` (25), `HierarchyDeleteCommandTest` (12) |
| A category row's long-press | yes, since §5. It used to call `taskRepository.deleteTask`, one tombstone with no question, and left children naming the row they hang from | `CategoryTaskDeleteTest` (9) |
| The archive's "delete forever" | yes, since §5, through `hardDelete`: the branch is resolved first and the physical verb then takes the rows that are already retired. `parent_uuid` has no foreign key behind it, so the database would not have objected | `ArchiveHardDeleteTest` (4) |
| Account merge (`AuthRepository`), series cancel (`RecurrenceRepository`), a tombstone applied by sync (`SyncAppliers`) | no — none of them is a reader's Task delete | named in Stage 16 §5's audit as open findings, not rewritten |
| Notifications, `TaskRepository.deleteAllTasksForCurrentUser` | nothing to check: the reminder path issues no delete, and the "delete all mine" method has no caller in `app/src` at all | the greps are in the report |

The two public bulk commands that used to sit next to `deleteRows` — `deleteSelected` and `deleteSelectedFor` — are
deleted. They were unwired since §4 and reachable by any caller, which is the shape of a door: a public method that can
write without asking, tested by twelve cases that pinned a route no reader could take. The suites call
`com.taskplanner.testing.deleteThroughTheWindow` instead, which presses, takes the one request the channel holds and
answers it the way the window's buttons do, so what is pinned now is reachable.

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

What is left of the stage's shape-changing work, and what this build knows it still owes a proof to. Editing by hand now
exists in the list (§6.1 below) and nowhere else:

* **§6.1 is in; §6.2 and §6.3 are not.** The row's own hierarchy menu — one control in the swipe panel, two named
  actions, the domain's refusal shown as a disabled reason — is implemented and asserted in a JVM
  (`HierarchyControlsTest`, see "A row's place in the tree is editable from the row itself" above). Still owed: the
  explicit hierarchy block in `TaskDetailFragment` (current topic, parent task, level, and the two moves, with an
  invalid destination shown before the save rather than refused after it), and the free "move to" destination picker —
  which §6.3 only allows if the list is built from admissible nodes, excludes the row's own subtree, shows each
  candidate's topic and parent path, and names the resulting depth in the confirmation. Nothing in §6.1 opens a
  picker: the menu's indent is always "the row above", because that is the only destination this screen can show the
  reader as they press it.
* **Two channels of the context mark have no photograph yet.** §7's filter clause is implemented and asserted in a JVM
  (`TasksFilterContextTest`, see "A filter that hides a parent draws the parent anyway" above), but the 16dp shape at
  the head of a title and the Russian sentence in line B's give-way slot are box arithmetic here and glyph widths on a
  device, so their fit at 320dp and at font scale 1.3 belongs to §19's frame matrix. Nothing about the *behaviour* is
  waiting on that frame; the frame can only embarrass the drawing.
* **The topic's own mark owes a photograph, and a device test.** §8's mark is implemented and asserted in a JVM
  (`TopicCompletionTest`, see "A topic's own completed mark" above), but the same line applies to it as to the context
  row: «тема выполнена» beside a 16dp check, under a struck-through bold name, at 320dp and font scale 1.3, is a glyph
  question this repository's graphics layer cannot answer. Nothing has ever bound a group header under instrumented
  code — `TaskCardWidthBudgetInstrumentedTest` measures the card, not the header — so §19's matrix is where that row
  first gets a camera, and §17.8's `HierarchyInstrumentedTest` is where its fold and its mark get driven by a touch.
* **The topic menu's button is 32dp, and the app's own floor is 48.** `item_group_header.xml` sizes `groupMenu` at
  32dp with 4dp of padding inside it, while `@dimen/touch_target_min` says 48dp and the card's own controls are built
  to it. It is left alone in §8 because the block is about the mark, and widening it is a geometry change to a row this
  stage does not otherwise measure; §16's accessibility pass owns it, with the width cost named there rather than here.
* Deleting a parent now asks which of the two deletes the reader meant (§9 below), but the question is asked *after*
  the first confirm, not instead of it: the list's dialog still opens with "Delete Task?", and the branch question
  follows only when the domain finds rows under the row. Reshaping that into one dialog is the §6 UI pass's.
* **The category screen's delete does not ask yet.** `CategoryTasksViewModel.deleteTask` (`ui/categories/CategoryTasksViewModel.kt:91`)
  still routes through `TaskRepository.deleteTask`, the raw soft-delete, so deleting a parent from a category's own
  list still leaves its children hanging on a tombstone. It is left that way on purpose rather than by accident: the
  two-operation dialog is now built once, in `TasksFragment`, and copying it into a second screen before the §6 pass
  decides what the hierarchy controls look like would mean maintaining two of them. The raw path is the one §6 has to
  retire, and this line is where a reader can check that it still exists.
* Topics cannot be nested inside topics: `TaskGroup` has no `parent_uuid`, and a Keep export has no syntax that
  would ask for one. Categories keep their own `parent_category_id`, which no screen has ever written.
* Depth past the third task level is refused — at the edge of an import, reported and clamped, and now at every move
  that would push a block past it — and it is never stored by either path.

## What a reader sees

A task imported under a topic appears under that topic's header, indented by its level, with the completion box
the source drew. A topic whose own line was `[X]` in Keep has that state **stored, synced and now drawn** —
`TaskGroup.is_completed`, read out of the database by `KeepImportTransactionTest`, carried on the sync document, and
painted on its header by `TasksAdapter.bindTopicCompletion` and switchable from the topic's own menu (see "A topic's own
completed mark" above). The two states remain independent facts: nothing aggregates one into the other and no press on a
topic's mark writes a task. No control changes the *shape* of a tree by hand yet — there is no indent, outdent or
move-to anywhere in the app — but deleting a row that has rows under it now asks which of the two deletes the reader
meant, and that dialog is in the list above rather than below it.
