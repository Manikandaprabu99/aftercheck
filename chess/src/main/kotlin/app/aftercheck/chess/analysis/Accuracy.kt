package app.aftercheck.chess.analysis

import app.aftercheck.chess.Side
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * AfterCheck's *estimated* accuracy. It follows the open method published by Lichess:
 *
 * 1. Every position gets White's win percentage (0..100) from [whiteWinChance].
 * 2. A move's accuracy is `103.1668 · e^(−0.04354 · drop) − 3.1669 + 1`, where `drop` is the fall in
 *    the mover's win percentage (no drop → 100), clamped to 0..100.
 * 3. A side's game accuracy is the mean of (a) the volatility-weighted mean of its move accuracies —
 *    weights are the standard deviation of win percentages in a sliding window, clamped to 0.5..12 —
 *    and (b) their harmonic mean (each move floored at 1 % so one collapse does not zero it).
 *
 * It is an estimate, not Chess.com's proprietary accuracy score.
 */
object Accuracy {

    fun moveAccuracy(winBefore: Double, winAfter: Double): Double {
        if (winAfter >= winBefore) return 100.0
        val drop = winBefore - winAfter
        val raw = 103.1668100711649 * exp(-0.04354415386753951 * drop) - 3.166924740191411 + 1.0
        return raw.coerceIn(0.0, 100.0)
    }

    /**
     * @param whiteWinPercents White's win percentage (0..100) for positions 0..n of the game.
     * @param firstMover side that played ply 1.
     * @return accuracy per side, or null for a side without moves.
     */
    fun gameAccuracy(whiteWinPercents: List<Double>, firstMover: Side): Map<Side, Double?> {
        val moves = whiteWinPercents.size - 1
        if (moves <= 0) return mapOf(Side.WHITE to null, Side.BLACK to null)
        val windowSize = (moves / 10).coerceIn(2, 8)
        val windows = ArrayList<List<Double>>(moves)
        val head = whiteWinPercents.take(windowSize)
        repeat((minOf(windowSize, whiteWinPercents.size) - 2).coerceAtLeast(0)) { windows += head }
        for (i in 0..whiteWinPercents.size - windowSize) windows += whiteWinPercents.subList(i, i + windowSize)
        val weights = windows.map { stdDev(it).coerceIn(0.5, 12.0) }

        val perSide = mutableMapOf<Side, MutableList<Pair<Double, Double>>>()
        for (i in 0 until moves) {
            val mover = if (i % 2 == 0) firstMover else firstMover.opposite
            val before = whiteWinPercents[i]
            val after = whiteWinPercents[i + 1]
            val acc = if (mover.isWhite) moveAccuracy(before, after) else moveAccuracy(100 - before, 100 - after)
            val weight = weights.getOrElse(i) { weights.last() }
            perSide.getOrPut(mover) { mutableListOf() } += acc to weight
        }
        return Side.entries.associateWith { side ->
            val xs = perSide[side] ?: return@associateWith null
            val weighted = xs.sumOf { it.first * it.second } / xs.sumOf { it.second }
            val harmonic = xs.size / xs.sumOf { 1.0 / it.first.coerceAtLeast(1.0) }
            (weighted + harmonic) / 2.0
        }
    }

    private fun stdDev(xs: List<Double>): Double {
        if (xs.isEmpty()) return 0.0
        val mean = xs.average()
        return sqrt(xs.sumOf { (it - mean) * (it - mean) } / xs.size)
    }
}
