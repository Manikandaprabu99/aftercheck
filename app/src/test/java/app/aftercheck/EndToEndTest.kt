package app.aftercheck

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollToKeyAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTouchInput
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.testing.WorkManagerTestInitHelper
import app.aftercheck.chess.engine.EngineSettings
import app.aftercheck.chess.engine.UciEngine
import app.aftercheck.data.chesscom.ChessComApi
import app.aftercheck.data.chesscom.HttpClient
import app.aftercheck.data.chesscom.HttpResponse
import app.aftercheck.data.db.AppDatabase
import app.aftercheck.data.db.GameStatus
import app.aftercheck.data.settings.ThemeMode
import app.aftercheck.data.sync.RoomSyncStore
import app.aftercheck.data.sync.SyncEngine
import app.aftercheck.engine.AnalysisOutcome
import app.aftercheck.ui.games.GamesScreen
import app.aftercheck.ui.home.HomeScreen
import app.aftercheck.ui.insights.InsightsScreen
import app.aftercheck.ui.onboarding.OnboardingScreen
import app.aftercheck.ui.practice.DrillScreen
import app.aftercheck.ui.practice.PracticeScreen
import app.aftercheck.ui.queue.QueueScreen
import app.aftercheck.ui.review.ReviewScreen
import app.aftercheck.ui.settings.SettingsScreen
import app.aftercheck.ui.theme.AfterCheckTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Full pipeline on the JVM: Chess.com-format archives → sync → real Stockfish analysis → Room →
 * every screen rendered (screenshots written when AFTERCHECK_SCREENSHOTS is set).
 *
 * Needs AFTERCHECK_MOCK_DATA (archives produced by scripts/testdata), AFTERCHECK_STOCKFISH and AFTERCHECK_NNUE.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xxhdpi")
class EndToEndTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val mock = System.getenv("AFTERCHECK_MOCK_DATA")?.let(::File)
    private val stockfish = System.getenv("AFTERCHECK_STOCKFISH")
    private val network = System.getenv("AFTERCHECK_NNUE")
    private val shots = System.getenv("AFTERCHECK_SCREENSHOTS")?.let(::File)

    /** Serves the archive files the way api.chess.com does, including ETag / 304. */
    private inner class MockChessCom(private val dir: File) : HttpClient {
        override suspend fun get(url: String, headers: Map<String, String>): HttpResponse {
            val path = url.substringAfter("/pub").trimEnd('/').lowercase()
            val user = "aftercheck_tester"
            val body = when {
                path == "/player/$user" -> """{"username":"aftercheck_tester","name":"Test Player","joined":1600000000}"""
                path == "/player/$user/games/archives" -> {
                    val months = org.json.JSONArray(File(dir, "months.json").readText())
                    val urls = (0 until months.length()).joinToString(",") { "\"https://mock/pub/player/$user/games/${months.getString(it).replace('-', '/')}\"" }
                    """{"archives":[$urls]}"""
                }
                path.startsWith("/player/$user/games/") -> File(dir, path.substringAfter("/games/").replace('/', '-') + ".json").takeIf { it.exists() }?.readText()
                else -> null
            } ?: return HttpResponse(404, null, emptyMap())
            val etag = "\"" + MessageDigest.getInstance("SHA-1").digest(body.toByteArray()).take(8).joinToString("") { "%02x".format(it) } + "\""
            if (headers["If-None-Match"] == etag) return HttpResponse(304, null, mapOf("etag" to etag))
            return HttpResponse(200, body, mapOf("etag" to etag))
        }
    }

    @Test fun syncAnalyseAndRenderEveryScreen() {
        assumeTrue("needs mock archives + host Stockfish", mock != null && stockfish != null && network != null)
        val app = ApplicationProvider.getApplicationContext<AfterCheckApp>()
        // Test WorkManager: background work is recorded but never runs real network jobs here.
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        val c = app.container
        val user = "aftercheck_tester"

        // 1. Connect and import the full history through the real sync engine.
        runBlocking {
            c.settings.connect(user, "Test Player")
            val sync = SyncEngine(ChessComApi(MockChessCom(mock!!), "https://mock/pub", sleep = {}), RoomSyncStore(c.db), { c.openingBook.get() }, pause = {})
            val first = sync.sync(user, null, maxArchives = 12)
            c.settings.recordSync(first.newGames, first.historyComplete, first.archivesEtag)
            assertTrue(first.historyComplete)
            assertEquals(1, first.skippedVariants)
            val again = sync.sync(user, first.archivesEtag, maxArchives = 12)
            assertEquals("second sync must not duplicate", 0, again.newGames)
        }
        val total = runBlocking { c.db.games().counts().first().total }
        assertTrue("imported $total games", total >= 20)

        // 2. Analyse the newest games with the real engine.
        val settings = EngineSettings(depth = 12, movetimeMillis = 700, multiPv = 2, threads = 4, hashMb = 64)
        runBlocking {
            val engine = UciEngine({ ProcessBuilder(stockfish).redirectErrorStream(true).start() })
            engine.start(mapOf("EvalFile" to network!!, "Threads" to "4", "Hash" to "64"))
            try {
                repeat(ANALYSED_GAMES) {
                    val next = c.db.games().nextQueued(0) ?: return@repeat
                    assertEquals(AnalysisOutcome.Done, c.analysis.analyze(next, engine, settings))
                }
                // Queue recovery: a game left "analyzing" by a killed process goes back to the queue.
                val victim = c.db.games().nextQueued(0)!!
                c.db.games().setStatus(victim.id, GameStatus.ANALYZING, null, 40)
                assertEquals(1, c.db.games().resetInterrupted())
                assertEquals(GameStatus.QUEUED, c.db.games().get(victim.id)!!.status)
            } finally {
                engine.close()
            }
        }
        val counts = runBlocking { c.db.games().counts().first() }
        assertEquals(ANALYSED_GAMES, counts.ready)

        // 3. Reviews persist: a fresh database connection (as after an app restart) reads them back.
        runBlocking {
            val reopened = Room.databaseBuilder(app, AppDatabase::class.java, AppDatabase.NAME).build()
            val ready = reopened.games().recentReviewed(50).first()
            assertEquals(ANALYSED_GAMES, ready.size)
            val moves = reopened.reviews().moves(ready.first().id)
            assertEquals(ready.first().plies, moves.size)
            assertTrue(moves.all { it.explanation.isNotBlank() })
            assertTrue(ready.all { it.userAccuracy != null })
            reopened.close()
        }
        val practice = runBlocking { c.db.practice().stats(System.currentTimeMillis()).first() }
        assertTrue("practice positions were extracted", practice.total > 0)

        // 4. Render every screen in both themes.
        val reviewGame = runBlocking {
            c.db.games().recentReviewed(50).first().maxByOrNull { (it.userBlunders ?: 0) + (it.userMistakes ?: 0) }!!
        }
        // Queue state: three games picked by the user, another one being analysed.
        val pendingGame = runBlocking {
            val waiting = c.db.games().observeAutoQueue(10).first()
            assertEquals(3, c.queue.pick(waiting.take(3).map { it.id }))
            c.db.games().setStatus(waiting[3].id, GameStatus.ANALYZING, null, 40)
            waiting[1]
        }
        var screen by mutableStateOf("welcome")
        var theme by mutableStateOf(ThemeMode.DARK)
        compose.setContent {
            AfterCheckTheme(theme) {
                // The app's Scaffold normally paints the window background.
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                when (screen) {
                    "welcome" -> OnboardingScreen(c) {}
                    "home" -> HomeScreen(c, {}, {}, {})
                    "games" -> GamesScreen(c) {}
                    "review" -> ReviewScreen(c, reviewGame.id) {}
                    "pending" -> ReviewScreen(c, pendingGame.id) {}
                    "queue" -> QueueScreen(c, {}, {})
                    "insights" -> InsightsScreen(c) {}
                    "practice" -> PracticeScreen(c, { _, _ -> }, {})
                    "drill" -> DrillScreen(c, "", false, {}, {})
                    "settings" -> SettingsScreen(c)
                }
                }
            }
        }
        val expectations = listOf(
            "welcome" to "Connect Chess.com",
            "home" to "Latest game",
            "games" to "accuracy",
            "review" to "Estimated accuracy",
            "pending" to "#2 in your queue",
            "queue" to "Your picks",
            "insights" to "Results",
            "practice" to "Practice",
            "drill" to "Find the best move",
            "settings" to "Sync now",
        )
        for (mode in listOf(ThemeMode.DARK, ThemeMode.LIGHT)) {
            theme = mode
            val prefix = mode.name.lowercase()
            for ((name, text) in expectations) {
                screen = name
                compose.waitUntil(15_000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
                when (name) {
                    "review" -> captureReview(prefix, reviewGame.id)
                    "games" -> captureSelection(prefix, runBlocking { c.db.games().observeAutoQueue(10).first().map { it.id } })
                    else -> capture("$prefix-$name")
                }
            }
        }
    }

    /** Report first, then the moves tab: start, the user's worst move, and its best line paused on its second move. */
    private fun captureReview(prefix: String, gameId: String) {
        capture("$prefix-review-report")
        compose.onNodeWithText("Moves").performClick()
        compose.onNodeWithContentDescription("First move").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Start").fetchSemanticsNodes().isNotEmpty() }
        capture("$prefix-review-start")
        val target = runBlocking {
            ApplicationProvider.getApplicationContext<AfterCheckApp>().container.db.reviews().moves(gameId).filter { it.isUser }.maxByOrNull { it.pointsLost }!!
        }
        repeat(target.ply) { compose.onNodeWithContentDescription("Next move").performClick() }
        compose.waitUntil(15_000) { compose.onAllNodesWithText(" is ", substring = true).fetchSemanticsNodes().isNotEmpty() }
        capture("$prefix-review")

        compose.onNodeWithText("Show best line").performClick()
        if (compose.onAllNodesWithContentDescription("Pause line").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithContentDescription("Pause line").performClick()
        }
        val lineMove = SemanticsMatcher("engine line move") { it.config.getOrNull(SemanticsActions.OnClick)?.label?.startsWith("Show the position after") == true }
        val moves = compose.onAllNodes(lineMove)
        moves[minOf(1, moves.fetchSemanticsNodes().size - 1)].performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Paused", substring = true).fetchSemanticsNodes().isNotEmpty() }
        capture("$prefix-review-line")
        compose.onNodeWithContentDescription("Back to game").performClick()
        compose.onNodeWithText("Report").performClick()
    }

    /** Games tab in selection mode with two waiting games picked. */
    private fun captureSelection(prefix: String, waiting: List<String>) {
        capture("$prefix-games")
        val gameList = hasScrollToKeyAction() and !hasAnyDescendant(hasText("Colour"))
        compose.onNode(gameList).performScrollToKey(waiting[0])
        compose.onAllNodesWithText("Queued", substring = true)[0].performTouchInput { longClick() }
        compose.onAllNodesWithText("Queued", substring = true)[1].performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("2 selected").fetchSemanticsNodes().isNotEmpty() }
        capture("$prefix-games-select")
        compose.onNodeWithContentDescription("Cancel selection").performClick()
        compose.onNode(gameList).performScrollToIndex(0)
    }

    private fun capture(name: String) {
        val dir = shots ?: return
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        dir.mkdirs()
        val view = compose.activity.window.decorView.rootView
        val full = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(full))
        val scaled = Bitmap.createScaledBitmap(full, view.width / 2, view.height / 2, true)
        FileOutputStream(File(dir, "$name.png")).use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    companion object {
        const val ANALYSED_GAMES = 12
    }
}
