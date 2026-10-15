package de.lanewise.lane

import de.lanewise.core.geo.Geo
import de.lanewise.core.geo.LatLon
import de.lanewise.core.model.Route
import de.lanewise.core.model.TrafficIncident
import kotlin.math.abs
import kotlin.math.ceil

/**
 * Lanes closed between [from] and [to] (route distance, m); [open] per lane left → right, aligned to the right edge
 * of the drawn road (the shoulder is not part of it). From Autobahn GmbH roadworks (`impact.symbols`).
 */
data class LaneClosure(val from: Double, val to: Double, val open: List<Boolean>) {

    /**
     * The closure as a "no lane change into it" span for the lane planner (like a solid line): an open lane next to a
     * closed one may not move into it; closed lanes may be left. So the advice never sends the car into a closed lane
     * — e.g. into the exit lane while it is still closed by roadworks (A 2 test run) — and keeps right only as far as
     * the rightmost open lane. Lanes are counted from the right edge, like [ChangeSpan].
     */
    /**
     * A checkpoint for the lane planner just before the closure: only the open lanes may be used, so the advice moves
     * the car out of a lane that is about to close in time and keeps right only as far as the rightmost open lane.
     * Not drawn (no stop line / arrows) - for the planner and the lane bar only.
     */
    fun site(kind: RoadKind): LaneSite? {
        if (open.all { it } || open.none { it }) return null
        return LaneSite((from - 20.0).coerceAtLeast(0.0), List(open.size) { LaneDir.STRAIGHT }, open, LaneDir.STRAIGHT,
            isManeuver = false, roadKind = kind)
    }

    fun toChangeSpan(): ChangeSpan? {
        if (open.all { it } || open.none { it }) return null
        val perms = open.indices.map { i ->
            var p = ChangeSpan.LEFT or ChangeSpan.RIGHT
            if (open[i] && i + 1 < open.size && !open[i + 1]) p = p and ChangeSpan.RIGHT.inv()
            if (open[i] && i - 1 >= 0 && !open[i - 1]) p = p and ChangeSpan.LEFT.inv()
            p
        }
        return ChangeSpan(from, to, perms)
    }

    companion object {
        /**
         * Closures of [incidents] that run along [route] in its direction: ≥ 30 m of the incident line within 14 m of
         * the route and heading the same way (the other carriageway of a motorway is ~15–30 m away and opposite).
         */
        fun along(route: Route, incidents: List<TrafficIncident>): List<LaneClosure> = incidents.mapNotNull { i ->
            val open = i.laneOpen ?: return@mapNotNull null
            if (!i.active || i.geometry.size < 2) return@mapNotNull null
            val hits = ArrayList<Double>()
            for (k in 0 until i.geometry.size - 1) {
                val a = i.geometry[k]; val b = i.geometry[k + 1]
                val len = Geo.distance(a, b)
                if (len < 0.5) continue
                val n = ceil(len / 10.0).toInt()
                val bearing = Geo.bearing(a, b)
                for (j in 0 until n) {
                    val t = (j + 0.5) / n
                    val p = LatLon(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)
                    val pr = route.geometry.project(p) ?: continue
                    if (abs(pr.signedOffset) > 14.0) continue
                    if (abs(Geo.bearingDelta(bearing, route.geometry.bearingAt(pr.distanceAlong))) > 35.0) continue
                    hits += pr.distanceAlong
                }
            }
            if (hits.size < 3 || hits.max() - hits.min() < 30.0) null else LaneClosure(hits.min(), hits.max(), open)
        }.sortedBy { it.from }
    }
}
