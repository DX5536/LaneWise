package de.lanewise.offline

import de.lanewise.core.geo.Geo
import de.lanewise.core.geo.LatLon
import de.lanewise.core.geo.Polyline
import de.lanewise.core.model.BikeLaneSpan
import de.lanewise.core.model.FeatureKind
import de.lanewise.core.model.Maneuver
import de.lanewise.core.model.RoadFeature
import de.lanewise.core.model.ManeuverType
import de.lanewise.core.model.Modifier
import de.lanewise.core.model.RoadSpan
import de.lanewise.core.model.Route
import de.lanewise.core.model.RouteStep
import de.lanewise.core.model.SpeedLimitSpan
import de.lanewise.lane.ChangeSpan
import de.lanewise.lane.LaneDir
import de.lanewise.lane.LaneSite
import de.lanewise.osm.OsmTags
import de.lanewise.routing.RouteRequest
import de.lanewise.routing.RoutingException
import de.lanewise.routing.RoutingProvider
import de.lanewise.ui.i18n.Strings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.min

/**
 * On-device A* router over an [OfflineGraph]. Needs no network: position comes from GNSS
 * (GPS/Galileo/GLONASS/BeiDou), the road network from the downloaded OSM extract.
 *
 * Turn restrictions (OSM `type=restriction` via a node) are respected. Limitation: no live traffic.
 */
class OfflineRouter(
    private val graphProvider: () -> OfflineGraph?,
    private val strings: () -> Strings,
) : RoutingProvider {
    override val id = "offline"
    override val requiresNetwork = false

    override suspend fun route(request: RouteRequest): List<Route> = withContext(Dispatchers.Default) {
        val g = graphProvider() ?: throw RoutingException(strings().offNoGraph)
        if (!g.contains(request.origin) || !g.contains(request.destination)) {
            throw RoutingException(strings().offOutside)
        }
        val s = g.nearestNode(request.origin)
        val t = g.nearestNode(request.destination, requireOutgoing = false)
        if (s < 0 || t < 0) throw RoutingException(strings().offNoRoad)
        val edges = astar(g, s, t, request)?.takeIf { it.isNotEmpty() } ?: throw RoutingException(strings().offNoRoute)
        listOf(buildRoute(g, edges, request))
    }

    private fun speedKmh(g: OfflineGraph, e: Int): Double {
        val w = g.edgeWay[e]
        val hw = OsmTags.HIGHWAY_CLASSES[g.ways.highway[w].toInt()]
        val max = if (g.edgeForward[e].toInt() == 1) g.ways.maxForward[w] else g.ways.maxBackward[w]
        val v = when {
            max == OsmTags.NO_LIMIT -> 120
            max > 0 -> min(max, 130)
            else -> OsmTags.defaultSpeed(hw)
        }
        // Urban traffic rarely reaches the limit; a mild factor keeps ETAs realistic.
        val factor = if (hw.startsWith("motorway") || hw.startsWith("trunk")) 0.92 else 0.75
        return (v * factor).coerceAtLeast(5.0)
    }

    private fun edgeSeconds(g: OfflineGraph, e: Int, request: RouteRequest): Double {
        val hw = OsmTags.HIGHWAY_CLASSES[g.ways.highway[g.edgeWay[e]].toInt()]
        if (request.avoidMotorways && hw.startsWith("motorway")) return g.edgeLenDm[e] / 10.0 * 10
        return g.edgeLenDm[e] / 10.0 / (speedKmh(g, e) / 3.6)
    }

    /**
     * Edge-based A*: the search state is the edge the car arrived on, so turn restrictions (from way, via node, to way)
     * and the no-U-turn rule apply exactly. Returns the edges of the fastest path from [s] to [t].
     */
    private fun astar(g: OfflineGraph, s: Int, t: Int, request: RouteRequest): IntArray? {
        if (s == t) return IntArray(0)
        val m = g.edgeCount
        val gScore = DoubleArray(m) { Double.POSITIVE_INFINITY }
        val prevEdge = IntArray(m) { -1 }
        val closed = BooleanArray(m)
        val heap = MinHeap(1024)
        val target = g.latLon(t)
        val maxSpeed = 130 / 3.6
        fun h(v: Int) = Geo.distance(g.latLon(v), target) / maxSpeed
        val restrictions = g.restrictions

        for (e in g.edgeStart[s] until g.edgeStart[s + 1]) {
            val c = edgeSeconds(g, e, request)
            if (c < gScore[e]) { gScore[e] = c; heap.push(e, c + h(g.edgeTo[e])) }
        }
        var found = -1
        while (heap.size > 0) {
            val ein = heap.pop()
            if (closed[ein]) continue
            closed[ein] = true
            val u = g.edgeTo[ein]
            if (u == t) { found = ein; break }
            val wayIn = g.edgeWay[ein]
            for (e in g.edgeStart[u] until g.edgeStart[u + 1]) {
                if (closed[e]) continue
                val wayOut = g.edgeWay[e]
                // Forbid immediate U-turns on the same way.
                if (wayOut == wayIn && g.edgeForward[e] != g.edgeForward[ein]) continue
                if (!restrictions.allows(wayIn, u, wayOut)) continue
                val cand = gScore[ein] + edgeSeconds(g, e, request)
                if (cand < gScore[e]) {
                    gScore[e] = cand
                    prevEdge[e] = ein
                    heap.push(e, cand + h(g.edgeTo[e]))
                }
            }
        }
        if (found < 0) return null
        val path = ArrayList<Int>()
        var cur = found
        while (cur >= 0) { path += cur; cur = prevEdge[cur] }
        path.reverse()
        return path.toIntArray()
    }

    /** CSR stores only targets; find the source by binary search over edgeStart. */
    private fun fromNode(g: OfflineGraph, e: Int): Int {
        var lo = 0
        var hi = g.nodeCount
        while (lo < hi - 1) {
            val mid = (lo + hi) ushr 1
            if (g.edgeStart[mid] <= e) lo = mid else hi = mid
        }
        // Skip nodes without outgoing edges that share the same start index.
        while (lo + 1 <= g.nodeCount && g.edgeStart[lo + 1] <= e) lo++
        return lo
    }

    private data class Traversal(val way: Int, val forward: Boolean, val firstEdge: Int, val lastEdge: Int, val from: Double, val to: Double, val endNode: Int)

    private fun buildRoute(g: OfflineGraph, edges: IntArray, request: RouteRequest): Route {
        val pts = ArrayList<LatLon>(edges.size + 1)
        pts += g.latLon(fromNode(g, edges.first()))
        var seconds = 0.0
        for (e in edges) {
            pts += g.latLon(g.edgeTo[e])
            seconds += edgeSeconds(g, e, request)
        }
        val line = Polyline(pts)

        // Group edges into way traversals.
        val trav = ArrayList<Traversal>()
        var i = 0
        while (i < edges.size) {
            val w = g.edgeWay[edges[i]]
            val fwd = g.edgeForward[edges[i]].toInt() == 1
            var j = i
            while (j + 1 < edges.size && g.edgeWay[edges[j + 1]] == w) j++
            trav += Traversal(w, fwd, i, j, line.cumulative[i], line.cumulative[j + 1], g.edgeTo[edges[j]])
            i = j + 1
        }

        val ways = g.ways
        fun hw(w: Int) = OsmTags.HIGHWAY_CLASSES[ways.highway[w].toInt()]
        fun limitOf(t: Traversal) = if (t.forward) ways.maxForward[t.way] else ways.maxBackward[t.way]

        val speed = trav.mapNotNull { t ->
            when (val m = limitOf(t)) {
                OsmTags.UNKNOWN -> null
                OsmTags.NO_LIMIT -> SpeedLimitSpan(t.from, t.to, null)
                else -> SpeedLimitSpan(t.from, t.to, m)
            }
        }
        val spans = trav.map { t ->
            val m = limitOf(t).takeIf { it > 0 }
            val firstEdge = edges[t.firstEdge]
            val twoWay = hasReverse(g, firstEdge)
            RoadSpan(t.from, t.to, OsmTags.roadKind(hw(t.way), m), twoWay)
        }

        val steps = ArrayList<RouteStep>()
        val sites = ArrayList<LaneSite>()
        steps += RouteStep(0.0, trav.first().to, 0.0, Maneuver(ManeuverType.DEPART, Modifier.STRAIGHT, pts.first(), 0.0, line.bearingAt(0.0)),
            ways.str(ways.name[trav.first().way]), ways.str(ways.ref[trav.first().way]).ifBlank { null }, null, null)

        for (k in 0 until trav.size - 1) {
            val a = trav[k]
            val b = trav[k + 1]
            val d = a.to
            val inB = Geo.bearing(line.pointAt(d - 25), line.pointAt(d))
            val outB = Geo.bearing(line.pointAt(d), line.pointAt(d + 25))
            val delta = Geo.bearingDelta(inB, outB)
            val turnBit = LaneDir.fromTurnAngle(delta)
            val nameA = ways.str(ways.name[a.way]); val nameB = ways.str(ways.name[b.way])
            val refA = ways.str(ways.ref[a.way]); val refB = ways.str(ways.ref[b.way])
            val hwA = hw(a.way); val hwB = hw(b.way)
            val type = when {
                hwA == "motorway" && hwB == "motorway_link" -> ManeuverType.OFF_RAMP
                !hwA.startsWith("motorway") && hwB.startsWith("motorway") -> ManeuverType.ON_RAMP
                abs(delta) >= 35 -> ManeuverType.TURN
                nameA != nameB || refA != refB -> ManeuverType.NEW_NAME
                else -> null
            }
            if (type != null) {
                steps += RouteStep(
                    distanceAlong = d,
                    distance = b.to - b.from,
                    duration = 0.0,
                    maneuver = Maneuver(type, modifierOf(delta), line.pointAt(d), inB, outB),
                    name = nameB,
                    ref = refB.ifBlank { null },
                    destinations = null,
                    exitNumber = null,
                )
            }
            // Lane site: turn:lanes apply at the end of the way in travel direction.
            val lanesStr = ways.str(if (a.forward) ways.turnLanesForward[a.way] else ways.turnLanesBackward[a.way])
            val wayEnd = if (a.forward) ways.lastNode[a.way] else ways.firstNode[a.way]
            if (lanesStr.isNotBlank() && a.endNode == wayEnd) {
                val m = limitOf(a).takeIf { it > 0 }
                sites += LaneSite(
                    distanceAlong = d,
                    lanes = LaneDir.parseTurnLanes(lanesStr),
                    providerValid = null,
                    turn = turnBit,
                    isManeuver = type == ManeuverType.TURN || type == ManeuverType.OFF_RAMP,
                    roadKind = OsmTags.roadKind(hwA, m),
                )
            }
        }
        steps += RouteStep(line.length, 0.0, 0.0, Maneuver(ManeuverType.ARRIVE, Modifier.STRAIGHT, pts.last(), 0.0, 0.0),
            "", null, null, null)

        // On-street bike lanes on the right in driving direction, merged across traversals.
        val bikes = ArrayList<BikeLaneSpan>()
        for (t in trav) {
            val bits = ways.bike.getOrElse(t.way) { 0 }.toInt()
            val present = if (t.forward) bits and 1 != 0 else bits and 4 != 0
            if (!present) continue
            val exclusive = if (t.forward) bits and 2 != 0 else bits and 8 != 0
            val last = bikes.lastOrNull()
            if (last != null && last.exclusive == exclusive && kotlin.math.abs(last.to - t.from) < 1) bikes[bikes.size - 1] = last.copy(to = t.to)
            else bikes += BikeLaneSpan(t.from, t.to, exclusive)
        }

        // Solid lines (OSM change:lanes) in driving direction.
        val changes = trav.mapNotNull { t ->
            val raw = ways.str(if (t.forward) ways.changeLanesForward.getOrElse(t.way) { -1 } else ways.changeLanesBackward.getOrElse(t.way) { -1 })
            ChangeSpan.parse(raw)?.let { ChangeSpan(t.from, t.to, it) }
        }

        return Route(
            provider = id,
            geometry = line,
            distance = line.length,
            duration = seconds,
            steps = steps,
            laneSites = sites,
            speedLimits = speed,
            roadSpans = spans,
            features = featuresAlong(g, line),
            bikeLanes = bikes,
            laneChanges = changes,
        )
    }

    /** Traffic lights / signs within 15 m of the route, one per 30 m and kind. */
    private fun featuresAlong(g: OfflineGraph, line: Polyline): List<RoadFeature> {
        if (g.featKind.isEmpty()) return emptyList()
        val pad = 0.001
        val minLat = line.points.minOf { it.lat } - pad; val maxLat = line.points.maxOf { it.lat } + pad
        val minLon = line.points.minOf { it.lon } - pad; val maxLon = line.points.maxOf { it.lon } + pad
        val hits = ArrayList<RoadFeature>()
        for (i in g.featKind.indices) {
            val p = LatLon(g.featLat[i] / 1e7, g.featLon[i] / 1e7)
            if (p.lat !in minLat..maxLat || p.lon !in minLon..maxLon) continue
            val proj = line.project(p) ?: continue
            if (abs(proj.signedOffset) > 15) continue
            hits += RoadFeature.decode(g.featKind[i].toInt(), p, proj.distanceAlong)
        }
        hits.sortBy { it.distanceAlong }
        val out = ArrayList<RoadFeature>()
        for (f in hits) {
            if (f.kind == FeatureKind.TRAFFIC_SIGNALS || out.none { it.kind == f.kind && abs(it.distanceAlong - f.distanceAlong) < 30 }) out += f
        }
        return out
    }

    private fun hasReverse(g: OfflineGraph, e: Int): Boolean {
        val from = fromNode(g, e)
        val to = g.edgeTo[e]
        for (r in g.edgeStart[to] until g.edgeStart[to + 1]) if (g.edgeTo[r] == from) return true
        return false
    }

    private fun modifierOf(delta: Double): Modifier {
        val a = abs(delta)
        val right = delta > 0
        return when {
            a < 20 -> Modifier.STRAIGHT
            a < 60 -> if (right) Modifier.SLIGHT_RIGHT else Modifier.SLIGHT_LEFT
            a < 120 -> if (right) Modifier.RIGHT else Modifier.LEFT
            a < 165 -> if (right) Modifier.SHARP_RIGHT else Modifier.SHARP_LEFT
            else -> Modifier.UTURN
        }
    }

    /** Binary min-heap of (node, priority) with lazy deletion. */
    private class MinHeap(cap: Int) {
        private var nodes = IntArray(cap)
        private var prio = DoubleArray(cap)
        var size = 0; private set

        fun push(n: Int, p: Double) {
            if (size == nodes.size) { nodes = nodes.copyOf(size * 2); prio = prio.copyOf(size * 2) }
            var i = size++
            nodes[i] = n; prio[i] = p
            while (i > 0) {
                val parent = (i - 1) / 2
                if (prio[parent] <= prio[i]) break
                swap(i, parent); i = parent
            }
        }

        fun pop(): Int {
            val top = nodes[0]
            size--
            if (size > 0) {
                nodes[0] = nodes[size]; prio[0] = prio[size]
                var i = 0
                while (true) {
                    val l = 2 * i + 1; val r = l + 1
                    var m = i
                    if (l < size && prio[l] < prio[m]) m = l
                    if (r < size && prio[r] < prio[m]) m = r
                    if (m == i) break
                    swap(i, m); i = m
                }
            }
            return top
        }

        private fun swap(a: Int, b: Int) {
            val tn = nodes[a]; nodes[a] = nodes[b]; nodes[b] = tn
            val tp = prio[a]; prio[a] = prio[b]; prio[b] = tp
        }
    }
}
