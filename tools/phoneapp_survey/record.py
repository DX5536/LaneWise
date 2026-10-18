"""Append one text reading to tools/streetview/work/amap_check.json (no screenshots, text only).
usage: python record.py JUNCTION "approach" "arrows" "lanes this|opposite" "osm_lanes" verdict "note"
"""
import json, sys, datetime, pathlib
p = pathlib.Path(__file__).resolve().parents[1] / "streetview" / "work" / "amap_check.json"
rows = json.loads(p.read_text(encoding="utf-8"))
j, appr, arrows, lanes, osm, verdict, note = sys.argv[1:8]
rows.append({"junction": int(j), "approach": appr, "amap": arrows, "amap_lanes": lanes, "osm_lanes": osm,
             "verdict": verdict, "note": note, "date": datetime.date.today().isoformat()})
p.write_text(json.dumps(rows, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
print(len(rows), "rows")
