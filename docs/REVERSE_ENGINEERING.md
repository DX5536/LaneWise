# Reverse-engineering notes: Petal Maps & AMAP lane guidance

Goal: understand *how* two production apps implement 3D lane-level guidance, to inform a
clean-room design for LaneWise. **No code, images or data files from either app are used in
LaneWise.** Only architecture, parameter choices and UX patterns were studied. The APKs
themselves must never be committed (see `.gitignore`).

> Note from the project owner: Petal Maps and AMAP lane data for Germany is badly outdated.
> LaneWise therefore uses OpenStreetMap (`turn:lanes`) + TomTom/HERE, never their data.

## Method

| Item | Value |
| --- | --- |
| Tool | REA (Reverse Engineer Anything) MCP server, archive route (`open_binary` → `extract_artifact`) |
| Why not JADX | REA's Android decompiler needs a separately supplied `jadx-headless-mcp` JAR (`REA_JADX_MCP_JAR`), not installed on this machine. DEX string tables and native string tables were scanned with small Python helpers instead. |
| Petal Maps | `com.huawei.maps.app` 4.7.0.322, APK SHA-256 `b55133d8…15bbd`, REA evidence `ev_35ef35b273a0a93800b0104ee3090b10e53e7a28f8ce6ea5aac789df78747e5b` |
| AMAP | `com.autonavi.minimap` 17.00.0.2009, APKM SHA-256 `ec8d23fa…e0aa0`, REA evidence `ev_561715cfa8ae251f3e90cb80ebc9b4023db3fcce6310091cc075a67fb4106820` |

Everything below is either **observed** (seen in shipped files) or **inferred** (my reading of
what the observation implies). No app was executed; nothing here is runtime-verified.

---

## Petal Maps (Huawei)

### 1. Lane data comes from the server, as protobuf (observed)
`NaviSDK.proto` descriptor embedded in the DEX (`com.huawei.navi.navibase.model.protobuf.NaviSDKProtocol`):

```
RouteGuide { legs, guide_points, lane_points: [LanePoint], zoom_points, branches, ... }
LanePoint  { coord: RPPoint, lane_info: [string], distance, lane_attr: [uint32], lane_ext_info: [LaneExtInfo] }
Leg.Link   { kind, road_class, lane_valid, lane_full_num, lane_full_num_4k, max_limit_speed,
             condition_speed_limit, traffic_sign, form_of_way, ... }
```

*Inferred:* lane guidance is computed server-side per route; the client receives one
`LanePoint` per junction with a per-lane string (`lane_info`) and attributes (`lane_attr`).
`lane_full_num_4k` + many `*_4k_*` resources = their lane-level ("4K") HD map product.

### 2. Lane arrows are a 9-bit direction mask (observed)
`assets/laneInfoImages/{day,night}/lane_<all>_<recommended>_000_0.png` (507 files).
Viewing the single-bit images gives the encoding:

| bit | arrow | bit | arrow |
| ---: | --- | ---: | --- |
| 1 | straight | 32 | sharp left |
| 2 | slight right | 64 | left |
| 4 | right | 128 | slight left |
| 8 | sharp right | 256 | U-turn (right-hand) |
| 16 | U-turn (left) | `N` | lane not recommended |

First number = all arrows painted on the lane, second = the arrow highlighted for the route.
→ LaneWise uses the same *idea* (one bit per arrow, AND to test) in `LaneDir`, with its own
bit order and its own vector-drawn glyphs (`ui/lane/LaneArrows.kt`).

### 3. 3D lane scene is rendered natively from a lane style (observed)
`libmap.so` (`MapDisplayServiceRender`) contains the classes/log tags
`LaneGuideBuildImpl`, `LaneGuideArrowImpl`, `LaneGuideFlowArrowImpl`, `LaneGuideTrafficImpl`,
`LaneGuideAnimateImpl`, `LaneFullScreenBubble`, `LaneFeatureCreator`, `MapTileSDLane`, with JNI
entry points `nativeGetLaneFenceLines`, `nativeGetLaneMidPoints`, `nativeGetLaneTurningPoints`,
`nativeGetLaneEntranceAngle`, `nativeGetLaneDynArrowsInfo`, `nativeLaneGuideSetNaviLocation`.
Log strings: `buildFence(LaneChange)`, `buildFence(Maneuver)`, `SetNaviLocation failed, targetIdx < currAnimateIndex`.

*Inferred pipeline:* build a centre line from route link segments → derive per-lane mid-lines
and "fences" (lane borders) → draw flowing arrows along the guide → colour it by traffic →
animate the car monotonically along the guide index (never backwards).

The scene style is `assets/sdLane_base_style.json` (+ `_night`, `_FullScreen`). Observed values:

| Road class (Chinese label) | `lane-width` (m) |
| --- | ---: |
| Expressway | 3.75 |
| National / provincial road | 3.5 |
| Urban arterial / county | 3.25 |
| Main road | 3.0 |
| Secondary | 2.75 |
| Branch | 2.5 |

Layer types: `road-lane.baseline`, `lane-line.single|double|long-dash|short-dash`,
`center-line` (yellow `#F4D81C`), `stop-line`, `crosswalk`, `shoulder`, `lane-background`
(`#262C35`), tunnel mask; painted road arrows via a separate 5-bit `road-sign` sprite table.
→ LaneWise uses the same per-road-class lane widths idea (`LaneRibbonBuilder.laneWidth`), German
values: 3.75 m motorway, 3.5 trunk, 3.25 rural, 3.0 urban.

### 4. Voice phrases (observed)
`StayLanePhrase`, `UseLanePhrase`, `stayLeft2Lane`, `useMiddleNumLane3`, `useRightNumLane10`,
`normalRoad2OutE1LaneVoice` … → a phrase table keyed by lane position from left/right/middle.
→ LaneWise `Phrases.laneChange()` names the target lane "2. Spur von links" etc.

---

## AMAP (Gaode / Alibaba)

### 1. Lane-level navigation engine "LNDS" (observed)
DEX: `startLndsDataUpdates`, `sendLndsInfo`, `LaneMatchInfo`, `onLaneMatchInfoReport`,
`getLaneGroupIdHdRoad`, `isHdMapMatching`, `ThreadType_LANE_ENGINE`, `getLinkLongSolidLane`.
Native (`libamapr.so`, `libamaptbt.so`, `libamaphorus.so`): `lanenavi::LaneNetMaker`,
`lanenavi::LaneNet::merge`, `GroupNode`, `LaneNode`, `SuggestAreaMaker`,
`LaneNaviConfigManager::parseSuggestLaneConfig`, `SurfaceBorrowLaneProcessor`,
`tbt::lanenavi::PlayDataBoard`, `scoreObs_elementWeight_tunnelLane`, `IntelligenceLane`.

*Inferred:* AMAP builds a **lane network** (lane groups → lane nodes with connectivity) from HD
tiles (`lnds.tile_net`), map-matches the car to a lane (HD map + high-precision GNSS), and scores
candidate lanes ("suggested lane", "optimal length") — i.e. a graph search over lanes, not a
per-junction lookup. LaneWise's planner follows the same principle (`LanePlanner`).

### 2. Lane guidance parameters (observed, `assets/navi/zh-Hans/lane_navi_config.bin`)
Plain key/value/description table. Selected values:

| Key | Value | Meaning (translated) |
| --- | ---: | --- |
| `lineBeforeCurpos` | 40 m | guide line drawn this far ahead of the car |
| `lineBeforeSolidLine` | 20 m | guide line avoids solid lines/hatched areas within this distance |
| `surfaceSolidLineMinLimitDistance` | 200 m | latest merge before a long solid line |
| `surfaceSolidLineLimitDistance` | 300 m | solid-line decision point from line start |
| `arrowInSurfaceDisplayDist` | 300 m | show lane-change hint 300 m before lane narrowing/shift |
| `keepLaneMatchSecond` | 5000 ms | keep HD lane position via connectivity when lane match is lost |
| `maxSurfaceLength` / `minSurfaceLength` | 5000 / 2000 m | guide surface length |
| `surfaceLengthAfterCar` | 500 m | guide surface behind the car |
| `nearMainAnchorMediumScaleDistance` | 800 m | camera: medium scale 800 m before main junction |
| main anchor large scale | 300 m | camera: close-up 300 m before |
| `minZoomInOutTimeSpan` | 5000 ms | min time between camera zoom changes |
| `increaseLengthForOneLaneInMainRoad` | +15 m | taper length per extra lane (arterial) |
| `jamChangeLaneReact` / `congestChangeLaneReact` | 90 / 120 m | reaction distance for traffic-driven lane changes |
| `shd_minOptimalLaneDistanceToNaviPoint` | 95 m | stop showing the optimal-lane hint 95 m before the junction |

→ LaneWise: lane-change lead distances (`LaneGuidance.leadDistance`), camera zoom steps near
junctions (`MapController.updateNavigation`), the ribbon drawn ahead and a little behind the car.

### 3. Voice rule engine (observed, `lane_sound_config.bin`)
Rules are boolean expressions over car/lane state, e.g.
`(car_lanemergetotarget==0)&&(car_laneremind==0)&&(car_playmergenum==0)` → "保持当前车道行驶"
(*keep your current lane*); `(car_lanemergetotarget==1)&&(car_playmergenum>0)` →
"准备驶出主路,请向@lanecodetext@变道" (*prepare to exit, change to lane X*);
`car_cannotmergebysolidlane==1` → "经过实线后,请向…变道" (*after the solid line, change to …*);
narrowing rules ("左侧车道变窄").

*Inferred:* AMAP tracks the **number of lane merges needed to reach the target lane**
(`car_playmergenum`) and says "keep lane" when it is zero — exactly the "avoid lane changes"
behaviour the LaneWise owner asked for. LaneWise: `LaneState.stayInLane`,
`NavigationEngine` "stay in lane" announcement, `Phrases.laneChange()`.

---

## What LaneWise does differently (design decisions, not observations)

* **Data**: OSM `turn:lanes` (fresh, community-maintained, excellent in Hannover) via OSRM, or
  TomTom lane sections; no HD map. Lane-level *positioning* is therefore not available — LaneWise
  assumes the driver follows advice and lets them tap their lane to correct it.
* **Planner**: dynamic programming over all lane sites in a 4 km horizon, minimising lane changes
  with a feasibility penalty for short gaps, the German keep-right rule as a tie-breaker, and
  "corresponding lane" mapping through turns. See [LANE_ALGORITHM.md](LANE_ALGORITHM.md).
* **Rendering**: lane geometry generated on the phone from route + lane counts, drawn by MapLibre
  in true 3D perspective, plus a Compose-drawn junction close-up.

## Open questions / next RE steps
* Install `jadx-headless-mcp` 0.7.1 and set `REA_JADX_MCP_JAR` to decompile Petal's
  `com.huawei.maps.navi.hdmi.*` beans (side-lane info JSON shape) and AMAP's lane message protos.
* AMAP's `laneprofile_*.data` files are compressed/encrypted; not decoded.
