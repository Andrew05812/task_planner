# Backup format and sync rules

## The file

A backup is one JSON document written or read through the Storage Access Framework: the app only
ever receives a content uri it does not resolve, so no storage permission is requested.

```json
{
  "formatVersion": "1.0",
  "appVersion": "1.0",
  "exportedAt": 1730000000000,
  "data": { "tasks": [...], "categories": [...], "groups": [...] }
}
```

`data.tasks`, `data.categories` and `data.groups` are the entity rows themselves, with the same
column names Room uses (`user_id`, `due_date`, `sync_state`, …).

**Never inside the file:** passwords, Firebase or Google tokens, the Firebase UID, `google-services.json`
contents, avatar urls, or anything else that authenticates a person. The exported rows belong to one
profile; the identity of that profile is not part of the payload. Export writes tasks, categories and
groups of the signed-in profile only.

Rows that predate stable identities are given a uuid **while exporting**, and that uuid is written
back to the local row too. Without it a backup could not be recognised on re-import, so every
restore would add a second copy of the same task.

## Version handling

`formatVersion` is `major.minor`. An import accepts the same major version with a minor not newer
than the app's own (`1.0` today), and refuses anything else with `BackupUnsupportedVersionException`
rather than guessing at fields it does not know.

## Validation before anything is written

`BackupRepository.readAndValidate` rejects a file that:

- cannot be parsed as JSON in this shape, or is empty;
- repeats an entity uuid (a blank uuid is allowed — it marks a row written before identities
  existed — but no identity may appear twice, because the import matches rows by uuid);
- has a category whose `parentCategoryId` points at a category that is not in the file;
- has a task whose `categoryId` or `groupId` points at a row that is not in the file.

Only after validation does the screen show its preview (counts of tasks, categories, topics and
archived rows) and ask for merge or replace.

## Import

Merge (`replaceExisting = false`) matches by uuid, scoped to the profile being imported into:

| Situation | Result |
| --- | --- |
| uuid unknown to this profile | inserted as a **new local row** with a fresh numeric id |
| uuid already owned by this profile | the newer `updatedAt` wins; the older copy is updated in place |
| uuid owned by a **different** profile | the row is neither stolen nor linked: tasks keep no reference to it |
| row was deleted locally, now re-imported | the tombstone is revived instead of creating a second row |

Numeric ids in the file mean nothing on another device, so they are never inserted directly:
inserting a file's id would REPLACE whatever unrelated local row happened to own that id. Category
parents are imported first (in several passes when nesting is deep) so a child can be re-pointed at
its new local row.

Replace (`replaceExisting = true`) empties the profile first, then runs the same merge. Before it
touches anything it writes a **safety copy** of the current data to
`filesDir/backups/safety_<timestamp>.json`, so a mistaken replace is recoverable.

Both modes run the whole import inside one Room transaction: on any failure everything rolls back,
and a half-imported profile can never remain.

## Sync

Firestore layout: `users/{uid}/tasks|categories|groups/{uuid}`, where `{uid}` is the Firebase Auth
uid — never the e-mail. `firebase/firestore.rules` allows read, write and delete only inside the
caller's own uid branch, and requires a created or updated document's id to equal its own `uuid`
field, so a replayed write from another account cannot overwrite or forge records. Deploy with
`firebase deploy --only firestore:rules`.

Offline first: every local change is stored with `sync_state` (`PENDING`, `SYNCED`, `DELETING`, …)
and a `uuid`; WorkManager pushes and pulls when a network is available, and passes queued while
offline run once connectivity returns instead of being dropped. Deleting a row keeps it as a
tombstone (`is_deleted`, `deleted_at`) so the deletion can travel upward instead of the row silently
coming back from the cloud.

Conflicts are decided by rules, not by a field-level merge:

- a local row that still has unsynced edits is never overwritten by the incoming copy;
- a remote deletion turns the local row into a tombstone, and a remote update to a row that is
  already deleted locally is ignored;
- otherwise the newer `updatedAt` wins; a remote copy that is not newer is dropped.

## Google sign-in over a local profile

`AuthRepository.attachLocalDataToAccount(sourceUserId, targetUser)` is the explicit handover the
settings screen offers. It changes the owner of the source rows instead of copying them, so local
ids, uuids and the links between tasks, categories and topics all survive; a task the account
already has by uuid is deduplicated (the newer copy stays) rather than becoming a second row with a
duplicated identity. It refuses to run unless the target is the profile that is currently signed in,
and refuses a profile onto itself, which would otherwise wipe it.
