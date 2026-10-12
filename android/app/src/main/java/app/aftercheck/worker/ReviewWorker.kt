package app.aftercheck.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.aftercheck.analysis.GameReviewProcessor
import app.aftercheck.data.AfterCheckDatabase
import app.aftercheck.data.ChessComSyncService
import kotlinx.coroutines.CancellationException
import java.io.IOException

class ReviewWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val dao = AfterCheckDatabase.get(applicationContext).gameDao()
        // A process may have been stopped by Android mid-review; make that job resumable.
        dao.recoverInterruptedReviews()
        val game = dao.nextWaitingForReview() ?: return Result.success()
        dao.setReviewStatus(game.url, "ANALYZING")
        return try {
            GameReviewProcessor.review(applicationContext, dao, game, depth = 10) { done, total ->
                setProgress(androidx.work.workDataOf("position" to done, "total" to total))
            }
            if (dao.nextWaitingForReview() != null) AnalysisScheduler.enqueue(applicationContext)
            Result.success()
        } catch (cancelled: CancellationException) {
            // Leave this game resumable if Android stops a long review.
            dao.setReviewStatus(game.url, "WAITING")
            throw cancelled
        } catch (error: IOException) {
            // Do not mark every queued game failed if the first-time network download is unavailable.
            dao.setReviewStatus(game.url, "WAITING")
            applicationContext.getSharedPreferences(ChessComSyncService.PREFS, Context.MODE_PRIVATE)
                .edit().putString("last_analysis_error", error.message ?: "Temporary engine/network failure").apply()
            Result.retry()
        } catch (error: Exception) {
            dao.setReviewStatus(game.url, "FAILED")
            applicationContext.getSharedPreferences(ChessComSyncService.PREFS, Context.MODE_PRIVATE)
                .edit().putString("last_analysis_error", error.message ?: "Local analysis failed").apply()
            if (dao.nextWaitingForReview() != null) AnalysisScheduler.enqueue(applicationContext)
            Result.success()
        }
    }
}
