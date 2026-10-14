# Architecture

Kotlin, Jetpack Compose, MapLibre Native (OpenGL), no Google Play Services. Package `de.lanewise`.

```
de.lanewise
├── LaneWiseApp / MainActivity        app + permission + theme + keep-screen-on
├── core/geo/Geo.kt                   LatLon, distances, bearings, Polyline (projection, slice, offset)
├── core/model/Route.kt               Route, RouteStep, Maneuver, SpeedLimitSpan, RoadSpan, TrafficIncident
├── core/net/Http.kt                  OkHttp client + JsonElement helpers
├── lane/                             ★ the lane assist
│   ├── LaneModel.kt                  LaneDir bitmask, LaneSite (lateral coords, pockets), LaneAdvice
│   ├── LanePlanner.kt                DP over lane sites, minimises lane changes
│   ├── LaneGuidance.kt               runtime: re-plan per fix, assumed lane, timed change instruction
│   └── LaneRibbonBuilder.kt          3D lane geometry for the map
├── routing/                          RoutingProvider, OSRM (FOSSGIS), TomTom, RouteScorer (traffic)
├── offline/                          OSM graph builder (Overpass), compact graph, A* router, tile packs
├── speed/SpeedLimitProvider.kt       OSM maxspeed along the route corridor (Overpass)
├── traffic/                          Autobahn GmbH API, TomTom, HERE, Mobilithek DATEX II
├── location/Location.kt              raw GNSS (LocationManager GPS_PROVIDER + GnssStatus), simulator
├── nav/                              NavigationEngine (snap, reroute, speed status, announcements),
│                                     Phrases (DE/EN), VoiceGuide (TTS)
├── search/Geocoder.kt                Photon (OSM) search, Germany only
├── settings/Settings.kt              DataStore-backed AppSettings
└── ui/
    ├── MainViewModel.kt              orchestration (state machine BROWSE → PREVIEW → NAVIGATING)
    ├── map/MapController.kt          MapLibre style, layers, 3D follow camera, car puck
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
        └► announcements ──► VoiceGuide (TTS)
off-route ×3 ──► reroute (10 s throttle)       speed limits: Overpass, 40 km windows
traffic every 5 min (Autobahn/TomTom/HERE/Mobilithek)
```

## Offline ("satellite only") mode

GNSS (GPS, Galileo, GLONASS, BeiDou) gives the position without internet; routing needs a road
graph, which is downloaded once per region:

1. `OfflineGraphBuilder` downloads drivable OSM ways for the region bbox via Overpass, streams the
   JSON (`android.util.JsonReader`), and builds a CSR graph with per-way `maxspeed`
   (`DE:urban` = 50 etc.), `turn:lanes(:forward/:backward)`, `change:lanes` (solid lines; format LWG3), names and refs → `filesDir/graph_<region>.lwg`
   (gzip).
2. `OfflineTiles` downloads MapLibre tile packs (z8–14, both styles) from OpenFreeMap.
3. `OfflineRouter` runs A* (time-weighted) and reconstructs steps, lane sites (turn lanes apply at the
   end of a way), speed limits and road kinds — so lane assist and speed warnings work offline too.

Limitations: no turn restrictions, no live traffic, region-bounded.

## Building

GitHub Actions (`.github/workflows/build.yml`): JDK 17 + Android SDK + Gradle 8.14.3 → unit tests
→ debug + release APKs as artifacts; tags `v*` attach APKs to a GitHub release. Optional secrets:
`TOMTOM_API_KEY`, `HERE_API_KEY`, `RELEASE_KEYSTORE_BASE64`, `RELEASE_KEYSTORE_PASSWORD`,
`RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`. Without a keystore the release APK is debug-signed.

The repo has no Gradle wrapper JAR (binary); CI uses a pinned Gradle. Locally run `gradle wrapper
--gradle-version 8.14.3` once (or open in Android Studio).
