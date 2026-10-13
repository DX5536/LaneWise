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
 *    two sites is too short to make those changes safely.
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
    )

    data class Options(
        val avoidLaneChanges: Boolean = true,
        val keepRight: Boolean = true,
        /** Assumed lateral lane coordinate of the car (see [LaneSite.lateral]), null if unknown. */
        val currentLateral: Int? = null,
        /** Car position along the route (m). */
        val carDistance: Double = 0.0,
    )

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
            val prevLat = if (k == 0) options.currentLateral
            else entryLateral(horizon[k - 1], path[k - 1], allowed[k - 1], site)
            val change = if (prevLat == null) 0 else site.lateral(path[k]) - prevLat
            LaneAdvice(
                site = site,
                recommended = path[k],
                allowed = allowed[k],
                optimal = optimalSets[k],
                changeFromPrevious = change,
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

        fun edgeCost(changes: Int, gap: Double, kind: RoadKind): Double {
            if (changes == 0) return 0.0
            val per = metersPerChange(kind)
            val doable = max(0.0, gap / per)
            val shortBy = max(0.0, changes - doable)
            return config.changeCost * changes + config.shortGapPenalty * shortBy
        }

        // Layer 0: from the car.
        val s0 = sites[0]
        val gap0 = s0.distanceAlong - options.carDistance
        for (j in 0 until s0.laneCount) {
            val nc = nodeCost(0, j)
            if (nc >= INF) continue
            val start = options.currentLateral?.let { edgeCost(abs(s0.lateral(j) - it), gap0, s0.roadKind) } ?: 0.0
            forward[0][j] = nc + start
        }
        // Layers 1..n-1.
        for (k in 1 until n) {
            val prev = sites[k - 1]
            val cur = sites[k]
            val gap = cur.distanceAlong - prev.distanceAlong
            for (j in 0 until cur.laneCount) {
                val nc = nodeCost(k, j)
                if (nc >= INF) continue
                var best = INF
                var arg = -1
                for (i in 0 until prev.laneCount) {
                    val f = forward[k - 1][i]
                    if (f >= INF) continue
                    val c = abs(cur.lateral(j) - entryLateral(prev, i, allowed[k - 1], cur))
                    val v = f + edgeCost(c, gap, cur.roadKind) + if (c == 0) 0.0 else TIE_EPS * i
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
            val gap = next.distanceAlong - cur.distanceAlong
            for (i in 0 until cur.laneCount) {
                if (!allowed[k][i]) continue
                var best = INF
                for (j in 0 until next.laneCount) {
                    val b = backward[k + 1][j]
                    if (b >= INF) continue
                    val nc = nodeCost(k + 1, j)
                    if (nc >= INF) continue
                    val c = abs(next.lateral(j) - entryLateral(cur, i, allowed[k], next))
                    best = min(best, b + nc + edgeCost(c, gap, next.roadKind))
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
