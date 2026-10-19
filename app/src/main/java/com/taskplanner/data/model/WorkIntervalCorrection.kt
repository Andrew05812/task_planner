package com.taskplanner.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.taskplanner.data.local.Converters

/**
 * The record that a measured interval was changed, and what it said before.
 *
 * A closed [WorkSession] is never rewritten: when the user corrects a start or an end — the timer was
 * started five minutes late, the entry was wrong by an hour — the interval row is updated *and* one of
 * these rows is appended, holding the old pair of instants. Without it the difference between "the user
 * worked 45 minutes" and "the user said it was 45 minutes" would be invisible, and an analytics layer
 * built on this history in a later stage could not tell a measurement from an opinion.
 *
 * Append-only like the intervals themselves: a correction is never edited, a later correction adds
 * another row.
 */
@Entity(
    tableName = "work_interval_corrections",
    indices = [
        // The detail screen asks "what was corrected on this interval", the profile purge asks by user.
        Index(value = ["interval_uuid"]),
        Index(value = ["user_id"])
    ],
    foreignKeys = [
        ForeignKey(
            entity = User::class,
            parentColumns = ["id"],
            childColumns = ["user_id"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
@TypeConverters(Converters::class)
data class WorkIntervalCorrection(
    @PrimaryKey
    @ColumnInfo(name = "uuid")
    val uuid: String = "",

    /** The [WorkSession.uuid] this correction was made against — the stable id, not the row id. */
    @ColumnInfo(name = "interval_uuid")
    val intervalUuid: String,

    @ColumnInfo(name = "user_id")
    val userId: Long,

    @ColumnInfo(name = "old_started_at")
    val oldStartedAt: Long,

    /** Null when the interval was still open at the time of the correction. */
    @ColumnInfo(name = "old_ended_at")
    val oldEndedAt: Long? = null,

    @ColumnInfo(name = "new_started_at")
    val newStartedAt: Long,

    @ColumnInfo(name = "new_ended_at")
    val newEndedAt: Long? = null,

    /** Why the user changed it, in their words; optional, and never invented for them. */
    @ColumnInfo(name = "reason")
    val reason: String? = null,

    @ColumnInfo(name = "sync_state", defaultValue = "'PENDING'")
    val syncState: SyncState = SyncState.PENDING,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
)
