# Status & roadmap

_Last updated: 2026-10-15 (v0.4.2: Schulenburger Landstraße lane, smooth camera, auto zoom, automatic light/dark, 3D car, Bilibili ideas as options, Autobahn icons)._

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
- [x] Owner's test route (Musterstraße 1 → Musterweg 3, Langenhagen) as an opt-in live check
      (`LANEWISE_LIVE=1`, `RouteRenderLiveTest`): highlight inside the road, no folded lines, lane advice per turn.
- [x] Turn junctions: only the turn lanes continue as a connector into the new road (left turns join its left side,
      right turns its right side); the other lanes end at the stop line, no markings inside the junction.
      Offset lines drop points that would run backwards (no folding on the inside of tight turns).
- [x] Lane bar only for the coming manoeuvre: never the lanes of a junction after the next turn (Melanchthonstraße
      case); OSRM lanes on the intersection ≤ 80 m before an unmapped turn are re-targeted to that turn.
- [x] Default language English; Demo / Los geht's follows the car immediately; Recenter button with lime outline;
      clear message while voice packs are not published.
- [x] Setting "Show streets while driving (Petal style)", off by default: during navigation the street layer (all
      OSM streets within 450 m, refetched every 250 m) replaces the route-only road; the route highlight stays on top.
- [x] Lane-change hint is a countdown too: bright lime shrinks to the deadline (end of the dashed stretch / junction)

## Done (v0.3.1, 2026-10-14) — owner's demo video (Musterstraße 1 → Musterweg 3, Langenhagen)
- [x] **Wrong lane highlight before left turns** (video 0:30–0:45, Celler Straße → Herschelstraße): the highlight stayed
      in the middle straight-only lane and "move one lane to the left" came only ~50 m before the turn. Two causes:
      (1) "lanes that open are free" was judged from the *car* to the junction, so once the car had passed the point where
      the road widens (1 → 3 lanes) the free move suddenly became a late lane change; it is now judged from where the
      assumed lane was established (last junction passed / lane tapped, `LaneGuidance.assumedSince`,
      `LanePlanner.Options.assumedSince`). (2) The highlight's lane-change transition and the change instruction were
      timed from `car + 5 m` inside the lead distance, so they slid ahead of the car and the strip at the car never left
      the old lane; both are now fixed on the road. Same bug at the Arndtstraße right-turn pocket and Hauptstraße.
      Tests: `laneThatOpenedBehindTheCarIsStillFree`, `changeStartDoesNotSlideWithTheCar`,
      `highlightIsInTheOpenedTurnLaneAfterTheWidening`; the live trace (`LANEWISE_TRACE=1` with `RouteRenderLiveTest`)
      prints what the lane bar / hint would show every 20 m.
- [x] **Demo lag**: the live highlight (1.2 km) was rebuilt 4×/s and the route road (up to 4 km, also whenever OSM
      corridor data arrived) built on the UI thread; both now run on a worker thread (one build at a time, newest wins),
      only the hand-over to MapLibre stays on the UI thread.
- [x] **Demo speed**: − / + on the DEMO badge, ×0.25 … ×8 (live; the shown speed stays the real one, so speed
      warnings still work). DE/EN/VI accessibility labels.
- [x] Traffic-light pop-up without the green pedestrian figure (owner's request); the push-button marker stays.
- [x] Light mode: the recommended lane and usable-lane band are violet (`#6C4CF5`) instead of lime; the route line
      underneath is lavender with a violet casing so the strip stands out. Dark mode unchanged (lime).
- [x] Lane bar of a junction the route only passes through is shown only in the last 250 m (600 m motorway/trunk)
      before it, not 900 m — under "turn right in 2.3 km" (Schulenburger Landstraße) it looked like the turn's lanes.

## Done (v0.4, 2026-10-14)
- [x] **Turn restrictions in the offline router** (was Next 3): OSM `type=restriction` relations with a via node
      (`no_*` / `only_*`, `restriction:motorcar`, `except=motorcar` skipped) are downloaded with the region (second,
      optional Overpass query) and stored in graph format **LWG4**; A* is now edge-based so "from way, via node, to way"
      applies exactly. Older graph files still load (without restrictions) — re-download the region. `OfflineRouterTest`.
- [x] **Guidance with the screen off** (was Next 5): `nav/NavigationService`, a location foreground service started by
      "Let's go" / Demo and stopped at the end; ongoing notification with the next manoeuvre and remaining distance
      (DE/EN/VI channel name). Asks for the notification permission on Android 13+ (optional).
- [x] **Settings text fields debounced** (was Next 7): written 600 ms after the last keystroke / when leaving the field.
- [x] **Lane reports** (first step of Next 0): long-press the lane bar while driving → "lanes wrong here" is saved with
      position, road and the lanes shown (`turn:lanes` notation) to `Android/data/<package>/files/lane_reports.geojson`;
      the file opens directly in JOSM / iD for fixing OSM. Pull it with
      `adb pull /sdcard/Android/data/de.lanewise.app.debug/files/lane_reports.geojson`. `LaneReportsTest`.
- [x] Lane bar of a junction before the banner's manoeuvre shows its own distance tag (video review item).
- [x] Browse map (no route) shows traffic lights / signs too: they came only from a route before. Signal and sign
      nodes are fetched with the nearby streets in the same Overpass query (`StreetLanes.fetchNearby`).
- [x] Search box hint shortened to "Where to?" / "Wohin?" / "Đi đâu?" (one line).
- Note (owner, a Hildesheimer Straße junction): no arrows because OSM has only `lanes=2` there, no `turn:lanes`
  (ways 29702218, 29702219, 45977791, 45977795, 1435846363) — fix in OSM, or later from the aerial arrows
  (aerial lanes item 4).

- [x] Sandbox for TomTom lanes: `ProviderCompareLiveTest` routes the test route with OSRM (OSM) and TomTom, pairs the
      junctions, prints both lane layouts + the advice per turn, flags TomTom lane counts wider than the OSM road, and
      writes an HTML map (`app/build/reports/lanewise/provider_compare.html`).
      **Result (2026-10-14, test route):** OSM 17 lane junctions vs TomTom 6; where both have data, 2 same, 2 different —
      TomTom simplifies (`left|right` where OSM has `left|through;right`; raw API checked, not a parser bug). TomTom does
      return `laneSeparators` (solid / dashed) at its lane sections — a possible extra source for solid lines.
      Conclusion: keep OSM for lanes; TomTom for traffic only.
- [x] **Auto = OSM lanes + TomTom traffic**: Auto routes with OSRM (OSM lanes); with a TomTom key, TomTom rebuilds that
      same route (`supportingPoints`, `reconstructionMode=track`, `TomTomRoutingProvider.withTraffic`) for the live-traffic
      ETA (no double-counted jams: `Route.trafficEta`) and its `laneSeparators`. Solid/dashed lines: OSM `change:lanes`
      first, TomTom separators only where OSM has none (`ChangeSpan.merge` / `fromTomTomSeparators`, mixed lines per
      § 41 StVO: the dashed side may cross). If TomTom's rebuild differs by > 3 % in length it is ignored. TomTom routes
      only if OSRM fails. Settings text updated (DE/EN/VI).
- [x] Signal card: one head per lane group in road order (owner's photo): a head with **arrow lamps** for left-turn
      lanes, the round head for straight on, an arrow head for right-turn lanes (arrow heads inferred, dashed outline).
- [x] APK names carry app and version: `LaneWise-v0.4.1.<CI run>-release.apk` (local: `-local`); version + commit shown
      at the bottom of Settings (`BuildConfig.VERSION_NAME`, `GIT_SHA`). Bump `appVersion` in `app/build.gradle.kts`.

## Done (v0.4.1, 2026-10-14) — owner's drive screenshots
- [x] **Wrong lane at Schiffgraben → left into Berliner Allee**: OSRM flagged the 2nd left lane, the straight lane and
      the straight+right lane as "valid" for the left turn (`left|left|through|through;right`), and the lane bar
      recommended the straight lane. At a real turn, provider flags that call a lane usable whose arrows cannot make
      the turn are now ignored and the arrows decide (`LaneSite.allowed`, test `wrongProviderFlagsAtATurnAreIgnored`);
      flags that only narrow the arrows down are still used. Live trace now recommends the leftmost lane.
- [x] Traffic-light pop-up moved off the map into a **HUD card under the banner** (left-aligned with it, "Then" chip on
      the right of the same row), bold white border: signal head, inferred arrow signals (dashed), Grünpfeil, push
      button. No light count (only one signal applies to the driver's lane) and no pedestrian figure; the top-left chip
      lost its count too.
- [x] **Journey progress bar** along the top of the bottom panel: violet = driven, grey = remaining, lime marker at the car.
      Counts the whole journey across re-routes (driven on earlier routes is kept: `UiState.journeyDriven`).
- [x] End-navigation button: red ✕ with thicker strokes and a red ring.
- [x] **Stop line at traffic lights without lane data**: one line across the drawn road at the first signal of a
      junction (other heads within 40 m are skipped; never where a lane site already has one). `stopLineAtTrafficLightWithoutLanes`.
- [x] **Start point**: "From: My position" in the route preview; tap to search any start (Demo then drives from there).
      "Let's go" from elsewhere re-routes from the GPS position once you drive. DE/EN/VI.
- [x] Search finds "musterstädterstr 1" / "Bremerstraße 1": adjective street names are also searched split ("Musterstädter Straße"),
      as OSM writes them — Photon only returned a same-named street in Austria (filtered out). Start picker shows
      "Searching …" / "Nothing found". `GeocoderTest`.
- [x] **ETA +3 h fixed** (owner: a short city trip showed 3 h 26 min, "6 closures"): since the TomTom key, TomTom
      road closures (icon category 8) came in, and any closure with an end point within 35 m of the route added 30 min —
      closed side streets crossing the route at junctions. An incident now counts only if ≥ 30 m of it runs along the
      route (within 20 m, parallel); TomTom incidents add nothing to a TomTom traffic ETA (already included).
      `RouteScorerTest`.
- [x] `RouteRenderLiveTest` takes `LANEWISE_FROM` / `LANEWISE_TO` (`lat,lon`) to check any route.

## Done (v0.4.2, part 1, 2026-10-14) — owner's video Musterstraße 1 → Musterweg 3, Langenhagen (13 km, demo ×4, classic view)
- [x] **Wrong lane on Schulenburger Landstraße → right into Beneckeallee** (video 3:35–3:55): after the junction with
      `left | through | through;right` the road has two lanes (OSM) for 350 m, then `through | right`. The right-only
      lane was treated as a turn pocket (lateral −1), so the plan kept the car in the *left* lane and asked "move one
      lane to the right" 160 m before the turn. Now `markFullLengthLanes` (run when OSM lane counts arrive,
      `Route.withLaneCounts`) marks a site whose lanes all run on from the previous site's through lanes
      (`LaneSite.fullLength`): its turn-only edge lanes are ordinary lanes. Real pockets (one lane more than the through
      lanes before) stay pockets even when OSM counts them along the whole way. Live trace: right lane all the way, no
      change. Test `turnOnlyLaneThatRunsTheWholeWayIsNotAPocket`.
- [x] **Smoother car + camera** (the recording itself runs at ~96 fps without frozen frames; the "lag" was motion):
      (1) the car puck's GeoJSON source was updated asynchronously each frame and landed a frame or two after the
      camera, so the puck jittered sideways → `setGeoJsonSync`; (2) the tween restarted at every fix (4/s) with a new
      linear speed and stopped dead when a fix came late → dead reckoning at the measured route speed, gently pulled
      towards the predicted position, never backwards; (3) bearing / zoom / tilt easing is time-based (same feel at
      60 and 120 Hz) instead of per frame.
- [x] **Less UI-thread work per fix**: the live highlight (1.2 km of polygons handed to MapLibre on the UI thread) was
      rebuilt at every fix; now only when the lane plan changes or the car has moved 25 m (`MapController.GEOM_STEP`).
- [x] Classic view: the 3D junction close-up fades/expands in and out (250 ms) instead of popping in one frame.
- [x] **Setting "Auto zoom while driving"** (Appearance, on by default; DE/EN/VI): off = fixed zoom level from a slider
      (15.5–19, default 17.5), tilt stays as set. Auto zoom in the classic view zooms out less (motorway 16.8, trunk /
      > 70 km/h 17.2, was 16.6 / 17.0).

## Done (v0.4.2, part 2, 2026-10-15)
- [x] **Automatic light/dark mode** (new default theme "Automatic (sunrise/sunset)", DE/EN/VI): dark while the sun is
      below −3° at the car's position (Hannover before the first fix), re-checked every minute; no internet or system
      setting needed (`core/geo/Sun.kt`, NOAA formulas, `SunTest`). System / Light / Dark still selectable.
- [x] **3D car** (setting "3D car", on by default): a low-poly car — body with rounded corners, dark glass cabin, roof,
      wheels, head and tail lights — built from MapLibre `fill-extrusion` blocks (MapLibre Native has no glTF model
      layer), rotating with the heading, with a violet glow underneath (`ui/map/CarModel.kt`, `CarModelTest`). Drawn
      2.4 × life size (a real car is ~6 dp long at zoom 17). Off = the flat arrow puck.
- [x] **Car / arrow size** slider (×0.5 … ×2, Appearance) for both the 3D car and the arrow.
- [x] **3D car no longer hidden by 3D buildings**: buildings within 9 m of the car (or containing it — GPS indoors /
      in a courtyard) are drawn flat (`building-3d` height via MapLibre's `distance` expression, updated every 4 m).
- [x] App version 0.4.2 (`appVersion`).
- [x] **Settings froze after a demo** (owner): the per-frame car / camera loop was only stopped by the next real GPS fix
      (rare indoors), so after the demo it kept updating car, chevrons and label synchronously every frame and starved
      the UI thread. It now stops as soon as navigation ends, and pauses while Settings covers the map.
- [x] Zoom-level and car-size sliders moved in whole steps only (the shared slider rounded to integers); now 0.1 steps.
- [x] Map style switch (light ↔ dark, now also at sunset while driving) re-sends road + highlight to the new style
      (sources start empty; the 25 m rebuild step would otherwise leave the road missing until the plan changed).

## Bilibili UI review (2026-10-15) — Baidu Maps V22 and Baidu vs AMAP motorway exit (BV1LQaz6XErV, BV1aU6pBJEzm)
What they do (all lane-level, light theme by day):
- Banner styled as a **road guide sign** (green on motorways in China — for Germany: blue Autobahn / yellow
  Bundesstraße), turn arrow + distance + name; the **lane arrows sit directly under it in the same card**, white
  arrows, recommended lanes bright. (LaneWise already docks the lane bar under the banner.)
- **3D car** (white SUV/sedan) with a soft blue ring around it, roughly one lane wide — done in v0.4.2.
- **Recommended-lane carpet**: teal/green gradient that starts at the car and fades out ~200 m ahead, with animated
  chevrons inside it showing the direction of travel; the route's turn is a blue arrow ribbon on the road.
- **Road furniture in 3D**: toll plaza with per-lane booths and ETC labels painted on the lanes, traffic-light poles
  at junctions, zebra crossings, painted lane arrows and painted road text, curbs and sidewalks, parked cars;
  buildings white/translucent so the road stays readable.
- **Closed lanes** shown as red ✕ markers on the lane surface; lane-level traffic colouring.
- Camera: high tilt, car in the lower third, zooms out on straight motorway (~1 km visible) and in for
  interchanges — like LaneWise's AMAP-style view.
- HUD: speed in a circle top-left; **section-control panel** (current / average speed, km left); vertical
  **route progress bar** on the right edge; current road name + speed-limit chip just below the car; bottom bar
  with distance · time · tolls · arrival, "overview" and "exit" buttons; voice/feedback button on the left.
Candidates 1–6 are done in v0.4.2 part 3 (below), each as an option.

## Done (v0.4.2, part 3, 2026-10-15) — Bilibili ideas as options + Autobahn icons
Settings → "Lane view extras" (all on by default, DE/EN/VI):
- [x] **Lane strip fades out** between 220 and 340 m ahead of the car (12 m pieces with falling opacity,
      `LaneRibbonBuilder.build(fade = true)`).
- [x] **Moving chevrons** in the recommended lane: 7 chevrons, 11 m apart, gliding forward at ~7 m/s on the lane path,
      fading with distance; rebuilt every display frame (`LaneRibbonBuilder.chevrons`, own source, synchronous).
- [x] **Banner in guide-sign colours**: blue / white for the road after the manoeuvre with an `A …` ref, yellow / black
      for `B …` (`Hud.guideSign`); otherwise the app colours.
- [x] **Road name under the car**: current street (or ref) and speed limit as a text label just below the car.
- [x] **Closed lanes** from Autobahn GmbH roadworks: `impact.symbols` (ARROW_UP / CLOSED per lane, after the last
      SEPARATE, shoulder after BORDER_RIGHT dropped) → `TrafficIncident.laneOpen`; `LaneClosure.along` keeps
      active (not `future`) roadworks whose line runs ≥ 30 m along the route in the same direction within 14 m;
      closed lanes get a red tint and a red ✕ every 40 m, aligned at the right road edge. Crossovers (all lanes of
      the carriageway closed) are not drawn. TomTom `LANE_CLOSED` has no lane index, so it is not used.
      `AutobahnLanesTest`, `roadExtrasAreBuiltWhereTheyBelong`.
- [x] **Zebra crossings and signal poles**: OSM `crossing` nodes on the route → 0.5 m stripes across the drawn road;
      a grey pole with a dark signal head (extruded, 5 m) at the right edge of each signalised stop line.
- [x] **See-through buildings while driving**: `building-3d` opacity 0.35 during navigation (0.85 otherwise).
Settings → "Autobahn icons (Autobahn GmbH)" (all off by default — pick which ones):
- [x] **Rest and parking areas** (`parking_lorry`, blue P), **EV charging stations** (`electric_charging_station`,
      green lightning), **webcams** (`webcam`) for A2 / A7 / A37 / A352 / A39 and the motorways on the selected route;
      tap an icon for its name and details (car/truck spaces, charge points, power, operator).
      Note: the API currently returns **no webcams** on any motorway checked (A1, A2, A3, A7, A8, A9, A37, A39, A352).

## Video review — still open
- [ ] Owner: check the 3D car (size, colours, behaviour in tunnels / under bridges) and the automatic theme on the phone.
- [ ] Owner: check the v0.4.2 lane-view options on the phone — chevron speed/colour, fade distance, label position under the
      3D car, pole size; closed lanes only show on a route through active Autobahn roadworks.
- [ ] Re-check on the phone: puck/camera smoothness with the new dead reckoning (demo ×4 and a real drive), and that
      the highlight never lags behind the car with the 25 m rebuild step.
- [ ] Philipsbornstraße (video 2:45): only the route line, no lane surface — OSM has no lane data on most of it;
      street-name labels are drawn on top of the route line there (label placement over the route).
- [ ] `RouteRenderLiveTest` Schiffgraben → Berliner Allee: one road edge folds back at the `CONTINUE LEFT` bend
      (route 1371 m) — check the offset-line pruning on tight bends.
- [ ] Re-check the whole test route on the phone with the fixes (the video was before them).
- [ ] Philipsbornstraße left turn (4 lanes, video 1:25–1:45) and Hauptstraße left turn (video 9:10): verify the strip
      enters the left pocket where it opens; Overpass timed out in the PC check of the 8.4 km route option, so those
      were only checked without OSM lane counts.

## Aerial lanes — next sessions
1. Owner spot-checks `work/spot/index.html` → `spot_checks.csv`; hand-correct 20–50 label tiles → retrain.
2. Run on the full `hannover` area (~2–3k tiles, ~1 h download) once agreement with OSM is good on `test`.
3. Use aerial counts in navigation too (`SpeedLimitProvider` corridor lane spans by way id).
4. Turn arrows from the mask → `turn:lanes` suggestions; solid lines → `change:lanes` (ties into "Next" 1, solid lane lines).
5. Optional: 2× bicubic input to the network for 1-px lines (no generative upscaler — it would invent paint).

- [x] AMAP-style camera zooms out only moderately on open road (urban 17.1 / fast 16.4, was 16.3 / 15.6).

## Not yet verified
- [ ] **CI build** of the current code (local builds and 29 unit tests pass; the owner pushes and runs CI).
- [ ] Real drive (so far demo drives on the owner's phone): turn-lane connector, signal pop-up, solid-line chip,
      Petal-style streets while driving, OmniVoice English test packs (only the Hbf-north streets are rendered).
- [ ] Voice packs: German / Vietnamese voices not picked yet; full renders (~2.4 h each) and the `voices-v1`
      GitHub release not done.

## Next
1. **Solid lane lines from aerial imagery** (owner's request 2026-10-14): draw solid dividers (and plan with them)
   where OSM has no `change:lanes`, using the DOP20 photos as reference — the aerial pipeline already measures
   solid vs dashed tracks per OSM way (`tools/aerial_lanes`); export them as `change:lanes`-like spans in
   `aerial_lanes.json`, apply them in `AerialLanes` for the route corridor and the browse map, and check how well
   `change:lanes` is tagged in Hannover on the phone first.
2. In-app lane corrections applied over OSM (reports are saved already, see v0.4), and/or fixing OSM directly —
   wrong lane counts found so far are OSM tagging errors, not imagery (DOP20 flown 2025-03-06).
3. Lane reports: a share button in Settings (FileProvider) instead of `adb pull`.
4. Lane-count continuity between sites from OSM `lanes`/`lanes:forward` instead of the next-site approximation
   (partly done: pocket length by road kind).
5. Owner's lane drawing ("coMap" sketch) was referenced but not attached in the first chat —
   compare it with `docs/LANE_ALGORITHM.md` once shared and add it as a unit test scenario.
6. Android Auto / car display.
7. HERE Routing v8 as an additional provider; TomTom flow tiles as a traffic overlay.
8. Optional: install `jadx-headless-mcp` for REA and decompile the Petal `hdmi` beans / AMAP lane messages.
