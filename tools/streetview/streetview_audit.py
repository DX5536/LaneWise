"""Street-level photos for the junction audit: open, crowd-sourced imagery of every junction approach on a route.

Where OSM `turn:lanes` is missing or doubtful and the aerial photo (DOP20, 20 cm) cannot show the painted arrows, a
photo from the road shows them. Sources, in this order (docs/DATA_SOURCES.md, "Street-level imagery"):
  1. Mapillary (primary)   - API v4, needs a free client token: MAPILLARY_TOKEN in local.properties (git-ignored) or
                              in the environment (never commit it). CC BY-SA 4.0.
                              Also lists Mapillary's machine detections near the junction (arrows, signs) as hints.
  2. Panoramax (secondary) - federated STAC API, no key (api.panoramax.xyz searches all instances). CC BY-SA 4.0 /
                              per-photo licence.
  3. KartaView (secondary) - only a viewer link per junction (check by hand).

Safety: the photos are evidence for a person, never data for the app. What a photo shows goes into OSM (JOSM / iD,
Mapillary and Panoramax are allowed sources for OSM) or, until OSM is fixed, into app/src/main/assets/lane_overrides.json
with the photo as `source` (e.g. "Mapillary 123456789, 2025-06-02"). Check the date: a photo older than the
re-marking shows the old lanes. Machine detections are hints only.

Input:  app/build/reports/lanewise/junctions/j_*.json (JunctionAuditLiveTest; the DOP20 crops j_*.png are linked too)
        or --point lat,lon[,heading]
Output: tools/streetview/work/streetview.html + streetview.json (photos are linked, not downloaded)

Usage:  python -I tools/streetview/streetview_audit.py [--dir DIR] [--point LAT,LON[,HEADING]] [--max 4]
        (PowerShell and bash alike; the token is read from local.properties)
Only the Python standard library is needed.
"""
import argparse
import datetime as dt
import glob
import html
import json
import math
import os
import sys
import urllib.parse
import urllib.request

UA = "LaneWise-junction-audit/1.0 (+https://github.com/DX5536/LaneWise)"
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
WORK = os.path.join(os.path.dirname(os.path.abspath(__file__)), "work")

BACK = 30.0        # look this far (m) before the stop line: the arrows sit 6-15 m before it
RADIUS = 45.0      # photos within this distance (m) of that point
HEADING_TOL = 50.0  # non-panoramic photos must look roughly the driving direction (deg)


def mapillary_token():
    """MAPILLARY_TOKEN from the environment, else from local.properties (key=value, like the Gradle keys)."""
    t = os.environ.get("MAPILLARY_TOKEN", "").strip()
    if t:
        return t
    path = os.path.join(ROOT, "local.properties")
    if os.path.exists(path):
        for line in open(path, encoding="utf-8"):
            k, sep, v = line.partition("=")
            if sep and k.strip() == "MAPILLARY_TOKEN":
                # Java .properties may escape ':' and '|' with a backslash.
                return v.strip().strip('"').replace("\\|", "|").replace("\\:", ":")
    return ""


def get_json(url, headers=None):
    req = urllib.request.Request(url, headers={"User-Agent": UA, **(headers or {})})
    with urllib.request.urlopen(req, timeout=40) as r:
        return json.load(r)


def dest(lat, lon, bearing, m):
    """Point [m] metres from (lat, lon) towards [bearing] (small distances)."""
    dlat = m * math.cos(math.radians(bearing)) / 111_320.0
    dlon = m * math.sin(math.radians(bearing)) / (111_320.0 * math.cos(math.radians(lat)))
    return lat + dlat, lon + dlon


def dist(a, b):
    dy = (a[0] - b[0]) * 111_320.0
    dx = (a[1] - b[1]) * 111_320.0 * math.cos(math.radians(a[0]))
    return math.hypot(dx, dy)


def bbox(lat, lon, m):
    dlat = m / 111_320.0
    dlon = m / (111_320.0 * math.cos(math.radians(lat)))
    return lon - dlon, lat - dlat, lon + dlon, lat + dlat


def angle_ok(compass, heading, pano):
    if pano or compass is None or heading is None:
        return True
    return abs((compass - heading + 180) % 360 - 180) <= HEADING_TOL


# --- Mapillary (primary) -------------------------------------------------------------------------------------------

def mapillary(lat, lon, heading, token):
    w, s, e, n = bbox(lat, lon, RADIUS)
    fields = "id,captured_at,compass_angle,computed_compass_angle,geometry,computed_geometry,thumb_1024_url,is_pano,sequence"
    q = urllib.parse.urlencode({"bbox": f"{w:.6f},{s:.6f},{e:.6f},{n:.6f}", "fields": fields, "limit": 200})
    data = get_json(f"https://graph.mapillary.com/images?{q}", {"Authorization": f"OAuth {token}"}).get("data", [])
    out = []
    for im in data:
        g = (im.get("computed_geometry") or im.get("geometry") or {}).get("coordinates")
        if not g:
            continue
        p = (g[1], g[0])
        compass = im.get("computed_compass_angle", im.get("compass_angle"))
        pano = bool(im.get("is_pano"))
        if dist(p, (lat, lon)) > RADIUS or not angle_ok(compass, heading, pano):
            continue
        taken = dt.datetime.fromtimestamp(im["captured_at"] / 1000, dt.timezone.utc).date().isoformat() if im.get("captured_at") else ""
        out.append({"source": "Mapillary", "id": str(im["id"]), "date": taken, "dist": round(dist(p, (lat, lon))),
                    "heading": compass, "pano": pano, "thumb": im.get("thumb_1024_url"),
                    "view": f"https://www.mapillary.com/app/?pKey={im['id']}", "licence": "CC BY-SA 4.0 (Mapillary)"})
    return out


def mapillary_detections(lat, lon, token):
    """Machine-detected map features near the junction: painted arrows and lane / turn signs. Hints only."""
    w, s, e, n = bbox(lat, lon, RADIUS)
    q = urllib.parse.urlencode({"bbox": f"{w:.6f},{s:.6f},{e:.6f},{n:.6f}",
                                "fields": "id,object_value,geometry,last_seen_at", "limit": 200})
    data = get_json(f"https://graph.mapillary.com/map_features?{q}", {"Authorization": f"OAuth {token}"}).get("data", [])
    keep = ("arrow", "lane", "turn", "keep-left", "keep-right", "go-straight")
    return sorted({d.get("object_value", "") for d in data if any(k in d.get("object_value", "") for k in keep)})


# --- Panoramax (secondary) -----------------------------------------------------------------------------------------

def panoramax(lat, lon, heading):
    w, s, e, n = bbox(lat, lon, RADIUS)
    q = urllib.parse.urlencode({"bbox": f"{w:.6f},{s:.6f},{e:.6f},{n:.6f}", "limit": 100})
    feats = get_json(f"https://api.panoramax.xyz/api/search?{q}").get("features", [])
    out = []
    for f in feats:
        c = (f.get("geometry") or {}).get("coordinates")
        if not c:
            continue
        p = (c[1], c[0])
        pr = f.get("properties", {})
        pano = (pr.get("pers:interior_orientation") or {}).get("field_of_view") == 360
        compass = pr.get("view:azimuth")
        if dist(p, (lat, lon)) > RADIUS or not angle_ok(compass, heading, pano):
            continue
        assets = f.get("assets", {})
        out.append({"source": "Panoramax", "id": f["id"], "date": (pr.get("datetime") or "")[:10], "dist": round(dist(p, (lat, lon))),
                    "heading": compass, "pano": pano, "thumb": (assets.get("sd") or assets.get("thumb") or {}).get("href"),
                    "view": f"https://api.panoramax.xyz/#focus=pic&pic={f['id']}",
                    "licence": f"{pr.get('license', 'CC-BY-SA-4.0')} (Panoramax, {pr.get('geovisio:producer', '?')})"})
    return out


def kartaview_link(lat, lon):
    return f"https://kartaview.org/map/@{lat:.6f},{lon:.6f},18z"


# --- audit -----------------------------------------------------------------------------------------------------------

def junctions(directory, point):
    if point:
        v = [float(x) for x in point.split(",")]
        return [{"n": 0, "name": "point", "stop": v[:2], "heading": v[2] if len(v) > 2 else None, "lanes": None, "png": None}]
    out = []
    for f in sorted(glob.glob(os.path.join(directory, "j_*.json"))):
        j = json.load(open(f, encoding="utf-8"))
        png = f[:-5] + ".png"
        out.append({"n": j.get("n"), "name": j.get("name", ""), "maneuver": j.get("maneuver", ""), "stop": j["stop"],
                    "heading": j.get("heading"), "lanes": j.get("lanes"), "rec": j.get("rec"),
                    "png": png if os.path.exists(png) else None})
    return out


def newest_first(items):
    return sorted(items, key=lambda x: (x["date"] or "", -x["dist"]), reverse=True)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--dir", default=os.path.join(ROOT, "app", "build", "reports", "lanewise", "junctions"))
    ap.add_argument("--point", help="lat,lon[,heading] instead of the junction audit")
    ap.add_argument("--max", type=int, default=4, help="photos per source and junction")
    args = ap.parse_args()
    token = mapillary_token()
    if not token:
        print("MAPILLARY_TOKEN not set (local.properties or environment): Mapillary (primary source) skipped - get a "
              "client token at https://www.mapillary.com/dashboard/developers", file=sys.stderr)
    js = junctions(args.dir, args.point)
    if not js:
        sys.exit(f"no junctions in {args.dir} (run JunctionAuditLiveTest first, or use --point)")
    os.makedirs(WORK, exist_ok=True)
    report = []
    for j in js:
        lat, lon = j["stop"]
        h = j.get("heading")
        # The approach: BACK metres before the stop line, against the driving direction.
        alat, alon = dest(lat, lon, (h + 180) % 360, BACK) if h is not None else (lat, lon)
        photos, hints, errors = [], [], []
        if token:
            try:
                photos += newest_first(mapillary(alat, alon, h, token))[: args.max]
                hints = mapillary_detections(lat, lon, token)
            except Exception as e:  # noqa: BLE001 - report and go on with the next source
                errors.append(f"Mapillary: {e}")
        try:
            photos += newest_first(panoramax(alat, alon, h))[: args.max]
        except Exception as e:  # noqa: BLE001
            errors.append(f"Panoramax: {e}")
        report.append({**j, "approach": [alat, alon], "photos": photos, "mapillary_detections": hints, "errors": errors,
                       "kartaview": kartaview_link(alat, alon)})
        print(f"#{j['n']} {j.get('name', '')}: {len(photos)} photos" + (f", errors: {errors}" if errors else ""))
    json.dump(report, open(os.path.join(WORK, "streetview.json"), "w", encoding="utf-8"), indent=1)
    write_html(report)
    print("->", os.path.join(WORK, "streetview.html"))


def write_html(report):
    e = html.escape
    rows = []
    for j in report:
        lanes = " | ".join(j["lanes"]) if j.get("lanes") else "no lane data"
        cards = "".join(
            f'<a class="ph" href="{e(p["view"])}" target="_blank"><img loading="lazy" src="{e(p["thumb"] or "")}">'
            f'<span>{e(p["source"])} · {e(p["date"])} · {p["dist"]} m{" · 360°" if p["pano"] else ""}</span>'
            f'<small>{e(p["licence"])}</small></a>' for p in j["photos"]) or "<p class=none>No photos near this approach.</p>"
        dop = f'<a href="file:///{e(j["png"])}"><img class="dop" src="file:///{e(j["png"])}"></a>' if j.get("png") else ""
        hints = (f'<p class="hint">Mapillary detections (machine, hints only): {e(", ".join(j["mapillary_detections"]))}</p>'
                 if j.get("mapillary_detections") else "")
        errs = "".join(f'<p class="err">{e(x)}</p>' for x in j["errors"])
        rows.append(
            f'<section><h2>#{j["n"]} {e(j.get("name", ""))} <small>{e(j.get("maneuver", ""))}</small></h2>'
            f'<p>App: <b>{e(lanes)}</b>{" · recommended lane " + str(j["rec"]) if j.get("rec", -1) not in (None, -1) else ""}'
            f' · <a href="{e(j["kartaview"])}" target="_blank">KartaView</a></p>{hints}{errs}'
            f'<div class="row">{dop}{cards}</div></section>')
    page = f"""<!doctype html><meta charset="utf-8"><title>LaneWise street-level audit</title>
<style>body{{font:14px system-ui;background:#111;color:#eee;margin:16px}}a{{color:#b8f33a}}section{{border-top:1px solid #333;padding:8px 0}}
.row{{display:flex;flex-wrap:wrap;gap:8px}}.ph{{display:flex;flex-direction:column;width:320px;text-decoration:none;color:#ddd}}
.ph img{{width:320px;height:200px;object-fit:cover;background:#222}}.dop{{height:200px}}small{{color:#888}}
.hint{{color:#9cf}}.err{{color:#f66}}.none{{color:#888}}</style>
<h1>Street-level audit</h1><p>Photos are evidence for you, not data for the app. Check the date (older than the
re-marking = old lanes). Photos: Mapillary / Panoramax contributors, CC BY-SA 4.0; aerial: LGLN DOP20, CC BY 4.0.</p>
{''.join(rows)}"""
    open(os.path.join(WORK, "streetview.html"), "w", encoding="utf-8").write(page)


if __name__ == "__main__":
    main()
