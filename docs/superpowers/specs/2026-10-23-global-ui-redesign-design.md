# Global UI/UX Redesign — design specification

Date: 2026-10-23
Branch: `feat/global-ui-redesign` (one branch, one PR, staged commits)
Baseline: `master` = `origin/master` = `c2c767d1b80db756c1a3517eecc083de598ff7af` (merge of PR #21)
Direction approved by the user: **Ink & Accent**.

Revision 3. An independent review of revision 1 falsified six of its claims against the source; they are
corrected here. Revision 2's contrast section was then found to carry two arithmetic errors of its own — a
dark-theme sweep reported as a minimum when it was a single-surface figure, and a priority-indicator pair
taken from the wrong colour — which is why §3.2 is no longer written by hand at all. Every ratio in this
document is now printed by `scripts/contrast_report.py` from `docs/design/tokens.json`, and the same
script re-measures the shipped resources once stage 1 writes them.

## 0. What this stage is, and what it is not

A visual and interaction redesign of the whole application, built on a design system the app does not
have yet. It is not a migration, not a feature stage, and not a rewrite.

The stack stays exactly as it is: XML layouts, ViewBinding, Fragments, Navigation, Material Components
1.11, Hilt, Room. No Compose, no new runtime dependency, no new font file, no image-loading library.
Nothing under `data/` changes: no schema, no migration, no repository, no sync, no backup format, no
business rule. `RepeatPreset` keeps its nullable unit label — the crash fix must not be undone.

**What is in scope in Kotlin.** "Visual stage" does not mean "XML only", and revision 1 implied otherwise.
The line this spec holds is: **display code may change, data and domain code may not.** The two halves of
that line are different rules and §0 states them separately, because "presentation-only computation" is the
phrase most likely to be read as a licence.

*Forbidden.* Nothing that decides what a value **is**. No change to a Room schema, migration, DAO query,
repository, domain model, state transition, calculation, sync or backup format, or to when a task counts as
finished, archived, measured or earned. No new persisted field, no new status, no new filter, no new group.
A screen may not begin to answer a question the domain answers differently.

*Allowed.* A computation whose whole input is data already on the row and whose whole output is a decision
about **how to draw it**. Two of the redesign's features are exactly this, and they are legitimate:

- **overdue**, which is a rendering decision, not a state — the row already has `dueDate` and `status`;
- **relative date wording** — "Today 18:00", "3 days late" instead of "Oct 25, 2026" — which re-expresses
  the same instant the row already carries.

Three conditions attach, and a presentation computation that breaks any of them has become business logic:

1. **Existing data only.** It reads fields the row already has. It issues no query and takes nothing new
   from a repository.
2. **No state, no writes.** It changes no stored value, no status, no ordering key, no filter, and nothing
   the domain can later read back.
3. **No second definition of a rule the domain already owns.** Overdue already has an answer in this app:
   `TaskStatisticsDao` counts the health panel's `OVERDUE` bucket as `due_date IS NOT NULL AND due_date <
   :now`. The card uses that predicate and no other, so a task the dashboard calls late cannot be a task
   the list calls on time. The wording lives in the display layer; the rule does not.

**Current time is injected, never read from the clock.** A presentation computation that calls
`System.currentTimeMillis()` inline is untestable at its boundary — the exact case where it is wrong is the
one no test can set up, and this repository has already been bitten by a midnight-boundary flake
(`WorkTrackingRepositoryTest`, fixed in stage G). So the computation takes its `now` as a parameter, and
`TasksViewModel` receives the seam the app already has: `data/tracking/TimeBase`, whose `wallMillis()` is
already Hilt-provided by `di/AppModule.provideTimeBase()` and already injected into every repository that
had to be testable to the second. A defaulted constructor parameter is the only change there, and it is
display-layer. A test then holds the clock still and asserts that a task dated 23:59 is late and one dated
00:01 is not, on the same row data.

The measurable goal is the user's own: after installing the new APK, Tasks and Profile must not look like
the screenshots they supplied. Colour and corner-radius edits do not pass.

## 1. Facts this design is built on

Everything below was read from the source at the baseline commit.

**Statuses are four and only four** — `TaskStatus.TODO`, `IN_PROGRESS`, `PAUSED`, `COMPLETED`
(`data/model/TaskStatus.kt`). There is no `OVERDUE` and none will be added. Overdue exists **only** as a
computed visual treatment derived from data already on the row — `dueDate` in the past, status not
`COMPLETED`, task not archived. No state, no column, no filter, no group.

**There are three status chips, not four** (`chipTodo`, `chipInProgress`, `chipPaused` in
`fragment_tasks.xml`). A "Completed" chip would be a dead filter: completing a task archives it
(`TasksViewModel.completeAndArchive`), so the active list never contains one. The redesign keeps three and
does not invent a fourth.

**Grouping is by topic, not by date.** `TasksListItem.GroupHeader` carries a `TaskGroup` with its own
`collapsed` flag. "Today / This week / Overdue" headers would be new functionality and are out.

**Sort orders that exist** (`SortOrder.kt`): `MANUAL` (drag handle, persisted in `tasks.order`),
`DATE_ASC`, `DATE_DESC`, `PRIORITY_ASC`, `PRIORITY_DESC`, `NAME_ASC`, `NAME_DESC`. The Tasks menu exposes
these as four submenu entries that toggle direction, not seven.

**Row data available to a card** (`TasksListItem.TaskRow`): the task, its `Category?`, selection flag, the
`RecurrenceRule?` this instance belongs to, the count of pending reminders, and a `CardWork` measured-time
line. Priorities are `LOW`, `MEDIUM`, `HIGH`.

**The Tasks header actions exist in code and are reachable on no screen.** `menu_tasks.xml` declares
search, new group, archive, work history and sort, and `TasksFragment` wires all five through
`requireActivity().addMenuProvider`. Nothing displays that menu: the app's theme is
`Theme.Material3.DayNight.NoActionBar`, `MainActivity` never calls `setSupportActionBar`, and no layout in
`res/layout` contains a `Toolbar` — the string appears in none of the fifty-two. `setSearchQuery`,
`setSortOrder`, `actionTasksToArchive` and `actionTasksToWorkHistory` each have exactly one caller, inside
that invisible menu, and the Archive screen has no other entry point in the app. Four working features are
therefore stranded behind chrome that was never built, which is visible in the audit screenshot as a Tasks
screen with an empty top edge. Revision 2 described these as "reached from Tasks today"; that was read off
the menu file and is wrong. §4.1's header is what makes them reachable, and it is the largest real problem
this stage fixes.

**Completed tasks leave the active list.** `TasksViewModel.completeAndArchive` finishes a task and moves it
out of the list the Tasks screen shows, so a Completed card treatment is only ever seen where completed
rows really are drawn — the Archive screen, the calendar's finished marks and the report — and never as a
state of a Tasks row. §4.1's completed style is scoped to those screens; the archiving behaviour itself is
untouched.

**Achievements: the data layer exists, the screen does not.** `data/achievement/AchievementRule.kt`,
`AchievementRules.kt`, the `achievements` table from schema v16, `AchievementRepository` and 62
`achievement_*` strings are all present. There is no achievements fragment, destination or action in
`nav_graph.xml`, and no `ui/achievements` package — that screen is the next planned stage (J Part 6).
Profile therefore gets **no** Achievements row, and this spec does not touch the achievement rules.

**Real navigation destinations** — the full set is twenty: `navigation_tasks` (start), `navigation_calendar`,
`navigation_categories`, `navigation_statistics`, `navigation_profile`, `navigation_task_detail`,
`navigation_archive`, `navigation_work_history`, `navigation_category_detail`, `navigation_goals`,
`navigation_goal_detail`, `navigation_goal_editor`, `navigation_habits`, `navigation_habit_detail`,
`navigation_habit_editor`, `navigation_insights`, `navigation_reports`, `navigation_settings`,
`navigation_profile_selection`, plus `categoryTasksFragment`. Revision 1 listed fourteen of these as if
they were all; the five reachable screens it omitted are Calendar, Categories, Statistics, Profile and
Tasks themselves, which are bottom-bar destinations rather than Profile targets, and that distinction is
what §4.2 now states explicitly instead of claiming "the four destinations that exist".

**Backup and import live inside Settings** (`fragment_settings.xml`: `exportButton`, `importButton`,
`signInButton`, `signOutButton`, `versionText`), so Profile's Data section points at Settings rather than
inventing a second backup entry.

**`avatarUrl` is stored and rendered nowhere.** `AuthRepository` writes it on Google sign-in,
`User.avatarUrl` holds it, and no layout or view ever reads it. `ui/components/AvatarView.kt` draws
initials only. Showing the photo would mean a network fetch plus a bitmap cache — new runtime behaviour in
a visual stage — so it stays out (§10) and the avatar remains an initial.

## 2. The real defect found during the audit

### 2.1 The swipe action panel peeks out from behind every card

`item_task.xml` is a `FrameLayout` (`match_parent` wide, `wrap_content` tall) holding `actionPanel` at
`layout_gravity="end"`, `layout_width="wrap_content"` (two 76dp buttons = 152dp), `layout_height="match_parent"`
and **no margins**, with `taskCard` on top carrying `layout_marginHorizontal="8dp"` and
`layout_marginVertical="4dp"`.

Two consequences, both visible on every row with no interaction:

- vertically, the `wrap_content` FrameLayout is card height + 8dp, and the `match_parent` panel fills
  that, so 4dp of panel shows above and below the card — the blue/red strip along the top;
- horizontally, the panel's right edge sits at the FrameLayout edge while the card's stops 8dp short, so
  8dp of the delete button is exposed down the right side — the red column.

Fix: the panel gets `layout_marginEnd="8dp"` and `layout_marginVertical="4dp"`, matching the card's
insets, so the panel's rectangle coincides with the card's while closed.

> Correction from review: revision 1 said `layout_marginStart`. For a `gravity="end"` child `marginStart`
> does not move the right edge, so the red strip would have survived.

One artifact of the fix is acknowledged rather than discovered later: when a row is *open*, the panel no
longer reaches the row's right edge, leaving an 8dp gutter. The panel is therefore given the row's own
background behind it, so the gutter reads as part of the card rather than as a gap.

What is **not** at risk: `TasksAdapter.handleTouch` measures the reveal from `binding.actionPanel.width`,
which margins do not change, and the drag handle lives inside the card. Swipe-to-reveal and
`ItemTouchHelper` manual reordering keep working, and both are covered by tests.

### 2.2 Not a defect: the FAB

Revision 1 claimed the FAB covers a list row because the bottom bar is not accounted for. That is wrong.
`activity_main.xml` constrains `nav_host_fragment` with `layout_constraintBottom_toTopOf="@id/bottom_navigation"`,
so the Tasks fragment and its FAB already sit entirely above the bar. The FAB occupies 72dp from the
fragment's bottom edge (16dp margin + 56dp) and the list carries `paddingBottom="88dp"` with
`clipToPadding="false"`, so at rest the last row clears it. There is no edge-to-edge anywhere in the app,
so nothing hides under the gesture bar either.

What remains is polish, not a bug: the FAB's resting position is re-checked visually after the card
redesign, and the 88dp padding is derived from the same dimension as the FAB so the two cannot drift apart.

## 3. Design system: Ink & Accent

Character: warm paper and warm graphite, one saturated accent, typography carrying the hierarchy, colour
used sparingly and always with a second signal. No third-party palette, no gradient, no shadow theatre.

### 3.1 Colour tokens

Names are semantic roles, not hues. Light values in `values/colors.xml`, dark in `values-night/`.

The table below is a quotation. Its source of truth is `docs/design/tokens.json`, and
`scripts/contrast_report.py` reads that file, re-measures every pair, and writes
`docs/design/contrast-report.md`; a later stage points the same script at the shipped resources, so a hex
value edited into `colors.xml` without the table is reported as drift.

| Token | Light | Dark | Role |
|---|---|---|---|
| `ink_background` | `#FBF9F6` | `#12100F` | screen behind everything |
| `ink_surface` | `#FFFFFF` | `#1A1817` | cards, sheets |
| `ink_surface_raised` | `#FFFDFB` | `#221F1D` | dialogs, lifted cards |
| `ink_surface_variant` | `#F1EDE7` | `#26231F` | wells and chip tracks — **not** under semantic-coloured text, see §3.2 |
| `ink_text` | `#1A1614` | `#F2EDE7` | primary text |
| `ink_text_secondary` | `#6B635C` | `#A39B92` | meta text |
| `ink_text_tertiary` | `#6E655D` | `#968D84` | captions, timestamps |
| `ink_outline` | `#E3DDD5` | `#312D29` | hairlines (decorative only, 1.35 / 1.30) |
| `ink_outline_strong` | `#8C8378` | `#7A736A` | strokes that carry state — 3.73 / 3.78 on a surface |
| `ink_accent` | `#2F6F5E` | `#6FD3B8` | the one accent |
| `ink_on_accent` | `#FFFFFF` | `#0E201B` | text on filled accent |
| `ink_success` | `#2F7A4B` | `#7CC98F` | finished |
| `ink_warning` | `#8F5B18` | `#E0A95F` | paused, workload |
| `ink_error` | `#C0402F` | `#F0836F` | overdue, destructive |
| `ink_on_error` | `#FFFFFF` | `#2B0F0A` | text on a filled destructive control |

Plus eight solid pill backgrounds, four per theme, chosen numerically rather than by alpha so each is a
checked pair: light `#EEF3F2` / `#EEF4F1` / `#F6F2ED` / `#FAF0EE` and their dark counterparts
`#26322E` / `#283128` / `#362C21` / `#382723`.

> Corrections from review and from recomputation: `ink_text_tertiary` was `#7A716A`, which measures 4.09:1
> on the variant surface it was assigned to — below the spec's own AA bar. `ink_warning` was `#9A6320`,
> which measures 4.40:1 on its own 10 % tint. Both were darkened. Revision 2 also drew pill backgrounds as
> a percentage alpha of the text colour; that composites differently on `surface`, `raised`, `bg` and
> `variant` (4.51 down to 3.91), so backgrounds are now explicit tokens.
>
> Revision 3 adds two more, both found by running the script rather than by reading the table.
> `ink_outline_strong` was `#B9B1A7` / `#5A544D`, which measures 2.12 and 2.37 against its own surface —
> it was described as carrying state and does not clear the 3:1 that asks for. It is now `#8C8378` /
> `#7A736A` (3.73 / 3.78 on a surface, 3.20 / 3.34 on the variant), so a resting field outline drawn with
> it is legible as a boundary. And `ink_on_error` was missing entirely: a filled destructive button had
> no declared foreground.

The theme maps these onto the Material 3 roles `colorPrimary`, `colorSurface`, `colorSurfaceVariant`,
`colorOutline`, `colorError` and the `colorSurfaceContainer` family — `color_surface_container`, `_low`,
`_lowest`, `_high`, `_highest` all exist in Material 1.11.0, confirmed in the artifact's `R.txt`, and the
base M3 themes assign them (an M3 elevated card reads `colorSurfaceContainerLow`). Every
`?android:attr/textColorSecondary` and hard-coded hex in layouts is replaced by a role.

Existing chart and report colours keep fixed values — a printed report must not change with the device
theme, which is already documented in `colors.xml`. The chart ramps are re-anchored to the new accent;
that is a deliberate change and it does break the colour assertions named in §8, which are updated rather
than left failing.

### 3.2 Contrast, measured

Ratios come from the WCAG 2.1 relative-luminance formula over the sRGB channels, computed by
`scripts/contrast_report.py` — not transcribed. Text must meet AA (4.5:1 at the sizes used here); graphics
that carry state must meet 3:1. Every text colour is measured against **all four** backgrounds it may
legally appear on, and the script prints two different minima because they answer two different questions.

**The shipped minimum** — the lowest ratio a colour reaches over the surfaces the design is allowed to put
it on:

| | light | dark |
|---|---|---|
| `ink_text` | 15.41 | 13.44 |
| `ink_text_secondary` | 5.05 | 5.71 |
| `ink_text_tertiary` | 4.89 | 4.80 |
| `ink_accent` | 5.06 | 8.70 |
| `ink_success` | 4.99 | 8.28 |
| `ink_warning` | 5.43 | 7.81 |
| `ink_error` | 4.98 | 6.38 |

**The raw sweep** — the same colours over all four surfaces with the rule switched off, which is what the
rule was derived from:

| | light | dark |
|---|---|---|
| `ink_success` | **4.49** on `ink_surface_variant` | 7.90 |
| `ink_warning` | 4.90 | 7.46 |
| `ink_error` | **4.48** on `ink_surface_variant` | 6.09 |

> Correction, revision 3: revision 2 reported the dark figures 8.94 / 8.43 / 6.89 as "the same sweep", but
> those are the measurements on `ink_surface` (`#1A1817`) alone. Over all four dark surfaces the minima are
> 7.90 / 7.46 / 6.09, all on `ink_surface_variant`. The distinction matters for the rule, so the rule is
> now stated with its own evidence: it is **load-bearing in the light theme only**, where success (4.49)
> and error (4.48) fall under AA on the variant surface. In the dark theme every semantic colour clears AA
> everywhere, and the ban is kept for consistency of the component rather than for the arithmetic.

**The rule:** semantic-coloured text — `ink_success`, `ink_warning`, `ink_error` — is never placed on
`ink_surface_variant`. Pills carry their own solid background token, and variant is used only behind
`ink_text`, `ink_text_secondary`, `ink_text_tertiary` and `ink_accent`.

Text on a filled control: `ink_on_accent` on `ink_accent` 5.91 light / 9.42 dark; `ink_on_error` on
`ink_error` 5.23 / 6.96.

Pill pairs, text on its own solid background: light 5.27 / 4.70 / 5.12 / 4.67 (accent, success, warning,
error); dark 7.40 / 6.80 / 6.50 / 5.52.

State-bearing graphics against `ink_surface`: the priority rule is `ink_error` for HIGH at **5.23** and
`ink_warning` for MEDIUM at **5.71**; LOW draws no rule at all, and where a LOW row needs words the caption
colour `ink_text_tertiary` measures **5.70** on the same surface. `ink_accent` (selected-chip stroke, focus
ring) 5.91 light / 9.85 dark; `ink_outline_strong` 3.73 / 3.78. All above 3:1.

> Correction, revision 3: revision 2 listed the indicators as "5.71 (HIGH), 4.90 (MEDIUM)". Those two
> numbers are `ink_warning` on white and `ink_warning` on the variant surface — the warning colour quoted
> twice and the error colour never measured.

`ink_outline` at 1.35 light / 1.30 dark is decorative and is never the sole carrier of a state; the script
reports it outside the gate so the figure is on the record without pretending it passes.

The same pairs are asserted a second time inside the build, as JVM tests that read the shipped resources
through Robolectric, so a drift that skips the script fails CI. Both are re-run at the end of the stage.

### 3.3 Type

System font only — no font resource, no APK growth, no licensing question. Identity comes from size,
weight and tracking.

| Style | Size / line | Weight | Tracking | Used for |
|---|---|---|---|---|
| `display` | 28 / 34 | 400 | 0 | screen title |
| `headline` | 22 / 28 | 500 | 0 | group and section headers |
| `title` | 16 / 22 | 500 | 0 | card title, row title |
| `body` | 15 / 22 | 400 | 0 | descriptions |
| `meta` | 13 / 20 | 400 | 0.1 | dates, category, counts |
| `label` | 11 / 14 | 500 | 0.4, all caps | status pills, section labels |
| `metric` | 20 / 24 | 500 | 0 | dashboard and report numbers |

> Correction from review: revision 1 asked for tabular figures on `metric`. Roboto's `tnum` is not
> reliably reachable through `android:fontFeatureSettings`, so the promise would have silently done
> nothing. Dropped. Where a column of numbers must align, alignment is achieved with fixed-width
> containers, which is testable, rather than with an OpenType feature that may not apply.

Defined once as `TextAppearance.TaskPlanner.*` over the Material 3 parents and applied through theme
attributes, so no layout sets a raw `textSize` for these roles again.

### 3.4 Space and shape

Base 4dp; scale 4 / 8 / 12 / 16 / 20 / 24 / 32. Screen margin 20dp. Card padding 14 vertical / 16
horizontal. Card gap 8dp. Between groups 20dp. Row height 56dp minimum, touch target 48dp minimum
including icon buttons. Radius: card 14dp, control 12dp, sheet and dialog 20dp, pill fully rounded.
Elevation is used twice at most (cards 1dp, dialogs 8dp); separation is done with surface roles and
hairlines, not shadows.

### 3.5 Components — two different mechanisms

> Correction from review: revision 1 filed every component as "a named style in `styles.xml` with a
> Material 3 parent". A style configures one view; several of these are multi-view compositions with no
> single parent to inherit from. Splitting them honestly:

**Styles** (single view, Material 3 parent): `.Card`, `.Card.Completed`, `.Button.Filled`, `.Button.Tonal`,
`.Button.Outlined`, `.Button.Text`, `.Button.Destructive`, `.Chip.Filter`, `.Chip.Category`, `.Field`,
`.BottomBar`, `.Dialog`, `.MetaText`, `.SectionLabel`.

**Layout includes** (multi-view, reused with `<include>`): `.ListRow` (icon container + title + optional
value + chevron), `.EmptyState`, `.LoadingState`, `.ErrorState`, `.SelectionBar`, `.GroupHeader`.

**Drawables plus bind-time code** (state-driven, cannot be static): `.StatusPill` variants, `.PriorityRule`.
A card's status, priority and overdue colour are chosen by `TasksAdapter` from the row's data; a style
could not do that.

Each replaces the current mix of `Widget.MaterialComponents.*` (M2) inside an M3 theme — the chips in
`fragment_tasks.xml` are `Widget.MaterialComponents.Chip.Filter` today — which is a large part of why the
app reads as stock.

## 4. Screen designs

### 4.1 Tasks

Header: `Tasks` in display size with the open count as a `meta` subtitle — `TasksState.activeTaskCount`,
already computed for the state object and shown nowhere. Right side: search, sort, and a kebab holding new
group / archive / work history. Search becomes a real field in the header driving the same
`viewModel.setSearchQuery` the invisible menu item drives now; the kebab is a `PopupMenu` anchored to a
header button, and it is what finally reaches `actionTasksToArchive` and `actionTasksToWorkHistory` (§1).
This is not decoration — it is the entry point four working features have been waiting for.

Filter rail: category chips, a hairline divider, then the **three** status chips. When anything is active a
`Filters · n` chip appears at the end and clears all in one press. The rail scrolls horizontally at 320dp
instead of wrapping and stealing list height.

List: topic group headers in `headline` with count and collapse control, exactly as today.

Card. Three lines inside one card, the same structure at every width, because a card that rearranges
itself between 320dp and 393dp is two cards to maintain and one of them is never tested:

- **line A** — the drag handle, then the 4dp priority rule inset from the border (`ink_error` for HIGH,
  `ink_warning` for MEDIUM, **no rule** for LOW, so absence reads as "not urgent"), then the title in
  `title` size taking the remaining width and truncating at two lines, then the status pill at the end;
- **line B** — one `meta` line: relative due date ("Today 18:00", "3 days late", "No due date"), an 8dp
  category dot with the name in `ink_text_tertiary` — the dot carries the colour, not the word, which is
  what makes the pale category greens in the audit screenshot legible — then icons only for what is
  present: repeat, pending reminders;
- **line C** — the measured-time figures on the left when there are any, and the action cluster
  right-aligned: the work control, the one lifecycle button the status calls for, and the completion
  toggle, which is the only one with emphasis.

> Why the actions moved off line A. At 320dp the card's text column is 208dp wide (§4.1 below), and three
> 48dp buttons are 144dp of it. Beside the title that leaves 64dp — four or five characters — so a
> four-button row is not a tight layout but an impossible one, and the audit screenshot shows the current
> card discovering this by squeezing the title. Putting the cluster on its own line is the only arrangement
> in which every action keeps a 48dp touch target *and* the title keeps a readable measure. The plan
> carries the arithmetic; the test enforces it.

> Decision the review showed was missing, and one revision 2 left under-specified. Today the card carries
> four status buttons (`startTaskButton`, `pauseTaskButton`, `resumeTaskButton`, `completeTaskButton`) plus
> a `workControlButton`. All five views stay in the layout, because `TaskCardWorkTest` and
> `TaskCardReminderTest` assert against real views and hiding a control behind a swipe would be a
> regression on the actions a daily user takes most. **What is fixed is that they are never all visible at
> once** — the visibility rule the card already half-follows, written out so it can be tested:
>
> | Row state | Start | Pause | Resume | Complete | Work control |
> |---|---|---|---|---|---|
> | `TODO`, timer idle | visible | — | — | visible | visible only when work has been measured on the task |
> | `IN_PROGRESS`, timer idle | — | visible | — | visible | as above |
> | `PAUSED`, timer idle | — | — | visible | visible | as above |
> | any status, timer running or paused mid-stretch | follows the status column | | | | visible, and it is the one button that answers for that phase |
> | `COMPLETED` | — | — | — | — | never: its timer stopped when the task was finished |
>
> `—` means gone, not invisible-but-present: a hidden button still steals 40dp of a 320dp row. The status
> transitions themselves are unchanged, and the work control keeps the existing contract in
> `TasksAdapter.bindWork` — shown with `work.isShown && status != COMPLETED`, one button whose icon and
> `contentDescription` follow `TrackingPhase`. At most two icon buttons plus the completion toggle are on
> screen at a time, and the completion toggle is the only one with emphasis.

Overdue is the presentation computation §0 defines: the same `dueDate != null && dueDate < now` the health
panel's bucket uses, with `now` from the injected `TimeBase`, and no third spelling of it. Its treatment is
date text and priority rule in `ink_error`, plus the word "late" — never colour alone, never a new group,
status or filter.

The completed treatment — no elevation, tertiary text, strikethrough title, a pill still carrying the word
"Done" — is applied **only where completed rows are actually drawn**: the Archive list, the calendar's
finished marks, the report and the work history. It is defined as a card style, not switched on for the
Tasks list, because `completeAndArchive` moves a finished task out of that list and a Tasks row therefore
never has the state to render. `completeAndArchive` itself is not touched by this stage; the Completed
style is dead code on the Tasks screen and is not allowed to become a reason to change when a task leaves
the list.

Selection mode replaces the header with `.SelectionBar`. Swipe reveals edit and delete over a
surface-coloured panel, now correctly hidden when closed (§2.1).

**The card at 320dp.** 320dp is the narrowest width the stage is verified at, and the card is designed
against a budget rather than a hope: 320dp of row, minus 16dp of card margins, minus 32dp of card padding,
minus 52dp of drag handle and 12dp of priority rule, leaves **208dp** of text column. Line C's three
buttons take 144dp of it and leave 64dp for the measured-time figures; line A's pill takes 69dp and leaves
131dp for the title; line B's fixed parts take 140dp and leave 68dp for the category name. The plan's Task
12 states that arithmetic in full, and its test measures the inflated row against it.

The rules, in the order the elements are allowed to give way:

- nothing overlaps: every line is a horizontal chain, and the title's right edge is provably at or before
  the pill's left edge;
- the title truncates at two lines with an ellipsis and never shrinks below one line's height;
- the `meta` line drops its tail before it wraps — category name first, then the reminder count, then the
  repeat icon. The due date and the status word are the two things that never drop, because they are the
  facts the list is sorted by and the state the list is filtered on. Wrapping is forbidden: a second meta
  line pushes a four-line card past what the list can hold at that width;
- icons never scale below their 48dp touch box; if the measured-time figures and the action cluster cannot
  both fit line C, the figures collapse to their icon and the buttons stay;
- the drag handle is the first thing to go, and it goes only when the sort order is not `MANUAL` — already
  its behaviour, restated so the 320dp screenshots are read against a rule;
- no text-bearing view gets a fixed width a long word can exceed, and the row stays `match_parent`, so
  horizontal overflow is structurally impossible.

Being inside the screen is not the bar. The test inflates a row at 320dp with a 120-character title, the
longest category name in the strings file, a due date, a repeat series, two pending reminders and a running
stretch — the worst legal row — and then asserts that what is *legible* is the part that matters: the due
date and the status word are drawn at their full measured width, the title is at least 128dp wide, every
visible button is at least 48dp on both axes, no two visible views overlap, and the category name is the
one element allowed to have given way.

### 4.2 Profile

Rebuilt as a screen with information architecture, not a column of buttons.

- **Header**: 64dp avatar — initial in an accent-tinted circle (see §1 for why not the photo); name in
  `headline`; email in `meta`.
- **Account**: Edit profile, Switch profile (→ `navigation_profile_selection`).
- **Your work**: Goals, Habits, Insights, Reports — the four destinations reachable *from the account*,
  which is the distinction §1 now states precisely. Calendar, Categories and Statistics are reached from
  the bottom bar and are not duplicated here.
- **Data**: Settings — where export, import, sync and sign-in already live.
- **Danger zone**: Delete profile, separated, destructive styling, confirmation naming the consequences,
  existing logic intact.

Each row is a `.ListRow`: 40dp tinted icon container, `title`, optional trailing value, chevron, 56dp
tall, ripple, hairline between siblings. No row goes anywhere that does not exist. Archive and work
history are **not** added here; they belong to Tasks.

### 4.3 Task editor

Same fields, same validation, same `RepeatPreset` behaviour — regrouped into labelled sections: Task
(title, description), When (due date, time, plan), How (priority, status, category), Repeat, Reminders,
Measured work. Repeat stays collapsed until opened.

Field errors stay inline on the offending field. Save and Cancel become a sticky footer. Delete keeps its
confirmation. The ids that `TaskDetailLayoutTest` and `WorkAdjustmentUiTest` bind to
(`repeat_preset`, `repeat_interval_layout`, `repeat_interval`, `work_start_input`, `work_end_input`,
`task_title`) are preserved or migrated in the same commit as the tests, never silently.

### 4.4 Navigation and the remaining screens

Bottom bar: all five labels always visible, active item marked by a pill behind the icon and by
`ink_text` against `ink_text_secondary`, background from a surface role instead of
`?android:attr/windowBackground` (today the bar merges into the screen).

> Correction from review: revision 1 asked for "correct system-window insets so it never sits under the
> gesture bar". The app is not edge-to-edge and does not become so in this stage — that would be new
> window-flag work in `MainActivity` and out of scope. The bar keeps its current placement; only its
> colour role and active-item treatment change.

Categories, Calendar, Statistics, Insights, Goals, Habits, Reports, Archive, Work history, Settings and
profile selection move onto the same tokens, type styles and components. Their queries, view models,
calculations and wording logic do not change.

## 5. Motion

Entry animation on list items (fade plus 12dp rise, 180ms, decelerate) fires on data change, not on
scroll, and is skipped above a threshold so a long list never animates a hundred views. Status change
cross-fades the pill. Group collapse animates height. Pressed states use the standard ripple. Durations
come from one place; the system's animator scale is respected, which Android applies automatically.

## 6. Accessibility and adaptivity

Touch targets ≥48dp. Every icon-only control has a `contentDescription`. Status, priority and overdue are
each legible without colour. Contrast per §3.2 including the variant-surface rule, measured by
`scripts/contrast_report.py` and asserted a second time by JVM tests over the shipped resources, so the
promise survives someone editing a hex value without reading this file.

The narrow-width contract for the task card is §4.1's "card at 320dp" list, and it is tested as a layout
measurement rather than reviewed as a photograph.

Verified on the emulator at 320dp, 360dp and 393dp widths at font scale 1.0 and 1.3, light and dark. The
widths are produced reproducibly with `adb shell wm size` / `wm density` overrides, and the exact
pixel+density pair for each width is recorded in the review document; font scale via
`settings put system font_scale`; theme via `cmd uimode night`. No fixed height on any text-bearing row.
Screenshots of every combination are kept, not asserted.

## 7. Performance

No new dependency. `RecyclerView` keeps its adapter and diffing; the card layout is flattened from today's
266-line nesting so each row measures less. No image loading and no bitmap cache — the avatar is an
initial, per §1. Scroll performance is compared before and after on the same seeded account size.

## 8. Verification

The acceptance evidence is screenshots, not a build log.

For every main screen: same emulator (1080×2280, 440dpi), same seeded account, same theme, same font
scale, before and after, in `docs/images/ui-redesign/before/` and `.../after/`. Tasks, Profile and the
task editor additionally get dark-theme, 320dp and font-scale-1.3 pairs.

`docs/UI_UX_AUDIT.md` records the starting problems with the before images; `docs/DESIGN_SYSTEM.md`
records the tokens and components; `docs/UI_REDESIGN_REVIEW.md` goes screen by screen — what changed,
which audit problem it answers, what is still open, on what configuration it was checked.

**Test baseline.** The JVM suite is 1371 tests across 92 suites. The instrumented suite is **six classes
carrying seventeen `@Test` methods** — `TaskEditorInstrumentedTest` 4, `TasksScreenInstrumentedTest` 4,
`PdfReportInstrumentedTest` 3, `DatabaseLocationInstrumentedTest` 3, `ReportScreenInstrumentedTest` 2,
`ReportPdfBytesInstrumentedTest` 1.

> Correction from review: revision 1 said "4 instrumented tests", which is one class's method count
> copied as the whole suite.

All of them stay enabled and unweakened. Tests that assert on ids, view structure, dimensions or colours
and therefore move with this redesign are updated in the same commit as the layout they cover, and the
assertion is rewritten to the new truth rather than deleted:
`TaskCardWorkTest`, `TaskCardReminderTest`, `TaskDetailLayoutTest`, `WorkAdjustmentUiTest`,
`TasksScreenInstrumentedTest`, `TaskEditorInstrumentedTest`, `ReportScreenInstrumentedTest`,
`CalendarScreenTest` (asserts `currentTextColor` and dp measurements), `StatisticsLayoutTest` (asserts
child order and `ColorStateList.defaultColor`), `DashboardRendererTest` and `ChartViewDrawTest` (assert
`R.dimen.chart_*` and `R.color.chart_heatmap_*`, which §3.1 deliberately re-anchors),
`WorkloadPanelTest` (asserts `R.color.priority_*`), `InsightsLayoutTest` (inflates the empty state that
§3.5 turns into a composition).

New coverage at the fragment level, following the pattern PR #21 introduced: Tasks renders topic groups,
the three statuses, the computed overdue treatment, long titles, the empty state and a fully closed swipe
panel; Profile renders with no avatar, a long name and a long email, and every row navigates; the task
editor opens, validates and saves. **Profile has no tests at all today**, so that whole block is net-new
work, not an extension.

Known open flake: `WorkAdjustmentUiTest > a stretch that ends where or before it began is refused by the
form` failed mainline run #55 and passed a re-run of the same commit. If it appears in this branch it is
investigated to root cause, never routed around by weakening an assertion.

Alongside the suite, `python scripts/contrast_report.py --resources app/src/main/res --check` is run at
stage 1, stage 7 and the end of the stage, and its report is committed to
`docs/design/contrast-report.md`. It is the arithmetic; the tests are the same arithmetic inside the build.

## 9. Delivery order

One branch, one PR, one commit per layer, each building and passing tests before the next starts, each
with its own screenshots:

1. tokens, themes, type, space, component styles and includes — with `docs/design/tokens.json`, the
   contrast script and the JVM pair tests landing in this stage, before any screen can drift from them;
2. Tasks: the swipe-panel geometry fixed at root, with a test that pins the closed bounds;
3. Tasks: the header that makes search, sort, archive and work history reachable, the filter rail, the
   card composition with its contextual action visibility, and the tests that travel with them;
4. Profile rebuilt, with its first-ever tests;
5. task editor regrouped;
6. bottom navigation treatment;
7. remaining screens onto the system;
8. motion;
9. accessibility and adaptivity pass;
10. docs, before/after evidence, contrast script.

Independent code review and independent visual review both run before the PR opens, and their findings are
fixed or argued, not acknowledged.

## 10. Explicitly out of scope

Compose migration. New dependencies. Edge-to-edge and window-inset rework. Rendering `avatarUrl` (and with
it any network image fetch or bitmap cache). Any change to Room schema, migrations, repositories, sync,
backup format, auth, time tracking, statistics calculations, report content, achievement rules, or the goal
and habit models. New features, including the Achievements screen. Any status, filter or group the current
data model cannot answer — which is why there are three status chips and no Completed filter on the active
list.
