# Marking plans (Neumarkierungspläne) → lane data

How official marking plans of the Landeshauptstadt Hannover (Fachbereich Tiefbau, Straßenplanung 66.2x) become
`lane_overrides.json` entries and, through them, `LaneSite` / `ChangeSpan` data. Status (2026-10-18): schema version 2
(section 5) is implemented in `osm/LaneOverrides.kt` (tests in `LaneOverridesTest`), the tool (section 6) in
`tools/marking_plans/`. No plan-based entry is shipped yet.

## 1. What a plan is (and is not)
- An engineering drawing (AutoCAD Map 3D plot, vector PDF) on the city base map "Stadtkarte 1:1000"
  (© Landeshauptstadt Hannover – Geoinformation). Found as attachments (`Anlage`) of council / district council
  papers (`Drucksache`) in the city's council information system (`e-government.hannover-stadt.de`).
- It shows **existing** markings, markings to **remove** and **planned** markings, with lane widths in metres. It is
  the most precise source for geometry: per-lane widths, exact stop-line positions (also staggered per lane), where a
  solid divider starts, pocket lengths, cycle lanes.
- It is **a plan, not an as-built record**: printed "Änderungen vorbehalten!", attached to a paper the council may
  change, reject or postpone, and built months or years later (or never). Using a plan before it is painted would put
  a lane hint on the road that does not exist yet — the worst kind of error for LaneWise.
- Bebauungspläne (land-use plans, the second link the owner sent) only define road areas (`Verkehrsfläche`), not
  markings or lanes: not useful for lane data.

**Rule:** a plan is the highest-confidence source for *geometry* **once it is confirmed built**. Confirmation = imagery
dated after the plan showing the new layout: a Mapillary / Panoramax photo, a DOP20 flight, or an own drive. Until then
the plan is only a "watch" item (section 4, step 2). Precedence once confirmed:

`marking plan (built)` = `lane_overrides.json` (the plan is the `source`) > OSM `turn:lanes` > `aerial_lanes.json` > heuristics.

The plan never becomes its own data path in the app: everything goes through `lane_overrides.json`, so the existing
validation, `osm_was` drift check and "lanes unknown" fallback stay the single safety net.

## 2. Reading the legend
**Legends differ per plan** — colours and line styles are chosen per drawing, so the legend of each sheet is read first
and nothing is hard-coded. The final state is `(Bestand − Demarkierung) ∪ Planung`. Example, Drucksache 15-2330-2024,
Anlage 2, Blatt 2:

| Legend entry | Drawn as (this sheet) | Meaning |
| --- | --- | --- |
| Fahrbahnmarkierung Bestand, Weißmarkierung S 0,12 | grey line | existing marking, stays |
| Radfahrerfurt Bestand, B 0,25 | grey broad dashes | existing cycle crossing, stays |
| Rotmarkierung Bestand | pink area | existing red cycle surface |
| Demarkierung Schutzstreifen B 0,25 / Mittellinie S 0,12 | magenta | **removed** |
| Fahrbahnmarkierung (Planung) S 0,12 | black solid | new solid line |
| Schutzstreifen Weißmarkierung S 0,12 Strich/Lücke | black dashed | new advisory cycle lane line |
| Radfahrerfurt (Planung) B 0,25 | black broad dashes | new cycle crossing |
| Rotmarkierung (Planung) | red area | new red cycle surface |
| Piktogramm (bicycle), Leitbake | symbols | cycle pictogram; guidance beacon (Z 605) |

Line widths follow the German marking guideline (RMS): **S** = narrow (0,12 m), **B** = broad (0,25 m); transverse
stop lines are thick bars (~0,50 m).

## 3. Feature mapping
| Plan element (StVO sign) | OSM tag / override property | LaneWise class |
| --- | --- | --- |
| Leitlinie, dashed S 0,12 (Z 340) between car lanes | `change:lanes` = `yes` | `ChangeSpan.perms` both bits set |
| Fahrstreifenbegrenzung, solid S 0,12 (Z 295) between car lanes | `change:lanes` = `no` on both sides | `ChangeSpan` clears LEFT/RIGHT across that line |
| Solid + dashed side by side (Z 296) | `not_left` / `not_right` | `ChangeSpan` one bit (`DASHED_SOLID`, `SOLID_DASHED`) |
| Solid divider only for the last *n* m before the stop line | proposed `change_spans` (section 5) | `ChangeSpan(from, to, perms)` with real distances |
| Haltlinie (Z 294), per lane, possibly staggered | proposed `stop_line:lanes` (m before the junction node) | `LaneRibbonBuilder.taper(cutAt)` — pockets end there |
| Turn arrows (Z 297) per lane | `turn:lanes` | `LaneSite.lanes` bitmask |
| Merge / lane-ends arrow (Z 297.1) | lane count change (`lanes`), **not** a turn arrow | pocket / lane end, not `LaneSite.lanes` |
| Turn pocket opening (start of the lane's taper) | proposed `pocket_start_m`; in OSM: split the way where the lane begins | `LaneSite.fullLength = false`, pocket length instead of the road-kind default |
| Lane widths (dimension lines, e.g. 3,25 / 3,00) | `width:lanes` | not used for planning; ribbon geometry later (optional) |
| Lane lines through the junction / which lane leads where | `connectivity` (existing) | `LaneSite.connectivity` |
| Schutzstreifen (advisory cycle lane, S 0,12 Strich/Lücke) | `cycleway:right=lane` + `cycleway:right:lane=advisory` | not a car lane (`carLanes`), never counted |
| Radfahrstreifen (solid B 0,25 + Z 237) | `cycleway:right=lane` + `:lane=exclusive` | not a car lane |
| Bus lane (BUS pictogram, Z 245) | `bus:lanes` / `access:lanes` | `LaneSite.carLanes = false` |
| Kernfahrbahn (no centre line, cycle strips both sides) | no `lanes` per direction marking; check OSM wiki for the current convention | 1 lane per direction, no lane advice |
| Parking strip, Randmarkierung | `parking:right=lane` | edge, not a lane |
| Radfahrerfurt, Sperrfläche (Z 298), Zufahrten | — | ignored for lanes (furt marks the junction mouth) |

## 4. Workflow (per plan)
1. **Collect.** Save the PDF in `tools/marking_plans/work/` (git-ignored; never commit plans) and note Drucksache
   number, Anlage, Blatt, date, street and section ("von … bis …").
2. **Built?** Find imagery dated after the plan (Mapillary / Panoramax via `tools/streetview`, DOP20 flight date, own
   drive). Not built yet → add it to `tools/marking_plans/watch.json` (street, plan date, bbox) and stop; re-check
   later. A watch item never changes the app. *(Option to decide: a decided plan whose build date is near could set
   `lanes_unknown` on the affected ways, since OSM is about to be outdated.)*
3. **Legend.** Record per sheet which colour / line style means Bestand, Demarkierung and Planung (section 2).
4. **Georeference.** The PDF has page coordinates only. Pick ≥ 4 well-spread control points (building corners, kerb
   corners) and match them to OSM / DOP20 in ETRS89 / UTM 32 (EPSG:25832); fit a similarity transform (shift, rotation,
   one scale). Accept only with RMS residual ≤ 0.5 m; check the scale against the dimension labels (a "5,00" label
   must measure 5 m).
5. **Extract.** From the vector paths, by legend style: lane dividers (solid vs dashed — AutoCAD exploded the
   linetypes into separate segments, so dashed = a run of short collinear segments with gaps), stop lines, arrows
   (filled polygons), dimension labels (text with position). Project each onto the OSM way(s) of the street: distance
   before the junction node, lateral order left → right in driving direction.
6. **Draft.** Write draft override features (`"draft": true`, `source_plan` filled, the OSM ids and `osm_was`).
   Anything the tool cannot classify (an arrow shape it does not know, a lane line it cannot place) becomes a note,
   never a guessed value; if the turn stays unclear the draft is `lanes_unknown`.
7. **Review (person).** Overlay the georeferenced plan on the DOP20 crop (as in `junction_audit.py`) next to the
   latest street photo; a person checks every lane, arrow and stop line and confirms "built as planned". Differences
   to the plan: the photo wins (it is newer); note it.
8. **Ship.** Copy the reviewed entries into `app/src/main/assets/lane_overrides.json` (drop `"draft"`), run the unit
   tests (`shippedOverridesAreValid`) and a junction audit for a route through it. Fix OSM too (the owner does this),
   then the override can go once OSM matches.

## 5. Schema version 2 (`lanewise_overrides: 2`, implemented)
Existing keys stay; version-1 files keep working. New optional properties:

```json
{"id": "hannoversche-str-wb-am-seelberg", "osm_node": 0, "from_way": 0, "direction": "forward",
 "turn:lanes": "through|left",
 "stop_line:lanes": "8.5|2.0",
 "change_spans": [{"from_stop_m": 0, "to_stop_m": 10, "change:lanes": "not_right|not_left"}],
 "pocket_start_m": 45,
 "width:lanes": "3.25|3.00",
 "source": "Neumarkierungsplan LHH FB Tiefbau 66.22 + Mapillary 2026-05",
 "source_plan": {"drucksache": "15-2330-2024", "anlage": 2, "blatt": 2, "date": "2024-10"},
 "built_confirmed": "Mapillary photo 2026-05-12",
 "checked": "2026-10-18"}
```
(values illustrative, not a reading of the sample plan)

| Property | Meaning | Applied as |
| --- | --- | --- |
| `stop_line:lanes` | stop line per lane, metres **before the junction node** (the node sits in the middle of the crossing) | pocket / lane end (`taper cutAt`) per lane instead of one point |
| `change_spans` | solid lines over part of the approach, measured back from the stop line; OSM `change:lanes` syntax | `ChangeSpan(from, to, perms)` at real route distances (OSM can only do this by splitting the way) |
| `pocket_start_m` | where a turn lane begins (start of the taper), from the stop line | pocket length; replaces the road-kind default |
| `width:lanes` | lane widths (OSM syntax) | stored; optional for the ribbon |
| `source_plan` | Drucksache, Anlage, Blatt, date (and URL) | evidence; required when the plan is the source |
| `built_confirmed` | dated evidence that the layout is painted | **required** together with `source_plan`, else the entry is rejected |

Validation (same "drop the whole entry" rule as today): stop lines 0–60 m, spans and `pocket_start_m` 0–1000 m, spans
must not overlap; lane counts of `stop_line:lanes`, `width:lanes`, `change_spans` match `turn:lanes`; widths 2–6 m;
`source_plan` without `built_confirmed` is rejected; a stop line further back than `pocket_start_m` is rejected;
`"draft": true` is always rejected; version-2 keys in a version-1 file are rejected; the geometry keys need `turn:lanes`
(not on `lanes_unknown` or connectivity entries).

How the app uses it: the junction distance of a way patch is the way's end (node patch: the node). The front stop line
is the one nearest to it; `change_spans` become `ChangeSpan`s at route distances and **replace** OSM's change spans
where they lie (`ChangeSpan.overlay`, so exactly one span applies at any point); `pocket_start_m` (+ front stop line)
becomes `LaneSite.pocketLength`, which the lane ribbon uses instead of the road-kind default. `stop_line:lanes` per lane
and `width:lanes` are stored and validated but not drawn yet. Offline routing (`OfflineGraphBuilder`) bakes only the
version-1 tags; spans and pocket starts work with online routes.

## 6. Tool (`tools/marking_plans/`, README there)
Python with PyMuPDF + Pillow in its own git-ignored `.venv`: `inspect` (title block, legend, scale from the dimension
labels) → `extract` (markings by legend role; stop lines and arrows from a raster) → `georef` (automatic from house
numbers + OSM building corners, or control points; RMS ≤ 0.5 m) → `draft` (draft entries, every judgement a `CHECK`)
→ `review` (plan crop | DOP20 crop | photo links). Same rule as `tools/streetview`: drafts are evidence for a person,
never imported automatically. Sample: scale 1:254, georef RMS 0.44 m from 139 building corners, 3 drafts.

## 7. Safety rules
- Not built (no dated imagery after the plan) → nothing in the app.
- A lane line, arrow or stop line the tool cannot classify with certainty → note, or `lanes_unknown`; never a default.
- Photo newer than the plan and different → the photo wins.
- The plan PDFs and the base map stay out of git (city copyright); only derived values (lanes, arrows, distances) go
  into `lane_overrides.json`, with the plan cited in `source`.

## 8. Sample: Drucksache 15-2330-2024, Anlage 2, Blatt 2 (Oct 2024)
Hannoversche Straße, Am Sünderkamp – Im Bruchkampe, "Optimierung Schutzstreifen":
- Along the street the centre line and the old cycle strips are removed; new advisory cycle strips 1,35 m both sides
  with red surface, core carriageway 5,00 m (Kernfahrbahn) → one lane per direction, nothing for lane advice.
  Parking loss of 14 spaces (house numbers 71–61) — not lane data.
- Westbound approach to Im Bruchkampe / Am Seelberg: two lanes (3,25 m north, 3,00 m south), **staggered stop
  lines**, a solid divider for the last 10,00 m, dashed before that. North lane: straight arrow. South lane: a bent
  arrow I cannot classify with confidence from the drawing (turn arrow Z 297 or merge arrow Z 297.1) — exactly the
  case that must go to a street photo before any patch.
- Not yet checked whether it is built. Not added to `lane_overrides.json`.
