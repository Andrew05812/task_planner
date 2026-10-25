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

**Spec:** `docs/superpowers/specs/2026-10-23-global-ui-redesign-design.md`, revision 9. Its SHA is
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

Each figure below was read or run at `a9f70e3` on 2026-10-23, re-checked at `d1839a8` on 2026-10-24
by two reviewers working from the source, and re-checked a third time at this commit. The instrumented
column is the one that has since changed status: Stage 0.1a **ran** it, and §20h records what came back —
including that two of the rows below describe the suite as it stood before that run. The spec baseline
this plan is written against is revision 9.

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
| `contentGroup` was asserted from another screen's test | As at `a9f70e3`: `TasksScreenInstrumentedTest` checked `R.id.contentGroup` was displayed after a round trip through Statistics, and that id exists only in `fragment_profile.xml:19` and `fragment_category_detail.xml:15`. Superseded by 0.1a, which proved it on the device and replaced the assertion — see §20h. |
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
- Every **complete-file** Kotlin block in this plan has been **compiled**, not read: 1.9.22 against
  `android-34`, the recyclerview 1.1.0 the APK resolves and the app's own classes. §20f records the harness,
  what it proves (syntax, imports, API signatures, override validity, cross-file references inside one
  package) and what it cannot prove (resource names are stubbed from each snippet's own references, so their
  existence is still this plan's ordering responsibility, and no layout XML is parsed). Re-run it after
  editing a snippet. Blocks that print a **method body** rather than a whole file — 3.1's readers, 3.6's rail
  test — cannot be compiled as printed, so those steps name the file and state its import list instead, and
  §20g records the two times that distinction was got wrong: a `/* comment */` in an expression position and
  a `dp(40)` written against a file whose helper is `Int.dp()`.
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
./gradlew --no-daemon --console=plain testDebugUnitTest           # 1406 JVM tests (100 suites, measured after stage 2's gesture fix)
./gradlew --no-daemon --console=plain lintDebug                   # must not add findings
./gradlew --no-daemon --console=plain assembleDebugAndroidTest     # instrumented compile gate (CI)
./gradlew --no-daemon --console=plain testDebugUnitTest --tests "com.taskplanner.ui.tasks.*"
python scripts/contrast_report.py --check --format markdown --out docs/design/contrast-report.md
python scripts/contrast_report.py --resources app/src/main/res --check
```

**Pin every instrumented run to one device.** `connectedDebugAndroidTest` fans out to *every* transport
`adb devices` lists, and this workspace has had three at once: two emulators and the physical handset
(`WCLJBINVEAT4PVGM`), which holds a real profile. The runner also clears the target package's data on each
device it runs (`clearPackageData`, `app/build.gradle.kts:29`). So the serial is not just an `adb` argument —
it is the difference between testing on a scratch emulator and wiping a person's phone:

```bash
export ANDROID_SERIAL="$S"        # Gradle reads this; `adb -s "$S"` alone does not filter the task
```

Device: **resolve the serial, never hard-code it.** An earlier draft of this page asserted "emulator-5554 is
live; API 35 x86_64" and every `adb` line below carried that serial, which is a claim about a moment, not a
procedure — and by the time this pass re-checked it, `adb devices -l` no longer listed `emulator-5554` at
all, only a physical handset. Both are recorded, because the evidence in §6 was really produced on the
emulator while it was live.

**This project owns one AVD: `taskplanner_avd`, on a fixed port.** Instrumented work must not run on an
emulator that belongs to another project. `befos_avd` (`emulator-5554`) is driven by the user's BeFoS/VKR
sessions, and borrowing it does two bad things at once: it interferes with work that is not this branch's, and
it makes this project's own evidence untrustworthy — §20j records a foreign `uiautomator dump` holding the
single `UiTestAutomationService` slot and another app sitting in `mCurrentFocus`, both of which produced
failures that looked exactly like app bugs. `taskplanner_avd` was created for this repository at the reference
display metrics of the before-matrix, so before/after pairs stay comparable while nothing is shared:

```bash
# once, if the AVD is missing (avdmanager asks whether to create a sdcard; "no" is fine)
echo no | "$SDK/cmdline-tools/latest/bin/avdmanager" create avd -n taskplanner_avd -k "system-images;android-35;google_apis;x86_64" -d pixel_5
# then pin the metrics the baseline was taken at: 540x1140 @ 220 dpi = 393x829 dp
sed -i 's/^hw.lcd.width=.*/hw.lcd.width=540/; s/^hw.lcd.height=.*/hw.lcd.height=1140/; s/^hw.lcd.density=.*/hw.lcd.density=220/' ~/.android/avd/taskplanner_avd.avd/config.ini

# every session: boot it on the same port, so the serial never moves
nohup "$SDK/emulator/emulator" -avd taskplanner_avd -port 5584 -no-snapshot-save -no-boot-anim -gpu auto &
adb -s emulator-5584 wait-for-device
```

**Two arbitration checks, added from Stage 2's debug session (§20j).** An instrumented run that fails on a
shared machine can be failing for reasons that have nothing to do with the code, and the two signatures are
cheap to rule out before reading the app:

```bash
adb -s "$S" shell dumpsys accessibility | grep -i "ui automation"   # a line here = someone else holds it
adb -s "$S" shell dumpsys window | grep mCurrentFocus               # a foreign package = focus is not ours
```

`UiAutomationService already registered`, `Cannot call disconnect() while connecting`,
`RootViewWithoutFocusException`, a refused `takeScreenshot()` and input that moves nothing are all one
symptom of the same thing: the AVD carries a single `UiTestAutomationService` slot and one focused window. A
freshly booted AVD adds its own case — the launcher ANRs during first boot and the dialog keeps focus until
BACK or HOME dismisses it, which is exactly what the first smoke run on `taskplanner_avd` hit before it passed
7/7. Nothing in another session's app or data is ever stopped, cleared or disabled to free a device: this
project uses its own AVD or waits. And any
horizontal gesture injected by a test or a script has to start **inside** the target view: on
`navigation_mode=2` a drag that begins in the screen's edge band is the system's Back gesture, which is how
a real defect in the task row stayed hidden behind an apparent emulator misbehaviour for two stages.

```bash
adb devices -l
S=emulator-5584        # taskplanner_avd, this project's own AVD — never "whichever device is first"
adb -s "$S" emu avd name | head -1 | grep -q '^taskplanner_avd$' || { echo "not this project's AVD"; exit 1; }
echo "$S"; adb -s "$S" shell getprop ro.build.version.sdk; adb -s "$S" shell getprop ro.product.cpu.abi
adb -s "$S" shell wm size; adb -s "$S" shell wm density
adb -s "$S" shell settings get system font_scale
adb -s "$S" shell settings get global animator_duration_scale
```

| as measured at this pass | the emulator the §6 proof ran on | the device attached now |
|---|---|---|
| serial | `emulator-5554` | `adb-WCLJBINVEAT4PVGM-8QzfSF…` (adb-over-TLS) |
| API / abi | 35 / x86_64 | 35 / `arm64-v8a,armeabi-v7a,armeabi` |
| size, density | 1080x2280 at 440dpi | 1220x2712 at 480dpi |
| width in dp | 393dp | ≈407dp |
| font scale | 1.0 | **0.81** |
| animation scales | 1.0 | **0.5 window / 0.5 transition / 0.5 animator** |

Two consequences the stages have to respect, and neither is cosmetic: the width matrix below must be
recomputed per device from `density = px_width / dp_width * 160` (1080px panel → 440/480/540dpi for
393/360/320dp; a 1220px panel → **496/542/610**), and a handset sitting at font scale 0.81 with animation
scales at 0.5 is not the configuration the adaptivity and motion stages claim to test — so 9.5 and 10.x set
`font_scale` and the three animation scales explicitly and read them back, rather than inheriting whatever
the device was left in. Every `adb` line in this plan means `adb -s "$S"`.

```bash
# width matrix — dp = px_width / (density / 160); substitute this device's px width
adb -s "$S" shell input keyevent KEYCODE_WAKEUP
# 540px panel (the emulator §20h measured): density alone does the dp work — 220 / 240 / 270.
adb -s "$S" shell wm density 220    # 393dp
adb -s "$S" shell wm density 240    # 360dp
adb -s "$S" shell wm density 270    # 320dp
# A 1080px panel would use 440 / 480 / 540 for the same three widths. Never carry a pair across devices:
# recompute from whatever `wm size` reports.
adb -s "$S" shell settings put system font_scale 1.3     # and 1.0 to restore
adb -s "$S" shell cmd uimode night yes                   # and `no` for light
adb -s "$S" shell pidof com.taskplanner                  # alive check, not a claim
adb -s "$S" exec-out screencap -p > docs/images/ui-redesign/after/01-tasks.png
adb -s "$S" shell uiautomator dump /sdcard/ui.xml && adb -s "$S" exec-out cat /sdcard/ui.xml
```

**`wm size` is not on that list, deliberately.** Overriding the panel size above the emulator's physical
540x1140 — `wm size 1080x2280`, which an earlier draft of this page and of Stage 0.2 told the executor to
force — makes Espresso's injected taps land outside the window: with the override in place,
`TasksScreenInstrumentedTest` clicks `navigation_statistics` and the app stays on Tasks (the failure
hierarchy shows `navigation_tasks` still selected); reset to the physical panel, the same build on the same
device passes 4/4. Density-only overrides leave the pixel space alone, so taps keep mapping, and dp widths
come out the same. The cost is resolution, not correctness: 540x1140 screenshots sample hairlines and 11sp
text at half the linear density of a real handset, and the audit says so rather than pretending otherwise.

Instrumented run (device only; CI only compiles them). **One Gradle invocation per class** — the six do not
share a process safely, and §20h records what was measured and what was not:

```bash
adb -s "$S" shell input keyevent KEYCODE_WAKEUP
export ANDROID_SERIAL="$S"          # the task runs on every connected device without this
# A stage that changed one screen may run only its own class; the full loop is the gate (11.4, and §14.3).
for C in com.taskplanner.data.local.DatabaseLocationInstrumentedTest \
         com.taskplanner.data.report.export.PdfReportInstrumentedTest \
         com.taskplanner.data.report.export.ReportPdfBytesInstrumentedTest \
         com.taskplanner.ui.TasksScreenInstrumentedTest \
         com.taskplanner.ui.report.ReportScreenInstrumentedTest \
         com.taskplanner.ui.tasks.TaskEditorInstrumentedTest; do
  ./gradlew --no-daemon --console=plain connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class="$C" || exit 1
done
# To run one class alone, drop the loop and pass its name:
#   -Pandroid.testInstrumentationRunnerArguments.class=com.taskplanner.ui.tasks.TaskEditorInstrumentedTest
```

Every connected invocation clears the app's data first (`clearPackageData`), so the screenshots taken after
one have to re-apply the fixture:

```bash
adb -s "$S" install -r app/build/outputs/apk/debug/app-debug.apk
adb -s "$S" shell monkey -p com.taskplanner -c android.intent.category.LAUNCHER 1   # Room creates v16
python scripts/apply-evidence-seed.py -s "$S"
python scripts/capture-ui-matrix.py --out docs/images/ui-redesign/after -s "$S"
```

Before trusting any screenshot: prove the installed bytes **are** the built bytes. Hashing the local file
and then reading `lastUpdateTime` proves nothing about identity — that is exactly the check that failed in
the audit, where a stale APK sat on the device. The procedure below was run on this emulator and returned
`PASS`; it is reproduced verbatim.

```bash
# 1. build, then install exactly that file
./gradlew --no-daemon --console=plain assembleDebug
adb -s "$S" install -r app/build/outputs/apk/debug/app-debug.apk

# 2. the path the package manager actually loaded
P=$(adb -s "$S" shell pm path com.taskplanner | tr -d '\r' | sed -n 's/^package://p' | head -1)
echo "$P"

# 3. hash it on the device (the shell user can read it: base.apk is -rw-r--r-- system system)
DEV=$(adb -s "$S" exec-out sha256sum "$P" | tr -d '\r' | cut -d' ' -f1)

# 4. and again after pulling it, in case the on-device hash command is unavailable
LOCAL=$(sha256sum app/build/outputs/apk/debug/app-debug.apk | cut -d' ' -f1)
adb -s "$S" pull "$P" /tmp/installed-base.apk >/dev/null
PULLED=$(sha256sum /tmp/installed-base.apk | cut -d' ' -f1)

# 5. three states, never a fourth
if [ "$LOCAL" = "$DEV" ] && [ "$LOCAL" = "$PULLED" ]; then echo "PASS $LOCAL"
elif [ -z "$DEV" ] || [ -z "$PULLED" ]; then echo "BLOCKED: cannot read the installed APK"
else echo "FAIL local=$LOCAL device=$DEV pulled=$PULLED"; fi
```

Measured on `emulator-5554` while that emulator was live, for reference: `base.apk` is world-readable,
`pm path` returns
`/data/app/~~esTsuNRQ…/com.taskplanner-eBW6J5SvcLEGLbxlF4qlAQ==/base.apk`, and local, on-device and pulled
hashes were all `28e898e91ad7a18a505b4555f0026dba138463d497e944ccb10ce9a7ad9600f7` — a real `PASS`, not an
inference from a timestamp. It is reported as what happened on that device at that commit; the procedure is
what a later stage re-runs, against whatever serial `$S` resolves to.

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
res/drawable/bg_pill_neutral.xml (the TODO pill; see 4.4)                     S1
res/values/anim.xml (motion_standard, motion_entry_max)                       S9
main/.../ui/tasks/CardItemAnimator.kt (new class; 9.0)                                    S9
test/.../ui/tasks/CardItemAnimatorTest.kt (holds 9.2's scale test and 9.1's three payload cases)  S9
res/menu/menu_tasks_sort.xml              res/menu/menu_tasks_overflow.xml                S3
test/.../ui/theme/VariantSurfaceTest.kt (10.1a: inflates each redesigned layout and walks its
  TextViews for the spec §3.2 ban on semantic text over ink_surface_variant)               S10
test/.../ui/tasks/TasksFragmentRenderTest.kt (spec §8's fragment-level Tasks coverage:
  groups drawn, three statuses, overdue treatment, empty state, closed panel)             S4
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
| S1 | `res/values/colors.xml`, `res/values-night/colors.xml`, `res/values/themes.xml`, `res/values-night/themes.xml`, `res/values/dimens.xml`, `res/values/type.xml` (created), `res/values/component_styles.xml` (created). `res/values/styles.xml` is **not** touched here — its two chart text appearances stay where they are until a stage has a reason to move them. The two chip colour state lists, `res/color/chip_background_color.xml` and `chip_stroke_color.xml`, are dropped by stage 8 when the last layout referencing them is migrated, not by stage 1 |
| S2 | `res/layout/item_task.xml`, `main/.../ui/tasks/TasksAdapter.kt`, and the nine other layouts that use `layout_marginHorizontal`/`layout_marginVertical` — see 2.3a |
| S3 | `res/layout/fragment_tasks.xml`, `res/menu/menu_tasks.xml` (split by 3.4 into the two new menu files, then deleted along with its last inflating caller), `main/.../ui/tasks/TasksFragment.kt`, `res/values/strings.xml` |
| S4 | `res/layout/item_task.xml`, `res/layout/item_group_header.xml`, `main/.../ui/tasks/TasksAdapter.kt`, `main/.../ui/tasks/TasksViewModel.kt` (constructor param only), `main/.../ui/tasks/TasksFragment.kt`, `res/values/dimens.xml`, `res/values/strings.xml`; tests `TaskCardWorkTest`, `TaskCardReminderTest`, `TasksScreenInstrumentedTest` |
| S5 | `res/layout/fragment_profile.xml`, `main/.../ui/profile/ProfileFragment.kt`, `res/values/strings.xml` |
| S6 | `res/layout/fragment_task_detail.xml`, `main/.../ui/tasks/TaskDetailFragment.kt`; tests `TaskDetailLayoutTest`, `util/WorkAdjustmentUiTest`, `TaskEditorInstrumentedTest` |
| S7 | `res/layout/activity_main.xml`, `res/menu/bottom_nav_menu.xml` — not `res/values/themes.xml`, which 1.5 already owns, and **not** `MainActivity.kt`, which an earlier draft listed with no step that touched it: the bar's
     label mode, surface and indicator are all XML and theme, and the nav controller already drives it |
| S8 | the layouts and adapters listed per sub-stage in §Stage 8 |
| S9 | `main/.../ui/tasks/TasksAdapter.kt`, `res/values/anim.xml` (new, in the list above), `res/values/dimens.xml` |
| S10 | screenshots, `docs/UI_REDESIGN_REVIEW.md`, and `test/.../ui/theme/VariantSurfaceTest.kt` (10.1a), which is source and was missing from this row |
| S11 | `README.md`, `docs/DESIGN_SYSTEM.md`, `docs/UI_UX_AUDIT.md`, `docs/UI_REDESIGN_REVIEW.md`, `docs/design/contrast-report.md` |

---

## Stage 0 — Evidence, baseline, audit document

Purpose: fix what "before" means, so no later stage can argue about it. Depends on: nothing.
Precondition: `git status --short` is empty and HEAD is the plan commit, whose SHA is on this page's
header — record it in the audit document as the baseline the before-photographs belong to.

- [x] **0.0 Create the evidence folders.** *Executed:* `docs/images/ui-redesign/before/` and `after/`
  exist; the before half holds 25 PNGs and `manifest.md` (§20i).

- [x] **0.1 Re-count the baseline.** *Executed at `42ebfc2`+this commit:* 93 suites / 1373 tests / 0 failures, the extra suite and two tests being `LegacySchemaV7SourceTest`. Instrumented: 17 methods in six classes, confirmed by `grep -c @Test`. Run the commands in §2. Expected `92 1371 0` and 17 instrumented
  methods. Record the numbers in `docs/UI_UX_AUDIT.md`. If they differ, stop: the spec's §8 needs the same
  correction this plan's §16 would otherwise inherit.

- [x] **0.1a Run the instrumented suite for real, and expect at least one red.** *Executed; see §20h for the three failures it found, their root causes, and the per-class isolation rule that came out of it.* Counting 17 `@Test`
  annotations proves they exist; CI has only ever compiled them
  (`assembleDebugAndroidTest` in `.github/workflows/android-ci.yml` — there is no instrumentation job), so
  their pass rate is an unmeasured premise this plan relied on. One known defect is already visible in the
  source: `TasksScreenInstrumentedTest.statisticsAndBackKeepsTheTaskListUsable` clicks
  `R.id.navigation_statistics` and then asserts `R.id.contentGroup` is displayed, while `contentGroup`
  exists only in `fragment_profile.xml:19` and `fragment_category_detail.xml:15` —
  `grep -c contentGroup app/src/main/res/layout/fragment_statistics.xml` is `0`, and that screen's own ids
  are `dashboardScroll`, `dashboardHeadline`, `statisticsPeriodScroll`. So that method cannot pass on the
  device today.

  ```bash
  ./gradlew --no-daemon --console=plain connectedDebugAndroidTest
  ```

  Record the real pass/fail list in `docs/UI_UX_AUDIT.md`. A pre-existing instrumented failure is fixed in
  its **own commit before C1**, asserted against a statistics id, and named in the commit message as a test
  correction rather than a redesign change — the rule against weakening a test applies to this one too, and
  the fix is to assert what the test's name promises. §14's third criterion is then measured, not assumed.

- [x] **0.2 Confirm the device is the one the spec describes.** *Executed and corrected:* the panel is
  540x1140 at 220 dpi (392.7 dp), the matrix uses density-only overrides, and forcing the size breaks
  Espresso taps — the measurement is in the step below, and §20i records what the plan used to claim.

```bash
adb -s "$S" shell getprop ro.build.version.sdk && adb -s "$S" shell wm size && adb -s "$S" shell wm density
```

Measured at `d1839a8`: `ro.build.version.sdk` = `35`, and the panel reports **`Physical size: 540x1140`
with `Physical density: 220`** — which is already 393dp, and is not the 1080x2280@440 an earlier draft of
this plan assumed. Two consequences, both of which have to be honoured rather than noted: the density
pairs for the other two widths are **240 for 360dp and 270 for 320dp** at this panel, not 480 and 540; and
a 540x1140 screenshot is half the linear resolution of a real handset, so hairlines and 11sp text are
under-sampled in the evidence. Prefer forcing the full panel and letting `wm density` do the dp work.
These pairs belong to *this* panel: the rule is always `density = px_width / dp_width × 160`, so at 540px
they are 240 and 270, at 1080px they are 480 and 540, and at the handset §6 now records (1220px) they are
542 and 610 — recompute against whatever `wm size` reports, and never carry a pair across devices.

```bash
adb -s "$S" shell wm density 220    # 393dp on this 540px panel — and nothing else is overridden
```

Record the reported `Physical`/`Override` pair, the density, the font scale and the uimode in the review
document **for every screenshot**, and reset with `wm density reset` at the end.

**Do not override the panel size on this emulator.** An earlier draft of this step told the executor to run
`wm size 1080x2280` so the evidence would sample like a real handset, and called the override "not
optional". Measured at `c1b2c07`, that instruction breaks the tests it was meant to protect: with
`Override size: 1080x2280` in place, `TasksScreenInstrumentedTest.statisticsAndBackKeepsTheTaskListUsable`
taps `navigation_statistics` and the app stays on Tasks — the failure hierarchy shows the Tasks fragment
with `navigation_tasks` still selected, i.e. the injected tap never reached the item. Reset to the physical
540x1140 and the same build on the same device passes 4/4; with `wm density 270` (320dp) layered on the
physical panel it passes 4/4 again. Density changes do not move the pixel space, so taps keep mapping;
size changes do. The trade is resolution, not correctness, and it is stated in the audit rather than
buried: 540x1140 photographs sample hairlines and 11sp text at half the linear density of the handset.

The dp arithmetic still holds and is what the width matrix uses: `density = px_width / dp_width × 160`, so
on this 540px panel 393/360/320dp are 220/240/270. Recompute against whatever `wm size` reports, and never
carry a pair across devices.

- [x] **0.3 Build and install the baseline APK**, then prove the installed bytes are the built bytes
  (§6). The audit found a stale APK; do not repeat that. *Executed:* `assembleDebug` at `c1b2c07` →
  `app-debug.apk` sha256 `28e898e91ad7a18a505b4555f0026dba138463d497e944ccb10ce9a7ad9600f7`; the
  installed `base.apk` was pulled back off the device and hashed to the same value.

- [x] **0.4 Capture the before-matrix** — *executed with `scripts/capture-ui-matrix.py`, not with
  `mobile-use:mobile-use`: that skill's MCP tools are not connected in this session (`mcp_list` returns no
  mobile tools), so the script drives `adb` directly and reads real view bounds from `uiautomator`.*
  25 of the 28 files exist; `11-archive` and `12-work-history` have no path to reach them (finding 2) and
  `27-swipe-open` could not be produced by any injected drag (finding 10) — see §20i. Every captured file
  carries the same account and the same 13 visible tasks, recorded per row in `before/manifest.md:

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
adb -s "$S" exec-out "run-as com.taskplanner sqlite3 databases/taskplanner.db 'select count(*) from tasks'"
```

If `run-as`/`sqlite3` is unavailable on this image, record the visible row count from the screenshot
instead and say so in the audit — do not silently drop the check that the data was the same.

Restore `wm density 440`, `font_scale 1.0`, `uimode night no` afterwards, and record that you did.

- [x] **0.5 Write `docs/UI_UX_AUDIT.md`.** *Written:* ten findings, the baseline test numbers, the
  configuration table, and the limits of an emulator in §5. Every finding points at a file and line or at
  a numbered screenshot. The list the step started from:

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
9. **Found while capturing, not in the original eight.** The dashboard's three-up figure row has no gutter:
   `fragment_statistics.xml:93-118` lays `dashboardFinished`, `dashboardAdded` and `dashboardStreak` as
   three weighted TextViews with nothing between them, and the figures are plural sentences, so
   `before/03-statistics.png` prints "1 task finished14 tasks added" as one run. 8b owns it.
10. **The row's own gestures, measured on the device.** One tap on a card entered *selection mode* instead of
    opening the task (`before/28-selection-mode.png` is that photograph), and six injected horizontal drags
    of 150–900 ms moved `taskCard` by zero pixels while a vertical drag scrolled the list. `handleTouch`
    arms a 400 ms long-press on DOWN and does the click on UP (`TasksAdapter.kt:148,185-194`), and nothing
    in the suite drives that path — so 2's acceptance is not "the panel is invisible", it is "the reveal
    happens, in a photograph, on a device". Audit §4.10 carries the numbers and §5 the photograph that
    could not be taken.

- [ ] **0.6 Commit.**

```bash
git add docs/UI_UX_AUDIT.md docs/images/ui-redesign/before/
git commit -m "stage 0: the before-evidence, and eight problems the redesign has to answer"
```

- [ ] **0.7 Commit the tooling the evidence depends on, before C1.** The fixture, the applier and the
  capture script are not evidence themselves, but every later stage's "same data, same configuration" claim
  is only true because they exist — and a stage that has to re-derive them will derive them differently.
  `superpowers:verification-before-completion` runs between 0.6 and 0.7, so the evidence commit is the one
  that is checked; if a review pass changes a test, it gets its own commit before this one.

```bash
git add scripts/evidence-seed.sql scripts/apply-evidence-seed.py scripts/capture-ui-matrix.py
git commit -m "stage 0: the fixture and the camera — reproducible data and a matrix that refuses bad taps"
```

**Acceptance:** 28 PNGs in `docs/images/ui-redesign/before/`, each named for the configuration it was
taken in; the audit's eight findings each backed by a file:line or a screenshot; baseline numbers recorded
and matching §2. **Screenshots:** the whole before-matrix.

---

## Stage 1 — Design system: tokens, theme, type, space, components

Purpose: build the layer every later stage reads from. Depends on: S0. Precondition: S0 committed.
Tools: `design-review:design-system-capture`, `ui-ux-pro-max-skill:design-system`.

- [x] **1.1 Capture what exists before replacing it.** *Executed by hand rather than with `design-review:design-system-capture`: that skill writes a `DESIGN.md` contract, and this repository's contract already exists as `docs/design/tokens.json` plus spec section 3 — a second source of the same truth is the thing section 3's rules exist to prevent. The inventory, counted over `app/src/main/res` and the Kotlin sources: 27 `@color` names in 85 uses, 26 `?attr` names in 252 uses, 6 `@dimen` names in 95 uses, 12 `@style` names in 111 uses, 33 `R.color` names in 73 uses, 180 hex literals. Two names the theme block below does not carry were found and are now carried: `?attr/colorControlNormal`, which thirteen vector drawables fill themselves with and which no contrast gate could see, and `colorPrimaryVariant` / `colorSecondaryVariant`, which fourteen `Widget.MaterialComponents.*` styles still ask for by name and which would have answered with Material's own purple. One name the sweep would have missed is recorded for 8c instead: `?attr/materialButtonOutlinedStyle`, four uses in `fragment_goal_detail.xml`, is an M2 attribute rather than a style, and grepping for `Widget.MaterialComponents` does not find it. Inventory
  every colour, dimension and style currently referenced by a layout, and diff it against the token table.
  Anything in use but not in the table is either added or explicitly mapped to an alias in 1.3. This is
  what prevents a stage-8 screen discovering it has no token for something.
  Measured state to fix on the way: `python scripts/contrast_report.py --resources app/src/main/res --check`
  today dies with `KeyError: 'ink_error'`, because `--resources` reads the shipped colours while the pair
  list comes from `docs/design/tokens.json`, and the `ink_*` names do not exist until 1.3. The script must
  say that in words and exit non-zero with the missing names listed — a traceback is not a gate message —
  and after 1.3 the same command is the one §14.4 runs.

- [x] **1.2 Write the failing contrast tests.** *Written: `Wcag.kt`, and `ContrastTest.kt` holding the abstract class with its light and dark subclasses — ten tests between them.* New file `app/src/test/java/com/taskplanner/ui/theme/Wcag.kt`
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

- [x] **1.3 Run them and watch them fail.** *Executed: ten failures, every one of them `java.lang.NoSuchFieldException` on `ink_text`, `ink_accent`, `ink_success`, `ink_error` or `ink_on_accent` — a missing token, in both themes, which is the failure this step asks for.* Run them:

```bash
./gradlew --no-daemon --console=plain testDebugUnitTest --tests "com.taskplanner.ui.theme.*"
```

Expected: `NoSuchFieldException: ink_background` (or the Robolectric equivalent). That is the failure this
step wants — a missing token, not a typo. If it fails differently, fix the test before continuing.

- [x] **1.4 Write the tokens.** *Executed: the twenty names below went into `values/colors.xml` and the dark column into `values-night/colors.xml`, the fourteen aliases were retargeted in place, the three `*_dark` colours were deleted from both files, and the ramp was given its own anchors. Both contrast classes are now 5/5 green and `contrast_report.py --resources` prints `RESULT: pass` with no drift line, which is the proof that the resources and `tokens.json` agree.* Add the fifteen `ink_*` role tokens of §4 and the five pill tokens to
  `res/values/colors.xml`, and **edit the existing role names in place** — do not append a second block of
  them. `primary`, `primary_dark`, `secondary`, `secondary_dark`, `background`, `surface`, `on_surface`,
  `error`, `on_error`, `status_todo`, `status_in_progress`, `status_paused`, `status_done`, `priority_low`,
  `priority_medium` and `priority_high` are already declared — the ten legacy roles at `colors.xml:4-25`, and
  `status_todo:28`, `status_in_progress:29`, `status_done:30`, `status_paused:31`, so the range to edit is
  `4-31`, and aapt rejects a
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
file. Delete them from **both** files: `grep -rn` finds each name declared twice, at
`values-night/colors.xml:4,5,6` *and* `values/colors.xml:9,11,15`, and removing only the night copies leaves
three dead colours in the default configuration. `values-night/themes.xml:15,16,19` are the only readers, and
1.5 rewrites all three lines. Keep `state_badge_fill` (three drawables read it).

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

- [x] **1.5 Map the theme.** *Executed with the three additions 1.1 asked for: `android:colorControlNormal` and `colorControlNormal` to `ink_outline_strong`, and `colorPrimaryVariant`/`colorSecondaryVariant` to `ink_accent`, so an unmigrated M2 style cannot answer with library purple.* Replace `res/values/themes.xml` with the ink roles on the M3 attributes, and
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

- [x] **1.5a Skeleton first.** *Executed: `type.xml` and `component_styles.xml` declared their names over their Material parents before any assertion pointed at them, which is what let 1.6 fail as a red test rather than as a compile error.* Before 1.6 can run, the nine `TextAppearance.TaskPlanner.*` styles and the `ink_*` colours must exist as *names*: Kotlin resolves `R.style.TextAppearance_TaskPlanner_Display` at compile time, so a test naming a style nobody has declared is a build failure, not a red test. 1.5a adds `type.xml` with the nine styles declared at their parent Material sizes and no tracking or colour overrides, and commits it alone. 1.6 then writes the failing assertions against names that resolve.

  It also creates `component_styles.xml` with all **fifteen** `Widget.TaskPlanner.*` names as empty styles
  whose only content is the Material 3 parent each one inherits. This is not tidiness: 1.5 puts
  `bottomNavigationStyle`, `materialCardViewStyle` and `chipStyle` into `themes.xml`, and `aapt2 link`
  resolves a theme's `@style/…` value at **link** time, so a theme naming `Widget.TaskPlanner.BottomBar`
  before 1.8 writes it fails the build with `resource style/Widget.TaskPlanner.BottomBar not found` —
  the same class of mistake §3's skeleton rule exists to prevent, one file earlier. An empty style with a
  real parent is valid XML and draws exactly what the parent draws, so 1.5 through 1.7 ship a redesigned
  palette over unchanged component shapes, and 1.8 fills the fifteen bodies in without touching any name.

- [x] **1.6 Failure-first:** *Executed: `each role is the size the scale says it is` and `tracking is expressed in ems...` both failed against the stubs, and the two theme-attribute tests passed, because 1.5 had already mapped the attributes.* write `TypeScaleTest` against the nine styles 1.5a declared as stubs at their Material parents' sizes. It builds a `TextView` with each style and
  asserts `textSize` in sp and `letterSpacing`:

```kotlin
package com.taskplanner.ui.theme

import android.content.Context
import android.util.TypedValue
import android.view.ContextThemeWrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
        assertEquals(28f, sizeSp(R.style.TextAppearance_TaskPlanner_Display), 0.2f)
        assertEquals(22f, sizeSp(R.style.TextAppearance_TaskPlanner_Headline), 0.2f)
        assertEquals(16f, sizeSp(R.style.TextAppearance_TaskPlanner_Title), 0.2f)
        assertEquals(15f, sizeSp(R.style.TextAppearance_TaskPlanner_Body), 0.2f)
        assertEquals(13f, sizeSp(R.style.TextAppearance_TaskPlanner_Meta), 0.2f)
        assertEquals(12f, sizeSp(R.style.TextAppearance_TaskPlanner_Caption), 0.2f)
        assertEquals(11f, sizeSp(R.style.TextAppearance_TaskPlanner_Pill), 0.2f)
        assertEquals(11f, sizeSp(R.style.TextAppearance_TaskPlanner_Label), 0.2f)
        assertEquals(20f, sizeSp(R.style.TextAppearance_TaskPlanner_Metric), 0.2f)
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
            // The non-zero check is not decoration: `StatisticsLayoutTest:655` asserts it because a
            // resolveAttribute can succeed by falling through to the M3 parent, returning 0 or the
            // parent's style, while the map below still sees five distinct ids. Without this line an
            // attribute this stage forgot to map is invisible to the test that claims to catch it.
            assertNotEquals("the theme names a resource for ${themed.resources.getResourceName(attr)}",
                0, value.resourceId)
            value.resourceId
        }
        assertEquals("five roles, five styles, or the dashboard has no hierarchy", 5, styles.toSet().size)
        // And the sizes really are the roles: a resolved id that points at the wrong TextAppearance would
        // satisfy the distinctness check above while drawing 12sp where 13sp is promised.
        assertEquals("Headline5 maps to display", sizeSp(R.style.TextAppearance_TaskPlanner_Display),
            sizeSpOf(TextView(themed, null, asked[0])), 0.2f)
    }

    @Test fun `a layout asking for the old body2 attribute gets the meta role`() {
        // Three arguments, not four: `TextView(context, attrs, defStyleAttr)` takes the THEME ATTRIBUTE and
        // resolves it through the theme, while the fourth slot is `defStyleRes`, a style resource id. The
        // earlier draft put `R.attr.textAppearanceBody2` in the defStyleRes slot, where no default style is
        // ever found, so the view came back at the platform default size and this test could not pass.
        val tv = TextView(themed, null, com.google.android.material.R.attr.textAppearanceBody2)
        assertEquals(sizeSp(R.style.TextAppearance_TaskPlanner_Meta), sizeSpOf(tv), 0.2f)
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

- [x] **1.6b Type scale** (the implementation; it runs after 1.6's test) in new `res/values/type.xml`: *Executed, with one deviation stated rather than buried. `android:lineHeight` is not set on these styles: `TextView.readTextAppearance()` reads a fixed list out of a TextAppearance — colour, size, style, family, caps, tracking, shadow, locale — and line height is not in it, so writing the section 3.3 line heights there would have looked like a decision and done nothing. The API-28 fallback note stays true of the attribute wherever it is actually set, and 10.2 measures the line boxes instead.* Nine `TextAppearance.TaskPlanner.*` styles over the
  M3 parents — display, headline, title, body, meta, caption, pill, label, metric — each carrying size,
  `android:lineHeight` (with `tools:targetApi="28"`), weight, `android:letterSpacing` **in ems** at the
  `lineHeight` is API 28 and the floor is 24, so on 24-27 the pair falls back to the font's own metrics
  and the line is a little tighter there. That is stated rather than implied because nothing in the JVM
  suite can see it: the tests read `textSize` and `letterSpacing`, both of which apply on every level,
  and Robolectric runs at 34. 10.2's device pass is where the fallback is confirmed harmless.

  values §3.3 of the spec now states, and colour through a role rather than a hex. Only `label` sets
  `android:textAllCaps`; `pill` is deliberately not capped, because the 82dp line-B budget in §4.1 depends
  on it. Add `type_caption` (12sp) and `type_pill` (11sp) to the dimens alongside the other seven.

- [x] **1.7 Space and shape.** *Executed, and it cost a test that was right. The first version of `Widget.TaskPlanner.Card` carried the section 3.4 `contentPadding` of 14/16, and because 1.5 made that style the theme's `materialCardViewStyle`, the padding reached every `MaterialCardView` in the app at once — `StatisticsLayoutTest` measured the dashboard's own 16dp inset, found 14dp, and failed. The padding came out of the style: a layout that wants it sets it, and 8b says so in the same commit as the rest of that screen's spacing. `task_item_height` and `category_item_height` were deleted as unread; `fab_margin` was deleted and put back, because 3.2 is its owner and has not arrived.* Add to `res/values/dimens.xml` (keeping the five existing `spacing_*` values
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

    @Test fun `the touch-target and row floors are their own numbers, not each other`() {
        assertTrue("touch_target_min is ${dimen(R.dimen.touch_target_min)}px",
            dimen(R.dimen.touch_target_min) >= pxOf(48))
        // Spec §3.4 sets two floors, and a test that reads the lower one for both would pass with the
        // row height collapsed to 48dp — the assertion has to name the rule it protects.
        assertTrue("row_min_height is ${dimen(R.dimen.row_min_height)}px, floor 56",
            dimen(R.dimen.row_min_height) >= pxOf(56))
    }
}
```

- [x] **1.7a Make the gutter test pin the UI, not two constants.** *Executed as far as Stage 1 can: `list_bottom_gutter` and `fab_size` exist. One correction to the premise — `ListGutterTest` does not exist in this repository (searching `app/src` for `ListGutter` and for `list_bottom_gutter` finds only the new dimen), so there was no test reading the sum to keep. 3.2 writes it in the same commit as the layout edit that makes it meaningful, which is the only order in which it can pin anything.* `fragment_tasks.xml:153` hard-codes
  `android:paddingBottom="88dp"`, and `fab_margin` is read by nothing in `app/src`, so a test comparing
  the two dimens proves arithmetic about a file nobody renders. What Stage 1 owns is the *names*: add
  `list_bottom_gutter` (88dp) and `fab_size` (56dp) to `dimens.xml` and keep `ListGutterTest` reading the
  sum. The layout edit is **not** here — `fragment_tasks.xml` belongs to Stage 3 (§7's one-file-one-owner
  rule, and §8's S3 row), so 3.2 replaces the literal with `@dimen/list_bottom_gutter` and points the FAB's
  16dp margin at `fab_margin`, and the grep below becomes a §14 gate at the end of Stage 3 rather than a
  claim Stage 1 can honour. An earlier draft had Stage 1 editing that layout, which would have given one
  change two owners and made Stage 1's own acceptance ("no layout changed except the six new includes")
  false the moment it ran.

```bash
# expected: no match (the literal is gone) — checked after 3.2, not after this step
grep -n 'paddingBottom="88dp"' app/src/main/res/layout/fragment_tasks.xml && echo 'STILL A LITERAL' || echo coupled
```

- [x] **1.7b Statistics allowlist.** *Checked at this commit: `spacing_medium` and `spacing_section` are declared and used by no dashboard layout, so the five-id allowlist still holds; 8b adds the ids it uses in the same commit, as written.* `StatisticsLayoutTest` asserts that every vertical gap on the dashboard
  is one of the named `spacing_*` values, and its allowlist is built from five ids. Adding `spacing_medium`
  and `spacing_section` does not break it while no dashboard layout uses them; the moment 8b uses one, that
  test's set must gain the same id **in the same commit**. Note it here so 8b does not discover it as a
  surprise failure.

- [x] **1.8 Component styles** in new `res/values/component_styles.xml`: *Executed. One thing only the device showed: with `chipStyle` pointing at the new filter style, the category rail began drawing the reader's own words as all-caps, because the label role it inherits is capped. `textAllCaps=false` on the two chip styles fixes it and `ComponentStyleTest` pins it, with the label role's caps kept for the headings a screen writes itself.* the fourteen `Widget.TaskPlanner.*`
  styles spec §3.5 lists, plus `Widget.TaskPlanner.Field.Dropdown` — the fifteenth, needed because five uses of
  `Widget.MaterialComponents.TextInputLayout.OutlinedBox.ExposedDropdownMenu` across two layouts and the spec's
  list names only the plain field. Then the pill styles over the five `bg_pill_*` drawables. And the new
  drawables listed in §8 — each a `<shape>` or `<ripple>` reading a token, no literals.

- [x] **1.9 Layout includes** — six of them, matching spec §3.5's list: `header_screen.xml`,
  `row_list.xml`, `state_empty.xml`, `state_loading.xml`, `state_error.xml`, `bar_selection.xml`. The
  seventh include the spec names, `.GroupHeader`, is not new: `item_group_header.xml` already exists and is
  restyled in place at 4.4 rather than replaced by an include, because `TasksAdapter.HeaderViewHolder` binds
  its four ids (`groupChevron`, `groupName`, `groupCount`, `groupMenu`) and rewriting that binding for a
  layout change would be churn with no user visible behind it.

  `row_list.xml` is the Profile row and is included **eight** times (Account 2, Your work 4, Data 1, Danger
  zone 1), so its children carry ids and each include gets its own `android:id` — ViewBinding scopes an
  include's binding to that include's own view tree, so the repeated child ids cannot collide.

- [x] **1.10 Write `ComponentStyleTest` and `IncludeBindingTest`** — inflate every style and every include
  under `Theme.TaskPlanner` in both qualifiers, and assert the include's children are reachable through the
  nested binding (`binding.row.root.findViewById(R.id.rowTitle) != null`), which is what proves the eight
  Profile rows will not fight over ids.

- [x] **1.11 Run the gates.** *Executed on a forced full re-run (`--rerun-tasks`, so no task could hide behind an UP-TO-DATE line from an earlier build): 98 suites / 1399 JVM tests / 0 failures / 0 skipped, `assembleDebug` green, `lintDebug` green. The numbers and the lint delta are in 1.11b.*

```bash
./gradlew --no-daemon --console=plain testDebugUnitTest
./gradlew --no-daemon --console=plain assembleDebug
python scripts/contrast_report.py --resources app/src/main/res --check --format markdown --out docs/design/contrast-report.md
```

Expected: 1371 + the new tests, 0 failures; build succeeds; the script prints `RESULT: pass` with no drift
lines. A drift line means a resource and the table disagree — fix the resource, never the table, unless the
design changed, in which case both move together and the spec's §3.1 quotation is regenerated.

> Known limitation of this mode today, measured rather than assumed: before Stage 1 writes the tokens, and
> for any name mistyped in `colors.xml`, `--resources` does not print a named failure — it dies inside
> `pairs()` with `KeyError: 'ink_error'` (reproduced at this commit: `python scripts/contrast_report.py
> --resources app/src/main/res --check`). Exit status is still non-zero, so a build gate catches it, but a
> worker gets a traceback instead of "which pair is missing". 1.11a therefore hardens the script: a token
> absent from the resources prints `MISSING <theme> <token>` and the run still ends with a non-zero
> `RESULT: fail`. `scripts/contrast_report.py` is a measurement tool, not app code, so this is one of the
> few places the stage edits a file outside `app/src/main/res`.

- [x] **1.11a Harden the report, then re-run it.** *Executed: the only unguarded `tokens[...]` index left in `pairs()` was the variant-surface block. A missing name now records `MISSING <theme> <token>`, keeps `ratio = None`, marks the pair not-allowed so the run still fails, and the traceback is gone. Both modes print `RESULT: pass` at this commit.* In `scripts/contrast_report.py`, wrap the token lookup in
  `pairs()` so an absent name yields `MISSING <theme> <token>` in the output list instead of a `KeyError`,
  keep the non-zero exit, and re-run both modes:

```bash
python scripts/contrast_report.py --check                                  # tokens only: must pass
python scripts/contrast_report.py --resources app/src/main/res --check      # names every missing pair now
```

- [x] **1.11b The numbers, as measured.** JVM: **98 suites / 1399 tests / 0 failures / 0 skipped** (1374 at Stage 0, so the stage added 25 tests: `Wcag`, ten contrast, four type-scale, and the two new component classes). Lint: **162 issues at `1bd55f4` against 189 here** — +26 `UnusedResources`, +1 `Overdraw`. The baseline was re-measured rather than remembered: `git worktree add` at the pre-stage commit and `lintDebug` there. The `UnusedResources` part is this stage creating tokens, dimens, styles, drawables and includes that the next eleven stages consume, and 14.2 is measured at the PR, so if the count has not come back down by then that is a stage that declared resources it never used. `RtlSymmetry`, the one finding this stage *caused* rather than deferred, was fixed in `bar_selection.xml` inside the stage.
- [x] **1.11c One failure that did not repeat, recorded rather than buried.** The first forced run (`testDebugUnitTest assembleDebug lintDebug --rerun-tasks`) failed `WorkAdjustmentUiTest.`a stretch that ends where or before it began is refused by the form` with `expected:<A stretch cannot end at or before it starts.> but was:<null>` — the notice the form shows was not found within the helper's 40x50 ms virtual-clock budget. A second forced run of the same suite passed, 1399/0, and the class passed twice on its own, once forced. Stage 1 touches no code on that screen, so this is recorded as a flake with a known shape — `noticeText()` reads a Snackbar through a bounded pump, so it fails when the pump loses to whatever else the JVM is doing — and *not* fixed by raising the budget, which would be tuning a timeout to hide a race. If it recurs, the fix is to make the notice readable without pumping at all.
- [x] **1.12 Visual check that the theme landed.** *Executed: the APK built from this tree (`be65d49fcec3b701b2b9f99f5eb370e4e4aa51ee5e1bac49de741b2d6fbc8dd7`) was installed, the installed `base.apk` was pulled back and hashed to the same value, and Tasks and Profile were photographed in light and dark against the same profile and the same 13 visible tasks the before-matrix carries. `after/s1-tasks-light.png` is the pair of `before/01-tasks.png`: the purple is gone, the accent is green, the surfaces are warm, the icons are ink rather than 40 % grey, and the category chips read in the case their owner typed — which is the 1.8 correction, visible. What has *not* moved is the swipe panel's strip on every closed row, because that is Stage 2's, and the eight-button Profile column, because that is Stage 5's.* Install, screenshot Tasks and Profile in light and dark. Install, screenshot Tasks and Profile in light and dark.
  Nothing here has moved a view, but every colour has changed, so the pair is the evidence:

```bash
adb -s "$S" install -r app/build/outputs/apk/debug/app-debug.apk
adb -s "$S" exec-out screencap -p > docs/images/ui-redesign/after/s1-tasks-light.png
adb -s "$S" shell cmd uimode night yes
adb -s "$S" exec-out screencap -p > docs/images/ui-redesign/after/s1-tasks-dark.png
adb -s "$S" shell cmd uimode night no
```

Judge them with `design-review:design-debt-review` in mind: any screen that now looks *worse* than the
baseline (a colour that landed on the wrong surface, a hairline that vanished in dark) is fixed here, not
deferred — a broken token layer makes every later stage's screenshots untrustworthy.

- [x] **1.13 Commit.**

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

Purpose: kill the strip that shows on every closed row, and prove it with a measurement. Depends on: S1 —
specifically on `bg_row_backdrop`, `card_gap`, `spacing_xs` and the `ink_*` colours the backdrop paints,
which is what 2.3's fix consumes; the test itself reads no colour. Precondition: S1 committed and green.

This stage changes geometry on the **current** card on purpose: if the fix and the redesign landed in one
commit, a passing test could not say which of them removed the strip.

- [x] **2.1 Write the failing test** — `app/src/test/java/com/taskplanner/ui/tasks/TaskCardClosedPanelTest.kt`,
  following `TaskCardWorkTest`'s inflate-measure-layout pattern:

```kotlin
package com.taskplanner.ui.tasks

import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.taskplanner.R
import com.taskplanner.data.model.Task
import com.taskplanner.data.model.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

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
        return holder.itemView.also { view ->
            // Two passes, because one cannot tell a match_parent child anything: ViewGroup's
            // getChildMeasureSpec maps MATCH_PARENT under an UNSPECIFIED parent to size 0, so with a single
            // UNSPECIFIED measure the action panel is laid out ZERO high and every vertical assertion
            // below passes on an empty rectangle. The first pass learns the row's own height; the second
            // gives it EXACTLY, which is what RecyclerView does when it measures an item.
            val widthSpec = View.MeasureSpec.makeMeasureSpec(393.dp(), View.MeasureSpec.EXACTLY)
            view.measure(widthSpec, View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            val rowHeight = view.measuredHeight
            view.measure(widthSpec, View.MeasureSpec.makeMeasureSpec(rowHeight, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, 393.dp(), rowHeight)
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
        assertTrue("the panel is a real rectangle in this fixture: ${panel.height}px tall",
            panel.height >= 40.dp())
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

    @Test fun `the reveal distance is exactly the panel's own width`() {
        val row = row()
        val card = row.findViewById<View>(R.id.taskCard)
        val panel = row.findViewById<View>(R.id.actionPanel)
        assertEquals("two 76dp buttons, which is what the drag measures", 152.dp(), panel.width)
        assertTrue("and the panel has height to reveal: ${panel.height}px", panel.height >= 40.dp())
        card.translationX = -panel.width.toFloat()
        // Equality, not <=. The earlier draft asserted `panel.left <= card.right - panel.width`, which is
        // the same statement as the closed-row test's `panel.right <= card.right` — panel.left IS
        // panel.right minus panel.width — so it proved nothing new, and it was satisfied pre-fix, when a
        // card sliding by 152dp still left 8dp of panel exposed down the right edge. Landing exactly on
        // the panel's left edge is the invariant the drag depends on.
        assertEquals("sliding by panel.width reveals the panel exactly, no early strip, no shortfall",
            panel.left, (card.right + card.translationX).toInt())
        assertTrue("and nothing of the panel sticks past the row", panel.right <= row.width)
    }
}
```

> The thirteen-callback constructor, the `Task(...)` shape and the `awaitUntil` helper are taken from
> `TaskCardWorkTest`, which already drives the real adapter this way — including `ListAdapter`'s async diff,
> which is why the wait loop idles the main looper instead of asserting straight after `submitList`.

- [x] **2.2 Run it, watch it fail.** *Executed: both tests failed, and the numbers were the ones derived above — `panel right 393 must not pass card right 385`, and `expected:<241> but was:<233>`. The method stops at its first failure, so the top and bottom assertions the paragraph predicts were not reached; they are the same class of defect and 2.3's device measurement covers them.* — on exactly four assertions, and the list is derived rather than
  guessed, from the shipped geometry (row 393dp wide, card `[8, 385]`, panel `[241, 393]` at `[0, rowHeight]`):
  `panel.right 393 > card.right 385` fails, `panel.top 0 < card.top 4` fails, `panel.bottom 32 > card.bottom`
  fails, and the reveal test's `card.right - panel.width = 233 != panel.left 241` fails. The `translationX`
  and `panel.left >= card.left` assertions PASS before the fix, which is why they are stated as guards rather
  than as the red: an earlier draft claimed "the first three assertions" fail, and one of those three cannot.

```bash
./gradlew --no-daemon --console=plain testDebugUnitTest --tests "com.taskplanner.ui.tasks.TaskCardClosedPanelTest"
```

- [x] **2.3 Fix the layout.** *Executed, and both buttons lost their full-bleed backgrounds; the two drawables that drew them, and the two colours only those drawables read, were deleted with them, because leaving a dead `bg_action_edit` behind is how the next stage paints the strip again.* In `res/layout/item_task.xml`, `actionPanel` gains
  `android:layout_marginEnd="@dimen/card_gap"`, `android:layout_marginTop="@dimen/spacing_xs"` and
  `android:layout_marginBottom="@dimen/spacing_xs"`, matching the card's insets, and its background becomes
  `@drawable/bg_row_backdrop` (surface, radius 14)

  > Decision on the 8dp gutter, taken here rather than discovered in 2.4: when a row is **open**, the strip
  > between the panel's right edge and the screen edge shows `ink_background` — the page — and that is the
  > answer, not a defect to mask. Painting the row root with the surface backdrop instead would put an 8dp
  > halo around *every closed card* as well, which is the same visual bug this stage exists to remove, only
  > in a neutral colour. A revealed action cluster sitting 8dp in from the edge reads as the inset the card
  > already has, so the two edges agree. Spec §2.1 says the same thing; this is the implementation note that
  > keeps it from being re-litigated at screenshot time, and 2.4 photographs the open row to confirm it.

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

- [x] **2.3a The same API-26 defect exists in nine more layouts, and lint will not say so.** *Executed: 22 `layout_marginHorizontal`/`layout_marginVertical` attributes expanded to their four explicit sides across the nine files the grep named, and the grep now returns empty. Every layout in `app/src/main/res/layout` still parses.* 2.3's
  correction was made because `layout_marginVertical` is API 26 on a minSdk 24 floor, where it is dropped
  silently. That is not a one-file problem: `grep -rl "layout_marginHorizontal\|layout_marginVertical"
  app/src/main/res/layout` at this commit lists exactly ten files — `fragment_goal_detail`, `item_archive`,
  `item_category`, `item_goal`, `item_group_header`, `item_habit`, `item_insight`, `item_profile`,
  `item_task`, `item_workload_panel` — including `item_group_header.xml`, which draws the header above every
  group on the screen this redesign is judged by. And the usual safety net does not catch it: `lint` reports
  zero `UnusedAttribute` findings on this project and its baseline has no such entry, because the attributes
  ARE used — they are just not applied on 24 and 25. So 2.3a converts all ten to explicit
  `marginStart`/`marginEnd` or `marginTop`/`marginBottom` dimens in this stage, and §14's gate gains the grep
  above as a check that returns empty, so the rule is enforced by a command rather than by memory.

- [x] **2.4 Run the whole Tasks test group**, then the full suite, then build: *Executed. The Tasks group is green on its own, then **99 suites / 1401 JVM tests / 0 failures / 0 skipped**, `assembleDebug` green, `lintDebug` green (187 issues, `UnusedResources` 80 against the 56 the baseline recorded — the same deferred-owners situation as 1.11b, and lower than it was before the dead drawables went). `TaskCardWorkTest` and `TaskCardReminderTest` passed unchanged.*

```bash
./gradlew --no-daemon --console=plain testDebugUnitTest --tests "com.taskplanner.ui.tasks.*"
./gradlew --no-daemon --console=plain testDebugUnitTest
```

Expected: all green, including `TaskCardWorkTest` and `TaskCardReminderTest` unchanged. If either breaks,
the layout change reached further than the panel — fix the change, not the test.

- [x] **2.5 Prove it on the device.** *Executed as far as this emulator's input injection allows, and the part that matters was measured rather than photographed: on a laid-out row the card is `[11,499][529,594]` and the panel `[319,499][529,594]` — the panel's right edge lands on the card's, its top on the card's, **0 px exposed**, against the 11 px of column and 6 px of band the same measurement produced at Stage 0. `after/s2-tasks-closed.png` is that row, and the red column is gone from it.*
>
> **What was not demonstrated by `adb shell input`, and why the command below is wrong.** Three injected horizontal drags across the fixed row moved `taskCard` by zero pixels, exactly as six did at Stage 0 (audit §4.10). The `input swipe 700 1200 200 1200` in the listing starts at x=700 on a 540 px screen — clamped to the right edge, which is the system's Back-gesture band on `navigation_mode=2`, so the drag was never the row's to take. 2.5b settled the question from inside the process, and the answer was the row's own touch handling: see §20j.

- [x] **2.5b Close the gesture gap.** *Executed, and it found a real defect: the reveal had never worked by touch. `TaskCardSwipeInstrumentedTest` (Espresso `GeneralSwipeAction`, from the card's centre to its left, because the edge band belongs to the system Back gesture) is green on the device — `translationX` lands on exactly `-actionPanel.width`, the edit button's centre clears the card, pressing it opens that task's editor, and the row comes home closed — and `TaskCardRowGestureTest` pins the same four outcomes in a JVM, where CI can see them. Photographs: `after/s2-row-closed.png`, `after/s2-row-swiped.png`, `after/s2-edit-from-panel.png`, `after/s2-row-closed-again.png`. `after/s2-tasks-swiped.png` was deleted: it is byte-identical to `s2-tasks-closed.png` (sha256 `6199671…` for both), so it documented a swipe that had not happened.*

The remaining device proof from this step is the reachability of each control once the panel is open — edit and delete by tap, and the reorder handle by drag — with a photograph of each state (the popup-free way):

```bash
adb -s "$S" install -r app/build/outputs/apk/debug/app-debug.apk
adb -s "$S" shell am start -n com.taskplanner/.ui.MainActivity
adb -s "$S" exec-out screencap -p > docs/images/ui-redesign/after/s2-tasks-closed.png
# start inside the card, away from both screen edges: on a 540 px panel the card runs [11,529],
# so 450 -> 150 is a drag the row can keep. 700 -> 200 is not; the system takes it as Back.
adb -s "$S" shell input swipe 450 545 150 545 220
adb -s "$S" exec-out screencap -p > docs/images/ui-redesign/after/s2-tasks-swiped.png
```

Then tap edit and delete once each, and drag the handle once, and photograph the results. Record what the
tap did — a snackbar, a navigation, a dialog — in the commit message. `uiautomator dump` cannot see this
emulator's popup windows, so the photographs are the evidence, the same way PR #21 handled it.

- [x] **2.6 Commit.** `git commit -m "stage 2: the swipe panel no longer shows on a closed row, and a test pins the geometry"`

**Acceptance:** `TaskCardClosedPanelTest` passes and failed before the change; `TaskCardWorkTest`,
`TaskCardReminderTest`, `TaskDetailLayoutTest` untouched and green; swipe reveal, edit, delete and manual
reorder each photographed working; the red column is gone from `s2-tasks-closed.png`.
**Screenshots:** `s2-tasks-closed.png`, `s2-tasks-swiped.png`, `s2-edit-open.png`, `s2-reorder.png`.

---

## Stage 3 — Tasks header: the four stranded actions

Purpose: give Tasks a header that exists, so search, sort, new group, archive and work history become
reachable. Depends on: S2. Tools: `frontend-design`, `android-jetpack`.

- [x] **3.0 Skeleton first, for the same reason as 1.5a.** One commit, no behaviour. It declares `header_screen.xml` with its six ids (`headerTitle`, `headerSubtitle`, `headerSearchField`, `headerSearchButton`, `headerSortButton`, `headerMenuButton`); **the include itself**, in `fragment_tasks.xml`, as `<include android:id="@+id/tasksHeader" layout="@layout/header_screen"/>`; and `menu_tasks_sort.xml` / `menu_tasks_overflow.xml`. The include's id belongs to the *including* layout, so a skeleton that creates only the child file leaves 3.1's test facing `unresolved reference: tasksHeader` — which is the compile error this step exists to remove, and the reason it is listed before 3.1 rather than folded into 3.4.

- [x] **3.1 Write the failing tests first.** Two of them:

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
    }
    // The assertion is the row count, not the list being on screen: a search that filters nothing
    // still leaves a displayed RecyclerView, which is exactly the false pass to avoid. Two tasks are
    // seeded, one of which contains the query.
    waitUntil("the filtered list to settle") { rowCount() == 1 }
    onView(withText("Написать обзор литературы")).check(matches(isDisplayed()))
    onView(withText("Сформировать цель ВКР")).check(matches(not(isDisplayed())))
}
```

  `waitUntil` is this project's own instrumented convention, copied from `TaskEditorInstrumentedTest:330`
  and `ReportScreenInstrumentedTest:583` — a 50 ms poll against `TIMEOUT_MILLIS = 60_000L` that throws an
  `AssertionError` naming what it waited for. It is declared in the new file, not borrowed: the two existing
  copies are `private` to their classes, and there is no shared instrumented test utility in this repo. An
  earlier draft of this step called an `awaitUntil` that exists in no `androidTest` source at all — the same
  mistake §20f‑f3 caught in Stage 9, reintroduced one stage over, and the reason this pass searched every
  block for helpers it could not find in the file that calls them.

  `rowCount()` reads the adapter through the same walk that finds the field, and returns an `Int`:

```kotlin
    private fun tasksRoot(): View = scenario.withActivity {
        (it.supportFragmentManager.fragments.first { f -> f is NavHostFragment } as NavHostFragment)
            .childFragmentManager.fragments.filterIsInstance<TasksFragment>().single().requireView()
    }

    private fun headerField(): EditText = tasksRoot().findViewById(R.id.headerSearchField)

    private fun rowCount(): Int =
        (tasksRoot().findViewById<RecyclerView>(R.id.recyclerView).adapter as? TasksAdapter)
            // TaskRow instances only. `TasksViewModel.render` also emits a GroupHeader per group
            // (`:255-277`), so a raw `itemCount` would count topic headings as tasks and the equality
            // below would depend on whether the account on the device happens to use groups.
            ?.currentList?.count { it is TasksListItem.TaskRow } ?: -1

    private fun waitUntil(description: String, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            if (runCatching(condition).getOrDefault(false)) return true
            Thread.sleep(50)
        }
        throw AssertionError("$description did not happen within $TIMEOUT_MILLIS ms")
    }

    private companion object { const val TIMEOUT_MILLIS = 60_000L }
```

  One walk, three readers, and the count assertion is the point of the test: a search that filters nothing
  leaves a perfectly displayed `RecyclerView`, which is exactly the false pass the earlier draft of this
  step would have allowed.

- [x] **3.2 Run them, watch them fail.** They compile — 3.0 declared the ids — and fail because the field
  is `GONE`, nothing is wired to it, and the row count does not move. If they fail to *compile*, 3.0 missed
  a name, and that is the bug to fix here rather than a reason to touch the test.

- [x] **3.3 Add the strings** to `res/values/strings.xml` — `values/` is the only locale folder in this
  repository, so each needs one entry: `tasks_header_title` (reuse the existing `app_name`/`title_tasks`
  if it already reads "Tasks"), `tasks_open_count_format` (`"%1$d open"`), `header_search_hint`,
  `header_sort`, `header_more_actions`, `filters_count_format` (`"Filters · %1$d"`),
  `filters_clear_all`.

- [x] **3.4 Add the header to `fragment_tasks.xml`**, above the filter rail, using `@layout/header_screen`
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
        binding.tasksHeader.headerSearchButton.setOnClickListener {
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

- [x] **3.5 Bind the title and count** in `setupObservers()`: `binding.tasksHeader.headerTitle
  .setText(R.string.title_tasks)`
  and `binding.tasksHeader.headerSubtitle.text =
  getString(R.string.tasks_open_count_format, state.activeTaskCount)`.
  `activeTaskCount` already exists on `TasksState` and is shown nowhere today.

- [x] **3.6 Rebuild the filter rail** (spec §4.1). Today the rail is two blocks: a `HorizontalScrollView`
  holding `chipGroupCategory`, and a non-scrolling `chipGroupStatus` below it with `Widget.MaterialComponents.Chip.Filter`
  styles — which is why the status row wraps and steals list height at 320dp. The change is to put a 1dp
  `ink_outline` hairline divider between the two rows, keeping them as two stacked bands, and to put a
  `Filters · n` chip at the head of the **category band**, inside a new `LinearLayout id=categoryBand` that
  the `HorizontalScrollView` now wraps (`categoryScroll` → `categoryBand` → [`filtersChip`,
  `chipGroupCategory`]) — which is the wrapper 3.6's containment test reads through `.parent.parent`. The
  three status chips keep their ids and their behaviour; no fourth chip is added to `chipGroupStatus`,
  because `completeAndArchive` means a Completed filter could never match a row, and because the test below
  pins that group's children to exactly the three it names. An earlier draft of this step said both rows
  should "share one horizontal band instead of stacking" and append the chip to the status row — the first
  contradicts the hairline-between-two-rows design spec §4.1 settles, and the second would make this step's
  own `childCount` assertion fail on the row it just added a chip to.

  **The two rows scroll differently, and that is a decision with a test behind it, not an oversight.** The
  category row stays inside its `HorizontalScrollView`, because its length is whatever the user's
  categories add up to. The three status chips stay outside any scroller, because
  `TasksScreenInstrumentedTest:48-50` asserts all three are `isDisplayed()` and a chip scrolled off the end
  of a `HorizontalScrollView` is laid out but not displayed — so a single scrolling rail would either fail
  that test or invite someone to weaken it, and weakening it is forbidden. Spec §4.1's revision-8
  correction says the same thing; an earlier draft of this step said the opposite, and its own test below
  contradicted it.

  Write its test first (in `TasksHeaderLayoutTest`, whose imports must include
  `org.junit.Assert.assertFalse` and `android.widget.LinearLayout` alongside the `ChipGroup` /
  `HorizontalScrollView` / `View` it already needs — this step shows test bodies rather than a whole file,
  so the import list is part of the instruction, the same way 4.2 and 9.2 state theirs):

```kotlin
    @Test fun `the categories scroll, the statuses do not, and there is no fourth chip`() {
        val rail = view.findViewById<HorizontalScrollView>(R.id.categoryScroll)
        assertNotNull("the category row still scrolls", rail)
        // Containment is the assertion. A two-block non-scrolling rail would pass a test that only
        // checks an id exists; this one fails, because the category group must sit inside the scroll.
        // The double `.parent` is the new band 3.6 adds: scroller → LinearLayout(categoryBand) → ChipGroup.
        // Today the ChipGroup is the scroller's DIRECT child, so this is a genuine red, and the step that
        // makes it green is the one that wraps the band — which is why the wrapper is named in the prose
        // above rather than left for the implementer to invent.
        assertSame("the category group is inside the scroller's band",
            rail, view.findViewById<ChipGroup>(R.id.chipGroupCategory).parent.parent)
        // NOT `assertSame(view, chipGroupStatus.parent)`: the inflated root here is the CoordinatorLayout,
        // while the status group's parent is the inner ConstraintLayout, so that comparison is false today
        // and false after the change. What the design promises is that the status chips are not inside the
        // scroller, and that is what this walks for.
        var ancestor: View? = view.findViewById<ChipGroup>(R.id.chipGroupStatus).parent as? View
        var insideScroller = false
        while (ancestor != null) {
            if (ancestor === rail) insideScroller = true
            ancestor = ancestor.parent as? View
        }
        assertFalse("the status chips are not inside the scroller, so all three stay on screen",
            insideScroller)
        assertEquals(
            "the app has four statuses and the active list can hold three of them",
            listOf(R.id.chipTodo, R.id.chipInProgress, R.id.chipPaused),
            view.findViewById<ChipGroup>(R.id.chipGroupStatus).let { group ->
                (0 until group.childCount).map { group.getChildAt(it).id }
            }
        )
    }

    /**
     * The chip's number is a function of `TasksState`, so the test is about that function and builds no
     * view model: `TasksViewModel` takes four repositories through Hilt, and constructing it here would
     * mean either mocking them or wiring a test component this project does not have. An earlier draft of
     * this block wrote `val viewModel = /* the fragment's view model */`, which is a comment in an
     * expression position — a syntax error, not a test.
     */
    private fun activeFilterCount(state: TasksState): Int = listOfNotNull(
        state.filterCategoryId, state.filterStatus, state.searchQuery.ifBlank { null }
    ).size

    @Test fun `an active filter counts one, three, or nothing, and never the empty query`() {
        assertEquals(1, activeFilterCount(TasksState(filterStatus = null, searchQuery = "x")))
        assertEquals(1, activeFilterCount(TasksState(searchQuery = "   ")))
        assertEquals(3, activeFilterCount(TasksState(
            filterCategoryId = 4L, filterStatus = TaskStatus.TODO, searchQuery = "x")))
        assertEquals(0, activeFilterCount(TasksState()))
    }

    @Test fun `the count and the boolean the header already reads never disagree`() {
        listOf(
            TasksState(), TasksState(searchQuery = "x"), TasksState(filterCategoryId = 2L),
            TasksState(filterStatus = TaskStatus.PAUSED), TasksState(filterCategoryId = 2L, searchQuery = "x")
        ).forEach { state ->
            assertEquals("hasActiveFilters and the chip's number must agree",
                if (state.hasActiveFilters) 1 else 0, activeFilterCount(state).coerceAtMost(1))
        }
    }
```

  `Filters · n` counts what `TasksState` already knows: `hasActiveFilters` is the boolean, and the number is
  `listOfNotNull(filterCategoryId, filterStatus, searchQuery.ifBlank { null }).size` — the helper above is
  that line, and 3.6 puts the same expression in the fragment. No new view-model field, because the three
  facts are already published. The chip's click reaches `clearFilters()`, which already exists; the reach is
  what 3.5's wiring test asserts, not this one.

- [x] **3.7 Selection mode uses the include.** `fragment_tasks.xml` carries a `selectionBar`
  `MaterialCardView` with its own four ids inline. Replace the card's body with the include and give the
  **include** the id `selectionBar`, dropping it from the `MaterialCardView` — an id declared twice in one
  tree is ambiguous, and ViewBinding resolves the include's id over the included root's own, so the card
  keeps no reachable id at all. `selectionCount`, `selectAllButton`, `deleteSelectedButton` and
  `cancelSelectionButton` are then reached as `binding.selectionBar.selectionCount` and so on, which is a
  real change to every call site in the fragment and must be listed as such. **`bar_selection.xml`'s root is
  the `MaterialCardView` itself**, not a layout wrapped around it: `TasksFragment:235` sets
  `binding.selectionBar.visibility`, and for an include with an id, `binding.selectionBar` is the included
  binding whose `root` is the included root view — so if the root were an inner container, the card would
  keep no id, no reachable visibility, and could never be hidden. Spec §4.1 says selection mode replaces the header; implement that as the header
  going `GONE` and the bar going `VISIBLE` in the same `when (state.selectionMode)` branch that exists now,
  and assert both in `TasksHeaderLayoutTest`:

```kotlin
    @Test fun `selection mode hides the header and shows the bar, not both`() {
        // Inflated through the same fixture as the header tests above, then driven from the state the
        // fragment already has — no comment-as-body here: an empty @Test passes whether or not the
        // branch exists, which is the false pass this document has been written to remove.
        val header = view.findViewById<View>(R.id.tasksHeader)
        val bar = view.findViewById<View>(R.id.selectionBar)

        showSelectionMode(true)
        assertEquals("the bar is the header's replacement, not its neighbour", View.VISIBLE, bar.visibility)
        assertEquals("and the header is gone, not merely overlapped", View.GONE, header.visibility)

        showSelectionMode(false)
        assertEquals(View.GONE, bar.visibility)
        assertEquals(View.VISIBLE, header.visibility)
    }
```

  `showSelectionMode(Boolean)` is the fixture's two-line helper that submits a `TasksState` with
  `selectionMode` set and idles the main looper, the way `TaskCardWorkTest` already drives the real
  fragment; it belongs to this file and is declared there.

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

- [x] **4.1 Write the presentation model and its tests first.** New
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
            // Read once into a local: `task.dueDate != null && task.dueDate < now` smart-casts only while
            // the property and its getter are in the same module, and a local makes the predicate read
            // the way the DAO's SQL reads — one value, compared once.
            val due = task.dueDate
            val overdue = due != null && due < now && task.status != TaskStatus.COMPLETED
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
package com.taskplanner.ui.tasks

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.taskplanner.R
import com.taskplanner.data.model.Priority
import com.taskplanner.data.model.Task
import com.taskplanner.data.model.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Calendar

/**
 * The presentation arithmetic of spec §4.1, with the clock held still.
 *
 * `now` is a parameter precisely so that the boundary — 23:59 against 00:01 — can be named in a test. A
 * computation that read `System.currentTimeMillis()` inline cannot be tested at the one moment where it is
 * wrong, which is the failure mode this repository already met once (WorkTrackingRepositoryTest, stage G).
 */
@RunWith(RobolectricTestRunner::class)
class CardPresentationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** 2026-10-23 00:00:00.000 local, so every case below is decided against one fixed instant. */
    private val midnight = Calendar.getInstance().apply {
        set(2026, Calendar.OCTOBER, 23, 0, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** One row, four knobs. The defaults are the ordinary TODO task with no deadline. */
    private fun present(
        due: Long? = null,
        now: Long = midnight,
        priority: Priority = Priority.MEDIUM,
        status: TaskStatus = TaskStatus.TODO
    ) = CardPresentation.of(
        context,
        TasksListItem.TaskRow(
            task = Task(id = 1, uuid = "presentation", title = "Present", description = "",
                userId = 1, status = status, dueDate = due, priority = priority),
            category = null, isSelected = false
        ),
        now
    )

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
        assertEquals("No due date", present(due = null).dueLine.toString())
        assertTrue(present(due = midnight - 3 * DAY, now = midnight).dueLine.contains("3"))
    }

    private companion object {
        /** Named here rather than imported: the fixture states its own arithmetic. */
        const val DAY = 86_400_000L
    }
}
```

  `no_due_date` is `"No due date"` in `values/strings.xml:139` today, which is why the first assertion can
  name the text: the string is reused, not invented. `R.color.ink_error` and `ink_warning` are the names 1.4
  writes, so this class is written after 1.4 and before 4.1's implementation — its RED is the four
  behaviours above, none of which today's adapter performs.

  *Executed.* The class is the plan's, with two additions the tests asked for: `repeatDescriptionRes`,
  because spec §4.1 moves the rate to the line-A icon and the icon then has to say what the due line used to
  say; and a "late today" branch — the plan's `dueLine` printed "0 days late" for a deadline that passed nine
  hours ago, which is a sentence about nothing, so the day count now starts when the date has actually rolled
  over (`task_late_today`).

- [x] **4.1a Skeleton for the card.** Declare `cardContent`, `categoryDot`, `repeatIcon` and `lineBFlow` in
  `item_task.xml` inside the existing structure, and `CardPresentation` as a data class with the fields and no logic, in one commit. 4.1's and 4.2's tests then compile and fail on behaviour. Without this step the predicted RED in 4.3 is a compile error, which is the mistake §3's rule exists to prevent.

  The same commit adds the copy `CardPresentation` names, because it is production source under
  `app/src/main` and `R.plurals.task_late_days`, `R.string.task_due_today_format` and
  `R.string.task_due_tomorrow_format` must exist the moment the file that references them is committed:
  `values/strings.xml` is the only locale folder in this repository, so each needs exactly one entry —
  `task_due_today_format` (`"Today %1$s"`), `task_due_tomorrow_format` (`"Tomorrow %1$s"`), a
  `<plurals name="task_late_days">` (`"%d day late"` / `"%d days late"`); `no_due_date`, `status_todo`,
  `status_in_progress`, `status_paused` and `status_done` already exist (verified by grep) and are reused
  rather than duplicated. An earlier draft put this in a step after 4.3, which meant 4.1's own commit could
  not build — the strings landed two commits after the code that names them.

- [x] **4.2 Write the width-budget test** — `TaskCardWidthBudgetTest`, the 320dp contract. It measures, not
  eyeballs:

```kotlin
package com.taskplanner.ui.tasks

import android.content.Context
import android.content.res.Configuration
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.taskplanner.R
import com.taskplanner.data.model.Category
import com.taskplanner.data.model.RecurrenceRule
import com.taskplanner.data.model.RepeatUnit
import com.taskplanner.data.model.Task
import com.taskplanner.data.model.TaskStatus
import com.taskplanner.data.tracking.TrackingPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode

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
    private fun Int.asDp() = toFloat() / context.resources.displayMetrics.density

    private companion object {
        const val DAY = 86_400_000L

        /** A real stored series, so the repeat icon is drawn from data and not from a mock. */
        fun aWeeklyRule() = RecurrenceRule(
            userId = 1, unit = RepeatUnit.WEEK, interval = 1, weekDays = "1,3",
            dayOfMonth = 0, anchorAt = 0, cursorAt = 0
        )
    }

    /** Copied from TaskCardWorkTest, which needs the same patience for ListAdapter's async diff. */
    private fun awaitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("no $what")
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    /**
     * The worst legal row, with the two inputs that decide the budget pinned rather than defaulted.
     *
     * `priority = HIGH` because LOW draws no rule, and a rule measured at zero would quietly move the
     * 64dp column. `sortOrder = MANUAL` because anything else hides the drag handle and the column
     * collapses to 12dp — which is its own case, asserted separately below.
     */
    private val categoryColor = 0xFF2F6F5E.toInt()   // ink_accent, a real ARGB, not the default 0

    private fun bindWorstCase(
        widthDp: Int = 320, fontScale: Float = 1.0f, sortOrder: SortOrder = SortOrder.MANUAL,
        // Thirty days ahead: an overdue row prints "3 days late", which is SHORTER than the full date the
        // 84dp allowance is defined against, so a fixture that was itself overdue never measured the
        // widest legal date. Ahead rather than behind also keeps the wording out of the today/tomorrow
        // branches, whose strings the 4.1 tests already cover.
        due: Long = System.currentTimeMillis() + 30L * DAY
    ): View {
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
                        userId = 1, status = TaskStatus.IN_PROGRESS, dueDate = due
                    ),
                    // A real colour: `color = 0` is transparent, and a dot tinted from it "carries the
                    // colour" while painting nothing, which is the exact defect spec §4.1 exists to fix.
                    category = Category(id = 1, uuid = "c", name = "Диссертация", color = categoryColor,
                        icon = "", userId = 1),
                    isSelected = false, series = aWeeklyRule(), pendingReminders = 2,
                    work = CardWork(closedSeconds = 6_300, liveSeconds = 60, phase = TrackingPhase.RUNNING)
                )
            )
        )
        awaitUntil("the row to be diffed") { adapter.itemCount == 1 }
        adapter.reorderEnabled = sortOrder == SortOrder.MANUAL
        val holder = adapter.createViewHolder(FrameLayout(context), adapter.getItemViewType(0))
        adapter.bindViewHolder(holder, 0)
        return holder.itemView.also {
            it.measure(
                View.MeasureSpec.makeMeasureSpec(dp(widthDp), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            it.layout(0, 0, dp(widthDp), it.measuredHeight)
            // Layout is synchronous, but the category name's 48dp rule runs in a `doOnLayout` post, so a
            // reader that returns here sees the visibility from BEFORE the decision. One idle makes the
            // posted work happen inside the fixture — without it the `name.visibility` assertions below
            // would read a stale GONE/VISIBLE and pass on a card that had decided differently.
            shadowOf(Looper.getMainLooper()).idle()
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
        val handle = row.findViewById<View>(R.id.dragHandle)
        val rule = row.findViewById<View>(R.id.priorityIndicator)
        assertEquals("a non-MANUAL sort hides the handle", View.GONE, handle.visibility)
        // A GONE view contributes no width, and the 4dp that separated it from the rule goes with it,
        // so the term has to be conditioned rather than added unconditionally: 0 + 4 + 4 + 8 is 16, not 12.
        val left = (if (handle.visibility == View.GONE) 0 else handle.width + dp(4)) + rule.width + dp(8)
        assertEquals("handle gone, so the column is the rule plus its gap", dp(12), left)
        // And the claim the bind path makes, measured as a difference rather than as a magic number:
        // the same row re-bound with a MANUAL sort keeps the handle, and the title is 52dp narrower.
        val manual = bindWorstCase(widthDp = 320, sortOrder = SortOrder.MANUAL)
        val collapsedTitle = row.findViewById<TextView>(R.id.taskTitle).width
        val manualTitle = manual.findViewById<TextView>(R.id.taskTitle).width
        assertEquals("the handle is visible and costs what the budget says", View.VISIBLE,
            manual.findViewById<View>(R.id.dragHandle).visibility)
        assertTrue("title ${collapsedTitle.asDp()}dp vs ${manualTitle.asDp()}dp",
            collapsedTitle >= manualTitle + dp(52))
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
    @Test fun `at font scale one point three the card grows and nothing is dropped, clipped or shrunk`() {
        val row = bindWorstCase(widthDp = 320, fontScale = 1.3f)
        val baseline = bindWorstCase(widthDp = 320, fontScale = 1.0f)
        val content = row.findViewById<View>(R.id.cardContent)

        listOf(R.id.taskDueDate to "the date", R.id.taskStatus to "the status word").forEach { (id, what) ->
            val tv = row.findViewById<TextView>(id)
            // Needed-vs-own-width is dropped on purpose: these two views are wrap_content, so their width
            // IS their measured text and the comparison can never fail. What can fail is the layout
            // clipping them, and that is visible only in the Layout — see the same correction in the
            // 1.0-scale test above.
            val clipped = (0 until tv.layout.lineCount).sumOf { line -> tv.layout.getEllipsisCount(line) }
            assertEquals("$what is clipped in ${tv.layout.lineCount} line(s)", 0, clipped)
            // The overflow that actually happens at 1.3: the line runs past the card, not the text past
            // its own box. This is the assertion the earlier draft was missing.
            assertTrue("$what runs past the content's right edge",
                tv.right <= content.width - content.paddingRight)
        }

        val due = row.findViewById<TextView>(R.id.taskDueDate)
        val pill = row.findViewById<TextView>(R.id.taskStatus)
        val dueAtOne = baseline.findViewById<TextView>(R.id.taskDueDate)
        assertEquals("line B is one line at the default scale", 1, dueAtOne.lineCount)
        assertTrue("the type grew rather than being scaled down", due.textSize > dueAtOne.textSize)
        // The reflow itself, measured rather than asserted: at 1.0 the status pill and the date share a
        // top edge; at 1.3 the Flow wrapped the date onto a second row, so its top is below the pill's.
        // A plain chain cannot do this and would have squeezed the date instead, which is why 4.4 names
        // the helper rather than leaving "line B reflows" as prose.
        assertTrue("line B wrapped: pill.top ${pill.top}, date.top ${due.top}", due.top > pill.top)
        assertEquals("and nothing vanished when it wrapped", View.VISIBLE, pill.visibility)

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
        val content = row.findViewById<View>(R.id.cardContent)
        listOf(R.id.taskDueDate, R.id.taskStatus).forEach { id ->
            val tv = row.findViewById<TextView>(id)
            val name = row.resources.getResourceEntryName(id)
            val layout = tv.layout
            assertNotNull("$name has no layout to inspect", layout)
            // The only assertion that can actually see truncation. A TextView's own text is never the
            // ellipsised string — the ellipsis lives in the Layout, not in mText — so the earlier draft's
            // `assertFalse(tv.text.endsWith("…"))` passed on a card that had clipped the date away.
            val clipped = (0 until layout.lineCount).sumOf { line -> layout.getEllipsisCount(line) }
            assertEquals("$name is clipped in ${layout.lineCount} line(s)", 0, clipped)
            // And it fits the box the budget reserves, which is a real constraint, unlike its own width:
            // a wrap_content view always satisfies width >= measureText(its own text), so that
            // comparison proved nothing about the card, only about the view measuring itself.
            assertTrue("$name ends at ${tv.right}, past the content edge ${content.right - content.paddingRight}",
                tv.right <= content.right - content.paddingRight)
            assertTrue("$name starts before the content edge", tv.left >= content.paddingLeft)
        }
    }

    @Test fun `the category dot is always on screen and its name only when it can say something`() {
        val row = bindWorstCase()
        val name = row.findViewById<TextView>(R.id.taskCategory)
        val dot = row.findViewById<View>(R.id.categoryDot)
        val title = row.findViewById<View>(R.id.taskTitle)
        assertTrue("the dot heads line A, so it is left of the title", dot.left < title.left)
        assertEquals("and the dot always carries the colour", View.VISIBLE, dot.visibility)
        // "Carries the colour" has to mean something measurable. The fixture's category used to be built
        // with `color = 0`, which is transparent, so a dot that painted nothing satisfied the assertion —
        // the very defect spec §4.1 exists to fix. Tint is how this adapter already colours a dot
        // (`TasksAdapter:237`), so that is what the test reads.
        assertEquals("the dot is tinted with the row's own category colour", categoryColor,
            dot.backgroundTintList?.defaultColor)
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

    @Test fun `nothing overlaps, and each line keeps its own band`() {
        val row = bindWorstCase()
        val dot = row.findViewById<View>(R.id.categoryDot)
        val title = row.findViewById<View>(R.id.taskTitle)
        val repeat = row.findViewById<View>(R.id.repeatIcon)
        val pill = row.findViewById<View>(R.id.taskStatus)
        val due = row.findViewById<TextView>(R.id.taskDueDate)
        val group = row.findViewById<View>(R.id.buttonGroup)

        // Line A is dot, title, icons — in that order, so each right edge precedes the next left.
        assertTrue("dot ${dot.left} before title ${title.left}", dot.left <= title.left)
        assertTrue("title ${title.right} before the repeat icon ${repeat.left}", title.right <= repeat.left)
        // Line B is pill, then date, then the name (spec 4.1). Asserting it the other way round would
        // pass on a card that had swapped them, which is the whole thing this test is for.
        assertTrue("pill ${pill.right} before the date ${due.left}", pill.right <= due.left)
        // Bands: no view from one line may share a vertical band with the next line.
        assertTrue("line A bottom ${title.bottom} at or above line B top ${pill.top}", title.bottom <= pill.top)
        assertTrue("line B bottom ${due.bottom} at or above line C top ${group.top}", due.bottom <= group.top)
        assertEquals("the date is one line tall at the default scale", 1, due.lineCount)
    }
}
```

  *Executed.* The JVM class measures the laid-out row; a second class, `TaskCardWidthBudgetInstrumentedTest`,
  measures what a JVM cannot: Robolectric's LEGACY graphics answer `Paint.measureText` with the character count
  and ignore `textSize`, and `@GraphicsMode(NATIVE)` has no platform binary on this machine, so the glyph-bound
  claims — the date and the status word drawn whole, line B's reflow, the 82dp and 84dp allowances, the 48dp
  name rule — are asserted on the device at six configurations instead of being guessed at here.

- [x] **4.3 Run 4.1 and 4.2 and watch them fail.** They compile — 4.1a declared the ids, the data class and
  the strings — and they fail on behaviour: the pill is still the old uncapped caption so its allowance is exceeded, the category name is still drawn at 320dp, and `priorityIndicator` is still painted from `R.color.priority_*` rather than from `CardPresentation`, so a LOW row still shows a rule. If either class fails to *compile* here, 4.1a missed a name — fix that, and do not touch the assertions.

  *Executed, and the RED was not the predicted one.* The pill allowance was not exceeded because nothing
  capped it: `item_task.xml` gave the pill the `label` role, whose `textAllCaps` is exactly what spec §3.3 and
  `type.xml`'s own comment exist to avoid, and JVM metrics cannot see a case change. The category rule failed
  differently again — see 4.5. A third failure came from lint rather than from a test: `ConstantLocale` on the
  two static `SimpleDateFormat`s this step's snippet holds in a companion, which a locale change mid-session
  makes wrong; the formatter is now built where it is used, pinned by
  `a date line follows the locale the device is on`.

- [x] **4.3b Nothing here.** This step used to add the strings `CardPresentation` says, in the position of a
  step that came after the code that names them — which meant 4.1's commit could not build. They moved into
  4.1a. The number is kept so the references to "4.3b" elsewhere in this document and in the review log do
  not silently point at a different step; a worker who reaches this line should go straight to 4.4.

- [x] **4.4 Rewrite `item_task.xml`** to the three-line structure of spec §4.1, flattening the current
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

  **Line B needs a wrapping mechanism, and the plan has to name it.** A horizontal chain in a
  `ConstraintLayout` does not wrap: at font scale 1.3 the pill (≈102dp) plus the widest date (≈109dp) plus
  their gaps is 227dp against a 208dp column, and a chain would simply squeeze all three into one row,
  clipping the date — the exact outcome spec §4.1 forbids, arrived at by a layout that "followed the spec"
  and had no way to reflow. The mechanism is a `Flow` helper
  (`androidx.constraintlayout.helper.widget.Flow`, `app:flow_wrapMode="chain"`, constraintlayout 2.1.4 is
  already the resolved artifact) over `taskStatus`, `taskDueDate` and `taskCategory`, and it is
  deliberately a *helper*: it positions children that stay direct children of `cardContent`, so
  `TaskCardWorkTest:189-193`'s locked assertion that `taskTitle.parent == workRow.parent` keeps holding,
  which a per-line wrapper container would break. Its id, `lineBFlow`, is declared in the 4.1a skeleton
  along with the three view ids, because 4.2's reflow test names it.

  Three view **types** are locked by tests and may not change while the ids survive: `workRow` is fetched
  as a `LinearLayout` (`TaskCardWorkTest:110`), `workControlButton` as an `ImageButton` (`:131`) and
  `reminderBadge` as an `ImageView` (`TaskCardReminderTest:97`). Swapping the LinearLayout for a second
  ConstraintLayout, or the ImageButton for a MaterialButton, fails three tests that never mentioned the
  thing being changed. That is why line C's cluster stays a `LinearLayout` and the buttons stay
  `ImageButton`s styled rather than replaced.

  `taskDescription` stays in the layout but is `View.GONE` unless the task has a description — the current
  behaviour — and when visible it takes a line between A and B.

  *Executed.* The structure is as written, with one correction to this stage's own arithmetic: line B's three
  children are positioned by a `Flow` helper, and a `Flow` never narrows a child — it wraps it — so the rule
  4.5 first wrote, which measured the name's own width, could never fire.

- [x] **4.5 Rewrite the bind path.** `TasksAdapter.TaskViewHolder.bind` calls
  `CardPresentation.of(context, item, now)` where `now` comes from a `timeBase` the adapter is given, and
  applies it: pill text/background/colour, rule colour or `INVISIBLE`, overdue `ink_error` on the due line,
  the four `showX` flags as `View.GONE`/`VISIBLE`, the category name's 48dp rule measured against the
  laid-out width in `doOnLayout`. Two of those are deliberately different mechanisms, and the difference is
  load-bearing for 4.2's arithmetic: the **priority rule** goes `INVISIBLE` for LOW so the 4dp column it
  occupies is still reserved and the title does not jump between a HIGH row and a LOW row, while the
  **drag handle** goes `GONE` off a `MANUAL` sort precisely so its 48dp + 4dp collapses and the text column
  gains 52dp (the `collapsed` case in 4.2). So "the left column is 64dp" and "the column is the rule plus its
  gap, 12dp" can both be true: the first counts a visible handle, the second counts a hidden one plus a rule
  that is invisible but not gone. Three further rules from spec §4.1 belong in the same bind path, because
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

  *Executed.* The category rule is decided on the **room left on the line** — `lineBFlow.width` minus the
  measured pill, the measured date and their two gaps — against the 48dp floor, not on the name's own width.
  Measured on the device: at 393dp the line is 363px, the pill 107px and the date 108px, which leaves 126px
  (91dp) and the name is drawn; at 320dp the line is 321px against a pill of 136px and a date of 131px, which
  leaves 26px against an 81px floor, and the name is gone with the dot still carrying the colour. The line-C
  give-way test runs on the last **visible** figure: the ticker is gone on every card that is not being
  measured, and the first draft read `childCount - 1`, which is the ticker, so the rule could not fire on an
  idle row. And the rule is tinted through `bg_priority_indicator` with `backgroundTintList` rather than
  painted with `setBackgroundColor`, which replaces the drawable and with it the 2dp corners the token
  carries.

- [x] **4.6 Inject the clock.** `TasksViewModel` gains one defaulted constructor parameter, and
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

  *Executed with the smaller of the two shapes this step offers.* The adapter takes a defaulted
  `timeBase: TimeBase = AndroidTimeBase()` and `bind` reads `timeBase.wallMillis()` once per row; the fragment
  passes nothing and `TasksViewModel` gained no parameter. The rule the step actually states — no
  `System.currentTimeMillis()` anywhere in `ui/tasks/` — holds: the grep returns nothing. Tests inject
  `FixedTimeBase`, which is what makes the 23:59/00:01 boundary nameable at all.

- [x] **4.7 Run the gates.** Full suite, then the Tasks group, then `assembleDebug`, then `lintDebug`
  (a new `contentDescription` gap is a lint finding, and lint must not regress).

  *Executed:* 1442 JVM tests over 104 classes, 0 failures, 0 errors, 0 skipped; `lintDebug` 0 errors and 179
  warnings, down from 182 — the two `ConstantLocale` findings and one dead string went with the fixes;
  `assembleDebug` and `assembleDebugAndroidTest` clean. The three were run again as one command after the last
  card change (`workTime`'s `marginStart` moved from a literal to `@dimen/spacing_xs`) and returned the same
  numbers — 104/1442/0/0/0, 0 errors and 179 warnings, `BUILD SUCCESSFUL in 10m 3s`, exit code 0 — and the same
  APK hash, which is how 4.8 could say the photographs are of the tested binary.

- [x] **4.8 Device evidence.** Install, then photograph the card states that the tests claim: TODO,
  IN_PROGRESS, PAUSED, overdue, LOW with no rule, a 120-character title, a running stretch, selection mode,
  and the same card at 320dp and at font scale 1.3. Compare each against `before/01-tasks.png` with
  `design-review:visual-regression-review` and write down what changed and why it is better, not merely
  different.

  *Executed on `taskplanner_avd` / `emulator-5584` and on no other device.* The built `app-debug.apk`
  (`6c9c0408c93de2e859419e8bbc7c5539328b30479c6fc409786885193b727a0d`) was installed and the installed
  `base.apk` was pulled back from `/data/app/~~9H6egSJCKrNGeSEsgs-X_w==/com.taskplanner-…/base.apk` and
  hashed to the same value; the gates were re-run after the last token change and `assembleDebug` returned
  that same hash, so the photographs below are of the binary the tests ran against.
  `TaskCardStatesInstrumentedTest` (12 tests) and `TaskCardWidthBudgetInstrumentedTest` (7) are green at 393dp,
  360dp and 320dp at font scale 1.0, at 320dp and 393dp at 1.3, and at 393dp in the dark theme — 114 test runs
  across six configurations. Those six came in two passes: the first pass lost its transport at the fifth
  configuration (`adb.exe: device 'emulator-5584' not found`, four times, then `device offline`), which
  cancelled the width class at 393dp/1.3 and left the dark configuration running at the previous font scale
  because its `settings put` never landed. Both configurations were redone in a second pass that refuses to
  run a test until the device reports the configuration it was asked for, and the dark photographs are from
  that pass. See §20l for the readback that makes the claim checkable.
  The photographs are `after/s4-card-todo.png`, `-inprogress`, `-paused`, `-overdue`, `-longtitle`, `-running`,
  plus `after/s4-swiped.png`, `after/s4-selection.png`, `after/s4-393.png`, `after/s4-360.png`,
  `after/s4-320.png`, `after/s4-font13.png` (320dp at 1.3, where line B reflows and the category name gives
  way), `after/s4-393-font13.png`, `after/s4-dark.png` (the worst row, dark, font 1.0) and
  `after/s4-dark-running.png` (a measured, running card in the same theme).

- [x] **4.9 If the budget does not close in code** — a measured due date wider than 84dp, a pill wider
  than 82 — **stop**. Do not shrink a button, a target or a font to make a test pass. Report the measured
  numbers and propose the spec amendment (a different line assignment, or a shorter status word), and wait.
  This is the one step in the plan whose correct failure mode is a conversation.

  *Not triggered: the budget closed, and it closed on measurement rather than on assumption.* The pill's real
  width at 393dp is 107px = 78dp against the 82dp allowance, and the widest date a card can be given is
  108px = 79dp against 84dp; both are asserted on the device by
  `thePillAndTheDateStayInsideTheirBudgetedAllowances`. Nothing was shrunk to get there — the roles are the ones
  §3.3 names, and the two elements that may not drop are asserted whole at every configuration.

- [x] **4.9a Fragment-level render coverage** — `TasksFragmentRenderTest`, the file spec §8 asks for and
  which §17 previously mapped only at the header, so the row-level tests were standing in for it. Robolectric
  with `ActivityScenario`-free fragment construction, the way `ui/util/WorkAdjustmentUiTest` already attaches
  a real fragment under Robolectric — it is this project's only fragment-level test, added by PR #21, and it
  is the pattern to copy rather than invent. Five assertions, each over a seeded
  `TasksState` rather than a mock: topic `GroupHeader`s are drawn above their rows and the row count matches
  the state; the three status chips are present and a fourth is not; an overdue row carries the overdue
  treatment while the dashboard's own count for the same data calls it late (spec §0's no-second-rule test,
  run through the screen); a long title wraps to two lines rather than clipping; and the empty state appears
  for an empty list. No Hilt test component is needed — the fragment is constructed and its state driven
  through the view model's public seam, exactly as `TaskCardWorkTest` drives the adapter.

  *Executed as `TasksCardRenderTest`, with one substitution stated rather than hidden.* This step names a
  fragment-level file, and `TasksFragment` is `@AndroidEntryPoint`: attaching it under Robolectric needs
  `hilt-android-testing` and a test application — a new dependency and a new component, which the brief allows
  only with cause. What the step wants is the render path covered over seeded state, and Stage 4's decisions
  live in the adapter and in `CardPresentation`; the fragment only forwards state. The class drives the real
  `TasksViewModel` over a real `AppFixture` database and reads the painted rows back, and it carries the step's
  assertions: rows reach the screen in the state's order, read off the views; the overdue treatment agrees with
  the dashboard's own count for the same data (spec §0's no-second-rule test); a long title takes two lines and
  stops; an empty account paints nothing; and the work control is hidden when nothing is measured *and* wired
  when something is. The fragment itself is run on a device by `TasksHeaderInstrumentedTest`, and the three
  status chips and the fourth-absent assertion belongs to the header, where `TasksHeaderLayoutTest` and
  `TasksHeaderInstrumentedTest` hold it.

- [x] **4.10 Commit**, listing in the message every test that moved and why its old assertion was the
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
- [ ] **7.3** A JVM test asserting all five labels are visible at once — the baseline shows only "Tasks" —
  and that the bar's top edge carries the hairline view 7.1 adds (`bg_hairline`, 1dp, `ink_outline_strong`),
  which is the separation the acceptance above actually measures.
- [ ] **7.4** Gates; screenshots of all five tabs at 393dp and 320dp, light and dark; commit.

**Acceptance, each one checkable:** `uiautomator dump` after the change lists five `text` nodes under
`bottom_navigation` where today it lists one; the active item's `contentDescription`-adjacent state carries
the indicator drawable and a different text style from the other four, which the same dump shows; and the bar
is separated from the page by a **drawn hairline**, not by a colour difference: `7.4` reads the resolved
bar background and asserts a 1dp `ink_outline_strong` rule is present above it. The figure is stated rather
than promised, because the promise was false — measured with the script's own luminance function,
`ink_surface` against `ink_background` is **1.05:1** light and **1.07:1** dark, and no surface token in
§4's table reaches 1.3:1 against the page (`ink_surface_variant` in dark is the best case at 1.21:1). An
earlier draft of this acceptance set the bar at 1.3:1, which no token in this design system can satisfy, so
the stage could only have closed by editing a measured token or deleting the criterion — both forbidden.
Spec §4.4 asks for "background from a surface role", which 7.1 delivers; the visible separation is the
hairline's job, and `ink_outline_strong` measures 3.73 / 3.78 against a surface, so the line is legible as
a boundary. **Screenshots:** `s7-nav-<tab>.png` ×5, `s7-nav-320.png`,
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
role; the printed report colours are unchanged, because a report is paper; and the figure row from audit
finding 9 — `fragment_statistics.xml:93-118`, three weighted TextViews with no gutter, which is why
`before/03-statistics.png` reads "1 task finished14 tasks added" — has a measured gap between its columns
and an after-photograph where the two figures are two figures.

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

- [ ] **8.4b The three state includes get their consumers — and this step is the consumer's editor.** The
  parentheticals below name the layouts, not other stages: 4.4 and 5.4 never touch an empty or loading view,
  so every replacement in this step is performed here, in this commit, against
  `fragment_tasks.xml`, `fragment_profile.xml`, `fragment_statistics.xml` and `fragment_reports.xml` (all
  four are listed as S8-owned edits in §7's overlap note, and §8's S8 row now carries them).
  `state_empty.xml` is used by Insights (8c replaces the inline empty row `item_insights_empty.xml`
  inflates) and by Tasks, where `emptyView` is a `LinearLayout` with one `TextView` today
  `fragment_tasks.xml:164`, whose `emptyView` id is on the block's own `TextView` at `:165`). `state_loading.xml` replaces the bare `ProgressBar` at
  `fragment_tasks.xml:156`, the one on Statistics and the one on Profile, so a wait is the same shape on all
  three. `state_error.xml` adds an in-place error block to Statistics and Reports; today both report a failed
  read through a `Snackbar` (`StatisticsFragment:100-101`, `ReportFragment:516`) and leave the body empty,
  which is a real statement about the body but not, as an earlier draft had it, that "nothing is shown" —
  a snackbar does appear, it just disappears. This step keeps the snackbar and adds the block. Each is a layout
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

  **Declare what the class reads before writing the class** — `motion_entry_rise` (12dp) in
  `res/values/dimens.xml` and `motion_standard` (180) in `res/values/anim.xml` (an `<integer>`, read with
  `getInteger()`). A Kotlin file that names `R.dimen.motion_entry_rise` before the resource exists is a
  compile error, not a red test, and §3's rule is that a step's RED must be an assertion failing.

```kotlin
package com.taskplanner.ui.tasks

import android.content.Context
import android.provider.Settings
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
 *
 * The scale is injected rather than read from `ValueAnimator.getDurationScale()`, for two reasons worth
 * stating in the code: that getter is API 33 (`api-versions.xml`: since="33") and this app's floor is 24,
 * so calling it is a NoSuchMethodError on every device in between and a fresh lint finding the PR criteria
 * forbid; and Robolectric exposes no public setter, so a test could not drive the branch either way. The
 * default reads the system setting, which is API 17 and is what the getter itself reports.
 */
class CardItemAnimator(
    private val context: Context,
    private val animatorScale: () -> Float = {
        Settings.Global.getFloat(
            context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f
        )
    }
) : DefaultItemAnimator() {

    /** Holders with a fade in flight — the set 9.3's ceiling counts, and the reason it is a set. */
    private val fading = mutableSetOf<RecyclerView.ViewHolder>()

    override fun animateAdd(holder: RecyclerView.ViewHolder): Boolean {
        val view = holder.itemView
        if (animatorScale() <= 0f) {
            view.alpha = 1f
            view.translationY = 0f
            dispatchAddStarting(holder)
            dispatchAddFinished(holder)
            return false
        }
        val ceiling = context.resources.getInteger(R.integer.motion_entry_max)
        if (fading.size >= ceiling) {
            // 9.3's threshold, implemented rather than described: a submitList that brings in two hundred
            // rows would otherwise start two hundred ViewPropertyAnimators inside one frame. Past the
            // ceiling a card simply appears, which is a decision a test can observe.
            view.alpha = 1f
            view.translationY = 0f
            dispatchAddStarting(holder)
            dispatchAddFinished(holder)
            return false
        }
        endAnimation(holder)
        fading += holder
        view.alpha = 0f
        view.translationY = context.resources
            .getDimensionPixelSize(R.dimen.motion_entry_rise).toFloat()
        dispatchAddStarting(holder)
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(context.resources.getInteger(R.integer.motion_standard).toLong())
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                fading -= holder
                dispatchAddFinished(holder)
            }
            .start()
        return true
    }

    // A cancelled ViewPropertyAnimator never runs its end action, so the count has to leave through the
    // doors the framework actually calls: recycling a holder goes through endAnimation, and a layout that
    // abandons everything goes through endAnimations. Without these two, a fast scroll would strand
    // entries in `fading` and the ceiling would silently disable every later entrance.
    override fun endAnimation(holder: RecyclerView.ViewHolder) {
        fading -= holder
        super.endAnimation(holder)
    }

    override fun endAnimations() {
        fading.clear()
        super.endAnimations()
    }

    /** A reorder is not news, and a status change cross-fades in bind: only the add animates. */
    override fun getMoveDuration(): Long = 0L
    override fun getChangeDuration(): Long = 0L

    /** Readable from a test, which is how 9.5 asserts the ceiling without instrumenting the framework. */
    fun runningAddCount(): Int = fading.size
}
```

  Both resources this class reads are declared inside 9.0, above, before the file is written. The class goes
  in `main/java/com/taskplanner/ui/tasks/CardItemAnimator.kt`, beside the adapter that installs it.

  Two facts from `javap -c` on recyclerview 1.1.0 shape the code, and one limitation is stated rather than
  papered over:
  - the end action calls **`dispatchAddFinished`**, not `onAddFinished`. `dispatchAddFinished` is
    `onAddFinished(holder)` followed by `dispatchAnimationFinished(holder)`, and the second half is what
    reaches `mListener.onAnimationFinished` — RecyclerView's own hook for releasing a holder once its
    animation ends. Calling the inner hook alone would fade the card and leave RecyclerView guessing.
  - `dispatchAddStarting`/`dispatchAddFinished` are `public final`, `onAddStarting`/`onAddFinished` are
    `public` and, in 1.1.0, empty — `DefaultItemAnimator` overrides neither. The tracking lives inside
    `animateAddImpl`, which is package-private and therefore not reusable from this package.
  - because the tracking is skipped, `DefaultItemAnimator.mAddAnimations` (package-private, unreachable from
    here) never learns about these fades, so `isRunning()` can report false while a 180 ms entry fade is
    still in flight. Nothing in this design waits on `isRunning()`; the behaviours actually required —
    a scroll starts nothing, a data change starts one, scale 0 starts nothing — are asserted directly on
    `animateAdd` call counts in 9.5, not inferred from the animator's busy flag.
  `TasksFragment.setupRecyclerView()` assigns `recyclerView.itemAnimator = CardItemAnimator(requireContext())`
  once; `fragment_tasks.xml` sets no `app:itemAnimator` today, so there is nothing to unset.

- [ ] **9.1 Status change cross-fades the pill, and only the pill.** The adapter is
  `ListAdapter<TasksListItem, RecyclerView.ViewHolder>(DIFF)` (`TasksAdapter.kt:41`), and `DIFF` is an
  anonymous `DiffUtil.ItemCallback` inside the **companion object** (`:350`), whose `areContentsTheSame` is
  plain data-class equality (`:359`). Two placements matter and an earlier draft of this step got them
  wrong: `PAYLOAD_STATUS` must be declared **in the companion object, next to `VIEW_TYPE_TASK`** — a
  `const` declared inside the anonymous object is not visible to `TasksAdapter.onBindViewHolder`, and the
  build would stop with `unresolved reference: PAYLOAD_STATUS`; and `getChangePayload` is a member of the
  callback, not of the adapter. The adapter then overrides
  `onBindViewHolder(holder, position, payloads)` and, when that list contains `PAYLOAD_STATUS`, re-applies
  the pill text, colour and background plus a 120 ms alpha crossfade, and touches nothing else.

```kotlin
    // TasksAdapter.kt, companion object — sibling of VIEW_TYPE_TASK, so onBindViewHolder can see it.
    private const val PAYLOAD_STATUS = "status"

    // ... inside `private val DIFF = object : DiffUtil.ItemCallback<TasksListItem>() {`
    override fun getChangePayload(o: TasksListItem, n: TasksListItem): Any? {
        if (o !is TasksListItem.TaskRow || n !is TasksListItem.TaskRow) return null
        if (o.task.status == n.task.status) return null
        // The payload is a promise that ONLY the status moved. Projecting the new status back onto the
        // new row must reproduce the old row exactly; both Task and TaskRow are data classes, so this
        // compares every other field — priority, category, reminders, work, selection — without
        // naming them, which is what keeps a field added later from slipping through half-drawn.
        return if (n.copy(task = n.task.copy(status = o.task.status)) == o) PAYLOAD_STATUS else null
    }
```

  An earlier draft compared `title`, `dueDate` and `work.phase` by hand and nothing else, which would have
  returned the pill payload for a row whose priority or reminder count also moved — the crossfade would
  repaint the pill and leave the priority rule drawing the old colour. A stale half-bind is worse than an
  extra full bind, so the check is the identity above rather than a list of fields.

  **What the payload branch may and may not skip.** `TasksAdapter:320-323` derives four button visibilities
  from `task.status`, and spec §4.1 makes exactly one lifecycle control visible per status — so a status-only
  change is precisely the case the payload exists for, and a branch that repainted only the pill would leave
  `startTaskButton` showing on a task that just went to `IN_PROGRESS`, wired to `viewModel.startTask`. The
  payload branch therefore re-runs the whole status-dependent block: the pill text, colour and background,
  the four `show*` visibilities, and the holder's stored row that every click listener reads. What it skips
  is the work-line re-measure and the category text, which no status change can alter. If a later stage adds
  another status-derived control and forgets this list, the `only the lifecycle button the status calls for
  is shown` test in 4.1 catches the full-bind path; the payload path is caught by 9.5's test that a paused
  row shows Resume and not Start after a status-only `submitList`.

- [ ] **9.2 The animator scale, tested rather than asserted.** RecyclerView 1.1.0 — the version this APK
  resolves — does **not** consult
  `ValueAnimator.getDurationScale()` itself — verified against recyclerview **1.1.0**, the artifact this
  build resolves, where neither `isAnimatorDisabled` nor a `getDurationScale` reference exists — so the
  "scale 0 means no motion" behaviour comes from the framework's own animators finishing immediately, not
  from this class, and it is read through the injected `animatorScale` rather than the API: the getter is
  API 33 on a 24 floor, and Robolectric has no public setter, so the seam is the only way to test the branch
  at all. Two consequences, both tested.

  at all. Two consequences, both tested. `CardItemAnimatorTest.kt` builds its own holder: `TaskCardWorkTest`'s
  `bindRow` is a private method of that class, so there is nothing to borrow, and an earlier draft of this
  step called a helper that does not exist outside the file it lives in. The shape is the one
  `TaskCardClosedPanelTest` uses — submit, wait for the diff, `createViewHolder`, `bindViewHolder` — and the
  file carries its own three-line `awaitUntil`, the same idling helper the other two card tests use. `aRow()`
  is that file's own one-row `TasksListItem.TaskRow` builder, constructed the way `TaskCardClosedPanelTest`
  constructs its row: a `Task(id = 7, uuid = "anim", …)` with `category = null, isSelected = false`.

```kotlin
    private fun boundHolder(): RecyclerView.ViewHolder {
        val adapter = TasksAdapter(
            onTaskClick = {}, onLongPressTask = {}, onToggleSelection = {}, onEdit = {},
            onDeleteRequest = {}, onStart = {}, onPause = {}, onResume = {}, onComplete = {},
            onWorkToggle = {}, onToggleGroup = {}, onGroupMenu = { _, _ -> }, onStartDrag = {}
        )
        adapter.submitList(listOf(aRow()))
        awaitUntil("the row to be diffed") { adapter.itemCount == 1 }
        val holder = adapter.createViewHolder(FrameLayout(context), adapter.getItemViewType(0))
        adapter.bindViewHolder(holder, 0)
        return holder
    }

    @Test fun `animator scale zero skips the work instead of starting a no-op animator`() {
        assertFalse("nothing to animate, and nothing scheduled",
            CardItemAnimator(context, animatorScale = { 0f }).animateAdd(boundHolder()))
        assertTrue("at scale 1 the insert does animate",
            CardItemAnimator(context, animatorScale = { 1f }).animateAdd(boundHolder()))
    }
```

  Two holders rather than one shared: the scale-1 branch leaves its `itemView` at alpha 0 with a fade in
  flight, so a holder reused by the second assertion would be measuring the first call's residue rather
  than the animator's decision. Fresh holders make each call's result depend only on the scale it was given.

- [ ] **9.2a Nothing to declare.** `motion_entry_rise` and `motion_standard` came up into 9.0 with the class
  that reads them, and `motion_entry_max` is read by the same class now that 9.3's ceiling is code rather
  than prose, so this step is empty. It is kept as a numbered line because other steps and the review log
  refer to it. An earlier draft declared all three here, one step too late for 9.0, and named a
  `motion_target_min` that nothing reads — Stage 1 already owns that dimension as `touch_target_min`.

- [ ] **9.3 The threshold, in the class.** Once `fading.size` reaches `motion_entry_max` (12), further adds
  in that batch appear at full opacity and `animateAdd` returns false. It is a set on the animator, not a
  scan of the adapter, and `runningAddCount()` makes it observable to a test: 9.5 submits 200 rows and
  asserts the count never exceeds 12 and that at least one holder got no animation at all.

- [ ] **9.4 Group collapse, stated correctly.** A collapse is not a height that tweens: `render()` drops the
  hidden rows from the list, so the rows leaving go through `animateRemove` and the rows coming back through
  `animateAdd`, on this same animator — which is why the header's own `groupName`/`groupCount` update is
  instant (`getChangeDuration() = 0L`) rather than cross-faded. No RecyclerView item animator tweens a
  container's height, and an earlier draft of this step claimed collapse "animates height through the same
  animator", which is not implementable as written. Pressed states stay the standard ripple; durations live
  in `res/values/anim.xml` and nowhere else.

- [ ] **9.5 Gates and evidence.** Full suite, `assembleDebug`, `lintDebug`; then a device check that a
  scroll produces no animation and a new task produces one, photographed mid-flight (`s9-scroll-static.png`,
  `s9-insert.png`), and a run with the system animation scale at 0 to confirm nothing hangs.

**Acceptance, each one checkable:** a test asserts `animateAdd` is not called for a holder rebound during
scroll (`RecyclerView` recycles, so the assertion is on the animator's call count across a
`scrollBy`); a test with 200 rows submitted at once asserts fewer than 12 animated; a test with
an injected `animatorScale = { 0f }` asserts `animateAdd` returns false; `getChangePayload` returns
`PAYLOAD_STATUS` for a status-only change, null for a title change, and **null for a change that moved the
status and the priority together** — the third case is the one the old hand-written field list got wrong, so
it is asserted rather than argued; and
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
adb -s "$S" shell am force-stop com.taskplanner
adb -s "$S" shell am start -W -n com.taskplanner/.ui.MainActivity   # TotalTime
adb -s "$S" shell dumpsys gfxinfo com.taskplanner framestats        # after a 20-fling scroll
```

The card's cost per row is the mechanism to check, and the number to check it by is **not the view count**:
`item_task.xml` today holds 26 views, and 4.4 keeps all 23 named ids because tests read them while adding
`cardContent`, `categoryDot`, `repeatIcon` and `lineBFlow` — about 30 after the change. A "fewer views" bar
would therefore be unmeetable except by deleting a test's subject, which §3 forbids; and nesting depth does
not change either (FrameLayout → card → content before and after). What actually governs scroll cost is
measure work, so 10.5 records the two figures that move — the row's measured height and a 20-fling
`dumpsys gfxinfo framestats` p95 — against the baseline captured in 0.4, and the spec's "flattened so each
row measures less" is read as a statement about the *structure being flatter to lay out*, which the frame
stats either support or refute. If the p95 regresses, the response is to flatten further within the ids the
tests keep, not to accept.

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
  CI only compiles them — including the two things a reader has to know before running them: the suite is
  executed as six invocations, one per class (§6, and §20h for why), and each one clears the app's data, so
  `python scripts/apply-evidence-seed.py` comes after it and not before.
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
3. The instrumented suite run on a device and passing, **one Gradle invocation per class**, each pinned to
   one serial with `ANDROID_SERIAL` (§6: the task fans out to every transport `adb devices` lists, and this
   workspace has had a physical handset with a real profile on it), and with the data fixture re-applied
   afterwards before any screenshot (§20h: `clearPackageData` wipes the app on every invocation). The
   isolation rule is measured for `ReportScreenInstrumentedTest` — the singleton `AppDatabase` holds a
   connection to the file `DatabaseLocationInstrumentedTest` renames aside — and is **not** established for
   `TaskEditorInstrumentedTest`, whose symptom contradicts it; §20h carries both, and the criterion does
   not depend on the unexplained one. Pass with the installed APK's sha256 matching the built one, or an
   explicit statement of which classes were not run and why. Measured state at the gate: all six classes
   pass alone, 17 tests, 0 failures.
4. `python scripts/contrast_report.py --resources app/src/main/res --check` prints `RESULT: pass` with no
   drift lines, and its report is committed.
5. Every screen in §13 has a before and an after photograph at the same configuration, and
   `docs/UI_REDESIGN_REVIEW.md` explains each pair.
6. The Tasks header reaches search, sort, new group, archive and work history — each demonstrated, not
   described.
7. The swipe panel is invisible on a closed row in a photograph and in `TaskCardClosedPanelTest`, **and the
   reveal itself is demonstrated on the device** — the gesture that opens it, photographed, because Stage 0
   could not produce that photograph at all (audit §4.10: six injected drags moved the card zero pixels, and
   a tap entered selection mode instead of opening the task). If the reveal still cannot be demonstrated,
   that is a defect this branch fixes, not a screenshot it is allowed to skip.
8. No `data/` file changed: `git diff --name-only master...HEAD | grep '^app/src/main/java/com/taskplanner/data/' || echo "clean"`.
9. No new dependency: `git diff master...HEAD -- '*build.gradle*'` is empty.
10. The M2 and platform sweep is empty across **every** resource directory and the Kotlin sources, not just
    `res/layout` — a hex in `res/values/colors.xml`, a drawable tint or a `Context.getColor(R.color.red)`
    is the same debt in a place the first grep could not see:
    `grep -rn "Widget.MaterialComponents\|?android:attr/textColor\|@dimen/text_" app/src/main/res app/src/main/java`
    is empty, or every survivor is named and justified.
10b. No API-26 layout attribute survives on a 24 floor, where it is dropped silently and lint never says so:
    `grep -rl "layout_marginHorizontal\|layout_marginVertical" app/src/main/res/layout` returns nothing. Ten
    files match at the baseline (2.3a), and the two that matter most are `item_group_header.xml`, which draws
    the band above every group on the redesigned screen, and `item_task.xml`, which 2.3 fixes.
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
commits, and no two stages may share a commit — that is what makes reverting possible at all. Where a stage
does carry more than one commit (the three skeletons of §3, and S8's four sub-stages), the unit that reverts
is the **pair**: a change commit and the skeleton that declared its names go back together, newest-first,
because reverting one without the other leaves either an unused skeleton or a build that names resources
nobody declares. A single `git revert` of one commit is therefore always either a whole stage or a whole
skeleton pair — never half of either. Never
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
| §3.2 | variant-surface rule | tokens: 1.2's split + 1.8's pills; layouts: **10.1a**, the only step that can see a violation |
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
| §8 | baseline preserved, tests moved honestly | 0.1, 0.1a, §14 items 1–2 |
| §8 | new fragment-level coverage incl. Profile | 4.9a, 5.1, 5.2, 3.1 |
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

Nineteen commits across thirteen stage headings: sixteen layer commits plus the three skeleton commits (1.5a, 3.0, 4.1a) that §3's red-first rule requires. S0 captures evidence and changes nothing; S1–S12 change the app; S8 is four commits. Each one builds, tests and photographs on its own, which is what makes
`git revert <stage>` a real option rather than a theory.

The count is still nineteen after the eighth pass, because the four steps that pass added ride inside
commits that already exist rather than opening new ones: 0.1a's `connectedDebugAndroidTest` result goes into
S0's evidence commit, 1.11a's script hardening into C1, 2.3a's ten-layout margin sweep into C2, and 4.9a's
`TasksFragmentRenderTest` into C4 — where it belongs, since it is the test that 4.4 and 4.5's layout and
bind changes must keep green. A step is not a commit; a commit is a point where the whole suite passes.

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
| c7 | The plan asserted "Android applies the animator scale automatically" | verified against the actual artifact: `androidx.recyclerview` 1.1.0 contains no `isAnimatorDisabled` and no `getDurationScale` reference, so the framework animators merely finish instantly while the setup work still runs — the hand-written path must check the scale itself | `unzip` of `recyclerview-1.3.2.aar` from the Gradle cache and a search over its `classes.jar` **(superseded by §20e-e7: the build resolves 1.1.0; the claim held when re-checked against the right artifact)** |
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
| d5 | The font-scale test could not fail: a disjunction of two top-edge comparisons is true for any two integers,; `layout == null` short-circuited the ellipsis check; `"%1.f"` throws `UnknownFormatConversionException` before any assertion runs | BLOCKER | reproduced on JDK 11 | tautology deleted; overflow asserted against the content's right edge; messages use Kotlin interpolation only |
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

### 20e. Fifth pass: two fresh verifiers, and the fixes of §20d were themselves wrong in five places

Neither verifier was told what the previous rounds concluded. Both worked from the artifacts — `javap` on the
resolved jars, `api-versions.xml` in the platform SDK, the manifest-merger report — rather than from this
document's own claims.

| # | finding | severity | evidence | disposition |
|---|---|---|---|---|
| e1 | §9.0's production code called `ValueAnimator.getDurationScale()`, which is API 33 on a 24 floor — a `NoSuchMethodError` on every device in between and a new lint finding, which §14 forbids | BLOCKER | `api-versions.xml`: `name="getDurationScale()F" since="33"`; `javap` on android-34's `android.jar` | the scale is injected as `animatorScale: () -> Float`, defaulting to `Settings.Global.ANIMATOR_DURATION_SCALE` (API 17). Same seam argument as the clock in §0: anything a test must drive is a parameter |
| e2 | §9.2's test called `ValueAnimator.setDurationScale(0f)`, which does not exist as public API, and Robolectric's shadow setter is `protected static` | BLOCKER | `javap android.animation.ValueAnimator`; `javap org.robolectric.shadows.ShadowValueAnimator` | the test injects `{ 0f }` and `{ 1f }` into the animator instead |
| e3 | 4.2's overlap test asserted `due.right <= pill.right`, inverting spec 4.1's own pill-then-date order, and compared the title's right edge to the date on another line | BLOCKER | read against the spec's line B order | rewritten: dot ≤ title ≤ repeat on line A, pill ≤ date on line B, and band separation asserted between the lines |
| e4 | 4.2 called `bindWorstCase(sortOrder = …)`, a parameter the fixture did not have, and `TasksAdapter` takes no sort input | BLOCKER | the fixture's own signature | `sortOrder` added to the fixture and threaded through `adapter.reorderEnabled`, which is the real control |
| e5 | 4.2 applied `Float.asDp()` to `Int` values, and used `awaitUntil`, `DAY` and `aWeeklyRule()` that the class never declared | BLOCKER | Kotlin has no implicit Int→Float | `Int.asDp()` added; the helper and the companion constants declared in the snippet |
| e6 | The spec header said "Revision 7" while its own table had a row 8 — the identical drift the document was rewritten to prevent | MAJOR | spec line 8 | now "Revision 8", with the rule that a header must name its own newest row |
| e7 | The recyclerview 1.3.2 citation survived in spec §5 and plan §9.2 after §20d had established the build resolves 1.1.0 | MAJOR | manifest-merger report line 92 | both restated to 1.1.0 |
| e8 | The §3 "skeleton commit" rule this plan adopted had no skeleton anywhere: 1.6a, 3.1, 4.2 and 1.10 all name `R.style`/`R.id` constants that no earlier step creates, so their predicted "watch it fail" is a compile error | MAJOR | the plan's own step order | steps 1.5a, 3.0 and 4.1a added, each declaring names without behaviour, in its own commit |
| e9 | 1.6a's note demanded ±0.2f while all ten assertions still used ±1f — wide enough for caption, pill and label to satisfy each other | MAJOR | the snippet | all ten tightened |
| e10 | 3.1's instrumented test asserted only that the list was displayed and *commented* that a count should follow: a search that filters nothing passes it | MAJOR | the snippet | asserts the row count and the presence and absence of the two seeded titles |
| e11 | 3.6's rail test checked an id existed; a non-scrolling two-block rail passes it | MAJOR | the snippet | asserts containment — the category group inside the scroller, the status group explicitly not |
| e12 | Two flattened `binding.headerTitle` / `binding.headerSearchButton` paths survived the §20d include correction | MAJOR | ViewBinding semantics | nested through `binding.tasksHeader` |
| e13 | `bg_pill_neutral.xml` and `ic_work.xml` were referenced by snippets with no inventory line owning them; `motion_entry_rise`/`motion_standard` were used by 9.0 before 9.1 declared them | MAJOR | §8's file list | inventory lines added; 9.0's note names the declaring step |
| e14 | S1's file list named `styles.xml` and the two chip colour-state-lists that no S1 step edits, and S7 still listed `themes.xml` and `MainActivity.kt` | MINOR | §8 vs the stage bodies | corrected |
| e15 | Spec §8 listed `TaskCardWorkTest` among the tests that move, while the plan and §20b preserve it unchanged | MINOR | both documents | removed from the spec's move-list, which is the accurate statement |
| e16 | Spec §3.3 justified the `caption` role with "seven roles cannot supply five distinct styles", which is false arithmetic | MINOR | five roles suffice; the mapping was the defect | the reasoning rewritten to say what actually broke |

What the two verifiers confirmed rather than refuted: the contrast gate exits 0 and every figure in spec §3.2
matches the script's output in both themes; the 320dp budget is consistent across the two documents at 208dp
with line remainders 152 / 26 / 56; the pill allowance recomputes to ≈80dp against a budget of 82; the
`Wcag` luminance maths is correct; `ListGutterTest` compiles and 56 + 2·16 = 88; all 29 Material attributes the
theme sets exist in material-1.11.0's `R.txt`; `@GraphicsMode(NATIVE)` is a valid target for a class in
Robolectric 4.11.1; and `animateAdd`, `dispatchAddStarting` and `onAddFinished` are all public in the
recyclerview 1.1.0 this build resolves.

## 20f — Seventh pass: the snippets were compiled, not read

The six passes before this one checked the Kotlin by reading it, and every one of them missed something
that a compiler says out loud. So this pass built a harness and ran the plan's own code through it.

**The harness.** `kotlin-compiler-embeddable-1.9.22` (the project's Kotlin) on JDK 17, compiling against
`C:\Android\platforms\android-34\android.jar`, the `classes.jar` extracted from **recyclerview 1.1.0** (the
version the manifest-merger report says this APK resolves), the AAR classes Jars already in this machine's
`~/.gradle/caches/transforms-3` for material 1.11.0, core 1.12.0, appcompat, fragment, room, hilt, the
Robolectric 4.11.1 and junit 4.13.2 plain Jars, and `app/build/tmp/kotlin-classes/debug` — the app's own
compiled classes, so `TasksAdapter`, `TasksListItem`, `Task`, `CardWork` and `TrackingPhase` are the real
signatures rather than a sketch. The classpath is ~200 entries, over the Windows command-line limit, so the
arguments go through an `@argfile`. Complete-file blocks are grouped by package and compiled together, which
is what lets `ContrastLightTest` see `Wcag` and `TaskCardWidthBudgetTest` see `CardPresentation`.

**What it does not prove.** `R.id.*`, `R.style.*` and `R.dimen.*` for resources the plan has not written yet
are generated into a stub `R.java` from each snippet's own references, so the compile says *the reference is
well-formed*, not *the resource exists* — that is still carried by the skeleton commits in §3 and by the
stages that create them. `com.google.android.material.R.attr.*` needed a matching stub, and the five names
were then checked against `material-1.11.0.aar/R.txt`, which contains all of them. Layout XML is not parsed.
Cross-module smart-casts are reported that the real build would allow, which is why f7 below is a clarity fix
rather than a bug fix.

**Result: both groups compile clean (exit 0)** — `ui.theme` (Wcag, ContrastLightTest, TypeScaleTest,
ListGutterTest) and `ui.tasks` (TaskCardClosedPanelTest, CardPresentation, TaskCardWidthBudgetTest,
CardItemAnimator) — and the two Stage 9 fragments, which have no `package` line and so are wrapped in probe
files, compile clean too.

| # | what the compiler found | severity | evidence | disposition |
|---|---|---|---|---|
| f1 | 4.2's `fun \`at font scale 1.3 …\`` — a dot inside a backtick-quoted name is rejected outright: `error: name contains illegal characters: .` | BLOCKER | the compile above; and 1370 backticked names in `app/src/test` and `app/src/androidTest` use only space, `,`, `'`, `-`, digits, letters, `_`, `ё` — **zero** contain a dot | renamed to `at font scale one point three …`, and the character set is now a rule rather than a habit |
| f2 | `TaskCardWidthBudgetTest` called `assertFalse` with only `assertEquals` and `assertTrue` imported: `unresolved reference: assertFalse` | BLOCKER | the compile above | the import added; a script now cross-checks every assert used against the imports in every block |
| f3 | 9.2's test called `boundHolder(recyclerViewWithOneRow())` — neither helper exists. `TaskCardWorkTest.bindRow` is `private` to that class, so there was nothing to borrow | BLOCKER | `grep -n "private fun" TaskCardWorkTest.kt` | the step now names `CardItemAnimatorTest.kt`, which builds its own holder the way `TaskCardClosedPanelTest` does and carries its own `awaitUntil` |
| f4 | 9.1 declared `PAYLOAD_STATUS` **inside** the anonymous `ItemCallback`, where `TasksAdapter.onBindViewHolder` cannot see it, and its hand-written field list (`title`, `dueDate`, `work.phase`) returned the pill payload for a row whose priority or reminder count had also moved | BLOCKER | `TasksAdapter.kt:342-360` — the companion holds `DIFF`; a `const` in an object expression is not visible outside it | the constant moved to the companion beside `VIEW_TYPE_TASK`, and the payload test is now `n.copy(task = n.task.copy(status = o.task.status)) == o`, which compares every other field without naming them |
| f5 | 9.0's end action called `onAddFinished(holder)` | MAJOR | `javap -c` on recyclerview 1.1.0: `dispatchAddFinished` = `onAddFinished` **plus** `dispatchAnimationFinished`, and the second half is what reaches `mListener.onAnimationFinished` — RecyclerView's hook for releasing the holder | the end action calls `dispatchAddFinished`; the step states the one thing the design cannot do — `mAddAnimations` is package-private, so `isRunning()` never counts these fades, which is why §9.5 asserts `animateAdd` call counts instead |
| f6 | 9.2a declared a `motion_target_min` nothing reads (Stage 1 already declares `touch_target_min`), ran *after* the 9.0 step that reads the resources it declares, and §8 still carried a bare `res/anim/` directory no step writes | MAJOR | the plan's own step order and file list | the declaration folded into 9.0 before the class is written, 9.2a reduced to the one resource 9.3 reads, `res/values/anim.xml`, `bg_pill_neutral.xml`, `CardItemAnimator.kt` and `CardItemAnimatorTest.kt` added to §8, `res/anim/` dropped |
| f7 | `CardPresentation.of` wrote `task.dueDate != null && task.dueDate < now`, which smart-casts only while producer and property share a module | MINOR | the probe compile reports it across the module boundary; inside `app` it is legal | `val due = task.dueDate` read once — the predicate now reads the way the DAO's SQL reads, one value compared once |

Also confirmed by running rather than reading in this pass: `override fun getMoveDuration()` and
`getChangeDuration()` are legal overrides, because both are `public` non-final on `RecyclerView.ItemAnimator`
in 1.1.0 (`javap`); `animateAdd` is `public abstract` on `SimpleItemAnimator` and `public` on
`DefaultItemAnimator`, while `animateAddImpl` stays package-private; `dispatchAddStarting` and
`dispatchAddFinished` are `public final`; and `Wcag.kt` — the block the audit named for `**` — compiles clean
on its own against nothing but `kotlin-stdlib`, with `kotlin.math.pow` doing the linearisation.
## 20g — Eighth pass: two fresh reviewers, different mandates, and what each one found

Two agents audited this document from opposite ends, neither asked to approve it. **Reviewer A** worked the
plan and test quality: compilability, whether each test proves its invariant, contradictions between stages,
command correctness, spec coverage, arithmetic. **Reviewer B** worked Android feasibility against this
repository as it is: every source claim re-read at the file and line, `javap` on the artifacts the APK
resolves, ViewBinding and RecyclerView semantics, realistic sizes, integration risk. Their reports were
independent, and the overlap between them — the strings ordering, the reflow mechanism, the collapsed-column
arithmetic, the rail hierarchy, the empty test body — was reached twice from different directions, which is
why this pass trusts those findings more than the singleton ones.

Between them they produced 47 findings. Nothing below claims a check that was not run: each row's evidence
column says what was executed or read.

### Confirmed and fixed in this pass

| # | finding | severity | evidence observed | what changed here |
|---|---|---|---|---|
| g1 | 1.5 puts `bottomNavigationStyle`, `materialCardViewStyle` and `chipStyle` into `themes.xml`, but those styles are authored in 1.8 — `aapt2 link` fails on a theme reference to a style that does not exist, so the 1.5 commit cannot build | BLOCKER | plan 792-794 against 1026; an independent compile reproduced the same class of link failure | 1.5a now creates `component_styles.xml` with all fifteen `Widget.TaskPlanner.*` names as parent-only stubs, and 1.8 fills the bodies |
| g2 | `CardPresentation` is production source and names `R.plurals.task_late_days`, `task_due_today_format` and `task_due_tomorrow_format`, which step 4.3b created **after** it | BLOCKER | `grep -c` in `strings.xml` returns 0 for all three; both reviewers, independently | the three entries moved into 4.1a's skeleton commit; 4.3b is a tombstone that says so |
| g3 | 4.2's collapsed-column test computed `handle.width + dp(4) + rule.width + dp(8)` and asserted `dp(12)` — with the handle `GONE` its width is 0, so the expression is 16dp and the test can never pass | BLOCKER | the arithmetic, plus `TasksAdapter:332`, which hides the handle off `MANUAL` | the handle term is conditioned on visibility, and the 52dp promise is asserted as a measured difference between two bound rows |
| g4 | Stage 7's acceptance demanded the bar's background differ from `android:colorBackground` by at least 1.3:1 | BLOCKER | recomputed with the plan's own formula: `ink_surface` against `ink_background` is 1.05 light and 1.07 dark, and the best surface token is the variant at 1.21 — nothing in the system reaches 1.3 | the criterion is now the drawn hairline (`ink_outline_strong`, 3.73 / 3.78), with the real figures stated as figures |
| g5 | Two assertions that cannot fail: `width − padding >= measureText(text)` on `wrap_content` views (the width *is* the text), and `assertFalse(text.endsWith("…"))` (a `TextView`'s text is never the ellipsised string) | BLOCKER | `item_task.xml:116` and `:137` are `wrap_content`; the ellipsis lives in the `Layout`, not in `mText` | both the 1.0 and the 1.3 tests use `layout.getEllipsisCount(...)` plus a containment check against the content edge |
| g6 | Three blocks do not compile: `val midnight = /* … */` and `val viewModel = /* … */` put a comment where an expression belongs, `present(...)` and `activeFilterCount(...)` were never declared, and an `awaitUntil(...)` is called in an **instrumented** test where no such helper exists | BLOCKER | a compile of the blocks reproduces `expecting an expression`; `grep -rn "fun awaitUntil" app/src/androidTest` is empty while `TaskEditorInstrumentedTest:330` has `waitUntil` | `CardPresentationTest` became a complete file with a real `Calendar` instant, a `present(...)` fixture and a `DAY` companion; 3.6's chip test became a pure `TasksState` count; 3.1 declares the project's own `waitUntil` and `TIMEOUT_MILLIS` |
| g7 | `TextView(themed, null, 0, R.attr.textAppearanceBody2)` puts a theme **attribute** in the `defStyleRes` slot, where no style is ever resolved, so the view comes back at the platform default and the test fails forever | BLOCKER | the constructor's signature in `android-34`'s `android.jar`; the sibling `sizeSp()` correctly passes a style in that slot | the three-argument form, with the reason written into the test |
| g8 | The page asserted "emulator-5554 is live" in the present tense and hard-coded that serial in 33 `adb` lines | BLOCKER for the evidence chain | `adb devices -l` now lists only `adb-WCLJBINVEAT4PVGM-…`; `adb -s emulator-5554 get-state` answers `error: device not found`; the attached device reads API 35, `arm64-v8a`, 1220x2712 at 480dpi, `font_scale 0.81`, animation scales 0.5 | §6 resolves the serial into `$S`, records both devices and their measured values in a table, states the per-device density formula, and keeps the emulator's `PASS` as what happened on that device at that commit. 9.5 and 10.x set the font and animation scales instead of inheriting them |
| g9 | 1.6 wrote the type scale **before** 1.6a wrote its failing test, so the promised red never existed — while §20b recorded that the move had been made | MAJOR | the two checkboxes' order in the document | the test is 1.6 and the implementation is 1.6b, in that order; 1.5a's reference follows |
| g10 | The rail test asserted `chipGroupCategory.parent.parent`, false today because the `ChipGroup` is the scroller's direct child (`fragment_tasks.xml:72` then `:80`), and `assertSame(view, chipGroupStatus.parent)`, comparing a `CoordinatorLayout` root against an inner `ConstraintLayout` — while the prose put the `Filters · n` chip in the status row that the same test pins to three children | MAJOR | the shipped hierarchy, read line by line | the `categoryBand` wrapper is named as part of 3.6's change, the second assertion became a non-descendant walk, the chip moved into the category band, and the "share one horizontal band" sentence that contradicted spec §4.1 is gone |
| g11 | 2.1's second test asserted `panel.left <= card.right − panel.width`, which is algebraically the same statement as the first test's `panel.right <= card.right`, so it proved nothing new — and it was satisfied **before** the fix | MAJOR | `panel.left ≡ panel.right − panel.width`; today's numbers are 241 against 233 | equality against the panel's own width, plus `panel.width == 152dp` and a height floor; 2.2's expected-red list is derived from the geometry rather than guessed |
| g12 | The single-row fixture was itself overdue, so it printed "3 days late" — shorter than the full date the 84dp allowance is defined against, which therefore was never measured | MAJOR | `CardPresentation.dueLine`'s overdue branch | the fixture's default is `now + 30 days`, with the reason stated |
| g13 | The fixture's category was built `color = 0`, which is transparent, while the test asserted the dot carries the colour | MAJOR | `Category.color` is an `Int` and 0 is `@android:color/transparent` | a real ARGB, and the assertion reads `dot.backgroundTintList.defaultColor`, the mechanism this adapter already uses (`TasksAdapter:237`) |
| g14 | 9.0's class never read `motion_entry_max`, 9.3 stated the threshold in prose with no code, and 9.4 claimed the item animator tweens a group's height — which no item animator does, two lines under `getChangeDuration() = 0L` | MAJOR | the class body, and `RecyclerView.ItemAnimator`'s API | the ceiling is a holder set with `endAnimation` and `endAnimations` bookkeeping plus a `runningAddCount()` a test can read; 9.4 restates collapse as removes and adds |
| g15 | 10.1a's variant-surface walk, the two menu files 3.0 creates, `anim.xml`, `bg_pill_neutral.xml`, `CardItemAnimator.kt`, `CardItemAnimatorTest.kt` and the fragment render test were all referenced by steps with no inventory line owning them | MAJOR | §8 against the stage bodies | each is listed; S10's row names the source it edits, and S3's `menu_tasks.xml` row says deleted rather than modified |
| g16 | `ListGutterTest` asserted `row_min_height >= 48dp`, so collapsing the row to the touch-target floor satisfied it and violated spec §3.4's 56dp | MINOR | the test body against §3.4 | two floors, two numbers, named in the test |
| g17 | `TypeScaleTest`'s five-attribute test asserted only that `resolveAttribute` succeeded and that five ids were distinct; under an M3 parent an unmapped attribute still resolves and still yields five distinct ids | MAJOR | `StatisticsLayoutTest:655` asserts `assertNotEquals(0, value.resourceId)` for exactly this reason | the non-zero check and a role-size check carried over, with `assertNotEquals` imported |
| g18 | 1.7a had Stage 1 editing `fragment_tasks.xml`, which S3 and S4 own, contradicting §7 and Stage 1's own acceptance | MAJOR | §8's rows and Stage 1's "no layout changed" line | Stage 1 keeps the dimens and the test; the literal moves to 3.2, and the grep gate is dated to Stage 3 |
| g19 | 8.4b's parentheticals "(4.4)", "(5.4)", "(8b)" described edits those steps never perform, and claimed a failed read leaves the screen showing nothing while both screens show a `Snackbar` | MAJOR | `fragment_tasks.xml:156` and `:165`, `StatisticsFragment:101`, `ReportFragment:516` | 8.4b owns its edits, cites the lines, and states the snackbar behaviour accurately |
| g20 | Spec §8's fragment-level Tasks coverage was mapped only to the header tests, leaving groups, the empty state and the closed panel with no fragment-level assertion | MAJOR | spec §8 against §17 | step 4.9a adds `TasksFragmentRenderTest`, patterned on the project's one existing fragment-level test, `ui/util/WorkAdjustmentUiTest` |
| g21 | `colors.xml:4-25` was cited for sixteen names, four of which live at 28-31; and the three dead `*_dark` colours are declared in **both** files, so a night-only deletion left three behind | MINOR | `status_todo:28` through `status_paused:31`; `values/colors.xml:9,11,15` | the range is 4-31, and the deletion says both files, naming the three readers at `values-night/themes.xml:15,16,19` |
| g22 | `python scripts/contrast_report.py --resources … --check` does not report a missing token — it raises `KeyError: 'ink_error'` | MAJOR | run at this commit; the traceback is the output | 1.11a hardens the script to print `MISSING <theme> <token>` and keep the non-zero exit, and 1.11 now says what the tool does today |
| g23 | `TasksScreenInstrumentedTest:56` clicks `navigation_statistics` and asserts `contentGroup`, which exists only in `fragment_profile.xml:19` and `fragment_category_detail.xml:15` | MAJOR, and a **critical premise** rather than a plan detail | `grep -c contentGroup app/src/main/res/layout/fragment_statistics.xml` returns `0`; CI runs `assembleDebugAndroidTest` only, so no job ever instrumented | 0.1a runs `connectedDebugAndroidTest` before C1, records the real pass list, and fixes that assertion in its own pre-redesign commit. §14's third criterion is measured from then on, and §2 states plainly that the 17 methods were counted, not executed |
| g24 | "Line B reflows onto two lines at font scale 1.3" had **no mechanism**: a `ConstraintLayout` horizontal chain cannot wrap, while the plan mandates one `cardContent` for all three lines and locks `taskTitle.parent == workRow.parent` | BLOCKER | the spec's own structure rule against chain semantics | 4.4 names a `Flow` helper (`app:flow_wrapMode="chain"`, constraintlayout 2.1.4, already the resolved artifact) over pill, date and name. A helper does not reparent, so the locked sibling assertion survives; `lineBFlow` is declared in 4.1a, and the reflow is asserted as `due.top > pill.top` at 1.3 against equality at 1.0 |
| g25 | 9.1's payload branch repainted the pill and nothing else, while status also drives the four `show*` visibilities — so a paused card kept offering Start, wired to `startTask` | BLOCKER | `TasksAdapter:320-323`, and spec §4.1's one-control-per-status rule | the branch's scope is written out: pill, the four visibilities, the holder's stored row; only the work measure and the category text are skipped, and §9.5 names the status-change test that catches a future omission |
| g26 | The closed-panel fixture measured with `UNSPECIFIED` height, where `ViewGroup.getChildMeasureSpec` maps a `match_parent` child to 0, so the panel was zero-high and both vertical assertions passed on an empty rectangle | MAJOR | the shipped `actionPanel` is `layout_height="match_parent"` (`item_task.xml:12`) | a two-pass measure — learn the height, then give it `EXACTLY` — plus a `panel.height >= 40dp` guard in both tests |
| g27 | The `layout_marginVertical` correction was applied to `item_task.xml` only, while ten layouts carry API-26 margins, `item_group_header.xml` among them, and lint never flags it | MAJOR | `grep -rl "layout_marginHorizontal\|layout_marginVertical" app/src/main/res/layout` lists exactly ten; `app/lint-baseline.xml` has no `UnusedAttribute` entry and the lint report has zero | step 2.3a converts all ten in Stage 2 and makes the grep a §14 gate; spec §2.1 states the inset as two margins (spec revision 9) |
| g28 | 3.7 moved the `selectionBar` id onto the include, which leaves the `MaterialCardView` with no reachable id and no settable visibility, because `binding.selectionBar`'s root is the **included** root — and its test body was a single comment, so it passed either way | MAJOR | ViewBinding's include semantics; the empty `@Test` in the plan | `bar_selection.xml`'s root is the card itself, and the test asserts both directions of the header and bar swap |
| g29 | 3.1's `rowCount()` was the adapter's `itemCount`, which counts `GroupHeader` rows too (`TasksViewModel.render:255-277`), so "two seeded, one after filtering" depended on whether the account on the device uses groups | MINOR | the render code | it counts `TasksListItem.TaskRow` instances through `currentList` |
| g30 | The performance metric "the flattened row must measure fewer views" contradicts 4.4, which keeps all 23 ids and adds four more, taking 26 to about 30 | MAJOR | 4.4's own id list | 10.5 measures what costs — row height and a 20-fling frame-stats p95 against the 0.4 baseline — and spec §7's sentence now reads "flatter to lay out". §6's density pairs are stated per device with the formula |
| g31 | The `FAIL` branch of the APK-identity script printed `local=$DEV`, labelling the device hash as local; §8's S7 row contained a double negative | MINOR | the script line, the table cell | `FAIL local=$LOCAL device=$DEV pulled=$PULLED`, and the sentence now reads "and **not** `MainActivity.kt`" |

### Rejected with evidence

| finding | why it does not hold |
|---|---|
| "§0.2's pairs 240 for 360dp and 270 for 320dp are wrong; the panel is 1220px, so they are 610 and 542" | Correct arithmetic, wrong panel. Those pairs belong to the `540x1140` record measured there: `540 / (360/160) = 240` and `540 / (320/160) = 270`. The 610 and 542 figures are the *handset's* answers to the same formula. What was genuinely wrong is carrying one device's table as universal, which is why §6 and §0.2 now state `density = px_width / dp_width × 160` and give all three panels' numbers |
| "The card's overdue predicate can disagree with the dashboard's" (raised again in this pass) | It cannot, and the reason is in the SQL: `TaskStatisticsDao.kt:76-77` restricts its `WHERE` to `is_deleted = 0 AND archived = 0 AND UPPER(status) NOT IN ('COMPLETED','DONE')`, and `:63` adds `due_date IS NOT NULL AND due_date < :now`. `CardPresentation`'s extra `status != COMPLETED` is a subset of a population the query already excludes, so the two cannot diverge. The drift test in 4.1 stays as the guard |
| "The 13-callback `TasksAdapter` constructor in the snippets will not match the real one" | It matches: `TasksAdapter.kt:27-41` is exactly the callback list every fixture here writes, and `reorderEnabled` and `selectionMode` are public `var`s at `:43`. `CardPresentationTest`, which builds a real `Task`, compiles against the app's own classes (g6) |

### Confirmed, open at the end of this pass

*All five were closed in the commit that records Stage 0.1a (§20h); they are kept here as the order they were found in.*

| finding | severity | why it is still open, and what closes it |
|---|---|---|
| §16 calls a stage one revertible unit while §18 gives three stages two or three commits each (the skeletons) | MINOR, wording | §16 should read "revert the change commit and its skeleton together, or neither". One sentence, left to the next document edit so this pass's diff stays reviewable; execution is not ambiguous, because §18 names the commits |
| 4.5 sets the priority rule `INVISIBLE` for LOW while the budget arithmetic assumes a `GONE` left column contributes no width | MINOR | these are different columns — the rule keeps its 4dp to avoid jitter, the handle is what collapses — and the plan should say so rather than leave the reader to reconcile 2022 with the 64dp and 12dp arithmetic. It does not block Stage 4 |
| Stage 2's stated S1 dependency is not the one its test reads | MINOR | Stage 2's precondition should name `bg_row_backdrop`, `card_gap` and `spacing_xs` rather than "`ink_*`". The real dependency is satisfied either way |
| The opened-row 8dp gutter: `bg_row_backdrop` is the panel's background, so it is clipped to the panel's own rect and the strip to its right stays the window background | MINOR, and acknowledged in spec §2.1 | the fix is to move the backdrop to the row root while a row is open; 2.4's visual check will see it. Spec §2.1 already declares the gutter accepted rather than discovered late, so this is an honesty line, not a hidden defect |
| `android:lineHeight` is API 28 and the floor is 24 | MINOR | 1.6 pins it with `tools:targetApi="28"` and the tests read `textSize`, so nothing asserts a 24-27 device behaves like the emulator. The plan should state that line spacing falls back to font metrics below 28 instead of leaving it implied; 10.2's device pass covers what unit tests cannot |

### What this pass says about the six before it

The seventh pass compiled this document's code and still left g1, g2, g7, g24 and g25 standing. A compile
with **stubbed** resources proves a reference is well-formed and nothing more: it could not see that
`R.plurals.task_late_days` would not exist until two commits later (g2), no compiler has ever rejected a
layout that cannot wrap (g24), and no type checker objects to a payload branch that repaints half a row
(g25). Two of the three items here that were rated critical came from reading the repository, and one —
g23, §14's instrumented premise — came from noticing that a number was *counted* where a *run* was implied.
That is the reason §3's rule now names, for every check, the thing it cannot prove.

## 20h — Stage 0.1a executed: the instrumented suite, run for the first time ever

`./gradlew connectedDebugAndroidTest` on `emulator-5554` (`befos_avd`, API 35, `Physical size: 540x1140`,
`Physical density: 220` → 393dp, `font_scale 1.0`, animation scales 1.0). CI had only ever run
`assembleDebugAndroidTest`, so this was the first execution of these 17 methods in the repository's
history. Baseline run at `42ebfc2`, unmodified: **17 tests, 3 failures**. All three were real, none was a
flake, and one of them was a defect in the plan's own reasoning.

| test | baseline | root cause, measured | fix |
|---|---|---|---|
| `DatabaseLocationInstrumentedTest.aLegacyCacheDatabaseIsCopiedIntoPermanentStorageAndLeftInPlace` | FAIL — `SQLiteException: no such column: user_id`, while compiling `CREATE INDEX IF NOT EXISTS index_tasks_user_id_due_date ON tasks (user_id, due_date)` | The fixture planted `CREATE TABLE tasks (id INTEGER PRIMARY KEY, title TEXT)` stamped `user_version = 7`. No release ever had that table. The rescue hands the file to the real chain, and `MIGRATION_9_10` (`AppMigrations.kt:257-273`) builds an index over `user_id`, which the sketch did not have. The test was proving that an invented database cannot be migrated. | The planted schema is now `app/src/androidTest/assets/legacy-v7.sql`, generated verbatim from Room's exported `app/schemas/…/7.json` (four tables, `tasks` with its 19 columns), executed statement by statement; a user and a task are inserted as that schema requires. `LegacySchemaV7SourceTest` (JVM) compares the asset against the JSON so the two cannot drift. |
| `TasksScreenInstrumentedTest.statisticsAndBackKeepsTheTaskListUsable` | FAIL — `NoMatchingViewException` for `contentGroup` | g23 confirmed on the device: `contentGroup` is a Profile and category-detail container, not a Statistics one (`grep -c contentGroup fragment_statistics.xml` = 0). The assertion could never pass, and nothing noticed for six stages because no job instruments. | Assert Statistics' own always-present chrome — `statisticsPeriodScroll` and `statisticsPeriodChips`, both built in `setupChips()` from `WorkPeriod.entries` regardless of data — then, on the way back, the list **and** the three status chips, so "usable" means the screen came home whole. The dashboard body is deliberately not the subject: `DashboardRenderer.kt:83` sets `dashboardScroll.isVisible = snapshot != null && !snapshot.isEmpty`, which is data-dependent. That fact was learned the hard way — the first version of this fix asserted `dashboardScroll` and failed with `Got: view.getVisibility() was <GONE>`. |
| `TaskEditorInstrumentedTest.theEditorOpensForAStoredSeriesOfEveryKind` | FAIL — `DAILY: the number the series runs on expected:<[1]> but was:<[]>` | Not an app defect and not the assertion's fault: the class passes on its own and fails when another class ran first in the same process. *Why* it fails that way is **not** established — see the mechanism note below, which is honest about the one class it does explain and the one it does not. | No change to the assertion. It is fixed by the isolation rule below. |

**The isolation finding, which is the part worth keeping.** These classes do not compose in one process:

| invocation | result |
|---|---|
| all six classes together, at `42ebfc2` | 3 failures (the three above) |
| `ReportScreenInstrumentedTest` alone | passes |
| `DatabaseLocationInstrumentedTest` then `ReportScreenInstrumentedTest`, one process | **Report fails** — `waitUntil` times out at 60 s while `screenState()` immediately after reports `sheet=true496x2139`, i.e. the work completed, later than the budget |
| each of the six classes in its own invocation, after the two fixes above | **all six pass** |
| JVM suite, same commit | 93 suites, 1373 tests, 0 failures (the baseline 92/1371 plus `LegacySchemaV7SourceTest`'s 1/2) |

**What is measured, and what is a hypothesis.** `DatabaseModule.provideDatabase` is `@Provides @Singleton`
in `SingletonComponent`, so the Application holds **one** `AppDatabase` — one open connection to
`databases/taskplanner.db` — for the whole instrumentation process, and
`DatabaseLocationInstrumentedTest` renames that exact file aside in `@Before` and deletes-then-restores it
in `@After`. For `ReportScreenInstrumentedTest` that mechanism is not a guess: the row above is the
experiment, the class that owns the file runs first, the class that reads through the app's connection
runs second, and the symptom is the work finishing late rather than not at all.

For `TaskEditorInstrumentedTest` the same story does **not** fit the evidence, and the plan said it did.
`renderSeries` (`TaskDetailFragment.kt:473-482`) sets the preset label and the interval text from the same
`RecurrenceRule` in one synchronous call, so a connection reading a stale or replaced file cannot produce
"label rendered, interval blank" — with no rule row the label stays `NONE` and the test fails at the
earlier `waitUntil`, which it did not. The deciding measurement, still open, is the two-class run with the
tree inspected at the moment of failure: how many `R.id.repeat_interval` views exist, and which instance
`onView(withId(...))` resolved. Until that is run, the isolation rule stands (it is justified by the Report
result on its own) and this row stays recorded as an unexplained cross-class failure, not as a solved one.

**The runner clears app data, which changes what "on a device" means.** `app/build.gradle.kts:29` sets
`testInstrumentationRunnerArguments["clearPackageData"] = "true"`, so the runner wipes the app's data
directory before **every** connected invocation — one data wipe per class under the rule above — and AGP
uninstalls the app afterwards. Two consequences the rest of this plan has to honour: the rename-aside in
`DatabaseLocationInstrumentedTest` protects bytes created inside the run, not a developer's profile (the
class KDoc now says so, and the class must not be run against a device holding a profile worth keeping);
and no screenshot matrix can survive an instrumented run, which is why Stage 0 carries
`scripts/evidence-seed.sql` and `scripts/apply-evidence-seed.py` and every capture step re-applies them.

Therefore, and now as a rule rather than an observation: **the instrumented suite is run per class**, each
in its own Gradle invocation, and §14's instrumented criterion counts those invocations. §6 carries the
command and 11.4 names the matrix it has to run:

```bash
# one invocation per class — the loop, not a `notClass` exclusion list: the runner supports `notClass`
# (androidx.test:runner 1.5.2, `app/build.gradle.kts:154`), but naming the six that exist is the claim that
# can be read and checked, while "everything except" hides a class that appears later.
for C in com.taskplanner.data.local.DatabaseLocationInstrumentedTest \
         com.taskplanner.data.report.export.PdfReportInstrumentedTest \
         com.taskplanner.data.report.export.ReportPdfBytesInstrumentedTest \
         com.taskplanner.ui.TasksScreenInstrumentedTest \
         com.taskplanner.ui.report.ReportScreenInstrumentedTest \
         com.taskplanner.ui.tasks.TaskEditorInstrumentedTest; do
  ./gradlew --no-daemon --console=plain connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class="$C" || exit 1
done
```

A stage that changes a screen re-runs **that class** while it is in progress, and the full six-invocation
matrix runs at 11.4 and again before the PR. This is not a workaround for a flaky test: every class passes
on its own, and the interference is reproducible in a two-class run. The open question, recorded rather
than hidden, is whether the 60 s budgets should be raised or the DB-location class restructured so it never
touches the live path; neither is a redesign change, and neither is done here.

## 20i — Stage 0 executed: the baseline, the fixture, and four things the plan had wrong

Executed on `emulator-5554` (`befos_avd`, API 35, `Physical size: 540x1140`, `Physical density: 220` =
392.7 dp) against the APK built at `c1b2c07`, sha256
`28e898e91ad7a18a505b4555f0026dba138463d497e944ccb10ce9a7ad9600f7`. The installed bytes were proved equal
to the built bytes by pulling `/data/app/~~B0eRFENfPFmH7nk5-HFRWA==/com.taskplanner-…/base.apk` and hashing
it: the same value. That is the check §6 asks for, and it is the reason the audit can say which build every
photograph belongs to.

**0.0, 0.2–0.6 are done; 0.1 and 0.1a were done earlier.** `docs/images/ui-redesign/{before,after}/` exist;
the before-matrix is 25 PNGs plus `before/manifest.md`, which records the `wm size` / `wm density` /
`font_scale` / uimode quadruple and the visible-task count for every file; `docs/UI_UX_AUDIT.md` carries the
ten findings, the baseline numbers and the limits. Three of the plan's 28 files have no photograph, and each
absence is the finding rather than a gap in the work: `11-archive.png` and `12-work-history.png` because
`nav_graph.xml:15-21` declares the actions, `menu_tasks.xml` declares the items, and `grep -rn
"action_tasks_to_archive\|action_tasks_to_work_history" app/src/main` finds the declarations and no caller,
while the theme is `NoActionBar` with no `Toolbar` in any layout, so the menu is inflated at
`TasksFragment.kt:67` and never shown. `27-swipe-open.png` is the third absence, and it is a measurement
rather than a navigation problem — see the last paragraph. Audit finding 2 is the record; Stage 3 makes
both screens reachable, and their `after/` photographs will be the first ones ever taken of them.

**A re-appliable data fixture replaced "same data, no clearing".** `scripts/evidence-seed.sql` +
`scripts/apply-evidence-seed.py` plant 5 categories (five deliberately AA-failing hues), 2 topics, 2
recurrence series, 14 task rows covering every state a card renders — measured time, armed reminders,
overdue by hours and by days, paused, completed, time-blocked, uncategorised, two titles long enough to
wrap at 320 dp — plus two rows that must stay invisible, which is how the after-matrix proves the list
still filters. Every row carries an `ev-` uuid and an id in the 9000 block; the cleanup deletes only
`uuid LIKE 'ev-%'`; the profile is created only when the device has no current one. It exists because
`clearPackageData` (§20h) wipes the app on every connected run — the plan asked for "same account, same
data, no clearing", and on this tooling that is only satisfiable by making the data reproducible.

**Four things this plan said that measuring proved wrong.**

| what the plan said | what was measured | what the plan now says |
|---|---|---|
| §0.2: force `wm size 1080x2280`; "the override is not optional" | With the override, `TasksScreenInstrumentedTest` taps `navigation_statistics` and stays on Tasks — the failure hierarchy shows `navigation_tasks` still selected. Reset to the physical panel: 4/4. At `wm density 270` (320 dp) on the physical panel: 4/4. | §0.2 and §6 now forbid the size override on this emulator and use density-only pairs (220/240/270), with the resolution cost stated in the audit instead of hidden |
| §6: `connectedDebugAndroidTest` needs only `adb -s "$S"` to be safe | The Gradle task fans out to every transport. One unpinned invocation ran on `emulator-5554` **and** `emulator-5556`, and the workspace also carries a physical handset (`WCLJBINVEAT4PVGM`, API 35, 1220x2712@480) with real data. The handset was not instrumented (`com.taskplanner.test` is absent, its app directory is unchanged), but "not this time" is not a safety property | §6 pins every connected run with `ANDROID_SERIAL`, and §14.3 counts it as part of the gate |
| §0.4: navigate by coordinates / trust one tap | Taps are occasionally swallowed on this emulator — the same coordinate, tapped again, works (reproduced by hand on `navigation_profile`, and again as a missing `GOALS` button that a second pass found). A horizontal drag that ends near the left edge is taken by the system as **Back**: one early capture photographed a completely different app that happened to sit behind Task Planner, and another caught the emulator's splash screen | `scripts/capture-ui-matrix.py` never trusts a tap: it reads the selected tab, the moved card, the vanished Profile, keeps every gesture inside the card, and refuses to write a file it did not navigate to |
| §0.5: eight findings | The capture found a ninth: `fragment_statistics.xml:93-118` puts three weighted figure TextViews in a row with no gutter, so `before/03-statistics.png` prints "1 task finished14 tasks added" as one run of text | §0.5 lists nine; 8b's acceptance names the gutter and its after-photograph |

**Two defects found that are not the redesign's to fix.** Running the suite unpinned surfaced
`IllegalStateException: Migration didn't properly handle: users` on `emulator-5556`: the table that comes
out of the chain has `firebase_uid` and `avatar_url` but no `avatar_color` / `avatar_initial`. No schema
this repository exported looks like that — `1.json`–`4.json` have no avatar columns at all and
`MIGRATION_4_5` adds them, so every version from 5 up has both — which means the database belongs to a
different build of this app. Room refuses to open it rather than rewriting it, which is the right failure
for user data; a repair migration, if one is ever wanted, is its own task with its own test. Separately,
one connected invocation reported `Starting 0 tests … Process crashed` immediately after a density change
while the app itself was alive and rendering correctly at 320 dp; re-running the same class passed 4/4.
Recorded as an emulator hiccup, not as a pass.

**The fixture's own numbers.** The seed prints what it planted (5/2/2/14/3/3), and the list query the
manifest uses reports **13** visible rows, not the 12 the seed wrote: the recurrence engine materialised
the next instance of a series on launch. That is the app behaving as designed, and it is why the manifest
records the count per photograph rather than asserting a constant. All 25 photographs carry the same 13,
which is the "same account, same data" check §0.4 asks for.

**What the capture could not do, and what that means for Stage 2.** `27-swipe-open.png` was never taken.
Six injected horizontal drags (150/200/250/300/400/600/900 ms), started and ended inside the card and away
from both screen edges, moved `taskCard` by zero pixels — while a vertical drag from the same tool scrolled
the list, so the events arrive. A single tap on a card, on a freshly launched app, entered **selection
mode** instead of opening the task. `handleTouch` arms `postDelayed(longPressRunnable, 400)` on DOWN and
performs the click on UP (`TasksAdapter.kt:148,185-194`); no test in the repository drives that path, so
1374 green JVM tests are compatible with the main screen's two primary gestures not working. §14.7 now
requires the reveal to be demonstrated on the device, not merely the closed panel to be invisible, and
audit §4.10 records the numbers. The panel's exposure while closed is documented from the view bounds
instead: `actionPanel` `[330,272][540,490]` against `taskCard` `[11,278][529,484]` — 11 px of panel to the
right and 6 px above, on every row, untouched.


## 20j — Stage 2's second commit: the reveal never worked, and the two things that hid it

2.5b asked whether the zero-pixel drag was the emulator or the row. It was the row, and the answer came
from measurement rather than from argument, in four steps worth repeating because each one removed a
plausible-sounding alternative:

1. **Espresso from inside the process**, with `GeneralSwipeAction` — real motion history through
   `UiController` instead of the shell's `input`. Still `translationX = 0.0`, on `emulator-5554`.
2. **A second AVD** (`Pixel_4`, 1080x2280 @ 440 dpi), idle, no other automation attached. Still 0.0. That
   cleared the emulator: two different systems, same nothing.
3. **The edge band.** Both AVDs report `navigation_mode=2`. A drag started at 95% of the card's width left
   the activity `STARTED` rather than `RESUMED` — the system had taken it as Back — while starts at 0.40 to
   0.80 kept it in front and still moved nothing. So the shell's `input swipe 700 1200 200 1200` (x=700 on a
   540 px screen, clamped to the edge) had been swallowed for a reason that had nothing to do with the row,
   and that fact alone explained Stage 0's six failures without explaining the seventh.
4. **A probe on the row itself.** Attached to the real adapter, on the device, printing what the framework
   hands the card: `clickable=false longClickable=false enabled=true slop=22 panelWidth=418`, 22 motion
   events reaching the list with `rawX` running 695→281, `translationAfterAdapterHandler=0.0`, and
   `translationAfterConsumingDown=-345.3`. The last number is the finding: the same injected stream moves
   the card as far as the handler lets it, provided the handler answers `ACTION_DOWN` with `true`.

`ViewGroup` designates a touch target on the down and only on the down. `handleTouch` returned `false`
there, the card is neither clickable nor long-clickable so `View.onTouchEvent` returned `false` too, and
the row was never told about `ACTION_MOVE` or `ACTION_UP` again. Every symptom in audit §4.10 is that one
line: no drag can start; a tap cannot land, so `onTaskClick` never runs and the row's own editor is
unreachable from the row; and the long press still fires, because it was armed on the only event delivered
and only the missing `UP` would have cancelled it — which is what made a light press open the selection
bar. The drag handle kept working because its listener does return `true` on the down, which is why the
feature looked alive for five stages.

**The fix is two lines and a flag**, in `TasksAdapter.TaskViewHolder`: take `ACTION_DOWN` (`return true`,
with the reasoning in a comment, because "return true from a touch listener" reads like a mistake), and
track `longPressFired` so the `UP` that follows a held press is not also a click. Scrolling is unaffected —
RecyclerView still intercepts a vertical drag and the row then receives `ACTION_CANCEL`, which the existing
branch already treats as "no gesture here" — and a press that starts on one of the card's own buttons never
reaches this handler at all, because children are offered the down first.

**`TaskCardRowGestureTest`** is the CI-visible half and the first test in the repository that dispatches a
gesture the way a screen does: at `holder.itemView`, letting `ViewGroup` route it. Dispatching at the card
would prove nothing, since `View.dispatchTouchEvent` accepts whatever it is handed whether or not a real
parent would have routed the rest of the stream there. Five tests, four of them red before the fix for
exactly the reasons above (drag `expected:<-152.0> but was:<0.0>`; tap `expected:<1> but was:<0>`; tap
selecting rather than opening) and a fifth that was already green and guards the regression a naive fix
would cause — a held press must select *and not* open. The row is attached to a real activity window in
that fixture, because `View.postDelayed` on an unattached view queues into `HandlerActionQueue` and never
runs, which made the first draft of the test pass for the wrong reason.

**`TaskCardSwipeInstrumentedTest`** is the device half: swipe, assert `translationX == -actionPanel.width`
exactly, assert the edit button's centre clears the card's right edge, press it, and assert the editor
opens *for that task* — the assertion that discriminates, because a press that lands on the card calls
`closeSelf()` and goes nowhere. Then back, and the row is closed again. Green on both AVDs, and four
photographs in `after/`. `s2-tasks-swiped.png` was deleted, not overwritten: both it and
`s2-tasks-closed.png` hash to `6199671…`, so it had been filed under a name that claimed a gesture it never
showed.

**Two rules for every later stage, both from this debug session.** Before blaming a device for an
instrumented failure, check that it is the only one driving that device:
`adb -s "$S" shell dumpsys accessibility | grep -i "ui automation"` returning a line means a stale or
concurrent automation client holds the single `UiTestAutomationService` slot, and every run after it fails
as `UiAutomationService already registered` / `Cannot call disconnect() while connecting`, with screenshots
refused and input going to whichever window has focus. `emulator-5554` was, for part of this stage, being
driven by another session of this user's for a different project — its own app appeared in `mCurrentFocus`,
and a crashed `uiautomator dump` was in the crash buffer. Nothing of that session's was touched; the runs
moved to an idle AVD and say which one. And any horizontal gesture a test or a script injects must start
inside the target view, away from both screen edges, or it is the system's Back gesture and not the app's.

**And one more thing the full-suite run measured, in the same sitting.** The first *single* connected
invocation across the whole instrumented suite reported 19 tests and 3 failures — two of them in classes
that had been green per class. The reason is now named rather than assumed: one invocation is one app
process, `SessionRepository` is a Hilt singleton, and a class that seeds a profile through the database
writes `users.is_current` without being able to reach that singleton. So the class that ran after the report
screen was shown the report profile — `AmbiguousViewMatcherException: taskCard matches 8 views`, and
`ReportScreenInstrumentedTest` seeds exactly 5 + 3 = 8 tasks — and the editor class read an empty interval
for a series belonging to a profile it had just created. `clearPackageData` wipes once per invocation, not
once per class, so it does not help here. Two consequences: the per-class rule at §14.3 is kept and now
carries its mechanism, and `TaskCardSwipeInstrumentedTest` fails in its own `@Before` with the sentence
"this class seeds exactly one task, so the list belongs to it" instead of letting Espresso describe eight
strangers. The third failure is a different kind of finding and it is still open:
`ReportScreenInstrumentedTest.aReaderOpensTheReportFromTheirAccountAndCanHandItOver` cannot click
`reportZoneButton` on the second AVD, because Espresso requires a click target to be 90% visible and the
button is not, while `scrollTo()` refuses with "is descendant of a view matching (ScrollView … RecyclerView)"
— the report header is deliberately fixed (`fragment_report.xml:5-9` says why), so there is nothing to scroll
it into view. `fragment_report.xml` has not been touched since `c1b2c07`, and the two buttons are
`0dp`-with-weight inside a card, so the row cannot overflow its parent by design. That leaves the honest
statement: this class has not been run on the reference AVD since Stage 1's theme landed, so whether the
clipping is the second AVD's chrome or a Stage 1 text/chip height effect on the report header is **not yet
known**, and the claim "green on the reference device" is not being made for it. It goes on the open list
with Stage 8d, which owns the report screen: reproduce on `emulator-5554` in a quiet window, and if it
reproduces there too, treat it as a Stage 1 regression in the fixed header rows and fix the layout rather
than the test.

## 20k — Stage 3 executed: the header, the four actions it unblocked, and a regression it caught

`f3216b3` was the skeleton; the rest of the stage is one commit. What the screen looks like now is
`after/s3-header.png` — `Tasks` / `13 open` with a magnifier, a sort and a kebab beside them, the category
band scrolling above a 1dp hairline and the three status chips below it. What was not visible before is:
`after/s3-kebab.png` photographs New topic / Archive / Work history, and `after/s3-archive.png` and
`after/s3-work-history.png` are those two screens reached by pressing that menu — the first time either has
been reachable in any build of this app, because both existed only behind an options menu that a NoActionBar
theme never drew (audit §4.2).

Four things worth recording, all of them measured rather than assumed:

**A `PopupMenu` is visible to Espresso on this hardware.** The stage was planned around the opposite belief,
carried over from Stage 0's editor test, where the `AutoCompleteTextView`'s dropdown could not be read. That
generalisation was wrong: `inRoot(isPlatformPopup())` reaches a `PopupMenu`'s items, so
`TasksHeaderInstrumentedTest` presses the real sort and kebab entries and asserts their effect — the list
reordered by name, and the navigation landing on `archiveList` / `historyList`. The dropdown limitation stays
as recorded; it is about a dropdown, not about popups.

**`activeTaskCount` and "the number of visible rows" are different facts**, and the acceptance line named the
second while spec §4.1 named the first. The screen shows `activeTaskCount` — the account's open tasks — and
the instrumented test pins that choice by asserting the subtitle still reads `5 open` while a filter holds
the list to one row. The distinction only shows up while something is filtered, which is why the assertion
is made in the filtered state and not the clean one.

**Adopting `bar_selection.xml` found a regression Stage 1 had left waiting.** The include was written in
Stage 1 and never used, and its three icon buttons carried `android:contentDescription="@null"` — so the
moment `fragment_tasks.xml` switched to it, the selection bar's *Select all*, *Delete* and *Cancel* became
anonymous to a screen reader, including the destructive one. The old inline card had labels for all three.
The assertion that caught it is in `IncludeBindingTest`, and it went red first
(`contentDescription='null'`); the labels and their tints (`ink_accent`, `ink_error`, `ink_text_secondary`)
went back into the layout. The general lesson is in the gate list: an unused component is an unverified one,
and adopting it is a change that needs its own assertions, not a move.

**Two deviations from the step list, both small and both deliberate.** `header_sort` was not added, because
`action_sort` already reads "Sort" and a second string for the same word is a second string to translate;
and `filters_clear_all` was added, found unused by lint, and deleted rather than left declared — the chip's
own text carries the count, and a `contentDescription` on it would replace that count for a screen reader
instead of adding to it. `Filters · n` is a property of `TasksState` (`activeFilterCount`, with
`hasActiveFilters` now derived from it) so the number and the boolean cannot drift; the earlier draft of the
test held its own copy of the expression, which could only ever agree with itself.

Gates for the stage: 101 suites / **1416 JVM tests / 0 failures / 0 skipped**, `assembleDebug` and
`lintDebug` green, and `TasksHeaderInstrumentedTest` 5/5 on `emulator-5556` (`Pixel_4`, 1080x2280@440 —
`emulator-5554` was busy with another session's work, see §20j). The instrumented class seeds five tasks and
asserts the row count through `TasksAdapter.currentList`, counting `TaskRow` only, because a topic header is
not a task.


## 20l — Stage 4 executed: the card's three lines, and five things only a renderer could tell

Executed on `taskplanner_avd` / `emulator-5584` — the first stage of this branch that ran on the AVD this
project owns. Gates: **104 suites / 1442 JVM tests / 0 failures / 0 errors / 0 skipped**, `lintDebug` 0 errors
and 179 warnings (from 182: two `ConstantLocale` findings and one dead string went with the fixes), and
`TaskCardStatesInstrumentedTest` (12) plus `TaskCardWidthBudgetInstrumentedTest` (7) green at six
configurations — 393dp, 360dp and 320dp at font scale 1.0, 320dp and 393dp at 1.3, and 393dp in the dark
theme — 114 instrumented runs in all, against an installed `base.apk` whose sha256 equals the built
`app-debug.apk` (`6c9c0408c93de2e859419e8bbc7c5539328b30479c6fc409786885193b727a0d`). Six configurations took
two passes, and the reason is the last finding below.

Five findings, each of which survived a green JVM suite:

* **A `Flow` never narrows a child, so a give-way rule that measures the child cannot fire.** Stage 4 first
  wrote the 48dp category rule as "hide the name when `taskCategory.width < 48dp`". Under
  `app:flow_wrapMode="chain"` a child that lacks room is wrapped onto a line of its own, where it has its
  full text width — so the condition is false at every width the card ships at, and the JVM test that
  "proved" it passed because LEGACY metrics make the name 11dp wide. The rule is now decided on the room left
  on the line: `lineBFlow.width` minus the measured pill, the measured date and their two gaps. Measured at
  393dp: a 363px line, a 107px pill and a 108px date leave 126px (91dp) — the name is drawn. At 320dp: a
  321px line, a 136px pill and a 131px date leave 26px against an 81px floor — the name is gone, and the dot
  on line A still carries the colour. One threshold, measured, is what keeps this one layout rather than
  three. The same line, printed by the device for every configuration the matrix covers — `line B at …`, the
  floor being 48dp at the density each width sets:

  | dp | font | line | pill | date | gap | room | floor | name |
  |----|------|------|------|------|-----|------|-------|------|
  | 393 | 1.0 | 363px | 107px | 108px | 11px | 126px | 66px | shown |
  | 360 | 1.0 | 348px | 120px | 120px | 12px | 84px | 72px | shown |
  | 320 | 1.0 | 321px | 136px | 131px | 14px | 26px | 81px | gone |
  | 320 | 1.3 | 321px | 161px | 174px | 14px | −42px | 81px | gone, and the pair wraps |
  | 393 | 1.3 | 363px | 133px | 143px | 11px | 65px | 66px | gone, by one pixel |

  The last row is the honest edge of this design: at the widest supported panel and the largest supported
  font, the name is dropped by 1px of margin. It is stable — the same numbers came back on every pass of
  every matrix — and it is the right outcome, because a name squeezed to 65px against a 66px floor is a
  truncated name, and the dot and the rule already carry the category. What would be wrong is to "fix" it by
  lowering the floor, which is the same act as letting text clip.
* **The pill's type role was invisible to every metric available in a JVM.** `item_task.xml` gave `taskStatus`
  the `label` role, which sets `textAllCaps`; spec §3.3 and `type.xml`'s own comment name that as the thing to
  avoid, because "IN PROGRESS" measures about 105dp against the 82dp the budget allows. Robolectric's LEGACY
  `measureText` answers with the character count and ignores both `textSize` and case, so no JVM assertion
  could see the defect at all. The role is now `pill`, and the two allowances are asserted on the device:
  78dp and 79dp measured against 82dp and 84dp.
* **Two blank bitmaps are equal, and a comparison of two blanks passes.** The distinctness of `ic_play`,
  `ic_work` and `ic_resume` is proved by rasterising each under one flat tint and comparing pixels. The first
  version loaded them through the instrumentation's target context — which has no theme — and these vectors
  take their `fillColor`/`strokeColor` from `?attr/colorControlNormal`, so each rendered nothing: four empty
  bitmaps, all "different". The test now loads them through the activity, which is the context the card
  inflates under, and refuses a shape that covers fewer than 20 pixels. Coverage figures on this device:
  play 170, work 304, resume 242, pause 240, check 137, repeat 257, reminder 392.
* **One app process serves one profile.** `SessionRepository` is a Hilt singleton, and `clearPackageData`
  wipes once per invocation rather than per class or per test, so the user read at a process's first launch
  is the user every later launch of that run serves. A class that seeds a fresh profile in each `@Before` is,
  from the second test onward, reading the first test's rows — and because a task carries no foreign key to
  its user while its series, reminders and work intervals do, those rows survive with their children
  cascade-deleted. That is what produced the two symptoms this section nearly mistook for card defects: a
  repeat mark missing from a row whose state said it had a series, a work line missing from a row the database
  said was being measured, and a due line one minute away from the deadline the test had just written. Both
  Stage 4 classes now seed once in `@BeforeClass` and clean up in `@AfterClass`, and the matrix script
  force-stops the app around every class run.
* **A screenshot has to be checked, and the check has to work in the dark.** `uiAutomation.takeScreenshot()`
  answered twice with the Tasks header over a list that had not drawn its row — filed beside a passing
  geometry test, because the test had measured the row seconds earlier in a different frame. The capture now
  waits for the row to be laid out and shown, then requires the card's own rectangle to span at least 120
  points of luminance, and retakes the frame until it does. The first attempt at that check asked for "any
  dark pixel", which is true of every pixel in the dark theme and caught nothing; contrast, not level, is
  what distinguishes a card from an empty screen in either theme.

A matrix is only evidence if each run can be shown to have happened in the configuration it is named for, and
the first pass could not. Its script applied `wm density`, `settings put system font_scale` and
`cmd uimode night` and printed back `settings get secure ui_night_mode` — which reports the *user preference*
(`1`, auto) no matter what `cmd uimode` forced, so the line read `night=1` in a light run and in a dark run
alike and proved nothing. Worse, the transport dropped between the fifth and sixth configurations: four
`adb.exe: device 'emulator-5584' not found` and a `device offline`, after which the dark configuration ran its
tests at the previous font scale, because the `settings put` that should have set 1.0 had failed silently into
`/dev/null`. Nothing in the log said FAILED — the classes were green in a configuration that was not the one
being claimed. The pass-2 script therefore applies each setting, then refuses to instrument until the device
answers with `Physical density: 220`, `font_scale 1.0` and `mComputedNightMode=true` from `dumpsys uimode`,
retrying up to six times and aborting the configuration if it never lands, and it reports the byte length of
every screenshot tar it extracts — which is how the first pass's empty 393dp/1.3 width run was found at all (a
0-byte tar where 90KB should have been). The two dark photographs in `after/` are from pass 2; pass 1's are
discarded, and 4.8 says so.

Two smaller things worth recording. Espresso cannot act on a row below the fold, and the list's own order —
deadlines first, tasks with no deadline last — puts the plain row below the fold at 393dp, so the two gesture
tests scroll their target to the top first; and `onView(withId(R.id.editTaskButton))` on a seven-row list
matches seven views, so the panel assertions read the named row's own children. The clock seam went the way
4.6 allows rather than the way it prefers: the adapter takes a defaulted `TimeBase` and
`grep System.currentTimeMillis() app/src/main/java/com/taskplanner/ui/tasks/` returns nothing, which is the
rule the step states. And 4.9a's `TasksFragmentRenderTest` landed as `TasksCardRenderTest`: attaching
`TasksFragment` under Robolectric needs `hilt-android-testing` and a test application — a new dependency and a
new component, allowed only with cause — so the render path is covered through the real view model and the
real adapter over a real database, and the fragment itself is exercised on the device.
