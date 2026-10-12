package app.aftercheck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import app.aftercheck.data.AfterCheckDatabase
import app.aftercheck.data.ChessGameEntity
import app.aftercheck.data.MoveAnalysisEntity
import app.aftercheck.worker.AnalysisScheduler
import kotlinx.coroutines.launch

private val ReviewBackground = Color(0xFF101512)
private val ReviewPanel = Color(0xFF1A211C)
private val ReviewRaised = Color(0xFF222C24)
private val ReviewGreen = Color(0xFF9BE36D)
private val ReviewMuted = Color(0xFFA6B2A7)
private val ReviewPaper = Color(0xFFEAF1E9)

@Composable
fun GameReviewScreen(
    database: AfterCheckDatabase,
    game: ChessGameEntity,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val dao = remember(database) { database.gameDao() }
    val activeGame by dao.observeGame(game.url).collectAsState(initial = game)
    val displayedGame = activeGame ?: game
    val moves by dao.observeMoveAnalyses(game.url).collectAsState(initial = emptyList())
    val summary by dao.observeReview(game.url).collectAsState(initial = null)
    var selectedIndex by remember(game.url) { mutableIntStateOf(0) }
    val selected = moves.getOrNull(selectedIndex)
    val fen = selected?.fenAfter ?: "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

    LazyColumn(
        modifier = modifier.fillMaxSize().background(ReviewBackground),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onBack) {
                    Icon(Icons.Rounded.ArrowBack, contentDescription = "Back")
                    Text(" Games")
                }
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text("GAME REVIEW", color = ReviewGreen, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)
                    Text("Move-by-move", color = ReviewPaper, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = ReviewPanel), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${displayedGame.white}  vs  ${displayedGame.black}", color = ReviewPaper, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("Result ${displayedGame.result}  ·  ${displayedGame.timeControl}", color = ReviewMuted, fontSize = 12.sp)
                    Text(if (displayedGame.reviewStatus == "READY") "Local Stockfish analysis complete" else "Review status: ${displayedGame.reviewStatus.lowercase().replace('_', ' ')}", color = if (displayedGame.reviewStatus == "READY") ReviewGreen else ReviewMuted, fontSize = 12.sp)
                    if (displayedGame.reviewStatus == "FAILED" || displayedGame.reviewStatus == "WAITING") {
                        androidx.compose.material3.Button(onClick = {
                            scope.launch {
                                dao.setReviewStatus(displayedGame.url, "WAITING")
                                AnalysisScheduler.enqueue(context)
                            }
                        }) {
                            Text(if (displayedGame.reviewStatus == "FAILED") "Retry analysis" else "Analyze now")
                        }
                    }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = ReviewPanel), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ChessBoard(fen = fen, modifier = Modifier.fillMaxWidth().aspectRatio(1f))
                    Row(horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text(selected?.let { "Ply ${it.plyNumber} · ${it.sideToMove.lowercase().replaceFirstChar(Char::uppercase)}" } ?: "Starting position", color = ReviewPaper, fontWeight = FontWeight.SemiBold)
                            Text(selected?.san ?: "Select a move below after analysis completes.", color = ReviewMuted, fontSize = 12.sp)
                        }
                        Row {
                            OutlinedButton(onClick = { selectedIndex = (selectedIndex - 1).coerceAtLeast(0) }, enabled = moves.isNotEmpty() && selectedIndex > 0) {
                                Icon(Icons.Rounded.ChevronLeft, contentDescription = "Previous move")
                            }
                            Button(onClick = { selectedIndex = (selectedIndex + 1).coerceAtMost(moves.lastIndex) }, enabled = moves.isNotEmpty() && selectedIndex < moves.lastIndex) {
                                Icon(Icons.Rounded.ChevronRight, contentDescription = "Next move")
                            }
                        }
                    }
                }
            }
        }
        if (summary != null) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = ReviewPanel), shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Estimated performance", color = ReviewPaper, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                        Text("App estimate · engine depth ${summary!!.engineDepth}", color = ReviewMuted, fontSize = 11.sp)
                        Text("Final evaluation (White POV): ${formatCp(summary!!.finalEvaluationWhiteCp)}", color = ReviewMuted, fontSize = 12.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            SummaryMetric("White", "${summary!!.accuracyWhite.formatOneDecimal()}%", Modifier.weight(1f))
                            SummaryMetric("Black", "${summary!!.accuracyBlack.formatOneDecimal()}%", Modifier.weight(1f))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SummaryMetric("Best", summary!!.bestMoves.toString(), Modifier.weight(1f))
                            SummaryMetric("Inacc.", summary!!.inaccuracies.toString(), Modifier.weight(1f))
                            SummaryMetric("Mistakes", summary!!.mistakes.toString(), Modifier.weight(1f))
                            SummaryMetric("Blunders", summary!!.blunders.toString(), Modifier.weight(1f))
                        }
                    }
                }
            }
        }
        item {
            Text("MOVE LIST", color = ReviewMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
        }
        if (moves.isEmpty()) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = ReviewPanel), shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(if (displayedGame.reviewStatus == "FAILED") "Analysis failed" else "Waiting for local analysis", color = ReviewPaper, fontWeight = FontWeight.SemiBold)
                        Text("AfterCheck needs the Stockfish engine bundled in the build and a replayable PGN before it can generate move classifications.", color = ReviewMuted, fontSize = 12.sp)
                    }
                }
            }
        } else {
            items(moves, key = { it.gameUrl + ":" + it.plyNumber }) { move ->
                MoveRow(move = move, selected = selected?.plyNumber == move.plyNumber) {
                    selectedIndex = moves.indexOfFirst { it.plyNumber == move.plyNumber }.coerceAtLeast(0)
                }
            }
        }
    }
}

@Composable
internal fun ChessBoard(fen: String, modifier: Modifier = Modifier) {
    val placement = fen.substringBefore(' ')
    val ranks = placement.split('/')
    if (ranks.size != 8) {
        Box(modifier.background(ReviewRaised), contentAlignment = Alignment.Center) {
            Text("Board position unavailable", color = ReviewMuted)
        }
        return
    }
    Column(modifier.clip(RoundedCornerShape(8.dp))) {
        ranks.forEachIndexed { rankIndex, rank ->
            val squares = buildList<Char?> {
                rank.forEach { char ->
                    if (char.isDigit()) repeat(char.digitToInt()) { add(null) } else add(char)
                }
            }
            Row(Modifier.weight(1f)) {
                squares.take(8).forEachIndexed { fileIndex, piece ->
                    val light = (rankIndex + fileIndex) % 2 == 0
                    Box(
                        Modifier.weight(1f).fillMaxSize().background(if (light) Color(0xFFDCE6D4) else Color(0xFF5F8065)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (piece != null) {
                            Text(
                                pieceGlyph(piece),
                                color = if (piece.isUpperCase()) Color(0xFFF8F8EE) else Color(0xFF1D241E),
                                fontSize = 29.sp,
                                lineHeight = 32.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MoveRow(move: MoveAnalysisEntity, selected: Boolean, onClick: () -> Unit) {
    val background = if (selected) Color(0xFF2A3D2D) else ReviewPanel
    Surface(
        color = background,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Row(
            Modifier.padding(horizontal = 13.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("${move.plyNumber}. ${move.san}", color = ReviewPaper, fontWeight = FontWeight.SemiBold)
                Text("Best: ${move.bestMoveSan.ifBlank { move.bestMoveUci }} · loss ${move.centipawnLoss} cp", color = ReviewMuted, fontSize = 11.sp)
                Text("Eval: ${formatCp(move.evaluationBeforeWhiteCp)} → ${formatCp(move.evaluationAfterWhiteCp)}", color = ReviewMuted, fontSize = 10.sp)
                if (move.principalVariationSan.isNotBlank()) Text("Line: ${move.principalVariationSan}", color = ReviewMuted, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Surface(color = classificationColor(move.classification), shape = RoundedCornerShape(20.dp)) {
                Text(move.classification, color = ReviewBackground, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp))
            }
        }
    }
    // The row content itself is not decorative; give the user a clear, full-width move selector.
    Box(Modifier.fillMaxWidth().height(1.dp).background(ReviewBackground))
}

@Composable
private fun SummaryMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = ReviewRaised), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(10.dp)) {
            Text(value, color = ReviewGreen, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text(label, color = ReviewMuted, fontSize = 10.sp)
        }
    }
}

private fun pieceGlyph(piece: Char): String = when (piece.lowercaseChar()) {
    'k' -> if (piece.isUpperCase()) "♔" else "♚"
    'q' -> if (piece.isUpperCase()) "♕" else "♛"
    'r' -> if (piece.isUpperCase()) "♖" else "♜"
    'b' -> if (piece.isUpperCase()) "♗" else "♝"
    'n' -> if (piece.isUpperCase()) "♘" else "♞"
    'p' -> if (piece.isUpperCase()) "♙" else "♟"
    else -> ""
}

private fun classificationColor(classification: String): Color = when (classification) {
    "BEST", "EXCELLENT" -> Color(0xFF9BE36D)
    "GOOD" -> Color(0xFFBBDDA4)
    "INACCURACY" -> Color(0xFFF0D36A)
    "MISTAKE" -> Color(0xFFF0A45A)
    else -> Color(0xFFFF887E)
}

private fun Double.formatOneDecimal(): String = String.format(java.util.Locale.ROOT, "%.1f", this)

private fun formatCp(value: Int): String = String.format(java.util.Locale.ROOT, "%+.2f", value / 100.0)
