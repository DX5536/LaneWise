package de.lanewise.search

import de.lanewise.core.geo.LatLon
import de.lanewise.routing.OsrmRoutingProvider
import de.lanewise.routing.RouteRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Opt-in check of smart stops against the live OSRM / Overpass servers (needs internet):
 *   LANEWISE_LIVE=1 gradlew testDebugUnitTest --tests '*StopFinderLiveTest*'
 * Hannover Hbf → Langenhagen, "Sparkasse" and "Tankstelle": prints the candidates with the minutes they add.
 */
class StopFinderLiveTest {

    @Test
    fun serverHealth() = runBlocking {
        assumeTrue(System.getenv("LANEWISE_LIVE") == "1")
        val r = de.lanewise.core.net.ServerHealth.probe(OsrmRoutingProvider.DEFAULT_URL)
        println("HEALTH overall=${r.overall} overpass=${r.overpass} routing=${r.routing} order=${r.overpassOrder}")
    }

    @Test
    fun sparkasseAlongTheRoute() = runBlocking {
        assumeTrue(System.getenv("LANEWISE_LIVE") == "1")
        val from = LatLon(52.3766, 9.7410)
        val to = LatLon(52.4396, 9.7395)
        val osrm = OsrmRoutingProvider()
        val route = osrm.route(RouteRequest(from, to, alternatives = false)).first()
        val finder = StopFinder { osrm.durations(it) }
        for (q in listOf("Sparkasse", "Tankstelle")) {
            val found = finder.find(q, route.geometry, listOf(from, to))
            println("$q: ${found.size} candidates along ${"%.1f".format(route.distance / 1000)} km")
            found.forEach { println("  +%.1f min  %-28s %s  (%s, leg %d)".format(it.detourSeconds / 60, it.place.title.take(28),
                it.place.subtitle, it.place.category, it.insertAt)) }
            assertTrue("$q: nothing found", found.isNotEmpty())
            assertTrue("$q: not sorted", found.zipWithNext().all { (a, b) -> a.detourSeconds <= b.detourSeconds })
        }
        // With a stop on the way, the best leg for a candidate is one of the two legs.
        val via = route.geometry.pointAt(route.distance / 2)
        val withStop = finder.find("Sparkasse", route.geometry, listOf(from, via, to))
        assertTrue(withStop.all { it.insertAt in 0..1 })
    }
}
