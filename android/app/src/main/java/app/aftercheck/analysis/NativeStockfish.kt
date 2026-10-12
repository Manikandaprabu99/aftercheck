package app.aftercheck.analysis

/**
 * Thin JNI access to Stockfish. Native methods are only called after ensureLoaded()
 * succeeds, so unsupported ABI/missing library failures produce a useful app error.
 */
object NativeStockfish {
    @Volatile private var loadFailure: Throwable? = null

    init {
        try {
            System.loadLibrary("stockfish-jni")
        } catch (error: Throwable) {
            loadFailure = error
        }
    }

    fun ensureLoaded() {
        loadFailure?.let {
            throw IllegalStateException(
                "The native Stockfish engine could not be loaded for this device.",
                it
            )
        }
    }

    @JvmStatic external fun nativeStart(): Boolean
    @JvmStatic external fun nativeSendCommand(command: String): Boolean
    @JvmStatic external fun nativeReadLine(timeoutMs: Int): String?
    @JvmStatic external fun nativeIsRunning(): Boolean
    @JvmStatic external fun nativeShutdown()
}
