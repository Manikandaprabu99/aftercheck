package app.aftercheck.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.aftercheck.chess.engine.EngineSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

enum class AnalysisMode(val label: String, val depth: Int, val movetimeMillis: Long, val multiPv: Int, val summary: String) {
    FAST("Fast", 12, 800, 1, "Quick verdicts, roughly 3× faster than Balanced."),
    BALANCED("Balanced", 16, 2_000, 2, "Recommended. Reliable classifications with alternatives."),
    DEEP("Deep", 20, 5_000, 3, "Most thorough; several times slower, uses more battery."),
    CUSTOM("Custom", 16, 2_000, 2, "Your own depth, time and line count."),
}

enum class ThemeMode(val label: String) { SYSTEM("System"), LIGHT("Light"), DARK("Dark") }

enum class BoardTheme(val label: String, val light: Long, val dark: Long) {
    MOSS("Moss", 0xFFE8EBD5, 0xFF7E9B6C),
    SLATE("Slate", 0xFFDDE3E7, 0xFF8BA1AE),
    WALNUT("Walnut", 0xFFF0D9B5, 0xFFB58863),
}

data class AppSettings(
    val username: String? = null,
    val displayName: String? = null,
    val connectedAt: Long? = null,
    val lastSyncAt: Long? = null,
    val lastSyncAttemptAt: Long? = null,
    val lastSyncError: String? = null,
    val lastSyncNewGames: Int = 0,
    val historyComplete: Boolean = false,
    val syncIntervalMinutes: Int = 60,
    val syncWifiOnly: Boolean = false,
    val analysisMode: AnalysisMode = AnalysisMode.BALANCED,
    val customDepth: Int = 16,
    val customMovetimeMillis: Long = 2_000,
    val customMultiPv: Int = 2,
    val threads: Int = defaultThreads(),
    val hashMb: Int = 32,
    val backlogWhileChargingOnly: Boolean = true,
    val analysisPaused: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val boardTheme: BoardTheme = BoardTheme.MOSS,
    val showCoordinates: Boolean = true,
    val haptics: Boolean = true,
    val avgMillisPerPosition: Double? = null,
    val positionsAnalyzed: Long = 0,
    val archivesEtag: String? = null,
) {
    val isConnected: Boolean get() = username != null

    fun engineSettings(): EngineSettings = when (analysisMode) {
        AnalysisMode.CUSTOM -> EngineSettings(customDepth, customMovetimeMillis, customMultiPv, threads, hashMb)
        else -> EngineSettings(analysisMode.depth, analysisMode.movetimeMillis, analysisMode.multiPv, threads, hashMb)
    }

    companion object {
        fun defaultThreads(): Int = if (Runtime.getRuntime().availableProcessors() >= 4) 2 else 1
    }
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {
    private object K {
        val username = stringPreferencesKey("username")
        val displayName = stringPreferencesKey("display_name")
        val connectedAt = longPreferencesKey("connected_at")
        val lastSyncAt = longPreferencesKey("last_sync_at")
        val lastSyncAttemptAt = longPreferencesKey("last_sync_attempt_at")
        val lastSyncError = stringPreferencesKey("last_sync_error")
        val lastSyncNewGames = intPreferencesKey("last_sync_new_games")
        val historyComplete = booleanPreferencesKey("history_complete")
        val syncInterval = intPreferencesKey("sync_interval")
        val syncWifiOnly = booleanPreferencesKey("sync_wifi_only")
        val analysisMode = stringPreferencesKey("analysis_mode")
        val customDepth = intPreferencesKey("custom_depth")
        val customMovetime = longPreferencesKey("custom_movetime")
        val customMultiPv = intPreferencesKey("custom_multipv")
        val threads = intPreferencesKey("threads")
        val hashMb = intPreferencesKey("hash_mb")
        val backlogCharging = booleanPreferencesKey("backlog_charging_only")
        val paused = booleanPreferencesKey("analysis_paused")
        val themeMode = stringPreferencesKey("theme_mode")
        val boardTheme = stringPreferencesKey("board_theme")
        val coordinates = booleanPreferencesKey("show_coordinates")
        val haptics = booleanPreferencesKey("haptics")
        val avgMillis = doublePreferencesKey("avg_millis_per_position")
        val positions = longPreferencesKey("positions_analyzed")
        val archivesEtag = stringPreferencesKey("archives_etag")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        val d = AppSettings()
        AppSettings(
            username = p[K.username],
            displayName = p[K.displayName],
            connectedAt = p[K.connectedAt],
            lastSyncAt = p[K.lastSyncAt],
            lastSyncAttemptAt = p[K.lastSyncAttemptAt],
            lastSyncError = p[K.lastSyncError],
            lastSyncNewGames = p[K.lastSyncNewGames] ?: 0,
            historyComplete = p[K.historyComplete] ?: false,
            syncIntervalMinutes = p[K.syncInterval] ?: d.syncIntervalMinutes,
            syncWifiOnly = p[K.syncWifiOnly] ?: d.syncWifiOnly,
            analysisMode = p[K.analysisMode]?.let { n -> AnalysisMode.entries.firstOrNull { it.name == n } } ?: d.analysisMode,
            customDepth = p[K.customDepth] ?: d.customDepth,
            customMovetimeMillis = p[K.customMovetime] ?: d.customMovetimeMillis,
            customMultiPv = p[K.customMultiPv] ?: d.customMultiPv,
            threads = p[K.threads] ?: d.threads,
            hashMb = p[K.hashMb] ?: d.hashMb,
            backlogWhileChargingOnly = p[K.backlogCharging] ?: d.backlogWhileChargingOnly,
            analysisPaused = p[K.paused] ?: false,
            themeMode = p[K.themeMode]?.let { n -> ThemeMode.entries.firstOrNull { it.name == n } } ?: d.themeMode,
            boardTheme = p[K.boardTheme]?.let { n -> BoardTheme.entries.firstOrNull { it.name == n } } ?: d.boardTheme,
            showCoordinates = p[K.coordinates] ?: d.showCoordinates,
            haptics = p[K.haptics] ?: d.haptics,
            avgMillisPerPosition = p[K.avgMillis],
            positionsAnalyzed = p[K.positions] ?: 0,
            archivesEtag = p[K.archivesEtag],
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun connect(username: String, displayName: String?) = context.dataStore.edit {
        it[K.username] = username
        if (displayName != null) it[K.displayName] = displayName else it.remove(K.displayName)
        it[K.connectedAt] = System.currentTimeMillis()
        it.remove(K.lastSyncAt); it.remove(K.lastSyncError); it.remove(K.archivesEtag); it.remove(K.historyComplete)
    }

    suspend fun disconnect() = context.dataStore.edit {
        listOf(K.username, K.displayName, K.connectedAt, K.lastSyncAt, K.lastSyncAttemptAt, K.lastSyncError,
            K.lastSyncNewGames, K.archivesEtag, K.historyComplete).forEach { key -> it.remove(key) }
    }

    suspend fun recordSyncAttempt() = context.dataStore.edit { it[K.lastSyncAttemptAt] = System.currentTimeMillis() }

    suspend fun recordSync(newGames: Int, historyComplete: Boolean, archivesEtag: String?) = context.dataStore.edit {
        it[K.lastSyncAt] = System.currentTimeMillis()
        it[K.lastSyncNewGames] = newGames
        it[K.historyComplete] = historyComplete
        it.remove(K.lastSyncError)
        if (archivesEtag != null) it[K.archivesEtag] = archivesEtag
    }

    suspend fun recordSyncError(message: String) = context.dataStore.edit { it[K.lastSyncError] = message }

    suspend fun recordAnalysisSpeed(positions: Int, millis: Long) = context.dataStore.edit {
        if (positions <= 0) return@edit
        val sample = millis.toDouble() / positions
        val previous = it[K.avgMillis]
        // Exponential moving average so the estimate follows the current settings and device state.
        it[K.avgMillis] = if (previous == null) sample else previous * 0.7 + sample * 0.3
        it[K.positions] = (it[K.positions] ?: 0) + positions
    }

    suspend fun setSyncInterval(minutes: Int) = context.dataStore.edit { it[K.syncInterval] = minutes }
    suspend fun setSyncWifiOnly(value: Boolean) = context.dataStore.edit { it[K.syncWifiOnly] = value }
    suspend fun setAnalysisMode(mode: AnalysisMode) = context.dataStore.edit { it[K.analysisMode] = mode.name; it.remove(K.avgMillis) }
    suspend fun setCustomEngine(depth: Int, movetimeMillis: Long, multiPv: Int) = context.dataStore.edit {
        it[K.customDepth] = depth; it[K.customMovetime] = movetimeMillis; it[K.customMultiPv] = multiPv
        it[K.analysisMode] = AnalysisMode.CUSTOM.name
        it.remove(K.avgMillis)
    }
    suspend fun setThreads(value: Int) = context.dataStore.edit { it[K.threads] = value }
    suspend fun setHash(value: Int) = context.dataStore.edit { it[K.hashMb] = value }
    suspend fun setBacklogChargingOnly(value: Boolean) = context.dataStore.edit { it[K.backlogCharging] = value }
    suspend fun setPaused(value: Boolean) = context.dataStore.edit { it[K.paused] = value }
    suspend fun setThemeMode(value: ThemeMode) = context.dataStore.edit { it[K.themeMode] = value.name }
    suspend fun setBoardTheme(value: BoardTheme) = context.dataStore.edit { it[K.boardTheme] = value.name }
    suspend fun setShowCoordinates(value: Boolean) = context.dataStore.edit { it[K.coordinates] = value }
    suspend fun setHaptics(value: Boolean) = context.dataStore.edit { it[K.haptics] = value }
}
