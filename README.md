# AfterCheck

**Free, unlimited chess game reviews for your Chess.com games — analysed on your phone with Stockfish.**

AfterCheck imports your finished Chess.com games automatically, analyses every move with a local Stockfish 16 engine, and turns your mistakes into practice positions. No subscription, no API key, no account, no daily limit.

> Independent open-source project. Not affiliated with or endorsed by Chess.com.

**Download:** [latest release](https://github.com/Manikandaprabu99/aftercheck/releases/latest) · Android 8.0+ · ~3.3 MB APK

## What it does

- **Zero manual import.** Enter your Chess.com username once. Your history is imported newest-first, and new games are picked up automatically in the background (WorkManager) and every time you open the app.
- **Automatic reviews.** Every imported game is queued and analysed by Stockfish on the device. Recent games go first; older history can wait for the charger.
- **Move-by-move review.** Best / Excellent / Good / Book / Inaccuracy / Mistake / Blunder / Missed opportunity / Missed mate, with the engine's best move, principal variations, alternatives, evaluation before and after, time spent, and a plain-language explanation grounded in the engine lines (material won or lost, hanging pieces, forks, forced mates).
- **Game summary.** Estimated accuracy for both players, move-class counts, key moments, biggest swing, strongest move, opening (ECO), final evaluation and targeted recommendations.
- **Insights.** Results, accuracy trend, errors per game, performance by time control and opening, recent form and recurring weaknesses — all from your stored games.
- **Practice.** Your own mistakes become puzzles with a spaced-repetition schedule; solve them on an interactive board, offline.
- **Private.** Games, reviews and practice stay on the device. No analytics, no tracking, no backend.

## Screens

Home · Games · Review · Insights · Practice · Settings — native Jetpack Compose, Material 3 with a custom dark/light design system, edge-to-edge, responsive (two-pane review on tablets and landscape).

## How the Chess.com connection works

Chess.com doesn't offer a sign-in (OAuth) for independent apps, so AfterCheck uses Chess.com's documented, read-only [Published-Data API](https://www.chess.com/news/view/published-data-api). Finished games in your archive are public by username, so AfterCheck never asks for your password and never reads cookies or private endpoints. See [docs/LIMITATIONS.md](docs/LIMITATIONS.md) for what this means in practice.

## Permissions

| Permission | Why |
|---|---|
| `INTERNET` | Read your public Chess.com archive; download the Stockfish network once. |
| `ACCESS_NETWORK_STATE` | Wait for a connection before syncing instead of failing. |
| `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED` | Added by WorkManager: let a running sync/analysis job finish and keep the schedule after a reboot. |

No location, contacts, storage, camera, notifications or accounts.

## Engine

Stockfish 16 (GPLv3), built from pinned upstream source for arm64-v8a (plus an ARMv8.2 dot-product build picked automatically on newer phones) and x86_64. It runs as a separate process from the app's native-library directory and speaks UCI. Its 40 MB NNUE network is downloaded once from the official Stockfish servers, verified against its SHA-256, and kept on the device — that keeps the APK small.

Analysis modes: **Fast** (depth 12), **Balanced** (depth 16, two lines, default), **Deep** (depth 20, three lines) or **Custom**. Results are cached per position, so repeated openings are only analysed once.

## Build

```bash
scripts/build-stockfish.sh   # needs the Android NDK (r28) — builds the engine executables
./gradlew assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
```

Full instructions, tests and release signing: [docs/BUILD.md](docs/BUILD.md). Architecture: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md). Testing: [docs/TESTING.md](docs/TESTING.md).

## Licence

GPL-3.0 — see [LICENSE](LICENSE). Third-party components and their licences: [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
