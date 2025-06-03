package com.taskplanner.data.model

import android.os.Parcelable
import androidx.room.*
import com.taskplanner.data.local.Converters
import kotlinx.parcelize.Parcelize

@Parcelize
@Entity(
    tableName = "users",
    indices = [Index(value = ["email"], unique = true)]
)
@TypeConverters(Converters::class)
data class User(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "email", collate = ColumnInfo.NOCASE)
    val email: String,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "avatar_color")
    val avatarColor: Int = 0,

    @ColumnInfo(name = "avatar_initial")
    val avatarInitial: String = if (name.isNotBlank()) name.first().uppercase() else "?",

    @ColumnInfo(name = "is_current", defaultValue = "0")
    val isCurrent: Boolean = false,

    @ColumnInfo(name = "last_used")
    val lastUsed: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
) : Parcelable