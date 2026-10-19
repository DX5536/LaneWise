"""Places around Hannover where a lane ends without a junction (3 -> 2, 2 -> 1): the work list for the merging-lanes
check (ROADMAP "Next"). Compare each with DOP20 / Mapillary / the phone-app survey and check that LaneWise moves the
driver out of the ending lane early.

Found from OSM only (nothing is changed):
  DROP   two consecutive ways of the same road (same name / ref, same driving direction) meet at a node that no other
         road uses, and the lane count in driving direction drops there (`lanes`, `lanes:forward|backward`)
  MERGE  `turn:lanes` with `merge_to_left` / `merge_to_right` (the side that ends is tagged)
For DROP the side that ends is read from the way before the node (`merge_to_*`, else `placement` / unknown).

Output: tools/streetview/work/merging_lanes.json and .geojson (JOSM). Overpass results are cached per day.
Usage: python -I tools/streetview/merging_lanes.py [--bbox S,W,N,E]     Standard library only.
"""
import argparse
import datetime as dt
import json
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import big_junctions as bj  # noqa: E402

WORK = bj.WORK
CLASSES = "motorway|motorway_link|" + bj.CLASSES
ROADS = ("motorway|motorway_link|trunk|trunk_link|primary|primary_link|secondary|secondary_link|tertiary|"
         "tertiary_link|unclassified|residential|living_street|service|road")


def dist(a, b):
    k = math.cos(math.radians((a[0] + b[0]) / 2))
    return math.hypot((a[0] - b[0]) * 111320, (a[1] - b[1]) * 111320 * k)


def directions(w):
    """(forward?, lanes, turn:lanes) per driving direction of way [w]."""
    t = w["tags"]
    ow = bj.oneway(t)
    out = []
    for fwd in (True, False):
        if ow == 1 and not fwd or ow == -1 and fwd:
            continue
        n = bj.lanes_in_direction(t, fwd if ow != -1 else True, ow != 0)
        tl = t.get("turn:lanes" if ow else ("turn:lanes:forward" if fwd else "turn:lanes:backward"))
        if n is None and tl:
            n = len(tl.split("|"))
        out.append((fwd, n, tl))
    return out


def road_id(t):
    return t.get("ref") or t.get("name")


def ending_side(tl):
    if not tl:
        return None
    parts = tl.split("|")
    if "merge_to_left" in parts[-1]:
        return "right"
    if "merge_to_right" in parts[0]:
        return "left"
    return None


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--bbox", default="52.20,9.45,52.70,10.15")
    args = ap.parse_args()
    os.makedirs(bj.CACHE, exist_ok=True)
    s, w, n, e = args.bbox.split(",")
    q = f'[out:json][timeout:240];way({s},{w},{n},{e})["highway"~"^({CLASSES})$"]["lanes"];out body geom;'
    print("Overpass: roads with lanes ...")
    data = bj.cached(f"merge_{args.bbox.replace(',', '_')}_{dt.date.today().isoformat()}", lambda: bj.overpass(q))
    ways = [x for x in data["elements"] if x.get("type") == "way" and len(x.get("nodes", [])) >= 2]
    starts, ends = {}, {}
    for x in ways:
        starts.setdefault(x["nodes"][0], []).append(x)
        ends.setdefault(x["nodes"][-1], []).append(x)

    found = []
    # DROP: way a ends where way b starts (both drawn in driving direction; two-way roads checked both ways).
    for node, ins in ends.items():
        outs = starts.get(node, [])
        for a in ins:
            for b in outs:
                if a is b or road_id(a["tags"]) is None or road_id(a["tags"]) != road_id(b["tags"]):
                    continue
                da = {f: (nl, tl) for f, nl, tl in directions(a)}
                db = {f: (nl, tl) for f, nl, tl in directions(b)}
                for fwd in (True, False):
                    if fwd not in da or fwd not in db:
                        continue
                    # Forward: a then b. Backward (two-way roads): b then a.
                    (n0, tl0), (n1, _), w0, w1 = (da[fwd], db[fwd], a, b) if fwd else (db[fwd], da[fwd], b, a)
                    if n0 is None or n1 is None or n1 >= n0:
                        continue
                    g = a["geometry"][-1]
                    found.append({"kind": "DROP", "node": node, "at": [g["lat"], g["lon"]],
                                  "road": road_id(a["tags"]), "highway": a["tags"]["highway"],
                                  "lanes": f"{n0} -> {n1}", "way_before": w0["id"], "way_after": w1["id"],
                                  "ends": ending_side(tl0) or "?", "turn_lanes_before": tl0})
    # MERGE: tagged merge lanes.
    for x in ways:
        for fwd, nl, tl in directions(x):
            if tl and "merge_to_" in tl:
                g = x["geometry"][-1 if fwd else 0]
                found.append({"kind": "MERGE", "node": x["nodes"][-1 if fwd else 0], "at": [g["lat"], g["lon"]],
                              "road": road_id(x["tags"]), "highway": x["tags"]["highway"], "lanes": str(nl),
                              "way_before": x["id"], "ends": ending_side(tl) or "?", "turn_lanes_before": tl})

    # A drop at a junction node is a junction (turn lanes leaving), not a merge: keep nodes no other road uses.
    use = {}
    for x in ways:
        for nd in set(x["nodes"]):
            use[nd] = use.get(nd, 0) + 1
    # Already a junction of two lane-tagged roads: skip without asking Overpass.
    found = [f for f in found if f["kind"] == "MERGE" or use.get(f["node"], 0) <= 2]
    ids = sorted({f["node"] for f in found if f["kind"] == "DROP"})
    print(f"{len(ids)} candidate nodes to check for side roads")
    other = {}
    idset = set(ids)
    for i in range(0, len(ids), 150):
        chunk = ",".join(map(str, ids[i:i + 150]))
        qq = f'[out:json][timeout:240];node(id:{chunk});way(bn)["highway"~"^({ROADS})$"];out skel;'
        res = bj.cached(f"merge_nodes_{dt.date.today().isoformat()}_{len(ids)}_{i}", lambda: bj.overpass(qq))
        for wy in res["elements"]:
            for nd in set(wy.get("nodes", [])) & idset:
                other[nd] = other.get(nd, 0) + 1
    kept = [f for f in found if f["kind"] == "MERGE" or other.get(f["node"], 0) <= 2]
    # Nearest junction after the drop is not computed here; the planner check does that on a route.
    kept.sort(key=lambda f: (f["kind"], f["road"] or "", f["at"][0]))
    for i, f in enumerate(kept, 1):
        f["n"] = i
    json.dump(kept, open(os.path.join(WORK, "merging_lanes.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    gj = {"type": "FeatureCollection", "features": [
        {"type": "Feature", "geometry": {"type": "Point", "coordinates": [f["at"][1], f["at"][0]]},
         "properties": {k: v for k, v in f.items() if k != "at"}} for f in kept]}
    json.dump(gj, open(os.path.join(WORK, "merging_lanes.geojson"), "w", encoding="utf-8"), ensure_ascii=False)
    drops = sum(f["kind"] == "DROP" for f in kept)
    print(f"{len(ways)} ways; {drops} lane drops without a junction, {len(kept) - drops} tagged merges "
          f"({len([f for f in found if f['kind'] == 'DROP']) - drops} drops at junctions skipped)")
    print("->", os.path.join(WORK, "merging_lanes.json"))


if __name__ == "__main__":
    main()
