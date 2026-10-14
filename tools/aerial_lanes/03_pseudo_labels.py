"""Step 3: bootstrap training labels with a classical road-marking detector (no ML yet).

    python 03_pseudo_labels.py --area hannover [--limit 300]
Output: <data>/labels_auto/<tile>.png   0 = background, 255 = marking, 127 = ignore (off-road)

How it works: paint is bright *and* unsaturated, so we use min(R, G, B), normalise it by the local
brightness (handles building shadows), keep only thin bright structures (white top-hat), restrict to the
OSM carriageway and drop blobs that are not line-like (cars, roofs, glare).

These labels are noisy (missed faint lines, some false hits). They are the starting point; tiles corrected by
hand go into <data>/labels_manual/<tile>.png (same encoding) and always win in training (see README).
"""
import argparse
import random

import cv2
import numpy as np

from common import AREAS, area_utm, load_roads, road_mask, sub, TileId


def detect(img_bgr: np.ndarray, road: np.ndarray) -> np.ndarray:
    v = img_bgr.min(axis=2).astype(np.float32)
    local = cv2.GaussianBlur(v, (0, 0), 12) + 8.0
    ratio = v / local                                   # ~1 on plain asphalt, >1.3 on paint, shadow-invariant
    k = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (7, 7))
    tophat = cv2.morphologyEx(ratio, cv2.MORPH_TOPHAT, k)
    # Hessian ridge: thin bright lines give one strongly negative eigenvalue; kerbs and other step edges do not.
    g = cv2.GaussianBlur(ratio, (0, 0), 1.0)
    dxx = cv2.Sobel(g, cv2.CV_32F, 2, 0, ksize=3)
    dyy = cv2.Sobel(g, cv2.CV_32F, 0, 2, ksize=3)
    dxy = cv2.Sobel(g, cv2.CV_32F, 1, 1, ksize=3)
    tmp = np.sqrt((dxx - dyy) ** 2 + 4 * dxy ** 2)
    l1, l2 = (dxx + dyy + tmp) / 2, (dxx + dyy - tmp) / 2
    ridge = np.where((l2 < 0) & (np.abs(l1) < 0.5 * np.abs(l2)), -l2, 0)
    sat = cv2.cvtColor(img_bgr, cv2.COLOR_BGR2HSV)[..., 1]
    lines = (tophat > 0.15) & (ridge > 0.3)
    blobs = tophat > 0.35                     # arrows, symbols (filtered by shape below)
    cand = ((lines | blobs) & (sat < 70) & (road > 0)).astype(np.uint8)

    n, lab, stats, _ = cv2.connectedComponentsWithStats(cand, connectivity=8)
    keep = np.zeros(n, bool)
    for c in range(1, n):
        area = stats[c, cv2.CC_STAT_AREA]
        if area < 4 or area > 4000:
            continue
        ys, xs = np.nonzero(lab[stats[c, 1]:stats[c, 1] + stats[c, 3], stats[c, 0]:stats[c, 0] + stats[c, 2]] == c)
        if len(xs) < 4:
            continue
        (cx, cy), (w, h), _ = cv2.minAreaRect(np.stack([xs, ys], 1).astype(np.float32))
        length, width = max(w, h), max(1.0, min(w, h))
        # Lines / dashes: long and thin. Arrows and stop lines are kept when compact but bright enough (area check).
        if length >= 8 and length / width >= 3.0 and width <= 4.5:
            keep[c] = True
        elif 25 <= area <= 400 and length / width < 3.0 and tophat[lab == c].mean() > 0.4:
            keep[c] = True          # arrow heads, symbols
    marks = keep[lab]
    out = np.where(road > 0, 0, 127).astype(np.uint8)
    out[marks] = 255
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--area", default="test", choices=sorted(AREAS))
    ap.add_argument("--limit", type=int, default=0, help="random subset of N tiles (enough for training)")
    a = ap.parse_args()
    roads = load_roads(a.area)
    dop, out_dir = sub("dop"), sub("labels_auto")
    tiles = sorted(dop.glob("*.jpg"))
    ax0, ay0, ax1, ay1 = area_utm(a.area)
    tiles = [p for p in tiles if (b := TileId.parse(p.stem).bbox)[2] > ax0 and b[0] < ax1 and b[3] > ay0 and b[1] < ay1]
    if a.limit:
        random.Random(1).shuffle(tiles)
        tiles = tiles[: a.limit]
    for k, p in enumerate(tiles, 1):
        t = TileId.parse(p.stem)
        img = cv2.imread(str(p))
        if img is None:
            continue
        lab = detect(img, road_mask(t, roads))
        cv2.imwrite(str(out_dir / f"{t.name}.png"), lab)
        if k % 50 == 0:
            print(f"{k}/{len(tiles)}")
    print(f"{len(tiles)} label tiles -> {out_dir}")


if __name__ == "__main__":
    main()
