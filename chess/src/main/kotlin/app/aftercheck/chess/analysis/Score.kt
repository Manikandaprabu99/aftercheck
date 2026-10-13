package app.aftercheck.chess.analysis

import app.aftercheck.chess.Side
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

/** An evaluation, always from White's point of view. */
sealed interface Score {
    /** Centipawns, positive = White is better. */
    data class Cp(val centipawns: Int) : Score

    /** Forced mate: positive = White mates in [moves], negative = Black mates in |moves|. Never 0. */
    data class Mate(val moves: Int) : Score {
        init { require(moves != 0) { "Use GameOver for a position that is already mate" } }
    }

    /** The game is over in this position: checkmate (winner) or a draw (winner == null). */
    data class GameOver(val winner: Side?) : Score

    companion object {
        /**
         * Converts a UCI score (reported from the side to move's perspective) to White's perspective.
         * `mate 0` means the side to move is checkmated.
         */
        fun fromUci(cp: Int?, mate: Int?, sideToMove: Side): Score {
            val sign = if (sideToMove.isWhite) 1 else -1
            return when {
                mate != null && mate == 0 -> GameOver(sideToMove.opposite)
                mate != null -> Mate(mate * sign)
                else -> Cp((cp ?: 0) * sign)
            }
        }

        fun parse(text: String): Score? {
            val parts = text.split(':')
            if (parts.size != 2) return null
            return when (parts[0]) {
                "cp" -> parts[1].toIntOrNull()?.let { Cp(it) }
                "mate" -> parts[1].toIntOrNull()?.takeIf { it != 0 }?.let { Mate(it) }
                "end" -> when (parts[1]) {
                    "w" -> GameOver(Side.WHITE)
                    "b" -> GameOver(Side.BLACK)
                    "d" -> GameOver(null)
                    else -> null
                }
                else -> null
            }
        }
    }
}

/** Compact, lossless text form for storage, e.g. `cp:34`, `mate:-3`, `end:w`, `end:d`. */
fun Score.encode(): String = when (this) {
    is Score.Cp -> "cp:$centipawns"
    is Score.Mate -> "mate:$moves"
    is Score.GameOver -> "end:" + when (winner) { Side.WHITE -> "w"; Side.BLACK -> "b"; null -> "d" }
}

/**
 * White's winning chance in [0, 1] using the logistic model popularised by Lichess:
 * `0.5 + 0.5 * (2 / (1 + e^(-0.00368208 * cp)) - 1)` with cp clamped to ±1000.
 * Forced mates and finished games map to 0, 0.5 or 1.
 */
fun Score.whiteWinChance(): Double = when (this) {
    is Score.Cp -> {
        val cp = centipawns.coerceIn(-1000, 1000)
        1.0 / (1.0 + exp(-0.00368208 * cp))
    }
    is Score.Mate -> if (moves > 0) 1.0 else 0.0
    is Score.GameOver -> when (winner) { Side.WHITE -> 1.0; Side.BLACK -> 0.0; null -> 0.5 }
}

/** Expected points (0..1) for [side]. */
fun Score.expectedPoints(side: Side): Double = if (side.isWhite) whiteWinChance() else 1.0 - whiteWinChance()

/** Mate distance for [side] if it is mating, else null. */
fun Score.mateFor(side: Side): Int? = when (this) {
    is Score.Mate -> if ((moves > 0) == side.isWhite) abs(moves) else null
    else -> null
}

fun Score.isWinFor(side: Side): Boolean = when (this) {
    is Score.Mate -> (moves > 0) == side.isWhite
    is Score.GameOver -> winner == side
    is Score.Cp -> false
}

/** Human readable, White's perspective: `+1.3`, `−0.4`, `M3`, `−M3`, `1-0`, `½-½`. */
fun Score.display(): String = when (this) {
    is Score.Cp -> {
        val pawns = centipawns / 100.0
        val rounded = (pawns * 10).roundToInt() / 10.0
        when {
            rounded > 0 -> String.format(Locale.US, "+%.1f", rounded)
            rounded < 0 -> String.format(Locale.US, "−%.1f", -rounded)
            else -> "0.0"
        }
    }
    is Score.Mate -> if (moves > 0) "M$moves" else "−M${-moves}"
    is Score.GameOver -> when (winner) { Side.WHITE -> "1-0"; Side.BLACK -> "0-1"; null -> "½-½" }
}

/** Centipawn value clamped for graphs and loss reporting (mates map to ±[cap]). */
fun Score.clampedCp(cap: Int = 1000): Int = when (this) {
    is Score.Cp -> centipawns.coerceIn(-cap, cap)
    is Score.Mate -> if (moves > 0) cap else -cap
    is Score.GameOver -> when (winner) { Side.WHITE -> cap; Side.BLACK -> -cap; null -> 0 }
}
