package app.aftercheck.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertTrue
import org.junit.Test

class StockfishEngineTest {
    @Test
    fun parsesCentipawnInfoAndPrincipalVariation() {
        val result = StockfishEngine.parseInfo(
            "info depth 12 seldepth 18 multipv 1 score cp -23 nodes 123456 nps 123456 time 1000 pv e2e4 e7e5 g1f3",
            "bestmove e2e4 ponder e7e5"
        )
        assertEquals(12, result.depth)
        assertEquals(-23, result.scoreCp)
        assertTrue(result.mateIn == null)
        assertEquals("e2e4", result.bestMoveUci)
        assertEquals("e2e4 e7e5 g1f3", result.principalVariationUci)
    }

    @Test
    fun convertsSideToMoveScoresToWhitePerspective() {
        val whiteToMove = EnginePositionResult(10, "e2e4", 75, null, "e2e4")
        val blackToMove = EnginePositionResult(10, "e7e5", 75, null, "e7e5")
        assertEquals(75, whiteToMove.scoreFromWhitePerspective("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"))
        assertEquals(-75, blackToMove.scoreFromWhitePerspective("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR b KQkq - 0 1"))
    }

    @Test
    fun parsesMateScore() {
        val result = StockfishEngine.parseInfo(
            "info depth 16 score mate 3 nodes 543 pv f7f8",
            "bestmove f7f8"
        )
        assertEquals(3, result.mateIn)
        assertTrue(result.scoreCp == null)
    }
}
