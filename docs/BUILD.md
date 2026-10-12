# Build guide

## Prerequisites

- JDK 17
- Android SDK platform/build tools 35
- Android NDK `27.2.12479018`
- CMake `3.22.1`
- Gradle 8.9 or a generated Gradle wrapper
- Internet access for the first source checkout

## Local build

The CMake project compiles Stockfish from upstream source fetched at tag `sf_19`. That source checkout is intentionally ignored by Git.

From a Linux/macOS shell (or WSL with Android SDK/NDK/CMake configured), run from the repository root:

```bash
./scripts/build-stockfish-android.sh
```

The script downloads the pinned Stockfish source, stages its `src/` directory under `android/app/src/main/cpp/stockfish/`, then runs unit tests and assembles the debug APK. If you prefer Android Studio, run the source-fetch portion first and open the `android/` directory after it has populated the generated source folder.

The debug APK is produced at `android/app/build/outputs/apk/debug/app-debug.apk`. This initial native build targets `arm64-v8a`, suitable for most modern Android phones.

## First analysis and offline behavior

The APK contains the JNI engine but does not bundle the large evaluation network. The first review fetches the official `nn-1a298aa575a0.nnue` network, checks its SHA-256 prefix, and saves it in app-private storage. After that succeeds once, engine analysis can work offline. If the initial download is interrupted or unavailable, WorkManager retries the review; the error is shown in the app.

## GitHub Actions and releases

Pushes to `main`, pull requests and manual runs execute unit tests and build a debug APK. Successful builds upload `aftercheck-debug-apk` as an artifact. A tag beginning with `v` publishes the debug APK plus its SHA-256 checksum as a GitHub Release.

The debug APK is **not a production-signed release**. Configure secure release signing before broader distribution. Never commit a keystore or passwords.
