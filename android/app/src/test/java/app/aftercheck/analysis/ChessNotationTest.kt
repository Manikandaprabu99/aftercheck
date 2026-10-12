package app.aftercheck.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChessNotationTest {
    private val startFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

    @Test
    fun convertsBestMoveToSan() {
        assertEquals("e4", ChessNotation.uciToSan(startFen, "e2e4"))
    }

    @Test
    fun convertsPrincipalVariationToSan() {
        val san = ChessNotation.uciVariationToSan(startFen, "e2e4 e7e5 g1f3 b8c6")
        assertTrue(san.contains("e4"))
        assertTrue(san.contains("e5"))
        assertTrue(san.contains("Nf3"))
        assertTrue(san.contains("Nc6"))
    }

    @Test
    fun refusesInvalidBestMove() {
        assertEquals("", ChessNotation.uciToSan(startFen, "(none)"))
    }
}
