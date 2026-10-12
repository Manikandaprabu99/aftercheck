package app.aftercheck.analysis

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class EnginePositionResult(
    val depth: Int,
    val bestMoveUci: String,
    val scoreCp: Int?,
    val mateIn: Int?,
    val principalVariationUci: String
) {
    fun scoreFromWhitePerspective(fen: String): Int {
        val raw = scoreCp ?: when {
            mateIn == null -> 0
            mateIn > 0 -> 10_000
            mateIn < 0 -> -10_000
            else -> 0
        }
        val blackToMove = fen.trim().split(' ').getOrNull(1) == "b"
        return if (blackToMove) -raw else raw
    }
}

/**
 * Stockfish runs inside a native JNI library (not as a copied executable), which
 * avoids modern Android restrictions on launching binaries from app-writable storage.
 * The pinned upstream NNUE evaluation file is downloaded once, SHA-256-prefix checked,
 * and retained locally so future analyses work offline.
 */
object StockfishEngine {
    private const val NNUE_FILE_NAME = "nn-1a298aa575a0.nnue"
    private const val NNUE_SHA256_PREFIX = "1a298aa575a0"
    private const val ENGINE_TIMEOUT_MS = 120_000L
    private val engineMutex = Mutex()

    suspend fun analyzeFens(
        context: Context,
        fens: List<String>,
        depth: Int = 10,
        onProgress: suspend (Int, Int) -> Unit = { _, _ -> }
    ): List<EnginePositionResult> = engineMutex.withLock {
        withContext(Dispatchers.IO) {
            require(fens.isNotEmpty()) { "At least one position is required." }
            require(depth in 1..30) { "Depth must be between 1 and 30." }

            val network = ensureNetworkFile(context)
            NativeStockfish.ensureLoaded()
            check(NativeStockfish.nativeStart()) { "Stockfish is already running." }
            try {
                sendCommand("uci")
                waitForExactLine("uciok")

                sendCommand("setoption name EvalFile value ${network.absolutePath}")
                sendCommand("setoption name Threads value 1")
                sendCommand("setoption name Hash value 64")
                sendCommand("isready")
                waitForExactLine("readyok")

                val analyses = mutableListOf<EnginePositionResult>()
                for ((index, fen) in fens.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    require(fen.trim().split(Regex("\\s+")).size >= 4) {
                        "Invalid FEN at position ${index + 1}."
                    }
                    sendCommand("position fen $fen")
                    sendCommand("go depth $depth")

                    var lastInfo: String? = null
                    var bestMove: String? = null
                    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ENGINE_TIMEOUT_MS)
                    while (bestMove == null && System.nanoTime() < deadline) {
                        currentCoroutineContext().ensureActive()
                        val line = NativeStockfish.nativeReadLine(750)
                        if (line == null) {
                            if (!NativeStockfish.nativeIsRunning()) {
                                throw IllegalStateException("Stockfish stopped during position ${index + 1}.")
                            }
                            continue
                        }
                        if (line.startsWith("info depth ") && line.contains(" score ")) lastInfo = line
                        if (line.startsWith("bestmove ")) bestMove = line.trim().split(Regex("\\s+")).getOrNull(1)
                    }
                    if (bestMove == null) {
                        throw IOException("Stockfish did not finish position ${index + 1} within two minutes.")
                    }
                    analyses += parseInfo(lastInfo.orEmpty(), "bestmove $bestMove")
                    onProgress(index + 1, fens.size)
                }
                sendCommand("quit")
                analyses
            } finally {
                NativeStockfish.nativeShutdown()
            }
        }
    }

    private fun sendCommand(command: String) {
        check(NativeStockfish.nativeSendCommand(command)) {
            "Could not send a command to the Stockfish engine."
        }
    }

    private fun waitForExactLine(expected: String) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ENGINE_TIMEOUT_MS)
        while (System.nanoTime() < deadline) {
            val line = NativeStockfish.nativeReadLine(750)
            if (line == expected) return
            if (line == null && !NativeStockfish.nativeIsRunning()) {
                throw IllegalStateException("Stockfish stopped before '$expected'.")
            }
        }
        throw IOException("Stockfish did not respond with '$expected' in time.")
    }

    private fun ensureNetworkFile(context: Context): File {
        val directory = File(context.filesDir, "engine").apply { mkdirs() }
        val destination = File(directory, NNUE_FILE_NAME)
        if (destination.isFile && destination.length() > 0L) return destination

        val temporary = File(directory, "$NNUE_FILE_NAME.part")
        temporary.delete()
        val urls = listOf(
            "https://tests.stockfishchess.org/api/nn/$NNUE_FILE_NAME",
            "https://github.com/official-stockfish/networks/raw/master/$NNUE_FILE_NAME"
        )
        var lastError: Exception? = null
        for (candidate in urls) {
            try {
                downloadAndVerify(candidate, temporary)
                if (destination.exists() && !destination.delete()) {
                    throw IOException("Could not replace the existing NNUE file.")
                }
                if (!temporary.renameTo(destination)) {
                    throw IOException("Could not install the downloaded Stockfish network.")
                }
                return destination
            } catch (error: Exception) {
                temporary.delete()
                lastError = error
            }
        }
        throw IOException(
            "The first analysis needs the official Stockfish evaluation network. Connect to the internet once, then retry.",
            lastError
        )
    }

    private fun downloadAndVerify(url: String, temporary: File) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 180_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("User-Agent", "AfterCheck-Android/0.1.0")
            setRequestProperty("Accept", "application/octet-stream")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("Stockfish network download failed (HTTP $code).")
            connection.inputStream.use { input ->
                temporary.outputStream().buffered().use { output -> input.copyTo(output, DEFAULT_BUFFER_SIZE * 16) }
            }
            if (temporary.length() < 1_000_000L) {
                throw IOException("The downloaded Stockfish network is incomplete.")
            }
            val digest = MessageDigest.getInstance("SHA-256")
            temporary.inputStream().buffered().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            if (!sha.startsWith(NNUE_SHA256_PREFIX)) {
                throw IOException("The downloaded Stockfish network failed its SHA-256 check.")
            }
        } finally {
            connection.disconnect()
        }
    }

    internal fun parseInfo(info: String, bestMoveLine: String): EnginePositionResult {
        val words = info.trim().split(' ').filter { it.isNotBlank() }
        fun valueAfter(key: String): String? {
            val index = words.indexOf(key)
            return words.getOrNull(index + 1)
        }
        val depth = valueAfter("depth")?.toIntOrNull() ?: 0
        val scoreIndex = words.indexOf("score")
        val scoreType = words.getOrNull(scoreIndex + 1)
        val scoreValue = words.getOrNull(scoreIndex + 2)?.toIntOrNull()
        val scoreCp = if (scoreType == "cp") scoreValue else null
        val mateIn = if (scoreType == "mate") scoreValue else null
        val pvIndex = words.indexOf("pv")
        val pv = if (pvIndex >= 0) words.drop(pvIndex + 1).joinToString(" ") else ""
        val bestMove = bestMoveLine.trim().split(Regex("\\s+")).getOrNull(1).orEmpty()
        return EnginePositionResult(depth, bestMove, scoreCp, mateIn, pv)
    }
}
