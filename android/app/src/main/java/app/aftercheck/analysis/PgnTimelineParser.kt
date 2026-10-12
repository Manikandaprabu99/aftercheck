package app.aftercheck.analysis

import com.github.bhlangonijr.chesslib.Board

data class PgnMoveSnapshot(
    val plyNumber: Int,
    val san: String,
    val sideToMove: String,
    val fenBefore: String,
    val fenAfter: String
)

object PgnTimelineParser {
    fun parse(pgn: String): List<PgnMoveSnapshot> {
        val board = Board()
        val fenHeader = headerValue(pgn, "FEN")
        if (fenHeader.isNotBlank()) board.loadFromFen(fenHeader)

        val separator = pgn.indexOf("\n\n").takeIf { it >= 0 }
            ?: pgn.indexOf("\r\n\r\n").takeIf { it >= 0 }
        val moveText = if (separator == null) "" else {
            val delimiterLength = if (pgn.startsWith("\r\n", separator)) 4 else 2
            pgn.substring(separator + delimiterLength)
        }
        val tokens = tokenize(moveText)
        val result = mutableListOf<PgnMoveSnapshot>()

        tokens.forEach { raw ->
            var san = stripMoveNumber(raw.trim())
            if (san.isBlank() || san.startsWith("$") || san in setOf("*", "1-0", "0-1", "1/2-1/2", "e.p.", "ep")) {
                return@forEach
            }
            san = san.trimEnd('!', '?')
            if (san.isBlank() || san == "...") return@forEach

            val fenBefore = board.getFen()
            val side = board.getSideToMove().name
            val applied = runCatching { board.doMove(san) }.getOrDefault(false)
            require(applied) { "Could not replay PGN ply ${result.size + 1} ('$san')." }
            result += PgnMoveSnapshot(
                plyNumber = result.size + 1,
                san = san,
                sideToMove = side,
                fenBefore = fenBefore,
                fenAfter = board.getFen()
            )
        }
        return result
    }

    private fun headerValue(pgn: String, key: String): String {
        val line = pgn.lineSequence().firstOrNull { it.startsWith("[$key ") } ?: return ""
        val first = line.indexOf('"')
        val last = line.lastIndexOf('"')
        return if (first >= 0 && last > first) line.substring(first + 1, last) else ""
    }

    private fun stripMoveNumber(token: String): String {
        val dotIndex = token.indexOf('.')
        if (dotIndex > 0 && token.substring(0, dotIndex).all { it.isDigit() }) {
            return token.dropWhile { it.isDigit() || it == '.' }
        }
        return token
    }

    private fun tokenize(text: String): List<String> {
        val cleaned = StringBuilder()
        var braceDepth = 0
        var variationDepth = 0
        var lineComment = false
        for (char in text) {
            when {
                lineComment -> if (char == '\n') { lineComment = false; cleaned.append(' ') }
                braceDepth > 0 -> {
                    if (char == '{') braceDepth++
                    if (char == '}') braceDepth--
                }
                variationDepth > 0 -> {
                    if (char == '(') variationDepth++
                    if (char == ')') variationDepth--
                }
                char == ';' -> lineComment = true
                char == '{' -> braceDepth = 1
                char == '(' -> variationDepth = 1
                else -> cleaned.append(char)
            }
        }
        return cleaned.toString().split(' ', '\n', '\r', '\t').filter { it.isNotBlank() }
    }
}
