package app.aftercheck.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.aftercheck.data.AfterCheckDatabase
import app.aftercheck.data.ChessComSyncService

class SyncGamesWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences(ChessComSyncService.PREFS, Context.MODE_PRIVATE)
        val username = prefs.getString(ChessComSyncService.KEY_USERNAME, null)?.trim().orEmpty()
        if (username.isBlank()) {
            prefs.edit().putString(ChessComSyncService.KEY_STATUS, "NOT_CONFIGURED").apply()
            return Result.success()
        }

        return try {
            val database = AfterCheckDatabase.get(applicationContext)
            val summary = ChessComSyncService(applicationContext, database.gameDao())
                .sync(username, archiveBatchSize = 3)
            AnalysisScheduler.enqueue(applicationContext)
            if (summary.hasMoreHistory) SyncScheduler.continueHistory(applicationContext)
            Result.success()
        } catch (error: Exception) {
            // WorkManager applies the configured exponential backoff for transient network/API failures.
            Result.retry()
        }
    }
}
