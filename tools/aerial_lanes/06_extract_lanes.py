"""Step 6: measure the number of lanes of every OSM way from the marking masks.

    python 06_extract_lanes.py --area hannover
Output: <data>/measurements_<area>.json   {way_id: {lanes, conf, windows, valid, per_window, osm}}

Method (per way):
  1. Stations every 2 m (skipping 12 m at both ends - junction paint: stop lines, zebras, arrows).
  2. At each station sample the mask on a profile perpendicular to the way (0.1 m steps) and find the
     painted lines crossing it (runs above 50 %, <= 0.8 m wide).
  3. In 40 m windows, lines that recur at the same lateral offset become *tracks*. Their duty cycle
     separates solid lines (>= 65 %) from dashed lane dividers (German dashes: 3 m / 6 m in town,
     6 m / 12 m on motorways -> ~33 %).
  4. Tracks spaced like lanes (2.4-4.6 m) form a chain around the OSM centre line. Lanes in the window =
     gaps in the chain, plus one lane beyond each outermost *dashed* track (a dashed line always has a
     lane on both sides) unless OSM says there is a bike lane on that side (Schutzstreifen line).
  5. The way's value is the most common window value; confidence = share of windows agreeing x coverage.
Lane counts are for the whole carriageway the way represents (both directions for two-way ways), the same
meaning as OSM `lanes`.
"""
import argparse
import json
import math
from collections import Counter

import numpy as np

from common import AREAS, GSD, Mosaic, assumed_half_width, load_roads, sub, _int

STEP = 2.0
END_SKIP = 12.0
WINDOW = 40.0
PROFILE_STEP = 0.1
MIN_LANE, MAX_LANE = 2.4, 4.6


def stations(coords, step):
    pts = np.asarray(coords, float)
    seg = np.diff(pts, axis=0)
    seg_len = np.hypot(seg[:, 0], seg[:, 1])
    cum = np.concatenate([[0], np.cumsum(seg_len)])
    total = cum[-1]
    out = []
    s = END_SKIP
    while s <= total - END_SKIP:
        k = min(np.searchsorted(cum, s, side="right") - 1, len(seg) - 1)
        if seg_len[k] > 0:
            f = (s - cum[k]) / seg_len[k]
            p = pts[k] + seg[k] * f
            # Tangent averaged over +-6 m so polyline kinks do not tilt the profile.
            a = _point_at(pts, cum, seg, seg_len, max(0, s - 6))
            b = _point_at(pts, cum, seg, seg_len, min(total, s + 6))
            t = b - a
            n = np.hypot(*t)
            if n > 0:
                t /= n
                out.append((s, p, np.array([-t[1], t[0]])))   # normal points to the left of the way
        s += step
    return out, total


def _point_at(pts, cum, seg, seg_len, s):
    k = min(np.searchsorted(cum, s, side="right") - 1, len(seg) - 1)
    f = 0 if seg_len[k] == 0 else (s - cum[k]) / seg_len[k]
    return pts[k] + seg[k] * f


def line_hits(profile, offsets):
    """Centres of painted lines crossing one profile; None if the profile is not fully imaged."""
    if np.isnan(profile).any():
        return None
    on = profile >= 128
    hits, i, n = [], 0, len(on)
    while i < n:
        if on[i]:
            j = i
            while j < n and on[j]:
                j += 1
            width = (j - i) * PROFILE_STEP
            if width <= 0.8:
                hits.append(float(offsets[(i + j - 1) // 2]))
            i = j
        else:
            i += 1
    return hits


def tracks(hit_lists, half):
    """Recurring lateral offsets in one window -> [(offset, duty)]."""
    m = len(hit_lists)
    if m == 0:
        return []
    bins = np.arange(-half, half + 0.2, 0.2)
    hist = np.zeros(len(bins))
    for hits in hit_lists:
        seen = set()
        for h in hits:
            b = int(round((h + half) / 0.2))
            for d in (-1, 0, 1):          # +-0.2 m tolerance, counted once per station
                if 0 <= b + d < len(bins) and b + d not in seen:
                    seen.add(b + d)
                    hist[b + d] += 1
    duty = hist / m
    peaks = []
    for k in range(1, len(bins) - 1):
        if duty[k] >= 0.15 and duty[k] >= duty[k - 1] and duty[k] > duty[k + 1]:
            peaks.append((float(bins[k]), float(duty[k])))
    # Double lines (0.1-0.6 m apart) count as one solid track.
    merged = []
    for off, d in peaks:
        if merged and off - merged[-1][0] < 0.7:
            po, pd = merged[-1]
            merged[-1] = ((po + off) / 2, max(pd, d, 0.65 if min(pd, d) >= 0.15 else 0))
        else:
            merged.append((off, d))
    return merged


def lanes_in_window(trk, bike_left, bike_right):
    if not trk:
        return None
    # Chains of lane-spaced tracks; take the one closest to the OSM centre line.
    chains, cur = [], [trk[0]]
    for t in trk[1:]:
        if MIN_LANE <= t[0] - cur[-1][0] <= MAX_LANE:
            cur.append(t)
        else:
            chains.append(cur)
            cur = [t]
    chains.append(cur)
    chain = min(chains, key=lambda c: 0 if c[0][0] <= 0 <= c[-1][0] else min(abs(c[0][0]), abs(c[-1][0])))
    if min(abs(chain[0][0]), abs(chain[-1][0])) > 6 and not (chain[0][0] <= 0 <= chain[-1][0]):
        return None                       # nothing near the way - probably a parallel road
    solid = lambda t: t[1] >= 0.65
    lanes = len(chain) - 1
    right, left = chain[0], chain[-1]     # offsets grow to the left
    if len(chain) == 1:
        # A lone line only counts when it runs along the way itself (not a parallel road or a bridge above).
        if abs(right[0]) > 2.5:
            return None
        return 2 if not solid(right) or abs(right[0]) < 1.5 else None
    if not solid(left) and not bike_left:
        lanes += 1
    if not solid(right) and not bike_right:
        lanes += 1
    return lanes


def bike_sides(tags):
    def lane(v):
        return v in ("lane", "share_busway")
    both = lane(tags.get("cycleway")) or lane(tags.get("cycleway:both"))
    return both or lane(tags.get("cycleway:left")), both or lane(tags.get("cycleway:right"))


def measure(road, mosaic):
    tags = road["tags"]
    # The photo shows whatever is on top: skip tunnels, covered and underground ways.
    if tags.get("tunnel") not in (None, "no") or tags.get("covered") not in (None, "no") or (_int(tags.get("layer")) or 0) < 0:
        return None
    half = min(16.0, assumed_half_width(tags) + 2.0)
    offsets = np.arange(-half, half + 1e-6, PROFILE_STEP)
    st, total = stations(road["utm"], STEP)
    if total < 2 * END_SKIP + WINDOW / 2 or not st:
        return None
    pts = np.array([p for _, p, _ in st])
    nrm = np.array([n for _, _, n in st])
    xs = pts[:, 0:1] + nrm[:, 0:1] * offsets[None, :]
    ys = pts[:, 1:2] + nrm[:, 1:2] * offsets[None, :]
    prof = mosaic.sample(xs, ys)
    hits = [line_hits(prof[i], offsets) for i in range(len(st))]
    bike_left, bike_right = bike_sides(tags)

    per_window = []
    s0 = st[0][0]
    w = 0
    while s0 + w * WINDOW <= st[-1][0]:
        lo, hi = s0 + w * WINDOW, s0 + (w + 1) * WINDOW
        sel = [h for (s, _, _), h in zip(st, hits) if lo <= s < hi]
        valid = [h for h in sel if h is not None]
        if len(valid) >= 0.8 * len(sel) and len(valid) >= 8:
            per_window.append(lanes_in_window(tracks(valid, half), bike_left, bike_right))
        else:
            per_window.append(None)       # missing imagery
        w += 1
    known = [v for v in per_window if v is not None]
    if not known:
        return {"lanes": None, "conf": 0.0, "windows": len(per_window), "valid": 0, "per_window": per_window}
    lanes, n = Counter(known).most_common(1)[0]
    coverage = len(known) / len(per_window)
    conf = n / len(known) * min(1.0, coverage / 0.6) * min(1.0, len(known) / 5)
    return {"lanes": lanes, "conf": round(conf, 3), "windows": len(per_window), "valid": len(known), "per_window": per_window}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--area", default="test", choices=sorted(AREAS))
    a = ap.parse_args()
    roads = load_roads(a.area)
    mosaic = Mosaic(sub("masks"), "png", cache=256)
    # Sort spatially so neighbouring ways reuse cached tiles.
    roads.sort(key=lambda r: (int(r["bbox"][1] // 400), r["bbox"][0]))
    out = {}
    for k, r in enumerate(roads, 1):
        m = measure(r, mosaic)
        if m is not None:
            m["osm"] = _int(r["tags"].get("lanes"))
            m["turn_lanes"] = any(t.startswith("turn:lanes") for t in r["tags"])
            m["highway"] = r["tags"].get("highway")
            out[str(r["id"])] = m
        if k % 500 == 0:
            print(f"{k}/{len(roads)}")
    p = sub(".") / f"measurements_{a.area}.json"
    p.write_text(json.dumps(out), encoding="utf-8")
    measured = [m for m in out.values() if m["lanes"] is not None]
    cmp = [m for m in measured if m["osm"] is not None and m["conf"] >= 0.6]
    agree = sum(1 for m in cmp if m["lanes"] == m["osm"])
    print(f"{len(out)} ways scanned, {len(measured)} with a lane count")
    if cmp:
        print(f"agreement with OSM `lanes` (conf >= 0.6): {agree}/{len(cmp)} = {agree / len(cmp):.0%}")
    print(f"-> {p}")


if __name__ == "__main__":
    main()
