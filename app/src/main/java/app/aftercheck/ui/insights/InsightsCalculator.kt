package app.aftercheck.ui.insights

import app.aftercheck.chess.analysis.GameReviewer
import app.aftercheck.chess.analysis.Theme
import app.aftercheck.data.db.InsightRow

data class Split(val games: Int, val wins: Int, val draws: Int, val losses: Int, val avgAccuracy: Double?) {
    /** Points scored per game, 0..1 (win = 1, draw = ½). */
    val score: Double get() = if (games == 0) 0.0 else (wins + draws * 0.5) / games
}

data class OpeningStat(val name: String, val color: String?, val split: Split)

data class Weakness(val theme: Theme, val count: Int, val advice: String)

data class Improvement(val recentAccuracy: Double, val previousAccuracy: Double, val recentBlunders: Double, val previousBlunders: Double)

data class InsightsData(
    val overall: Split,
    val reviewed: Int,
    val trend: List<Pair<Long, Double>>,
    val avgInaccuracies: Double,
    val avgMistakes: Double,
    val avgBlunders: Double,
    val avgMisses: Double,
    val byTimeClass: List<Pair<String, Split>>,
    val openingsWhite: List<OpeningStat>,
    val openingsBlack: List<OpeningStat>,
    val improvement: Improvement?,
    val weaknesses: List<Weakness>,
)

/** Pure aggregation of stored games into the Insights screen's numbers. */
object InsightsCalculator {

    fun compute(rows: List<InsightRow>, themeRows: List<String>): InsightsData {
        val reviewed = rows.filter { it.status == "ready" && it.userAccuracy != null }
        return InsightsData(
            overall = split(rows),
            reviewed = reviewed.size,
            trend = reviewed.sortedBy { it.endTime }.takeLast(60).map { it.endTime to it.userAccuracy!! },
            avgInaccuracies = reviewed.averageOf { it.userInaccuracies },
            avgMistakes = reviewed.averageOf { it.userMistakes },
            avgBlunders = reviewed.averageOf { it.userBlunders },
            avgMisses = reviewed.averageOf { it.userMisses },
            byTimeClass = rows.groupBy { it.timeClass }
                .map { (tc, list) -> tc to split(list) }
                .sortedByDescending { it.second.games },
            openingsWhite = openings(rows.filter { it.userColor == "w" }, "w"),
            openingsBlack = openings(rows.filter { it.userColor == "b" }, "b"),
            improvement = improvement(reviewed.sortedBy { it.endTime }),
            weaknesses = weaknesses(themeRows),
        )
    }

    fun split(rows: List<InsightRow>): Split {
        val acc = rows.mapNotNull { it.userAccuracy }
        return Split(
            games = rows.size,
            wins = rows.count { it.userResult == "win" },
            draws = rows.count { it.userResult == "draw" },
            losses = rows.count { it.userResult == "loss" },
            avgAccuracy = acc.takeIf { it.isNotEmpty() }?.average(),
        )
    }

    private fun openings(rows: List<InsightRow>, color: String): List<OpeningStat> =
        rows.filter { !it.openingFamily.isNullOrBlank() }
            .groupBy { it.openingFamily!! }
            .map { (name, list) -> OpeningStat(name, color, split(list)) }
            .sortedWith(compareByDescending<OpeningStat> { it.split.games }.thenBy { it.name })
            .take(5)

    private fun improvement(reviewed: List<InsightRow>): Improvement? {
        if (reviewed.size < 10) return null
        val recent = reviewed.takeLast(10)
        val previous = reviewed.dropLast(10).takeLast(10).takeIf { it.size >= 5 } ?: return null
        return Improvement(
            recentAccuracy = recent.mapNotNull { it.userAccuracy }.average(),
            previousAccuracy = previous.mapNotNull { it.userAccuracy }.average(),
            recentBlunders = recent.averageOf { it.userBlunders },
            previousBlunders = previous.averageOf { it.userBlunders },
        )
    }

    fun weaknesses(themeRows: List<String>): List<Weakness> =
        themeRows.flatMap { Theme.decode(it) }
            .groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }
            .map { (theme, n) -> Weakness(theme, n, GameReviewer.recommendationText(theme, n, null)) }

    private inline fun List<InsightRow>.averageOf(f: (InsightRow) -> Int?): Double =
        if (isEmpty()) 0.0 else sumOf { f(it) ?: 0 }.toDouble() / size
}
