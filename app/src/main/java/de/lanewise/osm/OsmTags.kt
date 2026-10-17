package de.lanewise.osm

import de.lanewise.core.geo.LatLon
import de.lanewise.core.model.FeatureKind
import de.lanewise.core.model.RoadFeature
import de.lanewise.lane.RoadKind

/** Helpers for interpreting OpenStreetMap tags with German conventions. */
object OsmTags {

    /** Highway classes LaneWise routes on, with a byte code for compact storage. */
    val HIGHWAY_CLASSES = listOf(
        "motorway", "motorway_link", "trunk", "trunk_link", "primary", "primary_link",
        "secondary", "secondary_link", "tertiary", "tertiary_link", "unclassified", "residential", "living_street",
    )

    fun classCode(highway: String): Int = HIGHWAY_CLASSES.indexOf(highway)

    /** Assumed free-flow speed when no maxspeed is tagged (km/h). */
    fun defaultSpeed(highway: String): Int = when (highway) {
        "motorway" -> 120
        "motorway_link" -> 60
        "trunk" -> 90
        "trunk_link" -> 50
        "primary" -> 60
        "primary_link" -> 40
        "secondary" -> 50
        "secondary_link" -> 40
        "tertiary", "tertiary_link" -> 45
        "unclassified" -> 40
        "residential" -> 30
        "living_street" -> 7
        else -> 30
    }

    fun roadKind(highway: String, maxspeedKmh: Int?): RoadKind = when {
        highway.startsWith("motorway") -> RoadKind.MOTORWAY
        highway.startsWith("trunk") -> RoadKind.TRUNK
        maxspeedKmh != null && maxspeedKmh >= 70 -> RoadKind.RURAL
        else -> RoadKind.URBAN
    }

    /**
     * Overpass statements selecting the point features LaneWise shows (traffic lights, stop/give way,
     * crossings) inside [area], e.g. "(52.3,9.6,52.4,9.8)" or "(around:20,lat,lon,...)".
     */
    fun featureNodeQuery(area: String): String =
        "node$area[\"highway\"~\"^(traffic_signals|stop|give_way|crossing)$\"];" +
            "node$area[\"railway\"=\"level_crossing\"];" +
            "node$area[\"traffic_sign\"~\"DE:20[56]\"];"

    /** Feature with traffic-light details, or null if the node is not shown. */
    fun feature(tags: Map<String, String>, position: LatLon, distanceAlong: Double): RoadFeature? {
        val kind = featureKind(tags) ?: return null
        return RoadFeature(
            kind, position, distanceAlong,
            pedestrian = kind == FeatureKind.TRAFFIC_SIGNALS && tags["crossing"] == "traffic_signals",
            onDemand = tags["traffic_signals"] == "on_demand" || tags["button_operated"] == "only",
            greenArrow = tags["red_turn:right"] == "yes" || tags["traffic_sign"].orEmpty().contains("DE:720"),
            zebra = kind == FeatureKind.CROSSING && isZebra(tags),
        )
    }

    /**
     * Zebra crossing as painted in Germany (Zeichen 293 with sign 350): `crossing=zebra`, `crossing_ref=zebra`,
     * `crossing:markings=zebra…` or the sign DE:350. Plain `marked` / `uncontrolled` crossings (often just a lowered
     * kerb or dashed lines) and crossings at traffic lights get no stripes — unlike China, Germany paints no zebra at
     * signalised junctions.
     */
    fun isZebra(tags: Map<String, String>): Boolean =
        tags["crossing"] == "zebra" || tags["crossing_ref"] == "zebra" ||
            tags["crossing:markings"].orEmpty().startsWith("zebra") || tags["traffic_sign"].orEmpty().contains("DE:350")

    /** Classify an OSM node's tags; null if it is not a feature LaneWise shows. */
    fun featureKind(tags: Map<String, String>): FeatureKind? {
        val hw = tags["highway"]
        val sign = tags["traffic_sign"].orEmpty()
        return when {
            hw == "traffic_signals" -> FeatureKind.TRAFFIC_SIGNALS
            hw == "crossing" && tags["crossing"] == "traffic_signals" -> FeatureKind.TRAFFIC_SIGNALS
            hw == "stop" || sign.contains("DE:206") -> FeatureKind.STOP
            hw == "give_way" || sign.contains("DE:205") -> FeatureKind.GIVE_WAY
            tags["railway"] == "level_crossing" -> FeatureKind.RAILWAY_CROSSING
            hw == "crossing" && (tags["crossing"] in setOf("zebra", "marked", "uncontrolled") || tags["crossing_ref"] == "zebra") ->
                FeatureKind.CROSSING
            else -> null
        }
    }

    /**
     * Bike lane on the right-hand side of travel. [forward] = travelling in the OSM way direction.
     * Returns null if none, true for an exclusive lane (Radfahrstreifen), false for advisory/shared.
     * Separate tracks (`track`, `separate`) are not on the carriageway and are ignored.
     */
    fun bikeLaneRight(tags: Map<String, String>, forward: Boolean, oneway: Boolean): Boolean? {
        val side = if (forward) "right" else "left"
        val v = tags["cycleway:$side"] ?: tags["cycleway:both"]
            ?: tags["cycleway"]?.takeIf { oneway || it == "lane" || it == "shared_lane" }
        return when (v) {
            "lane" -> (tags["cycleway:$side:lane"] ?: tags["cycleway:both:lane"] ?: tags["cycleway:lane"]) != "advisory"
            "shared_lane" -> false
            else -> null
        }
    }

    /**
     * Per lane in travel direction (left → right): may a car use it? From `access:lanes`, `vehicle:lanes`,
     * `motor_vehicle:lanes`, `motorcar:lanes` (no / psv / bus / designated / private …) and `bus:lanes` / `psv:lanes`
     * = designated (a bus lane, unless a car tag opens it). Null when no lane access is tagged. Hollerithallee:
     * `access:lanes:forward=psv|yes|yes|yes` - the unmarked left lane is a bus lane.
     */
    fun carLanes(tags: Map<String, String>, forward: Boolean, oneway: Boolean): List<Boolean>? {
        val dir = if (forward) "forward" else "backward"
        fun v(key: String) = (tags["$key:$dir"] ?: if (oneway && forward) tags[key] else null)?.split('|')?.map { it.trim() }
        val car = listOf("access:lanes", "vehicle:lanes", "motor_vehicle:lanes", "motorcar:lanes").mapNotNull(::v)
        val designated = listOf("bus:lanes", "psv:lanes").mapNotNull(::v)
        if (car.isEmpty() && designated.isEmpty()) return null
        val n = (car + designated).maxOf { it.size }
        if ((car + designated).any { it.size != n }) return null   // inconsistent tagging: not used
        val closed = setOf("no", "psv", "bus", "designated", "private", "delivery", "agricultural", "forestry", "emergency")
        return List(n) { i ->
            val carValue = car.lastOrNull { it[i].isNotEmpty() }?.get(i)   // the most specific car tag wins
            when {
                carValue != null -> carValue !in closed
                designated.any { it[i] == "designated" } -> false
                else -> true
            }
        }
    }

    /** Lane-change permissions in travel direction (`change:lanes*`), or null if unrestricted / not tagged. */
    fun changeLanes(tags: Map<String, String>, forward: Boolean, oneway: Boolean): List<Int>? {
        val v = tags[if (forward) "change:lanes:forward" else "change:lanes:backward"]
            ?: if (oneway && forward) tags["change:lanes"] else null
        return v?.let { de.lanewise.lane.ChangeSpan.parse(it) }
    }

    /**
     * Car lanes in travel direction ([forward] = along the OSM way), or null if not tagged.
     * On a two-way street `lanes` counts both directions (OSM): `lanes=4` is 2 + 2, not 4 lanes for us. Lanes of the
     * other direction and a shared centre lane (`lanes:both_ways`, e.g. a turn lane for both) are taken off when tagged:
     * `lanes=3` + `lanes:backward=1` is 2 for us, `lanes=3` + `lanes:both_ways=1` is 1 per direction.
     */
    fun lanesInDirection(tags: Map<String, String>, forward: Boolean, oneway: Boolean): Int? {
        val dir = if (forward) "forward" else "backward"
        tags["lanes:$dir"]?.toIntOrNull()?.let { return it }
        tags["turn:lanes:$dir"]?.let { return it.split('|').size }
        if (oneway) {
            tags["lanes"]?.toIntOrNull()?.let { return it }
            tags["turn:lanes"]?.let { return it.split('|').size }
            return null
        }
        val total = tags["lanes"]?.substringBefore(';')?.trim()?.toIntOrNull() ?: return null
        val both = tags["lanes:both_ways"]?.toIntOrNull() ?: 0
        val otherDir = if (forward) "backward" else "forward"
        val other = tags["lanes:$otherDir"]?.toIntOrNull() ?: tags["turn:lanes:$otherDir"]?.split('|')?.size
        return if (other != null) (total - other - both).coerceAtLeast(1) else ((total - both) / 2).coerceAtLeast(1)
    }

    /**
     * One-way direction of a way: 1 = one-way along the way, -1 = one-way against it (`oneway=-1`), 0 = two-way.
     * `oneway=yes|true|1`, and implied for motorways, motorway links and roundabouts (unless `oneway=no`).
     */
    fun onewayDir(tags: Map<String, String>): Int {
        when (tags["oneway"]) {
            "yes", "true", "1" -> return 1
            "-1", "reverse" -> return -1
            "no", "false", "0" -> return 0
        }
        val hw = tags["highway"].orEmpty()
        return if (hw == "motorway" || hw == "motorway_link" || tags["junction"] == "roundabout" || tags["junction"] == "circular") 1 else 0
    }

    const val NO_LIMIT = 0
    const val UNKNOWN = -1

    /**
     * Parse an OSM `maxspeed` value. Returns km/h, [NO_LIMIT] for "none"/German motorway default, or [UNKNOWN].
     * Handles German implicit values (DE:urban, DE:rural, DE:motorway, DE:living_street, DE:zone30 ...).
     */
    fun parseMaxspeed(raw: String?): Int {
        if (raw.isNullOrBlank()) return UNKNOWN
        val v = raw.trim().lowercase()
        if (v == "none" || v == "de:motorway") return NO_LIMIT
        if (v == "walk" || v == "de:living_street") return 7
        if (v == "de:urban") return 50
        if (v == "de:rural") return 100
        if (v.startsWith("de:zone")) return v.removePrefix("de:zone").filter { it.isDigit() }.toIntOrNull() ?: 30
        if (v.startsWith("de:bicycle_road")) return 30
        val n = v.takeWhile { it.isDigit() }.toIntOrNull() ?: return UNKNOWN
        return if (v.contains("mph")) (n * 1.609).toInt() else n
    }
}
