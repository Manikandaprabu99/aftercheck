# Project status (initial foundation + first review pipeline)

## Implemented in source

- Native Android project using Kotlin, Jetpack Compose, Material 3, Room and WorkManager.
- Dark green-accented Home, Games, Insights, Practice and Settings shell; game rows open a move-by-move review screen with a rendered board and selectable plies.
- Room game library, review summary table and per-move analysis table.
- Chess.com documented public archive-index and monthly PGN retrieval. Username-only setup; no password, cookie, or private endpoint access.
- Batched historical archive processing, per-username archive cursor, duplicate checks, explicit sync status, retry-vs-permanent-error handling and WorkManager continuation.
- PGN header extraction and SAN replay timeline with FEN before/after every ply.
- Stockfish UCI process adapter and local review pipeline source: per-position evaluation, best move, principal variation, app-defined CP-loss classification and estimated side accuracy.
- Practice questions are generated from analyzed inaccuracies, mistakes and blunders. Choices use legal moves from the recorded position and reveal the recorded engine recommendation after the user answers.
- GitHub Actions workflow to build Stockfish 19 for Android, run unit tests, build a debug APK, upload it as an artifact and publish a checksum-bearing debug release for version tags.
- Third-party Stockfish GPLv3 license text, upstream source link, Apache-2.0 chesslib license text and local engine build recipe.

## Not yet verified or complete

- The current GitHub Actions build has not yet completed successfully against the latest commit. Treat the APK as unavailable until a successful artifact appears in Actions.
- No physical-device/emulator validation has been performed in this environment.
- Analysis classification thresholds and accuracy formula are documented app estimates; they are not Chess.com's proprietary formulas.
- The review screen renders FEN positions and lets the user select plies. An evaluation graph, board move-entry for practice, spaced repetition, and rich opening analytics remain to be implemented.
- The engine currently starts a local Stockfish process per review and uses a conservative fixed depth. Performance and process execution permissions require on-device verification.
- Historical sync processes a few monthly archives at a time. Android can delay background WorkManager jobs, and Chess.com can publish games later than the match's end.
- Private Chess.com games are not available through public archives; no unsupported account authorization or private API is assumed.
- Release artifact is debug-signed by default, not a production-signed distribution. Set up a secure persistent release keystore for production installation/update flows.

## API/privacy boundaries

Only games exposed by Chess.com's documented public archive API can be imported. A username is not an authorization token. The app must not collect Chess.com passwords, extract session cookies, or bypass access controls. Periodic WorkManager tasks are subject to Android scheduling and battery policies.
