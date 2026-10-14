package de.lanewise.offline

import de.lanewise.core.geo.LatLon
import de.lanewise.osm.OsmTags
import de.lanewise.routing.RouteRequest
import de.lanewise.ui.i18n.EN
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OfflineRouterTest {

    /**
     * A junction (node 1) with the direct way west (w2) and a detour north → west → south (w1, w4, w3):
     *
     *   4 ── w4 ── 2
     *   |          |
     *   w3         w1
     *   |          |
     *   3 ── w2 ── 1
     *              |
     *              w0
     *              |
     *              0
     */
    private fun graph(restrictions: OfflineGraph.TurnRestrictions): OfflineGraph {
        val pts = listOf(52.370 to 9.740, 52.371 to 9.740, 52.372 to 9.740, 52.371 to 9.738, 52.372 to 9.738)
        val ways = listOf(0 to 1, 1 to 2, 1 to 3, 3 to 4, 4 to 2)
        // Two-way edges, CSR by source node.
        val edges = ways.flatMapIndexed { w, (a, b) -> listOf(Triple(a, b, w) to 1, Triple(b, a, w) to 0) }.sortedBy { it.first.first }
        val start = IntArray(pts.size + 1)
        edges.forEach { start[it.first.first + 1]++ }
        for (i in 0 until pts.size) start[i + 1] += start[i]
        val res = OsmTags.HIGHWAY_CLASSES.indexOf("residential").toByte()
        val n = ways.size
        return OfflineGraph(
            nodeLat = IntArray(pts.size) { (pts[it].first * 1e7).toInt() },
            nodeLon = IntArray(pts.size) { (pts[it].second * 1e7).toInt() },
            edgeStart = start,
            edgeTo = edges.map { it.first.second }.toIntArray(),
            edgeLenDm = IntArray(edges.size) { 1400 },
            edgeWay = edges.map { it.first.third }.toIntArray(),
            edgeForward = edges.map { it.second.toByte() }.toByteArray(),
            ways = OfflineGraph.Ways(ByteArray(n) { res }, IntArray(n) { 50 }, IntArray(n) { 50 }, IntArray(n) { -1 }, IntArray(n) { -1 },
                IntArray(n) { -1 }, IntArray(n) { -1 }, IntArray(n) { ways[it].first }, IntArray(n) { ways[it].second }, emptyArray()),
            bbox = doubleArrayOf(52.36, 9.73, 52.38, 9.75),
            createdAt = 0L,
            restrictions = restrictions,
        )
    }

    private fun route(g: OfflineGraph) = runBlocking {
        OfflineRouter({ g }, { EN }).route(RouteRequest(LatLon(52.370, 9.740), LatLon(52.371, 9.738))).first()
    }

    @Test
    fun takesTheDirectTurnWithoutRestrictions() {
        assertEquals(2, route(graph(OfflineGraph.TurnRestrictions.NONE)).geometry.points.size - 1)
    }

    @Test
    fun noLeftTurnForcesTheDetour() {
        val r = route(graph(OfflineGraph.TurnRestrictions(intArrayOf(0), intArrayOf(1), intArrayOf(2), byteArrayOf(0))))
        assertEquals(4, r.geometry.points.size - 1)   // 0 → 1 → 2 → 4 → 3
    }

    @Test
    fun onlyStraightOnForcesTheDetourToo() {
        val r = route(graph(OfflineGraph.TurnRestrictions(intArrayOf(0), intArrayOf(1), intArrayOf(1), byteArrayOf(1))))
        assertEquals(4, r.geometry.points.size - 1)
    }

    @Test
    fun restrictionsSurviveSaveAndLoad() {
        val f = File.createTempFile("lwg", ".lwg")
        try {
            graph(OfflineGraph.TurnRestrictions(intArrayOf(0), intArrayOf(1), intArrayOf(2), byteArrayOf(0))).save(f)
            val g = OfflineGraph.load(f)
            assertEquals(1, g.restrictions.size)
            assertTrue(!g.restrictions.allows(0, 1, 2))
            assertTrue(g.restrictions.allows(0, 1, 1))
        } finally { f.delete() }
    }
}
