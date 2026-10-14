package de.lanewise.osm

import de.lanewise.core.geo.LatLon
import de.lanewise.lane.LaneDir
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * "Lanes wrong here" reports made while driving (long-press on the lane bar). Stored on the phone as one GeoJSON
 * FeatureCollection (`Android/data/<package>/files/lane_reports.geojson`) that opens directly in JOSM / iD, so the
 * owner can fix the OSM tagging later. Each point carries what LaneWise showed (`turn:lanes` notation) and the road.
 */
object LaneReports {

    data class Report(
        val position: LatLon,
        val timeMs: Long,
        val road: String,
        /** Lanes the app showed, left → right, in OSM `turn:lanes` notation. */
        val shownLanes: String,
        /** Route distance of the reported junction ahead (m), for context. */
        val junctionAhead: Int?,
    )

    /** Bitmask (see [LaneDir]) → OSM `turn:lanes` notation, e.g. `left|through|through;right`. */
    fun turnLanes(lanes: List<Int>): String = lanes.joinToString("|") { m ->
        val parts = listOfNotNull(
            "reverse".takeIf { m and LaneDir.UTURN != 0 },
            "sharp_left".takeIf { m and LaneDir.SHARP_LEFT != 0 },
            "left".takeIf { m and LaneDir.LEFT != 0 },
            "slight_left".takeIf { m and LaneDir.SLIGHT_LEFT != 0 },
            "through".takeIf { m and LaneDir.STRAIGHT != 0 },
            "slight_right".takeIf { m and LaneDir.SLIGHT_RIGHT != 0 },
            "right".takeIf { m and LaneDir.RIGHT != 0 },
            "sharp_right".takeIf { m and LaneDir.SHARP_RIGHT != 0 },
            "merge_to_left".takeIf { m and LaneDir.MERGE_TO_LEFT != 0 },
            "merge_to_right".takeIf { m and LaneDir.MERGE_TO_RIGHT != 0 },
        )
        if (parts.isEmpty()) "none" else parts.joinToString(";")
    }

    /** Adds [r] to [file] (creating it), returns the number of reports now stored. */
    fun append(file: File, r: Report): Int {
        val features = read(file).toMutableList()
        features += feature(r)
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(collection(features))
        if (file.exists()) file.delete()
        tmp.renameTo(file)
        return features.size
    }

    /** Stored features as raw JSON objects (one per report). */
    fun read(file: File): List<String> {
        if (!file.exists()) return emptyList()
        return file.readLines().map { it.trim().removeSuffix(",") }.filter { it.startsWith("{\"type\":\"Feature\"") }
    }

    private fun collection(features: List<String>) =
        "{\"type\":\"FeatureCollection\",\"features\":[\n" + features.joinToString(",\n") + "\n]}\n"

    private fun feature(r: Report): String {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val props = buildList {
            add("\"note\":\"LaneWise: lanes wrong here\"")
            add("\"time\":\"${iso.format(Date(r.timeMs))}\"")
            if (r.road.isNotBlank()) add("\"road\":\"${esc(r.road)}\"")
            add("\"shown_turn_lanes\":\"${esc(r.shownLanes)}\"")
            r.junctionAhead?.let { add("\"junction_ahead_m\":$it") }
        }.joinToString(",")
        return "{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[" +
            String.format(Locale.US, "%.7f,%.7f", r.position.lon, r.position.lat) + "]},\"properties\":{$props}}"
    }

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
}
