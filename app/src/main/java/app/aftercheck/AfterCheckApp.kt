package app.aftercheck

import android.app.Application
import android.util.Log
import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import app.aftercheck.data.chesscom.ChessComApi
import app.aftercheck.data.chesscom.UrlConnectionHttpClient
import app.aftercheck.data.db.AppDatabase
import app.aftercheck.data.queue.AnalysisQueue
import kotlinx.coroutines.sync.Mutex
import app.aftercheck.data.settings.SettingsRepository
import app.aftercheck.data.sync.RoomSyncStore
import app.aftercheck.data.sync.SyncEngine
import app.aftercheck.engine.AnalysisService
import app.aftercheck.engine.EngineFiles
import app.aftercheck.engine.OpeningBookProvider
import app.aftercheck.work.WorkScheduler
import app.aftercheck.work.WorkStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Hand-written dependency container; small enough not to need a DI framework. */
class AppContainer(context: Context, apiBaseUrl: String) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db: AppDatabase = AppDatabase.build(context)
    val settings = SettingsRepository(context)
    val engineFiles = EngineFiles(context)
    val openingBook = OpeningBookProvider(context)
    val scheduler = WorkScheduler(context)
    val workStatus = WorkStatus(context, engineFiles)
    val api = ChessComApi(UrlConnectionHttpClient(USER_AGENT), apiBaseUrl)
    val analysis = AnalysisService(db, settings, engineFiles, openingBook)
    /** Held by the analysis run in progress, so the regular and the charging-only runs never overlap. */
    val analysisLock = Mutex()
    // Appended rather than kept, so a pick made as a run is finishing still gets a run of its own.
    val queue = AnalysisQueue(db) { scheduler.enqueueAnalysis(append = true) }

    val syncEngine = SyncEngine(api = api, store = RoomSyncStore(db), book = { openingBook.get() })

    companion object {
        const val USER_AGENT = "AfterCheck/" + BuildConfig.VERSION_NAME + " (Android; +https://github.com/Manikandaprabu99/aftercheck)"
    }
}

class AfterCheckApp : Application(), Configuration.Provider {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this, apiBaseUrl())
        container.appScope.launch {
            val s = container.settings.current()
            if (s.isConnected) {
                container.scheduler.schedulePeriodicSync(s.syncIntervalMinutes, s.syncWifiOnly, replace = false)
                if (container.engineFiles.isNetworkReady()) container.scheduler.enqueueAnalysis()
                else container.scheduler.downloadNetwork()
            }
        }
        // Opening the app is the best moment to look for just-finished games.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                container.appScope.launch {
                    val s = container.settings.current()
                    val last = s.lastSyncAttemptAt ?: 0
                    if (s.isConnected && System.currentTimeMillis() - last > FOREGROUND_SYNC_GAP) {
                        container.scheduler.syncNow(s.syncWifiOnly)
                    }
                }
            }
        })
    }

    /** WorkManager initialises on first use with this configuration (default initializer removed in the manifest). */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setMinimumLoggingLevel(if (BuildConfig.DEBUG) Log.INFO else Log.ERROR).build()

    private fun apiBaseUrl(): String {
        if (BuildConfig.DEBUG) {
            // Debug builds can point at a local test server: adb shell setprop debug.aftercheck.api http://10.0.2.2:8765
            debugProperty("debug.aftercheck.api")?.let { return it }
        }
        return ChessComApi.DEFAULT_BASE_URL
    }

    @Suppress("PrivateApi")
    private fun debugProperty(name: String): String? = try {
        val method = Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
        (method.invoke(null, name) as? String)?.takeIf { it.isNotBlank() }
    } catch (e: ReflectiveOperationException) {
        null
    }

    companion object {
        const val FOREGROUND_SYNC_GAP = 2 * 60 * 1000L
    }
}
