package app.aftercheck.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.io.File

object AnalysisScheduler {
    private const val UNIQUE_WORK_NAME = "aftercheck-local-analysis-queue"
    private const val NNUE_FILE_NAME = "nn-1a298aa575a0.nnue"

    fun enqueue(context: Context) {
        val request = OneTimeWorkRequestBuilder<ReviewWorker>()
            .setBackoffCriteria(
                androidx.work.BackoffPolicy.EXPONENTIAL,
                30,
                java.util.concurrent.TimeUnit.SECONDS
            )
            .apply {
                // Only the first analysis needs internet to fetch the pinned evaluation network.
                // Once cached, all further Stockfish analysis can run fully offline.
                val networkFile = File(File(context.filesDir, "engine"), NNUE_FILE_NAME)
                if (!networkFile.isFile || networkFile.length() == 0L) {
                    setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build()
                    )
                }
            }
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request
        )
    }
}
