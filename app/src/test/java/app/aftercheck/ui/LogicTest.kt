package app.aftercheck.ui

import app.aftercheck.chess.Move
import app.aftercheck.chess.Position
import app.aftercheck.chess.analysis.AlternativeLine
import app.aftercheck.chess.analysis.EngineLine
import app.aftercheck.chess.analysis.MoveClass
import app.aftercheck.chess.analysis.PositionEval
import app.aftercheck.chess.analysis.Score
import app.aftercheck.chess.analysis.Theme
import app.aftercheck.data.db.InsightRow
import app.aftercheck.data.db.PracticeEntity
import app.aftercheck.engine.ReviewCodec
import app.aftercheck.ui.components.Format
import app.aftercheck.ui.insights.InsightsCalculator
import app.aftercheck.ui.practice.SpacedReview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpacedReviewTest {
    private val base = PracticeEntity(
        gameId = "g", ply = 10, fen = Position.START_FEN, sideToMove = "w", playedUci = "e2e4", playedSan = "e4",
        solutionUci = "d2d4", solutionSan = "d4", acceptedUci = "c2c4,g1f3", solutionLine = "d4 d5", classification = "MISTAKE",
        themes = "", explanation = "", scoreBefore = "cp:0", scoreAfter = "cp:-120", createdAt = 0, dueAt = 0,
    )

    @Test fun acceptsBestAndNearlyEqualMoves() {
        assertTrue(SpacedReview.isCorrect(base, "d2d4"))
        assertTrue(SpacedReview.isCorrect(base, "g1f3"))
        assertFalse(SpacedReview.isCorrect(base, "e2e4"))
        assertFalse(SpacedReview.isCorrect(base.copy(acceptedUci = ""), ""))
    }

    @Test fun correctAnswersSpaceOutWrongOnesComeBackSoon() {
        val now = 1_000_000L
        val once = SpacedReview.schedule(base, correct = true, now = now)
        assertEquals(1, once.box)
        assertEquals(now + SpacedReview.INTERVALS[1], once.dueAt)
        assertEquals(1, once.correct)
        val twice = SpacedReview.schedule(once, correct = true, now = now)
        assertEquals(2, twice.box)
        val missed = SpacedReview.schedule(twice, correct = false, now = now)
        assertEquals(0, missed.box)
        assertEquals(now + SpacedReview.INTERVALS[0], missed.dueAt)
        assertEquals(3, missed.attempts)
        assertEquals(false, missed.lastCorrect)
        var p = base
        repeat(10) { p = SpacedReview.schedule(p, true, now) }
        assertEquals(SpacedReview.INTERVALS.lastIndex, p.box)
    }
}

class InsightsCalculatorTest {
    private fun row(i: Int, result: String, acc: Double?, tc: String = "blitz", color: String = "w", opening: String? = "Italian Game", blunders: Int = 1) =
        InsightRow("g$i", i * 1000L, result, color, tc, opening, acc, 2, 1, blunders, 0, 60, if (acc != null) "ready" else "queued")

    @Test fun aggregatesResultsAccuracyAndSplits() {
        val rows = listOf(
            row(1, "win", 80.0), row(2, "loss", 60.0, tc = "rapid", color = "b", opening = "Sicilian Defense"),
            row(3, "draw", 70.0), row(4, "win", null),
        )
        val d = InsightsCalculator.compute(rows, listOf("HANGING_PIECE,OPENING", "HANGING_PIECE"))
        assertEquals(4, d.overall.games)
        assertEquals(2, d.overall.wins)
        assertEquals((2 + 0.5) / 4, d.overall.score, 1e-9)
        assertEquals(3, d.reviewed)
        assertEquals(listOf(1000L, 2000L, 3000L), d.trend.map { it.first })
        assertEquals(1.0, d.avgBlunders, 1e-9)
        assertEquals("blitz", d.byTimeClass.first().first)
        assertEquals(3, d.byTimeClass.first().second.games)
        assertEquals("Italian Game", d.openingsWhite.single().name)
        assertEquals("Sicilian Defense", d.openingsBlack.single().name)
        assertEquals(Theme.HANGING_PIECE, d.weaknesses.first().theme)
        assertEquals(2, d.weaknesses.first().count)
        assertNull(d.improvement)
    }

    @Test fun improvementComparesLastTenWithPreviousTen() {
        val rows = (1..20).map { i -> row(i, "win", if (i > 10) 85.0 else 70.0, blunders = if (i > 10) 0 else 2) }
        val imp = InsightsCalculator.compute(rows, emptyList()).improvement
        assertNotNull(imp)
        assertEquals(85.0, imp!!.recentAccuracy, 1e-9)
        assertEquals(70.0, imp.previousAccuracy, 1e-9)
        assertEquals(0.0, imp.recentBlunders, 1e-9)
        assertEquals(2.0, imp.previousBlunders, 1e-9)
    }
}

class ReviewCodecTest {
    @Test fun evalRoundTripKeepsOnlyLegalMoves() {
        val pos = Position.initial
        val eval = PositionEval(
            Score.Cp(25),
            listOf(
                EngineLine(Score.Cp(25), listOf(Move.fromUci("e2e4")!!, Move.fromUci("e7e5")!!), 18),
                EngineLine(Score.Mate(-3), listOf(Move.fromUci("d2d4")!!), 17),
            ),
            18,
        )
        val decoded = ReviewCodec.decodeEval(ReviewCodec.encodeEval(eval), pos)!!
        assertEquals(eval, decoded)
        assertNull(ReviewCodec.decodeEval("18\ncp:10|18|e7e5", pos)) // illegal for White to move
    }

    @Test fun decodesStoredLists() {
        assertEquals(mapOf(MoveClass.BEST to 12, MoveClass.BLUNDER to 1), ReviewCodec.decodeCounts("BEST:12,BLUNDER:1,NOPE:3"))
        assertEquals(listOf(0.5f, 0.731f), ReviewCodec.decodeWinChances("500,731"))
        assertEquals(listOf(AlternativeLine(listOf("Nf3", "Nc6"), Score.Cp(-12))), ReviewCodec.decodeAlternatives("cp:-12|Nf3 Nc6"))
        assertEquals(1, ReviewCodec.decodeRecommendations("FORK|2|Watch forks\nBAD|1|x").size)
    }
}

class FormatTest {
    @Test fun formatsTimeControlsAndClocks() {
        assertEquals("3+2", Format.timeControl("180+2"))
        assertEquals("10 min", Format.timeControl("600"))
        assertEquals("1 day", Format.timeControl("1/86400"))
        assertEquals("30s", Format.timeControl("30"))
        assertEquals("2:59", Format.clock(179_500))
        assertEquals("0:09.5", Format.clock(9_500))
        assertEquals("1:00:00", Format.clock(3_600_000))
        assertEquals("1.2 MB", Format.bytes(1_234_567))
    }
}
