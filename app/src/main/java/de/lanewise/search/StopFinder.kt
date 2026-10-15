package de.lanewise.search

import de.lanewise.core.geo.Geo
import de.lanewise.core.geo.LatLon
import de.lanewise.core.geo.Polyline
import kotlinx.serialization.json.JsonElement
import de.lanewise.core.net.Overpass
import de.lanewise.core.net.arr
import de.lanewise.core.net.dbl
import de.lanewise.core.net.get
import de.lanewise.core.net.str
import java.util.Locale
import kotlin.math.abs

/** A stop found along the route: [detourSeconds] it adds, inserted as stop number [insertAt] (0 = first stop). */
data class StopCandidate(val place: Place, val detourSeconds: Double, val insertAt: Int, val alongMeters: Double)

/**
 * Smart stops (route preview → Add stop → "Best along the route"): finds places matching a name or brand
 * ("Sparkasse", "Aldi") or a category ("Tankstelle", "Geldautomat", "Apotheke") in a corridor along the route
 * (OpenStreetMap via Overpass) and ranks them by the driving time they add — the real time from the OSRM table
 * service between the stops of the trip and each candidate, not the straight-line distance. So the Sparkasse that is
 * right on the way wins over the one closest to the start.
 */
class StopFinder(private val durations: suspend (List<LatLon>) -> Array<DoubleArray>?) {

    /**
     * Candidates for [query] along [route] whose stops so far are [waypoints] (start, stops, destination), best first.
     */
    suspend fun find(query: String, route: Polyline, waypoints: List<LatLon>, limit: Int = 8): List<StopCandidate> {
        val q = buildQuery(query, route) ?: return emptyList()
        val found = parse(Overpass.jsonRace(q))
        if (found.isEmpty()) return emptyList()
        // Rough pre-ranking by the distance off the route (there and back), then real driving times for the best ones.
        val pre = found.map { p ->
            val pr = route.project(p.position)
            Triple(p, abs(pr?.signedOffset ?: 1e6), pr?.distanceAlong ?: 0.0)
        }.sortedBy { it.second }.take(MAX_TABLE)
        val pts = waypoints + pre.map { it.first.position }
        val t = durations(pts)
        return pre.mapIndexed { i, (p, off, along) ->
            val (leg, detour) = if (t != null) bestInsertion(t, waypoints.size, waypoints.size + i)
            else legByAlong(route, waypoints, along) to 2.0 * off / FALLBACK_MPS
            StopCandidate(p, detour.coerceAtLeast(0.0), leg, along)
        }.filter { it.detourSeconds.isFinite() }.sortedBy { it.detourSeconds }.take(limit)
    }

    companion object {
        /** Candidates whose real driving times are asked from OSRM (table of waypoints + these). */
        const val MAX_TABLE = 15
        /** Detour speed without the table (m/s, town): there and back off the route. */
        private const val FALLBACK_MPS = 8.0

        /**
         * Cheapest leg to put candidate [c] into: leg k runs from waypoint k to k + 1 (waypoints are the first [n]
         * entries of [t]). Returns (k, seconds added) — time to the candidate and on to the next waypoint, minus the
         * time of the leg without it.
         */
        fun bestInsertion(t: Array<DoubleArray>, n: Int, c: Int): Pair<Int, Double> {
            var best = 0 to Double.POSITIVE_INFINITY
            for (k in 0 until n - 1) {
                val add = t[k][c] + t[c][k + 1] - t[k][k + 1]
                if (add < best.second) best = k to add
            }
            return best
        }

        /** Leg of a candidate at [along] on the route, by where the stops are along it. */
        fun legByAlong(route: Polyline, waypoints: List<LatLon>, along: Double): Int {
            val stopsAlong = waypoints.drop(1).dropLast(1).map { route.project(it)?.distanceAlong ?: 0.0 }
            return stopsAlong.count { it < along }
        }

        /** OSM tag filters for common categories (German, English and Vietnamese words). */
        private val CATEGORIES: List<Pair<Regex, String>> = listOf(
            Regex("tankstelle|tanken|gas station|petrol|fuel|cây xăng|trạm xăng") to "[amenity=fuel]",
            Regex("geldautomat|bankautomat|atm|cây atm|rút tiền") to "[amenity=atm]",
            Regex("^bank$|ngân hàng") to "[amenity=bank]",
            Regex("ladestation|ladesäule|e-?laden|charging|trạm sạc") to "[amenity=charging_station]",
            Regex("apotheke|pharmacy|nhà thuốc|hiệu thuốc") to "[amenity=pharmacy]",
            Regex("supermarkt|supermarket|siêu thị") to "[shop=supermarket]",
            Regex("bäcker|baecker|bakery|tiệm bánh") to "[shop=bakery]",
            Regex("toilette|wc|toilet|nhà vệ sinh") to "[amenity=toilets]",
            Regex("parkplatz|parkhaus|parking|bãi đỗ|bãi đậu") to "[amenity=parking]",
            Regex("café|cafe|kaffee|coffee|cà phê") to "[amenity=cafe]",
            Regex("restaurant|nhà hàng") to "[amenity=restaurant]",
            Regex("imbiss|fast ?food|đồ ăn nhanh") to "[amenity=fast_food]",
            Regex("post(filiale|amt)?$|post office|bưu điện") to "[amenity=post_office]",
            Regex("waschanlage|car ?wash|rửa xe") to "[amenity=car_wash]",
            Regex("drogerie|drugstore") to "[shop=chemist]",
        )

        /** Overpass query for [query] in a corridor along [route]; null for an empty query. */
        fun buildQuery(query: String, route: Polyline): String? {
            val q = query.trim()
            if (q.length < 2) return null
            val lower = q.lowercase(Locale.GERMANY)
            val category = CATEGORIES.firstOrNull { it.first.containsMatchIn(lower) }?.second
            // Corridor: the route line, simplified to at most ~100 points, ±600 m in town … ±2 km on long trips.
            val step = maxOf(300.0, route.length / 100)
            val pts = route.slice(0.0, route.length, step)
            val radius = (route.length * 0.04).coerceIn(600.0, 2000.0).toInt()
            val line = pts.joinToString(",") { String.format(Locale.US, "%.5f,%.5f", it.lat, it.lon) }
            val around = "(around:$radius,$line)"
            // Names / brands: letters, digits, spaces and dashes only (no regex or quote injection into Overpass).
            val word = q.filter { it.isLetterOrDigit() || it == ' ' || it == '-' }.trim()
            val poi = "[~\"^(amenity|shop|tourism|leisure|office|healthcare|craft|fuel)$\"~\".\"]"
            val parts = buildList {
                category?.let { add("nwr$around$it;") }
                // A category word ("Tankstelle") is the tag alone: three name regex scans would only load the server.
                if (category == null && word.length >= 2) {
                    add("nwr$around[\"name\"~\"$word\",i]$poi;")
                    add("nwr$around[\"brand\"~\"$word\",i]$poi;")
                    add("nwr$around[\"operator\"~\"$word\",i]$poi;")
                }
            }
            if (parts.isEmpty()) return null
            return "[out:json][timeout:25];(" + parts.joinToString("") + ");out center 300;"
        }

        /** Places from an Overpass answer (nodes, and ways / relations by their centre). */
        fun parse(root: JsonElement): List<Place> = root["elements"].arr.orEmpty().mapNotNull { e ->
            val lat = e["lat"].dbl ?: e["center"]["lat"].dbl ?: return@mapNotNull null
            val lon = e["lon"].dbl ?: e["center"]["lon"].dbl ?: return@mapNotNull null
            val t = e["tags"]
            val name = t["name"].str ?: t["brand"].str ?: t["operator"].str ?: kindName(t) ?: return@mapNotNull null
            val street = listOfNotNull(t["addr:street"].str, t["addr:housenumber"].str).joinToString(" ")
            val sub = listOf(street, t["addr:city"].str.orEmpty()).filter { it.isNotBlank() }.joinToString(", ")
            val category = listOf("amenity", "shop", "leisure", "tourism", "healthcare")
                .firstNotNullOfOrNull { k -> t[k].str?.let { "$k:$it" } }
            Place(name, sub, LatLon(lat, lon), category)
        }.let { places ->
            // One per spot: an ATM inside a bank is mapped as both, a fuel station as node and area.
            val out = ArrayList<Place>()
            for (p in places) if (out.none { it.title == p.title && Geo.distance(it.position, p.position) < 60 }) out += p
            out
        }

        private fun kindName(t: JsonElement?): String? = when {
            t["amenity"].str == "fuel" -> "Tankstelle"
            t["amenity"].str == "atm" -> "Geldautomat"
            t["amenity"].str == "charging_station" -> "Ladestation"
            t["amenity"].str == "toilets" -> "WC"
            t["amenity"].str == "parking" -> "Parkplatz"
            else -> null
        }
    }
}
