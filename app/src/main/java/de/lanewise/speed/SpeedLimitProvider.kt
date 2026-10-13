package de.lanewise.speed

import de.lanewise.core.geo.Geo
import de.lanewise.core.geo.LatLon
import de.lanewise.core.geo.Polyline
import de.lanewise.core.model.SpeedLimitSpan
import de.lanewise.core.net.Http
import de.lanewise.core.net.arr
import de.lanewise.core.net.dbl
import de.lanewise.core.net.get
import de.lanewise.core.net.str
import de.lanewise.offline.OfflineGraphBuilder
import de.lanewise.osm.OsmTags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.abs

/**
 * Fetches OSM `maxspeed` for a corridor along the route (Overpass `around` filter with the route
 * line) and map-matches it onto the route. OSRM does not return speed limits, so this fills the gap.
 */
class SpeedLimitProvider(private val overpassUrl: String = OfflineGraphBuilder.OVERPASS_URL) {

    private class OsmWay(val geom: List<LatLon>, val fwd: Int, val bwd: Int, val oneway: Boolean,
                         val minLat: Double, val maxLat: Double, val minLon: Double, val maxLon: Double) {
        val line = Polyline(geom)
    }

    /** Returns spans for [fromDist]..[toDist] metres along [line]. */
    suspend fun limitsFor(line: Polyline, fromDist: Double, toDist: Double): List<SpeedLimitSpan> = withContext(Dispatchers.IO) {
        val sample = line.slice(fromDist, toDist, step = 120.0)
        if (sample.size < 2) return@withContext emptyList()
        val coords = sample.joinToString(",") { String.format(Locale.US, "%.5f,%.5f", it.lat, it.lon) }
        val q = "[out:json][timeout:60];way(around:25,$coords)[\"highway\"][~\"^maxspeed\"~\".\"];out tags geom;"
        val root = Http.getJson("$overpassUrl?data=" + URLEncoder.encode(q, "UTF-8"))
        val ways = root["elements"].arr.orEmpty().mapNotNull { el ->
            val geom = el["geometry"].arr.orEmpty().mapNotNull { p ->
                val la = p["lat"].dbl; val lo = p["lon"].dbl
                if (la != null && lo != null) LatLon(la, lo) else null
            }
            if (geom.size < 2) return@mapNotNull null
            val tags = el["tags"]
            val all = OsmTags.parseMaxspeed(tags["maxspeed"].str)
            val f = OsmTags.parseMaxspeed(tags["maxspeed:forward"].str).takeIf { it != OsmTags.UNKNOWN } ?: all
            val b = OsmTags.parseMaxspeed(tags["maxspeed:backward"].str).takeIf { it != OsmTags.UNKNOWN } ?: all
            val hw = tags["highway"].str.orEmpty()
            val oneway = tags["oneway"].str == "yes" || hw == "motorway" || hw == "motorway_link" || tags["junction"].str == "roundabout"
            OsmWay(geom, f, b, oneway, geom.minOf { it.lat }, geom.maxOf { it.lat }, geom.minOf { it.lon }, geom.maxOf { it.lon })
        }
        matchSpans(line, fromDist, toDist, ways)
    }

    private fun matchSpans(line: Polyline, fromDist: Double, toDist: Double, ways: List<OsmWay>): List<SpeedLimitSpan> {
        val step = 25.0
        val out = ArrayList<SpeedLimitSpan>()
        var d = fromDist
        var curStart = fromDist
        var curVal: Int? = Int.MIN_VALUE // sentinel: nothing yet
        val pad = 0.0004
        while (d < toDist) {
            val p = line.pointAt(d)
            val heading = line.bearingAt(d)
            var best: Int? = Int.MIN_VALUE
            var bestDist = 18.0
            for (w in ways) {
                if (p.lat < w.minLat - pad || p.lat > w.maxLat + pad || p.lon < w.minLon - pad || p.lon > w.maxLon + pad) continue
                val proj = w.line.project(p) ?: continue
                if (abs(proj.signedOffset) >= bestDist) continue
                val wb = w.line.bearingAt(proj.distanceAlong)
                val diff = abs(Geo.bearingDelta(heading, wb))
                val forward = diff < 45
                val backward = diff > 135 && !w.oneway
                if (!forward && !backward) continue
                val v = if (forward) w.fwd else w.bwd
                if (v == OsmTags.UNKNOWN) continue
                bestDist = abs(proj.signedOffset)
                best = if (v == OsmTags.NO_LIMIT) null else v
            }
            if (best != curVal) {
                if (curVal != Int.MIN_VALUE) out += SpeedLimitSpan(curStart, d, curVal)
                curStart = d
                curVal = best
            }
            d += step
        }
        if (curVal != Int.MIN_VALUE) out += SpeedLimitSpan(curStart, toDist, curVal)
        return out
    }
}
