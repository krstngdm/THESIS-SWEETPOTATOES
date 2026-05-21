package com.ai.growsight.workers

import android.content.Context
import androidx.room.Room
import androidx.work.*
import com.ai.growsight.data.AppDatabase
import com.ai.growsight.util.PlantationVoidChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Runs weekly at Monday midnight. Finds and archives any plantation that has
 * reached HardVoid condition. Tier 1 (SubjectToVoid) is handled on-entry
 * in ConversationsActivity — the worker only acts on irreversible hard voids.
 */
class PlantationVoidWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val db = Room.databaseBuilder(
                applicationContext,
                AppDatabase::class.java,
                "prompts-db"
            )
                .addMigrations(
                    AppDatabase.MIGRATION_6_7,
                    AppDatabase.MIGRATION_7_8,
                    AppDatabase.MIGRATION_8_9,
                    AppDatabase.MIGRATION_9_10
                )
                .build()

            val conversations = db.conversationDao().getActiveConversations()
            for (conv in conversations) {
                val result = PlantationVoidChecker.evaluate(db, conv.id)
                if (result is PlantationVoidChecker.VoidResult.HardVoid) {
                    db.conversationDao().markAsVoided(conv.id)
                }
            }
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "plantation_void_weekly_check"

        fun schedule(context: Context) {
            val now = Calendar.getInstance()
            val nextMonday = Calendar.getInstance().apply {
                set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (timeInMillis <= now.timeInMillis) add(Calendar.WEEK_OF_YEAR, 1)
            }
            val delayMs = nextMonday.timeInMillis - now.timeInMillis

            val request = PeriodicWorkRequestBuilder<PlantationVoidWorker>(7, TimeUnit.DAYS)
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request
                )
        }
    }
}