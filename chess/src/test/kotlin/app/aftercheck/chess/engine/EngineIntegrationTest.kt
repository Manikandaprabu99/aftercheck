package app.aftercheck.chess.engine

import app.aftercheck.chess.Fixtures
import app.aftercheck.chess.GameLine
import app.aftercheck.chess.Move
import app.aftercheck.chess.Pgn
import app.aftercheck.chess.Position
import app.aftercheck.chess.ReplayResult
import app.aftercheck.chess.Side
import app.aftercheck.chess.analysis.GameReviewer
import app.aftercheck.chess.analysis.MoveClass
import app.aftercheck.chess.analysis.Score
import app.aftercheck.chess.analysis.mateFor
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * Runs the real Stockfish engine. Enabled when AFTERCHECK_STOCKFISH (host binary) and
 * AFTERCHECK_NNUE (network file) are set — see `scripts/build-stockfish.sh --host`.
 */
class EngineIntegrationTest {
    private lateinit var engine: UciEngine
    private val binary = System.getenv("AFTERCHECK_STOCKFISH")
    private val network = System.getenv("AFTERCHECK_NNUE")

    @Before fun setUp() {
        assumeTrue("Stockfish not configured", binary != null && network != null && File(binary).canExecute())
        engine = UciEngine({ ProcessBuilder(binary).redirectErrorStream(true).start() })
        runBlocking { engine.start(mapOf("EvalFile" to network!!, "Threads" to "1", "Hash" to "16")) }
    }

    @After fun tearDown() {
        if (::engine.isInitialized) engine.close()
    }

    @Test fun findsMateInOne() = runBlocking {
        val line = (GameLine.fromPgn(Pgn.parse(Fixtures.SCHOLARS_MATE)) as ReplayResult.Success).line
        val eval = UciPositionEvaluator(engine, EngineSettings(depth = 12, movetimeMillis = 2_000, multiPv = 2))
            .evaluate(line, 6)
        assertEquals(Score.Mate(1), eval.score)
        assertEquals(Move.fromUci("h5f7"), eval.bestMove)
        assertEquals(2, eval.lines.size)
        assertTrue("engine name ${engine.engineName}", engine.engineName!!.startsWith("Stockfish"))
    }

    @Test fun reportsScoresFromWhitesPerspectiveForBlackToMove() = runBlocking {
        // Black to move and mates in one (…Qh4#) in the Fool's mate line.
        val line = (GameLine.replay(Position.initial, listOf("f3", "e5", "g4")) as ReplayResult.Success).line
        val eval = UciPositionEvaluator(engine, EngineSettings(depth = 10, movetimeMillis = 2_000, multiPv = 1))
            .evaluate(line, 3)
        assertEquals(Score.Mate(-1), eval.score)
        assertEquals(1, eval.score.mateFor(Side.BLACK))
    }

    @Test fun drawnEndgameIsNearZero() = runBlocking {
        val line = GameLine(listOf(Position.fromFen("8/8/4k3/4r3/8/3K4/4R3/8 w - - 0 1")), emptyList(), emptyList())
        val eval = UciPositionEvaluator(engine, EngineSettings(depth = 14, movetimeMillis = 3_000, multiPv = 1))
            .evaluate(line, 0)
        val cp = (eval.score as Score.Cp).centipawns
        assertTrue("rook endgame should be about equal, got $cp", abs(cp) < 80)
    }

    @Test fun reviewsARealGameEndToEnd() = runBlocking {
        val pgn = Pgn.parse(Fixtures.OPERA_GAME)
        val line = (GameLine.fromPgn(pgn) as ReplayResult.Success).line
        val evaluator = UciPositionEvaluator(engine, EngineSettings(depth = 10, movetimeMillis = 1_000, multiPv = 2))
        val evals = GameAnalyzer(evaluator).evaluateAll(line)
        val review = GameReviewer().review(line, evals, clocks = pgn.moves.map { it.clockMillis }, baseTimeMillis = 180_000)
        assertEquals(33, review.moves.size)
        assertEquals(Score.GameOver(Side.WHITE), review.finalScore)
        assertEquals(MoveClass.BEST, review.moves.last().classification)
        // Morphy's play is near-perfect; the Duke's 3...Bg4 era play is not.
        assertTrue("white=${review.white.accuracy} black=${review.black.accuracy}", review.white.accuracy!! > review.black.accuracy!!)
        assertTrue(review.moves.all { it.explanation.isNotBlank() })
        assertTrue(review.black.counts.keys.any { it.isError })
    }

    @Test fun cancellationStopsSearchQuickly() = runBlocking {
        val line = GameLine(listOf(Position.initial), emptyList(), emptyList())
        val evaluator = UciPositionEvaluator(engine, EngineSettings(depth = 60, movetimeMillis = 60_000, multiPv = 1))
        val job = async { evaluator.evaluate(line, 0) }
        delay(500)
        val started = System.nanoTime()
        job.cancel()
        job.join()
        val stopMillis = (System.nanoTime() - started) / 1_000_000
        assertTrue("stop took ${stopMillis}ms", stopMillis < 2_000)
        // The engine remains usable after a cancelled search.
        val again = UciPositionEvaluator(engine, EngineSettings(depth = 8, movetimeMillis = 1_000, multiPv = 1)).evaluate(line, 0)
        assertTrue(again.bestMove != null)
    }

    @Test fun restartsAfterTheProcessDies() = runBlocking {
        engine.close()
        assertTrue(!engine.isRunning)
        engine.start(mapOf("EvalFile" to network!!))
        val line = GameLine(listOf(Position.initial), emptyList(), emptyList())
        val eval = UciPositionEvaluator(engine, EngineSettings(depth = 6, movetimeMillis = 1_000, multiPv = 1)).evaluate(line, 0)
        assertTrue(eval.bestMove != null)
    }
}
