package de.lanewise.lane

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Chooses lanes across a sequence of upcoming lane sites.
 *
 * Classic navigation apps (and the "lane arrows" bar in Petal Maps) only look at the *next*
 * manoeuvre: they highlight every lane that works there. That regularly forces two lane changes
 * where one would do — e.g. moving right for an exit and then back left for the turn after it.
 *
 * LaneWise treats lane choice as a shortest-path problem over a layered graph (one layer per site,
 * one node per lane) — the same idea AMAP's lane engine uses ("car_playmergenum", "optimal lane",
 * solid-line merge limits; see docs/REVERSE_ENGINEERING.md) — and solves it with dynamic programming:
 *
 *  - node cost: lane not allowed for the route at that site → impossible;
 *    optional German keep-right tie-breaker on motorways (Rechtsfahrgebot, §7 StVO)
 *  - edge cost: CHANGE per lane change between consecutive sites (lateral coordinates, so sites with
 *    different lane counts / turn pockets are comparable), plus a penalty when the gap between the
 *    two sites is too short to make those changes safely. Stretches where a solid line forbids the
 *    change (OSM `change:lanes`) do not count as usable gap; a change with no legal stretch at all is
 *    penalised heavily (§ 295 StVO — crossing a solid line is not an option we recommend).
 *
 * With "avoid lane changes" off, the horizon collapses to the next site (classic behaviour).
 */
class LanePlanner(private val config: Config = Config()) {

    data class Config(
        /** Look at most this many sites ahead. */
        val maxSites: Int = 8,
        /** And at most this far ahead (m). */
        val horizonMeters: Double = 4_000.0,
        val changeCost: Double = 10.0,
        /** Penalty per lane that cannot be changed in the available distance. */
        val shortGapPenalty: Double = 25.0,
        /** Keep-right tie-breaker per lane left of the rightmost core lane (motorway/trunk only). */
        val keepRightCost: Double = 0.5,
        /** Tiny tie-breaker towards the centre of the allowed set on urban roads. */
        val centeringCost: Double = 0.02,
        /**
         * Cost per lane of turning into a lane other than the "corresponding" one. Much cheaper than a lane
         * change later: turning right straight into the left lane you need 200 m further beats changing there.
         */
        val turnEntryCost: Double = 1.0,
        /** Per lane change that can only be made across a solid line (no legal stretch in the gap). */
        val solidLinePenalty: Double = 200.0,
    )

    data class Options(
        val avoidLaneChanges: Boolean = true,
        val keepRight: Boolean = true,
        /** Assumed lateral lane coordinate of the car (see [LaneSite.lateral]), null if unknown. */
        val currentLateral: Int? = null,
        /** Car position along the route (m). */
        val carDistance: Double = 0.0,
        /** Allow choosing the lane you turn into (instead of the strictly corresponding one). */
        val freeTurnEntry: Boolean = true,
        /** Solid-line stretches (OSM `change:lanes`): changes must happen where the line is dashed. */
        val changeSpans: List<ChangeSpan> = emptyList(),
    )

    /** Moving from lane i of one site to lane j of the next: the lane entered after the junction and the changes after it. */
    data class Transition(val entry: Int, val changes: Int, val entryCost: Double)

    fun plan(allSites: List<LaneSite>, options: Options): List<LaneAdvice> {
        val ahead = allSites.filter { it.distanceAlong >= options.carDistance && it.laneCount > 0 }
            .sortedBy { it.distanceAlong }
        if (ahead.isEmpty()) return emptyList()
        val horizon = if (options.avoidLaneChanges) {
            ahead.takeWhile { it.distanceAlong - options.carDistance <= config.horizonMeters }
                .take(config.maxSites)
                .ifEmpty { listOf(ahead.first()) }
        } else {
            listOf(ahead.first())
        }

        val allowed = horizon.map { it.allowed() }
        val full = solve(horizon, allowed, options, includeTieBreakers = true)
        val changesOnly = solve(horizon, allowed, options, includeTieBreakers = false)

        val path = full.bestPath()
        val optimalSets = changesOnly.optimalSets(tolerance = config.changeCost * 0.5)

        return horizon.mapIndexed { k, site ->
            val entry = if (k == 0) options.currentLateral
            else transition(horizon[k - 1], path[k - 1], allowed[k - 1], site, path[k], options.freeTurnEntry).entry
            val change = if (entry == null) 0 else site.lateral(path[k]) - entry
            LaneAdvice(
                site = site,
                recommended = path[k],
                allowed = allowed[k],
                optimal = optimalSets[k],
                changeFromPrevious = change,
                entryLateral = if (k > 0 && horizon[k - 1].isManeuver) entry else null,
            )
        }
    }

    /**
     * Lateral coordinate (in [next]'s frame) of the lane the car is in right after leaving lane [i] of [prev].
     *
     * Passing straight through keeps the lateral coordinate. Turning follows the German rule of turning
     * into the corresponding lane: the k-th left-turn lane counted from the left enters the k-th lane
     * from the left of the new road (mirrored for right turns). Turn pockets at [next] open later, so
     * entry lanes are counted within [next]'s core lanes.
     */
    fun entryLateral(prev: LaneSite, i: Int, prevAllowed: BooleanArray, next: LaneSite): Int {
        if (!prev.isManeuver || prev.turn == LaneDir.STRAIGHT) return prev.lateral(i)
        val core = next.laneCount - next.leftPockets() - next.rightPockets()
        return when {
            prev.turn and LaneDir.ANY_LEFT != 0 -> {
                val rank = (0 until i).count { prevAllowed[it] }
                next.lateral(next.leftPockets() + rank.coerceAtMost(core - 1))
            }
            prev.turn and LaneDir.ANY_RIGHT != 0 -> {
                val rank = (i + 1 until prev.laneCount).count { prevAllowed[it] }
                next.lateral(next.laneCount - 1 - next.rightPockets() - rank.coerceAtMost(core - 1))
            }
            else -> prev.lateral(i)
        }
    }

    /**
     * Transition from lane [i] of [prev] to lane [j] of [next]. Through a turn (with [freeEntry]) the car may
     * enter any through lane of the new road — the one that is already right for [j] if possible — at a small
     * [Config.turnEntryCost] per lane away from the corresponding lane; remaining offsets are lane changes.
     */
    fun transition(prev: LaneSite, i: Int, prevAllowed: BooleanArray, next: LaneSite, j: Int, freeEntry: Boolean = true): Transition {
        val corr = entryLateral(prev, i, prevAllowed, next)
        val target = next.lateral(j)
        if (!freeEntry || !prev.isManeuver || prev.turn == LaneDir.STRAIGHT) return Transition(corr, abs(target - corr), 0.0)
        // Any lane of the new road may be entered, including one that becomes a turn lane further on:
        // keeping to that side after the turn is not a lane change.
        val lo = next.lateral(next.laneCount - 1)
        val hi = next.lateral(0)
        val e = target.coerceIn(lo, hi)
        return Transition(e, abs(target - e), config.turnEntryCost * abs(e - corr))
    }

    /**
     * Length of [d0]..[d1] where a solid line forbids at least one of the single-lane moves needed to get from
     * lateral [fromLat] to [toLat].
     */
    fun blockedLength(spans: List<ChangeSpan>, fromLat: Int, toLat: Int, d0: Double, d1: Double): Double {
        if (spans.isEmpty() || fromLat == toLat) return 0.0
        var blocked = 0.0
        for (s in spans) {
            val overlap = min(d1, s.to) - max(d0, s.from)
            if (overlap <= 0) continue
            val forbidden = if (toLat < fromLat) (toLat + 1..fromLat).any { !s.canMoveRight(it) }
            else (fromLat until toLat).any { !s.canMoveLeft(it) }
            if (forbidden) blocked += overlap
        }
        return blocked
    }

    /** Minimum distance (m) needed per lane change on this kind of road. */
    fun metersPerChange(kind: RoadKind): Double = when (kind) {
        RoadKind.MOTORWAY -> 150.0
        RoadKind.TRUNK -> 120.0
        RoadKind.RURAL -> 80.0
        RoadKind.URBAN -> 40.0
    }

    private inner class Solution(
        val sites: List<LaneSite>,
        /** forward[k][j]: min cost of reaching lane j at site k (including node cost). */
        val forward: Array<DoubleArray>,
        /** backward[k][j]: min cost from lane j at site k to the end (excluding node cost of j). */
        val backward: Array<DoubleArray>,
        val parent: Array<IntArray>,
    ) {
        fun bestPath(): IntArray {
            val last = sites.size - 1
            val path = IntArray(sites.size)
            path[last] = argMin(forward[last])
            for (k in last downTo 1) path[k - 1] = parent[k][path[k]]
            return path
        }

        fun optimalSets(tolerance: Double): List<BooleanArray> {
            val best = forward.last().min()
            return sites.indices.map { k ->
                BooleanArray(sites[k].laneCount) { j ->
                    val through = forward[k][j] + backward[k][j]
                    through < INF && through <= best + tolerance
                }
            }
        }
    }

    private fun solve(
        sites: List<LaneSite>,
        allowed: List<BooleanArray>,
        options: Options,
        includeTieBreakers: Boolean,
    ): Solution {
        val n = sites.size
        val forward = Array(n) { DoubleArray(sites[it].laneCount) { INF } }
        val parent = Array(n) { IntArray(sites[it].laneCount) { -1 } }

        fun nodeCost(k: Int, j: Int): Double {
            if (!allowed[k][j]) return INF
            if (!includeTieBreakers) return 0.0
            val s = sites[k]
            return if (options.keepRight && (s.roadKind == RoadKind.MOTORWAY || s.roadKind == RoadKind.TRUNK)) {
                config.keepRightCost * max(0, s.lateral(j))
            } else {
                val idx = allowed[k].indices.filter { allowed[k][it] }
                val centre = (idx.first() + idx.last()) / 2.0
                config.centeringCost * abs(j - centre)
            }
        }

        fun edgeCost(fromLat: Int, toLat: Int, d0: Double, d1: Double, kind: RoadKind): Double {
            val changes = abs(toLat - fromLat)
            if (changes == 0) return 0.0
            val gap = d1 - d0
            val usable = gap - blockedLength(options.changeSpans, fromLat, toLat, d0, d1)
            val per = metersPerChange(kind)
            val doable = max(0.0, usable / per)
            val shortBy = max(0.0, changes - doable)
            val illegal = if (usable < 1.0 && gap >= 1.0) changes * config.solidLinePenalty else 0.0
            return config.changeCost * changes + config.shortGapPenalty * shortBy + illegal
        }

        // Layer 0: from the car.
        val s0 = sites[0]
        for (j in 0 until s0.laneCount) {
            val nc = nodeCost(0, j)
            if (nc >= INF) continue
            val start = options.currentLateral?.let { edgeCost(it, s0.lateral(j), options.carDistance, s0.distanceAlong, s0.roadKind) } ?: 0.0
            forward[0][j] = nc + start
        }
        // Layers 1..n-1.
        for (k in 1 until n) {
            val prev = sites[k - 1]
            val cur = sites[k]
            for (j in 0 until cur.laneCount) {
                val nc = nodeCost(k, j)
                if (nc >= INF) continue
                var best = INF
                var arg = -1
                for (i in 0 until prev.laneCount) {
                    val f = forward[k - 1][i]
                    if (f >= INF) continue
                    val tr = transition(prev, i, allowed[k - 1], cur, j, options.freeTurnEntry)
                    val c = tr.changes
                    val e = edgeCost(tr.entry, cur.lateral(j), prev.distanceAlong, cur.distanceAlong, cur.roadKind)
                    val v = f + tr.entryCost + e + if (c == 0) 0.0 else TIE_EPS * i
                    if (v < best - 1e-9) { best = v; arg = i }
                }
                if (arg >= 0) {
                    forward[k][j] = best + nc
                    parent[k][j] = arg
                }
            }
        }
        // Backward pass for optimal-set computation.
        val backward = Array(n) { DoubleArray(sites[it].laneCount) { INF } }
        for (j in 0 until sites[n - 1].laneCount) backward[n - 1][j] = if (allowed[n - 1][j]) 0.0 else INF
        for (k in n - 2 downTo 0) {
            val cur = sites[k]
            val next = sites[k + 1]
            for (i in 0 until cur.laneCount) {
                if (!allowed[k][i]) continue
                var best = INF
                for (j in 0 until next.laneCount) {
                    val b = backward[k + 1][j]
                    if (b >= INF) continue
                    val nc = nodeCost(k + 1, j)
                    if (nc >= INF) continue
                    val tr = transition(cur, i, allowed[k], next, j, options.freeTurnEntry)
                    val e = edgeCost(tr.entry, next.lateral(j), cur.distanceAlong, next.distanceAlong, next.roadKind)
                    best = min(best, b + nc + tr.entryCost + e)
                }
                backward[k][i] = best
            }
        }
        // If every lane of the last layer was unreachable (inconsistent data), fall back to "stay put".
        if (forward.last().all { it >= INF }) {
            for (k in 0 until n) for (j in 0 until sites[k].laneCount) {
                if (forward[k][j] >= INF) forward[k][j] = BIG
                if (backward[k][j] >= INF) backward[k][j] = 0.0
                if (k > 0 && parent[k][j] < 0) parent[k][j] = min(j, sites[k - 1].laneCount - 1)
            }
        }
        return Solution(sites, forward, backward, parent)
    }

    private fun argMin(a: DoubleArray): Int {
        var bi = 0
        for (i in a.indices) if (a[i] < a[bi]) bi = i
        return bi
    }

    companion object {
        private const val INF = Double.POSITIVE_INFINITY
        private const val BIG = 1e6
        private const val TIE_EPS = 1e-6
    }
}
