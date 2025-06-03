package com.taskplanner.data.model

import android.os.Parcelable
import androidx.room.*
import com.taskplanner.data.local.Converters
import kotlinx.parcelize.Parcelize

@Parcelize
@Entity(
    tableName = "categories",
    indices = [
        Index(value = ["user_id"]),
        Index(value = ["parent_category_id"])
    ],
    foreignKeys = [
        ForeignKey(
            entity = Category::class,
            parentColumns = ["id"],
            childColumns = ["parent_category_id"],
            onDelete = ForeignKey.SET_NULL
        )
    ]
)
@TypeConverters(Converters::class)
data class Category(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,
    
    @ColumnInfo(name = "name", collate = ColumnInfo.NOCASE)
    val name: String,
    
    @ColumnInfo(name = "color")
    val color: Int,
    
    @ColumnInfo(name = "icon")
    val icon: String,
    
    @ColumnInfo(name = "user_id")
    val userId: Long,
    
    @ColumnInfo(name = "parent_category_id")
    val parentCategoryId: Long? = null,
    
    @ColumnInfo(name = "is_shared", defaultValue = "0")
    val isShared: Boolean = false,
    
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
    
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
) : Parcelable 