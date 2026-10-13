package app.aftercheck.work

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.aftercheck.AfterCheckApp
import app.aftercheck.data.chesscom.ApiException
import app.aftercheck.data.chesscom.NotFoundException
import app.aftercheck.data.chesscom.TransientApiException
import app.aftercheck.engine.AnalysisOutcome
import java.io.IOException
import java.util.concurrent.TimeUnit

object WorkNames {
    const val SYNC_PERIODIC = "sync-periodic"
    const val SYNC_NOW = "sync-now"
    const val SYNC_CONTINUE = "sync-continue"
    const val ANALYSIS = "analysis"
    const val ANALYSIS_BACKLOG = "analysis-backlog"
    const val NETWORK = "engine-network"
    const val TAG_SYNC = "sync"
}

/**
 * All background work goes through WorkManager, so Android decides when it runs (Doze, battery
 * saver, standby buckets). There is no always-on service.
 */
class WorkScheduler(private val context: Context) {
    private val wm get() = WorkManager.getInstance(context)

    private fun networkConstraints(wifiOnly: Boolean) = Constraints.Builder()
        .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .build()

    fun schedulePeriodicSync(intervalMinutes: Int, wifiOnly: Boolean, replace: Boolean) {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(intervalMinutes.toLong().coerceAtLeast(15), TimeUnit.MINUTES)
            .setConstraints(networkConstraints(wifiOnly))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .addTag(WorkNames.TAG_SYNC)
            .build()
        wm.enqueueUniquePeriodicWork(
            WorkNames.SYNC_PERIODIC,
            if (replace) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun syncNow(wifiOnly: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(networkConstraints(wifiOnly))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(WorkNames.TAG_SYNC)
            .build()
        wm.enqueueUniqueWork(WorkNames.SYNC_NOW, ExistingWorkPolicy.KEEP, request)
    }

    /** Older archives are imported in batches; each batch schedules the next. */
    fun continueSync(wifiOnly: Boolean) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(networkConstraints(wifiOnly))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(WorkNames.TAG_SYNC)
            .build()
        wm.enqueueUniqueWork(WorkNames.SYNC_CONTINUE, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    fun enqueueAnalysis(append: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<AnalysisWorker>()
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        wm.enqueueUniqueWork(
            WorkNames.ANALYSIS,
            if (append) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP,
            request,
        )
    }

    /** Older history waits for the charger when the user prefers that. */
    fun enqueueBacklogWhenCharging() {
        val request = OneTimeWorkRequestBuilder<AnalysisWorker>()
            .setConstraints(Constraints.Builder().setRequiresCharging(true).setRequiresBatteryNotLow(true).build())
            .build()
        wm.enqueueUniqueWork(WorkNames.ANALYSIS_BACKLOG, ExistingWorkPolicy.KEEP, request)
    }

    fun cancelAnalysis() {
        wm.cancelUniqueWork(WorkNames.ANALYSIS)
        wm.cancelUniqueWork(WorkNames.ANALYSIS_BACKLOG)
    }

    fun downloadNetwork(wifiOnly: Boolean = false, replace: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<NetworkDownloadWorker>()
            .setConstraints(networkConstraints(wifiOnly))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        wm.enqueueUniqueWork(WorkNames.NETWORK, if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request)
    }

    fun cancelAll() {
        listOf(WorkNames.SYNC_PERIODIC, WorkNames.SYNC_NOW, WorkNames.SYNC_CONTINUE, WorkNames.ANALYSIS, WorkNames.ANALYSIS_BACKLOG)
            .forEach { wm.cancelUniqueWork(it) }
    }
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as AfterCheckApp).container
        val s = c.settings.current()
        val username = s.username ?: return Result.success()
        c.settings.recordSyncAttempt()
        return try {
            val result = c.syncEngine.sync(username, s.archivesEtag, MAX_ARCHIVES_PER_RUN)
            c.settings.recordSync(result.newGames, result.historyComplete, result.archivesEtag)
            if (result.unavailable.isNotEmpty()) {
                val months = result.unavailable.joinToString(", ") { monthName(it.year, it.month) }
                c.settings.recordSyncError("Chess.com couldn't send your games for $months (a problem on their side). AfterCheck will try again automatically.")
            }
            if (c.db.games().queuedCount() > 0) c.scheduler.enqueueAnalysis(append = false)
            if (!result.historyComplete) c.scheduler.continueSync(s.syncWifiOnly)
            Result.success(workDataOf("new" to result.newGames, "remaining" to result.archivesRemaining))
        } catch (e: TransientApiException) {
            c.settings.recordSyncError(e.message ?: "Network error")
            if (runAttemptCount < 5) Result.retry() else Result.failure()
        } catch (e: NotFoundException) {
            c.settings.recordSyncError("Chess.com could not find the account “$username”.")
            Result.failure()
        } catch (e: ApiException) {
            c.settings.recordSyncError(e.message ?: "Sync failed")
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        } catch (e: IOException) {
            c.settings.recordSyncError(e.message ?: "Network error")
            if (runAttemptCount < 5) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val MAX_ARCHIVES_PER_RUN = 12

        fun monthName(year: Int, month: Int): String =
            java.text.DateFormatSymbols.getInstance().months.getOrElse(month - 1) { "$month" } + " $year"
    }
}

class AnalysisWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as AfterCheckApp).container
        val settings = c.settings.current()
        if (settings.analysisPaused) return Result.success()
        if (!c.engineFiles.isNetworkReady()) {
            c.scheduler.downloadNetwork(wifiOnly = false)
            return Result.success()
        }
        val games = c.db.games()
        games.resetInterrupted()
        val engineSettings = settings.engineSettings()
        val deadline = System.currentTimeMillis() + RUN_BUDGET_MILLIS
        var analyzed = 0
        var engineFailures = 0
        c.analysis.withEngine(engineSettings) { engine ->
            while (!isStopped && System.currentTimeMillis() < deadline) {
                if (c.settings.current().analysisPaused) break
                val minEndTime = if (settings.backlogWhileChargingOnly && !isCharging()) {
                    System.currentTimeMillis() - RECENT_WINDOW_MILLIS
                } else 0L
                val next = games.nextQueued(minEndTime) ?: break
                setProgress(workDataOf("gameId" to next.id))
                when (val outcome = c.analysis.analyze(next, engine, engineSettings)) {
                    AnalysisOutcome.Done -> analyzed++
                    is AnalysisOutcome.Failed -> if (outcome.willRetry) {
                        engineFailures++
                        if (engineFailures >= 2) break
                        if (!engine.isRunning) engine.start(
                            mapOf(
                                "EvalFile" to c.engineFiles.networkFile.absolutePath,
                                "Threads" to engineSettings.threads.toString(),
                                "Hash" to engineSettings.hashMb.toString(),
                            ),
                        )
                    }
                    is AnalysisOutcome.Skipped -> Unit
                }
            }
        }
        if (isStopped) return Result.success()
        val remainingNow = games.nextQueued(
            if (settings.backlogWhileChargingOnly && !isCharging()) System.currentTimeMillis() - RECENT_WINDOW_MILLIS else 0L,
        )
        when {
            engineFailures >= 2 -> return Result.retry()
            remainingNow != null -> c.scheduler.enqueueAnalysis(append = true)
            games.queuedCount() > 0 -> c.scheduler.enqueueBacklogWhenCharging()
        }
        return Result.success(workDataOf("analyzed" to analyzed))
    }

    private fun isCharging(): Boolean {
        val status = applicationContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        return status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    }

    companion object {
        /** Stay well inside WorkManager's 10-minute execution window, then continue in a new run. */
        const val RUN_BUDGET_MILLIS = 8 * 60 * 1000L
        const val RECENT_WINDOW_MILLIS = 30L * 24 * 60 * 60 * 1000
    }
}

class NetworkDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as AfterCheckApp).container
        return try {
            c.engineFiles.downloadNetwork { downloaded, total ->
                setProgress(workDataOf(KEY_DOWNLOADED to downloaded, KEY_TOTAL to total))
            }
            c.scheduler.enqueueAnalysis()
            Result.success()
        } catch (e: IOException) {
            if (runAttemptCount < 8) Result.retry() else Result.failure(workDataOf(KEY_ERROR to (e.message ?: "Download failed")))
        }
    }

    companion object {
        const val KEY_DOWNLOADED = "downloaded"
        const val KEY_TOTAL = "total"
        const val KEY_ERROR = "error"
    }
}
