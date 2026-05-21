package com.ai.growsight.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "prompts")
data class PromptEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val conversationId: Long,
    val imageUris: List<String>,
    val diagnostic: String,
    val timestamp: String,           // display string "MM/dd/yyyy HH:mm"
    val timestampMs: Long = 0L,      // unix epoch ms — used for calendar-week lock logic
    val weekNumber: Int? = null,
    val cropAgeWeeks: Int? = null,
    val uriHash: String? = null,
    val isHiddenForRetake: Boolean = false,   // true = card hidden, retake in progress
    val replacesPromptId: Long = -1L
)