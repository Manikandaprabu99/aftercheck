package app.aftercheck.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "game_reviews")
data class GameReviewEntity(
    @PrimaryKey val gameUrl: String,
    val evaluatedPlies: Int,
    val accuracyWhite: Double,
    val accuracyBlack: Double,
    val bestMoves: Int,
    val excellentMoves: Int,
    val goodMoves: Int,
    val inaccuracies: Int,
    val mistakes: Int,
    val blunders: Int,
    val finalEvaluationWhiteCp: Int,
    val engineDepth: Int,
    val createdAtEpochMs: Long = System.currentTimeMillis()
)
