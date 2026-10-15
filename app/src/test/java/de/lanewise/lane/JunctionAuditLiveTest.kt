package de.lanewise.lane

import de.lanewise.core.geo.LatLon
import de.lanewise.core.model.FeatureKind
import de.lanewise.core.model.Route
import de.lanewise.routing.OsrmRoutingProvider
import de.lanewise.routing.RouteRequest
import de.lanewise.speed.SpeedLimitProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Locale
import kotlin.math.abs

/**
 * Opt-in junction audit against the official aerial photos (needs internet):
 *   LANEWISE_LIVE=1 LANEWISE_JUNCTIONS=1 LANEWISE_FROM=lat,lon LANEWISE_TO=lat,lon [LANEWISE_VIA="lat,lon;…"]
 *   gradlew testDebugUnitTest --tests '*JunctionAuditLiveTest*'
 * then `python tools/aerial_lanes/junction_audit.py` draws every junction on the DOP20 photo (20 cm) with the app's
 * lanes, arrows, stop line and recommended lane on top - so the painted arrows on the road can be compared with what
 * the app tells the driver. Every lane site and every traffic light (also those without lane data) is exported to
 * `build/reports/lanewise/junctions/j_<n>.json`.
 */
class JunctionAuditLiveTest {

    @Test
    fun exportJunctions() = runBlocking {
        assumeTrue(System.getenv("LANEWISE_LIVE") == "1" && System.getenv("LANEWISE_JUNCTIONS") == "1")
        fun ll(t: String) = t.split(',').let { LatLon(it[0].trim().toDouble(), it[1].trim().toDouble()) }
        val from = ll(System.getenv("LANEWISE_FROM")!!)
        val to = ll(System.getenv("LANEWISE_TO")!!)
        val via = System.getenv("LANEWISE_VIA")?.split(';')?.filter { it.contains(',') }?.map(::ll).orEmpty()
        var route: Route = OsrmRoutingProvider().route(RouteRequest(from, to, alternatives = false, via = via)).first()
        val c = (1..3).firstNotNullOfOrNull {
            runCatching { SpeedLimitProvider().corridor(route.geometry, 0.0, route.distance, route.osmNodes) }.getOrNull()
                ?: run { delay(5000); null }
        }
        if (c != null) {
            route = route.copy(laneChanges = c.laneChanges, bikeLanes = c.bikeLanes, sideRoads = c.sideRoads, features = c.features)
                .withOsmTurnLanes(c.turnLanes.map { it.distanceAlong to it.lanes }).withLaneCounts(c.laneCounts)
        } else println("corridor unavailable - OSRM lanes only")
        println("route ${route.distance.toInt()} m, ${route.laneSites.size} lane sites, " +
            "${route.features.count { it.kind == FeatureKind.TRAFFIC_SIGNALS }} signals")

        // Junctions: lane sites (first node of a junction) and signals without lane data nearby.
        val sites = route.laneSites.filter { s -> route.laneSites.none { it.distanceAlong < s.distanceAlong && s.distanceAlong - it.distanceAlong < 35 } }
        val signals = route.features.filter { it.kind == FeatureKind.TRAFFIC_SIGNALS }.sortedBy { it.distanceAlong }
            .filter { sig -> route.laneSites.none { abs(it.distanceAlong - sig.distanceAlong) < 40 } }
            .fold(ArrayList<Double>()) { acc, f -> if (acc.none { abs(it - f.distanceAlong) < 50 }) acc += f.distanceAlong; acc }
        val junctions = (sites.map { it.distanceAlong to it } + signals.map { it to null }).sortedBy { it.first }

        val dir = File("build/reports/lanewise/junctions").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val road = (0 until (route.distance / 1000).toInt() + 1).flatMap {
            LaneRibbonBuilder.buildRoad(route, it * 1000.0, minOf(route.distance, (it + 1) * 1000.0), true, LaneRibbonBuilder.RoadExtras(furniture = true))
        }
        val g = LaneGuidance()
        val kindAt = { d: Double -> route.roadSpanAt(d)?.kind }
        val counts = route::laneCountAt.takeIf { route.laneCounts.isNotEmpty() }
        // Drive the route once (like the app) so the advice at each junction has its history.
        var car = 0.0
        val states = HashMap<Int, LaneState>()
        val probes = junctions.map { (d, _) -> (d - 60).coerceAtLeast(0.0) }.toSortedSet()
        for (p in probes) {
            while (car < p) { g.update(route.laneSites, car, true, true, route.laneChanges, counts, kindAt); car += 10.0 }
            states[p.toInt()] = g.update(route.laneSites, p, true, true, route.laneChanges, counts, kindAt)
        }
        fun pt(p: LatLon) = "[%.7f,%.7f]".format(Locale.US, p.lat, p.lon)
        for ((n, j) in junctions.withIndex()) {
            val (d, site) = j
            val stop = route.geometry.pointAt((d - 2).coerceAtLeast(0.0))
            val heading = route.geometry.bearingAt((d - 15).coerceAtLeast(0.0))
            val probe = (d - 60).coerceAtLeast(0.0)
            val st = states[probe.toInt()] ?: LaneState.EMPTY
            val live = LaneRibbonBuilder.build(route, st, probe)
            fun near(p: LatLon) = de.lanewise.core.geo.Geo.distance(p, stop) < 45
            val shapes = (road + live.shapes).filter { sh -> sh.points.any(::near) }
                .joinToString(",") { sh -> "{\"k\":\"${sh.kind}\",\"poly\":${sh.polygon},\"p\":[${sh.points.joinToString(",") { pt(it) }}]}" }
            val advice = st.plan.firstOrNull { it.site == site }
            val step = route.steps.firstOrNull { abs(it.distanceAlong - d) < 30 }
            val name = (step ?: route.steps.lastOrNull { it.distanceAlong <= d })?.let { it.name.ifBlank { it.ref.orEmpty() } }.orEmpty()
            val lanes = site?.lanes?.joinToString(",", "[", "]") { describe(it).let { s -> "\"$s\"" } } ?: "null"
            val allowed = site?.allowed()?.joinToString(",", "[", "]") ?: "null"
            val json = "{\"n\":$n,\"d\":${d.toInt()},\"stop\":${pt(stop)},\"heading\":${"%.1f".format(Locale.US, heading)}," +
                "\"name\":\"${name.replace("\"", "'")}\",\"turn\":\"${site?.let { describe(it.turn) } ?: ""}\"," +
                "\"maneuver\":\"${step?.let { "${it.maneuver.type} ${it.maneuver.modifier}" } ?: ""}\"," +
                "\"lanes\":$lanes,\"allowed\":$allowed,\"rec\":${advice?.recommended ?: -1},\"signal\":${site == null}," +
                "\"route\":[${route.geometry.slice((d - 50).coerceAtLeast(0.0), minOf(route.distance, d + 40)).joinToString(",") { pt(it) }}]," +
                "\"shapes\":[$shapes]}"
            File(dir, "j_%03d.json".format(n)).writeText(json)
        }
        println("JUNCTIONS: ${junctions.size} (${sites.size} with lane data, ${signals.size} signals without) -> ${dir.path}")
    }

    /** Lane arrow bits as OSM-like tokens ("left;through"). */
    private fun describe(m: Int): String {
        val names = listOf(LaneDir.UTURN to "reverse", LaneDir.SHARP_LEFT to "sharp_left", LaneDir.LEFT to "left",
            LaneDir.SLIGHT_LEFT to "slight_left", LaneDir.STRAIGHT to "through", LaneDir.SLIGHT_RIGHT to "slight_right",
            LaneDir.RIGHT to "right", LaneDir.SHARP_RIGHT to "sharp_right", LaneDir.MERGE_TO_LEFT to "merge_to_left",
            LaneDir.MERGE_TO_RIGHT to "merge_to_right")
        return names.filter { m and it.first != 0 }.joinToString(";") { it.second }.ifEmpty { "none" }
    }
}
