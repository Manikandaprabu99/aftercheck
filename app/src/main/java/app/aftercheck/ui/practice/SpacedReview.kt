package app.aftercheck.ui.practice

import app.aftercheck.data.db.PracticeEntity

/**
 * A small Leitner system: each correct answer moves a position to the next box and pushes its next
 * review further out; a wrong answer sends it back to box 0 so it returns in ten minutes.
 */
object SpacedReview {
    private const val MINUTE = 60_000L
    private const val DAY = 24 * 60 * MINUTE
    val INTERVALS = longArrayOf(10 * MINUTE, 1 * DAY, 3 * DAY, 7 * DAY, 16 * DAY, 35 * DAY)
    const val MASTERED_BOX = 4

    fun isCorrect(position: PracticeEntity, uci: String): Boolean =
        uci == position.solutionUci || uci in position.acceptedUci.split(',').filter { it.isNotBlank() }

    fun schedule(position: PracticeEntity, correct: Boolean, now: Long): PracticeEntity {
        val box = if (correct) (position.box + 1).coerceAtMost(INTERVALS.lastIndex) else 0
        return position.copy(
            box = box,
            dueAt = now + INTERVALS[box],
            attempts = position.attempts + 1,
            correct = position.correct + if (correct) 1 else 0,
            lastAttemptAt = now,
            lastCorrect = correct,
        )
    }
}
