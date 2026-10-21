"""DOP20 crops of junction approaches, turned so the driving direction points up, for counting lanes at the stop line
(AMAP cross-check: every AMAP / OSM disagreement goes to DOP20 first, docs/DATA_SOURCES.md).

Input: way ids (approaches from tools/streetview/work/big_junctions.json, which holds the stop point and heading).
Output: tools/aerial_lanes/work/stoplines/<way>.jpg (70 m before to 25 m after the stop point, 30 m wide, 10 px/m)
and a contact sheet sheet.jpg. Red ticks mark the OSM stop point. Evidence for a person, never data for the app.

Usage: tools/aerial_lanes/.venv/Scripts/python.exe -I tools/aerial_lanes/stopline_crops.py WAY [WAY ...]
"""
import io
import json
import math
import os
import sys
import urllib.request

from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "work", "stoplines")
WMS = ("https://opendata.lgln.niedersachsen.de/doorman/noauth/dop_wms?SERVICE=WMS&VERSION=1.3.0&REQUEST=GetMap"
       "&LAYERS=ni_dop20&STYLES=&CRS=EPSG:3857&BBOX={x0},{y0},{x1},{y1}&WIDTH={w}&HEIGHT={h}&FORMAT=image/jpeg")
R = 6378137.0
HALF, PX = 80.0, 10          # fetched ±80 m, 10 px per metre (DOP20 is 20 cm: upsampled ×2)
W, BACK, AHEAD = 30.0, 70.0, 25.0


def merc(lat, lon):
    return R * math.radians(lon), R * math.log(math.tan(math.pi / 4 + math.radians(lat) / 2))


def crop(lat, lon, head):
    cx, cy = merc(lat, lon)
    h = HALF / math.cos(math.radians(lat))
    size = int(2 * HALF * PX)
    url = WMS.format(x0=cx - h, y0=cy - h, x1=cx + h, y1=cy + h, w=size, h=size)
    img = Image.open(io.BytesIO(urllib.request.urlopen(
        urllib.request.Request(url, headers={"User-Agent": "LaneWise-stopline"}), timeout=90).read())).convert("RGB")
    img = img.rotate(head, resample=Image.BICUBIC, center=(size / 2, size / 2))
    c = size / 2
    v = img.crop((int(c - W / 2 * PX), int(c - AHEAD * PX), int(c + W / 2 * PX), int(c + BACK * PX)))
    d = ImageDraw.Draw(v)
    y = AHEAD * PX
    d.line([(0, y), (20, y)], fill=(255, 40, 40), width=4)
    d.line([(v.width - 20, y), (v.width, y)], fill=(255, 40, 40), width=4)
    return v


def main():
    ways = [int(a) for a in sys.argv[1:]]
    os.makedirs(OUT, exist_ok=True)
    apps = {a["way"]: a for j in json.load(open(os.path.join(ROOT, "tools", "streetview", "work", "big_junctions.json"),
                                                encoding="utf-8")) for a in j["approaches"]}
    tiles = []
    for w in ways:
        a = apps.get(w)
        if a is None:
            print("not in big_junctions.json:", w)
            continue
        t = crop(a["end"][0], a["end"][1], a["heading"])
        t.save(os.path.join(OUT, f"{w}.jpg"), quality=90)
        d = ImageDraw.Draw(t)
        d.rectangle((0, 0, t.width, 22), fill=(0, 0, 0))
        d.text((4, 5), f"{w} {a['name']} {a['turn_lanes']}", fill=(255, 255, 255))
        tiles.append(t)
        print(w, a["name"], a["turn_lanes"])
    if tiles:
        sheet = Image.new("RGB", (sum(t.width + 6 for t in tiles), tiles[0].height), (20, 20, 24))
        x = 0
        for t in tiles:
            sheet.paste(t, (x, 0)); x += t.width + 6
        sheet.save(os.path.join(OUT, "sheet.jpg"), quality=88)


if __name__ == "__main__":
    main()
