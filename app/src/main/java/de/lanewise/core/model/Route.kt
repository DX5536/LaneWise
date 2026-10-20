package de.lanewise.core.model

import de.lanewise.core.geo.LatLon
import de.lanewise.core.geo.Polyline
import de.lanewise.lane.LaneSite
import de.lanewise.lane.RoadKind

enum class ManeuverType {
    DEPART, ARRIVE, TURN, CONTINUE, NEW_NAME, MERGE, ON_RAMP, OFF_RAMP, FORK, END_OF_ROAD,
    ROUNDABOUT, EXIT_ROUNDABOUT, NOTIFICATION,
    /** A stop on the way reached (end of a route leg that is not the last one). */
    WAYPOINT;

    companion object {
        fun fromOsrm(type: String): ManeuverType = when (type) {
            "depart" -> DEPART
            "arrive" -> ARRIVE
            "turn" -> TURN
            "continue" -> CONTINUE
            "new name" -> NEW_NAME
            "merge" -> MERGE
            "on ramp" -> ON_RAMP
            "off ramp" -> OFF_RAMP
            "fork" -> FORK
            "end of road" -> END_OF_ROAD
            "roundabout", "rotary", "roundabout turn" -> ROUNDABOUT
            "exit roundabout", "exit rotary" -> EXIT_ROUNDABOUT
            else -> NOTIFICATION
        }
    }
}

/** Direction modifier; mirrors OSRM's vocabulary. */
enum class Modifier {
    UTURN, SHARP_RIGHT, RIGHT, SLIGHT_RIGHT, STRAIGHT, SLIGHT_LEFT, LEFT, SHARP_LEFT;

    companion object {
        fun fromOsrm(m: String?): Modifier = when (m) {
            "uturn" -> UTURN
            "sharp right" -> SHARP_RIGHT
            "right" -> RIGHT
            "slight right" -> SLIGHT_RIGHT
            "slight left" -> SLIGHT_LEFT
            "left" -> LEFT
            "sharp left" -> SHARP_LEFT
            else -> STRAIGHT
        }
    }
}

data class Maneuver(
    val type: ManeuverType,
    val modifier: Modifier,
    val location: LatLon,
    val bearingBefore: Double,
    val bearingAfter: Double,
    /** Roundabout exit number, if any. */
    val exit: Int? = null,
)

data class RouteStep(
    /** Distance from route start to this step's manoeuvre (m). */
    val distanceAlong: Double,
    val distance: Double,
    val duration: Double,
    val maneuver: Maneuver,
    /** Name of the road *after* the manoeuvre. */
    val name: String,
    val ref: String?,
    /** Signposted destinations (e.g. "Hannover-Messe"), for motorway exits. */
    val destinations: String?,
    val exitNumber: String?,
)

/** Speed limit valid on [from]..[to] metres along the route; null kmh = no limit (e.g. Autobahn). */
data class SpeedLimitSpan(val from: Double, val to: Double, val kmh: Int?, val conditional: Boolean = false)

/** Road classification span along the route. */
data class RoadSpan(val from: Double, val to: Double, val kind: RoadKind, val twoWay: Boolean)

data class Route(
    val provider: String,
    val geometry: Polyline,
    val distance: Double,
    val duration: Double,
    val steps: List<RouteStep>,
    val laneSites: List<LaneSite>,
    val speedLimits: List<SpeedLimitSpan> = emptyList(),
    val roadSpans: List<RoadSpan> = emptyList(),
    /**
     * Route-choice score (s) from the scorer: real delays plus penalties (30 min per closure, roadworks, forced lane
     * changes). Only for comparing routes - never shown as a time ([trafficDelay] is).
     */
    val trafficPenalty: Double = 0.0,
    /** Expected extra driving time (s) from jams / accidents on the route (their reported delay); part of the ETA. */
    val trafficDelay: Double = 0.0,
    val incidents: List<TrafficIncident> = emptyList(),
    /** Traffic lights and road signs on the route, sorted by distance. */
    val features: List<RoadFeature> = emptyList(),
    /** On-street bike lanes on the right-hand side in driving direction. */
    val bikeLanes: List<BikeLaneSpan> = emptyList(),
    /** Number of car lanes in driving direction, from OSM `lanes(:forward|:backward)` / `turn:lanes`. */
    val laneCounts: List<LaneCountSpan> = emptyList(),
    /** Where solid lines forbid lane changes: OSM `change:lanes`, plus TomTom lane separators where OSM has none. */
    val laneChanges: List<de.lanewise.lane.ChangeSpan> = emptyList(),
    /** TomTom lane separators (solid / dashed) projected onto this route; merged into [laneChanges] behind OSM. */
    val tomtomLaneLines: List<de.lanewise.lane.ChangeSpan> = emptyList(),
    /** [duration] includes live traffic (TomTom), so jams must not be added again by the scorer. */
    val trafficEta: Boolean = false,
    /** Carriageways running beside the route in the same direction (OSM), drawn as context lanes. */
    val sideRoads: List<SideRoad> = emptyList(),
    /**
     * OSM node ids the route drives through, in order (OSRM `annotations=nodes`; empty for other providers). Lets the
     * corridor take lanes / limits only from the ways the route is on — not from a bridge above or a road below
     * (owner: Berliner Allee under the Raschplatzhochstraße).
     */
    val osmNodes: List<Long> = emptyList(),
    /**
     * Route distances of junctions whose lanes are known to be unknown: a hand-checked patch (`lane_overrides.json`,
     * `lanes_unknown`) says OSM is wrong there, or a patch's lanes cannot make the route's turn. No lane advice there.
     */
    val unknownLanesAt: List<Double> = emptyList(),
) {
    fun laneChangeAt(d: Double): de.lanewise.lane.ChangeSpan? = laneChanges.firstOrNull { d >= it.from && d < it.to }
    fun laneCountAt(d: Double): Int? = laneCounts.firstOrNull { d >= it.from && d < it.to }?.count

    /** With OSM lane counts; lane sites are re-checked for turn lanes that run the whole way ([markFullLengthLanes]). */
    fun withLaneCounts(counts: List<LaneCountSpan>): Route = copy(laneCounts = fillShortLaneDips(counts, laneSites.map { it.distanceAlong })).let { r ->
        r.copy(laneSites = de.lanewise.lane.markFullLengthLanes(
            de.lanewise.lane.trimToRoadLanes(r.laneSites, r::laneCountAt) { d ->
                r.roadSpanAt(d)?.kind.let { it == de.lanewise.lane.RoadKind.MOTORWAY || it == de.lanewise.lane.RoadKind.TRUNK }
            },
            r::laneCountAt).let { sites -> de.lanewise.lane.markEndedLanes(sites, r.laneCounts.map { it.from to it.endedRight }, r::laneCountAt) })
    }
    /**
     * Adds lane sites from OSM `turn:lanes` ([at]: route distance and value) where OSRM gave none within 40 m: the
     * turn there is the route's step (or the bend of the route line), so the planner, the arrows and the stop line
     * work at junctions OSRM drops lanes at (Aegidientorplatz, left into Hildesheimer Straße).
     */
    fun withOsmTurnLanes(at: List<Pair<Double, String>>): Route {
        val added = at.filter { (d, _) -> laneSites.none { kotlin.math.abs(it.distanceAlong - d) < 40 } &&
            unknownLanesAt.none { kotlin.math.abs(it - d) < 40 } }.mapNotNull { (d, v) ->
            val masks = de.lanewise.lane.LaneDir.parseTurnLanes(v)
            if (masks.size < 2) return@mapNotNull null
            val turn = turnAt(d)
            val kind = roadSpanAt(d - 5)?.kind ?: de.lanewise.lane.RoadKind.URBAN
            de.lanewise.lane.LaneSite(d, masks, null, turn, turn != de.lanewise.lane.LaneDir.STRAIGHT, kind)
        }
        if (added.isEmpty()) return this
        // The added sites take part in the junction clean-up like OSRM's own (it ran when the route was built): a
        // `left | through` node 43 m before the left turn's node is one junction (audit, Nenndorfer Landstraße →
        // Gehrdener Damm: straight lane at the first node, then "move left" in the last 43 m).
        return copy(laneSites = de.lanewise.lane.dropRoundaboutInterior(
            de.lanewise.lane.mergeDoubleNodeJunctions((laneSites + added).sortedBy { it.distanceAlong }), roundabouts()))
    }

    /**
     * Lane access from OSM ([at]: route distance of a way's end, cars allowed per lane): set on the nearest lane site
     * within 40 m that has as many lanes, so a bus lane is never recommended (Hollerithallee).
     */
    fun withLaneAccess(at: List<Pair<Double, List<Boolean>>>): Route {
        if (at.isEmpty()) return this
        val sites = laneSites.toMutableList()
        for ((d, car) in at) {
            val k = sites.indices.filter { kotlin.math.abs(sites[it].distanceAlong - d) < 40 && sites[it].laneCount == car.size }
                .minByOrNull { kotlin.math.abs(sites[it].distanceAlong - d) } ?: continue
            sites[k] = sites[k].copy(carLanes = car)
        }
        return copy(laneSites = sites)
    }

    /** Route distances from each roundabout entry step to its exit step. */
    fun roundabouts(): List<ClosedFloatingPointRange<Double>> = steps.withIndex()
        .filter { it.value.maneuver.type == ManeuverType.ROUNDABOUT }
        .mapNotNull { (i, st) ->
            steps.drop(i + 1).firstOrNull { it.maneuver.type == ManeuverType.EXIT_ROUNDABOUT }?.let { st.distanceAlong..it.distanceAlong }
        }

    /** Direction the route takes at [d]: its step there, else the bend of the route line. */
    private fun turnAt(d: Double): Int {
        val step = steps.firstOrNull { kotlin.math.abs(it.distanceAlong - d) < 25 &&
            it.maneuver.type != ManeuverType.DEPART && it.maneuver.type != ManeuverType.ARRIVE && it.maneuver.type != ManeuverType.WAYPOINT }
        return step?.let { de.lanewise.routing.laneDirOf(it.maneuver.modifier) }
            ?: de.lanewise.lane.LaneDir.fromTurnAngle(de.lanewise.core.geo.Geo.bearingDelta(
                geometry.bearingAt((d - 20).coerceAtLeast(0.0)), geometry.bearingAt((d + 20).coerceAtMost(geometry.length))))
    }

    /**
     * Applies hand-checked lane patches (`lane_overrides.json`, highest precedence): every lane site within
     * [OVERRIDE_RADIUS] of a patched junction (OSRM's or one added from OSM) is replaced by the patch's lanes, keeping
     * the route's turn there. Provider "valid" flags are dropped (they belonged to the wrong lanes). A patch that says
     * `lanes_unknown`, or whose lanes cannot make the route's turn (a patch for another direction, or out of date),
     * leaves no site and marks the junction in [unknownLanesAt]. Call before [withOsmTurnLanes] and [withLaneCounts].
     */
    fun withLaneOverrides(at: List<de.lanewise.osm.LaneOverrides.At>): Route {
        if (at.isEmpty()) return this
        val sites = laneSites.toMutableList()
        val unknown = unknownLanesAt.toMutableList()
        for (o in at) {
            val near = sites.filter { kotlin.math.abs(it.distanceAlong - o.distanceAlong) < OVERRIDE_RADIUS }
            val nearest = near.minByOrNull { kotlin.math.abs(it.distanceAlong - o.distanceAlong) }
            sites.removeAll(near)
            val d = nearest?.distanceAlong ?: o.distanceAlong
            val turn = nearest?.turn ?: turnAt(d)
            val masks = o.lanes?.let { de.lanewise.lane.LaneDir.parseTurnLanes(it) }
            if (masks == null || masks.none { de.lanewise.lane.LaneDir.permits(it, turn) }) {
                if (unknown.none { kotlin.math.abs(it - d) < 1 }) unknown += d
                continue
            }
            sites += de.lanewise.lane.LaneSite(d, masks, null, turn, nearest?.isManeuver ?: (turn != de.lanewise.lane.LaneDir.STRAIGHT),
                nearest?.roadKind ?: roadSpanAt(d - 5)?.kind ?: de.lanewise.lane.RoadKind.URBAN, pocketLength = o.pocketLength)
        }
        return copy(laneSites = sites.sortedBy { it.distanceAlong }, unknownLanesAt = unknown.sorted())
    }

    /**
     * Sets explicit lane connectivity ([de.lanewise.lane.LaneConnectivity]) on the lane site nearest to each junction
     * in [at] (within [OVERRIDE_RADIUS]). A hand-checked patch wins over an OSM relation at the same junction. A value
     * that contradicts the site's lanes or arrows ([de.lanewise.lane.LaneConnectivity.contradiction]) removes the
     * site and marks the junction in [unknownLanesAt] - no lane advice there, never a guess between the two sources.
     * Junctions without a lane site are left alone. Call after [withLaneCounts] (the lanes are final then).
     */
    fun withConnectivity(at: List<de.lanewise.lane.ConnectivityAt>): Route {
        if (at.isEmpty()) return this
        val sites = laneSites.toMutableList()
        val unknown = unknownLanesAt.toMutableList()
        val bySite = at.groupBy { c ->
            sites.indices.filter { kotlin.math.abs(sites[it].distanceAlong - c.distanceAlong) < OVERRIDE_RADIUS }
                .minByOrNull { kotlin.math.abs(sites[it].distanceAlong - c.distanceAlong) }
        }
        val drop = HashSet<Int>()
        for ((k, list) in bySite) {
            if (k == null) continue
            val s = sites[k]
            // The patch if there is one, else the OSM relation(s); two values of the same rank that differ are not used.
            val values = list.filter { it.override }.ifEmpty { list }.map { it.value.trim() }.distinct()
            val parsed = values.singleOrNull()?.let { de.lanewise.lane.LaneConnectivity.parse(it) }
            val toLanes = listOfNotNull(laneCountAt(s.distanceAlong + 40), laneCountAt(s.distanceAlong + 80)).maxOrNull()
            val bad = parsed == null || de.lanewise.lane.LaneConnectivity.contradiction(s, parsed, toLanes) != null
            if (bad) {
                drop += k
                if (unknown.none { kotlin.math.abs(it - s.distanceAlong) < 1 }) unknown += s.distanceAlong
                continue
            }
            sites[k] = s.copy(connectivity = de.lanewise.lane.LaneConnectivity.forLanes(parsed!!, s.laneCount))
        }
        val kept = sites.filterIndexed { i, _ -> i !in drop }
        return copy(laneSites = de.lanewise.lane.markFullLengthLanes(kept, this::laneCountAt), unknownLanesAt = unknown.sorted())
    }

    fun bikeLaneAt(d: Double): BikeLaneSpan? = bikeLanes.firstOrNull { d >= it.from && d < it.to }
    fun speedLimitAt(d: Double): SpeedLimitSpan? = speedLimits.firstOrNull { d >= it.from && d < it.to }
    fun roadSpanAt(d: Double): RoadSpan? = roadSpans.firstOrNull { d >= it.from && d < it.to }
    /** For ranking routes (includes the scorer's penalties). */
    val effectiveDuration get() = duration + trafficPenalty
    /** For the time shown to the driver (remaining time, arrival): the provider's duration plus real delays. */
    val etaDuration get() = duration + trafficDelay

    companion object {
        /** Lane sites this close to a patched junction (m) are replaced by the patch (one junction, up to two nodes). */
        const val OVERRIDE_RADIUS = 30.0
    }
}

enum class IncidentKind { CLOSURE, ROADWORKS, JAM, ACCIDENT, HAZARD, WEATHER, OTHER }

data class TrafficIncident(
    val id: String,
    val source: String,
    val kind: IncidentKind,
    val title: String,
    val description: String,
    val location: LatLon,
    val geometry: List<LatLon> = emptyList(),
    /** Additional delay in seconds if known. */
    val delaySeconds: Int? = null,
    /** True if the road is fully blocked. */
    val blocked: Boolean = false,
    /**
     * Lanes in the incident's driving direction, left → right, true = open (Autobahn GmbH `impact.symbols`); null if
     * unknown or nothing is closed. Hard shoulders are not included.
     */
    val laneOpen: List<Boolean>? = null,
    /** False for announced roadworks that have not started yet (Autobahn GmbH `future`). */
    val active: Boolean = true,
    /**
     * Lanes are restricted here, but which ones is not known (e.g. DATEX II `lane2` - the numbering direction is not
     * reliable - or only `numberOfLanesRestricted`): no lane advice along it, never a guess.
     */
    val laneRestrictionUnknown: Boolean = false,
)

/**
 * A carriageway beside the route in the same direction (separate OSM way): e.g. the two-lane A 37 main carriageway
 * 11 m beside the one-lane branch the route takes towards the A 7 — without it the map showed a lone lane.
 */
data class SideRoad(val geometry: List<LatLon>, val tags: Map<String, String>)

/** Kinds of Autobahn GmbH points of interest shown as optional map icons. */
enum class AutobahnPoiKind { WEBCAM, PARKING, CHARGING }

data class AutobahnPoi(val id: String, val kind: AutobahnPoiKind, val title: String, val subtitle: String, val position: LatLon)

/**
 * Point features from OpenStreetMap shown on the map and in the HUD.
 * Speed cameras are intentionally absent: using camera warnings while driving is illegal in Germany (§ 23 Abs. 1c StVO).
 */
enum class FeatureKind { TRAFFIC_SIGNALS, STOP, GIVE_WAY, CROSSING, RAILWAY_CROSSING }

data class RoadFeature(
    val kind: FeatureKind,
    val position: LatLon,
    val distanceAlong: Double,
    /** Traffic light at a pedestrian/bike crossing (`crossing=traffic_signals`) rather than a junction light. */
    val pedestrian: Boolean = false,
    /** Push-button light (`traffic_signals=on_demand`). */
    val onDemand: Boolean = false,
    /** Green-arrow sign for turning right on red (Grünpfeil, Zeichen 720, OSM `red_turn:right=yes`). */
    val greenArrow: Boolean = false,
    /** A real zebra crossing (Fußgängerüberweg, Zeichen 293) — the only crossing that gets stripes on the road. */
    val zebra: Boolean = false,
) {
    /** Compact flag encoding for the offline graph (kind ordinal in the low nibble). */
    fun encode(): Int = kind.ordinal or (if (pedestrian) 16 else 0) or (if (onDemand) 32 else 0) or (if (greenArrow) 64 else 0) or
        (if (zebra) 128 else 0)

    companion object {
        fun decode(code: Int, position: LatLon, distanceAlong: Double) = RoadFeature(
            FeatureKind.entries[code and 15], position, distanceAlong,
            pedestrian = code and 16 != 0, onDemand = code and 32 != 0, greenArrow = code and 64 != 0, zebra = code and 128 != 0,
        )
    }
}

/**
 * Traffic lights of one junction as seen by the driver: how many signal positions OSM has there, of which kind,
 * and whether a separate turn-arrow signal is likely (inferred from exclusive turn lanes — OSM has no tag for it).
 */
data class SignalCluster(
    val distance: Double,
    val vehicleLights: Int,
    val pedestrianLights: Int,
    val greenArrow: Boolean,
    val onDemand: Boolean,
    /** LaneDir bits of exclusive-turn lanes at this junction that probably have their own arrow signal (inferred). */
    val inferredArrowSignals: Int,
    /** Where the first signal of the junction stands (for the map pop-up). */
    val position: LatLon? = null,
)

/**
 * Painted bike lane next to the car lanes on the right in driving direction.
 * [exclusive] = Radfahrstreifen (solid line, Zeichen 237), false = Schutzstreifen / advisory or shared lane.
 */
/**
 * [count] lanes in driving direction (OSM) from [from] to [to]. [endedRight]: of the lanes the road had just before
 * [from], how many ended on the right edge there (OSM `merge_to_left` on the way before; 0 = none, or side not known).
 */
data class LaneCountSpan(val from: Double, val to: Double, val count: Int, val endedRight: Int = 0)

/**
 * A short stretch with fewer lanes than on both sides of it, and no junction inside, is taken as the smaller of its
 * neighbours: such dips are almost always mapping artefacts — a bridge or underpass split off as its own way and
 * tagged with fewer lanes (owner: Berliner Allee northbound under the railway bridge, `lanes=2` for 55 m between 3 and
 * 3, "should have all 4 lanes") — and drew a choke in the road. [siteDistances]: junctions with lane data.
 */
fun fillShortLaneDips(spans: List<LaneCountSpan>, siteDistances: List<Double>, maxLen: Double = 120.0): List<LaneCountSpan> {
    if (spans.size < 3) return spans
    val out = spans.toMutableList()
    for (i in 1 until spans.size - 1) {
        val prev = out[i - 1]; val cur = spans[i]; val next = spans[i + 1]
        if (cur.from - prev.to > 5 || next.from - cur.to > 5) continue   // not one continuous road
        if (cur.to - cur.from > maxLen) continue
        val around = minOf(prev.count, next.count)
        if (cur.count >= around) continue
        if (siteDistances.any { it > cur.from + 10 && it < cur.to - 10 }) continue
        out[i] = cur.copy(count = around, endedRight = cur.endedRight.coerceAtMost((prev.count - around).coerceAtLeast(0)))
    }
    return out
}

data class BikeLaneSpan(val from: Double, val to: Double, val exclusive: Boolean)
