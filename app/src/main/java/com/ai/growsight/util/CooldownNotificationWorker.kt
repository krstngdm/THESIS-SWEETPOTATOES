package com.ai.growsight.util

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.ai.growsight.util.CooldownNotificationHelper

class CooldownNotificationWorker(
    ctx: Context,
    params: WorkerParameters
) : Worker(ctx, params) {

    override fun doWork(): Result {
        CooldownNotificationHelper.postUnlockNotification(applicationContext)
        return Result.success()
    }
}