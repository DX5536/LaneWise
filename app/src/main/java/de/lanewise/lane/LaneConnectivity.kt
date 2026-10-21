package de.lanewise.lane

/**
 * Explicit lane connectivity at a junction, like HERE's lane model ("which lane continues into which lane after the
 * accessor") and OSM `type=connectivity` relations (`connectivity=1:1|2:2,3`): for every lane of the arriving road
 * (left → right, 0-based) the lanes of the road the route leaves on (left → right, 0-based) it leads into, or null =
 * this lane does not lead onto the route's next road (it turns off elsewhere or ends at the stop line).
 *
 * Stored on [LaneSite.connectivity]. Sources by precedence: `lane_overrides.json` (`connectivity`) > OSM relations >
 * the heuristics (pockets, corresponding-lane rule, carried lanes) as fallback. A connectivity that contradicts the
 * junction's arrows is never used — the junction then gets no lane advice (see [contradiction]).
 */
object LaneConnectivity {

    /** Hard limit for lane numbers in a value (OSM roads in Germany have at most 8 lanes per direction). */
    const val MAX_LANES = 16

    /**
     * Parses an OSM `connectivity` value: `from:to[,to…]` groups separated by `|`, lanes counted from 1 on the left.
     * Lanes in parentheses (`3:(3)`) are optional connections: used only when the lane has no other target.
     * Returns from-lane → to-lanes (0-based), or null if the value cannot be read safely: backward lanes (`bw`),
     * non-numbers, a lane listed twice, targets that are not side by side (`1:1,3`), or lanes that cross.
     */
    fun parse(value: String): Map<Int, IntRange>? {
        val out = LinkedHashMap<Int, IntRange>()
        for (group in value.split('|')) {
            val parts = group.split(':')
            if (parts.size != 2) return null
            val from = parts[0].trim().toIntOrNull() ?: return null
            if (from < 1 || from > MAX_LANES || (from - 1) in out) return null
            val main = ArrayList<Int>(); val optional = ArrayList<Int>()
            for (tok in parts[1].split(',')) {
                val t = tok.trim()
                val opt = t.startsWith("(") && t.endsWith(")")
                val n = (if (opt) t.substring(1, t.length - 1).trim() else t).toIntOrNull() ?: return null
                if (n < 1 || n > MAX_LANES) return null
                (if (opt) optional else main) += n - 1
            }
            val targets = (main.ifEmpty { optional }).distinct().sorted()
            if (targets.isEmpty() || targets.last() - targets.first() + 1 != targets.size) return null
            out[from - 1] = targets.first()..targets.last()
        }
        if (out.isEmpty() || crossing(out)) return null
        return out
    }

    /** Lanes that cross each other after the junction (lane 1 into lane 2 while lane 2 goes into lane 1). */
    private fun crossing(c: Map<Int, IntRange>): Boolean {
        val sorted = c.entries.sortedBy { it.key }
        return sorted.zipWithNext().any { (a, b) -> b.value.first < a.value.first || b.value.last < a.value.last }
    }

    /** [parse]d value as one entry per lane of a road with [laneCount] lanes (unlisted lanes = null, do not continue). */
    fun forLanes(c: Map<Int, IntRange>, laneCount: Int): List<IntRange?> = List(laneCount) { c[it] }

    /** Back to OSM notation (`1:1|2:2,3`), for logs and the audit. */
    fun format(c: List<IntRange?>): String = c.withIndex().filter { it.value != null }
        .joinToString("|") { (i, r) -> "${i + 1}:" + r!!.joinToString(",") { "${it + 1}" } }

    /**
     * Why connectivity [c] must not be used at [site] (null = consistent): it names a lane the junction does not have,
     * leads into more lanes than the next road has ([toLanes], if known), no lane continues, or it disagrees with the
     * painted arrows — a lane whose arrows make the route's turn that does not continue, or a continuing lane whose
     * arrows cannot make the turn. Unmarked lanes (`none`) are not checked: there connectivity is the only information.
     * The caller drops the junction's lanes then (lanes unknown), it never guesses which of the two is right.
     */
    fun contradiction(site: LaneSite, c: Map<Int, IntRange>, toLanes: Int?): String? {
        val maxFrom = c.keys.max()
        if (maxFrom >= site.laneCount) return "connectivity names lane ${maxFrom + 1}, the junction has ${site.laneCount}"
        val maxTo = c.values.maxOf { it.last }
        if (toLanes != null && maxTo >= toLanes) return "connectivity leads into lane ${maxTo + 1}, the next road has $toLanes"
        for (i in site.lanes.indices) {
            val m = site.lanes[i]
            if (m == LaneDir.NONE) continue
            val makesTurn = LaneDir.permits(m, site.turn)
            if (makesTurn && i !in c) return "lane ${i + 1} has arrows for the route's turn but does not continue"
            if (!makesTurn && i in c) return "lane ${i + 1} continues but its arrows cannot make the route's turn"
        }
        return null
    }
}

/**
 * A connectivity on the route: [distanceAlong] of the via node, [value] in OSM notation, [source] for logs
 * (`override:<id>` or `osm:r<relation>`), [override] = hand-checked patch (wins over OSM at the same junction).
 */
data class ConnectivityAt(val distanceAlong: Double, val value: String, val source: String, val override: Boolean)
