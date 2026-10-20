package de.lanewise.routing

import de.lanewise.core.geo.Geo
import de.lanewise.core.geo.LatLon
import de.lanewise.core.geo.Polyline
import de.lanewise.core.model.IncidentKind
import de.lanewise.core.model.Route
import de.lanewise.core.model.TrafficIncident
import org.junit.Assert.assertEquals
import org.junit.Test

class RouteScorerTest {
    private val a = LatLon(52.38, 9.74)
    private val route = Route("osrm", Polyline(listOf(a, Geo.destination(a, 0.0, 2000.0))), 2000.0, 300.0, emptyList(), emptyList())

    private fun closure(from: LatLon, bearing: Double, len: Double, source: String = "TomTom") = TrafficIncident(
        "x", source, IncidentKind.CLOSURE, "", "", from, listOf(from, Geo.destination(from, bearing, len)), blocked = true)

    /** Owner's screenshot: closed side streets crossing the route added 30 min each. */
    @Test
    fun closedCrossStreetIsNotOnTheRoute() {
        val junction = Geo.destination(a, 0.0, 800.0)
        val side = closure(Geo.destination(junction, 270.0, 150.0), 90.0, 150.0)   // ends at the route
        assertEquals(0.0, RouteScorer.score(listOf(route), listOf(side), false).first().trafficPenalty, 1e-9)
    }

    @Test
    fun closureAlongTheRouteCounts() {
        val along = closure(Geo.destination(a, 0.0, 500.0), 180.0, 200.0, source = "Autobahn GmbH")   // opposite direction, same road
        assertEquals(1800.0, RouteScorer.score(listOf(route), listOf(along), false).first().trafficPenalty, 1e-9)
    }

    @Test
    fun tomTomEtaAlreadyContainsTomTomIncidents() {
        val along = closure(Geo.destination(a, 0.0, 500.0), 0.0, 200.0)
        assertEquals(0.0, RouteScorer.score(listOf(route.copy(trafficEta = true)), listOf(along), false).first().trafficPenalty, 1e-9)
    }

    /** Owner's screenshot: "2 h 27 min" for 20 km - closure penalties steer the route choice but are no delay. */
    @Test
    fun closurePenaltyIsNotInTheEta() {
        val along = closure(Geo.destination(a, 0.0, 500.0), 0.0, 200.0, source = "Autobahn GmbH")
        val jam = TrafficIncident("j", "Autobahn GmbH", IncidentKind.JAM, "", "", Geo.destination(a, 0.0, 900.0),
            listOf(Geo.destination(a, 0.0, 900.0), Geo.destination(a, 0.0, 1100.0)), delaySeconds = 240)
        val r = RouteScorer.score(listOf(route), listOf(along, jam), false).first()
        assertEquals(1800.0 + 240.0, r.trafficPenalty, 1e-9)
        assertEquals(240.0, r.trafficDelay, 1e-9)
        assertEquals(300.0 + 240.0, r.etaDuration, 1e-9)
    }
}
