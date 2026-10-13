#!/usr/bin/env python3
"""Fetch Material Symbols Rounded Android XMLs and normalize them.
usage: fetch_icons.py <download_dir> <drawable_out_dir> <names...>
A trailing '*' on a name also fetches the FILL=1 variant (-> ic_<name>_filled.xml).
Normalization: drop android:tint, every fillColor -> #FF000000, keep 24dp + source viewport.
"""
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

BASE = "https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android"
ANDROID = "{http://schemas.android.com/apk/res/android}"


def fetch(name, fill, dl_dir):
    fn = "%s_%s24px.xml" % (name, "fill1_" if fill else "")
    dst = os.path.join(dl_dir, fn)
    url = "%s/%s/materialsymbolsrounded/%s" % (BASE, name, fn)
    r = subprocess.run(["curl", "-sS", "-f", "-o", dst, url], capture_output=True, text=True)
    if r.returncode != 0:
        if os.path.exists(dst):
            os.remove(dst)
        return None, url
    return dst, url


def normalize(src_path, out_path):
    txt = open(src_path, encoding="utf-8").read()
    root = ET.fromstring(txt)  # source must be well-formed
    assert root.tag == "vector", root.tag
    if root.get(ANDROID + "width") != "24dp" or root.get(ANDROID + "height") != "24dp":
        raise ValueError("%s: unexpected size %s x %s" % (src_path, root.get(ANDROID + "width"),
                                                          root.get(ANDROID + "height")))
    other = {e.tag for e in root.iter()} - {"vector", "path", "group"}
    if other:
        raise ValueError("%s: unexpected elements %s" % (src_path, other))
    txt = re.sub(r'\s*android:tint="[^"]*"', "", txt)
    txt = re.sub(r'android:fillColor="[^"]*"', 'android:fillColor="#FF000000"', txt)
    if "strokeColor" in txt:
        raise ValueError("%s: has strokeColor, review manually" % src_path)
    if not txt.endswith("\n"):
        txt += "\n"
    out = ET.fromstring(txt)
    assert out.get(ANDROID + "tint") is None
    paths = [e for e in out.iter() if e.tag == "path"]
    assert paths and all(p.get(ANDROID + "fillColor") == "#FF000000" for p in paths)
    with open(out_path, "w", encoding="utf-8", newline="\n") as f:
        f.write(txt)
    return len(paths), out.get(ANDROID + "viewportWidth"), out.get(ANDROID + "autoMirrored")


def main():
    dl_dir, out_dir = sys.argv[1], sys.argv[2]
    names = sys.argv[3:]
    missing, written = [], []
    for spec in names:
        want_fill = spec.endswith("*")
        name = spec.rstrip("*")
        for fill in ([False, True] if want_fill else [False]):
            src, url = fetch(name, fill, dl_dir)
            if not src:
                missing.append(url)
                continue
            if out_dir == "-":
                continue
            out = os.path.join(out_dir, "ic_%s%s.xml" % (name, "_filled" if fill else ""))
            n, vp, mirrored = normalize(src, out)
            written.append((os.path.basename(out), os.path.getsize(out), n, vp, mirrored))
    for w in written:
        print("wrote %-36s %5d B  paths=%d viewport=%s%s" % (w[0], w[1], w[2], w[3],
                                                            "  autoMirrored" if w[4] else ""))
    print("written: %d   missing: %d" % (len(written), len(missing)))
    for m in missing:
        print("MISSING", m)


if __name__ == "__main__":
    main()
