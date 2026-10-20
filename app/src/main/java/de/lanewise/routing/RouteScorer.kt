package de.lanewise.routing

import de.lanewise.core.model.IncidentKind
import de.lanewise.core.model.Route
import de.lanewise.core.model.TrafficIncident
import de.lanewise.lane.LaneDir
import kotlin.math.abs

/**
 * Picks the best route among alternatives using live German traffic data on top of the provider's
 * own duration (OSRM has no traffic; TomTom already includes it, so only incidents it can't know about
 * — e.g. Autobahn GmbH closures — add penalties there).
 *
 * Also adds a small "lane comfort" term: routes with many forced lane changes are slightly penalised,
 * which fits the app's goal of calm, lane-change-light driving.
 */
object RouteScorer {

    fun score(routes: List<Route>, incidents: List<TrafficIncident>, preferFewLaneChanges: Boolean): List<Route> =
        routes.map { r ->
            val hits = incidents.filter { onRoute(r, it) }
            var penalty = 0.0
            var delay = 0.0
            for (i in hits) {
                // A TomTom traffic ETA already contains TomTom's own incidents; don't count them twice.
                if ((r.trafficEta || r.provider == "tomtom") && i.source == "TomTom") continue
                // Real expected delays (they go into the ETA too) vs. penalties that only steer the route choice: a
                // closure the route still uses has no known delay (owner: "2 h 27 min" for 20 km with closures matched).
                val d = when {
                    i.blocked || i.kind == IncidentKind.CLOSURE -> 0.0
                    i.kind == IncidentKind.ACCIDENT -> (i.delaySeconds ?: 420).toDouble()
                    i.kind == IncidentKind.JAM -> if (r.provider == "tomtom" || r.trafficEta) 0.0 else (i.delaySeconds ?: 300).toDouble()
                    else -> 0.0
                }
                delay += d
                penalty += when {
                    i.blocked || i.kind == IncidentKind.CLOSURE -> 1800.0
                    i.kind == IncidentKind.ROADWORKS -> 60.0
                    i.kind == IncidentKind.ACCIDENT || i.kind == IncidentKind.JAM -> d
                    else -> 30.0
                }
            }
            if (preferFewLaneChanges) penalty += 15.0 * forcedChanges(r)
            r.copy(trafficPenalty = penalty, trafficDelay = delay, incidents = hits)
        }.sortedBy { it.effectiveDuration }

    /**
     * An incident is on the route only if it runs *along* it: at least 30 m of its line within 20 m of the route and
     * parallel to it (either direction). Touching the route is not enough — closed side streets crossing it at a
     * junction added 30 min each (owner's screenshot: "6 closures", +182 min on a 13 km town route).
     * Point incidents (no line) must lie within 20 m.
     */
    internal fun onRoute(r: Route, i: TrafficIncident): Boolean {
        val line = i.geometry
        if (line.size < 2) return r.geometry.project(i.location)?.let { abs(it.signedOffset) < 20.0 } == true
        var along = 0.0
        for (k in 0 until line.size - 1) {
            val a = line[k]; val b = line[k + 1]
            val len = de.lanewise.core.geo.Geo.distance(a, b)
            if (len < 0.5) continue
            val n = kotlin.math.ceil(len / 10.0).toInt()
            val segBearing = de.lanewise.core.geo.Geo.bearing(a, b)
            for (j in 0 until n) {
                val t = (j + 0.5) / n
                val p = de.lanewise.core.geo.LatLon(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)
                val pr = r.geometry.project(p) ?: continue
                if (abs(pr.signedOffset) > 20.0) continue
                val diff = abs(de.lanewise.core.geo.Geo.bearingDelta(segBearing, r.geometry.bearingAt(pr.distanceAlong)))
                if (diff < 35.0 || diff > 145.0) along += len / n
            }
        }
        return along >= 30.0
    }

    /** Rough count of sites where the route forces the driver out of the through lanes. */
    private fun forcedChanges(r: Route): Int = r.laneSites.count { s ->
        val allowed = s.allowed()
        allowed.count { it } <= s.laneCount / 2 && s.turn != LaneDir.STRAIGHT
    }
}
