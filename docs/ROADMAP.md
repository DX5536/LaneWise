# Status & roadmap

_Last updated: 2026-10-14 (v0.3: crash fix, rendering fixes, aerial-lane pipeline)._

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

## Done (v0.3, 2026-10-14)
- [x] **Crash on start fixed** (since commit c989b5a, not fe0323c): MapLibre 11.13.5 `TileSet.setBounds(Float, Float, Float, Float)`
      calls itself → `StackOverflowError` in `onDidFinishLoadingStyle`. Now uses the `Array<Float>` overload (`MapController`).
- [x] Browse map: markings stop at junctions (OSM node ids → junction degree; lines cut by the crossing road's half width),
      stop lines/arrows sit in front of the crossing road instead of inside it
- [x] Navigation: car puck + camera drive on the planned lane's centre (`LaneRibbonBuilder.Ribbon.lanePath`), not on the
      road centre line; ribbon measured from the right edge and smoothed ±20 m (no more jagged steps)
- [x] Turn pockets open 35 m (urban) / 80 m (rural) / 150 m (trunk) / 250 m (motorway) before the junction instead of
      always 90 m (Herschelstraße showed 2 lanes over its whole length); opposite-direction lanes stay hidden
- [x] **Lanes from aerial imagery, session 1** (`tools/aerial_lanes/`, see its README): DOP20 download (WMS, UTM 32),
      bootstrap labels (top-hat + Hessian ridge), U-Net/ResNet-34 training on the RTX 3070 Ti, inference, lane counting
      per OSM way (cross-section tracks, dashed vs solid), asset builder, spot-check page + `spot_checks.csv`.
      App side: `osm/AerialLanes.kt` (+ unit tests) applies it only where OSM has no lane data or disagrees (never over
      `turn:lanes`), setting "Spuren aus Luftbildern" (DE/EN/VI). Currently applied to the browse map only.
- [x] Manoeuvre banner is a countdown: a violet fill shrinks to the manoeuvre (starts at the previous step, at most
      400 m / 1 km trunk / 1.5 km motorway before it); the lane bar docks under the banner at full width as one card
- [x] **Solid-line awareness** (was Next #1): OSM `change:lanes(:forward|:backward)` is matched along the route corridor
      (`ChangeSpan`), and `LanePlanner` only counts stretches where the needed change is allowed as usable gap; a change
      with no legal stretch costs 200 (vs 10 per change) → the change is planned before the solid line (unit test).
- [x] Solid lines, part 2: the change instruction is timed in the last dashed stretch before the junction
      (`LaneChangeInstruction.deadline`); solid dividers drawn from `change:lanes` on the route and the browse map;
      offline graph format LWG3 stores `change:lanes` per way and the offline router emits `ChangeSpan`s
      (old LWG1/2 files still load — re-download the region to get solid lines offline)
- [x] **OmniVoice voice packs** (`tools/voice_pack/`, README): announcements are spoken as segments; ~300 fixed
      phrases per language (inventory generated by `VoiceInventoryTest`) + ~11 k Region-Hannover street names/refs are
      rendered on the PC with OmniVoice (en-US / en-GB via voice design, de / vi via a picked automatic voice that is
      cloned), played gaplessly by `VoiceGuide`; missing segment → whole sentence via Android TTS. Packs download from
      the GitHub release `voices-v1` or `adb push` (`04_push.py`); choose in Settings → Sprachansagen (DE/EN/VI text).
- [x] Lanes that *open* are not lane changes: when OSM says the road had fewer lanes between the last junction and
      this one (e.g. Celler Straße: 1 lane, widening to left | straight | straight+right 84 m before Herschelstraße),
      moving into the new lanes costs nothing and is not announced (`LanePlanner.effectiveChanges`, unit test);
      without lane-count data every crossing still counts. Right-turn pockets now taper with the road width, so the
      lime ribbon no longer swings right and back before junctions (Vahrenwalder Straße).
- [x] Lane geometry: through lanes keep their place and turn pockets grow outward (left pocket over the median on
      two-way roads), also when OSM counts the pocket along the whole way; lanes that end at a junction stop at the
      stop line instead of tapering into the next lane; one stop line + arrow row per junction (dual-carriageway
      junctions have two OSRM sites ~20 m apart). `LaneRibbonBuilderTest` (planned lane stays within 0.3 m).
- [x] **AMAP-style view** (setting "Spurenkarte (wie AMAP)", on by default; off = classic with the 3D junction box):
      no junction box, camera zooms out to ~1 km on open road and closes in + tilts down towards turns/lane changes;
      two-level carpet (soft band over all usable lanes + lime recommended strip); raised white turn arrow with
      violet glow through the next turn; "Durchgezogene Linie" chip 250 m before `change:lanes` restrictions.
      Only the route's own lanes are drawn while driving (owner's choice).
- [x] Browse map: markings are clipped exactly where they enter another street's surface (no lines across
      junctions or where dual carriageways merge, no gaps before side streets); `StreetLanesTest`.
- [x] **Pre-built route road**: surface, lines, arrows and stop lines are built once per 1 km of route on a fixed
      6 m grid and kept (rebuilt only when the route or its OSM data changes); only the highlight (lime strip,
      usable-lane band, turn arrow) is live, on the same grid — no jitter, highlight always on its lane
      (`LaneRibbonBuilder.buildRoad` / `build`, test `geometryDoesNotShiftAsTheCarMoves`).
- [x] **Traffic-light pop-up** on the map at the next signalised junction (< 250 m): main signal × count, separate
      arrow signals (inferred from turn-only lanes, dashed outline = unconfirmed), Grünpfeil (Zeichen 720),
      pedestrian lights × count, push button. Source: OSM (`highway=traffic_signals`, `crossing`, `button_operated`,
      `red_turn:right`, `traffic_sign=DE:720`) — there is no national signal database in Germany.
- [x] Lane-change hint is a countdown too: bright lime shrinks to the deadline (end of the dashed stretch / junction)

## Aerial lanes — next sessions
1. Owner spot-checks `work/spot/index.html` → `spot_checks.csv`; hand-correct 20–50 label tiles → retrain.
2. Run on the full `hannover` area (~2–3k tiles, ~1 h download) once agreement with OSM is good on `test`.
3. Use aerial counts in navigation too (`SpeedLimitProvider` corridor lane spans by way id).
4. Turn arrows from the mask → `turn:lanes` suggestions; solid lines → `change:lanes` (ties into "Next" 1).
5. Optional: 2× bicubic input to the network for 1-px lines (no generative upscaler — it would invent paint).

## Not yet verified
- [ ] **First CI build** — code was written without a local Android SDK; expect a round of compile fixes.
- [ ] On-device test drive in Hannover (demo mode works without driving: preview → "Demo").

## Next
0. In-app lane corrections (owner's own observations, stored on the phone, applied over OSM), and/or
   fixing OSM directly — wrong lane counts found so far are OSM tagging errors, not imagery (DOP20 flown 2025-03-06).
1. Solid lines, rest: on-device check in Hannover (how well is `change:lanes` tagged?); fall back to the
   aerial-imagery pipeline (solid vs dashed tracks are already measured there) where OSM has no `change:lanes`.
2. Lane-count continuity between sites from OSM `lanes`/`lanes:forward` instead of the next-site approximation
   (partly done: pocket length by road kind).
3. Turn restrictions in the offline router (`type=restriction` relations).
4. Owner's lane drawing ("coMap" sketch) was referenced but not attached in the first chat —
   compare it with `docs/LANE_ALGORITHM.md` once shared and add it as a unit test scenario.
5. Foreground service so guidance continues with the screen off.
6. Android Auto / car display.
7. Settings text fields: debounce DataStore writes.
8. HERE Routing v8 as an additional provider; TomTom flow tiles as a traffic overlay.
9. Optional: install `jadx-headless-mcp` for REA and decompile the Petal `hdmi` beans / AMAP lane messages.
