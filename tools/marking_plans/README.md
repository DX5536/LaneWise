# Marking plans → draft lane overrides

Reads a Neumarkierungsplan of the Landeshauptstadt Hannover (vector PDF from AutoCAD Map 3D, attachment of a council
paper) and writes **draft** `lane_overrides.json` entries (schema version 2) for a person to check. Rules and the
mapping table: `docs/MARKING_PLANS.md`. A plan is not proof the road looks like this: every draft needs a dated photo
(`built_confirmed`) before it may be shipped, and drafts carry `"draft": true`, which the app always rejects.

```
python -m venv .venv && .venv/Scripts/python.exe -m pip install -r requirements.txt
.venv/Scripts/python.exe -I marking_plans.py inspect work/plans/15-2330-2024_Anlage2.pdf
.venv/Scripts/python.exe -I marking_plans.py extract work/plans/15-2330-2024_Anlage2.pdf
.venv/Scripts/python.exe -I marking_plans.py georef  work/plans/15-2330-2024_Anlage2.pdf [control_points.json]
.venv/Scripts/python.exe -I marking_plans.py draft   work/plans/15-2330-2024_Anlage2.pdf
.venv/Scripts/python.exe -I marking_plans.py review  work/plans/15-2330-2024_Anlage2.pdf
```
Plans go into `work/plans/` and everything is written to `work/<pdf name>/` (git-ignored: the plans and the city base
map are never committed).

| Step | What it does | Output |
| --- | --- | --- |
| `inspect` | title block (Drucksache, Anlage, Blatt, date, street, section), legend entries (Bestand / Planung / Demarkierung, colour, dashed, B 0,25 / S 0,12), scale from the dimension labels (median of "3,25"-style labels next to their dimension lines; labels more than 3 % off are listed) | `plan.json`, `legend.json`, `dimensions.json`, `overview.png` (100-pt grid for control points) |
| `extract` | markings by legend colour: line stripes chained into solid / dashed / mixed lines (AutoCAD explodes dashes into separate polygons), stop lines and arrows from a 20 px/m raster (an opening of 0.35 m removes every line; stop lines and arrow heads stay), arrow direction from the shaft, bent arrows flagged | `features.json`, `features.png` (blue existing, green planned, red removed, orange stop lines, purple arrows) |
| `georef` | page → ETRS89 / UTM 32. Without control points: house numbers on the plan ↔ OSM addresses (coarse, RANSAC with the scale fixed), then plan base-map line ends ↔ OSM building corners (ICP, 1.5 → 0.6 m). Accepted only with RMS ≤ 0.5 m and the scale within 3 % of the dimension labels. With `control_points.json` (`[{"pdf": [x, y], "lat": …, "lon": …}, …]`, ≥ 4 points) the same check | `georef.json` |
| `draft` | stop lines grouped per approach (parallel, ≤ 8 m apart) → the OSM way crossing them (perpendicular, ≤ 15 m) → draft entry: `osm_way`, direction, `stop_line:lanes` (m before the way end), solid lines touching the stop line as `change_spans`, `source_plan`, `osm_was`. Everything a person must decide is `CHECK: …` | `drafts.json`, `markings.geojson` (open in JOSM next to OSM) |
| `review` | per draft: plan crop next to the DOP20 crop of the same 60 m (north up) + Mapillary / Panoramax / OSM links | `review.html` |

Sample (Drucksache 15-2330-2024, Anlage 2, Hannoversche Straße): legend 10 entries, scale 1:254 from 14 dimension
labels, georef from 139 building corners with RMS 0.44 m, 3 drafts.

## Known limits (why every value is a CHECK)
- Arrow *meaning* is never decided by the tool: turn arrow (Z 297) and merge arrow (Z 297.1) look alike in plan view.
  `turn:lanes` is always typed in by a person.
- Zebra and cycle crossing bars in grey (Bestand) can be taken for stop lines (sample: the Am Wasserturm draft).
- Direction: the nearer way end is used; when the keep-right side of the stop line says otherwise the direction is a
  `CHECK` (on the sample all three approaches disagree - OSM centre lines are not always mid-road; not yet understood).
- Legends differ per sheet: always look at `legend.json` (wrong colour → wrong role). Shared colours (black for lane
  lines, cycle strips and crossings) are split by width and dash length; if still unclear the line is `ambiguous`.
- Only the first page of a PDF is read; plans with several sheets per file need splitting.
