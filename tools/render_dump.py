"""Draws the render-audit dumps (app/build/reports/lanewise/render_*.json from RenderAuditLiveTest) as PNG images.

Usage: python tools/render_dump.py [dump dir]   (needs Pillow, e.g. the tools/aerial_lanes venv)
"""
import glob, json, os, sys
from PIL import Image, ImageDraw

d = sys.argv[1] if len(sys.argv) > 1 else "app/build/reports/lanewise"
COL = {"SURFACE": (58, 63, 75), "BIKE": (229, 72, 77), "EDGE": (255, 255, 255), "DIVIDER": (187, 204, 204),
       "DIVIDER_SOLID": (255, 255, 204), "STOP": (255, 230, 0), "RECOMMENDED": (184, 243, 58), "ALLOWED": (90, 120, 40),
       "ARROW": (230, 230, 230), "ZEBRA": (240, 240, 240)}
for f in sorted(glob.glob(os.path.join(d, "render_*.json"))):
    data = json.load(open(f, encoding="utf-8"))
    size, ppm = 900, 7.0
    img = Image.new("RGB", (size, size), (20, 22, 28))
    g = ImageDraw.Draw(img)
    def px(p): return (size / 2 + p[0] * ppm, size / 2 - p[1] * ppm)
    order = ["SURFACE", "BIKE", "ALLOWED", "RECOMMENDED", "ARROW", "ZEBRA"]
    shapes = sorted(data["shapes"], key=lambda s: order.index(s["k"]) if s["k"] in order else 99)
    for s in shapes:
        pts = [px(p) for p in s["p"]]
        if len(pts) < 2: continue
        c = COL.get(s["k"], (119, 170, 255))
        if s["poly"]: g.polygon(pts, fill=c)
        else: g.line(pts, fill=c, width=2)
    if len(data["route"]) > 1: g.line([px(p) for p in data["route"]], fill=(255, 0, 255), width=1)
    if len(data["path"]) > 1: g.line([px(p) for p in data["path"]], fill=(0, 255, 255), width=1)
    g.ellipse((size / 2 - 10, size / 2 - 10, size / 2 + 10, size / 2 + 10), outline=(255, 160, 0), width=2)
    g.text((8, 8), data["title"], fill=(255, 255, 255))
    img.save(f[:-5] + ".png")
    print(f[:-5] + ".png")
