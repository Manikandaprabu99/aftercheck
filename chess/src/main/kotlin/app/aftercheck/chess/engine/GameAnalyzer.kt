package app.aftercheck.chess.engine

import app.aftercheck.chess.GameLine
import app.aftercheck.chess.Move
import app.aftercheck.chess.Position
import app.aftercheck.chess.analysis.EngineLine
import app.aftercheck.chess.analysis.GameReviewer
import app.aftercheck.chess.analysis.PositionEval
import app.aftercheck.chess.analysis.Score
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Engine search settings. [key] identifies results that may be reused from the cache. */
data class EngineSettings(
    val depth: Int = 16,
    val movetimeMillis: Long = 2_500,
    val multiPv: Int = 2,
    val threads: Int = 1,
    val hashMb: Int = 32,
) {
    val key: String get() = "d$depth-t$movetimeMillis-m$multiPv"
    val limits: SearchLimits get() = SearchLimits(depth = depth, movetimeMillis = movetimeMillis)
}

fun interface PositionEvaluator {
    suspend fun evaluate(line: GameLine, index: Int): PositionEval
}

/** Evaluates every position of a game in order, skipping finished positions. */
class GameAnalyzer(private val evaluator: PositionEvaluator) {
    suspend fun evaluateAll(
        line: GameLine,
        onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> },
    ): List<PositionEval> {
        val total = line.plyCount + 1
        val evals = ArrayList<PositionEval>(total)
        for (i in 0..line.plyCount) {
            currentCoroutineContext().ensureActive()
            val terminal = GameReviewer.terminalScore(line, i)
            evals += if (terminal != null) PositionEval.terminal(terminal) else evaluator.evaluate(line, i)
            onProgress(i + 1, total)
        }
        return evals
    }
}

/** [PositionEvaluator] backed by a running [UciEngine]. */
class UciPositionEvaluator(
    private val engine: UciEngine,
    private val settings: EngineSettings,
) : PositionEvaluator {
    private var currentMultiPv = -1

    override suspend fun evaluate(line: GameLine, index: Int): PositionEval {
        val position = line.positions[index]
        val legal = position.legalMoves().size
        val multiPv = settings.multiPv.coerceIn(1, maxOf(1, legal))
        if (multiPv != currentMultiPv) {
            engine.setOption("MultiPV", multiPv.toString())
            currentMultiPv = multiPv
        }
        val result = engine.search(line.start.toFen(), line.uciPrefix(index), settings.limits)
        return toPositionEval(position, result)
    }

    companion object {
        fun toPositionEval(position: Position, result: UciSearchResult): PositionEval {
            val lines = result.lines.mapNotNull { info ->
                val pv = legalPrefix(position, info.pv)
                if (pv.isEmpty()) return@mapNotNull null
                EngineLine(Score.fromUci(info.cp, info.mate, position.sideToMove), pv, info.depth)
            }.toMutableList()
            if (lines.isEmpty()) {
                val best = result.bestMove?.let { Move.fromUci(it) }?.takeIf { position.isLegal(it) }
                    ?: throw EngineException("The engine returned no usable line for ${position.toFen()}")
                val info = result.lines.firstOrNull()
                lines += EngineLine(Score.fromUci(info?.cp ?: 0, info?.mate, position.sideToMove), listOf(best), info?.depth ?: 0)
            }
            // Ensure the engine's reported best move leads the list.
            val best = result.bestMove?.let { Move.fromUci(it) }
            val bestIndex = lines.indexOfFirst { it.pv.first() == best }
            if (bestIndex > 0) lines.add(0, lines.removeAt(bestIndex))
            return PositionEval(lines.first().score, lines, lines.maxOf { it.depth })
        }

        private fun legalPrefix(start: Position, uci: List<String>): List<Move> {
            val out = ArrayList<Move>(uci.size)
            var pos = start
            for (u in uci) {
                val m = Move.fromUci(u)?.takeIf { pos.isLegal(it) } ?: break
                out += m
                pos = pos.play(m)
            }
            return out
        }
    }
}
