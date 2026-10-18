package com.taskplanner.data.model

enum class SyncState {
    /** Row was created/changed locally and still must be pushed to the cloud. */
    PENDING,

    /** Row matches the cloud copy. */
    SYNCED,

    /** Deletion happened locally and a tombstone still must be pushed. */
    DELETING
}
