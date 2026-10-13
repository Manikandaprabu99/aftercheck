package app.aftercheck

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.aftercheck.data.db.AppDatabase
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

    private companion object {
        const val DB = "migration-test.db"
    }
}
