package app.aftercheck.ui.home

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.aftercheck.AppContainer
import app.aftercheck.R
import app.aftercheck.chess.analysis.MoveClass
import app.aftercheck.data.db.GameSummary
import app.aftercheck.data.db.LibraryCounts
import app.aftercheck.data.settings.AppSettings
import app.aftercheck.ui.components.AccuracyRing
import app.aftercheck.ui.components.AppCard
import app.aftercheck.ui.components.AppIcon
import app.aftercheck.ui.components.ClassBadge
import app.aftercheck.ui.components.Format
import app.aftercheck.ui.components.Monogram
import app.aftercheck.ui.components.Pill
import app.aftercheck.ui.components.ResultBadge
import app.aftercheck.ui.components.SectionHeader
import app.aftercheck.ui.components.Skeleton
import app.aftercheck.ui.components.Sparkline
import app.aftercheck.ui.components.StatTile
import app.aftercheck.ui.components.resultWord
import app.aftercheck.ui.theme.AppTheme
import app.aftercheck.ui.theme.numeric
import app.aftercheck.work.NetworkStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar
import kotlin.math.roundToInt

data class HomeState(
    val settings: AppSettings,
    val counts: LibraryCounts,
    val recent: List<GameSummary>,
    val reviewed: List<GameSummary>,
    val analyzing: GameSummary?,
    val pendingPositions: Long,
    val practiceDue: Int,
    val syncing: Boolean,
    val network: NetworkStatus,
    val archivesTotal: Int,
    val archivesFetched: Int,
)

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(private val c: AppContainer) : ViewModel() {
    private val games = c.db.games()
    private val archives = c.settings.settings.flatMapLatest { s ->
        val u = s.username?.lowercase() ?: return@flatMapLatest flowOf(0 to 0)
        combine(c.db.archives().observeTotal(u), c.db.archives().observeFetched(u)) { t, f -> t to f }
    }
    private val library = combine(games.counts(), games.recent(10), games.recentReviewed(20), games.analyzing(), games.pendingPositions()) { counts, recent, reviewed, analyzing, pending ->
        arrayOf(counts, recent, reviewed, analyzing, pending)
    }

    val state: StateFlow<HomeState?> = combine(
        c.settings.settings, library, c.db.practice().stats(System.currentTimeMillis()), c.workStatus.syncing,
        combine(c.workStatus.network, archives) { n, a -> n to a },
    ) { s, lib, practice, syncing, (network, arch) ->
        @Suppress("UNCHECKED_CAST")
        HomeState(
            settings = s,
            counts = lib[0] as LibraryCounts,
            recent = lib[1] as List<GameSummary>,
            reviewed = lib[2] as List<GameSummary>,
            analyzing = lib[3] as GameSummary?,
            pendingPositions = lib[4] as Long,
            practiceDue = practice.due,
            syncing = syncing,
            network = network,
            archivesTotal = arch.first,
            archivesFetched = arch.second,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun syncNow() = viewModelScope.launch { c.scheduler.syncNow() }
    fun retryDownload() = c.scheduler.downloadNetwork(replace = true)
    fun setPaused(paused: Boolean) = viewModelScope.launch {
        c.settings.setPaused(paused)
        if (paused) c.scheduler.cancelAnalysis() else c.scheduler.enqueueAnalysis()
    }
}

@Composable
fun HomeScreen(
    container: AppContainer,
    onOpenGame: (String) -> Unit,
    onOpenTab: (String) -> Unit,
    onPractice: () -> Unit,
) {
    val vm: HomeViewModel = viewModel { HomeViewModel(container) }
    val state by vm.state.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            Modifier.widthIn(max = 720.dp).fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item { Header(state, vm::syncNow, Modifier.statusBarsPadding()) }
            val s = state
            if (s == null) {
                item { HomeSkeleton() }
                return@LazyColumn
            }
            item { SetupBanner(s, vm::retryDownload, vm::syncNow) }
            val latest = s.recent.firstOrNull()
            if (latest == null) {
                item { ImportingPlaceholder(s) }
            } else {
                item { LatestGameCard(latest, onOpenGame) }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatTile("Imported", "%,d".format(s.counts.total), Modifier.weight(1f))
                        StatTile("Reviewed", "%,d".format(s.counts.ready), Modifier.weight(1f))
                        StatTile("Waiting", "%,d".format(s.counts.pending), Modifier.weight(1f))
                    }
                }
                if (s.counts.pending > 0) item { ContinueCard(s, onOpenGame, vm::setPaused) }
                if (s.reviewed.size >= 2) item { TrendCard(s.reviewed, onOpenTab) }
                item { RecentResults(s.recent, onOpenGame, onOpenTab) }
                if (s.practiceDue > 0) item { PracticeShortcut(s.practiceDue, onPractice) }
            }
        }
    }
}

/** The rotation only exists while a sync runs, so an idle Home screen draws no frames. */
@Composable
private fun SyncIcon(spinning: Boolean) {
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    if (spinning) {
        val rotation by rememberInfiniteTransition(label = "sync").animateFloat(
            0f, 360f, infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart), label = "spin",
        )
        AppIcon(R.drawable.ic_sync, "Syncing", Modifier.rotate(-rotation), tint = tint)
    } else {
        AppIcon(R.drawable.ic_sync, "Sync now", tint = tint)
    }
}

private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..11 -> "Good morning"
    in 12..17 -> "Good afternoon"
    else -> "Good evening"
}

@Composable
private fun Header(state: HomeState?, onSync: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(greeting(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                state?.settings?.username ?: " ",
                style = MaterialTheme.typography.headlineMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (state != null) {
                val s = state.settings
                val line = when {
                    state.syncing -> "Checking Chess.com for new games…"
                    s.lastSyncError != null -> "Last sync failed · ${Format.relative(s.lastSyncAttemptAt)}"
                    s.lastSyncAt != null -> "Synced ${Format.relative(s.lastSyncAt)}"
                    else -> "Waiting for first sync"
                }
                Text(line, style = MaterialTheme.typography.labelMedium, color = AppTheme.colors.textMuted)
            }
        }
        val spinning = state?.syncing == true
        IconButton(onClick = onSync, enabled = state != null && !spinning) {
            SyncIcon(spinning)
        }
        state?.settings?.username?.let { Monogram(it, size = 40.dp) }
    }
}

@Composable
private fun SetupBanner(s: HomeState, onRetryDownload: () -> Unit, onSync: () -> Unit) {
    val n = s.network
    val importing = !s.settings.historyComplete && s.archivesTotal > 0
    when {
        !n.ready -> AppCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(R.drawable.ic_memory, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Setting up the engine", style = MaterialTheme.typography.titleSmall)
                    Text(
                        when {
                            n.downloading -> "Downloading Stockfish network · ${Format.bytes(n.downloaded)} of ${Format.bytes(n.total)}"
                            n.failed -> "The download didn't finish. It resumes where it stopped."
                            n.notStarted -> "Reviews need a one-time 40 MB download of the Stockfish network."
                            else -> "Waiting for a connection to download 40 MB (once)."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (n.failed || n.notStarted) TextButton(onClick = onRetryDownload) { Text(if (n.failed) "Retry" else "Download") }
            }
            if (n.downloading || n.downloaded > 0) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(progress = { n.fraction }, modifier = Modifier.fillMaxWidth(), trackColor = AppTheme.colors.subtle, drawStopIndicator = {})
            }
        }
        s.settings.lastSyncError != null && !s.syncing -> AppCard(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(R.drawable.ic_error, null, tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Chess.com sync issue", style = MaterialTheme.typography.titleSmall)
                    Text(s.settings.lastSyncError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = onSync) { Text("Retry") }
            }
        }
        importing -> AppCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(R.drawable.ic_cloud_download, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Importing your history", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${s.archivesFetched} of ${s.archivesTotal} months · newest first",
                        style = MaterialTheme.typography.bodySmall.numeric,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { if (s.archivesTotal > 0) s.archivesFetched.toFloat() / s.archivesTotal else 0f },
                modifier = Modifier.fillMaxWidth(), trackColor = AppTheme.colors.subtle, drawStopIndicator = {},
            )
        }
        else -> Unit
    }
}

@Composable
private fun ImportingPlaceholder(s: HomeState) {
    AppCard(Modifier.fillMaxWidth()) {
        Text(
            if (s.syncing || s.settings.lastSyncAt == null) "Looking for your games…" else "No games yet",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            if (s.settings.lastSyncAt == null) "Your latest games will appear here first, then your full history."
            else "Finished games on Chess.com usually show up a few minutes after they end. Pull Sync any time.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        repeat(3) {
            Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Skeleton(Modifier.size(36.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Skeleton(Modifier.fillMaxWidth(0.6f).height(12.dp))
                    Spacer(Modifier.height(6.dp))
                    Skeleton(Modifier.fillMaxWidth(0.35f).height(10.dp))
                }
            }
        }
    }
}

@Composable
private fun HomeSkeleton() {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Skeleton(Modifier.fillMaxWidth().height(190.dp), shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            repeat(3) { Skeleton(Modifier.weight(1f).height(72.dp)) }
        }
        Skeleton(Modifier.fillMaxWidth().height(120.dp))
    }
}

@Composable
private fun LatestGameCard(game: GameSummary, onOpenGame: (String) -> Unit) {
    val ready = game.status == "ready"
    AppCard(Modifier.fillMaxWidth(), onClick = { onOpenGame(game.id) }, contentPadding = PaddingValues(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Latest game", style = MaterialTheme.typography.labelMedium, color = AppTheme.colors.textMuted, modifier = Modifier.weight(1f))
            Text(
                "${Format.timeClass(game.timeClass)} · ${Format.timeControl(game.timeControl)} · ${Format.relative(game.endTime)}",
                style = MaterialTheme.typography.labelMedium,
                color = AppTheme.colors.textMuted,
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Monogram(game.opponent, size = 46.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("vs ${game.opponent}", style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(game.opponentRating?.toString(), game.openingName).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            ResultBadge(game.userResult, size = 34.dp)
        }
        Spacer(Modifier.height(16.dp))
        if (ready) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AccuracyRing(game.userAccuracy, size = 60.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Your accuracy", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        "Opponent ${Format.percent(game.opponentAccuracy)}",
                        style = MaterialTheme.typography.bodySmall.numeric,
                        color = AppTheme.colors.textMuted,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CountChip(MoveClass.INACCURACY, game.userInaccuracies ?: 0)
                        CountChip(MoveClass.MISTAKE, game.userMistakes ?: 0)
                        CountChip(MoveClass.BLUNDER, game.userBlunders ?: 0)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = { onOpenGame(game.id) }, modifier = Modifier.fillMaxWidth().height(48.dp), shape = MaterialTheme.shapes.medium) {
                Text("Open review", style = MaterialTheme.typography.titleSmall)
            }
        } else {
            val (title, body) = when (game.status) {
                "analyzing" -> "Analysing now · ${game.progress}%" to "Stockfish is checking every move of this game."
                "queued" -> "Queued for review" to "It will be analysed automatically — newest games go first."
                "failed" -> "Analysis failed" to (game.statusMessage ?: "It will be retried.")
                else -> "Not analysed" to (game.statusMessage ?: "This game has no moves to review.")
            }
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (game.status == "analyzing") {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(progress = { game.progress / 100f }, Modifier.fillMaxWidth(), trackColor = AppTheme.colors.subtle, drawStopIndicator = {})
            }
        }
    }
}

@Composable
private fun CountChip(c: MoveClass, n: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ClassBadge(c, size = 18.dp)
        Spacer(Modifier.width(5.dp))
        Text("$n", style = MaterialTheme.typography.labelLarge.numeric)
    }
}

@Composable
private fun ContinueCard(s: HomeState, onOpenGame: (String) -> Unit, onPause: (Boolean) -> Unit) {
    val paused = s.settings.analysisPaused
    AppCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (paused) "Reviews paused" else "Continue reviewing", style = MaterialTheme.typography.titleSmall)
                val eta = s.settings.avgMillisPerPosition?.let { " · about ${Format.duration(it * s.pendingPositions)} left" }.orEmpty()
                Text(
                    "${"%,d".format(s.counts.pending)} game${if (s.counts.pending == 1) "" else "s"} waiting$eta",
                    style = MaterialTheme.typography.bodySmall.numeric,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FilledTonalButton(onClick = { onPause(!paused) }, contentPadding = PaddingValues(horizontal = 14.dp)) {
                AppIcon(if (paused) R.drawable.ic_play_arrow else R.drawable.ic_pause, null, size = 18.dp, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                Spacer(Modifier.width(6.dp))
                Text(if (paused) "Resume" else "Pause")
            }
        }
        s.analyzing?.let { g ->
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("vs ${g.opponent}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${g.progress}%", style = MaterialTheme.typography.labelLarge.numeric, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(progress = { g.progress / 100f }, Modifier.fillMaxWidth(), trackColor = AppTheme.colors.subtle, drawStopIndicator = {})
        }
        if (!s.network.ready) {
            Spacer(Modifier.height(8.dp))
            Text("Starts as soon as the engine network is downloaded.", style = MaterialTheme.typography.labelSmall, color = AppTheme.colors.textMuted)
        }
    }
}

@Composable
private fun TrendCard(reviewed: List<GameSummary>, onOpenTab: (String) -> Unit) {
    val values = reviewed.mapNotNull { it.userAccuracy?.toFloat() }.reversed()
    if (values.size < 2) return
    val recent = values.takeLast(5).average()
    val earlier = values.dropLast(5).takeIf { it.isNotEmpty() }?.average()
    AppCard(Modifier.fillMaxWidth(), onClick = { onOpenTab("insights") }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Accuracy, last ${values.size} reviews", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("${recent.roundToInt()}%", style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.width(8.dp))
                    if (earlier != null) {
                        val delta = recent - earlier
                        val up = delta >= 0
                        Text(
                            "${if (up) "▲" else "▼"} ${"%.1f".format(kotlin.math.abs(delta))} vs earlier",
                            style = MaterialTheme.typography.labelMedium.numeric,
                            color = if (up) AppTheme.colors.win else AppTheme.colors.loss,
                            modifier = Modifier.padding(bottom = 5.dp),
                        )
                    }
                }
                Text("Average of your last 5 reviewed games", style = MaterialTheme.typography.labelSmall, color = AppTheme.colors.textMuted)
            }
            Spacer(Modifier.width(12.dp))
            Sparkline(values, Modifier.width(120.dp).height(48.dp))
        }
    }
}

@Composable
private fun RecentResults(recent: List<GameSummary>, onOpenGame: (String) -> Unit, onOpenTab: (String) -> Unit) {
    Column {
        SectionHeader("Recent games", action = "See all", onAction = { onOpenTab("games") })
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(recent, key = { it.id }) { g ->
                AppCard(Modifier.width(150.dp), onClick = { onOpenGame(g.id) }, contentPadding = PaddingValues(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ResultBadge(g.userResult, size = 24.dp)
                        Spacer(Modifier.weight(1f))
                        if (g.status == "ready") Text(Format.percent(g.userAccuracy), style = MaterialTheme.typography.labelLarge.numeric)
                        else Pill(if (g.status == "analyzing") "${g.progress}%" else g.status.replaceFirstChar { it.uppercase() })
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(g.opponent, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${resultWord(g.userResult)} · ${Format.timeClass(g.timeClass)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = AppTheme.colors.textMuted,
                    )
                }
            }
        }
    }
}

@Composable
private fun PracticeShortcut(due: Int, onPractice: () -> Unit) {
    AppCard(Modifier.fillMaxWidth(), onClick = onPractice, color = MaterialTheme.colorScheme.primaryContainer) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(R.drawable.ic_target, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Practise your mistakes", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(
                    "$due position${if (due == 1) "" else "s"} from your games are due",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                )
            }
            AppIcon(R.drawable.ic_chevron_right, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}
