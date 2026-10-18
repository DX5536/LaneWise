# LaneWise — session hand-off

Read this first when resuming work in a new chat.

## Copy-paste prompt for a new chat

> You are continuing work on **LaneWise**, my Android navigation app (Kotlin, Jetpack Compose, MapLibre) for
> Germany with focus on Hannover/Niedersachsen. Before doing anything, read `CLAUDE.md`, `README.md` and
> everything in `docs/` (especially `docs/ROADMAP.md` for status, `docs/LANE_ALGORITHM.md`, `docs/ARCHITECTURE.md`
> and `docs/DATA_SOURCES.md`). Build locally with `.\build-local.ps1` (add `-Install` to put it on my USB-connected
> phone); the build also runs the unit tests. Never commit or push — I review and push myself. Keep the app's
> three languages (DE/EN/VI) in sync when adding UI text. **Accuracy is the top priority: a wrong lane hint can cause
> an accident.** The app is for drivers unfamiliar with Niedersachsen, to plan lanes ahead with as few lane changes as
> possible. Never present a guessed lane as certain; check lane-logic changes against real junctions (junction audit
> on the aerial photos, my screenshots). For street-level checks use open imagery in this order: Mapillary (primary,
> `MAPILLARY_TOKEN`), then Panoramax / KartaView (`tools/streetview/streetview_audit.py`); fixes go into OSM or
> `app/src/main/assets/lane_overrides.json` with the photo as source, never imported automatically. Official Hannover marking plans
> (Neumarkierungspläne) are the most precise geometry source once confirmed built; they enter only via
> `lane_overrides.json` (`docs/MARKING_PLANS.md`). Precedence of lane
> data: `lane_overrides.json` > OSM `turn:lanes` > `aerial_lanes.json` > heuristics. AMAP / Petal Maps may only be used
> as screenshot references; as a last resort, where OSM / photos / DOP20 are unclear, the lanes read from an AMAP
> screenshot may go into `lane_overrides.json` as text (never the screenshot itself, never OSM; see `docs/DATA_SOURCES.md`). Never write my real addresses. When done, update `docs/ROADMAP.md`. My next request is:

## AMAP survey (copy-paste prompt to continue)

> Continue the AMAP cross-check of big junctions. Read `docs/DATA_SOURCES.md` (AMAP rules) and `docs/ROADMAP.md`
> (v0.4.4 parts 6-7). Plan: `tools/streetview/work/amap_plan.json` (64 junctions, OSM ways + arrows), results so far:
> `tools/streetview/work/amap_check.json` (`amap`, `amap_lanes` = this direction | opposite, `osm_lanes` = forward +
> backward, `verdict`), OSM lane tags per way in `osm_lanes.json`. AMAP is open on my phone (adb). Way of working:
> search a nearby place, open it, leave with the upper-left back button (not the system back) until the home map,
> then pan / zoom by hand (double-tap on empty map; double-tap + drag down zooms out) to the 5 m scale bar or closer -
> arrows often only show at 2 m / 1 m, a street label can hide one; "no arrows" only after maximum zoom along the whole
> approach. AMAP search may ask for a login: never sign in, pan the map instead. Note the lane count per direction
> (AMAP's "4 lanes" can be 3 + 1) and use logic (3 straight lanes before a light → 3 lanes after it until a merge).
> Never write AMAP screenshots anywhere or my own location; only text readings. Differences go to DOP20 / Mapillary
> first; AMAP only as last resort into `lane_overrides.json`. Update `docs/ROADMAP.md` when done. Next after that: the
> merging-lanes check (ROADMAP "Next").

## Project
Android (Kotlin/Compose/MapLibre) driving app for Germany, focus Region Hannover, centred on
**3D lane assist that minimises lane changes**. Owner: Hai Lam Dang (GitHub DX5536). UI and voice are German-first.
Goal: help **new / unfamiliar drivers** in Niedersachsen pick the right lane early, so the next turn never needs several
lane changes (slow and dangerous with impatient drivers) — better than AMAP / Petal 3D lane guidance. **Safety first:**
no lane hint is better than a wrong one. Personal project: not sold, not released on Google Play.

## Owner's requirements (from the first chat)
- Android, built by GitHub Actions (`.github/workflows/build.yml`).
- 3D lane assist + advanced lane logic avoiding lane changes; setting "Spurwechsel so weit wie möglich vermeiden".
- Germany only; official German data (Autobahn API, Mobilithek, TomTom/HERE); focus Niedersachsen/Hannover.
- Dark/light mode, speed limit, QoL features.
- Light-red screen border for over/under speed; configurable offsets in Settings.
- UI inspired by Petal Maps + AMAP but its own style; good contrast. Palette: violet `#6C4CF5` + lime `#B8F33A` on graphite.
- Offline routing with satellites (GNSS) when no internet.
- Petal/AMAP lane data in Germany: mostly correct (big junctions especially), some lanes outdated. Screenshots of the
  running apps are allowed as a reference to spot our mistakes (owner's decision, 2026-10-15); since 2026-10-17 AMAP's
  lanes may be used as a last resort where OSM / street-level photos / DOP20 are unclear - written as text into
  `lane_overrides.json`; AMAP screenshots are never committed / uploaded, nothing goes into OSM (`docs/DATA_SOURCES.md`).
  The most up-to-date source is the official aerial photos (DOP20).
- App icon: option **P** (violet tile, extruded converging road slab, N-style lime lane surface shifting lanes) — `app/src/main/res/drawable/ic_launcher_foreground.xml`, preview `docs/icon.svg`.
- Owner mentioned a lane drawing ("coMap") that was never attached — ask for it.

## Where things are
See `docs/ARCHITECTURE.md`. Core lane logic: `app/src/main/java/de/lanewise/lane/`. Tests:
`app/src/test/java/de/lanewise/lane/LanePlannerTest.kt`.

## Conventions
- Pure-Kotlin logic (lane/, core/, nav/NavigationEngine, routing parsing) stays Android-free so it is unit-testable.
- No code/assets from the reverse-engineered APKs. APKs/APKMs are git-ignored.
- Map styles are generated by `tools/gen_map_styles.py` (CI fails if out of date). LF line endings (`.gitattributes`).
- No AI attribution in git: never add `Co-Authored-By: Claude`, `Claude-Session:` or "Generated with Claude Code" lines to commit messages or PR descriptions.
- Versions: AGP 8.13.2, Gradle 8.14.3, Kotlin 2.2.21, Compose BOM 2026.02.01, MapLibre 11.13.5, compileSdk 36, minSdk 26.

## Tools
- Marking plans: `tools/marking_plans` (PyMuPDF; city Neumarkierungspläne → draft `lane_overrides.json` v2 entries,
  `docs/MARKING_PLANS.md`). Google Maps / satellite is never used as a source, not even screenshots (ToS).
- AMAP over adb (skill `phoneapp-survey`, helpers `tools/phoneapp_survey/`: shot / zoom / pan): search, leave with the upper-left back button, zoom in by hand to the 5 m scale or closer (arrows
  often only at 2 m / 1 m); "no arrows" only after maximum zoom.
- Python tools: `tools/aerial_lanes` (DOP20 + U-Net, venv `.venv` with torch cu124), `tools/voice_pack`
  (OmniVoice, venv `.venv` with torch 2.8 cu128) and `tools/streetview` (Mapillary / Panoramax / KartaView photos per
  junction, standard library only); see their READMEs. GPU: RTX 3070 Ti 8 GB.
- REA MCP server is installed (reverse engineering). JADX provider is NOT configured (needs `REA_JADX_MCP_JAR`).
- Local build: `.\build-local.ps1` (JDK 21 at `C:\Program Files\Java\jdk-21`, SDK at `%LOCALAPPDATA%\Android\Sdk`,
  isolated `GRADLE_USER_HOME=%LOCALAPPDATA%\LaneWise\gradle-home` because the shared `~/.gradle` 8.14.5 cache is
  corrupt). Do NOT use Android Studio's JBR (Java 25) for Gradle 8.14. Prefer local builds; CI is slow.
- Never push for the owner — they review and push themselves.

## Status
See `docs/ROADMAP.md` (v0.4.4, 2026-10-15). Builds locally, runs on the owner's phone (Samsung S24 Ultra,
Android 16, package `de.lanewise.app.debug`); the CI build/release APK is the owner's job.

## Things learned (read before changing these areas)
- MapLibre 11.13.5 `TileSet.setBounds(Float, Float, Float, Float)` recurses forever: use the `Array<Float>` overload.
- The app sandbox (Claude desktop) redirects `%LOCALAPPDATA%` writes; Python venvs/data live in git-ignored
  `tools/*/.venv` and `tools/*/work`.
- Owner's test route: home in Hannover → a restaurant in Langenhagen (coordinates in `RouteRenderLiveTest`), 2nd route option.
- Test drive for all road types: Settings → Test drive (or `LANEWISE_FROM=52.37390,9.74662 LANEWISE_TO=52.50796,9.81688
  LANEWISE_ALT=0` with `RouteRenderLiveTest`): city, B 3 trunk road, A 37 / A 7, exit, roundabouts, rural L 383.
- The phone can be driven over adb (screen 1440×3120; `adb shell input tap`, `uiautomator dump`, `screenrecord`); the
  owner allowed this for checking bugs. In Git Bash set `MSYS_NO_PATHCONV=1` for `/sdcard/...` paths.
- Never block coroutine IO threads with OkHttp `execute()`: use `Http.fetch` (async, cancellable).
- Lane logic changes: run the audit (`LANEWISE_LIVE=1 LANEWISE_AUDIT=1 gradlew testDebugUnitTest --tests '*LaneAuditLiveTest*'`,
  ~40 min for 20 routes, run in the background) and check a route in the app with the debug demo intent
  (`am start ... --es demo_from LAT,LON --es demo_to LAT,LON --ef demo_speed 4`, see ROADMAP v0.4.3 part 5).
- Privacy: never write the owner's real addresses or places (start/destination) in docs, code, tests or UI text —
  use placeholders like "Musterstraße 1" / "Musterweg 3"; street names of lane bugs (not house numbers) are fine.
  `LANEWISE_LIVE=1 gradlew testDebugUnitTest --tests '*RouteRenderLiveTest*'` checks it (Overpass may time out;
  `LANEWISE_ALT=0|1` picks the route option, `LANEWISE_TRACE=1` prints the lane advice every 20 m).
- Wrong lane counts so far were OSM tagging errors (way 41589961 `lanes=2`, Celler Straße way
  1053732103), not the imagery (DOP20 flown 2025-03-06). The owner fixes OSM; never scrape Google (ToS) or copy
  GPL code (e.g. Vela) into LaneWise.
- Owner prefers: only the route's own lanes while driving by default (Petal-style streets is an opt-in setting),
  AMAP-style camera, English default UI. Install APKs on the phone only when the owner asks.
- OSRM drops lane data at many complex city junctions (Aegidientorplatz): the app adds lane sites from OSM `turn:lanes`
  of the ways the route drives on (`Route.withOsmTurnLanes`, OSM node ids from OSRM `annotations=nodes`). A turn with
  still no lane data shows "No lane data – follow the road markings" instead of a guessed lane (`NavSnapshot.lanesUnknown`).
- Junction audit (check lane logic against reality): `LANEWISE_LIVE=1 LANEWISE_JUNCTIONS=1 LANEWISE_FROM=lat,lon
  LANEWISE_TO=lat,lon [LANEWISE_VIA="lat,lon;…"] gradlew testDebugUnitTest --tests '*JunctionAuditLiveTest*'`, then
  `tools/aerial_lanes/.venv/Scripts/python.exe -I tools/aerial_lanes/junction_audit.py` → DOP20 crop per junction
  (plain vs. app's lanes/arrows/recommended lane) in `app/build/reports/lanewise/junctions/`. DOP20 (20 cm) shows lane
  lines and counts, but painted arrow directions are too small to read — arrows come from OSM `turn:lanes`.
- Debug demo with stops: `--es demo_via "lat,lon;lat,lon"`. `LANEWISE_PROFILE=1 LANEWISE_PROFILE_STEP=10` in
  `RouteRenderLiveTest` prints the drawn lane count every 10 m.
- Outdated everywhere: the Herschelstraße / Celler Straße junction is wrong in LaneWise (OSM) and in AMAP / Petal alike
  — agreement with AMAP is no proof; the aerial photo / reality decides.
- Public Overpass mirrors are often overloaded (HTTP 500 / 504, ~25 s): the app probes them (`ServerHealth`, dot in
  "Add stop") and tries the healthiest first; without corridor data only OSRM's lanes exist.
