package app.aftercheck.chess.analysis

import app.aftercheck.chess.Fixtures
import app.aftercheck.chess.GameLine
import app.aftercheck.chess.Move
import app.aftercheck.chess.Pgn
import app.aftercheck.chess.Position
import app.aftercheck.chess.ReplayResult
import app.aftercheck.chess.Side
import app.aftercheck.chess.engine.GameAnalyzer
import app.aftercheck.chess.engine.PositionEvaluator
import app.aftercheck.chess.engine.UciEngine
import app.aftercheck.chess.engine.UciPositionEvaluator
import app.aftercheck.chess.engine.UciSearchResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ScoreTest {

    @Test fun uciScoresAreConvertedToWhitePerspective() {
        assertEquals(Score.Cp(35), Score.fromUci(35, null, Side.WHITE))
        assertEquals(Score.Cp(-35), Score.fromUci(35, null, Side.BLACK))
        assertEquals(Score.Mate(3), Score.fromUci(null, 3, Side.WHITE))
        assertEquals(Score.Mate(-3), Score.fromUci(null, 3, Side.BLACK))
        assertEquals(Score.Mate(2), Score.fromUci(null, -2, Side.BLACK))
        // "mate 0": side to move is checkmated.
        assertEquals(Score.GameOver(Side.BLACK), Score.fromUci(null, 0, Side.WHITE))
    }

    @Test fun winChanceIsSymmetricAndBounded() {
        assertEquals(0.5, Score.Cp(0).whiteWinChance(), 1e-9)
        val plus = Score.Cp(150).whiteWinChance()
        val minus = Score.Cp(-150).whiteWinChance()
        assertEquals(1.0, plus + minus, 1e-9)
        assertTrue(plus > 0.6 && plus < 0.7)
        assertEquals(1.0, Score.Mate(4).whiteWinChance(), 0.0)
        assertEquals(0.0, Score.Mate(-1).whiteWinChance(), 0.0)
        assertEquals(0.5, Score.GameOver(null).whiteWinChance(), 0.0)
        assertEquals(Score.Cp(5000).whiteWinChance(), Score.Cp(1000).whiteWinChance(), 0.0)
        assertEquals(plus, Score.Cp(150).expectedPoints(Side.WHITE), 0.0)
        assertEquals(1 - plus, Score.Cp(150).expectedPoints(Side.BLACK), 1e-12)
    }

    @Test fun encodesAndDisplays() {
        listOf(Score.Cp(-42), Score.Mate(5), Score.Mate(-2), Score.GameOver(Side.WHITE), Score.GameOver(null))
            .forEach { assertEquals(it, Score.parse(it.encode())) }
        assertNull(Score.parse("mate:0"))
        assertEquals("+1.3", Score.Cp(125).display())
        assertEquals("−0.4", Score.Cp(-40).display())
        assertEquals("0.0", Score.Cp(3).display())
        assertEquals("M3", Score.Mate(3).display())
        assertEquals("−M2", Score.Mate(-2).display())
        assertEquals(3, Score.Mate(-3).mateFor(Side.BLACK))
        assertNull(Score.Mate(-3).mateFor(Side.WHITE))
    }
}

class ClassifierTest {
    private val classifier = MoveClassifier()

    private fun classify(
        mover: Side, best: Score, played: Score, isBest: Boolean = false, previous: MoveClass? = null, book: Boolean = false,
    ) = classifier.classify(ClassificationInput(mover, isBest, book, best, played, previous)).moveClass

    @Test fun thresholdsFromWhitePerspective() {
        assertEquals(MoveClass.BEST, classify(Side.WHITE, Score.Cp(40), Score.Cp(-300), isBest = true))
        assertEquals(MoveClass.EXCELLENT, classify(Side.WHITE, Score.Cp(40), Score.Cp(30)))
        assertEquals(MoveClass.GOOD, classify(Side.WHITE, Score.Cp(40), Score.Cp(0)))
        assertEquals(MoveClass.INACCURACY, classify(Side.WHITE, Score.Cp(40), Score.Cp(-50)))
        assertEquals(MoveClass.MISTAKE, classify(Side.WHITE, Score.Cp(40), Score.Cp(-150)))
        assertEquals(MoveClass.BLUNDER, classify(Side.WHITE, Score.Cp(40), Score.Cp(-400)))
    }

    @Test fun blackMovesUseBlackPerspective() {
        // Black blunders: evaluation swings in White's favour.
        assertEquals(MoveClass.BLUNDER, classify(Side.BLACK, Score.Cp(-40), Score.Cp(400)))
        // A big swing *towards* Black is not an error for Black.
        assertEquals(MoveClass.EXCELLENT, classify(Side.BLACK, Score.Cp(40), Score.Cp(-400)))
    }

    @Test fun missedMateAndMates() {
        assertEquals(MoveClass.MISSED_MATE, classify(Side.WHITE, Score.Mate(2), Score.Cp(600)))
        assertEquals(MoveClass.MISSED_MATE, classify(Side.BLACK, Score.Mate(-3), Score.Cp(0)))
        // A slower but still forced mate is fine.
        assertEquals(MoveClass.EXCELLENT, classify(Side.WHITE, Score.Mate(2), Score.Mate(4)))
        // Delivering mate is always best.
        assertEquals(MoveClass.BEST, classify(Side.WHITE, Score.Mate(1), Score.GameOver(Side.WHITE)))
        // Walking into a forced mate.
        assertEquals(MoveClass.BLUNDER, classify(Side.WHITE, Score.Cp(0), Score.Mate(-2)))
        // Allowing mate is at least a mistake even when already lost.
        assertEquals(MoveClass.MISTAKE, classify(Side.BLACK, Score.Cp(900), Score.Mate(2)))
    }

    @Test fun missedOpportunityAfterOpponentError() {
        // Opponent blundered; the best move keeps +4, the move played only +0.5.
        assertEquals(MoveClass.MISS, classify(Side.WHITE, Score.Cp(400), Score.Cp(50), previous = MoveClass.BLUNDER))
        // Same swing without an opponent error is just a mistake/blunder.
        assertEquals(MoveClass.BLUNDER, classify(Side.WHITE, Score.Cp(400), Score.Cp(50), previous = MoveClass.GOOD))
        // Collapsing into a losing position stays a blunder.
        assertEquals(MoveClass.BLUNDER, classify(Side.WHITE, Score.Cp(400), Score.Cp(-500), previous = MoveClass.BLUNDER))
    }

    @Test fun bookMoves() {
        assertEquals(MoveClass.BOOK, classify(Side.WHITE, Score.Cp(30), Score.Cp(-100), book = true))
    }
}

class AccuracyTest {
    @Test fun perfectMovesScoreHundred() {
        assertEquals(100.0, Accuracy.moveAccuracy(50.0, 50.0), 0.0)
        assertEquals(100.0, Accuracy.moveAccuracy(50.0, 60.0), 0.0)
        val small = Accuracy.moveAccuracy(55.0, 50.0)
        val big = Accuracy.moveAccuracy(80.0, 20.0)
        assertTrue(small in 80.0..100.0)
        assertTrue(big < 10.0)
    }

    @Test fun gameAccuracyPenalisesTheSideThatErred() {
        // White plays steadily; Black throws the game away at the end.
        val win = listOf(50.0, 52.0, 51.0, 53.0, 52.0, 54.0, 53.0, 55.0, 90.0, 95.0)
        val acc = Accuracy.gameAccuracy(win, Side.WHITE)
        val white = acc[Side.WHITE]!!
        val black = acc[Side.BLACK]!!
        assertTrue("white=$white", white > 95.0)
        assertTrue("black=$black", black < white - 10)
    }

    @Test fun emptyGameHasNoAccuracy() {
        assertNull(Accuracy.gameAccuracy(listOf(50.0), Side.WHITE)[Side.WHITE])
    }
}

class ReviewerTest {

    private val line = (GameLine.fromPgn(Pgn.parse(Fixtures.SCHOLARS_MATE)) as ReplayResult.Success).line

    private fun ev(score: Score, vararg pv: String, alt: Pair<Score, List<String>>? = null): PositionEval {
        val lines = mutableListOf(EngineLine(score, pv.map { Move.fromUci(it)!! }, 18))
        alt?.let { lines += EngineLine(it.first, it.second.map { m -> Move.fromUci(m)!! }, 18) }
        return PositionEval(score, lines, 18)
    }

    private val evals = listOf(
        ev(Score.Cp(30), "e2e4", "e7e5"),
        ev(Score.Cp(30), "e7e5", "g1f3"),
        ev(Score.Cp(35), "g1f3", "b8c6"),
        ev(Score.Cp(30), "g8f6", "d2d3"),
        ev(Score.Cp(40), "g1f3", "g8f6"),
        ev(Score.Cp(0), "g7g6", "h5f3", "g8f6", alt = Score.Cp(5) to listOf("d8e7")),
        ev(Score.Mate(1), "h5f7"),
        PositionEval.terminal(Score.GameOver(Side.WHITE)),
    )

    @Test fun reviewsScholarsMate() {
        val review = GameReviewer().review(
            line, evals, clocks = Pgn.parse(Fixtures.SCHOLARS_MATE).moves.map { it.clockMillis },
            baseTimeMillis = 600_000, incrementMillis = 5_000, focus = Side.BLACK,
        )
        val classes = review.moves.map { it.classification }
        assertEquals(MoveClass.BEST, classes[0])
        assertEquals(MoveClass.BEST, classes[1])
        assertEquals(MoveClass.EXCELLENT, classes[2])
        assertEquals(MoveClass.BLUNDER, classes[5])
        assertEquals(MoveClass.BEST, classes[6])

        val blunder = review.moves[5]
        assertEquals("Nf6", blunder.san)
        assertEquals(Side.BLACK, blunder.side)
        assertEquals("g6", blunder.bestSan)
        assertEquals(listOf("g6", "Qf3", "Nf6"), blunder.bestLine)
        assertEquals(listOf("Qxf7#"), blunder.replyLine)
        assertTrue(blunder.explanation, blunder.explanation.contains("allows a forced mate in 1"))
        assertTrue(Theme.ALLOWED_MATE in blunder.themes)
        assertTrue(Theme.OPENING in blunder.themes)
        // Qxf7# is not a back-rank mate: the queen mates from f7, not along the 8th rank.
        assertTrue(Theme.BACK_RANK !in blunder.themes)
        assertEquals(1, blunder.alternatives.size)
        assertEquals("Checkmate.", review.moves[6].explanation)
        // Black spent 20s on Nf6: 9:55 + 5s increment - 9:40.
        assertEquals(20_000L, blunder.timeSpentMillis)

        assertEquals(1, review.black.count(MoveClass.BLUNDER))
        assertTrue(review.white.accuracy!! > review.black.accuracy!!)
        assertEquals(Score.GameOver(Side.WHITE), review.finalScore)
        assertEquals(6, review.biggestSwingPly)
        assertTrue(6 in review.turningPoints)
        assertTrue(review.recommendations.any { it.theme == Theme.ALLOWED_MATE })
        assertEquals(line.plyCount + 1, review.winChances.size)
    }

    @Test fun bookMovesAreMarked() {
        val review = GameReviewer().review(line, evals, bookPlies = 2)
        assertEquals(MoveClass.BOOK, review.moves[0].classification)
        assertEquals(MoveClass.BOOK, review.moves[1].classification)
        assertEquals(MoveClass.EXCELLENT, review.moves[2].classification)
    }

    @Test fun analyzerSkipsTerminalPositionsAndReportsProgress() = runTest {
        var calls = 0
        val progress = mutableListOf<Int>()
        val evaluator = PositionEvaluator { _, index -> calls++; evals[index] }
        val result = GameAnalyzer(evaluator).evaluateAll(line) { done, _ -> progress += done }
        assertEquals(line.plyCount + 1, result.size)
        assertEquals(line.plyCount, calls) // final checkmate is not sent to the engine
        assertEquals((1..line.plyCount + 1).toList(), progress)
        assertEquals(Score.GameOver(Side.WHITE), result.last().score)
    }
}

class ExplainerTest {
    private val explainer = MoveExplainer()

    @Test fun hangingPieceIsNamed() {
        // White plays Nd5?? where the pawn on e6 takes it.
        val before = Position.fromFen("r1bqkbnr/pppp1ppp/2n1p3/8/4P3/2N5/PPPP1PPP/R1BQKBNR w KQkq - 0 3")
        val played = Move.fromUci("c3d5")!!
        val text = explainer.explain(
            MoveExplainer.Input(
                before, played, MoveClass.BLUNDER, Score.Cp(40), Score.Cp(-260),
                bestLine = listOf(Move.fromUci("g1f3")!!), replyLine = listOf(Move.fromUci("e6d5")!!, Move.fromUci("e4d5")!!),
                ply = 5, openingName = null, clockMillis = null, baseTimeMillis = null,
            ),
        )
        assertTrue(text.text, text.text.contains("knight on d5"))
        assertTrue(Theme.HANGING_PIECE in text.themes)
    }

    @Test fun forkIsDetectedOnBestMove() {
        // Nc7+ forks king on e8 and rook on a8.
        val before = Position.fromFen("r3k3/8/8/1N6/8/8/8/4K3 w - - 0 1")
        val best = Move.fromUci("b5c7")!!
        val targets = explainer.forkTargets(before.play(best), best.to)
        assertEquals(2, targets.size)
        val text = explainer.explain(
            MoveExplainer.Input(
                before, Move.fromUci("e1e2")!!, MoveClass.MISTAKE, Score.Cp(500), Score.Cp(0),
                bestLine = listOf(best, Move.fromUci("e8d8")!!, Move.fromUci("c7a8")!!),
                replyLine = listOf(Move.fromUci("a8a5")!!), ply = 40, openingName = null, clockMillis = null, baseTimeMillis = null,
            ),
        )
        assertTrue(text.text, text.text.contains("fork"))
        assertTrue(Theme.FORK in text.themes)
        assertTrue(Theme.MISSED_MATERIAL in text.themes)
        assertTrue(Theme.ENDGAME in text.themes)
    }

    @Test fun materialWords() {
        assertEquals("a piece", MoveExplainer.materialWord(3))
        assertEquals("a pawn", MoveExplainer.materialWord(1))
    }
}

class UciParsingTest {
    @Test fun parsesInfoLines() {
        val info = UciEngine.parseInfo(
            "info depth 18 seldepth 25 multipv 2 score cp -34 nodes 123 nps 1000 hashfull 10 tbhits 0 time 50 pv e7e5 g1f3 b8c6",
        )!!
        assertEquals(18, info.depth)
        assertEquals(2, info.multiPv)
        assertEquals(-34, info.cp)
        assertEquals(listOf("e7e5", "g1f3", "b8c6"), info.pv)
        val mate = UciEngine.parseInfo("info depth 5 score mate -2 wdl 0 0 1000 pv h5f7")!!
        assertEquals(-2, mate.mate)
        assertEquals(listOf("h5f7"), mate.pv)
        assertTrue(UciEngine.parseInfo("info depth 10 score cp 20 lowerbound pv e2e4")!!.bound)
        assertNull(UciEngine.parseInfo("info string NNUE evaluation using nn.nnue enabled"))
        assertNull(UciEngine.parseInfo("info depth 3 currmove e2e4 currmovenumber 1"))
    }

    @Test fun convertsSearchResultsWithLegalPvOnly() {
        val pos = Position.fromFen("r1bqkb1r/pppp1ppp/2n2n2/4p2Q/2B1P3/8/PPPP1PPP/RNB1K1NR w KQkq - 4 4")
        val result = UciSearchResult(
            "h5f7",
            listOf(
                UciEngine.parseInfo("info depth 20 multipv 1 score mate 1 pv h5f7 zzzz")!!,
                UciEngine.parseInfo("info depth 20 multipv 2 score cp 300 pv c4f7 e8e7")!!,
            ),
        )
        val eval = UciPositionEvaluator.toPositionEval(pos, result)
        assertEquals(Score.Mate(1), eval.score)
        assertEquals(1, eval.lines[0].pv.size)
        assertEquals(Move.fromUci("h5f7"), eval.bestMove)
        assertTrue(abs(eval.lines[1].score.whiteWinChance() - Score.Cp(300).whiteWinChance()) < 1e-9)
        assertNotNull(eval.lines[1].pv.firstOrNull())
    }
}
