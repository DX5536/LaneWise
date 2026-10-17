"""Survey of the large junctions around Hannover: every approach with 5+ lanes (or 4 lanes going left, straight and
right) in OSM `turn:lanes`, checked for OSM inconsistencies and shown with street-level photos - the work list for
fine-tuning lanes junction by junction (OSM fixes, or lane_overrides.json with the photo as source).

Area (default): Region Hannover with Langenhagen, Laatzen / Rethen, Garbsen, Burgdorf, Lehrte ... and Celle:
bbox 52.20,9.45,52.70,10.15 (south, west, north, east; --bbox to change).

Automatic checks (OSM only - flags for a person, nothing is changed):
  LANES_MISMATCH  `lanes` (in that direction) differs from the number of arrows (e.g. lanes=2 with 3 turn:lanes)
  UNMARKED        some lanes have no arrow ("none" / empty) at a large junction
  SHORT_WAY       the way with the arrows is < 25 m (split junction: the arrows may belong to the next node)
  NO_PHOTOS       no Mapillary / Panoramax photo of the approach
  OLD_PHOTOS      newest photo older than 3 years (re-markings since then are not visible)
  DETECTION_HINT  Mapillary detected an arrow direction near the approach that no OSM lane has (machine hint only)

Output: tools/streetview/work/big_junctions.html, big_junctions.json and big_junctions.geojson (opens in JOSM next to
lane_reports.geojson). Photos and detections are cached in work/cache/ so a re-run is fast; --no-photos for OSM only.

Usage: python -I tools/streetview/big_junctions.py [--bbox S,W,N,E] [--no-photos] [--limit N]
Token for Mapillary: MAPILLARY_TOKEN in local.properties (see streetview_audit.py). Standard library only.
"""
import argparse
import datetime as dt
import html
import json
import math
import os
import sys
import time
import urllib.parse
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import streetview_audit as sv  # noqa: E402

WORK = sv.WORK
CACHE = os.path.join(WORK, "cache")
MIRRORS = ["https://overpass-api.de/api/interpreter", "https://overpass.private.coffee/api/interpreter",
           "https://maps.mail.ru/osm/tools/overpass/api/interpreter"]
CLASSES = "motorway_link|trunk|trunk_link|primary|primary_link|secondary|secondary_link|tertiary|tertiary_link"
FAMILY = {"left": "L", "sharp_left": "L", "slight_left": "L", "reverse": "L", "through": "S",
          "right": "R", "sharp_right": "R", "slight_right": "R"}


def overpass(query):
    last = None
    for attempt in range(3):
        for url in MIRRORS:
            try:
                req = urllib.request.Request(url, data=urllib.parse.urlencode({"data": query}).encode(),
                                             headers={"User-Agent": sv.UA})
                with urllib.request.urlopen(req, timeout=240) as r:
                    return json.load(r)
            except Exception as e:  # noqa: BLE001 - try the next mirror
                last = e
                print(f"  overpass {url.split('/')[2]}: {e}", file=sys.stderr)
        time.sleep(20 * (attempt + 1))
    raise RuntimeError(f"Overpass unavailable: {last}")


def bearing(a, b):
    y = math.sin(math.radians(b[1] - a[1])) * math.cos(math.radians(b[0]))
    x = (math.cos(math.radians(a[0])) * math.sin(math.radians(b[0])) -
         math.sin(math.radians(a[0])) * math.cos(math.radians(b[0])) * math.cos(math.radians(b[1] - a[1])))
    return (math.degrees(math.atan2(y, x)) + 360) % 360


def oneway(tags):
    v = tags.get("oneway")
    if v in ("yes", "true", "1"):
        return 1
    if v == "-1":
        return -1
    if v == "no":
        return 0
    return 1 if tags.get("highway", "").startswith("motorway") or tags.get("junction") in ("roundabout", "circular") else 0


def lanes_in_direction(tags, forward, ow):
    """Same rules as the app's OsmTags.lanesInDirection."""
    d = "forward" if forward else "backward"
    if tags.get(f"lanes:{d}", "").isdigit():
        return int(tags[f"lanes:{d}"])
    if ow:
        return int(tags["lanes"]) if tags.get("lanes", "").isdigit() else None
    total = tags.get("lanes", "").split(";")[0].strip()
    if not total.isdigit():
        return None
    both = int(tags["lanes:both_ways"]) if tags.get("lanes:both_ways", "").isdigit() else 0
    other_d = "backward" if forward else "forward"
    other = (int(tags[f"lanes:{other_d}"]) if tags.get(f"lanes:{other_d}", "").isdigit()
             else len(tags[f"turn:lanes:{other_d}"].split("|")) if f"turn:lanes:{other_d}" in tags else None)
    return max(1, int(total) - other - both) if other is not None else max(1, (int(total) - both) // 2)


def approaches(data):
    out = []
    for w in data["elements"]:
        if w.get("type") != "way" or len(w.get("geometry", [])) < 2:
            continue
        t = w.get("tags", {})
        g = [(p["lat"], p["lon"]) for p in w["geometry"]]
        ow = oneway(t)
        for forward in (True, False):
            d = "forward" if forward else "backward"
            v = t.get(f"turn:lanes:{d}") or (t.get("turn:lanes") if ow == (1 if forward else -1) else None)
            if not v:
                continue
            lanes = v.split("|")
            fams = {FAMILY.get(tok.strip()) for lane in lanes for tok in lane.split(";")} - {None}
            n = len(lanes)
            if not (n >= 5 or (n == 4 and {"L", "S", "R"} <= fams)):
                continue
            pts = g if forward else g[::-1]
            end, before = pts[-1], pts[-2]
            length = sum(sv.dist(pts[i], pts[i + 1]) for i in range(len(pts) - 1))
            flags = []
            count = lanes_in_direction(t, forward, ow != 0)
            if count is not None and count != n:
                flags.append(f"LANES_MISMATCH (lanes {count}, arrows {n})")
            if any(l.strip() in ("", "none") for l in lanes):
                flags.append("UNMARKED")
            if length < 25:
                flags.append(f"SHORT_WAY ({length:.0f} m)")
            out.append({"way": w["id"], "direction": d, "name": t.get("name") or t.get("ref") or "", "highway": t.get("highway"),
                        "turn_lanes": v, "lanes": n, "families": "".join(sorted(fams)), "end": end,
                        "heading": round(bearing(before, end), 1), "length": round(length), "flags": flags})
    return out


def cluster(apps):
    """Junction = approach ends within 70 m of each other."""
    js = []
    for a in sorted(apps, key=lambda x: (x["end"][0], x["end"][1])):
        for j in js:
            if sv.dist(j["center"], a["end"]) < 70:
                j["approaches"].append(a)
                n = len(j["approaches"])
                j["center"] = ((j["center"][0] * (n - 1) + a["end"][0]) / n, (j["center"][1] * (n - 1) + a["end"][1]) / n)
                break
        else:
            js.append({"center": a["end"], "approaches": [a]})
    return js


def cached(key, fn):
    path = os.path.join(CACHE, key + ".json")
    if os.path.exists(path):
        return json.load(open(path, encoding="utf-8"))
    v = fn()
    json.dump(v, open(path, "w", encoding="utf-8"))
    return v


def add_photos(a, token):
    lat, lon = a["end"]
    alat, alon = sv.dest(lat, lon, (a["heading"] + 180) % 360, sv.BACK)
    key = f"{a['way']}_{a['direction']}"
    photos, hints, errs = [], [], []
    if token:
        try:
            photos += sv.newest_first(cached("mly_" + key, lambda: sv.mapillary(alat, alon, a["heading"], token)))[:4]
            hints = cached("det_" + key, lambda: sv.mapillary_detections(lat, lon, token))
        except Exception as e:  # noqa: BLE001
            errs.append(f"Mapillary: {e}")
    try:
        photos += sv.newest_first(cached("pnx_" + key, lambda: sv.panoramax(alat, alon, a["heading"])))[:4]
    except Exception as e:  # noqa: BLE001
        errs.append(f"Panoramax: {e}")
    a["photos"], a["detections"], a["errors"] = photos, hints, errs
    a["kartaview"] = sv.kartaview_link(alat, alon)
    if not photos:
        a["flags"].append("NO_PHOTOS")
    else:
        newest = max(p["date"] for p in photos if p["date"]) if any(p["date"] for p in photos) else ""
        if newest and newest < (dt.date.today() - dt.timedelta(days=3 * 365)).isoformat():
            a["flags"].append(f"OLD_PHOTOS (newest {newest})")
    seen = {d.split("--")[-1] for d in hints if "arrow" in d}
    fam = {"left": "L", "straight": "S", "right": "R", "turn-left": "L", "turn-right": "R"}
    missing = {f for s in seen for k, f in fam.items() if s.startswith(k)} - set(a["families"])
    if missing:
        a["flags"].append(f"DETECTION_HINT (detected {','.join(sorted(missing))} not in OSM)")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--bbox", default="52.20,9.45,52.70,10.15")
    ap.add_argument("--no-photos", action="store_true")
    ap.add_argument("--limit", type=int, default=0, help="only the first N junctions (testing)")
    args = ap.parse_args()
    os.makedirs(CACHE, exist_ok=True)
    s, w, n, e = args.bbox.split(",")
    q = (f'[out:json][timeout:200];way({s},{w},{n},{e})["highway"~"^({CLASSES})$"][~"^turn:lanes"~"\\\\|"];'
         "out tags geom;")
    print("Overpass: ways with turn:lanes ...")
    data = cached(f"osm_{args.bbox.replace(',', '_')}_{dt.date.today().isoformat()}", lambda: overpass(q))
    apps = approaches(data)
    js = cluster(apps)
    js.sort(key=lambda j: -max(a["lanes"] for a in j["approaches"]))
    if args.limit:
        js = js[: args.limit]
    print(f"{len(data['elements'])} ways, {len(apps)} large approaches, {len(js)} junctions")
    token = "" if args.no_photos else sv.mapillary_token()
    if not args.no_photos:
        if not token:
            print("MAPILLARY_TOKEN not found: Panoramax only", file=sys.stderr)
        for i, j in enumerate(js):
            for a in j["approaches"]:
                add_photos(a, token)
            if i % 20 == 0:
                print(f"  photos {i + 1}/{len(js)}")
    for j in js:
        j["name"] = " / ".join(sorted({a["name"] for a in j["approaches"] if a["name"]})) or "?"
        j["flags"] = sum(len(a["flags"]) for a in j["approaches"])
    js.sort(key=lambda j: (-j["flags"], -max(a["lanes"] for a in j["approaches"])))
    json.dump(js, open(os.path.join(WORK, "big_junctions.json"), "w", encoding="utf-8"), indent=1)
    geojson(js)
    page(js, args.bbox)
    print("->", os.path.join(WORK, "big_junctions.html"))
    counts = {}
    for j in js:
        for a in j["approaches"]:
            for f in a["flags"]:
                counts[f.split(" ")[0]] = counts.get(f.split(" ")[0], 0) + 1
    print("flags:", counts)


def geojson(js):
    feats = []
    for j in js:
        for a in j["approaches"]:
            feats.append({"type": "Feature", "geometry": {"type": "Point", "coordinates": [a["end"][1], a["end"][0]]},
                          "properties": {"note": "LaneWise large-junction survey", "junction": j["name"], "osm_way": a["way"],
                                         "direction": a["direction"], "turn:lanes": a["turn_lanes"],
                                         "flags": "; ".join(a["flags"])}})
    json.dump({"type": "FeatureCollection", "features": feats},
              open(os.path.join(WORK, "big_junctions.geojson"), "w", encoding="utf-8"), indent=1)


def page(js, bbox):
    e = html.escape
    secs = []
    for k, j in enumerate(js):
        rows = []
        for a in j["approaches"]:
            pics = "".join(f'<a class="ph" href="{e(p["view"])}" target="_blank"><img loading="lazy" src="{e(p["thumb"] or "")}">'
                           f'<span>{e(p["source"])} · {e(p["date"])} · {p["dist"]} m</span></a>' for p in a.get("photos", []))
            flags = "".join(f'<span class="flag">{e(f)}</span>' for f in a["flags"])
            det = (f'<p class="hint">Detections: {e(", ".join(a["detections"]))}</p>' if a.get("detections") else "")
            rows.append(
                f'<div class="ap"><p><b>{e(a["name"])}</b> · way <a href="https://www.openstreetmap.org/way/{a["way"]}" '
                f'target="_blank">{a["way"]}</a> {e(a["direction"])} · <code>{e(a["turn_lanes"])}</code> · heading {a["heading"]:.0f}° '
                f'· <a href="{e(a.get("kartaview", ""))}" target="_blank">KartaView</a> {flags}</p>{det}<div class="row">{pics}</div></div>')
        lat, lon = j["center"]
        secs.append(f'<section><h2>{k + 1}. {e(j["name"])} <small><a href="https://www.openstreetmap.org/#map=19/{lat:.5f}/{lon:.5f}" '
                    f'target="_blank">map</a> · {len(j["approaches"])} large approaches</small></h2>{"".join(rows)}</section>')
    doc = f"""<!doctype html><meta charset="utf-8"><title>Large junctions</title>
<style>body{{font:14px system-ui;background:#111;color:#eee;margin:16px}}a{{color:#b8f33a}}section{{border-top:1px solid #333;padding:6px 0}}
.row{{display:flex;flex-wrap:wrap;gap:6px}}.ph{{display:flex;flex-direction:column;width:240px;color:#ccc;text-decoration:none;font-size:12px}}
.ph img{{width:240px;height:150px;object-fit:cover;background:#222}}.flag{{background:#5a1d1d;color:#fbb;border-radius:4px;padding:1px 6px;margin-left:4px;font-size:12px}}
.hint{{color:#9cf;margin:2px 0}}code{{color:#ffd76a}}.ap{{margin:6px 0 10px}}</style>
<h1>Large junctions ({len(js)}) - bbox {e(bbox)}</h1>
<p>Sorted by number of flags. Flags are automatic OSM checks and machine hints - a person decides. Fix OSM, or add a
lane_overrides.json entry with the photo as source. Photos: Mapillary / Panoramax contributors, CC BY-SA 4.0. Map data
© OpenStreetMap contributors (ODbL).</p>{"".join(secs)}"""
    open(os.path.join(WORK, "big_junctions.html"), "w", encoding="utf-8").write(doc)


if __name__ == "__main__":
    main()
