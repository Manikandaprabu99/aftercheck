package app.aftercheck.engine

import app.aftercheck.chess.Move
import app.aftercheck.chess.Position
import app.aftercheck.chess.analysis.AlternativeLine
import app.aftercheck.chess.analysis.EngineLine
import app.aftercheck.chess.analysis.GameReview
import app.aftercheck.chess.analysis.MoveClass
import app.aftercheck.chess.analysis.PositionEval
import app.aftercheck.chess.analysis.Recommendation
import app.aftercheck.chess.analysis.Score
import app.aftercheck.chess.analysis.SideSummary
import app.aftercheck.chess.analysis.Theme
import app.aftercheck.chess.analysis.encode
import app.aftercheck.chess.Side
import app.aftercheck.data.db.MoveEntity
import app.aftercheck.data.db.ReviewEntity

/** Compact text encodings for storing engine output and reviews in Room. */
object ReviewCodec {

    // -------------------------------------------------------------- eval cache

    fun encodeEval(eval: PositionEval): String = buildString {
        append(eval.depth)
        for (line in eval.lines) {
            append('\n').append(line.score.encode()).append('|').append(line.depth).append('|')
            append(line.pv.joinToString(" ") { it.uci })
        }
    }

    /** Decodes a cached evaluation, keeping only the legal prefix of each line. */
    fun decodeEval(text: String, position: Position): PositionEval? {
        val rows = text.split('\n')
        val depth = rows.firstOrNull()?.toIntOrNull() ?: return null
        val lines = rows.drop(1).mapNotNull { row ->
            val parts = row.split('|')
            if (parts.size != 3) return@mapNotNull null
            val score = Score.parse(parts[0]) ?: return@mapNotNull null
            var pos = position
            val pv = ArrayList<Move>()
            for (u in parts[2].split(' ').filter { it.isNotEmpty() }) {
                val m = Move.fromUci(u)?.takeIf { pos.isLegal(it) } ?: break
                pv += m
                pos = pos.play(m)
            }
            if (pv.isEmpty()) null else EngineLine(score, pv, parts[1].toIntOrNull() ?: depth)
        }
        if (lines.isEmpty()) return null
        return PositionEval(lines.first().score, lines, depth)
    }

    // ------------------------------------------------------------------ review

    fun moveEntities(gameId: String, review: GameReview, userSide: Side?): List<MoveEntity> = review.moves.map { m ->
        MoveEntity(
            gameId = gameId,
            ply = m.ply,
            san = m.san,
            uci = m.move.uci,
            isUser = userSide == m.side,
            classification = m.classification.name,
            scoreBefore = m.scoreBefore.encode(),
            scoreAfter = m.scoreAfter.encode(),
            pointsLost = m.pointsLost,
            accuracy = m.accuracy,
            bestUci = m.bestMove?.uci,
            bestSan = m.bestSan,
            bestLine = m.bestLine.joinToString(" "),
            replyLine = m.replyLine.joinToString(" "),
            alternatives = m.alternatives.joinToString(";") { "${it.score.encode()}|${it.san.joinToString(" ")}" },
            explanation = m.explanation,
            themes = m.themes.joinToString(",") { it.name },
            clockMillis = m.clockMillis,
            timeSpentMillis = m.timeSpentMillis,
        )
    }

    fun reviewEntity(
        gameId: String, review: GameReview, engineName: String, analysisKey: String, depth: Int, durationMillis: Long, now: Long,
    ) = ReviewEntity(
        gameId = gameId,
        whiteAccuracy = review.white.accuracy,
        blackAccuracy = review.black.accuracy,
        whiteCounts = encodeCounts(review.white),
        blackCounts = encodeCounts(review.black),
        eco = review.opening?.eco,
        openingName = review.opening?.name,
        bookPlies = review.bookPlies,
        winChances = review.winChances.joinToString(",") { (it * 1000).toInt().toString() },
        finalScore = review.finalScore.encode(),
        biggestSwingPly = review.biggestSwingPly,
        strongestMovePly = review.strongestMovePly,
        turningPoints = review.turningPoints.joinToString(","),
        recommendations = review.recommendations.joinToString("\n") { "${it.theme.name}|${it.count}|${it.text.replace('\n', ' ')}" },
        engineName = engineName,
        analysisKey = analysisKey,
        depth = depth,
        durationMillis = durationMillis,
        createdAt = now,
    )

    private fun encodeCounts(s: SideSummary) = s.counts.entries.joinToString(",") { "${it.key.name}:${it.value}" }

    fun decodeCounts(text: String): Map<MoveClass, Int> = text.split(',').mapNotNull { pair ->
        val (name, n) = pair.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
        (MoveClass.fromName(name) ?: return@mapNotNull null) to (n.toIntOrNull() ?: return@mapNotNull null)
    }.toMap()

    fun decodeWinChances(text: String): List<Float> = text.split(',').mapNotNull { it.toIntOrNull()?.div(1000f) }

    fun decodeRecommendations(text: String): List<Recommendation> = text.lines().mapNotNull { row ->
        val parts = row.split('|', limit = 3)
        if (parts.size != 3) return@mapNotNull null
        val theme = Theme.entries.firstOrNull { it.name == parts[0] } ?: return@mapNotNull null
        Recommendation(theme, parts[1].toIntOrNull() ?: 0, parts[2])
    }

    fun decodeAlternatives(text: String): List<AlternativeLine> = text.split(';').mapNotNull { row ->
        val parts = row.split('|', limit = 2)
        if (parts.size != 2) return@mapNotNull null
        val score = Score.parse(parts[0]) ?: return@mapNotNull null
        AlternativeLine(parts[1].split(' ').filter { it.isNotEmpty() }, score)
    }

    fun decodeInts(text: String): List<Int> = text.split(',').mapNotNull { it.trim().toIntOrNull() }
}
