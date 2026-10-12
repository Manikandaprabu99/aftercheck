package app.aftercheck.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PgnArchiveParserTest {
    @Test
    fun parsesTwoGamesAndTheirMetadata() {
        val pgn = """
            [Event "Live Chess"]
            [Site "Chess.com"]
            [Date "2026.10.10"]
            [Round "-"]
            [White "ExampleWhite"]
            [Black "ExampleBlack"]
            [Result "1-0"]
            [ECO "C20"]
            [Opening "King's Pawn Game"]
            [TimeControl "600"]
            [Link "https://www.chess.com/game/live/123"]

            1. e4 e5 2. Qh5 Nc6 1-0

            [Event "Live Chess"]
            [Date "2026.10.11"]
            [White "AnotherWhite"]
            [Black "AnotherBlack"]
            [Result "0-1"]
            [Link "https://www.chess.com/game/live/456"]

            1. d4 d5 0-1
        """.trimIndent()

        val games = parsePgnArchive("tester", "https://api.chess.com/pub/player/tester/games/2026/10", pgn, 10L)
        assertEquals(2, games.size)
        assertEquals("ExampleWhite", games[0].white)
        assertEquals("1-0", games[0].result)
        assertEquals("C20", games[0].eco)
        assertTrue(games[0].pgn.contains("1. e4"))
        assertEquals("https://www.chess.com/game/live/456", games[1].url)
    }

    @Test
    fun skipsEmptyPgn() {
        assertTrue(parsePgnArchive("tester", "https://api.chess.com/pub/player/tester/games/2026/10", "", 10L).isEmpty())
    }
}
