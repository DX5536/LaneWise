package de.lanewise.lane

import kotlin.math.abs

/**
 * Lane arrow directions as a bitmask.
 *
 * The layout (clockwise from straight) mirrors the scheme observed in Petal Maps' lane assets
 * (see docs/REVERSE_ENGINEERING.md): one bit per arrow so a lane with several markings is a
 * simple OR, and "is this lane usable for my manoeuvre" is a single AND.
 */
object LaneDir {
    const val NONE = 0
    const val STRAIGHT = 1
    const val SLIGHT_RIGHT = 2
    const val RIGHT = 4
    const val SHARP_RIGHT = 8
    const val UTURN = 16
    const val SHARP_LEFT = 32
    const val LEFT = 64
    const val SLIGHT_LEFT = 128
    const val MERGE_TO_LEFT = 256
    const val MERGE_TO_RIGHT = 512

    const val ANY_RIGHT = SLIGHT_RIGHT or RIGHT or SHARP_RIGHT
    const val ANY_LEFT = SLIGHT_LEFT or LEFT or SHARP_LEFT or UTURN

    /** OSM `turn:lanes` token or OSRM `indications` entry → bit. Unknown tokens map to NONE. */
    fun fromToken(token: String): Int = when (token.trim().lowercase().replace(' ', '_')) {
        "through", "straight" -> STRAIGHT
        "slight_right" -> SLIGHT_RIGHT
        "right" -> RIGHT
        "sharp_right" -> SHARP_RIGHT
        "reverse", "uturn" -> UTURN
        "sharp_left" -> SHARP_LEFT
        "left" -> LEFT
        "slight_left" -> SLIGHT_LEFT
        "merge_to_left" -> MERGE_TO_LEFT
        "merge_to_right" -> MERGE_TO_RIGHT
        else -> NONE // "none", "", unknown
    }

    /** Parse one lane value like "left;through". */
    fun parseLane(value: String): Int = value.split(';').fold(NONE) { acc, t -> acc or fromToken(t) }

    /** Parse a full OSM `turn:lanes` value like "left|through|through;right" (left-to-right). */
    fun parseTurnLanes(value: String): List<Int> = value.split('|').map { parseLane(it) }

    /** Classify a turn angle (deg, + = right) into the arrow bit drivers expect. */
    fun fromTurnAngle(delta: Double): Int {
        val a = abs(delta)
        val right = delta > 0
        return when {
            a < 20 -> STRAIGHT
            a < 60 -> if (right) SLIGHT_RIGHT else SLIGHT_LEFT
            a < 120 -> if (right) RIGHT else LEFT
            a < 165 -> if (right) SHARP_RIGHT else SHARP_LEFT
            else -> UTURN
        }
    }

    /**
     * Whether a lane with arrows [laneMask] can be used to perform [wanted].
     * Unmarked lanes (NONE) are treated as through lanes, which matches German practice
     * and how OSRM resolves "none".
     */
    fun permits(laneMask: Int, wanted: Int): Boolean {
        val m = if (laneMask and (MERGE_TO_LEFT or MERGE_TO_RIGHT).inv() == NONE) STRAIGHT else laneMask
        if (m and wanted != 0) return true
        // Tolerate slight vs. normal mismatches between map tagging and geometry-derived angles.
        return when (wanted) {
            SLIGHT_RIGHT -> m and (RIGHT or STRAIGHT) != 0 && m and LEFT == 0
            SLIGHT_LEFT -> m and (LEFT or STRAIGHT) != 0 && m and RIGHT == 0
            RIGHT -> m and (SLIGHT_RIGHT or SHARP_RIGHT) != 0
            LEFT -> m and (SLIGHT_LEFT or SHARP_LEFT) != 0
            SHARP_RIGHT -> m and RIGHT != 0
            SHARP_LEFT -> m and LEFT != 0
            // Wenden: from the left-turn lane (a `reverse` arrow is rarely painted / tagged), never from a through lane.
            UTURN -> m and (SHARP_LEFT or LEFT) != 0
            else -> false
        }
    }
}

/** Road category used for distance thresholds and the German keep-right rule. */
enum class RoadKind { MOTORWAY, TRUNK, URBAN, RURAL }

/**
 * A point along the route where lane markings are known (an intersection, a fork, an exit).
 * Lanes are ordered left → right in driving direction, like OSM `turn:lanes`.
 */
data class LaneSite(
    /** Distance from route start (m) of the junction itself. */
    val distanceAlong: Double,
    /** Arrow bitmask per lane, left → right. */
    val lanes: List<Int>,
    /**
     * Lanes the provider says can be used for the route here (OSRM `valid`, TomTom `follow`).
     * If null, the planner derives them from [lanes] and [turn].
     */
    val providerValid: List<Boolean>?,
    /** Direction the route takes at this site (LaneDir bit). */
    val turn: Int,
    /** True if the route makes an actual manoeuvre here, false if it only passes through. */
    val isManeuver: Boolean,
    val roadKind: RoadKind,
    /**
     * Every lane here runs all the way from the previous junction (OSM lane counts, see [markFullLengthLanes]):
     * turn-only lanes at the edges are then ordinary lanes, not pockets that open before the junction.
     */
    val fullLength: Boolean = false,
    /** Per lane: may cars use it (OSM lane access; false = bus lane …)? Null if not tagged. Never recommended if false. */
    val carLanes: List<Boolean>? = null,
    /**
     * Explicit lane connectivity ([LaneConnectivity]): per lane (left → right) the lanes of the road the route leaves
     * on that it leads into (0-based, left → right), null = it does not lead onto the route's next road. Null if not
     * known (then the heuristics decide). Checked against the arrows before it is set ([Route.withConnectivity]).
     */
    val connectivity: List<IntRange?>? = null,
    /**
     * How far before the junction its turn pockets open (m), measured on the ground (`pocket_start_m` of a hand-checked
     * patch, e.g. from a marking plan). Null = the road-kind default of the lane ribbon.
     */
    val pocketLength: Double? = null,
    /**
     * Lanes that ended on the right edge between the previous site and this one, without a junction (OSM
     * `merge_to_left`, lane count dropping; [markEndedLanes]). The lateral frame is anchored on the rightmost lane, so
     * the previous site's lane x is this site's lane x - [endedRight]; a lane below 0 is gone and must be left.
     */
    val endedRight: Int = 0,
    /** Where the last of those lanes ended (route distance, m): the lane change out of it is due before this point. */
    val endedRightAt: Double? = null,
) {
    val laneCount get() = lanes.size

    /** Lanes the route's next road has at least, by [connectivity] (null if not known). */
    fun connectedToLanes(): Int? = connectivity?.filterNotNull()?.maxOfOrNull { it.last + 1 }

    fun allowed(): BooleanArray {
        // Explicit connectivity says which lanes lead onto the route's next road: it beats the provider's flags.
        connectivity?.takeIf { c -> c.size == lanes.size && c.any { it != null } }?.let { c ->
            return BooleanArray(lanes.size) { c[it] != null }
        }
        val pv = providerValid
        // At a real turn the painted arrows decide: provider flags that call a lane usable although its arrows cannot make
        // the turn are wrong (OSRM at Schiffgraben → Berliner Allee: left|left|through|through;right, valid = lanes 2–4).
        val pvContradictsArrows = pv != null && pv.size == lanes.size && isManeuver && turn != LaneDir.STRAIGHT &&
            lanes.any { LaneDir.permits(it, turn) } &&
            pv.indices.any { pv[it] && lanes[it] != LaneDir.NONE && !LaneDir.permits(lanes[it], turn) }
        val arr = if (pv != null && pv.size == lanes.size && pv.any { it } && !pvContradictsArrows) {
            pv.toBooleanArray()
        } else {
            BooleanArray(lanes.size) { LaneDir.permits(lanes[it], turn) }
        }
        // A lane that ends by merging (acceleration lane, `merge_to_left` on the right edge) is never the lane to be in
        // while another lane works (audit, Bückeburger Allee: the joining on-ramp lane was recommended).
        val merging = BooleanArray(lanes.size) { isMergeOnly(it) }
        if (arr.indices.any { arr[it] && !merging[it] }) for (i in arr.indices) if (merging[i]) arr[i] = false
        // A lane closed to cars (bus lane, OSM `access:lanes` / `bus:lanes`) is never usable (Hollerithallee).
        carLanes?.takeIf { it.size == lanes.size }?.let { car ->
            if (arr.indices.any { arr[it] && car[it] }) for (i in arr.indices) if (!car[i]) arr[i] = false
        }
        // Never return an empty set: if nothing matches, the data is inconsistent — allow all.
        if (arr.none { it }) arr.fill(true)
        return arr
    }

    /** Lane [i] only merges into its neighbour (OSM `merge_to_left` / `merge_to_right`): it ends. */
    fun isMergeOnly(i: Int): Boolean {
        val m = lanes[i]
        return m != LaneDir.NONE && m and (LaneDir.MERGE_TO_LEFT or LaneDir.MERGE_TO_RIGHT).inv() == 0
    }

    /** Number of turn-pocket lanes on the left edge that only serve left turns / U-turns. */
    fun leftPockets(): Int {
        if (fullLength) return 0
        // Every lane turns left (a slip road / the turning arm of a split junction): the whole road turns, no pockets
        // (Aegidientorplatz: `reverse;left|left` - as pockets both left-turn lanes looked like one lane further on).
        if (lanes.all { it != LaneDir.NONE && it and LaneDir.ANY_LEFT.inv() == 0 }) return 0
        var n = 0
        while (n < lanes.size - 1) {
            val m = lanes[n]
            // A lane merging in from the left edge is not a through lane either.
            if ((m != LaneDir.NONE && m and LaneDir.ANY_LEFT.inv() == 0) || m == LaneDir.MERGE_TO_RIGHT) n++ else break
        }
        return n
    }

    /** Number of turn-pocket lanes on the right edge that only serve right turns. */
    fun rightPockets(): Int {
        if (fullLength) return 0
        if (lanes.all { it != LaneDir.NONE && it and LaneDir.ANY_RIGHT.inv() == 0 }) return 0
        var n = 0
        while (n < lanes.size - 1 - leftPockets()) {
            val m = lanes[lanes.size - 1 - n]
            // An acceleration lane joining on the right (`merge_to_left`) is not a through lane: the car's lane keeps its
            // number when it appears (audit, Bückeburger Allee: the right lane became "lane 1" and the strip zig-zagged).
            if ((m != LaneDir.NONE && m and LaneDir.ANY_RIGHT.inv() == 0) || m == LaneDir.MERGE_TO_LEFT) n++ else break
        }
        return n
    }

    /**
     * Lateral coordinate of lane [i] in "lane units", where 0 is the rightmost *through* (core) lane,
     * positive is further left. Right turn pockets get negative values; left pockets extend beyond the core.
     * This lets lanes of consecutive sites with different lane counts be compared: a lane change is a
     * change in this coordinate.
     */
    fun lateral(i: Int): Int = (laneCount - 1 - rightPockets()) - i
}

/** Planner output for one site. */
data class LaneAdvice(
    val site: LaneSite,
    /** The single best lane index (left → right). */
    val recommended: Int,
    /** All lanes that work for the route at this site. */
    val allowed: BooleanArray,
    /** Lanes that work AND keep the total number of lane changes minimal (subset of allowed). */
    val optimal: BooleanArray,
    /** Lane changes needed between the previous site's recommended lane and this one (signed, + = to the left). */
    val changeFromPrevious: Int,
    /**
     * After a turn: lateral coordinate of the lane to turn into; after lanes ended on the right on the way
     * ([LaneSite.endedRight]): the previous lane in this site's frame. Null otherwise.
     */
    val entryLateral: Int? = null,
) {
    override fun equals(other: Any?) = other is LaneAdvice && other.site == site &&
        other.recommended == recommended && other.allowed.contentEquals(allowed) &&
        other.optimal.contentEquals(optimal) && other.changeFromPrevious == changeFromPrevious

    override fun hashCode() = site.hashCode() * 31 + recommended
}

/**
 * Where lane changes are allowed along a stretch of the route, from OSM `change:lanes(:forward|:backward)`:
 * one entry per lane (left → right), bit [LEFT] = may change to the left from this lane, [RIGHT] = to the right.
 * A solid line between two lanes (Fahrstreifenbegrenzung, Zeichen 295) clears the bits on both sides.
 */
data class ChangeSpan(val from: Double, val to: Double, val perms: List<Int>) {
    /** May the car move one lane to the right from lateral coordinate [lateral] (0 = rightmost through lane)? */
    fun canMoveRight(lateral: Int): Boolean = perm(lateral)?.let { it and RIGHT != 0 } ?: true

    /** May the car move one lane to the left from [lateral]? */
    fun canMoveLeft(lateral: Int): Boolean = perm(lateral)?.let { it and LEFT != 0 } ?: true

    /** Lanes counted from the right; pockets (negative laterals) and unknown lanes are unrestricted. */
    private fun perm(lateral: Int): Int? = perms.getOrNull(perms.size - 1 - lateral)?.takeIf { lateral >= 0 }

    /** Is the line between lane [k]-1 and lane [k] (counted from the left of [laneCount] lanes) solid? */
    fun solidBoundary(k: Int, laneCount: Int): Boolean = solidBoundary(perms, k, laneCount)

    companion object {
        const val LEFT = 1
        const val RIGHT = 2

        /**
         * Line between lane [k]-1 and lane [k] (from the left) is drawn solid if either side may not cross it.
         * [perms] are aligned at the right edge when the drawn road has a different number of lanes (pockets).
         */
        fun solidBoundary(perms: List<Int>, k: Int, laneCount: Int): Boolean {
            val off = perms.size - laneCount
            val a = perms.getOrNull(off + k - 1)
            val b = perms.getOrNull(off + k)
            return (a != null && a and RIGHT == 0) || (b != null && b and LEFT == 0)
        }

        /**
         * [top] (hand-checked spans, e.g. from a marking plan) laid over [base]: base spans are cut where a top span
         * lies, so at every distance exactly one span applies (the list stays free of overlaps, see [Route.laneChangeAt]).
         */
        fun overlay(base: List<ChangeSpan>, top: List<ChangeSpan>): List<ChangeSpan> {
            if (top.isEmpty()) return base
            var out = base
            for (t in top.sortedBy { it.from }) {
                out = out.flatMap { b ->
                    if (b.to <= t.from || b.from >= t.to) listOf(b)
                    else listOfNotNull(b.copy(to = t.from).takeIf { it.to - it.from > 0.5 }, b.copy(from = t.to).takeIf { it.to - it.from > 0.5 })
                } + t
            }
            return out.sortedBy { it.from }
        }

        /**
         * OSM spans first; a TomTom span is added only where no OSM span overlaps it (OSM is the source the owner fixes).
         */
        fun merge(osm: List<ChangeSpan>, tomtom: List<ChangeSpan>): List<ChangeSpan> =
            (osm + tomtom.filter { t -> osm.none { o -> o.from < t.to && t.from < o.to } }).sortedBy { it.from }

        /**
         * TomTom `laneSeparators` (n + 1 values for n lanes, left → right, outer edges included) → lane permissions.
         * Dashed lines may be crossed; solid / double solid may not. Mixed lines follow § 41 StVO (Zeichen 295 + 340):
         * the lane on the dashed side may cross. Null if nothing is restricted. Unknown values count as dashed.
         */
        fun fromTomTomSeparators(seps: List<String>, lanes: Int): List<Int>? {
            if (lanes < 2 || seps.size != lanes + 1) return null
            fun solid(s: String) = s == "SINGLE_SOLID" || s == "DOUBLE_SOLID"
            val perms = (0 until lanes).map { i ->
                var p = 0
                // Line on the left of lane i is seps[i]; on its right seps[i + 1].
                val leftLine = seps[i]; val rightLine = seps[i + 1]
                if (i == 0 || !(solid(leftLine) || leftLine == "DASHED_SOLID")) p = p or LEFT    // DASHED_SOLID: solid on our side
                if (i == lanes - 1 || !(solid(rightLine) || rightLine == "SOLID_DASHED")) p = p or RIGHT
                p
            }
            return perms.takeIf { ps -> ps.any { it != (LEFT or RIGHT) } }
        }

        /** Parses `yes|not_left|no` etc.; null if the value has no restriction at all or cannot be read. */
        fun parse(value: String): List<Int>? {
            val perms = value.split('|').map { v ->
                when (v.trim()) {
                    "yes", "" -> LEFT or RIGHT
                    "no" -> 0
                    "not_left", "only_right" -> RIGHT
                    "not_right", "only_left" -> LEFT
                    else -> return null
                }
            }
            return perms.takeIf { p -> p.any { it != (LEFT or RIGHT) } }
        }
    }
}

/**
 * OSRM puts lane data on each intersection it passes. When the junction where the route actually turns has no
 * lane data of its own, the lanes of the intersection just before it (≤ 80 m) are the turn lanes — but OSRM marks
 * them for going *straight* there (e.g. [left | straight+right] valid = straight, then "turn left" 50 m later),
 * and the lane bar would recommend the wrong lane. Such a site is re-targeted to the upcoming turn.
 * [turns] are (route distance, LaneDir bit) of real turns.
 */
fun attachTurnsToLaneSites(sites: List<LaneSite>, turns: List<Pair<Double, Int>>): List<LaneSite> {
    val out = sites.toMutableList()
    for ((d, bit) in turns) {
        if (bit == LaneDir.STRAIGHT || out.any { kotlin.math.abs(it.distanceAlong - d) < 15 && it.isManeuver }) continue
        // Also a site at the turn itself that OSRM treats as "straight" (a later intersection of the previous step):
        // the U-turn at the end of Hildesheimer Straße was planned straight on, in the through lane (owner's demo).
        val i = out.indexOfLast { it.distanceAlong < d + 15 && d - it.distanceAlong <= 80 && !it.isManeuver }
        if (i < 0) continue
        val s = out[i]
        if (s.lanes.none { LaneDir.permits(it, bit) }) continue
        // The site now stands for another junction's turn: its connectivity (to the road after *this* node) no longer fits.
        out[i] = s.copy(turn = bit, isManeuver = true, providerValid = null, connectivity = null)
    }
    return out
}

/**
 * Marks sites whose edge turn lanes are not pockets (see [LaneSite.fullLength]): the route passes the previous site
 * straight on, that site has exactly as many through lanes as this one has lanes, and OSM counts at least that many
 * lanes all the way in between. Then each lane is the continuation of a through lane — e.g. Schulenburger Landstraße:
 * `left | through | through;right`, then two lanes, then `through | right` at Beneckeallee: the right lane *is* the
 * old through;right lane, so turning right needs no lane change. Without the mark the right-only lane counted as a
 * pocket opening to the right, and the planner kept the car in the left lane and asked for a late change.
 * A real pocket (one more lane than through lanes before) is never marked, even if OSM counts it along the whole way.
 */
/**
 * Sets [LaneSite.endedRight] / [LaneSite.endedRightAt] from the places ([ended]: route distance and how many lanes ended
 * on the right edge there) between each site and the one before it. Only real lane ends count - lanes leaving at a
 * junction are not in [ended] (they carry no `merge_to_left`).
 */
fun markEndedLanes(sites: List<LaneSite>, ended: List<Pair<Double, Int>>): List<LaneSite> {
    if (ended.none { it.second > 0 }) return sites
    val sorted = sites.sortedBy { it.distanceAlong }
    return sorted.mapIndexed { k, s ->
        val from = if (k == 0) Double.NEGATIVE_INFINITY else sorted[k - 1].distanceAlong
        // A lane ending right at the site (< 10 m) belongs to the junction itself, not to the stretch before it.
        val here = ended.filter { it.second > 0 && it.first > from && it.first < s.distanceAlong - 10 }
        if (here.isEmpty()) s.copy(endedRight = 0, endedRightAt = null)
        else s.copy(endedRight = here.sumOf { it.second }, endedRightAt = here.maxOf { it.first })
    }
}

fun markFullLengthLanes(sites: List<LaneSite>, laneCountAt: (Double) -> Int?): List<LaneSite> {
    val sorted = sites.sortedBy { it.distanceAlong }
    val out = ArrayList<LaneSite>(sorted.size)
    fun countedAll(from: Double, to: Double, n: Int): Boolean {
        var d = from + 15
        while (d < to - 5) { if ((laneCountAt(d) ?: return false) < n) return false; d += 10 }
        return true
    }
    for ((k, raw) in sorted.withIndex()) out += markOne(k, raw, sorted, out, ::countedAll)
    return out
}

private fun markOne(k: Int, raw: LaneSite, sorted: List<LaneSite>, done: List<LaneSite>,
                    countedAll: (Double, Double, Int) -> Boolean): LaneSite {
    run {
        val s = if (raw.fullLength) raw.copy(fullLength = false) else raw
        if (k == 0) return s
        val prevMarked = done[k - 1]
        // The same lane picture again, the route straight on and the lanes counted all the way (several nodes of one
        // approach - audit, Langenforther Straße: `through | slight_right` three times within 120 m): the same lanes,
        // so they are read the same way. Before, the first was "full length" and the next two had a right pocket - one
        // lane with two lateral numbers, a phantom "move right" and the strip zig-zagging 0 → 1 → 0.
        // Also two nodes of one junction (≤ 30 m) with as many lanes, whatever their arrows (audit, Bückeburger Allee
        // roundabout: `through;right | right`, 2 m later `left;through;right | right` - read differently, the plan moved
        // the car "right" between the two nodes).
        val gap = s.distanceAlong - prevMarked.distanceAlong
        if (prevMarked.laneCount == s.laneCount && !(prevMarked.isManeuver && prevMarked.turn != LaneDir.STRAIGHT) &&
            (gap <= 30 || prevMarked.lanes == s.lanes && countedAll(prevMarked.distanceAlong, s.distanceAlong, s.laneCount))) {
            return s.copy(fullLength = prevMarked.fullLength)
        }
        if (s.leftPockets() == 0 && s.rightPockets() == 0) return s
        val prev = sorted[k - 1]
        if (prev.isManeuver && prev.turn != LaneDir.STRAIGHT) return s
        // Lanes that carry on: by the previous junction's explicit connectivity if known, else its straight lanes.
        val carried = prev.connectedToLanes() ?: prev.lanes.count { LaneDir.permits(it, LaneDir.STRAIGHT) }
        if (carried != s.laneCount) return s
        if (prev.distanceAlong + 15 >= s.distanceAlong - 5) return s
        if (!countedAll(prev.distanceAlong, s.distanceAlong, s.laneCount)) return s
        return s.copy(fullLength = true)
    }
}

/**
 * Motorway junctions whose lane picture (OSRM) has more lanes than the carriageway the route is on (OSM `lanes`, known
 * all along the last 400 m): the picture belongs to the wider road next to it. Kept are the [n] lanes around the usable
 * ones — e.g. A 37 → A 7 at Kreuz Hannover-Buchholz: the route is already on a barrier-separated one-lane branch, OSRM
 * shows `through | through | exit`; drawn as three lanes the app said "move right" and, as the ramp widened into
 * Hamburg / Kassel, "move left" again. [fast] tells whether the road before a distance is a motorway / expressway.
 */
fun trimToRoadLanes(sites: List<LaneSite>, laneCountAt: (Double) -> Int?, fast: (Double) -> Boolean): List<LaneSite> = sites.map { s ->
    // Explicit connectivity was checked against these lanes: it is not trimmed by a guess.
    if (s.laneCount < 2 || s.connectivity != null || !fast(s.distanceAlong - 60)) return@map s
    // 400 m: an exit lane that opens before the junction (pocket) shows up in OSM within that stretch and keeps the picture.
    val counts = generateSequence(s.distanceAlong - 400) { it + 20 }.takeWhile { it < s.distanceAlong - 10 }.map(laneCountAt).toList()
    if (counts.isEmpty() || counts.any { it == null }) return@map s
    val n = counts.filterNotNull().max()
    if (n >= s.laneCount) return@map s
    val allowed = s.allowed()
    val first = allowed.indexOfFirst { it }; val last = allowed.indexOfLast { it }
    if (first < 0 || last - first + 1 > n) return@map s
    // Window of n lanes that contains every usable lane, as far right as possible (exits leave to the right).
    val start = (last - n + 1).coerceIn(0, first)
    s.copy(lanes = s.lanes.subList(start, start + n), providerValid = s.providerValid?.subList(start, start + n))
}

/**
 * Junctions with two nodes (dual carriageways), as OSRM reports them: lanes at the first node, where the route goes
 * straight, and the turn 20–50 m later at the second. The lanes at the first node that already point the way of the
 * turn are the turn lanes (OSRM marks them "not for this route" because the route goes straight there) — they are
 * usable at the first node too. And a site within 30 m after a turn that repeats the turn's lanes is the same junction
 * seen again: dropped. Audit route 16 (Blumenauer Straße → left into Fössestraße) asked for "move left" 30 m before the
 * turn and then wobbled right and left.
 */
fun mergeDoubleNodeJunctions(sites: List<LaneSite>): List<LaneSite> {
    val sorted = sites.sortedBy { it.distanceAlong }
    val out = ArrayList<LaneSite>()
    for ((i, s) in sorted.withIndex()) {
        val prev = out.lastOrNull()
        if (prev != null && prev.isManeuver && prev.turn != LaneDir.STRAIGHT && !s.isManeuver &&
            s.distanceAlong - prev.distanceAlong <= 30 && s.lanes == prev.lanes) continue
        val next = sorted.getOrNull(i + 1)
        if (!s.isManeuver && s.connectivity == null && next != null && next.isManeuver && next.turn != LaneDir.STRAIGHT &&
            next.distanceAlong - s.distanceAlong <= 50) {
            val allowed = s.allowed()
            val extra = s.lanes.map { it != LaneDir.NONE && LaneDir.permits(it, next.turn) && it and LaneDir.STRAIGHT == 0 }
            if (extra.any { it } && extra.indices.any { extra[it] && !allowed[it] }) {
                out += s.copy(providerValid = allowed.indices.map { allowed[it] || extra[it] })
                continue
            }
        }
        out += s
    }
    return out
}

/**
 * Lane sites inside a roundabout ([spans]: from the entry step to the exit step, route distances) are dropped; the
 * entry's lanes stay. Nodes on the circulating carriageway were planned as "straight" (going round is not straight),
 * and the plan moved the car out of the inner lane and back within 60 m (audit, Friedrich-Ebert-Straße roundabout).
 */
fun dropRoundaboutInterior(sites: List<LaneSite>, spans: List<ClosedFloatingPointRange<Double>>): List<LaneSite> =
    if (spans.isEmpty()) sites else sites.filter { s -> spans.none { s.distanceAlong > it.start + 3 && s.distanceAlong <= it.endInclusive + 3 } }

/**
 * "Straight on" for the route, but the only usable lanes have turn arrows only (e.g. `left | left | slight_right |
 * slight_right`, usable = the two left lanes): the road turns there although the name continues (Aegidientorplatz →
 * Friedrichswall). Treated as that turn, so the lanes before and after are matched like at a turn — measured from the
 * right edge they did not line up and the strip shifted one lane after the junction (audit STRIP_ZIGZAG).
 */
fun turnsHiddenAsStraight(sites: List<LaneSite>): List<LaneSite> = sites.map { s ->
    if (s.isManeuver || s.turn != LaneDir.STRAIGHT) return@map s
    val pv = s.providerValid ?: return@map s
    if (pv.size != s.lanes.size || pv.none { it }) return@map s
    val used = s.lanes.filterIndexed { i, _ -> pv[i] }
    if (used.any { it == LaneDir.NONE || it and LaneDir.STRAIGHT != 0 }) return@map s
    val dir = when {
        used.all { it and LaneDir.ANY_LEFT != 0 && it and LaneDir.ANY_RIGHT == 0 } -> LaneDir.LEFT
        used.all { it and LaneDir.ANY_RIGHT != 0 && it and LaneDir.ANY_LEFT == 0 } -> LaneDir.RIGHT
        else -> return@map s
    }
    s.copy(turn = dir, isManeuver = true)
}
