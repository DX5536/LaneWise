package de.lanewise.search

import de.lanewise.core.geo.LatLon
import de.lanewise.core.net.Http
import de.lanewise.core.net.arr
import de.lanewise.core.net.dbl
import de.lanewise.core.net.get
import de.lanewise.core.net.str
import java.net.URLEncoder
import java.util.Locale

data class Place(val title: String, val subtitle: String, val position: LatLon)

/**
 * Address/POI search via Photon (komoot, OpenStreetMap data, no key), restricted to Germany and biased
 * towards the current position (Hannover by default).
 */
class Geocoder(private val baseUrl: String = "https://photon.komoot.io") {

    suspend fun search(query: String, near: LatLon?): List<Place> {
        if (query.isBlank()) return emptyList()
        // "Grazerstr 2" / "Cellerstraße" are written "Grazer Straße" in OSM and Photon does not find them (it returned
        // Grazerstraße 2 in Austria only). Search the split spelling too, its hits first.
        val alt = normalize(query)
        if (alt == query) return searchOnce(query, near)
        val a = runCatching { searchOnce(alt, near) }
        val b = runCatching { searchOnce(query, near) }
        if (a.isFailure && b.isFailure) throw a.exceptionOrNull()!!
        return (a.getOrDefault(emptyList()) + b.getOrDefault(emptyList())).distinctBy { it.title + it.subtitle }
    }

    private suspend fun searchOnce(query: String, near: LatLon?): List<Place> {
        val bias = near ?: LatLon(52.3759, 9.7320)
        val url = String.format(
            Locale.US, "%s/api/?q=%s&lang=de&limit=10&lat=%.5f&lon=%.5f&bbox=5.87,47.27,15.04,55.06",
            baseUrl, URLEncoder.encode(query, "UTF-8"), bias.lat, bias.lon,
        )
        val root = Http.getJson(url)
        return root["features"].arr.orEmpty().mapNotNull { f ->
            val c = f["geometry"]["coordinates"]
            val lon = c[0].dbl ?: return@mapNotNull null
            val lat = c[1].dbl ?: return@mapNotNull null
            val p = f["properties"]
            val street = listOfNotNull(p["street"].str, p["housenumber"].str).joinToString(" ")
            val name = p["name"].str ?: street.ifBlank { null } ?: return@mapNotNull null
            val place = listOfNotNull(p["postcode"].str, p["city"].str ?: p["district"].str).joinToString(" ")
            val sub = listOf(if (name != street) street else "", place).filter { it.isNotBlank() }.joinToString(", ")
            Place(name, sub, LatLon(lat, lon))
        }.distinctBy { it.title + it.subtitle }
    }

    companion object {
        private val ER_STREET = Regex("""(?i)\b(\p{L}{2,}er)(str\.?|straße|strasse)(?=\s|\d|$)""")

        /** "grazerstr 2" → "grazer Straße 2" (adjective street names); anything else unchanged. */
        fun normalize(q: String): String = ER_STREET.replace(q.trim()) { m -> m.groupValues[1] + " Straße" }
    }
}
