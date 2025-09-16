package com.ai.growsight.data

import androidx.room.*

@Dao
interface ConversationDao {
    @Insert
    suspend fun insertConversation(conversation: ConversationEntity): Long

    @Query("SELECT COUNT(*) FROM conversations")
    suspend fun getConversationCount(): Int

    @Query("UPDATE conversations SET name = :newName WHERE id = :id")
    suspend fun renameConversation(id: Long, newName: String)

    @Query("SELECT * FROM conversations ORDER BY id ASC")
    suspend fun getAllConversations(): List<ConversationEntity>

    @Query("SELECT * FROM conversations WHERE id = :id LIMIT 1")
    suspend fun getConversationById(id: Long): ConversationEntity?

    @Query("UPDATE conversations SET name = :newName WHERE id = :id")
    suspend fun updateConversationName(id: Long, newName: String)

    @Delete
    suspend fun deleteConversation(conversation: ConversationEntity)
}
