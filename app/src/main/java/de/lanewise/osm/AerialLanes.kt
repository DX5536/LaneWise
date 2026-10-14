package de.lanewise.osm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * Lane counts measured from the official Niedersachsen aerial photos (LGLN DOP20) by the offline pipeline in
 * `tools/aerial_lanes/`, shipped as `assets/aerial_lanes.json`. Keyed by OSM way id; the file only contains ways
 * where OSM has no `lanes` tag or disagrees with the photos.
 *
 * OSM stays the primary source. The photos are used only:
 *  - where OSM has no lane data at all (no `lanes`, no `turn:lanes*`), or
 *  - where OSM `lanes` disagrees, there is no lane-by-lane `turn:lanes*` data, and the measurement is very
 *    confident (spot-checked ways have 100 %).
 * Ways with `turn:lanes*` are never touched: arrows per lane are better evidence than a count from paint.
 */
class AerialLanes(private val ways: Map<Long, Entry>) {

    data class Entry(val lanes: Int, val confidence: Int)

    val size get() = ways.size

    operator fun get(wayId: Long): Entry? = ways[wayId]

    /** OSM tags with the aerial lane count applied where the policy allows; unchanged otherwise. */
    fun apply(wayId: Long, tags: Map<String, String>): Map<String, String> {
        val e = ways[wayId] ?: return tags
        if (tags.keys.any { it.startsWith("turn:lanes") }) return tags
        val osm = tags["lanes"]?.substringBefore(';')?.trim()?.toIntOrNull()
        if (osm == e.lanes) return tags
        if (osm != null && e.confidence < OVERRIDE_CONFIDENCE) return tags
        val out = tags.toMutableMap()
        out["lanes"] = e.lanes.toString()
        out[SOURCE_TAG] = "aerial"
        val fwd = tags["lanes:forward"]?.toIntOrNull()
        val bwd = tags["lanes:backward"]?.toIntOrNull()
        if (fwd != null && bwd != null && fwd + bwd != e.lanes) {
            out.remove("lanes:forward"); out.remove("lanes:backward")
        }
        return out
    }

    fun apply(street: OsmStreet): OsmStreet {
        val t = apply(street.id, street.tags)
        return if (t === street.tags) street else street.copy(tags = t)
    }

    companion object {
        /** Minimum confidence (%) to overrule an existing OSM `lanes` value. */
        const val OVERRIDE_CONFIDENCE = 90
        const val SOURCE_TAG = "lanewise:lanes_source"
        val EMPTY = AerialLanes(emptyMap())

        /** Parses the asset (`{"v":1, "ways":[[wayId, lanes, confidencePct], ...]}`). */
        fun parse(json: String): AerialLanes {
            val root = Json.parseToJsonElement(json).jsonObject
            require(root["v"]?.jsonPrimitive?.int == 1) { "unsupported aerial_lanes version" }
            val ways = HashMap<Long, Entry>()
            for (w in root["ways"]?.jsonArray.orEmpty()) {
                val a = w as? JsonArray ?: continue
                if (a.size < 3) continue
                ways[a[0].jsonPrimitive.long] = Entry(a[1].jsonPrimitive.int, a[2].jsonPrimitive.int)
            }
            return AerialLanes(ways)
        }
    }
}
