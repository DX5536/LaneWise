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
  (mirrored for right turns).

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

Solved with forward DP (Viterbi) + backward pass. Outputs per site:

* `recommended` — single best lane (lime in the UI),
* `optimal` — every lane that achieves the minimum number of changes (soft lime),
* `allowed` — every lane the route can use there (white),
* `changeFromPrevious` — how many lanes to move and in which direction.

With the setting **off**, the horizon is just the next site (classic behaviour).

## Timing of instructions

`LaneGuidance.leadDistance`: 600 m (+250 m per extra lane) on motorways, 400 m trunk, 250 m rural,
150 m urban (+80 m). Inspired by AMAP's 300 m `arrowInSurfaceDisplayDist`, lengthened for German
Autobahn exit signage (1000 m / 500 m). The instruction never starts before the previous site.
When no change is needed and the next junction restricts lanes, LaneWise says
"Bleiben Sie auf Ihrer Spur" — AMAP's "keep current lane" rule.

## Current lane

Phones cannot see which lane they are in (that needs HD maps + RTK GNSS — AMAP's LNDS). LaneWise
assumes you followed the advice; after a site the assumed lane is the recommended one (mapped
through turns). Tap a lane in the lane bar to correct it; the plan updates immediately.

## Rendering

`LaneRibbonBuilder` turns the plan into geometry: road surface sized by lane count × lane width,
dashed dividers, and a lime ribbon whose lateral offset follows key frames with smooth-step
transitions where changes are advised. MapLibre draws it in the 3D map (widths are metres converted
to pixels per zoom), and `LaneScene3D` draws a perspective close-up of the next junction.

## Known limitations

* OSM lane data is only as good as the tagging (`turn:lanes` coverage in Hannover is high on main
  roads, lower on side streets). Sites without lane tags are simply absent from the plan.
* Solid lines (no lane change allowed) are not yet modelled — OSM `change:lanes` would allow it.
  See ROADMAP.
* Lane counts between sites are approximated from the next site (pockets open ~120 m before it).
