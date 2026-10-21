# Advanced lane assist: how LaneWise picks your lane

Setting: **Einstellungen → Spurassistent → "Spurwechsel so weit wie möglich vermeiden"** (on by default).

## The problem

Classic guidance only looks at the *next* junction and lights up every lane that works there.

```
   A (straight)          B (turn left, 300 m later)
 | ← | ↑ | ↑ | → |      | ← | ← | ↑ |
       ✓   ✓                 ✓   ✓
```

At A both through lanes are "fine", but only the left one is also fine at B. A driver in the
right through lane has to change lanes in the 300 m between A and B — often in dense traffic.
LaneWise tells you at A to take the left through lane, so you never change.

## Model

* **Lane site** (`LaneSite`): a point on the route with known lane markings (from OSRM
  `intersections[].lanes`, TomTom `LANES` sections, or OSM `turn:lanes` in offline mode):
  arrow bitmask per lane (left → right), the provider's "valid for route" flags, the direction the
  route takes, whether it is a real manoeuvre, and the road kind.
* **Lateral coordinate** (`LaneSite.lateral(i)`): lanes counted from the rightmost *through* lane
  (= 0), positive to the left. Right-turn pockets get −1, −2 …; left-turn pockets extend beyond the
  through lanes. Sites with different lane counts become comparable: a lane change is a change of
  lateral coordinate.
* **Through a turn** (`LanePlanner.entryLateral`): the German "turn into the corresponding lane"
  rule — the k-th left-turn lane from the left enters the k-th lane from the left of the new road
  (mirrored for right turns). With explicit connectivity the connected lanes decide instead (the leftmost of them
  for a left turn, the rightmost for a right turn, the nearest going straight on), and the planner may enter any of
  them at no cost — but no other lane.

* **Explicit lane connectivity** (`LaneSite.connectivity`, `lane/LaneConnectivity.kt`), like HERE's lane model: per lane
  (left → right) the lanes of the road the route leaves on that it leads into, or "does not continue" (it turns off
  elsewhere / ends at the stop line). Sources by precedence: `lane_overrides.json` (`connectivity`, see
  `docs/LANE_OVERRIDES.md`) > OSM `type=connectivity` relations (from way / via node / to way, `connectivity=1:1|2:2,3`,
  fetched with the corridor Overpass query and matched by the route's OSM nodes, `osm/OsmConnectivity.kt`) > the
  heuristics below as fallback. Where it is known, it decides which lanes are usable (`LaneSite.allowed`, beats
  OSRM's flags), which lanes can be entered after the junction (`LanePlanner.connectedLaterals`: only the connected
  lanes, counted from the new road's left edge past its left turn pockets), how many lanes carry on (ribbon
  `carriedLanes`, `markFullLengthLanes`) and which lanes end at the stop line (ribbon funnel). Lanes in parentheses
  (`3:(3)`) are used only when a lane has no other target; `bw` lanes and via ways are not supported (relation skipped).
* **Safety check** (`LaneConnectivity.contradiction`, `Route.withConnectivity`): a connectivity is not used if it names a
  lane the junction does not have, leads into more lanes than OSM counts after the junction, no lane continues, lanes
  cross, or it disagrees with the arrows (a lane whose arrows make the route's turn does not continue, or a continuing
  lane's arrows cannot make it; unmarked lanes are not checked). Two OSM relations that differ count as unreadable.
  Then the junction's lane site is dropped and it goes into `Route.unknownLanesAt`: "No lane data – follow the road
  markings" from 400 m before it, never a guess which source is right. A site re-targeted to a later turn
  (`attachTurnsToLaneSites`) loses its connectivity (it described the road after the first node).

## Optimisation

A layered graph: one layer per site in the horizon (≤ 8 sites, ≤ 4 km), one node per lane.

| Cost term | Value | Purpose |
| --- | --- | --- |
| lane not allowed at a site | ∞ | must be in a usable lane |
| each lane change between consecutive sites | 10 | the thing we minimise; moving into lanes that open in between (road widens, turn pockets) is free when OSM lane counts show it |
| change with no dashed stretch (OSM `change:lanes`) | 200 | never advise crossing a solid line |
| change impossible in the gap | 25 per missing lane | uses 150 m/change (motorway), 120 (trunk), 80 (rural), 40 (urban) |
| keep-right (motorway/trunk, optional) | 0.5 × lanes left of rightmost through lane | §7 StVO tie-breaker, never worth an extra change |
| centring (urban) | 0.02 × distance from centre of allowed lanes | avoids edge lanes (bus/bike/parking) on ties |
| start | 10 × changes from the lane the car is assumed to be in | "don't move me if I'm fine" |
| free move | 0.05 per lane moved where the move is free (lanes that open) | no drift into a widening lane and back |

Solved with forward DP (Viterbi) + backward pass. Outputs per site:

* `recommended` — single best lane (lime in the UI),
* `optimal` — every lane that achieves the minimum number of changes (soft lime),
* `allowed` — every lane the route can use there (white),
* `changeFromPrevious` — how many lanes to move and in which direction.

With the setting **off**, the horizon is just the next site (classic behaviour).

## Timing of instructions

`LaneGuidance.leadDistance`: 600 m (+250 m per extra lane) on motorways, 400 m trunk, 250 m rural,
150 m urban (+80 m). In town (free lane choice, § 7 (3) StVO) a needed change starts early instead: 30 m after the
previous junction, at most 600 m before the turn (`earlyStart`), only across lanes that already exist there; a turn
pocket is entered as it opens. Inspired by AMAP's 300 m `arrowInSurfaceDisplayDist`, lengthened for German
Autobahn exit signage (1000 m / 500 m). The instruction never starts before the previous site, and it is timed from
where the assumed lane was established (the last junction passed, or where the driver tapped a lane), never from the
car — a start measured from the car would slide along with it.
When no change is needed and the next junction restricts lanes, LaneWise says
"Bleiben Sie auf Ihrer Spur" — AMAP's "keep current lane" rule.

## Current lane

Phones cannot see which lane they are in (that needs HD maps + RTK GNSS — AMAP's LNDS). LaneWise
assumes you followed the advice; after a site the assumed lane is the recommended one (mapped
through turns). Tap a lane in the lane bar to correct it; the plan updates immediately.

## Keep right (Rechtsfahrgebot)

On motorways and expressways the assumed lane returns to the rightmost through lane after each junction (300 m /
200 m after the route is on that road, 150 m per lane to glide over), if most of the stretch to the next junction is
motorway / trunk and there is room for the next change; the next manoeuvre is then planned from the right lane. In town
there is free lane choice (§ 7 (3) StVO): the car keeps the lane the junction left it in.

## Lane data clean-up (before planning)

* `attachTurnsToLaneSites`: lanes on an intersection ≤ 80 m before a turn without own lane data are the turn's.
* `mergeDoubleNodeJunctions`: at junctions with two nodes, lanes at the first node that point the way of the turn 20–50 m
  later are usable there; a site ≤ 30 m after a turn that repeats its lanes is dropped.
* `turnsHiddenAsStraight`: "straight" with only turn-arrow lanes usable is treated as that turn.
* `trimToRoadLanes` (motorways): a lane picture wider than the OSM carriageway is trimmed to the usable lanes.
* `markFullLengthLanes`: turn-only edge lanes that run the whole way are ordinary lanes, not pockets.

## Hand-checked patches (lane_overrides.json)

Lane connectivity patches (`connectivity`) are applied after the lane counts (`Route.withConnectivity`), see Model.
Before planning, `Route.withLaneOverrides` replaces the lane sites at patched junctions (precedence: patch > OSM
`turn:lanes` > aerial counts > heuristics). A patch whose lanes cannot make the route's turn, or one that says
`lanes_unknown`, leaves the junction without lanes (`Route.unknownLanesAt`): no advice from 400 m before it, and nothing
after it is planned until it is passed. Details: `docs/LANE_OVERRIDES.md`.

## Lanes that open are not lane changes

`LanePlanner.effectiveChanges`: only crossings between lanes that existed all the way since the previous
junction count. When OSM lane counts (`Route.laneCountAt`) show the road was narrower in between (a turn
pocket opening, or one lane widening to three before a junction), moving into the new lanes is free and not
announced. "In between" starts where the assumed lane was established (`assumedSince`), not at the car: a lane that
opened behind the car is still entered for free. Without lane-count data every crossing counts. Turn-only lanes at a site are pockets only if they open there: when the route passes the previous site straight on,
that site has as many through lanes as this one has lanes, and OSM counts that many lanes all the way in between
(`markFullLengthLanes`, `LaneSite.fullLength`), each lane simply continues — e.g. `through;right` → two lanes →
`through | right`: the right lane is the old through;right lane, no change. OSRM lanes on the intersection ≤ 80 m before a turn
without its own lane data are re-targeted to that turn (`attachTurnsToLaneSites`); the lane bar never shows the
lanes of a junction after the next turn.

## Rendering

`LaneRibbonBuilder` has two parts, both on a fixed 6 m sampling grid along the route, so the same spot of
the route always gives the same geometry (no jitter):
the centre line is averaged over ±12 m and Chaikin-smoothed, so corners are round enough that edges and lane lines
several metres to the side never fold on the inside of a turn.

* `buildRoad(from, to)` is the route's road: surface sized by lane count × lane width, dashed/solid dividers,
  edges, stop lines, painted arrows, bike lanes. Through lanes keep their place and turn pockets grow outward
  (two-way roads: a left pocket takes the median). Lanes that end at a junction stop at the stop line; at a turn
  only the turn lanes continue as a connector into the new road (left side for left turns, right side for right
  turns), without markings inside the junction. Offset lines drop points that would run backwards, so nothing
  folds on the inside of tight turns. `MapController` builds it per 1 km and keeps it.
* `build(state, car)` is the live highlight: lime strip in the recommended lane (key frames with smooth-step
  transitions where changes are advised, smoothed ±20 m), a soft band over every usable lane up to each site,
  a raised turn arrow through the next turn, and `lanePath` (the car puck and camera drive on it).

The classic view adds `LaneScene3D`, a perspective close-up of the next junction; the AMAP-style view drops it
and moves the camera instead.

## Known limitations

* OSM lane data is only as good as the tagging (`turn:lanes` coverage in Hannover is high on main
  roads, lower on side streets). Sites without lane tags are simply absent from the plan.
* Solid lines come from OSM `change:lanes`; where it is not tagged, every divider is assumed crossable.
* Where OSM has no lane counts, lane counts between sites are approximated from the next site (pockets open
  35 m before it in town, 80 m rural, 150 m trunk, 250 m motorway).
* A turn whose junction has no lane data gets no lane bar.
