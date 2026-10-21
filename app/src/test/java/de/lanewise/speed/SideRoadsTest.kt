package de.lanewise.speed

import de.lanewise.core.geo.Geo
import de.lanewise.core.geo.LatLon
import de.lanewise.core.geo.Polyline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Carriageways beside the route (owner: only 1 lane drawn on the A 37 although the 2-lane carriageway runs beside it). */
class SideRoadsTest {
    private val a = LatLon(52.42, 9.86)
    private val route = Polyline(listOf(a, Geo.destination(a, 0.0, 1000.0)))
    private fun shifted(east: Double, reverse: Boolean = false): List<LatLon> {
        val p0 = Geo.destination(Geo.destination(a, 90.0, east), 0.0, 100.0)
        val p1 = Geo.destination(p0, 0.0, 700.0)
        return if (reverse) listOf(p1, p0) else listOf(p0, p1)
    }
    private val motorway = mapOf("highway" to "motorway", "lanes" to "2", "oneway" to "yes")

    @Test
    fun onlyParallelSameDirectionCarriageways() {
        val side = SpeedLimitProvider.sideRoads(route, 0.0, 1000.0, listOf(shifted(11.0) to motorway))
        assertEquals(1, side.size)
        assertTrue(Polyline(side[0].geometry).length > 650)
        // The route's own way, the opposite carriageway, far-away roads and two-way streets are not side roads.
        assertTrue(SpeedLimitProvider.sideRoads(route, 0.0, 1000.0, listOf(shifted(0.0) to motorway)).isEmpty())
        assertTrue(SpeedLimitProvider.sideRoads(route, 0.0, 1000.0, listOf(shifted(-20.0, reverse = true) to motorway)).isEmpty())
        assertTrue(SpeedLimitProvider.sideRoads(route, 0.0, 1000.0, listOf(shifted(60.0) to motorway)).isEmpty())
        assertTrue(SpeedLimitProvider.sideRoads(route, 0.0, 1000.0, listOf(shifted(11.0) to mapOf("highway" to "primary"))).isEmpty())
    }
}
