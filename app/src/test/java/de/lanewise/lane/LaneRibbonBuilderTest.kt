package de.lanewise.lane

import de.lanewise.core.geo.Geo
import de.lanewise.core.geo.LatLon
import de.lanewise.core.geo.Polyline
import de.lanewise.core.model.LaneCountSpan
import de.lanewise.core.model.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class LaneRibbonBuilderTest {

    private val L = LaneDir.LEFT
    private val S = LaneDir.STRAIGHT
    private val R = LaneDir.RIGHT

    /** 1.2 km straight north. */
    private fun route(sites: List<LaneSite>, lanes: Int?): Route {
        val a = LatLon(52.38, 9.74)
        val line = Polyline(listOf(a, Geo.destination(a, 0.0, 1200.0)))
        return Route("test", line, line.length, 100.0, emptyList(), sites,
            laneCounts = lanes?.let { listOf(LaneCountSpan(0.0, 1200.0, it)) } ?: emptyList())
    }

    private fun offsets(route: Route, carAt: Double, range: ClosedFloatingPointRange<Double>): List<Double> {
        val state = LaneGuidance().update(route.laneSites, carAt, avoidLaneChanges = true, keepRight = false)
        val ribbon = LaneRibbonBuilder.build(route, state, carAt)
        return ribbon.lanePath.filter { it.first in range }.map { route.geometry.project(it.second)!!.signedOffset }
    }

    /**
     * Vahrenwalder Straße case: 2 lanes, then left pocket + right pocket open before the junction
     * (left | straight | straight | right). Going straight in the right through lane, the planned lane must not
     * swing right and back while the pockets open.
     */
    @Test
    fun plannedLaneStaysPutWhilePocketsOpen() {
        val site = LaneSite(600.0, listOf(L, S, S, R), null, S, false, RoadKind.URBAN)
        val off = offsets(route(listOf(site), 2), 100.0, 200.0..595.0)
        val spread = off.max() - off.min()
        assertTrue("planned lane moved by $spread m", spread < 0.3)
    }

    /** OSM already counts the pocket along the whole way (lanes:forward = 3): still no movement. */
    @Test
    fun pocketCountedByOsmAllAlong() {
        val site = LaneSite(600.0, listOf(L, S, S or R), null, S, false, RoadKind.URBAN)
        val off = offsets(route(listOf(site), 3), 100.0, 200.0..575.0)   // smoothing reaches across the junction in the last 20 m
        assertTrue("planned lane moved by ${off.max() - off.min()} m", off.max() - off.min() < 0.3)
    }

    /** A turn lane leaving at the junction ends there: right after the site the road is narrow again. */
    @Test
    fun turnLaneEndsAtTheJunction() {
        val a = LaneSite(400.0, listOf(L, S), null, S, false, RoadKind.URBAN)
        val r = route(listOf(a), 1)
        val state = LaneGuidance().update(r.laneSites, 100.0, avoidLaneChanges = true, keepRight = false)
        val surface = LaneRibbonBuilder.buildRoad(r, 0.0, 1000.0).first { it.kind == LaneRibbonBuilder.Kind.SURFACE }
        // The ending lane narrows across the junction box (a short funnel, no notch) and is gone 20 m after the stop
        // line: one lane (+0.6 m margin), not still tapering from two along the next road.
        val p = Geo.destination(r.geometry.points.first(), 0.0, 420.0)
        val widths = surface.points.mapNotNull { q ->
            val pr = r.geometry.project(q) ?: return@mapNotNull null
            if (abs(pr.distanceAlong - 420.0) < 3) abs(pr.signedOffset) else null
        }
        assertTrue("no surface points near 410 m (${p})", widths.isNotEmpty())
        assertTrue("road still ${widths.max() * 2} m wide after the junction", widths.max() < 2.2)
    }

    /**
     * Owner's video (Celler Straße → left into Herschelstraße): one lane widening to left | straight | straight+right
     * 84 m before the turn. With the car already on the wide stretch, the highlight must be in the left-turn lane —
     * not in the middle straight-only lane — and must stay put as the car moves on.
     */
    @Test
    fun highlightIsInTheOpenedTurnLaneAfterTheWidening() {
        val a = LaneSite(200.0, listOf(S), null, S, false, RoadKind.URBAN)
        val b = LaneSite(400.0, listOf(L, S, S or R), null, L, true, RoadKind.URBAN)
        val line = route(emptyList(), null).geometry
        val r = Route("test", line, line.length, 100.0, emptyList(), listOf(a, b),
            laneCounts = listOf(LaneCountSpan(0.0, 316.0, 1), LaneCountSpan(316.0, 1200.0, 3)))
        val g = LaneGuidance()
        fun offsetAt(car: Double, d: Double): Double {
            val st = g.update(r.laneSites, car, avoidLaneChanges = true, keepRight = false, laneCountAt = r::laneCountAt)
            val p = LaneRibbonBuilder.build(r, st, car).pointAt(d)!!
            return r.geometry.project(p)!!.signedOffset
        }
        offsetAt(150.0, 370.0)
        val seen = listOf(250.0, 330.0, 350.0, 365.0).map { offsetAt(it, 375.0) }
        assertTrue("highlight moved as the car drove on: $seen", seen.max() - seen.min() < 0.3)
        // The driver taps the middle lane: that is where the straight-only lane is — one lane width away.
        g.overrideCurrentLane(b, 1)
        val middle = run {
            val st = g.update(r.laneSites, 366.0, avoidLaneChanges = true, keepRight = false, laneCountAt = r::laneCountAt)
            r.geometry.project(LaneRibbonBuilder.build(r, st, 366.0).pointAt(366.0)!!)!!.signedOffset
        }
        assertTrue("highlight ${seen.last()} not in the left lane (middle lane at $middle)", abs(seen.last() - middle) > 2.0)
    }

    /** A traffic light without lane data gets one stop line across the road (not one per signal head of the junction). */
    @Test
    fun stopLineAtTrafficLightWithoutLanes() {
        val a = LatLon(52.38, 9.74)
        val line = Polyline(listOf(a, Geo.destination(a, 0.0, 1200.0)))
        fun sig(d: Double) = de.lanewise.core.model.RoadFeature(de.lanewise.core.model.FeatureKind.TRAFFIC_SIGNALS,
            line.pointAt(d), d)
        val r = Route("test", line, line.length, 100.0, emptyList(), emptyList(),
            features = listOf(sig(500.0), sig(520.0)), laneCounts = listOf(LaneCountSpan(0.0, 1200.0, 2)))
        val stops = LaneRibbonBuilder.buildRoad(r, 0.0, 1000.0).filter { it.kind == LaneRibbonBuilder.Kind.STOP }
        assertTrue("expected 1 stop line, got ${stops.size}", stops.size == 1)
        val width = Geo.distance(stops[0].points.first(), stops[0].points.last())
        assertTrue("stop line $width m does not span 2 lanes", width > 5.5)
    }

    /** Fixed sampling grid: the same spot of the route gives the same geometry whatever the car position (no jitter). */
    @Test
    fun geometryDoesNotShiftAsTheCarMoves() {
        val a = LatLon(52.38, 9.74)
        // A bend, so smoothing matters.
        val line = Polyline(listOf(a, Geo.destination(a, 0.0, 400.0), Geo.destination(Geo.destination(a, 0.0, 400.0), 60.0, 400.0)))
        val r = Route("test", line, line.length, 60.0, emptyList(), emptyList())
        val state = LaneState.EMPTY
        val p1 = LaneRibbonBuilder.build(r, state, 100.0)
        val p2 = LaneRibbonBuilder.build(r, state, 133.0)
        for (d in listOf(300.0, 395.0, 420.0, 600.0)) {
            val x = p1.pointAt(d)!!; val y = p2.pointAt(d)!!
            assertTrue("moved ${Geo.distance(x, y)} m at $d", Geo.distance(x, y) < 0.05)
        }
    }

    /** Lane-view extras: closed lanes (Autobahn roadworks), zebra + signal pole, chevrons on the planned lane. */
    @Test
    fun roadExtrasAreBuiltWhereTheyBelong() {
        val r = route(emptyList(), 3)
        val closure = LaneClosure(300.0, 500.0, listOf(false, true, true))
        val shapes = LaneRibbonBuilder.buildRoad(r, 0.0, 1000.0, extras = LaneRibbonBuilder.RoadExtras(closures = listOf(closure)))
        val tint = shapes.filter { it.kind == LaneRibbonBuilder.Kind.CLOSED_LANE }
        assertEquals(1, tint.size)
        assertTrue(shapes.count { it.kind == LaneRibbonBuilder.Kind.CLOSED_X } >= 8)
        // The tint lies over the leftmost lane, between 300 and 500 m.
        val along = tint[0].points.mapNotNull { r.geometry.project(it) }
        assertTrue(along.all { it.distanceAlong in 290.0..510.0 })
        assertTrue(along.all { it.signedOffset < 0 })
        // Chevrons follow the lane path and fade with distance.
        val ribbon = LaneRibbonBuilder.build(r, LaneState.EMPTY, 100.0)
        val chev = LaneRibbonBuilder.chevrons(ribbon, 100.0, 250.0)
        assertTrue(chev.size in 15..17)
        assertTrue(chev.zipWithNext().all { (a, b) -> b.index == a.index + 1 && b.opacity <= a.opacity })
        // Fixed grid: a later build puts the same chevron at the same spot.
        val later = LaneRibbonBuilder.chevrons(LaneRibbonBuilder.build(r, LaneState.EMPTY, 120.0), 120.0, 270.0)
        val same = later.first { it.index == chev[5].index }
        assertTrue(Geo.distance(same.points[1], chev[5].points[1]) < 0.05)
    }

    /** Owner's screenshot (Berliner Allee): 3 lanes, 55 m tagged 2 under a bridge, 3 again - no choke. */
    @Test
    fun shortLaneDipIsFilled() {
        val spans = listOf(LaneCountSpan(0.0, 300.0, 3), LaneCountSpan(300.0, 355.0, 2), LaneCountSpan(355.0, 600.0, 3),
            LaneCountSpan(600.0, 900.0, 4))
        val f = de.lanewise.core.model.fillShortLaneDips(spans, listOf(300.0, 900.0))
        org.junit.Assert.assertEquals(listOf(3, 3, 3, 4), f.map { it.count })
        // A junction inside the narrow stretch, or a long one, stays as tagged.
        org.junit.Assert.assertEquals(2, de.lanewise.core.model.fillShortLaneDips(spans, listOf(330.0))[1].count)
        val long = spans.toMutableList().also { it[1] = LaneCountSpan(300.0, 500.0, 2); it[2] = LaneCountSpan(500.0, 600.0, 3) }
        org.junit.Assert.assertEquals(2, de.lanewise.core.model.fillShortLaneDips(long, emptyList())[1].count)
    }
}
