package app.aftercheck.chess.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedWriter
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class EngineException(message: String, cause: Throwable? = null) : Exception(message, cause)

data class SearchLimits(val depth: Int? = null, val movetimeMillis: Long? = null, val nodes: Long? = null) {
    init { require(depth != null || movetimeMillis != null || nodes != null) { "Search needs a limit" } }

    fun goCommand(): String = buildString {
        append("go")
        depth?.let { append(" depth ").append(it) }
        movetimeMillis?.let { append(" movetime ").append(it) }
        nodes?.let { append(" nodes ").append(it) }
    }
}

/** A parsed `info` line. Scores are from the side to move's point of view, as UCI defines. */
data class UciInfo(
    val multiPv: Int,
    val depth: Int,
    val cp: Int?,
    val mate: Int?,
    val bound: Boolean,
    val pv: List<String>,
)

data class UciSearchResult(val bestMove: String?, val lines: List<UciInfo>)

/**
 * Minimal, robust UCI client for an engine running as a child process.
 *
 * One search runs at a time. Coroutine cancellation sends `stop` and waits briefly for `bestmove`;
 * an unresponsive engine is killed and restarted on the next [start].
 */
class UciEngine(
    private val launcher: () -> Process,
    private val log: (String) -> Unit = {},
) : Closeable {
    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var output: Channel<String>? = null
    private val mutex = Mutex()

    @Volatile var engineName: String? = null
        private set

    val isRunning: Boolean get() = process?.isAlive == true

    suspend fun start(options: Map<String, String> = emptyMap()) = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (isRunning) return@withLock
            val p = try {
                launcher()
            } catch (e: IOException) {
                throw EngineException("Could not start the engine: ${e.message}", e)
            }
            process = p
            writer = p.outputStream.bufferedWriter()
            val channel = Channel<String>(Channel.UNLIMITED)
            output = channel
            thread(name = "uci-reader", isDaemon = true) {
                try {
                    p.inputStream.bufferedReader().forEachLine { channel.trySend(it) }
                } catch (_: IOException) {
                } finally {
                    channel.close()
                }
            }
            send("uci")
            awaitLine(15_000) { line ->
                if (line.startsWith("id name ")) engineName = line.removePrefix("id name ").trim()
                line == "uciok"
            } ?: run { kill(); throw EngineException("Engine did not answer 'uci'") }
            for ((name, value) in options) send("setoption name $name value $value")
            send("isready")
            awaitLine(60_000) { it == "readyok" } ?: run { kill(); throw EngineException("Engine did not become ready") }
        }
    }

    suspend fun setOption(name: String, value: String) = command("setoption name $name value $value")

    suspend fun newGame() = command("ucinewgame")

    private suspend fun command(cmd: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureRunning()
            send(cmd)
            send("isready")
            awaitLine(30_000) { it == "readyok" } ?: run { kill(); throw EngineException("Engine stopped responding") }
        }
    }

    /**
     * Searches the position reached from [startFen] after [moves] (UCI notation).
     * @param timeoutMillis hard limit after which the search is stopped and partial results returned.
     */
    suspend fun search(
        startFen: String,
        moves: List<String>,
        limits: SearchLimits,
        timeoutMillis: Long = (limits.movetimeMillis ?: 60_000L) + 15_000L,
    ): UciSearchResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureRunning()
            val channel = output!!
            while (channel.tryReceive().isSuccess) { /* drop stale output */ }

            val position = buildString {
                append("position fen ").append(startFen)
                if (moves.isNotEmpty()) append(" moves ").append(moves.joinToString(" "))
            }
            send(position)
            send(limits.goCommand())
            val latest = sortedMapOf<Int, UciInfo>()
            var bestMove: String? = null

            suspend fun readUntilBestMove(): Boolean {
                while (true) {
                    val line = channel.receiveCatching().getOrNull()
                        ?: throw EngineException("The engine process exited unexpectedly")
                    if (line.startsWith("bestmove")) {
                        bestMove = line.split(' ').getOrNull(1)?.takeIf { it != "(none)" }
                        return true
                    }
                    parseInfo(line)?.let { info -> if (!info.bound) latest[info.multiPv] = info }
                }
            }

            try {
                val finished = withTimeoutOrNull(timeoutMillis) { readUntilBestMove() }
                if (finished == null) {
                    send("stop")
                    withTimeoutOrNull(5_000) { readUntilBestMove() }
                        ?: run { kill(); throw EngineException("The engine did not stop when asked") }
                }
            } catch (e: CancellationException) {
                withContext(NonCancellable) {
                    runCatching { send("stop") }
                    val stopped = withTimeoutOrNull(3_000) { runCatching { readUntilBestMove() }.getOrDefault(false) }
                    if (stopped != true) kill()
                }
                throw e
            }
            UciSearchResult(bestMove, latest.values.toList())
        }
    }

    /** Asks a running search to stop early; the pending [search] returns what it has. */
    fun requestStop() {
        runCatching { send("stop") }
    }

    private fun ensureRunning() {
        if (!isRunning) throw EngineException("The engine is not running")
    }

    @Synchronized
    private fun send(cmd: String) {
        val w = writer ?: throw EngineException("The engine is not running")
        try {
            w.write(cmd)
            w.newLine()
            w.flush()
            log("> $cmd")
        } catch (e: IOException) {
            throw EngineException("Lost connection to the engine", e)
        }
    }

    private suspend fun awaitLine(timeoutMillis: Long, predicate: (String) -> Boolean): String? {
        val channel = output ?: return null
        return withTimeoutOrNull(timeoutMillis) {
            var found: String? = null
            while (found == null) {
                val line = channel.receiveCatching().getOrNull() ?: break
                if (predicate(line)) found = line
            }
            found
        }
    }

    private fun kill() {
        process?.let { p ->
            p.destroy()
            if (!p.waitFor(500, TimeUnit.MILLISECONDS)) p.destroyForcibly()
        }
        process = null
        writer = null
        output = null
    }

    override fun close() {
        runCatching { send("quit") }
        process?.let { p -> if (!p.waitFor(500, TimeUnit.MILLISECONDS)) p.destroyForcibly() }
        process = null
        writer = null
        output = null
    }

    companion object {
        private val ONE_ARG = setOf(
            "seldepth", "nodes", "nps", "time", "hashfull", "tbhits", "cpuload", "currmove", "currmovenumber", "sbhits",
        )

        fun parseInfo(line: String): UciInfo? {
            val t = line.trim().split(Regex("\\s+"))
            if (t.isEmpty() || t[0] != "info") return null
            var i = 1
            var depth = 0
            var multiPv = 1
            var cp: Int? = null
            var mate: Int? = null
            var bound = false
            var pv: List<String>? = null
            while (i < t.size) {
                when (val key = t[i]) {
                    "depth" -> { depth = t.getOrNull(i + 1)?.toIntOrNull() ?: 0; i += 2 }
                    "multipv" -> { multiPv = t.getOrNull(i + 1)?.toIntOrNull() ?: 1; i += 2 }
                    "score" -> {
                        when (t.getOrNull(i + 1)) {
                            "cp" -> cp = t.getOrNull(i + 2)?.toIntOrNull()
                            "mate" -> mate = t.getOrNull(i + 2)?.toIntOrNull()
                        }
                        i += 3
                        if (t.getOrNull(i) == "lowerbound" || t.getOrNull(i) == "upperbound") { bound = true; i++ }
                    }
                    "wdl" -> i += 4
                    "pv" -> { pv = t.subList(i + 1, t.size); i = t.size }
                    "string", "refutation", "currline" -> i = t.size
                    else -> i += if (key in ONE_ARG) 2 else 1
                }
            }
            if (cp == null && mate == null) return null
            return UciInfo(multiPv, depth, cp, mate, bound, pv.orEmpty())
        }
    }
}
