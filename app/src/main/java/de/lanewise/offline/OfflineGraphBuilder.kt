package de.lanewise.offline

import android.util.JsonReader
import android.util.JsonToken
import de.lanewise.core.geo.Geo
import de.lanewise.core.geo.LatLon
import de.lanewise.core.net.Overpass
import de.lanewise.osm.OsmTags
import de.lanewise.ui.i18n.Strings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.util.Locale

/**
 * Downloads the drivable road network of a region from the Overpass API (fresh OSM data) and
 * converts it into an [OfflineGraph]. Runs once while online; afterwards routing works with GNSS only.
 */
class OfflineGraphBuilder {

    data class Region(val id: String, val name: String, val south: Double, val west: Double, val north: Double, val east: Double)

    suspend fun build(region: Region, target: File, strings: Strings, progress: (String) -> Unit): OfflineGraph = withContext(Dispatchers.IO) {
        val bbox = String.format(Locale.US, "%.4f,%.4f,%.4f,%.4f", region.south, region.west, region.north, region.east)
        val classes = OsmTags.HIGHWAY_CLASSES.joinToString("|")
        val query = """
            [out:json][timeout:600][maxsize:1073741824];
            (
            way["highway"~"^($classes)$"]["access"!~"^(no|private|delivery|agricultural|forestry)$"]["motor_vehicle"!~"^(no|private)$"]($bbox);
            ${OsmTags.featureNodeQuery("($bbox)")}
            );
            out body geom qt;
        """.trimIndent()

        progress(strings.dlRoads)
        val acc = Accumulator()
        Overpass.stream(query) { resp ->
            val body = resp.body ?: throw IOException("Overpass: empty body")
            JsonReader(InputStreamReader(body.byteStream(), Charsets.UTF_8)).use { r ->
                r.beginObject()
                while (r.hasNext()) {
                    if (r.nextName() == "elements") {
                        r.beginArray()
                        var n = 0
                        while (r.hasNext()) {
                            readElement(r, acc)
                            if (++n % 5000 == 0) progress(String.format(strings.dlRead, acc.wayCount))
                        }
                        r.endArray()
                    } else r.skipValue()
                }
                r.endObject()
            }
        }
        coroutineContext.ensureActive()
        // Turn restrictions (no left turn, only straight on, …). Optional: without them routing still works.
        runCatching {
            Overpass.stream("[out:json][timeout:300];rel[\"type\"=\"restriction\"]($bbox);out body qt;") { resp ->
                val body = resp.body ?: throw IOException("Overpass: empty body")
                JsonReader(InputStreamReader(body.byteStream(), Charsets.UTF_8)).use { r ->
                    r.beginObject()
                    while (r.hasNext()) {
                        if (r.nextName() == "elements") {
                            r.beginArray(); while (r.hasNext()) readRestriction(r, acc); r.endArray()
                        } else r.skipValue()
                    }
                    r.endObject()
                }
            }
        }
        coroutineContext.ensureActive()
        progress(strings.dlBuild)
        val graph = acc.toGraph(doubleArrayOf(region.south, region.west, region.north, region.east))
        progress(strings.dlSave)
        target.parentFile?.mkdirs()
        graph.save(target)
        graph
    }

    /** One `type=restriction` relation with from way, via node and to way. */
    private fun readRestriction(r: JsonReader, acc: Accumulator) {
        var from = -1L; var via = -1L; var to = -1L
        var viaIsWay = false
        val tags = HashMap<String, String>()
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "members" -> {
                    r.beginArray()
                    while (r.hasNext()) {
                        var type = ""; var ref = -1L; var role = ""
                        r.beginObject()
                        while (r.hasNext()) when (r.nextName()) {
                            "type" -> type = r.nextString()
                            "ref" -> ref = r.nextLong()
                            "role" -> role = r.nextString()
                            else -> r.skipValue()
                        }
                        r.endObject()
                        when (role) {
                            "from" -> if (type == "way") from = ref
                            "to" -> if (type == "way") to = ref
                            "via" -> if (type == "node") via = ref else viaIsWay = true
                        }
                    }
                    r.endArray()
                }
                "tags" -> { r.beginObject(); while (r.hasNext()) tags[r.nextName()] = r.nextString(); r.endObject() }
                else -> r.skipValue()
            }
        }
        r.endObject()
        if (viaIsWay || from < 0 || via < 0 || to < 0) return
        val kind = tags["restriction:motorcar"] ?: tags["restriction"] ?: return
        if (tags["except"]?.split(';')?.any { it.trim() == "motorcar" } == true) return
        val only = when {
            kind.startsWith("no_") -> false
            kind.startsWith("only_") -> true
            else -> return
        }
        acc.addRestriction(from, via, to, only)
    }

    private fun readElement(r: JsonReader, acc: Accumulator) {
        var type = ""
        var id = -1L
        var lat = Double.NaN
        var lon = Double.NaN
        val nodeIds = ArrayList<Long>()
        val geom = ArrayList<LatLon>()
        val tags = HashMap<String, String>()
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "type" -> type = r.nextString()
                "id" -> id = r.nextLong()
                "lat" -> lat = r.nextDouble()
                "lon" -> lon = r.nextDouble()
                "nodes" -> { r.beginArray(); while (r.hasNext()) nodeIds += r.nextLong(); r.endArray() }
                "geometry" -> {
                    r.beginArray()
                    while (r.hasNext()) {
                        if (r.peek() == JsonToken.NULL) { r.nextNull(); geom += LatLon(Double.NaN, Double.NaN); continue }
                        var lat = Double.NaN; var lon = Double.NaN
                        r.beginObject()
                        while (r.hasNext()) when (r.nextName()) {
                            "lat" -> lat = r.nextDouble()
                            "lon" -> lon = r.nextDouble()
                            else -> r.skipValue()
                        }
                        r.endObject()
                        geom += LatLon(lat, lon)
                    }
                    r.endArray()
                }
                "tags" -> { r.beginObject(); while (r.hasNext()) tags[r.nextName()] = r.nextString(); r.endObject() }
                else -> r.skipValue()
            }
        }
        r.endObject()
        if (type == "way" && nodeIds.size >= 2 && nodeIds.size == geom.size) acc.addWay(id, nodeIds, geom, tags)
        if (type == "node" && !lat.isNaN() && !lon.isNaN()) OsmTags.feature(tags, LatLon(lat, lon), 0.0)?.let { acc.addFeature(lat, lon, it.encode()) }
    }

    /** Growable primitive buffers so a city-sized network fits comfortably in memory. */
    private class Accumulator {
        val nodeIndex = HashMap<Long, Int>(1 shl 18)
        val lat = IntList(); val lon = IntList()
        val eFrom = IntList(); val eTo = IntList(); val eLen = IntList(); val eWay = IntList(); val eFwd = ByteList()
        val wHw = ByteList(); val wMaxF = IntList(); val wMaxB = IntList(); val wName = IntList(); val wRef = IntList()
        val wTurnF = IntList(); val wTurnB = IntList(); val wFirst = IntList(); val wLast = IntList()
        val wChangeF = IntList(); val wChangeB = IntList()
        val strings = ArrayList<String>(); val stringIndex = HashMap<String, Int>()
        val wBike = ByteList(); val fLat = IntList(); val fLon = IntList(); val fKind = ByteList()

        fun addFeature(lat: Double, lon: Double, kind: Int) {
            fLat.add((lat * 1e7).toInt()); fLon.add((lon * 1e7).toInt()); fKind.add(kind.toByte())
        }
        var wayCount = 0
        val nodeCount get() = lat.size

        fun intern(s: String?): Int {
            if (s.isNullOrBlank()) return -1
            return stringIndex.getOrPut(s) { strings.add(s); strings.size - 1 }
        }

        fun node(id: Long, p: LatLon): Int = nodeIndex.getOrPut(id) {
            lat.add((p.lat * 1e7).toInt()); lon.add((p.lon * 1e7).toInt()); lat.size - 1
        }

        val wayIndex = HashMap<Long, Int>(1 shl 16)
        val rFrom = IntList(); val rVia = IntList(); val rTo = IntList(); val rOnly = ByteList()

        /** Keeps restrictions whose ways and via node are part of the drivable graph. */
        fun addRestriction(fromWay: Long, viaNode: Long, toWay: Long, only: Boolean) {
            val f = wayIndex[fromWay] ?: return
            val t = wayIndex[toWay] ?: return
            val v = nodeIndex[viaNode] ?: return
            rFrom.add(f); rVia.add(v); rTo.add(t); rOnly.add(if (only) 1 else 0)
        }

        fun addWay(osmId: Long, ids: List<Long>, geom: List<LatLon>, tags: Map<String, String>) {
            if (geom.any { it.lat.isNaN() }) return
            val hw = tags["highway"] ?: return
            val code = OsmTags.classCode(hw).takeIf { it >= 0 } ?: return
            val oneway = when (tags["oneway"]) {
                "yes", "1", "true" -> 1
                "-1", "reverse" -> -1
                "no" -> 0
                else -> if (hw == "motorway" || hw == "motorway_link" || tags["junction"] == "roundabout") 1 else 0
            }
            val maxAll = OsmTags.parseMaxspeed(tags["maxspeed"])
            val maxF = OsmTags.parseMaxspeed(tags["maxspeed:forward"]).takeIf { it != OsmTags.UNKNOWN } ?: maxAll
            val maxB = OsmTags.parseMaxspeed(tags["maxspeed:backward"]).takeIf { it != OsmTags.UNKNOWN } ?: maxAll
            val turnF = tags["turn:lanes:forward"] ?: if (oneway != 0) tags["turn:lanes"] else null
            val turnB = tags["turn:lanes:backward"] ?: if (oneway == -1) tags["turn:lanes"] else null
            val changeF = tags["change:lanes:forward"] ?: if (oneway == 1) tags["change:lanes"] else null
            val changeB = tags["change:lanes:backward"] ?: if (oneway == -1) tags["change:lanes"] else null

            val w = wHw.size
            if (osmId >= 0) wayIndex[osmId] = w
            wHw.add(code.toByte()); wMaxF.add(maxF); wMaxB.add(maxB)
            wName.add(intern(tags["name"])); wRef.add(intern(tags["ref"]))
            wTurnF.add(intern(turnF)); wTurnB.add(intern(turnB))
            wChangeF.add(intern(changeF)); wChangeB.add(intern(changeB))
            var bike = 0
            OsmTags.bikeLaneRight(tags, forward = true, oneway = oneway != 0)?.let { bike = bike or 1 or (if (it) 2 else 0) }
            if (oneway == 0) OsmTags.bikeLaneRight(tags, forward = false, oneway = false)?.let { bike = bike or 4 or (if (it) 8 else 0) }
            wBike.add(bike.toByte())

            val nodes = IntArray(ids.size) { node(ids[it], geom[it]) }
            wFirst.add(nodes.first()); wLast.add(nodes.last())
            for (i in 0 until nodes.size - 1) {
                val a = nodes[i]; val b = nodes[i + 1]
                val len = (Geo.distance(geom[i], geom[i + 1]) * 10).toInt().coerceAtLeast(1)
                if (oneway >= 0) { eFrom.add(a); eTo.add(b); eLen.add(len); eWay.add(w); eFwd.add(1) }
                if (oneway <= 0) { eFrom.add(b); eTo.add(a); eLen.add(len); eWay.add(w); eFwd.add(0) }
            }
            wayCount++
        }

        fun toGraph(bbox: DoubleArray): OfflineGraph {
            val n = lat.size
            val m = eFrom.size
            val start = IntArray(n + 1)
            for (i in 0 until m) start[eFrom[i] + 1]++
            for (i in 0 until n) start[i + 1] += start[i]
            val fill = start.copyOf()
            val to = IntArray(m); val len = IntArray(m); val way = IntArray(m); val fwd = ByteArray(m)
            for (i in 0 until m) {
                val pos = fill[eFrom[i]]++
                to[pos] = eTo[i]; len[pos] = eLen[i]; way[pos] = eWay[i]; fwd[pos] = eFwd[i]
            }
            return OfflineGraph(
                lat.toArray(), lon.toArray(), start, to, len, way, fwd,
                OfflineGraph.Ways(
                    wHw.toArray(), wMaxF.toArray(), wMaxB.toArray(), wName.toArray(), wRef.toArray(),
                    wTurnF.toArray(), wTurnB.toArray(), wFirst.toArray(), wLast.toArray(), strings.toTypedArray(), wBike.toArray(),
                    wChangeF.toArray(), wChangeB.toArray(),
                ),
                bbox, System.currentTimeMillis(), fLat.toArray(), fLon.toArray(), fKind.toArray(),
                OfflineGraph.TurnRestrictions(rFrom.toArray(), rVia.toArray(), rTo.toArray(), rOnly.toArray()),
            )
        }
    }

    private class IntList {
        private var a = IntArray(1024)
        var size = 0; private set
        fun add(v: Int) { if (size == a.size) a = a.copyOf(size * 2); a[size++] = v }
        operator fun get(i: Int) = a[i]
        fun toArray() = a.copyOf(size)
    }

    private class ByteList {
        private var a = ByteArray(1024)
        var size = 0; private set
        fun add(v: Byte) { if (size == a.size) a = a.copyOf(size * 2); a[size++] = v }
        operator fun get(i: Int) = a[i]
        fun toArray() = a.copyOf(size)
    }

    companion object {

        val REGIONS = listOf(
            Region("hannover", "Region Hannover", 52.20, 9.45, 52.58, 10.10),
            Region("hannover_city", "Hannover (Stadt)", 52.30, 9.60, 52.46, 9.92),
            Region("braunschweig", "Braunschweig", 52.20, 10.40, 52.34, 10.65),
            Region("niedersachsen_a2_a7", "Autobahnkreuz Hannover (A2/A7/A37/A352)", 52.25, 9.55, 52.55, 10.05),
        )
    }
}
