package app.aftercheck.data.sync

import app.aftercheck.data.db.AppDatabase
import app.aftercheck.data.db.ArchiveEntity
import app.aftercheck.data.db.GameEntity

class RoomSyncStore(private val db: AppDatabase) : SyncStore {
    override suspend fun archives(username: String) = db.archives().all(username)
    override suspend fun insertArchives(archives: List<ArchiveEntity>) = db.archives().insertIgnore(archives)
    override suspend fun updateArchive(archive: ArchiveEntity) = db.archives().update(archive)
    override suspend fun existingIds(ids: List<String>) = ids.chunked(500).flatMap { db.games().existingIds(it) }.toSet()
    override suspend fun insertGames(games: List<GameEntity>) = db.games().insertAll(games).count { it != -1L }
}
