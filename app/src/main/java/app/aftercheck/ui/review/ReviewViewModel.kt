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
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
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
) {
    /** The engine preferred another move, so there is a better line to show. */
    val hasBetter: Boolean get() = bestSan != null && moveClass != MoveClass.BEST && moveClass != MoveClass.BOOK
}

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
    /** The user's inaccuracies and worse (both sides' when the account played neither). */
    val userErrors: List<ReviewMove> get() = moves.filter { it.moveClass.isError && (userSide == null || it.side == userSide) }
    fun move(ply: Int): ReviewMove? = moves.getOrNull(ply - 1)
}

enum class ReviewTab { MOVES, REPORT }

enum class LineKind { BEST, PLAYED, ALTERNATIVE }

/** An engine line played out on the board from [start]; the first [index] moves are on the board. */
data class LinePlayback(
    val kind: LineKind,
    val title: String,
    val ply: Int,
    val start: Position,
    val moves: List<Move>,
    val sans: List<String>,
    val score: Score?,
    val index: Int = 0,
    val playing: Boolean = true,
) {
    val position: Position get() = moves.take(index).fold(start) { p, m -> p.play(m) }
    val lastMove: Move? get() = moves.getOrNull(index - 1)
    val nextMove: Move? get() = moves.getOrNull(index)
    val finished: Boolean get() = index >= moves.size
}

data class ReviewUiState(
    val data: ReviewData? = null,
    val ply: Int = 0,
    /** User's explicit orientation choice; by default the board is seen from the user's side. */
    val flipOverride: Boolean? = null,
    /** Chosen tab; until the user picks one, analysed games open on the report. */
    val tab: ReviewTab? = null,
    val line: LinePlayback? = null,
    /** The move that led to the position now on the board (reversed when stepping back), so the piece glides. */
    val animate: Move? = null,
    val playing: Boolean = false,
    val queueRank: Int? = null,
    val settings: AppSettings = AppSettings(),
    val notFound: Boolean = false,
    val loading: Boolean = true,
) {
    val flipped: Boolean get() = flipOverride ?: (data?.userSide == Side.BLACK)
    val currentTab: ReviewTab get() = tab ?: if (data?.analysed == true) ReviewTab.REPORT else ReviewTab.MOVES
}

private fun Move.reversed() = Move(to, from)

/** The stored coach text without the engine line it may end with; the board plays that line now. */
fun coachText(explanation: String): String = explanation.substringBefore(" Engine line:").trim()

@OptIn(ExperimentalCoroutinesApi::class)
class ReviewViewModel(private val c: AppContainer, private val gameId: String) : ViewModel() {
    private val ui = MutableStateFlow(ReviewUiState())
    private var replayJob: Job? = null
    private var lineJob: Job? = null

    private val data = combine(
        c.db.games().observe(gameId),
        c.db.reviews().observeReview(gameId),
        c.db.reviews().observeMoves(gameId),
    ) { game, review, moves -> Triple(game, review, moves) }
        .distinctUntilChanged()
        .mapLatest { (game, review, moves) -> game?.let { build(it, review, moves) } }

    val state: StateFlow<ReviewUiState> = combine(ui, data, c.settings.settings, c.db.games().observeQueueRank(gameId)) { u, d, s, rank ->
        u.copy(data = d, settings = s, queueRank = rank, notFound = d == null, loading = false)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReviewUiState())

    init {
        // Analysed games open on the report; the tab then stays put, even when a pending game finishes.
        viewModelScope.launch {
            val first = data.filterNotNull().first()
            ui.update { if (it.tab == null) it.copy(tab = if (first.analysed) ReviewTab.REPORT else ReviewTab.MOVES) else it }
        }
    }

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

    private val gameLine: GameLine? get() = state.value.data?.line
    private val plyCount: Int get() = state.value.data?.plyCount ?: 0

    fun selectTab(tab: ReviewTab) {
        if (tab == ReviewTab.REPORT) {
            stopReplay()
            exitLine()
        }
        ui.update { it.copy(tab = tab) }
    }

    /** Shows game position [ply] and leaves any line; single steps glide. */
    private fun show(ply: Int) {
        val line = gameLine ?: return
        val target = ply.coerceIn(0, plyCount)
        cancelLine()
        ui.update { s ->
            val animate = when {
                s.line != null -> null
                target == s.ply + 1 -> line.moves[s.ply]
                target == s.ply - 1 -> line.moves[target].reversed()
                else -> null
            }
            s.copy(ply = target, line = null, animate = animate)
        }
    }

    fun select(ply: Int) {
        stopReplay()
        show(ply)
    }

    fun openMoves(ply: Int) {
        ui.update { it.copy(tab = ReviewTab.MOVES) }
        select(ply)
    }

    fun next() {
        if (ui.value.line != null) stepLine(1) else select(ui.value.ply + 1)
    }

    fun previous() {
        if (ui.value.line != null) stepLine(-1) else select(ui.value.ply - 1)
    }

    fun first() = select(0)
    fun last() = select(plyCount)

    fun flip() {
        val current = state.value.flipped
        ui.update { it.copy(flipOverride = !current) }
    }

    /** Jumps to the user's next inaccuracy or worse, wrapping around to the first. */
    fun nextMistake() {
        val errors = state.value.data?.userErrors.orEmpty()
        val current = ui.value.ply
        val target = errors.firstOrNull { it.ply > current } ?: errors.firstOrNull() ?: return
        select(target.ply)
    }

    /** Plays an engine line for the move at the current ply on the board, one move at a time. */
    fun showLine(kind: LineKind, alternative: Int = 0) {
        val d = state.value.data ?: return
        val line = d.line ?: return
        val ply = ui.value.ply
        val m = d.move(ply) ?: return
        val (start, sans, score) = when (kind) {
            LineKind.BEST -> Triple(line.positions[ply - 1], m.bestLine, m.scoreBefore)
            LineKind.PLAYED -> Triple(line.positions[ply], m.replyLine, m.scoreAfter)
            LineKind.ALTERNATIVE -> m.alternatives.getOrNull(alternative)?.let { Triple(line.positions[ply - 1], it.san, it.score) } ?: return
        }
        val moves = ArrayList<Move>()
        var pos = start
        for (san in sans) {
            val move = pos.parseSan(san) ?: break
            moves += move
            pos = pos.play(move)
        }
        if (moves.isEmpty()) return
        val title = when (kind) {
            LineKind.BEST -> "Best line"
            LineKind.PLAYED -> "After ${m.san}"
            LineKind.ALTERNATIVE -> "Alternative ${alternative + 1}"
        }
        stopReplay()
        cancelLine()
        // Lines from the position before the move start by taking the played move back.
        val enter = if (kind == LineKind.PLAYED) null else line.moves[ply - 1].reversed()
        ui.update { it.copy(line = LinePlayback(kind, title, ply, start, moves, sans.take(moves.size), score), animate = enter) }
        runLine(LINE_START_DELAY)
    }

    /** Pauses a playing line, resumes a paused one, and replays a finished one. */
    fun toggleLine() {
        val l = ui.value.line ?: return
        when {
            l.playing -> {
                cancelLine()
                ui.update { it.copy(line = it.line?.copy(playing = false)) }
            }
            l.finished -> replayLine()
            else -> {
                ui.update { it.copy(line = l.copy(playing = true)) }
                runLine(LINE_RESUME_DELAY)
            }
        }
    }

    fun replayLine() {
        val l = ui.value.line ?: return
        cancelLine()
        ui.update { it.copy(line = l.copy(index = 0, playing = true), animate = null) }
        runLine(LINE_START_DELAY)
    }

    /** Steps through the line by hand; stepping back past its start returns to the game. */
    fun stepLine(delta: Int) {
        val l = ui.value.line ?: return
        if (l.index + delta < 0) exitLine() else jumpInLine(l.index + delta)
    }

    fun jumpInLine(index: Int) {
        val l = ui.value.line ?: return
        cancelLine()
        val target = index.coerceIn(0, l.moves.size)
        val animate = when (target) {
            l.index + 1 -> l.moves[l.index]
            l.index - 1 -> l.moves[target].reversed()
            else -> null
        }
        ui.update { it.copy(line = l.copy(index = target, playing = false), animate = animate) }
    }

    fun exitLine() {
        cancelLine()
        ui.update { it.copy(line = null, animate = null) }
    }

    private fun cancelLine() {
        lineJob?.cancel()
        lineJob = null
    }

    private fun runLine(initialDelay: Long) {
        cancelLine()
        lineJob = viewModelScope.launch {
            delay(initialDelay)
            while (true) {
                val l = ui.value.line ?: return@launch
                if (l.finished) break
                val next = l.copy(index = l.index + 1)
                // The last move stops playback at once, so the controls never show Pause on a finished line.
                ui.update { it.copy(line = next.copy(playing = !next.finished), animate = l.nextMove) }
                if (next.finished) return@launch
                delay(LINE_STEP_MILLIS)
            }
            ui.update { s -> s.copy(line = s.line?.copy(playing = false)) }
        }
    }

    /** Plays the game on from the current move (from the start when at the end). */
    fun toggleReplay() {
        if (replayJob?.isActive == true) {
            stopReplay()
            return
        }
        exitLine()
        if (ui.value.ply >= plyCount) show(0)
        ui.update { it.copy(playing = true, tab = ReviewTab.MOVES) }
        replayJob = viewModelScope.launch {
            while (ui.value.ply < plyCount) {
                delay(GAME_STEP_MILLIS)
                show(ui.value.ply + 1)
            }
            ui.update { it.copy(playing = false) }
        }
    }

    fun stopReplay() {
        replayJob?.cancel()
        replayJob = null
        ui.update { it.copy(playing = false) }
    }

    /** Moves this game to the front of the user's picks. */
    fun analyseNext() = viewModelScope.launch { c.queue.pickFirst(gameId) }

    fun reanalyse() = viewModelScope.launch {
        c.db.games().requeue(gameId, Priority.BACKLOG)
        c.queue.pickFirst(gameId)
    }

    fun delete(onDone: () -> Unit) = viewModelScope.launch {
        c.db.games().delete(gameId)
        onDone()
    }

    companion object {
        const val LINE_START_DELAY = 700L
        const val LINE_RESUME_DELAY = 300L
        const val LINE_STEP_MILLIS = 1_100L
        const val GAME_STEP_MILLIS = 900L
    }
}
