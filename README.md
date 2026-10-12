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

See [Project status](docs/PROJECT_STATUS.md) for implementation and verification details. The first review pipeline and local Stockfish build integration are present in source, but the app is not considered complete and no APK is described as tested until the current GitHub Actions build succeeds.

## Build
Open the `android/` directory in Android Studio with JDK 17 and Android SDK 35 installed. GitHub Actions is configured to build a debug APK and upload it as a workflow artifact. See [Build guide](docs/BUILD.md).

## Privacy
Game history and reviews are intended to stay on device by default. No Chess.com password, session cookie, private token, or secret API key should be collected or committed.

## License
Application code is intended to use MIT unless a specific file says otherwise. Third-party components retain their own licenses. Stockfish integration must include all notices and license obligations for the exact binary/source distributed.
