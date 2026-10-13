# Third-party notices

AfterCheck is licensed under the GNU General Public License v3.0 (see `LICENSE`). It includes or downloads:

| Component | Licence | Source | How it is used |
|---|---|---|---|
| Stockfish 16 | GPL-3.0 | <https://github.com/official-stockfish/Stockfish> tag `sf_16` (tarball SHA-256 pinned in `scripts/build-stockfish.sh`) | Compiled from unmodified source with `-DNNUE_EMBEDDING_OFF`; shipped as executables in the APK. Corresponding source: the tag above plus `scripts/build-stockfish.sh`. |
| Stockfish NNUE network `nn-5af11540bbfe.nnue` | GPL-3.0 (distributed by the Stockfish project) | <https://tests.stockfishchess.org/api/nn/nn-5af11540bbfe.nnue> | Downloaded by the app on first use; verified by SHA-256 prefix. Not bundled. |
| cburnett chess pieces | GPL-2.0-or-later | <https://github.com/lichess-org/lila/tree/master/public/piece/cburnett> | Converted to Android vector drawables (`third_party/cburnett/`). |
| Opening names (chess-openings) | CC0-1.0 | <https://github.com/lichess-org/chess-openings> | `app/src/main/assets/openings.tsv` (`third_party/chess-openings/`). |
| Inter typeface | SIL OFL 1.1 | <https://github.com/google/fonts/tree/main/ofl/inter> | Static weights, subset (`third_party/inter/`). |
| Material Symbols (Rounded) | Apache-2.0 | <https://github.com/google/material-design-icons> | A subset of icons as vector drawables (`third_party/material-symbols/`). |
| AndroidX, Jetpack Compose, Room, WorkManager, Paging, DataStore | Apache-2.0 | <https://developer.android.com/jetpack> | Libraries. |
| Kotlin standard library, kotlinx.coroutines | Apache-2.0 | <https://github.com/JetBrains/kotlin> | Libraries. |

Test-only tools (not shipped): JUnit (EPL-1.0), Robolectric (MIT), python-chess (GPL-3.0, used to generate test games).

"Chess.com" is a trademark of Chess.com, LLC. AfterCheck is not affiliated with or endorsed by Chess.com.
