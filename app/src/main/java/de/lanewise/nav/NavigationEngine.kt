package de.lanewise.nav

import de.lanewise.core.model.BikeLaneSpan
import de.lanewise.core.model.ManeuverType
import de.lanewise.core.model.FeatureKind
import de.lanewise.core.model.RoadFeature
import de.lanewise.core.model.SignalCluster
import de.lanewise.lane.LaneDir
import de.lanewise.core.model.Route
import de.lanewise.core.model.RouteStep
import de.lanewise.lane.LaneGuidance
import de.lanewise.lane.LaneState
import de.lanewise.lane.RoadKind
import de.lanewise.location.Fix
import de.lanewise.settings.AppSettings
import kotlin.math.abs
import kotlin.math.max

enum class SpeedStatus { OK, OVER, UNDER, UNKNOWN }

data class NavSnapshot(
    val route: Route,
    val fix: Fix,
    val distanceAlong: Double,
    val remainingDistance: Double,
    val remainingSeconds: Double,
    val nextStep: RouteStep?,
    val distanceToNextStep: Double,
    val stepAfter: RouteStep?,
    /** km/h; null = unknown. */
    val speedLimit: Int?,
    /** True on de-restricted Autobahn sections (Richtgeschwindigkeit 130). */
    val noSpeedLimit: Boolean,
    val speedStatus: SpeedStatus,
    val lanes: LaneState,
    val roadKind: RoadKind,
    val offRoute: Boolean,
    val arrived: Boolean,
    /** Next traffic lights / signs within 400 m with their distance from the car (one entry per junction). */
    val upcoming: List<Pair<RoadFeature, Double>> = emptyList(),
    /** Traffic lights of the next signalised junction within 400 m. */
    val signals: SignalCluster? = null,
    /** Painted bike lane next to the car lanes here (or within the next 50 m). */
    val bikeLane: BikeLaneSpan? = null,
    /** Metres to where a solid line starts (lane changes no longer allowed), if within 250 m. */
    val solidLineIn: Double? = null,
)

/** Something to say. [key] de-duplicates so each announcement is spoken once. */
/** Something to say; [parts] are the voice-pack segments (their concatenation is [text]). */
data class Announcement(val key: String, val text: String, val priority: Int = 0, val parts: List<String> = listOf(text))

/**
 * Turns GNSS fixes into navigation state. Pure Kotlin (no Android) so it is unit-testable and can be
 * driven by the simulator.
 */
class NavigationEngine(
    private val route: Route,
    private val laneGuidance: LaneGuidance = LaneGuidance(),
) {
    private var hint = 0
    private var lastAlong = 0.0
    private var offRouteCount = 0
    private var overActive = false
    private val spoken = HashSet<String>()

    /** Carry progress and already-spoken announcements over when the same route is enriched (e.g. speed limits). */
    fun rehydrate(previous: NavigationEngine?) {
        if (previous == null) return
        hint = previous.hint
        lastAlong = previous.lastAlong
        spoken += previous.spoken
    }

    /**
     * Demo scrubbed to route distance [d]: progress continues from there (also backwards), and announcements may be
     * spoken again.
     */
    fun seek(d: Double) {
        hint = route.geometry.segmentIndexAt(d)
        lastAlong = d
        offRouteCount = 0
        spoken.clear()
        laneGuidance.reset()
    }

    fun update(fix: Fix, settings: AppSettings, phrases: Phrases): Pair<NavSnapshot, List<Announcement>> {
        val line = route.geometry
        val proj = line.project(fix.position, hint, 300) ?: line.project(fix.position)!!
        val tolerance = max(35.0, fix.accuracyM + 20.0)
        val headingOk = fix.bearing?.let { b ->
            abs(de.lanewise.core.geo.Geo.bearingDelta(b.toDouble(), line.bearingAt(proj.distanceAlong))) < 100
        } ?: true
        offRouteCount = if (abs(proj.signedOffset) > tolerance || !headingOk) offRouteCount + 1 else 0
        val offRoute = offRouteCount >= 3

        // Keep progress monotonic against GNSS jitter, but allow re-anchoring on large jumps.
        val along = if (proj.distanceAlong < lastAlong && lastAlong - proj.distanceAlong < 30) lastAlong else proj.distanceAlong
        if (!offRoute) { hint = proj.segmentIndex; lastAlong = along }

        val remaining = max(0.0, route.distance - along)
        val remainingSec = route.effectiveDuration * (remaining / max(1.0, route.distance))
        val nextIdx = route.steps.indexOfFirst { it.distanceAlong > along + 5 }
        val next = route.steps.getOrNull(nextIdx)
        val after = if (nextIdx >= 0) route.steps.getOrNull(nextIdx + 1) else null
        val distNext = next?.let { it.distanceAlong - along } ?: remaining

        val span = route.speedLimitAt(along)
        val limit = span?.kmh
        val noLimit = span != null && span.kmh == null
        val kmh = fix.speedKmh
        val status = when {
            limit == null -> SpeedStatus.UNKNOWN
            settings.overSpeedWarning && (kmh > limit + settings.overSpeedOffset || (overActive && kmh > limit + settings.overSpeedOffset - 2)) -> SpeedStatus.OVER
            settings.underSpeedWarning && kmh >= settings.underSpeedMinSpeed && kmh < limit - settings.underSpeedOffset -> SpeedStatus.UNDER
            else -> SpeedStatus.OK
        }
        overActive = status == SpeedStatus.OVER

        val kind = route.roadSpanAt(along)?.kind ?: RoadKind.URBAN
        val lanes = laneGuidance.update(route.laneSites, along, settings.avoidLaneChanges, settings.keepRight, route.laneChanges,
            route::laneCountAt.takeIf { route.laneCounts.isNotEmpty() }, { d -> route.roadSpanAt(d)?.kind })
        val arrived = remaining < 25

        val ahead = route.features.filter { it.distanceAlong > along - 5 && it.distanceAlong - along < 400 }
        // One HUD entry per junction: signal positions within 50 m of each other count as one junction.
        val grouped = ArrayList<RoadFeature>()
        for (f in ahead) if (grouped.none { it.kind == f.kind && kotlin.math.abs(it.distanceAlong - f.distanceAlong) < 50 }) grouped += f
        val upcoming = grouped.take(2).map { it to (it.distanceAlong - along) }
        val signals = ahead.firstOrNull { it.kind == FeatureKind.TRAFFIC_SIGNALS }?.let { first ->
            val group = ahead.filter { it.kind == FeatureKind.TRAFFIC_SIGNALS && it.distanceAlong - first.distanceAlong in 0.0..50.0 }
            val site = route.laneSites.firstOrNull { kotlin.math.abs(it.distanceAlong - first.distanceAlong) < 60 }
            // Exclusive turn lanes at a signalised German junction usually get their own arrow signal.
            val exclusive = site?.lanes?.fold(0) { acc, m ->
                val only = m != LaneDir.NONE && (m and LaneDir.STRAIGHT) == 0
                if (only) acc or m else acc
            } ?: 0
            SignalCluster(
                distance = first.distanceAlong - along,
                vehicleLights = group.count { !it.pedestrian },
                pedestrianLights = group.count { it.pedestrian },
                greenArrow = group.any { it.greenArrow },
                onDemand = group.isNotEmpty() && group.all { it.onDemand },
                position = first.position,
                inferredArrowSignals = exclusive,
            )
        }
        val bike = route.bikeLaneAt(along) ?: route.bikeLaneAt(along + 50)

        val snap = NavSnapshot(route, fix, along, remaining, remainingSec, next, distNext, after, limit, noLimit,
            status, lanes, kind, offRoute, arrived, upcoming, signals, bike, solidLineIn = solidLineAhead(along))
        return snap to announcements(snap, phrases)
    }

    /** Distance to where lane changes stop being allowed (OSM `change:lanes`), within 250 m; null otherwise. */
    private fun solidLineAhead(along: Double): Double? {
        fun restricted(c: de.lanewise.lane.ChangeSpan) = c.perms.any { it != (de.lanewise.lane.ChangeSpan.LEFT or de.lanewise.lane.ChangeSpan.RIGHT) }
        if (route.laneChanges.any { along >= it.from && along < it.to && restricted(it) }) return null   // already inside
        return route.laneChanges.firstOrNull { it.from > along && it.from - along < 250 && restricted(it) }?.let { it.from - along }
    }

    private fun announcements(s: NavSnapshot, p: Phrases): List<Announcement> {
        val out = ArrayList<Announcement>()
        val step = s.nextStep
        if (s.arrived) {
            say(out, "arrive", p.instructionParts(route.steps.last()))
            return out
        }
        if (step != null && step.maneuver.type != ManeuverType.DEPART) {
            val fast = s.roadKind == RoadKind.MOTORWAY || s.roadKind == RoadKind.TRUNK
            val far = if (fast) 2000.0 else 500.0
            val near = if (fast) 800.0 else 200.0
            val now = max(if (fast) 300.0 else 40.0, s.fix.speedMps * 4.0)
            val id = step.distanceAlong.toInt()
            val d = s.distanceToNextStep
            when {
                d <= now -> say(out, "$id-now", p.instructionParts(step), 2)
                d <= near -> say(out, "$id-near", p.spokenParts(step, d), 1)
                d <= far && d > near + 100 -> say(out, "$id-far", p.spokenParts(step, d))
            }
        }
        // Lane advice — the "advanced lane assist" voice.
        val change = s.lanes.pendingChange
        if (change != null && s.distanceAlong >= change.startAt - 50) {
            say(out, "lane-${change.site.distanceAlong.toInt()}-${change.targetLane}", listOf(p.laneChange(change, change.site.laneCount)), 3)
        } else {
            val a = s.lanes.nextAdvice
            if (a != null && s.lanes.stayInLane && a.site.isManeuver) {
                val dist = a.site.distanceAlong - s.distanceAlong
                val restrictive = a.allowed.count { it } < a.site.laneCount
                if (restrictive && dist in 80.0..400.0) say(out, "stay-${a.site.distanceAlong.toInt()}", listOf(p.stayInLane()))
            }
        }
        return out
    }

    private fun say(out: MutableList<Announcement>, key: String, parts: List<String>, prio: Int = 0) {
        if (spoken.add(key)) out += Announcement(key, parts.joinToString(" "), prio, parts)
    }
}
