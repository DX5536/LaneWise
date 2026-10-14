package de.lanewise.lane

import de.lanewise.BuildConfig
import de.lanewise.core.geo.Geo
import de.lanewise.core.geo.LatLon
import de.lanewise.core.model.Route
import de.lanewise.osm.LaneReports
import de.lanewise.routing.OsrmRoutingProvider
import de.lanewise.routing.RouteRequest
import de.lanewise.routing.TomTomRoutingProvider
import de.lanewise.speed.SpeedLimitProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * Sandbox comparison of lane data: OSRM (OpenStreetMap `turn:lanes`) vs TomTom (LANES sections) on the owner's test
 * route, checked against OSM lane counts along each route. Needs internet and a TomTom key in local.properties
 * (`TOMTOM_API_KEY=...`, git-ignored — never commit it):
 *
 *   LANEWISE_LIVE=1 gradlew testDebugUnitTest --tests '*ProviderCompareLiveTest*'
 *
 * Prints a junction-by-junction table and writes `app/build/reports/lanewise/provider_compare.html` (map with both
 * routes; markers: green = same lanes, orange = different, grey = only one provider has lanes there).
 */
class ProviderCompareLiveTest {

    private data class Junction(val pos: LatLon, val osrm: LaneSite?, val tomtom: LaneSite?, val osmCountTomTom: Int?, val osmCountOsrm: Int?)

    @Test
    fun compareOsmAndTomTomLanes() = runBlocking {
        assumeTrue(System.getenv("LANEWISE_LIVE") == "1")
        val key = BuildConfig.TOMTOM_API_KEY
        assumeTrue("TOMTOM_API_KEY missing in local.properties", key.isNotBlank())

        // Owner's test drive (Musterstraße 1, Hannover → Musterweg 3, Langenhagen).
        val req = RouteRequest(LatLon(52.3784754, 9.7340973), LatLon(52.4380884, 9.7052888), alternatives = true)
        val osrmAll = OsrmRoutingProvider().route(req)
        val ttAll = TomTomRoutingProvider(key).route(req)
        println("OSRM: " + osrmAll.joinToString { "${it.distance.toInt()} m / ${it.laneSites.size} lane sites" })
        println("TomTom: " + ttAll.joinToString { "${it.distance.toInt()} m / ${(it.duration / 60).toInt()} min / ${it.laneSites.size} lane sites" })
        val osrm = withCorridor(osrmAll[System.getenv("LANEWISE_ALT")?.toIntOrNull()?.coerceIn(0, osrmAll.lastIndex) ?: 0])
        // TomTom route most similar to the OSRM one (share of points within 30 m).
        val tomtom = withCorridor(ttAll.maxBy { r -> r.geometry.points.count { p -> (osrm.geometry.project(p)?.signedOffset?.let { kotlin.math.abs(it) } ?: 99.0) < 30 } })

        // Pair junctions by position (both providers place a site at the junction / stop line).
        fun posOf(r: Route, s: LaneSite) = r.geometry.pointAt(s.distanceAlong)
        val used = HashSet<LaneSite>()
        val rows = ArrayList<Junction>()
        for (t in tomtom.laneSites) {
            val p = posOf(tomtom, t)
            val o = osrm.laneSites.filter { it !in used }.minByOrNull { Geo.distance(posOf(osrm, it), p) }
                ?.takeIf { Geo.distance(posOf(osrm, it), p) < 35 }
            if (o != null) used += o
            rows += Junction(p, o, t, tomtom.laneCountAt(t.distanceAlong - 15), o?.let { osrm.laneCountAt(it.distanceAlong - 15) })
        }
        osrm.laneSites.filter { it !in used }.forEach { o ->
            val p = posOf(osrm, o)
            if (tomtom.geometry.project(p)?.signedOffset?.let { kotlin.math.abs(it) < 30 } == true)
                rows += Junction(p, o, null, null, osrm.laneCountAt(o.distanceAlong - 15))
        }
        rows.sortBy { r -> tomtom.geometry.project(r.pos)?.distanceAlong ?: 0.0 }

        var same = 0; var differ = 0; var onlyOsm = 0; var onlyTt = 0; var ttVsOsmCount = 0
        println("\nJunction-by-junction (lanes left → right, * = usable for the route; OSM n = lanes OSM draws 15 m before):")
        for (r in rows) {
            val o = r.osrm?.let { fmt(it) } ?: "-"
            val t = r.tomtom?.let { fmt(it) } ?: "-"
            val verdict = when {
                r.osrm == null -> { onlyTt++; "only TomTom" }
                r.tomtom == null -> { onlyOsm++; "only OSM" }
                r.osrm.lanes == r.tomtom.lanes -> { same++; "same" }
                else -> { differ++; "DIFFERENT" }
            }
            val countNote = if (r.tomtom != null && r.osmCountTomTom != null && r.osmCountTomTom != r.tomtom.laneCount &&
                r.osmCountTomTom < r.tomtom.laneCount - r.tomtom.leftPockets() - r.tomtom.rightPockets()) {
                ttVsOsmCount++; "  ! TomTom ${r.tomtom.laneCount} lanes, OSM road ${r.osmCountTomTom}"
            } else ""
            println(String.format(Locale.US, "  %.5f,%.5f  %-12s OSM %-40s TomTom %-40s%s", r.pos.lat, r.pos.lon, verdict, o, t, countNote))
        }
        println("\nsame $same, different $differ, only OSM $onlyOsm, only TomTom $onlyTt; TomTom lane count wider than the OSM road: $ttVsOsmCount")

        // Lane advice the driver would get before each turn, per provider.
        println("\nAdvice ~60 m before each turn:")
        for ((name, route) in listOf("OSM/OSRM" to osrm, "TomTom" to tomtom)) {
            val g = LaneGuidance()
            for (st in route.steps.filter { it.maneuver.modifier != de.lanewise.core.model.Modifier.STRAIGHT &&
                it.maneuver.type != de.lanewise.core.model.ManeuverType.DEPART && it.maneuver.type != de.lanewise.core.model.ManeuverType.ARRIVE }) {
                val s = g.update(route.laneSites, st.distanceAlong - 60, true, true, route.laneChanges,
                    route::laneCountAt.takeIf { route.laneCounts.isNotEmpty() })
                val a = s.nextAdvice
                println("  $name  ${st.maneuver.modifier} ${st.name.ifBlank { "-" }}: " +
                    (a?.let { "lanes ${fmt(it.site)} → lane ${it.recommended + 1}" + (s.pendingChange?.let { c -> ", change ${c.lanes}" } ?: "") } ?: "no lane data"))
            }
        }
        // Auto mode: the OSM route rebuilt by TomTom with live traffic and its lane separators.
        val auto = TomTomRoutingProvider(key).withTraffic(osrm)
        if (auto == null) println("\nAuto (OSM + TomTom traffic): TomTom could not rebuild the route")
        else {
            println("\nAuto (OSM + TomTom traffic): ${(osrm.duration / 60).toInt()} min OSRM → ${(auto.duration / 60).toInt()} min with traffic; " +
                "${auto.tomtomLaneLines.size} TomTom solid-line spans, ${osrm.laneChanges.size} OSM change:lanes spans, " +
                "${auto.laneChanges.size} merged")
            auto.tomtomLaneLines.forEach { println(String.format(Locale.US, "  TomTom lines %.0f–%.0f m: %s", it.from, it.to, it.perms)) }
        }
        writeHtml(osrm, tomtom, rows)
    }

    private suspend fun withCorridor(r: Route): Route {
        val c = runCatching { SpeedLimitProvider().corridor(r.geometry, 0.0, r.distance) }
            .onFailure { println("OSM corridor for ${r.provider} unavailable (${it.message})") }.getOrNull() ?: return r
        return r.copy(laneChanges = c.laneChanges, bikeLanes = c.bikeLanes).withLaneCounts(c.laneCounts)
    }

    private fun fmt(s: LaneSite): String {
        val allowed = s.allowed()
        return LaneReports.turnLanes(s.lanes).split('|').mapIndexed { i, l -> if (allowed[i]) "$l*" else l }.joinToString("|")
    }

    private fun writeHtml(osrm: Route, tomtom: Route, rows: List<Junction>) {
        fun line(r: Route) = r.geometry.points.joinToString(",", "[", "]") { String.format(Locale.US, "[%.6f,%.6f]", it.lat, it.lon) }
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;")
        val markers = rows.joinToString(",\n") { r ->
            val color = when {
                r.osrm == null || r.tomtom == null -> "#888888"
                r.osrm.lanes == r.tomtom.lanes -> "#2e9d4f"
                else -> "#e8890c"
            }
            val text = "OSM: ${r.osrm?.let { fmt(it) } ?: "-"}<br>TomTom: ${r.tomtom?.let { fmt(it) } ?: "-"}" +
                (r.osmCountTomTom?.let { "<br>OSM lanes before: $it" } ?: "")
            String.format(Locale.US, "[%.6f,%.6f,\"%s\",\"%s\"]", r.pos.lat, r.pos.lon, color, esc(text))
        }
        val html = """<!doctype html><html><head><meta charset="utf-8"><title>LaneWise: OSM vs TomTom lanes</title>
<meta name="viewport" content="width=device-width,initial-scale=1">
<link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.9.4/leaflet.min.css">
<script src="https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.9.4/leaflet.min.js"></script>
<style>html,body,#m{height:100%;margin:0;font-family:sans-serif}#k{position:absolute;z-index:999;top:10px;right:10px;background:#fff;padding:8px 10px;border-radius:8px;font-size:13px;box-shadow:0 1px 4px #0004}</style>
</head><body><div id="m"></div><div id="k"><b style="color:#6C4CF5">━</b> OSRM/OSM ${osrm.distance.toInt()} m &nbsp; <b style="color:#d33">━</b> TomTom ${tomtom.distance.toInt()} m<br>
<span style="color:#2e9d4f">●</span> same lanes <span style="color:#e8890c">●</span> different <span style="color:#888">●</span> one provider only</div>
<script>
const m=L.map('m');L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:19,attribution:'© OpenStreetMap'}).addTo(m);
const a=L.polyline(${line(osrm)},{color:'#6C4CF5',weight:7,opacity:.7}).addTo(m);
L.polyline(${line(tomtom)},{color:'#d33',weight:3}).addTo(m);
for(const [la,lo,c,t] of [$markers]) L.circleMarker([la,lo],{radius:8,color:'#000',weight:1,fillColor:c,fillOpacity:.9}).bindPopup(t).addTo(m);
m.fitBounds(a.getBounds());
</script></body></html>"""
        val f = File("build/reports/lanewise/provider_compare.html")
        f.parentFile.mkdirs()
        f.writeText(html)
        println("\nmap: ${f.absolutePath}")
    }
}
