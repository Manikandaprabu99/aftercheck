package app.aftercheck.data

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Reads Chess.com's documented public archive endpoints. This client deliberately
 * does not implement private-account login, cookie scraping, or undocumented endpoints.
 */
class ChessComArchiveClient {
    fun normalizeUsername(input: String): String {
        val username = input.trim().lowercase(Locale.ROOT)
        require(username.matches(Regex("[a-z0-9_-]{1,30}"))) {
            "Enter a valid Chess.com username."
        }
        return username
    }

    fun fetchArchiveUrls(usernameInput: String): List<String> {
        val username = normalizeUsername(usernameInput)
        val body = request("https://api.chess.com/pub/player/$username/games/archives", "application/json")
        val array = JSONObject(body).getJSONArray("archives")
        return (0 until array.length())
            .map { array.getString(it) }
            .filter { it.startsWith("https://api.chess.com/pub/player/") }
    }

    fun fetchArchivePgn(archiveUrl: String): String {
        require(archiveUrl.startsWith("https://api.chess.com/pub/player/")) {
            "Unsupported archive URL."
        }
        return request(archiveUrl.trimEnd('/') + "/pgn", "application/x-chess-pgn")
    }

    private fun request(url: String, accept: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 45_000
            setRequestProperty("Accept", accept)
            setRequestProperty("User-Agent", "AfterCheck-Android/0.1.0")
        }
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val message = "Chess.com archive request failed (HTTP $code)."
                when {
                    code == HttpURLConnection.HTTP_CLIENT_TIMEOUT ||
                        code == 429 ||
                        code >= 500 -> throw IOException(message)
                    code == HttpURLConnection.HTTP_UNAUTHORIZED ||
                        code == HttpURLConnection.HTTP_FORBIDDEN ->
                        throw IllegalStateException("$message Only public archive data is supported.")
                    else -> throw IllegalArgumentException(message)
                }
            }
            return body
        } finally {
            connection.disconnect()
        }
    }
}

internal fun parsePgnArchive(
    username: String,
    archiveUrl: String,
    pgnText: String,
    importedAt: Long = System.currentTimeMillis()
): List<ChessGameEntity> {
    val blocks = pgnText.trim().split(Regex("(?m)(?=^\\[Event\\s+)"))
        .filter { it.trimStart().startsWith("[Event ") }

    fun header(block: String, key: String): String =
        Regex("(?m)^\\[$key\\s+\"([^\"]*)\"\\]$")
            .find(block)?.groupValues?.getOrNull(1).orEmpty()

    return blocks.mapIndexedNotNull { index, block ->
        val link = header(block, "Link")
        val date = header(block, "EndDate").ifBlank { header(block, "Date") }
        val parsedDate = Regex("^(\\d{4})[.](\\d{2})[.](\\d{2})").find(date)
        val epoch = parsedDate?.let {
            runCatching {
                java.time.LocalDate.of(
                    it.groupValues[1].toInt(),
                    it.groupValues[2].toInt(),
                    it.groupValues[3].toInt()
                ).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
            }.getOrNull()
        } ?: 0L
        val result = header(block, "Result").ifBlank { "*" }
        val gameUrl = link.ifBlank { "$archiveUrl#game-$index" }
        if (header(block, "White").isBlank() && header(block, "Black").isBlank()) {
            null
        } else {
            ChessGameEntity(
                url = gameUrl,
                username = username,
                white = header(block, "White").ifBlank { "White" },
                black = header(block, "Black").ifBlank { "Black" },
                result = result,
                endTimeEpochMs = epoch,
                timeControl = header(block, "TimeControl").ifBlank { "Unknown" },
                rated = header(block, "Rated").equals("true", ignoreCase = true),
                opening = header(block, "Opening"),
                eco = header(block, "ECO"),
                pgn = block.trim(),
                archiveUrl = archiveUrl,
                importedAtEpochMs = importedAt
            )
        }
    }
}
