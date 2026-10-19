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

Add them to `gradle.properties` (already git-ignored for secrets — never commit real keys):

```
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

If any of `projectId` / `appId` stays `NOT_CONFIGURED`, `CloudConfig.isConfigured` is false and
the app runs in local-only mode: no Firebase init, no sign-in button action, no sync work.

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

Migration is **explicit and user-visible**, never automatic. After signing in, Settings →
Account offers "Move local data into this account". It runs in a single Room transaction:
tasks, categories, topics, repeat patterns and lead times of the selected local profile are
re-tagged with the account's user id, verified, and only then removed from the source profile.
Cancelling leaves the local profile exactly as it was.

Signing out clears the session only. No stored task, category, topic, pattern or reminder is deleted
by signing out.
