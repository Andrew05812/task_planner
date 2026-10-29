# Backup format and sync rules

## The file

A backup is one JSON document written or read through the Storage Access Framework: the app only
ever receives a content uri it does not resolve, so no storage permission is requested.

```json
{
  "formatVersion": "1.1",
  "appVersion": "1.0",
  "exportedAt": 1730000000000,
  "data": {
    "tasks": [...], "categories": [...], "groups": [...],
    "recurrenceRules": [...], "reminders": [...]
  }
}
```

Every array holds the entity rows themselves, with the same column names Room uses (`user_id`,
`due_date`, `start_time`, `cursor_at`, `offset_minutes`, `sync_state`, …). `recurrenceRules` are the
repeat patterns and `reminders` the lead times, both added in 1.1 together with the planned window a
task can carry (`start_time`, `end_time`, `estimated_minutes`).

**Never inside the file:** passwords, Firebase or Google tokens, the Firebase UID, `google-services.json`
contents, avatar urls, or anything else that authenticates a person. The exported rows belong to one
profile; the identity of that profile is not part of the payload. Export writes tasks, categories,
groups, repeat patterns and lead times of the signed-in profile only.

Rows that predate stable identities are given a uuid **while exporting**, and that uuid is written
back to the local row too. Without it a backup could not be recognised on re-import, so every
restore would add a second copy of the same task.

## Version handling

`formatVersion` is `major.minor`. An import accepts the same major version with a minor not newer
than the app's own (`1.1` today), and refuses anything else with `BackupUnsupportedVersionException`
rather than guessing at fields it does not know.

A `1.0` file stays readable: `recurrenceRules` and `reminders` default to empty arrays instead of
breaking the parse, so restoring a backup made before Stage 2 works and simply restores no repeat
patterns and no lead times.

## Validation before anything is written

`BackupRepository.readAndValidate` rejects a file that:

- cannot be parsed as JSON in this shape, or is empty;
- repeats an entity uuid (a blank uuid is allowed — it marks a row written before identities
  existed — but no identity may appear twice, because the import matches rows by uuid);
- has a category whose `parentCategoryId` points at a category that is not in the file;
- has a task whose `categoryId` or `groupId` points at a row that is not in the file;
- has a reminder that names no task, or names a task that is not in the file — a lead time without
  its task cannot fire and cannot be re-armed, so it is refused instead of imported loose;
- has two reminders for the same lead time on the same task.

A task whose `seriesUuid` is absent from the file is **not** refused: deleting a series leaves the
instance it already produced, so such a row comes back detached from the pattern instead of being
thrown away with it.

Only after validation does the screen show its preview (counts of tasks, categories, topics, repeat
series, lead times and archived rows) and ask for merge or replace.

## Import

Merge (`replaceExisting = false`) matches by uuid, scoped to the profile being imported into:

| Situation | Result |
| --- | --- |
| uuid unknown to this profile | inserted as a **new local row** with a fresh numeric id |
| uuid already owned by this profile | the newer `updatedAt` wins; the older copy is updated in place |
| uuid owned by a **different** profile | the row is neither stolen nor linked: tasks keep no reference to it |
| row was deleted locally, now re-imported | the tombstone is revived instead of creating a second row |

A lead time is matched by the pair it is built from — its task and its offset — rather than by the
uuid in the file, and a series link is only kept when the pattern is in the file and belongs to this
profile; otherwise the task comes back as a single occurrence.

Numeric ids in the file mean nothing on another device, so they are never inserted directly:
inserting a file's id would REPLACE whatever unrelated local row happened to own that id. Category
parents are imported first (in several passes when nesting is deep) so a child can be re-pointed at
its new local row. Repeat patterns are imported before the tasks that point at them.

Replace (`replaceExisting = true`) empties the profile first, then runs the same merge. It empties
only that profile's rows — tasks, categories, topics, patterns and lead times — and before touching
anything it writes a **safety copy** of the current data to
`filesDir/backups/safety_<timestamp>.json`, so a mistaken replace is recoverable.

Both modes run the whole import inside one Room transaction: on any failure everything rolls back,
and a half-imported profile can never remain. After it commits, the open instances of every restored
pattern are materialised and every restored lead time is put back on the clock — those two steps arm
alarms and post notifications, so they deliberately run outside the transaction rather than holding
it open while they do.

## Sync

Firestore layout: `users/{uid}/tasks|categories|groups|recurrence_rules|reminders/{uuid}`, where
`{uid}` is the Firebase Auth uid — never the e-mail. `firebase/firestore.rules` allows read, write
and delete only inside the caller's own uid branch, and requires a created or updated document's id
to equal its own `uuid` field plus the fields that make the row meaningful — `title` and
`updated_at` for a task, `anchor_at` and `cursor_at` for a pattern, `task_uuid` and `offset_minutes`
for a lead time — so a replayed write from another account cannot overwrite or forge records, and a
partial write cannot leave a document that names no task. Deploy with
`firebase deploy --only firestore:rules`.

What a document contains is decided by the `SyncedTask` / `SyncedCategory` / `SyncedGroup` /
`SyncedRule` / `SyncedReminder` types, not by the Firestore layer, so the format is readable without
a project to push it to. A document carries the entity's own fields — for a task that now includes
`start_time`, `end_time`, `estimated_minutes`, `series_uuid` and `occurrence_at` — and never a local
row id, a numeric `user_id` or the `sync_state` of the device that wrote it.

Offline first: every local change is stored with `sync_state` (`PENDING`, `SYNCED`, `DELETING`, …)
and a `uuid`; WorkManager pushes and pulls when a network is available, and passes queued while
offline run once connectivity returns instead of being dropped. Deleting a row keeps it as a
tombstone (`is_deleted`, `deleted_at`) so the deletion can travel upward instead of the row silently
coming back from the cloud. Deleted patterns and cancelled lead times are queued and pushed too —
otherwise the other device keeps firing an alarm the user already cancelled.

A pull applies categories, then topics, then repeat patterns, then tasks, then reminders, so an
instance always finds the pattern it belongs to and a lead time the task it warns for. Conflicts are
decided by rules, not by a field-level merge — one rule for all five entity types, implemented in
`SyncAppliers`:

- a local row that still has unsynced edits is never overwritten by the incoming copy;
- a remote deletion turns the local row into a tombstone, and a remote update to a row that is
  already deleted locally is ignored;
- otherwise the newer `updatedAt` wins; a remote copy that is not newer is dropped;
- a row that belongs to a different profile on this device is never rewritten.

Two identities can name one occurrence of a pattern: the task the user started the series from keeps
its own uuid, while later instances are derived from (pattern, scheduled instant). A pull of an
instance whose row already exists is therefore ignored instead of overwriting what the user edited
there. A reminder's uuid is derived from (task, lead time), so two devices that both offered the same
warning write the same document and get one alarm, not two.

`SyncRepository.syncNow()` needs a configured Firebase project. Without `google-services.json` it
reports `CloudDisabled` and changes nothing: rows stay `PENDING` and keep queueing. **No cloud round
trip has been performed from this repository** — what is covered by tests is the document format and
the merge rules above, run against a real Room database.

## Google sign-in over a local profile

`AuthRepository.attachLocalDataToAccount(sourceUserId, targetUser)` is the explicit handover the
settings screen offers. It changes the owner of the source rows instead of copying them, so local
ids, uuids and the links between them survive — across **all sixteen tables that carry a `user_id`**,
which is more than it used to cover: until Stage 13 the handover moved tasks, categories, topics,
repeat patterns and lead times, and left the tracking sessions, measured intervals and their
corrections, the goals with their task sets and value-change history, the habits with their marks and
their correction history, the insights and the achievements behind on the profile the reader had just
stopped being shown (`ProfileAttachDao` is where those eleven now move).

A task the account already has by uuid is deduplicated (the newer copy stays) rather than becoming a
second row with a duplicated identity. A pattern or a lead time needs no such merge: their uuid is the
table's primary key, so one row can only ever have one owner. Of the eleven newer tables, the same is true
wherever `uuid` is unique across the table — which is most of them, and is why they move with one statement
each — and the collisions that remain are the *slots* two profiles can each legitimately hold:
`achievements (user_id, achievement_key)` and an insight's `(user_id, rule_key, period_from_day,
period_to_day)`. Two further slot rules, `goal_tasks (goal_uuid, task_uuid)` and
`habit_checks (habit_uuid, day_marker)`, cannot be reached while their parents are keyed by a table-wide unique
uuid, and they are handled in proportion to what a merge would cost: a repeated goal link is one fact twice, so one
is kept and nothing goes away, while two marks of one habit for one day could carry different correction histories —
there nothing is merged, the statement is refused by the index and the transaction rolls back. The schema fact is
itself under test (`AccountAttachTest.the schema says which row identities two profiles can share`), so a migration
that made one of those identities per-profile fails a test instead of quietly turning a rule into live code.

Two guards run before the first write, and one check after the last. A profile with a timer still
running is **refused** rather than merged, because an open interval is a fact still being written and
its unique index would not allow two on one profile. A verified copy of the whole database file is
written to `filesDir/safety/` (`ProfileSafetyCopy`) — the file, not the five-table backup format — and it is
*read back* before the move starts: the write-ahead log folded in and reported, the copy hashed against the
source, its SQLite header checked for a whole page count, then reopened as a separate database and counted
table by table against the live rows. The identity merge deletes a row, and a deletion is only survivable if
something provably holds the other copy. And when the transaction reaches its end, a count
over all sixteen tables proves nothing is still addressed to the source profile; a non-zero count throws,
which rolls the whole move back.

It refuses to run unless the target is the profile that is currently signed in, and answers
`AlreadyTheSameProfile` for a profile onto itself, which would otherwise wipe it. Signing out ends the
cloud session and leaves the reader on this profile, with the `firebase_uid` link in place so that
signing in again returns to the same row rather than creating a second one.

