# Data sources (Germany, focus Region Hannover)

| Source | Used for | Key? | Notes |
| --- | --- | --- | --- |
| **OpenStreetMap** via FOSSGIS OSRM `routing.openstreetmap.de/routed-car` | default routing + lanes per intersection | no | Fair-use public server hosted in Germany. Self-host OSRM for heavy use (URL configurable). |
| **Overpass API** `overpass-api.de` | speed limits along route; offline graph download | no | Fair use; downloads are one-off per region. |
| **OpenFreeMap** `tiles.openfreemap.org` | vector map tiles + fonts | no | OpenMapTiles schema; LaneWise ships its own styles (`tools/gen_map_styles.py`). |
| **LGLN DOP20** `opendata.lgln.niedersachsen.de/doorman/noauth/dop_wms` (layer `ni_dop20`) | official 20 cm aerial imagery of Niedersachsen (map layer toggle) | no | CC BY 4.0, attribution "LGLN (2026)". Bing imagery is licensed for OSM editing only, so it is not used. |
| **Aerial lanes** `app/src/main/assets/aerial_lanes.json` | lane counts per OSM way measured from DOP20 road markings (offline ML pipeline `tools/aerial_lanes/`) | no | Built on the PC, shipped with the app. Used only where OSM has no lane data or disagrees (`osm/AerialLanes.kt`); never overrides `turn:lanes`. Setting "Spuren aus Luftbildern". |
| **Photon** `photon.komoot.io` | search | no | Restricted to the Germany bbox, biased to current position; `osm_key`/`osm_value` give the result icons. |
| **Overpass** + OSRM `table` | smart stops (places along the route, ranked by added driving time) | no | One Overpass query per search (corridor along the route), one OSRM table request for the best 15. |
| **Die Autobahn GmbH des Bundes** `verkehr.autobahn.de/o/autobahn` | roadworks, warnings, closures on A2, A7, A37, A352, A39 + motorways on the route; closed lanes from roadworks `impact.symbols`; optional icons: rest/parking areas, EV chargers, webcams | no | Official open data. Closures add +30 min to routes passing them. |
| **TomTom** Routing API + Traffic Incidents v5 | traffic-aware routing with lane sections and speed-limit sections; incidents | yes | Free tier ~2,500 req/day. Settings → Verkehrsdaten or `TOMTOM_API_KEY`. |
| **HERE** Traffic API v7 | incidents | yes | Settings or `HERE_API_KEY`. |
| **Mobilithek** (NAP Deutschland) | DATEX II traffic messages, e.g. VMZ Niedersachsen | subscription + client cert | Subscribe on mobilithek.info, then enter the feed URL and import the `.p12` certificate in Settings. Generic DATEX II v2/v3 `situationRecord` parser. |

## AMAP / Petal Maps: reference screenshots only, never data
Their German lane data is mostly correct, but some lanes are outdated (owner's observation) — and some are wrong in
exactly the same way as OSM (Herschelstraße / Celler Straße), so agreeing with them proves nothing. Rules (owner's
decision, 2026-10-15):
- **Allowed:** opening AMAP / Petal on the owner's phone and taking screenshots of their lane view at a junction, like
  the owner does by hand, to spot where LaneWise disagrees.
- **Not allowed:** copying their lanes, arrows or geometry into LaneWise or into OSM (OSM forbids imports from
  proprietary maps), reverse-engineering their app or data, or automated bulk capture.
- A disagreement is a pointer: the junction is then checked against the official aerial photo (DOP20, junction audit)
  and reality, and fixed in our logic or by the owner in OSM.
- The most up-to-date reference is the aerial photo (LGLN DOP20, flown 2025): lane lines and counts are clear; painted
  arrow directions are too small at 20 cm and come from OSM `turn:lanes`.

## Attribution
Shown in Settings and by MapLibre's attribution button: © OpenStreetMap contributors (ODbL),
OpenFreeMap, FOSSGIS, Die Autobahn GmbH des Bundes, TomTom, HERE, Mobilithek.
