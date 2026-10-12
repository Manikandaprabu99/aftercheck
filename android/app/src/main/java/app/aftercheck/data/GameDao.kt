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

    @Query("DELETE FROM games WHERE url = :url")
    suspend fun delete(url: String)

    @Query("DELETE FROM games")
    suspend fun clearAll()
}
