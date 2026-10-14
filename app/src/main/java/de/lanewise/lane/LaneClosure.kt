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
