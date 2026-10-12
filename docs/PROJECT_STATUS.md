# Project status (initial foundation)

## Implemented in source

- Native Android/Jetpack Compose foundation and a dark green-accented dashboard/navigation shell.
- Room model/DAO/database for locally stored game archives.
- Chess.com public profile archive-index and monthly PGN retrieval client. It uses username-only public archive access and does not request a password or cookies.
- Initial PGN header parser and parser unit tests.
- WorkManager periodic schedule (six hours) and one-off manual sync.
- Incremental archive import in small batches, with local duplicate checks and continuation work.
- GitHub Actions workflow intended to run tests and build a debug APK artifact.

## Not yet implemented / not yet verified

- Stockfish native binary/engine integration, UCI lifecycle and evaluation pipeline.
- Position reconstruction, move classification, best lines, analysis queue, per-game review details and practice mode.
- Review-specific UI and robust move/variant parsing.
- App/device emulator validation and confirmed green CI build.
- Production release signing and stable release keystore setup.

The application must not be described as a finished chess-review product or a tested APK until those remaining items are implemented and verification results are available.

## API/privacy boundaries

A Chess.com username is sufficient only for games made available through public archive endpoints. This is not a login flow and will not access private data. Periodic WorkManager tasks are subject to Android scheduling. The archive client should respect errors, rate limits and publication delays.
