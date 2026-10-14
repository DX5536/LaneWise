"""Step 2: download the DOP20 tiles (1024 px = 204.8 m, EPSG:25832) that cover the roads of an area.

    python 02_fetch_dop.py --area hannover [--workers 3] [--limit 50]
Output: <data>/dop/<i>_<j>.jpg   (already downloaded tiles are skipped, so the run can be resumed)

Imagery: LGLN DOP20, (c) LGLN 2026, CC BY 4.0 - attribution must be kept in the app.
Please be gentle with the public WMS: few workers, it is a one-off download.
"""
import argparse
import time
from concurrent.futures import ThreadPoolExecutor, as_completed

import requests

from common import AREAS, TILE_PX, WMS, area_utm, load_roads, sub, tiles_along


def fetch(t, folder, session):
    out = folder / f"{t.name}.jpg"
    if out.exists() and out.stat().st_size > 1000:
        return "skip"
    x0, y0, x1, y1 = t.bbox
    params = {"SERVICE": "WMS", "VERSION": "1.3.0", "REQUEST": "GetMap", "LAYERS": "ni_dop20", "STYLES": "",
              "CRS": "EPSG:25832", "BBOX": f"{x0},{y0},{x1},{y1}", "WIDTH": TILE_PX, "HEIGHT": TILE_PX,
              "FORMAT": "image/jpeg"}
    for attempt in range(5):
        try:
            r = session.get(WMS, params=params, timeout=60)
            if r.status_code == 200 and r.headers.get("content-type", "").startswith("image/"):
                tmp = out.with_suffix(".part")
                tmp.write_bytes(r.content)
                tmp.replace(out)
                return "ok"
        except requests.RequestException:
            pass
        time.sleep(3 * (attempt + 1))
    return "fail"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--area", default="test", choices=sorted(AREAS))
    ap.add_argument("--workers", type=int, default=3)
    ap.add_argument("--limit", type=int, default=0, help="only the first N tiles (for trying things out)")
    a = ap.parse_args()

    ax0, ay0, ax1, ay1 = area_utm(a.area)
    tiles = set()
    for r in load_roads(a.area):
        tiles |= tiles_along(r["utm"], buffer_m=15)
    tiles = sorted(t for t in tiles if t.bbox[2] > ax0 and t.bbox[0] < ax1 and t.bbox[3] > ay0 and t.bbox[1] < ay1)
    if a.limit:
        tiles = tiles[: a.limit]
    folder = sub("dop")
    print(f"{len(tiles)} tiles (~{len(tiles) * 0.18:.0f} MB) -> {folder}")

    session = requests.Session()
    session.headers["User-Agent"] = "LaneWise-aerial-lanes/0.1 (one-off download for road-marking research)"
    stats = {"ok": 0, "skip": 0, "fail": 0}
    t0 = time.time()
    with ThreadPoolExecutor(a.workers) as ex:
        futs = [ex.submit(fetch, t, folder, session) for t in tiles]
        for k, f in enumerate(as_completed(futs), 1):
            stats[f.result()] += 1
            if k % 50 == 0 or k == len(futs):
                el = time.time() - t0
                print(f"{k}/{len(futs)} {stats}  {el / 60:.1f} min")
    if stats["fail"]:
        print("some tiles failed - just run again to retry them")


if __name__ == "__main__":
    main()
