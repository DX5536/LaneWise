package de.lanewise.traffic

import de.lanewise.core.geo.LatLon
import de.lanewise.core.model.IncidentKind
import de.lanewise.core.model.TrafficIncident
import de.lanewise.core.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.KeyStore
import java.time.Instant
import java.time.OffsetDateTime
import java.util.zip.GZIPInputStream
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import javax.xml.parsers.SAXParserFactory

/**
 * Mobilithek (Nationaler Zugangspunkt Mobilität) DATEX II feeds — e.g. roadworks and lane closures of the
 * Verkehrsmanagementzentrale Niedersachsen (VMZ NI) or Stadt Hannover. Pure Kotlin / JVM (SAX, KeyStore, OkHttp), so
 * the parser is unit-tested (`MobilithekClientTest`).
 *
 * The subscription's pull URL is called with the client certificate (PKCS#12) issued by Mobilithek, through
 * [Http.fetchBytes] (OkHttp's own threads, cancellable — never a blocking call on the IO pool). DATEX II v2 and v3 are
 * read by local element names, so namespaces and SOAP envelopes do not matter; gzip files are unpacked.
 *
 * Lanes (safety first): DATEX lane numbers (`lane1` …, v3 `laneNumber`) are not reliably counted from one side across
 * feeds, so only unambiguous designations become per-lane closures ([TrafficIncident.laneOpen]): `leftLane`,
 * `rightLane`, `middleLane` on three lanes, `allLanesCompleteCarriageway`, on the main carriageway with a known lane
 * count. Any other lane restriction sets [TrafficIncident.laneRestrictionUnknown]: no lane advice there.
 */
class MobilithekClient(
    private val feedUrl: String,
    private val certFile: File?,
    private val certPassword: String,
    private val now: () -> Instant = Instant::now,
) : TrafficSource {
    override val id = "mobilithek"

    override suspend fun incidents(box: BBox, roads: Set<String>): List<TrafficIncident> {
        if (feedUrl.isBlank()) return emptyList()
        val client = withContext(Dispatchers.Default) { clientFor(certFile, certPassword) }
        val (code, body) = Http.fetchBytes(Request.Builder().url(feedUrl).build(), client)
        if (code !in 200..299) throw IOException("Mobilithek HTTP $code")
        return withContext(Dispatchers.Default) {
            parse(unzip(body), now()).filter { i -> box.contains(i.location) || i.geometry.any(box::contains) }
        }
    }

    companion object {
        private var cached: Triple<String, Long, Int>? = null
        private var cachedClient: OkHttpClient? = null

        /** OkHttp client presenting the PKCS#12 client certificate; cached until the file or password changes. */
        @Synchronized
        fun clientFor(cert: File?, password: String): OkHttpClient {
            val f = cert?.takeIf { it.exists() } ?: return Http.client
            val key = Triple(f.path, f.lastModified(), password.hashCode())
            if (key == cached) cachedClient?.let { return it }
            val ks = KeyStore.getInstance("PKCS12")
            f.inputStream().use { ks.load(it, password.toCharArray()) }
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, password.toCharArray()) }
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(null as KeyStore?) }
            val tm = tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
            val ctx = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, arrayOf(tm), null) }
            return Http.client.newBuilder().sslSocketFactory(ctx.socketFactory, tm).build().also { cached = key; cachedClient = it }
        }

        /** Gzip files (magic 1f 8b) are unpacked; OkHttp already undoes `Content-Encoding: gzip`. */
        fun unzip(body: ByteArray): InputStream =
            if (body.size > 2 && body[0] == 0x1f.toByte() && body[1] == 0x8b.toByte()) GZIPInputStream(ByteArrayInputStream(body))
            else ByteArrayInputStream(body)

        /** DATEX II v2 / v3 `situationRecord`s → incidents (records without any coordinates are skipped). */
        fun parse(input: InputStream, now: Instant = Instant.now()): List<TrafficIncident> {
            val f = SAXParserFactory.newInstance().apply { isNamespaceAware = true }
            // No DTDs / external entities (XXE); not every parser knows every feature.
            for (feature in listOf("http://apache.org/xml/features/disallow-doctype-decl",
                "http://xml.org/sax/features/external-general-entities", "http://xml.org/sax/features/external-parameter-entities")) {
                runCatching { f.setFeature(feature, feature.endsWith("doctype-decl")) }
            }
            val h = Handler(now)
            f.newSAXParser().parse(InputSource(input), h)
            return h.out
        }

        private val NOT_DRIVING_LANES = setOf("hardShoulder", "verge", "emergencyLane", "layBy", "busStop", "setDownArea",
            "escapeLane", "centralReservation")

        /** Open lanes left → right from unambiguous lane names, or null if they do not say which lanes are closed. */
        fun laneOpen(lanes: List<String>, total: Int?): List<Boolean>? {
            val driving = lanes.filter { it !in NOT_DRIVING_LANES }
            if (driving.isEmpty() || total == null || total < 2 || total > 8) return null
            val open = MutableList(total) { true }
            for (l in driving) when (l) {
                "allLanesCompleteCarriageway" -> open.fill(false)
                "leftLane" -> open[0] = false
                "rightLane" -> open[total - 1] = false
                "middleLane" -> if (total == 3) open[1] = false else return null
                else -> return null
            }
            return open
        }

        private fun kindOf(type: String, management: String?) = when {
            type.contains("Accident", true) -> IncidentKind.ACCIDENT
            type.contains("Roadworks", true) || type.contains("MaintenanceWorks", true) || type.contains("ConstructionWorks", true) -> IncidentKind.ROADWORKS
            type.contains("AbnormalTraffic", true) -> IncidentKind.JAM
            type.contains("Closure", true) || management in CLOSED || type.contains("RoadOrCarriagewayOrLaneManagement", true) -> IncidentKind.CLOSURE
            type.contains("Weather", true) || type.contains("Conditions", true) -> IncidentKind.WEATHER
            else -> IncidentKind.OTHER
        }

        private val CLOSED = setOf("roadClosed", "carriagewayClosures", "roadOrCarriagewayClosed")
        private val LANE_MANAGEMENT = setOf("laneClosures", "lanesDeviated", "narrowLanes", "singleAlternateLineTraffic",
            "contraflow", "useOfSpecifiedLanesOrCarriagewaysAllowed", "useSpecifiedLanesOrCarriageways", "laneOrCarriagewayClosed")

        private fun time(s: String?): Instant? = s?.trim()?.takeIf { it.isNotEmpty() }?.let {
            runCatching { OffsetDateTime.parse(it).toInstant() }.recoverCatching { _ -> Instant.parse(it) }.getOrNull()
        }
    }

    private class Rec(val id: String, val type: String) {
        var status: String? = null
        var start: String? = null
        var end: String? = null
        var display: LatLon? = null
        var from: LatLon? = null
        var to: LatLon? = null
        val points = ArrayList<LatLon>()
        val posList = ArrayList<LatLon>()
        val lanes = ArrayList<String>()
        var laneNumbers = false
        var otherCarriageway = false
        var restricted: Int? = null
        var operational: Int? = null
        var original: Int? = null
        var management: String? = null
        val comment = StringBuilder()
    }

    private class Handler(private val now: Instant) : DefaultHandler() {
        val out = ArrayList<TrafficIncident>()
        private val path = ArrayList<String>()
        private val text = StringBuilder()
        private var rec: Rec? = null
        private var lat: Double? = null
        private var lon: Double? = null

        override fun startElement(uri: String?, local: String, qName: String?, atts: Attributes) {
            val name = local.ifEmpty { qName.orEmpty().substringAfter(':') }
            path += name
            text.setLength(0)
            if (name == "situationRecord") {
                val type = (0 until atts.length).firstOrNull { atts.getLocalName(it) == "type" || atts.getQName(it).endsWith(":type") }
                    ?.let { atts.getValue(it).substringAfter(':') }.orEmpty()
                rec = Rec(atts.getValue("id").orEmpty(), type)
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) { text.append(ch, start, length) }

        private fun inside(n: String) = path.dropLast(1).any { it == n }

        override fun endElement(uri: String?, local: String, qName: String?) {
            val name = local.ifEmpty { qName.orEmpty().substringAfter(':') }
            val t = text.toString().trim()
            val r = rec
            if (r != null) when (name) {
                "validityStatus" -> r.status = t
                "overallStartTime" -> r.start = t
                "overallEndTime" -> r.end = t
                "latitude" -> lat = t.toDoubleOrNull()
                "longitude" -> lon = t.toDoubleOrNull()
                "pointCoordinates", "locationForDisplay", "coordinatesForDisplay", "pointByCoordinates" -> {
                    val la = lat; val lo = lon
                    if (la != null && lo != null) {
                        val p = LatLon(la, lo)
                        when {
                            name == "locationForDisplay" || name == "coordinatesForDisplay" -> r.display = r.display ?: p
                            inside("from") -> r.from = r.from ?: p
                            inside("to") -> r.to = r.to ?: p
                            else -> if (r.points.lastOrNull() != p) r.points += p
                        }
                    }
                    lat = null; lon = null
                }
                "posList" -> {
                    val v = t.split(Regex("\\s+")).mapNotNull { it.toDoubleOrNull() }
                    for (k in 0 until v.size - 1 step 2) r.posList += LatLon(v[k], v[k + 1])
                }
                "carriageway" -> if (t.isNotEmpty() && t != "mainCarriageway") r.otherCarriageway = true
                "lane" -> if (t.isNotEmpty()) r.lanes += t
                "laneNumber" -> r.laneNumbers = true
                "numberOfLanesRestricted" -> r.restricted = t.toIntOrNull()
                "numberOfOperationalLanes" -> r.operational = t.toIntOrNull()
                "originalNumberOfLanes" -> r.original = t.toIntOrNull()
                "roadOrCarriagewayOrLaneManagementType" -> r.management = t
                "value" -> if (t.isNotEmpty() && r.comment.isEmpty() && (inside("generalPublicComment") || inside("comment"))) r.comment.append(t)
                "situationRecord" -> { finish(r)?.let { out += it }; rec = null }
            }
            path.removeAt(path.size - 1)
            text.setLength(0)
        }

        private fun finish(r: Rec): TrafficIncident? {
            val geometry = when {
                r.posList.size >= 2 -> r.posList
                r.from != null && r.to != null -> listOf(r.from!!, r.to!!)
                else -> r.points
            }
            val location = r.display ?: geometry.getOrNull(geometry.size / 2) ?: r.from ?: r.to ?: return null
            val start = time(r.start); val end = time(r.end)
            val active = when (r.status) {
                "suspended" -> false
                "active" -> end == null || end.isAfter(now)
                else -> (start == null || !start.isAfter(now)) && (end == null || end.isAfter(now))
            }
            val total = r.original ?: r.operational?.let { o -> r.restricted?.let { o + it } }
            val namesLanes = r.lanes.any { it !in NOT_DRIVING_LANES }
            val open = if (r.otherCarriageway || r.laneNumbers) null else laneOpen(r.lanes, total)
            val blocked = r.management in CLOSED || open?.none { it } == true
            // Lanes restricted, but not known which: by number, on a slip road, or only counted.
            val unknown = !blocked && open == null &&
                (namesLanes || r.laneNumbers || (r.restricted ?: 0) > 0 || r.management in LANE_MANAGEMENT)
            return TrafficIncident(
                id = "datex:${r.id}",
                source = "Mobilithek",
                kind = kindOf(r.type, r.management),
                title = r.type.replace(Regex("([a-z])([A-Z])"), "$1 $2"),
                description = r.comment.toString(),
                location = location,
                geometry = geometry,
                blocked = blocked,
                laneOpen = open?.takeIf { o -> o.any { !it } && o.any { it } },
                active = active,
                laneRestrictionUnknown = unknown,
            )
        }
    }
}
