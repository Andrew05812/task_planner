# Global UI/UX Redesign — design specification

Date: 2026-10-23
Branch: `feat/global-ui-redesign` (one branch, one PR, staged commits)
Baseline: `master` = `origin/master` = `c2c767d1b80db756c1a3517eecc083de598ff7af` (merge of PR #21)
Direction approved by the user: **Ink & Accent**.

Revision 2. An independent review of revision 1 falsified six of its claims against the source; they are
corrected here, and the corrections are marked. Contrast figures were recomputed, and two colours failed
the first pass.

## 0. What this stage is, and what it is not

A visual and interaction redesign of the whole application, built on a design system the app does not
have yet. It is not a migration, not a feature stage, and not a rewrite.

The stack stays exactly as it is: XML layouts, ViewBinding, Fragments, Navigation, Material Components
1.11, Hilt, Room. No Compose, no new runtime dependency, no new font file, no image-loading library.
Nothing under `data/` changes: no schema, no migration, no repository, no sync, no backup format, no
business rule. `RepeatPreset` keeps its nullable unit label — the crash fix must not be undone.

**What is in scope in Kotlin.** "Visual stage" does not mean "XML only", and revision 1 implied
otherwise. This redesign necessarily changes display-layer Kotlin: the task adapter (status, priority and
overdue presentation, the reveal geometry), the Tasks fragment and view model's *rendering* path (relative
date wording, an active-filter summary, search surfaced in the header instead of the overflow), the
Profile fragment's row bindings, and the avatar view. None of it changes a stored value, a query, a
calculation or a state transition. The line this spec holds is: **display code may change, data and
domain code may not.**

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

**Tasks header actions that already exist** (`menu_tasks.xml`): search, new group, archive, work history,
sort. Archive and work history are reached from Tasks today, so Profile does not duplicate them.

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

| Token | Light | Dark | Role |
|---|---|---|---|
| `ink_background` | `#FBF9F6` | `#12100F` | screen behind everything |
| `ink_surface` | `#FFFFFF` | `#1A1817` | cards, sheets |
| `ink_surface_raised` | `#FFFDFB` | `#221F1D` | dialogs, lifted cards |
| `ink_surface_variant` | `#F1EDE7` | `#26231F` | wells and chip tracks — **not** under semantic-coloured text, see §3.2 |
| `ink_text` | `#1A1614` | `#F2EDE7` | primary text |
| `ink_text_secondary` | `#6B635C` | `#A39B92` | meta text |
| `ink_text_tertiary` | `#6E655D` | `#968D84` | captions, timestamps |
| `ink_outline` | `#E3DDD5` | `#312D29` | hairlines (decorative only) |
| `ink_outline_strong` | `#B9B1A7` | `#5A544D` | strokes that carry state |
| `ink_accent` | `#2F6F5E` | `#6FD3B8` | the one accent |
| `ink_on_accent` | `#FFFFFF` | `#0E201B` | text on filled accent |
| `ink_success` | `#2F7A4B` | `#7CC98F` | finished |
| `ink_warning` | `#8F5B18` | `#E0A95F` | paused, workload |
| `ink_error` | `#C0402F` | `#F0836F` | overdue, destructive |

Plus eight solid pill backgrounds, four per theme, chosen numerically rather than by alpha so each is a
checked pair: light `#EEF3F2` / `#EEF4F1` / `#F6F2ED` / `#FAF0EE` and their dark counterparts
`#26322E` / `#283128` / `#362C21` / `#382723`.

> Corrections from review and from recomputation: `ink_text_tertiary` was `#7A716A`, which measures 4.09:1
> on the variant surface it was assigned to — below the spec's own AA bar. `ink_warning` was `#9A6320`,
> which measures 4.40:1 on its own 10 % tint. Both were darkened. Revision 1 also drew pill backgrounds as
> a percentage alpha of the text colour; that composites differently on `surface`, `raised`, `bg` and
> `variant` (4.51 down to 3.91), so backgrounds are now explicit tokens.

The theme maps these onto the Material 3 roles `colorPrimary`, `colorSurface`, `colorSurfaceVariant`,
`colorOutline`, `colorError` and the `colorSurfaceContainer` family — `color_surface_container`, `_low`,
`_lowest`, `_high`, `_highest` all exist in Material 1.11.0, confirmed in the artifact's `R.txt`, and the
base M3 themes assign them (an M3 elevated card reads `colorSurfaceContainerLow`). Every
`?android:attr/textColorSecondary` and hard-coded hex in layouts is replaced by a role.

Existing chart and report colours keep fixed values — a printed report must not change with the device
theme, which is already documented in `colors.xml`. The chart ramps are re-anchored to the new accent;
that is a deliberate change and it does break the colour assertions named in §8, which are updated rather
than left failing.

### 3.2 Contrast, computed

Ratios come from the sRGB relative-luminance formula. Text must meet WCAG 2.1 AA (4.5:1 at the sizes used
here); graphics that carry state must meet 3:1. Every text colour was measured against **all four**
backgrounds it may legally appear on, not just the one it was designed for.

Light, minimum over `background` / `surface` / `raised` / `variant`:
`ink` 15.41 · `secondary` 5.05 · `tertiary` 4.89 · `accent` 5.06 · `success` 4.49 · `warning` 4.90 ·
`error` 4.48 · `on_accent` on `accent` 5.91.

Dark, same sweep: `ink` 13.44 · `secondary` 5.71 · `tertiary` 4.80 · `accent` 8.70 · `success` 8.94 ·
`warning` 8.43 · `error` 6.89 · `on_accent` on `accent` 9.42.

**The one rule the sweep forces:** `success` (4.49) and `error` (4.48) fall a hair under AA on
`ink_surface_variant`. Semantic-coloured text is therefore never placed on a variant surface — pills carry
their own solid background token, and variant is used only behind `ink`, `secondary`, `tertiary` and
`accent`. This is a constraint on the design, stated so that it can be tested rather than noticed later.

Pill pairs, text on its own solid background: light 5.27 / 4.70 / 5.12 / 4.67 (accent, success, warning,
error); dark 7.40 / 6.80 / 6.50 / 5.52.

State-bearing graphics against the surface: priority rules 5.71 (HIGH), 4.90 (MEDIUM), tertiary 5.70
(LOW has no rule); selected-chip stroke and focus ring 5.91. All above 3:1. `ink_outline` at ~1.35 is
decorative and is never the sole carrier of a state.

These figures are reproduced by a script during implementation and again at the end of the stage.

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

Header: `Tasks` in display size with the open count as a `meta` subtitle. Right side: search, sort, and a
kebab holding new group / archive / work history. Search becomes a real expanding field in the header
driving the same `TasksViewModel` query the menu item drives now.

Filter rail: category chips, a hairline divider, then the **three** status chips. When anything is active a
`Filters · n` chip appears at the end and clears all in one press. The rail scrolls horizontally at 320dp
instead of wrapping and stealing list height.

List: topic group headers in `headline` with count and collapse control, exactly as today.

Card, left to right:
- a 4dp priority rule inset from the card border — error for HIGH, warning for MEDIUM, **no rule** for LOW,
  so absence reads as "not urgent";
- title in `title` size, two lines maximum;
- one `meta` line: relative due date ("Today 18:00", "3 days late", "No due date"), an 8dp category dot
  with the name, then icons only for what is present — repeat, pending reminders, measured time;
- right side: the completion toggle as the single primary action at 48dp.

> Decision the review showed was missing. Today the card carries four status buttons
> (`startTaskButton`, `pauseTaskButton`, `resumeTaskButton`, `completeTaskButton`) plus a
> `workControlButton`. Resolution: **start / pause / resume and the work control stay on the card**, in a
> compact icon group, because they are the actions a daily user takes most and hiding them behind a swipe
> would be a regression; the completion toggle keeps its current position and gains the strongest
> emphasis. Edit and delete stay in the swipe panel. Nothing is removed, so `TaskCardWorkTest` and
> `TaskCardReminderTest` keep asserting against real views.

Overdue is the computed treatment from §1: date text and priority rule take `ink_error`, plus the word
"late" — never colour alone, never a new group or status.

Completed cards recede: no elevation, tertiary text, strikethrough title, pill still carrying the word
"Done".

Selection mode replaces the header with `.SelectionBar`. Swipe reveals edit and delete over a
surface-coloured panel, now correctly hidden when closed (§2.1).

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
each legible without colour. Contrast per §3.2 including the variant-surface rule, re-measured by script.

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

## 9. Delivery order

One branch, one PR, one commit per layer, each building and passing tests before the next starts, each
with its own screenshots:

1. tokens, themes, type, space, component styles and includes;
2. Tasks: the swipe-panel geometry fixed at root, with a test that pins the closed bounds;
3. Tasks: header, filter rail, card composition, and the tests that travel with them;
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
