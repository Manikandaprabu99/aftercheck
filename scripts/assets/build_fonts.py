#!/usr/bin/env python3
"""Instantiate Inter[opsz,wght] at static weights and subset.
usage: build_fonts.py <Inter-VF.ttf> <out_dir>"""
import os
import sys

from fontTools import subset
from fontTools.otlLib import optimize
from fontTools.ttLib import TTFont
from fontTools.varLib import instancer

src, out_dir = sys.argv[1], sys.argv[2]
WEIGHTS = {400: "inter_regular", 500: "inter_medium", 600: "inter_semibold", 700: "inter_bold"}
OPSZ = 14  # axis default (range 14..32)

ranges = [(0x20, 0x7E), (0xA0, 0xFF), (0x100, 0x17F), (0x2000, 0x206F)]
symbols = "−±×÷½→←↑↓•…★☆✓✗♔♕♖♗♘♙♚♛♜♝♞♟"
wanted = set()
for a, b in ranges:
    wanted.update(range(a, b + 1))
wanted.update(ord(c) for c in symbols)

EXTRA_FEATURES = ["kern", "liga", "calt", "tnum", "ss01", "cv11"]

for wght, name in WEIGHTS.items():
    vf = TTFont(src)
    cmap = vf.getBestCmap()
    present = sorted(u for u in wanted if u in cmap)
    gsub_feats = {fr.FeatureTag for fr in vf["GSUB"].table.FeatureList.FeatureRecord}
    gpos_feats = {fr.FeatureTag for fr in vf["GPOS"].table.FeatureList.FeatureRecord}
    inst = instancer.instantiateVariableFont(vf, {"wght": wght, "opsz": OPSZ},
                                             updateFontNames=True)
    opts = subset.Options()
    # Default subsetter features minus frac/numr/dnom (not requested; they pull in ~40
    # numerator/denominator glyphs, ~6 KB; U+00BD ½ stays via cmap), plus the requested ones.
    opts.layout_features = sorted((set(opts.layout_features) - {"frac", "numr", "dnom"}) |
                                  {f for f in EXTRA_FEATURES if f in gsub_feats | gpos_feats})
    opts.name_IDs = [0, 1, 2, 3, 4, 5, 6, 13, 14, 16, 17]  # keep copyright + license + URL
    opts.notdef_outline = True
    opts.recalc_bounds = True
    opts.drop_tables += ["STAT"]  # irrelevant for a static instance on Android
    sub = subset.Subsetter(opts)
    sub.populate(unicodes=present)
    sub.subset(inst)
    optimize.compact(inst, 9)  # lossless GPOS PairPos compaction (~7 KB saved)
    out = os.path.join(out_dir, name + ".ttf")
    inst.save(out)
    print("built %s wght=%d" % (out, wght))

missing = sorted(u for u in wanted if u not in TTFont(src).getBestCmap())
in_ranges = set()
for a, b in ranges:
    in_ranges.update(range(a, b + 1))
print("requested codepoints absent from Inter (dropped):",
      " ".join("U+%04X" % u for u in missing if u not in in_ranges) or "none")
print("absent within the requested blocks: %d codepoints (unassigned/unsupported)"
      % len([u for u in missing if u in in_ranges]))
