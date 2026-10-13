package de.lanewise.traffic

import android.util.Xml
import de.lanewise.core.geo.LatLon
import de.lanewise.core.model.IncidentKind
import de.lanewise.core.model.TrafficIncident
import de.lanewise.core.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.KeyStore
import java.util.zip.GZIPInputStream
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Mobilithek (Nationaler Zugangspunkt Mobilität) DATEX II feeds — e.g. the traffic messages of the
 * Verkehrsmanagementzentrale Niedersachsen (VMZ NI) for Hannover.
 *
 * Mobilithek subscriptions are delivered by URL and usually require a client certificate (PKCS#12)
 * issued when you subscribe. Import the .p12 in Settings; the password is entered by you in the app.
 * Parses DATEX II v2 and v3 `situationRecord`s generically (type + point coordinates + comment).
 */
class MobilithekDatexSource(
    private val feedUrl: String,
    private val certFile: File?,
    private val certPassword: String,
) : TrafficSource {
    override val id = "mobilithek"

    override suspend fun incidents(box: BBox, roads: Set<String>): List<TrafficIncident> = withContext(Dispatchers.IO) {
        if (feedUrl.isBlank()) return@withContext emptyList()
        val client = clientWithCertificate()
        client.newCall(Request.Builder().url(feedUrl).header("Accept-Encoding", "gzip").build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Mobilithek HTTP ${resp.code}")
            val raw = resp.body?.byteStream() ?: return@withContext emptyList()
            val stream = if (resp.header("Content-Encoding") == "gzip") GZIPInputStream(raw) else raw
            parse(stream).filter { box.contains(it.location) }
        }
    }

    private fun clientWithCertificate(): OkHttpClient {
        val cert = certFile?.takeIf { it.exists() } ?: return Http.client
        val ks = KeyStore.getInstance("PKCS12")
        cert.inputStream().use { ks.load(it, certPassword.toCharArray()) }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, certPassword.toCharArray()) }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(null as KeyStore?) }
        val tm = tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
        val ctx = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, arrayOf(tm), null) }
        return Http.client.newBuilder().sslSocketFactory(ctx.socketFactory, tm).build()
    }

    /** Streaming DATEX II parser; tolerant to v2/v3 namespace differences by matching local names. */
    fun parse(input: InputStream): List<TrafficIncident> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(input, null)
        val out = ArrayList<TrafficIncident>()

        var inRecord = false
        var recId = ""
        var recType = ""
        var lat: Double? = null
        var lon: Double? = null
        val comment = StringBuilder()
        var inComment = false
        var text = ""

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    val name = parser.name
                    if (name == "situationRecord") {
                        inRecord = true
                        recId = parser.getAttributeValue(null, "id") ?: ""
                        recType = (0 until parser.attributeCount)
                            .firstOrNull { parser.getAttributeName(it) == "type" }
                            ?.let { parser.getAttributeValue(it).substringAfter(':') } ?: ""
                        lat = null; lon = null; comment.clear()
                    }
                    if (inRecord && (name == "generalPublicComment" || name == "comment")) inComment = true
                }
                XmlPullParser.TEXT -> text = parser.text ?: ""
                XmlPullParser.END_TAG -> {
                    val name = parser.name
                    if (inRecord) {
                        when (name) {
                            "latitude" -> if (lat == null) lat = text.trim().toDoubleOrNull()
                            "longitude" -> if (lon == null) lon = text.trim().toDoubleOrNull()
                            "value" -> if (inComment && text.isNotBlank()) comment.append(text.trim()).append(' ')
                            "generalPublicComment", "comment" -> inComment = false
                            "situationRecord" -> {
                                val la = lat; val lo = lon
                                if (la != null && lo != null) {
                                    out += TrafficIncident(
                                        id = "datex:$recId",
                                        source = "Mobilithek",
                                        kind = kindOf(recType),
                                        title = recType.replace(Regex("([a-z])([A-Z])"), "$1 $2"),
                                        description = comment.toString().trim(),
                                        location = LatLon(la, lo),
                                        blocked = recType.contains("Closure", ignoreCase = true),
                                    )
                                }
                                inRecord = false
                            }
                        }
                    }
                }
            }
        }
        return out
    }

    private fun kindOf(t: String) = when {
        t.contains("Accident", true) -> IncidentKind.ACCIDENT
        t.contains("Roadworks", true) || t.contains("MaintenanceWorks", true) || t.contains("ConstructionWorks", true) -> IncidentKind.ROADWORKS
        t.contains("AbnormalTraffic", true) -> IncidentKind.JAM
        t.contains("Closure", true) || t.contains("RoadOrCarriagewayOrLaneManagement", true) -> IncidentKind.CLOSURE
        t.contains("Weather", true) || t.contains("Conditions", true) -> IncidentKind.WEATHER
        else -> IncidentKind.OTHER
    }
}
