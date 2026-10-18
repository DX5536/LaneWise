# Data sources (Germany, focus Region Hannover)

| Source | Used for | Key? | Notes |
| --- | --- | --- | --- |
| **OpenStreetMap** via FOSSGIS OSRM `routing.openstreetmap.de/routed-car` | default routing + lanes per intersection | no | Fair-use public server hosted in Germany. Self-host OSRM for heavy use (URL configurable). |
| **Overpass API** `overpass-api.de` | speed limits along route; offline graph download | no | Fair use; downloads are one-off per region. |
| **OpenFreeMap** `tiles.openfreemap.org` | vector map tiles + fonts | no | OpenMapTiles schema; LaneWise ships its own styles (`tools/gen_map_styles.py`). |
| **LGLN DOP20** `opendata.lgln.niedersachsen.de/doorman/noauth/dop_wms` (layer `ni_dop20`) | official 20 cm aerial imagery of Niedersachsen (map layer toggle) | no | CC BY 4.0, attribution "LGLN (2026)". Bing imagery is licensed for OSM editing only, so it is not used. |
| **Aerial lanes** `app/src/main/assets/aerial_lanes.json` | lane counts per OSM way measured from DOP20 road markings (offline ML pipeline `tools/aerial_lanes/`) | no | Built on the PC, shipped with the app. Used only where OSM has no lane data or disagrees (`osm/AerialLanes.kt`); never overrides `turn:lanes`. Setting "Spuren aus Luftbildern". |
| **Lane overrides** `app/src/main/assets/lane_overrides.json` (+ the same file on the phone) | hand-checked lane patches per OSM way / junction node: `turn:lanes`, lane count per direction, `change:lanes`, "lanes unknown" | no | Highest precedence (over OSM and aerial lanes). Each entry needs evidence (`source`); the fix also goes into OSM. See `docs/LANE_OVERRIDES.md`. |
| **Marking plans** (Neumarkierungspläne, LH Hannover FB Tiefbau) | exact lane geometry: widths, solid-line spans, staggered stop lines, pockets, cycle strips | no | Plans, not as-built: used only once imagery dated after the plan shows them built, and only through `lane_overrides.json` (plan cited in `source`). PDFs stay out of git. See `docs/MARKING_PLANS.md`. |
| **Photon** `photon.komoot.io` | search | no | Restricted to the Germany bbox, biased to current position; `osm_key`/`osm_value` give the result icons. |
| **Overpass** + OSRM `table` | smart stops (places along the route, ranked by added driving time) | no | One Overpass query per search (corridor along the route), one OSRM table request for the best 15. |
| **Die Autobahn GmbH des Bundes** `verkehr.autobahn.de/o/autobahn` | roadworks, warnings, closures on A2, A7, A37, A352, A39 + motorways on the route; closed lanes from roadworks `impact.symbols`; optional icons: rest/parking areas, EV chargers, webcams | no | Official open data. Closures add +30 min to routes passing them. |
| **TomTom** Routing API + Traffic Incidents v5 | traffic-aware routing with lane sections and speed-limit sections; incidents | yes | Free tier ~2,500 req/day. Settings → Verkehrsdaten or `TOMTOM_API_KEY`. |
| **HERE** Traffic API v7 | incidents | yes | Settings or `HERE_API_KEY`. |
| **Mobilithek** (NAP Deutschland) | DATEX II traffic messages, e.g. VMZ Niedersachsen | subscription + client cert | Subscribe on mobilithek.info, then enter the feed URL and import the `.p12` certificate in Settings. `traffic/MobilithekClient.kt`: DATEX II v2 / v3 `situationRecord`s (SAX, SOAP envelopes and gzip files OK), validity (`active` / `suspended` / time spec); per-lane closures only from unambiguous lanes (`leftLane`, `rightLane`, `middleLane` on 3 lanes, main carriageway, known lane count) → `LaneClosure`; numbered lanes (`lane2`, v3 `laneNumber`) or a count only → "lanes unknown" along the roadworks (no lane advice). |

## Marking plans (Hannover)
Official Neumarkierungspläne (attachments of council papers) are the most precise geometry source for Hannover
corridors, but they are plans: a plan counts only once a dated photo / DOP20 / own drive shows it built, and then it
enters the app as a `lane_overrides.json` entry like any other evidence. Workflow, legend reading, georeferencing and
the proposed schema extensions: `docs/MARKING_PLANS.md`.

## Street-level imagery (visual junction audit)
Hierarchy for checking lane markings, turn arrows and signs where OSM is missing or ambiguous (asymmetric lane counts,
turns without `turn:lanes`): **1. Mapillary** (primary, API v4 with a free client token, CC BY-SA 4.0) → **2. Panoramax**
(federated European open imagery, STAC API without key) and **KartaView** (open-source platform, viewer link) as
secondary references where Mapillary is sparse → together with **`lane_overrides.json`** (immediate app fixes) and
**LGLN DOP20** + `aerial_lanes.json` (geometry, lane counts). Tool: `tools/streetview/streetview_audit.py` (README there),
run after the junction audit. The photos are evidence for a person (fix OSM, or write an override with the photo as
`source`), never imported into the app; machine detections are hints only; check the photo date against re-markings.

## AMAP / Petal Maps: reference screenshots; last resort for the phone's own patches
Their German lane data is mostly correct, but some lanes are outdated (owner's observation) — and some are wrong in
exactly the same way as OSM (Herschelstraße / Celler Straße), so agreeing with them proves nothing. Rules (owner's
decision, 2026-10-15):
- **Allowed:** opening AMAP / Petal on the owner's phone and taking screenshots of their lane view at a junction, like
  the owner does by hand, to spot where LaneWise disagrees.
- **Not allowed:** copying their lanes, arrows or geometry into LaneWise or into OSM (OSM forbids imports from
  proprietary maps), reverse-engineering their app or data, or automated bulk capture.
- A disagreement is a pointer: the junction is then checked against the official aerial photo (DOP20, junction audit)
  and reality, and fixed in our logic or by the owner in OSM.
- **Last resort (owner's decision, 2026-10-17):** where OSM, street-level photos and DOP20 leave a junction unclear,
  the lanes AMAP shows at that junction (big junctions especially) may be taken as they are, written down as words
  (`turn:lanes` notation) in `lane_overrides.json` with `"source": "AMAP screenshot <date> (unconfirmed)"` - **never
  into OSM** (licence). AMAP screenshots themselves are never committed or uploaded (they stay in the local scratch
  folder); only the lanes read from them, as text (owner's decision, 2026-10-17).
  Every other source wins over AMAP: the owner's own knowledge, a recent street-level photo, DOP20 lane counts
  (Celler Straße at Herschelstraße: AMAP showed 3 lanes, there are 2).
- How to read AMAP (owner, 2026-10-18): search the place, leave the result with the **upper-left back button** (not
  the system back), then zoom in by hand; arrows appear only at the 5 m scale bar or closer, sometimes only at 2 m / 1 m
  (a street label can hide an arrow - zoom one step further). "No arrows" counts only after checking at maximum zoom.
- **Google Maps / Google satellite (and viewers that show Google tiles, e.g. vntg-point.com): not used, not even as a
  screenshot reference.** Google's terms forbid deriving data from their maps and imagery, and every reading would end
  up in `lane_overrides.json`. The owner may look at it themselves; nothing read from it goes into LaneWise.
- The most up-to-date reference is the aerial photo (LGLN DOP20, flown 2025): lane lines and counts are clear; painted
  arrow directions are too small at 20 cm and come from OSM `turn:lanes`.

## Attribution
Shown in Settings and by MapLibre's attribution button: © OpenStreetMap contributors (ODbL),
OpenFreeMap, FOSSGIS, Die Autobahn GmbH des Bundes, TomTom, HERE, Mobilithek.
