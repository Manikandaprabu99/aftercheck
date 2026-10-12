package app.aftercheck.data

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "move_analyses",
    primaryKeys = ["gameUrl", "plyNumber"],
    indices = [Index("gameUrl")]
)
data class MoveAnalysisEntity(
    val gameUrl: String,
    val plyNumber: Int,
    val san: String,
    val sideToMove: String,
    val fenBefore: String,
    val fenAfter: String,
    val bestMoveUci: String,
    val principalVariationUci: String,
    val evaluationBeforeWhiteCp: Int,
    val evaluationAfterWhiteCp: Int,
    val centipawnLoss: Int,
    val classification: String,
    val depth: Int
)
