# cburnett chess pieces

- **Author:** Colin M.L. Burnett
- **License:** GNU General Public License, version 2 or (at your option) any later version (GPLv2+)
- **Source:** https://github.com/lichess-org/lila/tree/master/public/piece/cburnett
  (raw files: `https://raw.githubusercontent.com/lichess-org/lila/master/public/piece/cburnett/{wK,wQ,wR,wB,wN,wP,bK,bQ,bR,bB,bN,bP}.svg`)
- **Upstream revision:** lichess-org/lila commit `5b44d719ec5913dd62aa965f4b8d21e60a00c2c1`
  (latest commit touching `public/piece/cburnett` when fetched on 2026-10-13)

## Modifications

The 12 SVGs were converted to Android VectorDrawable XML:
`app/src/main/res/drawable/piece_{w,b}{k,q,r,b,n,p}.xml`.

- Viewport is 45x45, and the intrinsic size is 45dp.
- Path geometry is unchanged. The `d` data was re-tokenised with explicit separators.
- `<circle>` elements (black queen) were converted to equivalent arc paths.
- Group styles were flattened onto each path. This covers fill and stroke color, stroke width, cap, join, and `fill-rule` (`evenodd` became `fillType="evenOdd"`).

A raster round-trip check (VectorDrawable rendered back to SVG, then compared with the original at 180px) showed only sub-pixel anti-aliasing differences.
