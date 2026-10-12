package app.aftercheck.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [ChessGameEntity::class, MoveAnalysisEntity::class, GameReviewEntity::class], version = 1, exportSchema = false)
abstract class AfterCheckDatabase : RoomDatabase() {
    abstract fun gameDao(): GameDao

    companion object {
        @Volatile private var instance: AfterCheckDatabase? = null

        fun get(context: Context): AfterCheckDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AfterCheckDatabase::class.java,
                    "aftercheck.db"
                ).build().also { instance = it }
            }
    }
}
