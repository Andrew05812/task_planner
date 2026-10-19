# Work-time tracking: what the audit found and what the model is

Written before the first line of Stage 3F code, against `master` at `c701a0a` (schema version 11).

## What already exists

* `Task` carries `started_at` (when the user pressed *start* on the lifecycle), `updated_at`,
  `estimated_minutes`, `status`, `archived_at`, `deleted_at` — and **no `completed_at`**. Nothing in the
  schema records how long work actually took; schema v2 and v3 had `actual_time` and it was dropped in
  v4, so no table today holds a measured interval.
* The task card's play/pause/resume buttons (`item_task.xml`) drive `TaskStatus` through
  `TaskRepository.startTask/pauseTask/resumeTask`. They are lifecycle controls, not a stopwatch, and
  existing tests assert that. Tracking therefore gets its own names and its own rows.
* `SessionRepository` is the **auth** session — who is logged in. It is not touched by this feature and
  no work-time class takes that name; the terminology is `TrackingSession` (a logical work session) and
  `WorkSession` (one continuous measured interval).
* Statistics (`StatisticsViewModel`) loads every row of the profile into Kotlin and counts there. That
  pattern is deliberately not repeated: work-time aggregates are SQL (`docs` below, §Aggregation).
* Time is stored as UTC epoch millis everywhere (`due_date`, `trigger_at`, `created_at`), and every
  translation to a local day runs through `CalendarMath` (`startOfDay`, `toLocalDate`, `dayWindow`).
  Work tracking keeps both conventions.
* Sync is `users/{uid}/tasks|categories|groups|recurrence_rules|reminders/{uuid}`, dirty rows marked by
  `sync_state != SYNCED`, pull conflict = local-unsent wins, else newer `updated_at`
  (`SyncAppliers`). Backup is one JSON document, `formatVersion` 1.1, entity rows with Room column
  names, import deduplicated by uuid scoped to the profile. Both are extended, not bypassed.
* Migrations are explicit 1→11 with no destructive fallback (`AppMigrations.ALL`, registered in
  `DatabaseModule`), and each one is tested against the real previous schema built from
  `app/schemas/…/<N>.json`.

## The model

Two tables, because the spec separates a *logical session* from the *intervals* inside it, and the
intervals must never be rewritten while the state machine has to change in place.

**`tracking_sessions`** — one row per logical session. `uuid` (primary key, stable identity),
`user_id`, `task_id`, `task_uuid`, `task_title` (a snapshot, so history stays readable after the task
row is renamed or physically gone), `state` (`RUNNING` | `PAUSED` | `COMPLETED`), `started_at`,
`stopped_at`, `zone_id`, `running_marker`, `created_at`, `updated_at`, plus the sync columns.
`IDLE` is not stored: no row for the profile means idle.

**`work_sessions`** — one row per continuous interval: `id`, `uuid`, `user_id`,
`tracking_session_uuid`, `task_id`, `task_uuid`, `started_at`, `ended_at` (**NULL while the interval is
open**), `duration_seconds` (NULL until the interval closes), `local_day` (the epoch-day the interval
started on, resolved with the zone in force at that moment), `utc_offset_minutes`, `source`
(`TRACKER` | `MANUAL`), `open_marker`, `clock_anomaly`, `created_at`, `updated_at`, sync columns.

Start 09:00, pause 09:30, resume 10:00, stop 10:45 ⇒ two rows, `09:00–09:30` and `10:00–10:45`,
both tagged with the same `tracking_session_uuid`, 75 measured minutes. The 09:30–10:00 gap is not a
row and is not counted. Stopping a paused session updates only the `tracking_sessions` row — no
zero-length interval is invented to mark the end.

**`work_interval_corrections`** — the audit trail §13 asks for: `uuid`, `interval_uuid`, `user_id`,
`old_started_at`, `old_ended_at`, `new_started_at`, `new_ended_at`, `reason`, `created_at`. Editing a
closed interval appends a correction row instead of losing the original numbers.

Closed intervals are append-only; the only rows ever updated are the open interval (at pause or stop),
the session row (state), and the sync bookkeeping columns.

## How the invariants are held in the database

SQLite supports a partial unique index and Room 2.6.1 does not — `@Index` has no `WHERE` clause, so a
hand-written `CREATE UNIQUE INDEX … WHERE ended_at IS NULL` in a migration would leave the physical
schema different from the schema Room exports, and every fresh install would need a `RoomDatabase`
callback to keep the two in step. The alternative used here is the trick this repository already relies
on for `series_uuid`/`occurrence_at`: a **nullable marker column that is non-NULL only while a row is
open**, put in a plain unique index. SQLite treats NULLs as distinct, so closed rows never collide.

* `tracking_sessions (running_marker)` unique — one `RUNNING` session per profile.
* `work_sessions (user_id, open_marker)` unique — one open interval per profile.

Both are declared on the entity, so they exist identically after a fresh install, after `MIGRATION_11_12`
and after an import. Two parallel `Start` calls cannot both leave a timer running: the second insert or
update fails on the index, and `WorkTrackingRepositoryTest` drives that from two threads against a
file-backed database rather than asserting it from a UI guard.

Foreign keys: `user_id → users(id) ON DELETE CASCADE`, the same shape as `recurrence_rules`. There is
**no** foreign key from an interval to `tasks`, exactly as with `reminders.task_uuid`: a link by local
row id would either delete history when a task row disappears (`ON DELETE CASCADE`, which §9 forbids)
or block the order in which an import and a sync pull insert rows. The task link is kept by both
`task_id` and `task_uuid`, and history carries its own title snapshot.

## Time

`started_at`/`ended_at` are UTC epoch millis; duration is the difference of two instants, never of two
local date strings, so a zone change or a DST transition cannot move or resize a saved interval.
`local_day` is computed once, when the interval starts, with `java.time` and the device zone — which
makes day/week/month aggregation a plain SQL range over integers while still honouring the rule that a
session measured at 00:20 in Berlin belongs to the Berlin day it started on.

The source of truth for the running number is the stored `started_at` plus the already-closed intervals
of the same session, so the timer survives a process kill, a device reboot and a screen nobody opened.
The ViewModel holds no time; it only re-renders. Within one process the display also consults
`SystemClock.elapsedRealtime` through an injected `TimeBase`, so dragging the system clock cannot make
the ticker jump or go backwards. When an interval closes, the recorded duration is the wall-clock
difference; if that difference is negative, or disagrees with the monotonic reading by more than a
minute, the anomaly is stored in `clock_anomaly` and the monotonic figure is used — the value is never
silently clamped to zero. Across a reboot there is no monotonic reading to fall back to, and the honest
statement about that is: after a reboot a live interval is measured by the wall clock alone.

## Aggregation

Every number the screens show comes from SQL over `work_sessions`, filtered by `user_id` and, for
history, by `ended_at IS NOT NULL`; the open interval is surfaced separately and labelled as live
rather than folded into a total silently. Per task, per category (joined through `tasks` and
`categories`, with uncategorized explicit), per day/week/month/arbitrary range over `local_day`,
completed-task count over `completed_at`, average interval length, session count, and a
`GROUP BY local_day` series for the trend. Day-of-week/month boundaries are computed with `java.time`
and passed as epoch-day bounds, so no query loads a whole profile.

## Where it plugs in

`MIGRATION_11_12` adds `tasks.completed_at`, both work tables and their indices — three
`CREATE TABLE`/`CREATE INDEX` statements and one `ALTER TABLE … ADD COLUMN`, no table rebuild, no row
rewritten. Backup goes to `formatVersion` 1.2, importing 1.0 and 1.1 unchanged; the new arrays carry
uuids and never a device-local row id. Sync gains `users/{uid}/tracking_sessions/{uuid}` and
`users/{uid}/work_sessions/{uuid}` with matching rules, and the offline path stays exactly as it is now:
with no `google-services.json` the cloud is never contacted, so nothing here is claimed as a tested
round trip.
