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
- **The device half of the proof is not complete, and says so in two places.** The instrumented matrix was run
  four times against the same APK bytes and left 7 / 2 / 7 classes without a clean verdict, then a fifth run on a
  quiet machine re-certified one class; 23 classes are still owed a healthy-device pass on these bytes. And 28 of
  the 42 frames — every Russian configuration and both at 320dp — are from the build *before* the editor layout
  changed, because each capture run that chooses Russian died to the system's own ANR kill. What does stand for
  the Russian side is the instrumented measurement of the drawn widths, not a photograph. Both are recorded as
  `BLOCKED — emulator/system instability` in the roadmap's §14.14 rather than presented as done.

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
open.

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
| The tab was reported as "Profile not restored after recreation" | `ProfileNavigationRestorationTest`, written before touching anything | **The framing was wrong and the fix would have aimed at nothing.** The destination the brief asked to preserve was never lost: all five tabs come back as themselves — destination, bar selection and one attached fragment — after a bare `recreate()`, green on the device in the fifth run. Through a *language* change the same holds for every leg the walk reached (Tasks and Calendar in both directions, Categories in Russian); the walk's Categories/en-US leg dies on a lost press before Statistics and Profile get their turn, so those two are untested in that direction on this build | ИСПРАВЛЕНО (the reported defect; `recreate()` leg proven on device, language leg partial — see the open press row) | `ProfileNavigationRestorationTest` — 2 tests, green against the unfixed build; `build/instrumented-probe/com.taskplanner.ui.ProfileNavigationRestorationTest.014532.log` (`everyTabIsStillItselfAfterTheActivityIsRebuilt` passes in the same process that fails the other) |
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
| A tab press that does nothing, on a window that is focused and whose activity has settled | Deterministic: the same failure twice in a row from a clean install at load 6, and a third time after the settle wait was added, each with the state and `focused=true` in the message — and then a **fourth and fifth time on a quiet machine** (load 1.77, `MemAvailable` 1.17 GB, no crash line in either capture and no `ANR in com.taskplanner` — the second file does hold `ANR in com.android.chrome`, `No response to onStopJob`, killed at `adj 905`; APK sha read back from `/data/app`): once inside the whole class and once with that method run alone, both dying at the same Categories leg with the same sentence, from inside `go()` | **ОТКРЫТО, and no longer an environment story.** `theTabThatOwnsTheScreenCarriesTheReaderBackToIt` presses Profile from Tasks as its first step and the app stays on Tasks with the bar still on Tasks — no PerformException, so the tap was *performed*, and it had no effect; the restoration walk presses Categories from Calendar and gets the same. Two candidate explanations survive, neither yet separated: a leaked second bar from a window the previous recreation left in the hierarchy, which Espresso can match first and tap for nothing; or the re-selection listener installed by this pass being reached when the bar's internal selection already equals the pressed item, which pops to a destination that is not on the stack and so does nothing. The new reading favours neither but sharpens the first: the drawn window's own bar reports the *old* destination while the previous iteration's assertions, through the same reader, reported the new one. What is *not* left standing is the earlier claim in this file that the pop-back is proven on device — the method that proves it does not reach its claim in this build, and the fix in `MainActivity` is unproven until it does | ОТКРЫТО, evidence in `build/instrumented-full/LanguageSwitchInstrumentedTest.log`, `build/instrumented-probe/com.taskplanner.ui.ProfileNavigationRestorationTest.014532.log` and `build/instrumented-probe/lang-method-only.log` |
| The one test that claimed the choice survives a process death proved something else, and depended on which class ran first | Reading its premise check fail on a clean device after the leak was fixed | The method makes its own choice now: chooses Russian through the row, closes the window, opens another, and asserts the new activity speaks Russian — then hands the language back through the row. What a second activity in one process cannot show is named in its KDoc and belongs to `Stage14LanguageFramesInstrumentedTest`, whose `am force-stop` sits between the run that chooses and the run that walks, written into the capture script rather than assumed from the matrix's order | ИСПРАВЛЕНО (the claim is now the one the test can make) | `LanguageSwitchInstrumentedTest.theChoiceIsStillThereWhenTheAppIsStartedAgain`, passing in the matrix |
| Lint: 0 errors, 149 warnings; three of them in files this stage touched | `lintDebug` | `AppBundleLocaleChanges` — both locales ship inside the APK, there is no Play language split to download; two `SetTextI18n` in `ArchiveAdapter` are the runtime-join rows above | Not suppressed, not new-except-as-noted | `app/build/reports/lint-results-debug.xml` |


No test was `@Ignore`d, no lint check disabled, no assertion weakened to reach green. Two review passes that
were dispatched as independent subagents ended without a report (one on a credit limit, one on a server
error), so the silent-failure pass and the documentation-accuracy pass were performed in this session by
reading the complete files; the findings they produced — the `GONE`-container guard, the missed instrumented
classes, the application-context strings, the mis-pointed `AppLocaleTest` reference in a new KDoc — are rows
in the table above. That is weaker than an independent reviewer and is recorded as such rather than
described as eight independent passes.
