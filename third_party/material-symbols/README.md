# Material Symbols (Rounded)

- **License:** Apache License 2.0. See `LICENSE` in this directory, copied verbatim from https://github.com/google/material-design-icons/blob/master/LICENSE
- **Source:** https://github.com/google/material-design-icons/tree/master/symbols/android
  (path pattern `symbols/android/{name}/materialsymbolsrounded/{name}_24px.xml` and `{name}_fill1_24px.xml`; master at commit `49d4db35df873165d6bd6ba09b063c7dafbac2f4`, fetched 2026-10-13)

## Files

These are `app/src/main/res/drawable/ic_*.xml`.

Outline and filled (`ic_{name}.xml` and `ic_{name}_filled.xml`):

- home
- chess
- chess_pawn
- chess_knight
- chess_king
- insights
- target
- settings
- star
- bookmark

Outline only:

- search, tune, sort, close
- arrow_back, chevron_left, chevron_right, first_page, last_page
- swap_vert, sync, refresh, autorenew
- check_circle, error, info, done
- schedule, bolt, timer, hourglass_empty, calendar_month
- delete, open_in_new, share, pause, play_arrow, cloud_download, storage
- palette, dark_mode, light_mode, contrast
- person, link, lock, shield
- menu_book, trending_up, trending_down, emoji_events, lightbulb, flag
- more_vert, expand_more, expand_less
- sentiment_satisfied, auto_awesome, memory

## Modifications

- Removed `android:tint`.
- Set every `android:fillColor` to `#FF000000`, so that Compose `Icon` can tint it.
- Kept `width`/`height` at 24dp and kept the upstream viewport. Most icons use 960; `insights` and `auto_awesome` use 24.
- Added `android:autoMirrored="true"` to `ic_first_page.xml` to match `ic_last_page.xml`, which has it upstream, so the pair mirrors consistently in RTL.

Upstream ships identical outline and FILL=1 artwork for `target` and `insights`.
