package app.aftercheck.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.aftercheck.data.db.AppDatabase
import app.aftercheck.data.db.GameEntity
import app.aftercheck.data.db.GameStatus
import app.aftercheck.data.db.Priority
import app.aftercheck.data.queue.AnalysisQueue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

private const val DAY = 24 * 60 * 60 * 1000L

private fun game(id: String, endTime: Long, status: String = GameStatus.QUEUED, priority: Int = Priority.BACKLOG) = GameEntity(
    id = id, url = null, white = "me", black = id, whiteRating = 1500, blackRating = 1500, userColor = "w", opponent = id,
    opponentLower = id, opponentRating = 1500, userRating = 1500, result = "1-0", userResult = "win", termination = "resignation",
    endTime = endTime, timeClass = "blitz", timeControl = "300", rated = true, eco = null, openingName = null, openingFamily = null,
    openingLower = null, plies = 40, pgn = "1. e4 e5", status = status, priority = priority, importedAt = 0,
)

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class QueueTest {
    private lateinit var db: AppDatabase
    private lateinit var queue: AnalysisQueue
    private var scheduled = 0
    private val games get() = db.games()

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
        queue = AnalysisQueue(db) { scheduled++ }
        runBlocking {
            games.insertAll(
                listOf(
                    game("new", 10 * DAY, priority = Priority.NEW),
                    game("recent", 9 * DAY, priority = Priority.RECENT),
                    game("old1", 3 * DAY),
                    game("old2", 2 * DAY),
                    game("old3", 1 * DAY),
                    game("done", 8 * DAY, status = GameStatus.READY),
                    game("broken", 7 * DAY, status = GameStatus.FAILED),
                ),
            )
        }
    }

    @After fun tearDown() = db.close()

    private suspend fun order(): List<String> {
        val out = ArrayList<String>()
        while (true) {
            val next = games.nextQueued(0) ?: return out
            out += next.id
            games.setStatus(next.id, GameStatus.READY, null, 100)
        }
    }

    @Test fun automaticOrderIsNewestFirst() = runBlocking {
        assertEquals(listOf("new", "recent", "old1", "old2", "old3"), order())
    }

    @Test fun picksGoFirstInTheChosenOrder() = runBlocking {
        assertEquals("reviewed games are skipped", 2, queue.pick(listOf("old3", "old1", "done")))
        assertEquals(1, scheduled)
        assertEquals(listOf("old3", "old1", "new", "recent", "old2"), order())
    }

    @Test fun pickingAgainKeepsPlaceAndAppendsNewOnes() = runBlocking {
        queue.pick(listOf("old2", "old3"))
        queue.pick(listOf("old1", "old2"))
        assertEquals(listOf("old2", "old3", "old1"), games.pickedIds())
    }

    @Test fun analyseNextPutsAGameInFrontOfOtherPicks() = runBlocking {
        queue.pick(listOf("old2", "old3"))
        assertTrue(queue.pickFirst("old1"))
        assertFalse("reviewed games can't be picked", queue.pickFirst("done"))
        assertEquals(listOf("old1", "old2", "old3"), games.pickedIds())
    }

    @Test fun failedGameIsQueuedAgainWhenPicked() = runBlocking {
        games.update(games.get("broken")!!.copy(attempts = 3, statusMessage = "Engine crashed"))
        queue.pick(listOf("broken"))
        val g = games.get("broken")!!
        assertEquals(GameStatus.QUEUED, g.status)
        assertEquals(0, g.attempts)
        assertNull(g.statusMessage)
        assertEquals("broken", games.nextQueued(0)!!.id)
    }

    @Test fun reorderMoveAndUnpick() = runBlocking {
        queue.pick(listOf("old1", "old2", "old3"))
        queue.reorder(listOf("old3", "old1", "old2"))
        assertEquals(listOf("old3", "old1", "old2"), games.pickedIds())
        queue.moveBy("old2", -1)
        assertEquals(listOf("old3", "old2", "old1"), games.pickedIds())
        queue.moveToTop("old1")
        assertEquals(listOf("old1", "old3", "old2"), games.pickedIds())
        queue.unpick("old3")
        assertEquals(listOf("old1", "old2"), games.pickedIds())
        assertEquals(listOf("old1", "old2", "new", "recent", "old3"), order())
    }

    @Test fun picksIgnoreTheChargingWindow() = runBlocking {
        val window = 9 * DAY
        assertEquals("new", games.nextQueued(window)!!.id)
        assertEquals(3, games.backlogCount(window))
        queue.pick(listOf("old2"))
        assertEquals("old2", games.nextQueued(window)!!.id)
        assertEquals(2, games.backlogCount(window))
    }

    @Test fun ranksAndListsFollowThePicks() = runBlocking {
        assertFalse(games.hasWaitingPicks())
        queue.pick(listOf("old2", "old1"))
        assertTrue(games.hasWaitingPicks())
        assertEquals(listOf("old2" to 1, "old1" to 2), games.observePicks().first().map { it.id to it.queueRank })
        assertEquals(listOf("new", "recent", "old3"), games.observeAutoQueue(10).first().map { it.id })
        queue.clear()
        assertFalse(games.hasWaitingPicks())
        assertEquals(listOf("old3", "old2", "old1", "broken", "recent", "new"), games.libraryPickableIds("", "", "", "", "", 1, 10))
    }
}

/** Upgrades a real version-1 database (built from the exported schema) and lets Room validate the result. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MigrationJvmTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "migration-jvm.db"

    @After fun tearDown() {
        context.deleteDatabase(name)
    }

    @Test fun version1To2KeepsDataAndTurnsRequestsIntoPicks() {
        val schema = JSONObject(File("schemas/app.aftercheck.data.db.AppDatabase/1.json").readText()).getJSONObject("database")
        val file = context.getDatabasePath(name).also { it.parentFile!!.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { v1 ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val e = entities.getJSONObject(i)
                val table = e.getString("tableName")
                v1.execSQL(e.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = e.optJSONArray("indices") ?: continue
                for (j in 0 until indices.length()) v1.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) v1.execSQL(setup.getString(i))
            fun insert(id: String, status: String, priority: Int, endTime: Long) = v1.execSQL(
                "INSERT INTO games (id, white, black, opponent, opponentLower, result, userResult, endTime, timeClass, timeControl, rated, " +
                    "plies, pgn, status, priority, attempts, progress, importedAt) VALUES " +
                    "('$id', 'me', '$id', '$id', '$id', '1-0', 'win', $endTime, 'blitz', '300', 1, 40, '1. e4', '$status', $priority, 0, 0, 0)",
            )
            insert("asked-old", GameStatus.QUEUED, Priority.USER_REQUESTED, 1 * DAY)
            insert("asked-new", GameStatus.QUEUED, Priority.USER_REQUESTED, 2 * DAY)
            insert("plain", GameStatus.QUEUED, Priority.NEW, 3 * DAY)
            insert("reviewed", GameStatus.READY, Priority.USER_REQUESTED, 4 * DAY)
            v1.version = 1
        }

        val db = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(*AppDatabase.MIGRATIONS).build()
        runBlocking {
            assertEquals(listOf("asked-new", "asked-old"), db.games().pickedIds())
            assertNull(db.games().get("plain")!!.queueOrder)
            assertNull(db.games().get("reviewed")!!.queueOrder)
            assertEquals(listOf("asked-new", "asked-old", "plain"), generateSequence { runBlocking { db.games().nextQueued(0) } }
                .take(3).map { g -> g.id.also { runBlocking { db.games().setStatus(it, GameStatus.READY, null, 100) } } }.toList())
        }
        db.close()
    }
}
