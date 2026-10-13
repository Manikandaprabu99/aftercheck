package app.aftercheck.ui.settings

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.aftercheck.AppContainer
import app.aftercheck.BuildConfig
import app.aftercheck.R
import app.aftercheck.data.db.AppDatabase
import app.aftercheck.data.db.LibraryCounts
import app.aftercheck.data.settings.AnalysisMode
import app.aftercheck.data.settings.AppSettings
import app.aftercheck.data.settings.BoardTheme
import app.aftercheck.data.settings.ThemeMode
import app.aftercheck.engine.EngineFiles
import app.aftercheck.ui.components.AppCard
import app.aftercheck.ui.components.AppIcon
import app.aftercheck.ui.components.Format
import app.aftercheck.ui.components.Monogram
import app.aftercheck.ui.theme.AppTheme
import app.aftercheck.work.NetworkStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

data class Storage(val databaseBytes: Long, val networkBytes: Long, val cacheEntries: Int)

data class SettingsState(
    val settings: AppSettings,
    val counts: LibraryCounts,
    val network: NetworkStatus,
    val storage: Storage,
)

class SettingsViewModel(private val c: AppContainer, private val dbFile: File) : ViewModel() {
    private val storage = MutableStateFlow(Storage(0, 0, 0))

    val state: StateFlow<SettingsState?> = combine(
        c.settings.settings, c.db.games().counts(), c.workStatus.network, storage,
    ) { s, counts, network, st -> SettingsState(s, counts, network, st) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Reviews produced with engine settings other than the current ones. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val otherSettings: StateFlow<Int> = c.settings.settings
        .map { it.engineSettings().key }
        .distinctUntilChanged()
        .flatMapLatest { key -> c.db.games().countWithOtherSettings(key) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    init { refreshStorage() }

    fun refreshStorage() = viewModelScope.launch {
        val st = withContext(Dispatchers.IO) {
            val db = listOf(dbFile, File(dbFile.path + "-wal"), File(dbFile.path + "-shm")).sumOf { it.takeIf { f -> f.exists() }?.length() ?: 0L }
            Storage(db, c.engineFiles.networkFile.takeIf { it.exists() }?.length() ?: 0L, c.db.evalCache().count())
        }
        storage.value = st
    }

    private fun edit(block: suspend () -> Unit) = viewModelScope.launch { block() }

    fun syncNow() = c.scheduler.syncNow()
    fun setInterval(minutes: Int) = edit {
        c.settings.setSyncInterval(minutes)
        val s = c.settings.current()
        c.scheduler.schedulePeriodicSync(minutes, s.syncWifiOnly, replace = true)
    }
    fun setWifiOnly(v: Boolean) = edit {
        c.settings.setSyncWifiOnly(v)
        val s = c.settings.current()
        c.scheduler.schedulePeriodicSync(s.syncIntervalMinutes, v, replace = true)
    }
    fun setMode(mode: AnalysisMode) = edit { c.settings.setAnalysisMode(mode) }
    fun setCustom(depth: Int, movetime: Long, lines: Int) = edit { c.settings.setCustomEngine(depth, movetime, lines) }
    fun setThreads(v: Int) = edit { c.settings.setThreads(v) }
    fun setHash(v: Int) = edit { c.settings.setHash(v) }
    fun setBacklogCharging(v: Boolean) = edit { c.settings.setBacklogChargingOnly(v); c.scheduler.enqueueAnalysis() }
    fun setPaused(v: Boolean) = edit {
        c.settings.setPaused(v)
        if (v) c.scheduler.cancelAnalysis() else c.scheduler.enqueueAnalysis()
    }
    fun reanalyseOther() = edit {
        c.db.games().requeueWithOtherSettings(c.settings.current().engineSettings().key)
        c.scheduler.enqueueAnalysis()
    }
    fun retryFailed() = edit { c.db.games().requeueFailed(); c.scheduler.enqueueAnalysis() }
    fun setTheme(v: ThemeMode) = edit { c.settings.setThemeMode(v) }
    fun setBoard(v: BoardTheme) = edit { c.settings.setBoardTheme(v) }
    fun setCoordinates(v: Boolean) = edit { c.settings.setShowCoordinates(v) }
    fun setHaptics(v: Boolean) = edit { c.settings.setHaptics(v) }
    fun downloadNetwork() = c.scheduler.downloadNetwork(replace = true)
    fun removeNetwork() = edit {
        c.scheduler.cancelAnalysis()
        withContext(Dispatchers.IO) { c.engineFiles.deleteNetwork() }
        refreshStorage()
    }
    fun clearCache() = edit { c.db.evalCache().clear(); refreshStorage() }
    fun deleteAllGames() = edit {
        c.scheduler.cancelAnalysis()
        c.db.games().deleteAll()
        c.db.archives().deleteAll()
        c.settings.current().username?.let { u -> c.settings.connect(u, c.settings.current().displayName) }
        refreshStorage()
    }
    fun disconnect(deleteData: Boolean) = edit {
        c.scheduler.cancelAll()
        c.settings.disconnect()
        c.db.archives().deleteAll()
        if (deleteData) c.db.games().deleteAll()
    }
}

private val INTERVALS = listOf(15 to "15 min", 30 to "30 min", 60 to "1 hour", 180 to "3 hours", 360 to "6 hours")

@Composable
fun SettingsScreen(container: AppContainer) {
    val context = LocalContext.current
    val vm: SettingsViewModel = viewModel { SettingsViewModel(container, context.getDatabasePath(AppDatabase.NAME)) }
    val state by vm.state.collectAsStateWithLifecycle()
    val otherSettings by vm.otherSettings.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<String?>(null) }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val st = state ?: return@Box
        val s = st.settings
        LazyColumn(
            Modifier.widthIn(max = 720.dp).fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item { Text("Settings", style = MaterialTheme.typography.headlineMedium) }

            item {
                Section("Chess.com account") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Monogram(s.username ?: "?", size = 44.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(s.username ?: "Not connected", style = MaterialTheme.typography.titleMedium)
                            Text("Public archive · no password stored", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    InfoLine("Last successful sync", Format.relative(s.lastSyncAt))
                    s.lastSyncError?.let { InfoLine("Last problem", it, error = true) }
                    InfoLine("Games imported", "%,d".format(st.counts.total))
                    InfoLine("History", if (s.historyComplete) "Fully imported" else "Importing older months…")
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FilledTonalButton(onClick = vm::syncNow) { Text("Sync now") }
                        OutlinedButton(onClick = { dialog = "disconnect" }) { Text("Disconnect") }
                    }
                }
            }

            item {
                Section("Background sync") {
                    Text("Check for new games every", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(8.dp))
                    ChipChoice(INTERVALS.map { it.second }, INTERVALS.indexOfFirst { it.first == s.syncIntervalMinutes }.coerceAtLeast(0)) {
                        vm.setInterval(INTERVALS[it].first)
                    }
                    SwitchRow("Only on Wi-Fi", "Syncing uses very little data, so this is off by default.", s.syncWifiOnly, vm::setWifiOnly)
                    Note(
                        "Android decides exactly when background work runs (battery saver, Doze), so new games can take longer to appear. " +
                            "Opening AfterCheck always checks right away. Chess.com usually publishes finished games within minutes.",
                    )
                }
            }

            item {
                Section("Analysis") {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        AnalysisMode.entries.forEachIndexed { i, m ->
                            SegmentedButton(
                                selected = s.analysisMode == m,
                                onClick = { vm.setMode(m) },
                                shape = SegmentedButtonDefaults.itemShape(i, AnalysisMode.entries.size),
                                icon = {},
                                label = { Text(m.label, style = MaterialTheme.typography.labelMedium) },
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(s.analysisMode.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val es = s.engineSettings()
                    Text(
                        "Depth ${es.depth} · up to ${String.format(Locale.US, "%.1f", es.movetimeMillis / 1000.0)} s per position · ${es.multiPv} line${if (es.multiPv > 1) "s" else ""}",
                        style = MaterialTheme.typography.labelMedium,
                        color = AppTheme.colors.textMuted,
                    )
                    s.avgMillisPerPosition?.let { avg ->
                        Text(
                            "On this phone: ≈ ${String.format(Locale.US, "%.1f", avg / 1000)} s per position, about ${Format.duration(avg * 80)} for a 40-move game.",
                            style = MaterialTheme.typography.labelMedium,
                            color = AppTheme.colors.textMuted,
                        )
                    }
                    AdvancedEngine(s, vm)
                    HorizontalDivider(Modifier.padding(vertical = 8.dp), color = AppTheme.colors.border)
                    SwitchRow("Older history only while charging", "New and recent games are always reviewed. Games older than 30 days wait for the charger.", s.backlogWhileChargingOnly, vm::setBacklogCharging)
                    SwitchRow("Pause automatic reviews", "Stops the engine until you resume. Sync continues.", s.analysisPaused, vm::setPaused)
                    if (otherSettings > 0) {
                        Spacer(Modifier.height(6.dp))
                        ActionRow("$otherSettings review${if (otherSettings == 1) "" else "s"} used other settings", "Re-analyse") { vm.reanalyseOther() }
                    }
                    if (st.counts.failed > 0) ActionRow("${st.counts.failed} game${if (st.counts.failed == 1) "" else "s"} failed to analyse", "Retry") { vm.retryFailed() }
                }
            }

            item {
                Section("Engine") {
                    InfoLine("Engine", "${EngineFiles.ENGINE_NAME} (GPLv3)")
                    InfoLine(
                        "Network",
                        when {
                            st.network.ready -> "Ready · ${Format.bytes(EngineFiles.NETWORK_BYTES)}"
                            st.network.downloading -> "Downloading · ${Format.bytes(st.network.downloaded)}"
                            else -> "Not downloaded (${Format.bytes(EngineFiles.NETWORK_BYTES)})"
                        },
                    )
                    InfoLine("Build", container.engineFiles.engineVariant)
                    Spacer(Modifier.height(8.dp))
                    if (st.network.ready) OutlinedButton(onClick = { dialog = "network" }) { Text("Remove network") }
                    else FilledTonalButton(onClick = vm::downloadNetwork) { Text("Download network") }
                }
            }

            item {
                Section("Appearance") {
                    Text("Theme", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(8.dp))
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        ThemeMode.entries.forEachIndexed { i, m ->
                            SegmentedButton(
                                selected = s.themeMode == m,
                                onClick = { vm.setTheme(m) },
                                shape = SegmentedButtonDefaults.itemShape(i, ThemeMode.entries.size),
                                icon = {},
                                label = { Text(m.label) },
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Text("Board", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        BoardTheme.entries.forEach { b -> BoardSwatch(b, s.boardTheme == b) { vm.setBoard(b) } }
                    }
                    Spacer(Modifier.height(6.dp))
                    SwitchRow("Board coordinates", null, s.showCoordinates, vm::setCoordinates)
                    SwitchRow("Haptic feedback", null, s.haptics, vm::setHaptics)
                }
            }

            item {
                Section("Storage") {
                    InfoLine("Games and reviews", Format.bytes(st.storage.databaseBytes))
                    InfoLine("Engine network", Format.bytes(st.storage.networkBytes))
                    InfoLine("Cached evaluations", "%,d positions".format(st.storage.cacheEntries))
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = vm::clearCache) { Text("Clear cache") }
                        OutlinedButton(onClick = { dialog = "delete" }) { Text("Delete all games") }
                    }
                }
            }

            item {
                Section("Privacy & fair play") {
                    Note("Your games, reviews and practice stay on this device. AfterCheck has no account, no analytics and no tracking. The only network requests are to Chess.com's public API and, once, to download the official Stockfish network.")
                    Spacer(Modifier.height(6.dp))
                    Note("AfterCheck only analyses games that are finished and published in your Chess.com archive. It never looks at a game in progress and never suggests moves during play.")
                }
            }

            item {
                Section("About") {
                    InfoLine("Version", BuildConfig.VERSION_NAME)
                    ActionRow("Source code and releases", "GitHub") {
                        context.startActivity(Intent(Intent.ACTION_VIEW, "https://github.com/Manikandaprabu99/aftercheck".toUri()))
                    }
                    ActionRow("Open-source licences", "View") { dialog = "licenses" }
                    Note("AfterCheck is an independent project and is not affiliated with or endorsed by Chess.com. Accuracy figures are AfterCheck's own estimates.")
                }
            }
        }
    }

    when (dialog) {
        "disconnect" -> {
            var alsoDelete by remember { mutableStateOf(false) }
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text("Disconnect account?") },
                text = {
                    Column {
                        Text("Background sync stops. You can connect again at any time.")
                        Row(Modifier.padding(top = 12.dp).clickable { alsoDelete = !alsoDelete }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = alsoDelete, onCheckedChange = { alsoDelete = it })
                            Text("Also delete games, reviews and practice")
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { dialog = null; vm.disconnect(alsoDelete) }) { Text("Disconnect") } },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
            )
        }
        "delete" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Delete all games?") },
            text = { Text("All games, reviews and practice positions are removed from this device. While your account stays connected, your history will be imported and reviewed again.") },
            confirmButton = { TextButton(onClick = { dialog = null; vm.deleteAllGames() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
        )
        "network" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Remove engine network?") },
            text = { Text("Frees ${Format.bytes(EngineFiles.NETWORK_BYTES)}. Reviews pause until it's downloaded again.") },
            confirmButton = { TextButton(onClick = { dialog = null; vm.removeNetwork() }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
        )
        "licenses" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Open-source licences") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    LICENSES.forEach { (name, text) ->
                        Text(name, style = MaterialTheme.typography.titleSmall)
                        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(10.dp))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text("Close") } },
        )
    }
}

private val LICENSES = listOf(
    "AfterCheck" to "GNU GPL v3. Source: github.com/Manikandaprabu99/aftercheck",
    "Stockfish 16" to "GNU GPL v3 · The Stockfish developers. Source: github.com/official-stockfish/Stockfish (tag sf_16). Network nn-5af11540bbfe.nnue from tests.stockfishchess.org.",
    "Chess pieces (cburnett)" to "GPL v2 or later · Colin M.L. Burnett, via lichess-org/lila.",
    "Opening names" to "CC0 · lichess-org/chess-openings.",
    "Inter typeface" to "SIL Open Font License 1.1 · The Inter Project Authors.",
    "Material Symbols" to "Apache License 2.0 · Google.",
    "AndroidX, Jetpack Compose, Kotlin" to "Apache License 2.0 · Google, JetBrains.",
)

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column {
        Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, color = AppTheme.colors.textMuted, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
        AppCard(Modifier.fillMaxWidth()) { content() }
    }
}

@Composable
private fun InfoLine(label: String, value: String, error: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1.3f))
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(10.dp))
            .clickable(role = Role.Switch) { onChange(!checked) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun ActionRow(text: String, action: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        TextButton(onClick = onClick) { Text(action) }
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun ChipChoice(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier.weight(1f).heightIn(min = 40.dp).clip(RoundedCornerShape(10.dp))
                    .background(if (on) MaterialTheme.colorScheme.primaryContainer else AppTheme.colors.subtle)
                    .clickable(role = Role.RadioButton) { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = if (on) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
private fun BoardSwatch(b: BoardTheme, selected: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(role = Role.RadioButton, onClick = onClick).padding(4.dp)) {
        Box(
            Modifier.size(56.dp).clip(RoundedCornerShape(8.dp))
                .border(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else AppTheme.colors.border, RoundedCornerShape(8.dp)),
        ) {
            Column {
                repeat(2) { r ->
                    Row {
                        repeat(2) { c ->
                            Box(Modifier.size(28.dp).background(Color(if ((r + c) % 2 == 0) b.light else b.dark)))
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(b.label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun AdvancedEngine(s: AppSettings, vm: SettingsViewModel) {
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(10.dp)).clickable { open = !open }.heightIn(min = 44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Advanced engine settings", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
        AppIcon(if (open) R.drawable.ic_expand_less else R.drawable.ic_expand_more, null, tint = MaterialTheme.colorScheme.primary, size = 20.dp)
    }
    AnimatedVisibility(open) {
        Column {
            val es = s.engineSettings()
            var depth by remember(es.depth) { mutableFloatStateOf(es.depth.toFloat()) }
            var time by remember(es.movetimeMillis) { mutableFloatStateOf(es.movetimeMillis / 1000f) }
            var lines by remember(es.multiPv) { mutableFloatStateOf(es.multiPv.toFloat()) }
            SliderRow("Depth", "${depth.toInt()}", depth, 8f..30f, 21) { depth = it }
            SliderRow("Time per position", String.format(Locale.US, "%.1f s", time), time, 0.5f..10f, 18) { time = it }
            SliderRow("Engine lines (MultiPV)", "${lines.toInt()}", lines, 1f..3f, 1) { lines = it }
            TextButton(onClick = { vm.setCustom(depth.toInt(), (time * 1000).toLong(), lines.toInt()) }) { Text("Apply as custom mode") }
            val cores = Runtime.getRuntime().availableProcessors()
            Text("Threads", style = MaterialTheme.typography.labelLarge)
            ChipChoice((1..minOf(4, cores)).map { "$it" }, s.threads - 1) { vm.setThreads(it + 1) }
            Spacer(Modifier.height(10.dp))
            Text("Hash table", style = MaterialTheme.typography.labelLarge)
            val hashes = listOf(16, 32, 64, 128)
            ChipChoice(hashes.map { "$it MB" }, hashes.indexOf(s.hashMb).coerceAtLeast(0)) { vm.setHash(hashes[it]) }
            Note("More threads finish sooner but use more battery. Deeper settings give steadier verdicts on sharp positions.")
        }
    }
}

@Composable
private fun SliderRow(label: String, value: String, current: Float, range: ClosedFloatingPointRange<Float>, steps: Int, onChange: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Row {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(value, style = MaterialTheme.typography.labelLarge)
        }
        Slider(value = current, onValueChange = onChange, valueRange = range, steps = steps)
    }
}
