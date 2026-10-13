package app.aftercheck.ui.review

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.aftercheck.AppContainer
import app.aftercheck.R
import app.aftercheck.chess.Move
import app.aftercheck.chess.Side
import app.aftercheck.chess.analysis.MoveClass
import app.aftercheck.chess.analysis.Score
import app.aftercheck.chess.analysis.display
import app.aftercheck.chess.analysis.whiteWinChance
import app.aftercheck.chess.formatLine
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
import app.aftercheck.ui.components.LabeledDivider
import app.aftercheck.ui.components.Monogram
import app.aftercheck.ui.components.Pill
import app.aftercheck.ui.components.Skeleton
import app.aftercheck.ui.components.SquareBadge
import app.aftercheck.ui.theme.AppTheme
import app.aftercheck.ui.theme.classColor
import app.aftercheck.ui.theme.numeric

private enum class Sheet { SUMMARY, MOVES }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(container: AppContainer, gameId: String, onBack: () -> Unit) {
    val vm: ReviewViewModel = viewModel(key = "review-$gameId") { ReviewViewModel(container, gameId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    val context = LocalContext.current
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val data = state.data

    fun tick(action: () -> Unit) {
        if (state.settings.haptics) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
        action()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { AppIcon(R.drawable.ic_arrow_back, "Back") } },
                title = {
                    if (data != null) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
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
            if (data?.line != null) {
                Controls(
                    state = state,
                    onSummary = { sheet = Sheet.SUMMARY },
                    onMoves = { sheet = Sheet.MOVES },
                    onFirst = { tick(vm::first) },
                    onPrevious = { tick(vm::previous) },
                    onPlay = { tick(vm::toggleReplay) },
                    onNext = { tick(vm::next) },
                    onLast = { tick(vm::last) },
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> LoadingReview()
                state.notFound || data == null -> EmptyState(R.drawable.ic_search, "Game not found", "It may have been deleted.")
                data.line == null -> EmptyState(R.drawable.ic_error, "Can't show this game", data.invalidReason ?: "The PGN is invalid.")
                else -> ReviewBody(state, data, vm, onTick = { if (state.settings.haptics) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick) })
            }
        }
    }

    if (sheet != null && data?.line != null) {
        ModalBottomSheet(
            onDismissRequest = { sheet = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            when (sheet) {
                Sheet.SUMMARY -> SummarySheet(data, onJump = { vm.select(it); sheet = null }, onAnalyse = vm::analyseNow)
                Sheet.MOVES -> MovesSheet(data, state.ply, onJump = { vm.select(it); sheet = null })
                null -> Unit
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

@Composable
private fun LoadingReview() {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Skeleton(Modifier.fillMaxWidth().height(28.dp))
        Skeleton(Modifier.fillMaxWidth().height(340.dp))
        Skeleton(Modifier.fillMaxWidth().height(110.dp))
    }
}

@Composable
private fun ReviewBody(state: ReviewUiState, data: ReviewData, vm: ReviewViewModel, onTick: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 600.dp && maxWidth > maxHeight * 1.05f
        if (wide) {
            val boardSize = min(maxHeight - 96.dp, maxWidth * 0.55f)
            Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                BoardBlock(state, data, boardSize, vm)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    Panel(state, data, vm, onTick)
                }
            }
        } else {
            // Leave room for the coach panel on short phones by capping the board.
            val evalBar = if (data.analysed) 20.dp else 0.dp
            val boardSize = min(maxWidth - 24.dp - evalBar, (maxHeight - 290.dp).coerceAtLeast(240.dp))
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { BoardBlock(state, data, boardSize, vm) }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
                    Panel(state, data, vm, onTick)
                }
            }
        }
    }
}

@Composable
private fun BoardBlock(state: ReviewUiState, data: ReviewData, boardSize: Dp, vm: ReviewViewModel) {
    val line = data.line ?: return
    val ply = state.ply
    val variation = state.variation
    val move = data.move(ply)
    val position = variation?.position ?: line.positions[ply]
    val lastMove = if (variation != null) variation.lastMove else line.moves.getOrNull(ply - 1)
    val animate: Move? = when {
        variation != null -> variation.lastMove
        ply == state.previousPly + 1 -> line.moves[ply - 1]
        ply == state.previousPly - 1 -> line.moves[ply].let { Move(it.to, it.from) }
        else -> null
    }
    val arrows = if (variation == null && move != null && move.bestMove != null &&
        move.moveClass != MoveClass.BEST && move.moveClass != MoveClass.BOOK
    ) listOf(BoardArrow(move.bestMove.from, move.bestMove.to, Color(0xD934B97A))) else emptyList()
    val badge = if (variation == null && move != null) {
        SquareBadge(line.moves[ply - 1].to, classColor(move.moveClass), move.moveClass.symbol)
    } else null
    val score: Score? = when {
        variation != null -> null
        ply == 0 -> data.moves.firstOrNull()?.scoreBefore
        else -> move?.scoreAfter
    }
    val topSide = if (state.flipped) Side.WHITE else Side.BLACK
    val barWidth = if (data.analysed) 20.dp else 0.dp

    Column(Modifier.width(boardSize + barWidth).padding(vertical = 4.dp)) {
        PlayerStrip(data, topSide, ply, Modifier.padding(start = if (data.analysed) 20.dp else 0.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (data.analysed) {
                val fallback = data.winChances.getOrNull(ply) ?: 0.5f
                EvalBar(
                    whiteChance = score?.whiteWinChance()?.toFloat() ?: fallback,
                    label = score?.display() ?: "",
                    flipped = state.flipped,
                    modifier = Modifier.width(14.dp).height(boardSize),
                )
                Spacer(Modifier.width(6.dp))
            }
            ChessBoard(
                position = position,
                modifier = Modifier.size(boardSize),
                flipped = state.flipped,
                lastMove = lastMove,
                animate = animate,
                arrows = arrows,
                badge = badge,
                showCoordinates = state.settings.showCoordinates,
                description = buildString {
                    append(if (position.sideToMove == Side.WHITE) "White to move" else "Black to move")
                    if (move != null) append(". Last move ${move.san}, ${move.moveClass.label}")
                },
            )
        }
        PlayerStrip(data, topSide.opposite, ply, Modifier.padding(start = if (data.analysed) 20.dp else 0.dp))
    }
}

@Composable
private fun PlayerStrip(data: ReviewData, side: Side, ply: Int, modifier: Modifier = Modifier) {
    val g = data.game
    val name = if (side == Side.WHITE) g.white else g.black
    val rating = if (side == Side.WHITE) g.whiteRating else g.blackRating
    val accuracy = if (side == Side.WHITE) g.whiteAccuracy else g.blackAccuracy
    val line = data.line
    // Clock after this side's most recent move up to the selected ply.
    val clock = (ply downTo 1).firstOrNull { p -> line?.moverOf(p) == side }?.let { data.clocks.getOrNull(it - 1) }
    val position = line?.positions?.getOrNull(ply)
    val diff = position?.let { it.material(side) - it.material(side.opposite) } ?: 0
    Row(modifier.height(38.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(22.dp).clip(RoundedCornerShape(5.dp))
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
private fun Panel(state: ReviewUiState, data: ReviewData, vm: ReviewViewModel, onTick: () -> Unit) {
    val ply = state.ply
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (data.analysed && data.winChances.size == data.plyCount + 1) {
            EvalGraph(
                winChances = data.winChances,
                markers = data.moves.filter { it.moveClass.isSerious }.associate { it.ply to it.moveClass },
                selectedPly = ply,
                onSelect = { p -> if (p != state.ply) { onTick(); vm.select(p) } },
                height = 52.dp,
            )
        }
        MoveStrip(data, ply) { onTick(); vm.select(it) }
        val variation = state.variation
        when {
            variation != null -> VariationCard(variation, onExit = vm::exitVariation)
            !data.analysed -> PendingCard(data, ply, vm::analyseNow)
            ply == 0 -> StartCard(data)
            else -> data.move(ply)?.let { CoachCard(data, it, vm) }
        }
    }
}

@Composable
private fun MoveStrip(data: ReviewData, ply: Int, onSelect: (Int) -> Unit) {
    val line = data.line ?: return
    val listState = rememberLazyListState()
    LaunchedEffect(ply) { listState.animateScrollToItem((ply - 2).coerceAtLeast(0)) }
    LazyRow(state = listState, horizontalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(horizontal = 2.dp)) {
        item {
            MoveChip("Start", null, selected = ply == 0) { onSelect(0) }
        }
        itemsIndexed(line.sans) { i, san ->
            val p = i + 1
            val mover = line.moverOf(p)
            val number = line.moveNumberOf(p)
            val label = if (mover == Side.WHITE) "$number. $san" else if (p == 1) "$number… $san" else san
            MoveChip(label, data.move(p)?.moveClass, selected = p == ply) { onSelect(p) }
        }
    }
}

@Composable
private fun MoveChip(label: String, c: MoveClass?, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.heightIn(min = 36.dp).clip(RoundedCornerShape(8.dp))
            .background(if (selected) scheme.primaryContainer else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (c != null && c.isError) {
            Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(classColor(c)))
            Spacer(Modifier.width(5.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) scheme.onPrimaryContainer else scheme.onSurface,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        )
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
private fun CoachCard(data: ReviewData, m: ReviewMove, vm: ReviewViewModel) {
    val line = data.line ?: return
    val color = classColor(m.moveClass)
    AppCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ClassBadge(m.moveClass, size = 26.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "${m.san} is ${article(m.moveClass)}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "Move ${line.moveNumberOf(m.ply)} · ${if (m.side == Side.WHITE) "White" else "Black"}" +
                        (m.timeSpentMillis?.let { " · took ${Format.seconds(it)}" } ?: ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = AppTheme.colors.textMuted,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(m.scoreAfter.display(), style = MaterialTheme.typography.titleMedium.numeric)
                Text("was ${m.scoreBefore.display()}", style = MaterialTheme.typography.labelSmall.numeric, color = AppTheme.colors.textMuted)
            }
        }
        Box(Modifier.padding(top = 10.dp).fillMaxWidth().height(2.dp).clip(RoundedCornerShape(1.dp)).background(color.copy(alpha = 0.35f)))
        Spacer(Modifier.height(10.dp))
        Text(m.explanation, style = MaterialTheme.typography.bodyMedium)

        val showBest = m.bestSan != null && m.moveClass != MoveClass.BEST && m.moveClass != MoveClass.BOOK
        if (showBest || m.bestLine.size > 1) {
            Spacer(Modifier.height(12.dp))
            LabeledDivider("Engine lines")
            Spacer(Modifier.height(8.dp))
            if (m.bestLine.isNotEmpty()) {
                LineRow(
                    title = if (showBest) "Best · ${m.bestSan}" else "Best line",
                    score = m.scoreBefore,
                    text = formatLine(line.positions[m.ply - 1], m.bestLine),
                    accent = MaterialTheme.colorScheme.primary,
                ) { vm.playLine("Best line", m.bestLine, fromBefore = true) }
            }
            m.alternatives.forEachIndexed { i, alt ->
                LineRow("Alternative ${i + 1}", alt.score, formatLine(line.positions[m.ply - 1], alt.san), AppTheme.colors.textMuted) {
                    vm.playLine("Alternative ${i + 1}", alt.san, fromBefore = true)
                }
            }
            if (m.replyLine.isNotEmpty()) {
                LineRow("After ${m.san}", m.scoreAfter, formatLine(line.positions[m.ply], m.replyLine), AppTheme.colors.textMuted) {
                    vm.playLine("After ${m.san}", m.replyLine, fromBefore = false)
                }
            }
        }
        if (m.themes.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                m.themes.forEach { Pill(it.label) }
            }
        }
    }
}

@Composable
private fun LineRow(title: String, score: Score, text: String, accent: Color, onPlay: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onPlay).padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            score.display(),
            style = MaterialTheme.typography.labelLarge.numeric,
            modifier = Modifier.width(52.dp).clip(RoundedCornerShape(6.dp)).background(AppTheme.colors.subtle).padding(vertical = 3.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = accent)
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        AppIcon(R.drawable.ic_play_arrow, "Play line", tint = MaterialTheme.colorScheme.onSurfaceVariant, size = 20.dp)
    }
}

@Composable
private fun VariationCard(v: Variation, onExit: () -> Unit) {
    AppCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(14.dp), color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Exploring: ${v.title}", style = MaterialTheme.typography.titleSmall)
                Text("Move ${v.index} of ${v.moves.size} · use the arrows to step", style = MaterialTheme.typography.labelSmall, color = AppTheme.colors.textMuted)
            }
            OutlinedButton(onClick = onExit) { Text("Back to game") }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            formatLine(v.start, v.sans.take(v.index)) + if (v.index < v.sans.size) "  …" else "",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun StartCard(data: ReviewData) {
    val g = data.game
    val user = data.userSide
    AppCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(14.dp)) {
        Text(g.openingName ?: "Start position", style = MaterialTheme.typography.titleMedium)
        Text(
            listOfNotNull(g.eco, "${(data.plyCount + 1) / 2} moves", g.termination?.let { "ended by $it" }).joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = AppTheme.colors.textMuted,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            SideAccuracy(g.white, g.whiteAccuracy, user == Side.WHITE)
            SideAccuracy(g.black, g.blackAccuracy, user == Side.BLACK)
        }
        Spacer(Modifier.height(12.dp))
        val userCounts = if (user == Side.BLACK) data.blackCounts else data.whiteCounts
        val serious = listOf(MoveClass.MISS, MoveClass.MISSED_MATE, MoveClass.BLUNDER, MoveClass.MISTAKE, MoveClass.INACCURACY)
            .filter { (userCounts[it] ?: 0) > 0 }
        Text(
            if (serious.isEmpty()) "No errors from you in this game — step through to see the best moves."
            else "Step through the game, or tap a marker on the graph to jump to a key moment.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SideAccuracy(name: String, accuracy: Double?, isUser: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AccuracyRing(accuracy, size = 48.dp, stroke = 5.dp, color = if (isUser) MaterialTheme.colorScheme.primary else AppTheme.colors.draw)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(96.dp))
            Text(if (isUser) "You" else "Opponent", style = MaterialTheme.typography.labelSmall, color = AppTheme.colors.textMuted)
        }
    }
}

@Composable
private fun PendingCard(data: ReviewData, ply: Int, onAnalyse: () -> Unit) {
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
                Text("Waiting in the review queue", style = MaterialTheme.typography.titleSmall)
                Text("Games are analysed automatically, newest first. You can move this one to the front.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                Button(onClick = onAnalyse) { Text("Analyse next") }
            }
        }
        if (ply > 0) {
            Spacer(Modifier.height(10.dp))
            Text("Move ${data.line?.moveNumberOf(ply)}: ${data.line?.sans?.getOrNull(ply - 1)}", style = MaterialTheme.typography.labelMedium, color = AppTheme.colors.textMuted)
        }
    }
}

@Composable
private fun Controls(
    state: ReviewUiState,
    onSummary: () -> Unit,
    onMoves: () -> Unit,
    onFirst: () -> Unit,
    onPrevious: () -> Unit,
    onPlay: () -> Unit,
    onNext: () -> Unit,
    onLast: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.navigationBarsPadding()) {
            HorizontalDivider(color = AppTheme.colors.border)
            Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onSummary) {
                    AppIcon(R.drawable.ic_insights, null, size = 18.dp, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(4.dp))
                    Text("Summary")
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onFirst) { AppIcon(R.drawable.ic_first_page, "First move") }
                IconButton(onClick = onPrevious) { AppIcon(R.drawable.ic_chevron_left, "Previous move", size = 30.dp) }
                IconButton(onClick = onPlay) { AppIcon(if (state.playing) R.drawable.ic_pause else R.drawable.ic_play_arrow, if (state.playing) "Pause replay" else "Replay game") }
                IconButton(onClick = onNext) { AppIcon(R.drawable.ic_chevron_right, "Next move", size = 30.dp) }
                IconButton(onClick = onLast) { AppIcon(R.drawable.ic_last_page, "Last move") }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onMoves) { AppIcon(R.drawable.ic_menu_book, "All moves") }
            }
        }
    }
}

// --------------------------------------------------------------------------- sheets

private val CLASS_ORDER = listOf(
    MoveClass.BEST, MoveClass.EXCELLENT, MoveClass.GOOD, MoveClass.BOOK, MoveClass.INACCURACY,
    MoveClass.MISTAKE, MoveClass.BLUNDER, MoveClass.MISS, MoveClass.MISSED_MATE,
)

@Composable
private fun SummarySheet(data: ReviewData, onJump: (Int) -> Unit, onAnalyse: () -> Unit) {
    val g = data.game
    val review = data.review
    LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Text("Game summary", style = MaterialTheme.typography.headlineSmall)
            Text(
                "${g.white} vs ${g.black} · ${g.result.replace("1/2-1/2", "½-½")}${g.termination?.let { " by $it" } ?: ""}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (review == null) {
            item {
                AppCard(Modifier.fillMaxWidth()) {
                    Text("This game hasn't been analysed yet.", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onAnalyse) { Text("Analyse next") }
                }
            }
            return@LazyColumn
        }
        item {
            AppCard(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                    BigAccuracy(g.white, review.whiteAccuracy, data.userSide == Side.WHITE)
                    BigAccuracy(g.black, review.blackAccuracy, data.userSide == Side.BLACK)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Estimated accuracy (AfterCheck, Lichess-style method) — not Chess.com's official score.",
                    style = MaterialTheme.typography.labelSmall,
                    color = AppTheme.colors.textMuted,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
        item {
            AppCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 8.dp, horizontal = 12.dp)) {
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp, horizontal = 4.dp)) {
                    Text(g.white, style = MaterialTheme.typography.labelMedium, color = AppTheme.colors.textMuted, modifier = Modifier.width(72.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.weight(1f))
                    Text(g.black, style = MaterialTheme.typography.labelMedium, color = AppTheme.colors.textMuted, modifier = Modifier.width(72.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.End)
                }
                CLASS_ORDER.forEach { c ->
                    val w = data.whiteCounts[c] ?: 0
                    val b = data.blackCounts[c] ?: 0
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(enabled = w + b > 0) {
                            data.moves.firstOrNull { it.moveClass == c && (data.userSide == null || it.side == data.userSide) }
                                ?.let { onJump(it.ply) }
                                ?: data.moves.firstOrNull { it.moveClass == c }?.let { onJump(it.ply) }
                        }.heightIn(min = 40.dp).padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("$w", style = MaterialTheme.typography.titleSmall.numeric, modifier = Modifier.width(40.dp))
                        Spacer(Modifier.weight(1f))
                        ClassBadge(c, size = 20.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(c.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(150.dp))
                        Spacer(Modifier.weight(1f))
                        Text("$b", style = MaterialTheme.typography.titleSmall.numeric, modifier = Modifier.width(40.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                    }
                }
            }
        }
        val tps = data.turningPoints
        if (tps.isNotEmpty()) {
            item { Text("Key moments", style = MaterialTheme.typography.titleMedium) }
            items(tps) { p ->
                val m = data.move(p) ?: return@items
                KeyMomentRow(data, m) { onJump(p) }
            }
        }
        item {
            AppCard(Modifier.fillMaxWidth()) {
                review.biggestSwingPly?.let { p -> data.move(p)?.let { m -> Fact("Biggest swing", "${moveLabel(data, p)} · ${m.scoreBefore.display()} → ${m.scoreAfter.display()}") { onJump(p) } } }
                review.strongestMovePly?.let { p -> Fact("Strongest move", moveLabel(data, p)) { onJump(p) } }
                val critical = data.moves.filter { it.moveClass.isCritical }
                Fact(
                    "Critical mistakes",
                    if (critical.isEmpty()) "None" else critical.joinToString(", ") { moveLabel(data, it.ply) },
                    critical.firstOrNull()?.let { first -> { onJump(first.ply) } },
                )
                Fact("Final evaluation", Score.parse(review.finalScore)?.display() ?: "–", null)
                Fact("Opening", listOfNotNull(review.eco ?: g.eco, review.openingName ?: g.openingName).joinToString(" · ").ifEmpty { "Unknown" }, null)
                if (review.bookPlies > 0) Fact("Book moves", "${review.bookPlies} plies", null)
                Fact("Length", "${(data.plyCount + 1) / 2} moves · ${data.plyCount} plies", null)
                Fact("Time control", "${Format.timeClass(g.timeClass)} · ${Format.timeControl(g.timeControl)}${if (g.rated) " · rated" else ""}", null)
            }
        }
        val recs = data.recommendations
        if (recs.isNotEmpty()) {
            item { Text("What to work on", style = MaterialTheme.typography.titleMedium) }
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
            Text(
                "${review.engineName} · ${describeKey(review.analysisKey)} · analysed in ${Format.duration(review.durationMillis.toDouble())}",
                style = MaterialTheme.typography.labelSmall,
                color = AppTheme.colors.textMuted,
            )
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

private fun moveLabel(data: ReviewData, ply: Int): String {
    val line = data.line ?: return ""
    val n = line.moveNumberOf(ply)
    val san = line.sans[ply - 1]
    return if (line.moverOf(ply) == Side.WHITE) "$n. $san" else "$n… $san"
}

@Composable
private fun KeyMomentRow(data: ReviewData, m: ReviewMove, onClick: () -> Unit) {
    AppCard(Modifier.fillMaxWidth(), onClick = onClick, contentPadding = PaddingValues(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ClassBadge(m.moveClass, size = 22.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("${moveLabel(data, m.ply)} · ${m.moveClass.label}", style = MaterialTheme.typography.titleSmall)
                Text(m.explanation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
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

@Composable
private fun MovesSheet(data: ReviewData, ply: Int, onJump: (Int) -> Unit) {
    val line = data.line ?: return
    val rows = (line.plyCount + (if (line.start.sideToMove == Side.BLACK) 1 else 0) + 1) / 2
    Column(Modifier.padding(horizontal = 20.dp)) {
        Text("Moves", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
            items(rows) { r ->
                val offset = if (line.start.sideToMove == Side.BLACK) -1 else 0
                val whitePly = r * 2 + 1 + offset
                val blackPly = whitePly + 1
                Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${line.start.fullmoveNumber + r}.", style = MaterialTheme.typography.labelLarge.numeric, color = AppTheme.colors.textMuted, modifier = Modifier.width(40.dp))
                    MoveCell(data, whitePly, ply, onJump, Modifier.weight(1f))
                    MoveCell(data, blackPly, ply, onJump, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun MoveCell(data: ReviewData, p: Int, selected: Int, onJump: (Int) -> Unit, modifier: Modifier) {
    val line = data.line ?: return
    if (p < 1 || p > line.plyCount) {
        Spacer(modifier)
        return
    }
    val m = data.move(p)
    Row(
        modifier.padding(end = 6.dp).clip(RoundedCornerShape(8.dp))
            .background(if (p == selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .clickable { onJump(p) }.padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(line.sans[p - 1], style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        if (m != null && m.moveClass != MoveClass.GOOD && m.moveClass != MoveClass.EXCELLENT) ClassBadge(m.moveClass, size = 18.dp)
    }
}
