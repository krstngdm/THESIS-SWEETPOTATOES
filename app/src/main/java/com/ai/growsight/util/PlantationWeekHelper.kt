package com.ai.growsight.util

import com.ai.growsight.data.PromptEntity
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * Centralises all week-age and send-lock calculations so that both
 * PlantationProfileActivity and ConversationsActivity use identical logic.
 *
 * Send-lock uses CALENDAR-WEEK logic (Mon - Sun) instead of rolling 7-day window.
 * The bottom bar is locked when there is at least one non-hidden prompt whose
 * timestamp falls within the current calendar week (Monday 00:00 - Sunday 23:59:59
 * in the device locale).
 */
object PlantationWeekHelper {

    private val SDF = SimpleDateFormat("MM/dd/yyyy HH:mm", Locale.getDefault())

    // ── Calendar helpers ──────────────────────────────────────────────────────

    /** Returns the epoch-ms of Monday 00:00:00.000 of the week containing [epochMs]. */
    fun mondayOf(epochMs: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = epochMs
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        // DAY_OF_WEEK: 1=Sun 2=Mon … 7=Sat  →  offset to Monday
        val dow = cal.get(Calendar.DAY_OF_WEEK)          // 1-based, Sun=1
        val daysFromMonday = (dow + 5) % 7               // Sun→6, Mon→0, Tue→1 …
        cal.add(Calendar.DAY_OF_YEAR, -daysFromMonday)
        return cal.timeInMillis
    }

    /** Epoch-ms of the next Monday 00:00:00.000 after [epochMs]. */
    fun nextMondayOf(epochMs: Long): Long = mondayOf(epochMs) + TimeUnit.DAYS.toMillis(7)

    /** True if [epochMs] falls within the same Mon–Sun week as [now]. */
    private fun isSameCalendarWeek(epochMs: Long, now: Long = System.currentTimeMillis()): Boolean {
        return mondayOf(epochMs) == mondayOf(now)
    }

    // ── Resolve a prompt's effective epoch ms ────────────────────────────────

    /**
     * Returns the best available epoch-ms for [prompt].
     * Prefers [PromptEntity.timestampMs] (non-zero); falls back to parsing the
     * display string so that rows inserted before migration still work.
     */
    fun epochMs(prompt: PromptEntity): Long {
        if (prompt.timestampMs > 0L) return prompt.timestampMs
        return try { SDF.parse(prompt.timestamp)?.time ?: 0L } catch (e: Exception) { 0L }
    }

    // ── Week-age computation ──────────────────────────────────────────────────

    /**
     * Returns the current week-age of the plantation.
     *
     * Priority order:
     *  1. plantingDate stored in the DB  (most accurate — set during profiling)
     *  2. baseAge + weeks elapsed since first scan  (legacy fallback)
     *
     * @param plantingDateMs  epoch ms from [ConversationEntity.plantingDate], or null
     * @param baseAge         [ConversationEntity.cropAgeWeeks], or null
     * @param prompts         all [PromptEntity] rows for this conversation
     * @return computed week number (clamped 1–52), or null if nothing is available
     */
    fun computeCurrentWeek(
        plantingDateMs: Long?,
        baseAge: Int?,
        prompts: List<PromptEntity>
    ): Int? {
        // 1. Planting date takes highest priority
        if (plantingDateMs != null && plantingDateMs > 0L) {
            val diffMs = System.currentTimeMillis() - plantingDateMs
            return TimeUnit.MILLISECONDS.toDays(diffMs).toInt()
                .div(7)
                .coerceIn(1, 52)
        }

        // 2. Base age + elapsed since first scan
        if (baseAge != null) {
            if (prompts.isEmpty()) return baseAge
            return try {
                val firstDate = SDF.parse(prompts.first().timestamp) ?: return baseAge
                val weeksElapsed = TimeUnit.MILLISECONDS.toDays(
                    Date().time - firstDate.time
                ).toInt() / 7
                (baseAge + weeksElapsed).coerceAtMost(52)
            } catch (e: Exception) {
                baseAge
            }
        }

        return null
    }

    // ── Send-lock helpers (calendar-week based) ─────────────────────────────

    /**
     * Returns true if the user is allowed to send a new image right now.
     *
     * Unlocked when:
     *  - There are no previous prompts for this conversation, OR
     *  - No prompt exists in the current calendar week (Mon-Sun)
     */
    fun isSendUnlocked(prompts: List<PromptEntity>): Boolean {
        val now = System.currentTimeMillis()
        return prompts.none { isSameCalendarWeek(epochMs(it), now) }
    }

    /**
     * Returns how many milliseconds remain until the send lock expires.
     * Returns 0 if already unlocked.
     * Lock expires at next Monday 00:00.
     */
    fun msUntilUnlock(prompts: List<PromptEntity>): Long {
        if (isSendUnlocked(prompts)) return 0L
        val now = System.currentTimeMillis()
        return (nextMondayOf(now) - now).coerceAtLeast(0L)
    }

    /**
     * Human-readable countdown string like "6d 14h until next check-in".
     */
    fun formatUnlockCountdown(prompts: List<PromptEntity>): String {
        val ms = msUntilUnlock(prompts)
        if (ms <= 0L) return "Unlocked"
        val days    = TimeUnit.MILLISECONDS.toDays(ms)
        val hours   = TimeUnit.MILLISECONDS.toHours(ms) % 24
        val minutes = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
        return when {
            days > 0  -> "${days}d ${hours}h until next check-in"
            hours > 0 -> "${hours}h ${minutes}m until next check-in"
            else      -> "${minutes}m until next check-in"
        }
    }

    // ── Week-icon helpers ─────────────────────────────────────────────────────

    /**
     * Returns the week number that a given [PromptEntity] represents.
     *
     * Uses [PromptEntity.cropAgeWeeks] if stored, otherwise falls back to
     * inferring from the prompt timestamp relative to [plantingDateMs].
     */
    fun weekNumberForPrompt(prompt: PromptEntity, plantingDateMs: Long?): Int {
        prompt.cropAgeWeeks?.let { return it }
        if (plantingDateMs == null || plantingDateMs <= 0L) return 0
        return try {
            val promptDate = SDF.parse(prompt.timestamp) ?: return 0
            val diffMs     = promptDate.time - plantingDateMs
            TimeUnit.MILLISECONDS.toDays(diffMs).toInt().div(7).coerceAtLeast(1)
        } catch (e: Exception) {
            0
        }
    }

    /**
     * True if [prompt] was submitted during the current calendar week —
     * i.e. it is the one that is eligible for retake.
     */
    fun isRetakeEligible(prompt: PromptEntity): Boolean {
        return isSameCalendarWeek(epochMs(prompt))
    }
}