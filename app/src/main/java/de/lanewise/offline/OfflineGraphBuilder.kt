package de.lanewise.offline

import android.util.JsonReader
import android.util.JsonToken
import de.lanewise.core.geo.Geo
import de.lanewise.core.geo.LatLon
import de.lanewise.core.net.Http
import de.lanewise.osm.OsmTags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.util.Locale

/**
 * Downloads the drivable road network of a region from the Overpass API (fresh OSM data) and
 * converts it into an [OfflineGraph]. Runs once while online; afterwards routing works with GNSS only.
 */
class OfflineGraphBuilder(private val overpassUrl: String = OVERPASS_URL) {

    data class Region(val id: String, val name: String, val south: Double, val west: Double, val north: Double, val east: Double)

    suspend fun build(region: Region, target: File, progress: (String) -> Unit): OfflineGraph = withContext(Dispatchers.IO) {
        val bbox = String.format(Locale.US, "%.4f,%.4f,%.4f,%.4f", region.south, region.west, region.north, region.east)
        val classes = OsmTags.HIGHWAY_CLASSES.joinToString("|")
        val query = """
            [out:json][timeout:600][maxsize:1073741824];
            way["highway"~"^($classes)$"]["access"!~"^(no|private|delivery|agricultural|forestry)$"]["motor_vehicle"!~"^(no|private)$"]($bbox);
            out body geom qt;
        """.trimIndent()

        progress("Lade Straßennetz von OpenStreetMap …")
        val req = Request.Builder().url(overpassUrl).post(FormBody.Builder().add("data", query).build()).build()
        val acc = Accumulator()
        Http.client.newBuilder().readTimeout(15, java.util.concurrent.TimeUnit.MINUTES).build()
            .newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("Overpass HTTP ${resp.code}")
                val body = resp.body ?: throw IOException("Overpass: empty body")
                JsonReader(InputStreamReader(body.byteStream(), Charsets.UTF_8)).use { r ->
                    r.beginObject()
                    while (r.hasNext()) {
                        if (r.nextName() == "elements") {
                            r.beginArray()
                            while (r.hasNext()) {
                                readWay(r, acc)
                                if (acc.wayCount % 5000 == 0) {
                                    coroutineContext.ensureActive()
                                    progress("${acc.wayCount} Straßenabschnitte gelesen …")
                                }
                            }
                            r.endArray()
                        } else r.skipValue()
                    }
                    r.endObject()
                }
            }
        progress("Baue Routing-Graph (${acc.wayCount} Wege, ${acc.nodeCount} Knoten) …")
        val graph = acc.toGraph(doubleArrayOf(region.south, region.west, region.north, region.east))
        progress("Speichere …")
        target.parentFile?.mkdirs()
        graph.save(target)
        graph
    }

    private fun readWay(r: JsonReader, acc: Accumulator) {
        var type = ""
        val nodeIds = ArrayList<Long>()
        val geom = ArrayList<LatLon>()
        val tags = HashMap<String, String>()
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "type" -> type = r.nextString()
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
        if (type == "way" && nodeIds.size >= 2 && nodeIds.size == geom.size) acc.addWay(nodeIds, geom, tags)
    }

    /** Growable primitive buffers so a city-sized network fits comfortably in memory. */
    private class Accumulator {
        val nodeIndex = HashMap<Long, Int>(1 shl 18)
        val lat = IntList(); val lon = IntList()
        val eFrom = IntList(); val eTo = IntList(); val eLen = IntList(); val eWay = IntList(); val eFwd = ByteList()
        val wHw = ByteList(); val wMaxF = IntList(); val wMaxB = IntList(); val wName = IntList(); val wRef = IntList()
        val wTurnF = IntList(); val wTurnB = IntList(); val wFirst = IntList(); val wLast = IntList()
        val strings = ArrayList<String>(); val stringIndex = HashMap<String, Int>()
        var wayCount = 0
        val nodeCount get() = lat.size

        fun intern(s: String?): Int {
            if (s.isNullOrBlank()) return -1
            return stringIndex.getOrPut(s) { strings.add(s); strings.size - 1 }
        }

        fun node(id: Long, p: LatLon): Int = nodeIndex.getOrPut(id) {
            lat.add((p.lat * 1e7).toInt()); lon.add((p.lon * 1e7).toInt()); lat.size - 1
        }

        fun addWay(ids: List<Long>, geom: List<LatLon>, tags: Map<String, String>) {
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

            val w = wHw.size
            wHw.add(code.toByte()); wMaxF.add(maxF); wMaxB.add(maxB)
            wName.add(intern(tags["name"])); wRef.add(intern(tags["ref"]))
            wTurnF.add(intern(turnF)); wTurnB.add(intern(turnB))

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
                    wTurnF.toArray(), wTurnB.toArray(), wFirst.toArray(), wLast.toArray(), strings.toTypedArray(),
                ),
                bbox, System.currentTimeMillis(),
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
        const val OVERPASS_URL = "https://overpass-api.de/api/interpreter"

        val REGIONS = listOf(
            Region("hannover", "Region Hannover", 52.20, 9.45, 52.58, 10.10),
            Region("hannover_city", "Hannover (Stadt)", 52.30, 9.60, 52.46, 9.92),
            Region("braunschweig", "Braunschweig", 52.20, 10.40, 52.34, 10.65),
            Region("niedersachsen_a2_a7", "Autobahnkreuz Hannover (A2/A7/A37/A352)", 52.25, 9.55, 52.55, 10.05),
        )
    }
}
