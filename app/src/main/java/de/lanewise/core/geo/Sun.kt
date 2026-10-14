package de.lanewise.core.geo

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin

/**
 * Sun position (NOAA low-precision formulas, ±0.5°), for the automatic light/dark theme: dark from shortly after
 * sunset until shortly before sunrise at the car's position — no internet and no system setting needed.
 */
object Sun {

    /** Sun elevation above the horizon (degrees) at [p] and [timeMs] (UTC epoch millis). */
    fun elevation(p: LatLon, timeMs: Long): Double {
        val d = timeMs / 86_400_000.0 - 10_957.5          // days since J2000.0 (2000-01-01 12:00 UTC)
        val g = Geo.toRad((357.529 + 0.98560028 * d) % 360)  // mean anomaly
        val q = (280.459 + 0.98564736 * d) % 360             // mean longitude
        val l = Geo.toRad(q + 1.915 * sin(g) + 0.020 * sin(2 * g))  // ecliptic longitude
        val e = Geo.toRad(23.439 - 0.00000036 * d)           // obliquity
        val ra = kotlin.math.atan2(cos(e) * sin(l), cos(l))
        val dec = asin(sin(e) * sin(l))
        val gmst = (18.697374558 + 24.06570982441908 * d) % 24  // hours
        val hourAngle = Geo.toRad(gmst * 15 + p.lon) - ra
        val lat = Geo.toRad(p.lat)
        return Geo.toDeg(asin(sin(lat) * sin(dec) + cos(lat) * cos(dec) * cos(hourAngle)))
    }

    /** Dark theme when the sun is below [DARK_BELOW] degrees (≈ 15–20 min after sunset in Germany). */
    fun isDark(p: LatLon, timeMs: Long): Boolean = elevation(p, timeMs) < DARK_BELOW

    const val DARK_BELOW = -3.0
}
