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
 * Opt-in render stress test on convoluted routes (needs internet):
 *   LANEWISE_LIVE=1 LANEWISE_RENDER=1 gradlew testDebugUnitTest --tests '*RenderAuditLiveTest*'
 * Picks the `LANEWISE_RENDER_N` (20) curviest of many random inner-city routes (curvature + turns per km), builds the
 * road, the live highlight and the car path exactly like the app, and measures what looks jagged on screen:
 *  - KINK: a line bends > 25° at one vertex between short segments (a corner instead of a curve),
 *  - WOBBLE: a line bends one way and back (> 6° each) within 6 m (an S where the road has none),
 *  - FOLD: a line runs backwards against the route (offset loop on the inside of a curve),
 *  - STEP: the road edge jumps sideways by > 0.8 m between neighbouring points,
 *  - CARPATH: the car's path wobbles (> 4° back and forth within 8 m) — the camera follows it, so it reads as lag.
 * `LANEWISE_RENDER_ROUTES="lat,lon>lat,lon;…"` re-checks given routes. Prints a summary per route and the worst spots.
 */
class RenderAuditLiveTest {

    private data class Spot(val kind: String, val at: Double, val what: String, val value: Double)

    @Test
    fun stressCurves() = runBlocking {
        assumeTrue(System.getenv("LANEWISE_LIVE") == "1" && System.getenv("LANEWISE_RENDER") == "1")
        val n = System.getenv("LANEWISE_RENDER_N")?.toIntOrNull() ?: 20
        val rnd = Random(System.getenv("LANEWISE_RENDER_SEED")?.toIntOrNull() ?: 7)
        fun ll(t: String) = t.split(',').let { LatLon(it[0].trim().toDouble(), it[1].trim().toDouble()) }
        val fixed = System.getenv("LANEWISE_RENDER_ROUTES")?.split(';')?.filter { it.contains('>') }?.map { p -> p.split('>').let { ll(it[0]) to ll(it[1]) } }
        val center = LatLon(52.3759, 9.7320)

        // 1) Candidates: random inner-city pairs (2–6 km apart), scored by curvature and turns per km.
        val picked = ArrayList<Route>()
        if (fixed != null) {
            for ((a, b) in fixed) runCatching { OsrmRoutingProvider().route(RouteRequest(a, b, alternatives = false)).first() }.getOrNull()?.let { picked += it }
        } else {
            val cands = ArrayList<Pair<Double, Route>>()
            var tries = 0
            while (cands.size < n * 3 && tries < n * 8) {
                tries++
                val a = Geo.destination(center, rnd.nextDouble(0.0, 360.0), rnd.nextDouble(300.0, 4500.0))
                val b = Geo.destination(a, rnd.nextDouble(0.0, 360.0), rnd.nextDouble(2000.0, 6000.0))
                val r = runCatching { OsrmRoutingProvider().route(RouteRequest(a, b, alternatives = false)).first() }.getOrNull() ?: continue
                cands += curviness(r) to r
                delay(300)
            }
            picked += cands.sortedByDescending { it.first }.take(n).map { it.second }
        }
        var totals = HashMap<String, Int>()
        for ((i, base) in picked.withIndex()) {
            val c = (1..3).firstNotNullOfOrNull {
                runCatching { SpeedLimitProvider().corridor(base.geometry, 0.0, base.distance, base.osmNodes) }.getOrNull() ?: run { delay(4000); null }
            }
            val route = if (c == null) base else base.copy(laneChanges = c.laneChanges, bikeLanes = c.bikeLanes, sideRoads = c.sideRoads,
                features = c.features).withOsmTurnLanes(c.turnLanes.map { it.distanceAlong to it.lanes }).withLaneCounts(c.laneCounts)
            val spots = audit(route)
            spots.groupingBy { it.kind }.eachCount().forEach { (k, v) -> totals[k] = (totals[k] ?: 0) + v }
            val km = route.distance / 1000
            val a = route.geometry.points.first(); val b = route.geometry.points.last()
            println(String.format(java.util.Locale.US, "route %2d: %.5f,%.5f>%.5f,%.5f %.1f km, curv %.0f°/km, %d turns, %s | %s",
                i + 1, a.lat, a.lon, b.lat, b.lon, km, curviness(route), route.steps.size,
                if (c == null) "no OSM" else "${route.laneCounts.size} lane spans",
                spots.groupingBy { it.kind }.eachCount().entries.joinToString { "${it.key}=${it.value}" }))
            spots.sortedByDescending { it.value }.take(6).forEach {
                val p = route.geometry.pointAt(it.at)
                val road = route.steps.lastOrNull { s -> s.distanceAlong <= it.at }?.name.orEmpty()
                println(String.format(java.util.Locale.US, "    %-8s @%6.0f m %-24s %.5f,%.5f  %s %.1f", it.kind, it.at, road.take(24), p.lat, p.lon, it.what, it.value))
            }
            // LANEWISE_RENDER_DUMP=1: an HTML page per route with the road around its worst spots (view in a browser).
            if (System.getenv("LANEWISE_RENDER_DUMP") == "1") dump(route, spots, i + 1)
            delay(1500)
        }
        println("RENDER: ${picked.size} routes: $totals")
    }

    /** Degrees of heading change per km (10 m steps) plus 40 per real turn: how convoluted a route is. */
    private fun curviness(r: Route): Double {
        var sum = 0.0
        var d = 10.0
        while (d < r.distance - 10) { sum += abs(Geo.bearingDelta(r.geometry.bearingAt(d - 10), r.geometry.bearingAt(d))); d += 10 }
        val turns = r.steps.count { it.maneuver.modifier != de.lanewise.core.model.Modifier.STRAIGHT }
        return (sum + 40.0 * turns) / (r.distance / 1000).coerceAtLeast(0.5)
    }

    private fun audit(route: Route): List<Spot> {
        val out = ArrayList<Spot>()
        val road = (0 until (route.distance / 1000).toInt() + 1).flatMap {
            LaneRibbonBuilder.buildRoad(route, it * 1000.0, minOf(route.distance, (it + 1) * 1000.0), true,
                LaneRibbonBuilder.RoadExtras(furniture = true))
        }
        val lines = road.filter { !it.polygon && it.kind in setOf(LaneRibbonBuilder.Kind.EDGE, LaneRibbonBuilder.Kind.DIVIDER, LaneRibbonBuilder.Kind.DIVIDER_SOLID) }
        for (sh in lines) checkLine(route, sh.points, sh.kind.name, out)
        // Live highlight + car path, every 50 m like a drive.
        val g = LaneGuidance()
        var car = 20.0
        val seenStrip = HashSet<Int>()
        while (car < route.distance - 40) {
            val st = g.update(route.laneSites, car, true, true, route.laneChanges, route::laneCountAt.takeIf { route.laneCounts.isNotEmpty() },
                { d -> route.roadSpanAt(d)?.kind })
            val r = LaneRibbonBuilder.build(route, st, car)
            // Car path: the next 150 m.
            val path = r.lanePath.filter { it.first in car..(car + 150) }.map { it.second }
            checkWobble(route, path, "CARPATH", "car", 4.0, 8.0, out, seenStrip)
            car += 50.0
        }
        return out
    }

    private fun checkLine(route: Route, pts: List<LatLon>, what: String, out: MutableList<Spot>) {
        if (pts.size < 3) return
        // Headings of segments >= 0.3 m.
        val segs = ArrayList<Triple<LatLon, Double, Double>>()   // start, heading, length
        for (i in 0 until pts.size - 1) {
            val len = Geo.distance(pts[i], pts[i + 1])
            if (len >= 0.3) segs += Triple(pts[i], Geo.bearing(pts[i], pts[i + 1]), len)
        }
        var lastKinkAt = -1e9
        for (i in 1 until segs.size) {
            val dh = Geo.bearingDelta(segs[i - 1].second, segs[i].second)
            val at = route.geometry.project(segs[i].first)?.distanceAlong ?: continue
            if (abs(dh) > 25 && segs[i - 1].third < 4 && segs[i].third < 4 && at - lastKinkAt > 20) {
                out += Spot("KINK", at, what, abs(dh)); lastKinkAt = at
            }
            // Backwards against the route.
            val rb = route.geometry.bearingAt(at)
            if (abs(Geo.bearingDelta(segs[i].second, rb)) > 100 && segs[i].third > 0.5 && at - lastKinkAt > 20) {
                out += Spot("FOLD", at, what, abs(Geo.bearingDelta(segs[i].second, rb))); lastKinkAt = at
            }
        }
        checkWobble(route, pts, "WOBBLE", what, 6.0, 6.0, out, HashSet())
        if (what == "EDGE") {
            // Sideways steps of the edge relative to the route line.
            var prev: Double? = null; var prevAt = 0.0
            for (p in pts) {
                val pr = route.geometry.project(p) ?: continue
                val off = pr.signedOffset
                if (prev != null && abs(pr.distanceAlong - prevAt) < 2.0 && abs(off - prev) > 0.8) out += Spot("STEP", pr.distanceAlong, what, abs(off - prev))
                prev = off; prevAt = pr.distanceAlong
            }
        }
    }

    /** One way and back by more than [deg] each within [within] metres. */
    private fun checkWobble(route: Route, pts: List<LatLon>, kind: String, what: String, deg: Double, within: Double, out: MutableList<Spot>, seen: HashSet<Int>) {
        if (pts.size < 4) return
        val h = ArrayList<Pair<Double, LatLon>>()   // heading of ~1.5 m chords
        var i = 0
        while (i < pts.size - 1) {
            var j = i + 1
            while (j < pts.size - 1 && Geo.distance(pts[i], pts[j]) < 1.5) j++
            if (Geo.distance(pts[i], pts[j]) >= 1.0) h += Geo.bearing(pts[i], pts[j]) to pts[i]
            i = j
        }
        for (k in 1 until h.size - 1) {
            val d1 = Geo.bearingDelta(h[k - 1].first, h[k].first)
            for (m in k + 1 until minOf(h.size, k + (within / 1.5).toInt() + 2)) {
                val d2 = Geo.bearingDelta(h[m - 1].first, h[m].first)
                if (abs(d1) > deg && abs(d2) > deg && Integer.signum(d1.toInt()) == -Integer.signum(d2.toInt())) {
                    val at = route.geometry.project(h[k].second)?.distanceAlong ?: break
                    val key = (at / 25).toInt()
                    if (seen.add(key)) out += Spot(kind, at, what, minOf(abs(d1), abs(d2)))
                    break
                }
            }
        }
    }

    /**
     * Writes build/reports/lanewise/render_<n>_<k>.json: road shapes, live highlight, car path and route line ±60 m
     * around the 4 worst spots, in metres around the spot (x east, y north). `tools/render_dump.py` draws them as PNG.
     */
    private fun dump(route: Route, spots: List<Spot>, n: Int) {
        val dir = java.io.File("build/reports/lanewise").apply { mkdirs() }
        val worst = spots.sortedByDescending { it.value }.distinctBy { (it.at / 100).toInt() }.take(4)
        val shapes = (0 until (route.distance / 1000).toInt() + 1).flatMap {
            LaneRibbonBuilder.buildRoad(route, it * 1000.0, minOf(route.distance, (it + 1) * 1000.0), true, LaneRibbonBuilder.RoadExtras(furniture = true))
        }
        val g = LaneGuidance()
        for ((k, sp) in worst.withIndex()) {
            val c = route.geometry.pointAt(sp.at)
            val kx = 111320.0 * kotlin.math.cos(Math.toRadians(c.lat)); val ky = 110540.0
            fun xy(p: LatLon) = "[%.2f,%.2f]".format(java.util.Locale.US, (p.lon - c.lon) * kx, (p.lat - c.lat) * ky)
            fun near(p: LatLon) = abs((p.lon - c.lon) * kx) < 90 && abs((p.lat - c.lat) * ky) < 90
            val st = g.update(route.laneSites, sp.at - 40, true, true, route.laneChanges, route::laneCountAt.takeIf { route.laneCounts.isNotEmpty() },
                { d -> route.roadSpanAt(d)?.kind })
            val live = LaneRibbonBuilder.build(route, st, sp.at - 40)
            val items = (shapes + live.shapes.filter { it.kind != LaneRibbonBuilder.Kind.TURN_ARROW })
                .filter { sh -> sh.points.any(::near) }
                .joinToString(",") { sh -> "{\"k\":\"${sh.kind}\",\"poly\":${sh.polygon},\"p\":[${sh.points.joinToString(",") { xy(it) }}]}" }
            val routeLine = route.geometry.slice(sp.at - 60, sp.at + 60).joinToString(",") { xy(it) }
            val path = live.lanePath.map { it.second }.filter(::near).joinToString(",") { xy(it) }
            val title = "${sp.kind} ${sp.what} ${"%.0f".format(java.util.Locale.US, sp.value)} @${sp.at.toInt()} m ${"%.5f,%.5f".format(java.util.Locale.US, c.lat, c.lon)}"
            java.io.File(dir, "render_${n}_$k.json").writeText(
                "{\"title\":\"$title\",\"shapes\":[$items],\"route\":[$routeLine],\"path\":[$path]}")
        }
    }
}
