package de.lanewise.offline

import de.lanewise.core.geo.Geo
import de.lanewise.core.geo.LatLon
import de.lanewise.core.geo.Polyline
import de.lanewise.core.model.Maneuver
import de.lanewise.core.model.ManeuverType
import de.lanewise.core.model.Modifier
import de.lanewise.core.model.RoadSpan
import de.lanewise.core.model.Route
import de.lanewise.core.model.RouteStep
import de.lanewise.core.model.SpeedLimitSpan
import de.lanewise.lane.LaneDir
import de.lanewise.lane.LaneSite
import de.lanewise.osm.OsmTags
import de.lanewise.routing.RouteRequest
import de.lanewise.routing.RoutingException
import de.lanewise.routing.RoutingProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.min

/**
 * On-device A* router over an [OfflineGraph]. Needs no network: position comes from GNSS
 * (GPS/Galileo/GLONASS/BeiDou), the road network from the downloaded OSM extract.
 *
 * Limitations (documented in docs/ARCHITECTURE.md): no turn restrictions, no live traffic.
 */
class OfflineRouter(private val graphProvider: () -> OfflineGraph?) : RoutingProvider {
    override val id = "offline"
    override val requiresNetwork = false

    override suspend fun route(request: RouteRequest): List<Route> = withContext(Dispatchers.Default) {
        val g = graphProvider() ?: throw RoutingException("Keine Offline-Karte geladen. Bitte in den Einstellungen herunterladen.")
        if (!g.contains(request.origin) || !g.contains(request.destination)) {
            throw RoutingException("Start oder Ziel liegt außerhalb der Offline-Region.")
        }
        val s = g.nearestNode(request.origin)
        val t = g.nearestNode(request.destination, requireOutgoing = false)
        if (s < 0 || t < 0) throw RoutingException("Keine Straße in der Nähe gefunden.")
        val edges = astar(g, s, t, request) ?: throw RoutingException("Keine Route gefunden (offline).")
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

    private fun astar(g: OfflineGraph, s: Int, t: Int, request: RouteRequest): IntArray? {
        val n = g.nodeCount
        val gScore = DoubleArray(n) { Double.POSITIVE_INFINITY }
        val prevEdge = IntArray(n) { -1 }
        val closed = BooleanArray(n)
        val heap = MinHeap(1024)
        val target = g.latLon(t)
        val maxSpeed = 130 / 3.6
        fun h(v: Int) = Geo.distance(g.latLon(v), target) / maxSpeed

        gScore[s] = 0.0
        heap.push(s, h(s))
        while (heap.size > 0) {
            val u = heap.pop()
            if (u == t) break
            if (closed[u]) continue
            closed[u] = true
            val cameFrom = if (prevEdge[u] >= 0) g.edgeWay[prevEdge[u]] else -1
            for (e in g.edgeStart[u] until g.edgeStart[u + 1]) {
                val v = g.edgeTo[e]
                if (closed[v]) continue
                // Forbid immediate U-turns on the same way.
                if (prevEdge[u] >= 0 && g.edgeWay[e] == cameFrom && g.edgeForward[e] != g.edgeForward[prevEdge[u]]) continue
                val cand = gScore[u] + edgeSeconds(g, e, request)
                if (cand < gScore[v]) {
                    gScore[v] = cand
                    prevEdge[v] = e
                    heap.push(v, cand + h(v))
                }
            }
        }
        if (prevEdge[t] < 0 && s != t) return null
        val path = ArrayList<Int>()
        var cur = t
        while (cur != s) {
            val e = prevEdge[cur]
            if (e < 0) return null
            path += e
            cur = fromNode(g, e)
        }
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

        return Route(
            provider = id,
            geometry = line,
            distance = line.length,
            duration = seconds,
            steps = steps,
            laneSites = sites,
            speedLimits = speed,
            roadSpans = spans,
        )
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
