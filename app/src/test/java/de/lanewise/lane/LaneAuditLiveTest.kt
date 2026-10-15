package de.lanewise.lane

import de.lanewise.core.geo.Geo
import de.lanewise.core.geo.LatLon
import de.lanewise.core.model.Route
import de.lanewise.routing.OsrmRoutingProvider
import de.lanewise.routing.RouteRequest
import de.lanewise.speed.SpeedLimitProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * Opt-in audit of the lane advice on many random routes in Region Hannover (needs internet):
 *   LANEWISE_LIVE=1 LANEWISE_AUDIT=1 gradlew testDebugUnitTest --tests '*LaneAuditLiveTest*'
 * `LANEWISE_AUDIT_N` routes (default 20), `LANEWISE_AUDIT_SEED` (default 1). One route polished by hand says little —
 * this drives every route like the demo and flags what a driver would call wrong:
 *  - ZIGZAG: two change instructions in opposite directions within 800 m (A 37 → A 7 before the fix),
 *  - STRIP_ZIGZAG: the strip moves one way and back between two junctions without a turn in between,
 *  - LATE: a change instruction that starts too close to its junction,
 *  - KEEP_RIGHT: on a motorway / expressway the strip stays left of the right lane for > 1.5 km with no reason ahead,
 *  - NOT_ALLOWED: the recommended lane cannot be used for the route there.
 * Prints a summary per route and every finding with route distance, road and position.
 */
class LaneAuditLiveTest {

    private data class Finding(val kind: String, val at: Double, val road: String, val pos: LatLon, val detail: String)

    @Test
    fun auditRandomRoutes() = runBlocking {
        assumeTrue(System.getenv("LANEWISE_LIVE") == "1" && System.getenv("LANEWISE_AUDIT") == "1")
        val n = System.getenv("LANEWISE_AUDIT_N")?.toIntOrNull() ?: 20
        val rnd = Random(System.getenv("LANEWISE_AUDIT_SEED")?.toIntOrNull() ?: 1)
        val center = LatLon(52.3759, 9.7320)
        var routesDone = 0
        var total = 0
        val byKind = HashMap<String, Int>()
        var attempts = 0
        // LANEWISE_AUDIT_ROUTES="lat,lon>lat,lon;…" re-checks given routes (e.g. the ones with findings) instead of random ones.
        fun ll(t: String) = t.split(',').let { LatLon(it[0].trim().toDouble(), it[1].trim().toDouble()) }
        val fixed = System.getenv("LANEWISE_AUDIT_ROUTES")?.split(';')?.filter { it.contains('>') }?.map { p ->
            p.split('>').let { ll(it[0]) to ll(it[1]) }
        }
        val count = fixed?.size ?: n
        while (routesDone < count && attempts < count * 3) {
            attempts++
            // Mix of town trips and longer trips that use the Autobahn ring (A 2 / A 7 / A 37 / A 352).
            fun pick(maxKm: Double) = Geo.destination(center, rnd.nextDouble(0.0, 360.0), rnd.nextDouble(1500.0, maxKm * 1000))
            val (from, to) = fixed?.get(routesDone) ?: (pick(if (routesDone % 2 == 0) 8.0 else 22.0) to pick(if (routesDone % 2 == 0) 8.0 else 22.0))
            if (fixed == null && Geo.distance(from, to) < 4000) continue
            val routes = runCatching { OsrmRoutingProvider().route(RouteRequest(from, to, alternatives = false)) }.getOrNull()
            var route = routes?.firstOrNull() ?: continue
            if (route.laneSites.size < 2) continue
            val c = (1..3).firstNotNullOfOrNull {
                runCatching { SpeedLimitProvider().corridor(route.geometry, 0.0, route.distance) }.getOrNull()
                    ?: run { delay(5000); null }
            }
            if (c == null) { println("route ${routesDone + 1}: corridor unavailable, skipped"); continue }
            route = route.copy(laneChanges = c.laneChanges, bikeLanes = c.bikeLanes, sideRoads = c.sideRoads, features = c.features)
                .withLaneCounts(c.laneCounts)
            routesDone++
            val findings = audit(route)
            total += findings.size
            findings.groupingBy { it.kind }.eachCount().forEach { (k, v) -> byKind[k] = (byKind[k] ?: 0) + v }
            val motorwayKm = route.roadSpans.filter { it.kind == RoadKind.MOTORWAY }.sumOf { it.to - it.from } / 1000
            println(String.format(java.util.Locale.US,
                "route %2d: %.5f,%.5f -> %.5f,%.5f  %.1f km (motorway %.1f km), %d lane sites, %d findings",
                routesDone, from.lat, from.lon, to.lat, to.lon, route.distance / 1000, motorwayKm, route.laneSites.size, findings.size))
            findings.forEach {
                println(String.format(java.util.Locale.US, "    %-12s @%6.0f m  %-28s %.5f,%.5f  %s",
                    it.kind, it.at, it.road.take(28), it.pos.lat, it.pos.lon, it.detail))
            }
            delay(1500)   // fair use of the public OSRM / Overpass servers
        }
        println("AUDIT: $routesDone routes, $total findings: $byKind")
    }

    private fun audit(route: Route): List<Finding> {
        val out = ArrayList<Finding>()
        fun road(d: Double) = route.steps.lastOrNull { it.distanceAlong <= d }?.let { it.name.ifBlank { it.ref.orEmpty() } }.orEmpty()
        fun add(kind: String, d: Double, detail: String) = out.add(Finding(kind, d, road(d), route.geometry.pointAt(d), detail))
        val g = LaneGuidance()
        val kindAt = { d: Double -> route.roadSpanAt(d)?.kind }
        val counts = route::laneCountAt.takeIf { route.laneCounts.isNotEmpty() }
        val instructions = LinkedHashMap<Pair<Double, Int>, LaneChangeInstruction>()
        val strip = ArrayList<Pair<Double, Double>>()
        var keepRightRun = 0.0
        var d = 0.0
        while (d < route.distance - 30) {
            val st = g.update(route.laneSites, d, true, true, route.laneChanges, counts, kindAt)
            st.pendingChange?.let { pc -> instructions.putIfAbsent(pc.site.distanceAlong to pc.lanes, pc) }
            val a = st.nextAdvice
            if (a != null && !a.allowed[a.recommended] && abs(a.site.distanceAlong - d) < 15) {
                add("NOT_ALLOWED", d, "site @${a.site.distanceAlong.toInt()} ${a.site.lanes} rec=${a.recommended}")
            }
            val lat = LaneRibbonBuilder.stripLateralAt(route, st, d, d)
            if (lat != null) strip += d to lat
            // Keep right: motorway / expressway, strip left of the right lane, and the next change is not a move left soon.
            val fast = kindAt(d).let { it == RoadKind.MOTORWAY || it == RoadKind.TRUNK }
            val nextLeftSoon = st.pendingChange?.let { it.lanes > 0 && it.startAt - d < 1500 } == true
            val nextSiteSoon = route.laneSites.firstOrNull { it.distanceAlong > d }?.let { it.distanceAlong - d < 900 } == true
            if (fast && lat != null && lat >= 0.9 && !nextLeftSoon && !nextSiteSoon) keepRightRun += 10.0 else keepRightRun = 0.0
            if (keepRightRun == 1500.0) add("KEEP_RIGHT", d - 1500, "strip at lateral ${"%.1f".format(lat)} for 1.5 km on ${kindAt(d)}")
            d += 10.0
        }
        // Instruction checks.
        val list = instructions.values.sortedBy { it.startAt }
        for (i in list.indices) {
            val pc = list[i]
            val minLead = when (pc.site.roadKind) { RoadKind.MOTORWAY -> 300.0; RoadKind.TRUNK -> 200.0; RoadKind.RURAL -> 100.0; else -> 50.0 }
            if (pc.deadline - pc.startAt < minLead * 0.5) {
                add("LATE", pc.startAt, "${pc.lanes} lane(s) only ${(pc.deadline - pc.startAt).toInt()} m before the deadline (site @${pc.site.distanceAlong.toInt()})")
            }
            val next = list.getOrNull(i + 1) ?: continue
            if (Integer.signum(next.lanes) == -Integer.signum(pc.lanes) && next.startAt - pc.startAt < 800) {
                add("ZIGZAG", pc.startAt, "${pc.lanes} for site @${pc.site.distanceAlong.toInt()}, then ${next.lanes} for site @${next.site.distanceAlong.toInt()}")
            }
        }
        // Strip zig-zag between junctions with no real turn in between (same lateral frame).
        val turns = route.laneSites.filter { it.isManeuver && it.turn != LaneDir.STRAIGHT }.map { it.distanceAlong }
        var i = 0
        while (i < strip.size) {
            val (d0, l0) = strip[i]
            var extreme = l0; var extremeAt = d0
            var j = i + 1
            while (j < strip.size && strip[j].first - d0 < 800 && turns.none { it > d0 - 20 && it < strip[j].first + 20 }) {
                val (dj, lj) = strip[j]
                if (abs(lj - l0) > abs(extreme - l0)) { extreme = lj; extremeAt = dj }
                if (abs(extreme - l0) >= 0.9 && abs(lj - l0) < 0.2 && dj > extremeAt) {
                    add("STRIP_ZIGZAG", d0, "lane ${"%.0f".format(l0)} → ${"%.0f".format(extreme)} → ${"%.0f".format(lj)} within ${(dj - d0).toInt()} m")
                    i = j; break
                }
                j++
            }
            i++
        }
        return out
    }
}
