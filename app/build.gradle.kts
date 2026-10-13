import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

val versionNameFromTag: String? = System.getenv("GITHUB_REF_NAME")?.takeIf { it.startsWith("v") }?.removePrefix("v")
val appVersionName = versionNameFromTag ?: "1.0.0"
val appVersionCode = appVersionName.split('.', '-').take(3).map { it.toIntOrNull() ?: 0 }
    .let { (it + listOf(0, 0, 0)).take(3) }
    .let { (major, minor, patch) -> major * 10_000 + minor * 100 + patch }

// Release signing comes from environment variables (GitHub Actions secrets) or an untracked
// keystore.properties file. Without either, release builds are left unsigned.
val signingProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(key: String): String? = System.getenv(key) ?: signingProps.getProperty(key)
val releaseKeystore = signingValue("AFTERCHECK_KEYSTORE_FILE")?.let { rootProject.file(it) }?.takeIf { it.exists() }

android {
    namespace = "app.aftercheck"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.aftercheck"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = signingValue("AFTERCHECK_KEYSTORE_PASSWORD")
                keyAlias = signingValue("AFTERCHECK_KEY_ALIAS")
                keyPassword = signingValue("AFTERCHECK_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        // Stockfish ships as an executable named lib*.so; it must be extracted to disk to run.
        jniLibs { useLegacyPackaging = true }
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/*.version", "DebugProbesKt.bin", "kotlin-tooling-metadata.json")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
        unitTests.all { test ->
            test.maxHeapSize = "3g"
            // Real-engine tests use a host Stockfish build (scripts/build-stockfish.sh --host).
            listOf("AFTERCHECK_STOCKFISH", "AFTERCHECK_NNUE", "AFTERCHECK_MOCK_DATA", "AFTERCHECK_SCREENSHOTS", "LIVE_CHESSCOM", "LIVE_CHESSCOM_USER").forEach { key ->
                System.getenv(key)?.let { test.environment(key, it) }
            }
            test.testLogging { events("failed", "skipped"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        // The engine binaries are executables packaged as .so files on purpose (see README).
        disable += setOf("GradleDependency", "AndroidGradlePluginVersion", "NewerVersionAvailable", "OldTargetApi")
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

// The Stockfish executables are built from pinned source by scripts/build-stockfish.sh.
tasks.register("verifyStockfishBinaries") {
    val required = listOf("arm64-v8a/libstockfish.so", "arm64-v8a/libstockfish_dotprod.so", "x86_64/libstockfish.so")
        .map { layout.projectDirectory.file("src/main/jniLibs/$it").asFile }
    doLast {
        val missing = required.filterNot { it.exists() }.map { it.name }
        check(missing.isEmpty()) { "Missing Stockfish binaries $missing — run scripts/build-stockfish.sh first." }
    }
}
tasks.named("preBuild") { dependsOn("verifyStockfishBinaries") }

dependencies {
    implementation(project(":chess"))

    implementation(libs.kotlinx.coroutines.android)
    // Navigation brings kotlinx-serialization 1.7.x; Room's schema tools (migration tests) need ≥ 1.8.1,
    // and instrumented tests share the app's runtime classpath.
    implementation(libs.kotlinx.serialization.core)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.profileinstaller)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.animation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.room.paging)
    ksp(libs.room.compiler)
    implementation(libs.paging.runtime)
    implementation(libs.paging.compose)
    implementation(libs.work.runtime)
    implementation(libs.datastore.preferences)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.org.json)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.work.testing)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(libs.work.testing)
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
