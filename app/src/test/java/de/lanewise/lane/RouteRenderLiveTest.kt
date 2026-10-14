package de.lanewise.lane

import de.lanewise.core.geo.Geo
import de.lanewise.core.geo.LatLon
import de.lanewise.core.model.Route
import de.lanewise.routing.OsrmRoutingProvider
import de.lanewise.routing.RouteRequest
import de.lanewise.speed.SpeedLimitProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos

/**
 * Opt-in check against the real route the owner drives (needs internet):
 *   LANEWISE_LIVE=1 gradlew testDebugUnitTest --tests '*RouteRenderLiveTest*'
 * Celler Straße north → left into Herschelstraße → right into Arndtstraße. Prints problems instead of guessing.
 */
class RouteRenderLiveTest {

    @Test
    fun ownersRouteRendersCleanly() = runBlocking {
        assumeTrue(System.getenv("LANEWISE_LIVE") == "1")
        // Owner's test drive (Musterstraße 1, Hannover → Musterweg 3, Langenhagen), second route option.
        // LANEWISE_FROM / LANEWISE_TO = "lat,lon" check any other route.
        fun env(k: String, d: LatLon) = System.getenv(k)?.split(',')?.let { LatLon(it[0].trim().toDouble(), it[1].trim().toDouble()) } ?: d
        val req = RouteRequest(env("LANEWISE_FROM", LatLon(52.3784754, 9.7340973)), env("LANEWISE_TO", LatLon(52.4380884, 9.7052888)),
            alternatives = true)
        val routes = OsrmRoutingProvider().route(req)
        println("${routes.size} routes: " + routes.joinToString { "${it.distance.toInt()} m" })
        var route: Route = routes.getOrElse(System.getenv("LANEWISE_ALT")?.toIntOrNull() ?: 1) { routes.first() }
        val c = runCatching { SpeedLimitProvider().corridor(route.geometry, 0.0, route.distance) }
            .onFailure { println("corridor unavailable (${it.message}) - checking without OSM lane counts") }.getOrNull()
        if (c != null) route = route.copy(laneChanges = c.laneChanges, bikeLanes = c.bikeLanes).withLaneCounts(c.laneCounts)
        println("route ${route.distance.toInt()} m, ${route.laneSites.size} lane sites, ${route.laneCounts.size} lane-count spans")
        route.steps.forEach { st -> println("  step @${st.distanceAlong.toInt()} ${st.maneuver.type} ${st.maneuver.modifier} ${st.name}") }
        route.laneSites.forEach { println("  site @${it.distanceAlong.toInt()} lanes=${it.lanes} turn=${it.turn} maneuver=${it.isManeuver} valid=${it.providerValid}") }

        val road = (0 until (route.distance / 1000).toInt() + 1).flatMap {
            LaneRibbonBuilder.buildRoad(route, it * 1000.0, minOf(route.distance, (it + 1) * 1000.0))
        }
        val surfaces = road.filter { it.kind == LaneRibbonBuilder.Kind.SURFACE }.map { it.points }

        var problems = 0
        // 1) Live highlight outside the pre-built road.
        val guidance = LaneGuidance()
        var car = 20.0
        while (car < route.distance - 50) {
            val state = guidance.update(route.laneSites, car, avoidLaneChanges = true, keepRight = true, route.laneChanges,
                route::laneCountAt.takeIf { route.laneCounts.isNotEmpty() })
            val live = LaneRibbonBuilder.build(route, state, car)
            for (sh in live.shapes.filter { it.kind != LaneRibbonBuilder.Kind.TURN_ARROW }) {
                val out = sh.points.filter { p -> surfaces.none { inside(it, p) } && nearestOffset(route, p) > 1.0 }
                if (out.size > 2) {
                    problems++
                    val d = route.geometry.project(out.first())?.distanceAlong?.toInt()
                    println("car ${car.toInt()}: ${sh.kind} has ${out.size} points outside the road (first at route ${d} m)")
                }
            }
            car += 37.0
        }
        // 2) Lines that run backwards against the route (offset loops on the inside of tight turns).
        for (sh in road.filter { !it.polygon && it.kind != LaneRibbonBuilder.Kind.STOP }) {
            val pts = sh.points
            for (i in 0 until pts.size - 1) {
                if (Geo.distance(pts[i], pts[i + 1]) < 0.3) continue
                val pr = route.geometry.project(pts[i]) ?: continue
                val seg = Geo.bearing(pts[i], pts[i + 1])
                if (abs(Geo.bearingDelta(seg, route.geometry.bearingAt(pr.distanceAlong))) > 100) {
                    problems++
                    println("${sh.kind} runs backwards at route ${pr.distanceAlong.toInt()} m")
                    break
                }
            }
        }
        // 3) Lane advice at turns: the recommended lane must allow the turn.
        val g2 = LaneGuidance()
        for (st in route.steps.filter { it.maneuver.type != de.lanewise.core.model.ManeuverType.DEPART }) {
            val state = g2.update(route.laneSites, st.distanceAlong - 60, true, true, route.laneChanges,
                route::laneCountAt.takeIf { route.laneCounts.isNotEmpty() })
            val a = state.nextAdvice ?: continue
            println("  before step @${st.distanceAlong.toInt()} ${st.maneuver.modifier}: next site @${a.site.distanceAlong.toInt()} " +
                "lanes=${a.site.lanes} turn=${a.site.turn} rec=${a.recommended}")
        }
        // 4) Trace of the guidance while driving (LANEWISE_TRACE=1): what the lane bar and hint would show.
        if (System.getenv("LANEWISE_TRACE") == "1") {
            val g3 = LaneGuidance()
            var d = 0.0
            while (d < route.distance) {
                val s = g3.update(route.laneSites, d, true, true, route.laneChanges,
                    route::laneCountAt.takeIf { route.laneCounts.isNotEmpty() })
                val a = s.nextAdvice
                if (a != null && a.site.distanceAlong - d < 400) println("  car ${d.toInt()} lanesHere=${route.laneCountAt(d)} assumed=${s.assumedLateral} " +
                    "site@${a.site.distanceAlong.toInt()} ${a.site.lanes} turn=${a.site.turn} man=${a.site.isManeuver} rec=${a.recommended} " +
                    "chg=${a.changeFromPrevious} pending=${s.pendingChange?.let { "${it.lanes}@${it.startAt.toInt()}" }}")
                d += 20.0
            }
        }
        println("problems: $problems")
    }

    private fun nearestOffset(route: Route, p: LatLon) = abs(route.geometry.project(p)?.signedOffset ?: 0.0) - 12.0

    private fun inside(poly: List<LatLon>, p: LatLon): Boolean {
        var inside = false
        var j = poly.size - 1
        val k = cos(Math.toRadians(p.lat))
        for (i in poly.indices) {
            val a = poly[i]; val b = poly[j]
            if ((a.lat > p.lat) != (b.lat > p.lat) &&
                p.lon * k < ((b.lon - a.lon) * (p.lat - a.lat) / (b.lat - a.lat) + a.lon) * k) inside = !inside
            j = i
        }
        return inside
    }
}
