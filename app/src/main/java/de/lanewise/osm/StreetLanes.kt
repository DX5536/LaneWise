package de.lanewise.osm

import de.lanewise.core.geo.Geo
import de.lanewise.core.geo.LatLon
import de.lanewise.core.geo.Polyline
import de.lanewise.core.net.Overpass
import de.lanewise.core.net.arr
import de.lanewise.core.net.dbl
import de.lanewise.core.net.get
import de.lanewise.core.net.str
import de.lanewise.lane.ArrowGeometry
import de.lanewise.lane.LaneDir
import de.lanewise.lane.LaneRibbonBuilder.Kind
import de.lanewise.lane.LaneRibbonBuilder.Shape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** One OSM street segment with what is painted on it. */
data class OsmStreet(val geometry: List<LatLon>, val tags: Map<String, String>)

/**
 * Lanes and markings of all streets around the car while no route is active — like Petal / AMAP's lane-level
 * base map. Built from OSM `lanes(:forward|:backward)`, `turn:lanes(:forward|:backward)` and `cycleway:*`.
 * During navigation only the route's own lanes are drawn (see [de.lanewise.lane.LaneRibbonBuilder]).
 */
object StreetLanes {

    private const val CLASSES = "motorway|motorway_link|trunk|trunk_link|primary|primary_link|secondary|secondary_link|" +
        "tertiary|tertiary_link|unclassified|residential|living_street"

    suspend fun fetch(center: LatLon, radiusM: Int = 450): List<OsmStreet> {
        val q = String.format(Locale.US, "[out:json][timeout:40];way(around:%d,%.5f,%.5f)[\"highway\"~\"^(%s)$\"];out tags geom;",
            radiusM, center.lat, center.lon, CLASSES)
        val elements = Overpass.json(q)["elements"].arr.orEmpty()
        return elements.mapNotNull { el ->
            val geom = el["geometry"].arr.orEmpty().mapNotNull { p ->
                val la = p["lat"].dbl; val lo = p["lon"].dbl
                if (la != null && lo != null) LatLon(la, lo) else null
            }
            val tags = (el["tags"] as? JsonObject)?.mapValues { it.value.str.orEmpty() } ?: return@mapNotNull null
            if (geom.size < 2) null else OsmStreet(geom, tags)
        }
    }

    private fun laneWidth(hw: String) = when {
        hw.startsWith("motorway") -> 3.75
        hw.startsWith("trunk") || hw.startsWith("primary") -> 3.25
        else -> 3.0
    }

    suspend fun build(streets: List<OsmStreet>): List<Shape> = withContext(Dispatchers.Default) {
        val out = ArrayList<Shape>()
        for (st in streets) runCatching { buildStreet(st, out) }
        out
    }

    private fun buildStreet(st: OsmStreet, out: MutableList<Shape>) {
        val t = st.tags
        val hw = t["highway"].orEmpty()
        val oneway = t["oneway"] == "yes" || hw == "motorway" || hw == "motorway_link" || t["junction"] == "roundabout"
        val minor = hw == "residential" || hw == "living_street" || hw == "unclassified"
        val fwd = OsmTags.lanesInDirection(t, true, oneway) ?: 1
        val bwd = if (oneway) 0 else OsmTags.lanesInDirection(t, false, false) ?: 1
        val w = laneWidth(hw) * if (minor && t["lanes"] == null) 0.9 else 1.0

        val line = Polyline(st.geometry)
        if (line.length < 8) return
        val pts = smooth(line)
        val bearings = pts.indices.map { i ->
            val a = pts[max(0, i - 2)]; val b = pts[min(pts.size - 1, i + 2)]
            if (a == b) 0.0 else Geo.bearing(a, b)
        }
        fun at(i: Int, off: Double) = Geo.destination(pts[i], bearings[i] + 90.0, off)
        val idx = pts.indices.toList()

        // Lateral layout (m, + = right of way direction).
        val left = if (oneway) -fwd * w / 2 else -bwd * w
        val right = if (oneway) fwd * w / 2 else fwd * w
        out += Shape(idx.map { at(it, left - 0.3) } + idx.reversed().map { at(it, right + 0.3) } + at(0, left - 0.3), Kind.SURFACE, true)
        out += Shape(idx.map { at(it, left) }, Kind.EDGE, false, 0.18)
        out += Shape(idx.map { at(it, right) }, Kind.EDGE, false, 0.18)
        // Centre line between directions, lane dividers within each direction.
        if (!oneway && fwd > 0 && bwd > 0) {
            out += Shape(idx.map { at(it, 0.0) }, if (minor) Kind.DIVIDER else Kind.DIVIDER_SOLID, false, 0.15)
        }
        val fwdStart = if (oneway) left else 0.0
        for (k in 1 until fwd) out += Shape(idx.map { at(it, fwdStart + k * w) }, Kind.DIVIDER, false, 0.15)
        for (k in 1 until bwd) out += Shape(idx.map { at(it, -k * w) }, Kind.DIVIDER, false, 0.15)

        // Bike lanes on the right edge of each direction.
        OsmTags.bikeLaneRight(t, forward = true, oneway = oneway)?.let { exclusive ->
            val bw = if (exclusive) 1.6 else 1.3
            out += Shape(idx.map { at(it, right + 0.35) } + idx.reversed().map { at(it, right + 0.35 + bw) } + at(0, right + 0.35), Kind.BIKE, true)
        }
        if (!oneway) OsmTags.bikeLaneRight(t, forward = false, oneway = false)?.let { exclusive ->
            val bw = if (exclusive) 1.6 else 1.3
            out += Shape(idx.map { at(it, left - 0.35) } + idx.reversed().map { at(it, left - 0.35 - bw) } + at(0, left - 0.35), Kind.BIKE, true)
        }

        // Turn arrows + stop line at the end of each direction (turn:lanes apply where the way ends).
        val turnF = t["turn:lanes:forward"] ?: if (oneway) t["turn:lanes"] else null
        val turnB = t["turn:lanes:backward"]
        val len = line.length
        if (turnF != null && len > 25) {
            val masks = LaneDir.parseTurnLanes(turnF)
            paint(masks, forward = true, fromStart = len, line, w, if (oneway) left else 0.0, out)
        }
        if (turnB != null && len > 25 && !oneway) {
            val masks = LaneDir.parseTurnLanes(turnB)
            paint(masks, forward = false, fromStart = 0.0, line, w, 0.0, out)
        }
    }

    /** Arrows 6–15 m before the stop position of one direction, plus the stop line. */
    private fun paint(
        masks: List<Int>, forward: Boolean, fromStart: Double, line: Polyline,
        w: Double, edge: Double, out: MutableList<Shape>,
    ) {
        val dir = if (forward) -1.0 else 1.0          // towards the stop position, measured from the way start
        fun alongPoint(d: Double): Pair<LatLon, Double> {
            val p = line.pointAt(d)
            val b = line.bearingAt(d)
            return p to b
        }
        val stopD = fromStart + dir * 2.0
        // Lane i offset from the way's centre line (forward: right of it, backward: left of it).
        fun laneCentre(i: Int) = if (forward) edge + (i + 0.5) * w else -(i + 0.5) * w
        val n = masks.size
        val (sp, sb) = alongPoint(stopD)
        val a = if (forward) edge else 0.0
        val b = if (forward) edge + n * w else -n * w
        out += Shape(listOf(Geo.destination(sp, sb + 90.0, a), Geo.destination(sp, sb + 90.0, b)), Kind.STOP, false, 0.5)

        val top = fromStart + dir * 6.0
        val bottom = fromStart + dir * 15.0
        masks.forEachIndexed { i, mask ->
            ArrowGeometry.polygons(mask).forEach { poly ->
                out += Shape(poly.map { pt ->
                    val d = top + (bottom - top) * pt.y
                    val (p, br) = alongPoint(d)
                    // Driver's left→right is the way's left→right going forward, mirrored going backward.
                    val lateral = laneCentre(i) + (if (forward) 1 else -1) * (pt.x - 0.5) * w * 0.6
                    Geo.destination(p, br + 90.0, lateral)
                }, Kind.ARROW, true)
            }
        }
    }

    /** Light Chaikin smoothing of a street's geometry (keeps end points). */
    private fun smooth(line: Polyline): List<LatLon> {
        var pts = line.slice(0.0, line.length, step = 8.0)
        repeat(2) {
            if (pts.size < 3) return@repeat
            val n = ArrayList<LatLon>(pts.size * 2)
            n += pts.first()
            for (i in 0 until pts.size - 1) {
                val p = pts[i]; val q = pts[i + 1]
                n += LatLon(0.75 * p.lat + 0.25 * q.lat, 0.75 * p.lon + 0.25 * q.lon)
                n += LatLon(0.25 * p.lat + 0.75 * q.lat, 0.25 * p.lon + 0.75 * q.lon)
            }
            n += pts.last()
            pts = n
        }
        return pts
    }
}
