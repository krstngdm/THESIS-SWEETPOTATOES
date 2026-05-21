
package com.ai.growsight.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    var name: String,
    val cropAgeWeeks: Int? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val locationLabel: String? = null,
    @ColumnInfo(name = "planting_date")
    val plantingDate: Long = 0L,
    @ColumnInfo(name = "is_voided")
    val isVoided: Boolean = false
)
