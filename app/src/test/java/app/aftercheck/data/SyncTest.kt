package app.aftercheck.data

import app.aftercheck.data.chesscom.ArchiveParser
import app.aftercheck.data.chesscom.ArchiveUnavailableException
import app.aftercheck.data.chesscom.ChessComApi
import app.aftercheck.data.chesscom.Fetch
import app.aftercheck.data.chesscom.HttpClient
import app.aftercheck.data.chesscom.HttpResponse
import app.aftercheck.data.chesscom.NotFoundException
import app.aftercheck.data.chesscom.TransientApiException
import app.aftercheck.data.db.ArchiveEntity
import app.aftercheck.data.db.GameEntity
import app.aftercheck.data.db.GameStatus
import app.aftercheck.data.db.Priority
import app.aftercheck.data.sync.GameMapper
import app.aftercheck.data.sync.SyncEngine
import app.aftercheck.data.sync.SyncStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** Builds Chess.com-shaped archive JSON. */
object ArchiveFixtures {
    const val USER = "TestUser"

    fun pgn(white: String, black: String, result: String, moves: String) = """
        [Event "Live Chess"]
        [Site "Chess.com"]
        [White "$white"]
        [Black "$black"]
        [Result "$result"]
        [ECO "C50"]
        [ECOUrl "https://www.chess.com/openings/Italian-Game-Giuoco-Piano-Game-4.c3"]
        [TimeControl "180+2"]

        $moves $result
    """.trimIndent()

    fun game(uuid: String, endTime: Long, white: String = USER, black: String = "opp", whiteResult: String = "win", blackResult: String = "resigned",
             rules: String = "chess", moves: String = "1. e4 {[%clk 0:03:00]} 1... e5 {[%clk 0:02:59]} 2. Bc4 Nc6 3. Qh5 Nf6 4. Qxf7#") =
        """{"url":"https://www.chess.com/game/live/$uuid","pgn":${org.json.JSONObject.quote(pgn(white, black, if (whiteResult == "win") "1-0" else if (blackResult == "win") "0-1" else "1/2-1/2", moves))},
            "time_control":"180+2","end_time":$endTime,"rated":true,"uuid":"$uuid","time_class":"blitz","rules":"$rules",
            "white":{"rating":1200,"result":"$whiteResult","username":"$white"},
            "black":{"rating":1150,"result":"$blackResult","username":"$black"},
            "eco":"https://www.chess.com/openings/Italian-Game"}"""

    fun month(vararg games: String) = """{"games":[${games.joinToString(",")}]}"""
}

/** Serves canned responses by URL and records requests. */
class FakeHttp(private val routes: MutableMap<String, MutableList<HttpResponse>>) : HttpClient {
    val requests = mutableListOf<Pair<String, Map<String, String>>>()
    var failNext: IOException? = null

    override suspend fun get(url: String, headers: Map<String, String>): HttpResponse {
        requests += url to headers
        failNext?.let { failNext = null; throw it }
        val queue = routes[url] ?: return HttpResponse(404, null, emptyMap())
        val r = if (queue.size > 1) queue.removeAt(0) else queue.first()
        val inm = headers["If-None-Match"]
        if (inm != null && inm == r.header("etag")) return HttpResponse(304, null, r.headers)
        return r
    }

    companion object {
        fun ok(body: String, etag: String? = null) = HttpResponse(200, body, etag?.let { mapOf("etag" to it) } ?: emptyMap())
    }
}

class MemoryStore : SyncStore {
    val archives = linkedMapOf<String, ArchiveEntity>()
    val games = linkedMapOf<String, GameEntity>()
    var failInsertAfter: Int = Int.MAX_VALUE

    override suspend fun archives(username: String) =
        archives.values.filter { it.username == username }.sortedWith(compareByDescending<ArchiveEntity> { it.year }.thenByDescending { it.month })
    override suspend fun insertArchives(archives: List<ArchiveEntity>) { archives.forEach { this.archives.putIfAbsent(it.url, it) } }
    override suspend fun updateArchive(archive: ArchiveEntity) { archives[archive.url] = archive }
    override suspend fun existingIds(ids: List<String>) = ids.filter { it in games }.toSet()
    override suspend fun insertGames(games: List<GameEntity>): Int {
        var n = 0
        for (g in games) {
            if (this.games.size >= failInsertAfter) throw IOException("disk full")
            if (this.games.putIfAbsent(g.id, g) == null) n++
        }
        return n
    }
}

class ChessComApiTest {
    private val base = "https://api.test/pub"

    @Test fun normalisesUsernames() {
        assertEquals("magnus", ChessComApi.normalizeUsername("  Magnus "))
        assertEquals("some_user-1", ChessComApi.normalizeUsername("https://www.chess.com/member/Some_User-1"))
        assertNull(ChessComApi.normalizeUsername("no spaces allowed"))
        assertNull(ChessComApi.normalizeUsername(""))
    }

    @Test fun retriesRateLimitsAndServerErrors() = runTest {
        val url = "$base/player/testuser/games/archives"
        val http = FakeHttp(mutableMapOf(url to mutableListOf(
            HttpResponse(429, null, mapOf("retry-after" to "2")),
            HttpResponse(503, null, emptyMap()),
            FakeHttp.ok("""{"archives":["$base/player/testuser/games/2024/05"]}""", "\"a1\""),
        )))
        val sleeps = mutableListOf<Long>()
        val api = ChessComApi(http, base, sleep = { sleeps += it })
        val result = api.archives("TestUser", null) as Fetch.Fresh
        assertEquals(listOf("$base/player/testuser/games/2024/05"), result.value)
        assertEquals("\"a1\"", result.etag)
        assertEquals(listOf(2_000L, 2_000L), sleeps) // Retry-After honoured, then exponential backoff
    }

    @Test fun givesUpWithTransientErrorAfterRepeatedFailures() = runTest {
        val url = "$base/player/testuser"
        val http = FakeHttp(mutableMapOf(url to mutableListOf(HttpResponse(502, null, emptyMap()))))
        val api = ChessComApi(http, base, sleep = {}, maxAttempts = 3)
        val e = runCatching { api.profile("testuser") }.exceptionOrNull()
        assertTrue(e is TransientApiException)
        assertEquals(3, http.requests.size)
    }

    @Test fun networkErrorsAreRetried() = runTest {
        val url = "$base/player/testuser"
        val http = FakeHttp(mutableMapOf(url to mutableListOf(FakeHttp.ok("""{"username":"TestUser","name":"Test","joined":1600000000}"""))))
        http.failNext = IOException("timeout")
        val profile = ChessComApi(http, base, sleep = {}).profile("TestUser")
        assertEquals("TestUser", profile.username)
        assertEquals(2, http.requests.size)
    }

    @Test fun chessComInternalErrorIsNotTreatedAsMissing() = runTest {
        // Observed live: Chess.com answers 404 with its own error document when it can't build an archive.
        val url = "$base/player/testuser/games/2026/10"
        val body = """{"code":0,"message":"An internal error has occurred. Please contact Chess.com Developer's Forum for further help"}"""
        val http = FakeHttp(mutableMapOf(url to mutableListOf(HttpResponse(404, body, emptyMap()))))
        val e = runCatching { ChessComApi(http, base, sleep = {}).monthGames(url, null, null) }.exceptionOrNull()
        assertTrue(e is ArchiveUnavailableException)
        assertEquals(1, http.requests.size) // persistent server-side error: no pointless retries
        assertFalse(ChessComApi.isInternalError(null))
        assertFalse(ChessComApi.isInternalError("""{"message":"User not found"}"""))
    }

    @Test fun unknownPlayerIsNotFound() = runTest {
        val api = ChessComApi(FakeHttp(mutableMapOf()), base, sleep = {})
        assertTrue(runCatching { api.profile("nobody") }.exceptionOrNull() is NotFoundException)
    }

    @Test fun sendsEtagAndHandlesNotModified() = runTest {
        val url = "$base/player/testuser/games/2024/05"
        val http = FakeHttp(mutableMapOf(url to mutableListOf(FakeHttp.ok(ArchiveFixtures.month(), "\"m1\""))))
        val api = ChessComApi(http, base, sleep = {})
        assertTrue(api.monthGames(url, null, null) is Fetch.Fresh)
        assertEquals(Fetch.NotModified, api.monthGames(url, "\"m1\"", null))
        assertEquals("\"m1\"", http.requests.last().second["If-None-Match"])
    }

    @Test fun parserSkipsMalformedEntries() {
        val json = """{"games":[${ArchiveFixtures.game("g1", 1_700_000_000)},{"url":"x"},{"pgn":"","uuid":"g3"}]}"""
        val games = ArchiveParser.parseGames(json)
        assertEquals(1, games.size)
        assertEquals("g1", games[0].uuid)
        assertEquals(1200, games[0].white.rating)
        assertEquals("blitz", games[0].timeClass)
    }
}

class GameMapperTest {
    private val now = 1_800_000_000_000L

    private fun map(json: String, user: String = ArchiveFixtures.USER) =
        GameMapper.toEntity(ArchiveParser.parseGames(ArchiveFixtures.month(json)).single(), user, null, now)

    @Test fun mapsResultsFromTheUsersPerspective() {
        val win = map(ArchiveFixtures.game("a", now / 1000))!!
        assertEquals("w", win.userColor)
        assertEquals("win", win.userResult)
        assertEquals("1-0", win.result)
        assertEquals("resignation", win.termination)
        assertEquals("opp", win.opponent)
        assertEquals(7, win.plies)
        assertEquals(Priority.NEW, win.priority)
        assertEquals(GameStatus.QUEUED, win.status)

        val loss = map(ArchiveFixtures.game("b", now / 1000, white = "opp", black = ArchiveFixtures.USER.lowercase()))!!
        assertEquals("b", loss.userColor)
        assertEquals("loss", loss.userResult)

        val draw = map(ArchiveFixtures.game("c", now / 1000, whiteResult = "agreed", blackResult = "agreed"))!!
        assertEquals("draw", draw.userResult)
        assertEquals("1/2-1/2", draw.result)
        assertEquals("agreement", draw.termination)
    }

    @Test fun skipsVariantsAndOldGamesGoToBacklog() {
        assertNull(map(ArchiveFixtures.game("v", now / 1000, rules = "chess960")))
        val old = map(ArchiveFixtures.game("o", (now - 90L * 86_400_000) / 1000))!!
        assertEquals(Priority.BACKLOG, old.priority)
    }

    @Test fun gamesWithoutMovesAreSkipped() {
        val empty = map(ArchiveFixtures.game("e", now / 1000, moves = ""))!!
        assertEquals(GameStatus.SKIPPED, empty.status)
        assertEquals(0, empty.plies)
    }

    @Test fun openingNameFromChessComUrl() {
        assertEquals("Sicilian Defense Old Sicilian", GameMapper.openingFromUrl("https://www.chess.com/openings/Sicilian-Defense-Old-Sicilian-3.Nf3"))
        assertEquals("Italian Game", map(ArchiveFixtures.game("i", now / 1000))!!.openingName)
    }
}

class SyncEngineTest {
    private val base = "https://api.test/pub"
    private val user = "testuser"
    private val archivesUrl = "$base/player/$user/games/archives"
    private fun monthUrl(y: Int, m: Int) = "$base/player/$user/games/$y/${"%02d".format(m)}"

    // "Now" is 2024-07-10: June 2024 and earlier are closed months, July is the current one.
    private val now = SyncEngine.monthEnd(2024, 6) + 9L * 86_400_000

    private fun setup(months: Map<Pair<Int, Int>, String>): Pair<FakeHttp, MemoryStore> {
        val routes = mutableMapOf<String, MutableList<HttpResponse>>()
        routes[archivesUrl] = mutableListOf(FakeHttp.ok("""{"archives":[${months.keys.sortedWith(compareBy({ it.first }, { it.second })).joinToString(",") { "\"${monthUrl(it.first, it.second)}\"" }}]}""", "\"idx\""))
        months.forEach { (k, body) -> routes[monthUrl(k.first, k.second)] = mutableListOf(FakeHttp.ok(body, "\"${k.first}-${k.second}\"")) }
        return FakeHttp(routes) to MemoryStore()
    }

    private fun engine(http: FakeHttp, store: MemoryStore) =
        SyncEngine(ChessComApi(http, base, sleep = {}), store, book = { null }, clock = { now }, pause = {})

    private fun t(y: Int, m: Int, d: Int) = (SyncEngine.monthEnd(y, m - 1) + (d - 1) * 86_400_000L) / 1000

    @Test fun importsNewestFirstInBatchesAndResumes() = runTest {
        val months = (1..7).associate { m -> (2024 to m) to ArchiveFixtures.month(ArchiveFixtures.game("g$m-a", t(2024, m, 3)), ArchiveFixtures.game("g$m-b", t(2024, m, 5))) }
        val (http, store) = setup(months)
        val sync = engine(http, store)

        val first = sync.sync(user, null, maxArchives = 3)
        assertEquals(6, first.newGames)
        assertEquals(4, first.archivesRemaining)
        assertFalse(first.historyComplete)
        // Newest months were fetched first.
        assertEquals(listOf(monthUrl(2024, 7), monthUrl(2024, 6), monthUrl(2024, 5)), http.requests.drop(1).map { it.first })

        val second = sync.sync(user, first.archivesEtag, maxArchives = 3)
        val third = sync.sync(user, second.archivesEtag, maxArchives = 3)
        assertTrue(third.historyComplete)
        assertEquals(14, store.games.size)
        // Closed months are complete; the current month stays open for re-checks.
        assertTrue(store.archives[monthUrl(2024, 6)]!!.complete)
        assertFalse(store.archives[monthUrl(2024, 7)]!!.complete)
    }

    @Test fun completedMonthsAreNeverDownloadedAgainAndCurrentMonthUsesEtag() = runTest {
        val months = mapOf((2024 to 6) to ArchiveFixtures.month(ArchiveFixtures.game("old", t(2024, 6, 2))), (2024 to 7) to ArchiveFixtures.month(ArchiveFixtures.game("new", t(2024, 7, 2))))
        val (http, store) = setup(months)
        val sync = engine(http, store)
        val first = sync.sync(user, null, 12)
        http.requests.clear()
        val second = sync.sync(user, first.archivesEtag, 12)
        assertEquals(0, second.newGames)
        val urls = http.requests.map { it.first }
        assertEquals(listOf(archivesUrl, monthUrl(2024, 7)), urls)
        assertEquals("\"2024-7\"", http.requests.last().second["If-None-Match"])
        assertEquals("\"idx\"", http.requests.first().second["If-None-Match"])
    }

    @Test fun newGamesInTheCurrentMonthAreDetected() = runTest {
        val (http, store) = setup(mapOf((2024 to 7) to ArchiveFixtures.month(ArchiveFixtures.game("a", t(2024, 7, 2)))))
        val sync = engine(http, store)
        sync.sync(user, null, 12)
        // Chess.com publishes another finished game later.
        val updated = FakeHttp.ok(ArchiveFixtures.month(ArchiveFixtures.game("a", t(2024, 7, 2)), ArchiveFixtures.game("b", t(2024, 7, 9))), "\"v2\"")
        val routes = mutableMapOf(
            archivesUrl to mutableListOf(FakeHttp.ok("""{"archives":["${monthUrl(2024, 7)}"]}""", "\"idx\"")),
            monthUrl(2024, 7) to mutableListOf(updated),
        )
        val result = engine(FakeHttp(routes), store).sync(user, null, 12)
        assertEquals(1, result.newGames)
        assertEquals(setOf("a", "b"), store.games.keys)
    }

    @Test fun interruptedRunDoesNotDuplicateOnRetry() = runTest {
        val months = mapOf((2024 to 7) to ArchiveFixtures.month(ArchiveFixtures.game("a", t(2024, 7, 1)), ArchiveFixtures.game("b", t(2024, 7, 2)), ArchiveFixtures.game("c", t(2024, 7, 3))))
        val (http, store) = setup(months)
        store.failInsertAfter = 1
        assertTrue(runCatching { engine(http, store).sync(user, null, 12) }.isFailure)
        store.failInsertAfter = Int.MAX_VALUE
        val retry = engine(http, store).sync(user, null, 12)
        assertEquals(3, store.games.size)
        assertEquals(2, retry.newGames)
    }

    @Test fun anUnavailableMonthIsRetriedLaterWithoutBlockingOthers() = runTest {
        val months = mapOf(
            (2024 to 5) to ArchiveFixtures.month(ArchiveFixtures.game("may", t(2024, 5, 2))),
            (2024 to 6) to """{"code":0,"message":"An internal error has occurred."}""",
            (2024 to 7) to ArchiveFixtures.month(ArchiveFixtures.game("jul", t(2024, 7, 2))),
        )
        val (http, store) = setup(months)
        // Serve June as Chess.com's internal-error 404.
        val juneUrl = monthUrl(2024, 6)
        val routes = mutableMapOf<String, MutableList<HttpResponse>>()
        http.requests.clear()
        val failing = FakeHttp(routes.apply {
            put(archivesUrl, mutableListOf(FakeHttp.ok("""{"archives":["${monthUrl(2024, 5)}","$juneUrl","${monthUrl(2024, 7)}"]}""", "\"idx\"")))
            put(monthUrl(2024, 5), mutableListOf(FakeHttp.ok(months.getValue(2024 to 5))))
            put(juneUrl, mutableListOf(HttpResponse(404, months.getValue(2024 to 6), emptyMap())))
            put(monthUrl(2024, 7), mutableListOf(FakeHttp.ok(months.getValue(2024 to 7))))
        })
        val result = engine(failing, store).sync(user, null, 12)
        assertEquals(2, result.newGames)
        assertEquals(listOf(juneUrl), result.unavailable.map { it.url })
        assertTrue(result.historyComplete)
        // June is not marked complete, so the next sync asks for it again.
        assertFalse(store.archives[juneUrl]!!.complete)
        assertTrue(store.archives[monthUrl(2024, 5)]!!.complete)
    }

    @Test fun variantsAreCountedAndSkipped() = runTest {
        val (http, store) = setup(mapOf((2024 to 7) to ArchiveFixtures.month(ArchiveFixtures.game("a", t(2024, 7, 1)), ArchiveFixtures.game("x", t(2024, 7, 1), rules = "chess960"))))
        val result = engine(http, store).sync(user, null, 12)
        assertEquals(1, result.newGames)
        assertEquals(1, result.skippedVariants)
    }

    @Test fun parsesArchiveUrls() {
        val a = SyncEngine.parseArchiveUrl("https://api.chess.com/pub/player/x/games/2023/09", "x")!!
        assertEquals(2023, a.year)
        assertEquals(9, a.month)
        assertNull(SyncEngine.parseArchiveUrl("https://api.chess.com/pub/player/x/games/archives", "x"))
    }
}
