# Product roadmap — Stages 13 to 18

**Repository:** Task Planner (`C:\my\myprogi\task-planner`, GitHub `Andrew05812/task_planner`)
**Written:** 2026-10-28, on branch `feat/global-ui-redesign`, while Stage 8a was in flight.
**Status of everything in this file: not implemented.** Each stage below is a plan, not a report. Nothing
here has been built, tested or shipped, and no sentence in this document should be read as "the app can
do this". The UI redesign (Stages 0–12) is the only work on this branch, and its own state is recorded in
`docs/superpowers/plans/2026-10-23-global-ui-redesign-plan.md`.

This file exists because the redesign plan ended at Stage 12 and six product features were being carried as
conversation memory. Anything that lives only in a chat log gets invented later as already built. Writing
them as numbered, mandatory, sequenced stages — and wiring them into the plan — is what keeps the next
session from guessing.

## How to read this file

Every stage has the same eleven sections, in the same order: user problem, target UX, what already exists,
modules to touch, data changes, risks, dependencies, implementation phases, acceptance criteria, tests,
documentation. The facts under "what already exists" are read out of the code at the time of writing, with
`file:line` references, so a later reader can check them rather than trust them. Where the honest answer is
"this does not exist", that is written as the answer.

Three rules apply to every stage and are not negotiable, because each of them was learned from a defect on
this branch:

1. **Tests first, and the test must be seen failing.** A test written after the code proves the code does
   something, not that it does what was asked.
2. **Nothing is called done on assertion.** Each acceptance criterion names the command, the device or the
   file that answers it, and the stage's report quotes the real output.
3. **A user's data is never the cost of a feature.** Every stage that writes, moves, merges or deletes rows
   names the failure mode that would destroy data and the test that proves it cannot happen. Two incidents
   are already on this repo's record: `UserDao.insertUser` and `CategoryDao.insertCategory` were both
   downgraded from `INSERT OR REPLACE` to `ABORT` because a conflict deleted a row and cascaded
   (`UserDao.kt:27-43`, `CategoryDao.kt:46-56`), and `BackupRepository.kt:353-364` records an import that
   overwrote somebody else's task on a `(series_uuid, occurrence_at)` collision.

## The state the roadmap starts from

| fact | value | where |
|---|---|---|
| Room schema | version 16 | `data/local/AppDatabase.kt:44` |
| next migration number | 16 → 17 | `data/local/AppMigrations.kt` |
| min / target / compile SDK | 24 / 34 / 34 | `app/build.gradle.kts:12,23,24` |
| UI stack | XML + ViewBinding + Fragments + Material 1.11; no Compose anywhere | `app/build.gradle.kts`, `res/layout/` (58 layouts) |
| strings | 846 in `values/strings.xml`, 20 in `values/calendar.xml`, 0 hardcoded in layouts or Kotlin | `res/values/strings.xml`, verified 2026-10-28 |
| locales shipped | English only; `values-ru` does not exist | `res/` directory listing |
| cloud configuration | absent by default: `firebase.projectId`, `firebase.appId`, `firebase.apiKey`, `google.webClientId` have no entry in `gradle.properties`, so all four `BuildConfig` fields are the literal `NOT_CONFIGURED` | `app/build.gradle.kts:34-41`, `gradle.properties` |
| Firebase files in the repo | none — no `google-services.json`, no `com.google.gms.google-services` plugin; Firebase is built at runtime from `FirebaseOptions` | `data/auth/CloudConfig.kt:26-36` |
| device used for proof | `taskplanner_avd` / `emulator-5584` only | `docs/superpowers/plans/…-plan.md` §2 |

---

# Stage 13 — Google Sign-In that a user can actually reach

## 13.1 User problem

The app can already sign in to Google. `AuthRepository` is 356 lines of working code: Credential Manager
asks for a Google ID token (`AuthRepository.kt:85-107`), Firebase exchanges it (`:108-110`), the local
profile is linked to the account (`linkProfile`, `:156`), and everything the profile owns is re-tagged to
the account's row (`attachLocalDataToAccount`, `:220`). None of that runs in any build this repository
produces, because the four Gradle properties that feed it are unset, so `CloudConfig.isConfigured` is false
(`CloudConfig.kt:19-21`) and the Sign In button is hidden (`ui/settings/SettingsFragment.kt:104`).

The user's problem is therefore not "sign-in is missing" but "sign-in is unreachable, and nobody can tell
whether it works". A person reading the Settings screen sees the sentence "Cloud features are disabled until
Firebase is configured. All local functions work normally." (`values/strings.xml:215`) and has no way to know
that sync, backup to cloud, and multi-device continuity are all decided and waiting.

## 13.2 Target UX

- Settings shows one of three states, and says which: *not configured* (today's notice), *not signed in*
  (a Sign In button and what it costs), *signed in* (the account name, the sync status, a Sign Out).
- Signing in never loses a row. The existing attach engine merges the local profile into the account
  profile; the screen says what will be merged before it happens, and offers a safety copy.
- Signing out keeps local data. Today `signOut()` (`:344`) clears the credential state; what the reader
  needs to know is which profile they are left on.
- A signed-in device can switch profiles without silently losing sync: `ProfileViewModel.switchUser`
  (`ui/profile/ProfileViewModel.kt:160-168`) changes the local session and touches no Firebase state, so
  after a switch `SyncRepository.syncNow()` reports `NotLinked` (`data/sync/SyncRepository.kt:71-74`) and
  syncing stops with no message. That gap is part of this stage.

## 13.3 What already exists (read, not assumed)

| piece | state | reference |
|---|---|---|
| Credential Manager flow | implemented, unexercised | `AuthRepository.kt:81-134` |
| outcome type incl. "email belongs to another profile" | implemented | `SignInOutcome` `:38-56`, `EmailOwnedByAnotherProfile` `:53` |
| local → account data attach (tasks, categories, groups, rules, reminders) | implemented | `attachLocalDataToAccount` `:220`, `attachTasks` `:319`, `attachCategories` `:280`, `CategoryMerge` `:275` |
| placeholder email for accounts with no public address | implemented | `"google-$uid@firebase.local"` `:166` |
| Firestore document store, push/pull, watermarks | implemented | `data/sync/FirestoreSyncStore.kt:26-112`, `SyncRepository.kt:69-99` |
| periodic + one-shot WorkManager jobs | implemented, never enqueued without configuration | `SyncScheduler.kt:24,43` |
| UI wiring | implemented, hidden by the gate | `SettingsFragment.kt:64-67`, `SettingsViewModel.kt:107-134` |
| a real Firebase project | **does not exist** | no `google-services.json`, no properties |
| tests that exercise a signed-in session | **do not exist** — `SyncRepositoryTest.kt:63` asserts `assertFalse(CloudConfig.isConfigured)` | |

## 13.4 Modules to touch

`data/auth/AuthRepository.kt`, `data/auth/CloudConfig.kt`, `data/sync/*`, `ui/settings/*`,
`data/repository/SessionRepository.kt`, `ui/profile/ProfileViewModel.kt`, `app/build.gradle.kts`
(documented properties only, never values), `docs/FIREBASE_SETUP.md`, `docs/BACKUP_AND_SYNC.md`,
`README.md`.

## 13.5 Data changes

No schema migration is required for sign-in itself: `users.firebase_uid` already exists
(`data/model/User.kt:34-36`) and `UserDao.getUserByFirebaseUid` already reads it (`UserDao.kt:18-19`).
Two things do need care:

- `attachLocalDataToAccount` rewrites `user_id` across `tasks`, `categories` and `reminders`, which have no
  foreign key to `users` (`SeededProfile.kt:19-25` documents that list). A partial attach leaves rows owned
  by a profile nobody is signed into. It must run in one transaction with a safety copy taken first.
- Firestore's namespace is the Firebase uid (`FirestoreSyncStore.kt:30`), so a uid change is a different
  document tree. Nothing may infer "same email, same tree".

## 13.6 Risks

1. **Data loss on merge.** Two profiles with the same email are refused today (`ProfileUpsert.AddressTaken`,
   `:207`); the risk is the opposite case — a merge that half-completes. Test: an attach interrupted at each
   step leaves either all rows moved or none.
2. **Secrets in the repository.** The token, the project id and the API key live in `local.properties` or
   `-P` flags (`docs/FIREBASE_SETUP.md:38-47`). Nothing in this stage may put them in a committed file, in a
   test resource, or in a screenshot.
3. **A signed-in state that cannot be reproduced in CI.** GitHub Actions has no Firebase project, so any test
   requiring a real account must be `@Ignore`d with a reason or run only on the device. An `@Ignore` is not
   allowed to become the whole proof.
4. **`SyncWorker` treats "no cloud" and "not linked" as success** (`SyncWorker.kt:36-39`), and the periodic
   job is enqueued with `KEEP` (`SyncScheduler.kt:37`): a device that configures Firebase after the first
   enqueue keeps the old job. The stage must decide whether that is correct and say so.

## 13.7 Dependencies

None in code — this stage is the one the others wait for. Stage 14 (localization) must translate the
strings this stage adds; Stages 15–18 add fields that `FirestoreSyncStore` and `SyncAppliers` must carry, so
every later stage re-opens this one's document schema.

## 13.8 Implementation phases

1. **Configuration without secrets.** A documented, checked-in `firebase.example.properties`, a Gradle
   warning when the real one is absent, and a Settings line that names the mode the build is in. No value in
   git.
2. **A fake-account harness.** An in-test `AuthRepository` seam so the sign-in state machine can be driven
   under Robolectric without a network. The production path stays the real one.
3. **Attach under transaction + safety copy**, with the interrupted-attach tests.
4. **Profile switch that knows about the account** — either re-links, or signs the cloud session out and
   says so.
5. **Device proof** with one real test account: sign in, see the data, sign out, see the data again.
6. **Docs**: `FIREBASE_SETUP.md` gains the "how to tell it worked" section; `README.md` stops describing
   cloud features as available.

## 13.9 Acceptance criteria

1. A build with no configuration behaves exactly as it does today, and says so — the existing assertions in
   `Stage8aScreensInstrumentedTest` (`signInButton` is `GONE`, the notice names Firebase, sync is disabled)
   stay green.
2. A build with configuration shows Sign In, and pressing it on a device reaches the account chooser.
3. After sign-in, the count of tasks, categories, groups, rules and reminders the reader had before is
   identical, and the export contains the same rows.
4. An attach that is interrupted at every one of its five steps leaves the database either fully attached or
   fully untouched, checked by row counts and by `PRAGMA foreign_key_check`.
5. Switching profile while signed in either re-links or states that cloud sync stopped, and no path leaves
   `NotLinked` silent.
6. No committed file contains a project id, an API key, an OAuth client id or a token. Verified by a scan of
   the diff, quoted in the report.

## 13.10 Tests

`AuthRepositoryTest` (Robolectric, seam-driven): each `SignInOutcome` branch; `AddressTaken` does not write;
placeholder-email synthesis. `AccountAttachTest` (instrumented, real Room): attach all five tables, attach
twice, interrupt at each step. `SettingsScreenInstrumentedTest` extension: the three states render the three
sentences. `SyncStatusTest`: `NotLinked` after a switch is visible, not silent.

## 13.11 Documentation

`docs/FIREBASE_SETUP.md` (configuration, verification, what is *not* enabled), `docs/BACKUP_AND_SYNC.md`
(what sync carries, what it never carries), `README.md` (feature list corrected to match reality), and this
file's Stage 13 section updated with the measured result.

## 13.12 Definition of done

A named person signed in on a named device, saw their own data, signed out, saw it again, and every claim
above is backed by a command output in the stage report. Until then this stage is described as unreachable,
which is what is true today.

## 13.13 State after the Stage 13 work of 2026-10-29

**Status: `BLOCKED — Firebase project / OAuth configuration required` for the credential itself; the local
half is DONE and measured.**

What the code now does, against what 13.3 listed as "implemented, unexercised":

| 13.3 claim | state now |
|---|---|
| the handover covers tasks, categories, groups, rules, reminders | **all sixteen tables that carry a `user_id`**; the other eleven move through `ProfileAttachDao`, inside the same transaction |
| `linkProfile` | unchanged in decision, fixed in effect: the account row is no longer inserted with `is_current = true`, so the window in which two profiles answered that question is gone |
| `signOut` | ends the cloud session and **leaves the reader on their profile**; it no longer clears `is_current` and drops the repair anchor, which used to make every row unreachable after a sign-out |
| Settings shows one of three states | four named states (`AccountUiState`), and on a build with no project the sign-in row is **visible and disabled with the reason beside it** — Stage 8a's `GONE` assertion was rewritten to say so |
| a real Firebase project | **still does not exist.** No `google-services.json`, no `firebase.*` properties, so `CloudConfig.isConfigured` is false in every build of this repository |
| tests that exercise a signed-in session | `AccountAttachTest` (twenty, real Room on the JVM), `ProfileSafetyCopyTest` (eight), `AuthRepositoryTest` (ten, across a sign-in seam), `UserIdentityConflictTest` (ten), `CloudConfigTest` (four), `LocalDataMigrationTest` (nine), `AccountUiStateTest` (six), `SettingsLayoutTest` (six), and `SignInDataPreservationInstrumentedTest` on the device: sign-in seam, handover, restart, sign-out, second sign-in without duplicates, and the snapshot reopened by the shipping SQLite. Every instrumented class runs one at a time — see the note on the merged run below |

What remains blocked, and exactly what unblocks it: the four values in `firebase.example.properties` —
`firebase.projectId`, `firebase.appId`, `firebase.apiKey` (an Android API key) and `google.webClientId` (the
**Web** OAuth client id, which is the server client id `GetGoogleIdOption` asks for) — supplied through
`local.properties`, `-P` flags or CI secrets, plus a Firestore database and `firebase deploy --only
firestore:rules`. Until then no automated test can reach Credential Manager, the Google ID token or the
Firebase exchange, and "sign-in works" is claimed only of everything a sign-in is *for*.

**What was measured, on the tree this section describes.** JVM: 118 classes, 1576 tests, 0 failures, 0 errors,
0 skipped. Lint: 0 errors, 144 warnings. `assembleDebug` and `assembleDebugAndroidTest` both produced, installed on
`taskplanner_avd` (API 35) and sha256-matched against `/data/app` before any frame was taken. Instrumented: 18
classes, 90 tests, each class in its own Gradle command and its own instrumentation process — a merged run of the
same suite reports 35 failures that no class produces on its own, because the fixtures write global session state
and the runner does not re-seat it between classes; the merged number is therefore not the suite's result, and no
production code was changed to chase it. The one failure the isolated matrix did find (`TaskEditorFramesInstrumentedTest`)
was a test precondition the project's own `clearPackageData` setting makes unsatisfiable, fixed in the fixture.
Conservation: sixteen tables, each `1→1`, printed by the test that checks it. Screenshots: Settings in state A at
393dp light, 393dp dark and 320dp with the font at 1.3, in `docs/images/ui-redesign/after/s13-*.png`.

Two gaps this stage found and did **not** close, because they are later stages' by the roadmap's own ordering:
the eleven tables that moved into the handover are still in **neither** the backup file (format `1.1`) nor a
Firestore collection, so a signed-in device does not carry a reader's measured work, goals, habits, insights or
achievements to another device; and `SyncWorker` still treats "no cloud" and "not linked" as a finished job.


---

# Stage 14 — Russian: a language toggle, and the whole app behind it

## 14.1 User problem

The app is written for a Russian reader in every respect except the words on the screen. Its tests seed
Russian task titles and depend on Russian alphabet order
(`androidTest/.../TasksHeaderInstrumentedTest.kt:55,178`); its CSV export picks `;` "so the file a Russian
user double-clicks opens as columns" (`data/report/export/CsvReport.kt:96-97`); its PDF carries Cyrillic
glyphs (`data/report/export/ReportGlyphs.kt`); its manifest already sets `supportsRtl="true"`
(`AndroidManifest.xml:26`). The interface itself is English, and there is no way to change it.

## 14.2 Target UX

- Settings carries a Language row: System default / English / Русский. Choosing it re-applies immediately,
  without a reinstall, and survives a process death.
- Every visible string is translated, including the ones built at runtime from plurals and format strings.
- Dates, weekday names, first-day-of-week and number formats follow the chosen locale, not the device's.
- Nothing is half-translated: a missing translation is a defect the build reports, not an English island.

## 14.3 What already exists

- **No `values-ru` directory.** `res/` contains `values/` and `values-night/` only.
- **No locale API.** Zero matches for `setApplicationLocales`, `localeConfig`, `resourceQualifiers`,
  `AppCompatDelegate` or `LocalesRequest` in `app/src` and the Gradle files.
- **The string surface is clean**: 846 strings in `values/strings.xml` plus 20 in `values/calendar.xml`, and
  0 hardcoded user-visible literals in layouts or Kotlin (verified 2026-10-28 by grep over all 58 layouts and
  all of `app/src/main`). Translation is therefore a content task, not a refactor.
- **The real obstacle is plurals.** 47 `<plurals>` blocks exist and **every one of them has only `one` and
  `other`**. Russian needs `one` / `few` / `many` ("1 задача / 2-4 задачи / 5+ задач"). Several blocks were
  written invariant to dodge the problem — `history_manual_count` (`strings.xml:473-476`) has identical text
  for both quantities.
- Two format strings use a bare `%d` instead of a positional placeholder (`task_count` `:142-143`,
  `task_late_days` `:233-234`); reordering words in a translation needs positional arguments.
- Locale-sensitive behaviour already exists and will change under Russian:
  `CalendarMath.firstDayOfWeek()` reads `WeekFields.of(Locale.getDefault())` (`data/calendar/CalendarMath.kt:47`),
  and `CsvReport.defaultDelimiter()` (`:96-97`) branches on the locale's decimal separator.
- Persistence is locale-safe: enum conversion uses `Locale.ROOT` (`data/local/Converters.kt:95,128,142`).
- One test asserts a rendered English word and will break the moment `version_format` is translated:
  `Stage8aScreensInstrumentedTest` compares the version line to `"Version"`. Stage 8a replaces that with the
  installed version *number* for exactly this reason; the same rule applies to the rest.

## 14.4 Modules to touch

`res/values-ru/` (new), `res/values/strings.xml` and `res/values/calendar.xml` (plurals and positional
arguments), `AndroidManifest.xml` (`android:localeConfig`), `res/xml/locales_config.xml` (new),
`ui/settings/*` (the Language row), `app/build.gradle.kts` (lint's MissingTranslation as an error),
`scripts/` (a string-completeness check CI can run), all 58 layouts (only through resources).

## 14.5 Data changes

None. No migration, no new column. The chosen language is a preference, and the app's per-app locale is held
by the framework's backport on API < 33.

## 14.6 Risks

1. **A first-day-of-week change that silently moves the calendar.** Monday vs Sunday changes week buckets in
   the dashboard, heatmap and reports. The change must be asserted, not assumed.
2. **Plurals that read wrong for 0, 11–14, and 1.5.** `few`/`many` rules are the whole point of the Russian
   cardinal system; getting them wrong is worse than English.
3. **Width.** Russian strings for the same meaning are often longer; the 320dp budgets Stage 4 measured for
   the task card, and Stage 7's five nav labels, must be re-measured in Russian or they will ellipsize.
4. **Sorting.** `ORDER BY name ASC` on categories (`CategoryDao.kt:15`) is SQLite binary collation, not
   Russian collation; a Russian reader will see "Я" before "а". Deciding this is part of the stage.
5. **Tests that compare rendered text to literals.** Any surviving one becomes a false failure. The rule for
   this stage: a test compares to `getString(...)` or to a number, never to a word.

## 14.7 Dependencies

After Stage 13 in ordering, but not in code: Stage 13 adds strings (account states) that must be translated,
and Stage 14 must not be blocked by an unreachable cloud feature. New strings added by Stages 15–18 must be
added to both `values/` and `values-ru/` in the same commit, which is what the MissingTranslation gate
enforces from here on.

## 14.8 Implementation phases

1. **Make the count visible.** A script that reports strings, plurals, quantity branches and untranslated
   entries; run it, record the baseline in the report.
2. **Fix the plural skeleton**: add `few`/`many` to all 47 blocks, convert bare `%d` to positional, and let
   lint's `MissingQuantity` be an error.
3. **Translate** in batches by screen, each batch reviewed by a native reader, each with the screen
   photographed.
4. **The toggle**: `localeConfig`, the Settings row, the immediate re-apply, the persistence check.
5. **Locale behaviour**: first-day-of-week, CSV delimiter, number and date formats — each asserted on the
   device in both languages.
6. **Width re-audit** at 320dp and font scale 1.3 for the screens the redesign budgeted.

## 14.9 Acceptance criteria

1. `values-ru` contains every entry `values` contains; the completeness script prints zero missing and CI
   fails on a new one.
2. Every `<plurals>` block has `one`, `few`, `many`; `MissingQuantity` is not suppressed.
3. Changing the language changes the visible UI without a reinstall, on a device, photographed before and
   after.
4. The choice survives process death and app update.
5. No test compares a rendered string to an English literal — verified by grep over `app/src/test` and
   `app/src/androidTest` for `withText("…")` and `assertEquals("…", textOf(…))` patterns.
6. The Tasks card, the nav labels and the Settings screen are photographed at 320dp in Russian; nothing is
   truncated that is not truncated in English.
7. The week starts Monday in Russian and the dashboard's period boundaries are asserted for both locales.

## 14.10 Tests

`PluralsContractTest` (JVM): every plural name has the branches Russian needs; no bare `%d`.
`StringCompletenessTest` (JVM): the two files have the same key set. `LanguageSwitchInstrumentedTest`:
choose Russian, restart, assert the rendered title is the Russian resource and the locale the app reports is
`ru`. `CalendarLocaleTest`: first day of week and week buckets in both locales.
`TaskCardWidthTest` extended to the Russian strings at 320dp.

## 14.11 Documentation

`README.md` gains the supported languages; `docs/REPORT_EXPORT.md` records the CSV delimiter rule in both
locales; a new `docs/LOCALIZATION.md` states how to add a string correctly (both files, positional
arguments, no literal in code) and how the completeness gate works.

## 14.12 Definition of done

A Russian reader can use every screen, including the ones built at runtime from plurals, and the app's own
tests pass in both languages without a single assertion about an English word.

## 14.13 State after the Stage 14 work of 2026-10-30

**Status: DONE, with three named openings.** A reader chooses a language in Settings, the whole app speaks it,
the arithmetic follows it, and the choice survives everything a reader does to an app except uninstalling it.

Against what 14.9 asked for:

| 14.9 criterion | state now |
|---|---|
| 1 — `values-ru` holds every key `values` holds, CI fails on a new one | **met.** 897 English strings against 892 Russian, the five absent ones marked `translatable="false"`; 48 plurals each side; `check-localization.py` runs first in CI and now also refuses to pass on two empty files |
| 2 — every plural has `one`/`few`/`many`, `MissingQuantity` unsuppressed | **met.** `PluralsContractTest` walks each of the 48 through 0, 1, 2, 4, 5, 11, 14, 21, 22, 25, 101, 111 |
| 3 — the language changes the visible UI without a reinstall, photographed before and after | **met** on `taskplanner_avd` (API 35): apply, recreate, force-stop, relaunch — 42 frames in six configurations |
| 4 — the choice survives process death and app update | **met, and stronger than asked:** it survives `pm clear`, because the record is held by the framework, not the app. Only an uninstall clears it. Measured, not read from documentation |
| 5 — no test compares a rendered string to an English literal | **met for `androidTest`** and gated against new ones; **open for `app/src/test`**, where a literal is often the fixture's own data and needs a per-case rule — recorded in `EnglishLiteralAssertionTest` |
| 6 — nothing truncated in Russian that is not truncated in English | **not met.** Two Russian-only cuts survive: the plan-time field hint «Время окончан…» at 393dp/1.0 and 320dp/1.3, and «СОХРАНИТЬ» wrapping mid-word in the editor footer at 320dp/1.3. Both are in the ledger with their frames; both need a wording or layout decision that changes approved English baselines, so neither was made silently |
| 7 — the week starts Monday in Russian, dashboard boundaries asserted for both locales | **met and visible:** `s14-ru-320-light-13/02-calendar.png` opens on Пн, `s14-en-320-light-13/02-calendar.png` on Sun, same build, same width, same font |

**What was measured, on the tree this section describes.** JVM: 129 classes, 1637 tests, 0 failures, 0 skipped.
Lint: 0 errors, 149 warnings (144 at Stage 13; the three in files this stage touched are one
`AppBundleLocaleChanges` — both locales ship inside the APK, there is no Play language split to download — and
two pre-existing `SetTextI18n` joins in `ArchiveAdapter`, which belong to the runtime-string row below).
`assembleDebug` and `assembleDebugAndroidTest` both produced and both installed on `taskplanner_avd`, with the
device copy sha256-matched against `/data/app` before a frame was taken.

**Two builds, and which frames each one backs — said rather than smoothed over.** The matrix was shot twice.
Build `d774e5a2e6b8eabc…` produced `en-320-light-13`, `ru-393-light-10` and `ru-320-light-13`; the final build
`c329b3defa70bd26…`, which differs only in five `Locale.getDefault()` reads moved to `AppLocale`, produced
`en-393-light-10`, `en-393-dark-10` and `ru-393-dark-10`. On API 35 the two accessors answer the same locale —
that is the measured behaviour `CalendarLocaleTest` records — so no rendered string can differ between them;
the claim is stated as an argument, not as a measurement, because the attempt to measure it ran into the next
paragraph.

**The instrumented gate could not be completed on the final build, and the emulator is why.** All 22 classes
were run in isolation against `d774e5a2e6b8eabc…`: 19 green, three (`Stage8aScreensInstrumentedTest`,
`ReportScreenInstrumentedTest`, `TasksFabClearanceInstrumentedTest`) ending with no verdict line. After the
final build was installed, every device run failed — including `SeededProfileCleanupTest`, which had passed
twice earlier the same day and presses no tabs at all, and which is therefore the control that says the
device changed and not the code. `logcat -b crash` reports `DeadSystemException: The system died` across six
processes and a Bluetooth stack restarting every two minutes with `com.taskplanner` absent from it;
`dumpsys window` reports `mCurrentFocus=Window{… Application Not Responding: com.android.systemui}` while
`ps` shows the app alive. Two reboots and disabling the unused stack did not clear it. **No claim is made
that the instrumented suite is green on the committed build**; the frames above are the device evidence this
stage can stand behind, and re-running the 22-class matrix on a healthy emulator is the first task of the next
session.

**The stage's own gate caught the stage's own evidence.** The first two matrices were wrong in the same way —
a `ru` directory holding English screens, and a `01-tasks.png` that was a photograph of Settings — and the fix
was not a more careful operator but a shutter that refuses: every frame now checks the device's own width,
theme and font scale, the language the window is resolving, and that the view it is named after is actually
**shown**. That last clause was earned: `findViewById` answers a container the renderer left `GONE`, so the
Statistics screen was photographed mid-load as a blank panel in both languages, and the seed had to grow two
measured intervals before the frame showed a Russian figure at all.

**What Stage 14 found in the arithmetic and fixed.** Dates were the big one: five screens carried a literal
`"MMM dd, yyyy"`, which is an American word order written into every language, and the test that existed for
exactly that defect passed, because it compared French to English and never asked either what its own order
is. A Russian card read «нояб. 01, 2026» until a photograph said so. Also fixed: a `SimpleDateFormat` held in
a companion object, code-point ordering of displayed names instead of a `Collator`, a genitive month heading
(«октября 2026») on a screen with no day in it, notification channels frozen in the install-time language, and
seventeen notification strings resolved from the one context AppCompat does not wrap.

Three openings this stage leaves, each named in `docs/LOCALIZATION.md` rather than in a commit message: the
two Russian-only truncations above, twenty-nine failure sentences still built in code (a ratchet refuses a
thirtieth), and the stale-Profile-tab defect that predates the stage and has a control test recording it.

**All three were closed the next day. See 14.14.**

---

## 14.14 The completion pass of 2026-10-31

**Status: the four acceptance criteria are DONE; the instrumented matrix is `BLOCKED — emulator/system
instability`, with one real defect found underneath it that survives a healthy device.** Stage 14 shipped with
those four criteria unmet; this pass closed them, then spent longer on the proof than on the fixes, and what the
proof turned up is recorded here rather than smoothed over. CI is green on this pass's published SHAs — run
41249031777 on `9bee875`, run 41250642319 on the documentation commits after it, each one job with 16 of 16 steps
successful, dispatched by hand because a feature-branch push does not trigger the workflow (the branch's Actions
page is the live list). That green is JVM-plus-lint-plus-localization; the device verdict is the paragraph below
it, not that one.

| The finding | What it turned out to be | What closed it |
|---|---|---|
| «Время окончания» ellipsized; «СОХРАНИТЬ» wrapped mid-word | A width budget, not a language problem. The plan grid gave each field 149px for a label needing 153px — and English "Starts on" needed 147px of 121px at 320dp/1.3, so the grid was wrong for both languages and Russian only showed it. The footer could not be redistributed at all: three items claimed 527px of a 472px row once the drawn form of «СОХРАНИТЬ» (158px — all-caps and letterspaced, so not the word in the strings file) was counted | Four full-width plan rows; a two-band footer with the save-in-progress figure drawn inside the primary button's trailing corner; the category editor's pair redistributed. No word shortened, none broken, no size reduced, 48dp kept, English re-measured |
| 29 runtime sentences in code | The gate's own reason for leaving them — "a ViewModel holds no Context" — was the defect's excuse, not its limit | 24 sites now carry a `@StringRes`; 40 resources added in both languages; 4 of them already existed and nothing read them. Exception text went to the log, where machine text is read; a refusal a reader can act on became a `ReaderRefusal` with a resource |
| English literals in the tests | The instrumented set was already gated; the JVM set had never been measured. The gate's own detector reported 49, and 45 were it matching a SQLite table name, an enum constant or its own fixture | Zero-tolerance rules for `androidTest` (stem rule) and the `ui` package of `app/src/test` (whole-label rule); three sites fixed to ask their own `getString`; three exempted with the reason named; `ScreensInBothLanguagesTest` added as the positive claim |
| Profile not restored after recreation | It is restored, for the recreation the brief named. `ProfileNavigationRestorationTest`'s first method walks all five tabs through a `recreate()` and finds the destination, the bar and one attached fragment intact — green on the device in the pass's fifth run — and it was written first, green against the unfixed build, which is what stopped the pass from "fixing" the wrong thing. What was broken was the way out of a screen that has no tab of its own | A re-selected tab pops back to its destination; the test that recorded the dead tab was rewritten to the claim it promised, and run RED first. The *language-change* walk reaches six of its ten legs before the open press defect stops it, so Tasks and Calendar are proven in both directions, Categories in one, and Statistics and Profile are not claimed |
| The matrix itself, run against the result: seven classes came back without a clean verdict, and none of them because of a Russian string | (a) The pass's own new class chose Russian with no teardown and reset the language in `@Before`, before a window existed — the silent no-op `CsvLocaleTest` measures. The framework keeps the language outside the app's files, so `clearPackageData` between classes does not take it back: the next class failed on "asked for 'en', resolving 'ru'", and three classes later — from a second leaker, the language-switch class, which set its starting language but had no teardown — a date measured 136px against a budget written for "Oct 25, 2026" while the card drew «25 окт. 2026 г.» (b) Three classes failed with a press that moved neither the destination nor the bar's selection, on a device reading load average 16.83 on four cores with an empty crash buffer: Espresso waits for idle and *displayed*, and tap *arrival* is the window focus's decision. (c) Eight of the new class's ten "language changes" asked for the language the app already held, so nothing recreated and eight assertions passed about a screen that never moved | Every class that changes the language now establishes its starting language on a live window and hands it back before closing (`testing/DeviceLanguage`); every class that presses waits for focus and carries `focused=` in the message of a wait that gives up (`testing/AppWindow`, `navState`, `describe`); the language walk alternates and asserts a new activity instance per iteration; `scripts/run-one-class-with-logcat.sh` added so a future expired wait can be read rather than argued about. No timeout was lengthened, no assertion weakened, nothing `@Ignore`d |

**The matrix, four times, and one class a fifth time.** Discovery is settled: 24 `*Test.kt` files under
`app/src/androidTest`, 24 classes the runner finds, 24 processes run, 24 rows in the table — `Discovered =
Executed`, on `emulator-5584` / `taskplanner_avd` / API 35, one class per instrumentation process, each with its
own log and exit code. The verdicts are not settled, and the reason is measurable: the *same* app bytes (SHA-256
`08838047e26e0d16…`, checked against `pm path` before and after) left 7 classes without a clean verdict in the
first run, 2 in the third and 7 in the fourth — the third and fourth runs differing only in the harness fixes the
second and third produced, while the device's own load average wandered between 3.5 and 43 and its log carried
`Input dispatching timed out ([Gesture Monitor] edge-swipe (server) is not responding. Waited 12756ms for
MotionEvent"` and ANRs of processes that have nothing to do with this app. Three classes were fixed and verified
between the third and fourth runs — the language hand-back, the settled-activity wait, the zoom control's
displayability — and the fourth run still lists five failures and two classes that produced no verdict line at
all, three of which (`Stage8aScreens`, `TaskEditorRussianFit`, `TasksHeader`) were green in the third. So the
matrix is **BLOCKED — emulator/system instability**, not green, and no part of the suite is reported as passing
that did not pass in front of the person writing this. The fifth run was one class, not the twenty-four: it
re-certifies `ProfileNavigationRestorationTest` and `LanguageSwitchInstrumentedTest`'s neighbourhood only, and
the other 23 classes are still owed a healthy-device pass on these bytes.

What is *not* an environment story: one reproduction held through everything — a bottom tab pressed changes
nothing, with the window focused, the activity identity held steady, a clean install, and a load of 6 — and it
survived the machine becoming healthy. The fifth run, on a device reading load **1.77** with 1,169,944 kB
`MemAvailable`, no crash line in either logcat capture and no `ANR in com.taskplanner` in either (the second
capture does hold one foreign `ANR in com.android.chrome` — `No response to onStopJob`, killed at `adj 905`), and
the APK's SHA-256 read back from `/data/app`, produced it twice more: once inside `ProfileNavigationRestorationTest`, where
`everyTabIsStillItselfAfterTheActivityIsRebuilt` passed in the same process, and once with the failing method run
alone in its own process (`1 test, 1 failure`, 430 s, the same Categories leg, the same sentence). The stack
places both failures inside `go()` — the press, before that iteration's language change — so this is the tab that
does not arrive, not the destination that does not come back.
`theTabThatOwnsTheScreenCarriesTheReaderBackToIt` cannot reach its own first step, which withdraws the claim this
pass made an hour earlier that the re-selection pop-back is proven on device. It is in the code, it is what the
brief asked for, and it is **ОТКРЫТО** until a run says so. `docs/LOCALIZATION.md`'s ledger carries the two
candidate mechanisms, the new reading that sharpens the first of them, and the evidence for each.

**Frames: 14 of 42 re-shot from the verified build, and the Russian half is outstanding.** The capture script now
refuses to photograph anything until `sha256sum app-debug.apk` equals the SHA-256 of the file `pm path` reports
(`08838047e26e0d16c2ddd56f1e5fac9d3887c99cde480ac0307f90339e0936fc`), and it copied nothing over good frames when
a configuration failed. `s14-en-393-light-10` and `s14-en-393-dark-10` — 14 frames — are from that build and
confirm the English side of the editor after the layout change. The four remaining configurations did not
capture: every run that presses the Settings row to *choose* Russian died with `INSTRUMENTATION_RESULT:
shortMsg=Process crashed`, and the system log says what killed it — `ActivityManager: Killing
com.taskplanner/u0a322 (adj 0): bg anr` — twice, on a device whose load average was 1.5. So the Russian frames in
`docs/images/ui-redesign/after/s14/` are still 2026-10-30's, photographed **before** the plan grid and the
footer changed, and they are not evidence about the fixed layout and must not be read as such. The Russian
evidence that does stand is numerical and instrumented: `TaskEditorRussianFitInstrumentedTest` measures the drawn
form of every plan label and both footer buttons in both languages and reports the px against the box — «Время
окончания» 298px of 364px at 320dp/1.3, «СОХРАНИТЬ» 158px of 418px — and it passed 3/3 in the third matrix run.

**The gate widened with the work.** `HardcodedFailureTextTest` went from four shapes to three rules that see a
named constant (`EMAIL_TAKEN`) and an exception's text handed to a UI sink, and reads a call's arguments across
lines — the first version truncated `localized(context, R.string.x)` at the comma and reported a fix as a
defect. Its ceiling went 29 → 5, each of the five named with the reason it is a diagnostic rather than copy.

**Baseline, measured after the changes rather than carried over.** 130 JVM classes, 1640 tests, 0 failures,
0 skipped; lint 0 errors and 149 warnings, none of them in a file this pass touched; `check-localization.py`
pass at 937 English and 932 Russian strings with 0 missing, 0 placeholder mismatches and 0 invalid XML.

**Google Sign-In remains `BLOCKED — Firebase project / OAuth configuration required`**, unchanged from 14.13:
nothing in this pass made the cloud feature reachable, and the two refusals still built in code are inside it.

**Stage 15 (import from Google Keep) is next. Stage 8b is not scheduled and was not started.**

## 14.15 The device verification pass of 2026-11-01 (task #168)

**Status: the open navigation defect is closed as a harness finding; production code was not touched, because
the measurement found nothing in it to touch. The remaining opening is device proof, and it is named as such.**

The completion pass left one sentence unresolved — a bottom tab pressed that changed nothing, with two candidate
causes and no reading that could tell them apart. This pass took the reading. `testing/NavIdentity.kt` prints, per
live activity, the identity of the activity, its window, its decor view, the decor's `applicationWindowToken`, its
`BottomNavigationView`, its menu and the item view the matcher would press, beside the bar's selection, the
controller's current and previous destination and the attached fragments. Every press it was pointed at had **one**
live activity, **one** bar, and a matcher target identical to the resumed activity's own item view, with focus held
and the bar still lit on the *previous* tab — so neither candidate survived: there was no second window to tap, and
no re-selection to pop.

The cause was in the app's own log at the moment of the press: `W/MotionEvents: Overslept and turned a tap into a
long press`, with a `Window{… Tooltip}` created and destroyed around it. Espresso's `click()` injects a DOWN, pumps
the main thread for the gesture's span, and injects an UP; on a thread seconds behind — the same device spent
**30 s** to `Displayed com.taskplanner/.ui.MainActivity`, `Skipped 353 frames`, `Davey! duration=8849ms` — that span
passes `ViewConfiguration`'s long-press timeout (measured: **400 ms**), so the platform holds a long press, a
Material navigation item answers a long press with a tooltip and never performs a click, and Espresso returns the
interaction as performed. Four captures carry the line (`…TabPressIdentityInstrumentedTest.044455`, the two walk
runs of `ProfileNavigationRestorationTest` and `LanguageSwitchInstrumentedTest.053437`); the captures without it
navigated.

**What changed is the harness.** `testing/TabTap.kt` waits for the app's main thread to answer a round trip inside
100 ms, performs the ordinary click, and only if the destination did not move injects DOWN and UP as one 20 ms
sequence with `SOURCE_TOUCHSCREEN`; it reports which gesture moved the app, the timeout, and the round-trip
latency, with a verdict of `pressed normally`, `TAP-DEGRADED` or `PRESS-REFUSED`. Six classes that press the bar
now go through it — `ProfileNavigationRestorationTest`, `LanguageSwitchInstrumentedTest`,
`Stage14LanguageFramesInstrumentedTest`, `Stage8aScreensInstrumentedTest`, `TasksScreenInstrumentedTest`,
`ProfileNoUserInstrumentedTest`, `ReportScreenInstrumentedTest` — with no assertion relaxed and no tab forced.
`TabPressIdentityInstrumentedTest` is the controlled scenario the brief specifies (launch → focus → identities → a
tab that moves → Settings, which has no tab → `recreate()` → the new identities → the old references → the
matcher's target → the presses → the snapshot again), and it records a fact worth keeping: **after `recreate()`
the activity instance changes while the decor view object is reused** — so an assertion that the decor must differ
tests the framework's bookkeeping, not the app, and was replaced with a report.

**The gate itself had two bugs, found by reading what it printed.** `prep-emulator-for-matrix.sh` used `$TARGET`
without defining it (the stale-directory check compared the parent of every app's directory and always reported
agreement, so the EACCES guard had never protected a run) and assigned to `UID`, which bash keeps readonly — with
`TARGET` defined, the fallback became the *host's* uid and the guard deleted a directory that did belong to the
app. `run-instrumented-classes.sh` now prints `sys.boot_completed`, the System UI pid, the app's uid and process,
the crash-buffer count, host free memory and the installed APK's SHA-256 against the built one (refusing with exit
7 on a mismatch), and it labels every row `PASS`, `FAIL`, `ENVIRONMENT` or `NOT RUN` — because four matrices had
been read as almost-green while their logs said `Process crashed`.

**What the device gave, and what it did not.** Discovery is 25 = 25: every `*Test.kt` class found, run, and rowed,
one class per instrumentation process, on `taskplanner_avd` / `emulator-5584` / API 35, against the unchanged
production bytes `08838047e26e0d16c2ddd56f1e5fac9d3887c99cde480ac0307f90339e0936fc` (read back from `/data/app`
before and after). The uncontaminated run of the whole matrix, 05:49–07:03, gave **16 PASS / 6 FAIL / 3
ENVIRONMENT**; the two classes that had carried the open defect were green on a quiet device minutes earlier —
`ProfileNavigationRestorationTest` `OK (2 tests)`, all five tabs through a `recreate()` and all ten legs of the
alternating ru/en-US walk, and `LanguageSwitchInstrumentedTest` `OK (3 tests)`, whose reading
`destination navigation_settings->navigation_profile … selection navigation_profile->navigation_profile` is the
re-selection pop-back reached on the device, which the completion pass had had to withdraw. Of the six failures
that run reported, the press-shaped ones were then re-run individually after the harness change, and
`ProfileNoUserInstrumentedTest` came back `OK (2 tests)` and `ReportScreenInstrumentedTest` `2 of 3` (the
remaining one is its save-to-disk path, not a navigation claim).

**Part of this pass' own re-run was worthless, and the reason is the operator, not the app.** A swipe was sent at
the emulator's floating keyboard (`dumpsys input_method` → `keyboardViewType: WIDGET`) to try to dock it, and the
gesture left the system holding `mCurrentFocus=Window{… NotificationShade}`. Every press in the batch that
followed was aimed at a window the system was not sending input to; the rejections say so in their own readings
(`click moved=false, tightTapTried=true moved=false … mainThreadRoundTrip=11ms`), which is exactly the evidence
`TabTap` exists to produce. Focus was restored with `CLOSE_SYSTEM_DIALOGS` and HOME, and the eight classes were run
again: `TaskEditorInstrumentedTest` came back `OK (7 tests)` — including the keyboard test that had failed in the
contaminated batch, which says the earlier failure was the device's state and not the editor's — and
`ProfileNoUserInstrumentedTest` `OK (2 tests)`, with `ReportScreenInstrumentedTest` at 2 of 3 (the one left is its
save-to-disk path, not a navigation claim) and five rows of
`INSTRUMENTATION_RESULT: shortMsg=Process crashed.` at a guest load average of 7.6 to 12.5.

**So Stage 14's device proof is still `BLOCKED — emulator/system instability`, with the sentence narrowed.** What
is no longer open is the *why* of the lost presses: that is measured, fixed in the harness, and demonstrated on
the classes that press the bar. What remains is quantity, not mystery — a long enough run of the 25 classes on a
device that stays healthy for an hour, and the 28 frames of the Russian and 320dp configurations from a verified
build (still 14 of 42 today; the `s14-ru-*` files on disk remain 2026-10-30's, photographed before the editor
layout changed, and are not evidence about it).

**Baseline, re-measured here rather than carried over:** 130 JVM classes / 1640 tests / 0 failures / 0 skipped;
lint 0 errors / 149 warnings; `check-localization.py` pass. CI on `d7eb9ae` is run 41272737643 (one job, 16 of 16
steps) and covers JVM, lint and the localization gate — not the device.

**Google Sign-In remains `BLOCKED — Firebase project / OAuth configuration required`.**

## 14.16 The device proof closes, on one build, the same evening (task #168 continued)

**Status: Stage 14's device proof is complete — 25 instrumented classes / 108 tests, every one passing, and 42
frames from the same bytes. What is open after this section is named in it, and none of it is the navigation
defect §14.15 chased.**

The section that §14.15 left as "a quantity, not a mystery" turned out to need both: the quantity came from a
device that stayed healthy, and getting one required finding out that the mystery had been pointed at the wrong
thing twice.

**The 14:38 matrix, and what its fifteen crashes actually were.** Discovery 25 = 25, one class per instrumentation
process, on `taskplanner_avd` / `emulator-5584` / API 35 against the unchanged production bytes
`08838047e26e0d16c2ddd56f1e5fac9d3887c99cde480ac0307f90339e0936fc`: **8 PASS / 2 FAIL / 15 ENVIRONMENT**. The
fifteen were not scattered: the first eleven classes produced verdicts, and every class after them died at startup.
The reason was in the system log beside each one — `ANR in com.taskplanner / Reason: Process … failed to complete
startup` — with `Start proc` answered **18 s** after the request, ART verifying `TaskPlannerApplication.onCreate()`
at 253 bytecodes/s and `getExecutor()` at `0.00 bytecodes/s`, the guest at `69% kernel + 21% softirq`, and
`am start -W` returning `Status: timeout` at 30 s while the window took focus at t+40 s. `uptime` read
`load average: 2.44` during that. The gate had been reading a proxy; it now asks the question
(`run-instrumented-classes.sh` refuses with exit 8 when the app cannot start), and the same measurement on a
healthy guest is **6 027 ms / 6 538 ms** to the same screen.

**The AVD had to be restarted, not rebooted.** `adb reboot` left the guest running for 45+ minutes with
`sys.boot_completed` unset, no `system_server`, `pm` answering `Can't find service: package`, and the console
insisting `virtual device is running`. A graceful `adb emu kill` and a relaunch with the original command line
(`-avd taskplanner_avd -port 5584 -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -no-snapshot`, read
off the running process before it was stopped) booted in **90 s**. Nothing was wiped; the app's APK was still
`08838047e26e…` on `/data/app` afterwards, re-read before the matrix ran.

**The lost press came back, and it was not the oversleep story.** Two classes failed in that matrix —
`ProfileNavigationRestorationTest` (2 of 2) and `TabPressIdentityInstrumentedTest` (1 of 1) — with
`click moved=false, tightTapTried=true moved=false … mainThreadRoundTrip=38ms` and `81ms`: a main thread that
answered in under a tenth of a second cannot have stretched a 400 ms gesture, so §14.15's answer did not cover
this. Reading the captures against each other found the third cause, and it belonged to the harness:
`NavIdentity.readings()` returns an **empty list** when its own read fails, so eight seconds of settle polling is
indistinguishable from an app that would not move — and in the same run, the read taken one step later reported
`destination navigation_tasks->navigation_profile`, i.e. the app had moved. A second contributor: the reader took
`firstOrNull { lifecycle == RESUMED }`, which during a `recreate()` can be the outgoing activity, whose controller
really does still hold the old destination. `TabTap` now carries whether the input system accepted the gesture,
whether the window held input focus, and polls-run against polls-unread, and its verdict names which of
`INPUT-NOT-DELIVERED`, `NO-FOCUS`, `UNREAD`, `MOVED-UNOBSERVED` is present, reserving `PRESS-REFUSED` for
injected + focused + readable + unchanged. With that, `TabPressIdentityInstrumentedTest` is `OK (1 test)`: six
presses, all `click moved=true … windowFocus true->true … settle 1 polls 0 unread`, **zero** `Overslept` lines and
**zero** tooltip windows in the capture, and the re-selection pop-back reached on the device
(`destination navigation_settings->navigation_profile`). Production code is still untouched, because the readings
still do not show a navigation defect.

**A field that never varied was being read as evidence.** `accessibilityCopies=0` appeared identically in the green
captures, so "the accessibility tree holds no such node" says nothing about a refusal — it is how this Material bar
exposes its items on this image. Recorded so nobody reasons from it again.

**The locale test was comparing the app with itself.** The final matrix's only failure, before the fix below, was
`CalendarLocaleTest.systemDefaultLeavesTheDeviceAnswering`: `expected:<MONDAY> but was:<SUNDAY>` — where SUNDAY was
the app correctly following an `en-US` device and MONDAY was the test's own oracle, which read
`instrumentation.context.resources.configuration`: the instrumentation context lives in the target's process, and a
per-app locale is applied to that process, so the oracle followed the language a previous class had chosen. Run
alone, the method passed — both sides wrong together. Pointing the oracle at the framework's stored locale
(`system_locales`, the key `adb shell settings get system system_locales` answers) made the disagreement visible at
once, and the first attempt at fixing it — `Resources.getSystem().configuration` — failed the new assertion that
the oracle must not move when the app changes language, which is why that assertion is now in the test instead of
in a comment. Both the oracle and `applyLocale`'s settling condition read one helper over a source the app cannot
move. **No production change was justified**: with a fixed oracle the app answers the device's own day after
handing its choice back. What is recorded as open is the other direction — one class run failed at "while the app
holds Russian its calendar opens where Russian opens it", which `applyLocale`'s wait did not cover, and it has not
reproduced in the solo run, the class run before it, or the full matrix after it.

**The matrix, on the bytes everything else on this page names.** 25 discovered = 25 attempted, **PASS=25,
FAIL=0, ENVIRONMENT=0, NOT-RUN=0**, 108 tests / 108 passed, one instrumentation process per class, every row
carrying the installed APK's sha `08838047e26e…`, the guest at load 1.64 and `MemAvailable 1 227 116 kB` before it
and 6.50 after, `ANR in com.taskplanner` zero in the run, host 3 177 MB free. `build/matrix-run-168c.log`.

**The frames: 42 of 42 from one build.** The 28 that were owed — `en 320 light 1.3`, `ru 393 light 1.0`,
`ru 393 dark 1.0`, `ru 320 light 1.3` — were captured through the same provenance gate (`local and installed
agree` printed twice, once per run), and they settle what the October frames could not: «Ноябрь 2…» truncates at
320dp/1.3 **and “November…” truncates identically at the same settings**, so the heading cut is the width and the
scale's and stays `ОТКРЫТО, не Stage 14`; at 393dp/1.0 «Ноябрь 2026» fits whole; the editor footer draws
«СОХРАНИТЬ» on one line; the due field reads «29 окт. 2026 г.»; the Russian week opens Monday and the English
Sunday from the same build; and all five bottom-bar labels fit at the narrowest configuration. The 28 October
frames were moved to `docs/images/ui-redesign/superseded/` with a README naming the build they predate, not
deleted — and, stated plainly because it is a mistake worth keeping in the record: the `git mv` of those files was
swept into commit `6dda399`, whose message is about the press reader and never mentions them. The moves are
published, so the history stays as it is.

**Baseline re-measured for this section** (from `build/gates-168.log`, run after the androidTest changes and
before the docs): 130 JVM classes / 1640 tests / 0 failures / 0 skipped; `check-localization.py` pass (932 Russian
strings, 48 plurals, 0 missing); lint **0 errors / 147 warnings** — two fewer than the 149 §14.14 and §14.15
recorded, counted the same way (`severity="Warning"` in `lint-results-debug.xml`) on the same variant. The
difference is not attributed: nothing in this pass edited a file the warnings sit in, and the two lines were not
tracked down, so both numbers are printed with the run they came from rather than one of them being quietly
written over. CI covers the same gates on the published SHA.

**What is still open after Stage 14, with none of it hidden by the green table:** Google Sign-In
`BLOCKED — Firebase project / OAuth configuration required`; the API 24–32 locale behaviour, which stays proven
structurally and on the JVM because this device is API 35; the calendar heading at 320dp/1.3, open as a
width-and-scale defect; and the one unreproduced locale assertion above, filed as open rather than smoothed over.
**Stage 15 (import from Google Keep) is next. Stage 8b is not scheduled and was not started.**

---

# Stage 15 — Import from Google Keep

## 15.1 User problem

A reader leaving Google Keep has their notes as a text export and nowhere to put them. The app's current
import accepts only its own JSON backup: `BackupRepository.readAndValidate(uri)` (`:75-95`) deserialises with
Gson straight into Room entities (`:81`) and rejects anything that is not a `BackupFile` with a known
`formatVersion` (`:87-92`, `SemanticVersion(1, 1)` at `:484`). A Keep export cannot be imported today, and the
file picker will happily let a person choose one (`SettingsFragment.kt:65` passes
`arrayOf("application/json", "text/plain", "*/*")` — the `"*/*"` entry accepts any document).

## 15.2 Target UX

- Settings gains "Import from Google Keep" beside the existing Import.
- The person picks the Keep export (the `Takeout/Keep/` folder, or its `.zip`, or `notes.txt` + the per-note
  files); the app shows what it found *before* writing: note count, checklist line count, label count,
  nesting depth, and what will be dropped or flattened.
- Nothing is written until the reader confirms, and a safety copy is taken first, the way the existing
  replace-import path does (`createSafetyCopy`, `BackupRepository.kt:168`).
- Imported notes land as tasks and topics, not as a special second-class kind of row.

## 15.3 What already exists

- The SAF plumbing to reuse: `importLauncher` (`SettingsFragment.kt:39-42`), `handlePickedFile`,
  `previewImport(uri): Result<BackupSummary>` (`SettingsViewModel.kt:99-103`), `applyImport` (`:107-113`),
  `importErrorText` (`:105`), `runBusy` (`:189-204`), and `contentResolver.openInputStream`
  (`BackupRepository.kt:76`).
- **No text, HTML or ZIP parsing exists anywhere in the app.** Verified by grep: zero matches for
  `ZipInputStream`, `ZipFile`, `Html.fromHtml` in `app/src/**/*.kt`. The files under
  `data/report/export/` are writers only. A Keep importer is a net-new component.
- The target model: `Task` columns `title`, `description`, `status`, `completed_at`, `group_id` (holding a
  `TaskGroup.uuid`), `order`, `category_id`, `created_at`, `updated_at` (`data/model/Task.kt:53-156`);
  `TaskGroup` = `uuid` (PK), `user_id`, `name`, `collapsed`, `sort_order` (`data/model/TaskGroup.kt:27-56`).
  There is **no `is_checked` column** — a checked Keep line is `status = COMPLETED` plus `completed_at`, the
  same way `TaskDao.archiveTask` (`:198-202`) expresses it.
- A known corpus to test against, supplied with this roadmap: **382 checkbox lines in 37 groups**, with
  nesting **up to 4 levels deep**. It is the reference fixture; the importer is written against it and no
  other.

## 15.4 Modules to touch

New: `data/keep/KeepArchive.kt` (zip/entry discovery), `data/keep/KeepTextParser.kt` (the text format,
nesting, checkboxes), `data/keep/KeepImportMapping.kt` (parsed notes → `Task`/`TaskGroup`),
`data/keep/KeepImportPreview.kt` (what the reader confirms), plus `ui/settings/` for the entry point and a
preview screen. No change to `BackupRepository`'s JSON path.

## 15.5 Data changes

No migration for a first import: notes become `tasks` + `task_groups` rows. Nesting beyond one level does
need Stage 16's hierarchy columns, so this stage's phases 5–6 depend on it (see 15.7). Two things must be
decided before any row is written and recorded in the mapping file: what a Keep *label* becomes (a topic, a
category, or a tag that does not exist yet), and what a Keep *colour* becomes (categories carry `color`,
`Category.kt:41-42`).

## 15.6 Risks

1. **Duplicate import.** Running the importer twice must not double the list. The existing JSON path matches
   by uuid (`BackupRepository.kt:200-202`); a Keep import has no uuid, so it needs a stable derived identity
   (source hash) or a visible "already imported" marker. Getting this wrong is the most likely user-visible
   harm.
2. **`GroupDao.upsertGroup` is `OnConflictStrategy.REPLACE`** (`GroupDao.kt:36`) and `uuid` is the primary
   key, so a colliding uuid deletes and re-adds the row. `tasks.group_id` has no foreign key
   (`Task.kt:100-103`), so tasks survive but silently change owner. The importer must never invent a uuid
   that can collide — and this is the same class of defect that `UserDao`/`CategoryDao` already documented.
3. **Nesting flattened silently.** Four levels cannot be shown today. Either the importer refuses, or it
   flattens and says so in the preview; it must not flatten quietly.
4. **A 382-line corpus that is not the shape of real exports** — encodings, `is_archived`, images,
   attachments, colour hex values, and the `headers.txt`/`annotations.xml` sidecars all differ between
   Takeout versions.

## 15.7 Dependencies

Stages 14 (every new string needs its Russian pair) and 16 (nesting depth > 1). Recommended order: **16
first**, or 15 delivered in two phases — a flat import that reports the depth it dropped, then the nesting
phase after 16 lands. This is a real ordering decision and it is written down rather than left to the
implementer.

## 15.8 Implementation phases

1. **Corpus harness.** Put the 382-line / 37-group export under `app/src/test/resources/keep/` and write the
   counts as a test that reads the file, so the fixture cannot rot.
2. **Parser** (pure Kotlin, no Android): entries → notes; checkbox lines → items with depth; metadata
   (labels, colour, timestamps, archived).
3. **Mapper**: notes → topics and tasks; checked → `COMPLETED` + `completed_at`; order → `Task.order`.
4. **Preview + confirm** screen, with the duplicate strategy decided and shown.
5. **Safety copy, transaction, rollback** — the same guarantees the JSON import already makes.
6. **Nesting phase** (after Stage 16): four levels preserved.
7. **Device proof**: import the real corpus, count what appears, photograph the result.

## 15.9 Acceptance criteria

1. The corpus parses to exactly 37 groups and 382 checkbox lines, asserted from the fixture file rather than
   from a constant typed into a test.
2. Every depth-2/3/4 line is either preserved or reported in the preview as flattened — never silently lost.
3. Importing twice creates zero new rows, and the second run says so on screen.
4. A failed import writes nothing: the row counts before and after are equal, and a safety copy exists.
5. No uuid collision is possible: a test imports twice with a hand-made uuid equal to an existing group's and
   asserts the existing row survives unchanged.
6. Checked lines appear completed, with a completion timestamp, and carry the strike-through treatment Stage
   8a gave the archive.
7. The import is proven on the device with the real corpus, photographed, and the counts in the report come
   from the screen, not from the parser.

## 15.10 Tests

`KeepTextParserTest` (JVM, pure): the corpus, the depth cases, the empty-note case, CRLF and UTF-8, a line
with no checkbox. `KeepMappingTest`: labels, colours, timestamps, derived identity.
`KeepImportInstrumentedTest`: real database, twice-over idempotence, rollback, and a row-count proof.
`SettingsScreenTest`: the entry point exists and the preview is reachable.

## 15.11 Documentation

`docs/IMPORT_KEEP.md` (what a Keep export contains, what maps where, what is dropped and why), the Settings
copy, `README.md`'s feature list, and `docs/LOCALIZATION.md`'s rule applied to the new strings.

## 15.12 Definition of done

The known corpus is imported on a device, the counts on the screen equal the counts in the fixture, a second
import adds nothing, and every dropped or flattened thing was shown to the reader before it happened.

## 15.13 State after the Stage 15 work of 2026-11-02

**Status: delivered, with the half of Stage 16 it needed and no more than that half.** A Google Keep *text* export
pasted into Settings → «Импорт из Google Keep» becomes real topics and real tasks, with the indentation that
decided them kept, the checks that were on them kept, the order they were written in kept, and nothing invented
beside them. Stage 16 is **not** closed by this: what shipped is the storage and the drawing of a tree, and the
editing of one is still that stage's, named in §16.7 and §16.8.

**What was built, in the order it was needed.**

| part | where | what it answers |
|---|---|---|
| the syntax | `data/keep/KeepTextParser.kt` + `KeepTextParserTest` (26) | `[X]`/`[x]`/`[ ]`, BOM, CRLF/CR/LF, tabs and spaces, four levels and a clamp, empty checkbox rows counted as separators apart from blank lines, titles kept byte-for-byte, a line with no box reported rather than guessed at |
| the corpus | `KeepCorpusContractTest` (9) | reads `app/src/test/resources/keep/keep-corpus-shaped-like-the-original.txt` — the depersonalized file, not a constant — and counts it: 439 physical lines, 382 titled elements, 296 checked, 86 open, 57 separator rows, 56 root topics, 19 repeated titles, a 169-character title, and a maximum depth of 1 — the export the stage was written against nests one level, so the four-level contract is proven by the parser, the mapping tripwire and the device's own chain fixture, not claimed about this file |
| the schema | `AppMigrations` 16 → 17, `AppMigrationsTest` | additive: `tasks.parent_uuid` + `index_tasks_user_id_parent_uuid`, `task_groups.is_completed DEFAULT 0`. Schema equality and a file round-trip are tested; no destructive migration is reachable |
| the mapping | `KeepImportMapping.kt` + `KeepImportMappingTest` (25) | topic → `TaskGroup`, line → `Task`, parent links from indentation only, status from the box only, `completedAt` never written, and a deterministic identity per row |
| the importer | `KeepImportRepository.kt` + `KeepImportTransactionTest` (19) | guards → safety copy → one Room transaction → `PRAGMA foreign_key_check` before the commit; ABORT inserts, so a name that exists is refused rather than replaced; a second paste of the same text writes nothing |
| the screen | `ui/keep/` + `KeepImportViewModelTest` (15), `KeepImportScreenTest` (11) | paste → parse → preview → confirm, two phases, every sentence a resource, the tree opening and closing with the hidden count on the row that hid it |
| the drawing | `ui/tasks/TaskTree.kt` + `TaskTreeDepthTest` (6), `TaskCardIndentTest` (4) | the depth a card is drawn at, measured from the parent links of the rows on screen, bounded at four levels |
| the device | `KeepImportInstrumentedTest` (6) | the route, the paste through the real widget, the stored tree, the repeat paste, the nested cards, and the frame matrix |

**The identity decision, because it is the one a later reader will want to change.** A row's uuid is
`UUID.nameUUIDFromBytes` over `keep | KIND | profile | parent key | occurrence | title`, where the parent key is a
topic's own uuid or the parent task's. Two things are deliberately **not** in that key: the fingerprint of the whole
paste, and the row's position in it. The first version had the fingerprint and was wrong in a way the test found:
fixing one typo in a 439-line export re-identified all 382 rows, so the second paste became a duplicate import.
Position was excluded for the mirror reason — inserting one line would re-identify every sibling below it. The
fingerprint still exists and still answers "has this exact text been imported before"; it just does not decide what
a row *is*.

**What the device corrected — seven things in the test, five in the app.** All twelve were found by running the
route on `taskplanner_avd` and looking at what came back, not by reading the code.

*The test side:*

1. `Espresso.onView(keepImportButton).check(matches(isDisplayed()))` failed on a row the Settings panel keeps below
   the fold. The row is `VISIBLE` at `y=315` of a window that is not showing that far down; the check now scrolls
   first, which is what a reader does.
2. `?.findViewById(R.id.rowToggle)` with no type argument compiled and died at runtime with
   `MaterialButton cannot be cast to Void`: Kotlin inferred the generic from the `return@onScreen null` above it,
   which is `Nothing?`. Two reads were written that way.
3. `ViewActions.scrollTo()` refuses `confirmButton`, and is right to — the confirm bar is a pinned LinearLayout, not
   a scroll container. The action that writes rows is deliberately never below a fold.
4. `ViewActions.scrollTo()` cannot reach a task card the RecyclerView has not laid out: it is not an off-screen
   view, it does not exist yet. Measured on the first device run, it answered `NoMatchingViewException` for a card
   the list went on to draw one swipe later. The run asks the *drawn* rows instead — `paddingsFor` harvests every
   card the list passes, `scrollPreviewTo` stops on one named row — and item 6 records why neither of them gets
   there by swiping.
5. **A frame was taken one composition early.** The matrix photographed a topic whose row had already been rebound
   to "open this topic" while the pixels still said "close this topic", because the run waited on
   `adapter.itemCount` — which changes a layout pass before the list rebinds, and two passes before the compositor
   presents. Diffed against its neighbour frame, the only pixels that had moved were the scrollbar and the clock.
   Every frame is now taken after the *drawn* row has been read (`toggleLabelFor`, `hiddenLabelFor`,
   `visibleRowTitles`, `visibleCardTitles`) and after `onIdle` plus a 1.5 s settle, and the frame set is five rather
   than four so the statistics card and the tree are each photographed at the scroll position that shows them.
6. **A swipe travels a screenful, and a fling does not stop where the run looks.** Two helpers asked a list to
   reach a row by swiping it, and both failed at 320dp with the font at 1.3, where a screenful is two rows.
   `scrollPreviewTo` swiped the preview until a title appeared among the drawn rows; because the statistics card is
   taller than the viewport there, the list starts with no tree row drawn at all, and the one swipe that reaches the
   tree also flies past its first topic — the run reported the rows *below* it (`[open child, done child, grandchild,
   🟨Завтра, lowercase done, repeated item, repeated item]`) and refused after eight swipes. `paddingsFor` harvested
   every card at every rest, which is the fix for a *single* overshoot, and still found two of the three chain cards
   in both languages: `swipeUp()` ends in a fling, and the instant Espresso calls idle can be mid-glide, so two
   consecutive rests can skip a row anyway. Both now scroll a third of the list per step with `scrollBy`, which has
   no inertia and leaves two thirds of the viewport overlapping between steps; the preview additionally asks whether
   the row is drawn *inside* the list's bounds, because a row clipped at the bottom edge has a control that cannot be
   pressed and a label no frame can be trusted to show.
7. **The guard that refuses a blank frame was itself unprovable.** Its rule was a 9x9 grid across the window and a
   minimum contrast between the brightest and darkest sample, and it refused `g4-preview-tree` in both dark
   configurations at 33 and 32 levels. The refused picture — kept, because a refusal nobody can look at can only be
   argued about — shows the whole tree in white text with a mint button and a bottom bar. The same measure had read
   **238** on the previous run's dark frame of the same screen: the grid's 90 points landed on a glyph that time and
   between the rows this time. Nothing about the app changed; the measurement was never measuring what it named. The
   guard now reads the whole frame at a 3 px stride and asks the two questions a window that failed to draw cannot
   answer — how many distinct luminances it holds, and how much of it is not its most common colour. Over the 32
   frames this stage produced those read 189–225 and 18.5–62.2%, against 1 and 0 for a solid rectangle, with the
   floors set at 32 and 5%. A refused frame is still written to the device as `x-refuse-*` before the run stops. Four
   older frame classes carry the grid version of this guard — `Stage14LanguageFramesInstrumentedTest`,
   `Stage8aScreensInstrumentedTest`, `TaskEditorFramesInstrumentedTest`, `BottomNavigationInstrumentedTest` — and
   were not re-opened here: their screens are dense enough that the grid has always hit something.

*The app side: two figures read off a screenshot, and three drawings.*

8. **The preview's «rows to write» counted the rows on screen.** With one topic folded it read 5 where the same
   paste had read 8 — the three rows of the branch the reader had just hidden. Folding is a way of looking; it is
   not a smaller import. The two counted lines now come from `KeepImportPlan.rowsToWrite` and
   `rowsAlreadyHere`, which are derived from the plan, and `KeepImportMappingTest` pins that they do not move when
   `KeepPreviewRows.of` is handed an empty set of open topics.
9. **«Nested under a task» went to 0 on a re-paste.** It was read from the plan's *write* statistics, so a paste the
   profile already owned — writing nothing, correctly — described its own tree as flat beside a screen still
   drawing the grandchild indented. Shape figures now come from `KeepImportPlan.nestedRows`, counted over every
   candidate, and the write statistics say so in their own KDoc.
10. **«Импортировать» arrived cut in half.** At 320dp with the font at 1.3 the confirm bar kept its two actions
   side by side, each `weight=1`, so the Russian verb wrapped inside a button half the width of its own label and
   the second line fell outside the bar: the drawn button was 81 px tall and only 67 px of it were inside the
   window's visible rect — 82.7%. Every wide configuration looked right; only the narrow one showed it. The bar's
   shape is now chosen from the width rather than fixed in XML: `KeepConfirmBar.shape(...)` reads
   `R.bool.keep_confirm_actions_stacked`, `values/config.xml` says `true` and `values-w360dp/config.xml` says
   `false`, and the stacked form gives each action the whole width. `KeepImportScreenTest` pins both halves of
   that choice against a configuration whose `screenWidthDp` is the one under test — before this, `widthDp` only
   steered the measure specs, so a test asking for 320dp still read the default screen and a width-dependent
   decision could be asserted without being exercised.
11. **The empty paste field drew two sentences on one line.** The layout's floating label «Pasted text» and the edit
    text's own `android:hint` — the sentence that explains the format — are both drawn at the text position while the
    field is empty, and the behaviour frame showed the dark label printed straight across «Paste here, one note per
    line…». The guidance is now the layout's *helper text*, which Material draws in its own row under the box:
    readable while the field is empty and while it is full, and announced with the field. `KeepImportScreenTest` pins
    where the sentence lives and records why it cannot pin the drawing — read back under Robolectric,
    `TextInputLayout` answers `pasteInput.hint` with its own label whether or not the XML gives the child a hint, so
    an assertion about `getHint()` passes against the broken layout and proves nothing. The frame is the proof of the
    drawing; the test is the guard against putting the hint back.
12. **A preview row could lose its title entirely.** At 320dp with the font at 1.3 the topic row's title was measured
    at **0 px** on the device and drew nothing. The title was the row's only weighted child, and the state mark, the
    outcome caption, the hidden count and a Russian toggle label together took the whole width, so the weighted child
    was handed the leftover — which was none. The frame read «☑ · новая · ещё 3 · РАЗВЕРНУТЬ ТЕМ…» with a blank where
    the note's name belongs, while the binding still held «🟥Сегодня»: no assertion on `rowTitle.text` sees that, and
    no Robolectric measure can, because LEGACY graphics answer `measureText` with a character count and the labels
    that crowd the row out on a device measure almost nothing there. The captions now sit *under* the title, in the
    column the row weights, and the topic's control is the row's one fixed competitor for width. The device run
    measures every drawn row's title against a 48dp floor before it photographs it, and `KeepImportScreenTest` pins
    the arrangement that floor depends on. The first version of that fix left a smaller mess in the same frame — the
    two captions sharing one line reflowed inside their own words, «новая ещ / ё / 3» and "new 3 / hidden" — so each
    statement now gets the column to itself, and the 320dp frames are what says so.

**The numbers, measured on 2026-11-02.** JVM: **139 classes / 1761 tests / 0 failures / 0 skipped**. Lint:
**0 errors, 150 warnings**. `check-localization.py`: **pass — 985 strings and 53 plurals in English, 980 and 53 in
Russian, 0 missing, 0 placeholder mismatches** (the five keys English alone carries are the deliberately
untranslated ones Stage 14 named: `app_name`, the two language names, the two format labels).
Device, `taskplanner_avd` / `emulator-5584` / API 35, production bytes
`a96e62e7b90dd891b662c467b6fcbe7fa0b09b65df019d37cc8e82cbeaf5afb9` read back from `/data/app` before and after
(the instrumented APK beside it `95c115a65a0f277d91fae06e7c1bdcd5f55cd3d7bb49ea07ff7a437a23b74caf`):
`KeepImportInstrumentedTest` **`OK (6 tests)`**, and the frame matrix in `docs/images/ui-redesign/after/s15/` —
six configurations (en/ru × 393dp light, 393dp dark, 320dp light at font 1.3) × five frames, each
configuration's run refusing a tag that lies about the device's own width, theme or font scale, refusing an
installed APK whose bytes differ from the built one, and refusing any row whose drawn title is narrower than
48dp — plus six behaviour frames, **36 pictures in all, every one from those bytes**. Four rebuilds of the
instrumented APK and two of the app went into that set; the gallery holds only the last, because a frame is
evidence about a build and the run says which one.

**One finding about the device itself, recorded because it will bite the next stage.** The app came into Stage 15's
first device run rendering Russian while `ro.product.locale` and `system_locales` both read `en-US`: a per-app
language override from Stage 14's matrix was still set, and `am instrument -e clearPackageData true` does not take
it back. The class now hands the language back in its own teardown and its frames method refuses a directory name
the window does not agree with; the three resource-driven assertions in it read through
`AppLocale.localized(context)` so they hold in either language.

**What the review passes caught, recorded because the brief made each of them a rule.** Four things were right in
the code and wrong in the writing about it, and one was wrong in the files themselves:

* **A comment claimed a database guarantee that does not exist.** `KeepImportRepository` said the `ABORT` inserts
  and `PRAGMA foreign_key_check` make a half-written import impossible. `task_groups.uuid` *is* the primary key, so
  the topic side is real; `tasks.uuid` has no unique index (the PK is an autoincrement `id`), and
  `tasks.group_id` / `tasks.parent_uuid` have no declared foreign key, so the pragma cannot see an orphaned subtree
  and the task side rests on the mapping's ownership read. `KeepImportTransactionTest`'s KDoc likewise said an
  ABORT race was tested; it is not — only the trigger-injected abort is. Both now say which half holds and name the
  stage that owns the gap.
* **Three English literals stood where a resource belongs.** The TalkBack row description joined the title and the
  toggle's own label with a comma written in Kotlin; it is `R.string.keep_import_row_description` (`%1$s, %2$s` in
  English, `%1$s: %2$s` in Russian) read from the row that is being described.
* **A real line from the original export had been copied into the repo** — a date, a time and a street address, used
  because it was the clearest example of a title that is not a due date. It sat in `KeepTextParserTest`,
  `KeepImportMappingTest`, `KeepImportScreenTest`, the preview row's `tools:text` and `docs/IMPORT_KEEP.md`. All five
  now carry an invented line of the same shape. The export itself, the JSON import artefact and the backup the
  importer writes are `.gitignore`d (lines 100–103), were never staged, and nothing on the device or in the frame
  gallery comes from the reader's notes: the instrumented run pastes the fixtures it builds in its own companion
  object. The check is a sweep rather than a promise: each of the export's 234 distinct titles of twelve characters
  or more was searched for inside all 616 text files the worktree would commit — none appears.
* **A claim about the corpus's depth had been inherited from the brief rather than measured.** §15.13 and
  `docs/IMPORT_KEEP.md` described four levels as a property of the original export; `KeepCorpusContractTest` counts
  `maxDepth == 1`. The four-level contract is proven by the parser, the mapping tripwires and the device's chain
  fixture, and the docs now say that instead.

**And one thing CI caught that no local run could.** `KeepCorpusContractTest` counts 438 CR bytes in the corpus
fixture — the file is depersonalized, but it is shaped like the export the stage was written against, and the export
writes CRLF. That held on the machine that wrote it, where `core.autocrlf=true` rebuilds CRLF at checkout, and failed
on GitHub's Linux runner, where the same blob unpacks as LF: run 41416407024 stopped at line 39 of a test that had
passed in every local run, `expected:<438> but was:<0>`. The assertion was not the wrong claim and relaxing it would
have been the wrong lesson — a fixture whose shape depends on which client unpacked it is not a contract — so
`.gitattributes` now pins `app/src/test/resources/keep/*.txt` to `text eol=crlf` and nothing else. The failure was
reproduced before it was fixed and the fix confirmed the same way: a clone with `core.autocrlf=false` of `676bbb2`
reads `CRLF 0 / LF 438`, and of `1080dc2` reads `CRLF 438`. The published head `0916f4e` is green — **16 of 16 steps**, run 41419503843 — with one
thing said out loud rather than buried: its first attempt failed on `WorkAdjustmentUiTest`, a Stage 3F screen test
nothing in this stage touches (`the user is told which of the two fields is wrong expected:<A stretch cannot end at or
before it starts.> but was:<null>`), the same commit tree passed the suite minutes earlier at `1080dc2`, and the
rerun of the failed job passed 16/16. It is recorded as an intermittent to be diagnosed on its own day, not as a
green run to be reported and forgotten, and not as a test to be relaxed.

**What Stage 16 still owns, stated so this section cannot be read as that stage.** Move a task between parents,
delete-with-children policy, a cycle guard at the moment a *user* attempts a reparent, a collapse control below the
topic level, and the filtered-reorder collision `TaskReorderPersistenceTest` records but does not fix. The parent
key, the index, the depth bound, the topic's own completed state, the indent and the sync fields are in.

**Google Sign-In is unchanged by this stage and remains `BLOCKED — Firebase project / OAuth configuration
required`.**

---

# Stage 16 — A real topic and task hierarchy

## 16.1 User problem

Keep's lists nest. This app has exactly one level: a task belongs to at most one topic, a topic belongs to
nothing, and collapsing is the only tree affordance. A reader with "Работа → Отчёт → Глава 2 → абзацы" has
nowhere to put it.

## 16.2 Target UX

- Indent and outdent a topic or a task with a left/right control, in the editor and from the list.
- A child is visibly a child: an indent, a parent line, and a collapse control at every level.
- Moving a parent moves its subtree; deleting a parent says what happens to the children before it does it.
- Depth is bounded and stated (four levels; the reader's measured export nests one, so the bound is a promise about what the app can hold, not a description of the file it was written against), not unlimited and broken at the
  fourth.

## 16.3 What already exists

- **Schema space is already paid for on categories and entirely unbuilt.** `Category.parent_category_id`
  exists (`data/model/Category.kt:45-46`) with a self-FK `ON DELETE SET NULL` (`:18-22`), an index, and a real
  migration (`AppMigrations.kt:67-81`). Two DAO readers exist — `getRootCategories` (`CategoryDao.kt:25-29`)
  and `getSubcategories` (`:31-35`) — and **nothing in the app calls them**; the matching
  `CategoryRepository` methods (`:25-32`) are dead code. No writer ever sets a non-null parent, and the
  string `label_parent_category` ("Parent Category", `strings.xml:36`) is referenced by no layout and no
  Kotlin file.
- **Half of the schema landed on 2026-11-02, as Stage 15's prerequisite.** `Task.parent_uuid` exists
  (`Task.kt:163-175`) with an `index_tasks_user_id_parent_uuid` beside it, `TaskGroup.is_completed` exists
  (`TaskGroup.kt:59-71`), migration **16 → 17** is written and pinned by `AppMigrationsTest`, both fields ride
  the sync documents (`SyncedTask.parentUuid`, `SyncedGroup.completed`), and the task list already indents by
  the depth `TaskTree.depthsByTaskId` measures (`TaskTree.kt`, `TaskCardIndentTest`, `TaskTreeDepthTest`). What
  that bought is the *storage and the drawing* of a tree, which is what an import needs; it is not the editing of
  one, which is this stage.
- **`TaskGroup` still has no parent column**: topics do not nest inside topics, and no Keep export syntax asks
  for it. `Task.parent_uuid` is the only nesting key that exists.
- The hierarchy that is rendered is one level of grouping plus a depth-driven indent inside it:
  `TasksViewModel.render` emits a `GroupHeader`, then that group's tasks by `groupId`, each indented by
  `TaskTree`. **A per-task fold is stored, obeyed by the list, drawn on the card, and its filter case is closed**
  (schema v18, `tasks.children_collapsed`, hidden set from `TaskHierarchyPolicy.collapsedAwayIds`, and `branchFold` —
  digit plus chevron — on line C of `item_task.xml`, painted by `TasksAdapter.bindBranch` and wired to
  `TasksViewModel.toggleBranchCollapse` by `TasksFragment`). §16.2's "a collapse control at every level" is therefore
  built and asserted in `TaskCardBranchTest`, and §7's «Обработка фильтров» clause is built and asserted in
  `TasksFilterContextTest`: a category or status filter that drops a parent now draws that parent above the child as
  `shownForContext` — marked in three channels, not counted as a match, not selectable, and offering no fold and no
  handle — which is the brief's preferred answer to «нельзя показывать его потомка так, будто он стал корневой
  задачей». `docs/HIERARCHY.md` describes both, including the device frames the two channels still owe (§19).
- Ordering primitives exist and are usable: `tasks.order` (`:141`), `TaskDao.assignTaskOrder` (`:216-228`),
  `TaskDao.getRowsInStoredOrder`, `TaskHierarchyRepository.moveWithinSiblings`, `GroupDao.nextSortOrder` (`:30-34`).
- **The ordering defect this stage inherited is fixed** (2026-11-02, phase 1 below). `saveVisibleOrder` used to
  re-index `0..n` over the *filtered* subset and `onMove` required only equal view types
  (`TasksFragment.kt:178`), so a drag under a filter could collide with rows outside it and a row could be dropped
  across a group boundary. Both are gone: one profile-wide sequence, a subtree that travels as one block, and a
  sibling-set boundary that refuses the rest by name. `docs/HIERARCHY.md` describes what replaced them.
- **A parent's delete now has a policy, and the domain asks for it** (2026-11-02, §9). `TaskHierarchyRepository.deleteTask`
  takes `HierarchyDeletePolicy.PROMOTE_CHILDREN` / `DELETE_SUBTREE`, refuses by name when the row is being measured or
  its identities are not single-valued, and when called with no policy on a row that holds a branch it writes nothing and
  answers `Confirm(childCount, subtreeSize)` — which is what the tasks screen turns into a two-operation dialog
  (`delete_branch_*`, in English and Russian). The list and selection-bar deletes go through it, so the orphan path the
  Stage 15 import made reachable is closed **on the tasks screen**; the category screen still has the raw path, and
  `docs/HIERARCHY.md` names the file and line as §6's to retire rather than letting the claim read as a stage-wide one.
  `docs/HIERARCHY.md` states the three inherited rules it pins.
- The row that would show an indent, `res/layout/item_group_header.xml`, has a fixed `marginStart="8dp"`
  (`:7`) and `paddingHorizontal="12dp"` (`:18`) with nothing depth-driven.

## 16.4 Modules to touch

`data/model/Task.kt`, `data/model/TaskGroup.kt`, `data/local/AppDatabase.kt` (version 17),
`data/local/AppMigrations.kt`, `GroupDao`, `TaskDao`, `GroupRepository`, `TaskRepository`,
`ui/tasks/TasksViewModel.kt` (render, move, indent), `TasksAdapter.kt`, `TasksFragment.kt`,
`item_task.xml`, `item_group_header.xml`, `ui/tasks/TaskDetailFragment.kt`, plus the category screens if
categories join the same model.

## 16.5 Data changes

**Migration 16 → 17 is done** (Stage 15, additive: `tasks.parent_uuid` + its index, `task_groups.is_completed`),
and it settled the two questions this section used to pose:

- the key is a **uuid string** — `tasks.parent_uuid` matches `tasks.group_id`'s convention, and the inconsistency
  with the numeric `category_id` is documented rather than deepened;
- **backfill: every existing row stayed a root**, and nothing was inferred from titles or from order. `is_completed`
  arrived with a `DEFAULT 0`, so no topic that ever existed changed state because of it.

What this stage still needed, if anything, was **17 → 18** — the depth bound is enforced in code today
(`TaskHierarchyPolicy.MAX_TASK_LEVELS`, read by the parser, the mapping, the move validation and the renderer), cycles
are made impossible by how the import builds a chain and refused by how a move writes one, and neither wanted a
column. A schema change was not assumed; it was the decision this section still owned, and the decision went: **one
column, for the fold.** `MIGRATION_17_18` adds `tasks.children_collapsed INTEGER NOT NULL DEFAULT 0` and nothing else
— no index, since the flag is never a lookup key — so §7's per-level collapse is state on the row rather than memory
in a screen. `AppMigrationsTest` pins the schema equality and that no other column of any task moved.

**The identity audit ran before the move controls, and it decided against a schema change** (2026-11-02).
Measured on the emulator's own `taskplanner.db` (v17, 58 rows): zero empty uuids, zero duplicate uuids inside a
profile, zero uuids shared across profiles, zero parent links, zero cycles — and **eight reminder rows whose
`task_uuid` matches no task**, which is the proof that a key without a foreign key does dangle in this app's real
storage. `UNIQUE(user_id, uuid)` for non-empty uuids would build cleanly on that data, but a
`CREATE UNIQUE INDEX` that meets a duplicate fails the migration on a database nobody here has measured, and the
reader is left with an app that will not open; there is no safe automatic backfill either, because re-issuing a uuid
means rewriting `reminders`, `goal_tasks`, `work_sessions` and `tracking_sessions` — the tables the audit just showed
to hold dead references. So no index and no backfill were attempted, nothing was repaired with `INSERT OR REPLACE`,
and the guarantee moved into the write path: parent identities are resolved scoped to the profile, and
`HierarchyMoveRefusal.AMBIGUOUS_PARENT` refuses a move whose block cannot be named. `TaskIdentityAuditTest` pins it,
including the narrow case the refusal must *not* block — reordering two children of a doubled parent, which needs no
answer about who their parent is. Full table of measurements and the reminder-path finding left open: `docs/HIERARCHY.md`.

`task_groups.parent_uuid` is **out**, on purpose: nothing in the app or the source format asks for a topic inside
a topic, and a column with no writer is how `Category.parent_category_id` became dead code (§16.3).

## 16.6 Risks

1. **A move that creates a cycle** makes a subtree unreachable and the list render loop forever. Test it.
2. ~~**The flatten-and-reindex defect above** gets worse with depth: a partial re-index across three levels can
   interleave siblings of different parents.~~ **Closed by phase 1.** A reorder now splices one subtree block inside
   the profile's whole sequence and refuses any drop outside the dragged row's sibling set, so the interleaving the
   risk described has nowhere to come from. What is left of it is Stage 17's: the gesture arbitration between a drag
   and a list that re-renders while the finger is down.
3. **Sync and export must carry the new field** or a two-device user gets a tree that collapses on the other
   device: `FirestoreSyncStore` has a fixed field list (`:94-112`), `BackupFile`'s inner keys are the
   `@ColumnInfo` names, and `SyncAppliers` matches by uuid. All three need the parent key, and an
   out-of-order arrival (child before parent) needs a rule — `SyncAppliers` already handles the analogous case
   for categories.
4. **Deleting a parent** with `SET NULL` orphans children silently; with `CASCADE` it deletes a subtree the
   reader did not mean to remove. The decision must be visible in the UI, not only in the schema.
5. **Performance**: the list is built in memory per emission (`render`, `:266-287`); a recursive build over a
   large tree on the main thread is a jank risk Stage 10 measures.

## 16.7 Dependencies

**No longer blocks Stage 15**: the minimal hierarchy the import needed (parent key, index, depth bound, the
topic's own completed state, the drawing) shipped inside Stage 15 on 2026-11-02, and a four-level chain is proven end to end — parser, mapping, importer, task list, device frames — though the reader's own export nests one level deep. What remains here is the *editing*. Needs Stage 14's string discipline for the new controls. Interacts with
Stage 17 (dragging a subtree) and Stage 18 (selecting a subtree).

## 16.8 Implementation phases

1. **Fix the ordering defect**, with a failing test: reorder while filtered, and assert rows outside the filter
   keep their order. **Done.** The RED came first, against the ordering that shipped:
   `TaskReorderPersistenceTest.a_reordered_filtered_subset_still_leaves_the_profile_one_unambiguous_sequence` failed
   with `no two rows of one profile may claim the same position expected:<4> but was:<2>` — four rows, two positions.
   The fix put the decision in one place (`data/hierarchy/TaskHierarchyPolicy.kt`), the write in one transaction
   (`data/repository/TaskHierarchyRepository.kt`), and the boundary in front of the gesture
   (`TasksFragment.onMove`, which now refuses a target that is not a sibling and moves the whole subtree block).
   19 tests in `TasksOrderingTest` + 3 in `TaskReorderPersistenceTest` cover the filtered cases, the refusals, the
   subtree block, the bounded write, the cross-profile case, a blank `uuid`, and the reconciliation of a profile
   whose positions were already doubled or gapped. `docs/HIERARCHY.md` records the strategy chosen and why.
2. ~~Migration 16→17 with a schema-equality test and a round-trip on a copied database file.~~ **Done** as
   Stage 15's foundation (`parent_uuid`, `is_completed`, the parent index). This stage's data work starts at
   **17 → 18**, for whatever the move controls need beyond what exists — and it needs little: the key, the index
   and the depth bound are already in.

   **1.b — the identity audit ran before the move controls (2026-11-02) and refused a schema change.** Measured on the
   emulator's own database: 0 empty uuids, 0 duplicate uuids per profile, 0 shared across profiles, 0 parent links,
   0 cycles — and 8 of 8 `reminders` pointing at a task that is not there. A unique index would build on that data and
   was still not added, because a `CREATE UNIQUE INDEX` that meets a duplicate on an unmeasured account fails the
   migration; the ambiguity is refused at the write instead (`HierarchyMoveRefusal.AMBIGUOUS_PARENT`,
   `TaskIdentityAuditTest`), and the reproducible measurement lives in `scripts/audit-task-identity.py`.
3. **Model + repository**: move-subtree, delete-with-children policy, and the cycle guard *for moves* — the
   import's chain cannot cycle by construction and `TaskTree`'s walk is bounded, but a user-driven reparent
   needs the check at the moment it is attempted, which nothing does today.

   **The model and the cycle guard are in** (2026-11-02). `TaskHierarchyRepository` answers `indentTask`,
   `outdentTask`, `moveSubtreeUnder` and `moveWithinSiblings`, each inside one transaction over the profile's stored
   rows and each answering with a named `HierarchyMoveResult`; `HierarchyMoveTest` (26 tests) covers indent onto the
   row above, outdent past the subtree it leaves, a subtree crossing into another topic with `group_id` rewritten on
   every row, and the refusals — self, own descendant, another profile's row, a topic that does not exist, a topic
   that is not the destination parent's, and any move that would push the **deepest row of the block** past the third
   task level. Every refusal re-reads the profile afterwards and compares rows, links, topics and positions with what
   was there before: "the refusal writes nothing" is an assertion, not a claim.
   **The delete-with-children policy is the next phase**, and it is the one this phase makes unavoidable: an indent
   creates the first path that orphans a subtree, so `docs/HIERARCHY.md`'s sentence "there is no path that creates one"
   stops being true with this commit.
   **Done (2026-11-02), including the screen that asks.** `deleteTask(taskId, policy)` writes `PROMOTE_CHILDREN` or
   `DELETE_SUBTREE`, refuses a measured row and an ambiguous block by name, and with no policy on a row that holds a
   branch writes nothing and answers `Confirm(childCount, subtreeSize)`; `TasksFragment` turns that into a
   two-operation dialog, and `HierarchyDeleteTest` (18) and `HierarchyDeleteCommandTest` (12) cover both branches on
   real Room, the refusal snapshots, the reminder and series rules the tree inherits, and a delete whose second write
   is made to fail. See §16.3 above and `docs/HIERARCHY.md`'s "A parent's delete is a choice".
   **The depth contract was settled at the same time**: `TaskHierarchyPolicy.MAX_TASK_LEVELS = 3` (topic + three task
   levels) is now the single bound the parser, the mapping, the move validation and the renderer read — Stage 15 left
   the parser and the renderer each holding a constant called 3 and meaning different things by it, which let the card
   draw a level no import could write. `TaskTree` no longer owns a depth constant, and the drift is pinned by a test.
4. **Rendering**: a chevron per level and a collapse that works below the topic, and the first visible mark
   for a topic's own completed state. The depth-driven indent exists; a bounded depth exists; what was
   missing when this phase was written was the control at every level, and `TaskGroup.is_completed` — stored
   since 16 → 17 and carried by sync — was read by no screen. Both halves are delivered below.

   **The fold half of it is in** (2026-11-03, in two commits). `652e2e4` added migration **17 → 18**
   (`tasks.children_collapsed`, default `0`, additive), `TaskHierarchyPolicy.collapsedAwayIds` /
   `descendantCountsByTaskId` (the second since replaced by `foldDeltas` — it counted storage, and the card needed a
   number about the screen), the repository's `toggleBranchCollapse` (one row written, `NO_CHILDREN` and
   `AMBIGUOUS_IDENTITY` refused by name) and a list that hides the branch it is told to — with the search-vs-fold rule
   decided and asserted both ways. The next commit put the control on the card: `branchFold` on line C, the digit and
   the chevron `TasksAdapter.bindBranch` paints from the stored column, the accessible name that turns with the state,
   and `TasksFragment` calling `toggleBranchCollapse` under it. 16 tests in `TaskCardBranchTest`, 5 in
   `TasksHierarchyRenderTest`'s count section, and one new case in `TaskCardWidthBudgetTest` that prices the control
   against the work line. Two review passes changed the shape of that commit before it shipped, and their findings are
   in the code: the click had to move from the chevron onto the whole wrapper (the digit was a dead zone that opened the
   task instead of folding it), the mode had to travel on the row (a `ListAdapter` rebinds only what changed, so an
   adapter flag left every unselected parent offering a fold during a batch), and the number had to become
   screen-relative (a fold inside a fold promised rows no press would move). A third commit closed the last clause of §7,
   «Обработка фильтров»: `missingAncestorsBy` + `TaskRow.shownForContext` draw a filter-dropped parent above the child
   that needs it, marked in three channels and refused by every batch path, which is the brief's preferred answer and
   the end of §7's behaviour (§19 owes it two frames). **§8 is now in as well** — the topic's own mark is drawn on its
   header (a caption line with a 16dp check, the words «тема выполнена», the name struck through, all three written on
   both branches so a recycled header gives them up) and switched from the header's menu, whose label and write are
   decided together in `GroupHeaderMenu`; the write is one UPDATE over `is_completed`, `updated_at` and `sync_state`,
   and a review-driven test file, `TopicCompletionTest`, proves the four refusals: no aggregation from the children in
   either direction, no completion time invented for a topic, and no task row touched. Phase 4's rendering work is
   therefore complete in code and in JVM assertions; what it still owes is the camera (§19).
5. **Controls**: indent/outdent in the editor and on the row; a long-press move for whole subtrees.
6. **Carry it everywhere**: backup JSON, Firestore fields, CSV/PDF report grouping. The sync half has one named defect
   to close, found by Stage 16 §5's review and recorded in `docs/HIERARCHY.md` under *What a physical delete does to a
   tombstone*: `TaskDeleteCoordinator.hardDelete` — the archive's "delete forever" — tombstones a row and removes it from
   the table in the same call, so the deletion is never pushed and the local evidence a pull needs in order not to
   resurrect the row is destroyed with it (`TaskDao.getAllRowsOnce` and `TaskDao.findTaskByUuid` both read tombstones on
   purpose, and say so in their comments). The requirement is a purge, not a re-order: a retired row may be physically
   removed only once its own `sync_state` has come back to `SYNCED` — the state `SyncRepository.pushPending` sets when it
   has carried that row to the cloud. Not `TaskDao.getPendingSyncTasks`, which a first draft of this requirement named:
   its SQL is `sync_state != 'SYNCED' OR is_deleted = 1`, so it lists every tombstone forever and the purge it gates
   would never run. And because `SyncRepository.pushPending` marks a row `SYNCED` *before* it calls `store.pushTask`,
   that state is intent rather than confirmation: §11 must first make the marking follow the confirmed write, or the
   purge would delete tombstones that were never delivered and re-open the hole it closes. It predates §5 — the archive used to remove rows
   with no tombstone at all — and §5 made the two-step the domain verb, which is why it is stated here rather than left
   in a KDoc.
7. **Device proof** at 320dp and at font scale 1.3, light and dark.

## 16.9 Acceptance criteria

1. A four-level tree renders four distinct indents, photographed at 320dp.
2. Indenting a node under its own descendant is refused, and the attempt leaves the data unchanged.
3. Moving a parent moves its subtree and the child count under each parent is asserted from the database.
4. Deleting a parent asks what to do with the children, and both answers are tested.
5. Export → wipe → import round-trips the tree with the same shape and the same parent links.
6. Two devices (or a simulated second sync peer) converge on the same tree; a child that arrives before its
   parent is not dropped.
7. The ordering fix from phase 1 has its own regression test, and it fails before the fix is written.
8. No row is lost by the migration: counts before and after, per table, on a copy of a real database.

## 16.10 Tests

`HierarchyMoveTest` (JVM): cycle refusal, subtree move, depth bound. `AppMigrationsTest` extension: 16→17
schema equality and data preservation. `TasksHierarchyRenderTest` (Robolectric): indent per level, collapse
per level. `TasksOrderingTest`: the filtered-reorder defect. `HierarchySyncTest`: parent-before-child,
convergence. `HierarchyInstrumentedTest`: indent/outdent by tapping the real controls, on a device.

## 16.11 Documentation

A new `docs/HIERARCHY.md` (the model, the depth bound, the delete policy, why the key is a uuid),
`docs/BACKUP_AND_SYNC.md` and `docs/REPORT_EXPORT.md` updated for the new field, and this file's Stage 15
dependency note resolved.

## 16.12 Definition of done

A reader nests four levels deep, collapses the middle one, moves it, exports, imports on a clean profile, and
the tree is the same tree — with every step shown on a device.

---

# Stage 17 — Drag and drop that coexists with pull-to-refresh

## 17.1 User problem

Both gestures exist on the Tasks screen and neither behaves as it looks. Vertical drag reorders rows from a
handle; pulling down refreshes. The pull refreshes nothing, and the drag has no agreement with the pull.

## 17.2 Target UX

- Pull to refresh does something the reader can see, or it is not offered.
- Dragging a row never starts a refresh, and pulling to refresh never steals a row mid-drag.
- The dragged row is visibly lifted, the gap it will land in is visible, and the drop is committed once.

## 17.3 What already exists

- `SwipeRefreshLayout` on exactly one screen: `res/layout/fragment_tasks.xml:139-140`, wrapping the
  `RecyclerView` (`:152-158`), the progress bar and the empty view. Its listener
  (`ui/tasks/TasksFragment.kt:320-325`) calls `viewModel.retry()`, which is
  `taskRepository.getActiveTasksOnce()` with the result discarded (`TasksViewModel.kt:842-844`) — the list is
  driven entirely by the `combine` collector (`:180-216`). **The gesture refreshes nothing on screen and never
  touches the cloud.**
- One `ItemTouchHelper` callback, on the same screen (`TasksFragment.kt:161-198`): vertical drag only, swipes
  disabled (`makeMovementFlags(UP or DOWN, 0)`, `:168`), `isLongPressDragEnabled() = false` (`:188`), order
  persisted in `clearView` → `persistVisibleOrder()` (`:190-194`, `:201-206`).
- Drag starts only from a handle (`item_task.xml:92`, `TasksAdapter.kt:118-125`), gated on
  `reorderEnabled = sortOrder == MANUAL` (`TasksFragment.kt:248`) and hidden otherwise (`TasksAdapter.kt:403`).
- The hand-rolled horizontal swipe panel is separate from ItemTouchHelper (`TasksAdapter.kt:148-223`), with an
  axis test at `:173` (`abs(dx) > abs(dy) * 1.5f`) and `requestDisallowInterceptTouchEvent(true)` at `:178`.
- **There is no drag-vs-refresh conflict handling.** The handle's `ACTION_DOWN` never calls
  `requestDisallowInterceptTouchEvent`, nothing sets `swipeRefreshLayout.isEnabled`, and there is no
  `OnItemTouchListener`. The only reason it usually works is that the horizontal panel's call happens to
  propagate up the parent chain — which says nothing about a vertical drag.

## 17.4 Modules to touch

`ui/tasks/TasksFragment.kt`, `TasksAdapter.kt`, `TasksViewModel.kt`, `fragment_tasks.xml`,
`data/repository/TaskRepository.kt` (a real refresh), and a new named callback class if the anonymous one
grows.

## 17.5 Data changes

None. This stage changes which gesture means what, not what is stored. It does change *when* a write happens:
`persistVisibleOrder` currently writes on `clearView` for the whole visible list.

## 17.6 Risks

1. **A refresh that becomes a sync** starts writing to the network from a pull gesture — only with the
   reader's account state, and only after Stage 13 decides what "sync" means on this screen.
2. **Order thrash**: a drop that rewrites `order` for every visible row (today's behaviour) is O(n) writes per
   gesture; with Stage 16's subtree moves it becomes worse.
3. **A suppressed refresh that never comes back**: if the drag disables the layout and the touch stream ends
   abnormally (ANR, backgrounding, a configuration change mid-drag), the screen can be left with pull-to-refresh
   dead. Test the interrupted drag.
4. **Accessibility**: drag-only reordering excludes a screen-reader user. The stage is not done until reorder
   is reachable without a drag (Stage 10's finding applies here).

## 17.7 Dependencies

Independent of 13–16, but the "what does refresh do" answer depends on 13 (cloud) and the write cost depends
on 16. Recommended after both, or scoped to "refresh = reload local data, visibly" with the cloud question
left open and stated.

## 17.8 Implementation phases

1. **Decide and write down what refresh means**, then make it visibly do that.
2. **Prove the conflict exists**: an instrumented test that drags while the layout is enabled and asserts no
   refresh fires — it should fail today, which is the point.
3. **Fix the gesture arbitration** in one place, with the interrupted-drag cases.
4. **Lift-and-drop affordance**: a shadow or scale on the dragged row, a visible gap, one commit.
5. **A non-drag reorder path** for keyboard and screen-reader users.
6. **Device proof**: a recorded touch stream, frames before and after, and a frame showing the lifted row.

## 17.9 Acceptance criteria

1. Pull-to-refresh produces a visible change (spinner → data → spinner gone), or the gesture is removed.
2. A vertical drag from the handle never fires a refresh, asserted by an instrumented test that fails before
   the fix.
3. A drag interrupted by backgrounding leaves pull-to-refresh working, tested explicitly.
4. The dragged row is visibly different during the drag, in a photograph.
5. Reordering is achievable without a touch drag at all.
6. One drop performs one write batch, measured by a query count or a DAO call counter.

## 17.10 Tests

`TasksRefreshInstrumentedTest` (the visible change and the arbitration), `TaskDragConflictTest` (interrupted
drag), `TasksOrderingTest` extension (one batch per drop), `TaskCardReorderAccessibilityTest` (the non-drag
path).

## 17.11 Documentation

`docs/UI_UX_AUDIT.md` gains the gesture finding and its resolution; `docs/TIME_TRACKING.md` untouched; the
gesture table added to `docs/HIERARCHY.md` if Stage 16 lands first.

## 17.12 Definition of done

Both gestures do something a reader can see and describe, neither cancels the other, and the interrupted
cases are tested rather than assumed.

---

# Stage 18 — Bulk selection that selects what the reader means

## 18.1 User problem

Selection mode exists and can do one thing: delete. A reader who long-presses a row to "finish everything in
this topic" or "archive everything overdue" finds no such affordance, and the Select-all button selects only
what is currently visible, which is not what the word "all" says.

## 18.2 Target UX

- Select-all is a toggle with two states and a count that matches the label: "Select all visible" vs "Select
  all N in this scope".
- A scope dropdown names the scope the selection applies to: this topic, this category, this search result,
  this period, the whole account.
- Bulk operations beyond delete: complete, archive, move to topic, change category, set priority — each with
  an undo or a confirmation that says the count.
- The bar says what will happen before it happens.

## 18.3 What already exists

- State: `TasksViewModel` holds it as `selectionFlow: MutableStateFlow<Pair<Set<Long>, Boolean>>`
  (`:176-177`), joined into the render pipeline by `filterSignals` (`:180-182`) and surfaced as
  `TasksState.selectionMode` / `selectedIds` (`:89-90`).
- API: `enterSelection` (`:355-358`), `exitSelection` (`:360-363`), `toggleSelection` (`:348-353`),
  `selectAllVisible` (`:365-372`), `toggleGroupSelection` (`:374-388`), `deleteSelectedNow` (`:397-402`),
  `deleteSelected` (`:404`), `deleteSelectedFor` (`:416`).
- UI: `res/layout/bar_selection.xml` — `selectionCount` (`:31`), `selectAllButton` (`:39`),
  `deleteSelectedButton` (`:48`), `cancelSelectionButton` (`:57`). The bar **replaces** the header rather than
  stacking on it (`TasksFragment.kt:251-259`), and delete asks for confirmation (`:362-371`).
- **Bulk operations: delete only.** Grep for `selectedIds` finds no other consumer. The single-row lifecycle
  operations exist and take one id — `startTask` (`:428`), `pauseTask` (`:430`), `resumeTask` (`:432`),
  `completeTask` (`:435`), `toggleWork` (`:457`), `stopWork` (`:473`) — so the bulk versions are a widening of
  existing, tested code paths, not new behaviour.
- **Scope state that a dropdown can reuse already exists**: `Filters(categoryId, status, search, sortOrder)`
  (`:153-159`) with setters `:334-344`, `NO_CATEGORY_ID = -1L` (`:847`), and the chips that drive them
  (`renderCategoryChips` `:274-304`, status chips `:331-339`, `filtersChip` `:91`). There is **no period
  filter and no group filter** in `Filters` — grouping is a render-time partition (`:268-284`), and the date
  ranges that exist live on other screens (`dialog_report_range.xml`, `dialog_work_range.xml`,
  `ReportFragment`).
- `selectAllVisible` is visible-only by construction: it maps over
  `_state.value.items.filterIsInstance<TaskRow>()` (`:365-372`), so a collapsed group's tasks and anything
  outside the filter are not selected — while the button's content description says "Select all"
  (`bar_selection.xml:43`).

## 18.4 Modules to touch

`ui/tasks/TasksViewModel.kt`, `TasksFragment.kt`, `TasksAdapter.kt`, `bar_selection.xml`,
`data/repository/TaskRepository.kt` (bulk lifecycle), `data/local/TaskDao.kt` (bulk updates in one
transaction), `ui/tasks/` for the scope control, and the strings for every new label.

## 18.5 Data changes

No migration. Two existing columns matter: bulk delete already sets `sync_state = 'DELETING'` and
`group_id = NULL` in one `@Transaction` (`TaskDao.softDeleteTasks` `:210-215`) — the new bulk operations need
the same care, because a bulk write that skips `sync_state` desynchronises the account silently. Bulk
complete must set `completed_at` the way `archiveTask` (`:198-202`) does, or the work history and the report's
completion figures will disagree.

## 18.6 Risks

1. **"All" that is not all.** The current mismatch between the label and the behaviour is the bug this stage
   exists to fix; the fix must not become "select all in the account" silently, which for a large list means a
   bulk delete the reader did not mean.
2. **Bulk complete on a recurring series** — completing an instance vs completing the series are different
   acts, and `RecurrenceDao`/`Task.series_uuid` (`Task.kt:107`) make that reachable. Decide and test.
3. **Bulk archive vs the archive's wording.** Stage 8a recorded that `getArchivedTasks` filters only
   `archived = 1 AND is_deleted = 0` — no status filter — so an archived-unfinished task is already labelled
   "Completed" on the archive screen. Bulk archive multiplies that case.
4. **Selection that survives a filter change**: ids kept while the rows disappear leave an invisible
   selection. The bar must say what is selected, or the change must clear it.
5. **Undo.** A destructive bulk operation without an undo needs a confirmation that names the count; today's
   dialog does (`confirm_delete_selected_message`, `TasksFragment.kt:362-371`) and every new one must too.

## 18.7 Dependencies

Stage 16 (selecting a subtree), Stage 14 (labels in both languages), Stage 13 (a bulk write must mark
`sync_state`). Independent of 15 and 17, though 17's drag and this stage's selection share the row's touch
handling — the existing code already disables drag in selection mode at both layers
(`TasksFragment.kt:166`, `TasksAdapter.kt:119`) and that must keep working.

## 18.8 Implementation phases

1. **Make the current behaviour honest**: rename the button's meaning, show the count, and let a second press
   clear it.
2. **Scope model**: add the missing period and group fields to `Filters`, and make the scope the source of
   truth for "all".
3. **Scope dropdown** in the selection bar, fed by the same state the chips use, so the two cannot disagree.
4. **Bulk operations** one at a time, each as a `@Transaction` DAO call with `sync_state` and the timestamps
   set correctly, each with its confirmation copy.
5. **Subtree selection** after Stage 16.
6. **Device proof** for every operation, including the empty and single-row cases.

## 18.9 Acceptance criteria

1. The select-all control's label and its effect agree in every scope, and a test asserts the count it
   selected.
2. Each bulk operation is one transaction: a failure mid-operation leaves no partially updated rows, proven
   against the database.
3. Bulk complete sets `completed_at`; bulk archive sets `archived_at`; both are asserted from the DAO, not
   from the ViewModel.
4. Every bulk write marks `sync_state`, asserted for each operation.
5. A recurring series is handled by a written-down rule, with a test for instance and for series.
6. Changing a filter while rows are selected either keeps the selection visibly or clears it — never leaves
   an invisible selection.
7. Confirmation names the count, and the count is the number of rows the operation then changes.
8. Selection mode still disables drag, still replaces the header, and still leaves the app reachable by a
   screen reader.

## 18.10 Tests

`SelectionScopeTest` (JVM): each scope's membership, including the collapsed-group and filtered-out cases.
`BulkOperationsTest` (JVM + Room): transaction atomicity, timestamps, `sync_state`.
`TasksSelectionInstrumentedTest`: enter by long-press, choose a scope, run each operation, assert the screen
and the database. `SelectionAccessibilityTest`: the bar is readable and operable without a drag.

## 18.11 Documentation

`docs/UI_UX_AUDIT.md` (the "Select all" honesty finding), `docs/BACKUP_AND_SYNC.md` (which bulk writes mark
what for sync), and the selection section of the redesign spec's per-screen acceptance table.

## 18.12 Definition of done

A reader selects a scope by name, performs each bulk operation, sees the count they selected change, and no
operation ever touched a row outside the scope they named — with the database agreeing.

---

# Subsystem cross-impact matrix

Rows are the stages, columns are the subsystems each stage has to re-open. A filled cell means: this stage
changes something there, and the owner of that subsystem must be re-tested when it lands. An empty cell means
no expected change.

| stage | Room schema | Firestore / sync | Backup JSON | Report CSV/PDF | Tasks list render | Settings UI | Strings (both locales) | Design tokens | Device proof |
|---|---|---|---|---|---|---|---|---|---|
| 13 Sign-In | — (uses `firebase_uid`) | ● document schema, watermarks | ○ export gains account state | — | — | ● three account states | ● new copy | — | ● real account |
| 14 Russian | — | — | — | ● delimiter, collation | ● width budgets | ● Language row | ●● everything | ● re-measure 320dp / 1.3 | ● every screen × 2 locales |
| 15 Keep import | ○ if a source key is added | ● imported rows sync | ● importer beside it | ○ imported rows appear | ● topics appear | ● entry point + preview | ● new copy | — | ● the corpus on a device |
| 16 Hierarchy | ●● migration 16→17, parent keys | ● parent field + arrival order | ● parent in the round-trip | ● grouped figures | ●● indent, collapse, move | ○ detail editor | ● controls | ● indent = a spacing token | ● 320dp, 1.3, dark |
| 17 Drag vs refresh | — | ○ if refresh means sync | — | — | ●● gesture arbitration, drag visual | — | ○ refresh copy | — | ● recorded touch stream |
| 18 Bulk selection | — | ● `sync_state` on every bulk write | ○ bulk-deleted rows export | ● bulk state changes figures | ● selection bar, scope control | — | ● scope + confirmation copy | — | ● each operation |

● = expected change; ●● = the stage's central change; ○ = possible or partial, decide in the stage's own plan;
— = none expected.

Two readings of this matrix are worth stating out loud. First, **Stages 16 and 18 both re-open sync and the
backup format**, and neither can do it in the way the other would have chosen — so 16's parent-key decision
(`uuid` string vs numeric id, `Task.kt:100-103`) is the one to make first and write down. Second,
**Stage 14 is the only row that touches every screen**, which is why it must land before the strings of
15–18 are final rather than after.

# What this file is not

It is not a claim of progress. GitHub is where this project is shown to a prospective employer, and the
fastest way to make a portfolio untrustworthy is a document that describes planned work as shipped. The
rule for every stage here, and for the redesign branch it follows: a feature is described as working only
after it has been built, tested, and seen on a device, with the command output in the stage report.
