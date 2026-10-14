package de.lanewise.osm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

class AerialLanesTest {

    private val aerial = AerialLanes.parse(
        """{"v":1,"source":"test","fields":["osm_way_id","lanes","confidence_pct"],
           "ways":[[100,3,80],[200,4,95],[300,2,75],[400,4,100]]}""",
    )

    @Test
    fun parsesAsset() {
        assertEquals(4, aerial.size)
        assertEquals(AerialLanes.Entry(4, 95), aerial[200])
    }

    @Test
    fun fillsMissingOsmLanes() {
        val t = aerial.apply(100, mapOf("highway" to "secondary"))
        assertEquals("3", t["lanes"])
        assertEquals("aerial", t[AerialLanes.SOURCE_TAG])
    }

    @Test
    fun overridesDisagreeingOsmOnlyWhenVeryConfident() {
        val osm = mapOf("highway" to "primary", "lanes" to "2")
        assertEquals("4", aerial.apply(200, osm)["lanes"])            // 95 % >= 90 %
        assertSame(osm, aerial.apply(300, osm))                        // photos agree (2)
        val weak = mapOf("highway" to "primary", "lanes" to "3")
        assertSame(weak, aerial.apply(300, weak))                      // 75 % keeps OSM
    }

    @Test
    fun neverTouchesWaysWithTurnLanes() {
        val t = mapOf("highway" to "primary", "turn:lanes:forward" to "left|through", "lanes" to "3")
        assertSame(t, aerial.apply(400, t))
        val noCount = mapOf("highway" to "primary", "turn:lanes" to "left|through")
        assertSame(noCount, aerial.apply(100, noCount))
    }

    @Test
    fun dropsInconsistentDirectionalCounts() {
        val t = aerial.apply(400, mapOf("lanes" to "2", "lanes:forward" to "1", "lanes:backward" to "1"))
        assertEquals("4", t["lanes"])
        assertFalse(t.containsKey("lanes:forward"))
        assertFalse(t.containsKey("lanes:backward"))
    }

    @Test
    fun unknownWayIsUnchanged() {
        val st = OsmStreet(emptyList(), mapOf("highway" to "residential"), id = 999)
        assertSame(st, aerial.apply(st))
    }
}
