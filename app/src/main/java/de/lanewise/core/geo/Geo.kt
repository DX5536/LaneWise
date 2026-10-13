package de.lanewise.core.geo

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** WGS84 coordinate. */
data class LatLon(val lat: Double, val lon: Double)

object Geo {
    const val EARTH_RADIUS_M = 6_371_008.8

    fun toRad(d: Double) = d * PI / 180.0
    fun toDeg(r: Double) = r * 180.0 / PI

    fun distance(a: LatLon, b: LatLon): Double {
        val dLat = toRad(b.lat - a.lat)
        val dLon = toRad(b.lon - a.lon)
        val h = sin(dLat / 2).let { it * it } +
            cos(toRad(a.lat)) * cos(toRad(b.lat)) * sin(dLon / 2).let { it * it }
        return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(h)))
    }

    /** Initial bearing a→b in degrees [0, 360). */
    fun bearing(a: LatLon, b: LatLon): Double {
        val la1 = toRad(a.lat)
        val la2 = toRad(b.lat)
        val dLon = toRad(b.lon - a.lon)
        val y = sin(dLon) * cos(la2)
        val x = cos(la1) * sin(la2) - sin(la1) * cos(la2) * cos(dLon)
        return (toDeg(atan2(y, x)) + 360.0) % 360.0
    }

    /** Signed smallest difference to - from, in (-180, 180]. Positive = clockwise (right turn). */
    fun bearingDelta(from: Double, to: Double): Double {
        var d = (to - from) % 360.0
        if (d > 180) d -= 360
        if (d <= -180) d += 360
        return d
    }

    /** Destination point given start, bearing (deg) and distance (m). */
    fun destination(p: LatLon, bearingDeg: Double, distM: Double): LatLon {
        val dr = distM / EARTH_RADIUS_M
        val br = toRad(bearingDeg)
        val la1 = toRad(p.lat)
        val lo1 = toRad(p.lon)
        val la2 = asin(sin(la1) * cos(dr) + cos(la1) * sin(dr) * cos(br))
        val lo2 = lo1 + atan2(sin(br) * sin(dr) * cos(la1), cos(dr) - sin(la1) * sin(la2))
        return LatLon(toDeg(la2), ((toDeg(lo2) + 540) % 360) - 180)
    }

    fun lerp(a: LatLon, b: LatLon, t: Double) =
        LatLon(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)

    /** Metres per logical pixel in MapLibre (512px world tiles) at [zoom] and [lat]. */
    fun metersPerPixel(lat: Double, zoom: Double): Double =
        40_075_016.686 * cos(toRad(lat)) / (512.0 * Math.pow(2.0, zoom))
}

/** Result of projecting a point onto a polyline. */
data class Projection(
    val point: LatLon,
    /** Index of the segment start vertex. */
    val segmentIndex: Int,
    /** Distance from polyline start to the projected point (m). */
    val distanceAlong: Double,
    /** Perpendicular distance between the input and the polyline (m). Positive = right of travel direction. */
    val signedOffset: Double,
)

/**
 * Polyline with cumulative distances for fast along-track queries.
 * Uses a local equirectangular approximation per segment, which is accurate to
 * centimetres for the segment lengths that occur in road geometry.
 */
class Polyline(val points: List<LatLon>) {
    val cumulative: DoubleArray = DoubleArray(points.size).also { c ->
        for (i in 1 until points.size) c[i] = c[i - 1] + Geo.distance(points[i - 1], points[i])
    }
    val length: Double get() = if (cumulative.isEmpty()) 0.0 else cumulative.last()

    /** Project [p]; [hintIndex]/[window] limit the search to keep per-fix cost low during navigation. */
    fun project(p: LatLon, hintIndex: Int = 0, window: Int = Int.MAX_VALUE): Projection? {
        if (points.size < 2) return null
        val from = max(0, hintIndex - 5)
        val to = if (window == Int.MAX_VALUE) points.size - 1 else min(points.size - 1, hintIndex + window)
        var best: Projection? = null
        var bestDist = Double.MAX_VALUE
        for (i in from until to) {
            val a = points[i]
            val b = points[i + 1]
            val cosLat = cos(Geo.toRad((a.lat + b.lat) / 2))
            val ax = 0.0
            val ay = 0.0
            val bx = Geo.toRad(b.lon - a.lon) * cosLat * Geo.EARTH_RADIUS_M
            val by = Geo.toRad(b.lat - a.lat) * Geo.EARTH_RADIUS_M
            val px = Geo.toRad(p.lon - a.lon) * cosLat * Geo.EARTH_RADIUS_M
            val py = Geo.toRad(p.lat - a.lat) * Geo.EARTH_RADIUS_M
            val segLen2 = bx * bx + by * by
            val t = if (segLen2 <= 1e-9) 0.0 else ((px - ax) * bx + (py - ay) * by) / segLen2
            val tc = t.coerceIn(0.0, 1.0)
            val qx = bx * tc
            val qy = by * tc
            val dx = px - qx
            val dy = py - qy
            val d = sqrt(dx * dx + dy * dy)
            if (d < bestDist) {
                bestDist = d
                // cross product sign: negative => point is right of a->b (x east, y north)
                val cross = bx * py - by * px
                val segLen = sqrt(segLen2)
                best = Projection(
                    point = Geo.lerp(a, b, tc),
                    segmentIndex = i,
                    distanceAlong = cumulative[i] + segLen * tc,
                    signedOffset = if (cross < 0) d else -d,
                )
            }
        }
        return best
    }

    /** Point located [dist] metres along the polyline (clamped). */
    fun pointAt(dist: Double): LatLon {
        if (points.isEmpty()) return LatLon(0.0, 0.0)
        if (dist <= 0) return points.first()
        if (dist >= length) return points.last()
        val i = segmentIndexAt(dist)
        val segLen = cumulative[i + 1] - cumulative[i]
        val t = if (segLen <= 0) 0.0 else (dist - cumulative[i]) / segLen
        return Geo.lerp(points[i], points[i + 1], t)
    }

    fun bearingAt(dist: Double): Double {
        if (points.size < 2) return 0.0
        val i = segmentIndexAt(dist.coerceIn(0.0, length))
        return Geo.bearing(points[i], points[i + 1])
    }

    fun segmentIndexAt(dist: Double): Int {
        var lo = 0
        var hi = cumulative.size - 1
        while (lo < hi - 1) {
            val mid = (lo + hi) ushr 1
            if (cumulative[mid] <= dist) lo = mid else hi = mid
        }
        return lo.coerceAtMost(points.size - 2).coerceAtLeast(0)
    }

    /** Sub-polyline between two along-track distances, sampled every [step] metres (plus vertices). */
    fun slice(fromDist: Double, toDist: Double, step: Double = 0.0): List<LatLon> {
        val a = fromDist.coerceIn(0.0, length)
        val b = toDist.coerceIn(0.0, length)
        if (b <= a) return emptyList()
        val out = ArrayList<LatLon>()
        out += pointAt(a)
        var i = segmentIndexAt(a) + 1
        var lastD = a
        while (i < points.size && cumulative[i] < b) {
            if (step > 0) {
                var d = lastD + step
                while (d < cumulative[i]) { out += pointAt(d); d += step }
            }
            out += points[i]
            lastD = cumulative[i]
            i++
        }
        if (step > 0) {
            var d = lastD + step
            while (d < b) { out += pointAt(d); d += step }
        }
        out += pointAt(b)
        return out
    }

    companion object {
        /**
         * Offset a densely sampled polyline sideways. [offsetAt] gives the offset (m, positive = right of travel)
         * for each vertex index. Uses the averaged neighbour bearing for smooth joins.
         */
        fun offset(points: List<LatLon>, offsetAt: (Int) -> Double): List<LatLon> {
            if (points.size < 2) return points
            return points.mapIndexed { i, p ->
                val prev = points[max(0, i - 1)]
                val next = points[min(points.size - 1, i + 1)]
                val br = if (prev == next) 0.0 else Geo.bearing(prev, next)
                val o = offsetAt(i)
                if (abs(o) < 1e-3) p else Geo.destination(p, br + 90.0, o)
            }
        }
    }
}
