package de.lanewise.routing

import de.lanewise.core.geo.Geo
import de.lanewise.core.geo.LatLon
import de.lanewise.core.geo.Polyline
import de.lanewise.core.model.Maneuver
import de.lanewise.core.model.ManeuverType
import de.lanewise.core.model.Modifier
import de.lanewise.core.model.RoadSpan
import de.lanewise.core.model.Route
import de.lanewise.core.model.RouteStep
import de.lanewise.core.net.Http
import de.lanewise.core.net.arr
import de.lanewise.core.net.bool
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
 * OSRM HTTP API client. Default server: FOSSGIS (routing.openstreetmap.de), hosted in Germany and
 * built from fresh OpenStreetMap data, so `turn:lanes` reflect the current state of German roads.
 *
 * OSRM returns, per intersection, the lanes in driving direction (left → right) with their arrow
 * `indications` and a `valid` flag for the route — exactly what [LaneSite] needs.
 */
class OsrmRoutingProvider(
    private val baseUrl: String = DEFAULT_URL,
) : RoutingProvider {
    override val id = "osrm"
    override val requiresNetwork = true
    override val supportsVia = true

    override suspend fun route(request: RouteRequest): List<Route> {
        val all = listOf(request.origin) + request.via + request.destination
        val coords = all.joinToString(";") { String.format(Locale.US, "%.6f,%.6f", it.lon, it.lat) }
        val bearings = request.heading?.let { "&bearings=${it.toInt()},45" + ";".repeat(all.size - 1) } ?: ""
        val exclude = buildList {
            if (request.avoidTolls) add("toll")
            if (request.avoidMotorways) add("motorway")
        }.takeIf { it.isNotEmpty() }?.joinToString(",", prefix = "&exclude=") ?: ""
        val url = "$baseUrl/route/v1/driving/$coords?steps=true&overview=full&geometries=geojson" +
            "&alternatives=${request.alternatives}&annotations=duration,distance,nodes$bearings$exclude"
        val root = try {
            Http.getJson(url)
        } catch (e: Exception) {
            throw RoutingException("OSRM request failed: ${e.message}", e)
        }
        if (root["code"].str != "Ok") throw RoutingException("OSRM: ${root["code"].str} ${root["message"].str ?: ""}")
        return root["routes"].arr.orEmpty().mapNotNull { parseRoute(it) }
    }

    private fun parseRoute(r: JsonElement): Route? {
        val coords = r["geometry"]["coordinates"].arr ?: return null
        val pts = coords.mapNotNull { c -> val lon = c[0].dbl; val lat = c[1].dbl; if (lat != null && lon != null) LatLon(lat, lon) else null }
        if (pts.size < 2) return null
        val line = Polyline(pts)

        val steps = ArrayList<RouteStep>()
        val sites = ArrayList<LaneSite>()
        val spans = ArrayList<RoadSpan>()
        var hint = 0

        val legs = r["legs"].arr.orEmpty()
        // OSM node ids along the route (each leg starts with the node the previous one ended on).
        val osmNodes = ArrayList<Long>()
        for (leg in legs) for (n in leg["annotation"]["nodes"].arr.orEmpty()) {
            val id = n.dbl?.toLong() ?: continue
            if (osmNodes.lastOrNull() != id) osmNodes += id
        }
        for ((legIdx, leg) in legs.withIndex()) {
            for (s in leg["steps"].arr.orEmpty()) {
                val m = s["maneuver"]
                val loc = latLon(m["location"]) ?: continue
                val proj = line.project(loc, hint, 600) ?: continue
                hint = proj.segmentIndex
                // The end of every leg but the last is a stop on the way, not the destination.
                val type = ManeuverType.fromOsrm(m["type"].str ?: "")
                    .let { if (it == ManeuverType.ARRIVE && legIdx < legs.size - 1) ManeuverType.WAYPOINT else it }
                val modifier = Modifier.fromOsrm(m["modifier"].str)
                val stepDist = s["distance"].dbl ?: 0.0
                val stepDur = s["duration"].dbl ?: 0.0
                steps += RouteStep(
                    distanceAlong = proj.distanceAlong,
                    distance = stepDist,
                    duration = stepDur,
                    maneuver = Maneuver(
                        type, modifier, loc,
                        m["bearing_before"].dbl ?: 0.0, m["bearing_after"].dbl ?: 0.0, m["exit"].int,
                    ),
                    name = s["name"].str.orEmpty(),
                    ref = s["ref"].str?.takeIf { it.isNotBlank() },
                    destinations = s["destinations"].str?.takeIf { it.isNotBlank() },
                    exitNumber = s["exits"].str?.takeIf { it.isNotBlank() },
                )

                // Road classification for this step.
                val inters = s["intersections"].arr.orEmpty()
                val isMotorway = inters.any { i -> i["classes"].arr.orEmpty().any { it.str == "motorway" } }
                val v = if (stepDur > 0) stepDist / stepDur else 0.0
                // Autobahn by OSRM class or by its ref ("A 7"): the classes are often missing on motorway steps.
                val autobahnRef = s["ref"].str.orEmpty().split(';').any { Regex("^A ?\\d+").containsMatchIn(it.trim()) }
                val kind = when {
                    isMotorway || autobahnRef -> RoadKind.MOTORWAY
                    v > 21.0 -> RoadKind.TRUNK
                    v > 14.0 -> RoadKind.RURAL
                    else -> RoadKind.URBAN
                }
                // A road is two-way if, at a later intersection of this step, we could have entered from where we came.
                val twoWay = !isMotorway && !autobahnRef && inters.drop(1).any { i ->
                    val inIdx = i["in"].int ?: return@any false
                    i["entry"][inIdx].bool == true
                }
                spans += RoadSpan(proj.distanceAlong, proj.distanceAlong + stepDist, kind, twoWay)

                // Lane sites.
                inters.forEachIndexed { idx, inter ->
                    val lanes = inter["lanes"].arr ?: return@forEachIndexed
                    val iloc = latLon(inter["location"]) ?: return@forEachIndexed
                    val ip = line.project(iloc, hint, 800) ?: return@forEachIndexed
                    val masks = lanes.map { l -> l["indications"].arr.orEmpty().fold(LaneDir.NONE) { acc, e -> acc or LaneDir.fromToken(e.str ?: "") } }
                    val valid = lanes.map { it["valid"].bool ?: false }
                    val isFirst = idx == 0
                    val turn = if (isFirst && type != ManeuverType.DEPART) {
                        modifier.toLaneDir()
                    } else {
                        geometryTurn(inter) ?: LaneDir.STRAIGHT
                    }
                    sites += LaneSite(
                        distanceAlong = ip.distanceAlong,
                        lanes = masks,
                        providerValid = valid,
                        turn = turn,
                        isManeuver = isFirst && turn != LaneDir.STRAIGHT,
                        roadKind = kind,
                    )
                }
            }
        }
        return Route(
            provider = id,
            geometry = line,
            distance = r["distance"].dbl ?: line.length,
            duration = r["duration"].dbl ?: 0.0,
            steps = steps,
            laneSites = de.lanewise.lane.turnsHiddenAsStraight(de.lanewise.lane.mergeDoubleNodeJunctions(de.lanewise.lane.attachTurnsToLaneSites(
                sites.sortedBy { it.distanceAlong }.distinctBy { it.distanceAlong.toInt() },
                steps.filter {
                    it.maneuver.type != de.lanewise.core.model.ManeuverType.DEPART &&
                        it.maneuver.type != de.lanewise.core.model.ManeuverType.ARRIVE &&
                        it.maneuver.type != de.lanewise.core.model.ManeuverType.WAYPOINT
                }.map { it.distanceAlong to it.maneuver.modifier.toLaneDir() },
            ))),
            roadSpans = spans,
            osmNodes = osmNodes,
        ).let { route -> route.copy(laneSites = de.lanewise.lane.dropRoundaboutInterior(route.laneSites, route.roundabouts())) }
    }

    /**
     * Driving times (s) between all [points] (OSRM `table` service): `result[i][j]` from point i to point j, null where
     * OSRM found no way. One request — used to rank stop candidates by the time they add to the route.
     */
    suspend fun durations(points: List<LatLon>): Array<DoubleArray>? {
        if (points.size < 2) return null
        val coords = points.joinToString(";") { String.format(Locale.US, "%.6f,%.6f", it.lon, it.lat) }
        val root = runCatching { Http.getJson("$baseUrl/table/v1/driving/$coords?annotations=duration") }.getOrNull() ?: return null
        if (root["code"].str != "Ok") return null
        val rows = root["durations"].arr ?: return null
        return Array(rows.size) { i ->
            val row = rows[i].arr.orEmpty()
            DoubleArray(row.size) { j -> row[j].dbl ?: Double.POSITIVE_INFINITY }
        }
    }

    /** Turn direction from intersection bearings (entry = reverse of `in` bearing, exit = `out` bearing). */
    private fun geometryTurn(inter: JsonElement): Int? {
        val b = inter["bearings"].arr ?: return null
        val inIdx = inter["in"].int ?: return null
        val outIdx = inter["out"].int ?: return null
        val inB = b[inIdx].dbl ?: return null
        val outB = b[outIdx].dbl ?: return null
        return LaneDir.fromTurnAngle(Geo.bearingDelta((inB + 180.0) % 360.0, outB))
    }

    private fun latLon(e: JsonElement?): LatLon? {
        val lon = e[0].dbl ?: return null
        val lat = e[1].dbl ?: return null
        return LatLon(lat, lon)
    }

    companion object {
        const val DEFAULT_URL = "https://routing.openstreetmap.de/routed-car"
    }
}
