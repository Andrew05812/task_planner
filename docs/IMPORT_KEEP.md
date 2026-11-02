# Importing a Google Keep export

The reader's notebook arrives as text. This document is about what happens between pasting it and seeing the
topics in the list — which rules decide the tree, what the app refuses to invent, what a repeat paste does, and
the four things that can stop an import.

**The user-facing route.** Settings → *Import from Google Keep* → paste → *Preview* → read → *Import*. No file
picker, no ZIP, no JSON, no external converter. The JSON backup import is a different route and keeps its own
merge/replace semantics; a Keep import is always a merge into the signed-in profile.

## 1. What the text means

`KeepTextParser` (`app/src/main/java/com/taskplanner/data/keep/`) is pure Kotlin: one `String` in, a tree out, no
Android, no Room, no clock. Everything below is arguable on the JVM before any of it can touch a database.

| The source says | The app reads | Why |
| --- | --- | --- |
| `[X]`, `[x]` | completed | Both spellings, because Keep writes them both |
| `[ ]` | open | |
| a line with no indentation | a **topic**, of its own | The rule the user stated twice: 56 unindented lines are 56 topics, not one group of 382 elements |
| an indented line | a **task** of the nearest thing above it that can hold it | Indentation is the only hierarchy the format has; titles never decide it |
| `[ ]` / `[X]` with no text | a separator: counted, imported as nothing | 57 of them in the real export, and a task with an empty title is not what they are |
| a line with no checkbox at all | kept, with no answer about completion, and reported | §7: the app does not guess `done`, and does not drop the reader's text |
| `\r\n`, `\n`, `\r`, a leading BOM | the same text | Packaging, not content |
| four levels | four levels | `MAX_DEPTH = 3` counting a topic as level 0; a fifth is reported and placed at the fourth, because losing a line is worse than a slightly flatter tree |

Titles are copied character for character — Cyrillic, emoji, times, dates, punctuation, typos, duplicates. The
only thing stripped is whitespace around a title, because the real file ends several titles with a space before
the `CR`, and that space is not part of anybody's note.

The indentation unit is measured from the file (the smallest positive indent it contains), which is why the same
parser reads the user's one-space export and a hand-written four-space example without a mode switch.

## 2. What becomes what

Schema v17 made the two decisions below possible; before it there was nowhere to put either.

| Parsed | Stored | Detail |
| --- | --- | --- |
| topic | `task_groups` row, `is_completed` from the topic's own box | A topic's `[X]` is a fact about the topic. It is never computed from the children, and completing a topic does not open or close anything under it |
| level-1 line | `tasks` row with `group_id` = the topic, `parent_uuid` = null | |
| level 2–4 line | `tasks` row with `group_id` = the same topic, `parent_uuid` = the task above it | `Index(user_id, parent_uuid)` because the tree is read one parent at a time |
| anything with a box that says done | `status = COMPLETED`, `completed_at = NULL` | The export does not state when a note was finished, so importing would be a lie to write as a completion time |
| dates and times inside a title | the title | `12.03.27 - 09:30 - Ул. Примера, д. 7` is a task name, not a due date. No category, priority, reminder, recurrence, start/end time or estimate is invented, because the source has none of them |

Where the model needs a value the source does not give, it gets the model's neutral default (`TODO`,
`Priority.MEDIUM`, no category). The preview says so in words rather than leaving the reader to discover it.

## 3. Repeating a paste

A row's identity is derived, not random: `UUID.nameUUIDFromBytes` over the profile, the identity of the row above
it, the verbatim title, and which copy of that title it is among its siblings.

* Paste the same text again — every uuid is already this profile's, the plan comes back with no rows, and the
  screen answers *"this text is already imported; nothing was written"*.
* Edit one line and paste again — that line is a new row; everything untouched keeps its identity. Position is
  deliberately **not** in the key, so inserting a line at the top of a topic does not re-identify the ninety-five
  under it.
* The paste's own fingerprint travels as a marker (it is what a log may quote without repeating anybody's note)
  and is deliberately **not** in the key either: with it in, one corrected typo would re-import all 382 rows.

The cost of that choice is stated rather than hidden: two *different* pastes whose root lines carry the same
titles merge into the same topics, and renaming a topic in Keep and pasting again brings its subtree back as new
rows. The preview shows both cases before anything is written — each row is labelled *new*, *already here*, or
*belongs to another profile* — so a merge is something the reader sees, not something the app decided
afterwards.

A uuid that another profile holds is a different matter. `GroupDao.upsertGroup` and `TaskDao.insertTask` are
`OnConflictStrategy.REPLACE` inserts, and REPLACE at that moment means *delete the reader's row and put ours
where it was*. An import therefore writes through `insertImportedGroups` / `insertImportedTasks` — ABORT inserts,
never REPLACE — and the plan refuses the whole paste rather than importing the part that happened to be free. The
two halves of that are not equally strong, and the difference is worth knowing before trusting it:
`task_groups.uuid` is the primary key, so a group that appears between the plan and the commit really does abort
the transaction, while `tasks.uuid` has no unique index at all (the task table's key is an autoincrement `id`), so
for a task the same collision is caught only by the ownership read the plan takes — not by the database.
`KeepImportRepository`'s own note carries the same split.

One consequence of deriving identity from what the profile owns: **a topic or task the reader deleted is still
owned by them.** A soft-deleted row answers the ownership query, so pasting the same text again reports it as
already imported and writes nothing, rather than resurrecting it. That is deliberate for this stage — importing is
not an un-delete, and inventing revive semantics here would decide a policy that Stage 16's move and delete rules
have not been written yet — but it is the kind of thing a reader meets, so it is written down rather than left to
be discovered.

## 4. What an import does before it writes

1. the current profile — an import has one owner, and it is never chosen from the text;
2. the parse, the derived identities, then the owners of exactly those uuids (chunked, so a 382-line paste does
   not load a ten-thousand-task account);
3. the plan — which is what the preview shows, and what `Import` is armed from;
4. **a verified safety copy** through `ProfileSafetyCopy`, the Stage 13 mechanism: WAL checkpointed, the source
   hashed, the copy reopened and every profile-owned table counted inside it. If that step refuses, nothing is
   written; the import that could not be undone is the one this stage will not perform;
5. one Room transaction: the topics, the tasks, `PRAGMA foreign_key_check` read on the transaction's own
   connection, then the commit — or none of it.

Sixteen tables carry a profile's data. An import writes two of them, and `KeepImportTransactionTest` counts the
other fourteen before and after, because *"the import only added my notes"* is also a claim about the reader's
goals, habits and measured work. A failure injected at either table — a SQLite `BEFORE INSERT` trigger, the same
instrument the sign-in handover is measured with — leaves both exactly as they were.

## 5. What the preview shows

* The counts, as a label beside a number: topics, tasks, marked done, left open, nested under a task, lines with
  no checkbox, separators skipped, blank lines skipped, repeated titles, the deepest branch, rows to write, rows
  already here.
* The tree, in source order, one row per line, indented by depth, each with the box the source drew (☑ / ☐ / ·),
  the note's own words, and under them what will happen to it. The first three topic branches come open; the rest
  wait for the reader.
* The warnings, each naming its line: no checkbox, indentation that skips a level, a line deeper than four, an
  indented line with nothing above it.
* The notes that say what the format cannot carry, that a topic keeps its own mark, and that the import merges.

Confirming writes what that list said, in that order, and the row on screen is the row the importer would write —
the preview is generated from the plan rather than from a second reading of the text, which is the only
arrangement that keeps "2 topics" and the two topics underneath it from disagreeing.

**Two things about the figures, both found by looking at them on a screen.** The last two counts come from the plan,
not from the rows on screen: folding a topic takes rows off the list and nothing off the import, and the first
device matrix photographed «rows to write» dropping from 8 to 5 the moment a branch was closed. And a closed topic
says what it hid — «3 hidden» on its own row, with the control reading *open this topic* — because a list that
shortens without saying so is the same silence as an import that drops a line without saying so.

**One thing about the row, found the same way.** At 320dp with the font at 1.3 the topic row's title was measured
on the device at 0 px and simply was not drawn: the title was the row's only weighted part, and the state mark, the
outcome caption, the hidden count and a Russian toggle label together took the whole width, so the weighted child got
the leftover, which was none. The captions now sit under the title rather than beside it, and the topic's control is
the row's one fixed competitor — which is also where the task card puts its metadata. The run measures every drawn
row's title against a 48dp floor before it photographs it, because neither an assertion on the bound text nor a
Robolectric measure can see a row that lost its name.

**One thing about the field, found the same way.** The empty paste box used to draw two sentences on one line: the
outlined field's floating label «Pasted text» and, at the same text position, the hint that explains the format
(«Paste here, one note per line…»). The guidance is now the layout's *helper text*, which Material draws in its own
row under the box — readable while the box is empty, while it is full, and to a screen reader that reaches the field
before the three notes below it.

## 6. Where it is proven

| Layer | File | What it holds |
| --- | --- | --- |
| the syntax | `KeepTextParserTest` (26) | checkbox forms, BOM, CRLF/CR/LF, tabs and spaces, four levels, clamping, separators counted apart from blank lines, Cyrillic and emoji kept, no invented fields on the node type |
| the whole file | `KeepCorpusContractTest` (9) | reads `keep/keep-corpus-shaped-like-the-original.txt` and counts it: 439 physical lines, 382 titled elements, 296 `[X]`, 86 `[ ]`, 57 separators, 56 roots, 37 with children, one level deep, 19 repeated titles, longest title 169 — plus the privacy check that every title comes from the generator's vocabulary |
| the mapping | `KeepImportMappingTest` (25) | topic → group, line → task, parent links, completion from the box alone, no timestamps, deterministic identity, collisions refused, position shifts and re-wordings |
| the database | `KeepImportTransactionTest` (19) | real Room: the whole corpus arriving whole (56 topics, 326 tasks, 34 completed topics, 262 completed tasks, none lost to a shared group), rollback at either table, the safety copy counted before the write, another profile's row untouched, only two tables changed |
| the screen | `KeepImportViewModelTest` (15), `KeepImportScreenTest` (11) | the gate on the confirm, indentation surviving the field, rows labelled by what will happen, the tree opening and closing, both languages, the 320dp budget |
| the task list | `TaskTreeDepthTest` (6), `TaskCardIndentTest` (4) | the depth a card draws, measured from the parent links of the rows on screen |
| the device | `KeepImportInstrumentedTest` (6) | Settings → paste → preview → confirm → the stored tree, the repeat paste, and the imported subtree rendered one step deeper per level; plus `zFramesForThisConfiguration`, the run the frame matrix invokes |

The 382-element corpus is exercised on the JVM. On the device the fixture is a nine-line invented paste, for the
reason §4 of the stage brief gives: the reader's own export is personal data, it stays out of the repository, and
nobody's notebook title belongs in a log, a screenshot or a CI artifact.

**The frames are in `docs/images/ui-redesign/after/s15/`, and every one of them is a claim about a screen.** Six
configurations — English and Russian at the device's own 393dp in light and in dark, and both at 320dp with the
font at 1.3 — times five frames: the field, the statistics card, one topic closed, the tree scrolled to its
deepest line, and the imported chain in the task list. `scripts/capture-stage15-matrix.sh` refuses a
configuration whose tag lies about the device's width, theme or font scale, refuses to photograph an installed APK
whose bytes differ from the built one, and copies nothing until the run that produced those frames answered `OK`.
Six more frames, named `a`…`f`, come from the behaviour run itself and sit in `after/s15/behaviour/`.

Six of what the matrix and the behaviour frames found are recorded here, because each changed what the screen
shows rather than what the test claimed: a frame taken a composition before the state it names had been presented;
two counted figures that followed the reader's scroll or the profile's own rows instead of the plan; a primary
action whose Russian label was cut in half at 320dp; a paste field that printed two sentences on one line; and a
preview row that drew everything except the note's title.
