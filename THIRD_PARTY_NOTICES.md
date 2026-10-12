# Third-party notices

## Stockfish 19

Stockfish is a separate free chess engine distributed under GNU GPL version 3. The Android build workflow compiles the official upstream `sf_19` tag for `arm64-v8a` and `x86_64` and bundles those binaries in the APK.

- Source: https://github.com/official-stockfish/Stockfish/tree/sf_19
- License: [third_party/stockfish/COPYING.txt](third_party/stockfish/COPYING.txt)
- Build recipe: [scripts/build-stockfish-android.sh](scripts/build-stockfish-android.sh)

Review the upstream licensing files for any network/data notices accompanying the neural-network files. All third-party copyrights and terms remain with their respective holders.

## Chesslib

The chess rules and SAN/FEN helper uses [bhlangonijr/chesslib](https://github.com/bhlangonijr/chesslib), an Apache-2.0 licensed Java library distributed here through JitPack. Consult the library repository for the full license and attribution.

## AndroidX, Jetpack Compose, Room, WorkManager, Kotlin

These dependencies retain their respective licenses; consult their published metadata and source repositories. This file is not a replacement for the upstream license texts.
