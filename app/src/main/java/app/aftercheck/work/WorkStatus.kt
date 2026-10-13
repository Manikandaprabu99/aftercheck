package app.aftercheck.work

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.aftercheck.engine.EngineFiles
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

data class NetworkStatus(
    val ready: Boolean,
    val downloading: Boolean,
    val waiting: Boolean,
    val failed: Boolean,
    /** No download has been scheduled yet (e.g. after the network was removed). */
    val notStarted: Boolean,
    val downloaded: Long,
    val total: Long,
) {
    val fraction: Float get() = if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f) else 0f
}

/** Observes WorkManager so screens can show what is happening in the background. */
class WorkStatus(context: Context, private val files: EngineFiles) {
    private val wm by lazy { WorkManager.getInstance(context) }

    private fun running(name: String): Flow<Boolean> =
        wm.getWorkInfosForUniqueWorkFlow(name).map { infos -> infos.any { it.state == WorkInfo.State.RUNNING } }

    val syncing: Flow<Boolean> by lazy { syncingFlow() }
    val analysing: Flow<Boolean> by lazy { analysingFlow() }
    val network: Flow<NetworkStatus> by lazy { networkFlow() }

    private fun syncingFlow(): Flow<Boolean> = combine(
        running(WorkNames.SYNC_NOW), running(WorkNames.SYNC_CONTINUE), running(WorkNames.SYNC_PERIODIC),
    ) { a, b, c -> a || b || c }.distinctUntilChanged()

    private fun analysingFlow(): Flow<Boolean> = combine(running(WorkNames.ANALYSIS), running(WorkNames.ANALYSIS_BACKLOG)) { a, b -> a || b }
        .distinctUntilChanged()

    private fun networkFlow(): Flow<NetworkStatus> = wm.getWorkInfosForUniqueWorkFlow(WorkNames.NETWORK).map { infos ->
        val info = infos.lastOrNull()
        val ready = files.isNetworkReady()
        val downloaded = info?.progress?.getLong(NetworkDownloadWorker.KEY_DOWNLOADED, -1L)?.takeIf { it >= 0 } ?: files.partialBytes()
        NetworkStatus(
            ready = ready,
            downloading = !ready && info?.state == WorkInfo.State.RUNNING,
            waiting = !ready && info?.state == WorkInfo.State.ENQUEUED,
            failed = !ready && (info?.state == WorkInfo.State.FAILED || info?.state == WorkInfo.State.CANCELLED),
            notStarted = !ready && info == null,
            downloaded = if (ready) EngineFiles.NETWORK_BYTES else downloaded,
            total = EngineFiles.NETWORK_BYTES,
        )
    }.distinctUntilChanged()
}
