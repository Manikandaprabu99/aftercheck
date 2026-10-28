package app.aftercheck.ui.queue

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.aftercheck.AppContainer
import app.aftercheck.R
import app.aftercheck.data.db.GameSummary
import app.aftercheck.data.settings.AppSettings
import app.aftercheck.ui.components.AppCard
import app.aftercheck.ui.components.AppIcon
import app.aftercheck.ui.components.EmptyState
import app.aftercheck.ui.components.Format
import app.aftercheck.ui.components.SectionHeader
import app.aftercheck.ui.components.Skeleton
import app.aftercheck.ui.theme.AppTheme
import app.aftercheck.ui.theme.numeric
import app.aftercheck.work.NetworkStatus
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class QueueState(
    val analyzing: GameSummary?,
    val picks: List<GameSummary>,
    val upNext: List<GameSummary>,
    val queued: Int,
    val settings: AppSettings,
    val network: NetworkStatus,
)

private data class QueueLists(val analyzing: GameSummary?, val picks: List<GameSummary>, val upNext: List<GameSummary>, val queued: Int)

class QueueViewModel(private val c: AppContainer) : ViewModel() {
    private val games = c.db.games()

    val state = combine(
        combine(games.analyzing(), games.observePicks(), games.observeAutoQueue(AUTO_LIMIT), games.observeQueuedCount(), ::QueueLists),
        c.settings.settings,
        c.workStatus.network,
    ) { l, settings, network -> QueueState(l.analyzing, l.picks, l.upNext, l.queued, settings, network) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun add(id: String) = viewModelScope.launch { c.queue.pick(listOf(id)) }
    fun remove(id: String) = viewModelScope.launch { c.queue.unpick(id) }
    fun clear() = viewModelScope.launch { c.queue.clear() }
    fun moveToTop(id: String) = viewModelScope.launch { c.queue.moveToTop(id) }
    fun moveBy(id: String, delta: Int) = viewModelScope.launch { c.queue.moveBy(id, delta) }
    fun reorder(ids: List<String>) = viewModelScope.launch { c.queue.reorder(ids) }
    fun resume() = viewModelScope.launch {
        c.settings.setPaused(false)
        c.scheduler.enqueueAnalysis()
    }

    companion object {
        const val AUTO_LIMIT = 20
    }
}

private data class Battery(val charging: Boolean, val low: Boolean)

@Composable
private fun rememberBattery(): State<Battery> {
    val context = LocalContext.current
    val state = remember { mutableStateOf(Battery(charging = false, low = false)) }
    DisposableEffect(context) {
        fun read(intent: Intent) {
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            val low = if (Build.VERSION.SDK_INT >= 28) intent.getBooleanExtra(BatteryManager.EXTRA_BATTERY_LOW, false)
            else level in 0 until scale * 15 / 100
            state.value = Battery(status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL, low)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = read(intent)
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)?.let(::read)
        onDispose { context.unregisterReceiver(receiver) }
    }
    return state
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueScreen(container: AppContainer, onBack: () -> Unit, onOpenGame: (String) -> Unit) {
    val vm: QueueViewModel = viewModel { QueueViewModel(container) }
    val state by vm.state.collectAsStateWithLifecycle()
    val battery by rememberBattery()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { AppIcon(R.drawable.ic_arrow_back, "Back") } },
                title = { Text("Analysis queue", style = MaterialTheme.typography.titleMedium) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            val s = state
            when {
                s == null -> Column(Modifier.widthIn(max = 720.dp).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    repeat(4) { Skeleton(Modifier.fillMaxWidth().height(64.dp)) }
                }
                s.analyzing == null && s.queued == 0 -> EmptyState(
                    R.drawable.ic_check_circle,
                    "All caught up",
                    "Every imported game has been analysed. New games are queued automatically after each sync.",
                )
                else -> QueueList(s, battery, vm, onOpenGame)
            }
        }
    }
}

@Composable
private fun QueueList(s: QueueState, battery: Battery, vm: QueueViewModel, onOpenGame: (String) -> Unit) {
    val listState = rememberLazyListState()
    val avg = s.settings.avgMillisPerPosition
    var working by remember { mutableStateOf(s.picks) }
    var dragging by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val latestPicks by rememberUpdatedState(s.picks)
    LaunchedEffect(s.picks) { if (dragging == null) working = s.picks }

    fun drag(dy: Float) {
        dragOffset += dy
        val items = listState.layoutInfo.visibleItemsInfo
        val current = items.firstOrNull { it.key == pickKey(dragging) } ?: return
        val center = current.offset + dragOffset + current.size / 2f
        val target = items.firstOrNull {
            it.key != current.key && (it.key as? String)?.startsWith(PICK) == true && center >= it.offset && center <= it.offset + it.size
        } ?: return
        val from = working.indexOfFirst { pickKey(it.id) == current.key }
        val to = working.indexOfFirst { pickKey(it.id) == target.key }
        if (from < 0 || to < 0) return
        working = working.toMutableList().apply { add(to, removeAt(from)) }
        dragOffset += current.offset - target.offset
    }

    val current = s.analyzing?.let { g -> avg?.let { it * (g.plies + 1) * (100 - g.progress) / 100.0 } }
    // Time until each pick is ready, counting the game in progress.
    val ready: Map<String, Double> = if (avg == null) emptyMap() else {
        var t = current ?: 0.0
        working.associate { g -> t += avg * (g.plies + 1); g.id to t }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.widthIn(max = 720.dp).fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (s.settings.analysisPaused) {
            item(key = "paused") {
                Banner(R.drawable.ic_pause, "Analysis is paused", "Nothing is analysed until you resume.") {
                    Button(onClick = vm::resume) { Text("Resume") }
                }
            }
        }
        if (!s.network.ready) {
            item(key = "network") {
                val pct = if (s.network.total > 0) " · ${(s.network.downloaded * 100 / s.network.total)}%" else ""
                Banner(
                    R.drawable.ic_cloud_download,
                    if (s.network.downloading) "Downloading the engine$pct" else "Waiting to download the engine",
                    "Analysis starts as soon as the engine's 40 MB network is on this phone.",
                )
            }
        }
        if (battery.low && !battery.charging) {
            item(key = "battery") {
                Banner(R.drawable.ic_info, "Battery low", "Android holds analysis back until the battery recovers or the phone is charging.")
            }
        }

        item(key = "now-title") { SectionHeader("Analysing now", Modifier.padding(top = 8.dp)) }
        item(key = "now") {
            val g = s.analyzing
            if (g == null) {
                Text(
                    if (s.settings.analysisPaused) "Paused." else "The next game starts in a moment.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            } else {
                AppCard(Modifier.fillMaxWidth(), onClick = { onOpenGame(g.id) }, contentPadding = PaddingValues(14.dp)) {
                    GameLine(g, trailing = "${g.progress}%")
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(progress = { g.progress / 100f }, Modifier.fillMaxWidth(), trackColor = AppTheme.colors.subtle, drawStopIndicator = {})
                    if (current != null && current > 0) {
                        Spacer(Modifier.height(6.dp))
                        Text("About ${Format.duration(current)} left", style = MaterialTheme.typography.labelSmall, color = AppTheme.colors.textMuted)
                    }
                }
            }
        }

        item(key = "picks-title") {
            Column(Modifier.padding(top = 12.dp)) {
                SectionHeader("Your picks", action = if (s.picks.isNotEmpty()) "Clear" else null, onAction = vm::clear)
                Text(
                    if (s.picks.isEmpty()) "Choose games to analyse before the rest: long-press a game in Games, select more, then tap Analyse first. Or add one below."
                    else "Analysed in this order, before everything else. Drag to reorder.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(working, key = { pickKey(it.id) }) { g ->
            val index = working.indexOf(g)
            val isDragged = dragging == g.id
            val eta = ready[g.id]?.let { "in ~${Format.duration(it)}" }
            PickRow(
                g = g,
                number = index + 1,
                eta = eta,
                canMoveUp = index > 0,
                canMoveDown = index < working.lastIndex,
                onOpen = { onOpenGame(g.id) },
                onTop = { vm.moveToTop(g.id) },
                onMove = { vm.moveBy(g.id, it) },
                onRemove = { vm.remove(g.id) },
                handle = Modifier.pointerInput(g.id) {
                    detectDragGestures(
                        onDragStart = { dragging = g.id; dragOffset = 0f },
                        onDragEnd = {
                            val latest = latestPicks.associateBy { it.id }
                            val ordered = working.mapNotNull { latest[it.id] }
                            working = ordered + latestPicks.filter { p -> ordered.none { it.id == p.id } }
                            dragging = null
                            dragOffset = 0f
                            val ids = working.map { it.id }
                            if (ids != latestPicks.map { it.id }) vm.reorder(ids)
                        },
                        onDragCancel = { dragging = null; dragOffset = 0f; working = latestPicks },
                        onDrag = { change, amount -> change.consume(); drag(amount.y) },
                    )
                },
                modifier = if (isDragged) Modifier.zIndex(1f).graphicsLayer { translationY = dragOffset } else Modifier.animateItem(),
                lifted = isDragged,
            )
        }

        if (s.upNext.isNotEmpty()) {
            item(key = "auto-title") {
                Column(Modifier.padding(top = 12.dp)) {
                    SectionHeader("Then automatically")
                    Text(
                        "Newest games first." + if (s.settings.backlogWhileChargingOnly) " Games older than 30 days wait for the charger; your picks don't." else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(s.upNext, key = { "auto-" + it.id }) { g ->
                Row(
                    Modifier.fillMaxWidth().animateItem().heightIn(min = 64.dp).padding(start = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f)) { GameLine(g, trailing = null) }
                    IconButton(onClick = { vm.add(g.id) }) {
                        AppIcon(R.drawable.ic_add, "Add ${g.opponent} to your picks", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            val more = s.queued - s.picks.size - s.upNext.size
            if (more > 0) {
                item(key = "more") {
                    Text(
                        "+ ${"%,d".format(more)} more waiting",
                        style = MaterialTheme.typography.labelMedium.numeric,
                        color = AppTheme.colors.textMuted,
                        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                    )
                }
            }
        }
    }
}

private const val PICK = "pick-"
private fun pickKey(id: String?) = PICK + id

@Composable
private fun Banner(icon: Int, title: String, body: String, action: (@Composable () -> Unit)? = null) {
    AppCard(Modifier.fillMaxWidth().padding(top = 8.dp), contentPadding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(icon, null, tint = MaterialTheme.colorScheme.primary, size = 22.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (action != null) {
                Spacer(Modifier.width(8.dp))
                action()
            }
        }
    }
}

@Composable
private fun GameLine(g: GameSummary, trailing: String?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("vs ${g.opponent}", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${Format.timeClass(g.timeClass)} · ${Format.date(g.endTime)} · ${(g.plies + 1) / 2} moves",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (trailing != null) Text(trailing, style = MaterialTheme.typography.labelLarge.numeric, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun PickRow(
    g: GameSummary,
    number: Int,
    eta: String?,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onOpen: () -> Unit,
    onTop: () -> Unit,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit,
    lifted: Boolean,
    modifier: Modifier = Modifier,
    handle: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    AppCard(
        modifier.fillMaxWidth().semantics {
            customActions = buildList {
                if (canMoveUp) add(CustomAccessibilityAction("Move up") { onMove(-1); true })
                if (canMoveDown) add(CustomAccessibilityAction("Move down") { onMove(1); true })
                add(CustomAccessibilityAction("Remove from picks") { onRemove(); true })
            }
        },
        onClick = onOpen,
        contentPadding = PaddingValues(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        color = if (lifted) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(handle.size(44.dp), contentAlignment = Alignment.Center) {
                AppIcon(R.drawable.ic_drag_indicator, "Drag to reorder", tint = AppTheme.colors.textMuted)
            }
            Box(
                Modifier.size(28.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Text("$number", style = MaterialTheme.typography.labelLarge.numeric, color = MaterialTheme.colorScheme.onPrimary)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                GameLine(g, trailing = null)
                if (eta != null) Text("Ready $eta", style = MaterialTheme.typography.labelSmall, color = AppTheme.colors.textMuted)
            }
            Box {
                IconButton(onClick = { menu = true }) { AppIcon(R.drawable.ic_more_vert, "Options for ${g.opponent}") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (canMoveUp) {
                        DropdownMenuItem(text = { Text("Move to top") }, onClick = { menu = false; onTop() })
                        DropdownMenuItem(text = { Text("Move up") }, onClick = { menu = false; onMove(-1) })
                    }
                    if (canMoveDown) DropdownMenuItem(text = { Text("Move down") }, onClick = { menu = false; onMove(1) })
                    DropdownMenuItem(text = { Text("Remove from picks") }, onClick = { menu = false; onRemove() })
                }
            }
        }
    }
}
