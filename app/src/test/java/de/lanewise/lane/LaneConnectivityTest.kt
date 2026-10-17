package de.lanewise.lane

import de.lanewise.core.geo.Geo
import de.lanewise.core.geo.LatLon
import de.lanewise.core.geo.Polyline
import de.lanewise.core.model.LaneCountSpan
import de.lanewise.core.model.Maneuver
import de.lanewise.core.model.ManeuverType
import de.lanewise.core.model.Modifier
import de.lanewise.core.model.Route
import de.lanewise.core.model.RouteStep
import de.lanewise.location.Fix
import de.lanewise.nav.NavigationEngine
import de.lanewise.nav.Phrases
import de.lanewise.osm.LaneOverrides
import de.lanewise.osm.OsmConnectivity
import de.lanewise.settings.AppLanguage
import de.lanewise.settings.AppSettings
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Explicit lane connectivity ([LaneConnectivity], [LaneSite.connectivity]): parsing, sources, planner and ribbon. */
class LaneConnectivityTest {

    private val L = LaneDir.LEFT
    private val S = LaneDir.STRAIGHT
    private val R = LaneDir.RIGHT
    private val N = LaneDir.NONE

    // --- parsing --------------------------------------------------------------------------------------------------

    @Test
    fun parsesOsmSyntax() {
        assertEquals(mapOf(0 to 0..0, 1 to 1..2, 2 to 2..2), LaneConnectivity.parse("1:1|2:2,3|3:(3)"))
        // An optional target counts only when the lane has no other one.
        assertEquals(mapOf(0 to 0..0, 1 to 2..2), LaneConnectivity.parse("1:1| 2:(2),3"))
        assertEquals(mapOf(0 to 0..0, 1 to 1..1, 2 to 1..1), LaneConnectivity.parse("1:1|2:2|3:2"))   // lane 3 merges
        for (bad in listOf("", "x", "1", "1:", "0:1", "bw1:1", "1:bw1", "1:1,3", "1:2|2:1", "1:1|1:2", "1:1;2:2", "17:1")) {
            assertNull("'$bad' must be rejected", LaneConnectivity.parse(bad))
        }
        assertEquals("2:1|3:2,3", LaneConnectivity.format(listOf(null, 0..0, 1..2)))
    }

    @Test
    fun contradictionsWithTheArrowsAreFound() {
        val straight = LaneSite(400.0, listOf(L, S, S), null, S, false, RoadKind.URBAN)
        assertNull(LaneConnectivity.contradiction(straight, LaneConnectivity.parse("2:1|3:2")!!, 2))
        // The left-turn lane cannot go straight on.
        assertNotNull(LaneConnectivity.contradiction(straight, LaneConnectivity.parse("1:1|2:2")!!, null))
        // A through lane that does not continue straight on.
        assertNotNull(LaneConnectivity.contradiction(straight, LaneConnectivity.parse("2:1")!!, null))
        // A lane the junction does not have, and more lanes than the next road has.
        assertNotNull(LaneConnectivity.contradiction(straight, LaneConnectivity.parse("2:1|3:2|4:3")!!, null))
        assertNotNull(LaneConnectivity.contradiction(straight, LaneConnectivity.parse("2:1|3:2")!!, 1))
        // Unmarked lanes: connectivity is the only information, nothing to contradict.
        val unmarked = LaneSite(400.0, listOf(N, N, N), null, S, false, RoadKind.URBAN)
        assertNull(LaneConnectivity.contradiction(unmarked, LaneConnectivity.parse("2:1|3:2")!!, null))
    }

    // --- sources and precedence -----------------------------------------------------------------------------------

    private fun file(vararg props: String) = """{"type":"FeatureCollection","lanewise_overrides":1,"features":[""" +
        props.joinToString(",") { """{"type":"Feature","geometry":null,"properties":{$it}}""" } + "]}"

    @Test
    fun overridePropertyIsValidated() {
        val o = LaneOverrides.parse(file(
            """"id":"ok","osm_node":10,"from_way":1,"to_way":2,"connectivity":"2:1|3:2","source":"DOP20 2025"""",
            // A turn:lanes patch at the same node is its own entry (no clash with the connectivity patch).
            """"id":"lanes","osm_node":10,"from_way":1,"turn:lanes":"left|through|through","source":"drive"""",
            // rejected:
            """"id":"no-to","osm_node":10,"from_way":1,"connectivity":"1:1","source":"x"""",
            """"id":"way","osm_way":1,"to_way":2,"connectivity":"1:1","source":"x"""",
            """"id":"mixed","osm_node":11,"from_way":1,"to_way":2,"connectivity":"1:1","turn:lanes":"through","source":"x"""",
            """"id":"typo","osm_node":12,"from_way":1,"to_way":2,"connectivity":"1:1,3","source":"x"""",
            """"id":"unknown","osm_node":13,"from_way":1,"to_way":2,"connectivity":"1:1","lanes_unknown":true,"source":"x"""",
            """"id":"to-only","osm_node":14,"from_way":1,"to_way":2,"turn:lanes":"left|through","source":"x"""",
            """"id":"dup","osm_node":10,"from_way":1,"to_way":2,"connectivity":"1:1","source":"x"""",
        ))
        assertEquals(listOf("ok", "lanes"), o.entries.map { it.id })
        assertEquals(7, o.rejected.size)
        assertTrue(o.rejected.any { it.startsWith("typo:") })
        assertTrue(o.rejected.any { it.startsWith("dup:") })
    }

    /** Nodes 1-2-3 on way 100, 3-4-5 on way 200; the route drives 1 → 5 through via node 3. */
    private val ways = listOf(
        LaneOverrides.WayRef(100, listOf(1, 2, 3), listOf(LatLon(52.0, 9.0), LatLon(52.001, 9.0), LatLon(52.002, 9.0))),
        LaneOverrides.WayRef(200, listOf(3, 4, 5), listOf(LatLon(52.002, 9.0), LatLon(52.003, 9.0), LatLon(52.004, 9.0))),
        LaneOverrides.WayRef(300, listOf(3, 9), listOf(LatLon(52.002, 9.0), LatLon(52.002, 9.001))),
    )
    private val routeNodes = listOf(1L, 2L, 3L, 4L, 5L)
    private val project = { p: LatLon -> (p.lat - 52.0) * 111_000.0 }

    @Test
    fun relationsMatchOnlyWhereTheRouteDrivesFromViaTo() {
        val rels = listOf(
            OsmConnectivity.Relation("osm:r1", 100, 3, 200, "2:1|3:2"),
            OsmConnectivity.Relation("osm:r2", 100, 3, 300, "1:1"),       // the route does not leave on way 300
            OsmConnectivity.Relation("osm:r3", 200, 3, 100, "1:1"),       // other direction
            OsmConnectivity.Relation("osm:r4", 100, 3, 999, "1:1"),       // to way not in the data: cannot be checked
        )
        val at = OsmConnectivity.onRoute(rels, routeNodes, ways, project, override = false)
        assertEquals(listOf("osm:r1"), at.map { it.source })
        assertEquals(222.0, at.single().distanceAlong, 1.0)
        val o = LaneOverrides.parse(file(""""id":"p","osm_node":3,"from_way":100,"to_way":200,"connectivity":"2:1|3:2","source":"x""""))
        val patch = o.connectivityOnRoute(routeNodes, ways, project).single()
        assertTrue(patch.override)
        assertEquals("override:p", patch.source)
        // A connectivity patch is not a lane patch: no junction lanes from it.
        assertTrue(o.junctionsOnRoute(routeNodes, ways, project).isEmpty())
    }

    /** 1.2 km straight north. */
    private fun straightRoute(sites: List<LaneSite>, counts: List<LaneCountSpan> = emptyList()): Route {
        val a = LatLon(52.38, 9.74)
        val line = Polyline(listOf(a, Geo.destination(a, 0.0, 1200.0)))
        return Route("test", line, line.length, 100.0, emptyList(), sites, laneCounts = counts)
    }

    @Test
    fun overrideWinsOverOsmAndContradictionsLeaveNoLanes() {
        val site = LaneSite(400.0, listOf(L, S, S), null, S, false, RoadKind.URBAN)
        val other = LaneSite(800.0, listOf(S, S), null, S, false, RoadKind.URBAN)
        val r0 = straightRoute(listOf(site, other))
        // OSM relation contradicts the arrows, the patch is right: the patch is used.
        val r = r0.withConnectivity(listOf(
            ConnectivityAt(405.0, "1:1|2:2", "osm:r1", override = false),
            ConnectivityAt(402.0, "2:1|3:2", "override:p", override = true),
        ))
        assertEquals(listOf(null, 0..0, 1..1), r.laneSites.first().connectivity)
        assertTrue(r.unknownLanesAt.isEmpty())
        // OSM alone and contradicting: no lanes there, the junction is marked unknown, the other site is untouched.
        val bad = r0.withConnectivity(listOf(ConnectivityAt(405.0, "1:1|2:2", "osm:r1", override = false)))
        assertEquals(listOf(800.0), bad.laneSites.map { it.distanceAlong })
        assertEquals(listOf(400.0), bad.unknownLanesAt)
        // Two OSM relations that disagree: neither is trusted.
        val twice = r0.withConnectivity(listOf(ConnectivityAt(400.0, "2:1|3:2", "osm:r1", false), ConnectivityAt(401.0, "2:1|3:1", "osm:r2", false)))
        assertEquals(listOf(400.0), twice.unknownLanesAt)
        // Next road narrower than the connectivity says (OSM lanes=1 after the junction): unknown.
        val narrow = straightRoute(listOf(site), listOf(LaneCountSpan(0.0, 400.0, 3), LaneCountSpan(400.0, 1200.0, 1)))
            .withConnectivity(listOf(ConnectivityAt(400.0, "2:1|3:2", "osm:r1", false)))
        assertEquals(listOf(400.0), narrow.unknownLanesAt)
        // No lane site near: nothing to attach it to.
        assertEquals(r0.laneSites, r0.withConnectivity(listOf(ConnectivityAt(600.0, "1:1", "osm:r9", false))).laneSites)
    }

    /** Owner's route geometry from [LaneOverridesTest]: left turn at 600 m. A contradicting connectivity → "No lane data". */
    @Test
    fun contradictingConnectivityGivesNoLaneAdvice() {
        val a = LatLon(52.3700, 9.7400)
        val b = Geo.destination(a, 0.0, 600.0)
        val c = Geo.destination(b, 270.0, 400.0)
        val line = Polyline(listOf(a, b, c))
        fun step(at: Double, type: ManeuverType, m: Modifier, p: LatLon) =
            RouteStep(at, 0.0, 0.0, Maneuver(type, m, p, 0.0, 0.0), "Musterstraße", null, null, null)
        val sites = listOf(LaneSite(300.0, listOf(L, S, S, R), null, S, false, RoadKind.URBAN),
            LaneSite(600.0, listOf(L, S, S), null, L, true, RoadKind.URBAN))
        val route = Route("osrm", line, line.length, 120.0, listOf(step(0.0, ManeuverType.DEPART, Modifier.STRAIGHT, a),
            step(600.0, ManeuverType.TURN, Modifier.LEFT, b), step(line.length, ManeuverType.ARRIVE, Modifier.STRAIGHT, c)), sites)
            .withConnectivity(listOf(ConnectivityAt(600.0, "1:1|2:2", "osm:r1", false)))   // a through lane "turns left"
        assertEquals(listOf(600.0), route.unknownLanesAt)
        val engine = NavigationEngine(route)
        val snap = engine.update(Fix(line.pointAt(320.0), 12f, line.bearingAt(320.0).toFloat(), 5f, 1000), AppSettings(),
            Phrases(AppLanguage.ENGLISH)).first
        assertTrue(snap.lanesUnknown)
        assertNull(snap.lanes.nextAdvice)
    }

    // --- planner --------------------------------------------------------------------------------------------------

    @Test
    fun allowedLanesComeFromConnectivity() {
        val s = LaneSite(400.0, listOf(N, N, N), listOf(true, true, true), S, false, RoadKind.URBAN, connectivity = listOf(null, 0..0, 1..1))
        assertArrayEquals(booleanArrayOf(false, true, true), s.allowed())
        assertEquals(2, s.connectedToLanes())
    }

    /**
     * Two right-turn lanes `through | right | right` into a three-lane road; the connectivity says they lead into the
     * new road's *left* two lanes (its right lane starts at the junction, from the cross street). The heuristic
     * (corresponding lane counted from the right) put the right-turn lane into the right lane; with connectivity the
     * planner knows that the right lane of the new road needs one lane change.
     */
    @Test
    fun plannerUsesConnectivityThroughATurn() {
        val turnRaw = LaneSite(300.0, listOf(S, R, R), null, R, true, RoadKind.URBAN)
        val turn = turnRaw.copy(connectivity = listOf(null, 0..0, 1..1))
        val next = LaneSite(600.0, listOf(S, S, S), listOf(false, false, true), S, false, RoadKind.URBAN)
        val p = LanePlanner()
        assertEquals(0, p.entryLateral(turnRaw, 2, turnRaw.allowed(), next))   // heuristic: rightmost lane
        assertEquals(1, p.entryLateral(turn, 2, turn.allowed(), next))         // connectivity: the middle lane
        assertEquals(2, p.entryLateral(turn, 1, turn.allowed(), next))

        val heuristic = p.plan(listOf(turnRaw, next), LanePlanner.Options())
        assertEquals(0, heuristic[1].changeFromPrevious)
        val explicit = p.plan(listOf(turn, next), LanePlanner.Options())
        assertEquals(2, explicit[0].recommended)
        assertEquals(-1, explicit[1].changeFromPrevious)                       // honest: one change to the right
        assertEquals(1, explicit[1].entryLateral)
    }

    // --- ribbon ---------------------------------------------------------------------------------------------------

    /** Three unmarked lanes, two after the junction. Connectivity says the left lane ends: it stops at the stop line. */
    @Test
    fun laneThatEndsIsCutAtTheStopLine() {
        val counts = listOf(LaneCountSpan(0.0, 400.0, 3), LaneCountSpan(400.0, 1200.0, 2))
        val site = LaneSite(400.0, listOf(N, N, N), null, S, false, RoadKind.URBAN)
        val heuristic = straightRoute(listOf(site), counts)
        assertTrue(LaneRibbonBuilder.drawnLaneWidth(heuristic, 406.0)!! > 2.2)   // no arrows: narrows across the junction
        val ends = straightRoute(listOf(site.copy(connectivity = listOf(null, 0..0, 1..1))), counts)
        assertEquals(3.0, LaneRibbonBuilder.drawnLaneWidth(ends, 396.0)!!, 0.01)
        for (d in listOf(406.0, 412.0, 418.0)) assertEquals("at $d m", 2.0, LaneRibbonBuilder.drawnLaneWidth(ends, d)!!, 0.01)
        // The right lane merges into the middle one after the junction (3:2): it continues and narrows in the funnel.
        val merges = straightRoute(listOf(site.copy(connectivity = listOf(0..0, 1..1, 1..1))), counts)
        assertTrue(LaneRibbonBuilder.drawnLaneWidth(merges, 406.0)!! > 2.2)
    }
}
