package de.lanewise.search

import de.lanewise.core.geo.Geo
import de.lanewise.core.geo.LatLon
import de.lanewise.core.geo.Polyline
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StopFinderTest {

    private val start = LatLon(52.37, 9.73)
    private val route = Polyline(listOf(start, Geo.destination(start, 0.0, 6000.0)))

    /** Times (s) between start (0), destination (1) and two candidates: A near the start but off the way, B on the way. */
    private val table = arrayOf(
        //            start  dest   A     B
        doubleArrayOf(0.0, 600.0, 120.0, 300.0),  // start
        doubleArrayOf(600.0, 0.0, 700.0, 300.0),  // dest
        doubleArrayOf(120.0, 700.0, 0.0, 400.0),  // A
        doubleArrayOf(300.0, 300.0, 400.0, 0.0),  // B
    )

    @Test
    fun theStopOnTheWayBeatsTheNearestOne() {
        assertEquals(0 to 220.0, StopFinder.bestInsertion(table, 2, 2))   // A: 120 + 700 - 600
        assertEquals(0 to 0.0, StopFinder.bestInsertion(table, 2, 3))     // B: 300 + 300 - 600
    }

    @Test
    fun cheapestLegWithStops() {
        // start → stop → dest; the candidate is right next to the stop, after it on the way.
        val t = arrayOf(
            doubleArrayOf(0.0, 300.0, 600.0, 310.0),
            doubleArrayOf(300.0, 0.0, 300.0, 20.0),
            doubleArrayOf(600.0, 300.0, 0.0, 290.0),
            doubleArrayOf(310.0, 20.0, 290.0, 0.0),
        )
        val (leg, add) = StopFinder.bestInsertion(t, 3, 3)
        assertEquals(1, leg)
        assertEquals(10.0, add, 1e-9)
    }

    @Test
    fun queryCoversCategoriesAndNamesSafely() {
        val q = StopFinder.buildQuery("Tankstelle", route)!!
        assertTrue(q, q.contains("[amenity=fuel]"))
        val n = StopFinder.buildQuery("Sparkasse\"];out;", route)!!
        assertTrue(n, n.contains("[\"name\"~\"Sparkasseout\",i]"))
        assertNull(StopFinder.buildQuery(" ", route))
    }

    @Test
    fun parsesNodesAndAreas() = runBlocking {
        val a = Geo.destination(start, 90.0, 900.0)              // 900 m east of the start
        val b = Geo.destination(Geo.destination(start, 0.0, 3000.0), 90.0, 30.0)   // on the way
        val json = Json.parseToJsonElement("""{"elements":[
            {"type":"node","lat":${a.lat},"lon":${a.lon},"tags":{"amenity":"bank","name":"Sparkasse","addr:street":"Musterstraße","addr:housenumber":"1"}},
            {"type":"way","center":{"lat":${b.lat},"lon":${b.lon}},"tags":{"amenity":"bank","name":"Sparkasse"}}]}""")
        val places = StopFinder.parse(json)
        assertEquals(2, places.size)
        assertEquals("Musterstraße 1", places[0].subtitle)
    }
}
