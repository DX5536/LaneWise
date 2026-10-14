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

    enum class Kind { SURFACE, BIKE, RECOMMENDED, ALLOWED, ARROW, EDGE, DIVIDER, DIVIDER_SOLID, STOP }

    /** Either a closed polygon (fill) or an open line; [widthM] is used for lines only. */
    data class Shape(val points: List<LatLon>, val kind: Kind, val polygon: Boolean, val widthM: Double = 0.15)

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
        var left = 0.0           // offset of left carriageway edge (m, + = right of centre line)
        val right get() = left + lanes * w
        fun at(off: Double) = Geo.destination(p, bearing + 90.0, off)
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

    fun build(
        route: Route, state: LaneState, carDistance: Double, ahead: Double = 1200.0, behind: Double = 60.0,
        showBikeLanes: Boolean = true,
    ): Ribbon {
        val line = route.geometry
        val from = max(0.0, carDistance - behind)
        val to = min(line.length, carDistance + ahead)
        if (to - from < 20) return Ribbon(emptyList(), emptyList())

        val samples = smoothedSamples(line, from, to)
        if (samples.size < 3) return Ribbon(emptyList(), emptyList())
        assignLanes(route, samples)

        // Centre of the planned lane per sample. Counted from the right edge (lateral 0 = rightmost through lane),
        // which stays put while left-turn pockets open, then smoothed over ±20 m so lane-count changes and
        // pocket openings become gentle curves instead of steps.
        val plan = state.plan
        val keys = if (plan.isNotEmpty()) keyframes(state, carDistance, behind)
        else listOf(carDistance to (state.assumedLateral ?: 0).toDouble())
        val rawCentres = samples.map { s ->
            val lat = interpolate(keys, s.d)
            (s.right - (lat + s.rightPockets + 0.5) * s.w).coerceIn(s.left + 0.5 * s.w, max(s.left + 0.5 * s.w, s.right - 0.5 * s.w))
        }
        val centres = movingAverage(samples, rawCentres, 20.0)
        val lanePath = samples.mapIndexed { i, s -> s.d to s.at(centres[i]) }

        val out = ArrayList<Shape>()

        // Road surface.
        out += polygon(samples.map { it.at(it.left - 0.3) }, samples.map { it.at(it.right + 0.3) }, Kind.SURFACE)

        // Bike lanes right of the outermost car lane.
        if (showBikeLanes && route.bikeLanes.isNotEmpty()) {
            runs(samples) { route.bikeLaneAt(it.d) }.forEach { (span, run) ->
                val bw = if (span.exclusive) 1.6 else 1.3
                out += polygon(run.map { it.at(it.right + 0.35) }, run.map { it.at(it.right + 0.35 + bw) }, Kind.BIKE)
                out += Shape(run.map { it.at(it.right + 0.2) }, Kind.EDGE, polygon = false, widthM = 0.15)
            }
        }

        // Recommended lane ribbon (and the other optimal lanes just before the next site).
        if (plan.isNotEmpty()) {
            val hw = samples.map { it.w * 0.36 }
            val endIdx = samples.indexOfLast { it.d <= plan.last().site.distanceAlong + 40 }.coerceAtLeast(1)
            out += polygon(
                (0..endIdx).map { samples[it].at(centres[it] - hw[it]) },
                (0..endIdx).map { samples[it].at(centres[it] + hw[it]) },
                Kind.RECOMMENDED,
            )
            val next = plan.first()
            val near = samples.filter { it.d in (next.site.distanceAlong - 150)..(next.site.distanceAlong - 3) }
            if (near.size >= 2) {
                for (i in 0 until next.site.laneCount) {
                    if (i == next.recommended || !next.optimal.getOrElse(i) { false }) continue
                    out += polygon(near.map { it.at(it.left + (i + 0.5) * it.w - it.w * 0.3) },
                        near.map { it.at(it.left + (i + 0.5) * it.w + it.w * 0.3) }, Kind.ALLOWED)
                }
            }
        }

        // Dividers: one per lane boundary, only where both neighbouring lanes (almost) fully exist.
        val maxLanes = samples.maxOf { it.nominal }
        val sites = route.laneSites.filter { it.distanceAlong in from..(to + 40) }
        fun nearSite(d: Double) = sites.any { d in (it.distanceAlong - 30)..it.distanceAlong }
        for (k in 1 until maxLanes) {
            // Solid: last 30 m before a junction, or where OSM `change:lanes` forbids crossing this line.
            fun solidAt(s: S) = nearSite(s.d) || route.laneChangeAt(s.d)?.solidBoundary(k, s.nominal) == true
            runs(samples) { s -> if (s.lanes >= k + 0.85) solidAt(s) else null }.forEach { (solid, run) ->
                out += Shape(run.map { it.at(it.left + k * it.w) }, if (solid) Kind.DIVIDER_SOLID else Kind.DIVIDER, false, 0.15)
            }
        }
        out += Shape(samples.map { it.at(it.left) }, Kind.EDGE, false, 0.18)
        out += Shape(samples.map { it.at(it.right) }, Kind.EDGE, false, 0.18)

        // Stop lines and painted arrows at each lane site in view.
        for (site in sites) {
            val s = samples.minByOrNull { abs(it.d - (site.distanceAlong - 2)) } ?: continue
            if (abs(s.d - (site.distanceAlong - 2)) > 6) continue
            out += Shape(listOf(s.at(s.left), s.at(s.left + site.laneCount * s.w)), Kind.STOP, false, 0.5)
            paintArrows(site, samples, out)
        }
        return Ribbon(out, lanePath)
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
        // Bearings from points ~8 m apart for stable normals.
        for (i in out.indices) {
            val a = out[max(0, i - 3)].p; val b = out[min(out.size - 1, i + 3)].p
            out[i].bearing = if (a == b) 0.0 else Geo.bearing(a, b)
        }
        return out
    }

    private fun assignLanes(route: Route, samples: List<S>) {
        val sites = route.laneSites
        for (s in samples) {
            val span = route.roadSpanAt(s.d)
            val kind = span?.kind ?: RoadKind.URBAN
            s.w = laneWidth(kind)
            s.twoWay = span?.twoWay ?: false
            val next = sites.firstOrNull { it.distanceAlong >= s.d - 1 }
            val osm = route.laneCountAt(s.d)
            when {
                next != null && next.distanceAlong - s.d < pocketLength(kind) -> {
                    s.nominal = next.laneCount; s.rightPockets = next.rightPockets()
                }
                osm != null -> s.nominal = osm
                next != null && next.distanceAlong - s.d < 500 ->
                    s.nominal = (next.laneCount - next.leftPockets() - next.rightPockets()).coerceAtLeast(1)
                else -> s.nominal = defaultLanes(kind)
            }
        }
        // Tapers: lanes appear before a change point and disappear after it, 15 m per lane (min 30 m).
        val f = DoubleArray(samples.size) { samples[it].nominal.toDouble() }
        for (i in 1 until samples.size) {
            val a = samples[i - 1].nominal; val b = samples[i].nominal
            if (a == b) continue
            val len = max(30.0, 15.0 * abs(b - a))
            val di = samples[i].d
            if (b > a) {
                var j = i - 1
                while (j >= 0 && di - samples[j].d < len) {
                    val t = 1 - (di - samples[j].d) / len
                    f[j] = max(f[j], a + (b - a) * smooth(t)); j--
                }
            } else {
                var j = i
                while (j < samples.size && samples[j].d - di < len) {
                    val t = 1 - (samples[j].d - di) / len
                    f[j] = max(f[j], b + (a - b) * smooth(t)); j++
                }
            }
        }
        // Lane width also eases between road kinds.
        for (i in samples.indices) {
            val s = samples[i]
            s.lanes = f[i]
            s.left = if (s.twoWay) 0.0 else -s.lanes * s.w / 2.0
        }
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
    private fun keyframes(state: LaneState, carDistance: Double, behind: Double): List<Pair<Double, Double>> {
        val plan = state.plan
        val keys = ArrayList<Pair<Double, Double>>()
        val startLat = (state.assumedLateral ?: plan.first().site.lateral(plan.first().recommended)).toDouble()
        keys += carDistance - behind to startLat
        var prevD = carDistance
        var cur = startLat
        val guidance = LaneGuidance()
        for (a in plan) {
            // Through a turn the car goes straight into the chosen lane of the new road.
            a.entryLateral?.let { e -> keys += prevD + 3 to e.toDouble(); cur = e.toDouble() }
            val target = a.site.lateral(a.recommended).toDouble()
            if (target != cur) {
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
