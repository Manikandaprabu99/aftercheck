package app.aftercheck.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import app.aftercheck.R
import app.aftercheck.chess.Move
import app.aftercheck.chess.Piece
import app.aftercheck.chess.PieceType
import app.aftercheck.chess.Position
import app.aftercheck.chess.Side
import app.aftercheck.chess.Squares
import app.aftercheck.ui.theme.AppTheme
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

@Immutable
data class BoardArrow(val from: Int, val to: Int, val color: Color)

@Immutable
data class SquareBadge(val square: Int, val color: Color, val symbol: String)

@Composable
private fun piecePainters(): Map<Piece, Painter> {
    val ids = mapOf(
        Piece(Side.WHITE, PieceType.KING) to R.drawable.piece_wk,
        Piece(Side.WHITE, PieceType.QUEEN) to R.drawable.piece_wq,
        Piece(Side.WHITE, PieceType.ROOK) to R.drawable.piece_wr,
        Piece(Side.WHITE, PieceType.BISHOP) to R.drawable.piece_wb,
        Piece(Side.WHITE, PieceType.KNIGHT) to R.drawable.piece_wn,
        Piece(Side.WHITE, PieceType.PAWN) to R.drawable.piece_wp,
        Piece(Side.BLACK, PieceType.KING) to R.drawable.piece_bk,
        Piece(Side.BLACK, PieceType.QUEEN) to R.drawable.piece_bq,
        Piece(Side.BLACK, PieceType.ROOK) to R.drawable.piece_br,
        Piece(Side.BLACK, PieceType.BISHOP) to R.drawable.piece_bb,
        Piece(Side.BLACK, PieceType.KNIGHT) to R.drawable.piece_bn,
        Piece(Side.BLACK, PieceType.PAWN) to R.drawable.piece_bp,
    )
    return ids.mapValues { painterResource(it.value) }
}

/**
 * Responsive chessboard drawn on a single canvas.
 *
 * @param animate the move that led from the previously shown position to [position]
 *   (or its reverse when stepping back); the moving piece glides instead of jumping.
 * @param interactive enables tap-tap and drag moves for [movableSide]; legal moves only.
 */
@Composable
fun ChessBoard(
    position: Position,
    modifier: Modifier = Modifier,
    flipped: Boolean = false,
    lastMove: Move? = null,
    animate: Move? = null,
    arrows: List<BoardArrow> = emptyList(),
    badge: SquareBadge? = null,
    showCoordinates: Boolean = true,
    interactive: Boolean = false,
    movableSide: Side? = null,
    onMove: (Move) -> Unit = {},
    description: String? = null,
) {
    val colors = AppTheme.colors
    val painters = piecePainters()
    val textMeasurer = rememberTextMeasurer()
    val accent = MaterialTheme.colorScheme.primary

    var selected by remember(position) { mutableStateOf<Int?>(null) }
    var dragFrom by remember(position) { mutableStateOf<Int?>(null) }
    var dragOffset by remember(position) { mutableStateOf(Offset.Zero) }
    var pendingPromotion by remember(position) { mutableStateOf<List<Move>?>(null) }
    val progress = remember { Animatable(1f) }
    val fen = position.toFen()
    LaunchedEffect(fen) {
        if (animate != null) {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(170, easing = FastOutSlowInEasing))
        } else progress.snapTo(1f)
    }

    fun tryMove(from: Int, to: Int): Boolean {
        val candidates = position.legalMoves().filter { it.from == from && it.to == to }
        when {
            candidates.isEmpty() -> return false
            candidates.size > 1 -> pendingPromotion = candidates
            else -> onMove(candidates.first())
        }
        selected = null
        return true
    }

    BoxWithConstraints(
        modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(6.dp))
            .semantics { contentDescription = description ?: "Chess board" },
    ) {
        val boardPx = with(LocalDensity.current) { maxWidth.toPx() }
        val sq = boardPx / 8f

        fun squareAt(offset: Offset): Int? {
            val col = (offset.x / sq).toInt()
            val row = (offset.y / sq).toInt()
            if (col !in 0..7 || row !in 0..7) return null
            val file = if (flipped) 7 - col else col
            val rank = if (flipped) row else 7 - row
            return Squares.of(file, rank)
        }

        fun topLeft(square: Int): Offset {
            val file = Squares.file(square)
            val rank = Squares.rank(square)
            val col = if (flipped) 7 - file else file
            val row = if (flipped) rank else 7 - rank
            return Offset(col * sq, row * sq)
        }

        val inputModifier = if (interactive) {
            Modifier
                .pointerInput(position, flipped) {
                    detectTapGestures { offset ->
                        val target = squareAt(offset) ?: return@detectTapGestures
                        val piece = position.pieceAt(target)
                        val sel = selected
                        if (sel != null && sel != target && tryMove(sel, target)) return@detectTapGestures
                        selected = if (piece != null && piece.side == position.sideToMove && (movableSide == null || piece.side == movableSide)) {
                            if (sel == target) null else target
                        } else null
                    }
                }
                .pointerInput(position, flipped) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            val from = squareAt(offset)
                            val piece = from?.let { position.pieceAt(it) }
                            if (piece != null && piece.side == position.sideToMove && (movableSide == null || piece.side == movableSide)) {
                                dragFrom = from
                                selected = from
                                dragOffset = offset
                            }
                        },
                        onDrag = { change, amount ->
                            if (dragFrom != null) {
                                change.consume()
                                dragOffset += amount
                            }
                        },
                        onDragEnd = {
                            val from = dragFrom
                            val to = squareAt(dragOffset)
                            dragFrom = null
                            if (from != null && to != null && to != from) tryMove(from, to)
                        },
                        onDragCancel = { dragFrom = null },
                    )
                }
        } else Modifier

        Canvas(Modifier.size(maxWidth).then(inputModifier)) {
            // Squares
            for (sqIndex in 0 until 64) {
                val tl = topLeft(sqIndex)
                val light = Squares.isLight(sqIndex)
                drawRect(if (light) colors.boardLight else colors.boardDark, tl, Size(sq, sq))
            }
            lastMove?.let { m ->
                drawRect(colors.highlight, topLeft(m.from), Size(sq, sq))
                drawRect(colors.highlight, topLeft(m.to), Size(sq, sq))
            }
            selected?.let { drawRect(accent.copy(alpha = 0.38f), topLeft(it), Size(sq, sq)) }
            // King in check
            if (position.isCheck) {
                val k = position.kingSquare(position.sideToMove)
                val c = topLeft(k) + Offset(sq / 2, sq / 2)
                drawCircle(
                    androidx.compose.ui.graphics.Brush.radialGradient(
                        listOf(Color(0xCCE5484D), Color(0x00E5484D)), center = c, radius = sq * 0.7f,
                    ),
                    radius = sq * 0.7f, center = c,
                )
            }
            if (showCoordinates) drawCoordinates(sq, flipped, colors.boardLight, colors.boardDark, textMeasurer)

            // Pieces
            val anim = animate?.takeIf { progress.value < 1f }
            val p = progress.value
            for (sqIndex in 0 until 64) {
                val piece = position.pieceAt(sqIndex) ?: continue
                if (sqIndex == dragFrom) continue
                var tl = topLeft(sqIndex)
                if (anim != null) {
                    val rookMove = castleRookMove(anim, position)
                    when (sqIndex) {
                        anim.to -> tl = lerp(topLeft(anim.from), tl, p)
                        rookMove?.second -> tl = lerp(topLeft(rookMove.first), tl, p)
                    }
                }
                drawPiece(painters.getValue(piece), tl, sq)
            }
            // Legal destinations
            selected?.let { from ->
                for (m in position.legalMovesFrom(from)) {
                    val c = topLeft(m.to) + Offset(sq / 2, sq / 2)
                    if (position.pieceAt(m.to) != null) {
                        drawCircle(Color.Black.copy(alpha = 0.22f), radius = sq * 0.46f, center = c, style = Stroke(sq * 0.08f))
                    } else drawCircle(Color.Black.copy(alpha = 0.2f), radius = sq * 0.15f, center = c)
                }
            }
            for (a in arrows) drawArrow(topLeft(a.from) + Offset(sq / 2, sq / 2), topLeft(a.to) + Offset(sq / 2, sq / 2), sq, a.color)
            badge?.let { b ->
                val tl = topLeft(b.square)
                val r = sq * 0.2f
                // Inside the square's top-right corner so edge files aren't clipped.
                val center = Offset(tl.x + sq - r * 1.05f, tl.y + r * 1.05f)
                drawCircle(Color.Black.copy(alpha = 0.25f), r * 1.08f, center + Offset(0f, r * 0.1f))
                drawCircle(b.color, r, center)
                val layout = textMeasurer.measure(b.symbol, TextStyle(color = Color.White, fontSize = (r * if (b.symbol.length > 1) 0.85f else 1.1f).toSp(), fontWeight = FontWeight.Bold))
                drawText(layout, topLeft = center - Offset(layout.size.width / 2f, layout.size.height / 2f))
            }
            dragFrom?.let { from ->
                position.pieceAt(from)?.let { piece -> drawPiece(painters.getValue(piece), dragOffset - Offset(sq * 0.6f, sq * 0.75f), sq * 1.2f) }
            }
        }
    }

    pendingPromotion?.let { moves ->
        Dialog(onDismissRequest = { pendingPromotion = null }) {
            Column(
                Modifier.shadow(12.dp, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                listOf(PieceType.QUEEN, PieceType.ROOK, PieceType.BISHOP, PieceType.KNIGHT).forEach { type ->
                    val move = moves.firstOrNull { it.promotion == type } ?: return@forEach
                    Box(
                        Modifier.size(72.dp).clickable { pendingPromotion = null; onMove(move) }
                            .semantics { contentDescription = "Promote to ${type.displayName}" },
                    ) {
                        Canvas(Modifier.size(72.dp)) {
                            drawPiece(painters.getValue(Piece(position.sideToMove, type)), Offset.Zero, size.width)
                        }
                    }
                }
            }
        }
    }
}

private fun lerp(a: Offset, b: Offset, t: Float) = Offset(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)

/** For a castling animation: (rook from, rook to). */
private fun castleRookMove(m: Move, after: Position): Pair<Int, Int>? {
    val piece = after.pieceAt(m.to) ?: return null
    // Forward castling only (king leaves e1/e8); stepping back just moves the king.
    if (piece.type != PieceType.KING || kotlin.math.abs(m.to - m.from) != 2 || (m.from != 4 && m.from != 60)) return null
    return if (m.to > m.from) (m.from + 3) to (m.from + 1) else (m.from - 4) to (m.from - 1)
}

private fun DrawScope.drawPiece(painter: Painter, topLeft: Offset, size: Float) {
    translate(topLeft.x, topLeft.y) {
        with(painter) { draw(Size(size, size)) }
    }
}

private fun DrawScope.drawCoordinates(sq: Float, flipped: Boolean, light: Color, dark: Color, tm: androidx.compose.ui.text.TextMeasurer) {
    val fontSize = (sq * 0.2f).toSp()
    for (i in 0..7) {
        // Files along the bottom edge
        val file = if (flipped) 7 - i else i
        val fileSquareLight = Squares.isLight(Squares.of(file, if (flipped) 7 else 0))
        val fileLayout = tm.measure(('a' + file).toString(), TextStyle(fontSize = fontSize, fontWeight = FontWeight.SemiBold, color = if (fileSquareLight) dark else light))
        drawText(fileLayout, topLeft = Offset(i * sq + sq - fileLayout.size.width - sq * 0.06f, 8 * sq - fileLayout.size.height - sq * 0.02f))
        // Ranks along the left edge
        val rank = if (flipped) i else 7 - i
        val rankSquareLight = Squares.isLight(Squares.of(if (flipped) 7 else 0, rank))
        val rankLayout = tm.measure(('1' + rank).toString(), TextStyle(fontSize = fontSize, fontWeight = FontWeight.SemiBold, color = if (rankSquareLight) dark else light))
        drawText(rankLayout, topLeft = Offset(sq * 0.06f, i * sq + sq * 0.03f))
    }
}

private fun DrawScope.drawArrow(from: Offset, to: Offset, sq: Float, color: Color) {
    val angle = atan2(to.y - from.y, to.x - from.x)
    val head = sq * 0.42f
    val width = sq * 0.17f
    val tip = to - Offset(cos(angle) * sq * 0.12f, sin(angle) * sq * 0.12f)
    val shaftEnd = tip - Offset(cos(angle) * head * 0.8f, sin(angle) * head * 0.8f)
    val start = from + Offset(cos(angle) * sq * 0.22f, sin(angle) * sq * 0.22f)
    drawLine(color, start, shaftEnd, strokeWidth = width, cap = StrokeCap.Butt)
    val path = Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(shaftEnd.x + cos(angle + Math.PI.toFloat() / 2) * head * 0.55f, shaftEnd.y + sin(angle + Math.PI.toFloat() / 2) * head * 0.55f)
        lineTo(shaftEnd.x + cos(angle - Math.PI.toFloat() / 2) * head * 0.55f, shaftEnd.y + sin(angle - Math.PI.toFloat() / 2) * head * 0.55f)
        close()
    }
    drawPath(path, color)
}

/** Vertical evaluation bar; White's share fills from White's side of the board. */
@Composable
fun EvalBar(whiteChance: Float, label: String, flipped: Boolean, modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    val animated = remember { Animatable(whiteChance) }
    LaunchedEffect(whiteChance) { animated.animateTo(whiteChance, tween(260, easing = FastOutSlowInEasing)) }
    val textMeasurer = rememberTextMeasurer()
    val whiteLeads = whiteChance >= 0.5f
    Canvas(modifier.clip(RoundedCornerShape(4.dp)).semantics { contentDescription = "Evaluation $label" }) {
        val h = size.height
        drawRect(colors.evalBlack)
        val whiteH = h * animated.value.coerceIn(0.02f, 0.98f)
        if (!flipped) drawRect(colors.evalWhite, Offset(0f, h - whiteH), Size(size.width, whiteH))
        else drawRect(colors.evalWhite, Offset.Zero, Size(size.width, whiteH))
        val layout = textMeasurer.measure(
            label.replace("−", "").replace("+", ""),
            TextStyle(fontSize = 8.5.sp, fontWeight = FontWeight.Bold, color = if (whiteLeads) colors.evalBlack else colors.evalWhite),
        )
        val atBottom = whiteLeads != flipped
        val x = (size.width - layout.size.width) / 2f
        val y = if (atBottom) h - layout.size.height - 3.dp.toPx() else 3.dp.toPx()
        if (layout.size.width <= size.width + 2) drawText(layout, topLeft = Offset(x, y))
    }
}
