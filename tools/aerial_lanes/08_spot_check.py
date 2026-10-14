"""Step 8: build a page of image crops for spot checks.

    python 08_spot_check.py --area hannover [--n 60] [--kind disagree|gap|agree|all]
Output: <data>/spot/index.html  (open in a browser)

Each card shows the aerial photo along the way (straightened: the way runs left -> right, its left side is
up), the marking mask, the OSM value and the photo value. Record your verdicts in spot_checks.csv:
  way_id,verdict,note        verdict = ok | wrong | <correct number of lanes>
then rerun 07_build_asset.py. Verified ways also make good training material (see README).
"""
import argparse
import html
import json
import random

import cv2
import numpy as np

from common import AREAS, GSD, Mosaic, load_roads, sub

importlib_extract = __import__("importlib").import_module("06_extract_lanes")


def strip(road, mosaic_rgb, mosaic_mask, half=14.0, max_len=160.0):
    st, total = importlib_extract.stations(road["utm"], GSD)
    if not st:
        return None
    mid = total / 2
    st = [x for x in st if abs(x[0] - mid) <= max_len / 2]
    offsets = np.arange(half, -half, -GSD)    # rows: left side of the way on top
    pts = np.array([p for _, p, _ in st])
    nrm = np.array([n for _, _, n in st])
    xs = pts[:, 0:1] + nrm[:, 0:1] * offsets[None, :]
    ys = pts[:, 1:2] + nrm[:, 1:2] * offsets[None, :]
    chans = [mosaic_rgb.sample(xs, ys, band=c) for c in range(3)]
    rgb = np.nan_to_num(np.stack(chans, -1)).transpose(1, 0, 2).astype(np.uint8)
    mask = np.nan_to_num(mosaic_mask.sample(xs, ys)).T.astype(np.uint8)
    over = rgb.copy()
    over[mask >= 128] = (0.4 * over[mask >= 128] + 0.6 * np.array([58, 243, 184])).astype(np.uint8)   # lime (BGR)
    centre = over.shape[0] // 2
    over[centre, ::12] = (245, 76, 108)                                                                  # violet = OSM line
    return cv2.resize(np.vstack([rgb, over]), None, fx=2, fy=2, interpolation=cv2.INTER_CUBIC)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--area", default="test", choices=sorted(AREAS))
    ap.add_argument("--n", type=int, default=60)
    ap.add_argument("--kind", default="all", choices=["disagree", "gap", "agree", "all"])
    a = ap.parse_args()
    meas = json.loads((sub(".") / f"measurements_{a.area}.json").read_text(encoding="utf-8"))
    roads = {str(r["id"]): r for r in load_roads(a.area)}

    def kind(m):
        if m["lanes"] is None:
            return None
        if m["osm"] is None:
            return "gap"
        return "agree" if m["osm"] == m["lanes"] else "disagree"
    cand = [w for w, m in meas.items() if kind(m) and (a.kind == "all" or kind(m) == a.kind) and m["conf"] >= 0.5]
    random.Random(7).shuffle(cand)
    cand = cand[: a.n]

    out = sub("spot")
    rgb, mask = Mosaic(sub("dop"), "jpg", cache=128), Mosaic(sub("masks"), "png", cache=128)
    cards = []
    for wid in cand:
        r, m = roads.get(wid), meas[wid]
        if r is None:
            continue
        img = strip(r, rgb, mask)
        if img is None:
            continue
        cv2.imwrite(str(out / f"{wid}.jpg"), img, [cv2.IMWRITE_JPEG_QUALITY, 88])
        t = r["tags"]
        cards.append(f"""<div class=card><img src="{wid}.jpg"><div class=meta>
<b><a href="https://www.openstreetmap.org/way/{wid}" target=_blank>way {wid}</a></b> {html.escape(t.get('name', ''))}
 &middot; {t.get('highway')}{' &middot; oneway' if t.get('oneway') == 'yes' else ''}<br>
OSM lanes: <b>{m['osm'] if m['osm'] is not None else '&ndash;'}</b> &nbsp; photo: <b>{m['lanes']}</b>
 (conf {m['conf']:.2f}, windows {m['per_window']}) &nbsp; <span class=k>{kind(m)}</span></div></div>""")
    (out / "index.html").write_text(f"""<!doctype html><meta charset=utf-8><title>Aerial lanes spot check</title>
<style>body{{font:14px system-ui;background:#16181f;color:#e8e8ef;margin:16px}}a{{color:#b8f33a}}
.card{{margin:0 0 18px;background:#22252e;border-radius:10px;padding:8px}}img{{max-width:100%;image-rendering:pixelated}}
.k{{color:#9a82ff}}</style><h2>Aerial lanes - spot check ({a.area}, {len(cards)} ways)</h2>
<p>Top: photo straightened along the way (way runs left&rarr;right, its left side is up). Bottom: detected markings
(lime) and the OSM centre line (violet dots). Write verdicts to <code>tools/aerial_lanes/spot_checks.csv</code>.</p>
{''.join(cards)}""", encoding="utf-8")
    print(f"{len(cards)} cards -> {out / 'index.html'}")


if __name__ == "__main__":
    main()
