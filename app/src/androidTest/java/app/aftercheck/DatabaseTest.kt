package app.aftercheck

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.aftercheck.data.db.AppDatabase
import app.aftercheck.data.db.GameEntity
import app.aftercheck.data.db.GameStatus
import app.aftercheck.data.db.PracticeEntity
import app.aftercheck.data.db.Priority
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseTest {
    @get:Rule val timeout: org.junit.rules.Timeout = limit(3)

    private lateinit var db: AppDatabase

    @Before fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
    }

    @After fun close() = db.close()

    private fun game(id: String, endTime: Long, priority: Int = Priority.BACKLOG, status: String = GameStatus.QUEUED, opponent: String = "opp") = GameEntity(
        id = id, url = null, white = "me", black = opponent, whiteRating = 1200, blackRating = 1200, userColor = "w",
        opponent = opponent, opponentLower = opponent.lowercase(), opponentRating = 1200, userRating = 1200, result = "1-0",
        userResult = "win", termination = null, endTime = endTime, timeClass = "blitz", timeControl = "180", rated = true,
        eco = "C50", openingName = "Italian Game", openingFamily = "Italian Game", openingLower = "italian game", plies = 10,
        pgn = "1. e4 e5", status = status, priority = priority, importedAt = 0,
    )

    @Test fun queueOrderAndChargingFilter() = runBlocking {
        val day = 86_400_000L
        val now = 100 * day
        db.games().insertAll(
            listOf(
                game("old", now - 60 * day),
                game("recent", now - 2 * day),
                game("older-recent", now - 10 * day),
                game("requested", now - 90 * day, priority = Priority.USER_REQUESTED),
                game("done", now, status = GameStatus.READY),
            ),
        )
        // User requests jump the queue, then newest first.
        assertEquals("requested", db.games().nextQueued(0)!!.id)
        db.games().setStatus("requested", GameStatus.READY, null, 100)
        assertEquals("recent", db.games().nextQueued(0)!!.id)
        // Off the charger only the last 30 days qualify.
        db.games().setStatus("recent", GameStatus.READY, null, 100)
        db.games().setStatus("older-recent", GameStatus.READY, null, 100)
        assertNull(db.games().nextQueued(now - 30 * day))
        assertEquals("old", db.games().nextQueued(0)!!.id)
        assertEquals(1, db.games().backlogCount(now - 30 * day))
    }

    @Test fun duplicatesAreIgnoredAndInterruptedWorkIsRecovered() = runBlocking {
        val inserted = db.games().insertAll(listOf(game("a", 1), game("a", 1), game("b", 2)))
        assertEquals(2, inserted.count { it != -1L })
        db.games().setStatus("a", GameStatus.ANALYZING, null, 50)
        assertEquals(1, db.games().resetInterrupted())
        assertEquals(GameStatus.QUEUED, db.games().get("a")!!.status)
        assertEquals(0, db.games().get("a")!!.progress)
    }

    @Test fun deletingAGameCascadesToPracticeAndCounts() = runBlocking {
        db.games().insertAll(listOf(game("x", 5, status = GameStatus.READY), game("y", 6)))
        db.practice().insertIgnore(
            listOf(
                PracticeEntity(
                    gameId = "x", ply = 3, fen = "8/8/8/8/8/8/8/K6k w - - 0 1", sideToMove = "w", playedUci = "a1a2", playedSan = "Ka2",
                    solutionUci = "a1b1", solutionSan = "Kb1", acceptedUci = "", solutionLine = "", classification = "MISTAKE", themes = "",
                    explanation = "", scoreBefore = "cp:0", scoreAfter = "cp:0", createdAt = 0, dueAt = 0,
                ),
            ),
        )
        assertEquals(1, db.practice().stats(1).first().total)
        val counts = db.games().counts().first()
        assertEquals(2, counts.total)
        assertEquals(1, counts.ready)
        assertEquals(1, counts.pending)
        db.games().delete("x")
        assertEquals(0, db.practice().stats(1).first().total)
    }
}
