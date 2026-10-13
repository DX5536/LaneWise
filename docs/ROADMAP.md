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
