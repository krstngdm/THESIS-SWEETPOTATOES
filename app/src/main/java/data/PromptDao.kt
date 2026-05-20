package com.ai.growsight.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query

@Dao
interface PromptDao {
    @Insert
    suspend fun insertPrompt(prompt: PromptEntity): Long

    @Query("SELECT * FROM prompts WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    suspend fun getPromptsForConversation(conversationId: Long): List<PromptEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM prompts WHERE uriHash = :hash LIMIT 1)")
    suspend fun existsByUriHash(hash: String): Boolean

    @Delete
    suspend fun deletePrompt(prompt: PromptEntity)
}

