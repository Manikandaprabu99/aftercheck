package app.aftercheck.worker

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

object AnalysisScheduler {
    private const val UNIQUE_WORK_NAME = "aftercheck-local-analysis-queue"

    fun enqueue(context: Context) {
        val request = OneTimeWorkRequestBuilder<ReviewWorker>().build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request
        )
    }
}
