package app.aftercheck.chess

/** Standard Algebraic Notation for a legal [move] in this position, including check/mate suffix. */
fun Position.san(move: Move): String {
    val base = sanWithoutSuffix(move)
    val next = play(move)
    val suffix = when {
        next.isCheckmate -> "#"
        next.isCheck -> "+"
        else -> ""
    }
    return base + suffix
}

private fun Position.sanWithoutSuffix(move: Move): String {
    val piece = pieceAt(move.from) ?: throw IllegalArgumentException("No piece on ${Squares.name(move.from)}")
    if (isCastle(move)) return if (move.to > move.from) "O-O" else "O-O-O"
    val capture = isCapture(move)
    val target = Squares.name(move.to)
    if (piece.type == PieceType.PAWN) {
        return buildString {
            if (capture) append('a' + Squares.file(move.from)).append('x')
            append(target)
            if (move.promotion != null) append('=').append(move.promotion.letter)
        }
    }
    val rivals = legalMoves().filter {
        it.to == move.to && it.from != move.from && pieceAt(it.from)?.type == piece.type
    }
    val disambiguation = when {
        rivals.isEmpty() -> ""
        rivals.none { Squares.file(it.from) == Squares.file(move.from) } -> ('a' + Squares.file(move.from)).toString()
        rivals.none { Squares.rank(it.from) == Squares.rank(move.from) } -> ('1' + Squares.rank(move.from)).toString()
        else -> Squares.name(move.from)
    }
    return buildString {
        append(piece.type.letter)
        append(disambiguation)
        if (capture) append('x')
        append(target)
    }
}

/**
 * Parses SAN leniently: accepts check/annotation suffixes, `0-0`, missing `=` for promotions,
 * optional or superfluous capture marks and over-disambiguation. Returns null when the text is
 * not a legal move here (or is ambiguous).
 */
fun Position.parseSan(text: String): Move? {
    var s = text.trim()
        .replace("e.p.", "")
        .trimEnd('+', '#', '!', '?', ' ')
    if (s.isEmpty()) return null
    val legal = legalMoves()
    when (s.replace('0', 'O')) {
        "O-O", "OO" -> return legal.firstOrNull { isCastle(it) && it.to > it.from }
        "O-O-O", "OOO" -> return legal.firstOrNull { isCastle(it) && it.to < it.from }
    }
    // UCI fallback ("e2e4"), occasionally found in hand-written input.
    if (s.length in 4..5 && s[0] in 'a'..'h' && s[1] in '1'..'8' && s[2] in 'a'..'h' && s[3] in '1'..'8') {
        Move.fromUci(s.lowercase())?.let { m -> if (m in legal) return m }
    }

    var promotion: PieceType? = null
    val eq = s.indexOf('=')
    if (eq >= 0) {
        promotion = PieceType.fromLetter(s.getOrNull(eq + 1) ?: return null) ?: return null
        s = s.substring(0, eq)
    } else if (s.length >= 3 && s.last().uppercaseChar() in "QRBN" && s[s.length - 2] in "18" && s[0] in 'a'..'h') {
        promotion = PieceType.fromLetter(s.last())
        s = s.dropLast(1)
    }
    if (s.length < 2) return null

    val pieceType = if (s[0] in "NBRQK") PieceType.fromLetter(s[0])!! else PieceType.PAWN
    val body = if (pieceType == PieceType.PAWN) s else s.substring(1)
    val target = Squares.parse(body.takeLast(2)) ?: return null
    val hint = body.dropLast(2).replace("x", "").replace("-", "").replace(":", "")
    var fromFile = -1
    var fromRank = -1
    for (c in hint) when (c) {
        in 'a'..'h' -> fromFile = c - 'a'
        in '1'..'8' -> fromRank = c - '1'
        else -> return null
    }
    val candidates = legal.filter { m ->
        m.to == target &&
            pieceAt(m.from)?.type == pieceType &&
            !isCastle(m) &&
            (fromFile < 0 || Squares.file(m.from) == fromFile) &&
            (fromRank < 0 || Squares.rank(m.from) == fromRank) &&
            (m.promotion == promotion || (promotion == null && m.promotion == PieceType.QUEEN))
    }
    return when (candidates.size) {
        1 -> candidates[0]
        else -> null
    }
}

/** Converts a UCI move list to SAN starting from this position; stops at the first illegal move. */
fun Position.uciLineToSan(uciMoves: List<String>): List<String> {
    val out = ArrayList<String>(uciMoves.size)
    var pos = this
    for (u in uciMoves) {
        val m = Move.fromUci(u)?.takeIf { pos.isLegal(it) } ?: break
        out += pos.san(m)
        pos = pos.play(m)
    }
    return out
}

/** Formats a SAN line with move numbers, e.g. "18...Nf6 19. e5 Nd5". */
fun formatLine(start: Position, sans: List<String>): String = buildString {
    var moveNo = start.fullmoveNumber
    var white = start.sideToMove.isWhite
    for ((i, san) in sans.withIndex()) {
        if (white) append(moveNo).append(". ")
        else if (i == 0) append(moveNo).append("...")
        append(san)
        if (i < sans.lastIndex) append(' ')
        if (!white) moveNo++
        white = !white
    }
}
