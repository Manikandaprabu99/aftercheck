package app.aftercheck.data.chesscom

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class HttpResponse(val code: Int, val body: String?, val headers: Map<String, String>) {
    fun header(name: String): String? = headers[name.lowercase()]
}

fun interface HttpClient {
    suspend fun get(url: String, headers: Map<String, String>): HttpResponse
}

class UrlConnectionHttpClient(private val userAgent: String) : HttpClient {
    override suspend fun get(url: String, headers: Map<String, String>): HttpResponse = withContext(Dispatchers.IO) {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 45_000
            conn.useCaches = false
            conn.setRequestProperty("User-Agent", userAgent)
            conn.setRequestProperty("Accept", "application/json")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }
            val responseHeaders = conn.headerFields.orEmpty()
                .filterKeys { it != null }
                .mapKeys { it.key.lowercase() }
                .mapValues { it.value.firstOrNull().orEmpty() }
            HttpResponse(code, body, responseHeaders)
        } finally {
            conn.disconnect()
        }
    }
}

open class ApiException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Temporary failure (offline, rate limited, server error): retry later. */
class TransientApiException(message: String, cause: Throwable? = null) : ApiException(message, cause)

class NotFoundException(message: String) : ApiException(message)

sealed interface Fetch<out T> {
    data class Fresh<T>(val value: T, val etag: String?, val lastModified: String?) : Fetch<T>
    data object NotModified : Fetch<Nothing>
}

data class PlayerProfile(val username: String, val name: String?, val joined: Long?, val country: String?)

/**
 * Client for Chess.com's documented, read-only Published-Data API (https://www.chess.com/news/view/published-data-api).
 * No authentication exists or is used: it returns public profile data and public game archives.
 *
 * Requests are serial (the API rate-limits parallel access), use ETag / Last-Modified so unchanged
 * archives cost a 304, and back off on 429 and server errors.
 */
class ChessComApi(
    private val http: HttpClient,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val maxAttempts: Int = 4,
) {
    suspend fun profile(username: String): PlayerProfile {
        val name = normalizeUsername(username) ?: throw NotFoundException("That doesn't look like a Chess.com username")
        val response = request("$baseUrl/player/$name", emptyMap())
        if (response.code == 304) throw ApiException("Unexpected response")
        return ArchiveParser.parseProfile(response.body.orEmpty())
    }

    suspend fun archives(username: String, etag: String?): Fetch<List<String>> {
        val name = normalizeUsername(username) ?: throw NotFoundException("Invalid username")
        val response = request("$baseUrl/player/$name/games/archives", conditional(etag, null))
        if (response.code == 304) return Fetch.NotModified
        return Fetch.Fresh(ArchiveParser.parseArchiveList(response.body.orEmpty()), response.header("etag"), response.header("last-modified"))
    }

    suspend fun monthGames(url: String, etag: String?, lastModified: String?): Fetch<List<RemoteGame>> {
        val response = request(url, conditional(etag, lastModified))
        if (response.code == 304) return Fetch.NotModified
        return Fetch.Fresh(ArchiveParser.parseGames(response.body.orEmpty()), response.header("etag"), response.header("last-modified"))
    }

    private fun conditional(etag: String?, lastModified: String?) = buildMap {
        if (etag != null) put("If-None-Match", etag)
        if (lastModified != null) put("If-Modified-Since", lastModified)
    }

    private suspend fun request(url: String, headers: Map<String, String>): HttpResponse {
        var lastError: Exception? = null
        for (attempt in 1..maxAttempts) {
            val backoff = 1_000L shl (attempt - 1) // 1s, 2s, 4s…
            val response = try {
                http.get(url, headers)
            } catch (e: IOException) {
                lastError = e
                if (attempt < maxAttempts) sleep(backoff)
                continue
            }
            when (response.code) {
                200, 304 -> return response
                404, 410 -> throw NotFoundException("Not found on Chess.com")
                429 -> {
                    lastError = TransientApiException("Chess.com rate limit reached")
                    val retryAfter = response.header("retry-after")?.toLongOrNull()?.coerceIn(1, 120)
                    if (attempt < maxAttempts) sleep(retryAfter?.times(1_000) ?: (backoff * 4))
                }
                in 500..599 -> {
                    lastError = TransientApiException("Chess.com is unavailable (HTTP ${response.code})")
                    if (attempt < maxAttempts) sleep(backoff)
                }
                else -> throw ApiException("Unexpected response from Chess.com (HTTP ${response.code})")
            }
        }
        throw lastError as? TransientApiException
            ?: TransientApiException("Can't reach Chess.com: ${lastError?.message ?: "network error"}", lastError)
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.chess.com/pub"
        private val USERNAME = Regex("^[A-Za-z0-9_-]{2,25}$")

        fun normalizeUsername(input: String): String? {
            val trimmed = input.trim().removePrefix("@")
                .removePrefix("https://www.chess.com/member/").removePrefix("chess.com/member/").trim('/')
            return trimmed.takeIf { USERNAME.matches(it) }?.lowercase()
        }
    }
}

data class RemotePlayer(val username: String, val rating: Int?, val result: String)

data class RemoteGame(
    val uuid: String,
    val url: String?,
    val pgn: String,
    val timeControl: String,
    val endTimeSeconds: Long,
    val rated: Boolean,
    val timeClass: String,
    val rules: String,
    val white: RemotePlayer,
    val black: RemotePlayer,
    val ecoUrl: String?,
)

object ArchiveParser {
    fun parseProfile(json: String): PlayerProfile = try {
        val o = JSONObject(json)
        PlayerProfile(
            username = o.getString("username"),
            name = o.optString("name").takeIf { it.isNotBlank() },
            joined = o.optLong("joined").takeIf { it > 0 },
            country = o.optString("country").substringAfterLast('/').takeIf { it.isNotBlank() },
        )
    } catch (e: JSONException) {
        throw ApiException("Unexpected profile data from Chess.com", e)
    }

    fun parseArchiveList(json: String): List<String> = try {
        val arr = JSONObject(json).optJSONArray("archives")
        (0 until (arr?.length() ?: 0)).map { arr!!.getString(it) }
    } catch (e: JSONException) {
        throw ApiException("Unexpected archive list from Chess.com", e)
    }

    /** Parses a monthly archive; malformed entries are skipped rather than failing the month. */
    fun parseGames(json: String): List<RemoteGame> {
        val arr = try {
            JSONObject(json).optJSONArray("games")
        } catch (e: JSONException) {
            throw ApiException("Unexpected archive data from Chess.com", e)
        } ?: return emptyList()
        val out = ArrayList<RemoteGame>(arr.length())
        for (i in 0 until arr.length()) {
            val g = arr.optJSONObject(i) ?: continue
            val pgn = g.optString("pgn").takeIf { it.isNotBlank() } ?: continue
            val url = g.optString("url").takeIf { it.isNotBlank() }
            val uuid = g.optString("uuid").takeIf { it.isNotBlank() } ?: url ?: continue
            val white = player(g.optJSONObject("white")) ?: continue
            val black = player(g.optJSONObject("black")) ?: continue
            out += RemoteGame(
                uuid = uuid,
                url = url,
                pgn = pgn,
                timeControl = g.optString("time_control"),
                endTimeSeconds = g.optLong("end_time"),
                rated = g.optBoolean("rated", false),
                timeClass = g.optString("time_class").ifBlank { "unknown" },
                rules = g.optString("rules").ifBlank { "chess" },
                white = white,
                black = black,
                ecoUrl = g.optString("eco").takeIf { it.isNotBlank() },
            )
        }
        return out
    }

    private fun player(o: JSONObject?): RemotePlayer? {
        o ?: return null
        val name = o.optString("username").takeIf { it.isNotBlank() } ?: return null
        return RemotePlayer(name, o.optInt("rating").takeIf { o.has("rating") }, o.optString("result"))
    }
}
