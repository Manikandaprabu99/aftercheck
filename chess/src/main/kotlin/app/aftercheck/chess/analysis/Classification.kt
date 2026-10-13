package app.aftercheck.chess.analysis

import app.aftercheck.chess.Side

enum class MoveClass(val label: String, val symbol: String) {
    BOOK("Book", "≡"),
    BEST("Best", "★"),
    EXCELLENT("Excellent", "!"),
    GOOD("Good", "✓"),
    INACCURACY("Inaccuracy", "?!"),
    MISTAKE("Mistake", "?"),
    BLUNDER("Blunder", "??"),
    MISS("Missed opportunity", "×"),
    MISSED_MATE("Missed mate", "#");

    val isError: Boolean get() = this == INACCURACY || this == MISTAKE || this == BLUNDER || this == MISS || this == MISSED_MATE
    val isSerious: Boolean get() = this == MISTAKE || this == BLUNDER || this == MISS || this == MISSED_MATE
    val isCritical: Boolean get() = this == BLUNDER || this == MISSED_MATE

    companion object {
        fun fromName(name: String?): MoveClass? = entries.firstOrNull { it.name == name }
    }
}

/**
 * Thresholds for AfterCheck's move classification, expressed as *expected points* (0..1) lost by
 * the mover compared with the engine's best move. Expected points come from [whiteWinChance].
 *
 * | Class       | Expected-points loss        |
 * |-------------|-----------------------------|
 * | Best        | played the engine move      |
 * | Excellent   | ≤ [excellent]               |
 * | Good        | ≤ [good]                    |
 * | Inaccuracy  | ≤ [inaccuracy]              |
 * | Mistake     | ≤ [mistake]                 |
 * | Blunder     | > [mistake]                 |
 *
 * Special cases, checked first:
 * - **Missed mate**: the engine found mate in ≤ [missedMateMaxMoves] for the mover and the move
 *   played no longer forces mate.
 * - **Allowed mate**: a move that lets the opponent force mate (when best play did not) is at
 *   least a Mistake, even if the position was already lost.
 * - **Missed opportunity**: the opponent's previous move was an error, the best move kept the mover
 *   at ≥ [missMinBestPoints], the move played lost at least [inaccuracy] but the mover still has
 *   ≥ [missMinPlayedPoints] — the advantage was let slip rather than the game being thrown away.
 *
 * These are AfterCheck's own documented rules; they are not Chess.com's proprietary classifier.
 */
data class ClassificationThresholds(
    val excellent: Double = 0.02,
    val good: Double = 0.05,
    val inaccuracy: Double = 0.10,
    val mistake: Double = 0.20,
    val missedMateMaxMoves: Int = 5,
    val missMinBestPoints: Double = 0.65,
    val missMinPlayedPoints: Double = 0.35,
)

data class ClassificationInput(
    val mover: Side,
    val playedIsBest: Boolean,
    val isBook: Boolean,
    /** Best evaluation available before the move (White POV). */
    val bestScore: Score,
    /** Evaluation after the move played (White POV). */
    val playedScore: Score,
    /** Classification of the opponent's previous move, if any. */
    val previousOpponentClass: MoveClass?,
)

data class Classification(
    val moveClass: MoveClass,
    val bestPoints: Double,
    val playedPoints: Double,
    val loss: Double,
)

class MoveClassifier(private val t: ClassificationThresholds = ClassificationThresholds()) {

    fun classify(input: ClassificationInput): Classification {
        val side = input.mover
        val bestPoints = input.bestScore.expectedPoints(side)
        val playedPoints = input.playedScore.expectedPoints(side)
        val loss = if (input.playedIsBest) 0.0 else (bestPoints - playedPoints).coerceAtLeast(0.0)

        fun result(c: MoveClass) = Classification(c, bestPoints, playedPoints, loss)

        if (input.isBook) return result(MoveClass.BOOK)
        // Delivering mate is always best.
        if (input.playedScore is Score.GameOver && input.playedScore.winner == side) return result(MoveClass.BEST)
        if (input.playedIsBest) return result(MoveClass.BEST)

        val mateAvailable = input.bestScore.mateFor(side)
        if (mateAvailable != null && mateAvailable <= t.missedMateMaxMoves && !input.playedScore.isWinFor(side)) {
            return result(MoveClass.MISSED_MATE)
        }

        val opponentErred = input.previousOpponentClass?.isSerious == true
        if (opponentErred && bestPoints >= t.missMinBestPoints && loss > t.inaccuracy &&
            playedPoints >= t.missMinPlayedPoints
        ) {
            return result(MoveClass.MISS)
        }

        val byLoss = when {
            loss <= t.excellent -> MoveClass.EXCELLENT
            loss <= t.good -> MoveClass.GOOD
            loss <= t.inaccuracy -> MoveClass.INACCURACY
            loss <= t.mistake -> MoveClass.MISTAKE
            else -> MoveClass.BLUNDER
        }
        val allowsMate = input.playedScore.mateFor(side.opposite) != null && input.bestScore.mateFor(side.opposite) == null
        return result(if (allowsMate && byLoss.ordinal < MoveClass.MISTAKE.ordinal) MoveClass.MISTAKE else byLoss)
    }
}
