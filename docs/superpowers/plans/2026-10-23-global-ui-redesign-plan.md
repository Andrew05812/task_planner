# Global UI/UX Redesign — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: use `superpowers:executing-plans` (or
> `superpowers:subagent-driven-development`) to implement this plan stage by stage. Steps use checkbox
> (`- [ ]`) syntax for tracking. Every step is one action; every stage ends in a commit that builds and
> passes tests before the next stage starts.

**Goal:** Replace the app's stock-looking visual layer with the Ink & Accent design system and rebuild
Tasks, Profile and the task editor on top of it, so the installed app no longer resembles the audit
screenshots, without touching data, domain or business behaviour.

**Architecture:** Unchanged — XML layouts, ViewBinding, Fragments, Navigation, Material Components 1.11.0,
Hilt, Room. The redesign lands as a token layer (`docs/design/tokens.json` → `res/values/colors.xml`), a
theme/type/space/component layer, and then per-screen layouts and display-layer Kotlin. The line the spec
draws and this plan enforces: **display code may change, data and domain code may not.**

**Tech Stack:** Kotlin 1.9.22, AGP 8.2.2, JDK 17, Gradle 8.11.1, Room 2.6.1 (schema v16), Hilt 2.50,
Material 1.11.0, Robolectric 4.11.1 over real in-memory SQLite, JUnit 4, Espresso 3.5.1,
`ActivityScenario`. Python 3 for the contrast script.

**Spec:** `docs/superpowers/specs/2026-10-23-global-ui-redesign-design.md`, revision 8. Its SHA is
whatever `git log -1 --format=%H -- <that path>` answers at the moment a stage is started — the
previous line in this header named a commit that had already been superseded twice, which is how
the audit found the drift, and a document cannot cite its own successor. This plan argues from that document; read both. The
revision matters: revisions 1 through 6 each contained a claim this plan or a reviewer had to change. Two
independent reviews produced revision 6 and a third produced revision 7; all three are recorded in §20 and
§20b with the disposition of every finding.

**Baseline:** branch `feat/global-ui-redesign` at `ee75e61` (spec revision 7) with this plan's own commit
on top of it, and `master` =
`c2c767d1b80db756c1a3517eecc083de598ff7af` (merge of PR #21).

**Delivery:** one branch, one PR, one commit per layer. The PR is #22 or the next free number.

---

## 1. Scope

In scope: colour/type/space/shape tokens; theme mapping; component styles and reusable layout includes;
the Tasks header, filter rail and card; the swipe-panel geometry defect; Profile's information
architecture; the task editor's grouping and sticky footer; bottom navigation treatment; every remaining
screen moved onto the system; motion; the accessibility and adaptivity matrix; the documentation and
before/after evidence.

Out of scope (spec §10, restated because scope creep is this stage's main risk): Compose migration, new
dependencies, edge-to-edge and window-inset rework, rendering `avatarUrl`, any change to the Room schema,
migrations, DAOs, repositories, sync, backup format, auth, time tracking, statistics calculations, report
content, achievement rules, goal and habit models, and any new feature including the Achievements screen.

The measurable acceptance is the user's own: after installing the new APK, Tasks and Profile must not look
like the supplied screenshots. Colour and corner-radius edits alone do not pass.

## 2. Starting point, verified

Each figure below was read or run at `a9f70e3` on 2026-10-23 and re-checked at `d1839a8` on 2026-10-24
by two reviewers working from the source, not inherited from the previous report. The spec baseline this
plan is written against is revision 8.

| claim | evidence |
|---|---|
| JVM suite is 92 suites / 1371 tests / 0 failures | `./gradlew testDebugUnitTest`, then counting `app/build/test-results/testDebugUnitTest/*.xml` (all files timestamped 21:40, i.e. this run) |
| Instrumented suite is 6 classes / 17 `@Test` methods | `grep -rc "@Test" app/src/androidTest` → `TaskEditorInstrumentedTest` 4, `TasksScreenInstrumentedTest` 4, `PdfReportInstrumentedTest` 3, `DatabaseLocationInstrumentedTest` 3, `ReportScreenInstrumentedTest` 2, `ReportPdfBytesInstrumentedTest` 1 |
| 52 layout files, 0 hard-coded hex colours in them | `ls app/src/main/res/layout/*.xml \| wc -l`; grep for `#RRGGBB` in layouts returns nothing |
| `?android:attr/textColorSecondary` appears in 20 layout files | grep count per file |
| M2 widget styles inside an M3 theme | `Widget.MaterialComponents.TextInputLayout.OutlinedBox` ×14, `.Button.OutlinedButton` ×14, `.Chip.Filter` ×6, `.Button.TextButton` ×6, `.ExposedDropdownMenu` ×5 |
| No Toolbar anywhere | `grep -rn "Toolbar" app/src/main/res/layout/` → no files; `MainActivity` has no `setSupportActionBar` |
| `TimeBase` already exists and is Hilt-provided | `data/tracking/TimeBase.kt` (`wallMillis()`, `monotonicMillis()`), `di/AppModule.kt:46 fun provideTimeBase(): TimeBase = AndroidTimeBase()` |
| Overdue already has one definition | `data/local/TaskStatisticsDao.kt:63` → `due_date IS NOT NULL AND due_date < :now` |
| `startTask` and `toggleWork` are different operations | `TasksViewModel.kt:418` → `taskRepository.startTask`; `:447` → `workTrackingRepository.start/pause/resume` |
| Database | `AppDatabase.NAME = "taskplanner.db"`, `version = 16`, `exportSchema = true` |
| No clock-shaped icon exists to reuse | `ls app/src/main/res/drawable \| grep '^ic_'` lists 42 icons; none is a timer, clock or stopwatch, so the work control's distinct icon is a new vector |
| `contentGroup` is asserted from another screen's test | `TasksScreenInstrumentedTest:56` checks it is displayed after a round trip through Statistics |
| Two existing tests constrain the design, not just the code | `StatisticsLayoutTest` requires five legacy text attributes to resolve to five **distinct** styles; `TaskCardWorkTest` requires the work line and the title to share a parent |
| `@dimen/text_caption` has one reader | `fragment_report.xml:244` — the legacy text sizes are not unused |
| CI | `.github/workflows/android-ci.yml`: `assembleDebug`, `testDebugUnitTest`, `lintDebug`, `assembleDebugAndroidTest` |

Re-verify before Stage 0 closes:

```bash
git rev-parse HEAD && git status --short
./gradlew --no-daemon --console=plain testDebugUnitTest
python - <<'PY'
import glob, xml.etree.ElementTree as ET
t=s=f=0
for x in glob.glob('app/build/test-results/testDebugUnitTest/*.xml'):
    r=ET.parse(x).getroot(); s+=1; t+=int(r.get('tests',0)); f+=int(r.get('failures',0))+int(r.get('errors',0))
print(s,t,f)
PY
```

Expected: `92 1371 0`. If the numbers differ, stop and reconcile before writing any layout — every later
stage compares against them.

## 3. Global constraints

Every task below implicitly includes this section. Values are copied from the spec verbatim.

- Stack: XML + ViewBinding + Fragments + Material Components 1.11.0 + Navigation + Hilt + Room. No Compose,
  no new runtime dependency, no new font file, no image-loading library.
- `compileSdk 34`, `minSdk 24`, `targetSdk 34`, `applicationId com.taskplanner`.
- Nothing under `data/` changes: no schema, no migration, no repository, no sync, no backup format, no
  business rule. `RepeatPreset.unitLabelRes` stays nullable — the PR #21 crash fix must not be undone.
- Presentation computations must satisfy spec §0: existing row data only, no state and no writes, no second
  definition of a domain rule. Current time arrives through `TimeBase`, never `System.currentTimeMillis()`.
- Text contrast ≥ 4.5:1 (WCAG 2.1 AA), state-bearing graphics ≥ 3:1, measured by
  `python scripts/contrast_report.py`, never asserted by hand.
- Semantic-coloured text (`ink_success`, `ink_warning`, `ink_error`) is never placed on
  `ink_surface_variant`.
- Touch targets ≥ 48dp. Every icon-only control has a `contentDescription`. Status, priority and overdue are
  each legible without colour.
- A "watch it fail" step must fail **as a test**, not as a build. Kotlin resolves `R.id.…` and
  `R.style.…` at compile time, so a test naming a resource that does not exist yet produces
  `unresolved reference` and no test run at all. Every stage that introduces new ids therefore has two
  commits: a skeleton that declares the ids and styles with no behaviour, after which the test is a real
  red, and the change that turns it green. Where a test can read resources reflectively — as 1.2's
  contrast test does through `R.color::class.java.getField` — it should, because then the red is genuine
  and the missing token is the message.
- No existing test is disabled, deleted or weakened. When an assertion must move, it is rewritten to the new
  truth in the same commit as the layout it covers, and the commit message names it.
- Never force-push, never rewrite published history, never clear user data or uninstall the app, never
  claim a device check that did not happen, never invent `google-services.json`.
- Screenshots are the acceptance evidence. A stage whose screen changed without a fresh screenshot is not
  finished.
- Do not set `GRADLE_USER_HOME` when running proofs. Commands given to the user must be single-line and
  PowerShell-safe.

## 4. Design tokens in use

The single source is `docs/design/tokens.json`; `scripts/contrast_report.py` measures it and the shipped
resources. Stage 1 writes these into `res/values/colors.xml` and `res/values-night/colors.xml`.

| token | light | dark | | token | light | dark |
|---|---|---|---|---|---|---|
| `ink_background` | `#FBF9F6` | `#12100F` | | `ink_outline_strong` | `#8C8378` | `#7A736A` |
| `ink_surface` | `#FFFFFF` | `#1A1817` | | `ink_accent` | `#2F6F5E` | `#6FD3B8` |
| `ink_surface_raised` | `#FFFDFB` | `#221F1D` | | `ink_on_accent` | `#FFFFFF` | `#0E201B` |
| `ink_surface_variant` | `#F1EDE7` | `#26231F` | | `ink_success` | `#2F7A4B` | `#7CC98F` |
| `ink_text` | `#1A1614` | `#F2EDE7` | | `ink_warning` | `#8F5B18` | `#E0A95F` |
| `ink_text_secondary` | `#6B635C` | `#A39B92` | | `ink_error` | `#C0402F` | `#F0836F` |
| `ink_text_tertiary` | `#6E655D` | `#968D84` | | `ink_on_error` | `#FFFFFF` | `#2B0F0A` |
| `ink_outline` | `#E3DDD5` | `#312D29` | | `pill_accent/success/warning/error/neutral` | `#EEF3F2` `#EEF4F1` `#F6F2ED` `#FAF0EE` `#F1EDE7` | `#26322E` `#283128` `#362C21` `#382723` `#26231F` |

Type roles (`spec §3.3`, nine of them — tracking is given in `android:letterSpacing` units, which are ems):
display 28/34·400·0 · headline 22/28·500·0 · title 16/22·500·0 · body 15/22·400·0 · meta 13/20·400·0.019 ·
caption 12/16·400·0.021 · pill 11/14·500·0.045 normal case · label 11/14·500·0.045 all caps ·
metric 20/24·500·0. System font only.

Space (`spec §3.4`): base 4dp; scale 4/8/12/16/20/24/32; screen margin 20; card padding 14 vertical /
16 horizontal; card gap 8; between groups 20; row ≥ 56dp; touch target ≥ 48dp; radius card 14, control 12,
sheet 20, pill fully rounded; elevation cards 1dp, dialogs 8dp.

## 5. Where each tool is actually used

The user asked for real use, not a list. This is the schedule; the stage write-ups repeat it at the step
where the tool runs. Tools are invoked through the `Skill` tool (native names as listed in the session) or
the `Agent` tool for the review roles.

| stage | tool | what it does there |
|---|---|---|
| 0 | `mobile-use:mobile-use` | drive the emulator, capture the before-matrix, read real view bounds |
| 1 | `design-review:design-system-capture` | capture the pre-existing de-facto system (ad-hoc spacing, M2-in-M3 styles) so the token table is provably complete, not a wish list |
| 1 | `ui-ux-pro-max-skill:design-system` | check the three-layer primitive→semantic→component split of the token file |
| 3, 4, 5, 7 | `frontend-design` | the card, the profile rows, the editor sections, the remaining screens |
| 3, 9 | `design-review:accessibility-review` | contrast, targets, focus, labels, `contentDescription` sweep |
| 3 | `android-jetpack` | RecyclerView adapter, Navigation, ViewBinding, Hilt conventions in the changed Kotlin |
| 6, 9 | `design-review:responsive-design` | the 320/360/393dp contract and the width budget |
| 9, 10 | `design-review:visual-regression-review` | before/after pairs, threshold and mask decisions, "meaningful difference" report |
| 9, 10 | `design-review:design-debt-review` | hard-coded values, magic numbers, token drift left behind |
| 9, 10 | `design-review:component-library-alignment` | every screen uses `Widget.TaskPlanner.*`, no stray M2 styles |
| 10 | `product-design:check` | design walkthrough checklist over the finished screens |
| 10 | `architecture-visualization:dependency-impact-analyzer` | blast radius of the shared style/token files before the PR opens |
| 10 | `superpowers:requesting-code-review` → `pr-review-toolkit:code-reviewer` | independent code review |
| 10 | `pr-review-toolkit:pr-test-analyzer` | do the tests cover what changed |
| 10 | `pr-review-toolkit:silent-failure-hunter` | error handling introduced around navigation and file IO |
| 10 | `superpowers:verification-before-completion` | before any "done" claim |

Not planned for use, and why, belongs in the final report rather than here: `product-design:board`,
`:flow-mobile`, `:avatar`, `:poster`, `:pitch`, `:metric`, `:retro`, `:probe`, `:signal`, `:bench` (the
direction is already approved and this stage adds no new flow, no research and no marketing asset);
`tarot-pixel`, `figma` (no design file exists — the spec is the design source); `sites:*`,
`qoder-cloud-agents`, `mongodb`, `qmind`, `Presentations`, `slidev` (unrelated to this repository);
`superpowers:using-git-worktrees` (the user chose one branch, one PR, and work continues in place);
`mobile-use:setup-mobile-use` (the emulator is already running and verified).

## 6. Commands

Build and test (run from `C:/my/myprogi/task-planner/task_planner_repo`, which is the git root — the Gradle
project is here, not one level up):

```bash
./gradlew --no-daemon --console=plain assembleDebug              # compile gate
./gradlew --no-daemon --console=plain testDebugUnitTest           # 1371 JVM tests
./gradlew --no-daemon --console=plain lintDebug                   # must not add findings
./gradlew --no-daemon --console=plain assembleDebugAndroidTest     # instrumented compile gate (CI)
./gradlew --no-daemon --console=plain testDebugUnitTest --tests "com.taskplanner.ui.tasks.*"
python scripts/contrast_report.py --check --format markdown --out docs/design/contrast-report.md
python scripts/contrast_report.py --resources app/src/main/res --check
```

Device (emulator-5554 is live; API 35 x86_64):

```bash
adb devices -l
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 shell input keyevent KEYCODE_WAKEUP
# width matrix — dp = 1080 / (density / 160)
adb -s emulator-5554 shell wm size 1080x2280 && adb -s emulator-5554 shell wm density 440   # 393dp
adb -s emulator-5554 shell wm size 1080x2280 && adb -s emulator-5554 shell wm density 480   # 360dp
adb -s emulator-5554 shell wm size 1080x2280 && adb -s emulator-5554 shell wm density 540   # 320dp
adb -s emulator-5554 shell settings put system font_scale 1.3     # and 1.0 to restore
adb -s emulator-5554 shell cmd uimode night yes                   # and `no` for light
adb -s emulator-5554 shell pidof com.taskplanner                  # alive check, not a claim
adb -s emulator-5554 exec-out screencap -p > docs/images/ui-redesign/after/01-tasks.png
adb -s emulator-5554 shell uiautomator dump /sdcard/ui.xml && adb -s emulator-5554 exec-out cat /sdcard/ui.xml
```

Instrumented run (device only; CI only compiles them):

```bash
adb -s emulator-5554 shell input keyevent KEYCODE_WAKEUP
./gradlew --no-daemon --console=plain connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.taskplanner.ui.tasks.TaskEditorInstrumentedTest
```

Before trusting any screenshot: prove the installed bytes **are** the built bytes. Hashing the local file
and then reading `lastUpdateTime` proves nothing about identity — that is exactly the check that failed in
the audit, where a stale APK sat on the device. The procedure below was run on this emulator and returned
`PASS`; it is reproduced verbatim.

```bash
# 1. build, then install exactly that file
./gradlew --no-daemon --console=plain assembleDebug
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk

# 2. the path the package manager actually loaded
P=$(adb -s emulator-5554 shell pm path com.taskplanner | tr -d '\r' | sed -n 's/^package://p' | head -1)
echo "$P"

# 3. hash it on the device (the shell user can read it: base.apk is -rw-r--r-- system system)
DEV=$(adb -s emulator-5554 exec-out sha256sum "$P" | tr -d '\r' | cut -d' ' -f1)

# 4. and again after pulling it, in case the on-device hash command is unavailable
LOCAL=$(sha256sum app/build/outputs/apk/debug/app-debug.apk | cut -d' ' -f1)
adb -s emulator-5554 pull "$P" /tmp/installed-base.apk >/dev/null
PULLED=$(sha256sum /tmp/installed-base.apk | cut -d' ' -f1)

# 5. three states, never a fourth
if [ "$LOCAL" = "$DEV" ] && [ "$LOCAL" = "$PULLED" ]; then echo "PASS $LOCAL"
elif [ -z "$DEV" ] || [ -z "$PULLED" ]; then echo "BLOCKED: cannot read the installed APK"
else echo "FAIL local=$DEV pulled=$PULLED"; fi
```

Measured at `0abd9cb`, for reference: `base.apk` is world-readable, `pm path` returns
`/data/app/~~esTsuNRQ…/com.taskplanner-eBW6J5SvcLEGLbxlF4qlAQ==/base.apk`, and local, on-device and pulled
hashes were all `28e898e91ad7a18a505b4555f0026dba138463d497e944ccb10ce9a7ad9600f7` — a real `PASS`, not an
inference from a timestamp.

`BLOCKED` is a legitimate outcome and must be reported as one: a release build on a stock handset, or a
manufacturer ROM that tightens `/data/app` permissions, can make the installed APK unreadable to `adb`. In
that case the identity claim is **not** made; the review document says which of the three states was
reached, and the screenshot evidence is labelled "installed APK identity unverified" rather than dropped
silently. Nothing about this procedure clears app data or reinstalls over the user's account — it reads.

## 7. Stage dependency graph

```
S0 evidence ─┬─► S1 tokens ─┬─► S2 swipe geometry ─► S3 Tasks header ─► S4 Tasks card ─┐
             │              │        (S1 must land first: 2.3 paints the panel with ink_* and bg_row_backdrop)        │
             │              ├─► S5 Profile ─────────────────────────────────────────────┤
             │              ├─► S6 Task editor ─────────────────────────────────────────┤
             │              └─► S7 Bottom nav ───────────────────────────────────────────┤
             │                          S8 remaining screens (8a Categories/Archive/WH/   │
             │                             Settings/ProfileSelection → 8b Statistics →    │
             │                             8c Goals/Habits/Insights → 8d Calendar/Reports)│
             │                                        └───────────────────────────────────┤
             └────────────────────────────────────────────────────────────────────────────┤
                                                                          S9 motion ───────┤
                                                                          S10 a11y matrix ─┤
                                                                          S11 docs+evidence┤
                                                                          S12 review→PR ───┘
```

Hard rules the graph encodes:

- S1 precedes everything: no screen may hard-code a value a token exists for.
- S2 precedes S4: the panel geometry fix must be proven on the *current* card before the card changes, or
  the test cannot tell which of the two fixed it.
- S3 precedes S4: the header owns search/sort, and the card's drop rules depend on the sort order the
  header sets.
- S8b (Statistics) is the only stage that re-anchors the chart and health ramps, and S1 leaves them alone on
  purpose; it updates the tests that assert their invariant in the same commit.
- S9, S10, S11 run after every screen is on the system; S12 opens the PR.
- S5, S6, S7 are independent of each other and of S3/S4 — they may be reordered, none may be merged into
  one commit.
- One file, one owner per stage. Two places in this plan used to break that rule: `Widget.TaskPlanner.BottomBar`
  is authored in 1.8 and wired into the theme by 1.5, which means stage 1 already changes the bar while its
  acceptance says no layout changed — stage 7 now owns the *treatment* and stage 1 owns only the style's
  existence, and 1.12's screenshots are read with that in mind. And 8.4b edits `fragment_tasks.xml` (owned by
  S3/S4) and `fragment_profile.xml` (owned by S5) after those stages photographed them, so 8.4b re-takes the
  Tasks and Profile after-shots rather than leaving a screenshot of a superseded file.
- S8 depends on S1 **and** S5 **and** S7, not on S1 alone: 8a and 8c consume `row_list` (created in S5) and
  the bottom-bar style (S7), so running 8a before S5 would invent a second row layout. The graph edge in §7
  is drawn from S1 only because the diagram is read left to right; this is the accurate statement, and the
  precondition line of Stage 8 is the one a worker follows.

## 8. File inventory

New files (all created in the stage named):

```
res/values/type.xml                     res/values/component_styles.xml     S1
res/drawable/bg_card.xml                res/drawable/bg_row_backdrop.xml    S1
res/drawable/bg_pill_accent.xml         res/drawable/bg_pill_success.xml    S1
res/drawable/bg_pill_warning.xml        res/drawable/bg_pill_error.xml      S1
res/drawable/bg_icon_container.xml      res/drawable/bg_field.xml           S1
res/drawable/bg_nav_item.xml            res/drawable/bg_hairline.xml        S1
res/layout/header_screen.xml            res/layout/row_list.xml             S1
res/layout/state_empty.xml              res/layout/state_loading.xml        S1
res/layout/state_error.xml              res/layout/bar_selection.xml        S1
res/color/chip_background_ink.xml       res/color/chip_stroke_ink.xml       S1
test/.../ui/theme/Wcag.kt                        S1
test/.../ui/theme/AbstractContrastTest.kt        S1
test/.../ui/theme/ContrastLightTest.kt           S1
test/.../ui/theme/ContrastDarkTest.kt            S1
test/.../ui/theme/TypeScaleTest.kt  test/.../ui/theme/ListGutterTest.kt     S1
test/.../ui/theme/ComponentStyleTest.kt          S1
test/.../ui/theme/IncludeBindingTest.kt          S1
res/drawable/ic_work.xml (the measurement icon the card needs; see 4.5)      S4
test/.../ui/tasks/TaskCardClosedPanelTest.kt                                   S2
test/.../ui/tasks/CardPresentationTest.kt        S4
test/.../ui/tasks/TaskCardWidthBudgetTest.kt     S4
test/.../ui/tasks/TasksHeaderLayoutTest.kt       S3
androidTest/.../ui/tasks/TasksHeaderInstrumentedTest.kt   S3
test/.../ui/profile/ProfileLayoutTest.kt         S5
androidTest/.../ui/profile/ProfileScreenInstrumentedTest.kt  S5
docs/UI_UX_AUDIT.md                     S0
docs/DESIGN_SYSTEM.md                   S11
docs/UI_REDESIGN_REVIEW.md              S11
docs/images/ui-redesign/before/*.png    S0 (28 files)
docs/images/ui-redesign/after/*.png     S1..S10
```

Modified files, by stage:

| stage | files |
|---|---|
| S1 | `res/values/colors.xml`, `res/values-night/colors.xml`, `res/values/themes.xml`, `res/values-night/themes.xml`, `res/values/dimens.xml`, `res/values/styles.xml`, delete `res/drawable/bg_action_edit.xml`, `res/drawable/bg_action_delete.xml` (S2), `res/color/chip_background_color.xml`, `res/color/chip_stroke_color.xml` |
| S2 | `res/layout/item_task.xml`, `main/.../ui/tasks/TasksAdapter.kt` |
| S3 | `res/layout/fragment_tasks.xml`, `res/menu/menu_tasks.xml`, `main/.../ui/tasks/TasksFragment.kt`, `res/values/strings.xml` |
| S4 | `res/layout/item_task.xml`, `res/layout/item_group_header.xml`, `main/.../ui/tasks/TasksAdapter.kt`, `main/.../ui/tasks/TasksViewModel.kt` (constructor param only), `main/.../ui/tasks/TasksFragment.kt`, `res/values/dimens.xml`, `res/values/strings.xml`; tests `TaskCardWorkTest`, `TaskCardReminderTest`, `TasksScreenInstrumentedTest` |
| S5 | `res/layout/fragment_profile.xml`, `main/.../ui/profile/ProfileFragment.kt`, `res/values/strings.xml` |
| S6 | `res/layout/fragment_task_detail.xml`, `main/.../ui/tasks/TaskDetailFragment.kt`; tests `TaskDetailLayoutTest`, `util/WorkAdjustmentUiTest`, `TaskEditorInstrumentedTest` |
| S7 | `res/layout/activity_main.xml`, `res/values/themes.xml`, `res/menu/bottom_nav_menu.xml` — and
     **not** `MainActivity.kt`, which an earlier draft listed with no step that touched it: the bar's
     label mode, surface and indicator are all XML and theme, and the nav controller already drives it |
| S8 | the layouts and adapters listed per sub-stage in §Stage 8 |
| S9 | `main/.../ui/tasks/TasksAdapter.kt`, `res/anim/`, `res/values/anim.xml` (new), `res/values/dimens.xml` |
| S10 | no source; screenshots and `docs/UI_REDESIGN_REVIEW.md` |
| S11 | `README.md`, `docs/DESIGN_SYSTEM.md`, `docs/UI_UX_AUDIT.md`, `docs/UI_REDESIGN_REVIEW.md`, `docs/design/contrast-report.md` |

---

## Stage 0 — Evidence, baseline, audit document

Purpose: fix what "before" means, so no later stage can argue about it. Depends on: nothing.
Precondition: `git status --short` is empty and HEAD is the plan commit, whose SHA is on this page's
header — record it in the audit document as the baseline the before-photographs belong to.

- [ ] **0.0 Create the evidence folders.** `docs/images/ui-redesign/` does not exist yet:
  `mkdir -p docs/images/ui-redesign/before docs/images/ui-redesign/after`

- [ ] **0.1 Re-count the baseline.** Run the commands in §2. Expected `92 1371 0` and 17 instrumented
  methods. Record the numbers in `docs/UI_UX_AUDIT.md`. If they differ, stop: the spec's §8 needs the same
  correction this plan's §16 would otherwise inherit.

- [ ] **0.2 Confirm the device is the one the spec describes.**

```bash
adb -s emulator-5554 shell getprop ro.build.version.sdk && adb -s emulator-5554 shell wm size && adb -s emulator-5554 shell wm density
```

Measured at `d1839a8`: `ro.build.version.sdk` = `35`, and the panel reports **`Physical size: 540x1140`
with `Physical density: 220`** — which is already 393dp, and is not the 1080x2280@440 an earlier draft of
this plan assumed. Two consequences, both of which have to be honoured rather than noted: the density
pairs for the other two widths are **240 for 360dp and 270 for 320dp** at this panel, not 480 and 540; and
a 540x1140 screenshot is half the linear resolution of a real handset, so hairlines and 11sp text are
under-sampled in the evidence. Prefer forcing the full panel and letting `wm density` do the dp work:

```bash
adb -s emulator-5554 shell wm size 1080x2280 && adb -s emulator-5554 shell wm density 440
adb -s emulator-5554 shell wm size   # must report both Physical and Override lines
```

Record the reported `Physical`/`Override` pair, the density, the font scale and the uimode in the review
document **for every screenshot**, and reset with `wm size reset && wm density reset` at the end. An
earlier session found this emulator sitting at 540x1140 after a reboot, which made every tap miss; the
override is not optional, it is the thing that makes the matrix comparable to itself.

- [ ] **0.3 Build and install the baseline APK**, then prove the installed bytes are the built bytes
  (§6). The audit found a stale APK; do not repeat that.

- [ ] **0.4 Capture the before-matrix** with `mobile-use:mobile-use` (screenshots + real view bounds),
  same account, same data, no clearing:

| file | screen | theme | width | font |
|---|---|---|---|---|
| `before/01-tasks.png` | Tasks, list with data | light | 393dp | 1.0 |
| `before/02-profile.png` | Profile | light | 393dp | 1.0 |
| `before/03-statistics.png` | Statistics | light | 393dp | 1.0 |
| `before/04-categories.png` | Categories | light | 393dp | 1.0 |
| `before/05-calendar.png` | Calendar | light | 393dp | 1.0 |
| `before/06-task-editor.png` | Editor, opened from `+` | light | 393dp | 1.0 |
| `before/07-insights.png` `08-goals.png` `09-habits.png` `10-reports.png` `11-archive.png` `12-work-history.png` `13-settings.png` `14-profile-selection.png` | remaining screens | light | 393dp | 1.0 |

and the configurations the acceptance list pairs later — every one of these must exist **before** the
change it will be compared against, because a baseline cannot be reconstructed afterwards:

| file | screen | theme | width | font |
|---|---|---|---|---|
| `before/15-tasks-dark.png` `16-profile-dark.png` `17-editor-dark.png` | the three focus screens | dark | 393dp | 1.0 |
| `before/18-tasks-320.png` `19-profile-320.png` `20-editor-320.png` | the three focus screens | light | 320dp | 1.0 |
| `before/21-tasks-360.png` `22-profile-360.png` `23-editor-360.png` | the three focus screens | light | 360dp | 1.0 |
| `before/24-tasks-font13.png` `25-profile-font13.png` `26-editor-font13.png` | the three focus screens | light | 393dp | 1.3 |
| `before/27-swipe-open.png` | one row swiped open | light | 393dp | 1.0 |
| `before/28-selection-mode.png` | selection mode | light | 393dp | 1.0 |

Create the folders first — `docs/images/` exists but `ui-redesign/` does not, so a `screencap` redirect
into it fails until this runs:

```bash
mkdir -p docs/images/ui-redesign/before docs/images/ui-redesign/after
```

Record the exact `wm size` + `wm density` + `font_scale` + `uimode` quadruple for each file in a table at
the top of `docs/UI_UX_AUDIT.md`, and prove the same account and the same row count in every pair:

```bash
adb -s emulator-5554 exec-out "run-as com.taskplanner sqlite3 databases/taskplanner.db 'select count(*) from tasks'"
```

If `run-as`/`sqlite3` is unavailable on this image, record the visible row count from the screenshot
instead and say so in the audit — do not silently drop the check that the data was the same.

Restore `wm density 440`, `font_scale 1.0`, `uimode night no` afterwards, and record that you did.

- [ ] **0.5 Write `docs/UI_UX_AUDIT.md`.** Every finding must point at a file and line or at a numbered
  screenshot. The confirmed list at this baseline:

1. The swipe action panel is visible on every closed row — the blue/red strip along the top and the red
   column down the right of `before/01-tasks.png`. Root cause in spec §2.1.
2. The Tasks screen has no header at all, and search, sort, archive and work history are unreachable,
   because no Toolbar exists (spec §1). Archive and work history have no other entry point in the app.
3. Category names are drawn in the category's own colour as text on white (`before/01-tasks.png`:
   "Диссертация" in pale green) — below AA. Fixed by the dot-carries-the-colour rule.
4. Four of the five bottom-bar items show no label (`labelVisibilityMode` default), so the bar reads as
   five unexplained glyphs.
5. M2 widget styles inside an M3 theme (counts in §2) — the single largest reason the app reads as stock.
6. Two identical play triangles can appear on one card (`startTaskButton` and `workControlButton` both use
   `ic_play`) while doing different things: `taskRepository.startTask` versus
   `workTrackingRepository.start`. A real legibility defect, fixed in S4 with distinct icons and labels,
   not by merging the operations.
7. `dimens.xml` carries two spacing scales — `spacing_xs/small/normal/large/xl` at 4/8/16/24/32 alongside
   `activity_horizontal_margin` and friends — and five `text_*` sizes. Four of the five `text_*` are read by
   nobody; `text_caption` is read by `fragment_report.xml:244`, so it retires with the report screen in 8d
   and not with the token stage.
8. Profile is a column of eight full-width buttons with no grouping, no icon on three of them, and no test
   anywhere in the repository.

- [ ] **0.6 Commit.**

```bash
git add docs/UI_UX_AUDIT.md docs/images/ui-redesign/before/
git commit -m "stage 0: the before-evidence, and eight problems the redesign has to answer"
```

**Acceptance:** 28 PNGs in `docs/images/ui-redesign/before/`, each named for the configuration it was
taken in; the audit's eight findings each backed by a file:line or a screenshot; baseline numbers recorded
and matching §2. **Screenshots:** the whole before-matrix.

---

## Stage 1 — Design system: tokens, theme, type, space, components

Purpose: build the layer every later stage reads from. Depends on: S0. Precondition: S0 committed.
Tools: `design-review:design-system-capture`, `ui-ux-pro-max-skill:design-system`.

- [ ] **1.1 Capture what exists before replacing it** with `design-review:design-system-capture`: inventory
  every colour, dimension and style currently referenced by a layout, and diff it against the token table.
  Anything in use but not in the table is either added or explicitly mapped to an alias in 1.3. This is
  what prevents a stage-8 screen discovering it has no token for something.

- [ ] **1.2 Write the failing contrast tests.** New file `app/src/test/java/com/taskplanner/ui/theme/Wcag.kt`
  holds the arithmetic; the two theme classes below assert the pairs against the shipped resources:

```kotlin
package com.taskplanner.ui.theme

import kotlin.math.pow

/**
 * WCAG 2.1 relative luminance and contrast ratio over the sRGB channels of a packed ARGB colour.
 *
 * Kotlin has no exponent operator, so the linearisation is `Double.pow` rather than `**`.
 */
object Wcag {
    private fun channel(v: Int) = (v / 255.0).let {
        if (it <= 0.03928) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4)
    }

    fun luminance(color: Int): Double =
        0.2126 * channel(color shr 16 and 0xFF) +
            0.7152 * channel(color shr 8 and 0xFF) +
            0.0722 * channel(color and 0xFF)

    fun ratio(foreground: Int, background: Int): Double {
        val a = luminance(foreground); val b = luminance(background)
        return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    }
}
```

```kotlin
package com.taskplanner.ui.theme

import android.content.Context
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import com.taskplanner.R
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The pairs spec 3.2 promises, asserted against the shipped colour resources.
 *
 * scripts/contrast_report.py measures docs/design/tokens.json; this class measures what the APK carries.
 * The two agree only while the resources match the table, which is the point: editing a hex value in
 * colors.xml without the table fails here, in the build, rather than in a screenshot three stages later.
 */
@RunWith(RobolectricTestRunner::class)
abstract class AbstractContrastTest {
    protected lateinit var context: Context
        private set

    abstract fun themeName(): String

    @Before
    fun setUp() {
        RuntimeEnvironment.setQualifiers(qualifiers())
        context = ApplicationProvider.getApplicationContext()
    }

    protected abstract fun qualifiers(): String

    private fun color(name: String): Int {
        val id = R.color::class.java.getField(name).getInt(null)
        return ContextCompat.getColor(context, id)
    }

    private fun text(fg: String, bg: String) = check(fg, bg, 4.5, "text")
    private fun graphic(fg: String, bg: String) = check(fg, bg, 3.0, "graphic")

    private fun check(fg: String, bg: String, bar: Double, kind: String) {
        val ratio = Wcag.ratio(color(fg), color(bg))
        assertTrue(
            "$kind: @$fg on @$bg is %.2f, below the %.1f the design promises".format(ratio, bar),
            ratio >= bar
        )
    }

    /** The four surfaces every general text role may sit on. */
    private val surfaces = listOf("ink_background", "ink_surface", "ink_surface_raised", "ink_surface_variant")

    @Test fun `general text clears AA on every surface it is used on`() {
        listOf("ink_text", "ink_text_secondary", "ink_text_tertiary", "ink_accent")
            .forEach { fg -> surfaces.forEach { bg -> text(fg, bg) } }
    }

    @Test fun `semantic text clears AA on the three surfaces the design allows it on`() {
        listOf("ink_success", "ink_warning", "ink_error").forEach { fg ->
            listOf("ink_background", "ink_surface", "ink_surface_raised").forEach { bg -> text(fg, bg) }
        }
    }

    @Test fun `a pill carries its own background and its own text`() {
        text("ink_accent", "pill_accent"); text("ink_success", "pill_success")
        text("ink_warning", "pill_warning"); text("ink_error", "pill_error")
        text("ink_text_secondary", "pill_neutral")
    }

    @Test fun `filled controls carry their declared foreground`() {
        text("ink_on_accent", "ink_accent"); text("ink_on_error", "ink_error")
    }

    @Test fun `graphics that carry state clear one point three`() {
        graphic("ink_error", "ink_surface"); graphic("ink_warning", "ink_surface")
        graphic("ink_accent", "ink_surface"); graphic("ink_outline_strong", "ink_surface")
        graphic("ink_outline_strong", "ink_surface_variant")
    }
}

@RunWith(RobolectricTestRunner::class)
class ContrastLightTest : AbstractContrastTest() {
    override fun themeName() = "light"
    override fun qualifiers() = "notnight"
}

@RunWith(RobolectricTestRunner::class)
class ContrastDarkTest : AbstractContrastTest() {
    override fun themeName() = "dark"
    override fun qualifiers() = "night"
}
```

- [ ] **1.3 Run them and watch them fail** for the same reason in both classes:

```bash
./gradlew --no-daemon --console=plain testDebugUnitTest --tests "com.taskplanner.ui.theme.*"
```

Expected: `NoSuchFieldException: ink_background` (or the Robolectric equivalent). That is the failure this
step wants — a missing token, not a typo. If it fails differently, fix the test before continuing.

- [ ] **1.4 Write the tokens.** Add the fifteen `ink_*` role tokens of §4 and the five pill tokens to
  `res/values/colors.xml`, and **edit the existing role names in place** — do not append a second block of
  them. `primary`, `primary_dark`, `secondary`, `secondary_dark`, `background`, `surface`, `on_surface`,
  `error`, `on_error`, `status_todo`, `status_in_progress`, `status_paused`, `status_done`, `priority_low`,
  `priority_medium` and `priority_high` are already declared at `colors.xml:4-25`, and aapt rejects a
  duplicate `<color>` before any test runs. Each line below therefore *replaces* the line of the same name,
  and that diff is the review surface.

  > Correction from review: an earlier draft said "append the alias block", which is a build break, and
  > predicted the wrong failure for it.

```xml
    <!-- Ink & Accent. Roles, not hues: a name says what a colour is for, so a screen can be read without
         knowing the palette. values-night/colors.xml carries the dark column; the pill backgrounds are
         solid rather than alpha tints because an alpha over four different surfaces composites to four
         different ratios, and only two of them were checked. -->
    <color name="ink_background">#FBF9F6</color>
    <color name="ink_surface">#FFFFFF</color>
    <color name="ink_surface_raised">#FFFDFB</color>
    <color name="ink_surface_variant">#F1EDE7</color>
    <color name="ink_text">#1A1614</color>
    <color name="ink_text_secondary">#6B635C</color>
    <color name="ink_text_tertiary">#6E655D</color>
    <color name="ink_outline">#E3DDD5</color>
    <color name="ink_outline_strong">#8C8378</color>
    <color name="ink_accent">#2F6F5E</color>
    <color name="ink_on_accent">#FFFFFF</color>
    <color name="ink_success">#2F7A4B</color>
    <color name="ink_warning">#8F5B18</color>
    <color name="ink_error">#C0402F</color>
    <color name="ink_on_error">#FFFFFF</color>
    <color name="pill_accent">#EEF3F2</color>
    <color name="pill_success">#EEF4F1</color>
    <color name="pill_warning">#F6F2ED</color>
    <color name="pill_error">#FAF0EE</color>
    <color name="pill_neutral">#F1EDE7</color>

    <!-- The names the app still carries. Each one points at a role, so the screens not yet migrated move
         with the system instead of against it, and a test that reads getColor(R.color.on_surface) keeps
         measuring the colour a user actually sees. Deleted screen by screen in stage 8. -->
    <color name="primary">@color/ink_accent</color>
    <color name="primary_dark">@color/ink_accent</color>
    <color name="secondary">@color/pill_accent</color>
    <color name="secondary_dark">@color/ink_accent</color>
    <color name="background">@color/ink_background</color>
    <color name="surface">@color/ink_surface</color>
    <color name="on_surface">@color/ink_text</color>
    <color name="error">@color/ink_error</color>
    <color name="on_error">@color/ink_on_error</color>
    <color name="status_todo">@color/ink_text_secondary</color>
    <color name="status_in_progress">@color/ink_accent</color>
    <color name="status_paused">@color/ink_warning</color>
    <color name="status_done">@color/ink_success</color>
    <color name="priority_high">@color/ink_error</color>
    <color name="priority_medium">@color/ink_warning</color>
    <color name="priority_low">@color/ink_text_tertiary</color>
```

Then `res/values-night/colors.xml`: the dark column of §4 under the same names. Delete `background_dark`,
`surface_dark` and `on_surface_dark` — the old night theme was their only reader, and 1.5 replaces that
file. Keep `state_badge_fill` (three drawables read it).

**The ramp does not move here, and one alias makes that a constraint rather than an intention.**
`colors.xml` defines `chart_bar` as `@color/primary`, `chart_completions` as `@color/status_done`,
`health_overdue` as `@color/error` and `health_in_progress` as `@color/status_in_progress`
(`colors.xml:46,51,62,69`). Retargeting those four aliases to ink roles repaints the dashboard from inside
stage 1, and **no test would fail**, because each chart test compares a view against the same id that
paints it — exactly the unattributable ramp move spec §3.1 exists to prevent. So 1.4 first gives the ramp
its own anchors and the aliases stop feeding it:

```xml
    <!-- The ramp is anchored on its own colours, not on the theme's roles, so retargeting `primary` for
         the UI cannot repaint a chart from inside the token stage. 8b moves these lines, together with the
         tests that read them, in one commit where the change is attributable. -->
    <color name="chart_base_accent">#FF6750A4</color>
    <color name="chart_base_finished">#FF4CAF50</color>
    <color name="chart_bar">@color/chart_base_accent</color>
    <color name="chart_completions">@color/chart_base_finished</color>
    <color name="health_overdue">#FFB3261E</color>
    <color name="health_in_progress">#FF2196F3</color>
```

Every value there is the value the file resolves to today; what changes is the indirection. `8b` is what
changes the colour.

> The chart and health ramps are deliberately untouched by this stage. `StatisticsLayoutTest` asserts an
> invariant over them rather than a value — the four heat levels share one RGB with strictly rising alpha,
> and `chart_heatmap_none` is distinct from `chart_heatmap_1` — so re-anchoring them here would break a test
> this stage has no business touching, and the pill rule from spec §3.1 (solid tokens, not alpha tints)
> says nothing about a heat scale whose entire meaning is one hue at five densities. Spec §3.1 revision 6
> puts the re-anchoring in the dashboard stage, and 8b is where it happens, with the tests that read it.

- [ ] **1.5 Map the theme.** Replace `res/values/themes.xml` with the ink roles on the M3 attributes, and
  write the night file to match. The M2-compat text attributes are overridden as well, so a layout that has
  not been migrated yet already reads the new scale:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources xmlns:tools="http://schemas.android.com/tools">
    <style name="Theme.TaskPlanner" parent="Theme.Material3.DayNight.NoActionBar">
        <item name="colorPrimary">@color/ink_accent</item>
        <item name="colorOnPrimary">@color/ink_on_accent</item>
        <item name="colorPrimaryContainer">@color/pill_accent</item>
        <item name="colorOnPrimaryContainer">@color/ink_accent</item>
        <item name="colorSecondary">@color/ink_accent</item>
        <item name="colorOnSecondary">@color/ink_on_accent</item>
        <item name="colorSecondaryContainer">@color/pill_accent</item>
        <item name="colorOnSecondaryContainer">@color/ink_accent</item>

        <item name="android:colorBackground">@color/ink_background</item>
        <item name="colorOnBackground">@color/ink_text</item>
        <item name="colorSurface">@color/ink_surface</item>
        <item name="colorOnSurface">@color/ink_text</item>
        <item name="colorSurfaceVariant">@color/ink_surface_variant</item>
        <item name="colorOnSurfaceVariant">@color/ink_text_secondary</item>
        <item name="colorSurfaceContainerLowest">@color/ink_surface</item>
        <item name="colorSurfaceContainerLow">@color/ink_surface_raised</item>
        <item name="colorSurfaceContainer">@color/ink_surface</item>
        <item name="colorSurfaceContainerHigh">@color/ink_surface_variant</item>
        <item name="colorSurfaceContainerHighest">@color/ink_surface_variant</item>

        <item name="colorOutline">@color/ink_outline</item>
        <item name="colorOutlineVariant">@color/ink_outline</item>
        <item name="colorError">@color/ink_error</item>
        <item name="colorOnError">@color/ink_on_error</item>

        <item name="android:statusBarColor">@color/ink_background</item>
        <item name="android:windowBackground">@color/ink_background</item>
        <item name="android:windowLightStatusBar">true</item>

        <!-- Twenty layouts still ask the platform for its secondary text colour, which is a ~3.9:1 grey
             and is not a token, so neither contrast gate can see it. Mapping it here is what stops stage
             8's grep from being the only thing between the audit and a failing pair. -->
        <item name="android:textColorSecondary">@color/ink_text_secondary</item>

        <!-- Not yet migrated layouts read these; overriding them here means the type scale lands once.
             The five legacy attributes StatisticsLayoutTest asks the theme for — Headline5, Subtitle1,
             Body1, Body2, Caption — are deliberately mapped to five DIFFERENT styles, because that test
             asserts the dashboard still has a hierarchy, and two of them sharing a style fails it. -->
        <item name="textAppearanceDisplaySmall">@style/TextAppearance.TaskPlanner.Display</item>
        <item name="textAppearanceHeadlineSmall">@style/TextAppearance.TaskPlanner.Headline</item>
        <item name="textAppearanceTitleLarge">@style/TextAppearance.TaskPlanner.Headline</item>
        <item name="textAppearanceTitleMedium">@style/TextAppearance.TaskPlanner.Title</item>
        <item name="textAppearanceBodyMedium">@style/TextAppearance.TaskPlanner.Body</item>
        <item name="textAppearanceBodySmall">@style/TextAppearance.TaskPlanner.Meta</item>
        <item name="textAppearanceLabelLarge">@style/TextAppearance.TaskPlanner.Label</item>
        <item name="textAppearanceLabelMedium">@style/TextAppearance.TaskPlanner.Label</item>
        <item name="textAppearanceHeadline5">@style/TextAppearance.TaskPlanner.Display</item>
        <item name="textAppearanceHeadline6">@style/TextAppearance.TaskPlanner.Headline</item>
        <item name="textAppearanceSubtitle1">@style/TextAppearance.TaskPlanner.Title</item>
        <item name="textAppearanceSubtitle2">@style/TextAppearance.TaskPlanner.Caption</item>
        <item name="textAppearanceBody1">@style/TextAppearance.TaskPlanner.Body</item>
        <item name="textAppearanceBody2">@style/TextAppearance.TaskPlanner.Meta</item>
        <item name="textAppearanceCaption">@style/TextAppearance.TaskPlanner.Caption</item>
        <item name="textAppearanceButton">@style/TextAppearance.TaskPlanner.Label</item>

        <item name="bottomNavigationStyle">@style/Widget.TaskPlanner.BottomBar</item>
        <item name="materialCardViewStyle">@style/Widget.TaskPlanner.Card</item>
        <item name="chipStyle">@style/Widget.TaskPlanner.Chip.Filter</item>
    </style>
</resources>
```

`res/values-night/themes.xml`: same attribute names, `android:statusBarColor` and `windowBackground` →
`@color/ink_background`, `windowLightStatusBar` → `false`.

> If any `textAppearance*` attribute above is not declared by Material 1.11.0, aapt fails with "not found"
> at 1.9's build and the fix is to delete that one line — not to invent a new attribute. Check the two
> M2-compat names first (`textAppearanceSubtitle2`, `textAppearanceButton`) since those are the least
> likely to exist in an M3 parent.

- [ ] **1.6 Type scale** in new `res/values/type.xml`: nine `TextAppearance.TaskPlanner.*` styles over the
  M3 parents — display, headline, title, body, meta, caption, pill, label, metric — each carrying size,
  `android:lineHeight` (with `tools:targetApi="28"`), weight, `android:letterSpacing` **in ems** at the
  values §3.3 of the spec now states, and colour through a role rather than a hex. Only `label` sets
  `android:textAllCaps`; `pill` is deliberately not capped, because the 82dp line-B budget in §4.1 depends
  on it. Add `type_caption` (12sp) and `type_pill` (11sp) to the dimens alongside the other seven.

- [ ] **1.6a Failure-first:** write `TypeScaleTest` before `type.xml`. It builds a `TextView` with each style and
  asserts `textSize` in sp and `letterSpacing`:

```kotlin
package com.taskplanner.ui.theme

import android.content.Context
import android.util.TypedValue
import android.view.ContextThemeWrapper
import org.junit.Assert.assertTrue
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.taskplanner.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The nine roles of spec 3.3, read back off the styles the layouts will actually use.
 *
 * Asserting the style rather than a dimension is the point: a `type_title` dimen of 16sp that no style
 * refers to would satisfy a dimens test and change nothing on screen.
 */
@RunWith(RobolectricTestRunner::class)
class TypeScaleTest {

    private val themed: Context =
        ContextThemeWrapper(ApplicationProvider.getApplicationContext(), R.style.Theme_TaskPlanner)

    /** TypedValue.px2sp is @hide in the public SDK; scaledDensity is the documented route. */
    private fun sizeSp(styleRes: Int): Float =
        TextView(themed, null, 0, styleRes).textSize / themed.resources.displayMetrics.scaledDensity

    @Test fun `each role is the size the scale says it is`() {
        assertEquals(28f, sizeSp(R.style.TextAppearance_TaskPlanner_Display), 1f)
        assertEquals(22f, sizeSp(R.style.TextAppearance_TaskPlanner_Headline), 1f)
        assertEquals(16f, sizeSp(R.style.TextAppearance_TaskPlanner_Title), 1f)
        assertEquals(15f, sizeSp(R.style.TextAppearance_TaskPlanner_Body), 1f)
        assertEquals(13f, sizeSp(R.style.TextAppearance_TaskPlanner_Meta), 1f)
        assertEquals(12f, sizeSp(R.style.TextAppearance_TaskPlanner_Caption), 1f)
        assertEquals(11f, sizeSp(R.style.TextAppearance_TaskPlanner_Pill), 1f)
        assertEquals(11f, sizeSp(R.style.TextAppearance_TaskPlanner_Label), 1f)
        assertEquals(20f, sizeSp(R.style.TextAppearance_TaskPlanner_Metric), 1f)
    }

    /**
     * Tracking is asserted in `android:letterSpacing` units, which are multiples of an em and not dp.
     * A label written as `0.4` instead of `0.045` is 40 % of the font size added between every glyph, and
     * the widths §4.1 budgets stop being true — which is the exact mistake this test exists to prevent.
     */
    @Test fun `tracking is expressed in ems, not in dp typed into the em slot`() {
        listOf(
            R.style.TextAppearance_TaskPlanner_Meta to 0.019f,
            R.style.TextAppearance_TaskPlanner_Caption to 0.021f,
            R.style.TextAppearance_TaskPlanner_Pill to 0.045f,
            R.style.TextAppearance_TaskPlanner_Label to 0.045f
        ).forEach { (style, expected) ->
            val actual = TextView(themed, null, 0, style).letterSpacing
            assertEquals(
                themed.resources.getResourceEntryName(style), expected, actual, 0.0015f
            )
        }
    }

    /** The theme is what hands the roles to the attributes the not-yet-migrated layouts already use. */
    @Test fun `the five legacy attributes the dashboard asks for resolve to five different styles`() {
        val asked = listOf(
            com.google.android.material.R.attr.textAppearanceHeadline5,
            com.google.android.material.R.attr.textAppearanceSubtitle1,
            com.google.android.material.R.attr.textAppearanceBody1,
            com.google.android.material.R.attr.textAppearanceBody2,
            com.google.android.material.R.attr.textAppearanceCaption
        )
        val styles = asked.map { attr ->
            val value = TypedValue()
            assertTrue(
                "the theme answers ?attr/${themed.resources.getResourceName(attr)}",
                themed.theme.resolveAttribute(attr, value, true)
            )
            value.resourceId
        }
        assertEquals("five roles, five styles, or the dashboard has no hierarchy", 5, styles.toSet().size)
    }

    @Test fun `a layout asking for the old body2 attribute gets the meta role`() {
        val tv = TextView(themed, null, 0, com.google.android.material.R.attr.textAppearanceBody2)
        assertEquals(sizeSp(R.style.TextAppearance_TaskPlanner_Meta), sizeSpOf(tv), 1f)
    }

    private fun sizeSpOf(tv: TextView): Float =
        tv.textSize / themed.resources.displayMetrics.scaledDensity
}
```

> `scaledDensity` is what lets the assertion hold at any font scale the host runs at. The ±1f tolerance an
> earlier draft used is too wide for this scale: caption is 12sp and pill and label are 11sp, so a ±1
> window lets one role satisfy another and the test passes on a screen with no hierarchy at all. Use
> **±0.2f**. And because `letterSpacing` is read through `defStyleRes`, `type.xml` must set
> `android:letterSpacing` on the style itself rather than relying on a Material parent to carry it.

- [ ] **1.7 Space and shape.** Add to `res/values/dimens.xml` (keeping the five existing `spacing_*` values
  exactly as they are, because `StatisticsLayoutTest` asserts that every vertical gap on the dashboard is
  one of them):

```xml
    <!-- The scale spec 3.4 defines. spacing_xs/small/normal/large/xl already exist at 4/8/16/24/32;
         these two close the gaps at 12 and 20 so no layout needs a literal again. -->
    <dimen name="spacing_medium">12dp</dimen>
    <dimen name="spacing_section">20dp</dimen>
    <dimen name="screen_margin">20dp</dimen>
    <dimen name="card_padding_vertical">14dp</dimen>
    <dimen name="card_padding_horizontal">16dp</dimen>
    <dimen name="card_gap">8dp</dimen>
    <dimen name="radius_card">14dp</dimen>
    <dimen name="radius_control">12dp</dimen>
    <dimen name="radius_sheet">20dp</dimen>
    <dimen name="radius_pill">100dp</dimen>
    <dimen name="row_min_height">56dp</dimen>
    <dimen name="touch_target_min">48dp</dimen>
    <dimen name="priority_rule_width">4dp</dimen>
    <dimen name="icon_container">40dp</dimen>
    <dimen name="avatar_header">64dp</dimen>
    <dimen name="fab_size">56dp</dimen>
    <!-- Derived, and pinned by a test, so the list's last row and the FAB cannot drift apart. -->
    <dimen name="list_bottom_gutter">88dp</dimen>
    <dimen name="type_display">28sp</dimen>
    <dimen name="type_headline">22sp</dimen>
    <dimen name="type_title">16sp</dimen>
    <dimen name="type_body">15sp</dimen>
    <dimen name="type_meta">13sp</dimen>
    <dimen name="type_caption">12sp</dimen>
    <dimen name="type_pill">11sp</dimen>
    <dimen name="type_label">11sp</dimen>
    <dimen name="type_metric">20sp</dimen>
```

Delete `task_item_height` and `category_item_height` (no reader). **Do not delete the five `text_*`
sizes here** — `fragment_report.xml:244` reads `@dimen/text_caption`, and that layout belongs to 8d; the
retirement of `text_*` is step 8d.4.

Add the test that keeps `list_bottom_gutter` and the FAB from drifting apart, since the comment above it
promises one:

```kotlin
package com.taskplanner.ui.theme

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.taskplanner.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The list's bottom padding and the FAB that floats over it are one fact, not two numbers.
 *
 * The last row has to clear the button without the list gaining a scroll of dead space, and the 88dp that
 * does it today is a literal nobody re-checks when the FAB moves. This asserts the sum, so changing either
 * dimen without the other fails here rather than turning up in a screenshot three stages later.
 */
@RunWith(RobolectricTestRunner::class)
class ListGutterTest {

    private lateinit var context: Context

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    /** getDimensionPixelSize returns pixels, so the dp floor has to be converted the same way. */
    private fun pxOf(dp: Int) = (dp * context.resources.displayMetrics.density).toInt()

    private fun dimen(id: Int) = context.resources.getDimensionPixelSize(id)

    @Test fun `the list's bottom gutter is the fab plus its two margins`() {
        assertEquals(
            "fab_size + 2 * fab_margin == list_bottom_gutter",
            dimen(R.dimen.fab_size) + 2 * dimen(R.dimen.fab_margin),
            dimen(R.dimen.list_bottom_gutter)
        )
    }

    @Test fun `the touch-target scale is at least forty-eight dp`() {
        assertTrue("touch_target_min is ${dimen(R.dimen.touch_target_min)}px",
            dimen(R.dimen.touch_target_min) >= pxOf(48))
        assertTrue("row_min_height is ${dimen(R.dimen.row_min_height)}px",
            dimen(R.dimen.row_min_height) >= pxOf(48))
    }
}
```

- [ ] **1.7a Make the gutter test pin the UI, not two constants.** `fragment_tasks.xml:153` hard-codes
  `android:paddingBottom="88dp"`, and `fab_margin` is read by nothing in `app/src`, so a test comparing
  the two dimens proves arithmetic about a file nobody renders. Point the list and the FAB at the dimens,
  then let the grep be the gate:

```bash
grep -n 'paddingBottom="88dp"' app/src/main/res/layout/fragment_tasks.xml && echo 'STILL A LITERAL' || echo coupled
```

- [ ] **1.7b Statistics allowlist.** `StatisticsLayoutTest` asserts that every vertical gap on the dashboard
  is one of the named `spacing_*` values, and its allowlist is built from five ids. Adding `spacing_medium`
  and `spacing_section` does not break it while no dashboard layout uses them; the moment 8b uses one, that
  test's set must gain the same id **in the same commit**. Note it here so 8b does not discover it as a
  surprise failure.

- [ ] **1.8 Component styles** in new `res/values/component_styles.xml`: the fourteen `Widget.TaskPlanner.*`
  styles spec §3.5 lists, plus `Widget.TaskPlanner.Field.Dropdown` — the fifteenth, needed because five uses of
  `Widget.MaterialComponents.TextInputLayout.OutlinedBox.ExposedDropdownMenu` across two layouts and the spec's
  list names only the plain field. Then the pill styles over the five `bg_pill_*` drawables. And the new
  drawables listed in §8 — each a `<shape>` or `<ripple>` reading a token, no literals.

- [ ] **1.9 Layout includes** — six of them, matching spec §3.5's list: `header_screen.xml`,
  `row_list.xml`, `state_empty.xml`, `state_loading.xml`, `state_error.xml`, `bar_selection.xml`. The
  seventh include the spec names, `.GroupHeader`, is not new: `item_group_header.xml` already exists and is
  restyled in place at 4.7 rather than replaced by an include, because `TasksAdapter.HeaderViewHolder` binds
  its four ids (`groupChevron`, `groupName`, `groupCount`, `groupMenu`) and rewriting that binding for a
  layout change would be churn with no user visible behind it.

  `row_list.xml` is the Profile row and is included **eight** times (Account 2, Your work 4, Data 1, Danger
  zone 1), so its children carry ids and each include gets its own `android:id` — ViewBinding scopes an
  include's binding to that include's own view tree, so the repeated child ids cannot collide.

- [ ] **1.10 Write `ComponentStyleTest` and `IncludeBindingTest`** — inflate every style and every include
  under `Theme.TaskPlanner` in both qualifiers, and assert the include's children are reachable through the
  nested binding (`binding.row.root.findViewById(R.id.rowTitle) != null`), which is what proves the eight
  Profile rows will not fight over ids.

- [ ] **1.11 Run the gates.**

```bash
./gradlew --no-daemon --console=plain testDebugUnitTest
./gradlew --no-daemon --console=plain assembleDebug
python scripts/contrast_report.py --resources app/src/main/res --check --format markdown --out docs/design/contrast-report.md
```

Expected: 1371 + the new tests, 0 failures; build succeeds; the script prints `RESULT: pass` with no drift
lines. A drift line means a resource and the table disagree — fix the resource, never the table, unless the
design changed, in which case both move together and the spec's §3.1 quotation is regenerated.

- [ ] **1.12 Visual check that the theme landed.** Install, screenshot Tasks and Profile in light and dark.
  Nothing here has moved a view, but every colour has changed, so the pair is the evidence:

```bash
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 exec-out screencap -p > docs/images/ui-redesign/after/s1-tasks-light.png
adb -s emulator-5554 shell cmd uimode night yes
adb -s emulator-5554 exec-out screencap -p > docs/images/ui-redesign/after/s1-tasks-dark.png
adb -s emulator-5554 shell cmd uimode night no
```

Judge them with `design-review:design-debt-review` in mind: any screen that now looks *worse* than the
baseline (a colour that landed on the wrong surface, a hairline that vanished in dark) is fixed here, not
deferred — a broken token layer makes every later stage's screenshots untrustworthy.

- [ ] **1.13 Commit.**

```bash
git add -A
git commit -m "stage 1: the design system the app did not have

Fifteen role tokens and five pill backgrounds in both themes, a nine-role type scale, one spacing scale,
fifteen component styles and six reusable includes. The names the app already used now point at roles
instead of hues, so a screen not yet migrated moves with the theme rather than against it.

Contrast stops being prose: ContrastLightTest and ContrastDarkTest assert every pair spec 3.2 promises
against the shipped resources, and scripts/contrast_report.py measures the same pairs out of
docs/design/tokens.json and reports any resource that drifted from the table. The chart and health ramps
are deliberately left alone here — they move with the dashboard, whose test asserts an invariant over them."
```

**Acceptance:** the two contrast test classes pass in both themes; `--resources --check` prints
`RESULT: pass` with zero drift lines; no layout changed except the six new includes; the theme diff is

> One honest limit on what stage 1 achieves on screen. Forty-five views across seven layouts carry an
> explicit `style="@style/Widget.MaterialComponents.…"`, and an explicit style outranks a theme default,
> so `chipStyle`, `materialCardViewStyle` and the button defaults set in 1.5 change **nothing** for those
> views until stage 8 rewrites the attributes. The one override that does land immediately is
> `bottomNavigationStyle`, because `activity_main.xml:70` sets no style of its own. Stage 1's screenshots
> therefore show recolouring and re-typography, not restyled widgets, and a reviewer should read them as
> that rather than as evidence the component layer works.
tokens and attributes only. **Screenshots:** `s1-tasks-light.png`, `s1-tasks-dark.png`,
`s1-profile-light.png`, `s1-profile-dark.png`.

---

## Stage 2 — Tasks foundation: the swipe panel geometry, fixed at root

Purpose: kill the strip that shows on every closed row, and prove it with a measurement. Depends on: S1
(the test reads `ink_*`). Precondition: S1 committed and green.

This stage changes geometry on the **current** card on purpose: if the fix and the redesign landed in one
commit, a passing test could not say which of them removed the strip.

- [ ] **2.1 Write the failing test** — `app/src/test/java/com/taskplanner/ui/tasks/TaskCardClosedPanelTest.kt`,
  following `TaskCardWorkTest`'s inflate-measure-layout pattern:

```kotlin
package com.taskplanner.ui.tasks

/**
 * The panel is behind the card, and the user is never meant to see it until the card moves.
 *
 * It used to be visible on every row without any interaction: `actionPanel` sat at `gravity="end"` with
 * no margins while the card above it carried 8dp horizontally and 4dp vertically, so a blue/red band
 * showed along the top of the list and a red column down the right side. The audit photographs show it on
 * all nine rows.
 *
 * This measures the two rectangles on a laid-out row and requires the panel's to be inside the card's
 * while closed. It says nothing about the reveal, which the next test covers, and nothing about the card's
 * contents, which stage 4 rewrites.
 */
@RunWith(RobolectricTestRunner::class)
class TaskCardClosedPanelTest {

    private lateinit var context: Context
    private lateinit var parent: ViewGroup

    @Before fun setUp() {
        context = android.view.ContextThemeWrapper(
            ApplicationProvider.getApplicationContext(), R.style.Theme_TaskPlanner
        )
        parent = FrameLayout(context)
    }

    private fun Int.dp() = (this * context.resources.displayMetrics.density).toInt()

    private fun row(): View {
        val adapter = TasksAdapter(
            onTaskClick = {}, onLongPressTask = {}, onToggleSelection = {}, onEdit = {},
            onDeleteRequest = {}, onStart = {}, onPause = {}, onResume = {}, onComplete = {},
            onWorkToggle = {}, onToggleGroup = {}, onGroupMenu = { _, _ -> }, onStartDrag = {}
        )
        adapter.submitList(
            listOf(
                TasksListItem.TaskRow(
                    Task(
                        id = 1, uuid = "row-geometry", title = "Write the plan",
                        description = "", userId = 1, status = TaskStatus.TODO
                    ),
                    category = null, isSelected = false
                )
            )
        )
        awaitUntil("the row to be diffed") { adapter.itemCount == 1 }
        val holder = adapter.createViewHolder(parent, adapter.getItemViewType(0))
        adapter.bindViewHolder(holder, 0)
        return holder.itemView.also {
            it.measure(
                View.MeasureSpec.makeMeasureSpec(393.dp(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            it.layout(0, 0, 393.dp(), it.measuredHeight)
        }
    }

    private fun awaitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("timed out waiting for $what")
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    @Test fun `a closed row shows no part of the action panel`() {
        val row = row()
        val card = row.findViewById<View>(R.id.taskCard)
        val panel = row.findViewById<View>(R.id.actionPanel)

        assertEquals("the card does not move while nothing is swiping", 0f, card.translationX, 0f)
        assertTrue(
            "panel right ${panel.right} must not pass card right ${card.right}",
            panel.right <= card.right
        )
        assertTrue("panel left ${panel.left} must hide behind card left ${card.left}", panel.left >= card.left)
        assertTrue("panel top ${panel.top} must hide behind card top ${card.top}", panel.top >= card.top)
        assertTrue(
            "panel bottom ${panel.bottom} must hide behind card bottom ${card.bottom}",
            panel.bottom <= card.bottom
        )
    }

    @Test fun `the reveal distance is still the panel's own width`() {
        val row = row()
        val card = row.findViewById<View>(R.id.taskCard)
        val panel = row.findViewById<View>(R.id.actionPanel)
        card.translationX = -panel.width.toFloat()
        assertTrue(
            "sliding by panel.width brings the panel's left edge to where the card's was",
            panel.left <= card.right - panel.width
        )
        assertTrue("and the panel still does not reach past the row", panel.right <= row.width)
    }
}
```

> The thirteen-callback constructor, the `Task(...)` shape and the `awaitUntil` helper are taken from
> `TaskCardWorkTest`, which already drives the real adapter this way — including `ListAdapter`'s async diff,
> which is why the wait loop idles the main looper instead of asserting straight after `submitList`.

- [ ] **2.2 Run it, watch it fail** on the first three assertions with the actual numbers from the current
  layout (the panel's right edge will be 8dp past the card's, its top 4dp above):

```bash
./gradlew --no-daemon --console=plain testDebugUnitTest --tests "com.taskplanner.ui.tasks.TaskCardClosedPanelTest"
```

- [ ] **2.3 Fix the layout.** In `res/layout/item_task.xml`, `actionPanel` gains
  `android:layout_marginEnd="@dimen/card_gap"`, `android:layout_marginTop="@dimen/spacing_xs"` and
  `android:layout_marginBottom="@dimen/spacing_xs"`, matching the card's insets, and its background becomes
  `@drawable/bg_row_backdrop` (surface, radius 14)

  > Correction from review: an earlier draft used `android:layout_marginVertical`, which is API 26 on an
  > API 24 floor. On 24 and 25 it is dropped silently, so the strip returns on exactly the devices the fix
  > is for — and `TaskCardClosedPanelTest` runs at SDK 34, so it cannot see that, and the lint baseline
  > holds no `UnusedAttribute` entry to catch it either. Write both margins out, and do the same for the
  > `layout_marginHorizontal` the card already carries at `item_task.xml:41` while you are in the file.
  instead of the two full-bleed colour drawables. `editTaskButton` and `deleteTaskButton` lose
  `bg_action_edit` / `bg_action_delete` and gain a transparent background with `app:tint` —
  `ink_accent` for edit, `ink_error` for delete — so the panel is a surface-coloured panel with coloured
  icons, per spec §4.1.

> `marginEnd`, not `marginStart`: for a `gravity="end"` child it is `marginEnd` that moves the right edge.
> `TasksAdapter.handleTouch` measures the reveal from `binding.actionPanel.width`, and margins do not
> change `width`, so the reveal distance is untouched — which is what 2.1's second test pins.

- [ ] **2.4 Run the whole Tasks test group**, then the full suite, then build:

```bash
./gradlew --no-daemon --console=plain testDebugUnitTest --tests "com.taskplanner.ui.tasks.*"
./gradlew --no-daemon --console=plain testDebugUnitTest
```

Expected: all green, including `TaskCardWorkTest` and `TaskCardReminderTest` unchanged. If either breaks,
the layout change reached further than the panel — fix the change, not the test.

- [ ] **2.5 Prove it on the device**, including that swiping still works and that manual reorder still
  works, with photographs of each state (the popup-free way: `adb shell input swipe` and `screencap`):

```bash
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 shell am start -n com.taskplanner/.ui.MainActivity
adb -s emulator-5554 exec-out screencap -p > docs/images/ui-redesign/after/s2-tasks-closed.png
adb -s emulator-5554 shell input swipe 700 1200 200 1200 220
adb -s emulator-5554 exec-out screencap -p > docs/images/ui-redesign/after/s2-tasks-swiped.png
```

Then tap edit and delete once each, and drag the handle once, and photograph the results. Record what the
tap did — a snackbar, a navigation, a dialog — in the commit message. `uiautomator dump` cannot see this
emulator's popup windows, so the photographs are the evidence, the same way PR #21 handled it.

- [ ] **2.6 Commit.** `git commit -m "stage 2: the swipe panel no longer shows on a closed row, and a test pins the geometry"`

**Acceptance:** `TaskCardClosedPanelTest` passes and failed before the change; `TaskCardWorkTest`,
`TaskCardReminderTest`, `TaskDetailLayoutTest` untouched and green; swipe reveal, edit, delete and manual
reorder each photographed working; the red column is gone from `s2-tasks-closed.png`.
**Screenshots:** `s2-tasks-closed.png`, `s2-tasks-swiped.png`, `s2-edit-open.png`, `s2-reorder.png`.

---

## Stage 3 — Tasks header: the four stranded actions

Purpose: give Tasks a header that exists, so search, sort, new group, archive and work history become
reachable. Depends on: S2. Tools: `frontend-design`, `android-jetpack`.

- [ ] **3.1 Write the failing tests first.** Two of them:

`app/src/test/java/com/taskplanner/ui/tasks/TasksHeaderLayoutTest.kt` — inflates `fragment_tasks.xml` under
the app theme and asserts the header's title, subtitle, search field and three action buttons exist, that
every icon-only control has a non-empty `contentDescription`, and that the search field is a real
`TextInputEditText` (or `EditText`) rather than a decorative box:

```kotlin
@Test fun `the header offers the actions the screen has`() {
    val header = view.findViewById<ViewGroup>(R.id.tasksHeader)
    listOf(
        R.id.headerTitle, R.id.headerSubtitle, R.id.headerSearchField,
        R.id.headerSearchButton, R.id.headerSortButton, R.id.headerMenuButton
    ).forEach {
        assertNotNull("${context.resources.getResourceEntryName(it)} is on the header", header.findViewById<View>(it))
    }
}
```

`app/src/androidTest/java/com/taskplanner/ui/tasks/TasksHeaderInstrumentedTest.kt` — `ActivityScenario`,
following the PR #21 pattern (seed the profile into the app's own storage, no Hilt harness exists):
typing into the header field narrows the list, and the header's sort button changes the order. Both assert
the *effect*, because the popup windows the kebab and sort submenu open are invisible to Espresso's root
matchers on this device:

```kotlin
@Test fun `typing in the header field filters the list through the view model`() {
    scenario.onActivity { activity ->
        val host = activity.supportFragmentManager.fragments.first() as NavHostFragment
        // The Tasks fragment is the start destination; reach its binding through its view.
        val root = host.childFragmentManager.fragments.first { it is TasksFragment }.requireView()
        val field = root.findViewById<EditText>(R.id.headerSearchField)
        field.visibility = View.VISIBLE
        field.setText("обзор")
        onView(withId(R.id.recyclerView)).check(matches(isDisplayed()))
    }
    // then assert the row count dropped to the one seeded task whose title contains it
}
```

- [ ] **3.2 Run them, watch them fail** (`findViewById` returns null for every new id).

- [ ] **3.3 Add the strings** to `res/values/strings.xml` — `values/` is the only locale folder in this
  repository, so each needs one entry: `tasks_header_title` (reuse the existing `app_name`/`title_tasks`
  if it already reads "Tasks"), `tasks_open_count_format` (`"%1$d open"`), `header_search_hint`,
  `header_sort`, `header_more_actions`, `filters_count_format` (`"Filters · %1$d"`),
  `filters_clear_all`.

- [ ] **3.4 Add the header to `fragment_tasks.xml`**, above the filter rail, using `@layout/header_screen`
  with an `android:id="@+id/tasksHeader"`, and wire it in `TasksFragment`:

```kotlin
    /**
     * The header is the screen's only chrome, and it is the reason four features work.
     *
     * `menu_tasks.xml` has declared search, sort, new group, archive and work history since stage 1, and
     * `addMenuProvider` has wired all five, but the app runs a NoActionBar theme with no Toolbar in any
     * layout — the menu was never drawn, so `setSearchQuery`, `setSortOrder`, `actionTasksToArchive` and
     * `actionTasksToWorkHistory` each had exactly one caller behind chrome that did not exist. Archive had
     * no other entry point in the app at all.
     */
    private fun setupHeader() {
        binding.headerSearchButton.setOnClickListener {
            binding.tasksHeader.headerSearchField.visibility = View.VISIBLE
            binding.tasksHeader.headerSearchField.requestFocus()
        }
        // An <include> with an id yields a nested binding, not flattened fields: the header's children
        // are reached through binding.tasksHeader, and binding.headerSearchField does not exist.
        binding.tasksHeader.headerSearchField.doAfterTextChanged { text ->
            viewModel.setSearchQuery(text.toString())
        }
        binding.tasksHeader.headerSortButton.setOnClickListener { anchor -> showSortMenu(anchor) }
        binding.tasksHeader.headerMenuButton.setOnClickListener { anchor -> showOverflowMenu(anchor) }
    }

    private fun showOverflowMenu(anchor: View) = PopupMenu(requireContext(), anchor).apply {
        menuInflater.inflate(R.menu.menu_tasks_overflow, menu)
        setOnMenuItemClickListener { item -> when (item.itemId) {
            R.id.action_new_group -> { promptCreateGroup(); true }
            R.id.action_archive -> {
                findNavController().navigate(TasksFragmentDirections.actionTasksToArchive()); true
            }
            R.id.action_work_history -> {
                findNavController().navigate(TasksFragmentDirections.actionTasksToWorkHistory()); true
            }
            else -> false
        } }
    }.show()

    private fun showSortMenu(anchor: View) = PopupMenu(requireContext(), anchor).apply {
        menuInflater.inflate(R.menu.menu_tasks_sort, menu)
        setOnMenuItemClickListener { item -> viewModel.setSortOrder(
            when (item.itemId) {
                R.id.action_sort_manual -> SortOrder.MANUAL
                R.id.action_sort_by_date -> SortOrder.DATE_DESC
                R.id.action_sort_by_priority -> SortOrder.PRIORITY_DESC
                R.id.action_sort_by_name -> SortOrder.NAME_ASC
                else -> return@setOnMenuItemClickListener false
            }); true
        }
    }.show()
```

Split `menu_tasks.xml` into `menu_tasks_sort.xml` (the four entries) and `menu_tasks_overflow.xml` (new
group, archive, work history), delete the `SearchView` item, and **remove `setupMenu()`** — leaving the
`MenuProvider` in place would keep two paths to the same actions, one of them invisible.

- [ ] **3.5 Bind the title and count** in `setupObservers()`: `binding.headerTitle.setText(R.string.title_tasks)`
  and `binding.tasksHeader.headerSubtitle.text =
  getString(R.string.tasks_open_count_format, state.activeTaskCount)`.
  `activeTaskCount` already exists on `TasksState` and is shown nowhere today.

- [ ] **3.6 Rebuild the filter rail** (spec §4.1). Today the rail is two blocks: a `HorizontalScrollView`
  holding `chipGroupCategory`, and a non-scrolling `chipGroupStatus` below it with `Widget.MaterialComponents.Chip.Filter`
  styles — which is why the status row wraps and steals list height at 320dp. Change it to one
  `HorizontalScrollView` containing, in order: the category chips, a 1dp `ink_outline` hairline divider, the
  **three** status chips (`chipTodo`, `chipInProgress`, `chipPaused` — ids and behaviour unchanged; no
  fourth chip, because `completeAndArchive` means a Completed filter could never match a row), and finally
  the `Filters · n` chip. Write its test first:

```kotlin
    @Test fun `the rail is one scrolling row with three status chips and no fourth`() {
        val rail = view.findViewById<HorizontalScrollView>(R.id.filterRail)
        assertNotNull("one scrolling rail, not a scrolling row above a wrapping one", rail)
        assertEquals(
            "the app has four statuses and the active list can hold three of them",
            listOf(R.id.chipTodo, R.id.chipInProgress, R.id.chipPaused),
            view.findViewById<ChipGroup>(R.id.chipGroupStatus).let { group ->
                (0 until group.childCount).map { group.getChildAt(it).id }
            }
        )
    }

    @Test fun `an active filter offers one control that clears all of them`() {
        val viewModel = /* the fragment's view model, driven directly as TasksScreenInstrumentedTest does */
        viewModel.setSearchQuery("x")
        assertEquals(1, activeFilterCount(TasksState(filterStatus = null, searchQuery = "x")))
        // and the chip's click reaches clearFilters(), which already exists
    }
```

  `Filters · n` counts what `TasksState` already knows: `hasActiveFilters` is the boolean, and the number is
  `listOfNotNull(filterCategoryId, filterStatus, searchQuery.ifBlank { null }).size`. It is computed in the
  fragment from the state it is given — no new view-model field, because the three facts are already there.

- [ ] **3.7 Selection mode uses the include.** `fragment_tasks.xml` carries a `selectionBar`
  `MaterialCardView` with its own four ids inline. Replace the card's body with the include and give the
  **include** the id `selectionBar`, dropping it from the `MaterialCardView` — an id declared twice in one
  tree is ambiguous, and ViewBinding resolves the include's id over the included root's own, so the card
  keeps no reachable id at all. `selectionCount`, `selectAllButton`, `deleteSelectedButton` and
  `cancelSelectionButton` are then reached as `binding.selectionBar.selectionCount` and so on, which is a
  real change to every call site in the fragment and must be listed as such. Spec §4.1 says selection mode replaces the header; implement that as the header
  going `GONE` and the bar going `VISIBLE` in the same `when (state.selectionMode)` branch that exists now,
  and assert both in `TasksHeaderLayoutTest`:

```kotlin
    @Test fun `selection mode hides the header and shows the bar, not both`() {
        // given selectionMode true, the header is not visible and the bar is, and the reverse
    }
```

- [ ] **3.8 Run the gates**, then install and photograph: default, search open with a query typed, sort
  menu pressed, kebab pressed, archive reached, work history reached, the rail at 320dp, selection mode.
  Eight screenshots, and each of the first six is the proof that a feature is now reachable that was not at
  the baseline.

- [ ] **3.9 Commit**, naming in the message that this is the reachability fix, not a styling change.

**Acceptance:** search filters the list through `setSearchQuery`; sort changes the order through
`setSortOrder`; the kebab reaches Archive and Work history and both screens are photographed; the count in
the subtitle equals the number of visible uncompleted rows; the rail is one scrolling row at 320dp with
three status chips; `Filters · n` appears with any filter active and clears all three in one press;
selection mode shows the bar and not the header; `TasksScreenInstrumentedTest` still green.
**Screenshots:** `s3-header.png`, `s3-search.png`, `s3-sort.png`, `s3-kebab.png`, `s3-archive.png`,
`s3-work-history.png`, `s3-rail-320.png`, `s3-selection.png`.

---

## Stage 4 — Tasks card: composition, presentation computation, the 320dp budget

Purpose: the card itself. Depends on: S3 (the header sets the sort order the card's drop rules read).
Tools: `frontend-design`, `android-jetpack`, `design-review:accessibility-review`,
`design-review:responsive-design`.

- [ ] **4.1 Write the presentation model and its tests first.** New
  `app/src/main/java/com/taskplanner/ui/tasks/CardPresentation.kt`. It is pure display code: it reads what
  is already on the row, writes nothing, and takes `now` as a parameter (spec §0).

```kotlin
package com.taskplanner.ui.tasks

import android.content.Context
import com.taskplanner.R
import com.taskplanner.data.model.Priority
import com.taskplanner.data.model.TaskStatus
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * What a card says about a task, decided from the row's own data and nothing else.
 *
 * This is the whole of the display layer's arithmetic, and it is deliberately separate from the adapter
 * so the boundary the spec draws can be tested: every field here is derived from a field the row already
 * carries, nothing is written anywhere, and `now` arrives from outside rather than from the device clock.
 *
 * The overdue predicate is the one the dashboard already owns — `TaskStatisticsDao` counts the health
 * panel's OVERDUE bucket as `due_date IS NOT NULL AND due_date < :now` — and it is not restated
 * differently here on purpose: a task the dashboard calls late cannot be a task the list calls on time.
 */
data class CardPresentation(
    val dueLine: CharSequence,
    val isOverdue: Boolean,
    val statusLabelRes: Int,
    val pillBackgroundRes: Int,
    val pillTextRes: Int,
    /** null means no rule is drawn at all, which is how LOW reads: not urgent, not blank. */
    val priorityRuleColorRes: Int?,
    val showStart: Boolean,
    val showPause: Boolean,
    val showResume: Boolean,
    val showComplete: Boolean
) {
    companion object {
        private val dayFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
        private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

        /** The category name is drawn only when it has this much room; below it the dot stands alone. */
        const val CATEGORY_NAME_MIN_DP = 48

        fun of(context: Context, row: TasksListItem.TaskRow, now: Long): CardPresentation {
            val task = row.task
            val overdue = task.dueDate != null && task.dueDate < now &&
                task.status != TaskStatus.COMPLETED
            return CardPresentation(
                dueLine = dueLine(context, task.dueDate, now, overdue),
                isOverdue = overdue,
                statusLabelRes = when (task.status) {
                    TaskStatus.TODO -> R.string.status_todo
                    TaskStatus.IN_PROGRESS -> R.string.status_in_progress
                    TaskStatus.PAUSED -> R.string.status_paused
                    TaskStatus.COMPLETED -> R.string.status_done
                },
                pillBackgroundRes = when (task.status) {
                    TaskStatus.TODO -> R.drawable.bg_pill_neutral
                    TaskStatus.IN_PROGRESS -> R.drawable.bg_pill_accent
                    TaskStatus.PAUSED -> R.drawable.bg_pill_warning
                    TaskStatus.COMPLETED -> R.drawable.bg_pill_success
                },
                pillTextRes = when (task.status) {
                    TaskStatus.TODO -> R.color.ink_text_secondary
                    TaskStatus.IN_PROGRESS -> R.color.ink_accent
                    TaskStatus.PAUSED -> R.color.ink_warning
                    TaskStatus.COMPLETED -> R.color.ink_success
                },
                priorityRuleColorRes = when (task.priority) {
                    Priority.HIGH -> R.color.ink_error
                    Priority.MEDIUM -> R.color.ink_warning
                    Priority.LOW -> null
                },
                showStart = task.status == TaskStatus.TODO,
                showPause = task.status == TaskStatus.IN_PROGRESS,
                showResume = task.status == TaskStatus.PAUSED,
                showComplete = task.status != TaskStatus.COMPLETED
            )
        }

        private fun dueLine(context: Context, dueDate: Long?, now: Long, overdue: Boolean): CharSequence {
            if (dueDate == null) return context.getString(R.string.no_due_date)
            val days = daysBetween(now, dueDate)
            val time = timeFormat.format(java.util.Date(dueDate))
            return when {
                overdue -> context.resources.getQuantityString(
                    R.plurals.task_late_days, kotlin.math.abs(days).toInt(), kotlin.math.abs(days).toInt()
                )
                days == 0L -> context.getString(R.string.task_due_today_format, time)
                days == 1L -> context.getString(R.string.task_due_tomorrow_format, time)
                else -> dayFormat.format(java.util.Date(dueDate))
            }
        }

        /**
         * Whole days between two instants, counted as calendar days rather than as 24-hour blocks.
         *
         * A DST transition makes one local day 23 or 25 hours long, and dividing a raw millisecond gap
         * by 86_400_000 would then call "tomorrow" either today or the day after, twice a year. Both
         * instants are reduced to local midnight and the quotient is rounded, so the short day and the
         * long one both land on one.
         */
        private fun daysBetween(from: Long, to: Long): Long =
            Math.round((startOfDay(to) - startOfDay(from)) / MILLIS_PER_DAY.toDouble())

        private const val MILLIS_PER_DAY = 86_400_000L

        private fun startOfDay(millis: Long): Long = Calendar.getInstance().apply {
            timeInMillis = millis
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}
```

> `bg_pill_neutral` is the fifth pill drawable and the one a `TODO` row uses. Its two colours are already in
> `docs/design/tokens.json` as `pill_neutral` and already measured — `ink_text_secondary` on it is 5.05
> light and 5.71 dark — so 1.8 writes the drawable and nothing is invented at bind time. The rule behind
> that ordering is the one this stage exists to enforce: a token is measured before a layout is allowed to
> reach for it.

`CardPresentationTest` — the clock is held still, which is the whole reason `now` is a parameter:

```kotlin
    private val midnight = /* 2026-10-23 00:00 local, computed in the test */
    @Test fun `a task one minute past its deadline is late, and one before it is not`() {
        assertFalse(present(due = midnight + 60_000, now = midnight).isOverdue)
        assertTrue(present(due = midnight - 60_000, now = midnight).isOverdue)
    }

    @Test fun `LOW draws no rule, so absence carries the meaning`() {
        assertNull(present(priority = Priority.LOW).priorityRuleColorRes)
        assertEquals(R.color.ink_error, present(priority = Priority.HIGH).priorityRuleColorRes)
        assertEquals(R.color.ink_warning, present(priority = Priority.MEDIUM).priorityRuleColorRes)
    }

    @Test fun `only the lifecycle button the status calls for is shown`() {
        TaskStatus.values().filter { it != TaskStatus.COMPLETED }.forEach { status ->
            val p = present(status = status)
            assertEquals(
                "$status offers exactly one of start / pause / resume",
                1, listOf(p.showStart, p.showPause, p.showResume).count { it }
            )
            assertTrue("a row the list still holds offers to be finished", p.showComplete)
        }
        val done = present(status = TaskStatus.COMPLETED)
        assertFalse(done.showComplete)
        assertFalse(listOf(done.showStart, done.showPause, done.showResume).any { it })
    }

    @Test fun `the due line never says a date the row does not carry`() {
        assertEquals("No due date", present(due = null).dueLine)
        assertTrue(present(due = midnight - 3 * DAY, now = midnight).dueLine.contains("3"))
    }
```

- [ ] **4.2 Write the width-budget test** — `TaskCardWidthBudgetTest`, the 320dp contract. It measures, not
  eyeballs:

```kotlin
/**
 * The card at 320dp, as arithmetic (spec 4.1).
 *
 * 320 - 16 of card margins - 32 of card padding - 64 of the left column (48 handle + 4 + 4 rule + 8)
 * leaves 208dp of text column. Three 48dp buttons are 144 of them, which is why the action cluster has its
 * own line: beside the title it would leave four or five characters. This inflates the worst legal row — a
 * 120-character title, the longest category name in the strings file, a due date, a series, two reminders
 * and a running stretch — and measures what survives.
 *
 * Being inside the screen is not the bar. The bar is that the two facts the list is sorted and filtered by
 * are drawn in full, that the title has a readable measure, that every control is still 48dp, and that the
 * one element allowed to give way is the category name — visibly, by the 48dp rule, not by truncation.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TaskCardWidthBudgetTest {

    private lateinit var context: Context

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()
    private fun Float.asDp() = this / context.resources.displayMetrics.density

    /**
     * The worst legal row, with the two inputs that decide the budget pinned rather than defaulted.
     *
     * `priority = HIGH` because LOW draws no rule, and a rule measured at zero would quietly move the
     * 64dp column. `sortOrder = MANUAL` because anything else hides the drag handle and the column
     * collapses to 12dp — which is its own case, asserted separately below.
     */
    private fun bindWorstCase(widthDp: Int = 320, fontScale: Float = 1.0f): View {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val config = Configuration(app.resources.configuration).apply { this.fontScale = fontScale }
        // ContextThemeWrapper does not override createConfigurationContext, so the theme has to be
        // applied to the configuration context rather than wrapped before it — otherwise the inflated
        // row resolves ?attr/ink_* against the manifest theme and measures a different card.
        context = ContextThemeWrapper(app.createConfigurationContext(config), R.style.Theme_TaskPlanner)

        val adapter = TasksAdapter(
            onTaskClick = {}, onLongPressTask = {}, onToggleSelection = {}, onEdit = {},
            onDeleteRequest = {}, onStart = {}, onPause = {}, onResume = {}, onComplete = {},
            onWorkToggle = {}, onToggleGroup = {}, onGroupMenu = { _, _ -> }, onStartDrag = {}
        )
        adapter.submitList(
            listOf(
                TasksListItem.TaskRow(
                    task = Task(
                        id = 1, uuid = "budget", title = "Т".repeat(120), description = "",
                        userId = 1, status = TaskStatus.IN_PROGRESS,
                        dueDate = System.currentTimeMillis() - 3L * DAY
                    ),
                    category = Category(id = 1, uuid = "c", name = "Диссертация", color = 0, icon = "", userId = 1),
                    isSelected = false, series = aWeeklyRule(), pendingReminders = 2,
                    work = CardWork(closedSeconds = 6_300, liveSeconds = 60, phase = TrackingPhase.RUNNING)
                )
            )
        )
        awaitUntil("the row to be diffed") { adapter.itemCount == 1 }
        val holder = adapter.createViewHolder(FrameLayout(context), adapter.getItemViewType(0))
        adapter.bindViewHolder(holder, 0)
        return holder.itemView.also {
            it.measure(
                View.MeasureSpec.makeMeasureSpec(dp(widthDp), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            it.layout(0, 0, dp(widthDp), it.measuredHeight)
        }
    }

    @Test fun `the text column is what the budget says it is`() {
        val row = bindWorstCase(widthDp = 320)
        val card = row.findViewById<View>(R.id.taskCard)
        val content = row.findViewById<View>(R.id.cardContent)
        val leftColumn = row.findViewById<View>(R.id.dragHandle).width + dp(4) +
            row.findViewById<View>(R.id.priorityIndicator).width + dp(8)

        // getWidth() includes padding, so the subtraction is against content's own padding, not an
        // assumed 16dp on the card.
        assertEquals("card is the row less its 8dp margins", dp(320) - 2 * dp(8), card.width)
        assertEquals("the left column is 64dp", dp(64), leftColumn)
        assertEquals(
            "208dp of text column at 320dp", dp(208),
            content.width - content.paddingLeft - content.paddingRight - leftColumn
        )
    }

    @Test fun `each line keeps to its share of the column`() {
        val row = bindWorstCase(widthDp = 320)
        val title = row.findViewById<TextView>(R.id.taskTitle)
        val pill = row.findViewById<View>(R.id.taskStatus)
        val due = row.findViewById<View>(R.id.taskDueDate)
        val name = row.findViewById<TextView>(R.id.taskCategory)
        val figures = row.findViewById<View>(R.id.workRow)
        val group = row.findViewById<View>(R.id.buttonGroup)

        assertTrue("title ${title.width.asDp()}dp, budget 152", title.width >= dp(152))
        assertTrue("pill ${pill.width.asDp()}dp, budget 82", pill.width <= dp(82))
        assertTrue("date ${due.width.asDp()}dp, budget 84", due.width <= dp(84))
        assertEquals("the name is the element that gave way at 320dp", View.GONE, name.visibility)
        assertTrue("line C: figures ${figures.width.asDp()}dp + buttons ${group.width.asDp()}dp",
            figures.width + group.width <= dp(208))
    }

    /** The same card with the handle hidden, which is what a non-MANUAL sort does to the column. */
    @Test fun `a collapsed left column gives the title the 52dp the bind path promises`() {
        val row = bindWorstCase(widthDp = 320, sortOrder = SortOrder.DATE_DESC)
        val content = row.findViewById<View>(R.id.cardContent)
        val left = row.findViewById<View>(R.id.dragHandle).width + dp(4) +
            row.findViewById<View>(R.id.priorityIndicator).width + dp(8)
        assertEquals("handle gone, so the column is the rule plus its gap", dp(12), left)
        assertEquals("and the title's column grows to 260dp", dp(260),
            content.width - content.paddingLeft - content.paddingRight - left)
    }

    /**
     * Spec 4.1's "where the budget breaks", tested.
     *
     * The requirement is that the text FITS, not that it fills: a date needing 74dp in a box of 84dp is
     * correct, and asserting equality would call a healthy row a defect. Under Robolectric's LEGACY
     * graphics `Paint.measureText` is `text.length()` and ignores `textSize` entirely — verified in
     * `ShadowPaint` — which is why this class carries `@GraphicsMode(NATIVE)`; without it every fit
     * assertion below is vacuous and the test passes on a broken card.
     */
    @Test fun `at font scale 1.3 the card grows and nothing is dropped, clipped or shrunk`() {
        val row = bindWorstCase(widthDp = 320, fontScale = 1.3f)
        val baseline = bindWorstCase(widthDp = 320, fontScale = 1.0f)
        val content = row.findViewById<View>(R.id.cardContent)

        listOf(R.id.taskDueDate to "the date", R.id.taskStatus to "the status word").forEach { (id, what) ->
            val tv = row.findViewById<TextView>(id)
            val available = (tv.width - tv.paddingLeft - tv.paddingRight).toFloat()
            val needed = tv.paint.measureText(tv.text.toString())
            assertTrue("$what needs ${needed.asDp()}dp and has ${available.asDp()}dp", needed <= available)
            assertTrue(
                "$what must not be ellipsised: text ends '${tv.text.takeLast(2)}'",
                !tv.text.endsWith("…")
            )
            // The overflow that actually happens at 1.3: the line runs past the card, not the text past
            // its own box. This is the assertion the earlier draft was missing.
            assertTrue("$what runs past the content's right edge",
                tv.right <= content.width - content.paddingRight)
        }

        val due = row.findViewById<TextView>(R.id.taskDueDate)
        val dueAtOne = baseline.findViewById<TextView>(R.id.taskDueDate)
        assertEquals("line B is one line at the default scale", 1, dueAtOne.lineCount)
        assertTrue("the type grew rather than being scaled down", due.textSize > dueAtOne.textSize)

        // Card against card: the same level of the same hierarchy on both sides.
        val card = row.findViewById<View>(R.id.taskCard)
        val cardAtOne = baseline.findViewById<View>(R.id.taskCard)
        assertTrue("the card got taller instead of dropping a line (${card.height} vs ${cardAtOne.height})",
            card.height > cardAtOne.height)
        assertEquals("the status word is still on screen", View.VISIBLE,
            row.findViewById<View>(R.id.taskStatus).visibility)
    }

    @Test fun `the title keeps a readable measure`() {
        val row = bindWorstCase()
        val title = row.findViewById<TextView>(R.id.taskTitle)
        assertTrue("title is ${title.width.asDp()}dp, floor 128", title.width >= dp(128))
        assertEquals("two lines and no more", 2, title.maxLines)
    }

    @Test fun `the due date and the status word are drawn in full`() {
        val row = bindWorstCase()
        listOf(R.id.taskDueDate, R.id.taskStatus).forEach { id ->
            val tv = row.findViewById<TextView>(id)
            val wanted = tv.paint.measureText(tv.text.toString())
            assertTrue(
                "${row.resources.getResourceEntryName(id)} needs ${wanted}px and has ${tv.width - tv.paddingLeft - tv.paddingRight}",
                tv.width - tv.paddingLeft - tv.paddingRight >= wanted
            )
            assertFalse("neither may be ellipsised", tv.text.endsWith("…"))
        }
    }

    @Test fun `the category dot is always on screen and its name only when it can say something`() {
        val row = bindWorstCase()
        val name = row.findViewById<TextView>(R.id.taskCategory)
        val dot = row.findViewById<View>(R.id.categoryDot)
        val title = row.findViewById<View>(R.id.taskTitle)
        assertEquals("the dot heads line A, so it is left of the title", true, dot.left < title.left)
        assertTrue("and the dot always carries the colour", dot.visibility == View.VISIBLE)
        assertTrue(
            "the name is shown only with room to say something: ${name.width.asDp()}dp",
            name.visibility == View.GONE || name.width >= dp(CardPresentation.CATEGORY_NAME_MIN_DP)
        )
    }

    @Test fun `every visible control is a 48dp target`() {
        bindWorstCase().findViewById<ViewGroup>(R.id.buttonGroup).let { group ->
            (0 until group.childCount).map { group.getChildAt(it) }
                .filter { it.visibility == View.VISIBLE }.forEach {
                    assertTrue("${it.resources.getResourceEntryName(it.id)} is ${it.width}x${it.height}",
                        it.width >= dp(48) && it.height >= dp(48))
                }
        }
    }

    @Test fun `the work line stays a sibling of the title, as its own test demands`() {
        val row = bindWorstCase()
        assertEquals(
            "TaskCardWorkTest asserts this; three lines inside ONE ConstraintLayout keeps it true, three " +
                "nested line-layouts would break a test that is measuring something else entirely",
            row.findViewById<View>(R.id.taskTitle).parent,
            row.findViewById<View>(R.id.workRow).parent
        )
    }

    @Test fun `nothing overlaps and line B is one line tall`() {
        val row = bindWorstCase()
        val title = row.findViewById<View>(R.id.taskTitle)
        val due = row.findViewById<View>(R.id.taskDueDate)
        val pill = row.findViewById<View>(R.id.taskStatus)
        val group = row.findViewById<View>(R.id.buttonGroup)
        assertTrue("title right ${title.right} before the repeat icon", title.right <= due.left)
        assertTrue("due right ${due.right} before the pill", due.right <= pill.right)
        assertTrue("title bottom ${title.bottom} above the action group ${group.top}", title.bottom <= group.top)
        assertEquals("one line, not a wrapped meta row", 1, due.lineCount)
    }
}
```

- [ ] **4.3 Run 4.1 and 4.2 and watch them fail** — `CardPresentation` does not exist yet, and
  `R.id.cardContent` and `R.id.categoryDot` are new ids.

- [ ] **4.3b Add the strings the card says.** `values/strings.xml` is the only locale folder in this
  repository, so each needs exactly one entry: `task_due_today_format` (`"Today %1$s"`),
  `task_due_tomorrow_format` (`"Tomorrow %1$s"`), a `<plurals name="task_late_days">` (`"%d day late"` /
  `"%d days late"`), and `no_due_date`, `status_todo`, `status_in_progress`, `status_paused`, `status_done`
  already exist and are reused rather than duplicated.

- [ ] **4.4 Rewrite `item_task.xml`** to the three-line structure of spec §4.1, flattening the current
  266-line nesting: root `FrameLayout` → `actionPanel` (2 buttons, surface backdrop) + `taskCard` →
  **one** `ConstraintLayout id=cardContent` holding line A (`categoryDot`, `taskTitle`, `repeatIcon`,
  `reminderBadge`), line B (`taskStatus` as a pill, `taskDueDate`, `taskCategory`), line C (`workRow`
  figures + `buttonGroup` with the five existing button ids), with `dragHandle` and `priorityIndicator`
  sharing the 64dp left column. The card's padding lives on **`cardContent`** — `paddingStart`/`paddingEnd`
  16dp, `paddingTop`/`paddingBottom` 14dp, from `spacing_*` and `card_padding_*` — exactly where it lives
  today on the inner layout, and not on `taskCard`; 4.2's "content is the card less its padding" assertion
  depends on that choice, and putting it on the card instead would make the MaterialCardView clip its own
  ripple. Keep every existing id — `taskCard`, `actionPanel`, `editTaskButton`, `deleteTaskButton`,
  `selectionCheck`, `dragHandle`, `priorityIndicator`, `taskTitle`, `taskDescription`, `taskDueDate`,
  `taskCategory`, `taskStatus`, `reminderBadge`, `workRow`, `workTime`, `workTicker`, `workDot`,
  `workControlButton`, `startTaskButton`, `pauseTaskButton`, `resumeTaskButton`, `completeTaskButton`
  **and `buttonGroup`**, which is the `LinearLayout` the four status buttons sit in today
  (`item_task.xml:215`) and becomes the action cluster: it is renamed nowhere. New ids: `cardContent`,
  `categoryDot`, `repeatIcon`.

  Three view **types** are locked by tests and may not change while the ids survive: `workRow` is fetched
  as a `LinearLayout` (`TaskCardWorkTest:110`), `workControlButton` as an `ImageButton` (`:131`) and
  `reminderBadge` as an `ImageView` (`TaskCardReminderTest:97`). Swapping the LinearLayout for a second
  ConstraintLayout, or the ImageButton for a MaterialButton, fails three tests that never mentioned the
  thing being changed. That is why line C's cluster stays a `LinearLayout` and the buttons stay
  `ImageButton`s styled rather than replaced.

  `taskDescription` stays in the layout but is `View.GONE` unless the task has a description — the current
  behaviour — and when visible it takes a line between A and B.

- [ ] **4.5 Rewrite the bind path.** `TasksAdapter.TaskViewHolder.bind` calls
  `CardPresentation.of(context, item, now)` where `now` comes from a `timeBase` the adapter is given, and
  applies it: pill text/background/colour, rule colour or `INVISIBLE`, overdue `ink_error` on the due line,
  the four `showX` flags as `View.GONE`/`VISIBLE`, the category name's 48dp rule measured against the
  laid-out width in `doOnLayout`. Three further rules from spec §4.1 belong in the same bind path, because
  each is a behaviour the spec states and no step had yet assigned:

  - the drag handle is `GONE` unless `state.sortOrder == SortOrder.MANUAL` **and** the row is not selected
    — which is what `bind` does today through `reorderEnabled`, restated so the 320dp screenshots are read
    against a rule; when it goes, the 64dp left column collapses to the rule's 12dp and the title gains
    52dp, which 4.2's line-A assertion must allow for;
  - the measured-time figures collapse to their icon when `workRow` and `buttonGroup` together exceed the
    208dp column — the figures give way, the buttons never do;
  - `completeTaskButton` is the only control with emphasis: filled `ink_accent` background, `ink_on_accent`
    icon. The lifecycle and work buttons stay borderless tints. One primary action per card, which is the
    whole reason the card stopped looking like a toolbar.

  The two play buttons that meant different things at the baseline now differ. `startTaskButton` keeps
  `ic_play` and its `@string/start_task` description. `workControlButton` gets a **new** vector,
  `res/drawable/ic_work.xml` — a stopwatch outline, 24dp viewport, tinted from the theme like its
  neighbours — because the repository has no timer-shaped icon: `ls app/src/main/res/drawable | grep "^ic_"`
  lists 42 icons and none of them is a clock, so "reuse an existing one" is not an option here. A new
  drawable is a resource, not a dependency, and it is the only icon this stage adds; `repeatIcon` reuses
  the `ic_repeat.xml` that already exists. The work control keeps its existing phase-driven
  `contentDescription`, which already distinguishes start from pause from resume in words.

- [ ] **4.6 Inject the clock.** `TasksViewModel` gains one defaulted constructor parameter, and
  `TasksFragment` passes `viewModel.nowMillis()` down through the adapter — or the adapter takes a
  `() -> Long` supplied by the fragment from the view model. Choose the smaller diff, and keep the rule:
  no `System.currentTimeMillis()` anywhere in `ui/tasks/`.

```kotlin
    // TasksViewModel
    @Inject constructor(
        ...,
        private val timeBase: TimeBase = AndroidTimeBase()
    ) {
        /** The instant the cards measure "late" against. Injectable so the boundary is testable. */
        val nowMillis: Long get() = timeBase.wallMillis()
```

- [ ] **4.7 Run the gates.** Full suite, then the Tasks group, then `assembleDebug`, then `lintDebug`
  (a new `contentDescription` gap is a lint finding, and lint must not regress).

- [ ] **4.8 Device evidence.** Install, then photograph the card states that the tests claim: TODO,
  IN_PROGRESS, PAUSED, overdue, LOW with no rule, a 120-character title, a running stretch, selection mode,
  and the same card at 320dp and at font scale 1.3. Compare each against `before/01-tasks.png` with
  `design-review:visual-regression-review` and write down what changed and why it is better, not merely
  different.

- [ ] **4.9 If the budget does not close in code** — a measured due date wider than 84dp, a pill wider
  than 82 — **stop**. Do not shrink a button, a target or a font to make a test pass. Report the measured
  numbers and propose the spec amendment (a different line assignment, or a shorter status word), and wait.
  This is the one step in the plan whose correct failure mode is a conversation.

- [ ] **4.10 Commit**, listing in the message every test that moved and why its old assertion was the
  baseline's truth rather than the new one.

**Acceptance:** `TaskCardWidthBudgetTest` and `CardPresentationTest` pass at 320dp and at font scale 1.3;
`TaskCardWorkTest` and `TaskCardReminderTest` pass against the rewritten card **with one exception, named
here rather than discovered in a red build**: `a task with nothing measured and no timer has no work line
at all` and its siblings assert on `workRow` as a `LinearLayout` and on `workControlButton` as an
`ImageButton`, and 4.4 keeps both types precisely so those casts still compile and still assert the same
behaviour. No assertion in either file is deleted or loosened; if one of them fails, the layout changed a
type it should not have. The overdue treatment is photographed on a real row; the two play buttons are no
longer identical; the card at 320dp shows a full due date, a full status word and a title of at least 128dp.
**Screenshots:** `s4-card-todo.png`, `s4-card-inprogress.png`, `s4-card-paused.png`, `s4-card-overdue.png`,
`s4-card-longtitle.png`, `s4-card-running.png`, `s4-selection.png`, `s4-320.png`, `s4-font13.png`,
`s4-dark.png`.

---

## Stage 5 — Profile: information architecture, and its first tests

Purpose: turn eight stacked buttons into a screen. Depends on: S1 (styles, includes). Tools:
`frontend-design`, `android-jetpack`.

Profile has **no tests at all** today — verified by `find app/src/test -path "*profile*"` returning
nothing — so this stage writes coverage as its first act, not as its last.

- [ ] **5.1 Write `ProfileLayoutTest`** (JVM/Robolectric, inflates `fragment_profile.xml` under the app
  theme): every row is a `row_list` include with an icon, a title, a chevron and ≥ 48dp height; the four
  section labels exist; the danger row is separated from the others by a section header rather than by
  colour alone; no row is a bare `MaterialButton` spanning the screen.

- [ ] **5.2 Write `ProfileScreenInstrumentedTest`** (device; `ActivityScenario`; seed the profile straight
  into the app's storage the way `TaskEditorInstrumentedTest` does): the screen renders with a long name and
  a long email; each of the **six navigating** rows reaches its real destination — assert the destination by
  the `NavHostFragment`'s current destination id after the tap; the delete confirmation names the
  consequences and cancelling leaves the profile in place. The other two of the eight includes
  (`editProfileButton` and `deleteProfileButton`) open dialogs rather than navigating and are asserted as
  dialogs, which is what they are.

- [ ] **5.3 Run both, watch them fail.**

- [ ] **5.4 Rewrite `fragment_profile.xml`**: header (64dp `AvatarView` initial, name in `headline`, email
  in `meta`), then four sections — Account (`editProfileButton`, `switchProfileButton`), Your work
  (`openGoalsButton`, `openHabitsButton`, `openInsightsButton`, `openReportsButton`), Data
  (`openSettingsButton`), Danger zone (`deleteProfileButton`) — each row an
  `<include layout="@layout/row_list" android:id="@+id/rowEditProfile"/>`-style include keeping the
  existing button ids on the include root. That is **not** a no-op for the fragment: `binding.apply {
  editProfileButton.setOnClickListener { … } }` becomes `binding.rowEditProfile.root.setOnClickListener {
  … }` for all ten listeners, and `ProfileFragment.setupUI` is rewritten in this commit, not left for
  someone to discover. Keep `contentGroup`, `noUserGroup`, `progressBar`, `profileName`, `profileEmail`,
  `createProfileButton`, `switchProfileButtonNoUser`.

  `contentGroup` is not merely a Profile detail: `TasksScreenInstrumentedTest:56` asserts it is displayed
  after navigating away to Statistics and back, so removing or renaming it breaks a test on a different
  screen. `profileCard` is bound by nothing in Kotlin or in any test — `grep -rn "profileCard" app/src`
  returns the layout alone — so the header rewrite drops it, and the commit message says so rather than
  leaving the deletion unexplained.

  No Achievements row (spec §1: the screen does not exist). No archive or work-history row (they belong to
  Tasks). No backup row (Settings owns export and import). No avatar photo (spec §1).

- [ ] **5.5 Update `ProfileFragment`**: bind through the include bindings, set each row's icon, title,
  trailing value and `contentDescription`, and give the row root the click listener so the whole 56dp row is
  the target. `showDeleteConfirmation`, `validateInput`, `showEditProfileDialog`, `showCreateProfileDialog`
  and every `viewModel.*` call stay exactly as they are — the business logic is untouched, including the
  deletion path.

- [ ] **5.6 Gates, then device evidence:** light and dark at 393dp, 320dp, font 1.3, a long name
  ("Christopher Maximilian von und zu Thalberg"), a long email, and the no-profile state. Six screenshots.
  Run `design-review:accessibility-review` over the row list: targets, labels, focus order, contrast of the
  section labels.

- [ ] **5.7 Commit.**

**Acceptance:** every Profile row leads to a destination that exists and the test proves it; the screen has
tests for the first time; the delete flow behaves as it did at the baseline; no invented row.
**Screenshots:** `s5-profile-light.png`, `s5-profile-dark.png`, `s5-profile-320.png`,
`s5-profile-font13.png`, `s5-profile-long.png`, `s5-profile-nouser.png`.

---

## Stage 6 — Task editor: sections, sticky footer, ids preserved

Purpose: regroup the same fields. Depends on: S1. Tools: `frontend-design`.

- [ ] **6.1 Read the current assertions first** — `TaskDetailLayoutTest` asserts child *order* inside the
  editor's content container, and the full list is longer than it looks. Every one of these must be
  re-derived from the new section order, not assumed:

| assertion | file:line | survives the regrouping? |
|---|---|---|
| `index(button_container) == content.childCount - 1` | `:133` | **no** — the sticky footer leaves the scroll view entirely, so `button_container` is not a child of `content` any more and the assertion is rewritten to "the footer is a sibling of the scroll and pinned to the bottom" |
| `index(button_container) == index(repeat_section) + 2` | `:136-137` | no — the gap between Repeat and the footer grows when Reminders and Measured work move after Repeat |
| `task_category_layout < repeat_section` | `:139` | yes (How before Repeat in the new order too) |
| `plan_section < work_section` | `:219` | yes |
| `work_section < task_priority_layout` | `:221` | **no** — the new order puts How (priority) before Measured work, which inverts it |
| `task_due_time_layout < reminder_section` | `:255` | yes |
| `reminder_section < repeat_section` | `:259` | **no** — the new order is Repeat then Reminders, which inverts it |

  Write the new order down as a list of ids before touching the XML, change the test and the layout in one
  commit, and put the three inversions in the commit message so a reader can see they were decided rather
  than discovered by a red build.

- [ ] **6.2 Preserve the ids** the tests and the fragment bind to: `repeat_preset`,
  `repeat_interval_layout`, `repeat_interval`, `task_title`, `task_category_layout`, `plan_section`,
  `work_section`, `repeat_section`, `reminder_section`, `button_container` and every section id named above.
  The adjustment dialog's inputs are `workStartInput`, `workEndInput`, `workReasonInput` and `workPrevious`
  and they live in `dialog_work_interval.xml`, **not** in the editor layout — an earlier draft of this plan
  listed `work_start_input` and `work_end_input`, which exist nowhere in the repository.
  `RepeatPreset`'s nullable `unitLabelRes` and `TaskDetailFragment.applyPreset`'s
  `intervalHint(::getString)` line must survive untouched — PR #21's fix and its eight unit tests plus four
  instrumented tests are the regression gate here.

- [ ] **6.3 Rebuild the layout** into labelled sections — Task (title, description), When (due date, time,
  plan), How (priority, status, category), Repeat (collapsed until opened), Reminders, Measured work — with
  inline errors on the offending field and Save/Cancel in a sticky footer outside the scroll view.

- [ ] **6.4 Tests:** update `TaskDetailLayoutTest`'s order assertions to the new truth; keep
  `WorkAdjustmentUiTest`'s behaviour assertions; extend the editor's instrumented class with "the editor
  opens, validates and saves" if the existing four do not already cover a save.

- [ ] **6.5 Gates, device evidence** (editor opened from `+`, from a task with no series, from a task in a
  series; each repeat preset rendered; validation error shown; sticky footer at 320dp and at font 1.3),
  then commit.

**Acceptance:** no field lost; validation still inline; the repeat section still renders all eleven presets
without crashing; the sticky footer is visible at 320dp with the keyboard up; `TaskEditorInstrumentedTest`
green on device. **Screenshots:** `s6-editor-top.png`, `s6-editor-repeat.png`, `s6-editor-error.png`,
`s6-editor-320.png`, `s6-editor-font13.png`, `s6-editor-dark.png`.

---

## Stage 7 — Bottom navigation

Purpose: the bar stops merging into the screen and all five labels appear. Depends on: S1.
Tools: `design-review:responsive-design`.

- [ ] **7.1** `activity_main.xml`: `bottom_navigation` background → `?attr/colorSurfaceContainer` (it is
  `?android:attr/windowBackground` today, which is why it disappears into the page), add
  `app:labelVisibilityMode="labeled"`, `app:itemActiveIndicatorStyle` → a pill behind the icon, and a
  hairline above the bar.
- [ ] **7.2** `Widget.TaskPlanner.BottomBar` in `component_styles.xml`: `itemIconTint` and `itemTextColor`
  as colour-state-lists over `ink_text` / `ink_text_secondary`.
- [ ] **7.3** A JVM test asserting all five labels are visible at once — the baseline shows only "Tasks".
- [ ] **7.4** Gates; screenshots of all five tabs at 393dp and 320dp, light and dark; commit.

**Acceptance, each one checkable:** `uiautomator dump` after the change lists five `text` nodes under
`bottom_navigation` where today it lists one; the active item's `contentDescription`-adjacent state carries
the indicator drawable and a different text style from the other four, which the same dump shows; the bar's
resolved background colour differs from the resolved `android:colorBackground` by at least 1.3:1 measured
with the script's own luminance function, so the bar reads as a surface rather than as the page. **Screenshots:** `s7-nav-<tab>.png` ×5, `s7-nav-320.png`,
`s7-nav-dark.png`.

> The spec's revision-1 claim that the FAB covered a row was withdrawn (§2.2) and no FAB or inset work is
> in this stage. The bar keeps its placement; only its surface and its active treatment change.

---

## Stage 8 — The remaining screens, in four commits

Purpose: every screen on the same system. Depends on: S1, S5 (the `row_list` include), S7.
The rule for the whole stage: **tokens, type, components and spacing only.** No query, no view model, no
calculation, no wording logic changes. Each sub-stage is its own commit, its own test run and its own
screenshots, because one "restyle the rest" commit cannot be reviewed or bisected.

The substitution table every sub-stage applies. Left is what the layouts contain today (counts verified in
§2); right is what it becomes:

| current | becomes |
|---|---|
| `android:textColor="?android:attr/textColorSecondary"` | `android:textColor="?attr/colorOnSurfaceVariant"` |
| `android:textAppearance="?attr/textAppearanceCaption"` | `?attr/textAppearanceBodySmall` |
| `…"?attr/textAppearanceBody2"` | `?attr/textAppearanceBodyMedium` |
| `…"?attr/textAppearanceSubtitle1"` | `?attr/textAppearanceTitleMedium` |
| `…"?attr/textAppearanceSubtitle2"` | `?attr/textAppearanceLabelLarge` |
| `…"?attr/textAppearanceHeadline6"` | `?attr/textAppearanceTitleLarge` |
| `style="@style/Widget.MaterialComponents.Button.OutlinedButton"` | `style="@style/Widget.TaskPlanner.Button.Outlined"` |
| `style="@style/Widget.MaterialComponents.Button.TextButton"` | `style="@style/Widget.TaskPlanner.Button.Text"` |
| `style="@style/Widget.MaterialComponents.Chip.Filter"` | `style="@style/Widget.TaskPlanner.Chip.Filter"` |
| `style="…TextInputLayout.OutlinedBox"` | `style="@style/Widget.TaskPlanner.Field"` |
| `style="…OutlinedBox.ExposedDropdownMenu"` | `style="@style/Widget.TaskPlanner.Field.Dropdown"` |
| `app:cardElevation="2dp"` / no card style | `style="@style/Widget.TaskPlanner.Card"` |
| `@color/status_paused` / `@color/status_done` | `@color/ink_warning` / `@color/ink_success` |
| `app:chipBackgroundColor="@color/chip_background_color"` | the new `ink` chip CSLs |
| literal `16dp` / `8dp` / `12dp` / `20dp` paddings and margins | the `spacing_*` / `screen_margin` dimens |
| `@dimen/text_*` | the type role, or delete if unused |

After each sub-stage, grep the touched files for what must be gone:

```bash
grep -rn "?android:attr/textColor\|Widget.MaterialComponents\|@dimen/text_\|@color/status_\|@color/red" \
  app/src/main/res/layout/<files you touched> || echo "clean"
```

### 8a — Categories, Archive, Work history, Settings, profile selection

Files: `fragment_categories.xml`, `item_category.xml`, `fragment_category_detail.xml`,
`fragment_category_tasks.xml`, `fragment_archive.xml`, `item_archive.xml`, `fragment_work_history.xml`,
`item_work_interval.xml`, `item_history_interval.xml`, `fragment_settings.xml`,
`fragment_profile_selection.xml`, `item_profile.xml`, `dialog_profile_edit.xml`, and the matching
adapters' colour/tint reads.

Tests that travel, and an earlier draft of this step claimed did not exist. `WorkHistoryLayoutTest:67`
asserts `binding.historyList.parent === binding.root` on `fragment_work_history.xml`, which this sub-stage
rewrites: wrapping the list in a container to add a header would fail a test about growth, not about
colour, and the fix is to keep the list a direct child. Its hint assertions (`:143-145,158`) survive a
restyle untouched and must not be edited. `ReportPreviewViewTest:52` reads `R.dimen.report_page_gap`, and
`ReportPageFitTest` measures page fitting — both are re-run, not rewritten. `CalendarDayLayoutTest` and
`ChartGeometryTest` are not in this sub-stage's files but are listed here so 8d and 8b find them already in
the ledger. The risk that remains is silent visual breakage, which is what the screenshots are for.

Acceptance: Settings still shows export/import/sign-in/version with the same actions; Archive's rows read
their completion without colour alone (this is where the **Completed card treatment actually applies** —
spec §4.1: it is scoped to the screens that draw completed rows, and the Tasks list is not one of them);
profile selection lists the same profiles and switches the same way.

Screenshots: `s8a-categories.png`, `s8a-archive.png`, `s8a-work-history.png`, `s8a-settings.png`,
`s8a-profile-selection.png`, plus `s8a-archive-dark.png`.

### 8b — Statistics and the dashboard (the only colour-re-anchoring commit)

Files: `fragment_statistics.xml`, `item_dashboard_task.xml`, the chart views' style reads, and the
`chart_*` / `health_*` ramp values — which S1 deliberately left alone and which move **here**.

The ramp's invariant, because `StatisticsLayoutTest` asserts it and the re-anchor must not break it by
accident: the four heat levels keep **one RGB triple with strictly rising alpha**, and
`chart_heatmap_none` stays a different colour from `chart_heatmap_1`. Re-anchoring therefore means changing
the RGB in all four values together — `#4D2F6F5E`, `#802F6F5E`, `#B32F6F5E`, `#FF2F6F5E` in the light file —
and the same shape on the dark accent in `values-night/`. The pill rule from spec §3.1 (solid tokens, no
alpha tints) applies to pills; a heat scale's entire meaning is one hue at five densities.

Tests that move in this commit, rewritten to the new truth: `StatisticsLayoutTest` (the named-spacing
allowlist gains `spacing_medium` and `spacing_section` if any dashboard gap uses one — see 1.7b; the
five-distinct-appearances assertion is satisfied by 1.5's mapping and must be re-run, not edited; the ramp
assertions are re-checked against the new RGB), `DashboardRendererTest` and `ChartViewDrawTest` (both read
`R.color.chart_heatmap_*` and `R.dimen.chart_*` through `getColor`, so they follow the values — verify they
still measure what they claim and update any literal you find).

Acceptance: the heatmap's four levels are ordered by measured luminance, not by eyeball — assert
`L(none) > L(1) > L(2) > L(3) > L(4)` in the test; the health panel's five buckets each carry their word,
so a reader without colour access gets the same five categories; no chart text is smaller than the `meta`
role; the printed report colours are unchanged, because a report is paper.

Screenshots: `s8b-statistics.png`, `s8b-statistics-dark.png`, `s8b-statistics-320.png`,
`s8b-statistics-font13.png`.

### 8c — Goals, Habits, Insights

Files: `fragment_goals.xml`, `item_goal.xml`, `item_goal_change.xml`, `item_goal_task.xml`,
`fragment_goal_detail.xml`, `fragment_goal_editor.xml`, `fragment_habits.xml`, `item_habit.xml`,
`item_habit_day.xml`, `item_habit_weekday.xml`, `item_habit_change.xml`, `fragment_habit_detail.xml`,
`fragment_habit_editor.xml`, `dialog_habit_mark.xml`, `fragment_insights.xml`, `item_insight.xml`,
`item_insights_empty.xml`, `item_workload_day.xml`, `item_workload_panel.xml`.

Tests that move, with one that constrains the design rather than following it.
`InsightsLayoutTest:305-315` asserts the empty row is `match_parent` wide, has
`paddingLeft >= 24dp && paddingRight >= 24dp`, and is centred. `state_empty.xml` must therefore carry 24dp
of horizontal padding and `gravity=center_horizontal` — if it does not, the only way to pass is to relax
the assertion, and relaxing it is the silent weakening this plan forbids. `InsightCardTest` and
`InsightsScreenTest` read the same answer ids (`answerAccepted`, `answerUseful`, `answerHidden`,
`answerRejected`) and the same `insightAction` button; all four ids and their order survive the restyle.
`WorkloadPanelTest:296` is reference-locked to `R.color.priority_*` and follows the re-anchored values.

Acceptance: `bg_goal_state` and `bg_habit_day*` still read `state_badge_fill` and still work in dark; the
habit week grid keeps its 48dp tap targets; the insight answer buttons keep their four ids in their current
order (`answerAccepted`, `answerUseful`, `answerHidden`, `answerRejected`) — `InsightsLayoutTest` asserts
exactly that list.

Screenshots: `s8c-goals.png`, `s8c-habit-detail.png`, `s8c-insights.png`, `s8c-insights-empty.png`,
`s8c-insights-dark.png`, `s8c-goals-320.png`.

### 8d — Calendar and Reports

Files: `fragment_calendar.xml`, `item_calendar_cell_task.xml`, `item_calendar_block.xml`,
`item_calendar_day.xml`, `item_calendar_marker.xml`, `fragment_report.xml`, `dialog_report_range.xml`,
`dialog_report_zone.xml`, and `CalendarCellAdapter` / `CalendarViewController` colour reads.

Tests that move: `CalendarScreenTest` (it asserts `context.getColor(R.color.on_surface)` for an unfiled
task's chip — the alias keeps that passing, and the dp measurements are re-run rather than edited),
`ReportScreenInstrumentedTest` (device; ids preserved so it compiles and still passes).

Acceptance: the calendar's day cells keep their today/selected/overdue distinctions without colour alone —
asserted by the existing `CalendarScreenTest` passing unchanged plus a screenshot of a cell holding an
overdue, a today and a dated task at once; the report preview's paper stays paper at any theme (spec §3.1 —
report colours are fixed by design); the report screen's period, zone, preview and export controls are the
same controls.

**8d.4 — retire the legacy `text_*` dimensions.** This is where they go, because this is where their last
reader is: `fragment_report.xml:244` reads `@dimen/text_caption`. Replace that one attribute with the
`caption` role, then delete `text_caption`, `text_body`, `text_title`, `text_headline` and `text_display`
from `dimens.xml`, and prove nothing is left:

```bash
grep -rn "@dimen/text_\|R\.dimen\.text_" app/src || echo "retired"
```

Screenshots: `s8d-calendar.png`, `s8d-calendar-day.png`, `s8d-report.png`, `s8d-report-dark.png`,
`s8d-calendar-320.png`.

- [ ] **8.4b The three state includes get their consumers.** `state_empty.xml` is used by Insights (8c, it
  replaces the inline empty row `item_insights_empty.xml` inflates) and by Tasks (4.4's `emptyView`);
  `state_loading.xml` replaces the bare `ProgressBar` on Tasks (4.4), Statistics (8b) and Profile (5.4), so
  a wait is the same shape on all three; `state_error.xml` replaces the `Snackbar`-only path on Statistics
  (8b) and Reports (8d), where a failed read currently leaves the screen showing nothing. Each is a layout
  substitution plus one `when (state)` branch in the fragment that already has that branch — no new state,
  no new error handling, and the existing `showError` calls keep working. If a sub-stage reaches its
  screenshot pass and one of the three includes is still used nowhere, that is a gap in this plan: say so
  rather than shipping an unused layout and a coverage table claiming it is used.

- [ ] **8.5 After all four commits:** the whole suite, `assembleDebug`, `lintDebug`,
  `assembleDebugAndroidTest`, and the contrast script against the resources. Then delete the alias block
  from `colors.xml` **only if** `grep -rn "@color/primary\b\|@color/surface\b\|@color/on_surface\b" app/src/main/res`
  is empty; if any name is still referenced, leave the alias and record which screen still needs it —
  deleting an alias a layout reads is a build break, not a cleanup.

---

## Stage 9 — Motion

Purpose: the list should read as made rather than assembled, without ever costing a frame at the top of a
scroll. Depends on: S4.

- [ ] **9.0 Choose the mechanism, and know why the obvious one is wrong.** The tempting implementation is to
  start a fade-and-rise from `TasksAdapter.bind`, and it is wrong for a reason worth writing down:
  `onBindViewHolder` runs on every rebind, so scrolling a row off the top and back replays the animation,
  and a list that animates while you scroll looks broken rather than lively.

  The mechanism is the **item animator**, not the adapter. RecyclerView calls `animateAdd` only for a holder
  that appeared because the data changed — an insert from `submitList` — and never for a holder rebound from
  the recycle pool during a scroll. So:

```kotlin
package com.taskplanner.ui.tasks

import android.animation.ValueAnimator
import android.content.Context
import android.view.animation.DecelerateInterpolator
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.RecyclerView
import com.taskplanner.R

/**
 * Fade plus a 12dp rise on insert, and nothing at all on a scroll.
 *
 * Insertion is the only moment this animates, and RecyclerView decides that: an inserted holder is routed
 * through animateAdd, while a holder rebound from the pool during a scroll is routed through no animator
 * method at all. That is why the animation cannot live in onBindViewHolder, which runs on every rebind and
 * would replay the entrance each time a row scrolls back into view.
 *
 * animateAdd is the override point, not animateAddImpl. In the artifact this APK actually resolves —
 * recyclerview 1.1.0, per the manifest-merger report, not the 1.3.2 an earlier draft of this plan
 * inspected — animateAddImpl is package-private, and Kotlin rejects an override of it from another
 * package: "'public open fun animateAddImpl' has no access to ... so it cannot override it".
 *
 * The scale check is ours on purpose. recyclerview 1.1.0 contains no getDurationScale reference at all, so
 * at animator scale 0 the framework's animators merely finish instantly while the work of building and
 * starting them still happens. Returning false skips that work, which is the behaviour 9.2 tests.
 */
class CardItemAnimator(private val context: Context) : DefaultItemAnimator() {

    override fun animateAdd(holder: RecyclerView.ViewHolder): Boolean {
        val view = holder.itemView
        if (ValueAnimator.getDurationScale() <= 0f) {
            view.alpha = 1f
            view.translationY = 0f
            dispatchAddStarting(holder)
            dispatchAddFinished(holder)
            return false
        }
        endAnimation(holder)
        view.alpha = 0f
        view.translationY = context.resources
            .getDimensionPixelSize(R.dimen.motion_entry_rise).toFloat()
        dispatchAddStarting(holder)
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(context.resources.getInteger(R.integer.motion_standard).toLong())
            .setInterpolator(DecelerateInterpolator())
            .withEndAction { onAddFinished(holder) }
            .start()
        return true
    }

    /** A reorder is not news; the pill crossfade is done in bind, so only the add animates. */
    override fun getMoveDuration(): Long = 0L
    override fun getChangeDuration(): Long = 0L
}
```

  Every resource this touches is declared by the stage that uses it: `motion_entry_rise` (12dp) and
  `motion_standard` (180) land in 9.1's `dimens.xml` and `anim.xml` additions, before this class exists.
  `TasksFragment.setupRecyclerView()` assigns `recyclerView.itemAnimator = CardItemAnimator(requireContext())`
  once; `fragment_tasks.xml` sets no `app:itemAnimator` today, so there is nothing to unset.

- [ ] **9.1 Status change cross-fades the pill, and only the pill.** `DiffUtil.ItemCallback` gains
  `getChangePayload(old, new)`: when the two rows differ solely in `task.status`, it returns
  `PAYLOAD_STATUS`; otherwise null, which means a full bind. `TasksAdapter` overrides
  `onBindViewHolder(holder, position, payloads)` and, when the payload list contains `PAYLOAD_STATUS`,
  re-applies the pill text, colour and background plus a 120ms alpha crossfade, and touches nothing else.
  That is what makes a status change animate and a scroll not re-run the whole row.

```kotlin
        private const val PAYLOAD_STATUS = "status"

        override fun getChangePayload(o: TasksListItem, n: TasksListItem): Any? =
            if (o is TasksListItem.TaskRow && n is TasksListItem.TaskRow &&
                o.task.status != n.task.status && o.task.title == n.task.title &&
                o.task.dueDate == n.task.dueDate && o.work.phase == n.work.phase
            ) PAYLOAD_STATUS else null
```

  The payload decision is deliberately narrow: it fires only when the status is the *only* difference, so a
  change that also moved the due date or started a timer takes the full bind rather than a half-updated
  card. A wrong payload is a silently stale row, which is worse than an extra bind.

- [ ] **9.2 The animator scale, tested rather than asserted.** RecyclerView 1.3.2 does **not** consult
  `ValueAnimator.getDurationScale()` itself — verified against the artifact in the Gradle cache, where
  neither `isAnimatorDisabled` nor a `getDurationScale` reference exists in `androidx.recyclerview` — so the
  "scale 0 means no motion" behaviour comes from the framework's own animators finishing immediately, not
  from this class. Two consequences, both tested: the hand-written `animateAdd` must check
  `ValueAnimator.getDurationScale() == 0f` and skip the animation work outright rather than start an
  animator that ends instantly, and the group-collapse height animation gets the same guard.

```kotlin
    @Test fun `animator scale zero skips the work instead of starting a no-op animator`() {
        ValueAnimator.setDurationScale(0f)
        val holder = … // a bound holder
        val started = animator.animateAdd(holder)
        assertFalse("nothing to animate, and nothing scheduled", started)
        ValueAnimator.setDurationScale(1f)
    }
```

- [ ] **9.3 The threshold.** A `submitList` that grows the list past `motion_entry_max` (12) pending
  additions disables the entry animation for that submission. This is a counter on the animator, not a scan
  of the adapter, and a test with 200 rows asserts fewer than 12 holders ever animate.

- [ ] **9.4 Group collapse** animates height through the same animator; pressed states stay the standard
  ripple; durations live in `res/values/anim.xml` and nowhere else.

- [ ] **9.5 Gates and evidence.** Full suite, `assembleDebug`, `lintDebug`; then a device check that a
  scroll produces no animation and a new task produces one, photographed mid-flight (`s9-scroll-static.png`,
  `s9-insert.png`), and a run with the system animation scale at 0 to confirm nothing hangs.

**Acceptance, each one checkable:** a test asserts `animateAdd` is not called for a holder rebound during
scroll (`RecyclerView` recycles, so the assertion is on the animator's call count across a
`scrollBy`); a test with 200 rows submitted at once asserts fewer than 12 animated; a test with
`ValueAnimator.setDurationScale(0f)` asserts `animateAdd` returns false; `getChangePayload` returns
`PAYLOAD_STATUS` for a status-only change and null for a title change, asserted directly; and
`dumpsys gfxinfo framestats` after a 20-fling scroll shows no frame over 32ms — the same bar 10.5 holds the
whole stage to.

---

## Stage 10 — Accessibility and adaptivity matrix

Purpose: prove the design survives the configurations a real handset brings. Depends on: S2–S9.
Tools: `design-review:accessibility-review`, `design-review:responsive-design`,
`design-review:visual-regression-review`, `design-review:design-debt-review`,
`design-review:component-library-alignment`.

- [ ] **10.1a The variant-surface rule gets a verifier, because no token-pair test can see it.** Spec §3.2
  forbids `ink_success`, `ink_warning` and `ink_error` *as text* on `ink_surface_variant`. Both gates
  measure token pairs and neither looks at a layout, so the rule is currently enforced by nobody — and 1.5
  maps `colorSurfaceContainerHigh` and `_Highest` to that very surface, which is where Material paints
  component backgrounds. Add a JVM test that inflates every redesigned layout and walks its `TextView`s:
  for each, resolve `android:textColor`/`textColor` through the theme, resolve the nearest background
  colour the same way, and assert the pair is one the token table allows. Where the walk cannot resolve a
  background honestly — a drawable with no solid colour, a state list — the test records the view in a
  named exception list rather than skipping it silently, and the exception list is reviewed in stage 12.

- [ ] **10.1 Automated sweeps.**

```bash
python scripts/contrast_report.py --resources app/src/main/res --check
./gradlew --no-daemon --console=plain lintDebug
grep -rn "android:layout_height=\"[0-9]" app/src/main/res/layout | grep -v "item_calendar\|avatar\|icon\|dot\|rule"
grep -rL "contentDescription" $(grep -rl "ImageButton\|app:icon=\"@" app/src/main/res/layout) | head
```

The third and fourth are candidates to inspect, not verdicts: an icon-only control without a
`contentDescription` is a finding, a decorative `ImageView` is not.

- [ ] **10.2 The matrix.** Every screen of §Stage 8 plus Tasks, Profile and the editor, at 320dp / 360dp /
  393dp, font 1.0 / 1.3, light / dark. The density pairs are in §6; record them in the review document
  alongside each photograph so a reader can reproduce the exact configuration.

- [ ] **10.3 TalkBack pass** on Tasks, Profile and the editor: the row's state, priority and overdue reach a
  screen reader in words; every icon-only control announces itself; the sort and kebab menus are reachable by
  focus, not only by touch.

- [ ] **10.4 Independent visual review** with `design-review:visual-regression-review`: before/after pairs
  per screen, and for each pair a written statement of what improved and which audit finding it answers.
  Findings are fixed or argued in the review document, never acknowledged and deferred.

- [ ] **10.5 Performance check** (spec §7): same seeded account, before and after.

```bash
adb -s emulator-5554 shell am force-stop com.taskplanner
adb -s emulator-5554 shell am start -W -n com.taskplanner/.ui.MainActivity   # TotalTime
adb -s emulator-5554 shell dumpsys gfxinfo com.taskplanner framestats        # after a 20-fling scroll
```

The card's view count per row is the mechanism to check: the flattened `item_task.xml` must measure fewer
views than the baseline's 266-line nesting. Record the numbers; a regression here is a reason to flatten
further, not to accept.

- [ ] **10.6 Commit** the photographs and `docs/UI_REDESIGN_REVIEW.md`'s configuration section.

**Acceptance:** every matrix cell has a file; no finding above 4.5:1 text / 3:1 graphics anywhere; no
control under 48dp; no clipped or truncated text at font 1.3 at 320dp; frame stats no worse than baseline.

---

## Stage 11 — Documentation and evidence

- [ ] **11.1** `docs/DESIGN_SYSTEM.md`: the token tables generated from
  `docs/design/contrast-report.md` (paste the script's markdown, do not retype numbers), the type scale, the
  space and shape scale, every component with a screenshot of itself, and the variant-surface rule with the
  measurement that forced it.
- [ ] **11.2** `docs/UI_UX_AUDIT.md`: append the after-state to each of the eight findings — fixed, and by
  which commit, or explicitly still open.
- [ ] **11.3** `docs/UI_REDESIGN_REVIEW.md`: screen by screen — what changed, which finding it answers, what
  is still open, on which configuration it was checked, and what an emulator cannot stand in for (a
  manufacturer's Documents UI, a third-party PDF viewer, a real finger).
- [ ] **11.4** `README.md`: the design-system section, and the fact that instrumented tests need a device and
  CI only compiles them.
- [ ] **11.5** Re-run the contrast script one final time and commit its report.
- [ ] **11.6** Commit.

---

## Stage 12 — Review, PR, CI, merge

- [ ] **12.1** `superpowers:verification-before-completion` before any claim: run the gates, read the
  output, and quote it.
- [ ] **12.2** Independent code review — `pr-review-toolkit:code-reviewer` on the full branch diff, with the
  spec and this plan attached and the instruction to check adherence, not taste.
- [ ] **12.3** `pr-review-toolkit:pr-test-analyzer` — do the tests cover what changed, and did any assertion
  get weaker.
- [ ] **12.4** `pr-review-toolkit:silent-failure-hunter` — the navigation and popup paths added in S3 and
  S5 especially.
- [ ] **12.5** `architecture-visualization:dependency-impact-analyzer` — the blast radius of the shared
  `colors.xml` / `themes.xml` / `component_styles.xml` files across all 52 layouts.
- [ ] **12.6** Fix or argue every finding, in the commit it belongs to. Findings are not "acknowledged".
- [ ] **12.7** Re-run everything after the fixes.
- [ ] **12.8** Push, open the PR, watch CI to green with the token from `git credential fill` never echoed:

```bash
git push -u origin feat/global-ui-redesign
TOKEN=$(printf "protocol=https\nhost=github.com\n\n" | git credential fill | sed -n 's/^password=//p')
curl -s -H "Authorization: Bearer $TOKEN" "https://api.github.com/repos/Andrew05812/task_planner/actions/runs?per_page=5"
unset TOKEN
```

- [ ] **12.9** Merge only after CI is green on the final head, then verify `master` moved and the branch's
  commits are on it.

---

## 13. Per-screen acceptance criteria

| screen | done means | evidence |
|---|---|---|
| Tasks | header with title + open count; search, sort, new group, archive, work history all reachable; three status chips and a `Filters · n` chip; closed rows show no panel; card is three lines with contextual actions; overdue legible without colour; 320dp budget measured | S2–S4 tests + 22 screenshots |
| Profile | four sections; eight rows, the six that navigate each reaching a real destination and the two that open dialogs doing that; delete confirmation unchanged; first tests in the repo's history | S5 two test classes + 6 screenshots |
| Task editor | six labelled sections; sticky footer; inline errors; eleven repeat presets render; PR #21's fix intact | S6 + `TaskEditor*Test` green |
| Bottom bar | five labels; pill + weight mark the active item; surface distinct from the screen | S7 test + 7 screenshots |
| Categories / Archive / Work history / Settings / Profile selection | tokens, type and components; Archive carries the completed treatment; Settings' five controls unchanged | S8a screenshots |
| Statistics | ink-anchored ramps, ordered heatmap, five health buckets distinguishable without colour, report colours fixed | S8b + three dashboard test classes |
| Goals / Habits / Insights | tokens and includes; four insight answer ids in order; 48dp habit day cells | S8c + `InsightsLayoutTest`, `WorkloadPanelTest` |
| Calendar / Reports | cell states without colour alone; report preview unchanged in content | S8d + `CalendarScreenTest`, `ReportScreenInstrumentedTest` |
| Whole app | every matrix configuration photographed; no AA failure; no sub-48dp target; frame stats no worse | S10 |

## 14. PR completion criteria

All of these, with the command output quoted in the PR body. Nothing counts as done on assertion.

1. `./gradlew --no-daemon --console=plain testDebugUnitTest` green, with the suite **larger** than the
   baseline recorded in S0 and zero disabled, deleted or weakened tests. The diff of test names is in the PR.
2. `assembleDebug`, `lintDebug` and `assembleDebugAndroidTest` green; lint adds no new finding.
3. The instrumented suite run on the emulator and passing, with the installed APK's sha256 matching the
   built one, or an explicit statement of which classes were not run and why.
4. `python scripts/contrast_report.py --resources app/src/main/res --check` prints `RESULT: pass` with no
   drift lines, and its report is committed.
5. Every screen in §13 has a before and an after photograph at the same configuration, and
   `docs/UI_REDESIGN_REVIEW.md` explains each pair.
6. The Tasks header reaches search, sort, new group, archive and work history — each demonstrated, not
   described.
7. The swipe panel is invisible on a closed row in a photograph and in `TaskCardClosedPanelTest`.
8. No `data/` file changed: `git diff --name-only master...HEAD | grep '^app/src/main/java/com/taskplanner/data/' || echo "clean"`.
9. No new dependency: `git diff master...HEAD -- '*build.gradle*'` is empty.
10. The M2 and platform sweep is empty across **every** resource directory and the Kotlin sources, not just
    `res/layout` — a hex in `res/values/colors.xml`, a drawable tint or a `Context.getColor(R.color.red)`
    is the same debt in a place the first grep could not see:
    `grep -rn "Widget.MaterialComponents\|?android:attr/textColor\|@dimen/text_" app/src/main/res app/src/main/java`
    is empty, or every survivor is named and justified.
11. Independent code review and independent visual review both ran, and each of their findings is either
    fixed in a named commit or argued in the review document.
12. CI green on the final head, merged, `master` verified to contain the branch's commits.
13. The final report states, per stage: COMPLETE / PARTIAL / BLOCKED, with the reason.

## 15. Risks, and how each is detected early

| risk | detection | mitigation already in the plan |
|---|---|---|
| A theme attribute in S1 does not exist in Material 1.11.0 | aapt at 1.11 | delete that one line; the layouts do not depend on it |
| A `letterSpacing` written in dp rather than em silently widens every pill and date | `TypeScaleTest`'s tracking assertion | the em values are in spec §3.3 and the test pins them |
| Two legacy text attributes mapped to one style | `StatisticsLayoutTest` counts distinct styles the theme resolves | 1.5 maps the five to five, and `TypeScaleTest` asserts the same |
| A shared style or token breaks a screen no stage touched | `assembleDebug` plus the full suite at every stage boundary | the theme keeps every legacy name as an alias, so nothing fails to resolve |
| `RuntimeEnvironment.setQualifiers("night")` not accepted | 1.3's dark class | fall back to `@Config(qualifiers = "night")`; record which worked |
| A layout breaks at 320dp that no test covers | 4.2, 10.2 | the budget test exists before the layout is written |
| The 320dp budget does not actually close | 4.2 fails on real measurements | step 4.9: stop and amend the spec; never shrink a target |
| A colour-locked test fails in S8b for a reason nobody predicted | the stage's own test run | the test is rewritten to the new truth in the same commit, and the message says which |
| `WorkAdjustmentUiTest` flakes again (mainline run #55) | any full-suite run | `superpowers:systematic-debugging` to root cause; never a weakened assertion |
| Emulator instability / ANR storm (this happened during PR #21's final run) | `adb shell pidof`, empty screenshots | record the blocker and the reproduction command; do not claim the check |
| Popup contents invisible to Espresso on this device | 3.1's instrumented test | assert effects through the view model; photograph the presses, as PR #21 did |
| One giant commit | `git log` review before the PR | one commit per stage, each independently buildable and revertable |
| Token drift between the table and the resources | `--resources --check` at S1, S8, S10 | the JVM contrast tests fail the build too |
| Redesign used as cover for a behaviour change | criterion 8 (`git diff` on `data/`) | the diff is quoted in the PR body |

## 16. Stop and rollback

Stop and ask rather than proceed when any of these is true:

- a test that must move has no obvious new truth (that means the design decision is missing, not the test);
- the 320dp budget fails on measured values (step 4.9);
- a screen turns out to need a state, filter or group the data model cannot answer (spec §10 forbids
  inventing one);
- the emulator cannot produce a configuration the acceptance list requires;
- CI fails on a stage commit in a way the local run does not reproduce.

Rollback is per stage: every stage is one commit and every commit builds and passes on its own, so
`git revert <stage>` returns a working app with the earlier stages intact. No stage may be split across
commits, and no two stages may share a commit — that is what makes reverting possible at all. Never
force-push, never rewrite the branch's published history.

## 17. Requirement coverage

Every spec requirement mapped to the step that implements it, so nothing is silently dropped.

| spec | requirement | step |
|---|---|---|
| §0 | display-only Kotlin changes; no data/domain change | 4.6, 8 rule, PR criterion 8 |
| §0 | presentation computation conditions | 4.1 (`CardPresentation` + its tests) |
| §0 | injected clock | 4.6 |
| §1 | four statuses only, three chips | 4.4, 8 rule (no fourth chip added anywhere) |
| §1 | overdue is not a state | 4.1 |
| §1 | topic grouping unchanged | 4.4 (group header restyled in S4/8, not regrouped) |
| §1 | no Achievements row | 5.4 |
| §1 | `avatarUrl` not rendered | 5.4, 1.8 (`AvatarView` keeps initials) |
| §2.1 | panel invisible when closed | 2.1–2.4 |
| §2.2 | FAB is not a defect; gutter derived from the same dimens | 1.7 (`list_bottom_gutter` + test) |
| §3.1 | tokens in both themes | 1.4 |
| §3.1 | M3 role mapping | 1.5 |
| §3.1 | chart ramps re-anchored with the dashboard; report colours left fixed | 1.4 leaves them, 8b moves them |
| §3.2 | contrast measured, not asserted | 1.2, 1.11, 10.1 |
| §3.2 | variant-surface rule | 1.2's `text_on_surfaces` split, 1.8's pill styles |
| §3.3 | type scale, system font, tracking in ems | 1.6, and `TypeScaleTest`'s tracking assertion |
| §3.4 | space and shape | 1.7, 1.8 |
| §3.5 | styles vs includes vs bind-time code | 1.8, 1.9, 4.1 |
| §3.5 | all six includes exist and are used | 1.9 (six), 3.7 (selection bar), 8a/8c (empty, loading, error) |
| §4.1 | header makes four features reachable | 3.4 |
| §4.1 | one scrolling rail, three status chips, `Filters · n` clearing all | 3.6 |
| §4.1 | three-line card, one ConstraintLayout | 4.4 |
| §4.1 | contextual action visibility | 4.1's flags, 4.2's test, 4.5 |
| §4.1 | completed treatment scoped | 8a (Archive), not Tasks |
| §4.1 | 320dp budget, and the 48dp category rule | 4.2, 4.9 |
| §4.1 | selection mode replaces the header | 3.7 |
| §4.2 | Profile IA, eight rows, six of them navigating | 5.4, 5.5 |
| §4.3 | editor sections, sticky footer, ids | 6.2, 6.3 |
| §4.4 | bottom bar treatment, no inset work | 7.1, 7.2 |
| §4.4 | remaining screens onto the system | 8a–8d |
| §5 | motion rules | 9.1–9.5 |
| §6 | a11y and the matrix | 10.1–10.3 |
| §7 | performance, no new dependency | 10.5, PR criterion 9 |
| §8 | baseline preserved, tests moved honestly | 0.1, §14 items 1–2 |
| §8 | new fragment-level coverage incl. Profile | 5.1, 5.2, 3.1 |
| §9 | one commit per layer, in order | every stage's final step |
| §10 | out of scope respected | §3, the Stage 8 rule, §14 criteria 8 and 9 |

## 18. Commit sequence

| # | stage | commit |
|---|---|---|
| C1 | 0 | `stage 0: the before-evidence, and eight problems the redesign has to answer` |
| C2 | 1 | `stage 1: the design system the app did not have` |
| C3 | 2 | `stage 2: the swipe panel no longer shows on a closed row, and a test pins the geometry` |
| C4 | 3 | `stage 3: a Tasks header, which is how four features get reached` |
| C5 | 4 | `stage 4: the task card, three lines and a measured budget` |
| C6 | 5 | `stage 5: Profile as a screen, and its first tests` |
| C7 | 6 | `stage 6: the editor regrouped, the same fields and the same validation` |
| C8 | 7 | `stage 7: the bottom bar reads as a bar` |
| C9 | 8a | `stage 8a: Categories, Archive, work history, Settings and profile selection onto the system` |
| C10 | 8b | `stage 8b: the dashboard's charts, re-anchored, with their tests moved in the same commit` |
| C11 | 8c | `stage 8c: Goals, Habits and Insights onto the system` |
| C12 | 8d | `stage 8d: Calendar and Reports onto the system` |
| C13 | 9 | `stage 9: motion that answers to data changes and not to scrolling` |
| C14 | 10 | `stage 10: the accessibility and adaptivity matrix, and what it found` |
| C15 | 11 | `stage 11: the design system written down, and the before/after evidence` |
| C16 | 12 | review fixes, one commit per finding where a finding spans stages |

Sixteen commits across thirteen stage headings (S0 captures evidence and changes nothing; S1–S12 change
the app; S8 is four commits). Each one builds, tests and photographs on its own, which is what makes
`git revert <stage>` a real option rather than a theory.

---

## 19. What this plan deliberately does not do

It does not migrate to Compose, add a dependency, render the stored avatar, touch the schema or a
repository, add a Completed filter, add a date-based group, build the Achievements screen, change when a
task archives, or claim any of it happened on a physical handset. Spec §10 lists the same boundaries; the
reason they are repeated here is that a plan is read by someone who wants to be helpful, and the most
damaging failure mode for this stage is a useful-looking addition nobody asked for.

## 20. Independent review of this plan, and what came of it

Two reviewers read the plan and the spec against the source, independently, before any of it was executed.
Their findings are recorded here with their disposition, because a plan that has been reviewed should show
what the review cost it.

| # | finding | severity | verified? | disposition |
|---|---|---|---|---|
| 1 | The 320dp budget does not close: 320 − 16 − 32 − 52 = 220, not 208; the plan's own test computed a 64dp column while its prose said 52 | BLOCKER | yes, recomputed | spec §4.1 and plan 4.2 now state one column: 48 + 4 + 4 + 8 = 64 → 208. The "shared column saves 12dp" claim was false and is gone |
| 2 | `android:letterSpacing` is in ems, so the type table's `0.4` meant 40 % of an em and an all-caps pill nearer 143dp than 102 | BLOCKER | yes | spec §3.3 now gives sp intent and em value per role, adds a normal-case `pill` role at 82dp, and `TypeScaleTest` asserts the em values |
| 3 | The due-date allowance ignored the card's own `MMM dd, yyyy` fallback, which is wider than "Today 18:00" | MAJOR | yes | allowance raised to 84dp against the widest legal string; line B's arithmetic restated |
| 4 | `StatisticsLayoutTest` requires five legacy text attributes to resolve to five **distinct** styles; 1.5 mapped `Body1` and `Body2` to one and never set `Headline5` | MAJOR | yes, read at :636-663 | spec §3.3 gains the `caption` role; 1.5's mapping is now five-to-five; `TypeScaleTest` asserts it so the next collision is caught in the build |
| 5 | The chart ramp was re-anchored in stage 1 by one section and in 8b by another, and `StatisticsLayoutTest` asserts an invariant (one RGB, rising alpha) the token stage would have broken | MAJOR | yes | 1.4 now leaves `chart_*` and `health_*` alone; 8b owns the re-anchor and states the invariant; spec §3.1 corrected |
| 6 | `@dimen/text_*` is not unused — `fragment_report.xml:244` reads `text_caption`, so deleting it in S1 breaks the build | MAJOR | yes, grep confirms one reader, a layout not a test | deletion moved to 8d.4 with the grep that proves retirement; audit finding 7 corrected |
| 7 | The before-matrix was too small to satisfy the plan's own pairing acceptance: no 360dp shots, no dark/320/1.3 for Profile or the editor | MAJOR | yes | 0.4 extended to 28 files with the paired configurations, and the missing `mkdir` step added |
| 8 | Spec §4.1's filter rail and `Filters · n` had no implementing step; `.SelectionBar`, `.LoadingState` and `.ErrorState` were built and never used | MAJOR | yes | 3.6 (rail, three chips, the clearing chip) and 3.7 (selection bar) added with tests; 1.9 now writes all six includes |
| 9 | `work_start_input` / `work_end_input` exist nowhere; the real ids are camelCase and live in `dialog_work_interval.xml` | MINOR | yes, grep empty | 6.2 corrected to the real names and file; spec §4.3 corrected, since the error came from there |
| 10 | 6.1 listed four order assertions; the test has seven, three of which the new section order inverts | MINOR | yes | 6.1 is now a table of all seven with a survives? column, so the inversions are decided on paper |
| 11 | `buttonGroup` is bound by `TasksAdapter` and was silently replaced by a new `actionGroup` id | MINOR | yes | 4.4 keeps `buttonGroup`; the invented id is gone |
| 12 | `profileCard` and `contentGroup` were not in the keep-list, and `contentGroup` is asserted from another screen's test | MINOR | yes | 5.4 explains both: `contentGroup` survives because `TasksScreenInstrumentedTest:56` checks it; `profileCard` is dropped because nothing binds it |
| 13 | "row_list is included six times" contradicted the eight rows Profile actually has | MINOR | yes | corrected to eight includes, six of which navigate |
| 14 | `ic_stopwatch` / `ic_timer` were referenced as if one existed | MINOR | yes, 42 icons, none clock-shaped | 4.5 adds `ic_work.xml` as a new vector and says why reuse is not an option |
| 15 | `daysBetween` divided by 86 400 000, which a DST day breaks | MINOR | yes | rounded over local midnights, with the reason in the comment |
| 16 | Acceptance lines phrased as qualities ("legible in dark", "became slow", "without colour alone") | MINOR | yes | rewritten as dumps, measured contrasts and named tests in §13, S7, S8b, S9 |
| 17 | §8's inventory named `ContrastResourcesTest.kt`, which §1.2 does not write | MINOR | yes | inventory now lists `Wcag.kt`, `AbstractContrastTest`, `ContrastLightTest`, `ContrastDarkTest` |

One review finding was **not** accepted. Reviewer B reported that `TaskCardWorkTest`'s assertion
`taskTitle.parent == workRow.parent` would break under the three-line card. Read against the layout the
plan actually describes, it would not: three lines inside **one** `ConstraintLayout` keeps both views
children of the same parent, which is exactly what that assertion checks. The finding is nonetheless
load-bearing, because it is only true under that structure — so the sibling relation is now written into
spec §4.1 as a layout constraint and asserted in `TaskCardWidthBudgetTest`, which turns a plausible
false-positive into the reason a future refactor cannot quietly wrap each line in its own container.

Both reviewers independently confirmed the things the plan most depends on: the thirteen-callback adapter
constructor, every preserved view id, the Safe Args directions, the Archive-and-work-history single entry
point, the absence of any Toolbar, the reference-locking of the four colour tests, the `TimeBase` seam and
its Hilt provider, the overdue predicate against `TaskStatisticsDao`, the baseline counts, and that every
pair §1.2 asserts clears its bar at the measured values.

### 20b. Second pass, after the fixes

A third reviewer read the corrected pair and found that fixing seventeen defects had introduced more, and
that one original finding had been missed entirely. Its dispositions:

| # | finding | severity | disposition |
|---|---|---|---|
| b1 | At 320dp and font scale 1.3 the pill (~102dp) plus the widest date (~109dp) plus gaps is 227dp against a 208dp column, so "never drop, never shrink", "line B is one line" and "verified at 1.3" cannot all hold | BLOCKER | spec §4.1 now says the card **grows**: line B reflows at large scale, and the one-line rule is scoped to the default scale. 4.2 gains a test asserting the reflow branch — type grew, card taller, date and status word still whole |
| b2 | 4.2 asserted only 208 and 64; the published per-line figures (152 / 182 / 26 / 56) were never restated anywhere a build could check them | MAJOR | 4.2 gains `each line keeps to its share of the column` asserting all four against the measured row |
| b3 | "content is the card less its padding" only holds if the 16dp lives on `cardContent`, which nothing stated | MAJOR | 4.4 now fixes the padding to `cardContent`, with the ripple-clipping reason for not putting it on `taskCard` |
| b4 | The plan claimed `TaskCardWorkTest` was unaffected while the spec listed it among tests that move, and neither recorded the three view-**type** locks the tests depend on | MAJOR | 4.4 records the `LinearLayout` / `ImageButton` / `ImageView` casts with their line numbers; 4.10's acceptance states which assertion is at risk and why keeping the types is what saves it |
| b5 | `state_loading.xml` and `state_error.xml` were created and used by nothing, and §17 claimed otherwise | MAJOR | new step 8.4b names the consuming screen for each of the three state includes, and says what to report if one is still unused |
| b6 | §7's graph let 8a run before S5 created `row_list`, though Stage 8's own text depends on S5 and S7 | MINOR | hard rule added; the diagram stays simple and the rule is the accurate statement |
| b7 | 4.5's bind list omitted three rules the spec states: the handle dropping away from `MANUAL`, the figures collapsing before the buttons, and the completion toggle being the only emphasised control | MINOR | all three written into 4.5, including the 52dp the title regains when the handle goes |
| b8 | 6.1's table marked the `button_container` assertion as surviving and then explained it must be rewritten | MINOR | marked **no**, with the replacement assertion named |
| b9 | Stale numbers survived the first round: 70dp / 102dp in 4.9, "seven roles" and "eighth role", "four new includes", "16 screenshots", the ramp credited to 1.4 in §17 | MINOR | all corrected; the Tasks evidence count is 22 |
| b10 | False facts introduced by the fixes: `buttonGroup` "bound by TasksAdapter today" (it is bound by nothing — it is a layout id), 41 icons (there are 42), "five layouts use ExposedDropdownMenu" (five uses across two layouts), "five heat levels sharing one RGB" (four, plus a distinct `none`) | MINOR | each corrected at the source, and the spec's ramp sentence with them |
| b11 | `TypeScaleTest`'s tracking tolerance of 0.004 was wider than the 0.019 / 0.021 separation it polices | MINOR | tolerance 0.0015 |
| b12 | The `TypeScaleTest` step was a malformed, unnumbered checkbox placed after the step that needs it; §17 cited steps 13.1 and 14.1 that do not exist; 1.2 called the file `Wcag.java` | MINOR | numbered 1.6a and moved before 1.6; coverage rows point at §14 items; filename corrected |
| b13 | Baseline SHA claims disagreed across the document (a9f70e3, 422d7cd, 71d974a) | MINOR | header states the baseline, S0.0 creates the folders, S0's precondition refers to the plan commit rather than a frozen SHA that moves every time the plan is edited |
| b14 | `pill_neutral` is byte-identical to `ink_surface_variant`, so the stated reason for it being its own token was vacuous | MINOR | spec §3.1 now gives the real reason: a pill is painted with a pill token, not with a surface token borrowed for the job |

The second pass also confirmed, with its own arithmetic, that the corrected budget closes: 320 − 16 − 32 −
64 = 208, line A 56 fixed leaving 152 for the title, line B 182 fixed leaving 26 for a name that is therefore
hidden at 320dp, line C 152 leaving 56 for the figures — and that the pill's 82dp is plausible for Roboto
Medium at 11sp with 0.045 em of tracking (58dp of glyphs, 5.4dp of tracking, 16dp of padding ≈ 80dp). It
re-ran the contrast gate and confirmed every figure the spec quotes, in both themes, including the neutral
pill pair.

### 20c. Third pass: a user audit of the downloaded documents

The user read the committed files and found five problems, of which the first three were real defects in
Kotlin that had been written as if it were another language, and the fourth was a verification that did not
verify. All five are fixed; each was checked against the toolchain or the device rather than by eye.

| # | problem | fix | proof |
|---|---|---|---|
| c1 | The spec header still read "Revision 3" while the body carried revisions 4–7, and the plan cited revision 7 | header replaced with a revision table whose rows carry the commits each revision actually landed in | `git log --format=%H -- <spec>` gives `17492db 4b3ba11 7f392d6 ea3fff0 a9f70e3 422d7cd ee75e61` for revisions 1–7 |
| c2 | `Wcag` used `x ** 2.4`. Kotlin has no exponent operator; the snippet would not compile | `.pow(2.4)` with `import kotlin.math.pow`, and a line in the doc comment saying why | Kotlin 1.9.22 has no `**`; `Double.pow(Double)` is `kotlin.math.pow` |
| c3 | `ListGutterTest` used an undeclared `context` and compared pixels against `48.dp` — a Compose idiom in a View project | context obtained from `ApplicationProvider`, `pxOf(dp)` through `displayMetrics.density`, no Compose, imports listed | `getDimensionPixelSize` returns px; the project has no Compose dependency to import `.dp` from |
| c4 | The font-scale test asserted `measuredText - available == 0`, i.e. that text fills its box, and compared `row.height` against `taskCard.height` — two levels of one hierarchy | rewritten to `needed <= available` for the date and the status word, card height against card height, and the "no clipping" claim made through `ellipsize`/layout text rather than equality | a row with a short date in a wide box is correct and would have failed the old assertion |
| c5 | The APK check hashed the local file and then read `lastUpdateTime` and `pm path`, which proves nothing about identity | a build → install → `pm path` → on-device `sha256sum` → `pull` → local hash → three-state PASS/FAIL/BLOCKED procedure, with `BLOCKED` named as a legitimate outcome | run on `emulator-5554` at `0abd9cb`: `base.apk` is `-rw-r--r-- system system`, pull succeeded, and all three hashes were `28e898e91ad7a18a505b4555f0026dba138463d497e944ccb10ce9a7ad9600f7` — a measured PASS, not an inferred one |
| c6 | Motion was specified as "animate in `onBindViewHolder`, but not on scroll", which is not implementable: bind runs on every rebind from the pool | Stage 9 rewritten around `DefaultItemAnimator.animateAddImpl` for inserts and a narrow `getChangePayload` for the pill crossfade; spec §5 amended to match | RecyclerView calls `animateAdd` for inserted holders and no animator method for rebound ones |
| c7 | The plan asserted "Android applies the animator scale automatically" | verified against the actual artifact: `androidx.recyclerview` 1.3.2 contains no `isAnimatorDisabled` and no `getDurationScale` reference, so the framework animators merely finish instantly while the setup work still runs — the hand-written path must check the scale itself | `unzip` of `recyclerview-1.3.2.aar` from the Gradle cache and a search over its `classes.jar` |
| c8 | `TypedValue.px2sp(displayMetrics, px)` — arguments reversed; and four snippets used `resources` / `density` that the enclosing class never declared | superseded by §20d: the argument order was fixed onto a method that is **@hide** and does not exist in the public SDK, so the snippet still did not compile; `scaledDensity` is the real route | `javap android.util.TypedValue` on android-34: `px2sp` absent, `applyDimension` present |
| c9 | "Twelve stages, sixteen commits" undercounted the stage headings | §18 now states thirteen headings, S0 evidence-only and S8 split four ways | counted against the stage bodies |

Two things this round did **not** change, deliberately: the budget arithmetic and the contrast table, both
of which the second pass re-derived independently and which the script reproduces on every run.

### 20d. Fourth pass: two reviewers who checked the fixes against artifacts, not against the text

Reviewer A judged plan and test quality; reviewer B judged Android feasibility. They worked independently
and their findings do not overlap on anything important. Several of the fixes in §20c were themselves wrong,
and one was wrong in a way that would have stopped the build on the first try.

| # | finding | severity | evidence | disposition |
|---|---|---|---|---|
| d1 | `Wcag` still used a `**` exponent after §20c claimed it fixed one | BLOCKER | Kotlin 1.9.22 has no such operator | `.pow(2.4)`; the survivor was in a second snippet, which is the lesson — fix every occurrence, not the reported one |
| d2 | `TypedValue.px2sp` is not public SDK — §20c "corrected the argument order" of a hidden method | BLOCKER | `javap android.util.TypedValue` on android-34: `px2sp` absent, `applyDimension` present | `textSize / resources.displayMetrics.scaledDensity` |
| d3 | `animateAddImpl` is package-private in the recyclerview this APK actually resolves | BLOCKER | manifest-merger report: `MERGED from [androidx.recyclerview:recyclerview:1.1.0]`; `javap` shows `void animateAddImpl(...)` and `public boolean animateAdd(...)` | §9.0 rewritten over `animateAdd`, using `dispatchAddStarting`/`onAddFinished`, both public in 1.1.0. §20c's proof inspected 1.3.2, a library not in this build |
| d4 | 4.2's `content.width == card.width − 2·padding` cannot hold while 4.4 puts the padding on `cardContent`, because `getWidth()` includes padding | BLOCKER | Android view geometry | the test subtracts `content.paddingLeft/Right` from `content.width` |
| d5 | The font-scale test could not fail: `due.top >= pill.top || pill.top >= due.top` is true for any two integers; `layout == null` short-circuited the ellipsis check; `"%1.f"` throws `UnknownFormatConversionException` before any assertion runs | BLOCKER | reproduced on JDK 11 | tautology deleted; overflow asserted against the content's right edge; messages use Kotlin interpolation only |
| d6 | Under Robolectric's default LEGACY graphics `ShadowPaint.measureText` returns `text.length()` scaled and ignores `textSize`, so every fit assertion in 4.2 measured nothing | BLOCKER | `javap -c` of `ShadowPaint` in `shadows-framework-4.11.1.jar` | `@GraphicsMode(NATIVE)` on the class, with the reason in the comment and a stated fallback to device measurement |
| d7 | `ContextThemeWrapper` does not override `createConfigurationContext`, so the font-scale fixture silently lost `Theme.TaskPlanner` | MAJOR | `javap android.view.ContextThemeWrapper` | `ContextThemeWrapper(app.createConfigurationContext(cfg), theme)` |
| d8 | 1.4's alias block would have been appended to names aapt already has declared | MAJOR | `colors.xml:4-25` declares all sixteen | rewritten as an in-place edit with the overwritten names listed |
| d9 | The ramp moved from inside stage 1 by aliasing, invisibly: `chart_bar=@color/primary`, `chart_completions=@color/status_done`, `health_overdue=@color/error`, `health_in_progress=@color/status_in_progress` | MAJOR | `colors.xml:46,51,62,69` | the ramp gets `chart_base_accent`/`chart_base_finished` and written-out health colours — values unchanged, indirection added; 8b owns the colour |
| d10 | Stage 7's acceptance was defeated by stage 1: `colorSurfaceContainer` mapped to `ink_background`, the same colour as `android:colorBackground`, so "differs by ≥1.3:1" fails by construction | MAJOR | 1.5 as written | `colorSurfaceContainer` → `ink_surface`, and the bar's check named as a resolvable assertion |
| d11 | RED steps naming a new `R.id`/`R.style` cannot run at all — Kotlin resolves those at compile time, so "watch it fail" was a build error | MAJOR | `R.id.cardContent` in 4.2 with 4.3 predicting a test failure | a rule in §3: skeleton commit first, then a genuine red; or read resources reflectively, as 1.2 already does |
| d12 | `android:textColorSecondary` is used by twenty layouts and is not a token, so no gate could see it between stage 1 and 8d | MAJOR | grep count verified | mapped to `ink_text_secondary` in both themes |
| d13 | `layout_marginVertical` is API 26 on an API 24 floor: the swipe fix returns silently on 24–25 and the SDK-34 test cannot see it | MAJOR | attribute availability; no `UnusedAttribute` entry in the lint baseline | explicit top and bottom margins, and the same for the card's existing `marginHorizontal` |
| d14 | ViewBinding paths were flattened where the layout uses an id'd include, and the selection-bar include would have declared `selectionBar` twice in one tree | MAJOR | ViewBinding semantics | nested `binding.tasksHeader.…`; the include owns the id and the card keeps none |
| d15 | 8a claimed "none assert these layouts" while `WorkHistoryLayoutTest:67` asserts `historyList.parent === root`, and 8c's empty state must keep `paddingLeft >= 24dp` or the assertion gets relaxed | MAJOR | both lines read | the ledger names each with file:line and what it forbids |
| d16 | `ListGutterTest` compared two dimens nobody renders while `fragment_tasks.xml:153` hard-codes `88dp` | MAJOR | grep | new step 1.7a points the layout and the FAB at the dimens, with the grep as the gate |
| d17 | The variant-surface rule had no verifier, and 1.5 maps two container roles onto that surface | MAJOR | both gates measure token pairs only | new step 10.1a walks inflated layouts and records unresolvable backgrounds in a reviewed exception list |
| d18 | The device is `540x1140 @ 220`, not `1080x2280 @ 440`; the density table would have produced the wrong widths and half-resolution evidence | MAJOR | measured at `d1839a8` | §0.2 rewritten: force the panel, record Physical and Override lines, reset afterwards |
| d19 | Prose that overclaimed: "smallest possible diff" for ten Profile listeners; `MainActivity.kt` listed with no step; §9.2 crediting the wrong library | MINOR | each read against the source | corrected in place |

Confirmed fixed by the reviewers: the revision header now matches its history until this commit, which adds
revision 8 and says so; the APK identity procedure reproduces a real `PASS`; `ListGutterTest` compiles; the
`Wcag` luminance maths is right; and the budget arithmetic and the contrast tables hold.

Rejected with evidence: reviewer A's reading that the card's overdue predicate differs from the dashboard's
does not hold at the population level — `healthCounts` restricts its `WHERE` to `is_deleted = 0 AND
archived = 0 AND UPPER(status) NOT IN ('COMPLETED','DONE')` (`TaskStatisticsDao.kt:76-77`), which is the
population the active list is built from, so the card adds nothing to the rule. The finding still earned
something: spec §4.1 now states the whole predicate rather than the `CASE` fragment, and a test covers the
row where the two could drift apart.
---

**Document SHAs.** Spec revision 7: `ee75e61b95e671a2ccd753180f6a20a5913e9bdc`. This plan: the commit that adds it, recorded in
`docs/superpowers/plans/` history and reported to the user with the plan.
