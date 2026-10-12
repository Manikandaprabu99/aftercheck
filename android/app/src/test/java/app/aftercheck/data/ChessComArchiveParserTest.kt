package app.aftercheck.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChessComArchiveParserTest {
    private val archiveUrl = "https://api.chess.com/pub/player/tester/games/2026/10"

    @Test
    fun parsesRatedOnlyWhenExplicitlyDeclared() {
        val pgn = """
            [Event "Live Chess"]
            [White "tester"]
            [Black "opponent"]
            [Result "1-0"]
            [Rated "True"]
            [Link "https://www.chess.com/game/live/123"]

            1. e4 e5 2. Nf3 1-0

            [Event "Live Chess"]
            [White "tester"]
            [Black "opponent2"]
            [Result "0-1"]
            [Link "https://www.chess.com/game/live/456"]

            1. d4 d5 0-1
        """.trimIndent()

        val games = parsePgnArchive("tester", archiveUrl, pgn)
        assertEquals(2, games.size)
        assertTrue(games[0].rated)
        assertFalse(games[1].rated)
    }

    @Test
    fun ignoresPgnRecordsWithoutPlayerHeaders() {
        val pgn = """
            [Event "Live Chess"]
            [Result "*"]

            *
        """.trimIndent()
        assertTrue(parsePgnArchive("tester", archiveUrl, pgn).isEmpty())
    }
}
