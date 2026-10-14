"""Step 7: turn the measurements into the file shipped with the app.

    python 07_build_asset.py --area hannover [--min-conf 0.7]
Output: app/src/main/assets/aerial_lanes.json

Only ways where the aerial value is useful are written:
  * OSM has no `lanes` tag              -> "fills a gap"
  * OSM `lanes` differs from the photos -> "disagrees" (the app decides whether to trust it, see AerialLanes.kt)
Ways where both agree are left out (OSM already right, keeps the file small).

Until the model is validated on hand labels, a disagreement is only shipped once it is spot-checked
(--trust-disagree lifts that).

Spot checks (spot_checks.csv, committed - human truth beats the model):
  way_id,verdict,note        verdict = ok | wrong | <number of lanes>
  ok -> confidence 1.0, wrong -> dropped, number -> that value with confidence 1.0
"""
import argparse
import csv
import datetime as dt
import json
from pathlib import Path

from common import AREAS, sub

HERE = Path(__file__).resolve().parent
ASSET = HERE.parent.parent / "app" / "src" / "main" / "assets" / "aerial_lanes.json"


def load_checks():
    p = HERE / "spot_checks.csv"
    if not p.exists():
        return {}
    with p.open(encoding="utf-8") as f:
        return {r["way_id"].strip(): r["verdict"].strip().lower() for r in csv.DictReader(f) if r.get("way_id")}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--area", default="test", choices=sorted(AREAS))
    ap.add_argument("--min-conf", type=float, default=0.7)
    ap.add_argument("--trust-disagree", action="store_true",
                    help="also ship unchecked disagreements with OSM (only once the model is validated on manual labels)")
    a = ap.parse_args()
    meas = json.loads((sub(".") / f"measurements_{a.area}.json").read_text(encoding="utf-8"))
    checks = load_checks()
    model_info = sub("model") / "marking_unet.json"
    iou = json.loads(model_info.read_text())["best_val_iou"] if model_info.exists() else None

    ways, gaps, disagree, checked = [], 0, 0, 0
    for wid, m in meas.items():
        lanes, conf = m["lanes"], m["conf"]
        v = checks.get(wid)
        if v == "wrong":
            continue
        if v == "ok":
            conf = 1.0
        elif v and v.isdigit():
            lanes, conf = int(v), 1.0
        if lanes is None or conf < a.min_conf or lanes < 1 or lanes > 8:
            continue
        if m["osm"] == lanes:
            continue
        if m["osm"] is not None and v is None and not a.trust_disagree:
            continue                                  # overruling OSM needs a human spot check for now
        checked += v is not None
        if m["osm"] is None:
            gaps += 1
        else:
            disagree += 1
        ways.append([int(wid), lanes, round(conf * 100)])
    ways.sort()
    doc = {
        "v": 1,
        "source": "LGLN DOP20 (c) LGLN 2026, CC BY 4.0; road markings detected by LaneWise",
        "area": a.area,
        "generated": dt.date.today().isoformat(),
        "model_val_iou": iou,
        "fields": ["osm_way_id", "lanes", "confidence_pct"],
        "ways": ways,
    }
    ASSET.parent.mkdir(parents=True, exist_ok=True)
    ASSET.write_text(json.dumps(doc, separators=(",", ":")), encoding="utf-8")
    print(f"{len(ways)} ways ({gaps} fill OSM gaps, {disagree} disagree with OSM, {checked} spot-checked) -> {ASSET}")


if __name__ == "__main__":
    main()
