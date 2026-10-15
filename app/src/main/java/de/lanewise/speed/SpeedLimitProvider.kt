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
)

/**
 * Fetches OSM data for a corridor along the route in one Overpass request (route line as `around`
 * filter): ways with `maxspeed` or painted bike lanes, plus traffic-light/sign nodes. Everything is
 * map-matched onto the route. OSRM returns none of this, so it fills the gap.
 */
class SpeedLimitProvider {

    private class OsmWay(val geom: List<LatLon>, val tags: Map<String, String>) {
        val line = Polyline(geom)
        val minLat = geom.minOf { it.lat }; val maxLat = geom.maxOf { it.lat }
        val minLon = geom.minOf { it.lon }; val maxLon = geom.maxOf { it.lon }
        val oneway = tags["oneway"] == "yes" || tags["highway"].orEmpty().startsWith("motorway") || tags["junction"] == "roundabout"
        private val all = OsmTags.parseMaxspeed(tags["maxspeed"])
        val maxFwd = OsmTags.parseMaxspeed(tags["maxspeed:forward"]).takeIf { it != OsmTags.UNKNOWN } ?: all
        val maxBwd = OsmTags.parseMaxspeed(tags["maxspeed:backward"]).takeIf { it != OsmTags.UNKNOWN } ?: all
    }

    /** Data for [fromDist]..[toDist] metres along [line]. */
    suspend fun corridor(line: Polyline, fromDist: Double, toDist: Double): CorridorData = withContext(Dispatchers.Default) {
        val sample = line.slice(fromDist, toDist, step = 120.0)
        if (sample.size < 2) return@withContext CorridorData(emptyList(), emptyList(), emptyList())
        val c = sample.joinToString(",") { String.format(Locale.US, "%.5f,%.5f", it.lat, it.lon) }
        val q = "[out:json][timeout:90];(" +
            "way(around:25,$c)[\"highway\"][~\"^maxspeed\"~\".\"];" +
            "way(around:25,$c)[\"highway\"][~\"^cycleway\"~\"lane\"];" +
            "way(around:25,$c)[\"highway\"][~\"^(lanes|turn:lanes)\"~\".\"];" +
            OsmTags.featureNodeQuery("(around:20,$c)") +
            ");out tags geom;"
        val elements = Overpass.json(q)["elements"].arr.orEmpty()
        val ways = elements.filter { it["type"].str == "way" }.mapNotNull { el ->
            val geom = el["geometry"].arr.orEmpty().mapNotNull { p ->
                val la = p["lat"].dbl; val lo = p["lon"].dbl
                if (la != null && lo != null) LatLon(la, lo) else null
            }
            if (geom.size < 2) null else OsmWay(geom, tagsOf(el))
        }
        val nodes = elements.filter { it["type"].str == "node" }.mapNotNull { el ->
            val la = el["lat"].dbl ?: return@mapNotNull null
            val lo = el["lon"].dbl ?: return@mapNotNull null
            OsmTags.feature(tagsOf(el), LatLon(la, lo), 0.0)
        }
        val m = matchWays(line, fromDist, toDist, ways)
        CorridorData(m.limits, matchFeatures(line, fromDist, toDist, nodes), m.bikes, m.lanes, m.changes,
            sideRoads(line, fromDist, toDist, ways.map { it.geom to it.tags }))
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
            for (w in ways) {
                if (p.lat < w.minLat - pad || p.lat > w.maxLat + pad || p.lon < w.minLon - pad || p.lon > w.maxLon + pad) continue
                val proj = w.line.project(p) ?: continue
                val off = abs(proj.signedOffset)
                if (off >= 18.0) continue
                val diff = abs(Geo.bearingDelta(heading, w.line.bearingAt(proj.distanceAlong)))
                val forward = diff < 45
                if (!forward && !(diff > 135 && !w.oneway)) continue
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
