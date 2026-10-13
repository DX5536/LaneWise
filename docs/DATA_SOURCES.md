# Data sources (Germany, focus Region Hannover)

| Source | Used for | Key? | Notes |
| --- | --- | --- | --- |
| **OpenStreetMap** via FOSSGIS OSRM `routing.openstreetmap.de/routed-car` | default routing + lanes per intersection | no | Fair-use public server hosted in Germany. Self-host OSRM for heavy use (URL configurable). |
| **Overpass API** `overpass-api.de` | speed limits along route; offline graph download | no | Fair use; downloads are one-off per region. |
| **OpenFreeMap** `tiles.openfreemap.org` | vector map tiles + fonts | no | OpenMapTiles schema; LaneWise ships its own styles (`tools/gen_map_styles.py`). |
| **Photon** `photon.komoot.io` | search | no | Restricted to the Germany bbox, biased to current position. |
| **Die Autobahn GmbH des Bundes** `verkehr.autobahn.de/o/autobahn` | roadworks, warnings, closures on A2, A7, A37, A352, A39 + motorways on the route | no | Official open data. Closures add +30 min to routes passing them. |
| **TomTom** Routing API + Traffic Incidents v5 | traffic-aware routing with lane sections and speed-limit sections; incidents | yes | Free tier ~2,500 req/day. Settings → Verkehrsdaten or `TOMTOM_API_KEY`. |
| **HERE** Traffic API v7 | incidents | yes | Settings or `HERE_API_KEY`. |
| **Mobilithek** (NAP Deutschland) | DATEX II traffic messages, e.g. VMZ Niedersachsen | subscription + client cert | Subscribe on mobilithek.info, then enter the feed URL and import the `.p12` certificate in Settings. Generic DATEX II v2/v3 `situationRecord` parser. |

## Why not Petal/AMAP data
Their German lane data is outdated (owner's observation). OSM lane tagging in Hannover is actively
maintained by the community and reaches users within hours via FOSSGIS/Overpass.

## Attribution
Shown in Settings and by MapLibre's attribution button: © OpenStreetMap contributors (ODbL),
OpenFreeMap, FOSSGIS, Die Autobahn GmbH des Bundes, TomTom, HERE, Mobilithek.
