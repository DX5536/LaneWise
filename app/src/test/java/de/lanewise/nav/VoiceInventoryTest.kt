package de.lanewise.nav

import de.lanewise.settings.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Keeps `tools/voice_pack/inventory/<lang>.txt` (what the PC renders with OmniVoice) in sync with [Phrases].
 * After changing a phrase run: `gradlew testDebugUnitTest -PupdateVoiceInventory` and re-render the packs.
 */
class VoiceInventoryTest {

    private val dir = File("../tools/voice_pack/inventory")

    @Test
    fun inventoryFilesAreUpToDate() {
        val update = System.getProperty("updateVoiceInventory") == "true"
        for (lang in AppLanguage.entries) {
            val expected = VoiceInventory.phrases(lang).joinToString("\n", postfix = "\n")
            val f = File(dir, "${VoiceInventory.code(lang)}.txt")
            if (update || !f.exists()) {
                dir.mkdirs(); f.writeText(expected)
            }
            assertEquals("Voice inventory ${f.name} is stale - run with -PupdateVoiceInventory", expected, f.readText())
        }
    }

    @Test
    fun announcementsAreBuiltFromInventorySegments() {
        for (lang in AppLanguage.entries) {
            val inv = VoiceInventory.phrases(lang).map(VoiceInventory::normalize).toSet()
            val p = Phrases(lang)
            val step = de.lanewise.core.model.RouteStep(0.0, 0.0, 0.0,
                de.lanewise.core.model.Maneuver(de.lanewise.core.model.ManeuverType.TURN, de.lanewise.core.model.Modifier.LEFT,
                    de.lanewise.core.geo.LatLon(0.0, 0.0), 0.0, 0.0), "Celler Straße", "L 190", null, null)
            val parts = p.spokenParts(step, 300.0)
            assertEquals(listOf("L 190", "Celler Straße"), parts.takeLast(2))
            parts.dropLast(2).forEach { assertTrue("$lang: '$it' missing", VoiceInventory.normalize(it) in inv) }
            assertEquals(p.spoken(step, 300.0), parts.joinToString(" "))
        }
    }
}
