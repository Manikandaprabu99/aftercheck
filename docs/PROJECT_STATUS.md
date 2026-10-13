# Project status

## Implemented in source

- Native Android app built with Kotlin, Jetpack Compose, Material 3, Room and WorkManager.
- Dark green-accented Home, Games, Insights, Practice and Settings screens; game rows open a move-by-move review view with a rendered board and selectable plies.
- Room-backed local game archive, review summaries and per-move analysis.
- Chess.com documented public archive-index and monthly PGN retrieval. Username-only setup; no password, cookie, or private endpoint access.
- Batched historical archive processing, per-username archive cursor, duplicate detection, transient-error retry handling, and WorkManager continuation.
- PGN header extraction and SAN replay timeline with FEN before/after every ply.
- Stockfish analysis source for each played position: engine score, best move, principal variation, app-defined centipawn-loss classification and estimated side accuracy.
- Practice questions generated from analyzed inaccuracies, mistakes and blunders. Choices are legal moves from recorded positions.
- Stockfish runs as an in-process JNI shared library; the app no longer launches a copied native executable from writable storage.
- The pinned Stockfish NNUE network is downloaded on first analysis, SHA-256-prefix checked, and cached locally for later offline analysis.
- GitHub Actions workflow runs unit tests, compiles the native Android library and debug APK, uploads APK artifacts, and publishes checksum-bearing debug releases for version tags.

## Not yet verified or complete

- The JNI/CMake native engine and debug APK have built successfully in GitHub Actions, with JVM unit tests passing. Current main also verifies the cached NNUE network's SHA-256 filename prefix before reuse and tests that integrity check. CI confirms compilation and JVM tests only; it does not replace physical-device/emulator testing.
- No physical-device/emulator validation has been performed in this environment. Native engine loading, first-time NNUE download, and a complete game review still need device testing.
- The initial native build targets `arm64-v8a`; x86_64 emulator support is not included yet.
- Move-classification thresholds and the accuracy estimate are AfterCheck approximations, not Chess.com's proprietary formulas.
- The review screen renders FEN positions and lets the user select plies. An evaluation graph, board move-entry, spaced repetition and rich opening analytics remain to be implemented.
- Historical sync processes a few monthly archives at a time. Android can delay WorkManager jobs, and Chess.com may publish games after a match ends.
- Private Chess.com games are not available through public archives; no unsupported account authorization or private API is assumed.
- Release APKs are debug-signed unless secure release signing is added.

## Privacy and licensing boundaries

Game history and reviews are intended to remain on-device by default. A Chess.com username retrieves only games exposed through the documented public archive API; it is not an authorization token. AfterCheck's combined app and Stockfish JNI integration use GPLv3; see `LICENSE`.
