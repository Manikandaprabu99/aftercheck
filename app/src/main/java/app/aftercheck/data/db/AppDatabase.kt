package app.aftercheck.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Local store for games, reviews, practice and sync state.
 *
 * Schema history is exported to `app/schemas/`. Every future version must add a [androidx.room.migration.Migration]
 * (or an `AutoMigration`) to [MIGRATIONS] — destructive fallback is deliberately not enabled, so user
 * history is never silently wiped by an upgrade.
 */
@Database(
    entities = [
        GameEntity::class,
        MoveEntity::class,
        ReviewEntity::class,
        ArchiveEntity::class,
        EvalCacheEntity::class,
        PracticeEntity::class,
        PracticeAttemptEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun games(): GameDao
    abstract fun reviews(): ReviewDao
    abstract fun archives(): ArchiveDao
    abstract fun evalCache(): EvalCacheDao
    abstract fun practice(): PracticeDao

    companion object {
        const val NAME = "aftercheck.db"

        /** 1 -> 2: games the user asked to analyse next become the first picks of the new ordered queue. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE games ADD COLUMN queueOrder INTEGER")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_games_queueOrder` ON `games` (`queueOrder`)")
                db.execSQL("UPDATE games SET queueOrder = -endTime WHERE priority = ${Priority.USER_REQUESTED} AND status IN ('queued', 'analyzing')")
            }
        }

        val MIGRATIONS = arrayOf<Migration>(MIGRATION_1_2)

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, NAME)
                .addMigrations(*MIGRATIONS)
                .build()
    }
}
