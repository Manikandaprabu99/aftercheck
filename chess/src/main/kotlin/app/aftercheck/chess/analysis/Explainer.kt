package app.aftercheck.chess.analysis

import app.aftercheck.chess.Move
import app.aftercheck.chess.Piece
import app.aftercheck.chess.PieceType
import app.aftercheck.chess.Position
import app.aftercheck.chess.Side
import app.aftercheck.chess.Squares
import app.aftercheck.chess.formatLine
import app.aftercheck.chess.san

/** Recurring patterns detected from the position and the engine's lines (never guessed). */
enum class Theme(val label: String) {
    HANGING_PIECE("Hanging pieces"),
    MISSED_MATERIAL("Missed material wins"),
    FORK("Forks"),
    MISSED_MATE("Missed mates"),
    ALLOWED_MATE("Allowed mates"),
    BACK_RANK("Back-rank weakness"),
    KING_SAFETY("King safety"),
    OPENING("Opening errors"),
    ENDGAME("Endgame technique"),
    TIME_PRESSURE("Time pressure");

    companion object {
        fun decode(text: String?): Set<Theme> =
            text.orEmpty().split(',').mapNotNull { n -> entries.firstOrNull { it.name == n } }.toSet()
    }
}

fun Set<Theme>.encode(): String = joinToString(",") { it.name }

data class Explanation(val text: String, val themes: Set<Theme>)

/**
 * Builds short explanations strictly from facts we can verify: the classification, engine scores,
 * the engine's principal variations replayed on the board, material counts and attacked squares.
 */
class MoveExplainer {

    data class Input(
        val before: Position,
        val played: Move,
        val classification: MoveClass,
        val bestScore: Score,
        val playedScore: Score,
        /** Engine line from [before], starting with its best move. */
        val bestLine: List<Move>,
        /** Engine line from the position after the move played (opponent's best reply first). */
        val replyLine: List<Move>,
        val ply: Int,
        val openingName: String?,
        val clockMillis: Long?,
        val baseTimeMillis: Long?,
        /**
         * Mover's material balance where the current exchange began (before a run of captures), so a
         * recapture is not mistaken for winning material. Defaults to the balance before the move.
         */
        val baselineBalance: Int? = null,
    )

    fun explain(input: Input): Explanation {
        val before = input.before
        val mover = before.sideToMove
        val opponent = mover.opposite
        val after = before.play(input.played)
        val playedSan = before.san(input.played)
        val best = input.bestLine.firstOrNull()?.takeIf { before.isLegal(it) }
        val bestSan = best?.let { before.san(it) }
        val bestLineText = best?.let { formatLine(before, sanLine(before, input.bestLine, 6)) }
        val replyText = input.replyLine.takeIf { it.isNotEmpty() }?.let { formatLine(after, sanLine(after, it, 6)) }
        val themes = mutableSetOf<Theme>()

        val baseline = input.baselineBalance ?: balance(before, mover)
        val playedGain = materialAfterLine(after, input.replyLine, mover) - baseline
        val bestGain = if (best != null) materialAfterLine(before, input.bestLine, mover) - baseline else 0

        val c = input.classification
        if (c.isError) contextThemes(input, before, after, mover, themes)

        val text = when (c) {
            MoveClass.BOOK -> input.openingName?.let { "A known opening move ($it)." } ?: "A known opening move."
            MoveClass.BEST -> bestMoveText(before, input, playedSan, playedGain, bestGain)
            MoveClass.EXCELLENT ->
                if (bestSan != null) "An excellent move, nearly as strong as the engine's choice $bestSan." else "An excellent move."
            MoveClass.GOOD ->
                if (bestSan != null) "A good move. $bestSan was more precise (${input.bestScore.display()} instead of ${input.playedScore.display()})."
                else "A good move."
            MoveClass.MISSED_MATE -> {
                themes += Theme.MISSED_MATE
                if (lineEndsInBackRankMate(before, input.bestLine)) themes += Theme.BACK_RANK
                val n = input.bestScore.mateFor(mover)
                "You had a forced mate in $n: $bestLineText."
            }
            else -> errorText(input, before, after, mover, opponent, playedSan, bestSan, bestLineText, replyText, playedGain, bestGain, themes)
        }
        return Explanation(text.trim(), themes)
    }

    private fun bestMoveText(before: Position, input: Input, playedSan: String, playedGain: Int, bestGain: Int): String {
        val mover = before.sideToMove
        val mate = input.bestScore.mateFor(mover)
        val after = before.play(input.played)
        return when {
            after.isCheckmate -> "Checkmate."
            before.legalMoves().size == 1 -> "The only legal move."
            mate != null -> "Keeps a forced mate in $mate."
            bestGain >= 2 -> "$playedSan wins ${materialWord(bestGain)}."
            forkTargets(after, input.played.to).size >= 2 -> {
                val targets = forkTargets(after, input.played.to)
                "$playedSan attacks ${targets.joinToString(" and ") { "the ${it.second.type.displayName} on ${Squares.name(it.first)}" }} at once."
            }
            else -> "The best move in the position (${input.playedScore.display()})."
        }
    }

    private fun errorText(
        input: Input,
        before: Position,
        after: Position,
        mover: Side,
        opponent: Side,
        playedSan: String,
        bestSan: String?,
        bestLineText: String?,
        replyText: String?,
        playedGain: Int,
        bestGain: Int,
        themes: MutableSet<Theme>,
    ): String {
        val parts = ArrayList<String>()
        val allowedMate = input.playedScore.mateFor(opponent)
        val reply = input.replyLine.firstOrNull()?.takeIf { after.isLegal(it) }

        if (allowedMate != null && input.bestScore.mateFor(opponent) == null) {
            themes += Theme.ALLOWED_MATE
            if (lineEndsInBackRankMate(after, input.replyLine)) themes += Theme.BACK_RANK
            parts += "$playedSan allows a forced mate in $allowedMate: $replyText."
        } else if (bestGain - playedGain >= 2 && playedGain <= -2 && reply != null) {
            val captured = after.capturedPiece(reply)
            val replySan = after.san(reply)
            if (captured != null && reply.to == input.played.to) {
                themes += Theme.HANGING_PIECE
                parts += "$playedSan puts the ${captured.type.displayName} on ${Squares.name(reply.to)} where it can be taken: after $replySan you lose ${materialWord(-playedGain)}."
            } else if (captured != null && !wasAttacked(before, reply.to, opponent)) {
                themes += Theme.HANGING_PIECE
                parts += "$playedSan leaves the ${captured.type.displayName} on ${Squares.name(reply.to)} exposed: $replySan wins ${materialWord(-playedGain)}."
            } else {
                parts += "After $playedSan, the engine line $replyText costs you ${materialWord(-playedGain)}."
            }
            forkTargets(after.play(reply), reply.to).takeIf { it.size >= 2 && reply.to != input.played.to }?.let { targets ->
                themes += Theme.FORK
                parts += "$replySan is a fork, hitting ${targets.joinToString(" and ") { "the ${it.second.type.displayName}" }}."
            }
        } else if (reply != null && playedGain <= -1 && bestGain - playedGain >= 1 && reply.to == input.played.to &&
            after.capturedPiece(reply) != null
        ) {
            val captured = after.capturedPiece(reply)!!
            themes += Theme.HANGING_PIECE
            parts += "$playedSan lets ${after.san(reply)} win the ${captured.type.displayName} on ${Squares.name(reply.to)}."
        } else if (bestGain >= 2 && bestGain - playedGain >= 2 && bestSan != null) {
            themes += Theme.MISSED_MATERIAL
            val best = input.bestLine.first()
            val targets = forkTargets(before.play(best), best.to)
            if (targets.size >= 2) {
                themes += Theme.FORK
                parts += "$bestSan was a fork, attacking ${targets.joinToString(" and ") { "the ${it.second.type.displayName} on ${Squares.name(it.first)}" }}, and wins ${materialWord(bestGain)}: $bestLineText."
            } else {
                parts += "$bestSan wins ${materialWord(bestGain)}: $bestLineText."
            }
        }

        if (input.classification == MoveClass.MISS) {
            parts.add(0, "Your opponent's last move gave you a chance to take control.")
        }
        if (parts.isEmpty() || parts.size == 1 && input.classification == MoveClass.MISS) {
            val swing = "The evaluation goes from ${input.bestScore.display()} (best play) to ${input.playedScore.display()}."
            parts += if (bestSan != null) "$bestSan was stronger. $swing" else swing
            if (replyText != null) parts += "Engine line: $replyText."
        } else if (bestSan != null && Theme.MISSED_MATERIAL !in themes && Theme.FORK !in themes) {
            parts += "Better was $bestSan (${input.bestScore.display()})."
        }
        return parts.joinToString(" ")
    }

    private fun contextThemes(input: Input, before: Position, after: Position, mover: Side, themes: MutableSet<Theme>) {
        if (input.ply <= 20) themes += Theme.OPENING
        if (isEndgame(before)) themes += Theme.ENDGAME
        val base = input.baseTimeMillis
        val clock = input.clockMillis
        if (base != null && clock != null && base > 0 && clock < base / 10) themes += Theme.TIME_PRESSURE
        if (weakensKing(before, input.played, mover) && (after.isCheck || input.replyLine.take(3).any { m ->
                givesCheckAlong(after, input.replyLine, m)
            } || input.playedScore.mateFor(mover.opposite) != null)
        ) themes += Theme.KING_SAFETY
    }

    // ------------------------------------------------------------------ helpers

    private fun sanLine(start: Position, line: List<Move>, max: Int): List<String> {
        val out = ArrayList<String>()
        var pos = start
        for (m in line.take(max)) {
            if (!pos.isLegal(m)) break
            out += pos.san(m)
            pos = pos.play(m)
        }
        return out
    }

    private fun balance(pos: Position, side: Side) = pos.material(side) - pos.material(side.opposite)

    /**
     * Material balance for [side] after following [line] for 4 plies — short, forcing horizons
     * only — extended while the line keeps capturing so an exchange is not cut in half.
     */
    internal fun materialAfterLine(start: Position, line: List<Move>, side: Side): Int {
        var pos = start
        for ((i, m) in line.withIndex()) {
            if (i >= 4 && !pos.isCapture(m)) break
            if (i >= 10) break
            if (!pos.isLegal(m)) break
            pos = pos.play(m)
        }
        return balance(pos, side)
    }

    /** Enemy pieces (king, undefended or more valuable than the attacker) attacked from [sq]. */
    internal fun forkTargets(pos: Position, sq: Int): List<Pair<Int, Piece>> {
        val attacker = pos.pieceAt(sq) ?: return emptyList()
        if (pos.isAttacked(sq, attacker.side.opposite) && !pos.isAttacked(sq, attacker.side)) return emptyList()
        val value = if (attacker.type == PieceType.KING) 100 else attacker.type.value
        return pos.attacksFrom(sq).mapNotNull { t ->
            val target = pos.pieceAt(t) ?: return@mapNotNull null
            if (target.side == attacker.side) return@mapNotNull null
            val worthIt = target.type == PieceType.KING || target.type.value > value ||
                (target.type != PieceType.PAWN && !pos.isAttacked(t, target.side))
            if (worthIt) t to target else null
        }
    }

    private fun wasAttacked(pos: Position, sq: Int, by: Side): Boolean = pos.isAttacked(sq, by)

    private fun isEndgame(pos: Position): Boolean {
        val queens = pos.pieces().count { it.second.type == PieceType.QUEEN }
        val npm = pos.nonPawnMaterial(Side.WHITE) + pos.nonPawnMaterial(Side.BLACK)
        return npm <= 13 || (queens == 0 && pos.nonPawnMaterial(Side.WHITE) <= 8 && pos.nonPawnMaterial(Side.BLACK) <= 8)
    }

    private fun weakensKing(before: Position, move: Move, side: Side): Boolean {
        val piece = before.pieceAt(move.from) ?: return false
        val king = before.kingSquare(side)
        val kingFile = Squares.file(king)
        if (piece.type == PieceType.KING && !before.isCastle(move)) {
            val hadRights = if (side.isWhite) before.castlingRights and 3 != 0 else before.castlingRights and 12 != 0
            return hadRights
        }
        if (piece.type == PieceType.PAWN) {
            val pawnFile = Squares.file(move.from)
            val shelter = when {
                kingFile >= 5 -> 5..7
                kingFile <= 2 -> 0..2
                else -> return false
            }
            return pawnFile in shelter
        }
        return false
    }

    private fun givesCheckAlong(start: Position, line: List<Move>, target: Move): Boolean {
        var pos = start
        for (m in line) {
            if (!pos.isLegal(m)) return false
            pos = pos.play(m)
            if (m == target) return pos.isCheck
        }
        return false
    }

    private fun lineEndsInBackRankMate(start: Position, line: List<Move>): Boolean {
        var pos = start
        for (m in line) {
            if (!pos.isLegal(m)) return false
            pos = pos.play(m)
        }
        if (!pos.isCheckmate) return false
        val mated = pos.sideToMove
        val king = pos.kingSquare(mated)
        val rank = Squares.rank(king)
        if (rank != if (mated.isWhite) 0 else 7) return false
        // A rook or queen giving check along the back rank itself.
        return (0..7).map { Squares.of(it, rank) }.any { sq ->
            val p = pos.pieceAt(sq)
            p != null && p.side != mated && (p.type == PieceType.ROOK || p.type == PieceType.QUEEN) &&
                king in pos.attacksFrom(sq)
        }
    }

    companion object {
        fun materialWord(units: Int): String = when {
            units >= 9 -> "a queen's worth of material"
            units >= 5 -> "a rook's worth of material"
            units >= 3 -> "a piece"
            units == 2 -> "two pawns' worth of material"
            else -> "a pawn"
        }
    }
}
