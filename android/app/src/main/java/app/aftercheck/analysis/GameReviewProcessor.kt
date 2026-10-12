package app.aftercheck.analysis

import android.content.Context
import app.aftercheck.data.GameDao
import app.aftercheck.data.GameReviewEntity
import app.aftercheck.data.ChessGameEntity
import app.aftercheck.data.MoveAnalysisEntity
import kotlin.math.exp

object GameReviewProcessor {
    suspend fun review(
        context: Context,
        dao: GameDao,
        game: ChessGameEntity,
        depth: Int = 10,
        onProgress: suspend (Int, Int) -> Unit = { _, _ -> }
    ) {
        val timeline = PgnTimelineParser.parse(game.pgn)
        require(timeline.isNotEmpty()) { "This game has no replayable standard-chess moves." }
        val fens = timeline.map { it.fenBefore } + timeline.last().fenAfter
        val scores = StockfishEngine.analyzeFens(context, fens, depth, onProgress)
        require(scores.size == fens.size) { "Stockfish returned an incomplete evaluation list." }

        val moveRows = timeline.mapIndexed { index, move ->
            val before = scores[index].scoreFromWhitePerspective(move.fenBefore)
            val after = scores[index + 1].scoreFromWhitePerspective(move.fenAfter)
            val loss = if (move.sideToMove == "WHITE") {
                (before - after).coerceAtLeast(0)
            } else {
                (after - before).coerceAtLeast(0)
            }
            MoveAnalysisEntity(
                gameUrl = game.url,
                plyNumber = move.plyNumber,
                san = move.san,
                sideToMove = move.sideToMove,
                fenBefore = move.fenBefore,
                fenAfter = move.fenAfter,
                bestMoveUci = scores[index].bestMoveUci,
                principalVariationUci = scores[index].principalVariationUci,
                evaluationBeforeWhiteCp = before,
                evaluationAfterWhiteCp = after,
                centipawnLoss = loss,
                classification = classify(loss),
                depth = scores[index].depth
            )
        }
        dao.deleteMoveAnalyses(game.url)
        dao.upsertMoveAnalyses(moveRows)

        val whiteLosses = moveRows.filter { it.sideToMove == "WHITE" }.map { it.centipawnLoss }
        val blackLosses = moveRows.filter { it.sideToMove == "BLACK" }.map { it.centipawnLoss }
        val summary = GameReviewEntity(
            gameUrl = game.url,
            evaluatedPlies = moveRows.size,
            accuracyWhite = estimateAccuracy(whiteLosses),
            accuracyBlack = estimateAccuracy(blackLosses),
            bestMoves = moveRows.count { it.classification == "BEST" },
            excellentMoves = moveRows.count { it.classification == "EXCELLENT" },
            goodMoves = moveRows.count { it.classification == "GOOD" },
            inaccuracies = moveRows.count { it.classification == "INACCURACY" },
            mistakes = moveRows.count { it.classification == "MISTAKE" },
            blunders = moveRows.count { it.classification == "BLUNDER" },
            finalEvaluationWhiteCp = scores.last().scoreFromWhitePerspective(fens.last()),
            engineDepth = depth
        )
        dao.upsertReview(summary)
        dao.setReviewStatus(game.url, "READY")
    }

    internal fun classify(lossCp: Int): String = when {
        lossCp <= 10 -> "BEST"
        lossCp <= 25 -> "EXCELLENT"
        lossCp <= 50 -> "GOOD"
        lossCp <= 100 -> "INACCURACY"
        lossCp <= 200 -> "MISTAKE"
        else -> "BLUNDER"
    }

    private fun estimateAccuracy(losses: List<Int>): Double =
        if (losses.isEmpty()) 0.0 else losses.map { 100.0 * exp(-it.coerceAtLeast(0) / 350.0) }.average()
}
