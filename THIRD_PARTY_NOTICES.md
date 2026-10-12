# Third-party notices

## Stockfish 19

AfterCheck integrates the official Stockfish engine source at the pinned upstream tag `sf_19` as a native JNI shared library. The engine source is fetched at build time from:

- Source: https://github.com/official-stockfish/Stockfish/tree/sf_19
- License: [third_party/stockfish/COPYING.txt](third_party/stockfish/COPYING.txt), GNU GPL version 3
- JNI bridge: [android/app/src/main/cpp/stockfish_jni.cpp](android/app/src/main/cpp/stockfish_jni.cpp)
- Native build: [android/app/src/main/cpp/CMakeLists.txt](android/app/src/main/cpp/CMakeLists.txt)
- Build helper: [scripts/build-stockfish-android.sh](scripts/build-stockfish-android.sh)

The official evaluation network `nn-1a298aa575a0.nnue` is fetched on first analysis from an official Stockfish network endpoint, SHA-256-prefix checked, and stored locally. It is not bundled in the APK.

Stockfish and the JNI bridge are GPLv3; see the repository [LICENSE](LICENSE). The upstream engine source itself is not modified, but it is built together with the AfterCheck JNI interface.

## Chesslib

The chess rules and SAN/FEN helper uses [bhlangonijr/chesslib](https://github.com/bhlangonijr/chesslib), an Apache-2.0 licensed Java library distributed through JitPack. The license text is in [third_party/chesslib/LICENSE](third_party/chesslib/LICENSE).

## AndroidX, Jetpack Compose, Room, WorkManager and Kotlin

These dependencies retain their respective licenses; consult their published metadata and source repositories. This file is not a replacement for the upstream license texts.
