package de.lanewise.traffic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Lane pictures of Autobahn GmbH roadworks (`impact.symbols`), as returned for the A2 on 2026-10-15. */
class AutobahnLanesTest {
    private fun open(vararg s: String) = AutobahnApiSource.laneOpen(s.toList())

    @Test
    fun readsTheAffectedCarriageway() {
        assertEquals(listOf(false, true, true), open("SEPARATE", "CLOSED", "ARROW_UP", "ARROW_UP", "BORDER_RIGHT", "BREAKDOWN_LANE"))
        assertEquals(listOf(true, true, false), open("SEPARATE", "ARROW_UP", "ARROW_UP", "CLOSED", "BORDER_RIGHT", "CLOSED"))
        // Both carriageways pictured: the group after the last SEPARATE counts.
        assertEquals(listOf(true, true, false), open("CLOSED", "BORDER_LEFT", "CLOSED", "ARROW_DOWN", "ARROW_DOWN", "SEPARATE",
            "ARROW_UP", "ARROW_UP", "CLOSED", "BORDER_RIGHT", "CLOSED"))
    }

    @Test
    fun nothingWhenNoLaneIsClosedOrTrafficIsMovedOver() {
        assertNull(open("SEPARATE", "ARROW_UP", "ARROW_UP", "ARROW_UP", "BORDER_RIGHT", "CLOSED"))   // only the shoulder
        assertNull(open("ARROW_DOWN", "BORDER_LEFT", "ARROW_DOWN", "SEPARATE", "ARROW_UP", "ARROW_UP", "SEPARATE",
            "CLOSED", "CLOSED", "BORDER_RIGHT", "CLOSED"))                                                // crossover
        assertNull(open("SEPARATE", "ARROW_UP"))
        assertNull(open())
    }
}
