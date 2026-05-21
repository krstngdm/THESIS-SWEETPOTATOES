package com.ai.growsight.workers

import android.content.Context
import androidx.work.*
import com.ai.growsight.util.PlantationWeekHelper
import java.util.concurrent.TimeUnit

object CheckInNotificationScheduler {

    fun schedule(context: Context, conversationId: Long) {
        val now        = System.currentTimeMillis()
        val nextMonday = PlantationWeekHelper.nextMondayOf(now)
        val delayMs    = (nextMonday - now).coerceAtLeast(1_000L)

        val inputData = workDataOf(
            CheckInNotificationWorker.KEY_CONVERSATION_ID to conversationId
        )

        val request = OneTimeWorkRequestBuilder<CheckInNotificationWorker>()
            .setInputData(inputData)
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .addTag(CheckInNotificationWorker.WORK_TAG)   // for bulk cancel
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(false)
                    .build()
            )
            .build()

        // Each plantation gets its own unique work name
        WorkManager.getInstance(context).enqueueUniqueWork(
            "${CheckInNotificationWorker.WORK_TAG}_$conversationId",
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    /** Cancel notification for a specific plantation (e.g. on delete). */
    fun cancel(context: Context, conversationId: Long) {
        WorkManager.getInstance(context)
            .cancelUniqueWork("${CheckInNotificationWorker.WORK_TAG}_$conversationId")
    }

    /** Cancel all plantation notifications. */
    fun cancelAll(context: Context) {
        WorkManager.getInstance(context)
            .cancelAllWorkByTag(CheckInNotificationWorker.WORK_TAG)
    }
}