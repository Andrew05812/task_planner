# Global UI/UX Redesign — design specification

Date: 2026-10-23
Branch: `feat/global-ui-redesign` (one branch, one PR, staged commits)
Baseline: `master` = `origin/master` = `c2c767d1b80db756c1a3517eecc083de598ff7af` (merge of PR #21)
Direction approved by the user: **Ink & Accent**.

## 0. What this stage is, and what it is not

A visual and interaction redesign of the whole application, built on a design system the app does not
have yet. It is not a migration, not a feature stage, and not a rewrite.

The stack stays exactly as it is: XML layouts, ViewBinding, Fragments, Navigation, Material Components
1.11, Hilt, Room. No Compose, no new runtime dependency, no new font file, no image-loading library.
Nothing under `data/` changes: no schema, no migration, no repository, no sync, no backup format, no
business rule. `RepeatPreset` keeps its nullable unit label — the crash fix must not be undone.

The measurable goal is the user's own: after installing the new APK, Tasks and Profile must not look
like the screenshots they supplied. Colour and corner-radius edits do not pass.

## 1. Facts this design is built on

Everything below was read from the code at the baseline commit, not assumed.

**Statuses are four and only four** — `TaskStatus.TODO`, `IN_PROGRESS`, `PAUSED`, `COMPLETED`. There is
no `OVERDUE` in the model and none will be added. Overdue may exist **only** as a computed visual
treatment derived from data already on the row — `dueDate` in the past, status not `COMPLETED`, and the
task not archived — since the list already excludes archived rows. It creates no state, no column, no
filter, and no group.

**Grouping is by topic, not by date.** `TasksListItem.GroupHeader` carries a `TaskGroup` (a topic the
user created, with its own collapsed flag). A "Today / This week / Overdue" header set would be new
functionality, so it is out. What the screen really can group by is what it groups by today.

**Sort orders that exist:** `MANUAL` (drag handle, persisted in `tasks.order`), `DATE_ASC`, `DATE_DESC`,
`PRIORITY_ASC`, `PRIORITY_DESC`, `NAME_ASC`, `NAME_DESC`.

**Row data available to a card** (`TasksListItem.TaskRow`): the task, its `Category?`, selection flag,
the `RecurrenceRule?` this instance belongs to, the count of pending reminders, and a `CardWork`
measured-time line. Priorities are `LOW`, `MEDIUM`, `HIGH`.

**Tasks header actions that already exist** (`menu_tasks.xml`): search, new group, archive, work
history, and the sort submenu. Archive and work history are reached from Tasks today, so Profile does
not duplicate them.

**Real navigation destinations** (so every Profile row can lead somewhere true): `navigation_goals`,
`navigation_habits`, `navigation_insights`, `navigation_reports`, `navigation_settings`,
`navigation_profile_selection`, `navigation_task_detail`, `navigation_archive`,
`navigation_work_history`, `navigation_category_detail`, `navigation_goal_detail`,
`navigation_goal_editor`, `navigation_habit_detail`, `navigation_habit_editor`. There is **no**
achievements destination and no achievements package: the Achievements screen does not exist yet, so
Profile gets no Achievements row.

**Backup and import live inside Settings** (`fragment_settings.xml`: `exportButton`, `importButton`,
`signInButton`, `signOutButton`, `versionText`), so Profile's Data section points at Settings rather
than inventing a second backup entry.

## 2. The two real defects found during the audit

These are bugs, not style opinions, and they are fixed by root cause. Both are visible in the audit
screenshot at `docs/images/ui-redesign/before/tasks.png`.

### 2.1 The swipe action panel is always peeking out from behind every card

`item_task.xml` is a `FrameLayout` (`wrap_content`) holding a hidden `actionPanel` at
`layout_gravity="end"` with `layout_height="match_parent"`, and the `taskCard` on top of it with
`layout_marginHorizontal="8dp"` and `layout_marginVertical="4dp"`.

The FrameLayout is sized by the card **including its margins**, so the panel is 8dp taller and 8dp wider
than the card it is supposed to hide behind. The card's own margins then leave the right-hand 8dp of the
panel exposed. That is the blue/red strip along the top of every row and the red column down the right
edge of the list — it appears with no interaction at all, on every row, permanently.

Fix: the panel gets the same insets as the card. The row's `FrameLayout` keeps the vertical margin, the
card keeps its horizontal margin, and the panel is given `layout_marginStart` equal to the card's
horizontal margin plus `layout_marginVertical` equal to the card's, so the panel's visible rectangle is
exactly the card's rectangle when the card is at `translationX = 0`. Verified by a layout test that
asserts the panel's drawn bounds equal the card's while closed, and by a screenshot of an untouched list.

The reveal itself stays: `TasksAdapter.handleTouch` translates `taskCard` left over `actionPanel`,
tracks one `openHolder`, and closes on tap. Swiping keeps working; the drag handle and `ItemTouchHelper`
manual reordering are untouched.

### 2.2 The FAB covers a list row

`fragment_tasks.xml` already gives the `RecyclerView` `paddingBottom="88dp"` with `clipToPadding="false"`,
but the FAB is constrained to the parent's bottom edge with a 16dp margin, which puts it over the last
row's text because the bottom navigation bar is not accounted for. Fix: the FAB sits above the bottom
bar with a real inset and the list's bottom padding is derived from the same dimension, so the last row
is always reachable and readable.

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
| `ink_surface_variant` | `#F1EDE7` | `#26231F` | wells, meta rows, chips |
| `ink_text` | `#1A1614` | `#F2EDE7` | primary text |
| `ink_text_secondary` | `#6B635C` | `#A39B92` | meta text |
| `ink_text_tertiary` | `#7A716A` | `#8C837A` | captions, timestamps |
| `ink_outline` | `#E3DDD5` | `#312D29` | hairlines (decorative) |
| `ink_outline_strong` | `#B9B1A7` | `#5A544D` | strokes that carry state |
| `ink_accent` | `#2F6F5E` | `#6FD3B8` | the one accent |
| `ink_on_accent` | `#FFFFFF` | `#0E201B` | text on filled accent |
| `ink_success` | `#2F7A4B` | `#7CC98F` | finished |
| `ink_warning` | `#9A6320` | `#E0A95F` | paused, workload |
| `ink_error` | `#C0402F` | `#F0836F` | overdue, destructive |

The theme maps these onto the Material 3 roles (`colorPrimary`, `colorSurface`,
`colorSurfaceVariant`, `colorOutline`, `colorError`, and the `colorSurfaceContainer` family —
`color_surface_container`, `_low`, `_lowest`, `_high`, `_highest` all exist in Material 1.11, confirmed
in the artifact's `R.txt`), so stock Material components inherit the identity instead of fighting it.
Every `?android:attr/textColorSecondary` and hard-coded hex in layouts is replaced by a role.

Existing chart and report colours keep their own fixed values — a printed report must not change with the
device theme, and that is deliberate and documented in `colors.xml` today. The chart ramps are re-anchored
to the new accent so the dashboard and the list agree.

### 3.2 Contrast, computed

Ratios below were calculated from the sRGB relative-luminance formula, not estimated. Text pairs must
meet WCAG 2.1 AA (4.5:1 for the sizes used here); graphics that carry state must meet 3:1.

Light: `text/background` 17.10, `text/surface` 17.97, `text/variant` 15.41, `secondary/surface` 5.89,
`secondary/variant` 5.05, `tertiary/surface` 4.78, `accent/surface` 5.91, `accent/variant` 5.06,
`on_accent/accent` 5.91, `success/surface` 5.24, `warning/surface` 5.02, `error/surface` 5.23.

Dark: `text/surface` 15.20, `secondary/surface` 6.45, `tertiary/surface` 4.75, `accent/surface` 9.85,
`on_accent/accent` 9.42, `success/surface` 8.94, `warning/surface` 8.43, `error/surface` 6.89.

Status pills put the coloured word on a tint of the same colour. The tint alpha is **8 %** on light
(worst pair 4.51) and **14 %** on dark (worst 5.57) — 14 % on light fails at 4.18, which is why the two
themes do not share one alpha. Priority rules, the selected-chip stroke and the focus ring measure 4.78
to 5.91 against the surface, above the 3:1 graphics bar. `ink_outline` at 1.35 is decorative only and is
never the sole carrier of a state.

These numbers are re-verified by a script during implementation and again at the end of the stage; the
table above is a claim to be reproduced, not trusted.

### 3.3 Type

System font only. Identity comes from size, weight and tracking, which also means no APK growth and no
licensing question.

| Style | Size / line | Weight | Tracking | Used for |
|---|---|---|---|---|
| `display` | 28 / 34 | 400 | 0 | screen title |
| `headline` | 22 / 28 | 500 | 0 | group and section headers |
| `title` | 16 / 22 | 500 | 0 | card title, row title |
| `body` | 15 / 22 | 400 | 0 | descriptions |
| `meta` | 13 / 20 | 400 | 0.1 | dates, category, counts |
| `label` | 11 / 14 | 500 | 0.4, all caps | status pills, section labels |
| `metric` | 20 / 24 | 500 | tabular figures | dashboard and report numbers |

Defined once as `TextAppearance.TaskPlanner.*` over the Material 3 parents and applied through theme
attributes, so no layout sets a raw `textSize` for these roles again.

### 3.4 Space and shape

Base 4dp; scale 4 / 8 / 12 / 16 / 20 / 24 / 32. Screen margin 20dp. Card padding 14 vertical / 16
horizontal. Card gap 8dp. Between groups 20dp. Row height 56dp minimum, touch target 48dp minimum
everywhere including icon buttons. Radius: card 14dp, control 12dp, sheet and dialog 20dp, pill fully
rounded. Elevation is used twice at most (cards 1dp, dialogs 8dp); separation is done with surface
roles and hairlines, not shadows.

### 3.5 Components

Each is a named style in `styles.xml` with a Material 3 parent, replacing the current mix of
`Widget.MaterialComponents.*` (M2) inside an M3 theme — that mismatch is a large part of why the app
reads as stock.

`Widget.TaskPlanner.Card` · `.Card.Completed` · `.StatusPill` (four variants + overdue) ·
`.PriorityRule` · `.SectionLabel` · `.MetaText` · `.Chip.Filter` · `.Chip.Category` ·
`.Button.Filled` · `.Button.Tonal` · `.Button.Outlined` · `.Button.Text` · `.Button.Destructive` ·
`.ListRow` (icon container + title + optional value + chevron) · `.ListItem` · `.BottomBar` ·
`.Dialog` · `.Field` (outlined text input) · `.EmptyState` · `.LoadingState` · `.ErrorState` ·
`.SelectionBar`.

Empty, loading and error states get real compositions — an icon, a sentence, and the one action that
escapes them — replacing today's bare centred `TextView`.

## 4. Screen designs

### 4.1 Tasks

Header: `Tasks` in display size with the open-count as a `meta` subtitle. To its right, three actions
that already exist but are buried in an overflow menu today — search, sort, and a kebab holding new
group / archive / work history. Search becomes a real expanding field in the header; it drives the same
`TasksViewModel` query the menu item drives now.

Filter rail below the header: categories as chips, a hairline divider, then the four status chips. When
anything is active a `Filters · n` chip appears at the end and clears all in one press. The rail scrolls
horizontally at 320dp instead of wrapping and stealing list height.

List: topic group headers in `headline` with the count and the collapse control, exactly as today.
Cards are re-composed:

- left edge: a 4dp priority rule, inset from the card border, in error/warning/tertiary for
  HIGH/MEDIUM/LOW; no rule for LOW, so absence reads as "not urgent";
- title in `title` size, two lines maximum, then a single `meta` line: relative due date
  ("Today 18:00", "3 days late", "No due date"), a 8dp category dot with the category name, and icons
  only for what is actually present — repeat, pending reminders, measured time;
- right edge: one primary action, the completion toggle, at 48dp; everything else stays in the swipe
  panel and the kebab;
- completed cards recede: no elevation, tertiary text, strikethrough title, and the pill keeps the word
  "Done" so the state is never colour-only.

Overdue is the computed treatment from §1: the date text and the priority rule take `ink_error`, plus a
word ("late") — never colour alone, and never a new group or status.

Selection mode replaces the header with `.SelectionBar` (count, select all, delete, cancel). Swipe
reveals edit and delete over a surface-coloured panel, now correctly hidden when closed (§2.1). The FAB
no longer overlaps the last row (§2.2).

### 4.2 Profile

Rebuilt as a screen with information architecture, not a column of buttons.

- **Header**: 64dp avatar — the initial in an accent-tinted circle. `users.avatar_url` is written by
  Google sign-in but is rendered nowhere today, and rendering it would mean a network fetch plus an image
  cache inside a visual stage; so the remote photo stays out of scope (§10) and the avatar is the initial
  the model already carries. Name in `headline`; email in `meta`. Nothing invented.
- **Account**: Edit profile, Switch profile. Switch opens the existing `navigation_profile_selection`.
- **Your work**: Goals, Habits, Insights, Reports — the four destinations that exist.
- **Data**: Settings, which is where export, import, sync and sign-in already live.
- **Danger zone**: Delete profile, in a separated group with its own destructive styling and a
  confirmation dialog that names the consequence in the user's language, keeping today's logic.

Each row is a `.ListRow`: 40dp tinted icon container, `title`, optional trailing value, chevron, 56dp
tall, ripple, hairline between siblings inside a group. No row goes anywhere that does not exist.

### 4.3 Task editor

Same fields, same validation, same `RepeatPreset` behaviour — regrouped visually into labelled sections:
Task (title, description), When (due date, time, plan), How (priority, status, category), Repeat,
Reminders, Measured work. Repeat stays collapsed until opened, which the existing layout test asserts.

Field errors stay inline on the field that caused them. The keyboard does not cover the field being
typed in. Save and Cancel become a sticky footer so they are reachable from any section. Delete keeps its
existing confirmation.

### 4.4 Navigation and the remaining screens

Bottom bar: all five labels always visible, the active item marked by a pill behind the icon and by
`ink_text` against `ink_text_secondary`, background from the surface role instead of
`?android:attr/windowBackground` (today the bar merges into the screen), and correct system-window
insets so it never sits under the gesture bar.

Categories, Calendar, Statistics, Insights, Goals, Habits, Reports, Archive, Work history, Settings and
the profile-selection screen are moved onto the same tokens, type styles and components. Their layouts
change only where the new components require it; their queries, view models, calculations and wording
logic do not.

## 5. Motion

Entry animation on list items (fade plus 12dp rise, 180ms, decelerate) fires on data change, not on
scroll, and is skipped entirely for lists above a threshold so a long list never animates a hundred
views. Status change cross-fades the pill. Group collapse animates height. Pressed states use the
standard ripple. All durations come from one place and the system's animator scale is respected, which
Android applies automatically — no custom override that would fight it.

## 6. Accessibility and adaptivity

Touch targets ≥48dp. Every icon-only control has a `contentDescription`. Status, priority and overdue are
each legible without colour. Focus order in dialogs is sane and the keyboard does not cover input.
Contrast per §3.2, re-measured by script.

Verified on the emulator at 320dp, 360dp and 393dp widths, at font scale 1.0 and 1.3, in light and dark.
The widths are produced reproducibly with `adb shell wm size` / `wm density` overrides (recorded as the
exact pixel+density pair for each width in the review document), not eyeballed; font scale with
`settings put system font_scale`; theme with `cmd uimode night`. No fixed height on any text-bearing row;
text is allowed to grow. Screenshots of every combination are kept, not asserted.

## 7. Performance

No new dependency. `RecyclerView` keeps its existing adapter and diffing; the card layout is flattened
from today's 266-line nesting so each row measures less. No image loading library — the profile avatar is
an initial unless a URL exists, in which case it is loaded once and cached in the view model, not
re-fetched on rebind. Scroll performance is compared before and after on a seeded account of the same
size.

## 8. Verification

The acceptance evidence is screenshots, not a build log.

For every main screen: same emulator (1080×2280, 440dpi), same seeded account, same theme, same font
scale, before and after, in `docs/images/ui-redesign/before/` and `.../after/`. Tasks, Profile and the
task editor additionally get dark-theme and 320dp and font-scale-1.3 pairs.

`docs/UI_UX_AUDIT.md` records the starting problems with the before images. `docs/DESIGN_SYSTEM.md`
records the tokens and components. `docs/UI_REDESIGN_REVIEW.md` goes screen by screen: what changed,
which audit problem it answers, what is still open, and on what device configuration it was checked.

Tests: all 1371 JVM tests and the 4 instrumented ones stay enabled and unweakened. New coverage at the
fragment level, following the pattern PR #21 introduced: Tasks renders groups, statuses, long titles,
empty state and the closed swipe panel; Profile renders without an avatar, with a long name and a long
email, and every row navigates; the task editor opens, validates and saves. Layout tests at 320dp and
font scale 1.3 extend the existing pattern.

Known open flake: `WorkAdjustmentUiTest > a stretch that ends where or before it began is refused by the
form` failed mainline run #55 and passed a re-run of the same commit. If it appears in this branch it is
investigated to root cause, never routed around by weakening an assertion.

## 9. Delivery order

One branch, one PR, one commit per layer, each building and passing tests before the next starts, each
with its own screenshots:

1. tokens, themes, type, space, component styles;
2. Tasks: the two layout defects fixed;
3. Tasks: header, filter rail, card composition;
4. Profile rebuilt;
5. task editor regrouped;
6. bottom navigation and insets;
7. remaining screens onto the system;
8. motion;
9. accessibility and adaptivity pass;
10. tests, docs, before/after evidence.

Independent code review and an independent visual review both run before the PR opens, and their findings
are fixed or argued, not acknowledged.

## 10. Explicitly out of scope

Compose migration. New dependencies. Any change to Room schema, migrations, repositories, sync, backup
format, auth, time tracking, statistics calculations, report content, achievement rules, or the goal and
habit models. New features, including an Achievements screen. Any status, filter or group that the current
data model cannot answer.
