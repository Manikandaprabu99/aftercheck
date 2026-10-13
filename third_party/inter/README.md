# Inter

- **Copyright:** 2020 The Inter Project Authors (https://github.com/rsms/inter)
- **License:** SIL Open Font License 1.1. See `OFL.txt` in this directory. No Reserved Font Name is declared.
- **Source:** https://github.com/google/fonts/tree/main/ofl/inter
  (file `Inter[opsz,wght].ttf`, font version 4.001; google/fonts commit `0b58fb370093f9a9f4ff785d94405710b79de67c`; upstream rsms/inter commit `66647c0bbbe41a850d79d9c76fb13add3378940f`)
- **License text:** https://github.com/google/fonts/blob/main/ofl/inter/OFL.txt

## Modifications

Static instances were generated with fontTools `varLib.instancer` at `wght` 400, 500, 600 and 700, with `opsz` = 14 (the axis default). Output files are in `app/src/main/res/font/`:

- `inter_regular.ttf`
- `inter_medium.ttf`
- `inter_semibold.ttf`
- `inter_bold.ttf`

Each instance was subset with fontTools `subset` to the following characters:

- Basic Latin
- Latin-1 Supplement
- Latin Extended-A
- General Punctuation (U+2000 to U+206F)
- These symbols: − ± × ÷ ½ → ← ↑ ↓ • … ★ ☆ ✓ ✗

The chess symbols U+2654 to U+265F are not present in Inter.

Layout features kept:

- fontTools defaults, minus `frac`/`numr`/`dnom`
- `tnum`, `ss01`, `cv11` (Inter has no `liga` feature)

GPOS was compacted losslessly.
