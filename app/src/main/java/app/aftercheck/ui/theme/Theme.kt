package app.aftercheck.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aftercheck.R
import app.aftercheck.chess.analysis.MoveClass
import app.aftercheck.data.settings.BoardTheme
import app.aftercheck.data.settings.ThemeMode

/** Colours outside Material's scheme: results, move classes, board. */
@Immutable
data class AfterCheckColors(
    val win: Color,
    val loss: Color,
    val draw: Color,
    val border: Color,
    val subtle: Color,
    val textMuted: Color,
    val evalWhite: Color,
    val evalBlack: Color,
    val boardLight: Color,
    val boardDark: Color,
    val highlight: Color,
    val isDark: Boolean,
) {
    fun forClass(c: MoveClass): Color = classColor(c)
}

fun classColor(c: MoveClass): Color = when (c) {
    MoveClass.BOOK -> Color(0xFFB08D68)
    MoveClass.BEST -> Color(0xFF34B97A)
    MoveClass.EXCELLENT -> Color(0xFF63C98E)
    MoveClass.GOOD -> Color(0xFF8DB4A0)
    MoveClass.INACCURACY -> Color(0xFFF0BE47)
    MoveClass.MISTAKE -> Color(0xFFEE8A3C)
    MoveClass.BLUNDER -> Color(0xFFE5484D)
    MoveClass.MISS -> Color(0xFFE879C9)
    MoveClass.MISSED_MATE -> Color(0xFF7B8CF5)
}

val LocalAfterCheckColors = staticCompositionLocalOf<AfterCheckColors> { error("AfterCheckTheme not applied") }

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF7BD88F),
    onPrimary = Color(0xFF07210F),
    primaryContainer = Color(0xFF1B3324),
    onPrimaryContainer = Color(0xFFB9F0C7),
    secondary = Color(0xFFA9C7B3),
    onSecondary = Color(0xFF13241A),
    secondaryContainer = Color(0xFF223028),
    onSecondaryContainer = Color(0xFFD0E5D7),
    tertiary = Color(0xFFF0BE47),
    background = Color(0xFF0D0F0E),
    onBackground = Color(0xFFECEFED),
    surface = Color(0xFF0D0F0E),
    onSurface = Color(0xFFECEFED),
    surfaceVariant = Color(0xFF1E2321),
    onSurfaceVariant = Color(0xFFA3ACA7),
    surfaceContainerLowest = Color(0xFF0A0C0B),
    surfaceContainerLow = Color(0xFF131615),
    surfaceContainer = Color(0xFF171B19),
    surfaceContainerHigh = Color(0xFF1D2220),
    surfaceContainerHighest = Color(0xFF242A27),
    outline = Color(0xFF3A423E),
    outlineVariant = Color(0xFF262C29),
    error = Color(0xFFFF7A7E),
    onError = Color(0xFF3B0A0C),
    errorContainer = Color(0xFF4A1A1C),
    onErrorContainer = Color(0xFFFFD9DA),
    inverseSurface = Color(0xFFECEFED),
    inverseOnSurface = Color(0xFF151816),
    scrim = Color(0xFF000000),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF237A45),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDDF2E3),
    onPrimaryContainer = Color(0xFF0B3A1D),
    secondary = Color(0xFF4F6657),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE3ECE5),
    onSecondaryContainer = Color(0xFF16261C),
    tertiary = Color(0xFF9A6B00),
    background = Color(0xFFF5F6F4),
    onBackground = Color(0xFF131614),
    surface = Color(0xFFF5F6F4),
    onSurface = Color(0xFF131614),
    surfaceVariant = Color(0xFFE9ECE8),
    onSurfaceVariant = Color(0xFF59625D),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFBFCFA),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFEFF1EE),
    surfaceContainerHighest = Color(0xFFE6E9E5),
    outline = Color(0xFFC5CBC6),
    outlineVariant = Color(0xFFE2E6E1),
    error = Color(0xFFC62D33),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFDE3E3),
    onErrorContainer = Color(0xFF5A0D10),
    inverseSurface = Color(0xFF242826),
    inverseOnSurface = Color(0xFFF1F3F1),
    scrim = Color(0xFF000000),
)

val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

private fun style(size: Int, line: Int, weight: FontWeight, tracking: Double = 0.0) =
    TextStyle(fontFamily = Inter, fontSize = size.sp, lineHeight = line.sp, fontWeight = weight, letterSpacing = tracking.sp)

private val AppTypography = Typography(
    displayLarge = style(48, 54, FontWeight.SemiBold, -1.2),
    displayMedium = style(40, 46, FontWeight.SemiBold, -1.0),
    displaySmall = style(32, 38, FontWeight.SemiBold, -0.6),
    headlineLarge = style(28, 34, FontWeight.SemiBold, -0.5),
    headlineMedium = style(24, 30, FontWeight.SemiBold, -0.4),
    headlineSmall = style(21, 28, FontWeight.SemiBold, -0.3),
    titleLarge = style(19, 26, FontWeight.SemiBold, -0.2),
    titleMedium = style(16, 22, FontWeight.SemiBold, -0.1),
    titleSmall = style(14, 20, FontWeight.SemiBold),
    bodyLarge = style(16, 24, FontWeight.Normal),
    bodyMedium = style(14, 20, FontWeight.Normal),
    bodySmall = style(12, 17, FontWeight.Normal),
    labelLarge = style(14, 20, FontWeight.Medium),
    labelMedium = style(12, 16, FontWeight.Medium, 0.1),
    labelSmall = style(11, 14, FontWeight.Medium, 0.3),
)

/** Tabular figures so numbers don't jitter as they change. */
val TextStyle.numeric: TextStyle get() = copy(fontFeatureSettings = "tnum")

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(26.dp),
)

@Composable
fun AfterCheckTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    boardTheme: BoardTheme = BoardTheme.MOSS,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val scheme: ColorScheme = if (dark) DarkScheme else LightScheme
    val extra = AfterCheckColors(
        win = if (dark) Color(0xFF7BD88F) else Color(0xFF23864A),
        loss = if (dark) Color(0xFFFF7A7E) else Color(0xFFD03A40),
        draw = if (dark) Color(0xFFA3ACA7) else Color(0xFF7A837E),
        border = scheme.outlineVariant,
        subtle = scheme.surfaceContainerHigh,
        textMuted = if (dark) Color(0xFF737C77) else Color(0xFF8A928D),
        evalWhite = Color(0xFFF2F2EF),
        evalBlack = Color(0xFF2B2E2C),
        boardLight = Color(boardTheme.light),
        boardDark = Color(boardTheme.dark),
        highlight = Color(0x66F7D44C),
        isDark = dark,
    )
    CompositionLocalProvider(LocalAfterCheckColors provides extra) {
        MaterialTheme(colorScheme = scheme, typography = AppTypography, shapes = AppShapes, content = content)
    }
}

object AppTheme {
    val colors: AfterCheckColors @Composable get() = LocalAfterCheckColors.current
}
