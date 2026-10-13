#!/usr/bin/env python3
"""Rasterize original SVG and VD->SVG round-trip, diff them, build a contact sheet.
usage: roundtrip_check.py <pieces_svg_dir> <drawable_dir> <out_dir>"""
import os
import sys
import xml.etree.ElementTree as ET

import cairosvg
from PIL import Image, ImageChops, ImageDraw

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import svg2vd  # noqa: E402

src_dir, dr_dir, out_dir = sys.argv[1:4]
os.makedirs(out_dir, exist_ok=True)
PX = 180
BG = (128, 128, 128, 255)
pieces = ["wK", "wQ", "wR", "wB", "wN", "wP", "bK", "bQ", "bR", "bB", "bN", "bP"]

sheet = Image.new("RGBA", (PX * 3 + 40, len(pieces) // 2 * (PX + 10) + 10), (255, 255, 255, 255))
worst = []
for i, p in enumerate(pieces):
    vd = os.path.join(dr_dir, "piece_%s.xml" % p.lower())
    rt_svg = os.path.join(out_dir, "%s_rt.svg" % p)
    ET.register_namespace("", svg2vd.SVG_NS)
    ET.ElementTree(svg2vd.vd_to_svg(ET.parse(vd).getroot())).write(rt_svg, encoding="unicode")
    a_png, b_png = os.path.join(out_dir, "%s_orig.png" % p), os.path.join(out_dir, "%s_rt.png" % p)
    cairosvg.svg2png(url=os.path.join(src_dir, p + ".svg"), write_to=a_png, output_width=PX, output_height=PX)
    cairosvg.svg2png(url=rt_svg, write_to=b_png, output_width=PX, output_height=PX)
    a = Image.alpha_composite(Image.new("RGBA", (PX, PX), BG), Image.open(a_png).convert("RGBA"))
    b = Image.alpha_composite(Image.new("RGBA", (PX, PX), BG), Image.open(b_png).convert("RGBA"))
    diff = ImageChops.difference(a.convert("RGB"), b.convert("RGB"))
    px = list(diff.getdata())
    maxd = max(max(t) for t in px)
    ndiff = sum(1 for t in px if max(t) > 8)
    worst.append((p, maxd, ndiff))
    # contact sheet: 2 pieces per row -> [orig | rt] [orig | rt]? keep simple: rows of (orig, rt, diff*4)
    row = i
    y = 10 + (row % (len(pieces) // 2)) * (PX + 10)
    x0 = 0 if row < len(pieces) // 2 else 0
    # put first 6 in sheet 1, next 6 in sheet 2
    if i == len(pieces) // 2:
        sheet.save(os.path.join(out_dir, "sheet_white.png"))
        sheet = Image.new("RGBA", sheet.size, (255, 255, 255, 255))
    sheet.paste(a, (10, y))
    sheet.paste(b, (20 + PX, y))
    amp = diff.point(lambda v: min(255, v * 4)).convert("RGBA")
    sheet.paste(amp, (30 + 2 * PX, y))
sheet.save(os.path.join(out_dir, "sheet_black.png"))

print("piece  max_channel_diff  pixels_diff>8 (of %d)" % (PX * PX))
for p, m, n in worst:
    print("%-5s  %3d               %d" % (p, m, n))
