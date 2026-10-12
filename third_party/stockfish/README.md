# Stockfish third-party notice

AfterCheck builds Stockfish as an in-process JNI shared library from the official upstream **sf_19** source tag. This avoids executing a standalone copied binary from app-writable storage.

- Source: https://github.com/official-stockfish/Stockfish/tree/sf_19
- License: [COPYING.txt](COPYING.txt), GNU General Public License version 3
- JNI bridge: [../../android/app/src/main/cpp/stockfish_jni.cpp](../../android/app/src/main/cpp/stockfish_jni.cpp)
- CMake configuration: [../../android/app/src/main/cpp/CMakeLists.txt](../../android/app/src/main/cpp/CMakeLists.txt)
- Build recipe: [../../scripts/build-stockfish-android.sh](../../scripts/build-stockfish-android.sh)

The pinned source is fetched during CI/local builds into the ignored directory `android/app/src/main/cpp/stockfish/`; no full upstream source checkout or compiled native library is committed here. The workflow builds the `arm64-v8a` ABI used by most modern Android phones.

The official NNUE network `nn-1a298aa575a0.nnue` is fetched from the upstream network endpoints on first analysis, validated using the first 12 characters of its SHA-256 digest, and kept in app-private storage so later analyses can run offline. The network is not bundled in the APK.

Stockfish is not authored by AfterCheck contributors. All GPLv3 source-code, notice, and corresponding-source obligations remain applicable when distributing builds.
