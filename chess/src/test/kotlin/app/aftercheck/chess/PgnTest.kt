package app.aftercheck.chess

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PgnTest {

    @Test fun parsesChessComStylePgnWithClocks() {
        val game = Pgn.parse(Fixtures.OPERA_GAME)
        assertEquals("Paul Morphy", game.tags["White"])
        assertEquals("1-0", game.result)
        assertEquals(33, game.moves.size)
        assertEquals("e4", game.moves[0].san)
        assertEquals(179_500L, game.moves[0].clockMillis)
        assertEquals(178_000L, game.moves[1].clockMillis)
        assertEquals("Rd8#", game.moves.last().san)
        assertEquals("C41", game.tag("ECO"))
        assertNull(game.tag("Round"))
    }

    @Test fun replaysToCheckmate() {
        val replay = GameLine.fromPgn(Pgn.parse(Fixtures.OPERA_GAME)) as ReplayResult.Success
        val line = replay.line
        assertEquals(33, line.plyCount)
        assertTrue(line.final.isCheckmate)
        assertEquals(Outcome(Side.WHITE, Termination.CHECKMATE), line.outcomeAt(33))
        assertEquals("O-O-O", line.sans[22])
        assertEquals("Nbd7", line.sans[21])
    }

    @Test fun skipsCommentsVariationsAndNags() {
        val pgn = """
            [Event "Test"]
            [Result "*"]

            1. e4 {best by test} e5 (1... c5 2. Nf3 (2. c3) d6) 2. Nf3 $1 Nc6 ; line comment
            3. Bb5!? a6 *
        """.trimIndent()
        val game = Pgn.parse(pgn)
        assertEquals(listOf("e4", "e5", "Nf3", "Nc6", "Bb5", "a6"), game.moves.map { it.san })
        assertEquals("*", game.result)
    }

    @Test fun splitsMultiGameText() {
        val text = Fixtures.OPERA_GAME + "\n\n" + Fixtures.SCHOLARS_MATE
        val games = Pgn.parseAll(text)
        assertEquals(2, games.size)
        assertEquals(7, games[1].moves.size)
    }

    @Test fun reportsIllegalMoves() {
        val game = Pgn.parse("[Result \"*\"]\n\n1. e4 e5 2. Ke3 *")
        val result = GameLine.fromPgn(game)
        assertTrue(result is ReplayResult.Failure)
        assertEquals(3, (result as ReplayResult.Failure).ply)
    }

    @Test fun usesSetUpFen() {
        val pgn = """
            [SetUp "1"]
            [FEN "4k3/8/8/8/8/8/8/R3K3 w Q - 0 1"]
            [Result "1-0"]

            1. Ra8+ Kd7 1-0
        """.trimIndent()
        val line = (GameLine.fromPgn(Pgn.parse(pgn)) as ReplayResult.Success).line
        assertEquals(2, line.plyCount)
        assertEquals("Ra8+", line.sans[0])
    }

    @Test fun detectsThreefoldRepetition() {
        val sans = listOf("Nf3", "Nf6", "Ng1", "Ng8", "Nf3", "Nf6", "Ng1", "Ng8")
        val line = (GameLine.replay(Position.initial, sans) as ReplayResult.Success).line
        assertEquals(Termination.THREEFOLD_REPETITION, line.outcomeAt(8)?.termination)
        assertNull(line.outcomeAt(4))
    }

    @Test fun openingBookRecognisesTranspositions() {
        val tsv = """
            eco	name	pgn
            C20	King's Pawn Game	1. e4 e5
            C40	King's Knight Opening	1. e4 e5 2. Nf3
            C44	King's Pawn Game: Tayler Opening	1. e4 e5 2. Nf3 Nc6 3. Be2
            A04	Zukertort Opening	1. Nf3
        """.trimIndent()
        val book = OpeningBook.parse(tsv.reader())
        assertEquals(4, book.size)
        // 1. Nf3 e5?? is not in the book, but 1. Nf3 Nc6 2. e4 e5 transposes to the Tayler position path.
        val line = (GameLine.replay(Position.initial, listOf("e4", "e5", "Nf3", "Nc6", "Be2", "Nf6")) as ReplayResult.Success).line
        val match = book.match(line)
        assertEquals("C44", match.opening?.eco)
        assertEquals(5, match.bookPlies)
        val transposed = (GameLine.replay(Position.initial, listOf("Nf3", "Nc6", "e4", "e5", "Be2")) as ReplayResult.Success).line
        assertEquals("C44", book.match(transposed).opening?.eco)
    }
}

object Fixtures {
    val OPERA_GAME = """
        [Event "Live Chess"]
        [Site "Chess.com"]
        [Date "2024.01.15"]
        [Round "-"]
        [White "Paul Morphy"]
        [Black "Duke Karl"]
        [Result "1-0"]
        [ECO "C41"]
        [ECOUrl "https://www.chess.com/openings/Philidor-Defense"]
        [WhiteElo "2690"]
        [BlackElo "1500"]
        [TimeControl "180"]
        [Termination "Paul Morphy won by checkmate"]

        1. e4 {[%clk 0:02:59.5]} 1... e5 {[%clk 0:02:58]} 2. Nf3 {[%clk 0:02:58.1]} 2... d6 {[%clk 0:02:55.4]}
        3. d4 {[%clk 0:02:57]} 3... Bg4 {[%clk 0:02:50]} 4. dxe5 {[%clk 0:02:55]} 4... Bxf3 {[%clk 0:02:47]}
        5. Qxf3 {[%clk 0:02:54]} 5... dxe5 {[%clk 0:02:45]} 6. Bc4 {[%clk 0:02:50]} 6... Nf6 {[%clk 0:02:40]}
        7. Qb3 {[%clk 0:02:45]} 7... Qe7 {[%clk 0:02:30]} 8. Nc3 {[%clk 0:02:40]} 8... c6 {[%clk 0:02:20]}
        9. Bg5 {[%clk 0:02:35]} 9... b5 {[%clk 0:02:10]} 10. Nxb5 {[%clk 0:02:30]} 10... cxb5 {[%clk 0:02:05]}
        11. Bxb5+ {[%clk 0:02:25]} 11... Nbd7 {[%clk 0:02:00]} 12. O-O-O {[%clk 0:02:20]} 12... Rd8 {[%clk 0:01:50]}
        13. Rxd7 {[%clk 0:02:15]} 13... Rxd7 {[%clk 0:01:45]} 14. Rd1 {[%clk 0:02:10]} 14... Qe6 {[%clk 0:01:40]}
        15. Bxd7+ {[%clk 0:02:05]} 15... Nxd7 {[%clk 0:01:35]} 16. Qb8+ {[%clk 0:02:00]} 16... Nxb8 {[%clk 0:01:30]}
        17. Rd8# {[%clk 0:01:55]} 1-0
    """.trimIndent()

    val SCHOLARS_MATE = """
        [Event "Live Chess"]
        [White "alpha"]
        [Black "beta"]
        [Result "1-0"]
        [TimeControl "600+5"]

        1. e4 {[%clk 0:10:00]} 1... e5 {[%clk 0:10:00]} 2. Bc4 {[%clk 0:09:58]} 2... Nc6 {[%clk 0:09:55]}
        3. Qh5 {[%clk 0:09:50]} 3... Nf6 {[%clk 0:09:40]} 4. Qxf7# {[%clk 0:09:45]} 1-0
    """.trimIndent()
}
