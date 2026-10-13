package app.aftercheck.ui.insights

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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.aftercheck.AppContainer
import app.aftercheck.R
import app.aftercheck.chess.analysis.MoveClass
import app.aftercheck.ui.components.AppCard
import app.aftercheck.ui.components.AppIcon
import app.aftercheck.ui.components.BarRow
import app.aftercheck.ui.components.ClassBadge
import app.aftercheck.ui.components.EmptyState
import app.aftercheck.ui.components.Format
import app.aftercheck.ui.components.ResultSplitBar
import app.aftercheck.ui.components.Skeleton
import app.aftercheck.ui.components.TrendChart
import app.aftercheck.ui.components.TrendPoint
import app.aftercheck.ui.components.chartCaption
import app.aftercheck.ui.theme.AppTheme
import app.aftercheck.ui.theme.classColor
import app.aftercheck.ui.theme.numeric
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

enum class Range(val label: String, val days: Int?) { D30("30 days", 30), D90("90 days", 90), Y1("Year", 365), ALL("All", null) }

data class InsightsState(val range: Range, val data: InsightsData?)

@OptIn(ExperimentalCoroutinesApi::class)
class InsightsViewModel(c: AppContainer) : ViewModel() {
    val range = MutableStateFlow(Range.D90)

    val state: StateFlow<InsightsState> = range.flatMapLatest { r ->
        val since = r.days?.let { System.currentTimeMillis() - it * 86_400_000L } ?: 0L
        combine(c.db.games().insightRows(since), c.db.games().userErrorThemes(since)) { rows, themes ->
            InsightsState(r, InsightsCalculator.compute(rows, themes))
        }.flowOn(Dispatchers.Default)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InsightsState(Range.D90, null))
}

@Composable
fun InsightsScreen(container: AppContainer, onPracticeTheme: (String) -> Unit) {
    val vm: InsightsViewModel = viewModel { InsightsViewModel(container) }
    val state by vm.state.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            Modifier.widthIn(max = 720.dp).fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item { Text("Insights", style = MaterialTheme.typography.headlineMedium) }
            item {
                // One filter row scopes every chart below.
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    Range.entries.forEachIndexed { i, r ->
                        SegmentedButton(
                            selected = state.range == r,
                            onClick = { vm.range.value = r },
                            shape = SegmentedButtonDefaults.itemShape(i, Range.entries.size),
                            icon = {},
                            label = { Text(r.label, style = MaterialTheme.typography.labelMedium) },
                        )
                    }
                }
            }
            val d = state.data
            when {
                d == null -> items(3) { Skeleton(Modifier.fillMaxWidth().height(160.dp)) }
                d.overall.games == 0 -> item {
                    EmptyState(R.drawable.ic_insights, "No games in this period", "Pick a longer range, or check back after your next games sync.")
                }
                else -> {
                    item { ResultsCard(d) }
                    item { AccuracyCard(d) }
                    if (d.reviewed > 0) item { MistakesCard(d) }
                    if (d.improvement != null) item { ImprovementCard(d.improvement) }
                    if (d.byTimeClass.isNotEmpty()) item { TimeClassCard(d) }
                    if (d.openingsWhite.isNotEmpty() || d.openingsBlack.isNotEmpty()) item { OpeningsCard(d) }
                    if (d.weaknesses.isNotEmpty()) item { WeaknessCard(d, onPracticeTheme) }
                }
            }
        }
    }
}

@Composable
private fun CardTitle(title: String, caption: String? = null) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    if (caption != null) Text(caption, style = chartCaption)
    Spacer(Modifier.height(12.dp))
}

private fun pct(x: Double) = "${(x * 100).roundToInt()}%"

@Composable
private fun ResultsCard(d: InsightsData) {
    AppCard(Modifier.fillMaxWidth()) {
        CardTitle("Results", "${"%,d".format(d.overall.games)} games · score ${pct(d.overall.score)}")
        ResultSplitBar(d.overall.wins, d.overall.draws, d.overall.losses)
    }
}

@Composable
private fun AccuracyCard(d: InsightsData) {
    AppCard(Modifier.fillMaxWidth()) {
        if (d.trend.size < 2) {
            CardTitle("Accuracy trend")
            Text(
                if (d.reviewed == 0) "Reviews are still being prepared. Your accuracy trend appears after a couple of games are analysed."
                else "One more review and your trend appears here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@AppCard
        }
        val avg = d.trend.map { it.second }.average()
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text("Accuracy trend", style = MaterialTheme.typography.titleMedium)
                Text("Dots: each reviewed game · line: 5-game average", style = chartCaption)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(Format.percent(avg), style = MaterialTheme.typography.headlineSmall)
                Text("average", style = chartCaption)
            }
        }
        Spacer(Modifier.height(12.dp))
        TrendChart(d.trend.map { (t, v) -> TrendPoint(v.toFloat(), Format.shortDate(t)) })
    }
}

@Composable
private fun MistakesCard(d: InsightsData) {
    AppCard(Modifier.fillMaxWidth()) {
        CardTitle("Errors per game", "Average over ${d.reviewed} reviewed game${if (d.reviewed == 1) "" else "s"} — your moves only")
        val max = listOf(d.avgInaccuracies, d.avgMistakes, d.avgBlunders, d.avgMisses).maxOrNull()?.coerceAtLeast(1.0) ?: 1.0
        listOf(
            MoveClass.INACCURACY to d.avgInaccuracies,
            MoveClass.MISTAKE to d.avgMistakes,
            MoveClass.BLUNDER to d.avgBlunders,
            MoveClass.MISS to d.avgMisses,
        ).forEach { (c, v) ->
            BarRow(
                label = when (c) {
                    MoveClass.INACCURACY -> "Inaccuracies"
                    MoveClass.MISS -> "Missed chances"
                    else -> c.label + "s"
                },
                value = String.format(Locale.US, "%.1f", v),
                fraction = (v / max).toFloat(),
                color = classColor(c),
                leading = { ClassBadge(c, size = 22.dp) },
            )
        }
    }
}

@Composable
private fun ImprovementCard(i: Improvement) {
    AppCard(Modifier.fillMaxWidth()) {
        CardTitle("Recent form", "Your last 10 reviewed games vs the 10 before")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Delta("Accuracy", Format.percent(i.recentAccuracy), i.recentAccuracy - i.previousAccuracy, higherIsBetter = true, Modifier.weight(1f))
            Delta("Blunders / game", String.format(Locale.US, "%.1f", i.recentBlunders), i.recentBlunders - i.previousBlunders, higherIsBetter = false, Modifier.weight(1f))
        }
    }
}

@Composable
private fun Delta(label: String, value: String, delta: Double, higherIsBetter: Boolean, modifier: Modifier) {
    val better = if (higherIsBetter) delta >= 0 else delta <= 0
    val color = if (abs(delta) < 0.05) AppTheme.colors.draw else if (better) AppTheme.colors.win else AppTheme.colors.loss
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.headlineSmall)
        Text(
            "${if (delta >= 0) "▲" else "▼"} ${String.format(Locale.US, "%.1f", abs(delta))} ${if (better) "better" else "worse"}",
            style = MaterialTheme.typography.labelMedium.numeric,
            color = color,
        )
    }
}

@Composable
private fun TimeClassCard(d: InsightsData) {
    AppCard(Modifier.fillMaxWidth()) {
        CardTitle("By time control", "Score = points per game (win 1, draw ½)")
        d.byTimeClass.forEach { (tc, s) ->
            BarRow(
                label = Format.timeClass(tc),
                caption = "${s.games} games · ${s.wins}W ${s.draws}D ${s.losses}L" + (s.avgAccuracy?.let { " · ${Format.percent(it)}% acc." } ?: ""),
                value = pct(s.score),
                fraction = s.score.toFloat(),
            )
        }
    }
}

@Composable
private fun OpeningsCard(d: InsightsData) {
    AppCard(Modifier.fillMaxWidth()) {
        CardTitle("Openings", "Your most played, with your score")
        if (d.openingsWhite.isNotEmpty()) {
            Text("As White", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            d.openingsWhite.forEach { OpeningRow(it) }
        }
        if (d.openingsBlack.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("As Black", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            d.openingsBlack.forEach { OpeningRow(it) }
        }
    }
}

@Composable
private fun OpeningRow(o: OpeningStat) {
    BarRow(
        label = o.name,
        caption = "${o.split.games} games" + (o.split.avgAccuracy?.let { " · ${Format.percent(it)}% acc." } ?: ""),
        value = pct(o.split.score),
        fraction = o.split.score.toFloat(),
    )
}

@Composable
private fun WeaknessCard(d: InsightsData, onPracticeTheme: (String) -> Unit) {
    AppCard(Modifier.fillMaxWidth()) {
        CardTitle("Recurring weaknesses", "Patterns found in your errors")
        val max = d.weaknesses.maxOf { it.count }.toFloat()
        d.weaknesses.take(6).forEach { w ->
            BarRow(label = w.theme.label, value = "${w.count}", fraction = w.count / max, color = MaterialTheme.colorScheme.tertiary)
        }
        Spacer(Modifier.height(10.dp))
        val top = d.weaknesses.first()
        Row(verticalAlignment = Alignment.Top) {
            AppIcon(R.drawable.ic_lightbulb, null, tint = MaterialTheme.colorScheme.tertiary, size = 20.dp)
            Spacer(Modifier.width(10.dp))
            Text(top.advice, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        }
        TextButton(onClick = { onPracticeTheme(top.theme.name) }, contentPadding = PaddingValues(horizontal = 0.dp)) {
            Text("Practise ${top.theme.label.lowercase()}")
        }
    }
}
