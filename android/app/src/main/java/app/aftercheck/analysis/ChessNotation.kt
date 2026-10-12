package app.aftercheck.analysis

import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.move.Move
import com.github.bhlangonijr.chesslib.move.MoveList

/**
 * Converts Stockfish UCI coordinates to SAN using the actual board position.
 * If an engine line cannot be converted completely, only the legal prefix is shown.
 */
object ChessNotation {
    fun uciToSan(fen: String, uci: String): String = runCatching {
        require(uci.matches(Regex("[a-h][1-8][a-h][1-8][qrbn]?")))
        val board = Board().apply { loadFromFen(fen) }
        val move = Move(uci, board.sideToMove)
        check(board.doMove(move, true)) { "Illegal engine move." }
        MoveList(fen).apply { add(move) }.toSanArray().first()
    }.getOrDefault("")

    fun uciVariationToSan(fen: String, variation: String, maxMoves: Int = 12): String {
        val board = runCatching { Board().apply { loadFromFen(fen) } }.getOrNull() ?: return ""
        val moveList = MoveList(fen)
        variation.trim().split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .take(maxMoves.coerceIn(1, 30))
            .forEach { uci ->
                val move = runCatching { Move(uci, board.sideToMove) }.getOrNull() ?: return@forEach
                val applied = runCatching { board.doMove(move, true) }.getOrDefault(false)
                if (applied) moveList.add(move) else return@forEach
            }
        return runCatching { moveList.toSanWithMoveNumbers() }.getOrDefault("")
    }
}
