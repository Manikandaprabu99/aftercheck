package app.aftercheck.engine

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Locates the bundled Stockfish executable and manages its NNUE evaluation network.
 *
 * The network (≈38 MB) is not inside the APK; it is downloaded once from the official Stockfish
 * sources, verified against the SHA-256 prefix encoded in its file name, and kept in app storage.
 */
class EngineFiles(private val context: Context) {
    val directory: File get() = File(context.filesDir, "engine").apply { mkdirs() }
    val networkFile: File get() = File(directory, NETWORK)
    private val partFile: File get() = File(directory, "$NETWORK.part")

    fun isNetworkReady(): Boolean = networkFile.length() == NETWORK_BYTES

    fun partialBytes(): Long = partFile.takeIf { it.exists() }?.length() ?: 0

    /** Native-library directory is the one place Android lets an app execute its own binaries. */
    fun binary(): File {
        val dir = File(context.applicationInfo.nativeLibraryDir)
        val dotprod = File(dir, "libstockfish_dotprod.so")
        return if (supportsDotProduct() && dotprod.exists()) dotprod else File(dir, "libstockfish.so")
    }

    val engineVariant: String get() = if (binary().name.contains("dotprod")) "ARMv8.2 dot-product" else Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"

    fun launcher(): () -> Process = {
        ProcessBuilder(binary().absolutePath).directory(directory).redirectErrorStream(true).start()
    }

    fun deleteNetwork() {
        networkFile.delete()
        partFile.delete()
    }

    /** Downloads (resuming a partial file when possible) and verifies the network. */
    suspend fun downloadNetwork(onProgress: suspend (downloaded: Long, total: Long) -> Unit) = withContext(Dispatchers.IO) {
        if (isNetworkReady()) return@withContext
        var lastError: Exception? = null
        for (url in NETWORK_URLS) {
            try {
                downloadFrom(url, onProgress)
                verify()
                partFile.renameTo(networkFile) || throw IOException("Could not store the network file")
                return@withContext
            } catch (e: IOException) {
                lastError = e
            }
        }
        throw lastError ?: IOException("Download failed")
    }

    private suspend fun downloadFrom(url: String, onProgress: suspend (Long, Long) -> Unit) {
        val existing = partialBytes().takeIf { it in 1 until NETWORK_BYTES } ?: 0L
        if (existing == 0L) partFile.delete()
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 20_000
            conn.readTimeout = 60_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", USER_AGENT)
            if (existing > 0) conn.setRequestProperty("Range", "bytes=$existing-")
            val code = conn.responseCode
            val append = code == 206 && existing > 0
            if (code != 200 && code != 206) throw IOException("HTTP $code from $url")
            var downloaded = if (append) existing else 0L
            conn.inputStream.use { input ->
                FileOutputStream(partFile, append).use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var lastReport = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        downloaded += n
                        if (downloaded - lastReport >= 512 * 1024) {
                            onProgress(downloaded, NETWORK_BYTES)
                            lastReport = downloaded
                        }
                    }
                }
            }
            onProgress(downloaded, NETWORK_BYTES)
        } finally {
            conn.disconnect()
        }
    }

    private fun verify() {
        val digest = MessageDigest.getInstance("SHA-256")
        partFile.inputStream().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        if (!hex.startsWith(NETWORK_HASH_PREFIX) || partFile.length() != NETWORK_BYTES) {
            partFile.delete()
            throw IOException("The downloaded network failed verification")
        }
    }

    companion object {
        const val ENGINE_NAME = "Stockfish 16"
        const val NETWORK = "nn-5af11540bbfe.nnue"
        const val NETWORK_HASH_PREFIX = "5af11540bbfe"
        const val NETWORK_BYTES = 40_119_326L
        const val USER_AGENT = "AfterCheck (+https://github.com/Manikandaprabu99/aftercheck)"
        val NETWORK_URLS = listOf(
            "https://tests.stockfishchess.org/api/nn/$NETWORK",
            "https://github.com/official-stockfish/networks/raw/master/$NETWORK",
        )

        fun supportsDotProduct(): Boolean {
            if (Build.SUPPORTED_ABIS.firstOrNull() != "arm64-v8a") return false
            return try {
                File("/proc/cpuinfo").readLines().any { line ->
                    line.startsWith("Features") && line.split(' ', '\t').contains("asimddp")
                }
            } catch (e: IOException) {
                false
            }
        }
    }
}
