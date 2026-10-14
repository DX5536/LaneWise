package de.lanewise.osm

import de.lanewise.core.geo.LatLon
import de.lanewise.lane.LaneDir
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LaneReportsTest {

    @Test
    fun formatsTurnLanesLikeOsm() {
        val lanes = LaneDir.parseTurnLanes("left|through|through;right")
        assertEquals("left|through|through;right", LaneReports.turnLanes(lanes))
        assertEquals("none|through", LaneReports.turnLanes(listOf(LaneDir.NONE, LaneDir.STRAIGHT)))
    }

    @Test
    fun appendsToAValidFeatureCollection() {
        val dir = File(System.getProperty("java.io.tmpdir"), "lw-reports-${System.nanoTime()}")
        val f = File(dir, "lane_reports.geojson")
        try {
            val r = LaneReports.Report(LatLon(52.3785, 9.7341), 0L, "Celler \"Straße\"", "left|through", 345)
            assertEquals(1, LaneReports.append(f, r))
            assertEquals(2, LaneReports.append(f, r.copy(road = "")))
            val text = f.readText()
            assertTrue(text.startsWith("{\"type\":\"FeatureCollection\""))
            assertTrue(text.contains("[9.7341000,52.3785000]"))
            assertTrue(text.contains("Celler \\\"Straße\\\""))
            assertEquals(2, LaneReports.read(f).size)
        } finally { dir.deleteRecursively() }
    }
}
