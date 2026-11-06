package de.lanewise.nav

import de.lanewise.core.geo.Geo
import de.lanewise.core.geo.LatLon
import de.lanewise.core.geo.Polyline
import de.lanewise.core.model.Maneuver
import de.lanewise.core.model.ManeuverType
import de.lanewise.core.model.Modifier
import de.lanewise.core.model.Route
import de.lanewise.core.model.RouteStep
import de.lanewise.location.Fix
import de.lanewise.settings.AppLanguage
import de.lanewise.settings.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationEngineTest {

    /** A street going north, then 90° left, ending 300 m on (the last bit runs back close to the car's street). */
    private val start = LatLon(52.38, 9.735)
    private val corner = Geo.destination(start, 0.0, 600.0)
    private val end = Geo.destination(corner, 270.0, 300.0)
    private val line = Polyline(listOf(start, corner, end))
    private val route = Route("t", line, line.length, 120.0, listOf(
        RouteStep(0.0, 600.0, 60.0, Maneuver(ManeuverType.DEPART, Modifier.STRAIGHT, start, 0.0, 0.0), "A", null, null, null),
        RouteStep(600.0, 300.0, 60.0, Maneuver(ManeuverType.TURN, Modifier.LEFT, corner, 0.0, 270.0), "B", null, null, null),
        RouteStep(line.length, 0.0, 0.0, Maneuver(ManeuverType.ARRIVE, Modifier.STRAIGHT, end, 0.0, 0.0), "B", null, null, null),
    ), emptyList())
    private val phrases = Phrases(AppLanguage.ENGLISH)

    private fun NavigationEngine.at(p: LatLon, t: Long, speed: Float = 8f) =
        update(Fix(p, speed, null, 5f, t), AppSettings(), phrases).first

    /**
     * Owner's drive (2026-11-02, Herschelstraße at 28 km/h): one GNSS fix projected 230 m ahead onto the end of the
     * route - "You have arrived" and navigation stopped 250 m before the destination.
     */
    @Test
    fun oneFixFarAheadDoesNotEndTheTrip() {
        val e = NavigationEngine(route)
        var t = 0L
        var d = 0.0
        while (d < 400) { e.at(line.pointAt(d), t); d += 8; t += 1000 }
        // The outlier: right at the destination.
        val jump = e.at(end, t)
        assertFalse(jump.arrived)
        assertTrue("progress held at ${jump.distanceAlong}", jump.distanceAlong < 420)
        // Driving on normally afterwards.
        val next = e.at(line.pointAt(d), t + 1000)
        assertFalse(next.arrived)
        assertEquals(d, next.distanceAlong, 5.0)
    }

    /** Standing at a red light: one fix 80 m back does not move the car back ("20 m" → "100 m" → "20 m"). */
    @Test
    fun oneFixFarBackIsIgnored() {
        val e = NavigationEngine(route)
        var t = 0L
        var d = 0.0
        while (d < 500) { e.at(line.pointAt(d), t); d += 8; t += 1000 }
        val stop = e.at(line.pointAt(d), t, 0f).distanceAlong
        assertEquals(stop, e.at(line.pointAt(d - 80), t + 1000, 0f).distanceAlong, 1.0)
        assertEquals(stop, e.at(line.pointAt(d), t + 2000, 0f).distanceAlong, 1.0)
    }

    /** A real arrival still ends the trip, also after a gap in the fixes (tunnel, car park). */
    @Test
    fun realArrivalEndsTheTrip() {
        val e = NavigationEngine(route)
        var t = 0L
        var d = 0.0
        while (d < line.length - 10) { e.at(line.pointAt(d), t); d += 8; t += 1000 }
        assertTrue(e.at(line.pointAt(line.length - 5), t).arrived)
        // Fixes missing for a while, then the car is at the end: three fixes in a row agree.
        val e2 = NavigationEngine(route)
        e2.at(line.pointAt(100.0), 0)
        val snaps = (1..3).map { e2.at(line.pointAt(line.length - 5), 1000L * it) }
        assertFalse(snaps.first().arrived)
        assertTrue(snaps.last().arrived)
    }
}
