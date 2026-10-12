package app.aftercheck.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface GameDao {
    @Query("SELECT * FROM games ORDER BY endTimeEpochMs DESC LIMIT :limit")
    fun observeRecent(limit: Int = 100): Flow<List<ChessGameEntity>>

    @Query("SELECT * FROM games ORDER BY endTimeEpochMs DESC")
    suspend fun allGames(): List<ChessGameEntity>

    @Query("SELECT COUNT(*) FROM games")
    fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM games WHERE reviewStatus = 'READY'")
    fun observeReviewedCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(games: List<ChessGameEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(game: ChessGameEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM games WHERE url = :url)")
    suspend fun contains(url: String): Boolean

    @Query("SELECT * FROM games WHERE reviewStatus = 'WAITING' ORDER BY endTimeEpochMs DESC LIMIT 1")
    suspend fun nextWaitingForReview(): ChessGameEntity?

    @Query("UPDATE games SET reviewStatus = :status WHERE url = :url")
    suspend fun setReviewStatus(url: String, status: String)

    @Query("UPDATE games SET reviewStatus = 'WAITING' WHERE reviewStatus = 'ANALYZING'")
    suspend fun recoverInterruptedReviews()

    @Query("SELECT * FROM move_analyses WHERE gameUrl = :gameUrl ORDER BY plyNumber")
    fun observeMoveAnalyses(gameUrl: String): Flow<List<MoveAnalysisEntity>>

    @Query("SELECT * FROM game_reviews WHERE gameUrl = :gameUrl LIMIT 1")
    suspend fun getReview(gameUrl: String): GameReviewEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMoveAnalyses(rows: List<MoveAnalysisEntity>)

    @Query("DELETE FROM move_analyses WHERE gameUrl = :gameUrl")
    suspend fun deleteMoveAnalyses(gameUrl: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertReview(review: GameReviewEntity)

    @Query("DELETE FROM games WHERE url = :url")
    suspend fun delete(url: String)

    @Query("DELETE FROM games")
    suspend fun clearAll()
}
