# Status & roadmap

_Last updated: 2026-10-15 (v0.4.3 incl. lane audit, Settings preview, report sharing: car pinned to the camera, round corners, lane strip fix, Settings-after-demo fix, smoother demo, zebra rule checked on aerial photos, lane opacity setting, built-in test drive)._

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
- [x] App version 0.4.2 (`appVersion`); 0.4.3 for the parts below.
- [x] **Settings froze after a demo** (owner): the per-frame car / camera loop was only stopped by the next real GPS fix
      (rare indoors), so after the demo it kept updating car, chevrons and label synchronously every frame and starved
      the UI thread. It now stops as soon as navigation ends, and pauses while Settings covers the map.
- [x] Zoom-level and car-size sliders moved in whole steps only (the shared slider rounded to integers); now 0.1 steps.

## Done (v0.4.3, part 1, 2026-10-15) — owner's demo video (Musterstraße 1 → Musterweg 3, Langenhagen, ×2)
- [x] **Lime lane strip disappeared** (whole video: only the faint usable-lane band was visible): the strip's opacity
      used `to-number(get("o"))`, and MapLibre turns a missing value into 0 — every strip piece without a fade value
      was transparent. Now `number(get("o"), 1)`.
- [x] **Car not moving with the camera**: car, chevrons and road-name label were map sources updated every frame;
      MapLibre re-tiles such updates on a worker thread, so they appeared 0–2 frames after the camera (car jitter of a
      few pixels) and the extra work dropped frames in turns. While the camera follows, the car (3D or arrow) and the
      road name are now drawn by `CarOverlayView` at the camera target — fixed on screen, nothing to re-tile. The 3D car
      is a sprite rendered from `CarModel` at the current size and tilt (`CarSprite`, re-rendered only when they
      change); the map-layer car is used only when you pan away.
- [x] **Chevrons** are built with the highlight (fixed 9 m grid, `LaneRibbonBuilder.chevrons`) and animated by a light
      wave that only changes the layer's opacity (~30 ×/s) — no geometry update per frame.
- [x] **Jagged / wonky lane lines in corners**: the road centre line was rounded with only a ~3 m radius at junction
      corners, less than the 2–6 m the edges and dividers sit beside it, so they folded on the inside of each turn and
      were trimmed into jagged lines; the car's lane path turned just as abruptly. The centre line is now averaged over
      ±12 m (1-2-3-2-1) before the Chaikin smoothing, and normals come from points ~6 m apart. Live check of the test
      route: no folded / backwards lines, highlight inside the road.
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

## Done (v0.4.3, part 2, 2026-10-15) — checked on the owner's phone over adb
- [x] **Settings unusable after a demo that runs to the end** (reproduced: taps registered, but the settings file was
      written minutes later, and the screen still showed the old value): all coroutine IO threads (64) were blocked in
      network calls — OkHttp `execute()` inside `withContext(Dispatchers.IO)` keeps its thread until the server answers
      or the read timeout (60 s; Overpass 15 min) passes, even after the request was cancelled, and every route search
      fires ~20 traffic requests (Autobahn GmbH × 3 services, TomTom, HERE). DataStore saves on the same pool. Now
      `Http.fetch` runs requests on OkHttp's own queue (`enqueue`, cancellable, body read there) — `getText/getJson/
      postJson/postForm` and Overpass queries no longer hold IO threads; settings and places have their own thread
      (`storeScope`). Verified on the phone: demo at ×8 to the end, Settings change saved and shown at once.
- [x] **Occasional lag at ×4/×8**: the car's speed came from the last two updates only — one that snapped slightly
      backwards halved it and the map stood still for ~200 ms. Now the slope over the last ~1.2 s of updates.
      Screen recording on the phone: no more stalls.
- [x] **Car hidden by 3D buildings in the browse map**: MapLibre's `distance` expression does not take building
      polygons (errors in logcat), so the building cut never worked. The car is now always the screen overlay — also
      when browsing and when panned away (placed under the current camera, turned by its heading relative to the
      camera; `CarSprite` renders any heading) — and always drawn above buildings.
- [x] **Zebra stripes only at real zebra crossings**: OSM `crossing=zebra`, `crossing_ref=zebra`,
      `crossing:markings=zebra…` or sign DE:350 (`OsmTags.isZebra`, `RoadFeature.zebra`, offline graph bit 128), never
      within 30 m of traffic lights — Germany paints no zebra at signals (unlike China). Plain `marked` / `uncontrolled`
      crossings get none.
- [x] **Strip ends before the destination** (Hauptstraße): after the route's last junction with lane data the strip now
      runs on to the destination in the car's lane.
- [x] **Bottom panel reaches the screen edge** (its colour continues behind the system navigation bar).
- [x] **Setting "Recommended lane opacity"** (20–100 %, DE/EN/VI) with a live preview of a three-lane road in Settings.

## Done (v0.4.3, part 3, 2026-10-15)
- [x] **Zebra rule checked against the aerial photos** (LGLN DOP20, 2025; 16 random OSM crossings per class in the
      Hannover inner city, 24 m chips): `crossing=zebra` / `crossing_ref=zebra` 15/16 with visible stripes (one in a
      shadow); `marked` / `uncontrolled` 0/16 — almost all are red cycle crossings (Radfahrerfurt) with dashed edges;
      crossings at traffic lights 0/16 (dashed crossing lines, red cycle crossings). Counts in the area: 94 zebra, 139
      marked/uncontrolled, 649 at signals. The rule stays; `ZebraTest`. Idea: draw the red cycle crossings (OSM
      `crossing:markings=dashes` + `bicycle=yes`) as red strips — they are the most visible marking at Hannover junctions.
- [x] **Built-in test drive** (Settings → Test drive, DE/EN/VI): Lavesstraße (Hbf) → B 3 Messeschnellweg (trunk road,
      with a roundabout at the Pferdeturm exit) → A 37 → A 7 → exit Großburgwedel → L 381 / L 383 → roundabout → rural
      L 383; 24 km. Routes and starts the demo by itself on the option that uses the A 7 and the roundabout
      (`MainViewModel.startTestDrive`, `TEST_FROM` / `TEST_TO`). Live check (`RouteRenderLiveTest` with those points):
      0 problems; advice: B 3 exit lane, keep-right lane past exits, fork lanes A 37 → A 7, A 7 exit lane, right-turn
      lane on the L 381.

## Done (v0.4.3, part 4, 2026-10-15) — owner's demo on the test drive
- [x] **Keep right on the Autobahn** (Rechtsfahrgebot, § 2 StVO; owner's screenshot: strip in the left lane of the A 7
      for 6 km before the exit): after a junction on a motorway / expressway the expected lane returns to the rightmost
      through lane — 300 m (motorway) / 200 m (expressway) after the route is on that road (after a merge, not on the
      ramp), gliding 150 m per lane — when ≥ 60 % of the stretch to the next junction is motorway / trunk and there is
      room for the next change. The next manoeuvre is then planned from the right lane (a left exit is advised in the
      lead distance only). In town (free lane choice, § 7 (3) StVO) the car keeps the lane the junction left it in,
      e.g. stays left for a coming left turn. `LaneState.keepRightFrom`, `LaneGuidance.update(roadKindAt = …)`,
      test `keepRightOnTheMotorwayBetweenJunctions`. Live trace of the test drive: right lane on the B 3 and the A 7.
- [x] **Autobahn recognised by its ref** ("A 7"): OSRM often omits the `motorway` class, so the A 7 counted as an
      expressway (`OsrmRoutingProvider`).
- [x] **"Only one lane on the Autobahn"**: correct for the carriageway the route is on (A 37 → A 7 branch, OSM way
      270470746, `lanes=1`), but the two-lane main carriageway 11 m beside it (way 238572934) was not drawn.
      Carriageways beside the route in the same direction (motorway … secondary, one-way, 4–35 m away, ≥ 60 m) are now
      found in the corridor data (`SpeedLimitProvider.sideRoads`, `Route.sideRoads`) and drawn as context lanes with
      the street-lane builder. Test drive: 15 side carriageways. `SideRoadsTest`.
- [x] `RouteRenderLiveTest`: `LANEWISE_PROFILE=1` prints OSM lanes, road kind and drawn lanes every 200 m.

## Done (v0.4.3, part 5, 2026-10-15) — test drive on the phone + 20-route lane audit
- [x] **Messy Autobahn exit** (A 37 → A 7): a motorway diverge was drawn like a town junction — stop line across the
      motorway, painted arrows, solid lines and the turn connector squeezing the lanes into the exit. Junctions on a
      motorway / expressway (by the road leading to them) and slight forks / exits on ramps without traffic lights
      are now "flowing" (`LaneRibbonBuilder.flowing`): no stop line, arrows, solid lines or connector.
- [x] **No strip after the last junction when it was a turn** (Berkhopstraße): the lane is unknown after a turn, so
      nothing was drawn; now the rightmost lane of the new road, up to the destination.
- [x] **Demo did not end at the destination** (stayed at "50 m" with an empty banner): the end of the simulation now
      ends the navigation like an arrival.
- [x] **A 37 "move right, then back left"** (owner's picture): the route is on a barrier-separated one-lane branch
      (OSM `lanes=1`, checked on the DOP20 photo) that widens to three lanes 150 m before the exit; OSRM shows
      `through | through | exit`. Moving into the exit lane is free (the road widens) but the strip sat in the "middle"
      lane and moved over in the lead distance. A lane entered for free now gets the strip where the road widens
      (`LaneRibbonBuilder.widensAt`). Also: on motorways a lane picture with more lanes than OSM counts on the
      carriageway along the last 400 m is trimmed to the usable ones (`trimToRoadLanes`, test).
- [x] **Double-node junctions** (audit route 16, Blumenauer Straße → left into Fössestraße): lanes at the first node
      that already point the way of the turn 20–50 m later are usable there (OSRM marks them "not for this route"),
      and a site ≤ 30 m after a turn that repeats its lanes is dropped (`mergeDoubleNodeJunctions`, test) — no more
      "move left" 30 m before the turn and right-left wobble after it.
- [x] **Lane audit over random routes** (`LaneAuditLiveTest`, `LANEWISE_LIVE=1 LANEWISE_AUDIT=1`, `LANEWISE_AUDIT_N`,
      `LANEWISE_AUDIT_SEED`, `LANEWISE_AUDIT_ROUTES="lat,lon>lat,lon;…"`): drives every route like the demo and flags
      opposite change instructions within 800 m (ZIGZAG), the strip moving one way and back between junctions
      (STRIP_ZIGZAG), late changes (LATE), keep-right violations on motorways (KEEP_RIGHT) and unusable recommended
      lanes (NOT_ALLOWED). First run, 20 routes in Region Hannover (seed 1, 8 with Autobahn, 312 km): 8 findings
      (5 STRIP_ZIGZAG, 2 LATE, 1 ZIGZAG). After the fixes above the 4 affected routes have 3 left (STRIP_ZIGZAG, see
      open items); no KEEP_RIGHT or NOT_ALLOWED findings.
- [x] **Debug demo from adb** (debug builds): `adb shell am start -S -n de.lanewise.app.debug/de.lanewise.MainActivity
      --es demo_from LAT,LON --es demo_to LAT,LON --ef demo_speed 4` — any route in the real app. Audit route 16 on the
      phone (the app's own top route, 20 km B 443 / B 3 / B 6 / Westschnellweg): right lane on the expressways, one
      change for the Westschnellweg exit, no zig-zag, 120 fps without frame gaps (longest repeat 2 frames).

## Done (v0.4.3, part 6, 2026-10-15)
- [x] **Audit leftovers fixed** (STRIP_ZIGZAG): (1) free moves into lanes that open now cost a little (0.05 per lane,
      `LanePlanner.Config.freeMoveCost` — far below a change, above the centring tie-break), so the strip no longer
      drifts into a widening lane and back (Hildesheimer Straße, Bornumer Straße); (2) a "straight" whose only usable
      lanes carry turn arrows only is the turn it really is (`turnsHiddenAsStraight`, Aegidientorplatz → Friedrichswall:
      `left | left | slight_right | slight_right`). Tests `noFreeDriftAndHiddenTurns`. Re-audit of the affected routes:
      0 findings; **fresh audit, 20 new random routes (seed 2, 4 with Autobahn, up to 28.7 km motorway): 0 findings**.
- [x] **Lane reports: share button** (was Next 3): Settings → Lane assist → "Lane reports: N saved" + "Share
      (GeoJSON)" — share sheet via FileProvider (`${applicationId}.files`, `res/xml/file_paths.xml`), DE/EN/VI.
- [x] **Settings without other apps' names**: "Dynamic lane map" / "Show all streets while driving" (no AMAP / Petal).
- [x] **Live preview pinned at the top of Settings, drawn by the real map** (`SettingsPreview`, tap the title to fold):
      its own small MapView + `MapController` drives a demo car in a loop along Vahrenwalder Straße (three lanes, bike lane
      — Radfahrstreifen then Schutzstreifen, in the chosen colour —,
      traffic lights, a zebra, a closed lane) with every setting the navigation uses — tilt, dynamic / fixed zoom, lane
      ribbons, opacity, fade, chevrons, 3D car / arrow and size, road name, poles, see-through buildings, theme — and the
      same camera framing (42 % above the car). The car keeps driving while settings change. (A first hand-drawn version
      did not match the real view — owner's screenshots — and was replaced.)
- [x] **Bike symbol on bike lanes** (owner's request, photo): the official Sinnbild Radfahrer (StVO, symbol of
      Zeichen 237) in white on every on-street bike lane — one in the middle of a short lane (< 160 m), every 100 m on a
      long one (placed by the whole bike lane span, not per 1 km road piece), lying flat on the road, pointing along it,
      1.3 m wide and stretched 1.8 x along the lane like the real paint (`LaneRibbonBuilder.bikeSymbolSpots`, layer
      `lane-bike-symbol`; the icon is drawn from the SVG paths). MapLibre scales icons by the screen density — the
      metre-to-icon scale accounts for it. The Settings preview road got a bike lane (Radfahrstreifen, then
      Schutzstreifen) to show it.

## Done (v0.4.4, part 1, 2026-10-15) — curve stress test, demo player, OSM lane counts
- [x] **Render stress audit** (`RenderAuditLiveTest`, opt-in: `LANEWISE_LIVE=1 LANEWISE_RENDER=1`, `LANEWISE_RENDER_N`,
      `_SEED`, `_ROUTES="lat,lon>lat,lon;…"`, `LANEWISE_RENDER_DUMP=1` writes the road around the worst spots as JSON,
      `tools/render_dump.py` draws them as PNG): the 20 curviest of many random town routes (up to 582°/km, 22 turns
      each, ~98 km), every road piece and the live highlight / car path every 50 m, flagging KINK (corner > 25° between
      short segments), WOBBLE (an S within 6 m), STEP (edge jumps sideways > 0.8 m), FOLD and CARPATH.
- [x] **Fixes from it** (same 20 routes, before → after: KINK 150 → 36, STEP 102 → 16, WOBBLE 118 → 51,
      CARPATH 19 → 17):
  - Lanes ending at a stop line (turn pockets on a straight pass) narrow across the junction box — a short funnel,
    ≥ 18 m, 9 m per lane — instead of a 6 m grid step that left a rectangular notch and 76° kinks in the edges. This was
    most of the "jagged corners" and the "4 lanes, then suddenly 1".
  - Turn connector: runs past the second node of the same junction (dual carriageway, < 35 m), and widens into the
    new road's lanes, so the new road's edges continue it without a sideways jump.
  - A road after a junction without an OSM lane count keeps the lanes that carry on (through lanes going straight,
    the turn lanes after a turn, within 1.5 km) instead of falling back to 1 lane in town (`carriedLanes`).
  - Edge offsets on tight curves are limited by the curvature (`limitedOffset`, no measurable effect alone).
- [x] **OSM lane count per direction** (owner: "OSM says 4 lanes, it is 2 in my direction"): `lanes` on a two-way
      street was already halved; now also `lanes:both_ways` (shared centre lane) and the other direction's
      `lanes:backward/forward` / `turn:lanes:*` are taken off (`lanes=3` + `lanes:backward=1` → 2, was 1), and
      `oneway=-1|true|1`, `junction=circular`, `oneway=no` on motorways are understood (`OsmTags.onewayDir`;
      `oneway=-1` streets were treated as two-way and halved). A `oneway=yes` carriageway tagged with the lanes of
      both carriageways is an OSM error the app cannot see — report / fix it in OSM; the aerial lane counts
      (`aerial_lanes.json`) override such counts where confident. Test `lanesInTravelDirection`.
- [x] **Demo player**: ▶ / ❚❚ in the DEMO badge pauses the demo car where it is (the lanes there can be studied); the
      progress bar at the bottom becomes a scrub bar during a demo — tap or drag (also backwards), the car jumps there on
      release, "x km / y km" shows while dragging (`DemoControl`, `NavigationEngine.seek`, DE/EN/VI).
- [x] **App icon**: road slab moved up and centred in the adaptive-icon safe zone (foreground + monochrome, `docs/icon.svg`).
- [ ] Phone check: run the worst audit routes with the debug demo intent, screenshots; the remaining WOBBLEs are at
      wide-junction connectors and the ALLOWED band leaving the road at some splits (dumps).

## Done (v0.4.4, part 2, 2026-10-15) — stops, smart stops, places with icons, settings redesign
- [x] **Swap start ↔ destination** (⇅ in the route preview): from the GPS position, the position becomes the destination;
      stops are driven in reverse order.
- [x] **Stops** ("Add stop" in the route preview, ✕ removes one): OSRM / TomTom route through them (`RouteRequest.via`);
      the end of every leg but the last is a "stop reached" step (`ManeuverType.WAYPOINT`, own voice phrase in DE/EN/VI,
      voice inventory updated — re-render the OmniVoice packs). Re-routing keeps the stops not reached yet. Offline
      routing has no stops ("Stops need online routing").
- [x] **Smart stops** (checkbox "Best option along the route", on by default): a name / brand ("Sparkasse", "Aldi") or a
      category word in DE/EN/VI ("Tankstelle", "Geldautomat", "Apotheke", "Supermarkt", "Ladestation" …) is searched in
      a corridor along the route (Overpass, ±600 m … 2 km by trip length) and the best 15 by distance off the route are
      ranked by the **real driving time they add** (OSRM `table`: time to the place + on to the next stop − the leg
      without it), each in the leg where it adds least. The list shows "+N min" and where along the route; ★ marks the
      best (`search/StopFinder.kt`, tests `StopFinderTest`, live `StopFinderLiveTest`: Hbf → Langenhagen, Sparkasse
      +0.3 min, Tankstelle +0.4 min).
- [x] **Fixed zoom up to 21** (was 19).
- [x] **Places with icons** (Material icons by Google, `material-icons-extended`; R8 keeps only the used ones in
      release — the debug APK is ~70 MB now): search results show their kind (restaurant, café, gym, shop, supermarket,
      bank, fuel, pharmacy, hotel, station … from Photon's `osm_key`/`osm_value`, `ui/PlaceIcons.kt`); a saved place shows
      its badge next to it (yellow ★ favourite, Home, Work or its own icon) and your name for it: "Am Muster 1 (Cousin)".
      Saving a place: Home / Work / Favourite, **own name, icon (24) and colour (9)**, live preview. Home / Work /
      Favourites chips with icons.
- [x] **Saved places as presets** in the start picker and the add-stop dialog until something is typed.
- [x] **Speed warning glow strength** slider (20–200 %, width and opacity) with a live sample in Settings.
- [x] **Settings redesign** (Material 3 / Android 16 style): icon tile per section, larger rounded cards, segmented
      buttons for language and theme (with icons), buttons with icons (test drive, share, import, download, voice test),
      back arrow. Icons in the route preview (swap, add / remove stop) and the demo badge (play / pause).
- [x] **Server load dot** (owner's request) next to "Add stop" and in the add-stop dialog: green free, yellow moderately
      busy, red busy, grey unreachable / offline (outlined while checking). Every 30 s in the route preview a tiny real
      request goes to each Overpass mirror and the routing server (`/status` pages proved unreliable) — answer < 1.5 s
      free, < 5 s moderate, slower / 429 / 503 / 504 busy; Overpass counts with its best mirror (the app fails over),
      overall is the worse of Overpass and routing. The probe also puts the healthiest mirror first for the next
      searches (`Overpass.preferred`), so a hanging main server no longer costs a timeout per search
      (`core/net/ServerHealth.kt`, test `ServerHealthTest`).
- [ ] Phone check of all of the above (built and unit-tested on the PC only).

## Done (v0.4.4, part 3, 2026-10-15) — checked on the owner's phone over adb
- [x] Verified on the phone: category icons in search results, Home / Work / Favourites chips, saved places as presets,
      swap / add stop, smart stop (Tankstelle → Aral +2 min), routing through the stop, demo pause and scrub bar (jumps
      to the dragged point, lane advice follows), server dot.
- [x] **Browse map at way splits**: where one OSM way simply continues into the next (node shared by two way ends),
      both now use the same joint direction (blended over 10 m) and their road edges taper over 15 m to the mean of the
      two, so edges, lane lines, surfaces and bike lanes meet without a step or kink; every lateral offset is mapped
      onto the tapered edges (`StreetLanes.Join`, test `linesMeetAtAWaySplit`). Reversed one-ways (`oneway=-1`) now get
      their bike lane and turn arrows on the side / end they are driven.
- [x] Floating buttons (layers, recenter, satellite badge) no longer cover the search results.
- [x] Route overview frames the route above the (taller) preview panel — the panel's height is measured.
- [x] Stops shown on the map as numbered lime pins.
- [x] Server dot: probed with a tiny spatial query (two mirrors answered an id lookup but failed every map query with
      HTTP 500); a timeout counts as busy (red), only no connection as unreachable (grey). The browse-map street fetch
      probes first too (every 2 min at most), so it starts on a working mirror.
- [x] Smart-stop search asks the two healthiest mirrors at once and takes the first answer (`Overpass.jsonRace`;
      ~40 s → ~25 s on a busy day).
- [ ] Render audit leftovers (dumps): the usable-lane band (ALLOWED) runs straight on into a fork branch for a few
      metres; a small hook where a left-turn connector meets the new road's outer edge; a dark bulge where a 3-lane
      road funnels to 1 right behind a junction.

## Done (v0.4.4, part 4, 2026-10-15) — Berliner Allee choke (owner's screenshot + Street View), test drive
- [x] **Lanes only from the ways the route drives on**: OSRM now returns the route's OSM node ids
      (`annotations=nodes`, `Route.osmNodes`); the corridor keeps each way's nodes (`out body geom`) and, wherever a
      way sharing a route segment is in reach, matches lanes / limits / bike lanes / `change:lanes` only from those —
      never from the Raschplatzhochstraße bridge above Berliner Allee, a road below or a parallel service road.
- [x] **No choke between close junctions without OSM counts**: Berliner Allee southbound, Königstraße → Schiffgraben is
      127 m; the fallback "next junction's lanes minus its turn pockets" (5 − 3 = 2) drew 5 → 2 → 5. Blocks shorter than
      150 m now keep the next junction's lanes; longer ones never drop below the lanes carried on from the last junction.
- [x] **No bulge after a turn**: the stop-line funnel only applies going straight through (at a turn the connector
      joins the new road), and within 30 m after a turn the new road's OSM count is used (the 25 m samples still held
      the old road's 5 lanes). Trace: 5 lanes to the Schiffgraben stop line, right-turn connector, 2 lanes on.
- [x] **Short lane-count dips filled** (`fillShortLaneDips`): < 120 m with fewer lanes than both neighbours and no
      junction inside → the smaller neighbour count (bridges / underpasses mapped as their own way). Test
      `shortLaneDipIsFilled`.
- [x] `RouteRenderLiveTest`: `LANEWISE_PROFILE_STEP` for a fine lane profile with positions; `drawnLaneWidth`.
- [x] **Test drive on the phone** (×4, city → B 3 → A 37 → A 7 → exit → L 383): lane advice consistent (keep right on
      the Autobahn, exit lanes in time, merge banners), no rendering breaks seen. The search box no longer keeps the
      demo destination ("B") after arriving.
- [x] Voice download failing silently in Settings (owner's video): the "not published yet" message only showed on the
      map screen - Settings now shows messages as a toast. The `voices-v1` release does not exist yet (404); the two
      English test packs were pushed over USB (`tools/voice_pack/04_push.py`) and show up in Settings.
- [x] The map's (i) attribution button sits top left in the Settings preview; the main map keeps MapLibre's default
      place (owner: top left there floated over the HUD).
- [x] **Lanes shifting and breaking on short routes** (owner's video): on routes under 8 km the OSM corridor was
      refetched on every fix (4 / s) - "8 km before the fetched stretch ends" was always true - and every answer
      replaced the route, so the road was rebuilt from slightly different data all the time (also while paused) and
      the public Overpass servers got hammered. Now: only when there is route left to fetch, one fetch at a time, 30 s
      pause after a failure. Checked on the phone: three frames while paused are identical.
- [x] **Voice packs: no pause between clips** ("Turn right onto … Celler Straße"): MediaPlayer `setNextMediaPlayer`
      left an audible gap on the S24. The clips are now decoded (MediaCodec), trimmed of silence and joined with a
      60 ms gap into one sound played by an AudioTrack on a worker thread (queue order kept, flush on urgent
      announcements; `PcmJoin`, test `PcmJoinTest`). Owner to listen on the phone.
- [x] **Test run** (Südstadt → city multi-lane junctions → Westschnellweg → roundabout Garbsen as a stop → A 2 →
      B 441 → Wunstorf, ×4, 45 screenshots): lanes stable, no chokes or bulges, junction arrows / connectors clean,
      roundabout and arrival fine. Found: on the A 2 the advice kept right *into a lane closed by roadworks*.
- [x] **Roadworks closures in the lane advice**: closures were only drawn. Now each closure is (1) a checkpoint for
      the planner just before it where only the open lanes are usable (`LaneClosure.site`, not drawn) and (2) a
      "no change into the closed lane" span like a solid line (`toChangeSpan`); keep right stops at the rightmost open
      lane, and a change into a lane that is closed waits for the last legal stretch (even a short one right before
      the exit). Phone: the car stays next to the closure for kilometres and moves over only at the exit. Tests
      `closedLaneIsNotEnteredBeforeTheClosureEnds`, `keepRightStopsAtAClosedLane`.
- [x] Debug demo intent: `--es demo_via "lat,lon;lat,lon"` for stops (e.g. through a roundabout).
- [x] **Aegidientorplatz: no arrows / stop line and the wrong lane before the light** (owner's screenshot, AMAP shown
      for comparison only - no AMAP data or code used): OSM has the lanes (`reverse;left|left|right|right|right`, the 2
      left lanes turn left), but OSRM drops lane data at this split multi-node junction - the route had no lane site
      there at all. Now the corridor returns the `turn:lanes` of the ways the route drives on (by OSM node ids), and
      `Route.withOsmTurnLanes` adds a lane site at the way's end where OSRM gave none within 40 m (turn from the route
      step, else from the bend of the route line) - arrows, stop line and lane choice work there. Also: a road whose
      lanes all turn the same way has no "pockets" (both left-turn lanes were treated as one lane further on, so the
      strip took the leftmost and shifted a lane at the next node). Trace: one left-turn lane, kept through both nodes
      into Hildesheimer Straße, 0 changes. Test `allTurnLanesAreNoPockets`. Phone check pending (phone disconnected).
- [ ] Lane choice by the next manoeuvre where it has no lane data (e.g. right turn after the left turn into
      Hildesheimer Straße: prefer the right one of the two left-turn lanes) - tie-break from the route steps.
- [x] **Safety rule: no guessed lane before a turn without lane data.** If the next turn / fork / exit / end of road
      (≤ 400 m, road wider than 1 lane) has no lane site, no lane is recommended (no strip, no change instruction) and
      the HUD says "No lane data here – follow the road markings" (DE/EN/VI) until the turn is passed
      (`NavSnapshot.lanesUnknown`). Before, the plan only knew later junctions and could point into a lane that cannot
      make the turn (Aegidientorplatz). Product goal saved: accuracy first, for drivers unfamiliar with the area.
- [x] **Junction audit on the official aerial photos** (`JunctionAuditLiveTest` + `tools/aerial_lanes/junction_audit.py`):
      every lane site and every signal of a route as DOP20 crop, driving direction up, plain next to the app's lanes,
      arrows, stop line and recommended lane. Südstadt → Garbsen (15 km): lane sites 18 (OSRM) → **33** with the OSM
      `turn:lanes` fallback; 15 signalised junctions still have no lane data (OSM has no `turn:lanes` there).
      Findings: lane counts and junction layout match the photos; the drawn road is often 2–4 m to the side of the real
      carriageway (OSM way not in the middle of the carriageway) and the stop line sits at the OSM junction node,
      a few metres past the painted one. **Arrow directions cannot be read from 20 cm photos** (arrows are a few white
      pixels) - they must come from OSM `turn:lanes` (or street-level imagery, e.g. Mapillary).
- [ ] Align the drawn road with the real carriageway (lane edges detected on DOP20 by the U-Net, or OSM `width` /
      `placement`) and put the stop line at the painted one (OSM `stop` nodes / `traffic_signals:direction`).
- [ ] Signals without lane data on the test routes: list them for the owner to map `turn:lanes` in OSM.
- [ ] **Reference screenshots from AMAP / Petal Maps** (later, owner's decision): at flagged junctions (audit, no lane
      data, owner reports) open AMAP / Petal on the phone and screenshot their lane view; disagreements are checked
      against DOP20 and reality - never copied (see `docs/DATA_SOURCES.md`). Note: Herschelstraße / Celler Straße is
      outdated in OSM and in AMAP / Petal alike.
- [ ] Phone check of the safety rule ("No lane data – follow the road markings") and of Aegidientorplatz (installed
      2026-10-15, not yet checked on the phone).
- [ ] Overpass was overloaded most of the day (mirrors HTTP 500 / 504, ~25 s answers): without corridor data the app
      falls back to OSRM lane sites only. A self-hosted Overpass (or caching corridor data per way id) would make lanes
      reliable.

## Done (v0.4.4, part 5, 2026-10-15) — hand-checked lane overrides (no paid HD maps)
- [x] **`lane_overrides.json`** (`osm/LaneOverrides.kt`, `docs/LANE_OVERRIDES.md`): GeoJSON like `lane_reports.geojson`
      (opens in JOSM / iD, property names are OSM tags), shipped in `assets/` plus the same file on the phone (wins on the
      same `id`, `adb push` + restart). Way patches per direction (`turn:lanes`, `lanes` / `effective_lanes`,
      `change:lanes`), junction patches by node (+ `from_way`), `lanes_unknown`, `osm_was`; `source` (evidence) required.
      Precedence: patch > OSM `turn:lanes` > aerial counts > heuristics.
- [x] Applied where OSM tags are read: corridor ways (`SpeedLimitProvider`, patched ways fetched by id), browse map
      (after the aerial counts), offline graph (baked in at download). `Route.withLaneOverrides` replaces OSRM's / OSM's
      lane sites within 30 m of a patched junction (OSRM's valid flags dropped, the route's turn kept).
- [x] Safety: invalid entries are dropped whole (logcat); a two-way way without `direction` is not patched; a patch
      whose lanes cannot make the route's turn, `lanes_unknown`, or OSM changed since `osm_was` → no lanes there
      (`Route.unknownLanesAt`): "No lane data – follow the road markings" from 400 m before, and nothing after it is
      planned until it is passed.
- [x] `LaneOverridesTest` (9 tests): validation, asymmetric 1 + 3 lanes on a `lanes=4` street, outdated patch, junction
      patch only on its arriving way, local file wins, a mis-tagged 4-lane junction (`left|left|through|right` in OSM,
      re-marked `left|through|through|right`: the old data advised the straight-only 2nd lane; patched: left lane, no
      zig-zag driving up to it), `lanes_unknown` in `NavigationEngine`, patch that cannot make the turn, missing junction.
- [ ] Fill the asset with real, checked patches (none yet: owner to pick junctions from lane reports / junction audit,
      e.g. Herschelstraße / Celler Straße — DOP20 + drive as evidence).
- [ ] Phone check: push a test patch, demo a route through it (lanes replaced; `lanes_unknown` shows the hint).
- [ ] Settings: show "N lane patches (M rejected)" next to the lane reports; share / edit on the phone.
- [x] Phone check (2026-10-17, test-only patch `lanes_unknown` on Aegidientorplatz way 4699114, demo ×0.5 from Schiffgraben):
      "No lane data here – follow the road markings" from ~850 m before the left turn into Hildesheimer Straße, no strip,
      lane bar back after the junction. Test patch removed from the phone. The OSM lanes there
      (`reverse;left|left|right|right|right`) were not changed: DOP20 shows ~5 lanes, but the arrows cannot be read at
      20 cm - real patches need the owner's evidence. `JunctionAuditLiveTest` takes `LANEWISE_OVERRIDES=<file>`.
- [x] **New app icon** (owner's `docs/icon.svg`, 2026-10-15): `ic_launcher_foreground` / `_monochrome` regenerated from
      the SVG (tile mapped onto the visible 72 dp, dashes as segments).

## Done (v0.4.4, part 6, 2026-10-17)
- [x] **U-turn from the through lane** (owner's demo, end of Hildesheimer Straße): OSRM reports the junction as two nodes
      21 m apart - lanes `left | through | through;right` with the *through* lane flagged at the first, the U-turn at the
      second. `LaneDir.permits` never let a `left` lane make a U-turn, so the first node kept only the through lane and
      the plan asked "move one lane left" 20 m before the U-turn. Now a U-turn (Wenden) is made from the left-turn lane;
      a "straight" site at the turn's own node is re-targeted to the turn (`attachTurnsToLaneSites`); and a turn whose
      nearby lane data has no lane that can make it shows "No lane data" instead of falling back to the provider's flags
      (`NavigationEngine.unknownTurnAhead`, now also for U-turns of any step type). Trace: left lane from 400 m before,
      no change. Tests `uTurnUsesTheLeftTurnLaneNotTheThroughLane`, `uTurnWithoutAUsableLaneIsUnknown`.
- [x] **Mobilithek DATEX II client** (`traffic/MobilithekClient.kt`, replaces `MobilithekDatexSource`, which used
      Android's XML parser and a blocking `execute()` on the IO pool): client certificate (PKCS#12, cached client),
      `Http.fetchBytes` (OkHttp's threads, cancellable), SAX parser without DTDs, v2 / v3 / SOAP / gzip, validity,
      TPEG from → to or GML `posList` as the line. Per-lane closures only from unambiguous lane names
      (`TrafficIncident.laneOpen` → `LaneClosure`, like Autobahn GmbH roadworks); numbered lanes or a restricted-lane
      count only → `TrafficIncident.laneRestrictionUnknown` → "No lane data" from 400 m before and along it
      (`LaneClosure.unknownAlong`). `MobilithekClientTest` (8 tests, hand-written samples).
- [ ] Mobilithek with the owner's real subscription: check a real payload (namespaces, location types, lane names used
      by VMZ NI / Stadt Hannover) and adjust; if the feed documents its lane numbering, map `lane1…` too.
- [x] **Turn pockets cut at the stop line** (owner's screenshot, Celler Straße straight on at Herschelstraße,
      `left | through | through`, 2 lanes after): going straight, the left-turn lane narrowed across the junction into the
      through lanes (the v0.4.4 funnel). Now turn pockets end at the stop line; only through lanes that end after the
      junction still narrow in the short funnel (`LaneRibbonBuilder.taper`, `cutAt`). Test `turnPocketIsCutAtTheStopLine`;
      checked on the phone (demo ×0.25). OSM there: way 166391411 has `lanes=2` but 3 `turn:lanes`, and 143948254 after
      the junction `lanes=1` - check against DOP20 / reality (owner's screenshot shows 2 lanes after).
- [x] **In town: into the turn lane early** (owner's drawing: two straight-only lanes, the next turn 500 m ahead, the app
      kept the car in the straight lane on the wrong side until ~200 m before): a change was always advised "as late as
      comfortable" (150 m + 80 m per lane in town). In town (free lane choice, § 7 (3) StVO) it now starts right after
      the previous junction (30 m), at most 600 m before the turn, in the first dashed stretch, and only across lanes
      that already exist there - a turn pocket is entered as it opens (`LaneGuidance.earlyStart`, `firstChange`; the strip
      moves at the same point in `LaneRibbonBuilder.keyframes`). Autobahn, expressways and rural roads keep the lead
      distance (keep right, § 2 StVO). Test `inTownGetIntoTheTurnLaneEarly`. Lane audit: see below.
- [x] **Street-level imagery for the junction audit** (owner's strategy, 2026-10-17): Mapillary primary, Panoramax /
      KartaView secondary, with `lane_overrides.json` and DOP20 (`docs/DATA_SOURCES.md`). `tools/streetview/streetview_audit.py`
      (stdlib only) takes the junction audit's `j_*.json` (or `--point`), finds photos ≤ 45 m around a point 30 m before
      each stop line looking the driving direction (or 360°), newest first, plus Mapillary's detected arrows / lane signs
      as hints, and writes `tools/streetview/work/streetview.html` next to the DOP20 crops. Photos are evidence, never
      app data. First run (Schiffgraben → Hildesheimer Straße): Panoramax photos at 5 of 7 junctions, but from a bike /
      sidewalk (arrows not visible); Mapillary needs the owner's token.
- [ ] Owner: create a Mapillary client token (mapillary.com/dashboard/developers), run the street-level audit on the
      test routes, and write the first real `lane_overrides.json` entries / OSM fixes from it.
- [x] **Audit fixes (seed 1)**: (1) Langenforther Straße STRIP_ZIGZAG - three OSRM sites with the same
      `through | slight_right` within 120 m were read differently (first "full length", then a right pocket): a
      repeated lane picture with the lanes counted all the way now keeps the previous reading (`markFullLengthLanes`,
      test `repeatedLanePictureIsReadTheSameWay`). (2) Nenndorfer Landstraße LATE - a `left | through` site added from
      OSM 43 m before the left turn's node missed the two-node-junction clean-up (it ran before OSM sites were added):
      `Route.withOsmTurnLanes` now merges again (test `siteAddedFromOsmBeforeATurnIsPartOfTheJunction`); and a change
      before a solid line uses the last dashed stretch of ≥ 50 m, not a 20 m one right before it
      (`LaneGuidance.MIN_WINDOW`). (3) Bornumer Straße STRIP_ZIGZAG - with the early change in town the assumed lane
      stayed the old one until the next junction; close to it the plan flipped to "change after the junction" (right,
      left, right). An advised change now counts as made once driven through (60 m per lane in town, 100 rural, 150
      trunk, 200 motorway; `LaneGuidance.changeDistance`). (4) Bückeburger Allee STRIP_ZIGZAG - a joining on-ramp lane
      (`merge_to_left`) counted as a through lane: merge-only edge lanes are pockets now and never recommended while
      another lane works (test `accelerationLaneIsNotAThroughLane`). Route 6 re-audit: 0 findings.
- [x] **First real lane patch**: Celler Straße northbound at Herschelstraße is `left | through` (owner, local driver;
      OSM `lanes=2` agrees), OSM `turn:lanes` had 3 lanes and AMAP shows 3 too - `assets/lane_overrides.json`
      (`osm_was` set), test `shippedOverridesAreValid`. Owner to fix `turn:lanes` on way 166391411 in OSM.
- [x] **Large-junction survey** (`tools/streetview/big_junctions.py`): Region Hannover + Celle, 2712 ways with
      `turn:lanes` → 64 junctions / 104 approaches with 5+ lanes (or 4 with left, straight and right); flags LANES_MISMATCH
      4, UNMARKED 5, SHORT_WAY 16, OLD_PHOTOS 14, NO_PHOTOS 3, DETECTION_HINT 1; HTML + GeoJSON work list.
- [x] **AMAP as last resort** (owner, 2026-10-17): screenshots may be studied; where OSM / photos / DOP20 are unclear the
      lanes read from them go into `lane_overrides.json` as text ("AMAP screenshot <date> (unconfirmed)"); screenshots
      are never committed, nothing goes into OSM. AMAP can be searched by name over adb (not by coordinates).
- [x] **App icon updated again** from the owner's new `docs/icon.svg` (larger road slab).
- [ ] Owner's screenshot: ETA "2 h 27 min" for 20 km while the demo was paused - check how the ETA is computed at 0 km/h.
- [x] **Lane topology like HERE's lane model** (owner's link, docs.here.com "model-lane"): HERE stores explicit lane
      connectivity (lane inputs → outputs at "accessors"), a transition status per lane (forming / ending) and boundary
      traversal (= our `change:lanes`). Now `LaneSite.connectivity` (`lane/LaneConnectivity.kt`): per lane the lanes it
      leads into after the junction, or "does not continue". Sources: `lane_overrides.json` `connectivity` (junction
      patch with `osm_node` + `from_way` + `to_way`) > OSM `type=connectivity` relations (corridor Overpass query:
      `rel(bw.a)[type=connectivity]` + member ways as node lists, matched from / via node / to by the route's OSM nodes,
      `osm/OsmConnectivity.kt`) > heuristics. Used by `LaneSite.allowed`, `LanePlanner.entryLateral` / `transition`
      (only connected lanes can be entered), `markFullLengthLanes`, and the ribbon (`carriedLanes`; lanes that do not
      continue end at the stop line, merging lanes narrow in the funnel). A value that contradicts the arrows / lane
      counts, or two OSM relations that differ → the junction's lanes are dropped (`Route.unknownLanesAt`, "No lane
      data"). Tests `LaneConnectivityTest` (9). Not yet: offline graph (`OfflineGraphBuilder`) and HERE's per-lane
      forming status; `bw` lanes and via ways are skipped.
- [ ] Lane connectivity on real junctions: run the junction audit on a route through junctions with
      `type=connectivity` relations in Hannover (few are mapped) and check the printed connectivity against DOP20;
      the owner may add relations / `connectivity` patches where the heuristics were wrong (Celler Straße at
      Herschelstraße: `left|through|through` → 2 lanes, `2:1|3:2`, once the lane count after it is checked).
- [ ] TomTom Navigation SDK for Android (owner's link, API reference 0.3.1236): proprietary SDK with lane guidance
      (needs a key and its licence terms); evaluate as a lane *reference* like `ProviderCompareLiveTest`, not as data
      to copy. Earlier finding: TomTom's lanes were simpler than OSM's on the test route.

## Done (v0.4.4, part 7, 2026-10-18) — marking plans, override schema v2, AMAP rechecks
- **Override schema v2** (`osm/LaneOverrides.kt`, `docs/MARKING_PLANS.md` section 5): `stop_line:lanes`,
  `change_spans` (solid lines over part of the approach, measured from the stop line), `pocket_start_m`, `width:lanes`,
  `source_plan` + `built_confirmed` (a plan without dated proof is rejected), `draft` (always rejected). Spans replace
  OSM's change spans where they lie (`ChangeSpan.overlay`), the pocket start becomes `LaneSite.pocketLength` for the
  ribbon. 3 new tests (121 pass, 7 skipped).
- **`tools/marking_plans/`** (README there): inspect / extract / georef / draft / review for city Neumarkierungspläne.
  Sample 15-2330-2024 Anlage 2 (Hannoversche Straße): legend read, scale 1:254 from the dimension labels, automatic
  georeference from 139 building corners (RMS 0.44 m, checked on DOP20), 3 draft entries with every judgement left as
  `CHECK` (arrow meaning, direction, built or not). Nothing shipped from it yet.
- Google Maps / satellite (also via third-party tile viewers): not used as a source, not even screenshots (ToS).
- **AMAP rechecks** at maximum zoom (arrows often appear only at the 5 m / 2 m scale; a street label can hide one),
  `tools/streetview/work/amap_check.json`:
  - match: Brühlstraße NW-bound at Königsworther Platz `left|through|through|through|right`; Brühlstraße SE-bound at
    Otto-Brenner-Straße `left|left|through|through|through;right`; Hildesheimer Straße N-bound at Willmerstraße again.
  - differs (check DOP20 / Mapillary before any patch): Hildesheimer Straße S-bound at Willmerstraße (AMAP 4 lanes +
    a slip lane, OSM 5); Brühlstraße NW-bound at Otto-Brenner-Straße (AMAP 3 lanes `?|through|through;right`, OSM 4);
    Erich-Panitz-Straße S-bound, Laatzen (AMAP 2 lanes without arrows, OSM 4); Thaerplatz, Celle (AMAP
    `through|slight_right`, OSM `left|left|through;right|none` with lanes=2 - arm identity not yet sure).
  - AMAP without arrows even at maximum zoom: Willmerstraße W-bound (only a centre line), Hannoversche Heerstraße
    NE-bound at Mondhagen (Celle).
  - Round 2 (owner: also note how many lanes each direction has - AMAP's "4 lanes" can be 3 + 1): `amap_check.json`
    now has `amap_lanes` (this direction | opposite) and `osm_lanes` (forward + backward). Match: Hamelner Chaussee
    N / S (3 + slip lane = OSM 4), Karl-Wiechert-Allee N (3 + slip), Berliner Allee SE, Vahrenwalder Straße N / S,
    Lavesallee, Langenhagener Straße W, Fuhrberger Straße (count only). Differs: Berenbosteler Straße, Garbsen (AMAP
    `left|left|through`, 3 SW + 1 NE; OSM 4 + 1 with a right lane), Ritter-Brüning-Straße (AMAP 3 lanes without the
    left lane OSM has; deduction: Lavesallee feeds 4 straight-on lanes into it, so a lane must end in between),
    Karl-Wiechert-Allee S (AMAP 2 + slip at the stop line, OSM 4). No arrows at max zoom: Rudolf-von-Bennigsen-Ufer,
    Fuhrberger Straße. Lavesallee / Friederikenplatz: layout differs in shape, needs DOP20. Burgdorfer Straße (Lehrte)
    not found in AMAP. AMAP search now asks for a login (guest limit) - not signed in; the rest of the survey pans
    the map by hand. Round 3 (Vahrenwalder Straße, both carriageways at junctions 37, 49, 51, 52, 53): all match OSM
    (AMAP draws `left`/`right` where OSM has `sharp_left`/`sharp_right`; at 37 S the 5th OSM lane is the slip road)
    except junction 52 N-bound (Büttnerstraße): AMAP `left|through|through|through|right` (5 lanes), OSM 4 with
    `through;sharp_right` - check DOP20. AMAP draws 3 lanes north of that crossing, matching its 3 through lanes.
    Round 4 (2026-10-18/19, the rest of the plan; all 64 junctions now have a reading, 90 rows):
    - match / equivalent: 24, 25, 26, 27, 30, 31 (both arms), 32 (3 arms), 33 (both), 35 (only the right-lane arrow
      shown), 36 SE, 38 (both), 39, 42, 43, 47 (3 arms), 54 (no junction; same lanes as 55), 55 (both; SE right lane
      = slip lane), 56 (right turn via a slip road), 57, 59, 61, 63 (skewed: AMAP `slight_left` = OSM `left`), 64.
    - differs (check DOP20 / Mapillary before any patch): 60 Bothfelder Straße NW (AMAP 3 lanes
      `through|through|through;right`, OSM 4 with a left lane); 58 Hannoversche Straße NE, Lahe (AMAP
      `left|left|through`, OSM 4 `left|through|through|through;right`); 46 Berliner Allee W at Lister Meile (AMAP
      `left|through|through`, OSM `left|left|through|right`); 36 Hamburger Allee NW (AMAP's 5th arrow
      `through;right`, OSM `right`).
    - no arrows at max zoom: 28 Friedrichswall E (count = OSM 5), 29 Hans-Böckler-Allee E at Pferdeturm, 44 Am
      Pferdeturm W (count = OSM 4), 40 / 41 B 6 Gleidingen (3 + slip lane = OSM 4); with fewer lanes than OSM: 48
      Karl-Wiechert-Allee SE at Nobelring (AMAP 2, OSM 4), 50 Karl-Wiechert-Allee W (3 vs 4), 62 Bothfelder Straße E
      (3 vs 4).
    - no junction in AMAP: 23 Flughafenstraße / Petzelstraße, 34 inner segment of Königsworther Platz.
    Lane-count disagreements cluster on Karl-Wiechert-Allee (18, 45, 48, 50) and Bothfelder Straße (60, 62): check
    them on DOP20 first. Nothing was written to `lane_overrides.json`.
  - **Skill `phoneapp-survey`** (`.claude/skills/phoneapp-survey/`, helpers `tools/phoneapp_survey/`: `shot.sh`,
    `zoom_in.sh`, `zoom_out.sh`, `pan.sh`, `goto.sh`, `record.py`; screenshots only in the git-ignored `work/`), so
    the next survey does not rebuild the adb helpers. They send touch input only (`adb shell input`).
- **Drive timer** (owner's request 2026-10-18): stopwatch of the time driven since navigation started, next to the
  remaining time in the bottom bar (kept across re-routes; Settings → Lane assist → "Driving time stopwatch", on by
  default).
- **Lane box countdown** (owner's request): the lane box under the manoeuvre banner fills like the banner and empties
  towards the junction (last 400 m in town, 1 / 1.5 km on trunk roads / motorways). Settings → "Countdown in the lane
  box", on by default.
- **Reroute after losing the internet** (owner's test drive 2026-10-18: started on Wi-Fi, no internet while driving,
  missed turn - no new route, not even after switching mobile data on; only ending the route and searching again
  helped): `MainViewModel.reroute` now runs one attempt at a time with a 20 s limit per provider (a hung request no
  longer blocks), asks again without the GPS heading if a provider fails with it, tells the driver once ("no new route
  yet - trying again when there is a connection", DE / EN / VI) instead of saying "recalculating" every 10 s, and logs
  failures (`adb logcat -s LaneWise`). A network callback drops the HTTP connection pool on every network change (its
  connections belonged to the Wi-Fi) and reroutes at once when a validated network comes back while off the route.
  Not yet checked on a real drive.

## Video review — still open
- [ ] Owner: check the 3D car (size, colours, behaviour in tunnels / under bridges) and the automatic theme on the phone.
- [ ] Owner: check the car overlay (size vs. road at different zooms, tilt changes, pan away and recenter) and the
      corners (v0.4.3).

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

- [x] **Browse / standby map: lines broken at OSM way splits** (owner's screenshot, 2026-10-15, Herschelstraße /
      Celler Straße) — fixed in v0.4.4 part 3.

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
- **Merging lanes (owner's next task)**: cross-reference with AMAP / DOP20 the places where lanes end without a junction
  (3 → 2, 2 → 1: merge arrows Z 297.1, lane-end tapers). Find them from OSM (`lanes` dropping between consecutive ways
  of the same road without a junction node, `turn:lanes` with `merge_to_left` / `merge_to_right`), read AMAP at max
  zoom, and check that LaneWise moves the driver out of the ending lane early (planner `laneCountAt`, ribbon taper).
0. **Marking plans**: fill the three sample drafts by hand (arrows from the plan, `built_confirmed` from a dated
   photo / DOP20 - the DOP20 crop looks re-marked already), then more plans from the council papers; draw
   `stop_line:lanes` per lane in the ribbon; bake v2 spans into the offline graph.
1. **Solid lane lines from aerial imagery** (owner's request 2026-10-14): draw solid dividers (and plan with them)
   where OSM has no `change:lanes`, using the DOP20 photos as reference — the aerial pipeline already measures
   solid vs dashed tracks per OSM way (`tools/aerial_lanes`); export them as `change:lanes`-like spans in
   `aerial_lanes.json`, apply them in `AerialLanes` for the route corridor and the browse map, and check how well
   `change:lanes` is tagged in Hannover on the phone first.
2. In-app lane corrections applied over OSM (reports are saved already, see v0.4), and/or fixing OSM directly —
   wrong lane counts found so far are OSM tagging errors, not imagery (DOP20 flown 2025-03-06).
3. ~~Lane reports: a share button in Settings~~ — done (v0.4.3 part 6).
4. Lane-count continuity between sites from OSM `lanes`/`lanes:forward` instead of the next-site approximation
   (partly done: pocket length by road kind; lanes carried on after a junction, v0.4.4).
5. Owner's lane drawing ("coMap" sketch) was referenced but not attached in the first chat —
   compare it with `docs/LANE_ALGORITHM.md` once shared and add it as a unit test scenario.
6. Android Auto / car display.
7. HERE Routing v8 as an additional provider; TomTom flow tiles as a traffic overlay.
8. Optional: install `jadx-headless-mcp` for REA and decompile the Petal `hdmi` beans / AMAP lane messages.
