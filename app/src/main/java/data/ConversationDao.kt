package com.ai.growsight.data

import androidx.room.*

@Dao
interface ConversationDao {

    @Insert
    suspend fun insertConversation(conversation: ConversationEntity): Long

    @Query("SELECT COUNT(*) FROM conversations")
    suspend fun getConversationCount(): Int

    @Query("SELECT * FROM conversations ORDER BY id ASC")
    suspend fun getAllConversations(): List<ConversationEntity>

    @Query("SELECT * FROM conversations WHERE id = :id LIMIT 1")
    suspend fun getConversationById(id: Long): ConversationEntity?

    @Query("UPDATE conversations SET name = :newName WHERE id = :id")
    suspend fun updateConversationName(id: Long, newName: String)

    // NOTE: renameConversation and updateConversationName do the same thing.
    // Keeping both so existing call sites don't break.
    @Query("UPDATE conversations SET name = :newName WHERE id = :id")
    suspend fun renameConversation(id: Long, newName: String)

    @Query("UPDATE conversations SET cropAgeWeeks = :weeks WHERE id = :id")
    suspend fun updateCropAge(id: Long, weeks: Int)

    @Query("SELECT cropAgeWeeks FROM conversations WHERE id = :id")
    suspend fun getCropAge(id: Long): Int?

    @Delete
    suspend fun deleteConversation(conversation: ConversationEntity)

    @Query("UPDATE conversations SET planting_date = :date WHERE id = :id")
    suspend fun updatePlantingDate(id: Long, date: Long)

    @Query("SELECT planting_date FROM conversations WHERE id = :id")
    suspend fun getPlantingDate(id: Long): Long?

    // ── NEW: used by PlantationProfileActivity to store the plot/crop name ──
    // (reuses the existing name column — no schema change needed)

    // ── NEW: fetch the full entity so profile screen can pre-fill fields ──
    @Query("SELECT * FROM conversations WHERE id = :id LIMIT 1")
    suspend fun getProfile(id: Long): ConversationEntity?


    @Query("""
        UPDATE conversations
        SET latitude = :lat, longitude = :lon, locationLabel = :label
        WHERE id = :id
    """)
    suspend fun updateLocation(id: Long, lat: Double, lon: Double, label: String)

    @Query("SELECT latitude FROM conversations WHERE id = :id")
    suspend fun getLatitude(id: Long): Double?

    @Query("SELECT longitude FROM conversations WHERE id = :id")
    suspend fun getLongitude(id: Long): Double?

    @Query("SELECT locationLabel FROM conversations WHERE id = :id")
    suspend fun getLocationLabel(id: Long): String?

    @Query("""
        SELECT latitude, longitude, locationLabel
        FROM conversations WHERE id = :id LIMIT 1
    """)
    suspend fun getLocationInfo(id: Long): LocationInfo?

    data class LocationInfo(
        val latitude: Double?,
        val longitude: Double?,
        val locationLabel: String?
    )

    @Query("UPDATE conversations SET is_voided = 1 WHERE id = :id")
    suspend fun markAsVoided(id: Long)

    @Query("SELECT is_voided FROM conversations WHERE id = :id")
    suspend fun isVoided(id: Long): Boolean

    @Query("SELECT * FROM conversations WHERE is_voided = 0")
    suspend fun getActiveConversations(): List<ConversationEntity>
}