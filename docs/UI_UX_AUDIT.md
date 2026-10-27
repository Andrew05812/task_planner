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

**Resolved in Stage 3.** The header is on the screen (`after/s3-header.png`) and the five actions are behind
it: `after/s3-kebab.png` photographs the popup holding New topic / Archive / Work history, and
`after/s3-archive.png` and `after/s3-work-history.png` are the two screens reached from it by a press — the
first time either has been reachable in a build. `TasksHeaderInstrumentedTest` drives all of it on a device:
typing narrows the list through `setSearchQuery`, the sort popup reorders it through `setSortOrder`, the
kebab navigates, and the subtitle counts what is open. One correction to what this section assumed: a
`PopupMenu` *is* visible to Espresso on this hardware (`inRoot(isPlatformPopup())` reaches its items) — the
unreadable popup recorded in Stage 0 is the editor's `AutoCompleteTextView` dropdown specifically, not popups
in general, and the sort and kebab tests are built on that difference.

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

**Resolved in Stage 4.** Line A now opens with an 8dp dot tinted with `category.color` (`TasksAdapter.kt`
`bind`), and the name on line B is `ink_text_tertiary` — the word never takes the category's colour, so the
five palette values above are no longer text colours at all and none of them has to clear 4.5:1. The name is
also the only element on the card allowed to give way, and it gives way by a measured rule rather than by
truncation: it is drawn when at least 48dp are left on the line after the pill and the date, and gone
otherwise. Measured on the device, at 393dp the line is 363px, the pill 107px and the date 108px, which
leaves 126px — the name is drawn (`after/s4-393.png`); at 320dp the same row's line is 321px against a pill
of 136px and a date of 131px, which leaves 26px against a floor of 81px — the name is gone and the dot stands
alone (`after/s4-320.png`). `TaskCardWidthBudgetInstrumentedTest` asserts both the dot's tint and that
visibility, so neither half can drift back. The room is measured from the laid-out line rather than from the
screen width, which is what lets one layout serve 320dp, 360dp and 393dp and font scale 1.0 and 1.3. The two
remaining points of that grid are 360dp at 1.0 — 348px line, 120px pill, 120px date, 84px of room against a
72px floor, name drawn — and 393dp at font scale 1.3, where the pill grows to 133px and the date to 143px and
the room lands on 65px against a 66px floor: the name goes, by one pixel (`after/s4-393-font13.png`). At
320dp/1.3 the room is negative (−42px), so the pill and the date wrap onto two lines rather than clip
(`after/s4-font13.png`). The full five-row table is in plan §20l.

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

**Resolved in Stage 4.** The timer control draws `ic_work`, a stopwatch drawn for this stage because the
repository had no clock-shaped icon among its 42; the paused card draws `ic_resume`, a bar plus a triangle,
which is what replaces the second bare play triangle; `ic_play` is left on `startTaskButton` alone. The
distinctness is measured rather than asserted in XML: the device test rasterises each vector under one flat
tint and compares the pixels, so a difference in colour cannot be mistaken for a difference in shape, and it
refuses a frame in which a shape drew nothing (which is what the first version of that check did — these
vectors take their colour from a theme attribute, and loaded on an unthemed context they render blank, so
four empty bitmaps "proved" they were all different). Completion is now the only filled control on the card
(`bg_action_complete`, an `ink_on_accent` check on `ink_accent`), which is the other half of the same
complaint: the baseline's card had eight borderless glyphs and no answer to "what does this row want me to
do". Photographs: `after/s4-card-running.png` (stopwatch, pause, filled check) and `after/s4-card-paused.png`
(resume mark, no second triangle). Every visible control is a 48dp target with a content description, and the
work control's description names its phase — "Start measuring", "Pause measuring", "Resume measuring" — so
the two operations stay distinct to a screen reader as well as to an eye.

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

**Resolved in Stage 5.** The eight buttons are eight `row_list` rows under four named headers — Account (2),
Your work (4), Data (1), Danger zone (1) — each row a 40dp icon plate with a real glyph, the words, and a
chevron saying it goes somewhere; the header block carries a 64dp avatar with the initials, the name in
`headline` and the address in `meta`. The destructive row is now separated by a section of its own rather
than by paint, and the whole 56dp line is the click target (measured on the device at 104px tall at 393dp,
against a 48dp floor the test names). The profile has tests: `ProfileLayoutTest` (11 JVM),
`ProfileScreenInstrumentedTest` (6 on the device) and `ProfileNoUserInstrumentedTest` (2, the empty state
this screen had never rendered).

Two defects surfaced while writing them, and both are fixed rather than noted. The delete confirmation was
built with `setMessage(R.string.dialog_delete_profile_message)`, and that string carries a `%1$s` —
`setMessage(int)` does not substitute arguments, so the reader was shown *"Are you sure you want to delete
profile %1$s?"*. It now names the profile and the loss: tasks, categories, goals, habits and tracked work.
And `row_list` shipped in Stage 1 with an icon *container* and no icon, which is why the old buttons had to
carry their glyphs through `app:icon` — three of the eight did not. The row also announced itself to TalkBack
as a `LinearLayout`; `ListRowView` now reports `android.widget.Button` and the four headers are real
headings, so a reader can jump between sections instead of walking eight rows.

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

**Resolved in Stage 2, and it was the row.** An instrumented drag driven from inside the process
(`TaskCardSwipeInstrumentedTest`, Espresso's `GeneralSwipeAction`, which injects real motion history
rather than the shell's) moved the card zero pixels too, on two different AVDs — so the emulator was
cleared and the row was measured. A throwaway probe printed what the framework hands the card:
`clickable=false longClickable=false`, 22 motion events reaching the list with `rawX` running 695→281, and
`translationX` staying at `0.0` — while a listener that answers `ACTION_DOWN` with `true` moved the *same*
injected stream to `-345px`. That is the whole mechanism: `handleTouch` declined the down, the card is
neither clickable nor long-clickable so `View.onTouchEvent` declined it too, `ViewGroup` therefore recorded
no touch target for the child, and `ACTION_MOVE`/`ACTION_UP` were never delivered to the row. The drag
could not start, a tap could not land, and the long press — armed on the one event that was delivered and
cancelled only by the one that never was — fired on what the reader means as a tap. `TaskCardRowGestureTest`
now dispatches gestures at the row (not at the card, which would bypass the routing being tested) and pins
all four outcomes: a drag reveals, a tap opens, a tap does not select, a held press selects without opening.
The reveal photograph this section said could not be taken is `after/s2-row-swiped.png`.

### 4.11 The editor is one long scroll with no floor, and half of its groups are anonymous — `before/06-task-editor.png`, `before/20-editor-320.png`, `before/26-editor-font13.png`

The screen the app is used most, measured at the baseline rather than described. `fragment_task_detail.xml`
at `3ce8ecd` is a `NestedScrollView` whose single child is a `ConstraintLayout` of 93 view tags, and
`button_container` — Cancel and Save — is the *last* item inside it (`:764`). The root assertion
`TaskDetailLayoutTest` was built on says the same thing back: `index(button_container) == content.childCount
- 1`. So the primary action of a form with sixteen fields sits below its own fold at every width this audit
photographs, and it moves further away when the keyboard opens, because the window's default soft-input mode
was never declared and the scroll view owns the whole screen.

Grouping is half-done. Three of the six groups carry a title (`plan_section_title`, `work_section_title`,
`reminder_section_title`), and the other three — the date and time fields, the priority/status/category
choices, the whole repeat block — are anonymous runs of boxes. Where a title does exist it is
`android:textAppearance="?attr/textAppearanceSubtitle2"`, one of the Material 2 attributes §4.5 counts, and
no heading semantics: a screen-reader user hears a long column of fields with no sections. Ordering is the
other half of the problem — the fields sit in the order they were added rather than the order they are
filled in: `plan_section` sits before `work_section`, and both the category field and the work block are
constrained to
the same anchor below it, which is why `TaskDetailLayoutTest` could assert `work_section < task_priority_layout`
as a fact about the screen.

State is carried by colour alone in two places. The reminder and weekday chips are bare
`com.google.android.material.chip.Chip` elements with no `checkedIcon`, so a ticked lead time differs from an
unticked one only in fill; the measured difference between the two fills is small enough that a reader with a
colour vision deficiency has to remember which side was which. `work_state_dot` is a 10 dp view whose
background drawable is 8 dp, and it is the only thing between "running" and "not running" — the row's own
text is the elapsed counter, which also reads as a number when the stretch is paused.

**Resolved in Stage 6.** The form now scrolls and the footer does not: `editor_scroll` is a weighted child of
a vertical screen, `button_container` is its sibling, and `TaskDetailLayoutTest` asserts that relationship
rather than the old index (`scroll.bottom == footer.top`, `footer.bottom == window`). The manifest declares
`adjustResize` for the activity's soft-input mode, so the claim does not rest on an undocumented
default, and `theFooterStaysOnTheWindowWithTheKeyboardUp` measures the visible frame on the device. Six
sections in the order the reader fills them in — Task, When, How, Repeat, Reminders, Measured work — each
opened by `Widget.TaskPlanner.SectionHeader` with `android:accessibilityHeading="true"`, which the platform
honours from API 28: the app's minSdk is 24, and on 24 to 27 that attribute is dropped when the layout
inflates, so heading navigation is claimed for API 28+ and not for the whole supported range. The three
deliberate inversions of the old order are named in the commit message. Ticked chips carry a check icon
(`Widget.TaskPlanner.Chip.State`), and the work dot is drawn at `@dimen/dot_state` with
`importantForAccessibility="no"`, because the words beside it already say what it means. Six of the lines
the editor rewrites while the reader is somewhere else are `accessibilityLiveRegion="polite"`, and the two
chip groups that had no name now have one (`labelFor`). Frames: the twelve the editor's frames class writes in
each configuration — `after/s6-editor-top.png`, `s6-editor-how.png`, `s6-editor-reminders.png`,
`s6-editor-reminders-ticked.png`, `s6-editor-bottom.png`, `s6-editor-work-running.png`,
`s6-editor-date-picker.png`, `s6-editor-time-picker.png`, `s6-editor-category-picker.png`,
`s6-editor-repeat.png`, `s6-editor-new-empty.png`, `s6-editor-error.png` — all of them from 393 dp, light,
font scale 1.0 — plus four frames taken in another configuration: `s6-editor-320.png` and
`s6-editor-360.png` (the running commands, wrapped at the narrow widths), `s6-editor-font13.png` (the bottom
of the form at font scale 1.3) and `s6-editor-dark.png` (the top of the form at night). The class wrote
twelve frames in each of the twelve configurations — 144 photographs, every one measured before it was
allowed to become a file. §20o of the plan lists the configurations, what each run cost, and the frames the
rules refused before they were fixed.

## 5. What this evidence cannot show

Stated plainly, because a limitation nobody writes down becomes a claim nobody checks.

**Resolution.** The panel is 540x1140 at density 220. It is the right *dp* width for the reference
configuration, but it samples hairlines, 11–12 sp text and 1 dp strokes at half the linear density of the
handset §6 records (1220 px wide). A 1 dp divider that renders as one pixel here may band on a real device.
The fix for this is not `wm size 1080x2280`: measured at `c1b2c07`, forcing that override makes Espresso's
injected taps miss their target — the navigation test taps `navigation_statistics`, the app stays on Tasks,
and the failure hierarchy proves it. Density-only overrides (240 for 360 dp, 270 for 320 dp on this panel)
change the dp without moving the pixel space, so they are what the matrix uses.

**One photograph the plan asked for, and the two things that were hiding it.** `27-swipe-open.png` was not
taken, and §4.10 records the row's own defect as the reason it could not be. Two environment facts found
while measuring that are part of the record now, because both will otherwise be rediscovered as a
"flaky emulator" at the next stage:

 * **the edge band belongs to the system.** Both AVDs run gesture navigation (`settings get secure
   navigation_mode` = 2). A drag that begins at 95% of the card's width — which is where a right-to-left
   swipe naturally starts on a full-width card — left the activity `STARTED` rather than `RESUMED` and
   moved nothing: the system took it as Back. Every interior start point kept the activity in front. A
   reveal gesture has to be initiated (and demonstrated) from inside the card, not from its outer edge,
   and Stage 4's card work should keep that in mind for the ergonomics of the affordance.
 * **this AVD is shared.** During Stage 2 another session on this machine was driving `emulator-5554` for a
   different project: it launched its own app into the foreground (`mCurrentFocus` named a package that is
   not this one) and left a crashed `uiautomator dump` holding the single `UiTestAutomationService` slot.
   Every instrumented run after that failed with `UiAutomationService already registered` / `Cannot call
   disconnect() while connecting`, screenshots were refused, and injected input went to whichever window
   had focus. `adb shell dumpsys accessibility | grep -i "ui automation"` returning a line is the symptom;
   a reboot of the AVD clears it. Nothing in the other session's data was touched, and nothing of ours was
   trustworthy until it was checked — which is why the reveal was finally measured on a second AVD
   (`Pixel_4`, 1080x2280 at 440 dpi) and the photographs in `after/` say so. From Stage 4 onward neither of
   those is the device: this project now owns `taskplanner_avd` (`emulator-5584`), created for this repository
   at the baseline's own metrics (540x1140 @ 220 dpi = 393x829 dp), so no session borrows another project's
   emulator and the before/after pairs stay on identical geometry.

`before/manifest.md` lists the 25 files that were taken, each with its configuration and the same 13 visible
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

**One app process serves one profile, and a screenshot can miss the card.** Two limits found while taking
Stage 4's evidence, both of which produce a *wrong result that looks like a real one*:

* A connected run is one app process, and `SessionRepository` is a Hilt singleton inside it: the user read
  at the process's first launch is the user every later launch of that run serves, however many rows
  `setCurrentUser` has moved underneath it. `clearPackageData` wipes once per *invocation*, not per class and
  never per test. So an instrumented class that seeds a fresh profile in each `@Before` is, from the second
  test onward, reading the *first* test's rows — and because a task carries no foreign key to its user while
  its series, reminders and work intervals do, those rows survive with their cascade-deleted children gone.
  The symptoms Stage 4 met were exactly that and looked like card defects: a repeat mark missing from a row
  whose state said it had a series, a work line missing from a row the database said was being measured, and
  a due line one minute away from the deadline the test had just written. The rule the Stage 4 classes now
  follow is one profile per class, written in `@BeforeClass` before any screen opens and deleted in
  `@AfterClass` along with its rows; a class that ignores it must also `am force-stop` the app between
  classes, which is what the matrix script does around every run.
* `uiAutomation.takeScreenshot()` answers with whatever the compositor holds, and twice in Stage 4's first
  matrix it answered with the Tasks header over a list that had not drawn its row yet — a photograph of an
  empty screen filed beside a passing geometry test, because the test had measured the row seconds earlier
  in a different frame. Both screenshot helpers now require the row to be laid out and shown, then count the
  ink inside the card's own rectangle in the captured frame, and retake the frame until it holds one (up to
  six attempts). The `after/s4-*.png` files in this repository were produced that way, and each run prints
  the number of frames a photograph needed alongside the dp and font scale it was taken at.

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
