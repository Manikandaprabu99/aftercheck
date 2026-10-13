package app.aftercheck.chess

/**
 * A replayed game: `positions[0]` is the start, `positions[i]` the position after ply `i`.
 * `moves[i - 1]` / `sans[i - 1]` is the move that produced `positions[i]`.
 */
class GameLine(
    val positions: List<Position>,
    val moves: List<Move>,
    val sans: List<String>,
) {
    init {
        require(positions.size == moves.size + 1 && moves.size == sans.size)
    }

    val start: Position get() = positions.first()
    val final: Position get() = positions.last()
    val plyCount: Int get() = moves.size

    /** Side that played ply [ply] (1-based). */
    fun moverOf(ply: Int): Side = positions[ply - 1].sideToMove

    /** Move number shown before ply [ply], e.g. ply 1 → 1, ply 2 → 1, ply 3 → 2 (from the start position). */
    fun moveNumberOf(ply: Int): Int = positions[ply - 1].fullmoveNumber

    /** UCI moves leading up to the position at index [index]. */
    fun uciPrefix(index: Int): List<String> = moves.subList(0, index).map { it.uci }

    /** True when the position at [index] has occurred three times (threefold repetition). */
    fun isThreefold(index: Int): Boolean {
        val key = positions[index].zobrist
        var count = 0
        // Repetitions can only happen since the last irreversible move.
        val from = (index - positions[index].halfmoveClock).coerceAtLeast(0)
        for (i in from..index) if (positions[i].zobrist == key) count++
        return count >= 3
    }

    /** Game outcome at [index] considering checkmate, stalemate, material, fifty moves and repetition. */
    fun outcomeAt(index: Int): Outcome? =
        positions[index].outcome() ?: if (isThreefold(index)) Outcome(null, Termination.THREEFOLD_REPETITION) else null

    companion object {
        fun replay(start: Position, sanMoves: List<String>): ReplayResult {
            val positions = ArrayList<Position>(sanMoves.size + 1).apply { add(start) }
            val moves = ArrayList<Move>(sanMoves.size)
            val sans = ArrayList<String>(sanMoves.size)
            var pos = start
            for ((i, text) in sanMoves.withIndex()) {
                val move = pos.parseSan(text)
                    ?: return ReplayResult.Failure(i + 1, text, "Illegal or ambiguous move '$text' at ply ${i + 1}")
                sans += pos.san(move)
                moves += move
                pos = pos.play(move)
                positions += pos
            }
            return ReplayResult.Success(GameLine(positions, moves, sans))
        }

        fun fromPgn(game: PgnGame): ReplayResult {
            val start = try {
                game.startPosition()
            } catch (e: IllegalArgumentException) {
                return ReplayResult.Failure(0, game.tag("FEN") ?: "", "Invalid starting position: ${e.message}")
            }
            return replay(start, game.moves.map { it.san })
        }
    }
}

sealed interface ReplayResult {
    data class Success(val line: GameLine) : ReplayResult
    data class Failure(val ply: Int, val token: String, val reason: String) : ReplayResult
}
