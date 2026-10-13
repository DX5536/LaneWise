package de.lanewise.osm

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
