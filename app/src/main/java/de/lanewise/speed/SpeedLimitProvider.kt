package de.lanewise.speed

import de.lanewise.core.geo.Geo
import de.lanewise.core.geo.LatLon
import de.lanewise.core.geo.Polyline
import de.lanewise.core.model.BikeLaneSpan
import de.lanewise.core.model.FeatureKind
import de.lanewise.core.model.LaneCountSpan
import de.lanewise.lane.ChangeSpan
import de.lanewise.core.model.RoadFeature
import de.lanewise.core.model.SpeedLimitSpan
import de.lanewise.core.net.Overpass
import de.lanewise.core.net.arr
import de.lanewise.core.net.dbl
import de.lanewise.core.net.get
import de.lanewise.core.net.str
import de.lanewise.osm.OsmTags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.util.Locale
import kotlin.math.abs

/** Speed limits, on-street bike lanes and point features (traffic lights, signs) along part of a route. */
data class CorridorData(
    val limits: List<SpeedLimitSpan>,
    val features: List<RoadFeature>,
    val bikeLanes: List<BikeLaneSpan>,
    val laneCounts: List<LaneCountSpan> = emptyList(),
    val laneChanges: List<ChangeSpan> = emptyList(),
    val sideRoads: List<de.lanewise.core.model.SideRoad> = emptyList(),
    /** OSM `turn:lanes` at the end of ways the route drives on (for junctions OSRM gives no lanes for). */
    val turnLanes: List<TurnLanesAt> = emptyList(),
)

/** `turn:lanes` value (travel direction) of a way the route drives on, at the route distance where that way ends. */
data class TurnLanesAt(val distanceAlong: Double, val lanes: String)

/**
 * Fetches OSM data for a corridor along the route in one Overpass request (route line as `around`
 * filter): ways with `maxspeed` or painted bike lanes, plus traffic-light/sign nodes. Everything is
 * map-matched onto the route. OSRM returns none of this, so it fills the gap.
 */
class SpeedLimitProvider {

    private class OsmWay(val geom: List<LatLon>, val tags: Map<String, String>, val nodes: List<Long> = emptyList()) {
        /** Shares a segment (two consecutive nodes) with the route: the route really drives on this way. */
        var onRoute = false
        val line = Polyline(geom)
        val minLat = geom.minOf { it.lat }; val maxLat = geom.maxOf { it.lat }
        val minLon = geom.minOf { it.lon }; val maxLon = geom.maxOf { it.lon }
        private val dir = OsmTags.onewayDir(tags)
        val oneway = dir != 0
        /** One-way against the way's drawing direction (`oneway=-1`): travel runs backwards along [line]. */
        val reverse = dir < 0
        private val all = OsmTags.parseMaxspeed(tags["maxspeed"])
        val maxFwd = OsmTags.parseMaxspeed(tags["maxspeed:forward"]).takeIf { it != OsmTags.UNKNOWN } ?: all
        val maxBwd = OsmTags.parseMaxspeed(tags["maxspeed:backward"]).takeIf { it != OsmTags.UNKNOWN } ?: all
    }

    /** Data for [fromDist]..[toDist] metres along [line]. */
    suspend fun corridor(line: Polyline, fromDist: Double, toDist: Double, routeNodes: List<Long> = emptyList()): CorridorData = withContext(Dispatchers.Default) {
        val sample = line.slice(fromDist, toDist, step = 120.0)
        if (sample.size < 2) return@withContext CorridorData(emptyList(), emptyList(), emptyList())
        val c = sample.joinToString(",") { String.format(Locale.US, "%.5f,%.5f", it.lat, it.lon) }
        val q = "[out:json][timeout:90];(" +
            "way(around:25,$c)[\"highway\"][~\"^maxspeed\"~\".\"];" +
            "way(around:25,$c)[\"highway\"][~\"^cycleway\"~\"lane\"];" +
            "way(around:25,$c)[\"highway\"][~\"^(lanes|turn:lanes)\"~\".\"];" +
            OsmTags.featureNodeQuery("(around:20,$c)") +
            ");out body geom;"
        val elements = Overpass.json(q)["elements"].arr.orEmpty()
        val ways = elements.filter { it["type"].str == "way" }.mapNotNull { el ->
            val geom = el["geometry"].arr.orEmpty().mapNotNull { p ->
                val la = p["lat"].dbl; val lo = p["lon"].dbl
                if (la != null && lo != null) LatLon(la, lo) else null
            }
            val ids = el["nodes"].arr.orEmpty().mapNotNull { it.dbl?.toLong() }
            if (geom.size < 2) null else OsmWay(geom, tagsOf(el), ids)
        }
        markOnRoute(ways, routeNodes)
        val nodes = elements.filter { it["type"].str == "node" }.mapNotNull { el ->
            val la = el["lat"].dbl ?: return@mapNotNull null
            val lo = el["lon"].dbl ?: return@mapNotNull null
            OsmTags.feature(tagsOf(el), LatLon(la, lo), 0.0)
        }
        val m = matchWays(line, fromDist, toDist, ways)
        CorridorData(m.limits, matchFeatures(line, fromDist, toDist, nodes), m.bikes, m.lanes, m.changes,
            sideRoads(line, fromDist, toDist, ways.map { it.geom to it.tags }), turnLanesOnRoute(line, fromDist, toDist, ways))
    }

    /**
     * Speed limit of the road the car is on right now (no route needed): matches ways around the last
     * ~40 m of travel. Returns km/h, [OsmTags.NO_LIMIT], or null if unknown.
     */
    suspend fun currentLimit(position: LatLon, headingDeg: Double): Int? {
        val back = Geo.destination(position, headingDeg + 180.0, 40.0)
        val line = Polyline(listOf(back, position))
        val last = corridor(line, 0.0, line.length).limits.lastOrNull() ?: return null
        return last.kmh ?: OsmTags.NO_LIMIT
    }

    /**
     * `turn:lanes` of the ways the route drives on, at the route distance of the way's end in travel direction (where
     * the arrows apply). OSRM drops lane data at many complex junctions (Aegidientorplatz: 5 lanes
     * `reverse;left|left|right|right|right` in OSM, nothing from OSRM); these fill the gap.
     */
    private fun turnLanesOnRoute(line: Polyline, fromDist: Double, toDist: Double, ways: List<OsmWay>): List<TurnLanesAt> =
        ways.filter { it.onRoute }.mapNotNull { w ->
            val a = line.project(w.geom.first()) ?: return@mapNotNull null
            val b = line.project(w.geom.last()) ?: return@mapNotNull null
            val forward = b.distanceAlong > a.distanceAlong
            val end = if (forward) b else a
            if (abs(end.signedOffset) > 8 || end.distanceAlong < fromDist || end.distanceAlong > toDist) return@mapNotNull null
            val t = w.tags
            val value = if (forward) t["turn:lanes:forward"] ?: t["turn:lanes"]?.takeIf { w.oneway && !w.reverse }
            else t["turn:lanes:backward"] ?: t["turn:lanes"]?.takeIf { w.reverse }
            value?.takeIf { it.contains('|') }?.let { TurnLanesAt(end.distanceAlong, it) }
        }.sortedBy { it.distanceAlong }

    /** Marks the ways that share a segment with the route's OSM nodes (either direction). */
    private fun markOnRoute(ways: List<OsmWay>, routeNodes: List<Long>) {
        if (routeNodes.size < 2) return
        val segs = HashSet<Long>(routeNodes.size * 2)
        fun key(a: Long, b: Long) = minOf(a, b) * 31 + maxOf(a, b) * 1_000_003
        for (i in 0 until routeNodes.size - 1) segs += key(routeNodes[i], routeNodes[i + 1])
        for (w in ways) w.onRoute = (0 until w.nodes.size - 1).any { key(w.nodes[it], w.nodes[it + 1]) in segs }
    }

    private fun tagsOf(el: JsonElement): Map<String, String> =
        (el["tags"] as? JsonObject)?.mapValues { it.value.str.orEmpty() } ?: emptyMap()

    private fun matchFeatures(line: Polyline, fromDist: Double, toDist: Double, nodes: List<RoadFeature>): List<RoadFeature> {
        // Every signal position is kept (the HUD counts them per junction); other signs are de-duplicated.
        val hits = nodes.mapNotNull { f ->
            val proj = line.project(f.position) ?: return@mapNotNull null
            if (abs(proj.signedOffset) > 20 || proj.distanceAlong < fromDist || proj.distanceAlong > toDist) null
            else f.copy(distanceAlong = proj.distanceAlong)
        }.sortedBy { it.distanceAlong }
        val out = ArrayList<RoadFeature>()
        for (f in hits) {
            if (f.kind == FeatureKind.TRAFFIC_SIGNALS || out.none { it.kind == f.kind && abs(it.distanceAlong - f.distanceAlong) < 30 }) out += f
        }
        return out
    }

    companion object {
        private val SIDE_CLASSES = setOf("motorway", "motorway_link", "trunk", "trunk_link", "primary", "secondary")

        /**
         * One-way carriageways (motorway … secondary) that run beside the route in its direction, 4–35 m away, for at
         * least 60 m: the parts that do are returned as [de.lanewise.core.model.SideRoad]s. The route's own way (≈ 0 m)
         * and the opposite carriageway are left out. Pure (unit-tested).
         */
        fun sideRoads(line: Polyline, fromDist: Double, toDist: Double, ways: List<Pair<List<LatLon>, Map<String, String>>>):
            List<de.lanewise.core.model.SideRoad> {
            val out = ArrayList<de.lanewise.core.model.SideRoad>()
            for ((geom, tags) in ways) {
                val hw = tags["highway"] ?: continue
                if (hw !in SIDE_CLASSES || geom.size < 2) continue
                val oneway = tags["oneway"] == "yes" || hw.startsWith("motorway")
                if (!oneway) continue
                val wl = Polyline(geom)
                var run = ArrayList<LatLon>()
                fun flush() {
                    if (run.size >= 2 && Polyline(run).length >= 60.0) out += de.lanewise.core.model.SideRoad(run, tags)
                    run = ArrayList()
                }
                var d = 0.0
                while (d <= wl.length) {
                    val p = wl.pointAt(d)
                    val pr = line.project(p)
                    val ok = pr != null && abs(pr.signedOffset) in 4.0..35.0 && pr.distanceAlong in fromDist..toDist &&
                        abs(Geo.bearingDelta(wl.bearingAt(d), line.bearingAt(pr.distanceAlong))) < 30.0
                    if (ok) run += p else flush()
                    d += 10.0
                }
                flush()
            }
            return out
        }
    }

    /** Samples the route every 25 m, matches the nearest way in driving direction, and builds spans. */
    private class Matched(
        val limits: List<SpeedLimitSpan>, val bikes: List<BikeLaneSpan>, val lanes: List<LaneCountSpan>, val changes: List<ChangeSpan>,
    )

    private fun matchWays(line: Polyline, fromDist: Double, toDist: Double, ways: List<OsmWay>): Matched {
        val step = 25.0
        val pad = 0.0004
        val limits = ArrayList<SpeedLimitSpan>()
        val bikes = ArrayList<BikeLaneSpan>()
        var limStart = fromDist; var limVal: Int? = Int.MIN_VALUE   // sentinel: unknown
        var bikeStart = fromDist; var bikeVal: Boolean? = null       // null = no bike lane
        val laneSpans = ArrayList<LaneCountSpan>()
        var laneStart = fromDist; var laneVal: Int? = null
        val changeSpans = ArrayList<ChangeSpan>()
        var changeStart = fromDist; var changeVal: List<Int>? = null

        var d = fromDist
        while (d < toDist) {
            val p = line.pointAt(d)
            val heading = line.bearingAt(d)
            var limit: Int? = Int.MIN_VALUE
            var limitDist = 18.0
            var bike: Boolean? = null
            var bikeDist = 18.0
            var lanes: Int? = null
            var lanesDist = 18.0
            var change: List<Int>? = null
            // Ways the route really drives on (shared OSM nodes) win over anything else in reach - a bridge above,
            // a road below or a parallel service road.
            fun near(w: OsmWay) = !(p.lat < w.minLat - pad || p.lat > w.maxLat + pad || p.lon < w.minLon - pad || p.lon > w.maxLon + pad) &&
                (w.line.project(p)?.let { abs(it.signedOffset) < 18.0 } == true)
            val pool = if (ways.any { it.onRoute && near(it) }) ways.filter { it.onRoute } else ways
            for (w in pool) {
                if (p.lat < w.minLat - pad || p.lat > w.maxLat + pad || p.lon < w.minLon - pad || p.lon > w.maxLon + pad) continue
                val proj = w.line.project(p) ?: continue
                val off = abs(proj.signedOffset)
                if (off >= 18.0) continue
                val diff = abs(Geo.bearingDelta(heading, w.line.bearingAt(proj.distanceAlong)))
                val forward = diff < 45
                // One-way ways only in their direction of travel (the other carriageway is left out).
                if (forward && w.reverse) continue
                if (!forward && !(diff > 135 && (!w.oneway || w.reverse))) continue
                val v = if (forward) w.maxFwd else w.maxBwd
                if (v != OsmTags.UNKNOWN && off < limitDist) { limitDist = off; limit = if (v == OsmTags.NO_LIMIT) null else v }
                if (off < bikeDist) { bikeDist = off; bike = OsmTags.bikeLaneRight(w.tags, forward, w.oneway) }
                val n = OsmTags.lanesInDirection(w.tags, forward, w.oneway)
                if (n != null && off < lanesDist) { lanesDist = off; lanes = n; change = OsmTags.changeLanes(w.tags, forward, w.oneway) }
            }
            if (limit != limVal) {
                if (limVal != Int.MIN_VALUE) limits += SpeedLimitSpan(limStart, d, limVal)
                limStart = d; limVal = limit
            }
            if (lanes != laneVal) {
                laneVal?.let { laneSpans += LaneCountSpan(laneStart, d, it) }
                laneStart = d; laneVal = lanes
            }
            if (change != changeVal) {
                changeVal?.let { changeSpans += ChangeSpan(changeStart, d, it) }
                changeStart = d; changeVal = change
            }
            if (bike != bikeVal) {
                bikeVal?.let { bikes += BikeLaneSpan(bikeStart, d, it) }
                bikeStart = d; bikeVal = bike
            }
            d += step
        }
        if (limVal != Int.MIN_VALUE) limits += SpeedLimitSpan(limStart, toDist, limVal)
        bikeVal?.let { bikes += BikeLaneSpan(bikeStart, toDist, it) }
        laneVal?.let { laneSpans += LaneCountSpan(laneStart, toDist, it) }
        changeVal?.let { changeSpans += ChangeSpan(changeStart, toDist, it) }
        return Matched(limits, bikes, laneSpans, changeSpans)
    }
}
