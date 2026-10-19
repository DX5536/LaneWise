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

    /** Owner: "OmniVoice lacks a lot of lines" - a street outside the pack no longer sends the whole sentence to TTS. */
    @Test
    fun packSaysTheSentenceWithoutAMissingName() {
        val pack = setOf("in 300 metres", "turn right onto", "turn right", "keep left at the fork onto", "keep left at the fork",
            "a 7", "towards")
        val has = { p: String -> VoiceInventory.normalize(p) in pack }
        // Everything there: unchanged.
        assertEquals(listOf("In 300 metres", "turn right onto", "A 7"),
            VoiceInventory.sayable(listOf("In 300 metres", "turn right onto", "A 7"), has))
        // Street missing: without "onto" and the name.
        assertEquals(listOf("In 300 metres", "turn right"),
            VoiceInventory.sayable(listOf("In 300 metres", "turn right onto", "Musterstraße"), has))
        // Destination missing: without "towards" and the destination.
        assertEquals(listOf("Keep left at the fork onto", "A 7"),
            VoiceInventory.sayable(listOf("Keep left at the fork onto", "A 7", "towards", "Musterstadt"), has))
        // A fixed phrase missing: null (Android TTS says the whole sentence).
        assertEquals(null, VoiceInventory.sayable(listOf("In 300 metres", "turn sharp left onto", "A 7"), has))
        assertEquals(null, VoiceInventory.sayable(listOf("Make a U-turn"), has))
        // German / Vietnamese prepositions.
        val de = setOf("rechts abbiegen")
        assertEquals(listOf("Rechts abbiegen"),
            VoiceInventory.sayable(listOf("Rechts abbiegen auf", "Musterstraße")) { VoiceInventory.normalize(it) in de + "rechts abbiegen auf" })
    }
}
