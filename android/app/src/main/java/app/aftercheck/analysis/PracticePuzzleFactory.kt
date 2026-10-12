package app.aftercheck.analysis

import app.aftercheck.data.MoveAnalysisEntity
import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.move.MoveGenerator
import kotlin.random.Random

data class PracticeMoveOption(
    val uci: String,
    val san: String,
    val isBestMove: Boolean
)

/**
 * Builds answer options from legal moves in the recorded position. Distractors are
 * legal moves, never fabricated chess notation. Candidate order is stable per position.
 */
object PracticePuzzleFactory {
    fun options(position: MoveAnalysisEntity, optionCount: Int = 4): List<PracticeMoveOption> {
        require(optionCount in 2..8) { "Option count must be between 2 and 8." }
        val best = position.bestMoveUci
        if (best.isBlank()) return emptyList()

        val board = runCatching {
            Board().apply { loadFromFen(position.fenBefore) }
        }.getOrNull() ?: return emptyList()

        val legalUci = runCatching {
            MoveGenerator.generateLegalMoves(board).map { it.toString() }.distinct()
        }.getOrDefault(emptyList())

        if (best !in legalUci) return emptyList()
        val selected = (listOf(best) + legalUci.filterNot { it == best }.sorted())
            .distinct()
            .take(optionCount)

        return selected.mapNotNull { uci ->
            val san = ChessNotation.uciToSan(position.fenBefore, uci)
            if (san.isBlank()) null else PracticeMoveOption(uci, san, uci == best)
        }.shuffled(Random((position.gameUrl + ":" + position.plyNumber).hashCode()))
    }
}
