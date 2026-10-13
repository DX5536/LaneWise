package de.lanewise.routing

import de.lanewise.core.geo.LatLon
import de.lanewise.core.geo.Polyline
import de.lanewise.core.model.Maneuver
import de.lanewise.core.model.ManeuverType
import de.lanewise.core.model.Modifier
import de.lanewise.core.model.RoadSpan
import de.lanewise.core.model.Route
import de.lanewise.core.model.RouteStep
import de.lanewise.core.model.SpeedLimitSpan
import de.lanewise.core.net.Http
import de.lanewise.core.net.arr
import de.lanewise.core.net.dbl
import de.lanewise.core.net.get
import de.lanewise.core.net.int
import de.lanewise.core.net.str
import de.lanewise.lane.LaneDir
import de.lanewise.lane.LaneSite
import de.lanewise.lane.RoadKind
import kotlinx.serialization.json.JsonElement
import java.util.Locale

/**
 * TomTom Routing API (calculateRoute) with live traffic, lane sections and speed-limit sections.
 * Requires an API key (free tier: 2,500 requests/day) — set it in Settings or via TOMTOM_API_KEY.
 */
class TomTomRoutingProvider(private val apiKey: String) : RoutingProvider {
    override val id = "tomtom"
    override val requiresNetwork = true

    override suspend fun route(request: RouteRequest): List<Route> {
        if (apiKey.isBlank()) throw RoutingException("TomTom API key missing")
        val o = request.origin
        val d = request.destination
        val locs = String.format(Locale.US, "%.6f,%.6f:%.6f,%.6f", o.lat, o.lon, d.lat, d.lon)
        val avoid = buildString {
            if (request.avoidTolls) append("&avoid=tollRoads")
            if (request.avoidMotorways) append("&avoid=motorways")
        }
        val heading = request.heading?.let { "&vehicleHeading=${it.toInt()}" } ?: ""
        val url = "https://api.tomtom.com/routing/1/calculateRoute/$locs/json?key=$apiKey" +
            "&traffic=true&travelMode=car&routeType=fastest&computeTravelTimeFor=all" +
            "&instructionsType=coded&language=de-DE" +
            "&sectionType=lanes&sectionType=speedLimit&sectionType=motorway&sectionType=traffic" +
            "&maxAlternatives=${if (request.alternatives) 2 else 0}$avoid$heading"
        val root = try {
            Http.getJson(url)
        } catch (e: Exception) {
            throw RoutingException("TomTom request failed: ${e.message}", e)
        }
        return root["routes"].arr.orEmpty().mapNotNull { parse(it) }
    }

    private fun parse(r: JsonElement): Route? {
        val pts = r["legs"].arr.orEmpty().flatMap { leg ->
            leg["points"].arr.orEmpty().mapNotNull { p ->
                val lat = p["latitude"].dbl; val lon = p["longitude"].dbl
                if (lat != null && lon != null) LatLon(lat, lon) else null
            }
        }
        if (pts.size < 2) return null
        val line = Polyline(pts)
        val summary = r["summary"]

        val steps = r["guidance"]["instructions"].arr.orEmpty().mapNotNull { ins ->
            val p = ins["point"]
            val loc = LatLon(p["latitude"].dbl ?: return@mapNotNull null, p["longitude"].dbl ?: return@mapNotNull null)
            val (type, mod) = mapManeuver(ins["maneuver"].str.orEmpty())
            RouteStep(
                distanceAlong = ins["routeOffsetInMeters"].dbl ?: 0.0,
                distance = 0.0,
                duration = ins["travelTimeInSeconds"].dbl ?: 0.0,
                maneuver = Maneuver(type, mod, loc, 0.0, 0.0, ins["roundaboutExitNumber"].int),
                name = ins["street"].str.orEmpty(),
                ref = ins["roadNumbers"].arr?.firstOrNull()?.str,
                destinations = ins["signpostText"].str,
                exitNumber = ins["exitNumber"].str,
            )
        }

        val sections = r["sections"].arr.orEmpty()
        fun idxDist(i: Int?) = line.cumulative[(i ?: 0).coerceIn(0, line.cumulative.size - 1)]

        val motorwaySpans = sections.filter { it["sectionType"].str == "MOTORWAY" }
            .map { idxDist(it["startPointIndex"].int) to idxDist(it["endPointIndex"].int) }
        fun kindAt(dist: Double): RoadKind =
            if (motorwaySpans.any { dist >= it.first && dist <= it.second }) RoadKind.MOTORWAY else RoadKind.URBAN

        val speed = sections.filter { it["sectionType"].str == "SPEED_LIMIT" }.map {
            SpeedLimitSpan(idxDist(it["startPointIndex"].int), idxDist(it["endPointIndex"].int), it["maxSpeedLimitInKmh"].int)
        }

        val sites = sections.filter { it["sectionType"].str == "LANES" }.mapNotNull { s ->
            val lanes = s["lanes"].arr ?: return@mapNotNull null
            val end = idxDist(s["endPointIndex"].int)
            val masks = lanes.map { l -> l["directions"].arr.orEmpty().fold(LaneDir.NONE) { a, e -> a or tomTomDir(e.str) } }
            val follow = lanes.map { l -> tomTomDir(l["follow"].str) }
            val valid = follow.map { it != LaneDir.NONE }
            // Route direction at the site: the follow direction most lanes agree on.
            val turn = follow.filter { it != LaneDir.NONE }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
                ?: LaneDir.STRAIGHT
            val maneuverNear = steps.any { kotlin.math.abs(it.distanceAlong - end) < 40 && it.maneuver.modifier != Modifier.STRAIGHT }
            LaneSite(end, masks, valid, turn, maneuverNear && turn != LaneDir.STRAIGHT, kindAt(end))
        }

        // Road spans from speed limits as a proxy for urban/rural, refined by motorway sections.
        val spans = speed.map { s ->
            val kind = when {
                kindAt((s.from + s.to) / 2) == RoadKind.MOTORWAY -> RoadKind.MOTORWAY
                (s.kmh ?: 130) >= 90 -> RoadKind.RURAL
                else -> RoadKind.URBAN
            }
            RoadSpan(s.from, s.to, kind, twoWay = kind != RoadKind.MOTORWAY)
        }

        return Route(
            provider = id,
            geometry = line,
            distance = summary["lengthInMeters"].dbl ?: line.length,
            duration = summary["travelTimeInSeconds"].dbl ?: 0.0,
            steps = steps,
            laneSites = sites.sortedBy { it.distanceAlong },
            speedLimits = speed,
            roadSpans = spans,
        )
    }

    private fun tomTomDir(s: String?): Int = when (s) {
        "STRAIGHT" -> LaneDir.STRAIGHT
        "SLIGHT_RIGHT" -> LaneDir.SLIGHT_RIGHT
        "RIGHT" -> LaneDir.RIGHT
        "SHARP_RIGHT" -> LaneDir.SHARP_RIGHT
        "LEFT_U_TURN", "RIGHT_U_TURN" -> LaneDir.UTURN
        "SHARP_LEFT" -> LaneDir.SHARP_LEFT
        "LEFT" -> LaneDir.LEFT
        "SLIGHT_LEFT" -> LaneDir.SLIGHT_LEFT
        else -> LaneDir.NONE
    }

    private fun mapManeuver(m: String): Pair<ManeuverType, Modifier> = when (m) {
        "DEPART" -> ManeuverType.DEPART to Modifier.STRAIGHT
        "ARRIVE", "ARRIVE_LEFT", "ARRIVE_RIGHT" -> ManeuverType.ARRIVE to Modifier.STRAIGHT
        "STRAIGHT" -> ManeuverType.CONTINUE to Modifier.STRAIGHT
        "KEEP_RIGHT" -> ManeuverType.FORK to Modifier.SLIGHT_RIGHT
        "BEAR_RIGHT" -> ManeuverType.TURN to Modifier.SLIGHT_RIGHT
        "TURN_RIGHT" -> ManeuverType.TURN to Modifier.RIGHT
        "SHARP_RIGHT" -> ManeuverType.TURN to Modifier.SHARP_RIGHT
        "KEEP_LEFT" -> ManeuverType.FORK to Modifier.SLIGHT_LEFT
        "BEAR_LEFT" -> ManeuverType.TURN to Modifier.SLIGHT_LEFT
        "TURN_LEFT" -> ManeuverType.TURN to Modifier.LEFT
        "SHARP_LEFT" -> ManeuverType.TURN to Modifier.SHARP_LEFT
        "MAKE_UTURN" -> ManeuverType.TURN to Modifier.UTURN
        "ENTER_MOTORWAY", "ENTER_FREEWAY", "ENTER_HIGHWAY" -> ManeuverType.ON_RAMP to Modifier.SLIGHT_RIGHT
        "TAKE_EXIT" -> ManeuverType.OFF_RAMP to Modifier.SLIGHT_RIGHT
        "MOTORWAY_EXIT_LEFT" -> ManeuverType.OFF_RAMP to Modifier.SLIGHT_LEFT
        "MOTORWAY_EXIT_RIGHT" -> ManeuverType.OFF_RAMP to Modifier.SLIGHT_RIGHT
        "ROUNDABOUT_CROSS", "ROUNDABOUT_RIGHT", "ROUNDABOUT_LEFT", "ROUNDABOUT_BACK" -> ManeuverType.ROUNDABOUT to Modifier.STRAIGHT
        "SWITCH_MOTORWAY_LEFT", "SWITCH_PARALLEL_ROAD" -> ManeuverType.FORK to Modifier.SLIGHT_LEFT
        "SWITCH_MOTORWAY_RIGHT", "SWITCH_MAIN_ROAD" -> ManeuverType.FORK to Modifier.SLIGHT_RIGHT
        else -> ManeuverType.NOTIFICATION to Modifier.STRAIGHT
    }
}
