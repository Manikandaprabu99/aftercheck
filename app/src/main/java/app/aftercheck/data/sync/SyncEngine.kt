package app.aftercheck.data.sync

import app.aftercheck.chess.OpeningBook
import app.aftercheck.data.chesscom.ArchiveUnavailableException
import app.aftercheck.data.chesscom.ChessComApi
import app.aftercheck.data.chesscom.Fetch
import app.aftercheck.data.chesscom.NotFoundException
import app.aftercheck.data.chesscom.RemoteGame
import app.aftercheck.data.db.ArchiveEntity
import app.aftercheck.data.db.GameEntity
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.util.Calendar
import java.util.TimeZone

/** Persistence used by [SyncEngine]; implemented with Room in the app and in memory in tests. */
interface SyncStore {
    suspend fun archives(username: String): List<ArchiveEntity>
    suspend fun insertArchives(archives: List<ArchiveEntity>)
    suspend fun updateArchive(archive: ArchiveEntity)
    suspend fun existingIds(ids: List<String>): Set<String>
    suspend fun insertGames(games: List<GameEntity>): Int
}

data class SyncResult(
    val newGames: Int,
    val archivesFetched: Int,
    /** Older monthly archives still waiting to be imported (continue in another run). */
    val archivesRemaining: Int,
    val archivesEtag: String?,
    val skippedVariants: Int,
    /** Months Chess.com failed to serve this time; they stay pending and are retried on the next sync. */
    val unavailable: List<ArchiveEntity> = emptyList(),
) {
    val historyComplete: Boolean get() = archivesRemaining == 0
}

/**
 * Incremental import of a player's public Chess.com archives.
 *
 * - The archive index is fetched with its ETag; monthly archives are processed newest first.
 * - A month is marked *complete* once it ended more than two days ago and has been fetched, so it is
 *   never downloaded again. Current months are re-checked with ETag / Last-Modified (a 304 is cheap).
 * - Games are de-duplicated by Chess.com's UUID, so interrupted runs simply resume.
 * - If Chess.com can't serve one month (its "internal error" 404), that month is skipped for this run
 *   without being marked complete, and the other months are still imported.
 */
class SyncEngine(
    private val api: ChessComApi,
    private val store: SyncStore,
    private val book: suspend () -> OpeningBook?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    suspend fun sync(username: String, archivesEtag: String?, maxArchives: Int): SyncResult {
        val user = ChessComApi.normalizeUsername(username) ?: throw NotFoundException("Invalid username")
        var etag = archivesEtag
        when (val listing = api.archives(user, archivesEtag)) {
            is Fetch.Fresh -> {
                store.insertArchives(listing.value.mapNotNull { parseArchiveUrl(it, user) })
                etag = listing.etag
            }
            Fetch.NotModified -> Unit
        }
        val pending = store.archives(user).filter { !it.complete }
        val openingBook = book()
        var fetched = 0
        var newGames = 0
        var skipped = 0
        val unavailable = ArrayList<ArchiveEntity>()
        for (archive in pending.take(maxArchives)) {
            currentCoroutineContext().ensureActive()
            val now = clock()
            val ended = now >= monthEnd(archive.year, archive.month) + GRACE_MILLIS
            val result: Fetch<List<RemoteGame>> = try {
                api.monthGames(archive.url, archive.etag, archive.lastModified)
            } catch (e: NotFoundException) {
                Fetch.Fresh(emptyList(), null, null)
            } catch (e: ArchiveUnavailableException) {
                unavailable += archive
                fetched++
                pause(REQUEST_SPACING_MILLIS)
                continue
            }
            when (result) {
                Fetch.NotModified -> store.updateArchive(archive.copy(fetchedAt = now, complete = ended))
                is Fetch.Fresh -> {
                    val mapped = result.value.mapNotNull { GameMapper.toEntity(it, user, openingBook, now) }
                    skipped += result.value.size - mapped.size
                    val existing = store.existingIds(mapped.map { it.id })
                    val fresh = mapped.filter { it.id !in existing }
                    for (chunk in fresh.chunked(500)) newGames += store.insertGames(chunk)
                    store.updateArchive(
                        archive.copy(
                            etag = result.etag, lastModified = result.lastModified, fetchedAt = now,
                            complete = ended, gameCount = result.value.size,
                        ),
                    )
                }
            }
            fetched++
            pause(REQUEST_SPACING_MILLIS)
        }
        return SyncResult(newGames, fetched, (pending.size - fetched).coerceAtLeast(0), etag, skipped, unavailable)
    }

    companion object {
        const val GRACE_MILLIS = 2 * 24 * 60 * 60 * 1000L
        const val REQUEST_SPACING_MILLIS = 250L
        private val ARCHIVE = Regex(""".*/games/(\d{4})/(\d{1,2})/?$""")

        fun parseArchiveUrl(url: String, username: String): ArchiveEntity? {
            val m = ARCHIVE.matchEntire(url) ?: return null
            val year = m.groupValues[1].toInt()
            val month = m.groupValues[2].toInt().takeIf { it in 1..12 } ?: return null
            return ArchiveEntity(url = url, username = username, year = year, month = month)
        }

        /** First instant of the month after [year]/[month] in UTC. */
        fun monthEnd(year: Int, month: Int): Long = Calendar.getInstance(TimeZone.getTimeZone("UTC")).run {
            clear()
            set(year, month - 1, 1)
            add(Calendar.MONTH, 1)
            timeInMillis
        }
    }
}
