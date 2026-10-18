# Task Planner

Offline-first task manager for Android. Tasks, categories, topic groups, an archive and a lifecycle
(`TODO → IN_PROGRESS → PAUSED → DONE`), with optional Google sign-in and cloud sync that never has
to be used for the app to work.

## Build and test

Requirements: JDK 17, Android SDK 34. The Gradle wrapper pins everything else.

```
./gradlew assembleDebug        # build the debug APK
./gradlew testDebugUnitTest    # 89 JVM tests (Robolectric + real Room + real SQLite migrations)
./gradlew lintDebug            # Android Lint
./gradlew assembleDebugAndroidTest   # compile the on-device tests
./gradlew connectedDebugAndroidTest  # run them — needs a device or emulator
```

Firebase is optional. The app builds and runs without `google-services.json`: cloud features stay
disabled and everything is stored locally (see `docs/FIREBASE_SETUP.md`).

## Where your data lives

The database is `taskplanner.db`, opened through Room's default private location
`/data/data/com.taskplanner/databases/`. Android may clear a cache directory at any time, and
earlier builds kept the database in `cacheDir`, which is where the lost-tasks reports came from.

On first start `LegacyDbRescuer` looks for a database in the old locations (`cacheDir`,
`externalFilesDir`, `filesDir`), scores every copy it finds by how many rows it holds and how
recently it was updated, and copies the richest one — together with its `-wal` and `-shm` sidecars —
into the permanent location. Nothing is deleted: the legacy files stay where they were.

Migrations run for every retained schema version (1 → 7) and are covered by tests that execute the
production `Migration` objects against real SQLite files built from the exported schema JSON. There
is deliberately **no** `fallbackToDestructiveMigration()`: if the app meets a schema it does not
know, it refuses to open the database and the rows stay on disk untouched, instead of the user
waking up to an empty list.

## Features

- **Tasks** with priority, due date, category, topic group, free-text search and manual ordering
  via a dedicated drag handle (long-pressing a row starts a selection instead of dragging it).
- **Topic groups** (`TaskGroup`) that collapse and expand. A topic is not a category: one task can
  sit in a topic and belong to a category at the same time.
- **Swipe right to left** on a row to slide its Edit / Delete panel open.
- **Multi-select**: long press, then a mass-action bar with select-all, delete and cancel.
- **Archive**: completing a task archives it rather than removing it; the archive has its own
  search and category filter, restores, and an explicit hard delete that really drops the row.
- **Categories** with a filter chip row on the list screen, including "no category".
- **Backup**: versioned JSON exported and imported through the Storage Access Framework, so the app
  never asks for a broad storage permission. Import previews what is in the file first, then either
  merges by stable uuid or replaces, keeping a safety copy in app-private storage before a replace.
- **Accounts**: Google Credential Manager + Firebase Auth. The Firebase UID, never the e-mail, is
  the identity anchor. Signing out ends the session and deletes no data. A local profile can be
  moved onto a Google account explicitly, from Settings.
- **Sync**: an offline queue of pending writes with stable uuids, timestamps, tombstones and sync
  states, pushed and pulled by WorkManager when a network is available.

## Testing approach

Persistence is never mocked. `AppFixture` builds an in-memory Room database with the production
DAOs, repositories and a real `SharedPreferences` file, so repository, backup, session and UI-layer
tests execute the same SQL and the same code paths as the app. Migration tests read the schema
version they expect straight from the JSON Room exported into `app/schemas/`.

| Suite | Covers |
| --- | --- |
| `AppMigrationsTest` | every 1 → 7 migration against real SQLite, compared with the exported schema |
| `LegacyDbRescuerTest` | rescue selection, WAL sidecars, never overwriting or consuming the original |
| `DataSafetyTest` | the production `DatabaseModule` wiring: private location, rescue + migration end to end, restart, refusal of an unknown schema |
| `SessionRepositoryTest` | session survives restart, no fixed-user fallback, concurrent profile switches |
| `LocalDataMigrationTest` | the local → Google handover: moves rows, keeps links, dedups by uuid, refuses the wrong target |
| `BackupRepositoryTest` | export/import, version and relation validation, merge, replace, tombstone revival |
| `SyncRepositoryTest` | offline queue, uuid assignment, push/pull dedup |
| `TasksViewModelTest` | grouping, filters, search, sorting, selection, lifecycle, per-profile isolation |
| `RoomHarnessSmokeTest` | the test harness itself really executes DAO SQL and emits flows |

`app/src/androidTest` holds the on-device checks (real database location, process restart, Espresso
screen smoke tests). They are compile-checked in CI; running them needs a device, which CI does not
provide.

## Documentation

- `docs/FIREBASE_SETUP.md` — enabling Google sign-in and cloud sync.
- `docs/BACKUP_AND_SYNC.md` — the backup file format, import rules and how sync resolves conflicts.

## Known limitations

- Cloud sync and Google sign-in are untested against a live Firebase project: no project
  configuration is committed, by design. The code paths degrade to local-only when the placeholders
  in `gradle.properties` are unset, and the local paths are what the test suite covers.
- Conflict resolution is rule-based, not a merge: a local row with unsynced edits is never
  overwritten by the incoming copy, a deletion marker beats an older remote update, and a remote
  copy that is not newer than the local one is ignored. Two devices that edited the same field
  concurrently are not merged field by field — the row that still has local edits is pushed last and
  wins upstream.
- On-device (Espresso) tests compile but have not been executed as part of this work.
