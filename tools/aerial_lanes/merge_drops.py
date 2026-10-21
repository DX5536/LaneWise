"""DOP20 crops of the lane drops whose ending side OSM does not tag (merging-lanes check, ROADMAP part 8).

Reads tools/streetview/work/merging_lanes.json (from tools/streetview/merging_lanes.py) and the Overpass cache it used,
fetches a DOP20 crop around each drop (LGLN WMS), turns it so the driving direction points up, marks the OSM node
where the count drops (red tick at the sides) and writes contact sheets of 6 to work/merge_drops/sheet_N.jpg plus
work/merge_drops/index.json (sheet slot -> drop). A person reads which side's lane ends (taper, merge arrows Z 297.1).

With --photos: the newest Mapillary photo looking in driving direction 20-90 m before each drop (street level shows
the merge arrows and the taper DOP20 is too coarse for) -> work/merge_drops/photo_N.jpg (3 per sheet; CC BY-SA 4.0,
evidence for a person only, never committed). Token: MAPILLARY_TOKEN (local.properties), see streetview_audit.py.

Usage: tools/aerial_lanes/.venv/Scripts/python.exe -I tools/aerial_lanes/merge_drops.py [--all] [--photos]
  --all  every drop, not only the ones without a tagged side
"""
import argparse
import glob
import io
import json
import math
import os
import urllib.request

import sys

from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SV = os.path.join(ROOT, "tools", "streetview", "work")
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "work", "merge_drops")
WMS = ("https://opendata.lgln.niedersachsen.de/doorman/noauth/dop_wms?SERVICE=WMS&VERSION=1.3.0&REQUEST=GetMap"
       "&LAYERS=ni_dop20&STYLES=&CRS=EPSG:3857&BBOX={x0},{y0},{x1},{y1}&WIDTH={w}&HEIGHT={h}&FORMAT=image/jpeg")
R = 6378137.0
HALF = 95.0            # metres fetched around the node
PX_PER_M = 5           # DOP20 native resolution
VIEW_W, VIEW_BACK, VIEW_AHEAD = 44.0, 110.0, 70.0   # shown: width, metres before / after the node


def merc(lat, lon):
    return R * math.radians(lon), R * math.log(math.tan(math.pi / 4 + math.radians(lat) / 2))


def bearing(a, b):
    y = math.sin(math.radians(b[1] - a[1])) * math.cos(math.radians(b[0]))
    x = (math.cos(math.radians(a[0])) * math.sin(math.radians(b[0])) -
         math.sin(math.radians(a[0])) * math.cos(math.radians(b[0])) * math.cos(math.radians(b[1] - a[1])))
    return (math.degrees(math.atan2(y, x)) + 360) % 360


def ways_by_id():
    files = sorted(glob.glob(os.path.join(SV, "cache", "merge_52*.json")))
    data = json.load(open(files[-1], encoding="utf-8"))
    return {w["id"]: w for w in data["elements"] if w.get("type") == "way"}


def heading_at(way, node):
    """Driving heading of [way] arriving at / leaving [node] (way drawn in driving direction or against it)."""
    g = [(p["lat"], p["lon"]) for p in way["geometry"]]
    nodes = way["nodes"]
    i = nodes.index(node)
    if i == len(nodes) - 1:
        h = bearing(g[-2], g[-1])
    else:
        h = bearing(g[0], g[1]) if i == 0 else bearing(g[i - 1], g[i])
    return h


def crop(lat, lon, head):
    cx, cy = merc(lat, lon)
    k = 1 / math.cos(math.radians(lat))
    h = HALF * k
    size = int(2 * HALF * PX_PER_M)
    url = WMS.format(x0=cx - h, y0=cy - h, x1=cx + h, y1=cy + h, w=size, h=size)
    req = urllib.request.Request(url, headers={"User-Agent": "LaneWise-merge-drops"})
    img = Image.open(io.BytesIO(urllib.request.urlopen(req, timeout=90).read())).convert("RGB")
    # Turn so the driving direction points up (PIL rotates counter-clockwise).
    img = img.rotate(head, resample=Image.BICUBIC, center=(size / 2, size / 2))
    c = size / 2
    box = (c - VIEW_W / 2 * PX_PER_M, c - VIEW_AHEAD * PX_PER_M, c + VIEW_W / 2 * PX_PER_M, c + VIEW_BACK * PX_PER_M)
    v = img.crop(tuple(int(b) for b in box))
    d = ImageDraw.Draw(v)
    y = VIEW_AHEAD * PX_PER_M
    d.line([(0, y), (12, y)], fill=(255, 40, 40), width=3)
    d.line([(v.width - 12, y), (v.width, y)], fill=(255, 40, 40), width=3)
    d.polygon([(v.width / 2 - 8, v.height - 6), (v.width / 2 + 8, v.height - 6), (v.width / 2, v.height - 22)],
              fill=(170, 255, 40))
    return v


def photo(lat, lon, head, token):
    """Newest Mapillary photo (not a panorama) 20-90 m before the drop, looking along [head]; (image, meta) or None."""
    sys.path.insert(0, os.path.join(ROOT, "tools", "streetview"))
    import streetview_audit as sv  # noqa: E402 - standard library only
    best = None
    for back in (40.0, 75.0, 20.0):
        p = sv.dest(lat, lon, (head + 180) % 360, back)
        for im in sv.mapillary(p[0], p[1], head, token):
            if im["pano"] or not im["thumb"] or im["heading"] is None:
                continue
            if abs((im["heading"] - head + 180) % 360 - 180) > 25:
                continue
            if best is None or im["date"] > best["date"]:
                best = im
        if best:
            break
    if best is None:
        return None
    req = urllib.request.Request(best["thumb"], headers={"User-Agent": sv.UA})
    img = Image.open(io.BytesIO(urllib.request.urlopen(req, timeout=60).read())).convert("RGB")
    return img, best


def photos(drops, ways):
    sys.path.insert(0, os.path.join(ROOT, "tools", "streetview"))
    import streetview_audit as sv  # noqa: E402
    token = sv.mapillary_token()
    if not token:
        print("MAPILLARY_TOKEN missing"); return
    shots, meta = [], []
    for k, f in enumerate(drops, 1):
        w = ways.get(f["way_before"])
        if w is None or f["node"] not in w["nodes"]:
            continue
        try:
            r = photo(f["at"][0], f["at"][1], heading_at(w, f["node"]), token)
        except Exception as e:  # noqa: BLE001
            print("skip", k, e); continue
        if r is None:
            meta.append({"slot": k, "n": f["n"], "photo": None}); continue
        img, m = r
        img = img.resize((640, int(img.height * 640 / img.width)))
        d = ImageDraw.Draw(img)
        d.rectangle((0, 0, 640, 22), fill=(0, 0, 0))
        d.text((6, 5), f"{k}  {f['road']} {f['lanes']}  Mapillary {m['id']} {m['date']}  {m['dist']} m", fill=(255, 255, 255))
        shots.append(img)
        meta.append({"slot": k, "n": f["n"], "photo": m["id"], "date": m["date"], "view": m["view"]})
        print(f"photo {k}/{len(drops)} {m['date']}")
    for s in range(0, len(shots), 3):
        part = shots[s:s + 3]
        sheet = Image.new("RGB", (640, sum(p.height for p in part) + 6 * (len(part) - 1)), (20, 20, 24))
        y = 0
        for p in part:
            sheet.paste(p, (0, y)); y += p.height + 6
        sheet.save(os.path.join(OUT, f"photo_{s // 3 + 1}.jpg"), quality=85)
    json.dump(meta, open(os.path.join(OUT, "photos.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--all", action="store_true")
    ap.add_argument("--photos", action="store_true")
    args = ap.parse_args()
    os.makedirs(OUT, exist_ok=True)
    drops = [f for f in json.load(open(os.path.join(SV, "merging_lanes.json"), encoding="utf-8"))
             if f["kind"] == "DROP" and (args.all or f["ends"] == "?")]
    ways = ways_by_id()
    if args.photos:
        photos(drops, ways)
        print("->", OUT)
        return
    index, tiles = [], []
    for f in drops:
        w = ways.get(f["way_before"])
        if w is None or f["node"] not in w["nodes"]:
            continue
        try:
            tiles.append(crop(f["at"][0], f["at"][1], heading_at(w, f["node"])))
        except Exception as e:  # noqa: BLE001 - one bad tile must not stop the sheet
            print("skip", f["n"], e)
            continue
        index.append({"slot": len(tiles), "n": f["n"], "road": f["road"], "lanes": f["lanes"], "at": f["at"],
                      "highway": f["highway"], "way_before": f["way_before"], "way_after": f.get("way_after")})
        print(f"{len(tiles)}/{len(drops)} {f['road']} {f['lanes']}")
    per = 6
    for s in range(0, len(tiles), per):
        part = tiles[s:s + per]
        sheet = Image.new("RGB", (sum(t.width for t in part) + 8 * (len(part) - 1), part[0].height + 24), (20, 20, 24))
        d = ImageDraw.Draw(sheet)
        x = 0
        for i, t in enumerate(part):
            sheet.paste(t, (x, 24))
            d.text((x + 4, 4), f"{s + i + 1}", fill=(255, 255, 255))
            x += t.width + 8
        sheet.save(os.path.join(OUT, f"sheet_{s // per + 1}.jpg"), quality=88)
    json.dump(index, open(os.path.join(OUT, "index.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print("->", OUT)


if __name__ == "__main__":
    main()
