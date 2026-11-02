# Localization

Task Planner speaks two languages: English and Russian. The reader picks one in
**Profile → Settings → Язык / Language**, and the choice applies immediately, survives a process death, a
force-stop, a relaunch and an app update, and changes nothing else — no profile is signed out, no row moves,
no navigation state is lost.

This document is the contract: what the switch is built on, what a language is allowed to change, how each of
those is checked, and what is knowingly left undone.

## The switch

| Piece | Where |
|---|---|
| The row the reader presses | `res/layout/fragment_settings.xml` (`languageGroup`), rendered and handled by `ui/settings/SettingsFragment.kt` |
| The three choices | `AppLanguage.SYSTEM_DEFAULT` / `ENGLISH` / `RUSSIAN` in `ui/settings/AppLanguage.kt` |
| What a choice does | `AppCompatDelegate.setApplicationLocales(...)`, the AndroidX per-app-locale API |
| What "system default" means | an **empty** `LocaleListCompat` — not `Locale.getDefault()` |
| Languages the system is told about | `res/xml/locales_config.xml`, declared as `android:localeConfig` on the `<application>` |
| Where the choice is stored | the framework, not the app: `autoStoreLocales` on `AppLocalesMetadataHolderService` in `AndroidManifest.xml` |

The choice is stored by the framework rather than in a `DataStore`, which is why it survives `pm clear` on
API 33+ and is cleared only by uninstall. `LanguageSwitchInstrumentedTest` measures that on a device instead
of asserting it from the documentation.

**A locale cannot be applied from anywhere.** `AppCompatDelegate.setApplicationLocales` with no live
AppCompat host is a silent no-op: it does not throw, and `getApplicationLocales()` goes on answering the
empty string. This was met twice — once in a first version of `CsvLocaleTest` that never launched an
activity, once in a Robolectric test that tried to move the delegate from a plain unit test. Anything that
changes the language does so from a window.

## What a language changes

Translating the strings is most of the work and none of the difficulty. The difficulty is the arithmetic that
reads a locale: a number, a plural, a first day of the week, a delimiter, a sort order. Each of these is a
promise, and each is tested where it is made.

**Plurals.** Russian has four plural forms — `one`, `few`, `many`, `other` — and picks between them by the
last digit and the last two digits, not by "one versus more". Every plural in the app carries all four, and
`PluralsContractTest` walks each one through 0, 1, 2, 4, 5, 11, 14, 21, 22, 25, 101 and 111 and checks the
form the CLDR rules select. The counts are not decoration: 21 is `one` («21 день»), 11 is `many`, and a
translation that got 21 right by accident is a translation that will be wrong at 101.

**Argument order.** Every format string uses positional placeholders (`%1$s`, `%2$d`). Russian moves words
around inside a sentence, so an unnumbered `%s` is a sentence that can swap a task title with a category
name. `StringFormatContractTest` compares the placeholder sets of the two files, and Android Lint's
`StringFormatCount` and `StringFormatInvalid` run as gates rather than as advice.

**The first day of the week.** A calendar that starts on Sunday is wrong for a Russian reader and a German
one, and right for an American. `CalendarMath.firstDayOfWeek()` asks the locale, and `CalendarLocaleTest`
proves on a device that the answer follows the language chosen *in the app* — not the language the handset
was sold with.

**Numbers, dates and month names.** A figure is written with the separators the reader reads numbers with,
and a date with the word order the reader writes dates with. `LocalisedDates` holds no date pattern at all:
it asks the locale's own medium style, which answers «Nov 30, 2026» in English and «30 нояб. 2026 г.» in
Russian. A month named as a heading is in the nominative («Октябрь 2026»), not the genitive a dated phrase
takes — see `ReportFormatting.month`.

**The CSV delimiter.** A Russian reader's Excel expects `;`, because their numbers are written with a `,`.
`CsvReport.defaultDelimiter()` picks the delimiter from the reader's locale, and `CsvLocaleTest` checks both
halves of the claim: the delimiter, and that a figure is not written `1.5` in one section of a report and
`1,5` in another.

**Sorting a list of names.** `Collator` is what makes «Яндекс» land where a Russian expects it rather than
where a code point says it must. It is applied to *displayed* names in `CategoriesViewModel`, `TasksViewModel`
and `CalendarAggregator`; the stored data and any manual ordering a reader made are untouched, so changing
language re-sorts what is on screen without rewriting what is on disk.

## Which locale the code asks

`ui/settings/AppLocale.current()` is the one answer, and production code asks it rather than
`Locale.getDefault()`.

The reason is a measured difference between API levels. On API 33 and up the per-app locale rewrites the
process default, so `Locale.getDefault()` follows the app's choice; on API 24 through 32 the AppCompat
backport applies the locale to the `Configuration` — which is what resource lookup uses — and leaves the
process default alone. A formatter built from `Locale.getDefault()` on those releases answers in the
language of the device. `CalendarLocaleTest` measures the API 35 case on a device; the accessor is what
covers the older ones, and `LocaleStateSourceTest` is a source-level gate that fails if a locale-dependent
object is built once and kept, or if a reader-facing format is resolved from the process default.

## Adding a language

1. Copy `res/values/strings.xml` to `res/values-<tag>/strings.xml`. Every entry, including the plurals with
   all the quantities that language's CLDR rules need.
2. Add the tag to `res/xml/locales_config.xml`, and add a `RadioButton` and a label to the language group in
   `fragment_settings.xml` and to `AppLanguage`.
3. Run `python scripts/check-localization.py`. It parses both XML files and exits non-zero with the exact
   names that are missing, the plural quantities that are absent, and any placeholder set that does not
   match. It also fails if either file parses to zero entries — a gate that passes because it compared
   nothing is worse than no gate.
4. Re-shoot the frames for the new language (below). Widths are a claim about strings.

## Proof

**JVM.** `StringCompletenessTest`, `PluralsContractTest`, `StringFormatContractTest`, `AppLanguageTest`,
`AppLocaleTest`, `AppLocaleContextTest`, `LocalisedNamesTest`, `LocalisedDatesTest`,
`LocaleStateSourceTest`, `EnglishLiteralAssertionTest`, `HardcodedFailureTextTest`,
`ScreensInBothLanguagesTest`.

**Device.** `LanguageSwitchInstrumentedTest` (the row, the apply, the recreate, the force-stop, the hand-back),
`CalendarLocaleTest` (first day of the week, in the app's language), `CsvLocaleTest` (delimiter and figures in
a real export), `Stage14LanguageFramesInstrumentedTest` (the frame matrix),
`TaskEditorRussianFitInstrumentedTest` (whether a Russian label fits the box it is drawn in, measured on the
device that draws it), `ProfileNavigationRestorationTest` (every tab after a `recreate()` — green on the device;
its language-change walk reaches six of ten legs before the open press defect, so Statistics and Profile are not
claimed in that direction).

The frame matrix lives in `docs/images/ui-redesign/after/s14/`, named
`s14-<language>-<width>-<theme>-<font scale>`. **Six configurations, seven screens each, 42 frames**: English
and Russian at 393dp light and dark with the font at 1.0, and both languages at 320dp with the font at 1.3.
English at 320dp/1.3 is in there because a truncated Russian heading is only a localization finding if the
same screen in English fits, and «Октябрь …» and "October 2…" turn out to cut identically — a width defect
that predates this stage, not a translation one. Every frame is checked before the
shutter opens: the run's tag must match the device's own width, theme and font scale, the window must be
resolving the language the directory claims, and the view the frame names must actually be on the window. A
frame that names one screen and photographs another is worse than a missing one, and the first matrix
produced exactly that.

Each configuration is two invocations with an `am force-stop` between them — the choose run, then the walk
run in a fresh process. The force-stop is load-bearing: the app holds its signed-in profile in a Hilt
singleton that reads `users.is_current` once, so a walk that reuses the process the choose run left behind
walks an app with no profile, and photographs an empty dashboard.

## What is knowingly not done

- **Five failure sentences are built in code, not in resources.** They were twenty-nine. The completion pass
  moved every one that reaches a reader into a resource — see *The completion pass* below — and what is left is
  named in `HardcodedFailureTextTest`: a default display name written into a row, two refusals inside a sign-in
  that cannot be reached without a Firebase project, an exception's own log note, and an `IOException` the
  report screen maps to a resource before it is ever shown. The ceiling is a ratchet, not a to-do list that has
  been forgotten, and the gate now sees the shapes the first version could not.
- **English literals in the JVM tests outside `ui/`.** `EnglishLiteralAssertionTest` gates `androidTest` with a
  stem rule, and gates the `ui` package of `app/src/test` with a whole-label rule; both are zero-tolerance. It
  does not gate `data/`, `backup/`, `report/` — where an English literal is a SQLite table name, an enum
  constant or an ISO timestamp, and 45 of the 49 lines a first run reported were exactly that. The two rules and
  the reason they differ are written in the class.
- **A machine's sentence can still reach a screen on three screens.** `ArchiveViewModel`, `GoalsViewModel` and
  the habit ViewModels put `error.message` straight into their state, so a storage complaint appears in a
  Snackbar in whatever language a library happens to write. There is no app-authored English there to
  translate — which is why this is not the defect the ratchet counts — but the fix is the same one the
  completion pass applied elsewhere: a resource for the reader, the log for the cause. It belongs to the
  error-handling stage.
- **A notification channel's name is set once by the platform.** `ReminderNotifier` now re-announces the
  name and description on every start, so a language change reaches a channel that already exists; the
  reader's own importance setting is preserved. What cannot be done from a notification channel is a
  re-translation of a notification that is already on the shade.
- **The device half of the proof is not complete, and says so in two places.** Discovery is settled at 25 classes
  (`*Test.kt`, one class per instrumentation process), and the matrix's uncontaminated run of the whole set on the
  verified bytes gave 16 PASS / 6 FAIL / 3 ENVIRONMENT; the press-shaped failures were then re-run individually and
  came back green or near-green (`ProfileNavigationRestorationTest` 2/2, `LanguageSwitchInstrumentedTest` 3/3,
  `ProfileNoUserInstrumentedTest` 2/2, `ReportScreenInstrumentedTest` 2/3), which is the part the lost-press
  diagnosis was for. The clean verdict for all 25 is still owed to a device that stays healthy for an hour: the
  batches after that one met `INSTRUMENTATION_RESULT: shortMsg=Process crashed.` at a guest load of 7.6–12.5, and
  one batch was invalidated by the operator's own gesture, which left `mCurrentFocus=NotificationShade`. And 28 of
  the 42 frames — every Russian configuration and both at 320dp — are from the build *before* the editor layout
  changed, because each capture run that chooses Russian died to the system's own ANR kill. What does stand for the
  Russian side is the instrumented measurement of the drawn widths, not a photograph. Both are recorded as
  `BLOCKED — emulator/system instability` in the roadmap's §14.15 rather than presented as done.

## The completion pass

Stage 14 shipped with four acceptance criteria unmet. This section is what closed them, with the numbers the
device produced rather than the numbers the layout claimed.

**A Russian label that does not fit is a width problem.** The plan grid of the task editor put two fields in one
row, so each column was 237px wide on a 393dp screen and its label had 149px of that. «Время начала» needs
153px. The grid is four full-width rows now, each giving its label 396px at 393dp/1.0 and 364px at 320dp/1.3,
where «Время окончания» — the widest of the six — asks 298px.

The footer could not be fixed by redistribution, and the arithmetic is why. At 320dp with the font at 1.3
«СОХРАНИТЬ» draws at **158px** and «ОТМЕНА» at **111px** — drawn, not declared: the `Label` role is all-caps with
0.045em of tracking, so the word in the strings file and the word on the screen are different widths, and
`Paint.measureText` ignores the tracking as well. A Material button spends 82px of its own box on padding, and
the save-in-progress figure reserves 67px. Three items, 527px of claim, 472px of row. English asked for 355px
and never complained, which is what "the Russian is too long" actually was: a row over-subscribed for one
language.

So the footer is two bands. The primary action holds its own row with the figure drawn over its trailing corner
— 418px of room for a 158px word — and the secondary action is sized by its own word below. The category editor
keeps one row, redistributed: its Save half was 11px short of its own label and broke «СОХРАНИТЬ» mid-word. No
word was shortened, none was broken, no text size was touched, every touch target stayed at 48dp, and English
was re-measured at every configuration the matrix uses. `TaskEditorRussianFitInstrumentedTest` is the gate, and
it is an instrumented one because no Robolectric test in this repository can ask whether a string fits its box.

**Runtime sentences became resources.** 24 call sites that wrote their own English now name a resource, and 40
resources arrived in both languages. Several already existed and nothing read them — `error_title_required`,
`error_email_taken`, `error_no_profile_first`, `error_backup_invalid` — so the app had been carrying a second
copy of its own wording in Kotlin. The state carries the **id** rather than the sentence, which needed no new
`Context` anywhere and fixed a second bug: a sentence written into a state object at the moment of failure
survives the recreation a language change causes, and stays in the language the reader has just left. The exact
per-line inventory the brief asked for is `docs/STAGE14-RUNTIME-SENTENCES.md` — 50 lines, each with its line
number in `01b4438`, the call it was written into, and the resource that replaced it, regenerated from the
commit by `scripts/inventory-runtime-sentences.py` rather than written from memory.

**A machine's text stopped being a screen's content.** Where the old code showed `e.message ?: "Failed to load
categories"`, a Russian reader saw a Russian sentence wrapped around a SQLite complaint. The app's own sentence
goes on screen now and the cause goes to `Log.w` — except where a resource was written to carry a detail
(`error_database_refused_move`, `sync_state_failed`), which still does, and says so in its parameter name
(`SyncStatus.Failed.detail`, `SignInOutcome.Failed.detail`). A refusal a reader can act on — "stop the timer
that is still running" — is a `ReaderRefusal` with a resource, not an `IllegalStateException` with prose.

**The tab that owns a screen carries the reader back to it.** The report was that Profile did not come back
after a recreation. It does: `ProfileNavigationRestorationTest` walks all five tabs, recreates the activity, and
changes the language on each, asserting the destination, the bar's selection and the attached fragment — one
copy, no duplicate. What was broken was the way out: Settings, Goals, Habits, Insights and Reports are reached
from the account screen and have no tab of their own, so the bar keeps Profile lit, and a re-selection of the
tab the bar already holds did nothing at all. It pops back to that tab now.

**The matrix then found three things the tests were assuming, not establishing.** They are the harness's
defects, and all three were caught by reading a failure's numbers rather than by re-running and hoping.

*The language a class starts in.* Stage 14 made the interface language a reader's choice, and the choice is kept
by the framework rather than in the app's own files — so the runner's `clearPackageData` between classes wipes
the database and the preferences and leaves the language exactly where the last class put it.
`ProfileNavigationRestorationTest` chose Russian and had no teardown at all, and its `@Before` handed the
language back *before launching*, which is the silent no-op `CsvLocaleTest` measures; the class after it failed
with "the run asked for 'en' and the window is resolving 'ru'". `LanguageSwitchInstrumentedTest` leaked the same
way for a different reason — it establishes the system default at the head of every test, which covers a passing
run and not a failing one, and it had no teardown either — and three classes after it
`TaskCardWidthBudgetInstrumentedTest` measured a date at **136px against the 115px allowance**, both numbers
correct, because the allowance is a budget for "Oct 25, 2026" and the card was drawing «25 окт. 2026 г.». So a
class that changes the language now establishes its starting language on a live window and hands it back on the
way out, in every path including the failing one: `DeviceLanguage.restore`, `@After` before `scenario.close()`,
and the reset moved below the launch where a class had it above.

*That a language change happened.* The same class walked five tabs in Russian and then five in English, which
asks for the language the app already holds on eight of the ten iterations: a repeated locale recreates
nothing, so there is nothing to restore, and eight assertions were passing about a screen that never moved. The
walk alternates now — Russian, English, Russian — and each iteration *asserts* its own premise: a different
activity instance, the delegate answering the tag, the window resolving it.

**A press that does nothing is a focus reading, not a retry.** Three classes in one matrix — the frame walk,
the report screen, the language switch — failed with the same impossible sentence: a tab was pressed and neither
the destination nor the bar's selection moved, and 45 or 60 seconds of waiting changed nothing. Espresso's
`click()` waits for the window to be idle and the view to be displayed; whether the tap *arrives* is the
window focus's decision, and a drawn-but-unfocused app window lets it land on whatever window the system holds
instead. The device's own numbers that minute: load average **16.83 on four cores**, 272% of its CPU in kernel
time, 96MB free of 2.5GB — and no crash: the crash buffer holds one `libgoldfish-ril` SIGSEGV at 16:00 device
time, before the run, and nothing since. So `AppWindow` reads focus, three classes wait for it before the first
press after any launch or recreation, and the failure messages carry `focused=` — which is what tells a lost
tap apart from a screen that refuses to draw. `Stage14LanguageFramesInstrumentedTest` already held this lesson
in its own `@Before`; it is in one place now, and applied where a class presses. Nothing here is a longer
timeout: a lost tap stays lost however long the wait is.

**…and one of them is not the device's doing.** After the four blocked matrices, a fifth run happened on a quiet
machine — load average **1.77**, `MemAvailable` 1,169,944 kB, the installed APK's sha256 read back from
`/data/app` and equal to `08838047e26e0d16…`, and the window focused. Its own logcat capture (4,294 lines) holds
no `ANR` line and no crash line at all. The same press was still lost:
`everyTabIsStillItselfAfterTheLanguageChanges` failed at the Categories leg with
`bar='navigation_calendar' controller='navigation_calendar' attached=[CalendarFragment] nested=0 resumed=true
shown=true focused=true language='en-US'`, and the stack says the failure is inside `go()` — the *press*, not the
restore. Run again as that method alone, in its own process, on the same bytes: **the same leg, the same
sentence** (1 test, 1 failure, 430 s) — and that second capture does contain one ANR, which is worth naming
exactly rather than glossing: `ANR in com.android.chrome`, `Reason: No response to onStopJob`, killed as a
background process at `adj 905`, with `ANR in com.taskplanner` appearing zero times in either file. What that run
also settled is the other half of §6: the five tabs through a bare `recreate()` passed in the same process, so
the destination really does come back from an ordinary recreation, and it is the language walk's press that is
open. *That press is now closed* — the section below records what it actually was, which turned out to be neither
of the two explanations this file carried for it.

## The device verification pass of 2026-11-01

The completion pass left one sentence open: *a bottom tab pressed that does nothing, on a window that is focused
and whose activity has settled*, with two candidate causes and no measurement that could separate them. This pass
took that measurement. The answer was neither candidate, and the two classes that had failed for the whole
completion pass are now green on the same APK bytes.

**What was measured.** `testing/NavIdentity.kt` reads, for every live activity the process holds, the identity of
the activity, its window, its decor view, the decor's application window token, its `BottomNavigationView`, its
menu, and the item view the matcher would press — plus the bar's `selectedItemId`, the `NavController`'s current
and previous destination, the attached fragments and the nested back-stack count. `probeEspressoTarget` runs a
`ViewAction` that records and changes nothing, so the run learns which object `withId(...)` resolved to *before*
anything is tapped, and compares it with the resumed activity's own item view; `accessibilityCopies` counts the
nodes with that id inside the window the system is actually sending input to, through the accessibility tree
rather than through Espresso.

**What that ruled out.** In every failing press there was exactly one live activity — `activity=… state=RESUMED`,
one `bar=…`, one `item=…`, `attached=true`, `shown=true`, `focus=true`, destination and selection agreeing. The
stale-window story needed a second bar to tap; there was not one to find. And the re-selection story needed the
bar to be holding the pressed tab already; the readings show it holding the *previous* tab (`selected=navigation_tasks`
while `navigation_profile` was pressed), which is a normal navigation, not a re-selection.

**What the cause was.** The app's own log said it, at the moment of the press, in the app's own process:

| The capture | The device's line beside the press | What the press did |
|---|---|---|
| `…ProfileNavigationRestorationTest.014532.logcat.txt` (the walk, ten legs) | `22:48:14.994 Window{… Tooltip}` and `22:48:16.047 W/MotionEvents(8230): Overslept and turned a tap into a long press` | the Categories leg lost, twice |
| `…TabPressIdentityInstrumentedTest.044455.logcat.txt` (a clean single-window launch, no language touched) | `Displayed com.taskplanner/.ui.MainActivity: +30s754ms`, `Skipped 278 frames`, `01:46:15.657 Tooltip`, `01:46:16.954 Overslept (22809)` | the very first press lost |
| the same class re-run (`…045519…`) | no `Overslept` line in the whole capture | the same press landed |
| `…LanguageSwitchInstrumentedTest.053437.logcat.txt` (now `OK (3 tests)`) | six `Overslept` lines; `TabTap` readings `click moved=false, tightTapTried=true moved=true` on two of them | 3 tests green, including the pop-back |

Espresso's `click()` injects a DOWN, pumps the main thread for the gesture's span, then injects an UP. When the
app's thread is seconds behind — and a cold start after `clearPackageData` is exactly that: thirty seconds to the
first frame, 353 frames skipped, a 8 849 ms Davey — the span the platform measures between the two events passes
`ViewConfiguration`'s long-press timeout of **400 ms**, and the gesture becomes a long press. A long press on a
Material navigation item opens a tooltip and performs no click. That is the whole of "the tap was performed and
nothing moved": the tap was performed as something else. Espresso writes its own warning about it and returns the
interaction as successful, which is why four matrices could report a refusing app and mean a busy machine.

**What changed, and what did not.** The harness changed: `testing/TabTap.kt` waits for the app's main thread to
answer a round trip in 100 ms or less, performs the ordinary click first, and only if the destination did not move
injects a DOWN and UP as one 20 ms sequence (`SOURCE_TOUCHSCREEN`, which Stage 7 established is not decoration).
Every press reports `click moved=`, `tightTapTried=`, `mainThreadRoundTrip=`, the matcher's target and the
long-press timeout, and its verdict is one of `pressed normally`, `TAP-DEGRADED` or `PRESS-REFUSED` — so a machine
that stretches a tap says so in the log instead of leaving the next reader to blame the navigation. It is wired
into the four classes that press tabs (`ProfileNavigationRestorationTest`, `LanguageSwitchInstrumentedTest`,
`Stage14LanguageFramesInstrumentedTest`, `Stage8aScreensInstrumentedTest`) with their assertions unchanged: each
still requires the tab to be reached. **Nothing in `MainActivity`, the bar's listeners, the nav graph or fragment
lifecycle was touched by this pass**, because the measurement did not find a navigation defect to fix.

**Two script bugs the same pass turned up, both in the environment gate.**
`prep-emulator-for-matrix.sh` used `$TARGET` without defining it, so the stale-external-directory check was running
against `/storage/emulated/0/Android/data/` — the parent of every app's directory — and printing that the owner
"agrees", which meant the EACCES guard this file documents had never once protected a run. Defining it exposed the
second bug: the script assigned to `UID`, which bash keeps readonly, so the assignment failed, the comparison fell
back to the *host's* uid (197609 here), and the guard deleted a directory that did belong to the app. A check
whose variable cannot be set is worse than no check, because it acts. `run-instrumented-classes.sh` now prints the
rest of §5's pre-flight (`sys.boot_completed`, the System UI pid, the app's uid and process, crash-buffer lines,
host free MB) and refuses with exit 7 when the installed app is not the built APK, and it classifies every row —
`PASS`, `FAIL`, `ENVIRONMENT` — with a summary line, so a class that produced no verdict line can no longer be read
as "0 failed".

**The same evening closed the two things that section left owed.** The matrix ran to **25 classes discovered = 25
attempted, 108 tests / 108 passed, PASS=25 / FAIL=0 / ENVIRONMENT=0** against the unchanged
`08838047e26e0d16c2ddd56f1e5fac9d3887c99cde480ac0307f90339e0936fc`, after the guest was restarted cleanly (an
`adb reboot` had left it with no `system_server` for 45 minutes) and the app's cold start went from
`am start -W → Status: timeout` at 30–40 s to **6 027 ms**; and the 28 owed frames were captured through the
capture script's own provenance gate, so all 42 in `docs/images/ui-redesign/after/s14/` are photographs of one
build. The October frames they replace live in `docs/images/ui-redesign/superseded/` with the reason written down.
The full account, including the two readings that had to be corrected on the way — the press reader's blind spot
and a locale oracle that was asking the app what the device's language was — is §14.16 of
`docs/PRODUCT_ROADMAP.md`. What remains open after Stage 14 is named there: Google Sign-In, the API 24–32 half of
the locale behaviour, the calendar heading at 320dp/1.3, and one locale assertion that has not reproduced.

## The Keep import strings (Stage 15, 2026-11-02)

Forty-seven strings and five plurals, in both files, in the same commit that needed them — the rule this
document states for Stage 14 and `scripts/check-localization.py` enforces on every run: no new key in one
locale without its counterpart in the other, no positional placeholder missing, no plural quantity absent.

Two things about this batch are worth the extra paragraph, because both were decisions rather than
translations:

* **Counts that travel inside a sentence are plurals; counts shown beside a label are not.** The preview's
  statistics block is twelve rows of *label and number*, so neither language has to agree a noun it is not
  being shown — a screen that assembled «56 тем» in Kotlin would be wrong in one of the two languages by
  construction. Where a number sits inside a sentence — the result line, the warning header, the deepest
  branch, a conflict — the resource is a `plurals` entry with all four Russian forms, and `keep_import_result_*`
  is two of them joined by a **joiner that is itself a resource** (`keep_import_result_joiner`: ", " against
  " и "), because "2 topics imported, 5 tasks added" and "импортировано 2 темы и добавлено 5 задач" are the
  same code path with different glue. That is the shape Stage 14 established with `habit_weekday_joiner`.
* **The result of a failed import is an enum, not a sentence.** `KeepImportResult.Failed` carries a
  `KeepImportFailure` and the machine's own note; the fragment resolves the words. The gate that keeps it that
  way (`EnglishLiteralAssertionTest`, `HardcodedFailureTextTest`) caught this stage twice on the way: once when
  the importer started out writing English explanations into its result, and once when an instrumented assertion
  compared a stored `status` to the literal `"COMPLETED"` — which is a column value, not UI text, and is now
  compared to `TaskStatus.COMPLETED` instead.

`python scripts/check-localization.py` on the final build: pass, 985 English strings against 980 Russian
(the five keys English alone carries are deliberately untranslated: `app_name`, the two language names `language_english` and `language_russian` — each written in the script its own reader recognises — and the two format labels `report_format_csv` and `report_format_pdf`), 53 plurals on each side, 0 missing, 0 placeholder mismatches.

## Review ledger

Every finding Stage 14 produced, the check that settled it, and where the evidence lives. `ИСПРАВЛЕНО` means a
test saw it fail before it saw it pass; `ОТКРЫТО` means it is real, unfixed, and named here rather than in a
commit message three stages away.

| Finding | Reviewer | Проверка | Статус | Evidence |
|---|---|---|---|---|
| Dates carried a written-down American order in every language: a Russian card read «нояб. 01, 2026» | The device frame, not a test | RED on `CardPresentationTest`, then `LocalisedDates` replacing five literal patterns, then the frames re-shot in both languages | ИСПРАВЛЕНО | `LocalisedDatesTest` (7 cases), `a date line opens with the day in a locale that writes the day first`; `s14-ru-393-light-10/01-tasks.png` vs `s14-en-393-light-10/07-task-detail.png` |
| An existing test named that exact defect in its own KDoc and passed anyway, because it compared two locales to each other instead of to what either says | Self-review of the diff | Kept (it still proves the month word travels) and joined by a positional test that asks one locale for its own order | ИСПРАВЛЕНО | `CardPresentationTest` — the two cases side by side |
| `01-tasks.png` in a `ru` directory was a photograph of Settings, and other `ru` frames were English screens | Reading every captured frame | `photograph()` now refuses a frame whose named view is not on the window; the walk goes to Tasks through the bar before the first shutter | ИСПРАВЛЕНО | `Stage14LanguageFramesInstrumentedTest.photograph`, `gotoTab` |
| The walk reached Statistics and photographed a blank panel: `findViewById` answers a container the renderer had left `GONE` while the first snapshot was loading | Comparing a frame against `DashboardRenderer`'s visibility rules | `settled()` asks `isShown`, and the seed now writes two measured intervals so the dashboard has figures to render in the reader's number format | ИСПРАВЛЕНО | `s14-ru-393-light-10/04-statistics.png`, `s14-en-393-light-10/04-statistics.png` |
| The walk ran against the process the choose run left behind, so no profile was signed in: empty dashboard, no `profileName`, and a failure message that named a tab | `uiautomator` dump + `Settings` showing «Локальный профиль» while `Profile` showed none | `am force-stop` between the two runs of every configuration, documented as load-bearing | ИСПРАВЛЕНО | `scripts/capture-stage14-matrix.sh` header; the class KDoc |
| `scripts/run-instrumented-classes.sh` discovered classes by `*InstrumentedTest.kt`, so `CalendarLocaleTest` and `CsvLocaleTest` were never run — and it printed a clean twenty-class matrix while missing two on-device classes | Counting the tree against the script's own list | Pattern widened to `*Test.kt`; both classes now run in their own process | ИСПРАВЛЕНО | The matrix table in §14.13 of the roadmap |
| `check-localization.py` could print "0 missing" against two empty files and exit 0 | Mutation: pointed it at an empty directory | A pass now requires both sides to have parsed at least one entry | ИСПРАВЛЕНО | `compared = bool(default) and bool(russian)` |
| Channel names and descriptions were frozen in the language of the first install | Architecture pass over the language switch | `ensureChannel`/`ensureHabitChannel` re-announce every call; importance preserved | ИСПРАВЛЕНО | `ReminderNotifierTest` — `an existing channel is re-announced…`, `the habit channel is re-announced too` |
| `ReminderNotifier` resolved all 17 of its strings from the **application** context, which AppCompat does not wrap — so on API 24–32 a Russian app posted English notifications | Self-review after the channel fix, asking which context the strings came from | `AppLocale.localized(Context)`; the notifier reads through it | ИСПРАВЛЕНО (JVM; the device is API 35, where the framework already agrees) | `AppLocaleContextTest` (4 cases) |
| Eleven locale-dependent objects were built from `Locale.getDefault()`, right on API 33+ and wrong on 24–32 | `LocaleStateSourceTest`, written for this | Every reader-facing site moved to `AppLocale.current()` / `LocalisedDates` | ИСПРАВЛЕНО structurally, OPEN as a measurement (no API 24–32 device) | `git diff` of `CalendarMath`, `CsvReport`, `DashboardMath`, `GoalCard`, `HabitCard`, `InsightCard`, `WorkloadPanel`, `WorkInstantInput` |
| A leaked Russian locale from one instrumented class changed the assertions of the next | An instrumented run that passed alone and failed after `CalendarLocaleTest` | `@After` resets the locale **before** closing the window; the leaked record was cleared by uninstall, not `pm clear` | ИСПРАВЛЕНО | `CsvLocaleTest`, `CalendarLocaleTest` teardowns; the persistence finding in `LanguageSwitchInstrumentedTest` |
| Eighteen Russian entries carried wrong government, a calqued idiom, a missing comma or an awkward plural | Native-reading pass, screen by screen | Each rewritten and re-checked against the screen it appears on | ИСПРАВЛЕНО | `git diff app/src/main/res/values-ru/strings.xml` — `achievement_active_days_3_condition`, `dashboard_open`, `error_database_refused_move`, `google_account_missing`, `habit_reminder_channel_description`, `habits_empty_open`, `insight_completion_weekday_basis`, `insight_habit_miss_body`, `plan_hint_estimate`, `repeat_scope_hint_future`, `report_bytes_format`, `report_csv_shown_format`, `report_finding_superseded`, `report_none_dynamics`, `report_note_habit_series`, `report_note_set_goal`, `report_note_undated`, `work_no_intervals` |
| Plan-time field hint «Время окончан…» is ellipsized in Russian at 393dp/1.0 and at 320dp/1.3, while English "End time" fits both | The frame matrix, against the English frame at the same settings | Cause measured on device: an empty field draws its hint at full text size inside a half-width column — «Время начала» needed 153px of a 149px box, and English "Starts on" needed 147px of 121px at 320dp/1.3, so the grid was the defect and Russian only showed it. Four full-width rows now | ИСПРАВЛЕНО | `TaskEditorRussianFitInstrumentedTest.everyPlanLabelFitsItsBoxInBothLanguages`; `stage14 fit [ru] end time: needed=203px available=396px` at 393dp/1.0 and `298px / 364px` at 320dp/1.3 |
| «СОХРАНИТЬ» wraps mid-word («СОХРАН / ИТЬ») in the editor footer at 320dp/1.3; English «SAVE» does not | The frame matrix | Measured, not guessed: the drawn form is 158px (all-caps + 0.045em tracking — the strings file's word is narrower than the word on screen), the box left 106px, and the row could not be redistributed because spinner + two labels claim 527px of 472px. The footer is two bands now, and the category editor's pair was 11px short of its own word and got the same treatment | ИСПРАВЛЕНО | `TaskEditorRussianFitInstrumentedTest.theFooterButtonsHoldTheirLabelsOnOneLineInBothLanguages`, `theCategoryEditorHoldsTheSameTwoLabelsInOneRow`; `fragment_task_detail.xml` footer comment |
| The calendar month heading is truncated at 320dp/1.3 — «Октябрь …» | The frame matrix, then English at the same settings to find out whose defect it is | «October 2…» truncates identically: it is a width-and-font defect, not a localization one | **ОТКРЫТО, не Stage 14** | `s14-en-320-light-13/02-calendar.png`, `s14-ru-320-light-13/02-calendar.png` |
| Tapping the Profile tab while the bar already holds it navigates nowhere, so Settings cannot be left through the bar | `LanguageSwitchInstrumentedTest` | Control test reached the state in English with no locale touched — it predates Stage 14. The completion pass fixed it: a re-selected tab pops back to its own destination, and the recording test was rewritten to the claim it promised, choosing Russian through the row on the way | ИСПРАВЛЕНО | `theTabThatOwnsTheScreenCarriesTheReaderBackToIt` (run RED first: "pressing navigation_profile left the app on navigation_settings"); `MainActivity` footer comment |
| The tab was reported as "Profile not restored after recreation" | `ProfileNavigationRestorationTest`, written before touching anything | **The framing was wrong and the fix would have aimed at nothing.** The destination the brief asked to preserve was never lost: all five tabs come back as themselves — destination, bar selection and one attached fragment — after a bare `recreate()`, green on the device in the fifth run. Through a *language* change the same holds for every leg the walk reached (Tasks and Calendar in both directions, Categories in Russian); the walk's Categories/en-US leg dies on a lost press before Statistics and Profile get their turn, so those two are untested in that direction on this build | ИСПРАВЛЕНО (the reported defect), and **the language leg is now green too**: the pass that measured the lost press made `ProfileNavigationRestorationTest` report `OK (2 tests)` — all five tabs through a `recreate()` and all ten legs of the alternating ru/en-US walk | `ProfileNavigationRestorationTest` — 2 tests; `build/instrumented-probe/com.taskplanner.ui.ProfileNavigationRestorationTest.052909.log` (`OK (2 tests)` on the same APK bytes) |
| A gesture the test framework itself admitted degrading was reported as a successful interaction | `TabTap`, which performs the ordinary click and then a 20 ms DOWN/UP sequence, and prints which one moved the app | Espresso logs `Overslept and turned a tap into a long press` in the app's own process and returns the `ViewInteraction` as performed; nothing in the failure message distinguished "the app refused" from "the platform did not see a tap". Every press in the four classes that touch the bar now carries `click moved=`, `tightTapTried=`, `mainThreadRoundTrip=`, `longPressTimeout=` and a verdict of `pressed normally` / `TAP-DEGRADED` / `PRESS-REFUSED`, so the machine's timing is a reported condition rather than an inferred one | ИСПРАВЛЕНО (the reporting and the gesture; no assertion was relaxed) | `app/src/androidTest/java/com/taskplanner/testing/TabTap.kt`, the readings quoted in the row above |
| The environment gate the completion pass wrote had two bugs that made it weaker, and once destructive, than its own comments claimed | Running it on this project's AVD while the app was installed, and reading the printed lines against `dumpsys package` | `TARGET` was never defined, so the stale-directory check compared the *parent* of every app's directory and always reported agreement — the EACCES guard had never protected a run. Defining it exposed the second: the script assigned to `UID`, which bash keeps readonly, so the fallback was the host's uid (197609) and the guard deleted a directory that did belong to the app (the app's own external scratch area, which the runs recreate — nothing else lives there). `run-instrumented-classes.sh` also gained §5's remaining readings and a hash check that refuses (exit 7) when the installed app is not the built APK, and it now classifies each row `PASS` / `FAIL` / `ENVIRONMENT` instead of printing a missing verdict as zero failures | ИСПРАВЛЕНО | `scripts/prep-emulator-for-matrix.sh`, `scripts/run-instrumented-classes.sh` |
| 29 failure sentences are built in code, not resources | `HardcodedFailureTextTest` | The gate's own reasoning — "a ViewModel holds no Context" — turned out to be the defect's excuse rather than its limit: the state carries the resource id and the fragment resolves it. 24 sites moved, 40 resources added in both languages, and the gate widened to see the shapes it could not (a named constant, an exception's text handed to a sink) | ИСПРАВЛЕНО, 5 left and each named | `HardcodedFailureTextTest` (CEILING = 5, with the reason per site) |
| `app/src/test` still compares some rendered text to English literals | `EnglishLiteralAssertionTest` | Gated now for the `ui` package at zero tolerance; three sites fixed to ask their own `getString`/`getQuantityString`, three exempted with the reason in the gate, and 45 of the 49 a first run reported shown to be the detector matching a SQLite table name | ИСПРАВЛЕНО | `EnglishLiteralAssertionTest.theJvmSourcesCompareNoRenderedLabelToItsEnglishText`, `ScreensInBothLanguagesTest` |
| Five reader-facing formats still read `Locale.getDefault()` **at call time**, which the companion-object sweep could not see — a calendar month label, a dashboard percent and number, an insight's weekday and its clock span | Auditing the sweep's own claim by grepping the module afterwards | All five moved to `LocalisedDates.locale()`; a second rule now fails the build if any production file outside `AppLocale.kt` reads the process default at all | ИСПРАВЛЕНО (JVM; API 35 cannot distinguish the two) | `LocaleStateSourceTest.onlyTheAccessorReadsTheProcessDefault` |
| Three instrumented classes failed with "the tab did not draw its screen" only when run after the Stage 14 frame walk | First read as a restored-task state, and `-e clearPackageData true` was added on that theory | **The theory was wrong, and the control disproves it:** with the flag in place the same three classes still fail, and `SeededProfileCleanupTest` — green twice earlier the same day, and a class that presses no tabs at all — now fails identically. The runner change is kept because it is what Gradle does and the raw invocation was not doing it, but it is not the cause and is not claimed as a fix | Runner corrected; the three classes **OPEN**, blocked by the device, not by the code | `build/instrumented-full/*.log`, `scripts/run-instrumented-classes.sh` |
| The emulator is unhealthy, and it is what is stopping the device gate | Three separate readings, each before believing a failure | `logcat -b crash` shows `DeadSystemException: The system died` across six processes and a Bluetooth stack crash-looping every two minutes (`stack_manager.cc:57 StartUp: Can't start stack`) with `com.taskplanner` absent from it; `ActivityManager: Killing com.android.phone: bg anr`; and `dumpsys window` reporting `mCurrentFocus=Window{… Application Not Responding: com.android.systemui}` while `ps` shows the app's own process alive. Two reboots (7 minutes to `sys.boot_completed=1`) and disabling the unused Bluetooth stack did not clear it; host free RAM is ~3.2 GB | Environment, not a defect. **The instrumented matrix has therefore not been re-run against the final build**, and this document does not claim it was | `adb logcat -b crash`, `adb shell dumpsys window`, `build/instrumented-full/SeededProfileCleanupTest.log` |
| Three frame runs failed and stayed failed after a reboot: two `shortMsg=Process crashed`, one `RootViewWithoutFocusException` | Reading the device's own crash buffer before believing any of it | `logcat -b crash` shows a crash loop in `com.google.android.bluetooth` (`system/gd/stack_manager.cc:57 StartUp: Can't start stack`, `hci_layer.cc:478 on_hardware_error 0x42`) restarting every ~2 minutes; `com.taskplanner` appears nowhere in it. The app was never the failing process. Repair: reboot (4½ min to `sys.boot_completed=1`), then disable the unused stack (`pm disable-user com.google.android.bluetooth`), which stopped the loop | Environment, not a defect | `build/stage14-matrix/*.log`, `adb logcat -b crash` |
| …and the harness let that be reported as a test failure | The same reading | `waitForWindowFocus()` now runs before the first press: a window that draws without taking focus passes `findViewById` and then dies ten seconds later inside an unrelated tab press, blamed on the app. When focus does not arrive the run stops with `ENVIRONMENT, not a screen defect:` in the message, so a machine problem and a Russian string can never again be read as the same event | ИСПРАВЛЕНО (the reporting, not the emulator) | `Stage14LanguageFramesInstrumentedTest.waitForWindowFocus` |
| The completion pass's own new class leaked its language into the class after it: it chose Russian, had no teardown, and handed the language back in `@Before` — before any window existed, which is the silent no-op `CsvLocaleTest` measures | The next class's own sentence: "the run asked for 'en' and the window is resolving 'ru'" | The reset moved below the launch, an `@After` hands it back with the window alive, and `DeviceLanguage` holds the rule for the classes that measure in a language rather than change one. The language is not in the app's own files, so `clearPackageData` between classes does not take it back — which is the right behaviour for the promise the row makes and the wrong one for a matrix | ИСПРАВЛЕНО | `ProfileNavigationRestorationTest` `@Before`/`@After`, `LanguageSwitchInstrumentedTest` `@After`, `TaskEditorRussianFitInstrumentedTest` `@Before`, `Stage14LanguageFramesInstrumentedTest.openTheApp`, `testing/DeviceLanguage.kt` |
| A second leaker, three classes of run later, produced a measurement failure with two correct numbers: a date at 136px against spec §4.1's 115px allowance. `LanguageSwitchInstrumentedTest` established the system default at the head of each test but had no teardown, so a failure halfway through a language walk left the app Russian | Reading which string the card was actually drawing | «25 окт. 2026 г.» is wider than "Oct 25, 2026", and the allowance is a budget for the English one; the card was fine and the premise was missing. The class now establishes the language it measures in before it measures | ИСПРАВЛЕНО | `TaskCardWidthBudgetInstrumentedTest.openTheScreenTheWorstRowWasSeededFor`; `build/instrumented-full/TaskCardWidthBudgetInstrumentedTest.log` |
| Eight of the restoration test's ten "language changes" asked for the language the app already held, so nothing recreated and eight assertions were passing about a screen that never moved | Reading the loop instead of its result — the matrix gave no signal at all here, because vacuous iterations pass | The walk alternates Russian / English per tab, and every iteration asserts its own premise: a different activity instance, the delegate answering the tag, the window resolving it. A premise stated cannot be dropped by a later edit without failing | ИСПРАВЛЕНО | `ProfileNavigationRestorationTest.chooseLanguage`, the second bullet of its class note |
| Three classes in one matrix failed with a press that moved neither the destination nor the bar's selection, after 45–60 s of waiting | `top` on the device: load average **16.83** on four cores, 272% of CPU in kernel time, 96MB free of 2.5GB; `logcat -b crash` for the run: nothing since a single `libgoldfish-ril` SIGSEGV before it | Espresso waits for idle and for *displayed*; arrival of the tap is the focus's decision, and an unfocused app window lets it land on another window entirely. Focus is now read and awaited before the first press after any launch or recreation, and the expired waits carry `focused=` — which is what separates a lost tap from a screen that refuses to draw. Not a longer timeout: a lost tap stays lost | ИСПРАВЛЕНО (the reporting and the premise); the device's load stays environment, recorded rather than fixed | `testing/AppWindow.kt`, `LanguageSwitchInstrumentedTest.pressTab`, `ReportScreenInstrumentedTest.navState`, `ProfileNavigationRestorationTest.go`/`describe`, `scripts/run-one-class-with-logcat.sh` |
| A class that writes its own proof file failed with `open failed: EACCES (Permission denied)` inside the app's **own** external files directory | `stat -c %U /storage/emulated/0/Android/data/com.taskplanner` answered `u0_a316` while `dumpsys package` said the app was now `u0_a318` | An uninstall/reinstall — done to clear the leaked language — left the directory owned by the uid that no longer exists, and the new one cannot write into it. The stale scratch area is removed by `prep-emulator-for-matrix.sh`, which compares the two and says so; the proof PDFs and frame shots inside it are regenerated by the runs that write them | ИСПРАВЛЕНО (the device state, and the trap is now a check) | `scripts/prep-emulator-for-matrix.sh`; `build/instrumented-full/ReportPdfBytesInstrumentedTest.log` before and after |
| The device is the reason a matrix is green or not, and nothing printed that before | `uptime` in the guest: `load average: 19.05, 17.30, 14.58` on four cores with nothing running, later 41.8 after a reboot; `am start -W com.taskplanner/.ui.MainActivity` → `WaitTime: 17612`; dropbox `Input dispatching timed out ([Gesture Monitor] edge-swipe (server) is not responding. Waited 12756ms for MotionEvent`; the host itself was only ~56% busy | The matrix prints load, MemAvailable, the focused window and the ANR count before it runs and again after, refuses to run into an error dialog, and waits up to ten minutes for the load to come under `WAIT_FOR_LOAD` (default 12 — this image sits at 8–15 while the operator's tools work). `settings put global hide_error_dialogs 1` and disabling four background packages this project's tests never touch are recorded in `prep-emulator-for-matrix.sh`, with `com.google.android.gms` deliberately left alone because the account code under test needs it | ИСПРАВЛЕНО (the reporting); the machine's ceiling is recorded, not argued away | `build/instrumented-full/MATRIX-RUN-*.log` headers; `scripts/run-instrumented-classes.sh` |
| An assertion about a Snackbar's text compared it to the English head of a resource value, behind a named constant | Found by the failure it caused on a Russian screen ("the saved sentence did not happen") after the language leak was fixed, and by reading why the literal gate had not flagged it | `SAVED_PREFIX = "Written to the file you chose"` was the first twenty-eight characters of `report_saved_format` copied into the test. The gate's three blind spots were the reason: the line was not an assertion call, the words came from a constant resolved only inside Espresso matchers, and a *head* of a longer sentence matched neither the whole-label nor the word-stem rule. All three were widened — `startsWith`/`equals` are now assertion shapes, constants resolve through them, and a literal of twelve characters or more with a space counts when it is the opening of a resource value — and the test asks the resource for its own sentence in the reader's language | ИСПРАВЛЕНО, with the gate case that proves the shape is now seen | `EnglishLiteralAssertionTest.theDetectorFindsTheShapesItClaimsToFind` (the new case), `ReportScreenInstrumentedTest.savedOpening` |
| A tab press that does nothing, on a window that is focused and whose activity has settled | Identity, not inference: `testing/NavIdentity.kt` read the activity, window, decor, window token, bar, menu and item view of every live activity, and `probeEspressoTarget` recorded which object the matcher would actually tap; then `TabTap` compared the ordinary click with a 20 ms DOWN/UP sequence on the same bar | **ИСПРАВЛЕНО — and it was neither candidate.** One live activity, one bar, the matcher's target identical to the resumed activity's own item view, focus held: so no stale window. Selection was the *previous* tab at press time, so no re-selection either. The app's own log gave the answer at the press — `W/MotionEvents: Overslept and turned a tap into a long press` with a `Window{… Tooltip}` beside it — because Espresso's click pumps the main thread for the gesture's span, and a thread 353 frames behind stretches it past the 400 ms long-press timeout; a long press on a navigation item shows a tooltip and never becomes a click. The fix is in the harness (`TabTap`, wired into the four classes that press tabs); `MainActivity` and the nav graph were not touched. The same capture without the oversleep line navigated fine | ИСПРАВЛЕНО (the harness's gesture; no production change was justified) | `build/instrumented-probe/com.taskplanner.ui.TabPressIdentityInstrumentedTest.044455.logcat.txt` (oversleep at 01:46:16.954, `Displayed +30s754ms`) vs `…045519.logcat.txt` (none, press landed); `LanguageSwitchInstrumentedTest.053437.log` = `OK (3 tests)` with `TabTap` showing `click moved=false, tightTapTried=true moved=true` twice, including `destination navigation_settings->navigation_profile` — the pop-back reached; `ProfileNavigationRestorationTest.052909.log` = `OK (2 tests)` |
| The one test that claimed the choice survives a process death proved something else, and depended on which class ran first | Reading its premise check fail on a clean device after the leak was fixed | The method makes its own choice now: chooses Russian through the row, closes the window, opens another, and asserts the new activity speaks Russian — then hands the language back through the row. What a second activity in one process cannot show is named in its KDoc and belongs to `Stage14LanguageFramesInstrumentedTest`, whose `am force-stop` sits between the run that chooses and the run that walks, written into the capture script rather than assumed from the matrix's order | ИСПРАВЛЕНО (the claim is now the one the test can make) | `LanguageSwitchInstrumentedTest.theChoiceIsStillThereWhenTheAppIsStartedAgain`, passing in the matrix |
| Lint: 0 errors, 149 warnings at the completion pass, re-measured 0 errors / 147 warnings the same evening; three of them in files this stage touched | `lintDebug`, counted as `severity="Warning"` in `app/build/reports/lint-results-debug.xml` | `AppBundleLocaleChanges` — both locales ship inside the APK, there is no Play language split to download; two `SetTextI18n` in `ArchiveAdapter` are the runtime-join rows above. The two-warning drop between the two counts is not attributed: no file the warnings sit in was edited in between, so both numbers are kept with the run they came from | Not suppressed, not new-except-as-noted | `app/build/reports/lint-results-debug.xml` |
| A press refused while the app's main thread answered a round trip in 38 ms and 81 ms — the oversleep story does not cover that, and the row was labelled `PRESS-REFUSED` anyway | Re-running `TabPressIdentityInstrumentedTest` with the reading extended to carry what it was throwing away | **The reader, not the navigation.** The same run reported `click moved=false, tightTapTried=true moved=false` and, one step later, `destination navigation_tasks->navigation_profile`: the app had moved. `NavIdentity.readings()` answers with an **empty list** when its own `runOnMainSync` read fails, so eight seconds of settle polling is indistinguishable from an app that would not move; and `firstOrNull { lifecycle == RESUMED }` can take the *outgoing* activity of a `recreate()`, whose controller really does still hold the old destination. `TabTap` now carries whether the input system accepted the injected gesture (`injected=`), whether the window held input focus before and after, and polls-run against polls-unread, and `verdict()` names the failure that is actually present: `INPUT-NOT-DELIVERED`, `NO-FOCUS`, `UNREAD`, `MOVED-UNOBSERVED`, with `PRESS-REFUSED` reserved for injected + focused + readable + unchanged. `live()` takes the resumed activity that owns a bar. No assertion was relaxed and `SETTLE_MILLIS` is still 8 000 | ИСПРАВЛЕНО (harness; no production change is justified by the readings yet) | `build/instrumented-probe/com.taskplanner.ui.TabPressIdentityInstrumentedTest.211303.log` = `OK (1 test)` with six presses all `click moved=true … windowFocus true->true … settle 1 polls 0 unread`, zero `Overslept` lines, zero `Tooltip` windows, zero tight taps; the 38 ms/81 ms refusals are in `build/instrumented-full/ProfileNavigationRestorationTest.log` and the `…192157.logcat.txt` capture beside them |
| Fifteen instrumented classes in one matrix run ended `shortMsg=Process crashed.` while `uptime` read `load average: 2.44` — a machine the gate had just called healthy | Reading the system log beside the crash instead of the load figure | **The app could not complete startup, and load average was the wrong proxy for that.** Each crash had `ANR in com.taskplanner / Reason: Process … failed to complete startup` next to it, with `Start proc` answered **18 s** after the start request, ART verifying `TaskPlannerApplication.onCreate()` at 253 bytecodes/s and `Verification of … getExecutor() took 321.478ms (0.00 bytecodes/s)`, and the guest at `69% kernel + 21% softirq`. `am start -W` on the same bytes returned `Status: timeout` after 30–40 s while the window finally took focus at t+40 s — longer than the framework's process-start window. `run-instrumented-classes.sh` now asks that question directly before the first class and refuses with exit 8 rather than spending an hour producing crash rows | ИСПРАВЛЕНО (the gate); the machine's ceiling is recorded, not argued away | `build/matrix-run-168.log` (the run itself), `build/instrumented-probe/com.taskplanner.ui.profile.ProfileNoUserInstrumentedTest.184205.logcat.txt` (the ANR block, `Load: 0.0 / 0.0 / 0.0` and the per-process CPU table), `build/startup-probe.log` |
| The same external-directory guard that had two bugs already had a third, and it printed "agrees" about a comparison it never made | Reading what the guard printed against what the device answers | `stat -c %U` gives a *label* (`u0_a322`); the guard matched it against `dumpsys package`'s `userId=` token, which this API-35 image does not print at all. The read came back empty, `[ -n "$APP_UID" ]` failed, and the else-branch printed "agrees with the app's uid ?" — so an unread and a match looked identical in the log, and the EACCES guard still had never protected a run. It now compares the two numeric uids it can actually read (`stat -c %u` against `pm list packages -U`) and says "no comparison was made" when either side is missing; the runner's header, which had printed `app_uid=?` on every run, reads the same way | ИСПРАВЛЕНО | `scripts/prep-emulator-for-matrix.sh`, and the guard's own line after the fix: `external files dir: uid 10322 is the app's own` |
| A locale test compared the app's arithmetic with the app's own language and called that the device | Making the oracle prove its own immunity, then watching it fail | `CalendarLocaleTest.systemDefaultLeavesTheDeviceAnswering` asks whether the week opens where the *device* opens it. Both sides of that comparison read `instrumentation.context.resources.configuration`, on the reasoning that the test package holds no app locale of its own — but the instrumentation context lives in the target's process, and a per-app locale is applied to that process. The matrix read `expected:<MONDAY> but was:<SUNDAY>`: SUNDAY was the app correctly following an `en-US` device, MONDAY was the oracle still carrying the Russian a previous class had chosen, and run alone the method passed because both halves were wrong together. Pointing the oracle at the framework's stored locale (`system_locales`) exposed it; the first attempt at the fix, `Resources.getSystem().configuration`, then failed the new assertion that the oracle must not move when the app changes language — which is why that assertion is in the test now instead of in a comment. Oracle and settling condition read one helper over a source the app cannot move. **No production change was justified**: with the fixed oracle the app answers the device's own day after handing its choice back | ИСПРАВЛЕНО (the test's oracle) | `CalendarLocaleTest` `OK (1 test)` alone and `OK (3 tests)` for the class on the device, then 25/25 classes in `build/matrix-run-168c.log`; the `expected:<SUNDAY> but was:<MONDAY>` of the first attempt is quoted in the helper's KDoc |
| One locale assertion failed once and has not reproduced | Three runs after it, in the order that produced it and alone | `while the app holds Russian its calendar opens where Russian opens it` failed in one whole-class run at line 163 — the opposite direction from the oracle defect above, and `applyLocale`'s wait on the app locale and the process default did not cover it. The solo run, the class run before it and the full matrix after it all passed. Filed open rather than smoothed: if it returns, the next thing to read is whether `AppLocale.current()` can be asked between the delegate's state changing and the process default following it | **ОТКРЫТО (не воспроизведено)** | the failing instrument output, and `OK (3 tests)` / `PASS=25` in `build/matrix-run-168c.log` |
| `adb reboot` wedged the AVD worse than the state it was meant to clear | The emulator console beside `adb devices` | For 45+ minutes after a reboot the guest ran with `sys.boot_completed` unset, `pidof system_server` empty and `pm` answering `Can't find service: package`, while the console said `virtual device is running` and qemu burned ~2.3 cores. The recovery that worked was a graceful console shutdown and a relaunch with the **original** command line (`adb emu kill`, then `emulator -avd taskplanner_avd -port 5584 -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -no-snapshot`): `sys.boot_completed=1` after **90 s**, and the app's cold start went from `Status: timeout` to `Displayed com.taskplanner/.ui.MainActivity: +5s117ms`. The AVD was not wiped and no data was cleared | ЗАПИСАНО (as the operating procedure for this device) | `build/emulator-launch.log`; the 5 s 117 ms line in `…TabPressIdentityInstrumentedTest.211303.logcat.txt` at 18:13:17 device time |


No test was `@Ignore`d, no lint check disabled, no assertion weakened to reach green. Two review passes that
were dispatched as independent subagents ended without a report (one on a credit limit, one on a server
error), so the silent-failure pass and the documentation-accuracy pass were performed in this session by
reading the complete files; the findings they produced — the `GONE`-container guard, the missed instrumented
classes, the application-context strings, the mis-pointed `AppLocaleTest` reference in a new KDoc — are rows
in the table above. That is weaker than an independent reviewer and is recorded as such rather than
described as eight independent passes.
