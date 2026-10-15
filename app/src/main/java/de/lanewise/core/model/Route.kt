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
    /** Extra time (s) attributed to traffic/incidents by the scorer. */
    val trafficPenalty: Double = 0.0,
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
) {
    fun laneChangeAt(d: Double): de.lanewise.lane.ChangeSpan? = laneChanges.firstOrNull { d >= it.from && d < it.to }
    fun laneCountAt(d: Double): Int? = laneCounts.firstOrNull { d >= it.from && d < it.to }?.count

    /** With OSM lane counts; lane sites are re-checked for turn lanes that run the whole way ([markFullLengthLanes]). */
    fun withLaneCounts(counts: List<LaneCountSpan>): Route = copy(laneCounts = counts).let { r ->
        r.copy(laneSites = de.lanewise.lane.markFullLengthLanes(
            de.lanewise.lane.trimToRoadLanes(r.laneSites, r::laneCountAt) { d ->
                r.roadSpanAt(d)?.kind.let { it == de.lanewise.lane.RoadKind.MOTORWAY || it == de.lanewise.lane.RoadKind.TRUNK }
            },
            r::laneCountAt))
    }
    fun bikeLaneAt(d: Double): BikeLaneSpan? = bikeLanes.firstOrNull { d >= it.from && d < it.to }
    fun speedLimitAt(d: Double): SpeedLimitSpan? = speedLimits.firstOrNull { d >= it.from && d < it.to }
    fun roadSpanAt(d: Double): RoadSpan? = roadSpans.firstOrNull { d >= it.from && d < it.to }
    val effectiveDuration get() = duration + trafficPenalty
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
data class LaneCountSpan(val from: Double, val to: Double, val count: Int)

data class BikeLaneSpan(val from: Double, val to: Double, val exclusive: Boolean)
