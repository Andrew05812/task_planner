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
`LocaleStateSourceTest`, `EnglishLiteralAssertionTest`, `HardcodedFailureTextTest`.

**Device.** `LanguageSwitchInstrumentedTest` (the row, the apply, the recreate, the force-stop, the hand-back),
`CalendarLocaleTest` (first day of the week, in the app's language), `CsvLocaleTest` (delimiter and figures in
a real export), `Stage14LanguageFramesInstrumentedTest` (the frame matrix).

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

- **Twenty-nine failure sentences are built in code, not in resources.** `HardcodedFailureTextTest` counts
  them and fails if the number grows. They are the `viewModelScope` error strings that need a `Context` they
  do not have, and moving them is a refactor of the ViewModels rather than a translation. The ceiling is a
  ratchet, not a to-do list that has been forgotten.
- **English literals in `app/src/test`.** `EnglishLiteralAssertionTest` gates `androidTest`, where a literal
  is a test that quietly stops testing anything the moment the app learns a second language. In JVM tests a
  literal is often the fixture's own data, and separating the two needs a rule per case rather than a gate.
- **The Profile tab stays selected while the app shows Settings.** Settings is reached *from* the account
  screen and is not a bottom-bar destination of its own, so the bar holds `navigation_profile` after a
  recreate, and a tap on the tab the bar already holds is a re-selection that navigates nowhere. This
  predates Stage 14 and Stage 14 did not fix it. `theDeadTabIsDeadWithoutAnyLanguageChange` records the
  current behaviour in English with no locale touched, and says in its own KDoc that it must be rewritten
  the day the navigation is fixed.
- **A notification channel's name is set once by the platform.** `ReminderNotifier` now re-announces the
  name and description on every start, so a language change reaches a channel that already exists; the
  reader's own importance setting is preserved. What cannot be done from a notification channel is a
  re-translation of a notification that is already on the shade.

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
| Plan-time field hint «Время окончан…» is ellipsized in Russian at 393dp/1.0 and at 320dp/1.3, while English "End time" fits both | The frame matrix, against the English frame at the same settings | Reproduced on device in both languages; cause measured (an empty field draws its hint at full text size inside a half-width column) | **ОТКРЫТО** — needs a wording or layout decision | `s14-ru-393-light-10/07-task-detail.png` vs `s14-en-393-light-10/07-task-detail.png` |
| «СОХРАНИТЬ» wraps mid-word («СОХРАН / ИТЬ») in the editor footer at 320dp/1.3; English «SAVE» does not | The frame matrix | Reproduced; the layout's own comment predicted this trigger | **ОТКРЫТО** — the fix changes approved English baselines | `s14-ru-320-light-13/07-task-detail.png` vs `s14-en-320-light-13/07-task-detail.png` |
| The calendar month heading is truncated at 320dp/1.3 — «Октябрь …» | The frame matrix, then English at the same settings to find out whose defect it is | «October 2…» truncates identically: it is a width-and-font defect, not a localization one | **ОТКРЫТО, не Stage 14** | `s14-en-320-light-13/02-calendar.png`, `s14-ru-320-light-13/02-calendar.png` |
| Tapping the Profile tab while the bar already holds it navigates nowhere, so Settings cannot be left through the bar | `LanguageSwitchInstrumentedTest` | Control test reaches the state in English with no locale touched — it predates Stage 14 | **ОТКРЫТО** (recorded, worked around) | `theDeadTabIsDeadWithoutAnyLanguageChange` |
| 29 failure sentences are built in code, not resources | `HardcodedFailureTextTest` | Counted, capped, and refused to grow | **ОТКРЫТО** by decision | `HardcodedFailureTextTest` (CEILING = 29) |
| `app/src/test` still compares some rendered text to English literals | `EnglishLiteralAssertionTest` | Gated for `androidTest`, where a literal silently stops testing; JVM fixtures need a per-case rule | **ОТКРЫТО** (documented in the class) | `EnglishLiteralAssertionTest` KDoc |
| Five reader-facing formats still read `Locale.getDefault()` **at call time**, which the companion-object sweep could not see — a calendar month label, a dashboard percent and number, an insight's weekday and its clock span | Auditing the sweep's own claim by grepping the module afterwards | All five moved to `LocalisedDates.locale()`; a second rule now fails the build if any production file outside `AppLocale.kt` reads the process default at all | ИСПРАВЛЕНО (JVM; API 35 cannot distinguish the two) | `LocaleStateSourceTest.onlyTheAccessorReadsTheProcessDefault` |
| Three instrumented classes failed with "the tab did not draw its screen" only when run after the Stage 14 frame walk | First read as a restored-task state, and `-e clearPackageData true` was added on that theory | **The theory was wrong, and the control disproves it:** with the flag in place the same three classes still fail, and `SeededProfileCleanupTest` — green twice earlier the same day, and a class that presses no tabs at all — now fails identically. The runner change is kept because it is what Gradle does and the raw invocation was not doing it, but it is not the cause and is not claimed as a fix | Runner corrected; the three classes **OPEN**, blocked by the device, not by the code | `build/instrumented-full/*.log`, `scripts/run-instrumented-classes.sh` |
| The emulator is unhealthy, and it is what is stopping the device gate | Three separate readings, each before believing a failure | `logcat -b crash` shows `DeadSystemException: The system died` across six processes and a Bluetooth stack crash-looping every two minutes (`stack_manager.cc:57 StartUp: Can't start stack`) with `com.taskplanner` absent from it; `ActivityManager: Killing com.android.phone: bg anr`; and `dumpsys window` reporting `mCurrentFocus=Window{… Application Not Responding: com.android.systemui}` while `ps` shows the app's own process alive. Two reboots (7 minutes to `sys.boot_completed=1`) and disabling the unused Bluetooth stack did not clear it; host free RAM is ~3.2 GB | Environment, not a defect. **The instrumented matrix has therefore not been re-run against the final build**, and this document does not claim it was | `adb logcat -b crash`, `adb shell dumpsys window`, `build/instrumented-full/SeededProfileCleanupTest.log` |
| Three frame runs failed and stayed failed after a reboot: two `shortMsg=Process crashed`, one `RootViewWithoutFocusException` | Reading the device's own crash buffer before believing any of it | `logcat -b crash` shows a crash loop in `com.google.android.bluetooth` (`system/gd/stack_manager.cc:57 StartUp: Can't start stack`, `hci_layer.cc:478 on_hardware_error 0x42`) restarting every ~2 minutes; `com.taskplanner` appears nowhere in it. The app was never the failing process. Repair: reboot (4½ min to `sys.boot_completed=1`), then disable the unused stack (`pm disable-user com.google.android.bluetooth`), which stopped the loop | Environment, not a defect | `build/stage14-matrix/*.log`, `adb logcat -b crash` |
| …and the harness let that be reported as a test failure | The same reading | `waitForWindowFocus()` now runs before the first press: a window that draws without taking focus passes `findViewById` and then dies ten seconds later inside an unrelated tab press, blamed on the app. When focus does not arrive the run stops with `ENVIRONMENT, not a screen defect:` in the message, so a machine problem and a Russian string can never again be read as the same event | ИСПРАВЛЕНО (the reporting, not the emulator) | `Stage14LanguageFramesInstrumentedTest.waitForWindowFocus` |
| Lint: 0 errors, 149 warnings; three of them in files this stage touched | `lintDebug` | `AppBundleLocaleChanges` — both locales ship inside the APK, there is no Play language split to download; two `SetTextI18n` in `ArchiveAdapter` are the runtime-join rows above | Not suppressed, not new-except-as-noted | `app/build/reports/lint-results-debug.xml` |

No test was `@Ignore`d, no lint check disabled, no assertion weakened to reach green. Two review passes that
were dispatched as independent subagents ended without a report (one on a credit limit, one on a server
error), so the silent-failure pass and the documentation-accuracy pass were performed in this session by
reading the complete files; the findings they produced — the `GONE`-container guard, the missed instrumented
classes, the application-context strings, the mis-pointed `AppLocaleTest` reference in a new KDoc — are rows
in the table above. That is weaker than an independent reviewer and is recorded as such rather than
described as eight independent passes.
