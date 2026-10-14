package de.lanewise.core.model

import de.lanewise.core.geo.LatLon
import de.lanewise.core.geo.Polyline
import de.lanewise.lane.LaneSite
import de.lanewise.lane.RoadKind

enum class ManeuverType {
    DEPART, ARRIVE, TURN, CONTINUE, NEW_NAME, MERGE, ON_RAMP, OFF_RAMP, FORK, END_OF_ROAD,
    ROUNDABOUT, EXIT_ROUNDABOUT, NOTIFICATION;

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
) {
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
)

/**
 * Point features from OpenStreetMap shown on the map and in the HUD.
 * Speed cameras are intentionally absent: using camera warnings while driving is illegal in Germany (§ 23 Abs. 1c StVO).
 */
enum class FeatureKind { TRAFFIC_SIGNALS, STOP, GIVE_WAY, CROSSING, RAILWAY_CROSSING }

data class RoadFeature(val kind: FeatureKind, val position: LatLon, val distanceAlong: Double)

/**
 * Painted bike lane next to the car lanes on the right in driving direction.
 * [exclusive] = Radfahrstreifen (solid line, Zeichen 237), false = Schutzstreifen / advisory or shared lane.
 */
data class BikeLaneSpan(val from: Double, val to: Double, val exclusive: Boolean)
