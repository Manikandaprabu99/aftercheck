# lichess-org/chess-openings

- **License:** CC0 1.0 Universal (Public Domain Dedication). See https://creativecommons.org/publicdomain/zero/1.0/
  Upstream README: "As a collection of facts, this data set is in the public domain. [...] Insofar as that qualifies for copyright, the work is released under the CC0 Public Domain Dedication."
- **Source:** https://github.com/lichess-org/chess-openings
- **Upstream commit:** `a6189a30dc273ccb21fc2536a9a2fefd5592a67a` (master as of 2026-10-13, committed 2026-10-06)
- **Files used:** `a.tsv`, `b.tsv`, `c.tsv`, `d.tsv`, `e.tsv` at that commit,
  e.g. https://raw.githubusercontent.com/lichess-org/chess-openings/a6189a30dc273ccb21fc2536a9a2fefd5592a67a/a.tsv

## Modifications

The five files were concatenated in order (a to e) into `app/src/main/assets/openings.tsv`, with the per-file header lines removed.

- A single header line `eco<TAB>name<TAB>pgn` was added.
- The result is 3,866 lines: 1 header and 3,865 openings.
- Line endings are LF, and the encoding is UTF-8.
