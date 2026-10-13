# Testing

| Area | Tests | Where |
|---|---|---|
| Move legality, castling, en passant, promotion, pins | Perft on 7 standard positions (up to 674,624 leaf nodes) | `chess/…/PerftTest.kt` |
| SAN generation/parsing, FEN, disambiguation, draws | `NotationTest` | `chess/` |
| PGN parsing (clocks, comments, variations, NAGs, multi-game, SetUp/FEN), position reconstruction, threefold, openings/transpositions | `PgnTest` | `chess/` |
| Evaluation perspective, mate handling, win chances, encoding | `ScoreTest` | `chess/` |
| Move classification incl. missed mate, missed opportunity, allowed mate, book | `ClassifierTest` | `chess/` |
| Accuracy estimate | `AccuracyTest` | `chess/` |
| Review generation, PV display, explanations, themes, time spent | `ReviewerTest`, `ExplainerTest` | `chess/` |
| UCI parsing; real Stockfish: mates, Black-to-move perspective, drawn endgame, full game review, **cancellation**, restart | `UciParsingTest`, `EngineIntegrationTest` | `chess/` |
| Chess.com client: retries, 429/Retry-After, 5xx, network errors, ETag/304, not-found | `ChessComApiTest` | `app/src/test` |
| Archive pagination newest-first, batching, resume, duplicate detection, closed months never refetched, current-month refresh, variants | `SyncEngineTest`, `GameMapperTest` | `app/src/test` |
| Spaced repetition, insights maths, storage codec, formatting | `LogicTest` | `app/src/test` |
| **End to end**: Chess.com-format archives → sync → real Stockfish → Room → restart persistence → queue recovery → every screen rendered in dark and light | `EndToEndTest` (Robolectric) | `app/src/test` |
| Live Chess.com API (CI) | `LiveChessComTest` | `app/src/test`, `app/src/androidTest` |
| Packaged engine executes on Android; game analysed and persisted on device | `DeviceEngineTest` | `app/src/androidTest` |
| Room queries, queue order/charging filter, recovery, cascades; schema/migrations | `DatabaseTest`, `MigrationTest` | `app/src/androidTest` |
| **App tour on an emulator**: onboarding with a real account → automatic import → network download → WorkManager analysis → review (UI Automator, screenshots) | `AppTourTest` | `app/src/androidTest` |

Every instrumented test has a timeout that fails with the stuck thread's stack trace, so a hang can't stall CI. `scripts/ci-device-tests.sh` streams per-test results and saves logcat and screenshots for the workflow artifacts.

### Results so far

- JVM: 53 chess + 28 app tests pass locally and in GitHub Actions (the live Chess.com test runs only in CI).
- Android 15 emulator (GitHub Actions): engine-on-device, Room, live Chess.com import and the full app tour pass.

See [BUILD.md](BUILD.md) for the commands and environment variables.
