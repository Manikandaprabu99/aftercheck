package app.aftercheck.chess

import java.io.Reader

data class Opening(val eco: String, val name: String)

data class OpeningMatch(
    val opening: Opening?,
    /** Number of leading plies whose resulting positions all appear in a named opening line. */
    val bookPlies: Int,
)

/**
 * Opening names keyed by position (so transpositions are recognised). Built from the
 * lichess-org/chess-openings TSV (CC0): columns `eco`, `name`, `pgn`.
 */
class OpeningBook private constructor(
    private val named: Map<Long, Opening>,
    private val bookPositions: Set<Long>,
) {
    val size: Int get() = named.size

    fun lookup(position: Position): Opening? = named[position.zobrist]

    fun match(line: GameLine, maxPly: Int = 40): OpeningMatch {
        var opening: Opening? = null
        var bookPlies = 0
        var inBook = true
        val limit = minOf(maxPly, line.plyCount)
        for (ply in 1..limit) {
            val key = line.positions[ply].zobrist
            named[key]?.let { opening = it }
            if (inBook && key in bookPositions) bookPlies = ply else inBook = false
        }
        return OpeningMatch(opening, bookPlies)
    }

    companion object {
        fun parse(reader: Reader): OpeningBook {
            val named = HashMap<Long, Opening>(4096)
            val book = HashSet<Long>(16384)
            reader.buffered().useLines { lines ->
                for (raw in lines) {
                    val cols = raw.split('\t')
                    if (cols.size < 3 || cols[0] == "eco") continue
                    val opening = Opening(cols[0].trim(), cols[1].trim())
                    val sans = try {
                        Pgn.parse(cols[2]).moves.map { it.san }
                    } catch (e: PgnException) {
                        continue
                    }
                    var pos = Position.initial
                    val keys = ArrayList<Long>(sans.size)
                    for (san in sans) {
                        val move = pos.parseSan(san) ?: break
                        pos = pos.play(move)
                        keys += pos.zobrist
                    }
                    if (keys.size != sans.size || keys.isEmpty()) continue
                    book.addAll(keys)
                    // Longer (more specific) names win if two lines reach one position.
                    val key = keys.last()
                    val existing = named[key]
                    if (existing == null || existing.name.length < opening.name.length) named[key] = opening
                }
            }
            return OpeningBook(named, book)
        }

        val EMPTY = OpeningBook(emptyMap(), emptySet())
    }
}
