# Status & roadmap

_Last updated: 2026-10-14 (initial scaffold)._

## Done (v0.1)
- [x] Reverse-engineering notes for Petal Maps 4.7 and AMAP 17.00 lane guidance (`docs/REVERSE_ENGINEERING.md`)
- [x] Lane planner minimising lane changes (DP, keep-right, turn-pocket alignment, corresponding-lane rule) + unit tests
- [x] Runtime lane guidance: assumed lane, tap-to-correct, timed change instructions, "stay in lane"
- [x] 3D map (MapLibre, pitch 60°, 3D buildings) with lane surface, dividers and recommended-lane ribbon
- [x] 3D junction close-up (`LaneScene3D`) and lane bar
- [x] Routing: OSRM/FOSSGIS (free, OSM lanes), TomTom (traffic + lanes + speed limits), offline A*
- [x] Traffic: Autobahn GmbH API, TomTom, HERE, Mobilithek DATEX II; route ranking
- [x] Speed limits (OSM via Overpass / TomTom / offline graph), German sign, light-red over/under-speed border with configurable offsets
- [x] Offline region download (road graph + tile packs) for Hannover; GNSS-only positioning with satellite status chip
- [x] Light/dark mode, own palette (violet + lime on graphite), German/English voice
- [x] App icon (option P: violet tile, extruded converging road slab, lime lane surface shifting lanes)
- [x] GitHub Actions build (`.github/workflows/build.yml`)

## Done (v0.2, 2026-10-14)
- [x] Quicksand font, dark-mode text colours, centred icons, configurable junction close-up distance
- [x] Traffic lights / stop / give way / zebra / railway crossings (OSM) on map + HUD chips; speed-limit sign top-left (also without route)
- [x] On-street bike lanes (OSM `cycleway:*=lane`) as coloured strip, colour selectable
- [x] App language DE / EN / VI (UI + voice)
- [x] Home / Work / named favourites
- [x] Lane rendering rewrite: smoothed centre line, tapered lane-count changes, OSM `lanes:forward/backward`,
      polygons instead of wide lines, stop lines + painted arrows; per-frame tween of car + camera
- [x] Overpass client with retries + 4 mirrors (public servers are often overloaded)
- [x] Planner: free lane choice through turns (turn straight into the lane needed at the next junction;
      owner's Celler Straße → Arndtstraße case is a unit test)
- [x] Lanes + markings of all nearby streets when no route is active (only route lanes while navigating)
- [x] Traffic-light details per junction: vehicle/pedestrian light count, push-button, Grünpfeil;
      separate turn-arrow signals are *inferred* from exclusive turn lanes (OSM has no tag for signal heads)

## Not yet verified
- [ ] **First CI build** — code was written without a local Android SDK; expect a round of compile fixes.
- [ ] On-device test drive in Hannover (demo mode works without driving: preview → "Demo").

## Next
1. Solid-line awareness: parse OSM `change:lanes` (offline graph + Overpass corridor) and forbid lane
   changes across `not_left`/`not_right` segments in `LanePlanner` (AMAP: `surfaceSolidLineMinLimitDistance`).
2. Lane-count continuity between sites from OSM `lanes`/`lanes:forward` instead of the next-site approximation.
3. Turn restrictions in the offline router (`type=restriction` relations).
4. Owner's lane drawing ("coMap" sketch) was referenced but not attached in the first chat —
   compare it with `docs/LANE_ALGORITHM.md` once shared and add it as a unit test scenario.
5. Foreground service so guidance continues with the screen off.
6. Android Auto / car display.
7. Settings text fields: debounce DataStore writes.
8. HERE Routing v8 as an additional provider; TomTom flow tiles as a traffic overlay.
9. Optional: install `jadx-headless-mcp` for REA and decompile the Petal `hdmi` beans / AMAP lane messages.
