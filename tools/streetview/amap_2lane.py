"""2-lane junction survey (AMAP cross-check), district by district, with coverage per district.

Plan:     tools/streetview/work/amap_plan_2lane.json   (two-lane approaches with a lane choice, Hannover (Stadt))
Results:  tools/streetview/work/amap_check_2lane.json  (one reading per plan entry `n`; text only, never screenshots)
Districts: OSM Stadtteile (admin_level 10) from the Overpass cache (work/cache/districts_hannover_bbox.json).

Commands (python -I tools/streetview/amap_2lane.py ...):
  assign                      put the Stadtteil of each stop point into the plan (field `district`)
  status                      coverage per district: checked / total and %, sorted by district
  todo DISTRICT [N]           the next N unchecked approaches of a district (default 10), with coordinates
  record N "amap" "lanes this|opposite" verdict "note"
                              store a reading for plan entry N (verdict: match | equivalent | differs |
                              no_arrows | not_found); the OSM side is taken from the plan
Rules: docs/DATA_SOURCES.md (AMAP) - disagreements go to DOP20 / Mapillary first; AMAP only as last resort into
lane_overrides.json. Standard library only.
"""
import datetime as dt
import json
import os
import sys

WORK = os.path.join(os.path.dirname(os.path.abspath(__file__)), "work")
PLAN = os.path.join(WORK, "amap_plan_2lane.json")
CHECK = os.path.join(WORK, "amap_check_2lane.json")
DISTRICTS = os.path.join(WORK, "cache", "districts_hannover_bbox.json")


def load(path, default):
    return json.load(open(path, encoding="utf-8")) if os.path.exists(path) else default


def save(path, data):
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(json.dumps(data, ensure_ascii=False, indent=1) + "\n")


def districts():
    """(name, segments of all outer rings) per Stadtteil; even-odd ray casting needs no ring assembly."""
    out = []
    for r in load(DISTRICTS, {"elements": []})["elements"]:
        if r.get("tags", {}).get("admin_level") != "10":
            continue
        segs = []
        for m in r.get("members", []):
            if m.get("type") != "way" or m.get("role") not in ("outer", ""):
                continue
            g = m.get("geometry") or []
            segs += [((g[i]["lat"], g[i]["lon"]), (g[i + 1]["lat"], g[i + 1]["lon"])) for i in range(len(g) - 1)]
        if segs:
            out.append((r["tags"]["name"], segs))
    return out


def inside(p, segs):
    y, x = p
    c = False
    for (y1, x1), (y2, x2) in segs:
        if (y1 > y) != (y2 > y) and x < (x2 - x1) * (y - y1) / (y2 - y1) + x1:
            c = not c
    return c


def assign():
    plan = load(PLAN, [])
    ds = districts()
    if not ds:
        sys.exit("no district boundaries cached: work/cache/districts_hannover_bbox.json (Overpass, admin_level 9|10 in the Hannover bbox)")
    for a in plan:
        a["district"] = next((n for n, s in ds if inside(a["end"], s)), "outside")
    save(PLAN, plan)
    print(f"{len(plan)} approaches in {len({a['district'] for a in plan})} districts")


def status():
    plan = load(PLAN, [])
    done = {c["n"] for c in load(CHECK, [])}
    by = {}
    for a in plan:
        t = by.setdefault(a.get("district", "?"), [0, 0])
        t[0] += 1
        t[1] += a["n"] in done
    tot = sum(v[0] for v in by.values()); ok = sum(v[1] for v in by.values())
    for d, (n, k) in sorted(by.items()):
        print(f"{d:28s} {k:4d} / {n:4d}  {100 * k / n:5.1f} %")
    print(f"{'all':28s} {ok:4d} / {tot:4d}  {100 * ok / max(1, tot):5.1f} %")


COMPASS = ["N", "NE", "E", "SE", "S", "SW", "W", "NW"]


def heading(a):
    """Driving heading at the stop point (from the OSM API, cached in the plan as `heading`)."""
    if "heading" in a:
        return a["heading"]
    import math, urllib.request
    url = f"https://api.openstreetmap.org/api/0.6/way/{a['way']}/full.json"
    data = json.load(urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "LaneWise-amap-survey"}), timeout=30))
    nodes = {e["id"]: (e["lat"], e["lon"]) for e in data["elements"] if e["type"] == "node"}
    way = next(e for e in data["elements"] if e["type"] == "way")
    pts = [nodes[i] for i in way["nodes"]]
    if a["direction"] == "backward":
        pts = pts[::-1]
    (la1, lo1), (la2, lo2) = pts[-2], pts[-1]
    y = math.sin(math.radians(lo2 - lo1)) * math.cos(math.radians(la2))
    x = math.cos(math.radians(la1)) * math.sin(math.radians(la2)) - math.sin(math.radians(la1)) * math.cos(math.radians(la2)) * math.cos(math.radians(lo2 - lo1))
    a["heading"] = round((math.degrees(math.atan2(y, x)) + 360) % 360)
    return a["heading"]


def todo(district, n=10):
    done = {c["n"] for c in load(CHECK, [])}
    left = [a for a in load(PLAN, []) if a.get("district") == district and a["n"] not in done]
    plan = load(PLAN, [])
    for a in left[:n]:
        h = heading(next(p for p in plan if p["n"] == a["n"]) if False else a)
        print(f"{a['n']:4d}  {a['name']:30s} {COMPASS[round(h / 45) % 8]:>2s}-bound {a['turn_lanes']:24s} "
              f"{a['end'][0]:.5f},{a['end'][1]:.5f}  way {a['way']} ({a['direction']})")
    # keep the fetched headings
    hs = {a["n"]: a["heading"] for a in left[:n] if "heading" in a}
    for p in plan:
        if p["n"] in hs:
            p["heading"] = hs[p["n"]]
    save(PLAN, plan)
    print(f"{len(left)} left in {district}")


def record(n, amap, lanes, verdict, note=""):
    plan = {a["n"]: a for a in load(PLAN, [])}
    a = plan[int(n)]
    rows = [c for c in load(CHECK, []) if c["n"] != int(n)]
    rows.append({"n": int(n), "district": a.get("district"), "approach": f"{a['name']} ({a['direction']}, way {a['way']})",
                 "osm": a["turn_lanes"], "amap": amap, "amap_lanes": lanes, "verdict": verdict, "note": note,
                 "date": dt.date.today().isoformat()})
    save(CHECK, sorted(rows, key=lambda c: c["n"]))
    print(f"{len(rows)} readings")


if __name__ == "__main__":
    cmd, args = (sys.argv[1], sys.argv[2:]) if len(sys.argv) > 1 else ("status", [])
    {"assign": lambda: assign(), "status": lambda: status(),
     "todo": lambda: todo(args[0], int(args[1]) if len(args) > 1 else 10),
     "record": lambda: record(*args)}[cmd]()
