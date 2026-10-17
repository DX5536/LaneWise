package de.lanewise.lane

import de.lanewise.core.geo.Geo
import de.lanewise.core.geo.LatLon
import de.lanewise.core.geo.Polyline
import de.lanewise.core.model.Maneuver
import de.lanewise.core.model.ManeuverType
import de.lanewise.core.model.Modifier
import de.lanewise.core.model.Route
import de.lanewise.core.model.RouteStep
import de.lanewise.location.Fix
import de.lanewise.nav.NavigationEngine
import de.lanewise.nav.Phrases
import de.lanewise.osm.LaneOverrides
import de.lanewise.osm.OsmTags
import de.lanewise.settings.AppLanguage
import de.lanewise.settings.AppSettings
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Hand-checked lane patches (`lane_overrides.json`, [LaneOverrides]) and how the route and the planner use them. */
class LaneOverridesTest {

    private val L = LaneDir.LEFT
    private val S = LaneDir.STRAIGHT
    private val R = LaneDir.RIGHT

    private fun file(vararg props: String) = """{"type":"FeatureCollection","lanewise_overrides":1,"features":[""" +
        props.joinToString(",") { """{"type":"Feature","geometry":{"type":"Point","coordinates":[9.75,52.38]},"properties":{$it}}""" } +
        "]}"

    @Test
    fun parsesAndRejectsUnsafeEntries() {
        val o = LaneOverrides.parse(file(
            """"id":"ok","osm_way":1,"direction":"forward","turn:lanes":"left|through|through;right","source":"DOP20 2025"""",
            """"id":"count","osm_way":2,"lanes":3,"source":"drive"""",
            """"id":"node","osm_node":10,"from_way":1,"turn:lanes":"left|left|right","source":"drive"""",
            """"id":"unknown","osm_way":3,"direction":"backward","lanes_unknown":true,"source":"drive"""",
            // rejected:
            """"id":"no-source","osm_way":4,"turn:lanes":"left|through"""",
            """"id":"typo","osm_way":5,"turn:lanes":"left|throuhg","source":"x"""",
            """"id":"mismatch","osm_way":6,"turn:lanes":"left|through","lanes":3,"source":"x"""",
            """"id":"both","osm_way":7,"osm_node":8,"turn:lanes":"left|through","source":"x"""",
            """"id":"contradiction","osm_way":9,"lanes_unknown":true,"turn:lanes":"left|through","source":"x"""",
            """"id":"change","osm_way":11,"lanes":2,"change:lanes":"yes|no|yes","source":"x"""",
            """"id":"dup","osm_way":1,"direction":"forward","turn:lanes":"through|through","source":"x"""",
            """"id":"dir","osm_way":12,"direction":"north","lanes":2,"source":"x"""",
        ))
        assertEquals(listOf("ok", "count", "node", "unknown"), o.entries.map { it.id })
        assertEquals(8, o.rejected.size)
        assertTrue(o.rejected.any { it.startsWith("typo:") && "throuhg" in it })
        assertTrue(o.rejected.any { it.startsWith("dup:") })
        assertEquals(LatLon(52.38, 9.75), o.entries.first().position)
    }

    /**
     * Asymmetric road: OSM says `lanes=4` on a two-way street, which the app splits 2 + 2. In reality there is 1 lane
     * in the way's direction and 3 against it. The patch per direction wins; a patch without a direction is not applied
     * to a two-way street (it is not known which carriageway it means).
     */
    @Test
    fun asymmetricLaneCountsPerDirection() {
        val osm = mapOf("highway" to "primary", "lanes" to "4")
        assertEquals(2, OsmTags.lanesInDirection(osm, forward = true, oneway = false))
        val o = LaneOverrides.parse(file(
            """"id":"fwd","osm_way":100,"direction":"forward","lanes":1,"source":"DOP20"""",
            """"id":"bwd","osm_way":100,"direction":"backward","turn:lanes":"left|through|through;right","source":"DOP20"""",
            """"id":"nodir","osm_way":101,"lanes":3,"source":"DOP20"""",
        ))
        val t = o.apply(100, osm)
        assertEquals(1, OsmTags.lanesInDirection(t, forward = true, oneway = false))
        assertEquals(3, OsmTags.lanesInDirection(t, forward = false, oneway = false))
        assertEquals("left|through|through;right", t["turn:lanes:backward"])
        assertEquals("bwd", t["${LaneOverrides.MARK}:backward"])
        assertSame(osm, o.apply(101, osm))                     // two-way, no direction → untouched
        assertSame(osm, o.apply(999, osm))                     // not patched
        // On a one-way way the patch without direction is the travel direction, also for oneway=-1.
        val fwd = o.apply(101, mapOf("highway" to "primary", "oneway" to "yes", "lanes" to "2", "lanes:forward" to "2"))
        assertEquals(3, OsmTags.lanesInDirection(fwd, forward = true, oneway = true))
        val rev = o.apply(101, mapOf("highway" to "primary", "oneway" to "-1", "lanes" to "2"))
        assertEquals(3, OsmTags.lanesInDirection(rev, forward = false, oneway = true))
    }

    /** OSM changed since the patch was written and matches neither: nobody checked the new state → lanes unknown. */
    @Test
    fun outdatedPatchMeansLanesUnknown() {
        val o = LaneOverrides.parse(file(
            """"id":"p","osm_way":7,"turn:lanes":"left|through|through","osm_was":{"turn:lanes":"left|through"},"source":"DOP20""""))
        val was = mapOf("highway" to "secondary", "oneway" to "yes", "turn:lanes" to "left|through")
        assertEquals("left|through|through", o.apply(7, was)["turn:lanes:forward"])
        val fixedInOsm = was + ("turn:lanes" to "left|through|through")
        assertEquals("left|through|through", o.apply(7, fixedInOsm)["turn:lanes:forward"])
        val changed = was + ("turn:lanes" to "left|left|through")
        val t = o.apply(7, changed)
        assertNull(t["turn:lanes"]); assertNull(t["turn:lanes:forward"])
        assertEquals("p", t["${LaneOverrides.UNKNOWN_MARK}:forward"])
    }

    @Test
    fun junctionPatchOnlyWhenTheRouteArrivesOnItsWay() {
        val o = LaneOverrides.parse(file(""""id":"j","osm_node":3,"from_way":50,"turn:lanes":"left|left|right","source":"drive""""))
        val pos = LatLon(52.38, 9.75)
        val ways = listOf(
            LaneOverrides.WayRef(50, listOf(1, 2, 3), listOf(LatLon(52.37, 9.75), LatLon(52.375, 9.75), pos)),
            LaneOverrides.WayRef(60, listOf(9, 3, 4), listOf(LatLon(52.38, 9.74), pos, LatLon(52.38, 9.76))),
        )
        val at = o.junctionsOnRoute(listOf(1L, 2, 3, 4), ways) { 640.0 }
        assertEquals(listOf(LaneOverrides.At(640.0, "left|left|right", "j")), at)
        assertTrue(o.junctionsOnRoute(listOf(9L, 3, 4), ways) { 640.0 }.isEmpty())        // arrives on way 60
        assertTrue(o.junctionsOnRoute(listOf(1L, 2, 3), ways.drop(1)) { 640.0 }.isEmpty()) // way 50 not loaded: unchecked
    }

    @Test
    fun localFileWinsOnTheSameId() {
        val asset = LaneOverrides.parse(file(""""id":"a","osm_way":1,"lanes":2,"source":"x"""", """"id":"b","osm_way":2,"lanes":2,"source":"x""""))
        val local = LaneOverrides.parse(file(""""id":"a","osm_way":1,"lanes":3,"source":"drive""""))
        val m = LaneOverrides.merge(asset, local)
        assertEquals(listOf("b", "a"), m.entries.map { it.id })
        assertEquals(3, m.entries.last().lanes)
    }

    // --- Route + planner -------------------------------------------------------------------------------------------

    /** North 600 m, left (west) 300 m, right (north) 400 m. */
    private fun route(sites: List<LaneSite>): Route {
        val a = LatLon(52.3700, 9.7400)
        val b = Geo.destination(a, 0.0, 600.0)
        val c = Geo.destination(b, 270.0, 300.0)
        val d = Geo.destination(c, 0.0, 400.0)
        val line = Polyline(listOf(a, b, c, d))
        fun step(at: Double, type: ManeuverType, m: Modifier, p: LatLon) =
            RouteStep(at, 0.0, 0.0, Maneuver(type, m, p, 0.0, 0.0), "Musterstraße", null, null, null)
        return Route("osrm", line, line.length, 120.0, listOf(
            step(0.0, ManeuverType.DEPART, Modifier.STRAIGHT, a),
            step(600.0, ManeuverType.TURN, Modifier.LEFT, b),
            step(900.0, ManeuverType.TURN, Modifier.RIGHT, c),
            step(line.length, ManeuverType.ARRIVE, Modifier.STRAIGHT, d),
        ), sites)
    }

    private val siteA = LaneSite(300.0, listOf(L, S, S, R), null, S, false, RoadKind.URBAN)
    /** OSM / OSRM (outdated): two left-turn lanes, OSRM flags both. */
    private val osmB = LaneSite(600.0, listOf(L, L, S, R), listOf(true, true, false, false), L, true, RoadKind.URBAN)
    private val siteC = LaneSite(900.0, listOf(S, R), null, R, true, RoadKind.URBAN)

    /**
     * A mis-tagged 4-lane approach: OSM has `left|left|through|right`, the junction was re-marked to
     * `left|through|through|right` (one left-turn lane). With the old data the plan sends the driver into the 2nd lane
     * for the left turn (it enters the right lane needed for the right turn 300 m later) - in reality a straight-only
     * lane. With the patch only the left lane turns, the plan uses it, and driving up to the turn gives no zig-zag.
     */
    @Test
    fun misTaggedFourLaneJunctionIsReplacedAndPlannedWithoutZigZag() {
        val wrong = LanePlanner().plan(route(listOf(siteA, osmB, siteC)).laneSites, LanePlanner.Options())
        assertEquals(1, wrong[1].recommended)                 // the dangerous advice the patch must remove

        val r = route(listOf(siteA, osmB, siteC)).withLaneOverrides(listOf(LaneOverrides.At(604.0, "left|through|through|right", "x")))
        assertEquals(3, r.laneSites.size)
        val b = r.laneSites[1]
        assertEquals(600.0, b.distanceAlong, 0.0)             // kept at the junction OSRM reported
        assertEquals(listOf(L, S, S, R), b.lanes)
        assertNull(b.providerValid)                           // OSRM's flags belonged to the wrong lanes
        assertEquals(L, b.turn)
        assertArrayEquals(booleanArrayOf(true, false, false, false), b.allowed())

        val g = LaneGuidance()
        var lastDir = 0
        var car = 0.0
        while (car < 600.0) {
            val st = g.update(r.laneSites, car, avoidLaneChanges = true, keepRight = true)
            val atB = st.plan.firstOrNull { it.site == b }
            if (atB != null) assertEquals("car $car", 0, atB.recommended)
            st.pendingChange?.let { ch ->
                val dir = Integer.signum(ch.lanes)
                if (lastDir != 0) assertEquals("lane change reverses at car $car", lastDir, dir)
                lastDir = dir
            }
            car += 10.0
        }
        assertTrue(lastDir >= 0)                              // only ever towards the left-turn lane
    }

    /** OSM lanes at a junction the patch says are unknown: no site, not even from OSM `turn:lanes`, and no advice. */
    @Test
    fun lanesUnknownPatchRemovesTheJunctionsLanes() {
        val r = route(listOf(siteA, osmB, siteC)).withLaneOverrides(listOf(LaneOverrides.At(610.0, null, "u")))
            .withOsmTurnLanes(listOf(605.0 to "left|left|through|right"))
        assertEquals(listOf(300.0, 900.0), r.laneSites.map { it.distanceAlong })
        assertEquals(listOf(600.0), r.unknownLanesAt)

        val engine = NavigationEngine(r)
        val phrases = Phrases(AppLanguage.ENGLISH)
        fun at(d: Double) = engine.update(Fix(r.geometry.pointAt(d), 12f, r.geometry.bearingAt(d).toFloat(), 5f, d.toLong() * 100),
            AppSettings(), phrases).first
        assertTrue(at(250.0).lanesUnknown)                    // within 400 m of the unknown junction
        val before = at(320.0)
        assertTrue(before.lanesUnknown)
        assertNull(before.lanes.nextAdvice)                   // nothing recommended across it
        assertFalse(at(650.0).lanesUnknown)                   // passed: the right turn's lanes are known again
    }

    /** A patch whose lanes cannot make the route's turn (other direction, outdated) is not trusted. */
    @Test
    fun patchThatCannotMakeTheTurnIsUnknown() {
        val r = route(listOf(siteA, osmB, siteC)).withLaneOverrides(listOf(LaneOverrides.At(600.0, "through|through;right", "w")))
        assertTrue(r.laneSites.none { it.distanceAlong == 600.0 })
        assertEquals(listOf(600.0), r.unknownLanesAt)
    }

    /** A patch where OSRM had no lanes adds the junction, with the route's own turn there. */
    @Test
    fun patchAddsAMissingJunction() {
        val r = route(listOf(siteA, siteC)).withLaneOverrides(listOf(LaneOverrides.At(600.0, "reverse;left|left|right|right|right", "aeg")))
        val b = r.laneSites.single { it.distanceAlong == 600.0 }
        assertEquals(L, b.turn)
        assertTrue(b.isManeuver)
        assertArrayEquals(booleanArrayOf(true, true, false, false, false), b.allowed())
        // Applying the same patch again (next corridor fetch) changes nothing.
        assertEquals(r.laneSites, r.withLaneOverrides(listOf(LaneOverrides.At(600.0, "reverse;left|left|right|right|right", "aeg"))).laneSites)
    }

    /** A U-turn at a junction whose lanes cannot make it: "No lane data", not the provider's straight lane. */
    @Test
    fun uTurnWithoutAUsableLaneIsUnknown() {
        val base = route(listOf(siteA))
        val steps = base.steps.toMutableList()
        steps[2] = steps[2].copy(maneuver = steps[2].maneuver.copy(type = ManeuverType.CONTINUE, modifier = Modifier.UTURN))
        val noLeft = LaneSite(900.0, listOf(S, S or R), listOf(true, false), LaneDir.UTURN, true, RoadKind.URBAN)
        val r = base.copy(steps = steps, laneSites = listOf(siteA, noLeft))
        val engine = NavigationEngine(r)
        val d = 800.0
        val snap = engine.update(Fix(r.geometry.pointAt(d), 12f, r.geometry.bearingAt(d).toFloat(), 5f, 1000), AppSettings(),
            Phrases(AppLanguage.ENGLISH)).first
        assertTrue(snap.lanesUnknown)
        assertNull(snap.lanes.nextAdvice)
    }

    /** The shipped patches are all valid: a rejected entry would silently leave the wrong OSM lanes in place. */
    @Test
    fun shippedOverridesAreValid() {
        val o = LaneOverrides.parse(java.io.File("src/main/assets/lane_overrides.json").readText())
        assertEquals(emptyList<String>(), o.rejected)
        val celler = o.entries.single { it.id == "celler-strasse-nb-herschelstrasse" }
        val t = o.apply(166391411, mapOf("highway" to "secondary", "oneway" to "yes", "lanes" to "2", "turn:lanes" to "left|through|through"))
        assertEquals("left|through", t["turn:lanes:forward"])
        assertEquals(2, OsmTags.lanesInDirection(t, forward = true, oneway = true))
        assertEquals(celler.id, t["${LaneOverrides.MARK}:forward"])
    }
}
