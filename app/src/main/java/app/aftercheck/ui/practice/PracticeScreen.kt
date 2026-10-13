package app.aftercheck.ui.practice

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.aftercheck.AppContainer
import app.aftercheck.R
import app.aftercheck.chess.Move
import app.aftercheck.chess.Position
import app.aftercheck.chess.Side
import app.aftercheck.chess.analysis.MoveClass
import app.aftercheck.chess.analysis.Theme
import app.aftercheck.chess.formatLine
import app.aftercheck.data.db.PracticeAttemptEntity
import app.aftercheck.data.db.PracticeEntity
import app.aftercheck.data.db.PracticeStats
import app.aftercheck.ui.components.AppCard
import app.aftercheck.ui.components.AppIcon
import app.aftercheck.ui.components.BoardArrow
import app.aftercheck.ui.components.ChessBoard
import app.aftercheck.ui.components.ClassBadge
import app.aftercheck.ui.components.EmptyState
import app.aftercheck.ui.components.Pill
import app.aftercheck.ui.components.SectionHeader
import app.aftercheck.ui.components.Skeleton
import app.aftercheck.ui.components.StatTile
import app.aftercheck.ui.theme.AppTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PracticeOverview(
    val stats: PracticeStats,
    val themes: List<Pair<Theme, Int>>,
    val recent: List<PracticeEntity>,
    val weekAttempts: List<PracticeAttemptEntity>,
)

class PracticeViewModel(c: AppContainer) : ViewModel() {
    private val now = System.currentTimeMillis()
    val state: StateFlow<PracticeOverview?> = combine(
        c.db.practice().stats(now),
        c.db.practice().themeCounts(),
        c.db.practice().recent(8),
        c.db.practice().attemptsSince(now - 7 * 86_400_000L),
    ) { stats, themeRows, recent, attempts ->
        val themes = themeRows.flatMap { row -> Theme.decode(row.themes).map { it to row.n } }
            .groupBy({ it.first }, { it.second }).mapValues { it.value.sum() }
            .entries.sortedByDescending { it.value }.map { it.key to it.value }
        PracticeOverview(stats, themes, recent, attempts)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PracticeScreen(container: AppContainer, onStart: (String, Boolean) -> Unit, onOpenGame: (String) -> Unit) {
    val vm: PracticeViewModel = viewModel { PracticeViewModel(container) }
    val state by vm.state.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            Modifier.widthIn(max = 720.dp).fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item { Text("Practice", style = MaterialTheme.typography.headlineMedium) }
            val s = state
            when {
                s == null -> item { Skeleton(Modifier.fillMaxWidth().height(200.dp)) }
                s.stats.total == 0 -> item {
                    EmptyState(
                        R.drawable.ic_target,
                        "Your training set starts here",
                        "Every mistake, blunder and missed chance from your reviewed games becomes a position to solve. They'll appear after your first reviews.",
                    )
                }
                else -> {
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            StatTile("Due now", "${s.stats.due}", Modifier.weight(1f))
                            StatTile("Mastered", "${s.stats.mastered}", Modifier.weight(1f), caption = "of ${s.stats.total}")
                            StatTile(
                                "Solved",
                                if (s.stats.attempts == 0) "–" else "${s.stats.correct * 100 / s.stats.attempts}%",
                                Modifier.weight(1f),
                                caption = "${s.stats.attempts} tries",
                            )
                        }
                    }
                    item {
                        AppCard(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primaryContainer) {
                            Text(
                                if (s.stats.due > 0) "${s.stats.due} position${if (s.stats.due == 1) "" else "s"} ready" else "All caught up",
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            Text(
                                if (s.stats.due > 0) "Positions you got wrong come back sooner; ones you solve are spaced out."
                                else "Nothing is due. You can still practise — the toughest positions come first.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
                            )
                            Spacer(Modifier.height(14.dp))
                            Button(onClick = { onStart("", false) }, modifier = Modifier.fillMaxWidth().height(48.dp), shape = MaterialTheme.shapes.medium) {
                                Text(if (s.stats.due > 0) "Start session" else "Practise anyway", style = MaterialTheme.typography.titleSmall)
                            }
                            val week = s.weekAttempts
                            if (week.isNotEmpty()) {
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    "This week: ${week.size} tried · ${week.count { it.correct }} solved",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                                )
                            }
                        }
                    }
                    if (s.themes.isNotEmpty() || s.stats.starred > 0) {
                        item {
                            SectionHeader("Train a theme")
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (s.stats.starred > 0) {
                                    FilterChip(
                                        selected = false,
                                        onClick = { onStart("", true) },
                                        label = { Text("Saved (${s.stats.starred})") },
                                        leadingIcon = { AppIcon(R.drawable.ic_star_filled, null, size = 16.dp, tint = MaterialTheme.colorScheme.tertiary) },
                                        shape = RoundedCornerShape(10.dp),
                                    )
                                }
                                s.themes.forEach { (theme, n) ->
                                    FilterChip(
                                        selected = false,
                                        onClick = { onStart(theme.name, false) },
                                        label = { Text("${theme.label} ($n)") },
                                        shape = RoundedCornerShape(10.dp),
                                        colors = FilterChipDefaults.filterChipColors(),
                                    )
                                }
                            }
                        }
                    }
                    item { SectionHeader("Recently added") }
                    items(s.recent, key = { it.id }) { p -> RecentPosition(p) { onOpenGame(p.gameId) } }
                }
            }
        }
    }
}

@Composable
private fun RecentPosition(p: PracticeEntity, onClick: () -> Unit) {
    val c = MoveClass.fromName(p.classification) ?: MoveClass.MISTAKE
    AppCard(Modifier.fillMaxWidth(), onClick = onClick, contentPadding = PaddingValues(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(56.dp)) {
                ChessBoard(Position.fromFen(p.fen), Modifier.size(56.dp), flipped = p.sideToMove == "b", showCoordinates = false)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ClassBadge(c, size = 16.dp)
                    Spacer(Modifier.width(6.dp))
                    Text("You played ${p.playedSan}", style = MaterialTheme.typography.titleSmall)
                }
                Text(
                    Theme.decode(p.themes).joinToString(" · ") { it.label }.ifEmpty { c.label },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    when {
                        p.attempts == 0 -> "Not tried yet"
                        p.lastCorrect == true -> "Solved last time · box ${p.box}"
                        else -> "Missed last time"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = AppTheme.colors.textMuted,
                )
            }
            if (p.starred) AppIcon(R.drawable.ic_star_filled, "Saved", tint = MaterialTheme.colorScheme.tertiary, size = 18.dp)
        }
    }
}

// ---------------------------------------------------------------------------- drill

data class DrillState(
    val loading: Boolean = true,
    val positions: List<PracticeEntity> = emptyList(),
    val index: Int = 0,
    val attempt: Move? = null,
    val correct: Boolean? = null,
    val solved: Int = 0,
    val finished: Boolean = false,
) {
    val current: PracticeEntity? get() = positions.getOrNull(index)
}

class DrillViewModel(private val c: AppContainer, theme: String, starredOnly: Boolean) : ViewModel() {
    val state = MutableStateFlow(DrillState())

    init {
        viewModelScope.launch {
            val list = c.db.practice().queue(System.currentTimeMillis(), theme, starredOnly, SESSION_SIZE)
            state.value = DrillState(loading = false, positions = list, finished = list.isEmpty())
        }
    }

    fun attempt(move: Move) {
        val s = state.value
        val p = s.current ?: return
        if (s.correct != null) return
        val correct = SpacedReview.isCorrect(p, move.uci)
        val now = System.currentTimeMillis()
        val updated = SpacedReview.schedule(p, correct, now)
        state.update {
            it.copy(
                attempt = move,
                correct = correct,
                solved = it.solved + if (correct) 1 else 0,
                positions = it.positions.toMutableList().also { l -> l[it.index] = updated },
            )
        }
        viewModelScope.launch {
            c.db.practice().update(updated)
            c.db.practice().insertAttempt(PracticeAttemptEntity(positionId = p.id, at = now, correct = correct))
        }
    }

    fun next() = state.update {
        if (it.index + 1 >= it.positions.size) it.copy(finished = true)
        else it.copy(index = it.index + 1, attempt = null, correct = null)
    }

    fun toggleStar() {
        val p = state.value.current ?: return
        val updated = p.copy(starred = !p.starred)
        state.update { it.copy(positions = it.positions.toMutableList().also { l -> l[it.index] = updated }) }
        viewModelScope.launch { c.db.practice().update(updated) }
    }

    companion object {
        const val SESSION_SIZE = 15
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DrillScreen(container: AppContainer, theme: String, starredOnly: Boolean, onBack: () -> Unit, onOpenGame: (String) -> Unit) {
    val vm: DrillViewModel = viewModel(key = "drill-$theme-$starredOnly") { DrillViewModel(container, theme, starredOnly) }
    val state by vm.state.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    val title = Theme.entries.firstOrNull { it.name == theme }?.label ?: if (starredOnly) "Saved positions" else "Practice"
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { AppIcon(R.drawable.ic_close, "Close practice") } },
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(title, style = MaterialTheme.typography.titleMedium)
                        if (state.positions.isNotEmpty() && !state.finished) {
                            Text("${state.index + 1} of ${state.positions.size}", style = MaterialTheme.typography.labelSmall, color = AppTheme.colors.textMuted)
                        }
                    }
                },
                actions = {
                    state.current?.let { p ->
                        IconButton(onClick = vm::toggleStar) {
                            AppIcon(if (p.starred) R.drawable.ic_star_filled else R.drawable.ic_star, if (p.starred) "Remove from saved" else "Save position",
                                tint = if (p.starred) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            val p = state.current
            when {
                state.loading -> Skeleton(Modifier.padding(16.dp).fillMaxWidth().height(360.dp))
                state.finished -> SessionDone(state, onBack)
                p != null -> Column(
                    Modifier.widthIn(max = 560.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding(),
                ) {
                    LinearProgressIndicator(
                        progress = { (state.index + if (state.correct != null) 1 else 0).toFloat() / state.positions.size },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        trackColor = AppTheme.colors.subtle,
                        drawStopIndicator = {},
                    )
                    val position = Position.fromFen(p.fen)
                    val solution = Move.fromUci(p.solutionUci)
                    val arrows = buildList {
                        if (state.correct == false) {
                            state.attempt?.let { add(BoardArrow(it.from, it.to, Color(0xCCE5484D))) }
                        }
                        if (state.correct != null && solution != null) add(BoardArrow(solution.from, solution.to, Color(0xD934B97A)))
                    }
                    ChessBoard(
                        position = if (state.correct == true && state.attempt != null) position.play(state.attempt!!) else position,
                        modifier = Modifier.fillMaxWidth(),
                        flipped = p.sideToMove == "b",
                        lastMove = if (state.correct == true) state.attempt else null,
                        animate = if (state.correct == true) state.attempt else null,
                        arrows = if (state.correct == true) emptyList() else arrows,
                        interactive = state.correct == null,
                        movableSide = if (p.sideToMove == "w") Side.WHITE else Side.BLACK,
                        onMove = { m ->
                            vm.attempt(m)
                            val ok = vm.state.value.correct == true
                            haptics.performHapticFeedback(if (ok) HapticFeedbackType.Confirm else HapticFeedbackType.Reject)
                        },
                        description = "Practice position, ${if (p.sideToMove == "w") "White" else "Black"} to move",
                    )
                    Spacer(Modifier.height(12.dp))
                    PromptCard(p, state, position, onOpenGame)
                    Spacer(Modifier.height(12.dp))
                    AnimatedVisibility(state.correct != null) {
                        Button(onClick = vm::next, modifier = Modifier.fillMaxWidth().height(50.dp), shape = MaterialTheme.shapes.medium) {
                            Text(if (state.index + 1 >= state.positions.size) "Finish" else "Next position", style = MaterialTheme.typography.titleSmall)
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PromptCard(p: PracticeEntity, state: DrillState, position: Position, onOpenGame: (String) -> Unit) {
    val c = MoveClass.fromName(p.classification) ?: MoveClass.MISTAKE
    val sideName = if (p.sideToMove == "w") "White" else "Black"
    AppCard(Modifier.fillMaxWidth()) {
        when (state.correct) {
            null -> {
                Text("Find the best move for $sideName", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ClassBadge(c, size = 18.dp)
                    Spacer(Modifier.width(6.dp))
                    Text("In your game you played ${p.playedSan} — ${c.label.lowercase()}.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val themes = Theme.decode(p.themes)
                if (themes.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { themes.forEach { Pill(it.label) } }
                }
                Spacer(Modifier.height(8.dp))
                Text("Tap or drag a piece to answer.", style = MaterialTheme.typography.labelSmall, color = AppTheme.colors.textMuted)
            }
            true -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(R.drawable.ic_check_circle, null, tint = AppTheme.colors.win)
                    Spacer(Modifier.width(8.dp))
                    Text("Correct — ${state.attempt?.let { if (it.uci == p.solutionUci) p.solutionSan else "an equally good move" }}", style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.height(8.dp))
                SolutionText(p, position)
            }
            false -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(R.drawable.ic_error, null, tint = AppTheme.colors.loss)
                    Spacer(Modifier.width(8.dp))
                    Text("Not quite — the best move was ${p.solutionSan}", style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.height(8.dp))
                SolutionText(p, position)
            }
        }
        if (state.correct != null) {
            TextButton(onClick = { onOpenGame(p.gameId) }, contentPadding = PaddingValues(0.dp)) { Text("Open the game") }
        }
    }
}

@Composable
private fun SolutionText(p: PracticeEntity, position: Position) {
    val line = p.solutionLine.split(' ').filter { it.isNotEmpty() }
    if (line.isNotEmpty()) {
        Text("Best continuation", style = MaterialTheme.typography.labelMedium, color = AppTheme.colors.textMuted)
        Text(formatLine(position, line), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
    }
    Text("What happened in your game", style = MaterialTheme.typography.labelMedium, color = AppTheme.colors.textMuted)
    Text(p.explanation, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun SessionDone(state: DrillState, onBack: () -> Unit) {
    if (state.positions.isEmpty()) {
        EmptyState(R.drawable.ic_target, "Nothing to practise here", "Positions appear after games with mistakes are reviewed.") {
            OutlinedButton(onClick = onBack) { Text("Back") }
        }
        return
    }
    EmptyState(
        R.drawable.ic_emoji_events,
        "Session complete",
        "You solved ${state.solved} of ${state.positions.size}. Missed positions will come back soon; solved ones are spaced out.",
    ) {
        Button(onClick = onBack) { Text("Done") }
    }
}
