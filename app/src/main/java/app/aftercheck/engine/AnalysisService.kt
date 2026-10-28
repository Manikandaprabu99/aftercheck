package app.aftercheck.engine

import android.content.Context
import androidx.room.withTransaction
import app.aftercheck.chess.GameLine
import app.aftercheck.chess.Move
import app.aftercheck.chess.OpeningBook
import app.aftercheck.chess.Opening
import app.aftercheck.chess.Pgn
import app.aftercheck.chess.PgnException
import app.aftercheck.chess.ReplayResult
import app.aftercheck.chess.Side
import app.aftercheck.chess.analysis.GameReview
import app.aftercheck.chess.analysis.GameReviewer
import app.aftercheck.chess.analysis.MoveClass
import app.aftercheck.chess.analysis.PositionEval
import app.aftercheck.chess.analysis.encode
import app.aftercheck.chess.analysis.expectedPoints
import app.aftercheck.chess.engine.EngineException
import app.aftercheck.chess.engine.EngineSettings
import app.aftercheck.chess.engine.GameAnalyzer
import app.aftercheck.chess.engine.PositionEvaluator
import app.aftercheck.chess.engine.UciEngine
import app.aftercheck.chess.engine.UciPositionEvaluator
import app.aftercheck.data.db.AppDatabase
import app.aftercheck.data.db.EvalCacheDao
import app.aftercheck.data.db.EvalCacheEntity
import app.aftercheck.data.db.GameEntity
import app.aftercheck.data.db.GameStatus
import app.aftercheck.data.db.PracticeEntity
import app.aftercheck.data.db.Priority
import app.aftercheck.data.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Loads the opening table from assets once, off the main thread. */
class OpeningBookProvider(private val context: Context) {
    private val mutex = Mutex()
    @Volatile private var book: OpeningBook? = null

    suspend fun get(): OpeningBook = book ?: mutex.withLock {
        book ?: withContext(Dispatchers.Default) {
            context.assets.open("openings.tsv").reader().use { OpeningBook.parse(it) }
        }.also { book = it }
    }
}

/** Reuses evaluations of positions already searched with the same settings (openings repeat a lot). */
class CachedEvaluator(
    private val inner: PositionEvaluator,
    private val cache: EvalCacheDao,
    private val settingsKey: String,
) : PositionEvaluator {
    var engineCalls = 0
        private set

    override suspend fun evaluate(line: GameLine, index: Int): PositionEval {
        val position = line.positions[index]
        val key = "%016x|%s".format(position.zobrist, settingsKey)
        cache.get(key)?.let { cached -> ReviewCodec.decodeEval(cached.encoded, position)?.let { return it } }
        val eval = inner.evaluate(line, index)
        engineCalls++
        cache.put(EvalCacheEntity(key, ReviewCodec.encodeEval(eval), System.currentTimeMillis()))
        return eval
    }
}

sealed interface AnalysisOutcome {
    data object Done : AnalysisOutcome
    data class Skipped(val reason: String) : AnalysisOutcome
    data class Failed(val reason: String, val willRetry: Boolean) : AnalysisOutcome
    /** Stopped between positions so a game the user picked can go first; finished positions stay cached. */
    data object Yielded : AnalysisOutcome
}

private class YieldRequest : Exception()

/**
 * Analyses one stored game with a running engine and persists the review, move list and practice
 * positions in a single transaction.
 */
class AnalysisService(
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val files: EngineFiles,
    private val books: OpeningBookProvider,
) {
    private val reviewer = GameReviewer()

    /** Starts an engine process for a batch of games and always shuts it down afterwards. */
    suspend fun <T> withEngine(engineSettings: EngineSettings, block: suspend (UciEngine) -> T): T {
        val engine = UciEngine(files.launcher())
        try {
            engine.start(
                mapOf(
                    "EvalFile" to files.networkFile.absolutePath,
                    "Threads" to engineSettings.threads.toString(),
                    "Hash" to engineSettings.hashMb.toString(),
                ),
            )
            return block(engine)
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { engine.close() }
        }
    }

    suspend fun analyze(
        game: GameEntity,
        engine: UciEngine,
        engineSettings: EngineSettings,
        shouldYield: suspend () -> Boolean = { false },
    ): AnalysisOutcome {
        val games = db.games()
        val pgn = try {
            Pgn.parse(game.pgn)
        } catch (e: PgnException) {
            games.setStatus(game.id, GameStatus.FAILED, "Invalid PGN: ${e.message}", 0)
            games.unpick(game.id)
            return AnalysisOutcome.Failed("Invalid PGN", willRetry = false)
        }
        val line = when (val replay = GameLine.fromPgn(pgn)) {
            is ReplayResult.Success -> replay.line
            is ReplayResult.Failure -> {
                games.setStatus(game.id, GameStatus.FAILED, replay.reason, 0)
                games.unpick(game.id)
                return AnalysisOutcome.Failed(replay.reason, willRetry = false)
            }
        }
        if (line.plyCount == 0) {
            games.setStatus(game.id, GameStatus.SKIPPED, "No moves were played", 0)
            games.unpick(game.id)
            return AnalysisOutcome.Skipped("No moves")
        }

        games.setStatus(game.id, GameStatus.ANALYZING, null, 0)
        val started = System.currentTimeMillis()
        val evaluator = CachedEvaluator(UciPositionEvaluator(engine, engineSettings), db.evalCache(), engineSettings.key)
        try {
            engine.newGame()
            var lastReported = -1
            val evals = GameAnalyzer(evaluator).evaluateAll(line) { done, total ->
                val pct = done * 100 / total
                if (pct >= lastReported + 3 || done == total) {
                    games.setProgress(game.id, pct)
                    lastReported = pct
                }
                if (done < total && shouldYield()) throw YieldRequest()
            }
            val book = books.get()
            val match = book.match(line)
            val (base, increment) = GameReviewer.parseTimeControl(game.timeControl) ?: (null to 0L)
            val userSide = game.userSide
            val review = reviewer.review(
                line = line,
                evals = evals,
                clocks = pgn.moves.map { it.clockMillis },
                opening = match.opening ?: game.openingName?.let { Opening(game.eco ?: "", it) },
                bookPlies = match.bookPlies,
                baseTimeMillis = base,
                incrementMillis = increment,
                focus = userSide,
            )
            val elapsed = System.currentTimeMillis() - started
            persist(game, line, evals, review, userSide, engineSettings, engine.engineName ?: EngineFiles.ENGINE_NAME, elapsed)
            if (evaluator.engineCalls > 0) settings.recordAnalysisSpeed(evaluator.engineCalls, elapsed)
            pruneCache()
            return AnalysisOutcome.Done
        } catch (e: YieldRequest) {
            games.setStatus(game.id, GameStatus.QUEUED, null, 0)
            return AnalysisOutcome.Yielded
        } catch (e: CancellationException) {
            withContext(NonCancellable) { games.setStatus(game.id, GameStatus.QUEUED, null, 0) }
            throw e
        } catch (e: EngineException) {
            val attempts = game.attempts + 1
            val retry = attempts < MAX_ATTEMPTS
            games.update(
                game.copy(
                    status = if (retry) GameStatus.QUEUED else GameStatus.FAILED,
                    statusMessage = e.message,
                    attempts = attempts,
                    priority = if (retry) Priority.BACKLOG else game.priority,
                    progress = 0,
                    queueOrder = if (retry) game.queueOrder else null,
                ),
            )
            return AnalysisOutcome.Failed(e.message ?: "Engine error", retry)
        }
    }

    private suspend fun persist(
        game: GameEntity, line: GameLine, evals: List<PositionEval>, review: GameReview, userSide: Side?,
        engineSettings: EngineSettings, engineName: String, elapsed: Long,
    ) {
        val now = System.currentTimeMillis()
        val user = userSide?.let { review.summary(it) }
        val opponent = userSide?.let { review.summary(it.opposite) }
        db.withTransaction {
            db.reviews().deleteMoves(game.id)
            db.reviews().insertMoves(ReviewCodec.moveEntities(game.id, review, userSide))
            db.reviews().insertReview(
                ReviewCodec.reviewEntity(game.id, review, engineName, engineSettings.key, engineSettings.depth, elapsed, now),
            )
            db.practice().insertIgnore(practicePositions(game, line, evals, review, userSide, now))
            db.games().update(
                game.copy(
                    status = GameStatus.READY,
                    statusMessage = null,
                    progress = 100,
                    analysisKey = engineSettings.key,
                    eco = review.opening?.eco?.takeIf { it.isNotBlank() } ?: game.eco,
                    openingName = review.opening?.name ?: game.openingName,
                    openingFamily = (review.opening?.name ?: game.openingName)?.substringBefore(':')?.trim(),
                    openingLower = (review.opening?.name ?: game.openingName)?.lowercase(),
                    whiteAccuracy = review.white.accuracy,
                    blackAccuracy = review.black.accuracy,
                    userAccuracy = user?.accuracy,
                    opponentAccuracy = opponent?.accuracy,
                    userBest = user?.count(MoveClass.BEST),
                    userInaccuracies = user?.count(MoveClass.INACCURACY),
                    userMistakes = user?.count(MoveClass.MISTAKE),
                    userBlunders = user?.count(MoveClass.BLUNDER),
                    userMisses = user?.let { it.count(MoveClass.MISS) + it.count(MoveClass.MISSED_MATE) },
                    analyzedAt = now,
                    queueOrder = null,
                ),
            )
        }
    }

    /** The user's serious errors become practice positions: find the move you missed. */
    private fun practicePositions(
        game: GameEntity, line: GameLine, evals: List<PositionEval>, review: GameReview, userSide: Side?, now: Long,
    ): List<PracticeEntity> {
        userSide ?: return emptyList()
        return review.moves.filter { it.side == userSide && it.classification.isSerious && it.bestMove != null }.mapNotNull { m ->
            val before = line.positions[m.ply - 1]
            if (before.legalMoves().size < 2) return@mapNotNull null
            val eval = evals[m.ply - 1]
            val bestPoints = eval.score.expectedPoints(userSide)
            val accepted = eval.lines.drop(1)
                .filter { bestPoints - it.score.expectedPoints(userSide) <= ACCEPT_MARGIN }
                .mapNotNull { it.pv.firstOrNull()?.uci }
            PracticeEntity(
                gameId = game.id,
                ply = m.ply,
                fen = before.toFen(),
                sideToMove = if (userSide.isWhite) "w" else "b",
                playedUci = m.move.uci,
                playedSan = m.san,
                solutionUci = m.bestMove!!.uci,
                solutionSan = m.bestSan ?: m.bestMove!!.uci,
                acceptedUci = accepted.joinToString(","),
                solutionLine = m.bestLine.joinToString(" "),
                classification = m.classification.name,
                themes = m.themes.joinToString(",") { it.name },
                explanation = m.explanation,
                scoreBefore = m.scoreBefore.encode(),
                scoreAfter = m.scoreAfter.encode(),
                createdAt = now,
                dueAt = now,
            )
        }
    }

    private suspend fun pruneCache() {
        val dao = db.evalCache()
        val count = dao.count()
        if (count > CACHE_LIMIT) dao.deleteOldest(count - CACHE_LIMIT + CACHE_LIMIT / 10)
    }

    companion object {
        const val MAX_ATTEMPTS = 3
        const val CACHE_LIMIT = 60_000
        /** Alternatives within 3 expected points of the best move also count as solutions. */
        const val ACCEPT_MARGIN = 0.03
    }
}

val GameEntity.userSide: Side?
    get() = when (userColor) {
        "w" -> Side.WHITE
        "b" -> Side.BLACK
        else -> null
    }

/** Parses a UCI string and checks it's legal in [fen]; null otherwise. */
fun legalMoveIn(fen: String, uci: String): Move? = try {
    val pos = app.aftercheck.chess.Position.fromFen(fen)
    Move.fromUci(uci)?.takeIf { pos.isLegal(it) }
} catch (e: IllegalArgumentException) {
    null
}
