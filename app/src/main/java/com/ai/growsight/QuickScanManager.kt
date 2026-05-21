package com.ai.growsight

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * QuickScanManager
 * ─────────────────────────────────────────────────────────────────────────────
 * Lightweight SharedPreferences store for standalone quick scans.
 * Each scan stores: conversationId, imageUri, diagnosis, timestamp.
 * No plantation link required.
 */
object QuickScanManager {

    private const val PREFS_NAME   = "quick_scans_prefs"
    private const val KEY_SCANS    = "scans"
    private const val MAX_SCANS    = 20   // keep last 20

    data class QuickScan(
        val conversationId: Long,    // ConversationsActivity conversation row id
        val imageUri: String,        // first image URI string
        val diagnosis: String,       // e.g. "Harvest Ready" or "Scanning…"
        val timestamp: Long          // System.currentTimeMillis()
    )

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Persist a new quick scan entry (prepended, newest first). */
    fun save(ctx: Context, scan: QuickScan) {
        val all = load(ctx).toMutableList()
        all.add(0, scan)                         // newest first
        val trimmed = if (all.size > MAX_SCANS) all.take(MAX_SCANS) else all

        val arr = JSONArray()
        trimmed.forEach { s ->
            arr.put(JSONObject().apply {
                put("conversationId", s.conversationId)
                put("imageUri",       s.imageUri)
                put("diagnosis",      s.diagnosis)
                put("timestamp",      s.timestamp)
            })
        }
        prefs(ctx).edit().putString(KEY_SCANS, arr.toString()).apply()
    }

    /** Update the diagnosis for an existing conversationId (called after AI responds). */
    fun updateDiagnosis(ctx: Context, conversationId: Long, diagnosis: String) {
        val all = load(ctx).toMutableList()
        val idx = all.indexOfFirst { it.conversationId == conversationId }
        if (idx >= 0) {
            all[idx] = all[idx].copy(diagnosis = diagnosis)
            val arr = JSONArray()
            all.forEach { s ->
                arr.put(JSONObject().apply {
                    put("conversationId", s.conversationId)
                    put("imageUri",       s.imageUri)
                    put("diagnosis",      s.diagnosis)
                    put("timestamp",      s.timestamp)
                })
            }
            prefs(ctx).edit().putString(KEY_SCANS, arr.toString()).apply()
        }
    }

    /** Overwrite the entire scan list (used to remove orphaned entries). */
    fun saveAll(ctx: Context, scans: List<QuickScan>) {
        val arr = JSONArray()
        scans.forEach { s ->
            arr.put(JSONObject().apply {
                put("conversationId", s.conversationId)
                put("imageUri",       s.imageUri)
                put("diagnosis",      s.diagnosis)
                put("timestamp",      s.timestamp)
            })
        }
        prefs(ctx).edit().putString(KEY_SCANS, arr.toString()).apply()
    }

    /** Load all saved quick scans (newest first). */
    fun load(ctx: Context): List<QuickScan> {
        val raw = prefs(ctx).getString(KEY_SCANS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                QuickScan(
                    conversationId = o.getLong("conversationId"),
                    imageUri       = o.getString("imageUri"),
                    diagnosis      = o.getString("diagnosis"),
                    timestamp      = o.getLong("timestamp")
                )
            }
        } catch (e: Exception) { emptyList() }
    }
}