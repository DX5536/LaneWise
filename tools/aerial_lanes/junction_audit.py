"""Junction audit: the app's lanes, arrows and recommended lane on the official aerial photo, before every junction.

Input:  app/build/reports/lanewise/junctions/j_*.json  (JunctionAuditLiveTest)
Output: app/build/reports/lanewise/junctions/j_*.png and sheet_*.jpg

Each image is rotated so the car drives upward; the stop line is near the top third.
Left: the plain photo (read the painted arrows there). Right: the same with the app's drawing on top:
  white = road edges, cyan = lane lines (yellow = solid), red = stop line, magenta = the app's arrows,
  lime outline = recommended lane, green outline = other usable lanes, orange = route line.
Photo: LGLN DOP20 (20 cm), (c) LGLN, CC BY 4.0. Needs Pillow (the tools/aerial_lanes venv has it).

Usage: python tools/aerial_lanes/junction_audit.py [dir]
"""
import glob
import io
import json
import math
import os
import sys
import urllib.request

from PIL import Image, ImageDraw, ImageFont

WMS = ("https://opendata.lgln.niedersachsen.de/doorman/noauth/dop_wms?SERVICE=WMS&VERSION=1.3.0&REQUEST=GetMap"
       "&LAYERS=ni_dop20&STYLES=&CRS=EPSG:3857&BBOX={x0},{y0},{x1},{y1}&WIDTH={w}&HEIGHT={h}&FORMAT=image/jpeg")
R = 6378137.0
HALF = 45.0          # metres around the stop point that are fetched
PX_PER_M = 10        # 0.1 m per pixel (the photo has 0.2 m; upsampled for drawing)
VIEW_W, VIEW_BACK, VIEW_AHEAD = 40.0, 38.0, 22.0   # shown: width, metres before and after the stop line

COLORS = {"EDGE": (255, 255, 255), "DIVIDER": (0, 230, 255), "DIVIDER_SOLID": (255, 230, 0), "STOP": (255, 40, 40),
          "ARROW": (255, 0, 220), "RECOMMENDED": (170, 255, 40), "ALLOWED": (40, 200, 90)}


def merc(lat, lon):
    return R * math.radians(lon), R * math.log(math.tan(math.pi / 4 + math.radians(lat) / 2))


def fetch(lat, lon):
    cx, cy = merc(lat, lon)
    k = 1 / math.cos(math.radians(lat))          # mercator units per ground metre
    h = HALF * k
    size = int(2 * HALF * PX_PER_M)
    url = WMS.format(x0=cx - h, y0=cy - h, x1=cx + h, y1=cy + h, w=size, h=size)
    req = urllib.request.Request(url, headers={"User-Agent": "LaneWise-junction-audit"})
    img = Image.open(io.BytesIO(urllib.request.urlopen(req, timeout=60).read())).convert("RGB")

    def px(p):
        x, y = merc(p[0], p[1])
        return ((x - (cx - h)) / (2 * h) * size, ((cy + h) - y) / (2 * h) * size)
    return img, px


def render(j, out_png):
    lat, lon = j["stop"]
    photo, px = fetch(lat, lon)
    over = photo.copy()
    d = ImageDraw.Draw(over, "RGBA")
    shapes = j["shapes"]
    order = {"ALLOWED": 0, "RECOMMENDED": 1}
    for s in sorted(shapes, key=lambda s: order.get(s["k"], 2)):
        k = s["k"]
        if k not in COLORS:
            continue
        pts = [px(p) for p in s["p"]]
        if len(pts) < 2:
            continue
        c = COLORS[k]
        if s["poly"]:
            if k in ("RECOMMENDED", "ALLOWED"):
                d.polygon(pts, fill=c + (45,), outline=c + (255,))
                d.line(pts + [pts[0]], fill=c + (255,), width=3 if k == "RECOMMENDED" else 2)
            else:
                d.polygon(pts, fill=c + (110,), outline=c + (255,))
        else:
            d.line(pts, fill=c + (230,), width=5 if k == "STOP" else 2)
    if len(j["route"]) > 1:
        d.line([px(p) for p in j["route"]], fill=(255, 150, 0, 200), width=2)

    # Rotate so the driving direction points up, then crop the stretch before / after the stop line.
    def view(img):
        r = img.rotate(j["heading"], resample=Image.BICUBIC, center=(img.width / 2, img.height / 2))
        c = img.width / 2
        return r.crop((int(c - VIEW_W / 2 * PX_PER_M), int(c - VIEW_AHEAD * PX_PER_M),
                       int(c + VIEW_W / 2 * PX_PER_M), int(c + VIEW_BACK * PX_PER_M)))
    a, b = view(photo), view(over)
    pad = 70
    out = Image.new("RGB", (a.width * 2 + 10, a.height + pad), (20, 22, 28))
    out.paste(a, (0, pad))
    out.paste(b, (a.width + 10, pad))
    t = ImageDraw.Draw(out)
    try:
        font = ImageFont.truetype("arial.ttf", 17)
    except OSError:
        font = ImageFont.load_default()
    lanes = " | ".join(j["lanes"]) if j["lanes"] else "NO LANE DATA (signal only)"
    usable = "".join("o" if x else "-" for x in (j["allowed"] or []))
    t.text((8, 6), f"#{j['n']} @{j['d']} m  {j['name']}  {j['maneuver']}  turn={j['turn']}", fill=(255, 255, 255), font=font)
    t.text((8, 28), f"lanes: {lanes}", fill=(255, 220, 120), font=font)
    t.text((8, 48), f"usable: {usable}   recommended lane (from left): {j['rec']}", fill=(170, 255, 40), font=font)
    out.save(out_png)
    return out


def main():
    d = sys.argv[1] if len(sys.argv) > 1 else "app/build/reports/lanewise/junctions"
    files = sorted(glob.glob(os.path.join(d, "j_*.json")))
    imgs = []
    for f in files:
        j = json.load(open(f, encoding="utf-8"))
        try:
            imgs.append(render(j, f[:-5] + ".png"))
            print("ok", os.path.basename(f), j["name"])
        except Exception as e:  # noqa: BLE001 - one bad tile must not stop the audit
            print("failed", os.path.basename(f), e)
    # Contact sheets, 6 per sheet.
    for k in range(0, len(imgs), 6):
        part = imgs[k:k + 6]
        w, h = part[0].width // 2, part[0].height // 2
        sheet = Image.new("RGB", (w * 3, h * 2), (0, 0, 0))
        for i, im in enumerate(part):
            sheet.paste(im.resize((w, h)), ((i % 3) * w, (i // 3) * h))
        sheet.save(os.path.join(d, f"sheet_{k // 6}.jpg"), quality=85)


if __name__ == "__main__":
    main()
