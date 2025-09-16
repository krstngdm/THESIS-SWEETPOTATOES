package com.ai.growsight.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query

@Dao
interface PromptDao {
    @Insert
    suspend fun insertPrompt(prompt: PromptEntity)

    @Query("SELECT * FROM prompts WHERE conversationId = :conversationId ORDER BY id ASC")
    suspend fun getPromptsForConversation(conversationId: Long): List<PromptEntity>

    @Delete
    suspend fun deletePrompt(prompt: PromptEntity)
}

