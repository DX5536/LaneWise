"""Step 1: download the drivable OSM ways of an area (with tags + geometry) and project them to UTM 32.

    python 01_osm_roads.py --area hannover
Output: <data>/roads_<area>.json  - [{id, tags, utm: [[e, n], ...], bbox: [e0, n0, e1, n1]}]
"""
import argparse
import json
import time

import requests

from common import AREAS, OVERPASS, ROAD_CLASSES, data_dir, to_utm


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--area", default="test", choices=sorted(AREAS))
    a = ap.parse_args()
    s, w, n, e = AREAS[a.area]
    q = (f'[out:json][timeout:180];way({s},{w},{n},{e})["highway"~"^({"|".join(ROAD_CLASSES)})$"]'
         f'["area"!="yes"];out tags geom;')
    data = None
    for attempt in range(8):
        url = OVERPASS[attempt % len(OVERPASS)]
        try:
            r = requests.post(url, data={"data": q}, timeout=240,
                              headers={"User-Agent": "LaneWise-aerial-lanes/0.1 (offline research tool)"})
            if r.status_code == 200:
                data = r.json()
                break
            print(f"{url}: HTTP {r.status_code}, retrying")
        except requests.RequestException as ex:
            print(f"{url}: {ex}, retrying")
        time.sleep(5 * (attempt + 1))
    if data is None:
        raise SystemExit("Overpass failed")

    roads = []
    for el in data["elements"]:
        geom = el.get("geometry") or []
        if len(geom) < 2:
            continue
        utm = [to_utm(p["lon"], p["lat"]) for p in geom]
        xs = [p[0] for p in utm]
        ys = [p[1] for p in utm]
        roads.append({"id": el["id"], "tags": el.get("tags", {}), "utm": [[round(x, 2), round(y, 2)] for x, y in utm],
                      "bbox": [min(xs), min(ys), max(xs), max(ys)]})
    out = data_dir() / f"roads_{a.area}.json"
    out.write_text(json.dumps(roads), encoding="utf-8")
    with_lanes = sum(1 for r in roads if "lanes" in r["tags"])
    print(f"{len(roads)} ways ({with_lanes} with OSM lanes) -> {out}")


if __name__ == "__main__":
    main()
