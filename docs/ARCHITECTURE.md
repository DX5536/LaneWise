# Architecture

Kotlin, Jetpack Compose, MapLibre Native (OpenGL), no Google Play Services. Package `de.lanewise`.

```
de.lanewise
├── LaneWiseApp / MainActivity        app + permission + theme + keep-screen-on
├── core/geo/Geo.kt                   LatLon, distances, bearings, Polyline (projection, slice, offset)
├── core/geo/Sun.kt                   sun elevation → automatic light/dark theme
├── core/model/Route.kt               Route, RouteStep, Maneuver, SpeedLimitSpan, RoadSpan, TrafficIncident
├── core/net/Http.kt                  OkHttp client + JsonElement helpers
├── lane/                             ★ the lane assist
│   ├── LaneModel.kt                  LaneDir bitmask, LaneSite (lateral coords, pockets), LaneAdvice
│   ├── LanePlanner.kt                DP over lane sites, minimises lane changes
│   ├── LaneGuidance.kt               runtime: re-plan per fix, assumed lane, timed change instruction
│   └── LaneRibbonBuilder.kt          route road (buildRoad, per km) + live highlight (build) on a fixed grid
├── routing/                          RoutingProvider, OSRM (FOSSGIS), TomTom, RouteScorer (traffic)
├── offline/                          OSM graph builder (Overpass), compact graph, A* router, tile packs
├── speed/SpeedLimitProvider.kt       OSM maxspeed along the route corridor (Overpass)
├── traffic/                          Autobahn GmbH API, TomTom, HERE, Mobilithek DATEX II
├── location/Location.kt              raw GNSS (LocationManager GPS_PROVIDER + GnssStatus), simulator
├── nav/                              NavigationEngine (snap, reroute, speed status, announcements),
│                                     Phrases (DE/EN/VI, voice segments), VoiceGuide (OmniVoice packs → Android TTS),
│                                     VoicePack, VoiceInventory (tools/voice_pack),
│                                     NavigationService (foreground service: guidance with the screen off)
├── osm/                              OsmTags, StreetLanes (all streets around the car, clipped at junctions),
│                                     AerialLanes (lane counts from aerial photos, applied over OSM),
│                                     LaneReports ("lanes wrong here" → lane_reports.geojson)
├── search/Geocoder.kt                Photon (OSM) search, Germany only
├── settings/Settings.kt              DataStore-backed AppSettings
└── ui/
    ├── MainViewModel.kt              orchestration (state machine BROWSE → PREVIEW → NAVIGATING)
    ├── map/MapController.kt          MapLibre style, layers (road / live highlight / signal pop-up sources),
    │                                 AMAP-style or classic camera (auto or fixed zoom), car puck on the lane path,
    │                                 dead-reckoned every display frame between fixes
    ├── map/CarModel.kt               3D car as fill-extrusion blocks (body, cabin, wheels, lights)
    ├── lane/LaneArrows.kt, LaneViews.kt   vector lane glyphs, LaneBar, LaneScene3D, LaneHint
    ├── hud/Hud.kt                    speed border, speed sign, manoeuvre banner, ETA bar, GNSS chip
    ├── screens/                      MainScreen, SettingsScreen
    └── theme/Theme.kt                LaneWise palette (violet + lime on graphite), light/dark
```

## Data flow while navigating

```
GNSS fix (or simulator) ──► MainViewModel.onFix
        │
        ▼
NavigationEngine.update ──► snap to route, progress, speed status, off-route counter
        │                └► LaneGuidance.update ──► LanePlanner.plan (horizon)
        ▼
NavSnapshot ──► UI state ──► MapController.updateNavigation (camera + LaneRibbonBuilder layers)
        │                └► HUD (banner, lane bar, 3D scene, speed sign, red border)
        └► announcements ──► VoiceGuide (OmniVoice clips, else TTS)
off-route ×3 ──► reroute (10 s throttle)       speed limits: Overpass, 40 km windows
traffic every 5 min (Autobahn/TomTom/HERE/Mobilithek)
```

## Offline ("satellite only") mode

GNSS (GPS, Galileo, GLONASS, BeiDou) gives the position without internet; routing needs a road
graph, which is downloaded once per region:

1. `OfflineGraphBuilder` downloads drivable OSM ways for the region bbox via Overpass, streams the
   JSON (`android.util.JsonReader`), and builds a CSR graph with per-way `maxspeed`
   (`DE:urban` = 50 etc.), `turn:lanes(:forward/:backward)`, `change:lanes` (solid lines; format LWG3), names and refs → `filesDir/graph_<region>.lwg`
   (gzip). Format LWG4 adds turn restrictions (`type=restriction` via a node, second Overpass query).
2. `OfflineTiles` downloads MapLibre tile packs (z8–14, both styles) from OpenFreeMap.
3. `OfflineRouter` runs edge-based A* (time-weighted, respects turn restrictions) and reconstructs steps, lane sites (turn lanes apply at the
   end of a way), speed limits and road kinds — so lane assist and speed warnings work offline too.

Limitations: no live traffic, region-bounded, restrictions via a way (rare) are ignored.

## Tools (PC side)

- `tools/gen_map_styles.py`: MapLibre styles (CI checks they are current).
- `tools/aerial_lanes/`: DOP20 aerial photos → road-marking U-Net → lane counts per OSM way →
  `assets/aerial_lanes.json` (spot checks in `spot_checks.csv`).
- `tools/voice_pack/`: OmniVoice voice packs; `inventory/<lang>.txt` (written by `VoiceInventoryTest`) + Hannover
  street names → Ogg Opus clips + `index.tsv`, played by `nav/VoiceGuide`.

## Building

GitHub Actions (`.github/workflows/build.yml`): JDK 17 + Android SDK + Gradle 8.14.3 → unit tests
→ debug + release APKs as artifacts; tags `v*` attach APKs to a GitHub release. Optional secrets:
`TOMTOM_API_KEY`, `HERE_API_KEY`, `RELEASE_KEYSTORE_BASE64`, `RELEASE_KEYSTORE_PASSWORD`,
`RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`. Without a keystore the release APK is debug-signed.

The repo has no Gradle wrapper JAR (binary); CI uses a pinned Gradle. Locally run `gradle wrapper
--gradle-version 8.14.3` once (or open in Android Studio).
