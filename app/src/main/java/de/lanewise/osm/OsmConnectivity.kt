package de.lanewise.osm

import de.lanewise.core.geo.LatLon
import de.lanewise.lane.ConnectivityAt

/**
 * OSM `type=connectivity` relations (members `from` way, `via` node, `to` way; tag `connectivity=1:1|2:2,3`) and
 * connectivity patches from `lane_overrides.json`, matched onto the route by its OSM nodes. Pure (unit-tested).
 */
object OsmConnectivity {

    /** One connectivity: [source] for logs (`osm:r123`, `override:<id>`), [value] in OSM syntax. */
    data class Relation(val source: String, val fromWay: Long, val via: Long, val toWay: Long, val value: String)

    /**
     * The relations the route really drives through: it arrives at [Relation.via] on the from way (the segment from the
     * route's previous node) and leaves it on the to way (the segment to its next node). A relation whose from or to
     * way is not in [ways] cannot be checked and is skipped (the heuristics stay in charge there). [project] maps the via
     * node's position to a route distance (null = too far off the route).
     */
    fun onRoute(rels: List<Relation>, routeNodes: List<Long>, ways: List<LaneOverrides.WayRef>, project: (LatLon) -> Double?,
                override: Boolean): List<ConnectivityAt> {
        if (rels.isEmpty() || routeNodes.size < 3) return emptyList()
        val byId = ways.groupBy { it.id }
        val out = ArrayList<ConnectivityAt>()
        for (r in rels) {
            val from = byId[r.fromWay]?.firstOrNull() ?: continue
            val to = byId[r.toWay]?.firstOrNull() ?: continue
            // A route may pass the same node twice (a loop): every pass that matches counts.
            for (i in 1 until routeNodes.size - 1) {
                if (routeNodes[i] != r.via) continue
                if (!hasSegment(from.nodes, routeNodes[i - 1], r.via) || !hasSegment(to.nodes, r.via, routeNodes[i + 1])) continue
                val pos = ways.firstNotNullOfOrNull { w -> w.nodes.indexOf(r.via).takeIf { it >= 0 }?.let { w.geom.getOrNull(it) } }
                    ?: continue
                val d = project(pos) ?: continue
                out += ConnectivityAt(d, r.value, r.source, override)
            }
        }
        return out.sortedBy { it.distanceAlong }
    }

    /** [nodes] has [a] and [b] next to each other (either order). */
    private fun hasSegment(nodes: List<Long>, a: Long, b: Long): Boolean =
        (0 until nodes.size - 1).any { (nodes[it] == a && nodes[it + 1] == b) || (nodes[it] == b && nodes[it + 1] == a) }
}
