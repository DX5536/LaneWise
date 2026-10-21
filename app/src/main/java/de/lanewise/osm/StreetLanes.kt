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
import de.lanewise.lane.ChangeSpan
import de.lanewise.lane.LaneDir
import de.lanewise.lane.LaneRibbonBuilder.Kind
import de.lanewise.lane.LaneRibbonBuilder.Shape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** One OSM street segment with what is painted on it; [nodes] are the OSM node ids of [geometry] (for junctions). */
data class OsmStreet(val geometry: List<LatLon>, val tags: Map<String, String>, val id: Long = 0, val nodes: List<Long> = emptyList())

/**
 * Lanes and markings of all streets around the car while no route is active — like Petal / AMAP's lane-level
 * base map. Built from OSM `lanes(:forward|:backward)`, `turn:lanes(:forward|:backward)` and `cycleway:*`.
 * During navigation only the route's own lanes are drawn (see [de.lanewise.lane.LaneRibbonBuilder]).
 */
object StreetLanes {

    private const val CLASSES = "motorway|motorway_link|trunk|trunk_link|primary|primary_link|secondary|secondary_link|" +
        "tertiary|tertiary_link|unclassified|residential|living_street"

    suspend fun fetch(center: LatLon, radiusM: Int = 450): List<OsmStreet> = fetchNearby(center, radiusM).streets

    /** Streets around [center] plus their traffic lights / signs (for the browse map, where no route provides them). */
    class Nearby(val streets: List<OsmStreet>, val features: List<de.lanewise.core.model.RoadFeature>)

    suspend fun fetchNearby(center: LatLon, radiusM: Int = 450): Nearby {
        val around = String.format(Locale.US, "(around:%d,%.5f,%.5f)", radiusM, center.lat, center.lon)
        val q = "[out:json][timeout:40];(way$around[\"highway\"~\"^($CLASSES)$\"];${OsmTags.featureNodeQuery(around)});out geom;"
        val elements = Overpass.json(q)["elements"].arr.orEmpty()
        val features = elements.filter { it["type"].str == "node" }.mapNotNull { el ->
            val la = el["lat"].dbl; val lo = el["lon"].dbl
            val tags = (el["tags"] as? JsonObject)?.mapValues { it.value.str.orEmpty() }
            if (la == null || lo == null || tags == null) null else OsmTags.feature(tags, LatLon(la, lo), 0.0)
        }
        val streets = elements.filter { it["type"].str != "node" }.mapNotNull { el ->
            val geom = el["geometry"].arr.orEmpty().mapNotNull { p ->
                val la = p["lat"].dbl; val lo = p["lon"].dbl
                if (la != null && lo != null) LatLon(la, lo) else null
            }
            val tags = (el["tags"] as? JsonObject)?.mapValues { it.value.str.orEmpty() } ?: return@mapNotNull null
            val nodes = el["nodes"].arr.orEmpty().mapNotNull { it.dbl?.toLong() }
            if (geom.size < 2) null
            else OsmStreet(geom, tags, el["id"].dbl?.toLong() ?: 0, if (nodes.size == geom.size) nodes else emptyList())
        }
        return Nearby(streets, features)
    }

    private fun laneWidth(hw: String) = when {
        hw.startsWith("motorway") -> 3.75
        hw.startsWith("trunk") || hw.startsWith("primary") -> 3.25
        else -> 3.0
    }

    /** A junction on a street: along-way distance and the half width of the crossing roads (lines stop there). */
    private class Cut(val d: Double, val r: Double)

    /** Lateral layout of one street (m, + = right of the way direction). */
    private class Layout(t: Map<String, String>) {
        val hw = t["highway"].orEmpty()
        val oneway = OsmTags.onewayDir(t) != 0
        /** One-way against the drawing direction (`oneway=-1`): travel runs from the way's end to its start. */
        val reverse = OsmTags.onewayDir(t) < 0
        val minor = hw == "residential" || hw == "living_street" || hw == "unclassified"
        val fwd = OsmTags.lanesInDirection(t, true, oneway) ?: 1
        val bwd = if (oneway) 0 else OsmTags.lanesInDirection(t, false, false) ?: 1
        val w = laneWidth(hw) * if (minor && t["lanes"] == null) 0.9 else 1.0
        val left = if (oneway) -fwd * w / 2 else -bwd * w
        val right = if (oneway) fwd * w / 2 else fwd * w
        val extent get() = max(-left, right) + 0.3
    }

    suspend fun build(streets: List<OsmStreet>): List<Shape> = withContext(Dispatchers.Default) {
        val layouts = streets.map { Layout(it.tags) }
        // Node degree: interior nodes count twice (in + out); degree >= 3 is a real junction, 2 a plain way split.
        val degree = HashMap<Long, Int>()
        val atNode = HashMap<Long, MutableList<Int>>()
        streets.forEachIndexed { k, st ->
            st.nodes.forEachIndexed { i, n ->
                degree.merge(n, if (i == 0 || i == st.nodes.lastIndex) 1 else 2, Int::plus)
                atNode.getOrPut(n) { ArrayList(4) } += k
            }
        }
        // Plain way splits (a node shared by exactly two way ends): both ways get the same direction and road edges
        // there, so lines and bike lanes run on without a step or kink (owner's standby screenshot, Celler Straße).
        val joins = streets.mapIndexed { k, st -> joinAt(k, st, true, streets, layouts, degree, atNode) to joinAt(k, st, false, streets, layouts, degree, atNode) }
        // Road surfaces first: markings of one street stop where they enter another street's surface (junctions,
        // carriageways merging), so lines never run across another road and meet its edge exactly.
        val geo = streets.mapIndexed { k, st -> runCatching { Geometry(st, layouts[k], joins[k].first, joins[k].second) }.getOrNull() }
        val out = ArrayList<Shape>()
        streets.forEachIndexed { k, st ->
            val g = geo[k] ?: return@forEachIndexed
            val line = Polyline(st.geometry)
            val cuts = st.nodes.mapIndexedNotNull { i, n ->
                if ((degree[n] ?: 0) < 3) return@mapIndexedNotNull null
                val r = atNode[n].orEmpty().filter { it != k }.maxOfOrNull { layouts[it].extent } ?: return@mapIndexedNotNull null
                Cut(line.cumulative[i], r + 0.8)
            }
            // Plain continuations of this way (shared node of degree 2) do not clip it.
            val continuation = st.nodes.filter { (degree[it] ?: 0) == 2 }.flatMap { atNode[it].orEmpty() }.toSet()
            val foreign = geo.indices.filter { j ->
                j != k && j !in continuation && geo[j]?.let { o -> o.bbox.intersects(g.bbox) } == true
            }.mapNotNull { geo[it] }
            runCatching { buildStreet(st, layouts[k], line, cuts, g, foreign, out) }
        }
        out
    }

    /**
     * The neighbour at a plain way split, seen in this way's direction: its direction there and its road edges
     * ([left], [right], m from the centre line, + = right). Both ways blend into the average of the two.
     */
    private class Join(val bearing: Double, val left: Double, val right: Double)

    /** [Join] at the start ([atStart]) or end of street [k]; null at junctions, dead ends and loops. */
    private fun joinAt(
        k: Int, st: OsmStreet, atStart: Boolean, streets: List<OsmStreet>, layouts: List<Layout>,
        degree: Map<Long, Int>, atNode: Map<Long, List<Int>>,
    ): Join? {
        if (st.nodes.size < 2 || st.geometry.size < 2) return null
        val n = if (atStart) st.nodes.first() else st.nodes.last()
        if ((degree[n] ?: 0) != 2) return null
        val j = atNode[n].orEmpty().singleOrNull { it != k } ?: return null
        val o = streets[j]
        if (o.geometry.size < 2) return null
        val oStart = o.nodes.first() == n
        if (!oStart && o.nodes.last() != n) return null
        // The neighbour's direction away from / towards the node, turned into this way's direction of travel.
        val oAway = if (oStart) Geo.bearing(o.geometry[0], o.geometry[1])
        else Geo.bearing(o.geometry.last(), o.geometry[o.geometry.size - 2])
        val bearing = if (atStart) (oAway + 180.0) % 360.0 else oAway
        // Same orientation (end → start): edges as they are; opposite: mirrored.
        val same = atStart != oStart
        val lo = layouts[j]
        return if (same) Join(bearing, lo.left, lo.right) else Join(bearing, -lo.right, -lo.left)
    }

    /** Smoothed centre line, normals and road surface of one street. */
    private class Geometry(st: OsmStreet, lay: Layout, startJoin: Join? = null, endJoin: Join? = null) {
        val pts = smooth(Polyline(st.geometry))
        private val along = DoubleArray(pts.size).also { a -> for (i in 1 until pts.size) a[i] = a[i - 1] + Geo.distance(pts[i - 1], pts[i]) }
        val bearings = pts.indices.map { i ->
            val a = pts[max(0, i - 2)]; val b = pts[min(pts.size - 1, i + 2)]
            var br = if (a == b) 0.0 else Geo.bearing(a, b)
            // Towards a plain split, turn to the joint direction (the mean of both ways), reached at the node.
            val toStart = along[i]; val toEnd = along.last() - along[i]
            // Same raw end segments as the neighbour uses, so both ways get exactly the same joint direction.
            if (startJoin != null && toStart < BLEND_M) br = blend(br, mean(Geo.bearing(st.geometry[0], st.geometry[1]), startJoin.bearing), 1 - toStart / BLEND_M)
            if (endJoin != null && toEnd < BLEND_M) br = blend(br, mean(Geo.bearing(st.geometry[st.geometry.size - 2], st.geometry.last()), endJoin.bearing), 1 - toEnd / BLEND_M)
            br
        }
        /** Road edges at point [i]: this way's, tapering over [TAPER_M] to the mean with the neighbour at a split. */
        private val edgeL = DoubleArray(pts.size) { i -> edgeAt(i, lay.left, startJoin?.left, endJoin?.left) }
        private val edgeR = DoubleArray(pts.size) { i -> edgeAt(i, lay.right, startJoin?.right, endJoin?.right) }
        private fun edgeAt(i: Int, own: Double, start: Double?, end: Double?): Double {
            var v = own
            val toStart = along[i]; val toEnd = along.last() - along[i]
            if (start != null && toStart < TAPER_M) v += ((own + start) / 2 - own) * smoothstep(1 - toStart / TAPER_M)
            if (end != null && toEnd < TAPER_M) v += ((own + end) / 2 - own) * smoothstep(1 - toEnd / TAPER_M)
            return v
        }
        private val layL = lay.left
        private val span = (lay.right - lay.left).takeIf { it > 0.1 } ?: 1.0
        /** Point at nominal lateral [off] (as in [Layout]), mapped onto this point's (tapered) road edges. */
        fun at(i: Int, off: Double): LatLon {
            val f = (off - layL) / span
            return Geo.destination(pts[i], bearings[i] + 90.0, edgeL[i] + f * (edgeR[i] - edgeL[i]))
        }
        val surface: List<LatLon> = pts.indices.map { at(it, lay.left - 0.3) } + pts.indices.reversed().map { at(it, lay.right + 0.3) } + at(0, lay.left - 0.3)
        val bbox = Box(surface)

        /** Ray casting in lat/lon (fine at street scale). */
        fun contains(p: LatLon): Boolean {
            if (!bbox.contains(p)) return false
            var inside = false
            var j = surface.size - 1
            for (i in surface.indices) {
                val a = surface[i]; val b = surface[j]
                if ((a.lat > p.lat) != (b.lat > p.lat) && p.lon < (b.lon - a.lon) * (p.lat - a.lat) / (b.lat - a.lat) + a.lon) inside = !inside
                j = i
            }
            return inside
        }
    }

    /** Direction blending at way splits (m) and width taper towards the neighbour (m). */
    private const val BLEND_M = 10.0
    private const val TAPER_M = 15.0

    private fun smoothstep(t: Double) = t.coerceIn(0.0, 1.0).let { it * it * (3 - 2 * it) }

    /** Circular mean of two bearings. */
    private fun mean(a: Double, b: Double): Double = (a + Geo.bearingDelta(a, b) / 2 + 360.0) % 360.0

    /** From bearing [a] towards [b] by [t] (0..1), the short way round. */
    private fun blend(a: Double, b: Double, t: Double): Double = (a + Geo.bearingDelta(a, b) * smoothstep(t) + 360.0) % 360.0

    private class Box(pts: List<LatLon>) {
        val s = pts.minOf { it.lat }; val n = pts.maxOf { it.lat }
        val w = pts.minOf { it.lon }; val e = pts.maxOf { it.lon }
        fun contains(p: LatLon) = p.lat in s..n && p.lon in w..e
        fun intersects(o: Box) = s <= o.n && o.s <= n && w <= o.e && o.w <= e
    }

    private fun buildStreet(
        st: OsmStreet, lay: Layout, line: Polyline, cuts: List<Cut>, g: Geometry, foreign: List<Geometry>, out: MutableList<Shape>,
    ) {
        val t = st.tags
        val oneway = lay.oneway
        val fwd = lay.fwd
        val bwd = lay.bwd
        val w = lay.w
        if (line.length < 8) return
        val pts = g.pts
        fun at(i: Int, off: Double) = g.at(i, off)
        val idx = pts.indices.toList()
        fun free(p: LatLon) = foreign.none { it.contains(p) }
        /** Runs of consecutive points at lateral [off] that are not on another street's surface. */
        fun runsAt(off: Double): List<List<LatLon>> {
            val runs = ArrayList<List<LatLon>>()
            var cur = ArrayList<LatLon>()
            for (i in idx) {
                val p = at(i, off)
                if (free(p)) { cur += p; continue }
                if (cur.size >= 2) runs += cur
                cur = ArrayList()
            }
            if (cur.size >= 2) runs += cur
            return runs
        }
        fun lines(off: Double, kind: Kind, width: Double) = runsAt(off).forEach { r -> out += Shape(r, kind, false, width) }
        fun strip(a: Double, b: Double, kind: Kind) {
            // Bike strips follow the outer line's free runs.
            val ok = idx.map { free(at(it, a)) }
            var start = -1
            for (i in 0..idx.size) {
                val f = i < idx.size && ok[i]
                if (f && start < 0) start = i
                if (!f && start >= 0) {
                    if (i - start >= 2) {
                        val r = (start until i).toList()
                        out += Shape(r.map { at(it, a) } + r.reversed().map { at(it, b) } + at(r.first(), a), kind, true)
                    }
                    start = -1
                }
            }
        }

        val left = lay.left
        val right = lay.right
        out += Shape(g.surface, Kind.SURFACE, true)
        lines(left, Kind.EDGE, 0.18)
        lines(right, Kind.EDGE, 0.18)
        // Centre line between directions, lane dividers within each direction.
        if (!oneway && fwd > 0 && bwd > 0) lines(0.0, if (lay.minor) Kind.DIVIDER else Kind.DIVIDER_SOLID, 0.15)
        // Lane dividers, solid where OSM `change:lanes` forbids crossing (lanes counted from the driver's left).
        val fwdStart = if (oneway) left else 0.0
        val changeF = OsmTags.changeLanes(t, true, oneway)
        val changeB = if (oneway) null else OsmTags.changeLanes(t, false, false)
        fun divider(perms: List<Int>?, k: Int, n: Int) =
            if (perms != null && ChangeSpan.solidBoundary(perms, k, n)) Kind.DIVIDER_SOLID else Kind.DIVIDER
        for (k in 1 until fwd) lines(fwdStart + k * w, divider(changeF, k, fwd), 0.15)
        for (k in 1 until bwd) lines(-k * w, divider(changeB, k, bwd), 0.15)

        // Bike lanes on the right edge of each direction of travel (a reversed one-way travels on the way's left).
        if (!lay.reverse) OsmTags.bikeLaneRight(t, forward = true, oneway = oneway)?.let { exclusive ->
            strip(right + 0.35, right + 0.35 + if (exclusive) 1.6 else 1.3, Kind.BIKE)
        }
        if (!oneway || lay.reverse) OsmTags.bikeLaneRight(t, forward = false, oneway = oneway)?.let { exclusive ->
            strip(left - 0.35, left - 0.35 - if (exclusive) 1.6 else 1.3, Kind.BIKE)
        }

        // Turn arrows + stop line at the end of each direction (turn:lanes apply where the way ends).
        val turnF = t["turn:lanes:forward"] ?: if (oneway && !lay.reverse) t["turn:lanes"] else null
        val turnB = t["turn:lanes:backward"] ?: if (lay.reverse) t["turn:lanes"] else null
        val len = line.length
        // Stop line in front of the crossing road, not inside the junction.
        val endCut = cuts.filter { it.d > len - 1 }.maxOfOrNull { it.r } ?: 0.0
        val startCut = cuts.filter { it.d < 1 }.maxOfOrNull { it.r } ?: 0.0
        if (turnF != null && len > 25 + endCut) {
            val masks = LaneDir.parseTurnLanes(turnF)
            paint(masks, forward = true, fromStart = len - endCut, line, w, if (oneway) left else 0.0, out)
        }
        if (turnB != null && len > 25 + startCut && (!oneway || lay.reverse)) {
            val masks = LaneDir.parseTurnLanes(turnB)
            // Two-way: the backward lanes lie left of the centre line; reversed one-way: from its right edge leftwards.
            paint(masks, forward = false, fromStart = startCut, line, w, if (lay.reverse) right else 0.0, out)
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
        fun laneCentre(i: Int) = if (forward) edge + (i + 0.5) * w else edge - (i + 0.5) * w
        val n = masks.size
        val (sp, sb) = alongPoint(stopD)
        val a = edge
        val b = if (forward) edge + n * w else edge - n * w
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
