package app.aftercheck

import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import app.aftercheck.chess.GameLine
import app.aftercheck.chess.Pgn
import app.aftercheck.chess.ReplayResult
import app.aftercheck.chess.analysis.MoveClass
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
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

private val app get() = ApplicationProvider.getApplicationContext<AfterCheckApp>()

/** Fails a hung test with the stuck thread's stack trace instead of blocking the whole run. */
internal fun limit(minutes: Long): Timeout =
    Timeout.builder().withTimeout(minutes, TimeUnit.MINUTES).withLookingForStuckThread(true).build()
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
    @get:Rule val timeout: Timeout = limit(10)

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
        // One thread keeps the search deterministic.
        val settings = EngineSettings(depth = 12, movetimeMillis = 2_000, multiPv = 2, threads = 1)
        val outcome = c.analysis.withEngine(settings) { engine -> c.analysis.analyze(c.db.games().get(id)!!, engine, settings) }
        assertEquals(AnalysisOutcome.Done, outcome)
        val game = c.db.games().get(id)!!
        assertEquals(GameStatus.READY, game.status)
        assertEquals(33, c.db.reviews().moves(id).size)
        assertTrue(game.userAccuracy!! < game.opponentAccuracy!!)
        // Every serious error by the user (Black here) becomes exactly one practice position.
        val serious = c.db.reviews().moves(id).count { m ->
            m.isUser && m.bestUci != null &&
                MoveClass.fromName(m.classification)?.isSerious == true
        }
        assertEquals(serious, c.db.practice().queue(Long.MAX_VALUE, "", false, 500).count { it.gameId == id })
        c.db.games().delete(id)
    }
}

/** Chess.com's real public API through the device network stack (CI only: -e liveChessCom true). */
@RunWith(AndroidJUnit4::class)
class LiveChessComTest {
    @get:Rule val timeout: Timeout = limit(5)

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
 * Drives the real app end to end like a user: onboarding with a live Chess.com username, automatic
 * import, first-run network download, background analysis by WorkManager, then the review screens.
 * Uses UI Automator (accessibility tree) so running animations never block it. Screenshots go to the
 * app's external files dir (pulled by CI).
 */
@RunWith(AndroidJUnit4::class)
class AppTourTest {
    @get:Rule val timeout: Timeout = limit(25)

    private val device: UiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    private fun shot(name: String) {
        Thread.sleep(600)
        val dir = File(app.getExternalFilesDir(null), "screens").apply { mkdirs() }
        device.takeScreenshot(File(dir, "$name.png"))
    }

    private fun button(description: String): UiObject2 =
        device.wait(Until.findObject(By.desc(description)), 5_000) ?: throw AssertionError("no \"$description\" button")

    /** Waits for [text], dismissing system "isn't responding" dialogs that CI emulators show (launcher ANRs). */
    private fun find(text: String, timeoutMillis: Long): UiObject2 {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            device.findObject(By.pkg("android").text("Wait"))?.click()
            device.wait(Until.findObject(By.textContains(text)), 2_000)?.let { return it }
        }
        shot("failed-waiting-for-${text.filter { it.isLetterOrDigit() }}")
        throw AssertionError("\"$text\" did not appear within ${timeoutMillis / 1000}s")
    }

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
            find("Connect Chess.com", 30_000)
            shot("01-welcome")
            find("Connect Chess.com", 5_000).click()
            val field = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 10_000)
                ?: throw AssertionError("username field not found")
            field.text = user
            shot("02-connect")
            find("Find my account", 5_000).click()
            find("Import my games", 60_000)
            shot("03-profile")
            find("Import my games", 5_000).click()

            // Automatic import: the newest games appear without any manual step.
            find("Latest game", 240_000)
            shot("04-home-importing")
            // First-run network download + background analysis of the newest game.
            find("Open review", 900_000)
            shot("05-home-reviewed")
            find("Open review", 5_000).click()
            // An analysed game opens on its report; the moves tab steps through it.
            find("Estimated accuracy", 60_000)
            shot("06-review-report")
            find("Moves", 5_000).click()
            repeat(12) { button("Next move").click() }
            shot("07-review-move")
            // Follow-up: the engine line plays out on the board by itself.
            val mistake = device.wait(Until.findObject(By.desc("Next mistake")), 5_000)
            if (mistake != null && mistake.isEnabled) {
                mistake.click()
                device.wait(Until.findObject(By.text("Show best line")), 5_000)?.let { line ->
                    line.click()
                    // Reaching the end with no further taps shows the line played by itself.
                    find("End of the engine", 30_000)
                    shot("08-review-best-line")
                    button("Back to game").click()
                }
            }
            find("Report", 5_000).click()
            find("Estimated accuracy", 15_000)
            device.pressBack()
            (device.wait(Until.findObject(By.text("Games")), 15_000) ?: throw AssertionError("no Games tab")).click()
            button("Analysis queue").click()
            find("Analysis queue", 15_000)
            shot("09-queue")
        }
        val ready = runBlocking { app.container.db.games().counts().first().ready }
        assertTrue("at least one review is ready", ready > 0)
    }
}
