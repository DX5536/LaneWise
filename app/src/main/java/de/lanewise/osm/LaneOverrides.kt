package de.lanewise.osm

import de.lanewise.core.geo.LatLon
import de.lanewise.lane.LaneDir
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/**
 * Hand-checked lane corrections (`assets/lane_overrides.json`, plus `lane_overrides.json` next to
 * `lane_reports.geojson` on the phone, which wins on the same `id`). For junctions where OSM is wrong or OSRM drops the
 * lanes, until the owner has fixed OSM. Precedence: **overrides > OSM `turn:lanes` > aerial lane counts > heuristics**.
 *
 * The file is a GeoJSON FeatureCollection like `lane_reports.geojson`, so both open side by side in JOSM / iD: a report
 * point becomes an override by adding `osm_way` (or `osm_node`), the checked values and a `source`. Property names are
 * OSM tag names, so the fix can be copied into OSM as it is. The point geometry is only for finding the spot on the map;
 * matching is by OSM id.
 *
 * ```
 * {"type": "FeatureCollection", "lanewise_overrides": 1, "features": [
 *   {"type": "Feature", "geometry": {"type": "Point", "coordinates": [9.75, 52.38]}, "properties": {
 *     "id": "example-way",           unique, required
 *     "osm_way": 123,                way patch (or "osm_node": 456 [+ "from_way": 789] for a junction patch)
 *     "direction": "forward",        forward / backward along the OSM way; may be left out on one-way ways only
 *     "turn:lanes": "left|left|through|through;right",   arrows in travel direction, left → right
 *     "lanes": 4,                    car lanes in travel direction ("effective_lanes" is accepted as well)
 *     "change:lanes": "yes|not_left|yes|yes",            solid lines (OSM syntax)
 *     "lanes_unknown": true,         OSM arrows are wrong and the truth is not known: no lane advice here
 *     "osm_was": {"turn:lanes": "left|through|through|through"},  OSM when the patch was written (optional)
 *     "connectivity": "2:1|3:2",     lane connectivity (OSM `type=connectivity` syntax), only on an osm_node patch
 *                                    with "from_way" and "to_way", nothing else in the same entry
 *     "source": "DOP20 2025 + drive 2026-10-12",          evidence, required
 *     "checked": "2026-10-15", "note": "…"
 *   }}]}
 * ```
 *
 * Version 2 (`"lanewise_overrides": 2`, `docs/MARKING_PLANS.md`) adds exact geometry, e.g. from a city marking plan,
 * on entries with `turn:lanes`:
 * ```
 *     "stop_line:lanes": "8.5|2",    stop line per lane, m before the junction (way end / osm_node)
 *     "change_spans": [{"from_stop_m": 0, "to_stop_m": 10, "change:lanes": "not_right|not_left"}],
 *                                    solid lines over part of the approach, m before the front stop line
 *     "pocket_start_m": 45,          where the turn pockets begin, m before the front stop line
 *     "width:lanes": "3.25|3.00",    lane widths (stored only)
 *     "source_plan": {"drucksache": "15-2330-2024", "anlage": 2, "blatt": 2, "date": "2024-10"},
 *     "built_confirmed": "Mapillary 2026-05-12",   dated proof the plan is painted; required with source_plan
 *     "draft": true                  written by tools/marking_plans, not reviewed yet: always rejected
 * ```
 *
 * Safety: an entry that does not validate is dropped (see [rejected]), never half-applied; a patch for a two-way way
 * without `direction` is not applied (it would land on the wrong carriageway); when OSM has changed since `osm_was` and
 * now matches neither the old value nor the patch, nobody has checked the new state, so the junction gets no lane advice
 * (`lanes_unknown`) until the patch is re-checked.
 */
class LaneOverrides(entries: List<Entry>, val rejected: List<String> = emptyList()) {

    data class Entry(
        val id: String,
        val wayId: Long? = null,
        val nodeId: Long? = null,
        /** Only for node patches: the way the route arrives on. */
        val fromWay: Long? = null,
        /** "forward" / "backward" along the OSM way, null = travel direction of a one-way way. */
        val direction: String? = null,
        val turnLanes: String? = null,
        val lanes: Int? = null,
        val changeLanes: String? = null,
        val lanesUnknown: Boolean = false,
        /** Only for node patches: the way the route leaves on (for [connectivity]). */
        val toWay: Long? = null,
        /** Lane connectivity from [fromWay] via [nodeId] onto [toWay], OSM syntax (`1:1|2:2,3`). */
        val connectivity: String? = null,
        val osmWas: Map<String, String> = emptyMap(),
        val source: String,
        val position: LatLon? = null,
        // Version 2 (exact geometry, docs/MARKING_PLANS.md).
        /** Stop line per lane (left → right), m before the junction. */
        val stopLines: List<Double>? = null,
        /** Solid-line stretches measured back from the front stop line. */
        val changeSpans: List<Span> = emptyList(),
        /** Where the turn pockets begin, m before the front stop line. */
        val pocketStart: Double? = null,
        val widths: List<Double>? = null,
        /** The marking plan this entry was read from (Drucksache, Anlage, Blatt, date), as text. */
        val sourcePlan: String? = null,
        /** Dated evidence that [sourcePlan] is built (painted). */
        val builtConfirmed: String? = null,
        val draft: Boolean = false,
        /** File version the entry came from. */
        val version: Int = 1,
    ) {
        /** Metres before the junction of the stop line closest to it (0 if not given). */
        val frontStop: Double get() = stopLines?.minOrNull() ?: 0.0
    }

    /** `change:lanes` [value] from [fromStop] to [toStop] m before the front stop line ([toStop] > [fromStop]). */
    data class Span(val fromStop: Double, val toStop: Double, val value: String)

    /**
     * A patched junction on the route: [lanes] in `turn:lanes` notation, or null = lanes unknown there. Version-2
     * geometry: [changeSpans] at route distances, [pocketLength] = how far before the junction its pockets open.
     */
    data class At(
        val distanceAlong: Double, val lanes: String?, val id: String,
        val changeSpans: List<de.lanewise.lane.ChangeSpan> = emptyList(),
        val pocketLength: Double? = null,
    )

    /** A way of the corridor data, for matching node patches. [geom] is aligned with [nodes]. */
    data class WayRef(val id: Long, val nodes: List<Long>, val geom: List<LatLon>)

    private val byWay: Map<Long, List<Entry>> = entries.filter { it.wayId != null }.groupBy { it.wayId!! }
    private val nodeEntries: List<Entry> = entries.filter { it.nodeId != null && it.connectivity == null }
    private val connectivityEntries: List<Entry> = entries.filter { it.connectivity != null }

    val size = entries.size
    val entries: List<Entry> = entries
    private val byId: Map<String, Entry> = entries.associateBy { it.id }

    /**
     * [at] with the version-2 geometry of its entry at the route: change spans and pocket start, counted back from the
     * front stop line before the junction at [At.distanceAlong]. Unchanged for "lanes unknown" (nothing is planned there).
     */
    fun withGeometry(at: At): At {
        val e = byId[at.id] ?: return at
        if (at.lanes == null) return at
        val stop = at.distanceAlong - e.frontStop
        val spans = e.changeSpans.mapNotNull { s ->
            de.lanewise.lane.ChangeSpan.parse(s.value)?.let { de.lanewise.lane.ChangeSpan((stop - s.toStop).coerceAtLeast(0.0), stop - s.fromStop, it) }
        }
        return at.copy(changeSpans = spans, pocketLength = e.pocketStart?.let { it + e.frontStop })
    }

    /**
     * OSM tags of way [wayId] with its patches applied (explicit `:forward` / `:backward` keys, so they win over the
     * plain keys everywhere they are read), plus [MARK] / [UNKNOWN_MARK] keys for the corridor. Unchanged if none.
     */
    fun apply(wayId: Long, tags: Map<String, String>): Map<String, String> {
        val list = byWay[wayId] ?: return tags
        var out: MutableMap<String, String>? = null
        for (e in list) {
            val dir = e.direction ?: when (OsmTags.onewayDir(tags)) {
                1 -> "forward"
                -1 -> "backward"
                else -> continue   // two-way way without a direction: which carriageway is meant is not known
            }
            val t = out ?: tags.toMutableMap().also { out = it }
            val plainIsThisDir = OsmTags.onewayDir(tags) == (if (dir == "forward") 1 else -1)
            fun current(key: String) = tags["$key:$dir"] ?: tags[key].takeIf { plainIsThisDir }
            val stale = e.osmWas.isNotEmpty() && e.osmWas.any { (k, was) ->
                val now = current(k.removeSuffix(":$dir"))
                now != was && now != patched(e, k.removeSuffix(":$dir"))
            }
            if (e.lanesUnknown || stale) {
                t.remove("turn:lanes:$dir")
                if (plainIsThisDir) t.remove("turn:lanes")
                t["$UNKNOWN_MARK:$dir"] = e.id
                e.lanes?.let { t["lanes:$dir"] = it.toString() }
                continue
            }
            e.turnLanes?.let { t["turn:lanes:$dir"] = it }
            (e.lanes ?: e.turnLanes?.split('|')?.size)?.let { t["lanes:$dir"] = it.toString() }
            e.changeLanes?.let { t["change:lanes:$dir"] = it }
            if (e.turnLanes != null) t["$MARK:$dir"] = e.id
        }
        return out ?: tags
    }

    fun apply(street: OsmStreet): OsmStreet {
        val t = apply(street.id, street.tags)
        return if (t === street.tags) street else street.copy(tags = t)
    }

    /**
     * Junction patches on the route: [routeNodes] the route's OSM nodes in order, [ways] the corridor ways (for the
     * node's position and the arriving way), [project] a position → route distance (null if too far off the route).
     * A patch with `from_way` applies only when the route really arrives on that way; if the way is not in [ways] it
     * cannot be checked and is skipped.
     */
    fun junctionsOnRoute(routeNodes: List<Long>, ways: List<WayRef>, project: (LatLon) -> Double?): List<At> {
        if (nodeEntries.isEmpty() || routeNodes.size < 2) return emptyList()
        val out = ArrayList<At>()
        for (e in nodeEntries) {
            val i = routeNodes.indexOf(e.nodeId!!).takeIf { it > 0 } ?: continue
            if (e.fromWay != null) {
                val w = ways.firstOrNull { it.id == e.fromWay } ?: continue
                val prev = routeNodes[i - 1]
                val arrives = (0 until w.nodes.size - 1).any {
                    (w.nodes[it] == prev && w.nodes[it + 1] == e.nodeId) || (w.nodes[it] == e.nodeId && w.nodes[it + 1] == prev)
                }
                if (!arrives) continue
            }
            val pos = ways.firstNotNullOfOrNull { w -> w.nodes.indexOf(e.nodeId).takeIf { it >= 0 }?.let { w.geom.getOrNull(it) } }
                ?: continue
            val d = project(pos) ?: continue
            out += withGeometry(At(d, if (e.lanesUnknown) null else e.turnLanes, e.id))
        }
        return out.sortedBy { it.distanceAlong }
    }

    /**
     * Connectivity patches on the route (like [junctionsOnRoute]): only where the route arrives on `from_way` and
     * leaves on `to_way` through the node; if either way is not in [ways] the patch cannot be checked and is skipped.
     */
    fun connectivityOnRoute(routeNodes: List<Long>, ways: List<WayRef>, project: (LatLon) -> Double?): List<de.lanewise.lane.ConnectivityAt> =
        OsmConnectivity.onRoute(connectivityEntries.map {
            OsmConnectivity.Relation("override:${it.id}", it.fromWay!!, it.nodeId!!, it.toWay!!, it.connectivity!!)
        }, routeNodes, ways, project, override = true)

    companion object {
        /** Tag added to a patched way: `lanewise:override:<dir>` = entry id (its `turn:lanes:<dir>` comes from the patch). */
        const val MARK = "lanewise:override"
        /** Tag added where a patch says the lanes are not known: `lanewise:lanes_unknown:<dir>` = entry id. */
        const val UNKNOWN_MARK = "lanewise:lanes_unknown"
        val EMPTY = LaneOverrides(emptyList())

        private val TOKENS = setOf("", "none", "through", "left", "right", "slight_left", "slight_right", "sharp_left",
            "sharp_right", "reverse", "merge_to_left", "merge_to_right")
        private val CHANGE_TOKENS = setOf("yes", "no", "not_left", "not_right")

        private fun patched(e: Entry, key: String): String? = when (key) {
            "turn:lanes" -> e.turnLanes
            "lanes" -> (e.lanes ?: e.turnLanes?.split('|')?.size)?.toString()
            "change:lanes" -> e.changeLanes
            else -> null
        }

        /** Why [e] must not be used, or null if it is valid. */
        fun problem(e: Entry): String? {
            if (e.id.isBlank()) return "no id"
            if (e.source.isBlank()) return "no source (evidence) given"
            if ((e.wayId == null) == (e.nodeId == null)) return "needs exactly one of osm_way / osm_node"
            if (e.direction != null && e.direction != "forward" && e.direction != "backward") return "direction must be forward or backward"
            if (e.connectivity != null) {
                if (e.nodeId == null || e.fromWay == null || e.toWay == null) return "connectivity needs osm_node, from_way and to_way"
                if (e.turnLanes != null || e.lanesUnknown || e.lanes != null || e.changeLanes != null || e.direction != null ||
                    e.osmWas.isNotEmpty() || e.stopLines != null || e.changeSpans.isNotEmpty() || e.pocketStart != null || e.widths != null) return "a connectivity patch takes nothing else (patch turn:lanes in its own entry)"
                if (de.lanewise.lane.LaneConnectivity.parse(e.connectivity) == null) return "unreadable connectivity '${e.connectivity}'"
                return null
            }
            if (e.draft) return "draft (not reviewed yet)"
            val v2 = e.stopLines != null || e.changeSpans.isNotEmpty() || e.pocketStart != null || e.widths != null ||
                e.sourcePlan != null || e.builtConfirmed != null
            if (v2 && e.version < 2) return "stop_line:lanes / change_spans / pocket_start_m / width:lanes / source_plan need lanewise_overrides 2"
            if (e.sourcePlan != null && e.builtConfirmed.isNullOrBlank()) return "source_plan without built_confirmed (a plan is no proof the road looks like it)"
            if (e.toWay != null) return "to_way is only for connectivity patches"
            if (e.nodeId != null && (e.lanes != null || e.changeLanes != null || e.direction != null || e.osmWas.isNotEmpty()))
                return "a junction (osm_node) patch takes only turn:lanes or lanes_unknown"
            if (e.wayId != null && e.fromWay != null) return "from_way is for osm_node patches"
            if (e.lanesUnknown && e.turnLanes != null) return "lanes_unknown and turn:lanes contradict each other"
            if (!e.lanesUnknown && e.turnLanes == null && e.lanes == null && e.changeLanes == null) return "nothing to patch"
            if (e.nodeId != null && !e.lanesUnknown && e.turnLanes == null) return "a junction patch needs turn:lanes"
            val n = e.turnLanes?.split('|')?.size
            e.turnLanes?.let { v ->
                if (n!! < 1 || n > MAX_LANES) return "turn:lanes has $n lanes"
                val bad = v.split('|').flatMap { it.split(';') }.map { it.trim() }.firstOrNull { it !in TOKENS }
                if (bad != null) return "unknown turn:lanes value '$bad'"
                if (LaneDir.parseTurnLanes(v).all { it == LaneDir.NONE } && n > 1) return "turn:lanes has no arrows"
            }
            e.lanes?.let { if (it < 1 || it > MAX_LANES) return "lanes = $it" }
            if (n != null && e.lanes != null && n != e.lanes) return "turn:lanes has $n lanes but lanes = ${e.lanes}"
            e.changeLanes?.let { v ->
                val parts = v.split('|')
                if (parts.any { it.trim() !in CHANGE_TOKENS }) return "unknown change:lanes value"
                val count = e.lanes ?: n
                if (count != null && parts.size != count) return "change:lanes has ${parts.size} lanes, expected $count"
            }
            if ((e.stopLines != null || e.changeSpans.isNotEmpty() || e.pocketStart != null || e.widths != null) && e.turnLanes == null)
                return "stop lines, change spans, pocket start and widths need turn:lanes (the lanes they belong to)"
            val count = e.lanes ?: n
            e.stopLines?.let { s ->
                if (s.size != count) return "stop_line:lanes has ${s.size} lanes, expected $count"
                if (s.any { it < 0 || it > MAX_STOP }) return "stop_line:lanes outside 0…$MAX_STOP m"
            }
            e.widths?.let { w ->
                if (w.size != count) return "width:lanes has ${w.size} lanes, expected $count"
                if (w.any { it < 2.0 || it > 6.0 }) return "width:lanes outside 2…6 m"
            }
            for (s in e.changeSpans) {
                if (s.fromStop < 0 || s.toStop <= s.fromStop || s.toStop > MAX_SPAN) return "change span ${s.fromStop}…${s.toStop} m is not 0 ≤ from < to ≤ $MAX_SPAN"
                val parts = s.value.split('|')
                if (parts.any { it.trim() !in CHANGE_TOKENS }) return "unknown change:lanes value in change_spans"
                if (parts.size != count) return "change span has ${parts.size} lanes, expected $count"
            }
            e.changeSpans.sortedBy { it.fromStop }.zipWithNext().firstOrNull { (a, b) -> b.fromStop < a.toStop }
                ?.let { return "change spans overlap" }
            e.pocketStart?.let { p ->
                if (p <= 0 || p > MAX_SPAN) return "pocket_start_m outside 0…$MAX_SPAN m"
                if (e.stopLines != null && e.stopLines.max() - e.frontStop >= p) return "a stop line lies before pocket_start_m"
            }
            return null
        }

        const val MAX_LANES = 8
        /** Largest stop-line distance before the junction (m). */
        const val MAX_STOP = 60.0
        /** Longest change span / pocket counted from the stop line (m). */
        const val MAX_SPAN = 1000.0

        /** Parses the GeoJSON file; invalid or duplicate entries end up in [rejected] with the reason. */
        fun parse(json: String): LaneOverrides {
            val root = Json.parseToJsonElement(json).jsonObject
            val version = root["lanewise_overrides"]?.let { (it as? JsonPrimitive)?.intOrNull }
            require(version == 1 || version == 2) { "unsupported lane_overrides version" }
            val ok = ArrayList<Entry>()
            val rejected = ArrayList<String>()
            val keys = HashSet<String>()
            for ((i, f) in root["features"]?.jsonArray.orEmpty().withIndex()) {
                val p = (f as? JsonObject)?.get("properties") as? JsonObject
                if (p == null) { rejected += "feature $i: no properties"; continue }
                fun s(k: String) = (p[k] as? JsonPrimitive)?.contentOrNull?.trim()
                fun l(k: String) = (p[k] as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() }
                fun n(k: String) = (p[k] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }
                val coords = ((f["geometry"] as? JsonObject)?.get("coordinates"))?.let { runCatching { it.jsonArray }.getOrNull() }
                val pos = coords?.takeIf { it.size >= 2 }?.let {
                    val lon = (it[0] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
                    val lat = (it[1] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
                    if (lat != null && lon != null) LatLon(lat, lon) else null
                }
                val e = Entry(
                    id = s("id").orEmpty(),
                    wayId = l("osm_way"), nodeId = l("osm_node"), fromWay = l("from_way"),
                    direction = s("direction"),
                    turnLanes = s("turn:lanes"),
                    lanes = n("lanes") ?: n("effective_lanes"),
                    changeLanes = s("change:lanes"),
                    lanesUnknown = (p["lanes_unknown"] as? JsonPrimitive)?.booleanOrNull == true,
                    toWay = l("to_way"), connectivity = s("connectivity"),
                    osmWas = (p["osm_was"] as? JsonObject)?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }?.toMap().orEmpty(),
                    source = s("source").orEmpty(),
                    position = pos,
                    // Unreadable numbers become -1 so the checks below reject the entry instead of skipping the value.
                    stopLines = s("stop_line:lanes")?.let { v -> v.split('|').map { it.trim().toDoubleOrNull() ?: -1.0 } },
                    changeSpans = (p["change_spans"] as? JsonArray).orEmpty().map { el ->
                        val o = el as? JsonObject
                        fun d(k: String) = (o?.get(k) as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() ?: -1.0
                        Span(d("from_stop_m"), d("to_stop_m"), (o?.get("change:lanes") as? JsonPrimitive)?.contentOrNull.orEmpty())
                    },
                    pocketStart = (p["pocket_start_m"] as? JsonPrimitive)?.let { it.contentOrNull?.toDoubleOrNull() ?: -1.0 },
                    widths = s("width:lanes")?.let { v -> v.split('|').map { it.trim().toDoubleOrNull() ?: -1.0 } },
                    sourcePlan = p["source_plan"]?.let { sp ->
                        (sp as? JsonObject)?.entries?.joinToString(", ") { (k, v) -> "$k ${(v as? JsonPrimitive)?.contentOrNull ?: v}" }
                            ?: (sp as? JsonPrimitive)?.contentOrNull
                    },
                    builtConfirmed = s("built_confirmed"),
                    draft = (p["draft"] as? JsonPrimitive)?.booleanOrNull == true,
                    version = version!!,
                )
                val why = problem(e)
                val key = when {
                    e.connectivity != null -> "c${e.nodeId}:${e.fromWay}:${e.toWay}"
                    e.wayId != null -> "w${e.wayId}:${e.direction}"
                    else -> "n${e.nodeId}:${e.fromWay}"
                }
                when {
                    why != null -> rejected += "${e.id.ifBlank { "feature $i" }}: $why"
                    !keys.add(key) -> rejected += "${e.id}: second patch for the same ${if (e.wayId != null) "way and direction" else "junction"}"
                    else -> ok += e
                }
            }
            return LaneOverrides(ok, rejected)
        }

        /** [base] (the shipped asset) with [local] (the file on the phone) on top: same `id` → the local entry. */
        fun merge(base: LaneOverrides, local: LaneOverrides): LaneOverrides {
            val ids = local.entries.map { it.id }.toSet()
            return LaneOverrides(base.entries.filter { it.id !in ids } + local.entries, base.rejected + local.rejected)
        }
    }
}
