package com.ai.growsight

import android.app.Application
import com.ai.growsight.util.CooldownNotificationHelper

class GrowSightApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CooldownNotificationHelper.createNotificationChannel(this)
    }
}