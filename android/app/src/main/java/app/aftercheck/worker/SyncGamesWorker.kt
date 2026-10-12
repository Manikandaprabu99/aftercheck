package app.aftercheck.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.aftercheck.data.AfterCheckDatabase
import app.aftercheck.data.ChessComSyncService
import java.io.IOException
import kotlinx.coroutines.CancellationException

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
        } catch (cancelled: CancellationException) {
            // Do not turn WorkManager cancellation into a successful, swallowed worker result.
            prefs.edit().putString(ChessComSyncService.KEY_STATUS, "QUEUED").apply()
            throw cancelled
        } catch (error: IOException) {
            // Transient network failures, throttling, and server failures use WorkManager backoff.
            prefs.edit().putString(ChessComSyncService.KEY_STATUS, "RETRYING")
                .putString(ChessComSyncService.KEY_ERROR, error.message ?: "Temporary sync failure")
                .apply()
            Result.retry()
        } catch (error: Exception) {
            // Invalid username/non-public account responses should not retry forever.
            prefs.edit().putString(ChessComSyncService.KEY_STATUS, "ERROR")
                .putString(ChessComSyncService.KEY_ERROR, error.message ?: "Sync failed")
                .apply()
            Result.failure()
        }
    }
}
