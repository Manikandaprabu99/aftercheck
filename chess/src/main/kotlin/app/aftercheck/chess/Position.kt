package app.aftercheck.chess

/**
 * Immutable chess position with full legal move generation.
 *
 * Board codes: 0 = empty, otherwise `type.ordinal + 1`, plus 8 for black pieces.
 */
class Position private constructor(
    private val board: IntArray,
    val sideToMove: Side,
    /** Bit set: 1 = white O-O, 2 = white O-O-O, 4 = black O-O, 8 = black O-O-O. */
    val castlingRights: Int,
    /** Square a pawn just skipped over, or -1. Kept as in FEN even if no capture is possible. */
    val epSquare: Int,
    val halfmoveClock: Int,
    val fullmoveNumber: Int,
) {
    private var cachedLegal: List<Move>? = null

    fun pieceAt(sq: Int): Piece? = PIECES[board[sq]]

    fun pieces(): List<Pair<Int, Piece>> = (0 until 64).mapNotNull { sq -> PIECES[board[sq]]?.let { sq to it } }

    fun kingSquare(side: Side): Int {
        val code = code(side, PieceType.KING)
        for (sq in 0 until 64) if (board[sq] == code) return sq
        return -1
    }

    val isCheck: Boolean get() = kingSquare(sideToMove).let { it >= 0 && isAttacked(it, sideToMove.opposite) }
    val isCheckmate: Boolean get() = isCheck && legalMoves().isEmpty()
    val isStalemate: Boolean get() = !isCheck && legalMoves().isEmpty()

    /** Outcome detectable from this position alone (repetition needs history; see [GameLine]). */
    fun outcome(): Outcome? = when {
        legalMoves().isEmpty() -> if (isCheck) Outcome(sideToMove.opposite, Termination.CHECKMATE) else Outcome(null, Termination.STALEMATE)
        isInsufficientMaterial() -> Outcome(null, Termination.INSUFFICIENT_MATERIAL)
        halfmoveClock >= 100 -> Outcome(null, Termination.FIFTY_MOVES)
        else -> null
    }

    fun isInsufficientMaterial(): Boolean {
        var minorWhite = 0
        var minorBlack = 0
        val bishopColors = mutableSetOf<Boolean>()
        var bishops = 0
        var knights = 0
        for (sq in 0 until 64) {
            val p = PIECES[board[sq]] ?: continue
            when (p.type) {
                PieceType.PAWN, PieceType.ROOK, PieceType.QUEEN -> return false
                PieceType.KING -> Unit
                PieceType.KNIGHT -> { knights++; if (p.side.isWhite) minorWhite++ else minorBlack++ }
                PieceType.BISHOP -> {
                    bishops++; bishopColors += Squares.isLight(sq)
                    if (p.side.isWhite) minorWhite++ else minorBlack++
                }
            }
        }
        if (minorWhite + minorBlack <= 1) return true
        // Only bishops, all on the same colour complex: no mate is possible.
        return knights == 0 && bishops > 0 && bishopColors.size == 1
    }

    /** Material in pawn units (P=1, N=B=3, R=5, Q=9). */
    fun material(side: Side): Int {
        var total = 0
        for (sq in 0 until 64) {
            val p = PIECES[board[sq]] ?: continue
            if (p.side == side) total += p.type.value
        }
        return total
    }

    fun nonPawnMaterial(side: Side): Int {
        var total = 0
        for (sq in 0 until 64) {
            val p = PIECES[board[sq]] ?: continue
            if (p.side == side && p.type != PieceType.PAWN) total += p.type.value
        }
        return total
    }

    // ---------------------------------------------------------------- attacks

    fun isAttacked(sq: Int, by: Side): Boolean {
        val f = Squares.file(sq)
        val r = Squares.rank(sq)
        // Pawns: a white pawn attacks upwards, so it sits one rank below the target.
        val pawnRank = if (by.isWhite) r - 1 else r + 1
        if (pawnRank in 0..7) {
            val pawn = code(by, PieceType.PAWN)
            if (f > 0 && board[Squares.of(f - 1, pawnRank)] == pawn) return true
            if (f < 7 && board[Squares.of(f + 1, pawnRank)] == pawn) return true
        }
        val knight = code(by, PieceType.KNIGHT)
        for (d in KNIGHT_DELTAS) {
            val nf = f + d[0]; val nr = r + d[1]
            if (nf in 0..7 && nr in 0..7 && board[Squares.of(nf, nr)] == knight) return true
        }
        val king = code(by, PieceType.KING)
        for (d in KING_DELTAS) {
            val nf = f + d[0]; val nr = r + d[1]
            if (nf in 0..7 && nr in 0..7 && board[Squares.of(nf, nr)] == king) return true
        }
        val queen = code(by, PieceType.QUEEN)
        val bishop = code(by, PieceType.BISHOP)
        val rook = code(by, PieceType.ROOK)
        for (d in BISHOP_DIRS) if (rayHits(f, r, d, bishop, queen)) return true
        for (d in ROOK_DIRS) if (rayHits(f, r, d, rook, queen)) return true
        return false
    }

    private fun rayHits(f0: Int, r0: Int, d: IntArray, a: Int, b: Int): Boolean {
        var f = f0 + d[0]; var r = r0 + d[1]
        while (f in 0..7 && r in 0..7) {
            val c = board[Squares.of(f, r)]
            if (c != 0) return c == a || c == b
            f += d[0]; r += d[1]
        }
        return false
    }

    /** Squares attacked by the piece standing on [sq] (ignores pins). */
    fun attacksFrom(sq: Int): List<Int> {
        val p = pieceAt(sq) ?: return emptyList()
        val f = Squares.file(sq); val r = Squares.rank(sq)
        val out = ArrayList<Int>()
        fun steps(deltas: Array<IntArray>) {
            for (d in deltas) {
                val nf = f + d[0]; val nr = r + d[1]
                if (nf in 0..7 && nr in 0..7) out += Squares.of(nf, nr)
            }
        }
        fun rays(dirs: Array<IntArray>) {
            for (d in dirs) {
                var nf = f + d[0]; var nr = r + d[1]
                while (nf in 0..7 && nr in 0..7) {
                    val t = Squares.of(nf, nr)
                    out += t
                    if (board[t] != 0) break
                    nf += d[0]; nr += d[1]
                }
            }
        }
        when (p.type) {
            PieceType.PAWN -> {
                val nr = if (p.side.isWhite) r + 1 else r - 1
                if (nr in 0..7) {
                    if (f > 0) out += Squares.of(f - 1, nr)
                    if (f < 7) out += Squares.of(f + 1, nr)
                }
            }
            PieceType.KNIGHT -> steps(KNIGHT_DELTAS)
            PieceType.KING -> steps(KING_DELTAS)
            PieceType.BISHOP -> rays(BISHOP_DIRS)
            PieceType.ROOK -> rays(ROOK_DIRS)
            PieceType.QUEEN -> { rays(BISHOP_DIRS); rays(ROOK_DIRS) }
        }
        return out
    }

    // ---------------------------------------------------------- move generation

    fun legalMoves(): List<Move> {
        cachedLegal?.let { return it }
        val result = ArrayList<Move>(48)
        for (m in pseudoLegalMoves()) {
            val next = applyUnchecked(m)
            val k = next.kingSquare(sideToMove)
            if (k < 0 || !next.isAttacked(k, sideToMove.opposite)) result += m
        }
        cachedLegal = result
        return result
    }

    fun legalMovesFrom(sq: Int): List<Move> = legalMoves().filter { it.from == sq }

    fun isLegal(move: Move): Boolean = move in legalMoves()

    private fun pseudoLegalMoves(): List<Move> {
        val out = ArrayList<Move>(64)
        val us = sideToMove
        for (sq in 0 until 64) {
            val c = board[sq]
            if (c == 0 || sideOf(c) != us) continue
            val f = Squares.file(sq); val r = Squares.rank(sq)
            when (typeOf(c)) {
                PieceType.PAWN -> pawnMoves(sq, f, r, out)
                PieceType.KNIGHT -> stepMoves(sq, f, r, KNIGHT_DELTAS, out)
                PieceType.BISHOP -> slideMoves(sq, f, r, BISHOP_DIRS, out)
                PieceType.ROOK -> slideMoves(sq, f, r, ROOK_DIRS, out)
                PieceType.QUEEN -> { slideMoves(sq, f, r, BISHOP_DIRS, out); slideMoves(sq, f, r, ROOK_DIRS, out) }
                PieceType.KING -> { stepMoves(sq, f, r, KING_DELTAS, out); castleMoves(sq, out) }
                null -> Unit
            }
        }
        return out
    }

    private fun pawnMoves(sq: Int, f: Int, r: Int, out: MutableList<Move>) {
        val dir = if (sideToMove.isWhite) 1 else -1
        val startRank = if (sideToMove.isWhite) 1 else 6
        val lastRank = if (sideToMove.isWhite) 7 else 0
        val r1 = r + dir
        if (r1 !in 0..7) return
        fun add(to: Int) {
            if (Squares.rank(to) == lastRank) {
                for (p in PROMOTIONS) out += Move(sq, to, p)
            } else out += Move(sq, to)
        }
        val one = Squares.of(f, r1)
        if (board[one] == 0) {
            add(one)
            if (r == startRank) {
                val two = Squares.of(f, r + 2 * dir)
                if (board[two] == 0) out += Move(sq, two)
            }
        }
        for (df in intArrayOf(-1, 1)) {
            val nf = f + df
            if (nf !in 0..7) continue
            val t = Squares.of(nf, r1)
            val c = board[t]
            if (c != 0 && sideOf(c) != sideToMove) add(t)
            else if (t == epSquare && c == 0) out += Move(sq, t)
        }
    }

    private fun stepMoves(sq: Int, f: Int, r: Int, deltas: Array<IntArray>, out: MutableList<Move>) {
        for (d in deltas) {
            val nf = f + d[0]; val nr = r + d[1]
            if (nf !in 0..7 || nr !in 0..7) continue
            val t = Squares.of(nf, nr)
            val c = board[t]
            if (c == 0 || sideOf(c) != sideToMove) out += Move(sq, t)
        }
    }

    private fun slideMoves(sq: Int, f: Int, r: Int, dirs: Array<IntArray>, out: MutableList<Move>) {
        for (d in dirs) {
            var nf = f + d[0]; var nr = r + d[1]
            while (nf in 0..7 && nr in 0..7) {
                val t = Squares.of(nf, nr)
                val c = board[t]
                if (c == 0) out += Move(sq, t)
                else {
                    if (sideOf(c) != sideToMove) out += Move(sq, t)
                    break
                }
                nf += d[0]; nr += d[1]
            }
        }
    }

    private fun castleMoves(sq: Int, out: MutableList<Move>) {
        val white = sideToMove.isWhite
        val home = if (white) 4 else 60
        if (sq != home) return
        val them = sideToMove.opposite
        val kingSide = if (white) CASTLE_WK else CASTLE_BK
        val queenSide = if (white) CASTLE_WQ else CASTLE_BQ
        val rook = code(sideToMove, PieceType.ROOK)
        if (castlingRights and (kingSide or queenSide) == 0) return
        if (isAttacked(home, them)) return
        if (castlingRights and kingSide != 0 && board[home + 3] == rook &&
            board[home + 1] == 0 && board[home + 2] == 0 &&
            !isAttacked(home + 1, them) && !isAttacked(home + 2, them)
        ) out += Move(home, home + 2)
        if (castlingRights and queenSide != 0 && board[home - 4] == rook &&
            board[home - 1] == 0 && board[home - 2] == 0 && board[home - 3] == 0 &&
            !isAttacked(home - 1, them) && !isAttacked(home - 2, them)
        ) out += Move(home, home - 2)
    }

    // ---------------------------------------------------------------- playing

    /** Plays a legal move. Throws [IllegalArgumentException] for illegal moves. */
    fun play(move: Move): Position {
        require(isLegal(move)) { "Illegal move ${move.uci} in ${toFen()}" }
        return applyUnchecked(move)
    }

    fun isCapture(move: Move): Boolean {
        val target = board[move.to]
        return target != 0 || isEnPassant(move)
    }

    fun isEnPassant(move: Move): Boolean =
        move.to == epSquare && board[move.to] == 0 && typeOf(board[move.from]) == PieceType.PAWN &&
            Squares.file(move.from) != Squares.file(move.to)

    fun isCastle(move: Move): Boolean =
        typeOf(board[move.from]) == PieceType.KING && kotlin.math.abs(move.to - move.from) == 2

    /** Piece captured by [move] (including en passant), or null. */
    fun capturedPiece(move: Move): Piece? =
        if (isEnPassant(move)) Piece(sideToMove.opposite, PieceType.PAWN) else pieceAt(move.to)

    private fun applyUnchecked(move: Move): Position {
        val b = board.copyOf()
        val moving = b[move.from]
        val type = typeOf(moving)
        val captured = b[move.to]
        var ep = -1
        var rights = castlingRights

        if (type == PieceType.PAWN && move.to == epSquare && captured == 0 &&
            Squares.file(move.from) != Squares.file(move.to)
        ) {
            val capSq = if (sideToMove.isWhite) move.to - 8 else move.to + 8
            b[capSq] = 0
        }
        b[move.to] = if (move.promotion != null) code(sideToMove, move.promotion) else moving
        b[move.from] = 0

        if (type == PieceType.KING && kotlin.math.abs(move.to - move.from) == 2) {
            if (move.to > move.from) { b[move.from + 1] = b[move.from + 3]; b[move.from + 3] = 0 }
            else { b[move.from - 1] = b[move.from - 4]; b[move.from - 4] = 0 }
        }
        if (type == PieceType.PAWN && kotlin.math.abs(move.to - move.from) == 16) {
            ep = (move.from + move.to) / 2
        }
        rights = rights and CASTLE_MASK[move.from] and CASTLE_MASK[move.to]

        val halfmove = if (type == PieceType.PAWN || captured != 0) 0 else halfmoveClock + 1
        val fullmove = if (sideToMove == Side.BLACK) fullmoveNumber + 1 else fullmoveNumber
        return Position(b, sideToMove.opposite, rights, ep, halfmove, fullmove)
    }

    // ------------------------------------------------------------------- FEN

    fun toFen(): String = buildString {
        append(placementFen())
        append(' ').append(if (sideToMove.isWhite) 'w' else 'b')
        append(' ').append(castlingFen())
        append(' ').append(if (epSquare >= 0) Squares.name(epSquare) else "-")
        append(' ').append(halfmoveClock)
        append(' ').append(fullmoveNumber)
    }

    /** FEN without move counters and with en passant only when a capture is actually possible. */
    fun epd(): String = buildString {
        append(placementFen())
        append(' ').append(if (sideToMove.isWhite) 'w' else 'b')
        append(' ').append(castlingFen())
        append(' ').append(if (hasLegalEnPassant()) Squares.name(epSquare) else "-")
    }

    private fun placementFen(): String = buildString {
        for (rank in 7 downTo 0) {
            var empty = 0
            for (file in 0..7) {
                val p = PIECES[board[Squares.of(file, rank)]]
                if (p == null) empty++ else {
                    if (empty > 0) { append(empty); empty = 0 }
                    append(p.fenChar)
                }
            }
            if (empty > 0) append(empty)
            if (rank > 0) append('/')
        }
    }

    private fun castlingFen(): String {
        val s = buildString {
            if (castlingRights and CASTLE_WK != 0) append('K')
            if (castlingRights and CASTLE_WQ != 0) append('Q')
            if (castlingRights and CASTLE_BK != 0) append('k')
            if (castlingRights and CASTLE_BQ != 0) append('q')
        }
        return s.ifEmpty { "-" }
    }

    private fun hasLegalEnPassant(): Boolean = epSquare >= 0 && legalMoves().any { isEnPassant(it) }

    /** 64-bit Zobrist hash of the position (side, castling, real en passant). Stable across runs. */
    val zobrist: Long by lazy {
        var h = 0L
        for (sq in 0 until 64) {
            val c = board[sq]
            if (c != 0) h = h xor Zobrist.piece[pieceIndex(c) * 64 + sq]
        }
        if (!sideToMove.isWhite) h = h xor Zobrist.blackToMove
        h = h xor Zobrist.castling[castlingRights]
        if (hasLegalEnPassant()) h = h xor Zobrist.epFile[Squares.file(epSquare)]
        h
    }

    override fun equals(other: Any?): Boolean =
        other is Position && other.toFen() == toFen()

    override fun hashCode(): Int = toFen().hashCode()

    override fun toString(): String = toFen()

    companion object {
        const val START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

        const val CASTLE_WK = 1
        const val CASTLE_WQ = 2
        const val CASTLE_BK = 4
        const val CASTLE_BQ = 8

        val initial: Position by lazy { fromFen(START_FEN) }

        private val PROMOTIONS = arrayOf(PieceType.QUEEN, PieceType.ROOK, PieceType.BISHOP, PieceType.KNIGHT)
        private val KNIGHT_DELTAS = arrayOf(
            intArrayOf(1, 2), intArrayOf(2, 1), intArrayOf(2, -1), intArrayOf(1, -2),
            intArrayOf(-1, -2), intArrayOf(-2, -1), intArrayOf(-2, 1), intArrayOf(-1, 2),
        )
        private val KING_DELTAS = arrayOf(
            intArrayOf(1, 0), intArrayOf(1, 1), intArrayOf(0, 1), intArrayOf(-1, 1),
            intArrayOf(-1, 0), intArrayOf(-1, -1), intArrayOf(0, -1), intArrayOf(1, -1),
        )
        private val BISHOP_DIRS = arrayOf(intArrayOf(1, 1), intArrayOf(-1, 1), intArrayOf(1, -1), intArrayOf(-1, -1))
        private val ROOK_DIRS = arrayOf(intArrayOf(1, 0), intArrayOf(-1, 0), intArrayOf(0, 1), intArrayOf(0, -1))

        /** Castling rights that survive a move touching each square. */
        private val CASTLE_MASK = IntArray(64) { 15 }.also {
            it[4] = 15 and (CASTLE_WK or CASTLE_WQ).inv()
            it[0] = 15 and CASTLE_WQ.inv()
            it[7] = 15 and CASTLE_WK.inv()
            it[60] = 15 and (CASTLE_BK or CASTLE_BQ).inv()
            it[56] = 15 and CASTLE_BQ.inv()
            it[63] = 15 and CASTLE_BK.inv()
        }

        private val PIECES: Array<Piece?> = Array(16) { code ->
            if (code == 0 || (code and 7) == 0 || (code and 7) > 6) null
            else Piece(if (code and 8 != 0) Side.BLACK else Side.WHITE, PieceType.entries[(code and 7) - 1])
        }

        private fun code(side: Side, type: PieceType): Int = (type.ordinal + 1) or (if (side.isWhite) 0 else 8)
        private fun sideOf(code: Int): Side = if (code and 8 != 0) Side.BLACK else Side.WHITE
        private fun typeOf(code: Int): PieceType? = if (code == 0) null else PieceType.entries[(code and 7) - 1]
        private fun pieceIndex(code: Int): Int = ((code and 7) - 1) + (if (code and 8 != 0) 6 else 0)

        /** Parses a FEN string. Throws [IllegalArgumentException] when malformed. */
        fun fromFen(fen: String): Position {
            val parts = fen.trim().split(Regex("\\s+"))
            require(parts.size >= 4) { "FEN needs at least 4 fields: $fen" }
            val board = IntArray(64)
            val ranks = parts[0].split('/')
            require(ranks.size == 8) { "FEN placement needs 8 ranks: $fen" }
            for ((i, rankStr) in ranks.withIndex()) {
                val rank = 7 - i
                var file = 0
                for (ch in rankStr) {
                    if (ch.isDigit()) file += ch - '0'
                    else {
                        val p = Piece.fromFenChar(ch) ?: throw IllegalArgumentException("Bad piece '$ch' in $fen")
                        require(file <= 7) { "Rank overflow in $fen" }
                        board[Squares.of(file, rank)] = code(p.side, p.type)
                        file++
                    }
                }
                require(file == 8) { "Rank ${rank + 1} does not have 8 files in $fen" }
            }
            val side = when (parts[1]) {
                "w" -> Side.WHITE
                "b" -> Side.BLACK
                else -> throw IllegalArgumentException("Bad side to move in $fen")
            }
            var rights = 0
            if (parts[2] != "-") for (c in parts[2]) rights = rights or when (c) {
                'K' -> CASTLE_WK; 'Q' -> CASTLE_WQ; 'k' -> CASTLE_BK; 'q' -> CASTLE_BQ
                else -> throw IllegalArgumentException("Bad castling field in $fen")
            }
            // Drop rights that the piece placement cannot support.
            if (board[4] != code(Side.WHITE, PieceType.KING)) rights = rights and (CASTLE_WK or CASTLE_WQ).inv()
            if (board[7] != code(Side.WHITE, PieceType.ROOK)) rights = rights and CASTLE_WK.inv()
            if (board[0] != code(Side.WHITE, PieceType.ROOK)) rights = rights and CASTLE_WQ.inv()
            if (board[60] != code(Side.BLACK, PieceType.KING)) rights = rights and (CASTLE_BK or CASTLE_BQ).inv()
            if (board[63] != code(Side.BLACK, PieceType.ROOK)) rights = rights and CASTLE_BK.inv()
            if (board[56] != code(Side.BLACK, PieceType.ROOK)) rights = rights and CASTLE_BQ.inv()
            val ep = if (parts[3] == "-") -1 else Squares.parse(parts[3]) ?: throw IllegalArgumentException("Bad en passant square in $fen")
            val half = parts.getOrNull(4)?.toIntOrNull() ?: 0
            val full = parts.getOrNull(5)?.toIntOrNull()?.coerceAtLeast(1) ?: 1
            val whiteKings = board.count { it == code(Side.WHITE, PieceType.KING) }
            val blackKings = board.count { it == code(Side.BLACK, PieceType.KING) }
            require(whiteKings == 1 && blackKings == 1) { "Each side needs exactly one king: $fen" }
            return Position(board, side, rights, ep, half, full)
        }
    }
}

private object Zobrist {
    private var state = 0x9E3779B97F4A7C15uL.toLong()
    private fun next(): Long {
        state += -0x61c8864680b583ebL
        var z = state
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }

    val piece = LongArray(12 * 64) { next() }
    val blackToMove = next()
    val castling = LongArray(16) { next() }
    val epFile = LongArray(8) { next() }
}
