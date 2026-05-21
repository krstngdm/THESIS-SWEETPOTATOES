package com.ai.growsight.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query

@Dao
interface PromptDao {

    @Insert
    suspend fun insertPrompt(prompt: PromptEntity): Long

    // Returns all non-hidden prompts for a conversation, ordered by timestampMs (falls back to
    // alphabetical timestamp string for legacy rows that have timestampMs = 0).
    @Query("""
        SELECT * FROM prompts 
        WHERE conversationId = :convId 
          AND isHiddenForRetake = 0
        ORDER BY id ASC
    """)
    suspend fun getPromptsForConversation(convId: Long): List<PromptEntity>

    // For internal operations (retake deletion, history rebuild) — includes ALL rows
    @Query("SELECT * FROM prompts WHERE conversationId = :conversationId ORDER BY id ASC")
    suspend fun getAllPromptsForConversation(conversationId: Long): List<PromptEntity>

    // Returns the single prompt that is currently hidden-for-retake in this conversation,
    // or null if none. (There should never be more than one at a time.)
    @Query("""
        SELECT * FROM prompts
        WHERE conversationId = :conversationId
          AND isHiddenForRetake = 1
        LIMIT 1
    """)
    suspend fun getHiddenRetakePrompt(conversationId: Long): PromptEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM prompts WHERE uriHash = :hash LIMIT 1)")
    suspend fun existsByUriHash(hash: String): Boolean

    @Delete
    suspend fun deletePrompt(prompt: PromptEntity)

    @Query("UPDATE prompts SET isHiddenForRetake = 1 WHERE id = :promptId")
    suspend fun setHiddenForRetake(promptId: Long)

    // Restore a hidden prompt (used when user exits without submitting).
    @Query("UPDATE prompts SET isHiddenForRetake = 0 WHERE id = :promptId")
    suspend fun clearHiddenForRetake(promptId: Long)

    // Clear ALL hidden flags in a conversation (safety clean-up).
    @Query("UPDATE prompts SET isHiddenForRetake = 0 WHERE conversationId = :conversationId")
    suspend fun clearAllHiddenForConversation(conversationId: Long)

    // Update the unix-ms timestamp on a prompt (used by dev tool).
    @Query("UPDATE prompts SET timestampMs = :ms WHERE id = :promptId")
    suspend fun updateTimestampMs(promptId: Long, ms: Long)

    // Dev tool: shift every prompt in a conversation back by exactly 7 days.
    // Updates both the unix field and the display string.
    @Query("""
        UPDATE prompts
        SET timestampMs = timestampMs - 604800000
        WHERE conversationId = :conversationId
          AND timestampMs > 0
    """)
    suspend fun shiftAllTimestampsMsBackOneWeek(conversationId: Long)

    // Dev tool: also returns the prompts after shifting so we can rewrite the display strings.
    @Query("SELECT * FROM prompts WHERE conversationId = :conversationId")
    suspend fun getRawPromptsForConversation(conversationId: Long): List<PromptEntity>

    // Update display timestamp string individually (used after the shift above).
    @Query("UPDATE prompts SET timestamp = :display WHERE id = :promptId")
    suspend fun updateDisplayTimestamp(promptId: Long, display: String)

    @Query("UPDATE prompts SET diagnostic = :diagnostic WHERE id = :id")
    suspend fun updateDiagnostic(id: Long, diagnostic: String)

    @Query("DELETE FROM prompts WHERE id = :promptId")
    suspend fun deletePromptById(promptId: Long)

    @Query("DELETE FROM prompts WHERE conversationId = :conversationId AND isHiddenForRetake = 1")
    suspend fun deleteAllHiddenForConversation(conversationId: Long)

    // Retake: overwrite an existing prompt row in-place with new image/diagnostic data.
    @Query("""
        UPDATE prompts
        SET imageUris       = :imageUris,
            diagnostic      = :diagnostic,
            timestamp       = :timestamp,
            timestampMs     = :timestampMs,
            cropAgeWeeks    = :cropAgeWeeks,
            uriHash         = :uriHash,
            isHiddenForRetake = 0,
            replacesPromptId  = -1
        WHERE id = :id
    """)
    suspend fun updatePromptInPlace(
        id: Long,
        imageUris: String,
        diagnostic: String,
        timestamp: String,
        timestampMs: Long,
        cropAgeWeeks: Int?,
        uriHash: String?
    ): Int

    @Query("UPDATE prompts SET isHiddenForRetake = 0 WHERE id = :id")
    suspend fun clearHiddenFlag(id: Long)

    @Query("""UPDATE prompts
    SET imageUris     = :imageUris,
        diagnostic    = :diagnostic,
        timestamp     = :timestamp,
        timestampMs   = :timestampMs,
        cropAgeWeeks  = :cropAgeWeeks,
        uriHash       = :uriHash,
        isHiddenForRetake = 0
    WHERE id = :id""")
    suspend fun updatePromptInPlaceAndClearHidden(
        id: Long,
        imageUris: String,
        diagnostic: String,
        timestamp: String,
        timestampMs: Long,
        cropAgeWeeks: Int?,
        uriHash: String
    )

    @Query("""
        SELECT * FROM prompts 
        WHERE conversationId = :convId 
        AND (isHiddenForRetake = 0 OR isHiddenForRetake IS NULL)
    """)
    suspend fun getVisiblePromptsForConversation(convId: Long): List<PromptEntity>
}