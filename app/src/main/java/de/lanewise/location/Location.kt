package de.lanewise.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import de.lanewise.core.geo.LatLon
import de.lanewise.core.geo.Polyline
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow

data class Fix(
    val position: LatLon,
    val speedMps: Float,
    val bearing: Float?,
    val accuracyM: Float,
    val timeMs: Long,
    val simulated: Boolean = false,
) {
    val speedKmh get() = speedMps * 3.6f
}

/** Snapshot of the GNSS receiver: how many satellites of which constellations are used in the fix. */
data class GnssInfo(
    val satellitesUsed: Int = 0,
    val satellitesVisible: Int = 0,
    val constellations: Set<String> = emptySet(),
)

/**
 * Raw GNSS positioning via the platform LocationManager (GPS_PROVIDER), i.e. satellites only:
 * works without internet and without Google Play Services. Network location is only used as a
 * coarse first fix when available.
 */
class GnssLocationSource(context: Context) {
    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    @SuppressLint("MissingPermission")
    fun fixes(): Flow<Fix> = callbackFlow {
        val listener = object : LocationListener {
            override fun onLocationChanged(l: Location) { trySend(l.toFix()) }
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        runCatching { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull()?.let { trySend(it.toFix()) }
        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500L, 0f, listener, Looper.getMainLooper())
        if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            runCatching { lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000L, 0f, listener, Looper.getMainLooper()) }
        }
        awaitClose { lm.removeUpdates(listener) }
    }

    @SuppressLint("MissingPermission")
    fun gnss(): Flow<GnssInfo> = callbackFlow {
        val cb = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                var used = 0
                val names = HashSet<String>()
                for (i in 0 until status.satelliteCount) {
                    if (status.usedInFix(i)) {
                        used++
                        names += when (status.getConstellationType(i)) {
                            GnssStatus.CONSTELLATION_GPS -> "GPS"
                            GnssStatus.CONSTELLATION_GALILEO -> "Galileo"
                            GnssStatus.CONSTELLATION_GLONASS -> "GLONASS"
                            GnssStatus.CONSTELLATION_BEIDOU -> "BeiDou"
                            GnssStatus.CONSTELLATION_QZSS -> "QZSS"
                            GnssStatus.CONSTELLATION_SBAS -> "SBAS"
                            else -> "Other"
                        }
                    }
                }
                trySend(GnssInfo(used, status.satelliteCount, names))
            }
        }
        lm.registerGnssStatusCallback(cb, Handler(Looper.getMainLooper()))
        awaitClose { lm.unregisterGnssStatusCallback(cb) }
    }

    private fun Location.toFix() = Fix(
        position = LatLon(latitude, longitude),
        speedMps = if (hasSpeed()) speed else 0f,
        bearing = if (hasBearing() && speed > 1.5f) bearing else null,
        accuracyM = if (hasAccuracy()) accuracy else 50f,
        timeMs = time,
    )
}

/**
 * Drives along a route at (roughly) the speed limit — for testing lane guidance at the desk and
 * for the "Demo" button. Speeds up/slows down so speed warnings can be seen too.
 */
class SimulatedLocationSource(
    private val line: Polyline,
    private val speedAt: (Double) -> Double, // km/h for a given distance
    private val speedFactor: Double = 1.0,
) {
    fun fixes(startAt: Double = 0.0): Flow<Fix> = flow {
        var d = startAt
        val dtMs = 250L
        var phase = 0.0
        while (d < line.length) {
            phase += 0.01
            val limit = speedAt(d).coerceIn(20.0, 140.0)
            // Oscillate ±12% around the limit to exercise the over/under-speed border.
            val kmh = limit * (1.0 + 0.12 * kotlin.math.sin(phase))
            val mps = kmh / 3.6 * speedFactor
            emit(Fix(line.pointAt(d), (kmh / 3.6).toFloat(), line.bearingAt(d).toFloat(), 3f, System.currentTimeMillis(), simulated = true))
            delay(dtMs)
            d += mps * dtMs / 1000.0
        }
        emit(Fix(line.points.last(), 0f, null, 3f, System.currentTimeMillis(), simulated = true))
    }
}
