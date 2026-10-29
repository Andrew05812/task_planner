# Auth and sync: how the account boundary is drawn

A backend-flavoured read of the two subsystems a signed-in user depends on: what each class is responsible
for, where its authority ends, and what the code refuses to guess. Written for someone reading this
repository to judge engineering rather than to onboard onto it — every claim names a file, and the
"what is proven" table at the end says which of it a test actually exercises and which of it waits on a
Firebase project this repository does not have.

## 1. Responsibility boundaries

| component | owns | does **not** own |
|---|---|---|
| `data/auth/CloudConfig` | whether this *build* can talk to Firebase at all, from four `BuildConfig` fields | any network call; any credential |
| `data/auth/AuthRepository` | the sign-in state machine: credential → Firebase user → local profile, the linking decision, the local→account handover, the end of a session | what a task is; how a row is stored; when sync runs |
| `data/auth/ProfileSafetyCopy` | a verified copy of the database file, and the refusal to proceed without one | the backup file format (that is `BackupRepository`) |
| `data/repository/SessionRepository` | "who is the current profile", as one mutex-guarded code path over `users.is_current` with a SharedPreferences repair anchor | any row outside `users` |
| `data/local/UserDao` | the atomic switch (`@Transaction setCurrentUser`: clear, then set, in place — never re-insert, because `INSERT OR REPLACE` deletes and the delete cascades through thirteen tables) | business rules about identities |
| `data/local/ProfileAttachDao` | the *move* of the eleven tables the handover used to skip, as named SQL statements | deciding what to do when two different live facts collide (that is a refusal, above) |
| `data/sync/SyncRepository` | one serialised push/pull pass over the five synced entity types, its watermark and its status | which profile is current; anything about credentials beyond "is this profile linked" |
| `data/sync/FirestoreSyncStore` | the document shape and the uid namespace | conflict policy |
| `data/sync/SyncAppliers` | the conflict rules, applied to this device's Room database | what the cloud holds |
| `data/sync/SyncScheduler` / `SyncWorker` | when a pass happens (periodic + immediate, `CONNECTED`-constrained) | whether the device has a cloud to talk to |

The line that matters most is the one between `AuthRepository` and `SyncRepository`. Auth decides **identity**
and **ownership of local rows**; sync decides **what this device owes the cloud and what it takes back**.
Neither reaches into the other's storage: sync asks the session for a user id and a `firebase_uid`, and never
writes a user row; auth writes rows and never touches a Firestore document. A bug in one therefore cannot
quietly become a bug in the other's data.

## 2. The sequence a sign-in performs

```
CredentialManager.getCredential(GetGoogleIdOption(serverClientId, filterByAuthorizedAccounts=false,
                                               autoSelectEnabled=false))
  → GoogleIdTokenCredential.createFrom(credential.data).idToken
  → FirebaseAuth.signInWithCredential(GoogleAuthProvider.getCredential(idToken, null))
  → FirebaseUser { uid, email, displayName, photoUrl }
  → AuthRepository.linkProfile(uid, email, name, photoUrl)        ← identity decision, no network
       ├─ getUserByFirebaseUid(uid) → the same profile?           (uid is the anchor, email is display data)
       ├─ getUserByEmail(address) held by another profile? → AddressTaken (and the cloud session is closed)
       └─ otherwise insert (is_current = false) — the session is moved by the caller, not here
  → SessionRepository.setCurrentUser(profile)                     ← the one path that changes "current"
  → SignInOutcome.Success
  → SyncScheduler.requestNow(context)                             ← a full local queue is waiting upward
```

`SignInOutcome` has five names — `Success`, `Canceled`, `NotConfigured`, `EmailOwnedByAnotherProfile`, `Failed` —
and Settings renders each as its own sentence, because "sign-in failed" and "you dismissed the chooser" are
different things for a reader to be told. `EmailOwnedByAnotherProfile` signs the cloud session back out before it
reports, and says whether that sign-out *worked* (`sessionLeftOpen`), because leaving a reader authenticated to
Firebase with no local profile under them would show a signed-in app whose data belongs to somebody else's row —
and claiming "nothing happened" when a live session is standing would be a second lie on top of the first.

The handover is a **separate, explicit** call (`attachLocalDataToAccount`) that the UI must ask for. Nothing in
the sign-in path moves a row.

## 3. Transaction strategy

One Room transaction (`database.withTransaction`) over sixteen tables, in an order chosen by foreign keys
rather than by convenience:

1. categories (merge by uuid, build the survivor map) → 2. topics → 3. repeat patterns **before** tasks, so an
instance arrives with the pattern that produced it → 4. tasks (re-pointed at merged categories on the way) →
5. drop the merged category copies **after** the tasks moved, because `tasks.category_id` is
`ON DELETE SET NULL` and deleting early un-categorises rows the map still needs → 6. reminders → 7. the eleven
tables added in Stage 13 → 8. a count over all sixteen proving nothing is left on the source profile, which
throws and rolls the whole thing back if it is not zero.

Steps 1–6 predate Stage 13 and keep their order; what moved is *inside* step 7's neighbourhood. The rowid links
(`work_sessions.task_id`, `tracking_sessions.task_id`) are re-pointed **after** `moveTrackingSessions` and
`moveWorkSessions` have run, not where they used to sit — before the tables moved. Re-pointing a row that has not
arrived yet searches the account's rows for an owner they do not have yet, updates nothing, and reports success;
the interval then arrives still naming a task row that the merge has deleted. The ordering is asserted by
`AccountAttachTest.a merged task takes its measured intervals to the row that survives`, which is how the bug was
found rather than how it was planned.

Three properties the tests pin rather than assume: **atomicity** (an injected `RAISE(ABORT)` trigger on each of
the sixteen tables in turn leaves both profiles exactly as they were — the JVM test asserts row counts on both
sides for every one of the sixteen positions, *and* that the failure it caught names the table it injected into,
so an unrelated exception cannot be mistaken for the proof), **idempotence** (a second attach moves nothing and
loses nothing), and **referential integrity** (`PRAGMA foreign_key_check` returns nothing after the commit).

## 4. Conflict policy

Identity is what makes two rows "the same record", and which collisions the storage can even present is a fact
about the shipped DDL, not a guess. Measured against `app/schemas/…/16.json`, three situations exist:

* **A shared identity that two profiles can really hold.** `tasks.uuid` and `categories.uuid` carry no unique
  index at all, so one task or category imported into two profiles exists twice. The handover keeps the newer
  copy (`updated_at`), deletes the older, and re-points the links that were made by local rowid —
  `work_sessions.task_id`, `tracking_sessions.task_id`, `categories.parent_category_id` — because those three are
  not foreign keys and would otherwise keep naming a row that no longer exists. This is the deletion the safety
  copy exists to make recoverable.
* **A per-profile slot that two profiles can each legitimately hold.** `achievements (user_id, achievement_key)`
  and `insights (user_id, rule_key, period_from_day, period_to_day)` are unique inside a profile, so two profiles
  can each hold "ten tasks done" or "Thursdays are heavy" for the same window. The account's own row survives and
  the incoming one is dropped — which is the only deletion the handover performs outside a uuid twin, and the
  reason it is the record type the safety copy is taken for. Both are *derived* rows: `AchievementRepository` and
  `InsightRepository` regenerate them from the reader's data on their next pass, so a dropped duplicate is a
  reading the app can arrive at again rather than something a person typed.
* **A slot the table-wide index already guards.** `reminders (task_uuid, offset_minutes)`,
  `tasks (series_uuid, occurrence_at)` and `habit_checks (habit_uuid, day_marker)` are unique across the whole
  table, so two warnings for one task at one minute, or two instances of one series at one instant, cannot be
  written by *anybody* — a second profile could not have created the pair, whatever its own uuid is. Nothing here
  merges them and nothing refuses them; the index is the guarantee. An earlier draft of this document claimed the
  first two were reachable and refused before the write; the refusal it described was dead code, and it is gone
  from both the SQL and this page.
* **A live clock, refused because the app would lose it, not because the index would.**
  `tracking_sessions.running_marker` holds the owner's id and is unique across the table; `work_sessions.open_marker`
  holds the interval's own uuid inside a per-profile index, so moving an open interval would collide with nothing.
  Both are still refused by `openIntervalsOf` before any write: a running row moved to another profile leaves that
  profile answering to two clocks while `getRunning(userId)` is a `LIMIT 1`, and leaves the marker naming a
  profile nobody is signed into. The reader is told to stop it rather than have it stopped for them.
* **A collision the current schema forbids.** `habits.uuid`, `goals.uuid`, `habit_checks.uuid`,
  `work_sessions.uuid`, `insights.uuid` and `achievements.uuid` are unique across the table, and
  `task_groups`, `recurrence_rules`, `reminders`, `work_interval_corrections`, `goal_value_changes` and
  `habit_check_changes` are keyed by uuid outright — so `goal_tasks (goal_uuid, task_uuid)` and
  `habit_checks (habit_uuid, day_marker)` cannot pair up, since both of their columns name a parent the second
  profile could never have written. The two are handled differently, and the difference is the loss: a repeated
  goal link is the same fact twice, so `moveGoalTaskLinks` keeps one and nothing goes away; two marks of one habit
  for one day could carry different correction histories, so `moveHabitChecks` merges nothing — the plain statement
  would be refused by the index, the transaction rolls back, and the app chooses nothing it cannot justify. The
  unreachability is itself under test:
  `AccountAttachTest.the schema says which row identities two profiles can share` reads the live `sqlite_master`
  and fails if a migration ever makes one of those identities per-profile, which is the day those two rules need
  decisions and tests of their own. The case that *is* reachable and is tested is the retired habit mark carrying a
  NULL `day_marker`: SQLite treats two NULLs as distinct, so a rule that folded NULL into a sentinel would delete
  the reader's history of taking days back.

* Email is never an identity. `users.email` is unique and non-null, so it constrains inserts, but the account
  anchor is `firebase_uid`; a Google account with no public address gets `google-$uid@firebase.local` rather
  than a colliding empty string. A sign-in whose address is already owned by a local profile is **refused**: the
  cloud session is closed again, the profile keeps its rows, and the reader is told to rename or sign in
  differently. Taking the address as identity would mean adopting somebody else's profile, and the alternative
  this repository once implemented — an insert that resolves the collision by replacing the row — cascades
  through thirteen tables.
* Sync conflicts are a different rule set, in `SyncAppliers`: an unsent local edit always wins over an incoming
  copy, a remote deletion becomes a tombstone, otherwise the newer `updated_at` wins, and a row belonging to a
  different profile on this device is never rewritten. No field-level merge, no silent last-write-wins.

## 5. Sync schema

```
users/{firebaseUid}/tasks/{uuid}
users/{firebaseUid}/categories/{uuid}
users/{firebaseUid}/groups/{uuid}
users/{firebaseUid}/recurrence_rules/{uuid}
users/{firebaseUid}/reminders/{uuid}
```

The uid is the namespace, so a uid change is a different document tree and nothing may infer "same email, same
tree". A document carries the entity's own fields and never a local row id, a numeric `user_id` or
`sync_state`; cross-device references travel as uuids and are re-resolved to local ids on pull, in the order
categories → topics → patterns → tasks → reminders so a row always finds what it points at. A per-uid watermark
makes the pull incremental. `firebase/firestore.rules` enforces the uid branch, the document-id-equals-uuid
rule and the required fields.

**Coverage today is five of sixteen tables.** Time tracking, goals, habits, insights and achievements move
between local profiles, are in no backup file (format `1.1`) and have no collection or rule. A signed-in reader
therefore does *not* get their measured work on a second device yet; that is the next piece of this roadmap,
and it is written as such rather than implied.

## 6. Data-integrity mechanisms

* `is_current` is set by one mutex-guarded path; the account row is inserted with the flag **false** so two
  profiles never answer "who is current" at once (`getCurrentUser` is a `LIMIT 1` with no `ORDER BY`, so the
  loser of that race is chosen by SQLite).
* A profile switch and a sign-out never re-insert a user row: `INSERT OR REPLACE` on `users` deletes first, and
  thirteen tables answer that delete with a cascade of their own.
* Sign-out ends the cloud session and keeps the local profile selected, because clearing `is_current` *and* the
  repair anchor leaves an app that shows an empty account over a full database.
* Every destructive local operation is preceded by a copy — **two different copies, for two different
  operations.** A replace-import (`BackupRepository.import(replaceExisting = true)`) writes
  `createSafetyCopy()`, a `backups/safety_*.json` of the five entity types, which is exactly the five the replace
  deletes (`BackupRepository.kt:191-198`): the other eleven are left standing, so nothing is owed more than that.
  The handover deletes across the whole profile, so it is preceded by the whole database file instead, and the
  snapshot is *read back* before the operation starts: `ProfileSafetyCopy.take` folds the write-ahead log in,
  counts the profile's rows in the live database, copies the file, compares SHA-256 of source-before /
  source-after / copy, checks the copy's SQLite header (magic, page size, page count against file length), then
  reopens the copy and counts the same rows in it. Only then does the transaction begin. Reopening happens on the
  JVM too — measured, not assumed: Robolectric's legacy SQLite answers a second open of a small file happily, and
  the earlier claim that it "runs out of usable connections" turned out to be a symptom of the *path length*
  limit, which is why the fixture database lives under a short temp directory.
* The fold is judged on the number that carries the guarantee. `PRAGMA wal_checkpoint(TRUNCATE)` answers
  `(busy, log, checkpointed)`, and SQLite defines `checkpointed` as the frames copied into the database file by
  that call. The refusal is therefore `busy != 0 || checkpointed < log` — "a committed frame is still missing from
  the file". Requiring `log == 0` instead reads one implementation's side effect as the rule: on the SQLite that
  ships, a successful TRUNCATE does answer `(0, 0, 0)`, while Robolectric's layer folds the frames in, reports
  `(0, 10, 10)` and leaves the log un-reset — so a strict `log == 0` check refuses a copy that is provably complete.
  The same reading also protects a live app: SQLite documents that a checkpoint which cannot reset the log returns
  with the frames still listed there, and a screen collecting Room `Flow`s is a reader, so `log > 0` on a device is
  an expected state rather than an error — `checkpointed >= log` is the sentence that stays true either way.
* `sync_state` keeps a row's queue position locally and never on the wire; deletes travel as tombstones so a
  deletion cannot be resurrected by an older cloud copy.

## 7. What is proven, and by what

| claim | evidence |
|---|---|
| the handover moves all sixteen tables and strands none | `AccountAttachTest` (JVM, real Room), `SignInDataPreservationInstrumentedTest` (device) |
| atomicity at each of the sixteen steps | `AccountAttachTest.a failure at any single table leaves both profiles exactly as they were` |
| `PRAGMA foreign_key_check` clean after the move | both suites |
| the snapshot is a database the device can open and count | `SignInDataPreservationInstrumentedTest` via `ProfileSafetyCopy.verifyReadable`, and by `take` itself: no handover starts on a copy that has not been reopened and counted |
| the fold is judged on frames written, not on the log being reset | **the measurement, not a test.** Under Robolectric's legacy SQLite every `wal_checkpoint(TRUNCATE)` answers `(0, N, N)` — the frames are folded in and reported, the log is never reset — so no JVM assertion can reach the refusal. What was checked is the consequence: on that layer the copy reopens and answers `SELECT COUNT(*)` with the right rows, which is the state the old `log == 0` rule refused. The refusal itself is exercised only where a checkpoint can actually be held up, which is on a device with readers on the database — `SignInDataPreservationInstrumentedTest` runs a real handover through the same guard |
| which collisions the storage can present | `AccountAttachTest.the schema says which row identities two profiles can share`, read from live `sqlite_master` — the three groups *and* each of the seven slot indexes, so a migration that turns a table-wide slot into a per-profile one fails by name |
| one uuid held by two profiles merges instead of duplicating | `ProfileAttachDao.getTaskOfUser` / `getCategoryOfUser` — the twin has to be read with its owner in the query, because `TaskDao.findTaskByUuid` is a `LIMIT 1` over a rowid-order scan and answers with the *local* row, which hides the twin and leaves the account holding one task twice. Both arms of the merge are under test: `a merged task takes its measured intervals to the row that survives` (the account's copy is newer) and `a task newer than the account's own copy is the one that survives` (the arriving row is, and the reader's later edit is what stands) |
| a clock that is still counting stops the handover before anything is written | `AccountAttachTest.a clock still running on either table stops the handover before anything is written` — asserts both profiles' sixteen counts unchanged, both markers still standing, and the safety folder still empty, because the refusal comes before the copy |
| the reader is told what the move did, per table | `AttachReport`: `moved` carries all sixteen table counts rather than the five the handover used to know, `mergedDuplicates` counts rows folded away, and `repointedLinks` is reported separately so "two links changed their target" is never read as "two more records arrived" (`SettingsViewModel` renders it into `migrate_local_data_moved_format`) |
| a sign-out that could not close the cloud session says so | `SessionEnd` (`Closed` / `NotConfigured` / `Failed(message)`) is what `GoogleSignInTransport.endSession` answers, and `SettingsViewModel.signOut` raises rather than reporting success on `Failed` — the same honesty the stage demands of `SignInOutcome.EmailOwnedByAnotherProfile.sessionLeftOpen` |
| only the three newest snapshots stay on the device, sidecars included | `ProfileSafetyCopyTest.only the newest snapshots are kept, and a snapshot's sidecars go with it` — the test that caught a prune which rebuilt each path without its `.db` extension, deleted nothing, and said nothing |
| a task that merges takes its measured hours with it | `AccountAttachTest.a merged task takes its measured intervals to the row that survives` — this is the test that found the re-pointing running before the table moved, where it could not match anything |
| a row arrives as the record it was, and keeps its place in the sync queue | `AccountAttachTest.the moved rows keep their identities and their place in the sync queue` — per table, both directions: identities that existed are the same ones, no row arrives identity-less (a blank `uuid` is minted on the way in, which is what `categories` does), arriving identities are distinct, and the fourteen tables that carry `sync_state` hand it over unchanged. Load-bearing was proved twice over: re-minting identities on arrival fails this test and three merge tests, and making `moveGoals` write `sync_state = 'SYNCED'` fails **only** this one — no other assertion in the suite watched that column |
| a refused address leaves the other profile intact | `AuthRepositoryTest` (JVM) and `SignInDataPreservationInstrumentedTest.aGoogleAddressAlreadyOwnedByALocalProfileRefusesWithoutTouchingIt` (device, on the shipping unique index) |
| data survives a restart, a sign-out and a second sign-in | the instrumented test's steps 4–6, through a fresh `SessionRepository` |
| Settings renders state A honestly (visible, disabled, explained) | `Stage8aScreensInstrumentedTest.settingsKeepsItsControlsAndExplainsTheAbsentSignIn` |
| `linkProfile`'s branches, including "address belongs to another profile" | `UserIdentityConflictTest`, `AuthRepositoryTest` |
| the credential itself — Credential Manager, the ID token, the Firebase exchange | **nothing in this repository.** No Firebase project and no OAuth Web client id is committed, by design; see `docs/FIREBASE_SETUP.md` §3 and `firebase.example.properties` for the four values that would enable it |

## 8. Review findings ledger

Seven review passes worked over this stage: five independent reviews of the diff as it stood, then a code review
and a silent-failure review of the fixes those five produced. The ledger also carries what the runs found on their
own — an isolated matrix, a device frame, a mutation — because those are findings too, and the ones a reviewer
cannot imagine. Every finding is listed once, with the check that settled it. A review is not a test — the
*Evidence* column names the assertion or the measurement that decides, and where none exists the row says so and
stays open.

| Finding | Reviewer | How it was checked | Status | Evidence |
|---|---|---|---|---|
| A sign-in whose Google address is owned by a local profile ran into the unique index, and the strategy then in force resolved the collision by replacing the row — which thirteen tables answer by deleting everything that profile owns | code review (data safety) | `linkProfile` now reads the address on **both** branches (uid-matched and new) and refuses before any write; the refusal's other half — the cloud session must not be left standing — is answered by `SessionEnd` | FIXED | `UserIdentityConflictTest`, `AuthRepositoryTest`, and on the device, on the shipping index: `SignInDataPreservationInstrumentedTest.aGoogleAddressAlreadyOwnedByALocalProfileRefusesWithoutTouchingIt` |
| The handover moved five tables of the sixteen a profile owns; the other eleven were left pointing at a row the handover deletes | code review (data safety) | per-table counts of all sixteen before and after, on both profiles, plus `PRAGMA foreign_key_check` | FIXED | `AccountAttachTest` (20 tests, counted from its own result XML rather than from memory) and the conservation ledger this stage writes rather than trusting: `build/account-attach-conservation.txt`, sixteen rows, each `1→1` |
| `attachGroups` minted a new identity for every arriving topic, so `tasks.group_id` pointed at a row that no longer existed and two profiles could end up holding the same topic twice | code review (data safety) | topics now move as they are; the link is asserted on the moved task, not on the count | FIXED | `AccountAttachTest` group-link assertions; the reason is written into the function's KDoc rather than left in a commit message |
| One `uuid` held by both profiles produced two rows on the account | code review (data safety) | the twin is read *with its owner in the query* (`getTaskOfUser` / `getCategoryOfUser`), because a `LIMIT 1` over a rowid scan answers with the local row and hides it. Both merge arms under test | FIXED | `a merged task takes its measured intervals to the row that survives`, `a task newer than the account's own copy is the one that survives` |
| Links were re-pointed before the table that owns them had moved, so the re-point could match nothing | silent-failure review | the ordering is the only thing that changed between RED and GREEN | FIXED | `a merged task takes its measured intervals to the row that survives` — this is the test that found it |
| A topic with a blank identity arrived as a row nothing points at | silent-failure review | refusal names the tables that would be stranded, read from the live schema | FIXED | `a topic with no identity arrives with the tasks that name it`; `ProfileAttachDao.strandedTablesOf`. Proved load-bearing by mutation: removing the guard fails exactly that test |
| A verified copy could stop describing the profile before the transaction opened (the window between the two) | code review (data safety) | re-count immediately before the transaction; refuse with the two numbers | FIXED | `a handover will not run against a copy that has stopped describing the profile`, driven through the `takeCopy` seam so the window is reachable from a test. Mutation-proven: removing the guard fails that test and nothing else |
| The snapshot prune rebuilt each path without its `.db` extension, so it matched nothing, deleted nothing, and reported success | test review (my own, against the promise the code makes) | five snapshots kept where three were promised; sidecars survived their database | FIXED | `ProfileSafetyCopyTest.only the newest snapshots are kept, and a snapshot's sidecars go with it` — observed RED with fifteen files named |
| A rejected snapshot left a partial file behind, which the next prune would then treat as a good copy | silent-failure review | `reject()` renames to a `.rejected-<stamp>` stem, and if even that fails it says so in the exception rather than swallowing it | FIXED | `ProfileSafetyCopyTest` rejected-attempt pruning; `residueSuffix` in the message |
| `wal_checkpoint(TRUNCATE)` was judged on `log == 0`, which refuses a copy that is provably complete | code review (data safety) | the refusal is now `busy != 0 \|\| checkpointed < log` — the number that carries the guarantee | FIXED | measured, not asserted: under Robolectric's legacy SQLite every checkpoint answers `(0, N, N)` and the copy reopens with the right row count. Documented in §6 and in the §7 row that says plainly this refusal is only reachable on a device |
| `linkProfile` read the table and then wrote it, outside any transaction, on a column (`users.firebase_uid`) that carries no unique index | code review (data safety) | the read and both writes now sit inside one `database.withTransaction` | FIXED (the race) / DEFERRED (the index) | `AuthRepositoryTest`; the unique index itself needs a schema change — **Stage 17**, migration to v17 |
| A handover that had already committed could be reported to the reader as a failure, because the optional sync that follows it raised | silent-failure review | the report is published before the sync attempt, and a sync failure is rendered as its own sentence | FIXED | `SettingsViewModel.migrateLocalProfileData`, `migrate_local_data_sync_failed` |
| `endSession()`'s result was discarded, so a sign-out that could not close the cloud session told the reader they were signed out | silent-failure review | `SessionEnd` (`Closed` / `NotConfigured` / `Failed(message)`), and the two halves run separately so the report says which one failed | FIXED | `AuthRepositoryTest.a refusal that could not close the cloud session reports the session as still open`; `sign_out_session_left_open` |
| A half-finished sign-in path left a message that promised two things while only one had happened | silent-failure review | `SignInOutcome.Failed(message, sessionProblem)` chooses the sentence that matches what actually closed | FIXED | `sign_in_failed` vs `sign_in_failed_session_open` in `SettingsViewModel.signIn` |
| `removeMergedCategories` deleted a category whose surviving twin had vanished — data loss reported as a clean merge | silent-failure review | throws instead of deleting | FIXED | `AccountAttachTest` category-merge assertions |
| Comments and docs claimed measurements the code could not show (a 9px/13% clip presented as today's cause, `CloudConfigTest` credited with a claim it does not make, a freed row id described as adoptable, slot-index semantics that did not match the schema) | comment review | each claim re-measured or re-read against the source; where it could not be reproduced today it is labelled as the historical case with today's number beside it | FIXED | `fragment_report.xml` (6px of 126 = 4.8% on the forced-asymmetry mutant, `top=0 bottom=126 ratio=1.000` fixed), `CloudConfigTest`, `UserDao`, `ProfileAttachDao` header, `docs/FIREBASE_SETUP.md` §3, roadmap §13.13 |
| The instrumented geometry test asked an AlertDialog through `activity.findViewById`, which cannot reach it, and the same test left a dialog open for its sibling | test review | Espresso window matchers, and `Espresso.pressBack()` in a `finally` | FIXED | `ReportScreenInstrumentedTest.thePeriodRowHoldsBothButtonsInsideItselfAtEveryWidth` |
| `tickingALeadTimeShowsAMarkAndNotOnlyAColour` asserted a runtime permission and told the reader to `adb shell pm grant` it — a command this project's own build file makes useless, because `clearPackageData = "true"` (`app/build.gradle.kts:55`) wipes grants when the instrumentation starts | the isolated per-class matrix, which was the first Gradle-launched run this class ever had | the app is not involved: it asks for the permission correctly (`TaskDetailFragment`), and the failure is at the test's own precondition, before any app behaviour is exercised. The fixture now takes the permission itself with `GrantPermissionRule`, after the wipe; the assertion stayed, with the same strength, as the check that the grant took | FIXED (fixture, not app) | `build/instrumented-gradle/TaskEditorFramesInstrumentedTest.before-permission-rule.log` (11 tests, 1 failure) and `.after-permission-rule.log` (11, 0, `BUILD SUCCESSFUL`). The merged run's 35 failures hid this one for the whole of Stage 6 |
| Nothing in the suite asserted that a moved row keeps its `uuid`, and nothing asserted that it keeps its `sync_state` | the stage's own per-table re-check (section 8 of the brief) | wrote the test, and it disagreed with the code twice before passing: a `Category` is created with a blank `uuid` and the handover mints one on the way in, which the first version of the claim called a lost identity | FIXED | `AccountAttachTest.the moved rows keep their identities and their place in the sync queue`; two mutations, each reverted — see §7 |
| The Settings frame the test wrote showed the button and its explanation, but not the state line the stage added, because `photograph` captures wherever the last *in-scroll* `scrollTo` left the screen (the cloud notice sits outside the scroll and moves nothing) | the device check for this record | a second frame, written at the moment the state line is the thing being asserted; three configurations, from the installed pair, hash-matched against `/data/app` first | FIXED | `docs/images/ui-redesign/after/s13-{393-light-10,393-dark-10,320-light-13}-settings-account.png` |
| `AuthRepository` is 665 lines and holds three responsibilities: sign-in, the handover, and the session | code review | measured, not felt — the handover is ~250 of those lines and has its own DAO, its own snapshot type and its own report | DEFERRED | **Stage 17**, as its own commit with its own tests. Doing it here would have put a refactor with no test of its own inside a data-safety change |
| The merged `connectedDebugAndroidTest` run: 89 tests, 35 failures | code review (data safety), then isolation | one class per Gradle command and per instrumentation process; then two classes combined; then the same two in reversed order; then the database read afterwards | VERIFIED — NOT A BUG | shared process/session state, not a production defect: every class passes alone, the two-class run reproduces both failures, the reversed order passes, and the post-run database has `users` empty while `session_last_user_id = 29` — a fixture that writes global state and a runner that never re-seats the session between classes. Production code was not changed to satisfy an artificially merged run |
| A horizontal row clips its two-line child by 9px / 13% of its height, and Espresso refuses to press it | UI alignment review, then the device | bounds of both children against the row at 320dp, font 1.3, light and dark; and a mutant that forces the asymmetry to show the gate can still fail | VERIFIED — NOT A BUG (the attribute stays) | `android:baselineAligned="false"` holds: measured `top=0 bottom=126 ratio=1.000`; the forced-asymmetric mutant is clipped by 6px of 126 (4.8%) and the historical 9px/13% case is what `isDisplayingAtLeast(90)` refuses. `GeneralClickAction.java:99` → `ViewInteraction.java` → `isDisplayingAtLeast(90)`, read from `espresso-core-3.5.1-sources.jar` |
| `running_marker` / `open_marker` semantics, and a refusal branch that no index could ever trigger | code review (data safety) | read from the shipping schema and the two producers | VERIFIED — NOT A BUG | `running_marker` is the owner's user id (`WorkTrackingStateMachine.kt:165`), `open_marker` is the tracking session's uuid (`WorkTrackingRepository.kt:520`); the slot uniqueness is a semantic rule, not an index constraint, so the dead refusal was removed rather than left as decoration |
| Eleven user-owned tables are still absent from the backup JSON and from Firestore sync (`BackupRepository` writes tasks, categories, topics, patterns and reminders) | code review (data safety) | counted from `AppDatabase.entities` (17 tables, 16 carrying `user_id`) against the backup's own sections | DEFERRED | **Stage 15/16** (backup and sync scope). The README says so under *Known limitations* rather than implying a whole-profile cloud copy |
| `SyncWorker.doWork` maps `NotLinked` to `Result.success()`, so a scheduled sync that never ran reports a clean run to WorkManager | silent-failure review | read of both call sites: `SyncRepository.syncNow` returns `NotLinked` when the profile has no `firebaseUid` | DEFERRED | **Stage 17**. Honest for the signed-out case — nothing to do, not an error — and unprovable-but-silent for the case that should not exist; distinguishing them needs a session state the worker does not read |
| `SettingsViewModel` has no JVM test harness of its own, so its command sequences (sign-in → refresh → sync-now, migrate → publish → sync) are argued from reading | test review | searched the test sources: the state *arithmetic* it depends on is covered elsewhere, the orchestration is not | OPEN | `AccountUiStateTest` (6 methods, the twelve combinations) covers what the screen renders; nothing covers what the ViewModel does between renders. Needs a harness with a fake `AuthRepository` — the next stage that touches this screen owns it |
| Real Google authorisation | — | no Firebase project and no OAuth Web client id exists in this repository, by design | OPEN — BLOCKED | `CloudConfig.isConfigured` is false on every build of this tree; the sign-in row is visible, disabled, and explained. No test seam stands in for the credential |
