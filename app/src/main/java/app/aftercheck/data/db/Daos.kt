package app.aftercheck.data.db

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** Game columns without the PGN, for lists. */
data class GameSummary(
    val id: String,
    val url: String?,
    val white: String,
    val black: String,
    val whiteRating: Int?,
    val blackRating: Int?,
    val userColor: String?,
    val opponent: String,
    val opponentRating: Int?,
    val userRating: Int?,
    val result: String,
    val userResult: String,
    val termination: String?,
    val endTime: Long,
    val timeClass: String,
    val timeControl: String,
    val rated: Boolean,
    val eco: String?,
    val openingName: String?,
    val plies: Int,
    val status: String,
    val statusMessage: String?,
    val progress: Int,
    val userAccuracy: Double?,
    val opponentAccuracy: Double?,
    val userBlunders: Int?,
    val userMistakes: Int?,
    val userInaccuracies: Int?,
)

data class LibraryCounts(val total: Int, val ready: Int, val pending: Int, val failed: Int, val analyzing: Int)

data class InsightRow(
    val id: String,
    val endTime: Long,
    val userResult: String,
    val userColor: String?,
    val timeClass: String,
    val openingFamily: String?,
    val userAccuracy: Double?,
    val userInaccuracies: Int?,
    val userMistakes: Int?,
    val userBlunders: Int?,
    val userMisses: Int?,
    val plies: Int,
    val status: String,
)

private const val SUMMARY_COLUMNS =
    "id, url, white, black, whiteRating, blackRating, userColor, opponent, opponentRating, userRating, result, " +
        "userResult, termination, endTime, timeClass, timeControl, rated, eco, openingName, plies, status, " +
        "statusMessage, progress, userAccuracy, opponentAccuracy, userBlunders, userMistakes, userInaccuracies"

@Dao
interface GameDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(games: List<GameEntity>): List<Long>

    @Query("SELECT id FROM games WHERE id IN (:ids)")
    suspend fun existingIds(ids: List<String>): List<String>

    @Query("SELECT * FROM games WHERE id = :id")
    suspend fun get(id: String): GameEntity?

    @Query("SELECT * FROM games WHERE id = :id")
    fun observe(id: String): Flow<GameEntity?>

    @Update
    suspend fun update(game: GameEntity)

    @Query("SELECT $SUMMARY_COLUMNS FROM games ORDER BY endTime DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<GameSummary>>

    @Query("SELECT $SUMMARY_COLUMNS FROM games WHERE status = 'ready' ORDER BY endTime DESC LIMIT :limit")
    fun recentReviewed(limit: Int): Flow<List<GameSummary>>

    @Query("SELECT $SUMMARY_COLUMNS FROM games WHERE status = 'analyzing' ORDER BY priority DESC LIMIT 1")
    fun analyzing(): Flow<GameSummary?>

    @Query("SELECT COALESCE(SUM(plies + 1), 0) FROM games WHERE status IN ('queued', 'analyzing')")
    fun pendingPositions(): Flow<Long>

    @Query(
        "SELECT COUNT(*) AS total, " +
            "COALESCE(SUM(status = 'ready'), 0) AS ready, " +
            "COALESCE(SUM(status IN ('queued', 'analyzing')), 0) AS pending, " +
            "COALESCE(SUM(status = 'failed'), 0) AS failed, " +
            "COALESCE(SUM(status = 'analyzing'), 0) AS analyzing FROM games",
    )
    fun counts(): Flow<LibraryCounts>

    @Query(
        "SELECT $SUMMARY_COLUMNS FROM games WHERE " +
            "(:query = '' OR opponentLower LIKE '%' || :query || '%' OR openingLower LIKE '%' || :query || '%' OR eco LIKE :query || '%') " +
            "AND (:result = '' OR userResult = :result) " +
            "AND (:color = '' OR userColor = :color) " +
            "AND (:timeClass = '' OR timeClass = :timeClass) " +
            "AND (:status = '' OR (:status = 'pending' AND status IN ('queued', 'analyzing')) OR status = :status) " +
            "ORDER BY " +
            "CASE WHEN :sort = 1 THEN endTime END ASC, " +
            "CASE WHEN :sort = 2 THEN COALESCE(userAccuracy, -1) END DESC, " +
            "CASE WHEN :sort = 3 THEN COALESCE(userAccuracy, 101) END ASC, " +
            "endTime DESC",
    )
    fun library(query: String, result: String, color: String, timeClass: String, status: String, sort: Int): PagingSource<Int, GameSummary>

    @Query(
        "SELECT * FROM games WHERE status = 'queued' AND (priority >= ${Priority.NEW} OR endTime >= :minEndTime) " +
            "ORDER BY priority DESC, endTime DESC LIMIT 1",
    )
    suspend fun nextQueued(minEndTime: Long): GameEntity?

    @Query("SELECT COUNT(*) FROM games WHERE status = 'queued'")
    suspend fun queuedCount(): Int

    @Query("SELECT COUNT(*) FROM games WHERE status = 'queued' AND priority < ${Priority.NEW} AND endTime < :minEndTime")
    suspend fun backlogCount(minEndTime: Long): Int

    @Query("UPDATE games SET status = :status, statusMessage = :message, progress = :progress WHERE id = :id")
    suspend fun setStatus(id: String, status: String, message: String?, progress: Int)

    @Query("UPDATE games SET progress = :progress WHERE id = :id")
    suspend fun setProgress(id: String, progress: Int)

    @Query("UPDATE games SET status = 'queued', progress = 0 WHERE status = 'analyzing'")
    suspend fun resetInterrupted(): Int

    @Query("UPDATE games SET status = 'queued', statusMessage = NULL, attempts = 0, priority = MAX(priority, :priority) WHERE id = :id")
    suspend fun requeue(id: String, priority: Int)

    @Query("UPDATE games SET status = 'queued', statusMessage = NULL, attempts = 0 WHERE status = 'failed'")
    suspend fun requeueFailed(): Int

    @Query("UPDATE games SET status = 'queued', priority = ${Priority.BACKLOG} WHERE status = 'ready' AND (analysisKey IS NULL OR analysisKey != :key)")
    suspend fun requeueWithOtherSettings(key: String): Int

    @Query("SELECT COUNT(*) FROM games WHERE status = 'ready' AND (analysisKey IS NULL OR analysisKey != :key)")
    fun countWithOtherSettings(key: String): Flow<Int>

    @Query(
        "SELECT id, endTime, userResult, userColor, timeClass, openingFamily, userAccuracy, userInaccuracies, " +
            "userMistakes, userBlunders, userMisses, plies, status FROM games WHERE endTime >= :since ORDER BY endTime ASC",
    )
    fun insightRows(since: Long): Flow<List<InsightRow>>

    @Query(
        "SELECT m.themes FROM moves m JOIN games g ON g.id = m.gameId WHERE m.isUser = 1 AND g.endTime >= :since " +
            "AND m.classification IN ('INACCURACY', 'MISTAKE', 'BLUNDER', 'MISS', 'MISSED_MATE') AND m.themes != ''",
    )
    fun userErrorThemes(since: Long): Flow<List<String>>

    @Query("DELETE FROM games WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM games")
    suspend fun deleteAll()
}

@Dao
interface ReviewDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReview(review: ReviewEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMoves(moves: List<MoveEntity>)

    @Query("DELETE FROM moves WHERE gameId = :gameId")
    suspend fun deleteMoves(gameId: String)

    @Query("SELECT * FROM reviews WHERE gameId = :gameId")
    fun observeReview(gameId: String): Flow<ReviewEntity?>

    @Query("SELECT * FROM moves WHERE gameId = :gameId ORDER BY ply")
    fun observeMoves(gameId: String): Flow<List<MoveEntity>>

    @Query("SELECT * FROM moves WHERE gameId = :gameId ORDER BY ply")
    suspend fun moves(gameId: String): List<MoveEntity>
}

@Dao
interface ArchiveDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(archives: List<ArchiveEntity>)

    @Update
    suspend fun update(archive: ArchiveEntity)

    @Query("SELECT * FROM archives WHERE username = :username ORDER BY year DESC, month DESC")
    suspend fun all(username: String): List<ArchiveEntity>

    @Query("SELECT COUNT(*) FROM archives WHERE username = :username")
    fun observeTotal(username: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM archives WHERE username = :username AND fetchedAt IS NOT NULL")
    fun observeFetched(username: String): Flow<Int>

    @Query("DELETE FROM archives")
    suspend fun deleteAll()
}

@Dao
interface EvalCacheDao {
    @Query("SELECT * FROM eval_cache WHERE `key` = :key")
    suspend fun get(key: String): EvalCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entry: EvalCacheEntity)

    @Query("SELECT COUNT(*) FROM eval_cache")
    suspend fun count(): Int

    @Query("DELETE FROM eval_cache WHERE `key` IN (SELECT `key` FROM eval_cache ORDER BY createdAt ASC LIMIT :n)")
    suspend fun deleteOldest(n: Int)

    @Query("DELETE FROM eval_cache")
    suspend fun clear()
}

data class PracticeStats(val total: Int, val due: Int, val mastered: Int, val attempts: Int, val correct: Int, val starred: Int)

data class ThemeCount(val themes: String, val n: Int)

@Dao
interface PracticeDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(positions: List<PracticeEntity>)

    @Update
    suspend fun update(position: PracticeEntity)

    @Insert
    suspend fun insertAttempt(attempt: PracticeAttemptEntity)

    @Query("SELECT * FROM practice WHERE id = :id")
    suspend fun get(id: Long): PracticeEntity?

    @Query(
        "SELECT * FROM practice WHERE (:theme = '' OR (',' || themes || ',') LIKE '%,' || :theme || ',%') " +
            "AND (:starredOnly = 0 OR starred = 1) " +
            "ORDER BY (dueAt <= :now) DESC, dueAt ASC, createdAt DESC LIMIT :limit",
    )
    suspend fun queue(now: Long, theme: String, starredOnly: Boolean, limit: Int): List<PracticeEntity>

    @Query(
        "SELECT COUNT(*) AS total, " +
            "COALESCE(SUM(dueAt <= :now), 0) AS due, " +
            "COALESCE(SUM(box >= 4), 0) AS mastered, " +
            "COALESCE(SUM(attempts), 0) AS attempts, " +
            "COALESCE(SUM(correct), 0) AS correct, " +
            "COALESCE(SUM(starred), 0) AS starred FROM practice",
    )
    fun stats(now: Long): Flow<PracticeStats>

    @Query("SELECT themes, COUNT(*) AS n FROM practice GROUP BY themes")
    fun themeCounts(): Flow<List<ThemeCount>>

    @Query("SELECT * FROM practice_attempts WHERE at >= :since ORDER BY at ASC")
    fun attemptsSince(since: Long): Flow<List<PracticeAttemptEntity>>

    @Query("SELECT * FROM practice ORDER BY createdAt DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<PracticeEntity>>
}
