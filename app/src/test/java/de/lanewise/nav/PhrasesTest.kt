package de.lanewise.nav

import de.lanewise.lane.LaneChangeInstruction
import de.lanewise.lane.LaneDir
import de.lanewise.lane.LaneSite
import de.lanewise.lane.RoadKind
import de.lanewise.settings.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Test

/** The short on-screen lane hint names the same direction, count and target lane as the spoken sentence. */
class PhrasesTest {
    private val site = LaneSite(500.0, List(5) { LaneDir.STRAIGHT }, null, LaneDir.STRAIGHT, false, RoadKind.URBAN)

    @Test
    fun shortHintMatchesTheSpokenOne() {
        val left3 = LaneChangeInstruction(100.0, site, lanes = 3, targetLane = 1)
        assertEquals("Move 3 lanes to the left into the 2nd lane from the left", Phrases(AppLanguage.ENGLISH).laneChange(left3, 5))
        assertEquals("← 3 lanes · 2nd from left", Phrases(AppLanguage.ENGLISH).laneChangeShort(left3, 5))
        assertEquals("← 3 Spuren · 2. von links", Phrases(AppLanguage.GERMAN).laneChangeShort(left3, 5))
        val right1 = LaneChangeInstruction(100.0, site, lanes = -1, targetLane = 4)
        assertEquals("→ 1 lane · right lane", Phrases(AppLanguage.ENGLISH).laneChangeShort(right1, 5))
        assertEquals("→ 1 làn · làn phải", Phrases(AppLanguage.VIETNAMESE).laneChangeShort(right1, 5))
        val right2 = LaneChangeInstruction(100.0, site, lanes = -2, targetLane = 3)
        assertEquals("→ 2 lanes · 2nd from right", Phrases(AppLanguage.ENGLISH).laneChangeShort(right2, 5))
    }
}
