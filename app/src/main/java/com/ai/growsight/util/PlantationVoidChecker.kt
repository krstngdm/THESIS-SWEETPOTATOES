package com.ai.growsight.util

import com.ai.growsight.data.AppDatabase
import com.ai.growsight.data.PromptEntity
import java.text.SimpleDateFormat
import java.util.*

/**
 * Evaluates whether a plantation's scan history warrants an entry block or a hard void.
 *
 * TIER 1 (SubjectToVoid) — last output is flagged AND its calendar week has expired.
 *   Action: entry-blocking dialog → user accepts deletion → week becomes Missed → continue.
 *
 * TIER 2 (HardVoid) — Tier 1 condition is true AND all weeks in a transition window
 *   (8–12 OR 13–17) have zero valid scans.
 *   Action: plantation archived permanently.
 */
object PlantationVoidChecker {

    private val TRANSITION_WINDOWS = listOf(8..12, 13..17)

    sealed class VoidResult {
        object Clean : VoidResult()
        data class SubjectToVoid(
            val flaggedPromptId: Long,
            val flaggedWeek: Int,
            val flagReason: String
        ) : VoidResult()
        data class HardVoid(
            val flaggedPromptId: Long,
            val missedWindow: IntRange
        ) : VoidResult()
    }

    /** Must be called from IO dispatcher. Returns the most severe applicable result. */
    suspend fun evaluate(db: AppDatabase, conversationId: Long): VoidResult {
        if (conversationId == -1L) return VoidResult.Clean
        if (db.conversationDao().isVoided(conversationId)) return VoidResult.Clean

        val prompts = db.promptDao().getPromptsForConversation(conversationId)
        if (prompts.isEmpty()) return VoidResult.Clean

        // Earliest unresolved flagged-and-expired prompt — not just the last one.
        // A flagged prompt superseded by a newer submission is still a violation.
        val flaggedPrompt = prompts.firstOrNull { isFlagged(it) && weekHasExpired(it) }
            ?: return VoidResult.Clean

        val plantingDate = db.conversationDao().getPlantingDate(conversationId)
        val flaggedWeek = weekNumberForPrompt(flaggedPrompt, plantingDate)
        val flagReason = flagReasonFor(flaggedPrompt)

        val currentWeek = currentCropWeek(db, conversationId) ?: flaggedWeek

        val validWeeks = prompts
            .filter { !isFlagged(it) }
            .map { weekNumberForPrompt(it, plantingDate) }
            .toSet()

        for (window in TRANSITION_WINDOWS) {
            if (currentWeek <= window.last) continue
            val hasValidInWindow = window.any { w -> w in validWeeks }
            if (!hasValidInWindow) {
                return VoidResult.HardVoid(flaggedPrompt.id, window)
            }
        }
        return VoidResult.SubjectToVoid(flaggedPrompt.id, flaggedWeek, flagReason)
    }

    // ── Public helpers ─────────────────────────────────────────────────────

    fun isFlagged(prompt: PromptEntity): Boolean {
        val diag = prompt.diagnostic
        return when {
            diag.startsWith("Stage Conflict")     -> true
            diag.startsWith("Insufficient Batch") -> true
            diag == "no_detection"                 -> true
            diag.startsWith("No Detection")        -> true
            diag.contains("🔴") || diag.contains("🟠") -> true
            else -> false
        }
    }

    fun flagReasonFor(prompt: PromptEntity): String {
        val diag = prompt.diagnostic
        return when {
            diag.startsWith("Stage Conflict")     -> "Stage Conflict"
            diag.startsWith("Insufficient Batch") -> "Insufficient Batch"
            diag == "no_detection" || diag.startsWith("No Detection") -> "No Detection"
            diag.contains("🔴") -> "Critical Anomaly"
            diag.contains("🟠") -> "High-Severity Anomaly"
            else -> "Flagged Output"
        }
    }

    // ── Private helpers ────────────────────────────────────────────────────

    fun weekHasExpired(prompt: PromptEntity): Boolean {
        val ts = if (prompt.timestampMs > 0L) prompt.timestampMs else {
            try {
                SimpleDateFormat("MM/dd/yyyy HH:mm", Locale.getDefault())
                    .parse(prompt.timestamp)?.time ?: return false
            } catch (e: Exception) { return false }
        }
        val promptMonday  = PlantationWeekHelper.mondayOf(ts)
        val currentMonday = PlantationWeekHelper.mondayOf(System.currentTimeMillis())
        return currentMonday > promptMonday
    }

    private fun weekNumberForPrompt(prompt: PromptEntity, plantingDate: Long?): Int {
        if (plantingDate != null && plantingDate > 0L) {
            val ts = if (prompt.timestampMs > 0L) prompt.timestampMs
            else System.currentTimeMillis()
            val diff = ts - plantingDate
            return ((diff / (1000L * 60 * 60 * 24 * 7)).toInt() + 1).coerceAtLeast(1)
        }
        return prompt.cropAgeWeeks ?: 1
    }

    private suspend fun currentCropWeek(db: AppDatabase, conversationId: Long): Int? {
        val plantingDate = db.conversationDao().getPlantingDate(conversationId)
        if (plantingDate != null && plantingDate > 0L) {
            val diffMs = System.currentTimeMillis() - plantingDate
            return ((diffMs / (1000L * 60 * 60 * 24 * 7)).toInt() + 1).coerceAtLeast(1)
        }
        return db.conversationDao().getCropAge(conversationId)
    }
}