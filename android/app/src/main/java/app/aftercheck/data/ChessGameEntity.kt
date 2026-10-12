package app.aftercheck.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "games",
    indices = [Index("endTimeEpochMs"), Index("result"), Index("username")]
)
data class ChessGameEntity(
    @PrimaryKey val url: String,
    val username: String,
    val white: String,
    val black: String,
    val result: String,
    val endTimeEpochMs: Long,
    val timeControl: String,
    val rated: Boolean,
    val opening: String,
    val eco: String,
    val pgn: String,
    val archiveUrl: String,
    val importedAtEpochMs: Long = System.currentTimeMillis(),
    val reviewStatus: String = "WAITING"
)
