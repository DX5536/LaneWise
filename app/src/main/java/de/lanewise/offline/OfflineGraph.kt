package de.lanewise.offline

import de.lanewise.core.geo.Geo
import de.lanewise.core.geo.LatLon
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.cos
import kotlin.math.floor

/**
 * Compact, immutable road graph (CSR adjacency) built from OpenStreetMap for offline routing.
 * Coordinates are stored as 1e-7 degree integers, edge lengths in decimetres.
 */
class OfflineGraph(
    val nodeLat: IntArray,
    val nodeLon: IntArray,
    /** CSR: edges of node n are edgeStart[n] until edgeStart[n+1]. */
    val edgeStart: IntArray,
    val edgeTo: IntArray,
    val edgeLenDm: IntArray,
    val edgeWay: IntArray,
    /** 1 if the edge follows the OSM way direction, 0 if against it. */
    val edgeForward: ByteArray,
    val ways: Ways,
    val bbox: DoubleArray, // south, west, north, east
    val createdAt: Long,
) {
    class Ways(
        val highway: ByteArray,
        /** km/h; [de.lanewise.osm.OsmTags.NO_LIMIT] = none, UNKNOWN = -1. */
        val maxForward: IntArray,
        val maxBackward: IntArray,
        val name: IntArray,
        val ref: IntArray,
        val turnLanesForward: IntArray,
        val turnLanesBackward: IntArray,
        val firstNode: IntArray,
        val lastNode: IntArray,
        val strings: Array<String>,
    ) {
        val size get() = highway.size
        fun str(i: Int): String = if (i < 0) "" else strings[i]
    }

    val nodeCount get() = nodeLat.size
    val edgeCount get() = edgeTo.size

    fun latLon(n: Int) = LatLon(nodeLat[n] / 1e7, nodeLon[n] / 1e7)

    // --- spatial index for nearest-node lookup ------------------------------------------------
    private val cellDeg = 0.005
    private val grid: HashMap<Long, IntArray> by lazy {
        val tmp = HashMap<Long, MutableList<Int>>()
        for (n in 0 until nodeCount) {
            if (edgeStart[n + 1] == edgeStart[n] && !hasIncoming[n]) continue
            tmp.getOrPut(cellKey(nodeLat[n] / 1e7, nodeLon[n] / 1e7)) { ArrayList() }.add(n)
        }
        HashMap<Long, IntArray>(tmp.size).also { m -> tmp.forEach { (k, v) -> m[k] = v.toIntArray() } }
    }
    private val hasIncoming: BooleanArray by lazy {
        BooleanArray(nodeCount).also { for (t in edgeTo) it[t] = true }
    }

    private fun cellKey(lat: Double, lon: Double): Long {
        val y = floor(lat / cellDeg).toLong()
        val x = floor(lon / cellDeg).toLong()
        return (y shl 32) or (x and 0xffffffffL)
    }

    /** Nearest routable node within ~1 km, or -1. Only nodes with outgoing edges are returned. */
    fun nearestNode(p: LatLon, requireOutgoing: Boolean = true): Int {
        val cy = floor(p.lat / cellDeg).toLong()
        val cx = floor(p.lon / cellDeg).toLong()
        var best = -1
        var bestD = Double.MAX_VALUE
        val kx = cos(Geo.toRad(p.lat))
        for (r in 0..3) {
            for (dy in -r..r) for (dx in -r..r) {
                if (maxOf(kotlin.math.abs(dy), kotlin.math.abs(dx)) != r) continue
                val cell = grid[((cy + dy) shl 32) or ((cx + dx) and 0xffffffffL)] ?: continue
                for (n in cell) {
                    if (requireOutgoing && edgeStart[n + 1] == edgeStart[n]) continue
                    val dLat = nodeLat[n] / 1e7 - p.lat
                    val dLon = (nodeLon[n] / 1e7 - p.lon) * kx
                    val d = dLat * dLat + dLon * dLon
                    if (d < bestD) { bestD = d; best = n }
                }
            }
            if (best >= 0 && r >= 1) break
        }
        return best
    }

    fun contains(p: LatLon) = p.lat in bbox[0]..bbox[2] && p.lon in bbox[1]..bbox[3]

    // --- persistence ---------------------------------------------------------------------------
    fun save(file: File) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        DataOutputStream(BufferedOutputStream(GZIPOutputStream(tmp.outputStream()), 1 shl 16)).use { out ->
            out.writeInt(MAGIC)
            out.writeLong(createdAt)
            bbox.forEach { out.writeDouble(it) }
            out.writeIntArr(nodeLat); out.writeIntArr(nodeLon)
            out.writeIntArr(edgeStart); out.writeIntArr(edgeTo); out.writeIntArr(edgeLenDm); out.writeIntArr(edgeWay)
            out.writeInt(edgeForward.size); out.write(edgeForward)
            out.writeInt(ways.highway.size); out.write(ways.highway)
            out.writeIntArr(ways.maxForward); out.writeIntArr(ways.maxBackward)
            out.writeIntArr(ways.name); out.writeIntArr(ways.ref)
            out.writeIntArr(ways.turnLanesForward); out.writeIntArr(ways.turnLanesBackward)
            out.writeIntArr(ways.firstNode); out.writeIntArr(ways.lastNode)
            out.writeInt(ways.strings.size); ways.strings.forEach { out.writeUTF(it) }
        }
        if (file.exists()) file.delete()
        tmp.renameTo(file)
    }

    companion object {
        private const val MAGIC = 0x4C574731 // "LWG1"

        fun load(file: File): OfflineGraph =
            DataInputStream(BufferedInputStream(GZIPInputStream(file.inputStream()), 1 shl 16)).use { inp ->
                require(inp.readInt() == MAGIC) { "Not a LaneWise graph file" }
                val created = inp.readLong()
                val bbox = DoubleArray(4) { inp.readDouble() }
                val lat = inp.readIntArr(); val lon = inp.readIntArr()
                val es = inp.readIntArr(); val et = inp.readIntArr(); val el = inp.readIntArr(); val ew = inp.readIntArr()
                val ef = ByteArray(inp.readInt()).also { inp.readFully(it) }
                val hw = ByteArray(inp.readInt()).also { inp.readFully(it) }
                val mf = inp.readIntArr(); val mb = inp.readIntArr()
                val nm = inp.readIntArr(); val rf = inp.readIntArr()
                val tf = inp.readIntArr(); val tb = inp.readIntArr()
                val fn = inp.readIntArr(); val ln = inp.readIntArr()
                val strings = Array(inp.readInt()) { inp.readUTF() }
                OfflineGraph(lat, lon, es, et, el, ew, ef, Ways(hw, mf, mb, nm, rf, tf, tb, fn, ln, strings), bbox, created)
            }

        private fun DataOutputStream.writeIntArr(a: IntArray) {
            writeInt(a.size)
            for (v in a) writeInt(v)
        }

        private fun DataInputStream.readIntArr(): IntArray {
            val n = readInt()
            return IntArray(n) { readInt() }
        }
    }
}
