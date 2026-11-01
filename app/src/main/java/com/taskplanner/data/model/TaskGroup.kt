package com.taskplanner.data.model

import android.os.Parcelable
import androidx.room.*
import com.taskplanner.data.local.Converters
import kotlinx.parcelize.Parcelize
import java.util.UUID

/**
 * Topic group ("РАБОТА", "ЛИЧНОЕ", …) — a logical block that unites several tasks.
 * Deliberately separate from Category, which classifies a single task.
 */
@Parcelize
@Entity(
    tableName = "task_groups",
    indices = [Index(value = ["user_id"])],
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
data class TaskGroup(
    @PrimaryKey
    @ColumnInfo(name = "uuid")
    val uuid: String = UUID.randomUUID().toString(),

    @ColumnInfo(name = "user_id")
    val userId: Long,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "collapsed", defaultValue = "0")
    val collapsed: Boolean = false,

    @ColumnInfo(name = "sort_order", defaultValue = "0")
    val sortOrder: Int = 0,

    @ColumnInfo(name = "is_deleted", defaultValue = "0")
    val isDeleted: Boolean = false,

    @ColumnInfo(name = "deleted_at")
    val deletedAt: Long? = null,

    @ColumnInfo(name = "sync_state", defaultValue = "'PENDING'")
    val syncState: SyncState = SyncState.PENDING,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis(),

    /**
     * Whether the reader finished this topic itself.
     *
     * A Google Keep export marks its root lines with a checkbox too, and 34 of the 56 topics in the user's own
     * file are `[X]`. Until schema v17 there was nowhere to put that, which is why the earlier import attempt
     * either dropped the topic or flattened it into a task.
     *
     * It is a fact about the topic and nothing else: it is never computed from the children, and completing a
     * topic does not complete what hangs under it. `KeepImportMapping` writes exactly what the source said, and
     * `AppMigrationsTest` pins the column down.
     */
    @ColumnInfo(name = "is_completed", defaultValue = "0")
    val isCompleted: Boolean = false
) : Parcelable
