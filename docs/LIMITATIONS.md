# Limitations and design decisions

These are constraints of Chess.com, Android or the approach — not bugs. The app explains them where they matter.

## Chess.com account access

- **No sign-in for third-party apps.** Chess.com's Published-Data API is read-only and unauthenticated; Chess.com's OAuth is only available to approved partners. AfterCheck therefore connects with your **username** and reads your **public game archive**. It never asks for a password, never stores cookies and never calls undocumented endpoints.
- **What is available:** every finished game listed in `https://api.chess.com/pub/player/{username}/games/archives` (live and daily chess).
- **What is not:** games in progress (by design — see fair play), games removed from your archive, and anything Chess.com doesn't publish in the API. If a username's archive is empty, AfterCheck shows that rather than guessing.
- **Publication delay:** finished games appear in the archive after Chess.com publishes them — usually within minutes, occasionally longer. AfterCheck re-checks the current month on every sync using ETags, so the extra cost of checking is a 304 response.
- **Variants** (Chess960, bughouse, crazyhouse, …) are skipped; the engine analyses standard chess only.
- **Rate limits:** requests are made one at a time with backoff on HTTP 429/5xx, as the API asks.

## Background sync on Android

- Sync runs through WorkManager at the interval you choose (15 min – 6 h). Android decides the exact moment based on Doze, battery saver and app-standby buckets, so a new game can take longer than the interval to appear. Opening the app always triggers an immediate check.
- There is no permanent background service; AfterCheck does not try to bypass Android's power management.

## Analysis

- Analysis speed depends on the phone, the mode and the game length. Settings shows the measured speed on your device (seconds per position).
- WorkManager gives each run up to 10 minutes; AfterCheck analyses for ~8 minutes and then continues in a follow-up run. Interrupted games resume quickly because every evaluated position is cached.
- By default, games older than 30 days are analysed only while charging (toggle in Settings). New and recent games are always analysed.
- The NNUE network (40 MB) is downloaded once on first use. Until it is present, games are imported but not analysed.
- Supported CPUs: arm64-v8a (almost all phones since 2017) and x86_64 (emulators, Chromebooks). 32-bit-only devices are not supported.

## Classifications and accuracy

- Move classes use AfterCheck's own documented thresholds on expected-points loss (see `chess/src/main/kotlin/app/aftercheck/chess/analysis/Classification.kt`). They are **not** Chess.com's proprietary classifier, so labels can differ.
- Accuracy is an **estimate** based on the open method published by Lichess (win-percentage drop per move, volatility-weighted and harmonic means). It is not Chess.com's CAPS/accuracy score.
- Explanations are generated from verifiable facts only: the engine's lines replayed on the board, material balance, attacked squares and forced mates. When no specific tactical reason is found, the explanation says what the engine prefers and how the evaluation changed instead of inventing a story.

## Fair play

AfterCheck only analyses games that are finished and published in your archive. It never looks at a live game, never suggests moves during play and never interacts with the Chess.com website.
