# Task Planner

Offline-first task manager for Android. Tasks, categories, topic groups, an archive and a lifecycle
(`TODO → IN_PROGRESS → PAUSED → DONE`), repeating tasks with series management, deadline reminders
that survive a reboot, optional Google sign-in and cloud sync that never has to be used for the app
to work.

## Build and test

Requirements: JDK 17, Android SDK 34. The Gradle wrapper pins everything else.

```
./gradlew assembleDebug        # build the debug APK
./gradlew testDebugUnitTest    # 235 JVM tests (Robolectric + real Room + real SQLite migrations)
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

Migrations run for every retained schema version (1 → 9) and are covered by tests that execute the
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
- **Repeating tasks**: daily / weekly / monthly / yearly and counted variants, chosen weekdays,
  month ends and leap days included, with series pause, resume, finish and delete (see
  *Recurring tasks* below).
- **Reminders**: one or more lead times before a deadline — at the time, 5, 15, 30 or 60 minutes
  before, a day before, or any custom number of minutes — each delivered as its own notification with
  Complete, Snooze and Edit buttons, and armed as an alarm that survives a reboot, a clock change, a
  force-stop and a week with the phone switched off (see *Reminders* below).
- **Backup**: versioned JSON exported and imported through the Storage Access Framework, so the app
  never asks for a broad storage permission. Import previews what is in the file first, then either
  merges by stable uuid or replaces, keeping a safety copy in app-private storage before a replace.
- **Accounts**: Google Credential Manager + Firebase Auth. The Firebase UID, never the e-mail, is
  the identity anchor. Signing out ends the session and deletes no data. A local profile can be
  moved onto a Google account explicitly, from Settings.
- **Sync**: an offline queue of pending writes with stable uuids, timestamps, tombstones and sync
  states, pushed and pulled by WorkManager when a network is available.

## Recurring tasks

A series is stored as one `RecurrenceRule` row plus **one open instance** in `tasks` — the row its
cursor points at — so a daily series that has been running for a year costs two rows, not 365. The
next instance is produced when the current one is finished: completing, archiving or deleting it is
what advances the cursor, and the repair step that creates the missing row is idempotent, so
start-up, a profile switch, a sync pass and the background worker can all call it freely.

- **Engine** (`RecurrenceCalculator`): dates are computed from the series' own anchor by ordinal —
  `day`/`week`/`month`/`year` plus an interval, optionally on chosen weekdays — in the wall-clock
  zone the series recorded when it was created. That is what makes month ends behave (Jan 31 → Feb 28
  → Mar 31), keeps `Feb 29` a yearly series instead of dropping it, and returns a spring-forward
  `02:30` series to `02:30` the day after the clock jumps. Moving the device to another zone does not
  move an existing series.
- **Stable identity**: an instance uuid is derived from `(series, scheduled instant)`, and a unique
  `(series_uuid, occurrence_at)` index refuses a second copy, so a retry, a restart or another device
  that produced the same occurrence first cannot duplicate the task. Materialisation inserts with
  `IGNORE` — an instance the user already edited is never overwritten.
- **In the editor**: presets (daily, every N days, weekly, biweekly, chosen weekdays, monthly, every
  N months, yearly, custom counts), the interval and day-of-month fields only appear when the chosen
  preset uses them, and the screen always states which tasks a change applies to — *"Only this task"*
  moves that one instance and leaves the series schedule alone, *"This task and all following ones"*
  re-anchors the series. A running series can be paused, resumed, stopped after the current task, or
  deleted together with the instances it still owes; completed history in the archive is never taken.
- **Local work**: `RecurrenceRefreshWorker` keeps series producing their next task while the app is
  closed, with no network constraint and no cloud dependency, and Room stays the source of truth.

## Reminders

A reminder is a row, not an alarm. `reminders` (schema version 9) holds one row per
**(task, lead time)** pair — the uuid is derived from that pair and a unique index refuses a second
copy — so pressing the same chip twice, importing a file twice or pulling a row another device
already wrote all land on one reminder instead of producing two notifications for the same minute.

- **What can be reminded**: a lead time counts back from a deadline, so a task with no date cannot
  hold reminders and the editor says so instead of accepting chips that would never ring. A day picked
  without a time is read as the start of that day: the app does not invent a minute for a task the
  user described as "some day".
- **The alarm is only a wakeup** (`ReminderScheduler`): every pending row gets its own AlarmManager
  slot, `setExactAndAllowWhileIdle` when the platform grants exact alarms and `setAndAllowWhileIdle`
  when it does not — later, but never dropped — and the editor tells the user when the device will not
  ring at the minute it names. The instant is read out of the database when the broadcast arrives, so
  a lost alarm costs a delay, not a missing reminder.
- **The notification** (`ReminderNotifier`): a HIGH-importance channel published at start-up, category
  `reminder`, gathered under one group, with three buttons — *Complete* finishes and archives the
  task, *Snooze* moves the same row ten minutes ahead, *Edit* opens the editor. The id a notification
  is posted under comes from the reminder's uuid, so a re-armed reminder replaces its own copy instead
  of leaving a second one in the shade. Tapping the body deep-links `taskplanner://task/<id>`. When
  the notification bar is off nothing is recorded as delivered: the row stays pending and arrives
  after the user turns it back on.
- **What wakes the app** (`ReminderReceiver`, not exported): the alarm, the notification's own
  buttons, and the four broadcasts that invalidate every armed alarm — boot, `TIME_SET`,
  `TIMEZONE_CHANGED`, `MY_PACKAGE_REPLACED`. Each asks for the same idempotent work — repair any
  series instance that is owed, rebuild the alarms from the rows, deliver whatever minute passed — so
  a broadcast the system hands over twice announces nothing twice. A button pressed after the profile
  was switched away drops its notification rather than acting on a task the current profile cannot
  see.
- **The safety net** (`ReminderSweepWorker`): a six-hourly `KEEP` periodic pass, scheduled from
  `MainActivity.onCreate`, that re-arms what the database says is pending. The list screen runs the
  same pass whenever a profile becomes active, so opening the app after a lost alarm repairs it there
  and then instead of waiting for the next sweep. A reminder whose minute
  went by more than an hour ago is retired instead of posted: a phone that was off since Tuesday does
  not owe the user Tuesday's notifications on Thursday.
- **One announcement per (deadline, lead time)**: saving a task again does not repeat a notice the
  user already had, moving the deadline to a future date reminds again for that date, moving it back
  into the past does not, and un-ticking a chip takes its alarm off the clock rather than leaving it
  armed.
- **Silencing**: completing, archiving or deleting a task cancels its alarms; restoring it puts the
  same lead times back on the deadline it has then. A finished instance of a series hands its lead
  times to the next one, so "every day at nine, warn me a quarter before" does not go quiet after the
  first day.
- **Isolation**: a reminder carries the profile that owns its task, and the delivery query joins on
  that task, so a second profile is never reminded of the first one's errands and a reminder whose
  task row has disappeared is never announced.

## Testing approach

Persistence is never mocked. `AppFixture` builds an in-memory Room database with the production
DAOs, repositories and a real `SharedPreferences` file, so repository, backup, session and UI-layer
tests execute the same SQL and the same code paths as the app. Migration tests read the schema
version they expect straight from the JSON Room exported into `app/schemas/`. Neither is the
reminder engine: its tests arm and post through Robolectric's real `AlarmManager` and
`NotificationManager` shadows and read the result back from them, because "the row exists but nothing
was scheduled" is exactly the bug a fake would hide.

Everything runs on the thread that owns the test: the fixture pins Room's executors and hands
`SessionRepository` an unconfined scope, because Robolectric keeps SQLite connection handles per
thread, so a background job that outlives its test surfaces as a failure in whichever test runs next.

| Suite | Covers |
| --- | --- |
| `AppMigrationsTest` | every 1 → 9 migration against real SQLite, compared with the exported schema |
| `LegacyDbRescuerTest` | rescue selection, WAL sidecars, never overwriting or consuming the original |
| `DataSafetyTest` | the production `DatabaseModule` wiring: private location, rescue + migration end to end, restart, refusal of an unknown schema |
| `SessionRepositoryTest` | session survives restart, no fixed-user fallback, concurrent profile switches, switching keeps what a profile owns |
| `LocalDataMigrationTest` | the local → Google handover: moves rows, keeps links, dedups by uuid, refuses the wrong target |
| `BackupRepositoryTest` | export/import, version and relation validation, merge, replace, tombstone revival |
| `SyncRepositoryTest` | offline queue, uuid assignment, push/pull dedup |
| `RecurrenceCalculatorTest` | month ends, leap days, weekday blocks, DST shifts, zone independence, year rollover, monotonic long runs |
| `RecurrenceRepositoryTest` | one open instance per series, idempotent repair, catch-up after a week away, pause / finish / delete, per-profile isolation |
| `TasksRecurrenceTest` | the editor's repeat plan end to end: starting a series, "only this task" versus "this and following", refused date clashes, series controls |
| `RepeatPresetTest` | the presets the user reads and the rule fields that are stored translate into each other both ways |
| `ReminderIdentityTest` | the (task, lead time) identity, the trigger it produces, the notification and alarm slots that follow from it |
| `ReminderSchedulerTest` | a future row on the clock for its own minute, a past one not armed, exact alarms withheld still armed later, one reminder never borrowing another's slot |
| `ReminderNotifierTest` | the channel, the text and category colour, the three separate button intents, the deep link, and nothing claimed as delivered when the bar is off |
| `ReminderRepositoryTest` | lead times written, kept, retired and revived against the deadline a task has now; which profile's rows are armed |
| `ReminderDeliveryTest` | one announcement per (deadline, lead time), the late grace, snooze, silencing by finish / archive / delete, per-profile isolation |
| `ReminderReceiverTest` | the alarm and button broadcasts, the reboot / clock / update rebuilds, and the series instance a boot has to produce before arming it |
| `TasksReminderTest` | the editor's reminder plan end to end: chips stored, a lead time without a date refused, the bell state, the exact-alarm warning, and the alarms opening the list puts back |
| `TaskCardReminderTest` | the bell badge on a list row, including the narrowest screen the app supports |
| `ReminderPresetTest` | the chips and the single custom field translate to stored offsets and back |
| `TaskDetailLayoutTest` | the editor inflates under the app's theme, keeps the recurrence detail collapsed, and lays out inside 320dp |
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
- Recurrence is local-only so far. Instances travel as ordinary tasks, but the series rule itself is
  not in the backup file and not pushed to Firestore yet, so a second device or a restored backup
  gets the tasks that were already produced and not the schedule that makes more of them. Export,
  import and sync of `recurrence_rules` are the next step.
- The background recurrence worker is exercised on a device only: the JVM test process has no
  initialized `WorkManager`, so its `Application`-level scheduling is not reachable from a unit test.
  The work it performs — `ensureOpenInstances()` — is fully covered by the repository and UI suites.
- The same applies to the reminder sweep worker and to `ReminderReceiver.onReceive`: what they *ask
  for* (`rescheduleAll`, `deliverDue`, the button handlers) is covered against the real alarm and
  notification shadows, but the `WorkManager` schedule and the Hilt entry point that `onReceive` uses
  need a device.
- Reminders are local so far. A row carries a sync state and a tombstone, and a sync pull rebuilds the
  alarms from whatever the other device left in the database, but reminders are not in the backup file
  and have no Firestore documents yet, so a second device gets the tasks and not the lead times set on
  them. Export, import and cloud documents for `reminders` come with the integration stage, together
  with the same step for `recurrence_rules`.
- Restoring an archived instance of a series from the archive leaves it open next to the instance the
  series produced afterwards. Nothing is lost and the schedule is unaffected; the older row simply
  becomes an ordinary task of that series.
