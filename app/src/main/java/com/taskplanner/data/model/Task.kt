package com.taskplanner.data.model

import android.os.Parcelable
import androidx.room.*
import com.taskplanner.data.local.Converters
import kotlinx.parcelize.Parcelize

@Parcelize
@Entity(
    tableName = "tasks",
    indices = [
        Index(value = ["user_id"]),
        Index(value = ["category_id"])
    ],
    foreignKeys = [
        ForeignKey(
            entity = Category::class,
            parentColumns = ["id"],
            childColumns = ["category_id"],
            onDelete = ForeignKey.SET_NULL
        )
    ]
)
@TypeConverters(Converters::class)
data class Task(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "title")
    val title: String,

    @ColumnInfo(name = "description")
    val description: String,

    @ColumnInfo(name = "due_date")
    val dueDate: Long? = null,

    @ColumnInfo(name = "priority")
    val priority: Priority = Priority.MEDIUM,

    @ColumnInfo(name = "status")
    val status: TaskStatus = TaskStatus.TODO,

    @ColumnInfo(name = "category_id")
    val categoryId: Long? = null,

    @ColumnInfo(name = "user_id")
    val userId: Long,

    @ColumnInfo(name = "order")
    var order: Int = 0,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
) : Parcelable 