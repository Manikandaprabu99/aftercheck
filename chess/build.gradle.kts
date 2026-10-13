plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    // Engine integration tests run when a host Stockfish build and its network are available
    // (see scripts/build-stockfish.sh --host). They are skipped otherwise.
    System.getenv("AFTERCHECK_STOCKFISH")?.let { environment("AFTERCHECK_STOCKFISH", it) }
    System.getenv("AFTERCHECK_NNUE")?.let { environment("AFTERCHECK_NNUE", it) }
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
