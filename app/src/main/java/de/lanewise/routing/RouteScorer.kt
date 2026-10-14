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
            for (i in hits) {
                penalty += when {
                    i.blocked || i.kind == IncidentKind.CLOSURE -> 1800.0
                    i.kind == IncidentKind.ACCIDENT -> (i.delaySeconds ?: 420).toDouble()
                    i.kind == IncidentKind.JAM -> if (r.provider == "tomtom" || r.trafficEta) 0.0 else (i.delaySeconds ?: 300).toDouble()
                    i.kind == IncidentKind.ROADWORKS -> 60.0
                    else -> 30.0
                }
            }
            if (preferFewLaneChanges) penalty += 15.0 * forcedChanges(r)
            r.copy(trafficPenalty = penalty, incidents = hits)
        }.sortedBy { it.effectiveDuration }

    private fun onRoute(r: Route, i: TrafficIncident): Boolean {
        val pts = if (i.geometry.isNotEmpty()) listOf(i.geometry.first(), i.location, i.geometry.last()) else listOf(i.location)
        return pts.any { p -> r.geometry.project(p)?.let { abs(it.signedOffset) < 35.0 } == true }
    }

    /** Rough count of sites where the route forces the driver out of the through lanes. */
    private fun forcedChanges(r: Route): Int = r.laneSites.count { s ->
        val allowed = s.allowed()
        allowed.count { it } <= s.laneCount / 2 && s.turn != LaneDir.STRAIGHT
    }
}
