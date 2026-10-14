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
)

/** Everything the UI needs about lanes for the current car position. */
data class LaneState(
    val nextAdvice: LaneAdvice?,
    val plan: List<LaneAdvice>,
    val pendingChange: LaneChangeInstruction?,
    /** True if no lane change is needed up to the end of the planning horizon. */
    val stayInLane: Boolean,
    val assumedLateral: Int?,
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
    private var lastPassedSite: LaneSite? = null
    private var lastPlan: List<LaneAdvice> = emptyList()

    fun reset() {
        assumedLateral = null
        lastPassedSite = null
        lastPlan = emptyList()
    }

    /** Driver tells us which lane (index at [site]) they are in. */
    fun overrideCurrentLane(site: LaneSite, laneIndex: Int) {
        assumedLateral = site.lateral(laneIndex.coerceIn(0, site.laneCount - 1))
    }

    fun update(
        sites: List<LaneSite>,
        carDistance: Double,
        avoidLaneChanges: Boolean,
        keepRight: Boolean,
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
        }

        val plan = planner.plan(
            sites,
            LanePlanner.Options(avoidLaneChanges, keepRight, assumedLateral, carDistance),
        )
        lastPlan = plan
        if (plan.isEmpty()) return LaneState(null, emptyList(), null, true, assumedLateral)

        val change = firstChange(plan, carDistance)
        return LaneState(
            nextAdvice = plan.first(),
            plan = plan,
            pendingChange = change,
            stayInLane = change == null,
            assumedLateral = assumedLateral,
        )
    }

    private fun firstChange(plan: List<LaneAdvice>, carDistance: Double): LaneChangeInstruction? {
        var prevDist = carDistance
        for (a in plan) {
            if (a.changeFromPrevious != 0) {
                val gap = a.site.distanceAlong - prevDist
                val lead = leadDistance(a.site.roadKind, abs(a.changeFromPrevious))
                // Start as late as is comfortable (fewer surprises from traffic), but never before the
                // previous site and never so late that the change can't be completed.
                val start = max(prevDist + 10.0, a.site.distanceAlong - min(lead, max(0.0, gap - 10.0)))
                return LaneChangeInstruction(start, a.site, a.changeFromPrevious, a.recommended)
            }
            prevDist = a.site.distanceAlong
        }
        return null
    }

    /** How far before the site we advise the change (AMAP uses ~300 m on expressways). */
    fun leadDistance(kind: RoadKind, changes: Int): Double = when (kind) {
        RoadKind.MOTORWAY -> 600.0 + 250.0 * (changes - 1)
        RoadKind.TRUNK -> 400.0 + 200.0 * (changes - 1)
        RoadKind.RURAL -> 250.0 + 120.0 * (changes - 1)
        RoadKind.URBAN -> 150.0 + 80.0 * (changes - 1)
    }
}
