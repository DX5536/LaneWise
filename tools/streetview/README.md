# Street-level audit (`tools/streetview`)

Open, crowd-sourced street-level photos of every junction approach of a route, next to the app's lanes and the DOP20
aerial crop - to read the painted arrows and signs where OSM `turn:lanes` is missing or doubtful (the aerial photo
shows lane lines and counts, but arrows are too small at 20 cm).

| Order | Source | Access | Licence | Used for |
| --- | --- | --- | --- | --- |
| 1 (primary) | **Mapillary** | API v4 `graph.mapillary.com/images?bbox=…` (+ `map_features` for machine detections), free client token in `MAPILLARY_TOKEN` | CC BY-SA 4.0 | photos along the approach, newest first; detected arrows / lane signs as hints |
| 2 | **Panoramax** | STAC API `api.panoramax.xyz/api/search?bbox=…` (federated, all instances), no key | CC BY-SA 4.0 (per photo) | fallback where Mapillary is sparse |
| 3 | **KartaView** | viewer link `kartaview.org/map/@lat,lon,18z` (check by hand; its API changed several times, not queried) | CC BY-SA 4.0 | manual cross-check |

## Run
1. Junction audit for a route (see `CLAUDE.md`): `LANEWISE_LIVE=1 LANEWISE_JUNCTIONS=1 LANEWISE_FROM=… LANEWISE_TO=… gradlew testDebugUnitTest --tests '*JunctionAuditLiveTest*'`
   (optionally `tools/aerial_lanes/junction_audit.py` for the DOP20 crops, which the report links).
2. `MAPILLARY_TOKEN=MLY|… python -I tools/streetview/streetview_audit.py` (standard library only; without the token only
   Panoramax is queried). Single spot: `--point 52.36881,9.74322,197`.
3. Open `tools/streetview/work/streetview.html` (git-ignored): per junction the app's lanes, the DOP20 crop, up to 4
   photos per source (taken ≤ 45 m around a point 30 m before the stop line, looking the driving direction ± 50°, or
   360° panoramas), Mapillary detections, a KartaView link.

## Rules (safety first, licences)
- Photos are **evidence for a person, never data for the app**. Nothing is imported automatically.
- A finding goes into OSM (Mapillary / Panoramax photos are allowed sources for OSM edits) and, until OSM is fixed,
  into `app/src/main/assets/lane_overrides.json` with the photo as `source`, e.g. `"Mapillary 123456789, 2025-06-02"`.
- Check the **date**: a photo older than a re-marking shows the old lanes. When photo, aerial and OSM disagree and the
  newest evidence is not clear, use `lanes_unknown` rather than guessing.
- Machine detections (Mapillary `map_features`, Panoramax `annotations`) are hints only.
- Photos are linked, not copied; keep the attribution (contributors, CC BY-SA 4.0) when sharing screenshots.
- Never put the owner's real addresses into a query file or report that is committed.

First check (2026-10-17, Schiffgraben → Hildesheimer Straße): Panoramax had July 2026 photos at 5 of 7 junctions, but
from a bike / the sidewalk - the car lanes' arrows at Aegidientorplatz were not visible. Mapillary not tried yet (no
token).
