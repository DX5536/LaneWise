package de.lanewise.lane

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** One concrete instruction the driver should act on. */
data class LaneChangeInstruction(
    /** Where to start changing (m along route). */
    val startAt: Double,
    /** Site that requires the change. */
    val site: LaneSite,
    /** Signed number of lanes, + = to the left. */
    val lanes: Int,
    /** Target lane index at [site], left → right. */
    val targetLane: Int,
    /** Last point (m along route) where the change can still be made: before the site or a solid line. */
    val deadline: Double = site.distanceAlong,
)

/** Everything the UI needs about lanes for the current car position. */
data class LaneState(
    val nextAdvice: LaneAdvice?,
    val plan: List<LaneAdvice>,
    val pendingChange: LaneChangeInstruction?,
    /** True if no lane change is needed up to the end of the planning horizon. */
    val stayInLane: Boolean,
    val assumedLateral: Int?,
    /** Route distance where [assumedLateral] was established (last junction passed / lane tapped); null = route start. */
    val assumedSince: Double? = null,
    /**
     * Keep right (§ 2 StVO) on motorways / expressways: from this route distance the car moves from
     * [keepRightFromLateral] back to the rightmost through lane ([assumedLateral] is already that lane).
     */
    val keepRightFrom: Double? = null,
    val keepRightFromLateral: Int? = null,
) {
    companion object { val EMPTY = LaneState(null, emptyList(), null, true, null) }
}

/**
 * Runtime wrapper around [LanePlanner]: re-plans as the car moves, tracks the lane the driver is
 * assumed to be in, and turns plan changes into timed instructions.
 *
 * Lane-level positioning (AMAP's "LaneMatchInfo"/LNDS) needs HD maps and RTK-grade GNSS which a phone
 * does not have, so LaneWise assumes the driver follows advice: after passing a site, the assumed lane
 * is the recommended one. Users can override this by tapping a lane in the lane bar.
 */
class LaneGuidance(private val planner: LanePlanner = LanePlanner()) {

    private var assumedLateral: Int? = null
    private var assumedSince: Double? = null
    private var overridePending = false
    private var lastPassedSite: LaneSite? = null
    private var lastPlan: List<LaneAdvice> = emptyList()
    private var keepRightFrom: Double? = null
    private var keepRightFromLateral: Int? = null

    fun reset() {
        keepRightFrom = null
        keepRightFromLateral = null
        assumedLateral = null
        assumedSince = null
        overridePending = false
        lastPassedSite = null
        lastPlan = emptyList()
    }

    /** Driver tells us which lane (index at [site]) they are in. */
    fun overrideCurrentLane(site: LaneSite, laneIndex: Int) {
        assumedLateral = site.lateral(laneIndex.coerceIn(0, site.laneCount - 1))
        overridePending = true
    }

    fun update(
        sites: List<LaneSite>,
        carDistance: Double,
        avoidLaneChanges: Boolean,
        keepRight: Boolean,
        changeSpans: List<ChangeSpan> = emptyList(),
        laneCountAt: ((Double) -> Int?)? = null,
        /** Road kind of the route at a distance (keep right applies to the stretch, not to the junction's ramp step). */
        roadKindAt: ((Double) -> RoadKind?)? = null,
    ): LaneState {
        // Advance the "assumed lane" over sites we have passed.
        val passed = sites.lastOrNull { it.distanceAlong < carDistance }
        if (passed != null && passed != lastPassedSite) {
            // Use the advice we actually gave (multi-site context), not a fresh single-site plan.
            val advice = lastPlan.firstOrNull { it.site == passed } ?: planner.plan(
                listOf(passed),
                LanePlanner.Options(avoidLaneChanges, keepRight, assumedLateral, passed.distanceAlong - 1),
            ).firstOrNull()
            val nextSite = sites.firstOrNull { it.distanceAlong > passed.distanceAlong }
            assumedLateral = when {
                advice == null -> null
                nextSite != null -> lastPlan.firstOrNull { it.site == nextSite }?.entryLateral
                    ?: planner.entryLateral(passed, advice.recommended, advice.allowed, nextSite)
                passed.isManeuver -> null
                else -> passed.lateral(advice.recommended)
            }
            lastPassedSite = passed
            assumedSince = passed.distanceAlong
            keepRightFrom = null; keepRightFromLateral = null
            // Rechtsfahrgebot: on a motorway / expressway the driver returns to the rightmost through lane after the
            // junction, unless the next manoeuvre is too close for that to make sense (it is then planned from the
            // lane the junction left the car in). In town (free lane choice, § 7 (3) StVO) the car stays where it is.
            val lat = assumedLateral
            // The stretch is judged by the route's road kind (every 100 m), not by the junction's own step (often a
            // ramp): keep right once the route is on the motorway / expressway — after a merge, not on the ramp.
            val end = nextSite?.distanceAlong ?: (passed.distanceAlong + 3000.0)
            val kinds = generateSequence(passed.distanceAlong + 50.0) { it + 100.0 }.takeWhile { it < end - 100.0 }
                .map { it to (roadKindAt?.invoke(it) ?: passed.roadKind) }.toList()
            val fastFrom = kinds.firstOrNull { keepRightRoad(it.second) }?.first
            val fastShare = if (kinds.isEmpty()) 0.0 else kinds.count { keepRightRoad(it.second) }.toDouble() / kinds.size
            if (keepRight && lat != null && lat > 0 && fastFrom != null && fastShare >= 0.6) {
                val motorway = kinds.any { it.second == RoadKind.MOTORWAY }
                val start = fastFrom + if (motorway) 300.0 else 200.0
                val back = KEEP_RIGHT_PER_LANE * lat
                val nextKind = nextSite?.let { roadKindAt?.invoke(it.distanceAlong - 100.0) ?: it.roadKind } ?: passed.roadKind
                if (end - start > back + leadDistance(nextKind, max(1, lat)) + 200.0) {
                    keepRightFrom = start
                    keepRightFromLateral = lat
                    assumedLateral = 0
                    assumedSince = start + back
                }
            }
        }
        if (overridePending) { assumedSince = carDistance; overridePending = false; keepRightFrom = null; keepRightFromLateral = null }

        val plan = planner.plan(
            sites,
            LanePlanner.Options(avoidLaneChanges, keepRight, assumedLateral, carDistance, changeSpans = changeSpans, laneCountAt = laneCountAt,
                assumedSince = assumedSince),
        )
        lastPlan = plan
        if (plan.isEmpty()) return LaneState(null, emptyList(), null, true, assumedLateral, assumedSince, keepRightFrom, keepRightFromLateral)

        // The first change is timed from where the assumed lane was established, not from the car: otherwise its start
        // slides along with the car inside the lead distance.
        val change = firstChange(plan, min(carDistance, assumedSince ?: carDistance), changeSpans)
        return LaneState(
            nextAdvice = plan.first(),
            plan = plan,
            pendingChange = change,
            stayInLane = change == null,
            assumedLateral = assumedLateral,
            assumedSince = assumedSince,
            keepRightFrom = keepRightFrom,
            keepRightFromLateral = keepRightFromLateral,
        )
    }

    /** Roads with the keep-right rule in the lane advice: Autobahn and expressways (Kraftfahrstraße, fast roads). */
    private fun keepRightRoad(kind: RoadKind) = kind == RoadKind.MOTORWAY || kind == RoadKind.TRUNK

    companion object {
        /** Metres the strip takes per lane when it moves back to the right lane (gentle, like a real lane change). */
        const val KEEP_RIGHT_PER_LANE = 150.0
    }

    private fun firstChange(plan: List<LaneAdvice>, since: Double, spans: List<ChangeSpan>): LaneChangeInstruction? {
        var prevDist = since
        for (a in plan) {
            if (a.changeFromPrevious != 0) {
                val gap = a.site.distanceAlong - prevDist
                val lead = leadDistance(a.site.roadKind, abs(a.changeFromPrevious))
                val target = a.site.lateral(a.recommended)
                // Change where the line is dashed: the last legal stretch before the site (solid lines from OSM).
                val window = legalWindows(spans, target - a.changeFromPrevious, target, prevDist + 10.0, a.site.distanceAlong - 10.0)
                    .lastOrNull { it.endInclusive - it.start >= 20.0 }
                if (window != null) {
                    val start = max(window.start, window.endInclusive - lead)
                    return LaneChangeInstruction(start, a.site, a.changeFromPrevious, a.recommended, deadline = window.endInclusive)
                }
                // Start as late as is comfortable (fewer surprises from traffic), but never before the
                // previous site and never so late that the change can't be completed.
                val start = max(prevDist + 10.0, a.site.distanceAlong - min(lead, max(0.0, gap - 10.0)))
                return LaneChangeInstruction(start, a.site, a.changeFromPrevious, a.recommended)
            }
            prevDist = a.site.distanceAlong
        }
        return null
    }

    /** Parts of [d0]..[d1] where no solid line forbids moving from lateral [fromLat] to [toLat], in order. */
    fun legalWindows(spans: List<ChangeSpan>, fromLat: Int, toLat: Int, d0: Double, d1: Double): List<ClosedFloatingPointRange<Double>> {
        if (d1 <= d0) return emptyList()
        val blocked = spans.filter { s ->
            s.to > d0 && s.from < d1 &&
                if (toLat < fromLat) (toLat + 1..fromLat).any { !s.canMoveRight(it) } else (fromLat until toLat).any { !s.canMoveLeft(it) }
        }.sortedBy { it.from }
        val out = ArrayList<ClosedFloatingPointRange<Double>>()
        var cur = d0
        for (b in blocked) {
            if (b.from > cur) out += cur..b.from
            cur = max(cur, b.to)
        }
        if (cur < d1) out += cur..d1
        return out
    }

    /** How far before the site we advise the change (AMAP uses ~300 m on expressways). */
    fun leadDistance(kind: RoadKind, changes: Int): Double = when (kind) {
        RoadKind.MOTORWAY -> 600.0 + 250.0 * (changes - 1)
        RoadKind.TRUNK -> 400.0 + 200.0 * (changes - 1)
        RoadKind.RURAL -> 250.0 + 120.0 * (changes - 1)
        RoadKind.URBAN -> 150.0 + 80.0 * (changes - 1)
    }
}
