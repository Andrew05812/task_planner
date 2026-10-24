# UI/UX audit — Task Planner, before the redesign

This is the "before" half of the Global UI/UX Redesign. Everything in it is either a file and a line, a
number a command printed, or a numbered photograph in [`images/ui-redesign/before/`](images/ui-redesign/before/).
It exists so that no later stage can argue about what the app looked like, what was wrong with it, or what
"fixed" was supposed to mean. The redesign it feeds is
[`docs/superpowers/specs/2026-10-23-global-ui-redesign-design.md`](superpowers/specs/2026-10-23-global-ui-redesign-design.md)
(revision 9) and its plan
[`docs/superpowers/plans/2026-10-23-global-ui-redesign-plan.md`](superpowers/plans/2026-10-23-global-ui-redesign-plan.md).

## 1. The baseline these photographs belong to

| | |
|---|---|
| Branch, UI code as photographed | `feat/global-ui-redesign` at `c1b2c07` — no layout, drawable, style or string changed between `42ebfc2` and this capture; the commits in between are test and document corrections |
| APK | `app/build/outputs/apk/debug/app-debug.apk`, sha256 `28e898e91ad7a18a505b4555f0026dba138463d497e944ccb10ce9a7ad9600f7` |
| Proof the bytes photographed are the bytes built | `adb pull /data/app/~~B0eRFENfPFmH7nk5-HFRWA==/com.taskplanner-RsGTmgYsnDqSSXusRoKrig==/base.apk` hashed to the same value, `28e898e9…600f7` |
| Device | `emulator-5554` (`befos_avd`), API 35, x86_64. Panel `Physical size: 540x1140`, `Physical density: 220` → 392.7 dp wide. No `wm size` override (§6 explains why forcing one is wrong here) |
| Data | `scripts/evidence-seed.sql`, applied by `scripts/apply-evidence-seed.py`: 5 categories, 2 topics, 2 recurrence series, 14 task rows (12 meant to be visible, 2 planted to prove the list still filters), 3 closed work stretches, 3 armed reminders. The list reports **13** visible rows, not 12: the app's own recurrence engine materialises the next instance of a series on launch, which is behaviour, not fixture error |
| Profile | created by the fixture only when the device has none; every row binds to `users.is_current = 1` |
| Captured by | `scripts/capture-ui-matrix.py`, which navigates by resource-id read from a live `uiautomator` dump, refuses to photograph a screen it did not reach, and writes `before/manifest.md` with the configuration of every file |

The data is reproducible rather than precious on purpose: `app/build.gradle.kts:29` sets
`clearPackageData`, so every instrumented run wipes the app, and AGP uninstalls it afterwards. A baseline
that cannot be re-created after a test run is not a baseline.

## 2. Test state at the baseline, measured

| gate | result |
|---|---|
| `./gradlew testDebugUnitTest` | **93 suites / 1374 tests / 0 failures**, measured on this branch after the review corrections (the baseline at Stage 0.1 was 93/1373; the extra test is `LegacySchemaV7SourceTest.the fixture plants a schema and nothing more`). §14.1 requires the suite to be **larger** at the PR, never smaller, and nothing here is `@Ignore`d or deleted — `skipped=0` in the same count |
| `./gradlew connectedDebugAndroidTest`, all six classes in one invocation, at `42ebfc2` | 17 tests / **3 failures** — the first execution these 17 methods ever had; CI only ever ran `assembleDebugAndroidTest` |
| Each class in its own invocation, after the two test corrections | all six classes pass, 17 tests / 0 failures |
| `DatabaseLocationInstrumentedTest` after the review corrections | 3 tests, green — Gradle reported `Starting 3 tests on befos_avd(AVD) - 15` and `BUILD SUCCESSFUL` |
| `TasksScreenInstrumentedTest` after the review corrections | 4 tests, green at the physical panel and again at `wm density 270` (320 dp) |

The three baseline failures, their root causes and the isolation rule that came out of them are recorded
in §20h of the plan. Two of them were defects in the tests; the third was cross-class interference, and
the plan is explicit about which mechanism was measured and which is still a hypothesis.

## 3. The configurations photographed

| file | screen | theme | width dp | density | font scale | uimode | visible tasks |
|---|---|---|---|---|---|---|---|
| 01-tasks | Tasks, list with data | light | 393 | 220 | 1.0 | light | 13 |
| 02-profile | Profile | light | 393 | 220 | 1.0 | light | 13 |
| 03-statistics | Statistics | light | 393 | 220 | 1.0 | light | 13 |
| 04-categories | Categories | light | 393 | 220 | 1.0 | light | 13 |
| 05-calendar | Calendar | light | 393 | 220 | 1.0 | light | 13 |
| 06-task-editor | Editor, opened from + | light | 393 | 220 | 1.0 | light | 13 |
| 07-insights | Insights | light | 393 | 220 | 1.0 | light | 13 |
| 08-goals | Goals | light | 393 | 220 | 1.0 | light | 13 |
| 09-habits | Habits | light | 393 | 220 | 1.0 | light | 13 |
| 10-reports | Reports | light | 393 | 220 | 1.0 | light | 13 |
| 13-settings | Settings | light | 393 | 220 | 1.0 | light | 13 |
| 14-profile-selection | Profile selection | light | 393 | 220 | 1.0 | light | 13 |
| 15-tasks-dark | Tasks | dark | 393 | 220 | 1.0 | dark | 13 |
| 16-profile-dark | Profile | dark | 393 | 220 | 1.0 | dark | 13 |
| 17-editor-dark | Editor | dark | 393 | 220 | 1.0 | dark | 13 |
| 18-tasks-320 | Tasks | light | 320 | 270 | 1.0 | light | 13 |
| 19-profile-320 | Profile | light | 320 | 270 | 1.0 | light | 13 |
| 20-editor-320 | Editor | light | 320 | 270 | 1.0 | light | 13 |
| 21-tasks-360 | Tasks | light | 360 | 240 | 1.0 | light | 13 |
| 22-profile-360 | Profile | light | 360 | 240 | 1.0 | light | 13 |
| 23-editor-360 | Editor | light | 360 | 240 | 1.0 | light | 13 |
| 24-tasks-font13 | Tasks | light | 393 | 220 | 1.3 | light | 13 |
| 25-profile-font13 | Profile | light | 393 | 220 | 1.3 | light | 13 |
| 26-editor-font13 | Editor | light | 393 | 220 | 1.3 | light | 13 |
| 28-selection-mode | selection mode | light | 393 | 220 | 1.0 | light | 13 |

`27-swipe-open` is the one file in the plan's list that was **not** taken, and §5 says why. Every other
row of this table shares the same account and the same 13 visible tasks, which is what makes a before and
an after pair comparable rather than merely similar.


## 4. What is wrong with the interface

Ten problems, each one either a file and a line or a numbered screenshot. They are ordered by how much of
the app's look they account for, not by how hard they are to fix — except the last, which is a functional
defect the visual list would have hidden.

### 4.1 The swipe action panel is visible on every closed row — `before/01-tasks.png`, `before/28-selection-mode.png`

Every card in `01-tasks.png` has a blue-and-red strip along its top edge and a red column down its right,
with no finger anywhere near it. The cause is structural and it is in the layout, not in the animation:
`item_task.xml:10-14` puts `actionPanel` in a `FrameLayout` at `layout_gravity="end"` with
`layout_height="match_parent"` and no margins, while the card on top of it carries
`layout_marginHorizontal="8dp"` and `layout_marginVertical="4dp"` (`item_task.xml:41-42`). The panel is
therefore wider and taller than the card that is supposed to hide it, and the gutter the card leaves is
exactly the panel's own border. The comment above the panel says "Hidden action panel"
(`item_task.xml:9`); it is not hidden, it is overlapped, and the overlap has margins. Spec §2.1 carries
the arithmetic (4 dp above and below, 8 dp down the right) and Stage 2 fixes the insets — which is why 2.3a
then has to sweep the ten other layouts that inherited the same 8 dp gutter.

### 4.2 The Tasks screen has no header, and four functions are unreachable because of it — `before/01-tasks.png`

There is no title bar of any kind above the category chips: the list starts at the top of the window. That
would be a style choice if the screen's own menu were reachable, but it is not. `TasksFragment.kt:67`
inflates `R.menu.menu_tasks`, which declares five items — search (`action_search`, a `SearchView`),
new group, archive, work history, and a sort submenu with four orders — and the app's theme is
`Theme.Material3.DayNight.NoActionBar` (`values/themes.xml:3`) with no `Toolbar` anywhere in
`app/src/main/res/layout/` and no `setSupportActionBar` in `MainActivity`. A menu that is inflated and
never attached to anything is a menu nobody can open.

The consequence is measured rather than argued: **Archive and Work history have no entry point at all.**
`nav_graph.xml:15-21` declares `action_tasks_to_archive` and `action_tasks_to_work_history`, and
`grep -rn "action_tasks_to_archive\|action_tasks_to_work_history" app/src/main` finds those declarations
and nothing else — no `navigate(R.id.navigation_archive)` anywhere in the code. Both screens exist, are
built, are tested on the JVM, and cannot be reached by a user. That is why the matrix has no
`11-archive.png` or `12-work-history.png`: the honest record is that there is no path to photograph, which
is the finding itself. Stage 3 restores the header and with it search, sort, new group, archive and work
history.

### 4.3 Category names are drawn in the category's own colour, as text, on white — `before/01-tasks.png`

`TasksAdapter.kt:288-291` calls `b.taskCategory.setTextColor(it.color)`, and `item_task.xml:126-132` gives
that view `textAppearance="?attr/textAppearanceCaption"` — 12 sp — on a card whose background is the
theme's `colorSurface`, `#FFFFFFFF` (`values/colors.xml:10`). The fixture's five category colours, measured
against that white with the WCAG relative-luminance formula, are:

| colour | hex | ratio on white | AA for 12 sp text (needs 4.5:1) |
|---|---|---|---|
| Диссертация | `#A5D6A7` | 1.64:1 | fails |
| Учёба | `#FFD54F` | 1.41:1 | fails |
| Работа | `#4FC3F7` | 2.00:1 | fails |
| Дом | `#F48FB1` | 2.23:1 | fails |
| Спорт | `#9E9E9E` | 2.68:1 | fails |

None of the five clears AA, and the palest is under a third of what it needs. The colour is the only thing
carrying the category's identity, so the fix is not "pick darker colours" but "stop using colour as text":
the dot carries the colour, the name is ink. The user's own palette is not the problem — the pattern is —
which is why the same five values are still legal data after the redesign.

### 4.4 Four of the five bottom-bar items show no label — `before/01-tasks.png`

`uiautomator` on the Tasks screen reports `navigation_bar_item_large_label_view` with text `Tasks` for the
selected item and the same node `INVISIBLE` for Calendar, Categories, Statistics and Profile; the small
labels are `INVISIBLE` for all five. That is `labelVisibilityMode`'s default (`auto`) on a bar with five
items, and it means the bar reads as five unexplained glyphs — including two that are geometric shapes.
Stage 7 labels all five permanently.

### 4.5 Material 2 widgets inside a Material 3 theme — counts, not taste

The single largest reason the app reads as stock rather than as designed: the theme is M3
(`values/themes.xml:3`) while the widgets are styled with M2 class names, and the two systems disagree
about shape, state layers, elevation and typography. In `app/src/main/res/layout/`:
`Widget.MaterialComponents.TextInputLayout.OutlinedBox` ×14,
`Widget.MaterialComponents.Button.OutlinedButton` ×14, `Widget.MaterialComponents.Chip.Filter` ×6,
`Widget.MaterialComponents.Button.TextButton` ×6, `Widget.MaterialComponents.ExposedDropdownMenu` ×5.
Stage 1 replaces the styles with tokens; 4.5 of the plan explains why the swap is done with `INVISIBLE`
rather than `GONE` where a view's slot has to survive.

### 4.6 Two identical play triangles on one card, doing different things — `before/01-tasks.png`

The card for "Глава 2: обзор методов" shows a pause glyph at the top right (the task's own lifecycle) and
a play triangle below it (the work timer). `startTaskButton` and `workControlButton` both use `ic_play`,
and they call different repositories: `TasksViewModel.kt:418` → `taskRepository.startTask`, `:447` →
`workTrackingRepository.start/pause/resume`. Two buttons that look identical and do different things is a
legibility defect, not a cosmetic one. Stage 4 gives them distinct icons and labels; it does **not** merge
the operations, because they are different facts about the task.

### 4.7 `dimens.xml` carries two spacing scales and five text sizes, four of which nobody reads

`values/dimens.xml:3-16` holds `spacing_xs/small/normal/large/xl` (4/8/16/24/32 dp) next to
`activity_horizontal_margin` and `activity_vertical_margin` (both 16 dp) — two names for the same scale,
so a layout can pick either and nothing tells it which. Of the five `text_*` sizes only `text_caption`
has a reader (`fragment_report.xml:244`); `text_body`, `text_title`, `text_headline` and `text_display`
are read by nobody. And the card that needs spacing most hard-codes it instead: `item_task.xml:42` is
`layout_marginHorizontal="8dp"`, a literal, in the row the redesign restyles. Stage 1 retires the four
unused sizes; `text_caption` retires with the report screen in 8d, because that is the only place it is
alive.

### 4.8 Profile is a column of eight full-width buttons — `before/02-profile.png`

Measured from the live hierarchy: "Edit Profile", "Switch Profile", "SETTINGS", "GOALS", "HABITS",
"INSIGHTS", "REPORTS", "DELETE PROFILE" — eight buttons of identical weight and identical width, stacked,
with no grouping, no icon on three of them, and a destructive action sitting two rows below the ordinary
ones with nothing to distinguish it. It is also the only screen in the app with no test of any kind: there
is no `ui/profile` package under `app/src/test/`, and where the instrumented suite mentions a profile at
all — `TaskEditorInstrumentedTest` creates and deletes one — the profile is a session fixture, never the
thing under test. Stage 5 regroups it and the profile tests arrive with it.

### 4.9 The dashboard's three-up figure row has no gutter — `before/03-statistics.png`

Found while capturing, not in the plan's list of eight. The headline card prints
"1 task finished14 tasks added" as one run of text: `fragment_statistics.xml:93-118` lays
`dashboardFinished`, `dashboardAdded` and `dashboardStreak` as three `0dp`/`weight=1` TextViews in a
horizontal `LinearLayout` with **no margin between them**, and each figure is a plural sentence long
(`DashboardRenderer.kt:113-114`), so a wrapped column runs straight into the next one. The row is also
styled with `?attr/textAppearanceBody1`, an M2 attribute in the M3 theme (4.5). Stage 8b gives the figure
row the token spacing scale and a layout that says what it means per column; this one is added to 8b's
acceptance so the photograph has something to be compared against.

### 4.10 A tap on a task row selects it instead of opening it, and the swipe reveal cannot be demonstrated

The most serious finding, and the one no test in the repository sees. `TasksAdapter.kt:109` hangs the whole
row interaction on `binding.taskCard.setOnTouchListener { _, event -> handleTouch(event) }`, and
`handleTouch` does the opening itself on `ACTION_UP` (`TasksAdapter.kt:185-194`) after arming a
`postDelayed(longPressRunnable, 400)` on `ACTION_DOWN` (`:148`). Measured on this emulator, with the app
freshly launched and no prior gesture: **one tap on a card entered selection mode and checked that row**
(`before/28-selection-mode.png` is that photograph) rather than opening the task, because the 400 ms
long-press timer fires before the slow image delivers the `UP`. And six injected horizontal drags of
150/200/250/300/400/600/900 ms, started and ended inside the card and away from both screen edges, moved
`taskCard` by **zero pixels** — while a vertical drag from the same tool scrolled the list, so the events
are arriving.

What that costs the evidence: `27-swipe-open.png` does not exist. What it costs the product: the two
primary interactions on the main screen — open a task, and swipe a row for Edit/Delete — could not be
demonstrated to work, while the third (long press to select) fires on what a user means as a tap. The
panel's exposure while closed is documented numerically instead: `uiautomator` reports `actionPanel` at
`[330,272][540,490]` against `taskCard` at `[11,278][529,484]` for the same row — 11 px of panel to the
right of the card and 6 px above it, on every row, with nothing touched.

Whether the zero-pixel drag is the emulator or the row's touch handling is exactly the question Stage 2's
acceptance answers: §14.7 requires the reveal to be demonstrated in a photograph *and* in
`TaskCardClosedPanelTest`. If it cannot be demonstrated on this device, that is a defect to fix, not a
screenshot to skip.

## 5. What this evidence cannot show

Stated plainly, because a limitation nobody writes down becomes a claim nobody checks.

**Resolution.** The panel is 540x1140 at density 220. It is the right *dp* width for the reference
configuration, but it samples hairlines, 11–12 sp text and 1 dp strokes at half the linear density of the
handset §6 records (1220 px wide). A 1 dp divider that renders as one pixel here may band on a real device.
The fix for this is not `wm size 1080x2280`: measured at `c1b2c07`, forcing that override makes Espresso's
injected taps miss their target — the navigation test taps `navigation_statistics`, the app stays on Tasks,
and the failure hierarchy proves it. Density-only overrides (240 for 360 dp, 270 for 320 dp on this panel)
change the dp without moving the pixel space, so they are what the matrix uses.

**One photograph the plan asked for and this run refused.** `27-swipe-open.png` does not exist, for the
reason measured in 4.10: no injected horizontal drag moved the card, on any of six timings, while vertical
drags scrolled fine. The alternatives were to fake it (a programmatic `translationX`, which would photograph
the app's drawing code rather than the user's gesture) or to leave the slot empty and say so. It is empty.
`before/manifest.md` lists the 25 files that do exist, each with its configuration and the same 13 visible
tasks, so every before/after pair later on is taken against the same account and the same data.

**Speed, and the taps that go missing.** Instrumented runs on this emulator take 2–4 minutes per class and
individual taps are occasionally swallowed: the same coordinate, tapped again, works (reproduced by hand on
`navigation_profile`, and again as a `GOALS` button that a second pass found). One connected invocation also
reported `Starting 0 tests … Process crashed` immediately after a density change while the app itself was
alive and rendering at 320 dp; re-running the same class passed 4/4. Recorded as an emulator hiccup, not as
a passing run.

**A physical handset is attached, and it is not a test device.** `adb devices` lists
`WCLJBINVEAT4PVGM` (API 35, arm64, 1220x2712 at 480 dpi) alongside the emulators.
`connectedDebugAndroidTest` fans out to every transport it can see, so every instrumented command in this
project carries `ANDROID_SERIAL` to pin it to one emulator. The handset's own Task Planner data was not
touched: `com.taskplanner.test` is not installed on it, and its app directory still holds the files it had
before.

**A database this build cannot open, found on a second emulator.** Running the suite unpinned also
surfaced a real crash on `emulator-5556`: `IllegalStateException: Migration didn't properly handle:
users` — the table that came out of the chain has `firebase_uid` and `avatar_url` but no `avatar_color` or
`avatar_initial`, which the entity requires. That shape matches **no schema this repository ever exported**
(`1.json`–`16.json`: every version from 5 up has both columns, and `MIGRATION_4_5` is what adds them), so
the database belongs to a different build of this app. Room refuses to open it rather than rewriting it,
which is the correct failure for user data; the redesign does not fix foreign databases and does not
pretend to. It is recorded here so the next person who sees it knows what it is, and the repair path —
if one is wanted — is a separate task with its own migration and its own test.

**What an emulator cannot stand in for at all:** a manufacturer's Documents UI for SAF exports, a
third-party PDF viewer, a real finger's pressure on a swipe, talkback announcements, and battery-optimiser
behaviour around alarms. Those are named in `docs/UI_REDESIGN_REVIEW.md` when the stage that touches them
runs.

## 6. What the redesign must not change

The brief is a visual and interaction redesign. Business logic, stored data, task states and existing
behaviour are not in scope, and the following are the concrete checks that keep it that way:

- the five statuses (`TaskStatus`: TODO, IN_PROGRESS, PAUSED, COMPLETED plus the archived flag) stay the
  states they are; the redesign changes how a state is drawn, not how many there are;
- `startTask` and the work timer stay two operations (4.6);
- swipe-to-reveal and `ItemTouchHelper` reordering keep working after the panel insets change (spec §2.1
  shows why the reveal arithmetic is unaffected);
- no test is deleted, disabled or weakened to make CI pass, and the instrumented suite stays 17 methods
  across six classes or grows;
- user data is never cleared, migrated destructively, or rewritten by a screenshot run — which is why the
  fixture deletes only rows whose uuid starts with `ev-`.
