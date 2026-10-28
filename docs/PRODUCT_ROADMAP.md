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
- Depth is bounded and stated (four levels, matching the Keep corpus), not unlimited and broken at the
  fourth.

## 16.3 What already exists

- **Schema space is already paid for on categories and entirely unbuilt.** `Category.parent_category_id`
  exists (`data/model/Category.kt:45-46`) with a self-FK `ON DELETE SET NULL` (`:18-22`), an index, and a real
  migration (`AppMigrations.kt:67-81`). Two DAO readers exist — `getRootCategories` (`CategoryDao.kt:25-29`)
  and `getSubcategories` (`:31-35`) — and **nothing in the app calls them**; the matching
  `CategoryRepository` methods (`:25-32`) are dead code. No writer ever sets a non-null parent, and the
  string `label_parent_category` ("Parent Category", `strings.xml:36`) is referenced by no layout and no
  Kotlin file.
- **`TaskGroup` has no parent column at all** (`TaskGroup.kt:27-58`), and **`Task` has no parent, depth,
  level or indent column** (`Task.kt:47-158`).
- The hierarchy that is rendered is an illusion of order: `TasksViewModel.render` (`:266-287`) emits a
  `GroupHeader` then its tasks by `groupId` string equality, and everything not in a group as a bare row
  (`:285-287`).
- Ordering primitives exist and are usable: `tasks.order` (`:141`), `TaskDao.updateTaskOrder` (`:190-191`),
  `updateTaskOrders` (`:193-196`), `TaskRepository.reorderTasks` (`:156`), `GroupDao.nextSortOrder` (`:30-34`).
- **A known ordering defect this stage inherits**: `saveVisibleOrder` (`TasksViewModel.kt:594-603`) re-indexes
  `0..n` over the *filtered* subset, so a drag while a filter is active can collide with rows outside it, and
  `onMove` only requires equal view types (`TasksFragment.kt:178`), so a row can be dragged across a group
  boundary. Any hierarchy work must fix this first or inherit it into a deeper tree.
- The row that would show an indent, `res/layout/item_group_header.xml`, has a fixed `marginStart="8dp"`
  (`:7`) and `paddingHorizontal="12dp"` (`:18`) with nothing depth-driven.

## 16.4 Modules to touch

`data/model/Task.kt`, `data/model/TaskGroup.kt`, `data/local/AppDatabase.kt` (version 17),
`data/local/AppMigrations.kt`, `GroupDao`, `TaskDao`, `GroupRepository`, `TaskRepository`,
`ui/tasks/TasksViewModel.kt` (render, move, indent), `TasksAdapter.kt`, `TasksFragment.kt`,
`item_task.xml`, `item_group_header.xml`, `ui/tasks/TaskDetailFragment.kt`, plus the category screens if
categories join the same model.

## 16.5 Data changes

Migration **16 → 17**, additive and non-destructive, in the style the repo already uses (a table rebuild with
the old rows copied, as `MIGRATION_2_3` does):

- `task_groups.parent_uuid` (nullable, self-FK, index on `parent_uuid`) — the nesting key, matching the
  existing `uuid`-as-primary-key convention.
- `tasks.parent_uuid` or `tasks.parent_task_id` (nullable) — decide once, and note that `tasks.group_id`
  already stores a *uuid string* while `category_id` stores a *numeric id*; the inconsistency is documented at
  `Task.kt:100-103` and this stage has to pick a side deliberately.
- A depth invariant enforced in code, not in SQLite: a cycle check on every move. SQLite cannot express
  "no ancestor of yourself" and a recursive CTE guard is a per-query promise.
- Backfill: every existing row stays a root. Nothing is inferred from titles or from order.

## 16.6 Risks

1. **A move that creates a cycle** makes a subtree unreachable and the list render loop forever. Test it.
2. **The flatten-and-reindex defect above** gets worse with depth: a partial re-index across three levels can
   interleave siblings of different parents.
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

Blocks Stage 15's nesting phase. Needs Stage 14's string discipline for the new controls. Interacts with
Stage 17 (dragging a subtree) and Stage 18 (selecting a subtree).

## 16.8 Implementation phases

1. **Fix the ordering defect first**, with a failing test: reorder while filtered, and assert rows outside the
   filter keep their order.
2. **Migration 16→17** with a schema-equality test and a round-trip on a copied database file.
3. **Model + repository**: parent key, cycle guard, move-subtree, delete-with-children policy.
4. **Rendering**: depth-driven indent as a token (`spacing_*`), a chevron per level, and a bounded depth.
5. **Controls**: indent/outdent in the editor and on the row; a long-press move for whole subtrees.
6. **Carry it everywhere**: backup JSON, Firestore fields, CSV/PDF report grouping.
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
