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
    private val user = System.getenv("LIVE_CHESSCOM_USER") ?: "hikaru"

    @Test fun profileArchivesAndConditionalRequests() = runBlocking {
        assumeTrue(System.getenv("LIVE_CHESSCOM") == "1")
        val api = ChessComApi(UrlConnectionHttpClient("AfterCheck-CI (+https://github.com/Manikandaprabu99/aftercheck)"))
        assertEquals(user, api.profile(user).username.lowercase())
        val archives = api.archives(user, null) as Fetch.Fresh
        assertTrue(archives.value.isNotEmpty())
        val latest = api.monthGames(archives.value.last(), null, null) as Fetch.Fresh
        assertTrue("parsed games from ${archives.value.last()}", latest.value.isNotEmpty())
        assertTrue(latest.value.all { it.pgn.contains("[White ") })
        // The API supports ETags: an unchanged archive answers 304 Not Modified.
        if (latest.etag != null) assertEquals(Fetch.NotModified, api.monthGames(archives.value.last(), latest.etag, null))
    }
}
