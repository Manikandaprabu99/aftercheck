package app.aftercheck.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

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
    version = 1,
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
        val MIGRATIONS = emptyArray<androidx.room.migration.Migration>()

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, NAME)
                .addMigrations(*MIGRATIONS)
                .build()
    }
}
