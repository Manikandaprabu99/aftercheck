#!/usr/bin/env python3
"""SVG -> Android VectorDrawable converter (small, dependency-free).

Handles:
  * nested <g> with presentation attributes and `style="..."` (style wins),
    with SVG inheritance rules for fill/stroke/stroke-* /fill-rule/*-opacity
  * `opacity` on groups/elements (pushed down into fillAlpha/strokeAlpha,
    since VectorDrawable groups have no alpha)
  * transform="translate()/scale()/rotate()" on <g> and shapes -> nested
    <group android:translateX/Y | scaleX/Y | rotation + pivot>
    (matrix()/skew*() are rejected loudly rather than silently mis-rendered)
  * <path>, <circle>, <ellipse>, <rect> (incl. rx/ry), <line>, <polyline>,
    <polygon> -> android:pathData
  * fill-rule:evenodd -> android:fillType="evenOdd"
  * fill:none -> no fillColor; stroke:none -> no strokeColor
  * colors (#rgb, #rrggbb, #rrggbbaa, rgb(), named) -> #AARRGGBB
  * path data is re-tokenised and re-serialised with explicit separators,
    so compact SVGO output (".5.5", "a1 1 0 011 1") is safe for Android's
    PathParser.

Usage:
  svg2vd.py convert <in.svg> <out.xml> [--size 45dp]
  svg2vd.py tosvg   <in.xml> <out.svg>          (inverse mapping, for checks)
  svg2vd.py check   <in.svg> <out.xml>          (well-formed + path audit)
"""
import math
import re
import sys
import xml.etree.ElementTree as ET

SVG_NS = "http://www.w3.org/2000/svg"
ANDROID_NS = "http://schemas.android.com/apk/res/android"
A = "{%s}" % ANDROID_NS

# --------------------------------------------------------------------------
# Colors
# --------------------------------------------------------------------------
NAMED_COLORS = {
    "black": "000000", "white": "ffffff", "red": "ff0000", "green": "008000",
    "lime": "00ff00", "blue": "0000ff", "yellow": "ffff00", "cyan": "00ffff",
    "aqua": "00ffff", "magenta": "ff00ff", "fuchsia": "ff00ff",
    "gray": "808080", "grey": "808080", "silver": "c0c0c0", "maroon": "800000",
    "olive": "808000", "navy": "000080", "purple": "800080", "teal": "008080",
    "orange": "ffa500", "darkgray": "a9a9a9", "darkgrey": "a9a9a9",
    "lightgray": "d3d3d3", "lightgrey": "d3d3d3", "gold": "ffd700",
    "brown": "a52a2a", "pink": "ffc0cb",
}


def parse_color(value):
    """Return (rrggbb_hex_upper, alpha_0_1) or None for 'none'/'transparent'."""
    v = value.strip().lower()
    if v in ("none", "transparent"):
        return None
    if v == "currentcolor":
        v = "black"  # no CSS color context here; Compose tints anyway
    if v in NAMED_COLORS:
        return NAMED_COLORS[v].upper(), 1.0
    m = re.fullmatch(r"#([0-9a-f]{3,8})", v)
    if m:
        h = m.group(1)
        if len(h) in (3, 4):
            h = "".join(c * 2 for c in h)
        if len(h) == 6:
            return h.upper(), 1.0
        if len(h) == 8:  # CSS #rrggbbaa
            return h[:6].upper(), int(h[6:], 16) / 255.0
    m = re.fullmatch(r"rgba?\(([^)]*)\)", v)
    if m:
        parts = [p.strip() for p in re.split(r"[,\s/]+", m.group(1)) if p.strip()]
        rgb = []
        for p in parts[:3]:
            rgb.append(round(float(p[:-1]) * 2.55) if p.endswith("%") else int(float(p)))
        a = 1.0
        if len(parts) > 3:
            a = float(parts[3][:-1]) / 100 if parts[3].endswith("%") else float(parts[3])
        return "".join("%02X" % max(0, min(255, c)) for c in rgb), a
    if v.startswith("url("):
        raise ValueError("gradients/patterns (%s) are not supported" % value)
    raise ValueError("unrecognised color %r" % value)


def argb(rgb_hex, alpha=1.0):
    return "#%02X%s" % (round(alpha * 255), rgb_hex)


# --------------------------------------------------------------------------
# Path data tokeniser / serialiser
# --------------------------------------------------------------------------
_NUM = r"[+-]?(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?"
ARGC = {"M": 2, "L": 2, "H": 1, "V": 1, "C": 6, "S": 4, "Q": 4, "T": 2, "A": 7, "Z": 0}


def parse_path(d):
    """Parse SVG path data into [(cmd, [floats...]), ...] — one entry per
    command segment (implicit repeats are expanded). Arc flags are read as
    single characters so compact forms like 'a1 1 0 011 1' parse correctly."""
    out = []
    i, n = 0, len(d)
    cmd = None
    num_re = re.compile(_NUM)
    sep_re = re.compile(r"[\s,]*")

    def skip(i):
        return sep_re.match(d, i).end()

    i = skip(i)
    while i < n:
        c = d[i]
        if c.isalpha():
            if c.upper() not in ARGC:
                raise ValueError("bad path command %r in %r" % (c, d[:40]))
            cmd = c
            i = skip(i + 1)
            if cmd.upper() == "Z":
                out.append((cmd, []))
                continue
        elif cmd is None:
            raise ValueError("path data must start with a command: %r" % d[:40])
        elif cmd.upper() == "Z":
            raise ValueError("numbers after Z in %r" % d[:40])
        k = ARGC[cmd.upper()]
        args = []
        for j in range(k):
            if cmd.upper() == "A" and j in (3, 4):
                if i >= n or d[i] not in "01":
                    raise ValueError("bad arc flag in %r at %d" % (d, i))
                args.append(float(d[i]))
                i = skip(i + 1)
                continue
            m = num_re.match(d, i)
            if not m:
                raise ValueError("expected number in %r at %d" % (d, i))
            args.append(float(m.group(0)))
            i = skip(m.end())
        out.append((cmd, args))
        # Implicit repeat: after M/m extra pairs are L/l.
        if cmd == "M":
            cmd = "L"
        elif cmd == "m":
            cmd = "l"
    return out


def fmt(x):
    if x == int(x) and abs(x) < 1e15:
        return str(int(x))
    s = repr(x)
    if "e" in s or "E" in s:
        s = ("%.10f" % x).rstrip("0").rstrip(".")
    return s


def serialise_path(segs):
    parts = []
    for cmd, args in segs:
        if cmd.upper() == "A":
            a = args
            parts.append("%s%s %s %s %d %d %s %s" % (
                cmd, fmt(a[0]), fmt(a[1]), fmt(a[2]), int(a[3]), int(a[4]), fmt(a[5]), fmt(a[6])))
        else:
            parts.append(cmd + " ".join(fmt(v) for v in args))
    return " ".join(parts)


# --------------------------------------------------------------------------
# Shapes -> path data
# --------------------------------------------------------------------------
def fattr(el, name, default=0.0):
    v = el.get(name)
    if v is None or v == "":
        return default
    v = v.strip()
    if v.endswith("px"):
        v = v[:-2]
    if v.endswith("%"):
        raise ValueError("percentage lengths not supported (%s=%s)" % (name, v))
    return float(v)


def shape_to_d(el, tag):
    if tag == "path":
        return el.get("d", "")
    if tag == "circle":
        cx, cy, r = fattr(el, "cx"), fattr(el, "cy"), fattr(el, "r")
        return ellipse_d(cx, cy, r, r)
    if tag == "ellipse":
        cx, cy = fattr(el, "cx"), fattr(el, "cy")
        return ellipse_d(cx, cy, fattr(el, "rx"), fattr(el, "ry"))
    if tag == "rect":
        x, y = fattr(el, "x"), fattr(el, "y")
        w, h = fattr(el, "width"), fattr(el, "height")
        rx_s, ry_s = el.get("rx"), el.get("ry")
        rx = fattr(el, "rx") if rx_s not in (None, "auto") else None
        ry = fattr(el, "ry") if ry_s not in (None, "auto") else None
        if rx is None:
            rx = ry if ry is not None else 0.0
        if ry is None:
            ry = rx
        rx, ry = min(rx, w / 2), min(ry, h / 2)
        if rx <= 0 or ry <= 0:
            return "M%s %sH%sV%sH%sZ" % (fmt(x), fmt(y), fmt(x + w), fmt(y + h), fmt(x))
        return ("M%s %s" % (fmt(x + rx), fmt(y)) +
                "H%s" % fmt(x + w - rx) +
                "A%s %s 0 0 1 %s %s" % (fmt(rx), fmt(ry), fmt(x + w), fmt(y + ry)) +
                "V%s" % fmt(y + h - ry) +
                "A%s %s 0 0 1 %s %s" % (fmt(rx), fmt(ry), fmt(x + w - rx), fmt(y + h)) +
                "H%s" % fmt(x + rx) +
                "A%s %s 0 0 1 %s %s" % (fmt(rx), fmt(ry), fmt(x), fmt(y + h - ry)) +
                "V%s" % fmt(y + ry) +
                "A%s %s 0 0 1 %s %sZ" % (fmt(rx), fmt(ry), fmt(x + rx), fmt(y)))
    if tag == "line":
        return "M%s %sL%s %s" % (fmt(fattr(el, "x1")), fmt(fattr(el, "y1")),
                                 fmt(fattr(el, "x2")), fmt(fattr(el, "y2")))
    if tag in ("polyline", "polygon"):
        nums = [float(t) for t in re.findall(_NUM, el.get("points", ""))]
        if len(nums) < 2:
            return ""
        pts = list(zip(nums[0::2], nums[1::2]))
        d = "M%s %s" % (fmt(pts[0][0]), fmt(pts[0][1]))
        d += "".join("L%s %s" % (fmt(px), fmt(py)) for px, py in pts[1:])
        return d + ("Z" if tag == "polygon" else "")
    raise ValueError("unsupported shape %s" % tag)


def ellipse_d(cx, cy, rx, ry):
    if rx <= 0 or ry <= 0:
        return ""
    return ("M%s %s" % (fmt(cx - rx), fmt(cy)) +
            "A%s %s 0 1 0 %s %s" % (fmt(rx), fmt(ry), fmt(cx + rx), fmt(cy)) +
            "A%s %s 0 1 0 %s %sZ" % (fmt(rx), fmt(ry), fmt(cx - rx), fmt(cy)))


# --------------------------------------------------------------------------
# Style resolution
# --------------------------------------------------------------------------
INHERITED = ("fill", "stroke", "stroke-width", "stroke-linecap", "stroke-linejoin",
             "stroke-miterlimit", "fill-rule", "fill-opacity", "stroke-opacity")
NON_INHERITED = ("opacity", "display", "visibility")
SVG_DEFAULTS = {"fill": "#000000", "stroke": "none", "stroke-width": "1",
                "stroke-linecap": "butt", "stroke-linejoin": "miter",
                "stroke-miterlimit": "4", "fill-rule": "nonzero",
                "fill-opacity": "1", "stroke-opacity": "1"}
WARN_PROPS = ("stroke-dasharray", "filter", "mask", "clip-path", "marker-start",
              "marker-mid", "marker-end", "paint-order")


def own_props(el, warnings):
    props = {}
    for k in INHERITED + NON_INHERITED + WARN_PROPS:
        if el.get(k) is not None:
            props[k] = el.get(k).strip()
    style = el.get("style")
    if style:
        for decl in style.split(";"):
            if ":" in decl:
                k, v = decl.split(":", 1)
                k, v = k.strip(), v.strip()
                v = re.sub(r"\s*!important$", "", v)
                if k in INHERITED + NON_INHERITED + WARN_PROPS:
                    props[k] = v
    for k in WARN_PROPS:
        if k in props and props[k] not in ("none", "normal", ""):
            warnings.append("unsupported property %s=%s ignored" % (k, props[k]))
    return props


def parse_transform(t):
    """Return list of ('translate'|'scale'|'rotate', args) in SVG order."""
    ops = []
    for name, args in re.findall(r"(\w+)\s*\(([^)]*)\)", t or ""):
        nums = [float(x) for x in re.findall(_NUM, args)]
        if name == "translate":
            ops.append(("translate", (nums[0], nums[1] if len(nums) > 1 else 0.0)))
        elif name == "scale":
            ops.append(("scale", (nums[0], nums[1] if len(nums) > 1 else nums[0])))
        elif name == "rotate":
            ops.append(("rotate", (nums[0], nums[1] if len(nums) > 1 else 0.0,
                                   nums[2] if len(nums) > 2 else 0.0)))
        else:
            raise ValueError("transform %s() is not supported by this converter" % name)
    return ops


def transform_groups(ops):
    """SVG applies the transform list right-to-left to points, so the first op
    is the outermost group. Each op becomes its own <group>."""
    groups = []
    for kind, a in ops:
        attrs = {}
        if kind == "translate":
            if a[0]:
                attrs["translateX"] = fmt(a[0])
            if a[1]:
                attrs["translateY"] = fmt(a[1])
        elif kind == "scale":
            attrs["scaleX"] = fmt(a[0])
            attrs["scaleY"] = fmt(a[1])
        elif kind == "rotate":
            attrs["rotation"] = fmt(a[0])
            if a[1] or a[2]:
                attrs["pivotX"] = fmt(a[1])
                attrs["pivotY"] = fmt(a[2])
        if attrs:
            groups.append(attrs)
    return groups


def localname(tag):
    return tag.split("}", 1)[1] if "}" in tag else tag


SHAPES = ("path", "circle", "ellipse", "rect", "line", "polyline", "polygon")
SKIP = ("defs", "title", "desc", "metadata", "style", "symbol", "clipPath", "mask",
        "linearGradient", "radialGradient", "pattern", "filter")


class Converter:
    def __init__(self):
        self.warnings = []
        self.src_paths = []   # list of (tag, d) of drawable source elements
        self.out_paths = []   # list of pathData emitted

    def convert(self, svg_root, size_dp=None):
        if localname(svg_root.tag) != "svg":
            raise ValueError("root is not <svg>")
        vb = svg_root.get("viewBox")
        if vb:
            minx, miny, vw, vh = [float(x) for x in re.split(r"[\s,]+", vb.strip())]
        else:
            minx = miny = 0.0
            vw, vh = fattr(svg_root, "width"), fattr(svg_root, "height")
        vec = ET.Element("vector")
        if size_dp:
            w = h = size_dp
        else:
            w, h = fmt(vw) + "dp", fmt(vh) + "dp"
        vec.set(A + "width", w)
        vec.set(A + "height", h)
        vec.set(A + "viewportWidth", fmt(vw))
        vec.set(A + "viewportHeight", fmt(vh))
        parent = vec
        if minx or miny:
            parent = ET.SubElement(vec, "group")
            parent.set(A + "translateX", fmt(-minx))
            parent.set(A + "translateY", fmt(-miny))
        inherited = dict(SVG_DEFAULTS)
        root_props = own_props(svg_root, self.warnings)
        for k in INHERITED:
            if k in root_props and root_props[k] != "inherit":
                inherited[k] = root_props[k]
        opacity = float(root_props.get("opacity", "1"))
        for child in svg_root:
            self.walk(child, parent, inherited, opacity)
        return vec

    def walk(self, el, parent, inherited, opacity):
        tag = localname(el.tag)
        if not isinstance(el.tag, str) or tag in SKIP:
            return
        props = own_props(el, self.warnings)
        if props.get("display") == "none":
            if tag in SHAPES:
                self.src_paths.append((tag, None))  # counted but intentionally hidden
            return
        style = dict(inherited)
        for k in INHERITED:
            if k in props and props[k] != "inherit":
                style[k] = props[k]
        op = opacity * float(props.get("opacity", "1"))
        out_parent = parent
        for g in transform_groups(parse_transform(el.get("transform"))):
            out_parent = ET.SubElement(out_parent, "group")
            for k, v in g.items():
                out_parent.set(A + k, v)
        if tag in ("g", "a", "switch"):
            for child in el:
                self.walk(child, out_parent, style, op)
            return
        if tag == "use":
            self.warnings.append("<use> not supported; skipped")
            return
        if tag not in SHAPES:
            self.warnings.append("unsupported element <%s> skipped" % tag)
            return
        d = shape_to_d(el, tag)
        self.src_paths.append((tag, d))
        if not d.strip():
            self.warnings.append("empty geometry on <%s> skipped" % tag)
            return
        segs = parse_path(d)
        if style.get("visibility") == "hidden":
            return
        p = ET.SubElement(out_parent, "path")
        path_data = serialise_path(segs)
        p.set(A + "pathData", path_data)
        self.out_paths.append(path_data)
        fill = parse_color(style["fill"])
        if fill is not None:
            rgb, a = fill
            p.set(A + "fillColor", argb(rgb, a))
            fa = float(style["fill-opacity"]) * op
            if fa < 1:
                p.set(A + "fillAlpha", fmt(round(fa, 4)))
        stroke = parse_color(style["stroke"])
        sw = float(re.sub(r"px$", "", style["stroke-width"]))
        if stroke is not None and sw > 0:
            rgb, a = stroke
            p.set(A + "strokeColor", argb(rgb, a))
            p.set(A + "strokeWidth", fmt(sw))
            sa = float(style["stroke-opacity"]) * op
            if sa < 1:
                p.set(A + "strokeAlpha", fmt(round(sa, 4)))
            cap = style["stroke-linecap"]
            if cap not in ("butt", "round", "square"):
                raise ValueError("bad stroke-linecap %s" % cap)
            if cap != "butt":
                p.set(A + "strokeLineCap", cap)
            join = style["stroke-linejoin"]
            join = {"miter-clip": "miter", "arcs": "miter"}.get(join, join)
            if join not in ("miter", "round", "bevel"):
                raise ValueError("bad stroke-linejoin %s" % join)
            if join != "miter":
                p.set(A + "strokeLineJoin", join)
            ml = float(style["stroke-miterlimit"])
            if ml != 4 and join == "miter":
                p.set(A + "strokeMiterLimit", fmt(ml))
        if style["fill-rule"] == "evenodd" and fill is not None:
            p.set(A + "fillType", "evenOdd")


def indent(elem, level=0):
    pad = "\n" + "    " * level
    if len(elem):
        if not (elem.text or "").strip():
            elem.text = pad + "    "
        for child in elem:
            indent(child, level + 1)
        if not (child.tail or "").strip():
            child.tail = pad
    if level and not (elem.tail or "").strip():
        elem.tail = pad


def write_vector(vec, out_path, header_comment=None):
    ET.register_namespace("android", ANDROID_NS)
    indent(vec)
    body = ET.tostring(vec, encoding="unicode")
    # ElementTree puts xmlns:android wherever; ensure it is on <vector>.
    if 'xmlns:android=' not in body.split(">", 1)[0]:
        body = body.replace("<vector", '<vector xmlns:android="%s"' % ANDROID_NS, 1)
    # One attribute per line for readability / diffs.
    def split_attrs(m):
        tag, attrs, end = m.group(1), m.group(2), m.group(3)
        ws = re.match(r"\s*", m.string[m.string.rfind("\n", 0, m.start()) + 1:m.start()]).group(0)
        items = re.findall(r'\S+?="[^"]*"', attrs)
        if len(items) <= 1:
            return m.group(0)
        sep = "\n" + ws + "    "
        return "<" + tag + sep + sep.join(items) + end
    body = re.sub(r"<(\w+)((?:\s+\S+?=\"[^\"]*\")+)\s*(/?>)", split_attrs, body)
    txt = '<?xml version="1.0" encoding="utf-8"?>\n'
    if header_comment:
        txt += "<!--\n" + header_comment.rstrip() + "\n-->\n"
    txt += body + "\n"
    with open(out_path, "w", encoding="utf-8", newline="\n") as f:
        f.write(txt)


# --------------------------------------------------------------------------
# Inverse mapping VectorDrawable -> SVG (for visual round-trip checks only)
# --------------------------------------------------------------------------
def vd_to_svg(vd_root):
    g = lambda el, k, d=None: el.get(A + k, d)
    vw, vh = g(vd_root, "viewportWidth"), g(vd_root, "viewportHeight")
    svg = ET.Element("svg", {"xmlns": SVG_NS, "viewBox": "0 0 %s %s" % (vw, vh),
                             "width": vw, "height": vh})

    def conv(src, dst):
        for el in src:
            t = localname(el.tag)
            if t == "group":
                tx, ty = float(g(el, "translateX", 0)), float(g(el, "translateY", 0))
                sx, sy = float(g(el, "scaleX", 1)), float(g(el, "scaleY", 1))
                rot = float(g(el, "rotation", 0))
                px, py = float(g(el, "pivotX", 0)), float(g(el, "pivotY", 0))
                # Android: T(t+p) R S T(-p)
                tr = "translate(%s %s) rotate(%s) scale(%s %s) translate(%s %s)" % (
                    fmt(tx + px), fmt(ty + py), fmt(rot), fmt(sx), fmt(sy), fmt(-px), fmt(-py))
                conv(el, ET.SubElement(dst, "g", {"transform": tr}))
            elif t == "path":
                a = {"d": g(el, "pathData")}
                fc = g(el, "fillColor")
                if fc:
                    al = int(fc[1:3], 16) / 255 * float(g(el, "fillAlpha", 1))
                    a["fill"] = "#" + fc[3:]
                    if al < 1:
                        a["fill-opacity"] = fmt(round(al, 4))
                else:
                    a["fill"] = "none"
                if g(el, "fillType") == "evenOdd":
                    a["fill-rule"] = "evenodd"
                sc = g(el, "strokeColor")
                if sc:
                    al = int(sc[1:3], 16) / 255 * float(g(el, "strokeAlpha", 1))
                    a["stroke"] = "#" + sc[3:]
                    if al < 1:
                        a["stroke-opacity"] = fmt(round(al, 4))
                    a["stroke-width"] = g(el, "strokeWidth", "0")
                    a["stroke-linecap"] = g(el, "strokeLineCap", "butt")
                    a["stroke-linejoin"] = g(el, "strokeLineJoin", "miter")
                    a["stroke-miterlimit"] = g(el, "strokeMiterLimit", "4")
                ET.SubElement(dst, "path", a)
    conv(vd_root, svg)
    return svg


# --------------------------------------------------------------------------
# CLI
# --------------------------------------------------------------------------
def audit(conv):
    """Every source path d must round-trip to the identical numeric segment list."""
    problems = []
    src = [(t, d) for t, d in conv.src_paths if d]
    if len(src) != len(conv.out_paths):
        problems.append("path count mismatch: %d source drawables vs %d output paths"
                        % (len(src), len(conv.out_paths)))
    for (t, d), out in zip(src, conv.out_paths):
        if parse_path(d) != parse_path(out):
            problems.append("pathData differs for <%s> d=%r" % (t, d[:50]))
    return problems


def main(argv):
    if len(argv) < 3:
        print(__doc__)
        return 2
    mode = argv[0]
    if mode in ("convert", "check"):
        src, dst = argv[1], argv[2]
        size = None
        if "--size" in argv:
            size = argv[argv.index("--size") + 1]
        conv = Converter()
        vec = conv.convert(ET.parse(src).getroot(), size)
        comment = None
        if "--comment" in argv:
            comment = argv[argv.index("--comment") + 1]
        if mode == "convert":
            write_vector(vec, dst, comment)
        root = ET.parse(dst).getroot()  # well-formedness check of the written file
        if root.tag != "vector":
            print("FAIL %s: root is %s" % (dst, root.tag))
            return 1
        n_out_file = sum(1 for e in root.iter() if e.tag == "path")
        problems = audit(conv)
        n_src = len([1 for _, d in conv.src_paths if d])
        if n_out_file != n_src:
            problems.append("written file has %d <path>, expected %d" % (n_out_file, n_src))
        tags = {}
        for t, _ in conv.src_paths:
            tags[t] = tags.get(t, 0) + 1
        status = "OK " if not problems else "FAIL"
        print("%s %-28s src=%s out_paths=%d%s" % (
            status, dst.rsplit("/", 1)[-1], tags, n_out_file,
            "" if not conv.warnings else " warnings=%s" % conv.warnings))
        for p in problems:
            print("     - " + p)
        return 1 if problems else 0
    if mode == "tosvg":
        svg = vd_to_svg(ET.parse(argv[1]).getroot())
        ET.register_namespace("", SVG_NS)
        ET.ElementTree(svg).write(argv[2], encoding="unicode")
        return 0
    print(__doc__)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
