package de.lanewise.osm

import de.lanewise.core.model.FeatureKind
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
