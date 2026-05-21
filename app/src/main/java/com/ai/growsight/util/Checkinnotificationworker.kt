package com.ai.growsight.workers

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.room.Room
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ai.growsight.ConversationsActivity
import com.ai.growsight.R
import com.ai.growsight.data.AppDatabase

class CheckInNotificationWorker(
    private val ctx: Context,
    params: WorkerParameters
) : CoroutineWorker(ctx, params) {

    companion object {
        const val CHANNEL_ID          = "growsight_checkin"
        const val CHANNEL_NAME        = "Weekly Check-in"
        const val NOTIF_ID            = 1001
        const val WORK_TAG            = "weekly_checkin_notif"
        const val KEY_CONVERSATION_ID = "conversation_id"
    }

    private val db by lazy {
        Room.databaseBuilder(ctx.applicationContext, AppDatabase::class.java, "prompts-db")
            .addMigrations(
                AppDatabase.MIGRATION_6_7,
                AppDatabase.MIGRATION_7_8,
                AppDatabase.MIGRATION_8_9,
                AppDatabase.MIGRATION_9_10
            )
            .build()
    }

    override suspend fun doWork(): Result {
        val conversationId = inputData.getLong(KEY_CONVERSATION_ID, -1L)

        // Hard stop for quick scans, independent of who scheduled this work —
        // don't rely solely on the call site remembering to gate it.
        val isQuickScan = ctx.getSharedPreferences("quick_scan_prefs", Context.MODE_PRIVATE)
            .getBoolean("is_quick_scan_$conversationId", false)
        if (isQuickScan) return Result.success()

        ensureChannel()

        val plantationName = if (conversationId > 0L) {
            db.conversationDao().getConversationById(conversationId)?.name ?: "Your plantation"
        } else "Your plantation"

        val intent = Intent(ctx, ConversationsActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (conversationId > 0L) {
                putExtra(ConversationsActivity.EXTRA_CONVERSATION_ID, conversationId)
            }
        }
        val pendingIntent = PendingIntent.getActivity(
            ctx, conversationId.toInt(),   // unique request code per plantation
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notif = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.grow)
            .setContentTitle("🌿 Weekly Check-in Available – $plantationName")
            .setContentText("$plantationName is ready for its weekly scan. Tap to check in.")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("$plantationName is ready for its weekly scan. Open GrowSight to record this week's progress.")
            )
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        // Unique notification ID per plantation so they don't overwrite each other
        val notifId = if (conversationId > 0L) (NOTIF_ID + (conversationId % 1000).toInt()) else NOTIF_ID
        nm.notify(notifId, notif)
        return Result.success()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifies you when your weekly crop check-in is available."
            }
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
    }
}