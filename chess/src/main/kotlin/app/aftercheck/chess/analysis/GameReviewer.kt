package app.aftercheck.chess.analysis

import app.aftercheck.chess.GameLine
import app.aftercheck.chess.Move
import app.aftercheck.chess.Opening
import app.aftercheck.chess.Side
import app.aftercheck.chess.Termination
import app.aftercheck.chess.formatLine
import app.aftercheck.chess.san
import kotlin.math.abs

/** One engine line: score from White's perspective and the principal variation. */
data class EngineLine(val score: Score, val pv: List<Move>, val depth: Int)

/** Engine result for one position; [lines] is empty for finished games. */
data class PositionEval(val score: Score, val lines: List<EngineLine>, val depth: Int) {
    val bestMove: Move? get() = lines.firstOrNull()?.pv?.firstOrNull()

    companion object {
        fun terminal(score: Score.GameOver) = PositionEval(score, emptyList(), 0)
    }
}

data class AlternativeLine(val san: List<String>, val score: Score)

data class MoveReview(
    val ply: Int,
    val side: Side,
    val move: Move,
    val san: String,
    val classification: MoveClass,
    /** Best evaluation before the move (White POV). */
    val scoreBefore: Score,
    /** Evaluation after the move played (White POV). */
    val scoreAfter: Score,
    val pointsLost: Double,
    val accuracy: Double,
    val bestMove: Move?,
    val bestSan: String?,
    /** Engine's best line from the position before the move (SAN, starts with the best move). */
    val bestLine: List<String>,
    /** Engine's line after the move played (SAN, opponent's reply first). */
    val replyLine: List<String>,
    val alternatives: List<AlternativeLine>,
    val explanation: String,
    val themes: Set<Theme>,
    val clockMillis: Long?,
    val timeSpentMillis: Long?,
)

data class SideSummary(
    val accuracy: Double?,
    val counts: Map<MoveClass, Int>,
) {
    fun count(c: MoveClass) = counts[c] ?: 0
}

data class Recommendation(val theme: Theme, val count: Int, val text: String)

data class GameReview(
    val moves: List<MoveReview>,
    val white: SideSummary,
    val black: SideSummary,
    val opening: Opening?,
    val bookPlies: Int,
    /** White's win chance (0..1) for positions 0..n — the evaluation graph. */
    val winChances: List<Double>,
    val finalScore: Score,
    val biggestSwingPly: Int?,
    val strongestMovePly: Int?,
    val turningPoints: List<Int>,
    val recommendations: List<Recommendation>,
) {
    fun summary(side: Side) = if (side.isWhite) white else black
}

/**
 * Turns per-position engine evaluations into a complete, deterministic game review.
 * `evals[i]` must describe `line.positions[i]` for every i in 0..n.
 */
class GameReviewer(
    private val classifier: MoveClassifier = MoveClassifier(),
    private val explainer: MoveExplainer = MoveExplainer(),
) {

    fun review(
        line: GameLine,
        evals: List<PositionEval>,
        clocks: List<Long?> = emptyList(),
        opening: Opening? = null,
        bookPlies: Int = 0,
        baseTimeMillis: Long? = null,
        incrementMillis: Long = 0,
        /** Side whose improvement recommendations we generate; null = both sides. */
        focus: Side? = null,
    ): GameReview {
        require(evals.size == line.plyCount + 1) { "Need ${line.plyCount + 1} evaluations, got ${evals.size}" }
        val moves = ArrayList<MoveReview>(line.plyCount)
        val winChances = evals.map { it.score.whiteWinChance() }
        val accuracies = Accuracy.gameAccuracy(winChances.map { it * 100 }, line.start.sideToMove)

        var previous: MoveClass? = null
        for (ply in 1..line.plyCount) {
            val before = line.positions[ply - 1]
            val mover = before.sideToMove
            val move = line.moves[ply - 1]
            val evalBefore = evals[ply - 1]
            val evalAfter = evals[ply]
            val best = evalBefore.bestMove?.takeIf { before.isLegal(it) }
            val playedIsBest = best == move
            val c = classifier.classify(
                ClassificationInput(
                    mover = mover,
                    playedIsBest = playedIsBest,
                    isBook = ply <= bookPlies,
                    bestScore = evalBefore.score,
                    playedScore = evalAfter.score,
                    previousOpponentClass = previous,
                ),
            )
            val clock = clocks.getOrNull(ply - 1)
            val previousOwnClock = if (ply >= 3) clocks.getOrNull(ply - 3) else baseTimeMillis
            val spent = if (clock != null && previousOwnClock != null) {
                (previousOwnClock + incrementMillis - clock).coerceAtLeast(0)
            } else null

            val bestLine = evalBefore.lines.firstOrNull()?.pv.orEmpty()
            val replyLine = evalAfter.lines.firstOrNull()?.pv.orEmpty()
            val explanation = explainer.explain(
                MoveExplainer.Input(
                    before = before,
                    played = move,
                    classification = c.moveClass,
                    bestScore = evalBefore.score,
                    playedScore = evalAfter.score,
                    bestLine = bestLine,
                    replyLine = replyLine,
                    ply = ply,
                    openingName = opening?.name,
                    clockMillis = clock,
                    baseTimeMillis = baseTimeMillis,
                    baselineBalance = exchangeBaseline(line, ply, mover),
                ),
            )
            val after = line.positions[ply]
            val winBefore = winChances[ply - 1] * 100
            val winAfter = winChances[ply] * 100
            val moveAccuracy = if (mover.isWhite) Accuracy.moveAccuracy(winBefore, winAfter)
            else Accuracy.moveAccuracy(100 - winBefore, 100 - winAfter)

            moves += MoveReview(
                ply = ply,
                side = mover,
                move = move,
                san = line.sans[ply - 1],
                classification = c.moveClass,
                scoreBefore = evalBefore.score,
                scoreAfter = evalAfter.score,
                pointsLost = c.loss,
                accuracy = moveAccuracy,
                bestMove = best,
                bestSan = best?.let { before.san(it) },
                bestLine = sanLine(line.positions[ply - 1], bestLine),
                replyLine = sanLine(after, replyLine),
                alternatives = evalBefore.lines.drop(1).map { AlternativeLine(sanLine(before, it.pv), it.score) }
                    .filter { it.san.isNotEmpty() },
                explanation = explanation.text,
                themes = explanation.themes,
                clockMillis = clock,
                timeSpentMillis = spent,
            )
            previous = c.moveClass
        }

        fun summaryFor(side: Side) = SideSummary(
            accuracy = accuracies[side],
            counts = moves.filter { it.side == side }.groupingBy { it.classification }.eachCount(),
        )

        return GameReview(
            moves = moves,
            white = summaryFor(Side.WHITE),
            black = summaryFor(Side.BLACK),
            opening = opening,
            bookPlies = bookPlies,
            winChances = winChances,
            finalScore = evals.last().score,
            biggestSwingPly = moves.maxByOrNull { it.pointsLost }?.takeIf { it.pointsLost >= 0.05 }?.ply,
            strongestMovePly = strongestMove(moves, evals),
            turningPoints = turningPoints(winChances),
            recommendations = recommendations(moves.filter { focus == null || it.side == focus }, opening),
        )
    }

    /** Mover's material balance before the run of captures that led to ply [ply]. */
    private fun exchangeBaseline(line: GameLine, ply: Int, mover: Side): Int {
        var index = ply - 1
        while (index > 0 && line.positions[index - 1].isCapture(line.moves[index - 1])) index--
        val pos = line.positions[index]
        return pos.material(mover) - pos.material(mover.opposite)
    }

    private fun sanLine(start: app.aftercheck.chess.Position, pv: List<Move>, max: Int = 10): List<String> {
        val out = ArrayList<String>()
        var pos = start
        for (m in pv.take(max)) {
            if (!pos.isLegal(m)) break
            out += pos.san(m)
            pos = pos.play(m)
        }
        return out
    }

    /**
     * The best move that mattered most: preferably the engine move where the second-best option
     * was clearly worse (needs MultiPV), otherwise the best reply to the opponent's largest error.
     */
    private fun strongestMove(moves: List<MoveReview>, evals: List<PositionEval>): Int? {
        val withGap = moves.filter { it.classification == MoveClass.BEST }.mapNotNull { m ->
            val lines = evals[m.ply - 1].lines
            if (lines.size < 2) return@mapNotNull null
            val gap = lines[0].score.expectedPoints(m.side) - lines[1].score.expectedPoints(m.side)
            if (gap >= 0.10) m.ply to gap else null
        }
        withGap.maxByOrNull { it.second }?.let { return it.first }
        return moves.zipWithNext().filter { (prev, cur) ->
            prev.classification.isSerious && (cur.classification == MoveClass.BEST || cur.classification == MoveClass.EXCELLENT)
        }.maxByOrNull { it.first.pointsLost }?.second?.ply
    }

    /**
     * Plies where the game changed character: a swing of ≥ 15 points, or ≥ 5 points that crosses into
     * or out of a winning zone (≥ 70 % / ≤ 30 %). At most five, in game order.
     */
    private fun turningPoints(win: List<Double>): List<Int> {
        fun zone(w: Double) = when {
            w >= 0.70 -> 1
            w <= 0.30 -> -1
            else -> 0
        }
        val points = ArrayList<Pair<Int, Double>>()
        for (ply in 1 until win.size) {
            val delta = abs(win[ply] - win[ply - 1])
            if (delta >= 0.15 || zone(win[ply]) != zone(win[ply - 1]) && delta >= 0.05) points += ply to delta
        }
        return points.sortedByDescending { it.second }.take(5).map { it.first }.sorted()
    }

    private fun recommendations(moves: List<MoveReview>, opening: Opening?): List<Recommendation> {
        val errors = moves.filter { it.classification.isError }
        val counts = errors.flatMap { it.themes }.groupingBy { it }.eachCount()
        return counts.entries.sortedByDescending { it.value }.map { (theme, n) ->
            Recommendation(theme, n, recommendationText(theme, n, opening))
        }
    }

    companion object {
        fun recommendationText(theme: Theme, n: Int, opening: Opening?): String {
            val times = if (n == 1) "once" else "$n times"
            return when (theme) {
                Theme.HANGING_PIECE -> "Before releasing a move, check which of your pieces it leaves attacked or undefended — material was dropped $times."
                Theme.MISSED_MATERIAL -> "Scan every capture and threat first: a winning material grab was missed $times."
                Theme.FORK -> "Watch for double attacks (forks) by knights, queens and pawns — they decided $times."
                Theme.MISSED_MATE -> "Look at checks first when the enemy king is exposed — a forced mate was missed $times."
                Theme.ALLOWED_MATE -> "Before each move, ask what checks your opponent has — a forced mate was allowed $times."
                Theme.BACK_RANK -> "Give your king an escape square (luft) to avoid back-rank mates."
                Theme.KING_SAFETY -> "Keep the pawns in front of your castled king intact unless it wins something concrete — king safety cost you $times."
                Theme.OPENING -> "Opening: ${if (n == 1) "an error" else "$n errors"} came in the first 10 moves${opening?.let { " of the ${it.name}" } ?: ""}. Develop minor pieces and castle before attacking."
                Theme.ENDGAME -> "Endgame technique: ${if (n == 1) "an error" else "$n errors"} came after most pieces were traded. Activate your king and push passed pawns."
                Theme.TIME_PRESSURE -> "Clock management: ${if (n == 1) "an error" else "$n errors"} came with under 10% of your time left."
            }
        }

        /** Parses a Chess.com/PGN TimeControl such as "180+2" into base and increment millis. */
        fun parseTimeControl(tc: String?): Pair<Long, Long>? {
            if (tc.isNullOrBlank() || tc.contains('/')) return null
            val parts = tc.split('+')
            val base = parts[0].toLongOrNull() ?: return null
            val inc = parts.getOrNull(1)?.toLongOrNull() ?: 0
            return base * 1000 to inc * 1000
        }

        fun terminalScore(line: GameLine, index: Int): Score.GameOver? {
            val outcome = line.outcomeAt(index) ?: return null
            return when (outcome.termination) {
                Termination.CHECKMATE -> Score.GameOver(outcome.winner)
                else -> Score.GameOver(null)
            }
        }

        fun describePly(line: GameLine, ply: Int): String =
            formatLine(line.positions[ply - 1], listOf(line.positions[ply - 1].san(line.moves[ply - 1])))
    }
}
