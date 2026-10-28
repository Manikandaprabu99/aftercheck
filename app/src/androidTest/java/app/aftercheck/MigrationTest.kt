package app.aftercheck

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.aftercheck.data.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Validates the exported schema (app/schemas) against the database the app builds, and every
 * migration in [AppDatabase.MIGRATIONS]. Add a `migrate(n -> n+1)` case here with each new version.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @get:Rule val timeout: org.junit.rules.Timeout = limit(3)

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    @Test fun latestSchemaOpensWithAllMigrations() {
        helper.createDatabase(DB, 1).close()
        Room.databaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java, DB)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .build()
            .apply { openHelper.writableDatabase.close() }
    }

    @Test fun version1To2TurnsAnalyseNextRequestsIntoPicks() {
        helper.createDatabase(DB, 1).apply {
            fun insert(id: String, status: String, priority: Int, endTime: Long) = execSQL(
                "INSERT INTO games (id, white, black, opponent, opponentLower, result, userResult, endTime, timeClass, timeControl, rated, " +
                    "plies, pgn, status, priority, attempts, progress, importedAt) VALUES " +
                    "('$id', 'me', '$id', '$id', '$id', '1-0', 'win', $endTime, 'blitz', '300', 1, 40, '1. e4', '$status', $priority, 0, 0, 0)",
            )
            insert("asked", "queued", 3, 1_000)
            insert("plain", "queued", 2, 2_000)
            insert("done", "ready", 3, 3_000)
            close()
        }
        helper.runMigrationsAndValidate(DB, 2, true, AppDatabase.MIGRATION_1_2).use { db ->
            db.query("SELECT id, queueOrder FROM games ORDER BY id").use { c ->
                val rows = buildMap { while (c.moveToNext()) put(c.getString(0), if (c.isNull(1)) null else c.getLong(1)) }
                assertEquals(mapOf("asked" to -1_000L, "done" to null, "plain" to null), rows)
            }
        }
    }

    private companion object {
        const val DB = "migration-test.db"
    }
}
