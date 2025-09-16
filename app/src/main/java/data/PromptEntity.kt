package com.ai.growsight.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "prompts")
data class PromptEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    val imageUris: List<String>,
    val diagnostic: String,
    val timestamp: String
)