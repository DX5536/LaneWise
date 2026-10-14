package de.lanewise.traffic

import de.lanewise.core.geo.LatLon
import de.lanewise.core.model.AutobahnPoi
import de.lanewise.core.model.AutobahnPoiKind
import de.lanewise.core.model.IncidentKind
import de.lanewise.core.model.TrafficIncident
import de.lanewise.core.net.Http
import de.lanewise.core.net.arr
import de.lanewise.core.net.bool
import de.lanewise.core.net.dbl
import de.lanewise.core.net.get
import de.lanewise.core.net.int
import de.lanewise.core.net.str
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.net.URLEncoder
import java.util.Locale

/** A bounding box: south, west, north, east. */
data class BBox(val south: Double, val west: Double, val north: Double, val east: Double) {
    fun contains(p: LatLon) = p.lat in south..north && p.lon in west..east

    companion object {
        fun around(points: List<LatLon>, padDeg: Double = 0.02) = BBox(
            points.minOf { it.lat } - padDeg, points.minOf { it.lon } - padDeg,
            points.maxOf { it.lat } + padDeg, points.maxOf { it.lon } + padDeg,
        )

        /** Region Hannover — the project's focus area. */
        val HANNOVER = BBox(52.20, 9.45, 52.58, 10.10)
    }
}

interface TrafficSource {
    val id: String
    suspend fun incidents(box: BBox, roads: Set<String>): List<TrafficIncident>
}

/**
 * Die Autobahn GmbH des Bundes — official open API (no key): roadworks, warnings, closures.
 * https://verkehr.autobahn.de/o/autobahn
 */
class AutobahnApiSource : TrafficSource {
    override val id = "autobahn"

    override suspend fun incidents(box: BBox, roads: Set<String>): List<TrafficIncident> = coroutineScope {
        val wanted = (roads.filter { it.matches(Regex("A\\d{1,3}")) } + HANNOVER_AUTOBAHNS).toSet()
        wanted.flatMap { road ->
            listOf("roadworks", "warning", "closure").map { svc ->
                async { runCatching { fetch(road, svc) }.getOrDefault(emptyList()) }
            }
        }.awaitAll().flatten().filter { box.contains(it.location) }.distinctBy { it.id }
    }

    private suspend fun fetch(road: String, service: String): List<TrafficIncident> {
        val root = Http.getJson("$BASE/$road/services/$service")
        return root[service].arr.orEmpty().mapNotNull { e ->
            val c = e["coordinate"]
            val (lat, lon) = coordinate(c) ?: return@mapNotNull null
            val geom = e["geometry"]["coordinates"].arr.orEmpty().mapNotNull { p ->
                val x = p[0].dbl; val y = p[1].dbl
                if (x != null && y != null) LatLon(y, x) else null
            }
            val blocked = e["isBlocked"].str == "true" || e["isBlocked"].bool == true
            val kind = when (service) {
                "closure" -> IncidentKind.CLOSURE
                "roadworks" -> if (blocked) IncidentKind.CLOSURE else IncidentKind.ROADWORKS
                else -> when {
                    e["abnormalTrafficType"].str != null -> IncidentKind.JAM
                    else -> IncidentKind.HAZARD
                }
            }
            val desc = e["description"].arr.orEmpty().mapNotNull { it.str }.filter { it.isNotBlank() }.joinToString("\n")
            TrafficIncident(
                id = "autobahn:" + (e["identifier"].str ?: "$road-$service-$lat-$lon"),
                source = "Autobahn GmbH",
                kind = kind,
                title = e["title"].str ?: road,
                description = listOfNotNull(e["subtitle"].str, desc).joinToString("\n"),
                location = LatLon(lat, lon),
                geometry = geom,
                delaySeconds = e["delayTimeValue"].str?.toIntOrNull()?.times(60),
                blocked = blocked,
                laneOpen = laneOpen(e["impact"]["symbols"].arr.orEmpty().mapNotNull { it.str }),
                active = !(e["future"].str.equals("true", ignoreCase = true) || e["future"].bool == true),
            )
        }
    }

    /** Rest/parking areas, EV charging stations and webcams on [roads] (Autobahn GmbH, no key). */
    suspend fun pois(roads: Set<String>, kinds: Set<AutobahnPoiKind>): List<AutobahnPoi> = coroutineScope {
        roads.flatMap { road ->
            kinds.map { kind ->
                async {
                    runCatching {
                        val svc = when (kind) {
                            AutobahnPoiKind.WEBCAM -> "webcam"
                            AutobahnPoiKind.PARKING -> "parking_lorry"
                            AutobahnPoiKind.CHARGING -> "electric_charging_station"
                        }
                        Http.getJson("$BASE/$road/services/$svc")[svc].arr.orEmpty().mapNotNull { e ->
                            val (lat, lon) = coordinate(e["coordinate"]) ?: return@mapNotNull null
                            AutobahnPoi(
                                id = "$svc:" + (e["identifier"].str ?: "$lat,$lon"), kind = kind,
                                title = e["title"].str?.replace(" | undefined", "") ?: road,
                                subtitle = listOfNotNull(e["subtitle"].str).plus(e["description"].arr.orEmpty()
                                    .mapNotNull { it.str?.trim() }.filter { it.isNotBlank() }.take(3)).joinToString(" · "),
                                position = LatLon(lat, lon),
                            )
                        }
                    }.getOrDefault(emptyList())
                }
            }
        }.awaitAll().flatten().distinctBy { it.id }
    }

    companion object {
        const val BASE = "https://verkehr.autobahn.de/o/autobahn"
        /** Motorways around Hannover: A2, A7, A37 (Messeschnellweg), A352, A39 (Braunschweig). */
        val HANNOVER_AUTOBAHNS = listOf("A2", "A7", "A37", "A352", "A39")

        /** `{"lat": "52.1", "long": "9.7"}` or GeoJSON `{"type": "Point", "coordinates": [9.7, 52.1]}` → (lat, lon). */
        private fun coordinate(c: kotlinx.serialization.json.JsonElement?): Pair<Double, Double>? {
            if (c == null) return null
            val lat = c["lat"].str?.toDoubleOrNull() ?: c["lat"].dbl
            val lon = c["long"].str?.toDoubleOrNull() ?: c["long"].dbl
            if (lat != null && lon != null) return lat to lon
            val xy = c["coordinates"].arr ?: return null
            val x = xy.getOrNull(0)?.dbl ?: return null
            val y = xy.getOrNull(1)?.dbl ?: return null
            return y to x
        }

        /**
         * Lane picture of a roadworks entry (`impact.symbols`), e.g. `SEPARATE, ARROW_UP, ARROW_UP, CLOSED, BORDER_RIGHT,
         * BREAKDOWN_LANE` → [open, open, closed]. The group after the last SEPARATE is the affected carriageway, left →
         * right; everything from BORDER_RIGHT on is the hard shoulder. Null if no lane is closed, all lanes are closed
         * (traffic moved to the other carriageway) or the picture cannot be read.
         */
        fun laneOpen(symbols: List<String>): List<Boolean>? {
            if (symbols.isEmpty()) return null
            val last = symbols.lastIndexOf("SEPARATE")
            val group = symbols.drop(last + 1).takeWhile { it != "BORDER_RIGHT" }
            val lanes = group.map {
                when (it) {
                    "ARROW_UP" -> true
                    "CLOSED" -> false
                    else -> return null
                }
            }
            return lanes.takeIf { l -> l.size >= 2 && l.any { it } && l.any { !it } }
        }
    }
}

/** TomTom Traffic Incident Details v5 (key required). */
class TomTomTrafficSource(private val apiKey: String) : TrafficSource {
    override val id = "tomtom"

    override suspend fun incidents(box: BBox, roads: Set<String>): List<TrafficIncident> {
        if (apiKey.isBlank()) return emptyList()
        val fields = "{incidents{type,geometry{type,coordinates},properties{id,iconCategory,magnitudeOfDelay," +
            "events{description,code},from,to,delay,roadNumbers}}}"
        val bbox = String.format(Locale.US, "%.5f,%.5f,%.5f,%.5f", box.west, box.south, box.east, box.north)
        val url = "https://api.tomtom.com/traffic/services/5/incidentDetails?key=$apiKey&bbox=$bbox" +
            "&fields=${URLEncoder.encode(fields, "UTF-8")}&language=de-DE&timeValidityFilter=present"
        val root = Http.getJson(url)
        return root["incidents"].arr.orEmpty().mapNotNull { inc ->
            val p = inc["properties"]
            val g = inc["geometry"]
            val coords = when (g["type"].str) {
                "Point" -> listOfNotNull(pt(g["coordinates"]))
                else -> g["coordinates"].arr.orEmpty().mapNotNull { pt(it) }
            }
            if (coords.isEmpty()) return@mapNotNull null
            val cat = p["iconCategory"].int ?: 0
            val kind = when (cat) {
                1 -> IncidentKind.ACCIDENT
                6 -> IncidentKind.JAM
                7, 9 -> IncidentKind.ROADWORKS
                8 -> IncidentKind.CLOSURE
                2, 4, 5, 10, 11 -> IncidentKind.WEATHER
                else -> IncidentKind.HAZARD
            }
            val events = p["events"].arr.orEmpty().mapNotNull { it["description"].str }
            TrafficIncident(
                id = "tomtom:" + (p["id"].str ?: coords.first().toString()),
                source = "TomTom",
                kind = kind,
                title = events.firstOrNull() ?: kind.name,
                description = listOfNotNull(p["from"].str, p["to"].str).joinToString(" → "),
                location = coords[coords.size / 2],
                geometry = coords,
                delaySeconds = p["delay"].int,
                blocked = cat == 8,
            )
        }
    }

    private fun pt(e: kotlinx.serialization.json.JsonElement?): LatLon? {
        val lon = e[0].dbl ?: return null
        val lat = e[1].dbl ?: return null
        return LatLon(lat, lon)
    }
}

/** HERE Traffic API v7 incidents (key required). */
class HereTrafficSource(private val apiKey: String) : TrafficSource {
    override val id = "here"

    override suspend fun incidents(box: BBox, roads: Set<String>): List<TrafficIncident> {
        if (apiKey.isBlank()) return emptyList()
        val bbox = String.format(Locale.US, "%.5f,%.5f,%.5f,%.5f", box.west, box.south, box.east, box.north)
        val url = "https://data.traffic.hereapi.com/v7/incidents?in=bbox:$bbox&locationReferencing=shape&lang=de-DE&apiKey=$apiKey"
        val root = Http.getJson(url)
        return root["results"].arr.orEmpty().mapNotNull { r ->
            val pts = r["location"]["shape"]["links"].arr.orEmpty().flatMap { l ->
                l["points"].arr.orEmpty().mapNotNull { p ->
                    val la = p["lat"].dbl; val lo = p["lng"].dbl
                    if (la != null && lo != null) LatLon(la, lo) else null
                }
            }
            if (pts.isEmpty()) return@mapNotNull null
            val d = r["incidentDetails"]
            val closed = d["roadClosed"].bool == true
            val kind = when (d["type"].str) {
                "accident" -> IncidentKind.ACCIDENT
                "construction", "laneRestriction" -> IncidentKind.ROADWORKS
                "congestion" -> IncidentKind.JAM
                "roadClosure" -> IncidentKind.CLOSURE
                "weather" -> IncidentKind.WEATHER
                else -> if (closed) IncidentKind.CLOSURE else IncidentKind.HAZARD
            }
            TrafficIncident(
                id = "here:" + (d["id"].str ?: pts.first().toString()),
                source = "HERE",
                kind = kind,
                title = d["summary"]["value"].str ?: kind.name,
                description = d["description"]["value"].str.orEmpty(),
                location = pts[pts.size / 2],
                geometry = pts,
                blocked = closed,
            )
        }
    }
}

/** Fetches from all enabled sources in parallel; one failing source never breaks the others. */
class TrafficService(private val sources: () -> List<TrafficSource>) {
    suspend fun incidents(box: BBox, roads: Set<String> = emptySet()): List<TrafficIncident> = coroutineScope {
        sources().map { s -> async { runCatching { s.incidents(box, roads) }.getOrDefault(emptyList()) } }
            .awaitAll().flatten()
    }
}
