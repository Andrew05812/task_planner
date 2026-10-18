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
    val updatedAt: Long = System.currentTimeMillis()
) : Parcelable
