package com.ai.growsight.util

import android.content.Context
import com.ai.growsight.util.CooldownNotificationHelper
import java.util.concurrent.TimeUnit

object CooldownManager {

    private const val PREFS = "cooldown_prefs"
    private const val KEY_UNLOCK_TIME = "unlock_time_ms"
    private const val COOLDOWN_DAYS = 7L

    /** Call this right after a successful send to start the cooldown. */
    fun startCooldown(ctx: Context) {
        val unlockAt = System.currentTimeMillis() + TimeUnit.DAYS.toMillis(COOLDOWN_DAYS)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(KEY_UNLOCK_TIME, unlockAt).apply()
        CooldownNotificationHelper.scheduleUnlockNotification(ctx, unlockAt)
    }

    /** Returns true if the send button should be enabled. */
    fun isUnlocked(ctx: Context): Boolean {
        val unlockAt = getUnlockTime(ctx)
        return unlockAt == 0L || System.currentTimeMillis() >= unlockAt
    }

    /** Milliseconds remaining, or 0 if already unlocked. */
    fun remainingMs(ctx: Context): Long {
        val unlockAt = getUnlockTime(ctx)
        if (unlockAt == 0L) return 0L
        return (unlockAt - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    fun getUnlockTime(ctx: Context): Long =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_UNLOCK_TIME, 0L)

    /** Dev only — wipe the cooldown immediately. */
    fun resetCooldown(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_UNLOCK_TIME).apply()
        CooldownNotificationHelper.cancelUnlockNotification(ctx)
    }

    /** Dev only — set a short cooldown (2 minutes) to test the notification. */
    fun startShortCooldown(ctx: Context, minutes: Long = 2) {
        val unlockAt = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(minutes)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(KEY_UNLOCK_TIME, unlockAt).apply()
        CooldownNotificationHelper.scheduleUnlockNotification(ctx, unlockAt)
    }

    fun formatCountdown(ctx: Context): String {
        val ms = remainingMs(ctx)
        if (ms <= 0L) return "Ready now"
        val days  = TimeUnit.MILLISECONDS.toDays(ms)
        val hours = TimeUnit.MILLISECONDS.toHours(ms) % 24
        val mins  = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
        return when {
            days > 0  -> "$days day${if (days != 1L) "s" else ""} $hours hr${if (hours != 1L) "s" else ""}"
            hours > 0 -> "$hours hr${if (hours != 1L) "s" else ""} $mins min${if (mins != 1L) "s" else ""}"
            else      -> "$mins min${if (mins != 1L) "s" else ""} remaining"
        }
    }
}