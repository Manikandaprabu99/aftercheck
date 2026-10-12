# Stockfish third-party notice

AfterCheck's APK build bundles an unmodified Stockfish engine binary compiled from the official Stockfish repository tag **sf_19**.

- Official source at the exact tag: https://github.com/official-stockfish/Stockfish/tree/sf_19
- Official license text: [COPYING.txt](COPYING.txt), GNU General Public License version 3
- Build recipe: [scripts/build-stockfish-android.sh](../../scripts/build-stockfish-android.sh)
- CI compiles the engine from source for Android `arm64-v8a` and `x86_64`, then places the resulting binaries in the APK assets.
- No Stockfish executable is committed to this repository; generated binaries are added to the build workspace only.

Stockfish is a separate upstream project. It is not authored by AfterCheck contributors. When redistributing the executable, review all GPLv3 source-code and notice obligations. The source tag above and this reproducible build recipe identify the source used by CI; no local patches are applied in the engine build step.

Stockfish NNUE networks are distributed as part of the official engine build. The Stockfish project documents the relevant upstream network/data licensing in its source repository.
