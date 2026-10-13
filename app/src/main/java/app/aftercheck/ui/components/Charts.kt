package app.aftercheck.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aftercheck.chess.analysis.MoveClass
import app.aftercheck.ui.theme.AppTheme
import app.aftercheck.ui.theme.classColor
import app.aftercheck.ui.theme.numeric
import kotlin.math.roundToInt

/**
 * The review's evaluation graph: White's winning chance per ply (white area = White better).
 * Errors are marked with their class colour; touch or drag selects a ply.
 */
@Composable
fun EvalGraph(
    winChances: List<Float>,
    markers: Map<Int, MoveClass>,
    selectedPly: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 64.dp,
) {
    val colors = AppTheme.colors
    val surface = MaterialTheme.colorScheme.surfaceContainer
    val accent = MaterialTheme.colorScheme.primary
    val n = winChances.size
    Canvas(
        modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(8.dp))
            .semantics { contentDescription = "Evaluation graph, ${n - 1} moves. Touch to jump to a move." }
            .pointerInput(n) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    fun select(x: Float) {
                        if (n < 2) return
                        onSelect(((x / size.width) * (n - 1)).roundToInt().coerceIn(0, n - 1))
                    }
                    select(down.position.x)
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        if (!change.pressed) break
                        if (change.positionChange() != Offset.Zero) {
                            change.consume()
                            select(change.position.x)
                        }
                    }
                }
            },
    ) {
        drawRect(colors.evalBlack)
        if (n < 2) return@Canvas
        val w = size.width
        val h = size.height
        fun x(i: Int) = i * w / (n - 1)
        fun y(v: Float) = h * (1f - v.coerceIn(0.02f, 0.98f))
        val area = Path().apply {
            moveTo(0f, h)
            for (i in 0 until n) lineTo(x(i), y(winChances[i]))
            lineTo(w, h)
            close()
        }
        drawPath(area, colors.evalWhite)
        drawLine(Color(0x55888888), Offset(0f, h / 2), Offset(w, h / 2), strokeWidth = 1.dp.toPx())
        val sel = selectedPly.coerceIn(0, n - 1)
        drawLine(accent, Offset(x(sel), 0f), Offset(x(sel), h), strokeWidth = 2.dp.toPx())
        for ((ply, c) in markers) {
            if (ply !in 0 until n) continue
            val center = Offset(x(ply), y(winChances[ply]))
            drawCircle(surface, radius = 5.dp.toPx(), center = center)
            drawCircle(classColor(c), radius = 4.dp.toPx(), center = center)
        }
    }
}

data class TrendPoint(val value: Float, val label: String)

/**
 * Single-series line chart (0..100) with a hairline grid, a rolling average and a touch tooltip.
 */
@Composable
fun TrendChart(
    points: List<TrendPoint>,
    modifier: Modifier = Modifier,
    height: Dp = 168.dp,
    averageWindow: Int = 5,
    valueSuffix: String = "%",
) {
    val colors = AppTheme.colors
    val scheme = MaterialTheme.colorScheme
    val textMeasurer = rememberTextMeasurer()
    var touched by remember(points) { mutableStateOf<Int?>(null) }
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = colors.textMuted)
    val tooltipStyle = MaterialTheme.typography.labelMedium.copy(color = scheme.inverseOnSurface)
    val minV = ((points.minOfOrNull { it.value } ?: 0f) / 10f).toInt() * 10f
    val lo = minV.coerceIn(0f, 80f)
    val hi = 100f
    Canvas(
        modifier.fillMaxWidth().height(height)
            .semantics { contentDescription = "Accuracy trend over ${points.size} games" }
            .pointerInput(points) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val left = 28.dp.toPx()
                    fun pick(x: Float) {
                        if (points.isEmpty()) return
                        val step = (size.width - left) / (points.size - 1).coerceAtLeast(1)
                        touched = (((x - left) / step).roundToInt()).coerceIn(0, points.lastIndex)
                    }
                    pick(down.position.x)
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        if (!change.pressed) break
                        change.consume()
                        pick(change.position.x)
                    }
                    touched = null
                }
            },
    ) {
        val left = 28.dp.toPx()
        val bottom = size.height - 6.dp.toPx()
        val top = 8.dp.toPx()
        fun y(v: Float) = bottom - (v - lo) / (hi - lo) * (bottom - top)
        val step = (size.width - left) / (points.size - 1).coerceAtLeast(1)
        fun x(i: Int) = left + i * step
        // Grid
        for (g in listOf(lo, (lo + hi) / 2, hi)) {
            drawLine(colors.border, Offset(left, y(g)), Offset(size.width, y(g)), strokeWidth = 1f)
            val l = textMeasurer.measure(g.roundToInt().toString(), labelStyle)
            drawText(l, topLeft = Offset(0f, y(g) - l.size.height / 2f))
        }
        if (points.isEmpty()) return@Canvas
        // Per-game dots
        points.forEachIndexed { i, p ->
            drawCircle(scheme.primary.copy(alpha = 0.35f), radius = 3.dp.toPx(), center = Offset(x(i), y(p.value)))
        }
        // Rolling average line
        if (points.size >= 2) {
            val avg = points.indices.map { i ->
                val from = (i - averageWindow + 1).coerceAtLeast(0)
                points.subList(from, i + 1).map { it.value }.average().toFloat()
            }
            val path = Path().apply {
                moveTo(x(0), y(avg[0]))
                for (i in 1 until avg.size) lineTo(x(i), y(avg[i]))
            }
            drawPath(path, scheme.primary, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        touched?.let { i ->
            val p = points[i]
            val cx = x(i)
            drawLine(colors.textMuted, Offset(cx, top), Offset(cx, bottom), strokeWidth = 1.dp.toPx())
            drawCircle(scheme.surfaceContainer, radius = 6.dp.toPx(), center = Offset(cx, y(p.value)))
            drawCircle(scheme.primary, radius = 4.5.dp.toPx(), center = Offset(cx, y(p.value)))
            val text = textMeasurer.measure("${p.value.roundToInt()}$valueSuffix · ${p.label}", tooltipStyle)
            val pad = 6.dp.toPx()
            val bw = text.size.width + pad * 2
            val bh = text.size.height + pad * 1.4f
            val bx = (cx - bw / 2).coerceIn(left, size.width - bw)
            drawRoundRect(scheme.inverseSurface, Offset(bx, top), Size(bw, bh), CornerRadius(6.dp.toPx()))
            drawText(text, topLeft = Offset(bx + pad, top + pad * 0.7f))
        }
    }
}

/** Compact single-series sparkline for the home screen. */
@Composable
fun Sparkline(values: List<Float>, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        val lo = values.min() - 3
        val hi = values.max() + 3
        val range = (hi - lo).coerceAtLeast(1f)
        val step = size.width / (values.size - 1)
        val path = Path()
        values.forEachIndexed { i, v ->
            val px = i * step
            val py = size.height - (v - lo) / range * size.height
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        drawPath(path, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        val last = values.last()
        drawCircle(color, 3.5.dp.toPx(), Offset(size.width, size.height - (last - lo) / range * size.height))
    }
}

/** 100 % stacked bar for win / draw / loss with a 2 dp gap between segments, labelled below. */
@Composable
fun ResultSplitBar(wins: Int, draws: Int, losses: Int, modifier: Modifier = Modifier, showLegend: Boolean = true, barHeight: Dp = 10.dp) {
    val colors = AppTheme.colors
    val total = (wins + draws + losses).coerceAtLeast(1)
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(barHeight).semantics {
            contentDescription = "$wins wins, $draws draws, $losses losses"
        }) {
            val gap = 2.dp.toPx()
            val parts = listOf(wins to colors.win, draws to colors.draw, losses to colors.loss).filter { it.first > 0 }
            val usable = size.width - gap * (parts.size - 1).coerceAtLeast(0)
            var x = 0f
            val r = CornerRadius(size.height / 2)
            if (parts.isEmpty()) drawRoundRect(colors.subtle, size = size, cornerRadius = r)
            for ((n, c) in parts) {
                val w = usable * n / total
                drawRoundRect(c, Offset(x, 0f), Size(w, size.height), r)
                x += w + gap
            }
        }
        if (showLegend) {
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LegendItem(colors.win, "Wins", "$wins · ${wins * 100 / total}%")
                LegendItem(colors.draw, "Draws", "$draws · ${draws * 100 / total}%")
                LegendItem(colors.loss, "Losses", "$losses · ${losses * 100 / total}%")
            }
        }
    }
}

@Composable
fun LegendItem(color: Color, label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Column {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.labelLarge.numeric)
        }
    }
}

/** A labelled row with a thin proportional bar: label · bar · value. */
@Composable
fun BarRow(
    label: String,
    value: String,
    fraction: Float,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    caption: String? = null,
    leading: (@Composable () -> Unit)? = null,
) {
    val track = AppTheme.colors.subtle
    Column(modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (leading != null) {
                leading()
                Spacer(Modifier.width(10.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (caption != null) Text(caption, style = MaterialTheme.typography.labelSmall, color = AppTheme.colors.textMuted, maxLines = 1)
            }
            Spacer(Modifier.width(12.dp))
            Text(value, style = MaterialTheme.typography.labelLarge.numeric)
        }
        Spacer(Modifier.height(6.dp))
        Canvas(Modifier.fillMaxWidth().height(4.dp)) {
            val r = CornerRadius(size.height / 2)
            drawRoundRect(track, size = size, cornerRadius = r)
            val w = size.width * fraction.coerceIn(0f, 1f)
            if (w > 0f) drawRoundRect(color, size = Size(w.coerceAtLeast(size.height), size.height), cornerRadius = r)
        }
    }
}

/** Small text style used for chart captions. */
val chartCaption: TextStyle @Composable get() = MaterialTheme.typography.labelSmall.copy(color = AppTheme.colors.textMuted, fontSize = 11.sp)
