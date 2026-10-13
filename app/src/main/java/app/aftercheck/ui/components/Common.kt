package app.aftercheck.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aftercheck.R
import app.aftercheck.chess.analysis.MoveClass
import app.aftercheck.ui.theme.AppTheme
import app.aftercheck.ui.theme.classColor
import app.aftercheck.ui.theme.numeric
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun AppIcon(
    @DrawableRes id: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    size: Dp = 22.dp,
) {
    Icon(painterResource(id), contentDescription, modifier.size(size), tint = tint)
}

/** Flat, bordered surface used for every card in the app. */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    color: Color = MaterialTheme.colorScheme.surfaceContainer,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = MaterialTheme.shapes.large
    val border = BorderStroke(1.dp, AppTheme.colors.border)
    if (onClick != null) {
        Surface(onClick = onClick, modifier = modifier, shape = shape, color = color, border = border) {
            Column(Modifier.padding(contentPadding), content = content)
        }
    } else {
        Surface(modifier = modifier, shape = shape, color = color, border = border) {
            Column(Modifier.padding(contentPadding), content = content)
        }
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) {
            TextButton(onClick = onAction, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text(action, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
fun ClassBadge(c: MoveClass, modifier: Modifier = Modifier, size: Dp = 20.dp) {
    Box(
        modifier.size(size).clip(CircleShape).background(classColor(c)).semantics { contentDescription = c.label },
        contentAlignment = Alignment.Center,
    ) {
        if (c == MoveClass.BOOK) {
            Icon(painterResource(R.drawable.ic_menu_book), null, Modifier.size(size * 0.62f), tint = Color.White)
        } else {
            Text(
                c.symbol,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = (size.value * if (c.symbol.length > 1) 0.42f else 0.55f).sp,
                lineHeight = (size.value * 0.6f).sp,
                maxLines = 1,
            )
        }
    }
}

@Composable
fun ResultBadge(userResult: String, modifier: Modifier = Modifier, size: Dp = 28.dp) {
    val colors = AppTheme.colors
    val (label, color) = when (userResult) {
        "win" -> "W" to colors.win
        "loss" -> "L" to colors.loss
        else -> "D" to colors.draw
    }
    Box(
        modifier.size(size).clip(RoundedCornerShape(8.dp)).background(color.copy(alpha = 0.16f))
            .semantics { contentDescription = resultWord(userResult) },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = color, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
    }
}

fun resultWord(userResult: String) = when (userResult) {
    "win" -> "Win"
    "loss" -> "Loss"
    else -> "Draw"
}

@Composable
fun Monogram(name: String, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val palette = listOf(0xFF2F6B4A, 0xFF35577A, 0xFF6B4A7A, 0xFF7A5A35, 0xFF3F6E73, 0xFF7A3F4A)
    val color = Color(palette[(name.lowercase().hashCode() and 0x7fffffff) % palette.size])
    Box(modifier.size(size).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
        Text(
            name.trim().take(1).uppercase().ifEmpty { "?" },
            color = Color.White,
            fontSize = (size.value * 0.42f).sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
fun Pill(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant, background: Color = AppTheme.colors.subtle) {
    Box(
        modifier.clip(RoundedCornerShape(50)).background(background).padding(horizontal = 9.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1)
    }
}

@Composable
fun StatusPill(status: String, progress: Int, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    when (status) {
        "ready" -> {}
        "analyzing" -> Pill("Analyzing $progress%", modifier, color = scheme.primary, background = scheme.primary.copy(alpha = 0.12f))
        "queued" -> Pill("Queued", modifier)
        "failed" -> Pill("Failed", modifier, color = scheme.error, background = scheme.error.copy(alpha = 0.12f))
        "skipped" -> Pill("No moves", modifier)
    }
}

@Composable
fun AccuracyRing(value: Double?, modifier: Modifier = Modifier, size: Dp = 56.dp, stroke: Dp = 6.dp, color: Color = MaterialTheme.colorScheme.primary) {
    val track = AppTheme.colors.subtle
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val s = stroke.toPx()
            drawArc(track, -90f, 360f, false, topLeft = Offset(s / 2, s / 2), size = this.size.copy(this.size.width - s, this.size.height - s), style = Stroke(s))
            if (value != null) {
                drawArc(color, -90f, (value / 100.0 * 360).toFloat(), false, topLeft = Offset(s / 2, s / 2),
                    size = this.size.copy(this.size.width - s, this.size.height - s), style = Stroke(s, cap = StrokeCap.Round))
            }
        }
        Text(
            value?.let { it.roundToInt().toString() } ?: "–",
            style = (if (size >= 56.dp) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge).numeric,
        )
    }
}

@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier, caption: String? = null, valueColor: Color = MaterialTheme.colorScheme.onSurface) {
    AppCard(modifier, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)) {
        Text(value, style = MaterialTheme.typography.headlineSmall.numeric, color = valueColor, maxLines = 1)
        Spacer(Modifier.height(2.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (caption != null) Text(caption, style = MaterialTheme.typography.labelSmall, color = AppTheme.colors.textMuted, maxLines = 1)
    }
}

/** Loading placeholder with a soft travelling sheen. */
@Composable
fun Skeleton(modifier: Modifier = Modifier, shape: RoundedCornerShape = RoundedCornerShape(10.dp)) {
    val base = AppTheme.colors.subtle
    val shine = if (AppTheme.colors.isDark) Color.White.copy(alpha = 0.05f) else Color.White.copy(alpha = 0.7f)
    val t by rememberInfiniteTransition(label = "skeleton").animateFloat(
        0f, 1f, infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Restart), label = "sheen",
    )
    Box(
        modifier.clip(shape).background(base).background(
            Brush.linearGradient(
                listOf(Color.Transparent, shine, Color.Transparent),
                start = Offset(t * 1600f - 400f, 0f),
                end = Offset(t * 1600f, 200f),
            ),
        ),
    )
}

@Composable
fun EmptyState(
    @DrawableRes icon: Int,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.size(64.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) { AppIcon(icon, null, tint = MaterialTheme.colorScheme.primary, size = 30.dp) }
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(4.dp))
            action()
        }
    }
}

@Composable
fun LabeledDivider(text: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = AppTheme.colors.textMuted)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f).height(1.dp).background(AppTheme.colors.border))
    }
}

// ------------------------------------------------------------------ formatting

object Format {
    fun relative(epochMillis: Long?, now: Long = System.currentTimeMillis()): String {
        if (epochMillis == null) return "never"
        val diff = (now - epochMillis).coerceAtLeast(0)
        val min = diff / 60_000
        return when {
            min < 1 -> "just now"
            min < 60 -> "${min}m ago"
            min < 24 * 60 -> "${min / 60}h ago"
            min < 7 * 24 * 60 -> "${min / (24 * 60)}d ago"
            else -> date(epochMillis)
        }
    }

    fun date(epochMillis: Long): String = SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(epochMillis))
    fun shortDate(epochMillis: Long): String = SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(epochMillis))
    fun dateTime(epochMillis: Long): String = SimpleDateFormat("MMM d, yyyy · HH:mm", Locale.getDefault()).format(Date(epochMillis))

    fun timeClass(tc: String): String = when (tc) {
        "bullet" -> "Bullet"
        "blitz" -> "Blitz"
        "rapid" -> "Rapid"
        "daily" -> "Daily"
        else -> tc.replaceFirstChar { it.uppercase() }
    }

    fun timeControl(tc: String): String {
        if (tc.contains('/')) {
            val seconds = tc.substringAfter('/').toLongOrNull() ?: return tc
            val days = seconds / 86_400
            return if (days >= 1) "$days day${if (days > 1) "s" else ""}" else "${seconds / 3600}h"
        }
        val base = tc.substringBefore('+').toLongOrNull() ?: return tc
        val inc = tc.substringAfter('+', "").toLongOrNull()
        val baseText = if (base % 60 == 0L) "${base / 60}" else "${base}s"
        return when {
            inc != null && inc > 0 -> "$baseText+$inc"
            base % 60 == 0L -> "$baseText min"
            else -> baseText
        }
    }

    fun clock(millis: Long?): String {
        millis ?: return ""
        val total = millis / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else if (millis < 20_000) "%d:%02d.%d".format(m, s, (millis % 1000) / 100) else "%d:%02d".format(m, s)
    }

    fun seconds(millis: Long?): String {
        millis ?: return ""
        return if (millis < 10_000) "%.1fs".format(Locale.US, millis / 1000.0) else "${millis / 1000}s"
    }

    fun percent(value: Double?): String = value?.let { "%.1f".format(Locale.US, it) } ?: "–"

    fun bytes(n: Long): String = when {
        n >= 1_000_000_000 -> "%.1f GB".format(Locale.US, n / 1e9)
        n >= 1_000_000 -> "%.1f MB".format(Locale.US, n / 1e6)
        n >= 1_000 -> "%.0f KB".format(Locale.US, n / 1e3)
        else -> "$n B"
    }

    fun duration(millis: Double): String {
        val s = (millis / 1000).roundToInt()
        return when {
            s < 60 -> "${s}s"
            s < 3600 -> "${s / 60} min"
            else -> "%.1f h".format(Locale.US, s / 3600.0)
        }
    }
}
