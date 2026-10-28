package app.aftercheck.ui.review

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.aftercheck.AppContainer
import app.aftercheck.R
import app.aftercheck.chess.Position
import app.aftercheck.chess.Side
import app.aftercheck.chess.analysis.MoveClass
import app.aftercheck.chess.analysis.Score
import app.aftercheck.chess.analysis.display
import app.aftercheck.chess.analysis.whiteWinChance
import app.aftercheck.ui.components.AccuracyRing
import app.aftercheck.ui.components.AppCard
import app.aftercheck.ui.components.AppIcon
import app.aftercheck.ui.components.BoardArrow
import app.aftercheck.ui.components.ChessBoard
import app.aftercheck.ui.components.ClassBadge
import app.aftercheck.ui.components.EmptyState
import app.aftercheck.ui.components.EvalBar
import app.aftercheck.ui.components.EvalGraph
import app.aftercheck.ui.components.Format
import app.aftercheck.ui.components.Monogram
import app.aftercheck.ui.components.Pill
import app.aftercheck.ui.components.Skeleton
import app.aftercheck.ui.components.SquareBadge
import app.aftercheck.ui.theme.AppTheme
import app.aftercheck.ui.theme.classColor
import app.aftercheck.ui.theme.numeric

private val BEST_ARROW = Color(0xD934B97A)
private val THREAT_ARROW = Color(0xD9E0703A)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(container: AppContainer, gameId: String, onOpenQueue: () -> Unit = {}, onBack: () -> Unit) {
    val vm: ReviewViewModel = viewModel(key = "review-$gameId") { ReviewViewModel(container, gameId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val data = state.data
    val tab = state.currentTab

    fun tick(action: () -> Unit) {
        if (state.settings.haptics) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
        action()
    }

    BackHandler(enabled = state.line != null) { vm.exitLine() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { AppIcon(R.drawable.ic_arrow_back, "Back") } },
                title = {
                    when {
                        data?.analysed == true -> TabSwitch(tab) { t -> tick { vm.selectTab(t) } }
                        data != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("vs ${data.game.opponent}", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${data.game.result.replace("1/2-1/2", "½-½")} · ${Format.timeClass(data.game.timeClass)} · ${Format.shortDate(data.game.endTime)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = AppTheme.colors.textMuted,
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { tick(vm::flip) }) { AppIcon(R.drawable.ic_swap_vert, "Flip board") }
                    Box {
                        IconButton(onClick = { menu = true }) { AppIcon(R.drawable.ic_more_vert, "More options") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            if (data?.line != null) {
                                DropdownMenuItem(
                                    text = { Text(if (state.playing) "Stop playing the game" else "Play through the game") },
                                    leadingIcon = { AppIcon(if (state.playing) R.drawable.ic_pause else R.drawable.ic_play_arrow, null, size = 20.dp) },
                                    onClick = { menu = false; vm.toggleReplay() },
                                )
                            }
                            if (data?.analysed == true) {
                                DropdownMenuItem(
                                    text = { Text("Analyse again") },
                                    leadingIcon = { AppIcon(R.drawable.ic_refresh, null, size = 20.dp) },
                                    onClick = { menu = false; vm.reanalyse() },
                                )
                            }
                            data?.game?.url?.let { url ->
                                DropdownMenuItem(
                                    text = { Text("Open on Chess.com") },
                                    leadingIcon = { AppIcon(R.drawable.ic_open_in_new, null, size = 20.dp) },
                                    onClick = { menu = false; context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) },
                                )
                            }
                            data?.game?.pgn?.let { pgn ->
                                DropdownMenuItem(
                                    text = { Text("Share PGN") },
                                    leadingIcon = { AppIcon(R.drawable.ic_share, null, size = 20.dp) },
                                    onClick = {
                                        menu = false
                                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, pgn)
                                        context.startActivity(Intent.createChooser(send, "Share PGN"))
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Delete game") },
                                leadingIcon = { AppIcon(R.drawable.ic_delete, null, size = 20.dp) },
                                onClick = { menu = false; confirmDelete = true },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            if (data?.line != null && tab == ReviewTab.MOVES) {
                NavBar(
                    state = state,
                    hasMistakes = data.userErrors.isNotEmpty(),
                    onFirst = { tick(vm::first) },
                    onPrevious = { tick(vm::previous) },
                    onNext = { tick(vm::next) },
                    onLast = { tick(vm::last) },
                    onMistake = { tick(vm::nextMistake) },
                    onExitLine = { tick(vm::exitLine) },
                    onToggleLine = { tick(vm::toggleLine) },
                    onReplayLine = { tick(vm::replayLine) },
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> LoadingReview()
                state.notFound || data == null -> EmptyState(R.drawable.ic_search, "Game not found", "It may have been deleted.")
                data.line == null -> EmptyState(R.drawable.ic_error, "Can't show this game", data.invalidReason ?: "The PGN is invalid.")
                tab == ReviewTab.REPORT -> ReportTab(data, onOpenMoves = { p -> tick { vm.openMoves(p) } })
                else -> MovesTab(state, data, vm, onOpenQueue, onTick = { if (state.settings.haptics) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick) })
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this game?") },
            text = { Text("The game, its review and practice positions are removed from this device. It won't be imported again unless you clear all data.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.delete(onBack) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TabSwitch(tab: ReviewTab, onSelect: (ReviewTab) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.widthIn(max = 220.dp)) {
        listOf(ReviewTab.MOVES to "Moves", ReviewTab.REPORT to "Report").forEachIndexed { i, (t, label) ->
            SegmentedButton(
                selected = tab == t,
                onClick = { onSelect(t) },
                shape = SegmentedButtonDefaults.itemShape(index = i, count = 2),
                icon = {},
            ) { Text(label, maxLines = 1) }
        }
    }
}

@Composable
private fun LoadingReview() {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Skeleton(Modifier.fillMaxWidth().height(28.dp))
        Skeleton(Modifier.fillMaxWidth().height(340.dp))
        Skeleton(Modifier.fillMaxWidth().height(110.dp))
    }
}

// --------------------------------------------------------------------------- moves tab

@Composable
private fun MovesTab(state: ReviewUiState, data: ReviewData, vm: ReviewViewModel, onOpenQueue: () -> Unit, onTick: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 600.dp && maxWidth > maxHeight * 1.05f
        val evalBar = if (data.analysed) 20.dp else 0.dp
        val panel: @Composable () -> Unit = { Panel(state, data, vm, onOpenQueue, onTick) }
        if (wide) {
            val boardSize = min(maxHeight - 88.dp, maxWidth * 0.55f)
            Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                BoardBlock(state, data, boardSize, vm)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) { panel() }
            }
        } else {
            // The coach card keeps at least ~140dp under the board; very short screens scroll it.
            val boardSize = min(maxWidth - 24.dp - evalBar, (maxHeight - 260.dp).coerceAtLeast(220.dp))
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { BoardBlock(state, data, boardSize, vm) }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) { panel() }
            }
        }
    }
}

@Composable
private fun BoardBlock(state: ReviewUiState, data: ReviewData, boardSize: Dp, vm: ReviewViewModel) {
    val game = data.line ?: return
    val ply = state.ply
    val line = state.line
    val move = data.move(ply)
    val position = line?.position ?: game.positions[ply]
    val lastMove = if (line != null) line.lastMove else game.moves.getOrNull(ply - 1)
    val arrows = when {
        line != null -> line.nextMove?.takeIf { !line.playing || line.index == 0 }
            ?.let { listOf(BoardArrow(it.from, it.to, if (line.kind == LineKind.PLAYED) THREAT_ARROW else BEST_ARROW)) }.orEmpty()
        move?.bestMove != null && move.hasBetter -> listOf(BoardArrow(move.bestMove.from, move.bestMove.to, BEST_ARROW))
        else -> emptyList()
    }
    val badge = if (line == null && move != null) SquareBadge(game.moves[ply - 1].to, classColor(move.moveClass), move.moveClass.symbol) else null
    val score: Score? = when {
        line != null -> line.score
        ply == 0 -> data.moves.firstOrNull()?.scoreBefore
        else -> move?.scoreAfter
    }
    val topSide = if (state.flipped) Side.WHITE else Side.BLACK
    val barWidth = if (data.analysed) 20.dp else 0.dp
    val accent = MaterialTheme.colorScheme.primary

    Column(Modifier.width(boardSize + barWidth).padding(vertical = 4.dp)) {
        PlayerStrip(data, topSide, ply, position, line == null, Modifier.padding(start = barWidth))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (data.analysed) {
                EvalBar(
                    whiteChance = score?.whiteWinChance()?.toFloat() ?: data.winChances.getOrNull(ply) ?: 0.5f,
                    label = score?.display() ?: "",
                    flipped = state.flipped,
                    modifier = Modifier.width(14.dp).height(boardSize),
                )
                Spacer(Modifier.width(6.dp))
            }
            ChessBoard(
                position = position,
                modifier = Modifier.size(boardSize)
                    .then(if (line != null) Modifier.border(3.dp, accent, RoundedCornerShape(6.dp)) else Modifier)
                    .pointerInput(Unit) {
                        var dx = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { dx = 0f },
                            onDragEnd = { if (dx < -SWIPE_PX) vm.next() else if (dx > SWIPE_PX) vm.previous() },
                        ) { change, amount ->
                            change.consume()
                            dx += amount
                        }
                    },
                flipped = state.flipped,
                lastMove = lastMove,
                animate = state.animate,
                arrows = arrows,
                badge = badge,
                showCoordinates = state.settings.showCoordinates,
                description = buildString {
                    append(if (position.sideToMove == Side.WHITE) "White to move" else "Black to move")
                    when {
                        line != null -> append(". ${line.title}, move ${line.index} of ${line.moves.size}")
                        move != null -> append(". Last move ${move.san}, ${move.moveClass.label}")
                    }
                },
            )
        }
        PlayerStrip(data, topSide.opposite, ply, position, line == null, Modifier.padding(start = barWidth))
    }
}

private const val SWIPE_PX = 120f

@Composable
private fun PlayerStrip(data: ReviewData, side: Side, ply: Int, position: Position, showClock: Boolean, modifier: Modifier = Modifier) {
    val g = data.game
    val name = if (side == Side.WHITE) g.white else g.black
    val rating = if (side == Side.WHITE) g.whiteRating else g.blackRating
    val accuracy = if (side == Side.WHITE) g.whiteAccuracy else g.blackAccuracy
    val line = data.line
    // Clock after this side's most recent move up to the selected ply.
    val clock = if (!showClock) null else (ply downTo 1).firstOrNull { p -> line?.moverOf(p) == side }?.let { data.clocks.getOrNull(it - 1) }
    val diff = position.material(side) - position.material(side.opposite)
    Row(modifier.height(36.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(20.dp).clip(RoundedCornerShape(5.dp))
                .background(if (side == Side.WHITE) AppTheme.colors.evalWhite else AppTheme.colors.evalBlack)
                .border(1.dp, AppTheme.colors.border, RoundedCornerShape(5.dp)),
        )
        Spacer(Modifier.width(8.dp))
        Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        rating?.let {
            Spacer(Modifier.width(6.dp))
            Text("($it)", style = MaterialTheme.typography.labelMedium.numeric, color = AppTheme.colors.textMuted)
        }
        if (diff > 0) {
            Spacer(Modifier.width(6.dp))
            Text("+$diff", style = MaterialTheme.typography.labelMedium.numeric, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.weight(1f))
        if (accuracy != null && data.analysed) {
            Pill("${Format.percent(accuracy)}%", color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.width(6.dp))
        }
        if (clock != null) {
            Text(
                Format.clock(clock),
                style = MaterialTheme.typography.labelLarge.numeric,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(AppTheme.colors.subtle).padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}

@Composable
private fun Panel(state: ReviewUiState, data: ReviewData, vm: ReviewViewModel, onOpenQueue: () -> Unit, onTick: () -> Unit) {
    val ply = state.ply
    val line = state.line
    Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (line != null) {
            LineCard(data, line, onJump = { i -> onTick(); vm.jumpInLine(i) })
            data.move(line.ply)?.let { m -> LineContext(data, m, line) }
            return@Column
        }
        MoveStrip(data, ply) { onTick(); vm.select(it) }
        when {
            !data.analysed -> PendingCard(data, ply, state.queueRank, state.settings.analysisPaused, vm::analyseNext, onOpenQueue)
            ply == 0 -> StartCard(data, onFirstMistake = { onTick(); vm.nextMistake() }, onStart = { onTick(); vm.next() })
            else -> data.move(ply)?.let { m -> CoachCard(data, m) { kind, alt -> onTick(); vm.showLine(kind, alt) } }
        }
        if (data.analysed && data.winChances.size == data.plyCount + 1) {
            EvalGraph(
                winChances = data.winChances,
                markers = data.moves.filter { it.moveClass.isSerious }.associate { it.ply to it.moveClass },
                selectedPly = ply,
                onSelect = { p -> if (p != state.ply) { onTick(); vm.select(p) } },
                height = 48.dp,
            )
        }
    }
}

private fun moveLabel(data: ReviewData, ply: Int): String {
    val line = data.line ?: return ""
    val n = line.moveNumberOf(ply)
    val san = line.sans[ply - 1]
    return if (line.moverOf(ply) == Side.WHITE) "$n. $san" else "$n… $san"
}

@Composable
private fun MoveStrip(data: ReviewData, ply: Int, onSelect: (Int) -> Unit) {
    val line = data.line ?: return
    val listState = rememberLazyListState()
    LaunchedEffect(ply) { listState.animateScrollToItem((ply - 2).coerceAtLeast(0)) }
    LazyRow(state = listState, horizontalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(horizontal = 2.dp)) {
        item { MoveChip("Start", null, selected = ply == 0) { onSelect(0) } }
        itemsIndexed(line.sans) { i, san ->
            val p = i + 1
            val number = line.moveNumberOf(p)
            val label = if (line.moverOf(p) == Side.WHITE) "$number. $san" else if (p == 1) "$number… $san" else san
            MoveChip(label, data.move(p)?.moveClass, selected = p == ply) { onSelect(p) }
        }
    }
}

@Composable
private fun MoveChip(label: String, c: MoveClass?, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(10.dp))
            .background(if (selected) scheme.primaryContainer else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) scheme.onPrimaryContainer else scheme.onSurface,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        )
        if (c != null && (c.isError || c == MoveClass.BEST && selected)) {
            Spacer(Modifier.width(4.dp))
            Text(c.symbol, style = MaterialTheme.typography.labelMedium, color = classColor(c), fontWeight = FontWeight.Bold)
        }
    }
}

private fun article(c: MoveClass) = when (c) {
    MoveClass.BEST -> "the best move"
    MoveClass.BOOK -> "a book move"
    MoveClass.EXCELLENT -> "excellent"
    MoveClass.GOOD -> "good"
    MoveClass.INACCURACY -> "an inaccuracy"
    MoveClass.MISTAKE -> "a mistake"
    MoveClass.BLUNDER -> "a blunder"
    MoveClass.MISS -> "a missed opportunity"
    MoveClass.MISSED_MATE -> "a missed mate"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CoachCard(data: ReviewData, m: ReviewMove, onLine: (LineKind, Int) -> Unit) {
    val line = data.line ?: return
    AppCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ClassBadge(m.moveClass, size = 28.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("${m.san} is ${article(m.moveClass)}", style = MaterialTheme.typography.titleMedium)
                Text(
                    listOfNotNull(
                        moveLabel(data, m.ply),
                        if (m.side == Side.WHITE) "White" else "Black",
                        m.timeSpentMillis?.takeIf { it > 0 }?.let { "took ${Format.seconds(it)}" },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = AppTheme.colors.textMuted,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(m.scoreAfter.display(), style = MaterialTheme.typography.titleMedium.numeric)
                Text("was ${m.scoreBefore.display()}", style = MaterialTheme.typography.labelSmall.numeric, color = AppTheme.colors.textMuted)
            }
        }
        if (m.hasBetter) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(R.drawable.ic_star_filled, null, tint = classColor(MoveClass.BEST), size = 18.dp)
                Spacer(Modifier.width(6.dp))
                Text("Best was ${m.bestSan}", style = MaterialTheme.typography.titleSmall, color = classColor(MoveClass.BEST))
            }
        }
        val text = coachText(m.explanation)
        if (text.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
        val canBest = m.hasBetter && m.bestLine.isNotEmpty()
        if (canBest || m.replyLine.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (canBest) {
                    Button(onClick = { onLine(LineKind.BEST, 0) }, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
                        AppIcon(R.drawable.ic_play_arrow, null, size = 18.dp, tint = MaterialTheme.colorScheme.onPrimary)
                        Spacer(Modifier.width(6.dp))
                        Text("Show best line")
                    }
                }
                if (m.replyLine.isNotEmpty()) {
                    val label = if (m.hasBetter) "After ${m.san}" else "Show follow-up"
                    if (canBest) {
                        OutlinedButton(onClick = { onLine(LineKind.PLAYED, 0) }, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
                            AppIcon(R.drawable.ic_play_arrow, null, size = 18.dp, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(6.dp))
                            Text(label)
                        }
                    } else {
                        FilledTonalButton(onClick = { onLine(LineKind.PLAYED, 0) }, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
                            AppIcon(R.drawable.ic_play_arrow, null, size = 18.dp, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                            Spacer(Modifier.width(6.dp))
                            Text(label)
                        }
                    }
                }
            }
        }
        if (m.alternatives.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text("Other options", style = MaterialTheme.typography.labelMedium, color = AppTheme.colors.textMuted)
            Spacer(Modifier.height(4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                m.alternatives.forEachIndexed { i, alt ->
                    val first = alt.san.firstOrNull() ?: return@forEachIndexed
                    Row(
                        Modifier.heightIn(min = 36.dp).clip(RoundedCornerShape(10.dp)).background(AppTheme.colors.subtle)
                            .clickable(onClickLabel = "Play this line") { onLine(LineKind.ALTERNATIVE, i) }.padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(first, style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.width(6.dp))
                        Text(alt.score.display(), style = MaterialTheme.typography.labelMedium.numeric, color = AppTheme.colors.textMuted)
                    }
                }
            }
        }
        if (m.themes.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                m.themes.forEach { Pill(it.label) }
            }
        }
        if (line.plyCount == m.ply) {
            Spacer(Modifier.height(10.dp))
            Text(
                "Final move${data.game.termination?.let { " · game ended by $it" } ?: ""}",
                style = MaterialTheme.typography.labelSmall,
                color = AppTheme.colors.textMuted,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LineCard(data: ReviewData, l: LinePlayback, onJump: (Int) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    AppCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(14.dp), color = scheme.primaryContainer.copy(alpha = 0.35f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(l.title, style = MaterialTheme.typography.titleMedium)
                val move = moveLabel(data, l.ply)
                Text(
                    when (l.kind) {
                        LineKind.PLAYED -> "How the game goes on after $move"
                        else -> "Instead of $move"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = AppTheme.colors.textMuted,
                )
            }
            l.score?.let { Text(it.display(), style = MaterialTheme.typography.titleMedium.numeric) }
        }
        Spacer(Modifier.height(10.dp))
        val labels = remember(l.start, l.sans) { numbered(l.start, l.sans) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            l.sans.forEachIndexed { i, san ->
                val label = labels[i]
                val shown = i < l.index
                val current = i == l.index - 1
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (current) FontWeight.SemiBold else FontWeight.Medium,
                    color = when {
                        current -> scheme.onPrimary
                        shown -> scheme.onSurface
                        else -> AppTheme.colors.textMuted
                    },
                    modifier = Modifier.heightIn(min = 36.dp).clip(RoundedCornerShape(8.dp))
                        .background(if (current) scheme.primary else Color.Transparent)
                        .clickable(onClickLabel = "Show the position after $san") { onJump(i + 1) }
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            when {
                l.finished -> "End of the engine's line · evaluation ${l.score?.display() ?: "–"}"
                l.playing -> "Playing the line… move ${l.index} of ${l.moves.size}"
                else -> "Paused · move ${l.index} of ${l.moves.size}"
            },
            style = MaterialTheme.typography.labelMedium,
            color = AppTheme.colors.textMuted,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

/** SAN moves with move numbers where they belong ("12. Nf3", "12… e5" for a line starting with Black). */
private fun numbered(start: Position, sans: List<String>): List<String> {
    var number = start.fullmoveNumber
    var white = start.sideToMove == Side.WHITE
    return sans.mapIndexed { i, san ->
        val label = when {
            white -> "$number. $san"
            i == 0 -> "$number… $san"
            else -> san
        }
        if (!white) number++
        white = !white
        label
    }
}

/** Keeps the verdict in view while a line plays. */
@Composable
private fun LineContext(data: ReviewData, m: ReviewMove, l: LinePlayback) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        ClassBadge(m.moveClass, size = 20.dp)
        Spacer(Modifier.width(8.dp))
        Text(
            buildString {
                append("${moveLabel(data, m.ply)} is ${article(m.moveClass)}")
                if (m.hasBetter && l.kind != LineKind.PLAYED) append(" · best was ${m.bestSan}")
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StartCard(data: ReviewData, onFirstMistake: () -> Unit, onStart: () -> Unit) {
    val g = data.game
    val errors = data.userErrors
    AppCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(14.dp)) {
        Text(g.openingName ?: "Start position", style = MaterialTheme.typography.titleMedium)
        Text(
            listOfNotNull(g.eco, "${(data.plyCount + 1) / 2} moves", g.termination?.let { "ended by $it" }).joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = AppTheme.colors.textMuted,
        )
        Spacer(Modifier.height(10.dp))
        Text(errorSummary(data, hint = errors.isNotEmpty()), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (errors.isNotEmpty()) {
                Button(onClick = onFirstMistake) { Text(if (data.userSide == null) "First mistake" else "Your first mistake") }
                OutlinedButton(onClick = onStart) { Text("Move 1") }
            } else {
                Button(onClick = onStart) { Text("Step through the game") }
            }
        }
    }
}

private fun errorSummary(data: ReviewData, hint: Boolean = false): String {
    val errors = data.userErrors
    val who = if (data.userSide == null) "This game has" else "You made"
    if (errors.isEmpty()) return if (data.userSide == null) "No inaccuracies or worse in this game." else "No inaccuracies or worse from you in this game."
    val parts = listOf(MoveClass.INACCURACY, MoveClass.MISTAKE, MoveClass.BLUNDER, MoveClass.MISS, MoveClass.MISSED_MATE).mapNotNull { c ->
        val n = errors.count { it.moveClass == c }
        if (n == 0) null else countOf(c, n)
    }
    val list = if (parts.size == 1) parts[0] else parts.dropLast(1).joinToString(", ") + " and " + parts.last()
    return "$who $list." + if (hint) " Tap Mistake below to jump between them." else ""
}

private fun countOf(c: MoveClass, n: Int): String {
    val word = c.label.lowercase()
    return "$n " + when {
        n == 1 -> word
        word.endsWith("y") -> word.dropLast(1) + "ies"
        else -> word + "s"
    }
}

@Composable
private fun PendingCard(data: ReviewData, ply: Int, queueRank: Int?, paused: Boolean, onAnalyse: () -> Unit, onOpenQueue: () -> Unit) {
    val g = data.game
    AppCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(14.dp)) {
        when (g.status) {
            "analyzing" -> {
                Text("Analysing this game · ${g.progress}%", style = MaterialTheme.typography.titleSmall)
                Text("The review appears here automatically when it's ready.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(progress = { g.progress / 100f }, Modifier.fillMaxWidth(), trackColor = AppTheme.colors.subtle, drawStopIndicator = {})
            }
            "failed" -> {
                Text("Analysis failed", style = MaterialTheme.typography.titleSmall)
                Text(g.statusMessage ?: "Something went wrong.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                Button(onClick = onAnalyse) { Text("Try again") }
            }
            "skipped" -> Text(g.statusMessage ?: "This game can't be analysed.", style = MaterialTheme.typography.bodyMedium)
            else -> {
                Text(
                    when (queueRank) {
                        null -> "Waiting to be analysed"
                        1 -> "Next in your queue"
                        else -> "#$queueRank in your queue"
                    },
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    when {
                        paused -> "Analysis is paused. Resume it from the queue."
                        queueRank == null -> "Games are analysed automatically, newest first. Move this one to the front to see its review sooner."
                        else -> "Games you pick are analysed before the rest."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (queueRank != 1) Button(onClick = onAnalyse) { Text("Analyse next") }
                    OutlinedButton(onClick = onOpenQueue) { Text("View queue") }
                }
            }
        }
        if (ply > 0) {
            Spacer(Modifier.height(10.dp))
            Text("Move ${data.line?.moveNumberOf(ply)}: ${data.line?.sans?.getOrNull(ply - 1)}", style = MaterialTheme.typography.labelMedium, color = AppTheme.colors.textMuted)
        }
    }
}

@Composable
private fun NavBar(
    state: ReviewUiState,
    hasMistakes: Boolean,
    onFirst: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onLast: () -> Unit,
    onMistake: () -> Unit,
    onExitLine: () -> Unit,
    onToggleLine: () -> Unit,
    onReplayLine: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.navigationBarsPadding()) {
            HorizontalDivider(color = AppTheme.colors.border)
            Row(Modifier.fillMaxWidth().height(68.dp).padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                val l = state.line
                if (l == null) {
                    NavItem(R.drawable.ic_first_page, "First", "First move", onFirst)
                    NavItem(R.drawable.ic_chevron_left, "Back", "Previous move", onPrevious)
                    NavItem(R.drawable.ic_chevron_right, "Next", "Next move", onNext, primary = true)
                    NavItem(R.drawable.ic_last_page, "Last", "Last move", onLast)
                    NavItem(R.drawable.ic_skip_next, "Mistake", "Next mistake", onMistake, enabled = hasMistakes)
                } else {
                    NavItem(R.drawable.ic_close, "Game", "Back to game", onExitLine)
                    NavItem(R.drawable.ic_chevron_left, "Back", "Previous move", onPrevious)
                    NavItem(
                        if (l.playing) R.drawable.ic_pause else R.drawable.ic_play_arrow,
                        if (l.playing) "Pause" else "Play",
                        if (l.playing) "Pause line" else "Play line",
                        onToggleLine,
                        primary = true,
                    )
                    NavItem(R.drawable.ic_chevron_right, "Next", "Next move", onNext)
                    NavItem(R.drawable.ic_replay, "Replay", "Replay line", onReplayLine)
                }
            }
        }
    }
}

@Composable
private fun RowScope.NavItem(icon: Int, label: String, description: String, onClick: () -> Unit, primary: Boolean = false, enabled: Boolean = true) {
    val scheme = MaterialTheme.colorScheme
    val tint = when {
        !enabled -> scheme.onSurface.copy(alpha = 0.35f)
        primary -> scheme.onPrimaryContainer
        else -> scheme.onSurface
    }
    Column(
        Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.width(56.dp).height(30.dp).clip(RoundedCornerShape(15.dp)).background(if (primary && enabled) scheme.primaryContainer else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) { AppIcon(icon, null, tint = tint, size = 24.dp) }
        Spacer(Modifier.height(2.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = if (enabled) scheme.onSurfaceVariant else tint)
    }
}

// --------------------------------------------------------------------------- report tab

private val CLASS_ORDER = listOf(
    MoveClass.BEST, MoveClass.EXCELLENT, MoveClass.GOOD, MoveClass.BOOK, MoveClass.INACCURACY,
    MoveClass.MISTAKE, MoveClass.BLUNDER, MoveClass.MISS, MoveClass.MISSED_MATE,
)

@Composable
private fun ReportTab(data: ReviewData, onOpenMoves: (Int) -> Unit) {
    val g = data.game
    val review = data.review ?: return
    val errors = data.userErrors
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            Modifier.widthIn(max = 720.dp).fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                AppCard(Modifier.fillMaxWidth()) {
                    Text(
                        "${g.result.replace("1/2-1/2", "½-½")}${g.termination?.let { " · $it" } ?: ""} · ${Format.timeClass(g.timeClass)} · ${Format.shortDate(g.endTime)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = AppTheme.colors.textMuted,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                        BigAccuracy(g.white, review.whiteAccuracy, data.userSide == Side.WHITE)
                        BigAccuracy(g.black, review.blackAccuracy, data.userSide == Side.BLACK)
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(errorSummary(data), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                        if (errors.isNotEmpty()) {
                            Button(onClick = { onOpenMoves(errors.first().ply) }) { Text(if (data.userSide == null) "Review mistakes" else "Review my mistakes") }
                            OutlinedButton(onClick = { onOpenMoves(0) }) { Text("From move 1") }
                        } else {
                            Button(onClick = { onOpenMoves(0) }) { Text("Step through the game") }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Estimated accuracy (AfterCheck, Lichess-style method) — not Chess.com's official score.",
                        style = MaterialTheme.typography.labelSmall,
                        color = AppTheme.colors.textMuted,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                    )
                }
            }
            if (data.winChances.size == data.plyCount + 1) {
                item {
                    AppCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(12.dp)) {
                        Text("Evaluation", style = MaterialTheme.typography.titleSmall)
                        Text("Tap the graph to open that move", style = MaterialTheme.typography.labelSmall, color = AppTheme.colors.textMuted)
                        Spacer(Modifier.height(8.dp))
                        EvalGraph(
                            winChances = data.winChances,
                            markers = data.moves.filter { it.moveClass.isSerious }.associate { it.ply to it.moveClass },
                            selectedPly = -1,
                            onSelect = onOpenMoves,
                            height = 88.dp,
                            scrub = false,
                        )
                    }
                }
            }
            item {
                AppCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 8.dp, horizontal = 12.dp)) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp, horizontal = 4.dp)) {
                        Text(g.white, style = MaterialTheme.typography.labelMedium, color = AppTheme.colors.textMuted, modifier = Modifier.width(72.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.weight(1f))
                        Text(g.black, style = MaterialTheme.typography.labelMedium, color = AppTheme.colors.textMuted, modifier = Modifier.width(72.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End)
                    }
                    CLASS_ORDER.forEach { c ->
                        val w = data.whiteCounts[c] ?: 0
                        val b = data.blackCounts[c] ?: 0
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(enabled = w + b > 0) {
                                (data.moves.firstOrNull { it.moveClass == c && (data.userSide == null || it.side == data.userSide) }
                                    ?: data.moves.firstOrNull { it.moveClass == c })?.let { onOpenMoves(it.ply) }
                            }.heightIn(min = 40.dp).padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("$w", style = MaterialTheme.typography.titleSmall.numeric, modifier = Modifier.width(40.dp))
                            Spacer(Modifier.weight(1f))
                            ClassBadge(c, size = 20.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(c.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(150.dp))
                            Spacer(Modifier.weight(1f))
                            Text("$b", style = MaterialTheme.typography.titleSmall.numeric, modifier = Modifier.width(40.dp), textAlign = TextAlign.End)
                        }
                    }
                }
            }
            val tps = data.turningPoints
            if (tps.isNotEmpty()) {
                item { Text("Key moments", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp)) }
                items(tps) { p ->
                    val m = data.move(p) ?: return@items
                    KeyMomentRow(data, m) { onOpenMoves(p) }
                }
            }
            val recs = data.recommendations
            if (recs.isNotEmpty()) {
                item { Text("What to work on", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp)) }
                items(recs) { r ->
                    AppCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(14.dp)) {
                        Row(verticalAlignment = Alignment.Top) {
                            AppIcon(R.drawable.ic_lightbulb, null, tint = MaterialTheme.colorScheme.tertiary, size = 20.dp)
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(r.theme.label, style = MaterialTheme.typography.titleSmall)
                                Text(r.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            item {
                AppCard(Modifier.fillMaxWidth()) {
                    review.biggestSwingPly?.let { p -> data.move(p)?.let { m -> Fact("Biggest swing", "${moveLabel(data, p)} · ${m.scoreBefore.display()} → ${m.scoreAfter.display()}") { onOpenMoves(p) } } }
                    review.strongestMovePly?.let { p -> Fact("Strongest move", moveLabel(data, p)) { onOpenMoves(p) } }
                    val critical = data.moves.filter { it.moveClass.isCritical }
                    Fact(
                        "Critical mistakes",
                        if (critical.isEmpty()) "None" else critical.joinToString(", ") { moveLabel(data, it.ply) },
                        critical.firstOrNull()?.let { first -> { onOpenMoves(first.ply) } },
                    )
                    Fact("Final evaluation", Score.parse(review.finalScore)?.display() ?: "–", null)
                    Fact("Opening", listOfNotNull(review.eco ?: g.eco, review.openingName ?: g.openingName).joinToString(" · ").ifEmpty { "Unknown" }, null)
                    if (review.bookPlies > 0) Fact("Book moves", "${review.bookPlies} plies", null)
                    Fact("Length", "${(data.plyCount + 1) / 2} moves · ${data.plyCount} plies", null)
                    Fact("Time control", "${Format.timeClass(g.timeClass)} · ${Format.timeControl(g.timeControl)}${if (g.rated) " · rated" else ""}", null)
                }
            }
            item {
                Text(
                    "${review.engineName} · ${describeKey(review.analysisKey)} · analysed in ${Format.duration(review.durationMillis.toDouble())}",
                    style = MaterialTheme.typography.labelSmall,
                    color = AppTheme.colors.textMuted,
                )
            }
        }
    }
}

private fun describeKey(key: String): String {
    val parts = key.split('-').associate { it.take(1) to it.drop(1) }
    val depth = parts["d"]
    val time = parts["t"]?.toLongOrNull()?.let { "%.1f s".format(java.util.Locale.US, it / 1000.0) }
    val lines = parts["m"]
    return listOfNotNull(depth?.let { "depth $it" }, time?.let { "≤ $it per move" }, lines?.let { "$it line${if (it == "1") "" else "s"}" }).joinToString(", ")
}

@Composable
private fun KeyMomentRow(data: ReviewData, m: ReviewMove, onClick: () -> Unit) {
    AppCard(Modifier.fillMaxWidth(), onClick = onClick, contentPadding = PaddingValues(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ClassBadge(m.moveClass, size = 22.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("${moveLabel(data, m.ply)} · ${m.moveClass.label}", style = MaterialTheme.typography.titleSmall)
                Text(coachText(m.explanation), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            Text("${m.scoreBefore.display()} → ${m.scoreAfter.display()}", style = MaterialTheme.typography.labelMedium.numeric)
        }
    }
}

@Composable
private fun Fact(label: String, value: String, onClick: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(120.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        if (onClick != null) AppIcon(R.drawable.ic_chevron_right, null, tint = AppTheme.colors.textMuted, size = 18.dp)
    }
}

@Composable
private fun BigAccuracy(name: String, value: Double?, isUser: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        AccuracyRing(value, size = 76.dp, stroke = 7.dp, color = if (isUser) MaterialTheme.colorScheme.primary else AppTheme.colors.draw)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Monogram(name, size = 18.dp)
            Spacer(Modifier.width(6.dp))
            Text(name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 110.dp))
        }
        Text(if (isUser) "You" else "Opponent", style = MaterialTheme.typography.labelSmall, color = AppTheme.colors.textMuted)
    }
}
