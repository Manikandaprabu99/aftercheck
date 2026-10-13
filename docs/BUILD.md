# Building, testing and releasing

## Prerequisites

| Tool | Version |
|---|---|
| JDK | 17 |
| Android SDK | platform `android-37.0`, build-tools 37.0.0 |
| Android NDK | 28.2.13676358 (r28c) — only to build the engine |
| Gradle | wrapper included (9.8.1) |
| Python 3 + `pip install chess` | only to generate end-to-end test data |

Linux or macOS (or WSL on Windows) is needed for `scripts/build-stockfish.sh`.

## 1. Build the engine executables

```bash
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/28.2.13676358   # optional; the newest installed NDK is used otherwise
scripts/build-stockfish.sh
```

This downloads Stockfish `sf_16` source (SHA-256 pinned), compiles it with `NNUE_EMBEDDING_OFF` and writes:

```
app/src/main/jniLibs/arm64-v8a/libstockfish.so          # ARMv8 baseline
app/src/main/jniLibs/arm64-v8a/libstockfish_dotprod.so  # ARMv8.2 dot-product (chosen at runtime when the CPU supports it)
app/src/main/jniLibs/x86_64/libstockfish.so             # emulators / Chromebooks
```

They are executables, not JNI libraries: Android only allows apps to execute binaries from their native-library directory, so they are packaged as `lib*.so` with `useLegacyPackaging = true` (extracted on install, compressed in the APK). The build fails early (`verifyStockfishBinaries`) if they are missing.

## 2. Build the app

```bash
./gradlew assembleDebug     # app/build/outputs/apk/debug/app-debug.apk (application id app.aftercheck.debug)
./gradlew assembleRelease   # R8-minified; signed only when signing is configured (see below)
```

## 3. Tests

```bash
./gradlew :chess:test :app:testDebugUnitTest   # JVM: rules, PGN, perft, classification, sync, codec, insights…
./gradlew :app:connectedDebugAndroidTest       # on a device/emulator: engine on Android, Room, migrations
```

Tests that need the real engine or test data are skipped unless configured:

```bash
scripts/build-stockfish.sh --host                      # Linux Stockfish + verified network in build/stockfish/host
pip install chess==1.11.2
python3 scripts/testdata/generate.py build/stockfish/host/stockfish build/stockfish/host/nn-5af11540bbfe.nnue build/testdata

export AFTERCHECK_STOCKFISH=$PWD/build/stockfish/host/stockfish
export AFTERCHECK_NNUE=$PWD/build/stockfish/host/nn-5af11540bbfe.nnue
export AFTERCHECK_MOCK_DATA=$PWD/build/testdata        # Chess.com-format archives of real Stockfish games
export AFTERCHECK_SCREENSHOTS=$PWD/build/screens       # optional: PNGs of every screen (dark + light)
export LIVE_CHESSCOM=1                                 # optional: hit the real Chess.com API
./gradlew :chess:test :app:testDebugUnitTest
```

Live instrumented tests (real Chess.com account import, onboarding → automatic sync → background analysis → review). The tour uses the public account `erik` by default (`-Pandroid.testInstrumentationRunnerArguments.liveUser=<name>` to change it):

```bash
scripts/ci-device-tests.sh   # same as CI: streams results, saves logcat + screenshots in build/
```

`scripts/testdata/server.py build/testdata 8765` serves the generated archives over HTTP like `api.chess.com/pub`. Debug builds can be pointed at it: `adb shell setprop debug.aftercheck.api http://10.0.2.2:8765/pub` and restart the app.

## 4. Continuous integration

`.github/workflows/android-build.yml` runs on pushes to `main`, pull requests, version tags and manual dispatch:

1. **build** — JDK 17, SDK, NDK, Gradle cache; builds (and caches) Stockfish; generates test archives; runs all JVM tests including the real-engine end-to-end test and the live Chess.com API test; lint; builds release + debug APKs; verifies the release signature with `apksigner`; uploads `aftercheck-apk` (APK + SHA-256), test reports and screenshots.
2. **device-test** — boots an Android 15 emulator (KVM) and runs every instrumented test, including the live app tour; uploads screenshots and reports.
3. **release** (tags `v*` only, after both jobs pass) — publishes a GitHub Release with the APK, its `.sha256` file and release notes.

## 5. Release signing

The release keystore is never committed. CI reads four repository secrets:

| Secret | Value |
|---|---|
| `AFTERCHECK_KEYSTORE_BASE64` | `base64 -w0 aftercheck-release.jks` |
| `AFTERCHECK_KEYSTORE_PASSWORD` | keystore password |
| `AFTERCHECK_KEY_ALIAS` | key alias (`aftercheck`) |
| `AFTERCHECK_KEY_PASSWORD` | key password |

Create a key once and keep it safe — every future version must be signed with the same key or Android will refuse the update:

```bash
keytool -genkeypair -v -keystore aftercheck-release.jks -alias aftercheck -keyalg RSA -keysize 4096 -validity 10000
```

Locally, put the same values in an untracked `keystore.properties` at the repo root (`AFTERCHECK_KEYSTORE_FILE=…`, etc.) or export them as environment variables. Without signing configured, `assembleRelease` produces `app-release-unsigned.apk` and CI publishes the debug-signed APK instead, clearly labelled.

## 6. Publishing a release

```bash
git tag v1.0.1
git push origin v1.0.1
```

The version name comes from the tag (`v1.2.3` → `1.2.3`, versionCode `10203`). The release appears at <https://github.com/Manikandaprabu99/aftercheck/releases/latest>.
