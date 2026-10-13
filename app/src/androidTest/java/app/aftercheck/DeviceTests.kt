package app.aftercheck

import android.graphics.Bitmap
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.aftercheck.chess.GameLine
import app.aftercheck.chess.Pgn
import app.aftercheck.chess.ReplayResult
import app.aftercheck.chess.analysis.Score
import app.aftercheck.chess.engine.EngineSettings
import app.aftercheck.chess.engine.UciEngine
import app.aftercheck.chess.engine.UciPositionEvaluator
import app.aftercheck.data.chesscom.ChessComApi
import app.aftercheck.data.chesscom.UrlConnectionHttpClient
import app.aftercheck.data.db.AppDatabase
import app.aftercheck.data.db.GameEntity
import app.aftercheck.data.db.GameStatus
import app.aftercheck.data.db.Priority
import app.aftercheck.data.settings.AnalysisMode
import app.aftercheck.data.sync.RoomSyncStore
import app.aftercheck.data.sync.SyncEngine
import app.aftercheck.engine.AnalysisOutcome
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

private val app get() = ApplicationProvider.getApplicationContext<AfterCheckApp>()
private fun arg(name: String) = InstrumentationRegistry.getArguments().getString(name)

/** Downloads (once) and verifies the real network, exactly as the app does on first run. */
private fun ensureNetwork() = runBlocking {
    if (!app.container.engineFiles.isNetworkReady()) app.container.engineFiles.downloadNetwork { _, _ -> }
    assertTrue(app.container.engineFiles.isNetworkReady())
}

private val OPERA = """
    [White "Paul Morphy"]
    [Black "Duke Karl"]
    [Result "1-0"]
    [TimeControl "180"]

    1. e4 e5 2. Nf3 d6 3. d4 Bg4 4. dxe5 Bxf3 5. Qxf3 dxe5 6. Bc4 Nf6 7. Qb3 Qe7 8. Nc3 c6 9. Bg5 b5
    10. Nxb5 cxb5 11. Bxb5+ Nbd7 12. O-O-O Rd8 13. Rxd7 Rxd7 14. Rd1 Qe6 15. Bxd7+ Nxd7 16. Qb8+ Nxb8 17. Rd8# 1-0
""".trimIndent()

/** The packaged Stockfish executable runs on Android and produces correct results. */
@RunWith(AndroidJUnit4::class)
class DeviceEngineTest {
    @Before fun network() = ensureNetwork()

    @Test fun packagedEngineFindsMate() = runBlocking {
        val files = app.container.engineFiles
        assertTrue("engine binary ${files.binary()}", files.binary().exists())
        val engine = UciEngine(files.launcher())
        engine.start(mapOf("EvalFile" to files.networkFile.absolutePath, "Threads" to "2", "Hash" to "16"))
        try {
            val line = (GameLine.fromPgn(Pgn.parse(OPERA)) as ReplayResult.Success).line
            // Before 16. Qb8+ White has mate in 2.
            val eval = UciPositionEvaluator(engine, EngineSettings(depth = 14, movetimeMillis = 3_000, multiPv = 1)).evaluate(line, 30)
            assertEquals(Score.Mate(2), eval.score)
            assertTrue(engine.engineName!!.startsWith("Stockfish"))
        } finally {
            engine.close()
        }
    }

    @Test fun analysesAndPersistsAGame() = runBlocking {
        val c = app.container
        val id = "device-test-opera"
        c.db.games().delete(id)
        c.db.games().insertAll(
            listOf(
                GameEntity(
                    id = id, url = null, white = "Paul Morphy", black = "Duke Karl", whiteRating = null, blackRating = null,
                    userColor = "b", opponent = "Paul Morphy", opponentLower = "paul morphy", opponentRating = null, userRating = null,
                    result = "1-0", userResult = "loss", termination = "checkmate", endTime = 0, timeClass = "blitz",
                    timeControl = "180", rated = false, eco = null, openingName = null, openingFamily = null, openingLower = null,
                    plies = 33, pgn = OPERA, priority = Priority.USER_REQUESTED, importedAt = System.currentTimeMillis(),
                ),
            ),
        )
        val settings = EngineSettings(depth = 12, movetimeMillis = 1_000, multiPv = 2, threads = 2)
        val outcome = c.analysis.withEngine(settings) { engine -> c.analysis.analyze(c.db.games().get(id)!!, engine, settings) }
        assertEquals(AnalysisOutcome.Done, outcome)
        val game = c.db.games().get(id)!!
        assertEquals(GameStatus.READY, game.status)
        assertEquals(33, c.db.reviews().moves(id).size)
        assertTrue(game.userAccuracy!! < game.opponentAccuracy!!)
        assertTrue("practice positions from Black's errors", c.db.practice().stats(Long.MAX_VALUE).first().total > 0)
        c.db.games().delete(id)
    }
}

/** Chess.com's real public API through the device network stack (CI only: -e liveChessCom true). */
@RunWith(AndroidJUnit4::class)
class LiveChessComTest {
    @Test fun importsAPublicArchiveWithoutDuplicates() = runBlocking {
        assumeTrue(arg("liveChessCom") == "true")
        val user = arg("liveUser") ?: "erik"
        val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        val api = ChessComApi(UrlConnectionHttpClient(AppContainer.USER_AGENT))
        assertEquals(user, api.profile(user).username.lowercase())
        val sync = SyncEngine(api, RoomSyncStore(db), book = { app.container.openingBook.get() })
        val first = sync.sync(user, null, maxArchives = 1)
        assertEquals(1, first.archivesFetched)
        assertTrue("games imported: ${first.newGames}", first.newGames > 0)
        val second = sync.sync(user, first.archivesEtag, maxArchives = 1)
        assertEquals(0, second.newGames)
        db.close()
    }
}

/**
 * Drives the real app end to end: onboarding with a live Chess.com username, automatic import,
 * first-run network download, background analysis by WorkManager, then the review screens.
 * Screenshots go to the app's external files dir (pulled by CI).
 */
@RunWith(AndroidJUnit4::class)
class AppTourTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun shot(name: String) {
        compose.waitForIdle()
        Thread.sleep(400)
        val bmp: Bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        val dir = File(app.getExternalFilesDir(null), "screens").apply { mkdirs() }
        FileOutputStream(File(dir, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun waitForText(text: String, timeoutMillis: Long) =
        compose.waitUntil(timeoutMillis) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    @Test fun onboardingSyncAnalysisAndReview() {
        assumeTrue(arg("liveChessCom") == "true")
        val user = arg("liveUser") ?: "erik"
        runBlocking {
            val c = app.container
            c.settings.disconnect()
            c.db.games().deleteAll()
            c.db.archives().deleteAll()
            c.settings.setAnalysisMode(AnalysisMode.FAST)
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            waitForText("Connect Chess.com", 20_000)
            shot("01-welcome")
            compose.onNodeWithText("Connect Chess.com").performClick()
            compose.onNodeWithText("Chess.com username").performTextInput(user)
            shot("02-connect")
            compose.onNodeWithText("Find my account").performClick()
            waitForText("Import my games", 30_000)
            shot("03-profile")
            compose.onNodeWithText("Import my games").performClick()

            // Automatic import: the newest games appear without any manual step.
            waitForText("Latest game", 180_000)
            shot("04-home-importing")
            // First-run network download + background analysis of the newest game.
            waitForText("Open review", 900_000)
            shot("05-home-reviewed")
            compose.onNodeWithText("Open review").performClick()
            waitForText("Summary", 30_000)
            shot("06-review-start")
            repeat(12) { compose.onNode(hasContentDescription("Next move")).performClick() }
            shot("07-review-move")
            compose.onNode(hasText("Summary")).performClick()
            waitForText("Game summary", 10_000)
            shot("08-summary")
        }
        val ready = runBlocking { app.container.db.games().counts().first().ready }
        assertTrue("at least one review is ready", ready > 0)
    }
}
