package de.lanewise.lane

import de.lanewise.core.geo.Geo
import de.lanewise.core.geo.LatLon
import de.lanewise.core.model.Route
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Builds lane-level geometry around the route for the 3D map — LaneWise's take on AMAP's guide surface and
 * Petal's lane view, inspired by detailed street-space maps such as osmberlin/strassenraumkarte-neukoelln:
 *
 *  - a smoothed centre line (resampled + Chaikin), so offsets never kink at junction corners,
 *  - per-sample lane counts from OSM (`lanes:forward`, `turn:lanes`) and lane sites, with linear tapers
 *    when lanes are added or dropped (AMAP: +15 m taper per lane) instead of hard jumps,
 *  - road surface, bike lane and recommended-lane ribbon as filled polygons (true metres, no pixel maths),
 *  - lane dividers (dashed, solid in the last 30 m before a junction), edges, stop lines and painted arrows.
 */
object LaneRibbonBuilder {

    enum class Kind {
        SURFACE, BIKE, RECOMMENDED, ALLOWED, ARROW, EDGE, DIVIDER, DIVIDER_SOLID, STOP, TURN_ARROW,
        /** Zebra stripes, signal poles and heads (extruded), closed-lane tint and cross, moving chevrons. */
        ZEBRA, POLE, SIGNAL_HEAD, CLOSED_LANE, CLOSED_X, CHEVRON,
        /** Bike symbol painted on a bike lane (point + [Shape.bearing]). */
        BIKE_SYMBOL,
    }

    /** Either a closed polygon (fill) or an open line; [widthM] is used for lines only; [opacity] multiplies the layer's. */
    data class Shape(val points: List<LatLon>, val kind: Kind, val polygon: Boolean, val widthM: Double = 0.15,
                     val opacity: Double = 1.0, val index: Int = -1, val bearing: Double = 0.0)

    /** Optional road details: zebra crossings + traffic-light poles ([furniture]) and closed lanes. */
    data class RoadExtras(val furniture: Boolean = false, val closures: List<LaneClosure> = emptyList())

    fun laneWidth(kind: RoadKind) = when (kind) {
        RoadKind.MOTORWAY -> 3.75
        RoadKind.TRUNK -> 3.5
        RoadKind.RURAL -> 3.25
        RoadKind.URBAN -> 3.0
    }

    private fun defaultLanes(kind: RoadKind) = when (kind) {
        RoadKind.MOTORWAY -> 3
        RoadKind.TRUNK -> 2
        else -> 1
    }

    /** A centre-line sample: position, along-route distance, unit normal (pointing right) and lane data. */
    private class S(val p: LatLon, val d: Double) {
        var bearing = 0.0
        var nominal = 1          // integer lanes
        var lanes = 1.0          // tapered lanes
        var w = 3.0
        var twoWay = false
        var rightPockets = 0
        var rightPocketsT = 0.0  // tapered like [lanes], so the planned lane does not move while a pocket opens
        var leftPockets = 0
        var leftPocketsT = 0.0
        var junction = false     // inside a turn junction: only the connector of the turn lanes, no markings
        var left = 0.0           // offset of left carriageway edge (m, + = right of centre line)
        val right get() = left + lanes * w
        /** Curvature of the centre line (rad/m, + = turning right), smoothed. */
        var kappa = 0.0

        /**
         * Point [off] metres right (+) / left (-) of the centre line. On the inside of a curve the offset is limited
         * smoothly to 90 % of the curve radius: an offset larger than the radius would make the line loop back
         * (those points used to be dropped, leaving 70–80° corners in edges and lane lines at tight turns — render
         * audit KINK); now the inner side hugs a slightly tighter curve. Monotonic, so lines never cross.
         */
        fun at(off: Double) = Geo.destination(p, bearing + 90.0, limitedOffset(off, kappa))
    }

    /**
     * Lane geometry plus [lanePath]: the centre of the lane the car is in / should be in (along-route distance →
     * position), so the car puck and camera drive in the lane instead of on the road's centre line.
     */
    data class Ribbon(val shapes: List<Shape>, val lanePath: List<Pair<Double, LatLon>>) {
        /** Point on [lanePath] at along-route distance [d], or null outside it. */
        fun pointAt(d: Double): LatLon? {
            if (lanePath.size < 2 || d < lanePath.first().first || d > lanePath.last().first) return null
            var lo = 0; var hi = lanePath.size - 1
            while (hi - lo > 1) { val m = (lo + hi) / 2; if (lanePath[m].first <= d) lo = m else hi = m }
            val (d0, p0) = lanePath[lo]; val (d1, p1) = lanePath[hi]
            val t = if (d1 - d0 < 1e-6) 0.0 else (d - d0) / (d1 - d0)
            return LatLon(p0.lat + (p1.lat - p0.lat) * t, p0.lon + (p1.lon - p0.lon) * t)
        }
    }

    /** Sample grid spacing (m). Samples sit at fixed route distances, so geometry never shifts between frames. */
    private const val GRID = 6.0

    /** Fade of the recommended strip ahead of the car (option): full until FADE_FROM, gone at FADE_TO (m). */
    const val FADE_FROM = 220.0
    const val FADE_TO = 340.0

    /** Chevrons: one every CHEVRON_SPACING m, up to CHEVRON_AHEAD m ahead of the car (FADE_FROM with the fade on). */
    const val CHEVRON_SPACING = 9.0
    const val CHEVRON_AHEAD = 180.0

    /**
     * A junction the traffic flows through without stopping: on a motorway / expressway (by the road leading to it), or
     * a slight fork / exit on a ramp without traffic lights. Drawn without stop line, painted arrows, solid lines and
     * without the turn connector that squeezes the lanes into the turn — that is how town junctions look, not an
     * Autobahn diverge.
     */
    fun flowing(route: Route, site: LaneSite): Boolean {
        val approach = route.roadSpanAt(site.distanceAlong - 60)?.kind ?: site.roadKind
        if (approach == RoadKind.MOTORWAY || approach == RoadKind.TRUNK) return true
        val slight = site.turn and (LaneDir.SLIGHT_LEFT or LaneDir.SLIGHT_RIGHT) != 0 || !site.isManeuver
        val lights = route.features.any {
            it.kind == de.lanewise.core.model.FeatureKind.TRAFFIC_SIGNALS && abs(it.distanceAlong - site.distanceAlong) < 60
        }
        return slight && !lights && approach != RoadKind.URBAN && site.lanes.all { it and (LaneDir.LEFT or LaneDir.RIGHT or LaneDir.SHARP_LEFT or LaneDir.SHARP_RIGHT) == 0 }
    }

    /** Route distances of the bike symbols on [span]: the middle if it is short, else every 100 m. */
    fun bikeSymbolSpots(span: de.lanewise.core.model.BikeLaneSpan): List<Double> {
        val len = span.to - span.from
        if (len < 15) return emptyList()
        if (len < 160) return listOf(span.from + len / 2)
        return generateSequence(span.from + 40) { it + 100 }.takeWhile { it < span.to - 20 }.toList()
    }

    /** Number of lanes drawn at route distance [d] (diagnostics, live tests). */
    fun drawnLanes(route: Route, d: Double): Int? =
        gridSamples(route, max(0.0, d - 130), min(route.geometry.length, d + 130)).minByOrNull { abs(it.d - d) }?.nominal

    /** Lanes actually drawn at [d] after tapers and connectors (diagnostics, live tests). */
    fun drawnLaneWidth(route: Route, d: Double): Double? =
        gridSamples(route, max(0.0, d - 130), min(route.geometry.length, d + 130)).minByOrNull { abs(it.d - d) }?.lanes

    /** Samples on the fixed route grid covering [from]..[to] (plus smoothing context), with lane data. */
    private fun gridSamples(route: Route, from: Double, to: Double): List<S> {
        val a = max(0.0, kotlin.math.floor(from / GRID) * GRID)
        val b = min(route.geometry.length, kotlin.math.ceil(to / GRID) * GRID)
        if (b - a < 20) return emptyList()
        val samples = smoothedSamples(route.geometry, a, b)
        if (samples.size >= 3) assignLanes(route, samples)
        return samples
    }

    /**
     * The route's road for [from]..[to]: surface, bike lanes, dividers, edges, stop lines and painted arrows. It does
     * not depend on the car, so the map builds it once per piece of route (see MapController) — no regeneration and
     * no jitter while driving; only [build]'s highlight moves.
     */
    fun buildRoad(route: Route, from: Double, to: Double, showBikeLanes: Boolean = true, extras: RoadExtras = RoadExtras()): List<Shape> {
        val all = gridSamples(route, from - 120, to + 120)          // context for smoothing and tapers
        if (all.size < 3) return emptyList()
        val samples = all.filter { it.d >= from - GRID && it.d <= to + GRID }   // one sample overlap with neighbours
        if (samples.size < 2) return emptyList()
        val out = ArrayList<Shape>()

        // Road surface.
        out += polygon(line(samples) { it.left - 0.3 }, line(samples) { it.right + 0.3 }, Kind.SURFACE)

        // Bike lanes right of the outermost car lane.
        if (showBikeLanes && route.bikeLanes.isNotEmpty()) {
            runs(samples) { route.bikeLaneAt(it.d) }.forEach { (span, run) ->
                val bw = if (span.exclusive) 1.6 else 1.3
                out += polygon(line(run) { it.right + 0.35 }, line(run) { it.right + 0.35 + bw }, Kind.BIKE)
                out += Shape(line(run) { it.right + 0.2 }, Kind.EDGE, polygon = false, widthM = 0.15)
                // Bike symbol (Sinnbild Radfahrer) on the lane: one in the middle of a short lane, every 100 m on a long
                // one - placed by the whole bike lane span, so the 1 km road pieces do not each add their own.
                for (d in bikeSymbolSpots(span)) {
                    if (d < run.first().d || d > run.last().d) continue
                    val s = run.minBy { abs(it.d - d) }
                    out += Shape(listOf(s.at(s.right + 0.35 + bw / 2)), Kind.BIKE_SYMBOL, polygon = false, bearing = s.bearing)
                }
            }
        }

        // Dividers: one per lane boundary, only where both neighbouring lanes (almost) fully exist.
        val maxLanes = samples.maxOf { it.nominal }
        val near = route.laneSites.filter { it.distanceAlong in (from - 40)..(to + 40) && !flowing(route, it) }
        fun nearSite(d: Double) = near.any { d in (it.distanceAlong - 30)..it.distanceAlong }
        for (k in 1 until maxLanes) {
            // Solid: last 30 m before a junction, or where OSM `change:lanes` forbids crossing this line.
            fun solidAt(s: S) = nearSite(s.d) || route.laneChangeAt(s.d)?.solidBoundary(k, s.nominal) == true
            runs(samples) { s -> if (s.lanes >= k + 0.85 && !s.junction) solidAt(s) else null }.forEach { (solid, run) ->
                out += Shape(line(run) { it.left + k * it.w }, if (solid) Kind.DIVIDER_SOLID else Kind.DIVIDER, false, 0.15)
            }
        }
        // Edges stop at a turn junction: the connector through it is drawn as surface only.
        runs(samples) { if (it.junction) null else true }.forEach { (_, run) ->
            out += Shape(line(run) { it.left }, Kind.EDGE, false, 0.18)
            out += Shape(line(run) { it.right }, Kind.EDGE, false, 0.18)
        }

        // Stop lines and painted arrows of the sites in this piece.
        val allSites = route.laneSites.sortedBy { it.distanceAlong }
        for (site in route.laneSites.filter { it.distanceAlong >= from && it.distanceAlong < to }) {
            // Second node of the same junction (dual carriageway): one stop line and one row of arrows is enough.
            if (allSites.any { it.distanceAlong < site.distanceAlong && site.distanceAlong - it.distanceAlong < 35 }) continue
            // Motorway exits, forks and merges flow through: no stop line, no arrows (owner: the A 37 exit looked messy).
            if (flowing(route, site)) continue
            val s = all.minByOrNull { abs(it.d - (site.distanceAlong - 2)) } ?: continue
            if (abs(s.d - (site.distanceAlong - 2)) > 6) continue
            out += Shape(listOf(s.at(s.left), s.at(s.left + site.laneCount * s.w)), Kind.STOP, false, 0.5)
            paintArrows(site, all, out)
        }
        // Traffic lights without lane data still get a clear stop line across the road (owner's request): the first
        // signal of a junction only (the far-side / other-direction signals follow within 40 m), never where a lane
        // site already drew one.
        val signals = route.features.filter { it.kind == de.lanewise.core.model.FeatureKind.TRAFFIC_SIGNALS }.sortedBy { it.distanceAlong }
        for (sig in signals.filter { it.distanceAlong >= from && it.distanceAlong < to }) {
            if (signals.any { it.distanceAlong < sig.distanceAlong && sig.distanceAlong - it.distanceAlong < 40 }) continue
            if (allSites.any { it.distanceAlong - sig.distanceAlong in -15.0..40.0 }) continue
            val s = all.minByOrNull { abs(it.d - (sig.distanceAlong - 2)) } ?: continue
            if (abs(s.d - (sig.distanceAlong - 2)) > 6 || s.junction) continue
            out += Shape(listOf(s.at(s.left), s.at(s.right)), Kind.STOP, false, 0.6)
        }
        if (extras.furniture) furniture(route, all, from, to, out)
        for (c in extras.closures) closedLanes(c, samples, out)
        return out
    }

    /**
     * Zebra crossings (OSM `crossing` nodes on the route: 0.5 m stripes, 0.5 m gaps, 3 m long, across the drawn road)
     * and a traffic-light pole with its head at the right road edge of every signalised stop line (Baidu / AMAP).
     */
    private fun furniture(route: Route, all: List<S>, from: Double, to: Double, out: MutableList<Shape>) {
        fun sampleAt(d: Double) = all.minByOrNull { abs(it.d - d) }?.takeIf { abs(it.d - d) <= GRID && !it.junction }
        val lights = route.features.filter { it.kind == de.lanewise.core.model.FeatureKind.TRAFFIC_SIGNALS }
        for (f in route.features.filter { it.kind == de.lanewise.core.model.FeatureKind.CROSSING && it.zebra && it.distanceAlong >= from && it.distanceAlong < to }) {
            // Never next to traffic lights (no zebra at signals in Germany, whatever the tagging says).
            if (lights.any { abs(it.distanceAlong - f.distanceAlong) < 30 }) continue
            val s = sampleAt(f.distanceAlong) ?: continue
            var x = s.left + 0.3
            while (x + 0.5 <= s.right - 0.1) {
                val a = s.at(x); val b = s.at(x + 0.5)
                out += Shape(listOf(Geo.destination(a, s.bearing, -1.5), Geo.destination(b, s.bearing, -1.5),
                    Geo.destination(b, s.bearing, 1.5), Geo.destination(a, s.bearing, 1.5), Geo.destination(a, s.bearing, -1.5)),
                    Kind.ZEBRA, polygon = true)
                x += 1.0
            }
        }
        val signals = route.features.filter { it.kind == de.lanewise.core.model.FeatureKind.TRAFFIC_SIGNALS && !it.pedestrian }
            .sortedBy { it.distanceAlong }
        for (sig in signals.filter { it.distanceAlong >= from && it.distanceAlong < to }) {
            if (signals.any { it.distanceAlong < sig.distanceAlong && sig.distanceAlong - it.distanceAlong < 40 }) continue
            // At the stop line of the junction's lane site if there is one just after the signal, else at the signal.
            val stop = route.laneSites.firstOrNull { it.distanceAlong - sig.distanceAlong in -15.0..40.0 }?.distanceAlong ?: sig.distanceAlong
            val s = sampleAt(stop - 2) ?: continue
            val base = s.at(s.right + 0.9)
            out += box(base, s.bearing, 0.35, 0.35, Kind.POLE)
            out += box(Geo.destination(base, s.bearing, -0.25), s.bearing, 0.5, 0.95, Kind.SIGNAL_HEAD)
        }
    }

    /** Red tint over each closed lane of [c] plus a cross every 40 m (Autobahn GmbH roadworks). */
    private fun closedLanes(c: LaneClosure, samples: List<S>, out: MutableList<Shape>) {
        val run = samples.filter { it.d >= c.from && it.d <= c.to && !it.junction }
        if (run.size < 2) return
        val n = c.open.size
        for ((i, open) in c.open.withIndex()) {
            if (open) continue
            // Aligned at the right edge: closure lane i is drawn lane (lanes - n + i).
            fun lo(s: S) = s.right - (n - i) * s.w + 0.25
            fun hi(s: S) = s.right - (n - i - 1) * s.w - 0.25
            if (run.any { lo(it) < it.left - 0.1 }) continue
            out += polygon(line(run) { lo(it) }, line(run) { hi(it) }, Kind.CLOSED_LANE)
            var d = run.first().d + 10
            while (d < run.last().d - 5) {
                val s = run.minBy { abs(it.d - d) }
                val m = s.at((lo(s) + hi(s)) / 2)
                for (rot in listOf(45.0, -45.0)) out += box(m, s.bearing + rot, 0.45, 2.6, Kind.CLOSED_X)
                d += 40
            }
        }
    }

    /** Rectangle [width] x [length] m centred on [c], long side along [bearing]. */
    private fun box(c: LatLon, bearing: Double, width: Double, length: Double, kind: Kind): Shape {
        val f = Geo.destination(c, bearing, length / 2); val b = Geo.destination(c, bearing, -length / 2)
        val pts = listOf(Geo.destination(b, bearing - 90, width / 2), Geo.destination(b, bearing + 90, width / 2),
            Geo.destination(f, bearing + 90, width / 2), Geo.destination(f, bearing - 90, width / 2))
        return Shape(pts + pts.first(), kind, polygon = true)
    }

    /**
     * Chevrons (^ shapes) on the planned lane ([path]) from the car to [until]: one every [CHEVRON_SPACING] m on a fixed
     * route grid, so successive builds put them at the same spots. [Shape.index] is the grid index: the map lights them
     * up in a wave running forward by changing only the layer's opacity (no geometry update per frame).
     */
    fun chevrons(path: Ribbon, car: Double, until: Double, laneWidth: Double = 3.0): List<Shape> {
        val out = ArrayList<Shape>()
        val half = laneWidth * 0.26
        var k = kotlin.math.ceil((car + 6) / CHEVRON_SPACING).toInt()
        while (k * CHEVRON_SPACING <= until) {
            val d = k * CHEVRON_SPACING
            val p = path.pointAt(d) ?: break
            val ahead = path.pointAt(d + 2) ?: break
            val br = Geo.bearing(p, ahead)
            fun at(x: Double, y: Double) = Geo.destination(Geo.destination(p, br, y), br + 90, x)
            val ring = listOf(at(-half, -0.2), at(0.0, 0.7), at(half, -0.2), at(half, -0.8), at(0.0, 0.05), at(-half, -0.8))
            val fade = (1.0 - (d - car) / (until - car + 1)).coerceIn(0.15, 1.0)
            out += Shape(ring + ring.first(), Kind.CHEVRON, polygon = true, opacity = fade, index = k)
            k++
        }
        return out
    }

    /**
     * The live part: recommended-lane strip, band of usable lanes and the turn arrow, plus [Ribbon.lanePath] for the
     * car. Built on the same fixed grid as [buildRoad], so it lines up with the road exactly.
     */
    fun build(
        route: Route, state: LaneState, carDistance: Double, ahead: Double = 1200.0, behind: Double = 60.0,
        /** Let the recommended strip fade out between [FADE_FROM] and [FADE_TO] m ahead of the car (Baidu style). */
        fade: Boolean = false,
        /** Chevrons on the strip ([chevrons]); the map animates them by index, without rebuilding. */
        withChevrons: Boolean = false,
    ): Ribbon {
        val from = max(0.0, carDistance - behind)
        val to = min(route.geometry.length, carDistance + ahead)
        val samples = gridSamples(route, from, to)
        if (samples.size < 3) return Ribbon(emptyList(), emptyList())

        // Centre of the planned lane per sample. Counted from the right edge of the through lanes (lateral 0 =
        // rightmost through lane), which stays put while turn pockets open on either side, then smoothed over ±20 m
        // so lane-count changes and pocket openings become gentle curves instead of steps.
        val plan = state.plan
        // Without a plan (past the last junction with lane data) the car stays in its lane — after a turn, which leaves
        // the lane unknown, in the rightmost lane of the new road (owner: no strip on the Berkhopstraße).
        val keys = if (plan.isNotEmpty()) keyframes(state, carDistance, behind, route)
        else listOf(carDistance to (state.assumedLateral ?: 0).toDouble())
        val rawCentres = samples.map { s ->
            val lat = interpolate(keys, s.d)
            (s.right - (lat + s.rightPocketsT + 0.5) * s.w).coerceIn(s.left + 0.5 * s.w, max(s.left + 0.5 * s.w, s.right - 0.5 * s.w))
        }
        val centres = movingAverage(samples, rawCentres, 20.0)
        val lanePath = samples.mapIndexed { i, s -> s.d to s.at(centres[i]) }

        val out = ArrayList<Shape>()
        if (plan.isNotEmpty()) {
            val hw = samples.map { it.w * 0.36 }
            // After the route's last junction with lane data the strip runs on to the destination in the same lane
            // (owner: it stopped 40 m after the last Hauptstraße junction although the goal was further on).
            val lastOfRoute = route.laneSites.maxOfOrNull { it.distanceAlong } == plan.last().site.distanceAlong
            val endIdx = samples.indexOfLast { it.d <= if (lastOfRoute) route.geometry.length else plan.last().site.distanceAlong + 40 }
                .coerceAtLeast(1)
            val strip = samples.subList(0, endIdx + 1)
            val pos = strip.indices.associateBy { strip[it] }
            fun piece(run: List<S>, opacity: Double) {
                if (run.size < 2) return
                out += polygon(
                    line(run) { centres[pos[it]!!] - hw[pos[it]!!] },
                    line(run) { centres[pos[it]!!] + hw[pos[it]!!] },
                    Kind.RECOMMENDED,
                ).copy(opacity = opacity)
            }
            if (!fade) piece(strip, 1.0)
            else {
                // Solid up to FADE_FROM, then 12 m pieces getting fainter, nothing beyond FADE_TO.
                piece(strip.filter { it.d <= carDistance + FADE_FROM }, 1.0)
                var d0 = carDistance + FADE_FROM
                while (d0 < carDistance + FADE_TO) {
                    val run = strip.filter { it.d >= d0 - 0.1 && it.d <= d0 + 12.1 }
                    piece(run, 1.0 - (d0 + 6 - carDistance - FADE_FROM) / (FADE_TO - FADE_FROM))
                    d0 += 12
                }
            }
            // Soft band under the lime strip: every lane the route can use up to each site (AMAP's two-level carpet).
            band(plan, samples, carDistance - behind)?.let { out += it }
        } else if (route.laneSites.isNotEmpty() && route.laneSites.all { it.distanceAlong < carDistance }) {
            // Past the last junction with lane data: keep the strip in the lane the car is in, up to the destination.
            val at = samples.indices.associateBy { samples[it] }
            out += polygon(line(samples) { centres[at[it]!!] - it.w * 0.36 },
                line(samples) { centres[at[it]!!] + it.w * 0.36 }, Kind.RECOMMENDED)
        }

        // Raised arrow through the next turn, from just before the stop line into the new road (AMAP style).
        val ribbonPath = Ribbon(emptyList(), lanePath)
        if (withChevrons && plan.isNotEmpty()) {
            val stripEnd = if (route.laneSites.maxOfOrNull { it.distanceAlong } == plan.last().site.distanceAlong) to
                else min(plan.last().site.distanceAlong + 40, to)
            out += chevrons(ribbonPath, carDistance, min(stripEnd - 4, carDistance + (if (fade) FADE_FROM else CHEVRON_AHEAD)),
                laneWidth = samples.first().w)
        }
        route.steps.firstOrNull { st ->
            st.distanceAlong > carDistance + 2 && st.distanceAlong - carDistance < 300 &&
                st.maneuver.type != de.lanewise.core.model.ManeuverType.DEPART &&
                st.maneuver.type != de.lanewise.core.model.ManeuverType.ARRIVE &&
                st.maneuver.modifier != de.lanewise.core.model.Modifier.STRAIGHT
        }?.let { st -> turnArrow(ribbonPath, st.distanceAlong - 14, minOf(st.distanceAlong + 24, to - 1))?.let { out += it } }
        return Ribbon(out, lanePath)
    }

    /**
     * Band over the lanes the route can use (the contiguous block of allowed lanes around the recommended one) from
     * the previous site to each site, smoothed, clamped to the drawn road.
     */
    private fun band(plan: List<LaneAdvice>, samples: List<S>, from: Double): Shape? {
        val lo = DoubleArray(samples.size) { Double.NaN }
        val hi = DoubleArray(samples.size) { Double.NaN }
        var prev = from
        for (a in plan) {
            val allowed = a.allowed
            var l = a.recommended; var r = a.recommended
            while (l - 1 >= 0 && allowed.getOrElse(l - 1) { false }) l--
            while (r + 1 < a.site.laneCount && allowed.getOrElse(r + 1) { false }) r++
            val leftLat = a.site.lateral(l).toDouble(); val rightLat = a.site.lateral(r).toDouble()
            for (i in samples.indices) {
                val s = samples[i]
                if (s.d < prev || s.d > a.site.distanceAlong || !lo[i].isNaN()) continue
                val coreRight = s.right - s.rightPocketsT * s.w
                lo[i] = (coreRight - (leftLat + 1) * s.w).coerceIn(s.left, s.right)
                hi[i] = (coreRight - rightLat * s.w).coerceIn(s.left, s.right)
            }
            prev = a.site.distanceAlong
        }
        val idx = samples.indices.filter { !lo[it].isNaN() && hi[it] - lo[it] > 0.5 }
        if (idx.size < 3) return null
        val run = samples.slice(idx)
        val l = movingAverage(run, idx.map { lo[it] }, 15.0)
        val h = movingAverage(run, idx.map { hi[it] }, 15.0)
        val pos = run.indices.associateBy { run[it] }
        return polygon(line(run) { l[pos[it]!!] + 0.15 }, line(run) { h[pos[it]!!] - 0.15 }, Kind.ALLOWED)
    }

    /** Arrow polygon along [path] from [d0] to [d1]: 1.6 m body, 5 m head, drawn raised by the map. */
    private fun turnArrow(path: Ribbon, d0: Double, d1: Double): Shape? {
        if (d1 - d0 < 12) return null
        val pts = generateSequence(d0) { it + 1.0 }.takeWhile { it <= d1 }.mapNotNull { d -> path.pointAt(d)?.let { d to it } }.toList()
        if (pts.size < 8) return null
        val headStart = d1 - 5.0
        val left = ArrayList<LatLon>(); val right = ArrayList<LatLon>()
        for (k in pts.indices) {
            val (d, p) = pts[k]
            if (d > headStart) break
            val a = pts[max(0, k - 2)].second; val b = pts[min(pts.size - 1, k + 2)].second
            val br = Geo.bearing(a, b)
            left += Geo.destination(p, br - 90.0, 0.8); right += Geo.destination(p, br + 90.0, 0.8)
        }
        if (left.size < 2) return null
        val base = pts.last { it.first <= headStart }.second
        val tip = pts.last().second
        val br = Geo.bearing(base, tip)
        val head = listOf(Geo.destination(base, br - 90.0, 1.9), tip, Geo.destination(base, br + 90.0, 1.9))
        val ring = left + head + right.reversed() + left.first()
        return Shape(ring, Kind.TURN_ARROW, polygon = true)
    }

    /**
     * Offset line along [samples]; points that would run backwards against the road (the loop an offset forms on the
     * inside of a tight turn) are dropped, so edges and lanes never fold over themselves.
     */
    private fun line(samples: List<S>, off: (S) -> Double): List<LatLon> {
        val out = ArrayList<LatLon>(samples.size)
        for (s in samples) {
            val p = s.at(off(s))
            val last = out.lastOrNull()
            if (last != null && Geo.distance(last, p) > 0.05 && abs(Geo.bearingDelta(Geo.bearing(last, p), s.bearing)) > 90) continue
            out += p
        }
        return out
    }

    /** Distance-weighted moving average of [v] over ±[radius] m along the samples. */
    private fun movingAverage(samples: List<S>, v: List<Double>, radius: Double): List<Double> {
        val prefix = DoubleArray(v.size + 1).also { p -> for (i in v.indices) p[i + 1] = p[i] + v[i] }
        var lo = 0; var hi = 0
        return samples.indices.map { i ->
            while (samples[i].d - samples[lo].d > radius) lo++
            while (hi + 1 < samples.size && samples[hi + 1].d - samples[i].d <= radius) hi++
            (prefix[hi + 1] - prefix[lo]) / (hi - lo + 1)
        }
    }

    /** How far before a junction turn pockets open (m): short in town, long on fast roads. */
    private fun pocketLength(kind: RoadKind) = when (kind) {
        RoadKind.MOTORWAY -> 250.0
        RoadKind.TRUNK -> 150.0
        RoadKind.RURAL -> 80.0
        RoadKind.URBAN -> 35.0
    }

    // --- geometry helpers ---------------------------------------------------------------------------

    /** Resample every 6 m and smooth with Chaikin (×3) so junction corners become arcs. */
    private fun smoothedSamples(line: de.lanewise.core.geo.Polyline, from: Double, to: Double): List<S> {
        var pts = ArrayList<Triple<Double, Double, Double>>() // lat, lon, d
        var d = from
        while (d < to) { val p = line.pointAt(d); pts += Triple(p.lat, p.lon, d); d += 6.0 }
        val e = line.pointAt(to); pts += Triple(e.lat, e.lon, to)
        // Round the corners first: a weighted average over ±12 m (1-2-3-2-1). Chaikin alone left junction corners with a
        // ~3 m radius — less than the 2–6 m the edges and dividers sit beside the centre line, so they folded on the
        // inside of every turn (jagged, wonky lines) and the car's lane path turned abruptly. The road start/end keep
        // their exact points. Same result for every piece of route (fixed grid, ±120 m context per piece).
        if (pts.size >= 5) {
            val w = doubleArrayOf(1.0, 2.0, 3.0, 2.0, 1.0)
            pts = ArrayList(pts.indices.map { i ->
                if (i < 2 || i > pts.size - 3) pts[i] else {
                    var la = 0.0; var lo = 0.0
                    for (k in -2..2) { la += pts[i + k].first * w[k + 2]; lo += pts[i + k].second * w[k + 2] }
                    Triple(la / 9.0, lo / 9.0, pts[i].third)
                }
            })
        }
        repeat(3) {
            if (pts.size < 3) return@repeat
            val n = ArrayList<Triple<Double, Double, Double>>(pts.size * 2)
            n += pts.first()
            for (i in 0 until pts.size - 1) {
                val a = pts[i]; val b = pts[i + 1]
                n += Triple(0.75 * a.first + 0.25 * b.first, 0.75 * a.second + 0.25 * b.second, 0.75 * a.third + 0.25 * b.third)
                n += Triple(0.25 * a.first + 0.75 * b.first, 0.25 * a.second + 0.75 * b.second, 0.25 * a.third + 0.75 * b.third)
            }
            n += pts.last()
            pts = n
        }
        val out = pts.map { S(LatLon(it.first, it.second), it.third) }
        // Bearings from points ~6 m apart (±4 samples of 0.75 m) for stable normals.
        for (i in out.indices) {
            val a = out[max(0, i - 4)].p; val b = out[min(out.size - 1, i + 4)].p
            out[i].bearing = if (a == b) 0.0 else Geo.bearing(a, b)
        }
        // Curvature from headings ±6 samples (~4.5 m) apart, then averaged over ±8 samples.
        val raw = DoubleArray(out.size) { i ->
            val a = max(0, i - 6); val b = min(out.size - 1, i + 6)
            val ds = out[b].d - out[a].d
            if (ds < 1.0) 0.0 else Math.toRadians(Geo.bearingDelta(out[a].bearing, out[b].bearing)) / ds
        }
        for (i in out.indices) {
            val a = max(0, i - 8); val b = min(out.size - 1, i + 8)
            var sum = 0.0
            for (k in a..b) sum += raw[k]
            out[i].kappa = sum / (b - a + 1)
        }
        return out
    }

    /** See [S.at]: inner-side offsets limited smoothly to 90 % of the radius 1/|[kappa]|. */
    internal fun limitedOffset(off: Double, kappa: Double): Double {
        if (abs(kappa) < 1e-4 || off == 0.0 || (off > 0) != (kappa > 0)) return off
        val lim = 0.9 / abs(kappa)
        val o = abs(off)
        val knee = 0.6 * lim
        val eff = if (o <= knee) o else knee + (lim - knee) * kotlin.math.tanh((o - knee) / (lim - knee))
        return if (off > 0) eff else -eff
    }

    /**
     * Lanes that carry on after the last junction with lane data before [d] (within 1.5 km), for road without OSM lane
     * counts: the lanes going straight on when the route passes it, the lanes used for the turn when it turns there.
     * Without it such a road dropped to the default (1 lane in town) right behind the stop line — "4 lanes, then all
     * merge into 1" (owner) and most corner kinks of the render audit.
     */
    private fun carriedLanes(sites: List<LaneSite>, d: Double): Int? {
        val prev = sites.lastOrNull { it.distanceAlong < d - 1 } ?: return null
        if (d - prev.distanceAlong > 1500) return null
        val turned = prev.isManeuver && prev.turn != LaneDir.STRAIGHT
        val n = if (turned) prev.allowed().count { it }
        else prev.lanes.count { LaneDir.permits(it, LaneDir.STRAIGHT) }
        return n.takeIf { it > 0 }
    }

    private fun assignLanes(route: Route, samples: List<S>) {
        val sites = route.laneSites
        for (s in samples) {
            val span = route.roadSpanAt(s.d)
            val kind = span?.kind ?: RoadKind.URBAN
            s.w = laneWidth(kind)
            s.twoWay = span?.twoWay ?: false
            val next = sites.firstOrNull { it.distanceAlong >= s.d - 1 }
            // Just after a turn the 25 m OSM samples may still hold the road we came from: use the new road's count.
            val prevTurn = sites.lastOrNull { it.distanceAlong < s.d && s.d - it.distanceAlong < 30 && it.isManeuver && it.turn != LaneDir.STRAIGHT }
            val osm = prevTurn?.let { route.laneCountAt(it.distanceAlong + 30) } ?: route.laneCountAt(s.d)
            when {
                next != null && next.distanceAlong - s.d < pocketLength(kind) -> {
                    s.nominal = next.laneCount; s.rightPockets = next.rightPockets(); s.leftPockets = next.leftPockets()
                }
                osm != null -> {
                    s.nominal = osm
                    // OSM counts the turn lanes along the whole way: they are pockets all along, not lanes that merge.
                    if (next != null && next.distanceAlong - s.d < 500 && osm >= next.laneCount) {
                        s.rightPockets = next.rightPockets(); s.leftPockets = next.leftPockets()
                    }
                }
                next != null && next.distanceAlong - s.d < 500 -> {
                    // The next junction's lanes without its turn pockets - unless the block is too short for pockets
                    // to open (< 150 m from the last junction): then its lanes run the whole block (Berliner Allee,
                    // Königstraße → Schiffgraben, 127 m: 5 - 3 pockets drew a 2-lane choke between two 5-lane stop
                    // lines). Never fewer than the lanes carried on from the last junction.
                    val prev = sites.lastOrNull { it.distanceAlong < s.d - 1 }
                    val core = (next.laneCount - next.leftPockets() - next.rightPockets()).coerceAtLeast(1)
                    val shortBlock = prev != null && next.distanceAlong - prev.distanceAlong < 150
                    s.nominal = if (shortBlock) next.laneCount else max(core, carriedLanes(sites, s.d) ?: 0)
                    if (shortBlock) { s.rightPockets = next.rightPockets(); s.leftPockets = next.leftPockets() }
                }
                else -> s.nominal = carriedLanes(sites, s.d) ?: defaultLanes(kind)
            }
        }
        // Tapers: lanes appear before a change point and disappear after it, 15 m per lane (min 30 m).
        // Right pockets taper over the same stretch, so lanes and pockets open in step.
        val len = DoubleArray(samples.size) { i -> if (i == 0) 30.0 else max(30.0, 15.0 * abs(samples[i].nominal - samples[i - 1].nominal)) }
        // Lanes that end *at* a junction (turn lanes leaving there) stop at the stop line instead of tapering
        // into the next lane after the junction.
        val endsAtJunction = BooleanArray(samples.size) { i -> sites.any { abs(it.distanceAlong - samples[i].d) < 8 } }
        // Going straight through, the ending lanes narrow in a short funnel across the junction box; at a turn the
        // connector already joins the new road, so there they stop at the stop line (a funnel there bulged the new
        // road to 4 lanes right behind the corner - Berliner Allee → Schiffgraben).
        val turnAt = BooleanArray(samples.size) { i ->
            sites.any { abs(it.distanceAlong - samples[i].d) < 8 && it.isManeuver && it.turn != LaneDir.STRAIGHT && !flowing(route, it) }
        }
        val funnel = BooleanArray(samples.size) { endsAtJunction[it] && !turnAt[it] }
        val f = taper(samples, { it.nominal }, len, endsAtJunction, funnel)
        val rp = taper(samples, { it.rightPockets }, len, endsAtJunction, funnel)
        val lp = taper(samples, { it.leftPockets }, len, endsAtJunction, funnel)
        for (i in samples.indices) {
            val s = samples[i]
            s.lanes = f[i]
            s.rightPocketsT = rp[i].coerceIn(0.0, max(0.0, s.lanes - 1))
            s.leftPocketsT = lp[i].coerceIn(0.0, max(0.0, s.lanes - 1 - s.rightPocketsT))
            // Through lanes keep their place; pockets grow outward (two-way: the left pocket takes the median).
            val core = s.lanes - s.leftPocketsT - s.rightPocketsT
            s.left = if (s.twoWay) -s.leftPocketsT * s.w else -core * s.w / 2.0 - s.leftPocketsT * s.w
        }
        connectTurnLanes(route, samples)
    }

    /**
     * At a turn, only the lanes used for the turn continue through the junction (AMAP): the connector starts on the
     * turn lanes at the stop line and joins the new road on its left side (left turn) or right side (right turn) —
     * the German "turn into the corresponding lane" rule. The other lanes end at the stop line.
     */
    private fun connectTurnLanes(route: Route, samples: List<S>) {
        for (site in route.laneSites) {
            if (!site.isManeuver || site.turn == LaneDir.STRAIGHT || flowing(route, site)) continue
            val allowed = site.allowed()
            val first = allowed.indexOfFirst { it }
            val last = allowed.indexOfLast { it }
            if (first < 0) continue
            val n = (last - first + 1).toDouble()
            val i0 = samples.indexOfFirst { it.d >= site.distanceAlong - 1 }
            if (i0 < 0) continue
            // The second node of the same junction (dual carriageway, < 35 m on) is still inside the junction: the
            // connector runs past it, so the lanes ending there do not step the new road's edge sideways right where
            // the connector joins it (render audit: 76° kinks after turns).
            val second = route.laneSites.filter { it.distanceAlong > site.distanceAlong && it.distanceAlong - site.distanceAlong < 35 }
                .maxOfOrNull { it.distanceAlong - site.distanceAlong + 8 } ?: 0.0
            val j = max(junctionLength(route, site.distanceAlong), second)
            val i1 = samples.indexOfLast { it.d <= site.distanceAlong + j }
            if (i1 <= i0 + 1 || i1 >= samples.size - 1) continue
            val s0 = samples[i0]
            val end = samples[i1 + 1]
            val startLeft = s0.left + first * s0.w
            val left = site.turn and LaneDir.ANY_LEFT != 0
            val endLeft = end.left
            for (i in i0..i1) {
                val s = samples[i]
                val t = smooth((s.d - s0.d) / (end.d - s0.d))
                s.junction = true
                // Widens (or narrows) into the new road's lanes, so its edges continue the connector without a step.
                s.lanes = n + (end.lanes - n) * t
                s.leftPocketsT = 0.0; s.rightPocketsT = 0.0
                s.w = s0.w + (end.w - s0.w) * t
                s.left = startLeft + (endLeft - startLeft) * t
            }
        }
    }

    /** Length (m) from the site until the route has done most of the turn — the junction area. */
    private fun junctionLength(route: Route, d: Double): Double {
        val line = route.geometry
        val bIn = line.bearingAt(max(0.0, d - 15))
        val total = abs(Geo.bearingDelta(bIn, line.bearingAt(min(line.length, d + 40))))
        if (total < 20) return 10.0
        var x = 4.0
        while (x < 45 && abs(Geo.bearingDelta(bIn, line.bearingAt(min(line.length, d + x)))) < 0.85 * total) x += 2
        return (x + 6).coerceIn(10.0, 45.0)
    }

    /** Integer series → smooth series: increases ramp up *before* the change point, decreases ramp down after it. */
    private fun taper(samples: List<S>, value: (S) -> Int, len: DoubleArray, stepDown: BooleanArray, funnel: BooleanArray): DoubleArray {
        val f = DoubleArray(samples.size) { value(samples[it]).toDouble() }
        for (i in 1 until samples.size) {
            val a = value(samples[i - 1]); val b = value(samples[i])
            if (a == b) continue
            val l = len[i]
            val di = samples[i].d
            if (b > a) {
                var j = i - 1
                while (j >= 0 && di - samples[j].d < l) {
                    val t = 1 - (di - samples[j].d) / l
                    f[j] = max(f[j], a + (b - a) * smooth(t)); j--
                }
            } else if (!stepDown[i] || funnel[i]) {
                // Lanes ending at a stop line narrow across the junction box (a short funnel) instead of in one grid
                // step: the 6 m step left a rectangular notch and 76° kinks in the edges (render audit).
                val ll = if (funnel[i]) max(18.0, 9.0 * (a - b)) else l
                val d0 = if (funnel[i]) samples[i - 1].d else di
                var j = i
                while (j < samples.size && samples[j].d - d0 < ll) {
                    val t = 1 - (samples[j].d - d0) / ll
                    f[j] = max(f[j], b + (a - b) * smooth(t)); j++
                }
            }
        }
        return f
    }

    /** Painted arrows on every lane, 6–15 m before the stop line. */
    private fun paintArrows(site: LaneSite, samples: List<S>, out: MutableList<Shape>) {
        val top = site.distanceAlong - 6
        val bottom = site.distanceAlong - 15
        val near = samples.filter { it.d in (bottom - 3)..(top + 3) }
        if (near.size < 2) return
        fun sampleAt(d: Double) = near.minBy { abs(it.d - d) }
        site.lanes.forEachIndexed { lane, mask ->
            ArrowGeometry.polygons(mask).forEach { poly ->
                out += Shape(poly.map { pt ->
                    val d = top - pt.y * (top - bottom)
                    val s = sampleAt(d)
                    val lateral = s.left + (lane + 0.5) * s.w + (pt.x - 0.5) * s.w * 0.6
                    // Position along the sample's tangent for sub-sample accuracy.
                    Geo.destination(s.at(lateral), s.bearing, d - s.d)
                }, Kind.ARROW, polygon = true)
            }
        }
    }

    private fun polygon(left: List<LatLon>, right: List<LatLon>, kind: Kind): Shape =
        Shape(left + right.reversed() + left.first(), kind, polygon = true)

    /** Contiguous runs of samples where [key] is non-null and constant. */
    private fun <K : Any> runs(samples: List<S>, key: (S) -> K?): List<Pair<K, List<S>>> {
        val out = ArrayList<Pair<K, List<S>>>()
        var cur: K? = null
        var start = 0
        for (i in 0..samples.size) {
            val k = if (i < samples.size) key(samples[i]) else null
            if (k != cur) {
                val c = cur
                if (c != null && i - start >= 2) out += c to samples.subList(start, i)
                cur = k; start = i
            }
        }
        return out
    }

    /** Lateral key frames (distance → lateral coordinate) with smooth transitions at advised changes. */
    fun keyframes(state: LaneState, carDistance: Double, behind: Double, route: Route? = null): List<Pair<Double, Double>> {
        val plan = state.plan
        val keys = ArrayList<Pair<Double, Double>>()
        val startLat = (state.assumedLateral ?: plan.first().site.lateral(plan.first().recommended)).toDouble()
        // Transitions are placed relative to where the assumed lane was established (fixed on the road), never relative
        // to the car: anchored at the car they would slide ahead of it and the strip would stay in the old lane until
        // the junction. A lane that opens on the way is entered as it opens (the strip is clamped to the drawn road).
        var prevD = min(carDistance, state.assumedSince ?: carDistance)
        keys += min(carDistance - behind, prevD) to startLat
        var cur = startLat
        // Keep right after a motorway junction: glide from the lane the junction left the car in to the right lane.
        val krFrom = state.keepRightFrom
        val krLat = state.keepRightFromLateral
        if (krFrom != null && krLat != null) {
            keys.clear()
            val end = krFrom + LaneGuidance.KEEP_RIGHT_PER_LANE * (krLat - startLat).coerceAtLeast(0.0)
            keys += min(carDistance - behind, krFrom) to krLat.toDouble()
            keys += krFrom to krLat.toDouble()
            keys += end to startLat
            prevD = max(prevD, end)
        }
        val guidance = LaneGuidance()
        for (a in plan) {
            // Through a turn the car goes straight into the chosen lane of the new road.
            a.entryLateral?.let { e -> keys += prevD + 3 to e.toDouble(); cur = e.toDouble() }
            val target = a.site.lateral(a.recommended).toDouble()
            // A lane entered for free because the road widens (no lane change in the plan): the strip moves into it
            // where the road widens, not in the lead distance — otherwise it showed the car in a lane it never was in and
            // then "moved" it over (A 37 → A 7: one lane widening to three, exit on the right; looked like right-left).
            val widening = if (target != cur && a.changeFromPrevious == 0 && route != null && route.laneCounts.isNotEmpty())
                widensAt(route, prevD, a.site.distanceAlong) else null
            if (widening != null) {
                keys += widening to cur
                keys += min(a.site.distanceAlong - 5, widening + 40) to target
                cur = target
            } else if (target != cur) {
                val changes = abs(target - cur)
                val trans = 60.0 * changes * when (a.site.roadKind) {
                    RoadKind.MOTORWAY -> 2.5; RoadKind.TRUNK -> 2.0; RoadKind.RURAL -> 1.5; RoadKind.URBAN -> 1.0
                }
                val lead = guidance.leadDistance(a.site.roadKind, changes.roundToInt().coerceAtLeast(1))
                val start = max(prevD + 5, a.site.distanceAlong - lead)
                val end = min(a.site.distanceAlong - 10, start + trans)
                keys += start to cur
                keys += max(end, start + 1) to target
                cur = target
            }
            keys += a.site.distanceAlong to target
            prevD = a.site.distanceAlong
        }
        return keys.sortedBy { it.first }
    }

    /** Lateral coordinate of the recommended strip at route distance [d] (audits / live tests). */
    fun stripLateralAt(route: Route, state: LaneState, carDistance: Double, d: Double): Double? {
        if (state.plan.isEmpty()) return state.assumedLateral?.toDouble()
        return interpolate(keyframes(state, carDistance, 60.0, route), d)
    }

    /** Where the road gets more lanes between [d0] and [d1] (OSM lane counts, 10 m steps), or null. */
    private fun widensAt(route: Route, d0: Double, d1: Double): Double? {
        var prev: Int? = null
        var d = d0 + 5
        while (d < d1 - 5) {
            val n = route.laneCountAt(d)
            if (n != null && prev != null && n > prev) return d - 10
            if (n != null) prev = n
            d += 10
        }
        return null
    }

    private fun smooth(t: Double) = t.coerceIn(0.0, 1.0).let { it * it * (3 - 2 * it) }

    private fun interpolate(k: List<Pair<Double, Double>>, d: Double): Double {
        if (d <= k.first().first) return k.first().second
        for (i in 1 until k.size) {
            val (d1, v1) = k[i]
            val (d0, v0) = k[i - 1]
            if (d <= d1) {
                if (d1 - d0 < 1e-6) return v1
                return v0 + (v1 - v0) * smooth((d - d0) / (d1 - d0))
            }
        }
        return k.last().second
    }
}
