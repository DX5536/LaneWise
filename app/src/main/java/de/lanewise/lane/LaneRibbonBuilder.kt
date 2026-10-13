package de.lanewise.lane

import de.lanewise.core.geo.LatLon
import de.lanewise.core.geo.Polyline
import de.lanewise.core.model.Route
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Builds lane-level geometry around the route for the 3D map view — LaneWise's take on AMAP's
 * "guide surface" (引导面) and Petal's lane guide: the road surface with individual lanes, lane
 * dividers, and a ribbon in the recommended lane that bends smoothly where a lane change is advised.
 *
 * All geometry is real-world metres offset from the route line, so MapLibre renders it in proper
 * perspective at any pitch.
 */
object LaneRibbonBuilder {

    data class Line(val points: List<LatLon>, val widthM: Double, val kind: Kind)
    enum class Kind { SURFACE, EDGE, DIVIDER, RECOMMENDED, ALLOWED }

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

    private class Sample(val d: Double, val p: LatLon, val lanes: Int, val rightPockets: Int, val w: Double, val twoWay: Boolean)

    fun build(route: Route, state: LaneState, carDistance: Double, ahead: Double = 1200.0, behind: Double = 60.0): List<Line> {
        val line: Polyline = route.geometry
        val from = max(0.0, carDistance - behind)
        val to = min(line.length, carDistance + ahead)
        if (to - from < 10) return emptyList()

        val step = 5.0
        val sites = route.laneSites
        val samples = ArrayList<Sample>()
        var d = from
        while (d <= to) {
            val span = route.roadSpanAt(d)
            val kind = span?.kind ?: RoadKind.URBAN
            val next = sites.firstOrNull { it.distanceAlong >= d }
            val (n, rp) = when {
                next != null && next.distanceAlong - d < 120 -> next.laneCount to next.rightPockets()
                next != null && next.distanceAlong - d < 700 ->
                    (next.laneCount - next.leftPockets() - next.rightPockets()).coerceAtLeast(1) to 0
                else -> defaultLanes(kind) to 0
            }
            samples += Sample(d, line.pointAt(d), n, rp, laneWidth(kind), span?.twoWay ?: false)
            d += step
        }
        if (samples.size < 2) return emptyList()

        val out = ArrayList<Line>()
        val pts = samples.map { it.p }

        // Offset (m, + = right of travel) of the centre of lane i at a sample.
        fun laneCenter(s: Sample, i: Double): Double =
            if (s.twoWay) (i + 0.5) * s.w else (i - (s.lanes - 1) / 2.0) * s.w

        // Split into runs of constant lane count so each feature has one width.
        var runStart = 0
        for (k in 1..samples.size) {
            val boundary = k == samples.size || samples[k].lanes != samples[runStart].lanes || samples[k].twoWay != samples[runStart].twoWay
            if (!boundary) continue
            // Start one sample early so consecutive runs join without a gap.
            val runPts = pts.subList(max(0, runStart - 1), k)
            if (runPts.size >= 2) {
                val s0 = samples[runStart]
                val n = s0.lanes
                val centreOffset = if (s0.twoWay) n * s0.w / 2.0 else 0.0
                out += Line(Polyline.offset(runPts) { centreOffset }, n * s0.w + 0.6, Kind.SURFACE)
                for (b in 0..n) {
                    val off = if (s0.twoWay) b * s0.w else (b - n / 2.0) * s0.w
                    val kind = if (b == 0 || b == n) Kind.EDGE else Kind.DIVIDER
                    out += Line(Polyline.offset(runPts) { off }, 0.15, kind)
                }
            }
            runStart = k
        }

        // Recommended lane: lateral coordinate over distance, with smooth transitions at change points.
        val plan = state.plan
        if (plan.isNotEmpty()) {
            val keyframes = ArrayList<Pair<Double, Double>>() // (distance, lateral)
            val startLat = (state.assumedLateral ?: plan.first().site.lateral(plan.first().recommended)).toDouble()
            keyframes += carDistance - behind to startLat
            var prevSiteD = carDistance
            var curLat = startLat
            for (a in plan) {
                val target = a.site.lateral(a.recommended).toDouble()
                if (target != curLat) {
                    val changes = abs(target - curLat)
                    val trans = 60.0 * changes * (a.site.laneWidthFactor())
                    val lead = LaneGuidance().leadDistance(a.site.roadKind, changes.toInt().coerceAtLeast(1))
                    val start = max(prevSiteD + 5, a.site.distanceAlong - lead)
                    val end = min(a.site.distanceAlong - 10, start + trans)
                    keyframes += start to curLat
                    keyframes += max(end, start + 1) to target
                    curLat = target
                }
                keyframes += a.site.distanceAlong to target
                prevSiteD = a.site.distanceAlong
            }
            val ribbon = samples.map { s ->
                val lat = interpolate(keyframes, s.d)
                // lateral → lane index from the left in this sample's frame
                val idx = (s.lanes - 1 - s.rightPockets) - lat
                laneCenter(s, idx.coerceIn(-0.5, s.lanes - 0.5))
            }
            out += Line(Polyline.offset(pts) { ribbon[it] }, samples.first().w * 0.72, Kind.RECOMMENDED)

            // Faint highlight of the other lanes that also work, in the last 150 m before the next site.
            val next = plan.first()
            val siteD = next.site.distanceAlong
            val nearIdx = samples.indices.filter { samples[it].d in (siteD - 150)..siteD }
            if (nearIdx.size >= 2) {
                val nearPts = nearIdx.map { pts[it] }
                for (i in 0 until next.site.laneCount) {
                    if (i == next.recommended || !next.optimal.getOrElse(i) { false }) continue
                    val s = samples[nearIdx.first()]
                    out += Line(Polyline.offset(nearPts) { laneCenter(s, i.toDouble()) }, s.w * 0.6, Kind.ALLOWED)
                }
            }
        }
        return out
    }

    private fun LaneSite.laneWidthFactor() = when (roadKind) {
        RoadKind.MOTORWAY -> 2.5
        RoadKind.TRUNK -> 2.0
        RoadKind.RURAL -> 1.5
        RoadKind.URBAN -> 1.0
    }

    /** Piecewise interpolation with smoothstep easing between keyframes (sorted by distance). */
    private fun interpolate(k: List<Pair<Double, Double>>, d: Double): Double {
        if (d <= k.first().first) return k.first().second
        for (i in 1 until k.size) {
            val (d1, v1) = k[i]
            val (d0, v0) = k[i - 1]
            if (d <= d1) {
                if (d1 - d0 < 1e-6) return v1
                val t = ((d - d0) / (d1 - d0)).coerceIn(0.0, 1.0)
                val e = t * t * (3 - 2 * t)
                return v0 + (v1 - v0) * e
            }
        }
        return k.last().second
    }
}
