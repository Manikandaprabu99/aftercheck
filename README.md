# AfterCheck ♟️

**Free, local-first chess game review for Android.**

AfterCheck aims to review completed chess games using Stockfish on your Android device, explain moves with engine-backed evidence, and turn recurring mistakes into practice—without a mandatory paid analysis API or app account.

> Independent community project; not affiliated with Chess.com. Chess.com and Stockfish are respective third-party projects and trademarks.

## Planned stack
- Kotlin + Jetpack Compose + Material 3
- Room for local game/review storage
- WorkManager for OS-scheduled incremental synchronization
- Local Stockfish analysis
- GitHub Actions builds and GitHub Releases for APK distribution

## Status
Initial repository setup. See [Project status](docs/PROJECT_STATUS.md) for what is implemented and what remains. A source tree or configured workflow is not proof that an APK was built or tested; check Actions and Releases for verified results.

## Important constraints
- Sync can only retrieve games available through supported public archives or a documented authorization mechanism. The app must not collect Chess.com passwords, extract browser cookies, scrape private endpoints, or bypass access controls.
- WorkManager schedules periodic sync but Android decides when it runs; Chess.com may also publish a game after a delay.
- No app-imposed daily review quota is planned. Throughput, battery usage, and storage still depend on the device.
- Engine-driven move classifications and accuracy are estimates based on documented app methodology, not claims to reproduce Chess.com's proprietary algorithms.

## Build
Open the `android/` directory in Android Studio with JDK 17 and Android SDK 35 installed. GitHub Actions is configured to build a debug APK and upload it as a workflow artifact. See [Build guide](docs/BUILD.md).

## Privacy
Game history and reviews are intended to stay on device by default. No Chess.com password, session cookie, private token, or secret API key should be collected or committed.

## License
Application code is intended to use MIT unless a specific file says otherwise. Third-party components retain their own licenses. Stockfish integration must include all notices and license obligations for the exact binary/source distributed.
