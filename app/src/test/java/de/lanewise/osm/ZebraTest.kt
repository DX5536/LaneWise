package de.lanewise.osm

import de.lanewise.core.geo.LatLon
import de.lanewise.core.model.FeatureKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Zebra stripes only where Germany paints them. Checked against LGLN DOP20 aerial photos (2025) of 16 random crossings
 * per class in Hannover: zebra-tagged 15/16 visible (one in shadow), `marked`/`uncontrolled` 0/16 (red cycle crossings,
 * dashed edges), crossings at traffic lights 0/16.
 */
class ZebraTest {
    private fun f(vararg kv: Pair<String, String>) = OsmTags.feature(mapOf(*kv), LatLon(52.37, 9.73), 0.0)

    @Test
    fun onlyRealZebrasGetStripes() {
        assertTrue(f("highway" to "crossing", "crossing" to "zebra")!!.zebra)
        assertTrue(f("highway" to "crossing", "crossing" to "uncontrolled", "crossing_ref" to "zebra")!!.zebra)
        assertTrue(f("highway" to "crossing", "crossing" to "marked", "crossing:markings" to "zebra")!!.zebra)
        assertTrue(f("highway" to "crossing", "crossing" to "marked", "traffic_sign" to "DE:350")!!.zebra)
        // Marked crossings in Hannover are mostly red cycle crossings with dashed edges — no stripes.
        val marked = f("highway" to "crossing", "crossing" to "marked", "crossing:markings" to "dashes")!!
        assertEquals(FeatureKind.CROSSING, marked.kind)
        assertFalse(marked.zebra)
        // At traffic lights: a signal, never a zebra.
        val lights = f("highway" to "crossing", "crossing" to "traffic_signals")!!
        assertEquals(FeatureKind.TRAFFIC_SIGNALS, lights.kind)
        assertFalse(lights.zebra)
        // The flag survives the offline graph encoding.
        val z = f("highway" to "crossing", "crossing" to "zebra")!!
        assertTrue(de.lanewise.core.model.RoadFeature.decode(z.encode().toByte().toInt() and 0xFF, z.position, 0.0).zebra)
    }
}
