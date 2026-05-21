package com.ai.growsight.util

import android.content.Context
import androidx.work.*
import com.ai.growsight.util.PlantationWeekHelper
import java.util.concurrent.TimeUnit

object WeekEndAlertScheduler {

    // Hours before Monday 00:00 to fire the warning.
    // 36h = fires ~Saturday noon. Change to 24L (Saturday midnight) or 48L (Friday midnight).
    private const val WARN_HOURS_BEFORE_WEEK_END = 36L

    fun schedule(context: Context, conversationId: Long) {
        val now       = System.currentTimeMillis()
        val weekEndMs = PlantationWeekHelper.nextMondayOf(now)
        val alertMs   = weekEndMs - TimeUnit.HOURS.toMillis(WARN_HOURS_BEFORE_WEEK_END)

        // Alert window for this week already passed — nothing to schedule
        if (alertMs <= now) return

        val request = OneTimeWorkRequestBuilder<WeekEndAlertWorker>()
            .setInitialDelay(alertMs - now, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(WeekEndAlertWorker.KEY_CONVERSATION_ID to conversationId))
            .addTag(WeekEndAlertWorker.WORK_TAG)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(false).build())
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "${WeekEndAlertWorker.WORK_TAG}_$conversationId",
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    fun cancel(context: Context, conversationId: Long) {
        WorkManager.getInstance(context)
            .cancelUniqueWork("${WeekEndAlertWorker.WORK_TAG}_$conversationId")
    }
}