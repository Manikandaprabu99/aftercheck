package app.aftercheck.chess

data class PgnMove(
    val san: String,
    /** Remaining clock after the move in milliseconds, from a `[%clk h:mm:ss]` comment. */
    val clockMillis: Long? = null,
)

data class PgnGame(
    val tags: Map<String, String>,
    val moves: List<PgnMove>,
    val result: String,
) {
    fun tag(name: String): String? = tags[name]?.takeIf { it.isNotBlank() && it != "?" && it != "-" }

    /** Starting position from the SetUp/FEN tags, or the standard initial position. */
    fun startPosition(): Position = tag("FEN")?.let { Position.fromFen(it) } ?: Position.initial
}

class PgnException(message: String) : Exception(message)

object Pgn {
    private val TAG = Regex("""^\[\s*([A-Za-z0-9_]+)\s+"((?:[^"\\]|\\.)*)"\s*]\s*$""")
    private val CLOCK = Regex("""\[%clk\s+(\d+):(\d{1,2}):(\d{1,2}(?:\.\d+)?)]""")
    private val MOVE_NUMBER = Regex("""^\d+\.+$""")
    private val MOVE_NUMBER_PREFIX = Regex("""^\d+\.+""")
    private val RESULTS = setOf("1-0", "0-1", "1/2-1/2", "*")

    /** Parses every game in a (possibly multi-game) PGN text. Malformed games raise [PgnException]. */
    fun parseAll(text: String): List<PgnGame> = splitGames(text).map { parse(it) }

    fun parse(text: String): PgnGame {
        val tags = LinkedHashMap<String, String>()
        val movetext = StringBuilder()
        var inHeader = true
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (inHeader) {
                if (line.isEmpty()) continue
                if (line.startsWith("[") && !line.startsWith("[%")) {
                    val m = TAG.matchEntire(line) ?: throw PgnException("Malformed tag: $line")
                    tags[m.groupValues[1]] = m.groupValues[2].replace("\\\"", "\"").replace("\\\\", "\\")
                    continue
                }
                inHeader = false
            }
            if (line.startsWith("%")) continue // escape line
            movetext.append(raw).append('\n')
        }
        val (moves, result) = parseMovetext(movetext.toString())
        val finalResult = tags["Result"]?.takeIf { it in RESULTS } ?: result ?: "*"
        return PgnGame(tags, moves, finalResult)
    }

    /** Splits text into individual game chunks (tag section + movetext). */
    fun splitGames(text: String): List<String> {
        val games = ArrayList<String>()
        val current = StringBuilder()
        var seenMoves = false
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("[") && !line.startsWith("[%") && seenMoves) {
                if (current.isNotBlank()) games += current.toString()
                current.setLength(0)
                seenMoves = false
            }
            if (line.isNotEmpty() && !line.startsWith("[")) seenMoves = true
            current.append(raw).append('\n')
        }
        if (current.isNotBlank()) games += current.toString()
        return games
    }

    private fun parseMovetext(text: String): Pair<List<PgnMove>, String?> {
        val moves = ArrayList<PgnMove>()
        var result: String? = null
        var i = 0
        val n = text.length
        var variationDepth = 0
        while (i < n) {
            val c = text[i]
            when {
                c.isWhitespace() -> i++
                c == '{' -> {
                    val end = text.indexOf('}', i + 1).let { if (it < 0) n else it }
                    if (variationDepth == 0) {
                        val comment = text.substring(i + 1, end)
                        CLOCK.find(comment)?.let { m ->
                            val h = m.groupValues[1].toLong()
                            val min = m.groupValues[2].toLong()
                            val sec = m.groupValues[3].toDouble()
                            val ms = (h * 3600 + min * 60) * 1000 + (sec * 1000).toLong()
                            if (moves.isNotEmpty()) moves[moves.lastIndex] = moves.last().copy(clockMillis = ms)
                        }
                    }
                    i = end + 1
                }
                c == ';' -> {
                    val end = text.indexOf('\n', i).let { if (it < 0) n else it }
                    i = end + 1
                }
                c == '(' -> { variationDepth++; i++ }
                c == ')' -> { if (variationDepth > 0) variationDepth--; i++ }
                c == '$' -> {
                    i++
                    while (i < n && text[i].isDigit()) i++
                }
                else -> {
                    var j = i
                    while (j < n && !text[j].isWhitespace() && text[j] !in "{}();") j++
                    var token = text.substring(i, j)
                    i = j
                    if (variationDepth > 0) continue
                    if (token in RESULTS) { result = token; continue }
                    if (MOVE_NUMBER.matches(token)) continue
                    token = token.replace(MOVE_NUMBER_PREFIX, "")
                    if (token.isEmpty()) continue
                    // Strip inline annotation glyphs such as "e4!?".
                    token = token.trimEnd('!', '?')
                    if (token.isEmpty()) continue
                    moves += PgnMove(token)
                }
            }
        }
        return moves to result
    }
}
