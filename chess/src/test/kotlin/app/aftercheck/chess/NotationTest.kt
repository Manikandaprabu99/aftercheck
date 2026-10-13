package app.aftercheck.chess

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotationTest {

    private fun sanOf(fen: String, uci: String): String {
        val pos = Position.fromFen(fen)
        return pos.san(Move.fromUci(uci)!!)
    }

    @Test fun fenRoundTrips() {
        listOf(
            Position.START_FEN,
            "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1",
            "rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq e6 0 2",
        ).forEach { assertEquals(it, Position.fromFen(it).toFen()) }
    }

    @Test fun rejectsMalformedFen() {
        listOf("", "8/8/8/8/8/8/8/8 w - -", "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP w KQkq - 0 1", "xnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1")
            .forEach { fen -> assertTrue(runCatching { Position.fromFen(fen) }.isFailure) }
    }

    @Test fun basicSan() {
        assertEquals("e4", sanOf(Position.START_FEN, "e2e4"))
        assertEquals("Nf3", sanOf(Position.START_FEN, "g1f3"))
    }

    @Test fun castlingSan() {
        val fen = "r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1"
        assertEquals("O-O", sanOf(fen, "e1g1"))
        assertEquals("O-O-O", sanOf(fen, "e1c1"))
    }

    @Test fun disambiguation() {
        // Knights on b1 and f3 can both reach d2; rooks on a1 and a5 both reach a3.
        val fen = "4k3/8/8/R7/8/5N2/8/RN2K3 w - - 0 1"
        assertEquals("Nbd2", sanOf(fen, "b1d2"))
        assertEquals("Nfd2", sanOf(fen, "f3d2"))
        assertEquals("R1a3", sanOf(fen, "a1a3"))
        assertEquals("R5a3", sanOf(fen, "a5a3"))
        // Queens on a4, h4 and a1 all reach d4: a4 shares a file with a1 and a rank with h4.
        val queens = "6k1/8/8/8/Q6Q/8/8/Q3K3 w - - 0 1"
        assertEquals("Qa4d4", sanOf(queens, "a4d4"))
        assertEquals("Qhd4", sanOf(queens, "h4d4"))
        assertEquals("Q1d4", sanOf(queens, "a1d4"))
        assertEquals(Move.fromUci("a4d4"), Position.fromFen(queens).parseSan("Qa4d4"))
    }

    @Test fun promotionAndEnPassant() {
        assertEquals("e8=Q", sanOf("8/4P3/8/8/8/8/8/k3K3 w - - 0 1", "e7e8q"))
        assertEquals("e8=R", sanOf("8/4P3/8/8/8/8/8/k3K3 w - - 0 1", "e7e8r"))
        assertEquals("exd8=N", sanOf("3r4/4P3/8/8/8/8/8/k3K3 w - - 0 1", "e7d8n"))
        assertEquals("exd8=N+", sanOf("3r4/4P3/2k5/8/8/8/8/4K3 w - - 0 1", "e7d8n"))
        assertEquals("exd6", sanOf("4k3/8/8/3pP3/8/8/8/4K3 w - d6 0 1", "e5d6"))
        val pos = Position.fromFen("4k3/8/8/3pP3/8/8/8/4K3 w - d6 0 1")
        val after = pos.play(Move.fromUci("e5d6")!!)
        assertNull(after.pieceAt(Squares.parse("d5")!!))
        assertEquals(PieceType.PAWN, after.pieceAt(Squares.parse("d6")!!)?.type)
    }

    @Test fun checkAndMateSuffixes() {
        // Scholar's mate final move.
        val pos = Position.fromFen("r1bqkb1r/pppp1ppp/2n2n2/4p2Q/2B1P3/8/PPPP1PPP/RNB1K1NR w KQkq - 4 4")
        assertEquals("Qxf7#", pos.san(Move.fromUci("h5f7")!!))
        val check = Position.fromFen("4k3/8/8/8/8/8/8/R3K3 w - - 0 1")
        assertEquals("Ra8+", check.san(Move.fromUci("a1a8")!!))
    }

    @Test fun parsesLenientSan() {
        val start = Position.initial
        assertEquals(Move.fromUci("e2e4"), start.parseSan("e4"))
        assertEquals(Move.fromUci("g1f3"), start.parseSan("Nf3!?"))
        assertEquals(Move.fromUci("g1f3"), start.parseSan("Ng1f3"))
        assertEquals(Move.fromUci("g1f3"), start.parseSan("g1f3"))
        assertNull(start.parseSan("e5"))
        assertNull(start.parseSan("Qh5"))
        val castle = Position.fromFen("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1")
        assertEquals(Move.fromUci("e1g1"), castle.parseSan("0-0"))
        assertEquals(Move.fromUci("e1c1"), castle.parseSan("O-O-O+"))
        val promo = Position.fromFen("8/4P3/8/8/8/8/8/k3K3 w - - 0 1")
        assertEquals(Move.fromUci("e7e8n"), promo.parseSan("e8=N"))
        assertEquals(Move.fromUci("e7e8q"), promo.parseSan("e8Q"))
        assertEquals(Move.fromUci("e7e8q"), promo.parseSan("e8"))
        // Pawn capture "bxc3" vs bishop capture "Bxc3".
        val bc = Position.fromFen("4k3/8/8/4B3/8/2p5/1P6/4K3 w - - 0 1")
        assertEquals(Move.fromUci("b2c3"), bc.parseSan("bxc3"))
        assertEquals(Move.fromUci("e5c3"), bc.parseSan("Bxc3"))
    }

    @Test fun ambiguousSanIsRejected() {
        val fen = "4k3/8/8/R7/8/5N2/8/RN2K3 w - - 0 1"
        assertNull(Position.fromFen(fen).parseSan("Nd2"))
        assertNull(Position.fromFen(fen).parseSan("Ra3"))
    }

    @Test fun pinnedPieceCannotMove() {
        // The e2 knight is pinned by the rook on e8.
        val pos = Position.fromFen("4r1k1/8/8/8/8/8/4N3/4K3 w - - 0 1")
        assertFalse(pos.legalMoves().any { it.from == Squares.parse("e2") })
    }

    @Test fun formatsLinesWithMoveNumbers() {
        val pos = Position.fromFen("rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1")
        assertEquals("1...e5 2. Nf3 Nc6", formatLine(pos, listOf("e5", "Nf3", "Nc6")))
        assertEquals("1. e4 e5", formatLine(Position.initial, listOf("e4", "e5")))
        assertEquals(listOf("e5", "Nf3"), pos.uciLineToSan(listOf("e7e5", "g1f3", "zzzz")))
    }

    @Test fun drawDetection() {
        assertTrue(Position.fromFen("8/8/8/8/8/8/8/k1K5 w - - 0 1").isInsufficientMaterial())
        assertTrue(Position.fromFen("8/8/8/8/8/8/8/kbK5 w - - 0 1").isInsufficientMaterial())
        assertFalse(Position.fromFen("8/8/8/8/8/8/8/kbKN4 w - - 0 1").isInsufficientMaterial())
        // Bishops on b1 and d1 share a colour complex: no mate possible.
        assertTrue(Position.fromFen("8/8/8/8/8/8/8/kbKB4 w - - 0 1").isInsufficientMaterial())
        // Bishops on opposite colours can still mate in theory.
        assertFalse(Position.fromFen("8/8/8/8/8/8/8/kbK1B3 w - - 0 1").isInsufficientMaterial())
        val stalemate = Position.fromFen("7k/5Q2/6K1/8/8/8/8/8 b - - 0 1")
        assertTrue(stalemate.isStalemate)
        assertEquals(Termination.STALEMATE, stalemate.outcome()?.termination)
        val mate = Position.fromFen("7k/6Q1/6K1/8/8/8/8/8 b - - 0 1")
        assertEquals(Outcome(Side.WHITE, Termination.CHECKMATE), mate.outcome())
        assertEquals(Termination.FIFTY_MOVES, Position.fromFen("4k3/8/8/8/8/8/8/R3K3 w - - 100 80").outcome()?.termination)
    }

    @Test fun epdOnlyIncludesCapturableEnPassant() {
        val noCapture = Position.fromFen("rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1")
        assertTrue(noCapture.epd().endsWith(" -"))
        val capture = Position.fromFen("4k3/8/8/3pP3/8/8/8/4K3 w - d6 0 1")
        assertTrue(capture.epd().endsWith(" d6"))
    }
}
