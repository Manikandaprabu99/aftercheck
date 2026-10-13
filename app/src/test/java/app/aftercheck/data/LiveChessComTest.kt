package app.aftercheck.data

import app.aftercheck.data.chesscom.ChessComApi
import app.aftercheck.data.chesscom.Fetch
import app.aftercheck.data.chesscom.UrlConnectionHttpClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Talks to the real Chess.com Published-Data API. Runs only when LIVE_CHESSCOM=1 (CI), so local
 * builds never depend on the network.
 */
class LiveChessComTest {
    private val candidates = listOfNotNull(System.getenv("LIVE_CHESSCOM_USER"), "hikaru", "magnuscarlsen", "danielnaroditsky", "erik")
    private val http = UrlConnectionHttpClient("AfterCheck-CI (+https://github.com/Manikandaprabu99/aftercheck)")

    /** Raw status codes, so a failure says exactly what the API answered. */
    private fun diagnose(): String = runBlocking {
        candidates.joinToString("; ") { u ->
            listOf("/player/$u", "/player/$u/games/archives").joinToString(", ") { path ->
                val r = runCatching { http.get("${ChessComApi.DEFAULT_BASE_URL}$path", emptyMap()) }
                "$path → ${r.getOrNull()?.code ?: r.exceptionOrNull()}"
            }
        }
    }

    @Test fun profileArchivesAndConditionalRequests() = runBlocking {
        assumeTrue(System.getenv("LIVE_CHESSCOM") == "1")
        val api = ChessComApi(http)
        val user = candidates.firstOrNull { runCatching { api.profile(it) }.isSuccess }
        assertTrue("No candidate profile was found. ${diagnose()}", user != null)
        assertEquals(user, api.profile(user!!).username.lowercase())
        val archives = api.archives(user, null) as Fetch.Fresh
        assertTrue("archives for $user", archives.value.isNotEmpty())
        val month = archives.value.last()
        val latest = api.monthGames(month, null, null) as Fetch.Fresh
        assertTrue("parsed games from $month", latest.value.isNotEmpty())
        assertTrue(latest.value.all { it.pgn.contains("[White ") })
        // The API supports ETags: an unchanged archive answers 304 Not Modified.
        if (latest.etag != null) assertEquals(Fetch.NotModified, api.monthGames(month, latest.etag, null))
    }
}
