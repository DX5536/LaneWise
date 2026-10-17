# Lane overrides (`lane_overrides.json`)

Hand-checked lane corrections for junctions where OSM is wrong, outdated or where OSRM drops the lanes — applied
right away in the app, until the fix is in OSM. Code: `osm/LaneOverrides.kt`, tests `lane/LaneOverridesTest.kt`.

**Precedence:** lane overrides > OSM `turn:lanes` (OSRM / corridor) > aerial lane counts (`aerial_lanes.json`) > heuristics.

## Where the files are
- `app/src/main/assets/lane_overrides.json` — shipped with the app (reviewed, committed).
- `Android/data/de.lanewise.app.debug/files/lane_overrides.json` — on the phone, next to `lane_reports.geojson`; an
  entry with the same `id` replaces the shipped one. Patch a junction on the spot:
  `adb push lane_overrides.json /sdcard/Android/data/de.lanewise.app.debug/files/` (Git Bash: `MSYS_NO_PATHCONV=1`),
  then restart the app. Rejected entries are logged: `adb logcat -s LaneWise`.

## Format
A GeoJSON FeatureCollection like `lane_reports.geojson`, so both open side by side in JOSM / iD. A report point becomes
a patch by adding the OSM id, the checked values and the evidence. Property names are OSM tag names, so the fix can be
copied into OSM unchanged. The point geometry only helps to find the spot; matching is by OSM id.

```json
{"type": "FeatureCollection", "lanewise_overrides": 1, "features": [
  {"type": "Feature", "geometry": {"type": "Point", "coordinates": [9.7500, 52.3800]},
   "properties": {
     "id": "example-left-turn",
     "osm_way": 123456,
     "direction": "forward",
     "turn:lanes": "left|through|through|right",
     "osm_was": {"turn:lanes": "left|left|through|right"},
     "source": "DOP20 2025 + own drive 2026-10-12",
     "checked": "2026-10-15",
     "note": "re-marked, one left-turn lane"
   }},
  {"type": "Feature", "geometry": null,
   "properties": {"id": "example-junction", "osm_node": 456789, "from_way": 123456,
                  "turn:lanes": "reverse;left|left|right|right|right", "source": "own drive 2026-10-12"}},
  {"type": "Feature", "geometry": null,
   "properties": {"id": "example-connectivity", "osm_node": 456789, "from_way": 123456, "to_way": 234567,
                  "connectivity": "2:1|3:2", "source": "DOP20 2025 + own drive 2026-10-12"}},
  {"type": "Feature", "geometry": null,
   "properties": {"id": "example-unknown", "osm_way": 654321, "direction": "backward", "lanes_unknown": true,
                  "source": "OSM arrows contradict DOP20, arrows not readable"}}
]}
```

| Property | Meaning |
| --- | --- |
| `id` | unique name (required) |
| `osm_way` | way patch: applies to the OSM way's tags before anything reads them (lanes, arrows at the way's end, solid lines) |
| `osm_node` (+ `from_way`) | junction patch: the lanes arriving at this node; with `from_way` only when the route arrives on that way (if that way is not in the corridor data, the patch is skipped — it cannot be checked) |
| `direction` | `forward` / `backward` along the OSM way. May be left out only on one-way ways (= travel direction); a two-way way without it is not patched |
| `turn:lanes` | arrows in travel direction, left → right, OSM syntax (unknown values reject the entry) |
| `lanes` (`effective_lanes`) | car lanes in travel direction; asymmetric roads get one entry per direction |
| `change:lanes` | solid lines, OSM syntax (`yes`, `no`, `not_left`, `not_right`) |
| `connectivity` (+ `osm_node`, `from_way`, `to_way`) | which lane continues into which lane after the junction, OSM `type=connectivity` syntax (`1:1\|2:2,3`, lanes from 1 on the left, `(n)` = optional); lanes not listed do not lead onto `to_way`. Only on a junction patch with all three ids and nothing else (patch `turn:lanes` in its own entry). Wins over OSM relations at the same junction |
| `lanes_unknown` | OSM is wrong here but the truth is not known: no lane advice ("No lane data – follow the road markings") |
| `osm_was` | OSM tags when the patch was written (optional) |
| `source` | evidence (required): DOP20 junction audit, own drive, photo |
| `checked`, `note` | free text |

## Safety rules
- An entry that does not validate (no source, typo in an arrow, lane counts that do not match, both or neither id,
  `lanes_unknown` together with `turn:lanes`, a second patch for the same way + direction) is dropped as a whole.
- A patch replaces every lane site within 30 m of its junction (OSRM's or one added from OSM `turn:lanes`); OSRM's
  "valid" flags are dropped, the route's turn there is kept. A double-node junction further apart needs a patch per node.
- If none of the patched lanes can make the route's turn there (a patch for the other direction, or out of date), the
  junction gets **no** lanes, never a guess.
- At a `lanes_unknown` junction (and the two cases above) the HUD shows "No lane data – follow the road markings"
  from 400 m before it, and nothing after it is planned until it is passed (a plan across it could lead into a lane that
  turns off or ends there).
- `osm_was`: when OSM now matches neither `osm_was` nor the patch, OSM changed after the check and nobody has checked
  the new state → lanes unknown there until the patch is re-checked. When OSM now equals the patch, the OSM fix is in;
  the patch can be deleted.
- A `connectivity` that contradicts the junction's arrows (see `docs/LANE_ALGORITHM.md`, "Safety check") or its lane
  count leaves the junction without lanes (lanes unknown), like a patch that cannot make the turn.
- Patches are baked into the offline graph when a region is downloaded — re-download after changing them.

## AMAP-based patches (last resort)
Only where OSM, street-level photos and DOP20 leave a junction unclear (`docs/DATA_SOURCES.md`): the lanes read from
an AMAP screenshot are written as text into `lane_overrides.json` with `"source": "AMAP screenshot <date> (unconfirmed)"`.
Never into OSM, and the screenshots themselves are never committed. Any clearer source later replaces the patch.

## Workflow
1. Long-press the lane bar where the lanes are wrong → `lane_reports.geojson`.
2. Check the junction: junction audit on DOP20 (lane lines, counts), own drive / photo (arrows).
3. Add the entry (way / node id from JOSM or iD), `adb push`, re-drive or demo the route.
4. Fix OSM with the same values; once OSM has them, delete the entry (or set `osm_was` to notice later edits).
