package app.aftercheck.data

import android.content.Context
import java.io.IOException

data class SyncSummary(
    val imported: Int,
    val processedArchives: Int,
    val hasMoreHistory: Boolean
)

class ChessComSyncService(
    private val context: Context,
    private val dao: GameDao,
    private val client: ChessComArchiveClient = ChessComArchiveClient()
) {
    companion object {
        const val PREFS = "aftercheck_sync"
        const val KEY_USERNAME = "username"
        const val KEY_STATUS = "status"
        const val KEY_LAST_SYNC = "last_sync_ms"
        const val KEY_ERROR = "last_error"
        private const val KEY_PROCESSED_ARCHIVES = "processed_archives"
    }

    suspend fun sync(usernameInput: String, archiveBatchSize: Int = 3): SyncSummary {
        val username = client.normalizeUsername(usernameInput)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_STATUS, "SYNCING").remove(KEY_ERROR).apply()
        try {
            val archiveUrls = client.fetchArchiveUrls(username).distinct()
            if (archiveUrls.isEmpty()) {
                prefs.edit().putString(KEY_STATUS, "READY").putLong(KEY_LAST_SYNC, System.currentTimeMillis()).apply()
                return SyncSummary(0, 0, false)
            }

            val completed = prefs.getStringSet(KEY_PROCESSED_ARCHIVES, emptySet())
                ?.toMutableSet() ?: mutableSetOf()
            // Always refresh the newest archive in case games were published since the last run.
            val latest = archiveUrls.last()
            val toProcess = (listOf(latest) + archiveUrls.asReversed().filter { it !in completed })
                .distinct()
                .take(archiveBatchSize.coerceAtLeast(1))

            var imported = 0
            var processedCount = 0
            for (archiveUrl in toProcess) {
                val pgn = client.fetchArchivePgn(archiveUrl)
                val games = parsePgnArchive(username, archiveUrl, pgn)
                val unseen = games.filterNot { dao.contains(it.url) }
                if (unseen.isNotEmpty()) {
                    dao.upsertAll(unseen)
                    imported += unseen.size
                }
                completed.add(archiveUrl)
                processedCount++
                prefs.edit().putStringSet(KEY_PROCESSED_ARCHIVES, completed.toSet()).apply()
            }

            val hasMore = archiveUrls.any { it !in completed }
            prefs.edit()
                .putString(KEY_USERNAME, username)
                .putString(KEY_STATUS, if (hasMore) "SYNCING_HISTORY" else "READY")
                .putLong(KEY_LAST_SYNC, System.currentTimeMillis())
                .remove(KEY_ERROR)
                .apply()
            return SyncSummary(imported, processedCount, hasMore)
        } catch (error: Exception) {
            prefs.edit()
                .putString(KEY_STATUS, "ERROR")
                .putString(KEY_ERROR, error.message ?: "Sync failed")
                .apply()
            throw error
        }
    }
}
