package app.aftercheck.ui.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.aftercheck.AppContainer
import app.aftercheck.chess.GameLine
import app.aftercheck.chess.Move
import app.aftercheck.chess.Pgn
import app.aftercheck.chess.PgnException
import app.aftercheck.chess.Position
import app.aftercheck.chess.ReplayResult
import app.aftercheck.chess.Side
import app.aftercheck.chess.analysis.AlternativeLine
import app.aftercheck.chess.analysis.MoveClass
import app.aftercheck.chess.analysis.Recommendation
import app.aftercheck.chess.analysis.Score
import app.aftercheck.chess.analysis.Theme
import app.aftercheck.chess.parseSan
import app.aftercheck.data.db.GameEntity
import app.aftercheck.data.db.MoveEntity
import app.aftercheck.data.db.Priority
import app.aftercheck.data.db.ReviewEntity
import app.aftercheck.data.settings.AppSettings
import app.aftercheck.engine.ReviewCodec
import app.aftercheck.engine.userSide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ReviewMove(
    val ply: Int,
    val side: Side,
    val san: String,
    val moveClass: MoveClass,
    val scoreBefore: Score,
    val scoreAfter: Score,
    val bestMove: Move?,
    val bestSan: String?,
    val bestLine: List<String>,
    val replyLine: List<String>,
    val alternatives: List<AlternativeLine>,
    val explanation: String,
    val themes: Set<Theme>,
    val clockMillis: Long?,
    val timeSpentMillis: Long?,
    val accuracy: Double,
)

data class ReviewData(
    val game: GameEntity,
    val line: GameLine?,
    val invalidReason: String?,
    val clocks: List<Long?>,
    val moves: List<ReviewMove>,
    val review: ReviewEntity?,
) {
    val analysed: Boolean get() = review != null && moves.size == (line?.plyCount ?: -1)
    val plyCount: Int get() = line?.plyCount ?: 0
    val userSide: Side? get() = game.userSide
    val winChances: List<Float> get() = review?.let { ReviewCodec.decodeWinChances(it.winChances) }.orEmpty()
    val whiteCounts: Map<MoveClass, Int> get() = review?.let { ReviewCodec.decodeCounts(it.whiteCounts) }.orEmpty()
    val blackCounts: Map<MoveClass, Int> get() = review?.let { ReviewCodec.decodeCounts(it.blackCounts) }.orEmpty()
    val recommendations: List<Recommendation> get() = review?.let { ReviewCodec.decodeRecommendations(it.recommendations) }.orEmpty()
    val turningPoints: List<Int> get() = review?.let { ReviewCodec.decodeInts(it.turningPoints) }.orEmpty()
    fun move(ply: Int): ReviewMove? = moves.getOrNull(ply - 1)
}

/** A line being stepped through on the board (an engine continuation). */
data class Variation(val title: String, val start: Position, val moves: List<Move>, val sans: List<String>, val index: Int) {
    val position: Position get() = moves.take(index).fold(start) { p, m -> p.play(m) }
    val lastMove: Move? get() = moves.getOrNull(index - 1)
}

data class ReviewUiState(
    val data: ReviewData? = null,
    val ply: Int = 0,
    /** User's explicit orientation choice; by default the board is seen from the user's side. */
    val flipOverride: Boolean? = null,
    val previousPly: Int = 0,
    val variation: Variation? = null,
    val playing: Boolean = false,
    val settings: AppSettings = AppSettings(),
    val notFound: Boolean = false,
    val loading: Boolean = true,
) {
    val flipped: Boolean get() = flipOverride ?: (data?.userSide == Side.BLACK)
}

@OptIn(ExperimentalCoroutinesApi::class)
class ReviewViewModel(private val c: AppContainer, private val gameId: String) : ViewModel() {
    private val ui = MutableStateFlow(ReviewUiState())
    private var player: Job? = null

    private val data = combine(
        c.db.games().observe(gameId),
        c.db.reviews().observeReview(gameId),
        c.db.reviews().observeMoves(gameId),
    ) { game, review, moves -> Triple(game, review, moves) }
        .distinctUntilChanged()
        .mapLatest { (game, review, moves) -> game?.let { build(it, review, moves) } }

    val state: StateFlow<ReviewUiState> = combine(ui, data, c.settings.settings) { u, d, s ->
        u.copy(data = d, settings = s, notFound = d == null, loading = false)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReviewUiState())

    private suspend fun build(game: GameEntity, review: ReviewEntity?, moves: List<MoveEntity>): ReviewData =
        kotlinx.coroutines.withContext(Dispatchers.Default) {
            val pgn = try { Pgn.parse(game.pgn) } catch (e: PgnException) { null }
            val replay = pgn?.let { GameLine.fromPgn(it) }
            val line = (replay as? ReplayResult.Success)?.line
            val reason = when {
                pgn == null -> "This game's PGN could not be read."
                replay is ReplayResult.Failure -> replay.reason
                else -> null
            }
            val decoded = if (line != null && moves.size == line.plyCount) moves.map { m -> decode(m, line) } else emptyList()
            ReviewData(game, line, reason, pgn?.moves?.map { it.clockMillis }.orEmpty(), decoded, review)
        }

    private fun decode(m: MoveEntity, line: GameLine): ReviewMove {
        val before = line.positions[m.ply - 1]
        val best = m.bestUci?.let { Move.fromUci(it) }?.takeIf { before.isLegal(it) }
        return ReviewMove(
            ply = m.ply,
            side = before.sideToMove,
            san = m.san,
            moveClass = MoveClass.fromName(m.classification) ?: MoveClass.GOOD,
            scoreBefore = Score.parse(m.scoreBefore) ?: Score.Cp(0),
            scoreAfter = Score.parse(m.scoreAfter) ?: Score.Cp(0),
            bestMove = best,
            bestSan = m.bestSan,
            bestLine = m.bestLine.split(' ').filter { it.isNotEmpty() },
            replyLine = m.replyLine.split(' ').filter { it.isNotEmpty() },
            alternatives = ReviewCodec.decodeAlternatives(m.alternatives),
            explanation = m.explanation,
            themes = Theme.decode(m.themes),
            clockMillis = m.clockMillis,
            timeSpentMillis = m.timeSpentMillis,
            accuracy = m.accuracy,
        )
    }

    private val plyCount: Int get() = state.value.data?.plyCount ?: 0

    fun select(ply: Int) {
        val target = ply.coerceIn(0, plyCount)
        ui.update { it.copy(previousPly = it.ply, ply = target, variation = null) }
    }

    fun next() {
        val v = ui.value.variation
        if (v != null) {
            if (v.index < v.moves.size) ui.update { it.copy(variation = v.copy(index = v.index + 1)) }
            return
        }
        select(ui.value.ply + 1)
    }

    fun previous() {
        val v = ui.value.variation
        if (v != null) {
            if (v.index > 0) ui.update { it.copy(variation = v.copy(index = v.index - 1)) } else exitVariation()
            return
        }
        select(ui.value.ply - 1)
    }

    fun first() = select(0)
    fun last() = select(plyCount)
    fun flip() {
        val current = state.value.flipped
        ui.update { it.copy(flipOverride = !current) }
    }

    fun nextOfClass(predicate: (MoveClass) -> Boolean, sideOnly: Side? = null) {
        val d = state.value.data ?: return
        val current = ui.value.ply
        val candidates = d.moves.filter { predicate(it.moveClass) && (sideOnly == null || it.side == sideOnly) }
        val target = candidates.firstOrNull { it.ply > current } ?: candidates.firstOrNull() ?: return
        select(target.ply)
    }

    /** Step through an engine line. [fromBefore] = line starts in the position before the selected move. */
    fun playLine(title: String, sans: List<String>, fromBefore: Boolean) {
        val d = state.value.data ?: return
        val line = d.line ?: return
        val ply = ui.value.ply
        val start = if (fromBefore) line.positions[(ply - 1).coerceAtLeast(0)] else line.positions[ply]
        var pos = start
        val moves = ArrayList<Move>()
        for (san in sans) {
            val m = pos.parseSan(san) ?: break
            moves += m
            pos = pos.play(m)
        }
        if (moves.isEmpty()) return
        stopReplay()
        ui.update { it.copy(variation = Variation(title, start, moves, sans.take(moves.size), 1)) }
    }

    fun exitVariation() = ui.update { it.copy(variation = null) }

    fun toggleReplay() {
        if (player?.isActive == true) { stopReplay(); return }
        if (ui.value.ply >= plyCount) select(0)
        ui.update { it.copy(playing = true, variation = null) }
        player = viewModelScope.launch {
            while (ui.value.ply < plyCount) {
                delay(900)
                select(ui.value.ply + 1)
            }
            ui.update { it.copy(playing = false) }
        }
    }

    fun stopReplay() {
        player?.cancel()
        ui.update { it.copy(playing = false) }
    }

    fun analyseNow() = viewModelScope.launch {
        c.db.games().requeue(gameId, Priority.USER_REQUESTED)
        c.scheduler.enqueueAnalysis()
    }

    fun reanalyse() = viewModelScope.launch {
        c.db.games().requeue(gameId, Priority.USER_REQUESTED)
        c.scheduler.enqueueAnalysis()
    }

    fun delete(onDone: () -> Unit) = viewModelScope.launch {
        c.db.games().delete(gameId)
        onDone()
    }
}
