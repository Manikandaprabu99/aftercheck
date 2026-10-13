package app.aftercheck.data.sync

import app.aftercheck.chess.GameLine
import app.aftercheck.chess.OpeningBook
import app.aftercheck.chess.Pgn
import app.aftercheck.chess.PgnException
import app.aftercheck.chess.ReplayResult
import app.aftercheck.data.chesscom.RemoteGame
import app.aftercheck.data.db.GameEntity
import app.aftercheck.data.db.GameStatus
import app.aftercheck.data.db.Priority

object GameMapper {
    private const val DAY = 24 * 60 * 60 * 1000L

    /** Maps a Chess.com archive entry; returns null for variants AfterCheck can't analyse. */
    fun toEntity(remote: RemoteGame, username: String, book: OpeningBook?, now: Long): GameEntity? {
        if (remote.rules != "chess") return null
        val userColor = when {
            remote.white.username.equals(username, ignoreCase = true) -> "w"
            remote.black.username.equals(username, ignoreCase = true) -> "b"
            else -> null
        }
        val result = when {
            remote.white.result == "win" -> "1-0"
            remote.black.result == "win" -> "0-1"
            else -> "1/2-1/2"
        }
        val userResult = when {
            result == "1/2-1/2" -> "draw"
            (result == "1-0") == (userColor != "b") -> "win"
            else -> "loss"
        }
        val loserCode = when (result) {
            "1-0" -> remote.black.result
            "0-1" -> remote.white.result
            else -> remote.white.result
        }
        val endTime = remote.endTimeSeconds * 1000
        val opponent = if (userColor == "b") remote.white else remote.black
        val user = if (userColor == "b") remote.black else remote.white

        var status = GameStatus.QUEUED
        var message: String? = null
        var plies = 0
        var eco: String? = null
        var openingName: String? = null
        try {
            val pgn = Pgn.parse(remote.pgn)
            plies = pgn.moves.size
            eco = pgn.tag("ECO")
            if (book != null && plies > 0) {
                val replay = GameLine.replay(pgn.startPosition(), pgn.moves.take(30).map { it.san })
                if (replay is ReplayResult.Success) book.match(replay.line).opening?.let {
                    eco = it.eco
                    openingName = it.name
                }
            }
            if (openingName == null) openingName = openingFromUrl(remote.ecoUrl ?: pgn.tag("ECOUrl"))
            if (plies == 0) {
                status = GameStatus.SKIPPED
                message = "No moves were played"
            }
        } catch (e: PgnException) {
            status = GameStatus.FAILED
            message = "Invalid PGN: ${e.message}"
        } catch (e: IllegalArgumentException) {
            status = GameStatus.FAILED
            message = "Invalid starting position"
        }

        val age = now - endTime
        val priority = when {
            age <= 7 * DAY -> Priority.NEW
            age <= 30 * DAY -> Priority.RECENT
            else -> Priority.BACKLOG
        }
        return GameEntity(
            id = remote.uuid,
            url = remote.url,
            white = remote.white.username,
            black = remote.black.username,
            whiteRating = remote.white.rating,
            blackRating = remote.black.rating,
            userColor = userColor,
            opponent = opponent.username,
            opponentLower = opponent.username.lowercase(),
            opponentRating = opponent.rating,
            userRating = user.rating,
            result = result,
            userResult = userResult,
            termination = describeTermination(loserCode),
            endTime = endTime,
            timeClass = remote.timeClass,
            timeControl = remote.timeControl,
            rated = remote.rated,
            eco = eco,
            openingName = openingName,
            openingFamily = openingName?.substringBefore(':')?.trim(),
            openingLower = openingName?.lowercase(),
            plies = plies,
            pgn = remote.pgn,
            status = status,
            statusMessage = message,
            priority = priority,
            importedAt = now,
        )
    }

    fun describeTermination(code: String): String? = when (code) {
        "checkmated" -> "checkmate"
        "resigned" -> "resignation"
        "timeout" -> "time"
        "abandoned" -> "abandonment"
        "agreed" -> "agreement"
        "repetition" -> "repetition"
        "stalemate" -> "stalemate"
        "insufficient" -> "insufficient material"
        "50move" -> "50-move rule"
        "timevsinsufficient" -> "time vs insufficient material"
        else -> null
    }

    /** "https://www.chess.com/openings/Sicilian-Defense-Old-Sicilian-3.Nf3" → "Sicilian Defense Old Sicilian". */
    fun openingFromUrl(url: String?): String? {
        val slug = url?.substringAfter("/openings/", "")?.takeIf { it.isNotBlank() } ?: return null
        val words = slug.split('-').takeWhile { w -> !(w.isNotEmpty() && w[0].isDigit()) }
        return words.joinToString(" ").takeIf { it.isNotBlank() }
    }
}
