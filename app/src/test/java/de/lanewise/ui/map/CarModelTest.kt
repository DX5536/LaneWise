package de.lanewise.ui.map

import de.lanewise.core.geo.Geo
import de.lanewise.core.geo.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CarModelTest {
    private val c = LatLon(52.3759, 9.7320)

    @Test
    fun carPointsAlongTheBearingAndScales() {
        for (bearing in listOf(0.0, 37.0, 200.0)) {
            val blocks = CarModel.blocks(c, bearing, 1.0)
            val head = blocks.filter { it.part == CarModel.Part.HEADLIGHT }.flatMap { it.ring }
            val tail = blocks.filter { it.part == CarModel.Part.TAILLIGHT }.flatMap { it.ring }
            fun mid(p: List<LatLon>) = LatLon(p.map { it.lat }.average(), p.map { it.lon }.average())
            // Headlights are in front of the centre, tail lights behind.
            assertEquals(0.0, Geo.bearingDelta(bearing, Geo.bearing(c, mid(head))), 2.0)
            assertEquals(0.0, Geo.bearingDelta(bearing + 180, Geo.bearing(c, mid(tail))), 2.0)
        }
        val body1 = CarModel.blocks(c, 0.0, 1.0).first { it.part == CarModel.Part.BODY }
        val body2 = CarModel.blocks(c, 0.0, 2.0).first { it.part == CarModel.Part.BODY }
        fun length(b: CarModel.Block) = (b.ring.maxOf { it.lat } - b.ring.minOf { it.lat }) * 111_195.0
        assertEquals(4.6, length(body1), 0.1)
        assertEquals(9.2, length(body2), 0.2)
        assertEquals(2 * body1.top, body2.top, 1e-9)
        // Rings are closed (MapLibre polygons).
        assertTrue(CarModel.blocks(c, 10.0).all { it.ring.first() == it.ring.last() })
    }
}
