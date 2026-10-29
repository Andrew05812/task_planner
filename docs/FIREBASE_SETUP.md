# Cloud setup (Google sign-in + Firestore sync)

The app is fully functional **without** any of this: local profiles, tasks, categories,
topics, archive and import/export all work offline. Cloud features stay disabled until the
values below are supplied. No `google-services.json`, OAuth client id or API key is committed
to this repository, and the build never invents one — it uses `NOT_CONFIGURED` placeholders.

## 1. What the app deliberately does not use

* No `com.google.gms.google-services` plugin and no `google-services.json`. Firebase is
  initialised at runtime from `BuildConfig` fields (`data/auth/CloudConfig.kt`). This keeps
  secrets out of the APK asset folder and out of git.
* No deprecated `GoogleSignInClient`. Sign-in uses **AndroidX Credential Manager** with
  `GetGoogleIdOption`, exchanges the Google ID token for a Firebase credential, and treats the
  **Firebase UID (never the email)** as the primary identity.

## 2. Create the Firebase project

1. Firebase Console → Add project.
2. Authentication → Sign-in method → enable **Google**. Enter the support email.
3. Project settings → Your apps → **Android app**:
   * Android package name: `com.taskplanner` (must match `applicationId` in `app/build.gradle.kts`).
   * Register the app. You do **not** need to download `google-services.json` — the four values
     below are enough.
4. From the registered app, copy:
   * **App ID** → `firebase.appId` (looks like `1:1234567890:android:abcdef…`)
   * **API key** → `firebase.apiKey` (an Android API key, `AIza…`)
   * Project ID → `firebase.projectId` (`something-4b7f1`)
5. Credentials → **OAuth 2.0 client IDs** → copy the *Web client* ID
   (`1234567890-xxxx.apps.googleusercontent.com`) → `google.webClientId`.
   This is the **server client id** Credential Manager needs; the Android client id is not used.

## 3. Supply the values locally

**Where a real value may live, and where it may not.** `gradle.properties` is *committed to this repository* —
putting a key there publishes it, and it stays in git history after you remove the line. The three safe places
are `local.properties` (git-ignored, and already on every machine that builds this app because Android Studio
creates it for `sdk.dir`), `-P` flags on a single command, and CI repository secrets.

`firebase.example.properties` at the repository root is the template: it carries the four keys, the shape of
each value, and where in the Firebase console it comes from, and every value in it is `NOT_CONFIGURED`. Copy its
four lines into `local.properties` and fill them in.

```
# local.properties  (git-ignored; never commit a filled-in copy)
firebase.projectId=your-project-id
firebase.appId=1:000000000000:android:0000000000000000
firebase.apiKey=AIza...................................
google.webClientId=000000000000-................................apps.googleusercontent.com
```

Or pass them per build without touching any file:

```
./gradlew assembleDebug -Pfirebase.projectId=... -Pfirebase.appId=... -Pfirebase.apiKey=... -Pgoogle.webClientId=...
```

In CI, store the same four values as repository secrets and pass them with `-P`.

**How a value is found, in order.** `app/build.gradle.kts` reads each key from a `-P` command-line property
first, then from `local.properties`, and falls back to `NOT_CONFIGURED`. The second step is a deliberate detail
rather than an assumption: Gradle's `providers.gradleProperty(…)` does *not* look at `local.properties` (that
file is Android Gradle Plugin's own, not a Gradle property source), so a value written there would be silently
ignored without the explicit read. The order was verified on this tree by setting one key in
`local.properties` and watching the build log name that key as filled while the other three stayed placeholders.

If **any** of `projectId` / `appId` / `apiKey` / `webClientId` stays `NOT_CONFIGURED` — or is blank —
`CloudConfig.isConfigured` is false and the app runs in local-only mode: no Firebase init, no sign-in, no sync work.
The rule is one function of the four values (`CloudConfig.configuredFor`), and `CloudConfigTest` asserts the cases
that matter: all four present, each of the four left as a placeholder in turn, a blank and a whitespace value, and
this repository's own build measured as local-only. Three of four is not a cloud build with a small gap: it is a
build that would initialise Firebase, offer a button, and then fail at the first Google call. The build says which
keys are missing on the console rather than leaving a reader to guess which mode they are looking at. Settings
shows the sign-in row **disabled**, with the reason beside it — the feature exists, this build just cannot use it
yet.

`google.webClientId` is the one of the four that `FirebaseOptions` never asks for — it is the *server* client id,
the one Credential Manager names when it asks Google for an ID token — which is exactly why it was left out of the
gate at first, and why a build with it missing looked ready. A local build has no way to tell the two kinds of value
apart; the code does.

## 4. Firestore

Database → Firestore → create database (production mode, no test rules).

Deploy the rules that ship with this repo (`firebase/firestore.rules`). They grant access only
to `users/{uid}/…` where `uid == request.auth.uid`, require the document id to equal the entity
`uuid`, and require the fields that make each row meaningful (a task's `title` and `updated_at`, a
pattern's `anchor_at` and `cursor_at`, a reminder's `task_uuid` and `offset_minutes`), so a replayed
write cannot overwrite someone else's data and a partial write cannot leave a document that points at
nothing:

```
firebase deploy --only firestore:rules
```

Optional, for CI/Emulator Suite, `firebase.json`:

```json
{
  "firestore": { "rules": "firebase/firestore.rules" }
}
```

## 5. Data layout and sync semantics

```
users/{firebaseUid}/tasks/{uuid}
users/{firebaseUid}/categories/{uuid}
users/{firebaseUid}/groups/{uuid}
users/{firebaseUid}/recurrence_rules/{uuid}
users/{firebaseUid}/reminders/{uuid}
```

* **Stable identity.** Every task, category, group, repeat pattern and reminder carries a `uuid`
  generated locally. It is the document id, so pushes are idempotent and a retry after a dropped
  network cannot duplicate a record. Rows that predate the uuid column get one on their first push,
  and it is persisted before the write, so the retry reuses the same id. A reminder's uuid is derived
  from its task and its lead time, so two devices that both offered the same warning write the same
  document.
* **Cross-device references.** Tasks reference categories, topics and their series by `uuid` in the
  cloud (local numeric row ids never leave the device) and are re-resolved to the local id on pull. A
  pull applies categories, then topics, then patterns, then tasks, then reminders, so a row always
  finds what it points at.
* **Offline queue.** `sync_state` is `PENDING`, `SYNCED` or `DELETING`. Local writes never wait
  for the network. WorkManager (`data/sync/SyncScheduler.kt`) runs a periodic pass and an
  immediate pass, both with a `CONNECTED` constraint, so work enqueued offline is picked up when
  connectivity returns rather than being dropped. Deleted patterns and cancelled lead times are
  queued too, otherwise the other device keeps firing an alarm the user already removed.
* **Tombstones.** Deletes set `is_deleted`/`deleted_at` and are pushed as tombstones. A remote
  tombstone deletes the local row; a remote update of a locally deleted row is ignored, so a
  delete never resurrects data.
* **Conflict rule** (documented once in `SyncAppliers`, the same for all five entity types):
  1. a local row with unsent changes (`sync_state != SYNCED`) wins — an offline edit is never
     silently dropped in favour of a cloud snapshot;
  2. otherwise the newer `updated_at` wins;
  3. deletions propagate as in the previous point.
  A blind last-write-wins merge is deliberately not used, because it would erase offline edits.
* **Incremental pull.** A per-uid watermark (`updated_at` of the last applied document) is kept
  in `SharedPreferences`; the first pass pulls everything, later passes pull only newer docs.
* **What the cloud never sees.** A document holds the entity's own fields: never its local row id,
  never the numeric `user_id`, never `sync_state`. Those three describe one device's storage, and
  putting them on the wire would let a client claim identities or queue positions it does not have.

## 6. Moving existing local data into a Google account

Migration is **explicit and user-visible**, never automatic. After signing in, Settings → Account offers "Move
local data into this account", names the local profile it would move, and says what happens before it happens.

What it moves is **every table that carries a `user_id`** — sixteen of them in schema v16: tasks, categories,
topics, repeat patterns and lead times; the tracking sessions, measured intervals and interval corrections of
time tracking; goals with their task sets and their value-change history; habits with their marks and the
history of correcting a mark; the insight notebook; and the achievements the reader earned. Rows change owner
rather than being copied, so local row ids, uuids and the links between them survive the move.

Three things happen before the first write, and each of them exists because the alternative is losing data:

* **A refusal where two live facts would collide.** A timer still running on the local profile cannot be
  re-tagged onto an account that has one of its own, and an open interval is a number still counting on
  somebody's screen. The app refuses and says "stop the timer first" rather than closing it for the reader.
* **A verified safety copy of the whole database** (`data/auth/ProfileSafetyCopy.kt`). It is the SQLite file
  itself — not the app's five-table backup format — checkpointed out of the write-ahead log, copied into
  `filesDir/safety/`, and checked against the SQLite header it now carries. If any of that fails, the move
  refuses to start. The copy is what makes the next point recoverable.
* **One Room transaction over all sixteen tables.** A failure at any step rolls the whole thing back; when the
  transaction commits, a final count proves no row is still addressed to the profile that was just emptied, and
  a non-zero count is an exception rather than a success message.

Identity, and the one place the merge deletes: a row whose identity — its `uuid`, or where a uuid is not what
makes it the same record, its business key (`achievement_key`; an insight's rule and window; a habit mark's
habit and day; a goal link's goal and task) — the account already holds is the *same record* arriving twice,
which is what a replayed import or an earlier half-finished attach leaves behind. The account's copy survives and
the incoming duplicate is dropped; the safety copy holds the other. Nothing else in this operation deletes a row.

Signing out ends the Google and Firebase session and leaves the reader on the profile they were using: the rows
stay, the profile stays current, and the `firebase_uid` link stays on the row so that signing in again returns to
this profile instead of creating a second one. Sync stops reporting anything but `NotLinked`, and the Settings
screen says that in words rather than letting the status sit silently.

## 7. How to tell it worked

The claims above are each backed by something a reader can run or open; none of them rests on a screenshot of a
button.

| what to check | where it is checked | what it proves |
|---|---|---|
| Every table arrives, and none is stranded | `AccountAttachTest` (JVM, real Room) and `SignInDataPreservationInstrumentedTest` (device) | the handover covers sixteen tables, not five |
| A failure at any single table moves nothing | `AccountAttachTest`, a `RAISE(ABORT)` trigger installed on each table in turn | the transaction is atomic per table, including the tables added in this stage |
| No reference breaks | `PRAGMA foreign_key_check` after the move, both suites | links were re-tagged, not orphaned |
| The snapshot can be read back | `ProfileSafetyCopy.take` reopens the copy and counts it before the handover starts, on both: `ProfileSafetyCopyTest` (JVM) and `SignInDataPreservationInstrumentedTest` (device SQLite) | the copy is a database the app can restore, not a file whose bytes happen to match |
| Data survives a restart and a sign-out | the instrumented test's steps 4 and 5, through a fresh `SessionRepository` | the session, not just the file, still resolves to the reader's rows |
| A second sign-in does not duplicate the person | the instrumented test's step 6 (`linkProfile` with the same uid) | the uid, not the email, is the identity |
| A Google address that a local profile already owns is refused, and that profile is untouched | `AuthRepositoryTest` (JVM) and `SignInDataPreservationInstrumentedTest.aGoogleAddressAlreadyOwnedByALocalProfileRefusesWithoutTouchingIt` (device) | signing in cannot delete the profile that shares the address, which is the case a reader with a Gmail-named local profile meets |
| Settings shows state A honestly | `Stage8aScreensInstrumentedTest.settingsKeepsItsControlsAndExplainsTheAbsentSignIn`, photographed in three configurations | the sign-in row is visible, disabled, and explained on a build with no configuration |

What still requires a real project: the credential itself. `CredentialManager.getCredential`, the Google ID
token and the Firebase exchange are not exercised by any automated test in this repository, because no build of
it has an OAuth Web client id. Until one does, "sign-in works" means "everything a sign-in is *for* works", and
the stage report says it that way.

