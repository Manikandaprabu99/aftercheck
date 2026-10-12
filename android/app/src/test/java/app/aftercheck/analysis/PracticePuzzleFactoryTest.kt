package app.aftercheck.analysis

import app.aftercheck.data.MoveAnalysisEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PracticePuzzleFactoryTest {
    private val startFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

    private fun sample(bestMove: String = "e2e4") = MoveAnalysisEntity(
        gameUrl = "https://www.chess.com/game/live/123",
        plyNumber = 1,
        san = "d4",
        sideToMove = "WHITE",
        fenBefore = startFen,
        fenAfter = startFen,
        bestMoveUci = bestMove,
        bestMoveSan = "e4",
        principalVariationUci = "e2e4 e7e5",
        principalVariationSan = "1. e4 e5",
        evaluationBeforeWhiteCp = 0,
        evaluationAfterWhiteCp = -100,
        centipawnLoss = 100,
        classification = "MISTAKE",
        depth = 10
    )

    @Test
    fun returnsOnlyLegalDistinctChoicesAndOneEngineBest() {
        val options = PracticePuzzleFactory.options(sample(), optionCount = 4)
        assertEquals(4, options.size)
        assertEquals(4, options.map { it.uci }.distinct().size)
        assertEquals(1, options.count { it.isBestMove })
        assertTrue(options.all { it.san.isNotBlank() })
        assertTrue(options.any { it.uci == "e2e4" && it.isBestMove })
    }

    @Test
    fun returnsNoChoicesWhenRecordedBestMoveIsNotLegal() {
        assertTrue(PracticePuzzleFactory.options(sample("a1a8")).isEmpty())
    }
}
