package com.ai.growsight.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.ai.growsight.ConversationsActivity
import com.ai.growsight.R
import com.ai.growsight.UploadActivity
import java.util.concurrent.TimeUnit

object CooldownNotificationHelper {

    private const val CHANNEL_ID   = "plantation_cooldown"
    private const val CHANNEL_NAME = "Plantation Check-in"
    private const val WORK_TAG     = "cooldown_unlock_work"

    fun createNotificationChannel(ctx: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Notifies you when your weekly plantation check-in is available"
        }
        ctx.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    fun scheduleUnlockNotification(ctx: Context, unlockAtMs: Long) {
        val delayMs = (unlockAtMs - System.currentTimeMillis()).coerceAtLeast(0L)

        val work = OneTimeWorkRequestBuilder<CooldownNotificationWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .addTag(WORK_TAG)
            .build()

        WorkManager.getInstance(ctx).enqueueUniqueWork(
            WORK_TAG,
            ExistingWorkPolicy.REPLACE,
            work
        )
    }

    fun cancelUnlockNotification(ctx: Context) {
        WorkManager.getInstance(ctx).cancelAllWorkByTag(WORK_TAG)
    }

    /** Called by the Worker when it fires. */
    fun postUnlockNotification(ctx: Context, conversationId: Long = -1L) {
        val intent = Intent(ctx, ConversationsActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (conversationId > 0L) {
                putExtra(ConversationsActivity.EXTRA_CONVERSATION_ID, conversationId)
            }
        }
        val pi = PendingIntent.getActivity(
            ctx, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.grow_sight_leaf)
            .setContentTitle("🌱 Time to check your plantation!")
            .setContentText("Your weekly check-in is now available. Tap to scan.")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("Your weekly plantation check-in is now available. Open GrowSight to scan your crops and track progress.")
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()

        ctx.getSystemService(NotificationManager::class.java)
            .notify(1001, notification)
    }
}