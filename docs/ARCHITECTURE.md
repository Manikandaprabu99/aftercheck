# Architecture

Two Gradle modules, no backend.

```
chess/   Pure Kotlin (JVM) — no Android dependencies, fully unit-tested
  Position, San, Pgn, GameLine, OpeningBook        rules, notation, PGN, repetition, openings (perft-verified)
  engine/UciEngine, GameAnalyzer                   process-based UCI client; per-position evaluation
  analysis/Score, Classification, Accuracy,        win chances, move classes, accuracy estimate,
           Explainer, GameReviewer                 grounded explanations, full game review

app/     Android (Kotlin, Jetpack Compose, Material 3)
  data/chesscom   ChessComApi (HttpURLConnection, ETag, backoff), ArchiveParser
  data/sync       SyncEngine (incremental, resumable archive import), GameMapper, RoomSyncStore
  data/db         Room: games, moves, reviews, archives, eval_cache, practice, practice_attempts
  data/settings   DataStore preferences
  engine          EngineFiles (binary + NNUE download/verify), AnalysisService, CachedEvaluator, ReviewCodec
  work            SyncWorker, AnalysisWorker, NetworkDownloadWorker, WorkScheduler, WorkStatus
  ui              theme, components (board, eval bar/graph, charts), screens + ViewModels
```

`AppContainer` (in `AfterCheckApp.kt`) wires everything by hand; the graph is small enough not to need a DI framework.

## Data flow

```
Chess.com archive ──SyncWorker──▶ games (status = queued)
                                     │
                       AnalysisWorker │  Stockfish process (UCI) ◀── eval_cache
                                     ▼
                     moves + reviews + practice (status = ready) ──▶ Compose screens (Flow)
```

1. **Sync** (`SyncEngine`): fetch the archive index (ETag), then monthly archives newest-first, up to 12 per run. Games are keyed by Chess.com's UUID (`INSERT OR IGNORE`), so reruns and interrupted runs never duplicate. Past months become `complete` once fetched after the month ended (+2 days) and are never requested again; the current month is re-checked with `If-None-Match`. Remaining history continues in chained work.
2. **Queue**: `games.status` is the queue (`queued → analyzing → ready | failed | skipped`). Games the user picks get a `queueOrder` (`AnalysisQueue`) and run first in that order, regardless of the charging preference; everything else follows by priority (new > recent > backlog) then end time. If a pick arrives while an automatic game is being analysed, the worker stops that game between two positions and starts the pick; positions already evaluated stay in the cache, so the interrupted game resumes cheaply. A game left `analyzing` by a killed process is reset at the next run.
3. **Analysis** (`AnalysisService`): replay the PGN, evaluate every position with the engine (via the cache), classify, explain, summarise, and write moves + review + practice positions in one transaction. Engine failures retry up to three times.
4. **UI**: screens observe Room `Flow`s; the Games list uses Paging 3.

## Engine integration

Stockfish is a separate process (`ProcessBuilder` on `nativeLibraryDir/libstockfish*.so`) rather than JNI: a crash can't take the app down, `stop`/kill gives reliable cancellation, and memory is released when analysis ends. `UciEngine` serialises searches, converts side-to-move scores to White's perspective, keeps only bound-free `info` lines, validates every PV move, and restarts the engine after failures.

## Classification method

Expected points `E = 1 / (1 + e^(−0.00368208 · cp))` for the mover (mates/finished games map to 0, ½ or 1). Loss = E(best) − E(played). Thresholds and special cases (missed mate, missed opportunity, allowed mate) are documented in `Classification.kt` and covered by tests.

## Size

Release APK ≈ 3.3 MB: R8 + resource shrinking, a handful of vector icons instead of an icon library, Canvas-drawn charts and board, subset Inter font, compressed engine executables. The NNUE network is downloaded once (40 MB) instead of being bundled.
