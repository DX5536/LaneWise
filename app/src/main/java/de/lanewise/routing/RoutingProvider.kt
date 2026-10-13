package de.lanewise.routing

import de.lanewise.core.geo.LatLon
import de.lanewise.core.model.Modifier
import de.lanewise.core.model.Route
import de.lanewise.lane.LaneDir

data class RouteRequest(
    val origin: LatLon,
    val destination: LatLon,
    /** Current heading of the car (deg) if moving — helps avoid routes that start with a U-turn. */
    val heading: Double? = null,
    val alternatives: Boolean = true,
    val avoidTolls: Boolean = false,
    val avoidMotorways: Boolean = false,
)

interface RoutingProvider {
    val id: String
    val requiresNetwork: Boolean
    suspend fun route(request: RouteRequest): List<Route>
}

class RoutingException(message: String, cause: Throwable? = null) : Exception(message, cause)

internal fun Modifier.toLaneDir(): Int = when (this) {
    Modifier.UTURN -> LaneDir.UTURN
    Modifier.SHARP_RIGHT -> LaneDir.SHARP_RIGHT
    Modifier.RIGHT -> LaneDir.RIGHT
    Modifier.SLIGHT_RIGHT -> LaneDir.SLIGHT_RIGHT
    Modifier.STRAIGHT -> LaneDir.STRAIGHT
    Modifier.SLIGHT_LEFT -> LaneDir.SLIGHT_LEFT
    Modifier.LEFT -> LaneDir.LEFT
    Modifier.SHARP_LEFT -> LaneDir.SHARP_LEFT
}
