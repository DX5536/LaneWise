package de.lanewise.osm

import de.lanewise.core.geo.Geo
import de.lanewise.core.geo.LatLon
import de.lanewise.lane.LaneRibbonBuilder.Kind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class StreetLanesTest {

    private val c = LatLon(52.38, 9.74)
    private fun p(northM: Double, eastM: Double) = Geo.destination(Geo.destination(c, 0.0, northM), 90.0, eastM)

    /** Browse map: edges and dividers of one street stop where they enter another street, they never cross it. */
    @Test
    fun markingsDoNotRunAcrossACrossingStreet() = runBlocking {
        val ew = OsmStreet(listOf(p(0.0, -150.0), c, p(0.0, 150.0)),
            mapOf("highway" to "primary", "lanes" to "4"), id = 1, nodes = listOf(10, 2, 11))
        val ns = OsmStreet(listOf(p(-150.0, 0.0), c, p(150.0, 0.0)),
            mapOf("highway" to "secondary", "lanes" to "2"), id = 2, nodes = listOf(20, 2, 21))
        val shapes = StreetLanes.build(listOf(ew, ns))
        val lines = shapes.filter { !it.polygon && it.kind in setOf(Kind.EDGE, Kind.DIVIDER, Kind.DIVIDER_SOLID) }
        assertTrue(lines.isNotEmpty())
        // The crossing area is roughly ±7 m (primary, 4 lanes) by ±4 m (secondary, 2 lanes) around the centre.
        val inside = lines.flatMap { it.points }.filter { q ->
            Geo.distance(q, LatLon(q.lat, c.lon)) < 3.0 && Geo.distance(q, LatLon(c.lat, q.lon)) < 5.5
        }
        assertTrue("${inside.size} marking points inside the crossing", inside.isEmpty())
    }

    /** A plain continuation of the same road (way split, node of degree 2) keeps its lines connected. */
    @Test
    fun continuationIsNotClipped() = runBlocking {
        val a = OsmStreet(listOf(p(0.0, -150.0), c), mapOf("highway" to "primary", "lanes" to "2"), id = 1, nodes = listOf(10, 2))
        val b = OsmStreet(listOf(c, p(0.0, 150.0)), mapOf("highway" to "primary", "lanes" to "2"), id = 2, nodes = listOf(2, 11))
        val edges = StreetLanes.build(listOf(a, b)).filter { it.kind == Kind.EDGE }
        assertTrue("expected 4 unbroken edges, got ${edges.size}", edges.size == 4)
    }
}
