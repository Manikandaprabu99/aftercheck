package app.aftercheck.chess

enum class Side {
    WHITE, BLACK;

    val opposite: Side get() = if (this == WHITE) BLACK else WHITE
    val isWhite: Boolean get() = this == WHITE
}

enum class PieceType(val letter: Char, val value: Int) {
    PAWN('P', 1),
    KNIGHT('N', 3),
    BISHOP('B', 3),
    ROOK('R', 5),
    QUEEN('Q', 9),
    KING('K', 0);

    val displayName: String get() = name.lowercase()

    companion object {
        fun fromLetter(c: Char): PieceType? = when (c.uppercaseChar()) {
            'P' -> PAWN
            'N' -> KNIGHT
            'B' -> BISHOP
            'R' -> ROOK
            'Q' -> QUEEN
            'K' -> KING
            else -> null
        }
    }
}

data class Piece(val side: Side, val type: PieceType) {
    val fenChar: Char get() = if (side.isWhite) type.letter else type.letter.lowercaseChar()

    companion object {
        fun fromFenChar(c: Char): Piece? {
            val type = PieceType.fromLetter(c) ?: return null
            return Piece(if (c.isUpperCase()) Side.WHITE else Side.BLACK, type)
        }
    }
}

/** Squares are 0..63 with a1 = 0, h1 = 7, a8 = 56. */
object Squares {
    fun of(file: Int, rank: Int): Int = rank * 8 + file
    fun file(sq: Int): Int = sq and 7
    fun rank(sq: Int): Int = sq shr 3
    fun name(sq: Int): String = "${'a' + file(sq)}${'1' + rank(sq)}"
    fun parse(name: String): Int? {
        if (name.length != 2) return null
        val f = name[0] - 'a'
        val r = name[1] - '1'
        return if (f in 0..7 && r in 0..7) of(f, r) else null
    }
    fun isLight(sq: Int): Boolean = (file(sq) + rank(sq)) % 2 == 1
}

data class Move(val from: Int, val to: Int, val promotion: PieceType? = null) {
    val uci: String
        get() = Squares.name(from) + Squares.name(to) + (promotion?.letter?.lowercaseChar()?.toString() ?: "")

    override fun toString(): String = uci

    companion object {
        fun fromUci(uci: String): Move? {
            if (uci.length !in 4..5) return null
            val from = Squares.parse(uci.substring(0, 2)) ?: return null
            val to = Squares.parse(uci.substring(2, 4)) ?: return null
            val promo = if (uci.length == 5) {
                PieceType.fromLetter(uci[4])?.takeIf { it != PieceType.PAWN && it != PieceType.KING } ?: return null
            } else null
            return Move(from, to, promo)
        }
    }
}

enum class Termination { CHECKMATE, STALEMATE, INSUFFICIENT_MATERIAL, FIFTY_MOVES, THREEFOLD_REPETITION }

data class Outcome(val winner: Side?, val termination: Termination)
