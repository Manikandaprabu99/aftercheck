package app.aftercheck.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

object GameStatus {
    const val QUEUED = "queued"
    const val ANALYZING = "analyzing"
    const val READY = "ready"
    const val FAILED = "failed"
    const val SKIPPED = "skipped"
}

object Priority {
    const val BACKLOG = 0
    const val RECENT = 1
    const val NEW = 2
    const val USER_REQUESTED = 3
}

@Entity(
    tableName = "games",
    indices = [
        Index("endTime"),
        Index(value = ["status", "priority", "endTime"]),
        Index("userResult"),
        Index("timeClass"),
        Index("opponentLower"),
        Index("queueOrder"),
    ],
)
data class GameEntity(
    /** Chess.com game UUID (stable across archive refreshes). */
    @PrimaryKey val id: String,
    val url: String?,
    val white: String,
    val black: String,
    val whiteRating: Int?,
    val blackRating: Int?,
    /** "w", "b", or null when the account played neither side. */
    val userColor: String?,
    val opponent: String,
    val opponentLower: String,
    val opponentRating: Int?,
    val userRating: Int?,
    /** "1-0", "0-1", "1/2-1/2". */
    val result: String,
    /** "win", "loss", "draw". */
    val userResult: String,
    /** Human readable reason, e.g. "checkmate", "resignation", "time". */
    val termination: String?,
    /** Epoch millis. */
    val endTime: Long,
    /** bullet, blitz, rapid, daily. */
    val timeClass: String,
    val timeControl: String,
    val rated: Boolean,
    val eco: String?,
    val openingName: String?,
    val openingFamily: String?,
    val openingLower: String?,
    /** Moves in the PGN (plies). */
    val plies: Int,
    val pgn: String,
    val status: String = GameStatus.QUEUED,
    val statusMessage: String? = null,
    val priority: Int = Priority.BACKLOG,
    val attempts: Int = 0,
    /** Analysis progress 0..100 while ANALYZING. */
    val progress: Int = 0,
    /** Engine settings key the stored review was produced with. */
    val analysisKey: String? = null,
    val whiteAccuracy: Double? = null,
    val blackAccuracy: Double? = null,
    val userAccuracy: Double? = null,
    val opponentAccuracy: Double? = null,
    val userBest: Int? = null,
    val userInaccuracies: Int? = null,
    val userMistakes: Int? = null,
    val userBlunders: Int? = null,
    val userMisses: Int? = null,
    val importedAt: Long,
    val analyzedAt: Long? = null,
    /** Set when the user picked this game to be analysed early; lower values go first. */
    val queueOrder: Long? = null,
)

@Entity(
    tableName = "moves",
    primaryKeys = ["gameId", "ply"],
    foreignKeys = [ForeignKey(entity = GameEntity::class, parentColumns = ["id"], childColumns = ["gameId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["isUser", "classification"])],
)
data class MoveEntity(
    val gameId: String,
    val ply: Int,
    val san: String,
    val uci: String,
    val isUser: Boolean,
    val classification: String,
    val scoreBefore: String,
    val scoreAfter: String,
    val pointsLost: Double,
    val accuracy: Double,
    val bestUci: String?,
    val bestSan: String?,
    /** Space separated SAN. */
    val bestLine: String,
    val replyLine: String,
    /** Alternatives: "score|san san san" joined by ";". */
    val alternatives: String,
    val explanation: String,
    /** Comma separated [app.aftercheck.chess.analysis.Theme] names. */
    val themes: String,
    val clockMillis: Long?,
    val timeSpentMillis: Long?,
)

@Entity(
    tableName = "reviews",
    foreignKeys = [ForeignKey(entity = GameEntity::class, parentColumns = ["id"], childColumns = ["gameId"], onDelete = ForeignKey.CASCADE)],
)
data class ReviewEntity(
    @PrimaryKey val gameId: String,
    val whiteAccuracy: Double?,
    val blackAccuracy: Double?,
    /** "CLASS:count" pairs joined by ",". */
    val whiteCounts: String,
    val blackCounts: String,
    val eco: String?,
    val openingName: String?,
    val bookPlies: Int,
    /** White win chance per position, permille, comma separated. */
    val winChances: String,
    val finalScore: String,
    val biggestSwingPly: Int?,
    val strongestMovePly: Int?,
    val turningPoints: String,
    /** "THEME|count|text" joined by newline. */
    val recommendations: String,
    val engineName: String,
    val analysisKey: String,
    val depth: Int,
    val durationMillis: Long,
    val createdAt: Long,
)

@Entity(tableName = "archives", indices = [Index("username")])
data class ArchiveEntity(
    @PrimaryKey val url: String,
    val username: String,
    val year: Int,
    val month: Int,
    val etag: String? = null,
    val lastModified: String? = null,
    val fetchedAt: Long? = null,
    /** A past month whose archive can no longer change; never fetched again. */
    val complete: Boolean = false,
    val gameCount: Int = 0,
)

@Entity(tableName = "eval_cache", indices = [Index("createdAt")])
data class EvalCacheEntity(
    /** "<zobrist hex>|<settings key>". */
    @PrimaryKey val key: String,
    val encoded: String,
    val createdAt: Long,
)

@Entity(
    tableName = "practice",
    foreignKeys = [ForeignKey(entity = GameEntity::class, parentColumns = ["id"], childColumns = ["gameId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["gameId", "ply"], unique = true), Index("dueAt")],
)
data class PracticeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val gameId: String,
    val ply: Int,
    val fen: String,
    /** "w" or "b". */
    val sideToMove: String,
    val playedUci: String,
    val playedSan: String,
    val solutionUci: String,
    val solutionSan: String,
    /** Other moves within a small margin of the best one, comma separated UCI. */
    val acceptedUci: String,
    val solutionLine: String,
    val classification: String,
    val themes: String,
    val explanation: String,
    val scoreBefore: String,
    val scoreAfter: String,
    val createdAt: Long,
    /** Leitner box 0..5; higher means reviewed less often. */
    val box: Int = 0,
    val dueAt: Long,
    val attempts: Int = 0,
    val correct: Int = 0,
    val lastAttemptAt: Long? = null,
    val lastCorrect: Boolean? = null,
    val starred: Boolean = false,
)

@Entity(
    tableName = "practice_attempts",
    foreignKeys = [ForeignKey(entity = PracticeEntity::class, parentColumns = ["id"], childColumns = ["positionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("positionId"), Index("at")],
)
data class PracticeAttemptEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val positionId: Long,
    val at: Long,
    val correct: Boolean,
)
