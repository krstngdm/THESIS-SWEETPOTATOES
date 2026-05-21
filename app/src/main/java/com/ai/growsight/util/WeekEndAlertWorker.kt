package com.ai.growsight.util

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
import com.ai.growsight.data.PromptEntity
import com.ai.growsight.util.PlantationWeekHelper

class WeekEndAlertWorker(
    private val ctx: Context,
    params: WorkerParameters
) : CoroutineWorker(ctx, params) {

    companion object {
        const val KEY_CONVERSATION_ID = "conversation_id"
        const val KEY_FORCE_FIRE      = "force_fire"          // dev-only bypass
        const val WORK_TAG            = "week_end_alert"
        const val CHANNEL_ID          = "week_end_alerts"
        const val CHANNEL_NAME        = "Week-End Scan Alerts"
    }

    // ⚠️ Keep this migration list in sync with ConversationsActivity whenever
    // you add a new AppDatabase migration.
    private val db by lazy {
        Room.databaseBuilder(ctx.applicationContext, AppDatabase::class.java, "prompts-db")
            .addMigrations(
                AppDatabase.MIGRATION_6_7,
                AppDatabase.MIGRATION_7_8,
                AppDatabase.MIGRATION_8_9
            )
            .build()
    }

    override suspend fun doWork(): Result {
        val conversationId = inputData.getLong(KEY_CONVERSATION_ID, -1L)
        if (conversationId == -1L) return Result.failure()

        return try {
            val conv = db.conversationDao().getConversationById(conversationId)
                ?: return Result.success()  // plantation deleted — silently ignore

            val prompts = db.promptDao().getVisiblePromptsForConversation(conversationId)

            val forceFire = inputData.getBoolean(KEY_FORCE_FIRE, false)
            val scenario  = if (forceFire) AlertScenario.NO_SCAN_YET else resolveScenario(prompts)
            if (scenario != AlertScenario.NONE) {
                ensureChannel()
                fireNotification(conv.name, conversationId, scenario)
            }
            Result.success()
        } catch (e: Exception) {
            Result.failure()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────

    private fun resolveScenario(prompts: List<PromptEntity>): AlertScenario {
        // No scan submitted this week at all
        if (PlantationWeekHelper.isSendUnlocked(prompts)) {
            return AlertScenario.NO_SCAN_YET
        }

        // Find the most recent scan in the current calendar week
        val thisWeekPrompt = prompts
            .filter { PlantationWeekHelper.isRetakeEligible(it) }
            .maxByOrNull { it.id }
            ?: return AlertScenario.NONE

        return classifyDiagnostic(thisWeekPrompt.diagnostic)
    }

    /**
     * Reads a stored diagnostic string and returns the appropriate alert scenario.
     *
     * ANOMALY NOTE: The anomaly cases check for a segment like "anomaly:critical,medium"
     * in the pipe-delimited diagnostic string. If your ScenarioClassifier does not yet
     * write an anomaly segment, those two cases will never fire — everything else works.
     * To enable anomaly alerts, append "|anomaly:sev1,sev2" when building diagnosticForDb
     * in processImagesWithAI (the section where you build the final diagnostic string).
     */
    private fun classifyDiagnostic(diag: String): AlertScenario {
        if (diag.isBlank()) return AlertScenario.NONE
        return when {
            diag == "no_detection" || diag == "No Detection" ->
                AlertScenario.NO_DETECTION

            diag.startsWith("Stage Conflict|") ->
                AlertScenario.STAGE_CONFLICT

            diag.startsWith("Insufficient Batch|") ->
                AlertScenario.INSUFFICIENT_BATCH

            else -> {
                val severities = extractAnomalySeverities(diag)
                when {
                    severities.any { it == "critical" || it == "high" } -> AlertScenario.ANOMALY_HIGH
                    severities.any { it == "medium" }                   -> AlertScenario.ANOMALY_MEDIUM
                    else                                                 -> AlertScenario.NONE
                }
            }
        }
    }

    private fun extractAnomalySeverities(diag: String): List<String> =
        diag.split("|")
            .firstOrNull { it.startsWith("anomaly:") }
            ?.removePrefix("anomaly:")
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?: emptyList()

    // ─────────────────────────────────────────────────────────────────────────

    private fun fireNotification(
        plantationName: String,
        conversationId: Long,
        scenario: AlertScenario
    ) {
        val (title, body) = messageFor(plantationName, scenario)

        val intent = Intent(ctx, ConversationsActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(ConversationsActivity.EXTRA_CONVERSATION_ID, conversationId)
        }
        val pi = PendingIntent.getActivity(
            ctx,
            ("week_end_$conversationId").hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.grow_sight_leaf)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()

        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(("week_end_$conversationId").hashCode(), notif)
    }

    private fun messageFor(name: String, scenario: AlertScenario): Pair<String, String> =
        when (scenario) {
            AlertScenario.NO_SCAN_YET ->
                "📅 Scan due soon – $name" to
                        "This week's monitoring window closes Sunday. Don't miss your check-in."

            AlertScenario.NO_DETECTION ->
                "❌ No detection recorded – $name" to
                        "This week's scan found no sweet potato. Retake with clearer images before Sunday."

            AlertScenario.STAGE_CONFLICT ->
                "⚠️ Inconclusive scan – $name" to
                        "This week's result was flagged as a Stage Conflict. Consider retaking before Sunday."

            AlertScenario.INSUFFICIENT_BATCH ->
                "📊 Too few valid images – $name" to
                        "This week's scan had too few detectable images. Submit a clearer batch before Sunday."

            AlertScenario.ANOMALY_HIGH ->
                "🔴 Critical anomaly – $name" to
                        "A high-severity issue was flagged in this week's scan. Review and consider retaking before Sunday."

            AlertScenario.ANOMALY_MEDIUM ->
                "🟡 Scan anomaly – $name" to
                        "This week's scan has a flagged issue. Review before Sunday's monitoring window closes."

            AlertScenario.NONE -> "" to ""
        }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "Alerts when a weekly scan is missing or has issues." }
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
    }

    private enum class AlertScenario {
        NONE,
        NO_SCAN_YET,
        NO_DETECTION,
        STAGE_CONFLICT,
        INSUFFICIENT_BATCH,
        ANOMALY_HIGH,
        ANOMALY_MEDIUM
    }
}