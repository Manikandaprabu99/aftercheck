# AfterCheck ♟️

**Free, local-first chess game review for Android.**

AfterCheck reviews completed chess games on-device with Stockfish, explains moves with engine-backed evidence, and turns recurring mistakes into practice—without a paid analysis API or an AfterCheck account.

> Independent community project; not affiliated with Chess.com or the Stockfish project.

## Features in source

- Native Kotlin + Jetpack Compose + Material 3
- Local Room database for games and reviews
- Sync of games exposed by Chess.com's documented public archive API
- Stockfish analysis through an in-process JNI library
- Move-by-move evaluations, best-move suggestions and principal variations
- Practice questions generated from analyzed mistakes
- WorkManager sync/review queue and GitHub Actions APK builds

## Status

See [Project status](docs/PROJECT_STATUS.md). The JNI migration is undergoing a fresh CI build and has not yet been validated on a physical device. Follow the Actions results before treating the latest APK as verified.

## Build

See [Build guide](docs/BUILD.md). Local builds first fetch the pinned Stockfish `sf_19` source using `scripts/build-stockfish-android.sh`. The first review downloads the official evaluation network once and stores it locally; later analysis can work offline.

## Privacy

Game history and reviews are intended to remain on-device by default. The app asks only for a Chess.com username and imports games available through the documented public archive API. It does not request Chess.com passwords, session cookies, private tokens or undocumented endpoint access.

## License

AfterCheck's combined application and Stockfish JNI integration are distributed under GPLv3. See [LICENSE](LICENSE) and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for third-party components and source references.
