package de.lanewise.nav

import de.lanewise.core.geo.LatLon
import de.lanewise.core.model.Maneuver
import de.lanewise.core.model.ManeuverType
import de.lanewise.core.model.Modifier
import de.lanewise.core.model.RouteStep
import de.lanewise.lane.LaneChangeInstruction
import de.lanewise.lane.LaneDir
import de.lanewise.lane.LaneSite
import de.lanewise.lane.RoadKind
import de.lanewise.settings.AppLanguage
import java.util.Locale

/**
 * Every fixed voice segment [Phrases] can produce, per language — the script list for OmniVoice voice packs
 * (`tools/voice_pack/inventory/<lang>.txt`, kept in sync by `VoiceInventoryTest`). Street names, refs and exit
 * numbers come from OSM on the PC side; anything not in a pack is spoken by Android TTS.
 */
object VoiceInventory {

    fun phrases(lang: AppLanguage): List<String> {
        val p = Phrases(lang)
        val out = LinkedHashSet<String>()
        out += p.allSpokenDistances()
        val here = LatLon(0.0, 0.0)
        for (type in ManeuverType.entries) for (mod in Modifier.entries) {
            val exits = if (type == ManeuverType.ROUNDABOUT) listOf(null) + (1..8) else listOf(null)
            for (exit in exits) for (onto in listOf(false, true)) {
                val step = RouteStep(0.0, 0.0, 0.0, Maneuver(type, mod, here, 0.0, 0.0, exit), "", null, null, null)
                out += p.core(step, onto)
            }
        }
        out += p.instructionParts(RouteStep(0.0, 0.0, 0.0,
            Maneuver(ManeuverType.ON_RAMP, Modifier.STRAIGHT, here, 0.0, 0.0), "", null, "X", null))[1]   // "Richtung"
        for (count in 1..8) {
            val site = LaneSite(0.0, List(count) { LaneDir.STRAIGHT }, null, LaneDir.STRAIGHT, false, RoadKind.URBAN)
            for (target in 0 until count) for (lanes in -4..4) {
                if (lanes == 0) continue
                out += p.laneChange(LaneChangeInstruction(0.0, site, lanes, target), count)
            }
        }
        out += p.stayInLane()
        out += p.rerouting()
        out += p.speedWarning()
        return out.toList()
    }

    /** Lookup key shared with the PC renderer: lower case, single spaces, no trailing full stop. */
    fun normalize(s: String): String =
        s.lowercase(Locale.ROOT).trim().split(Regex("\\s+")).joinToString(" ").trimEnd('.', '!')

    /**
     * The segments of an announcement a voice pack can say in its own voice ([has]: a clip exists): all of them, or -
     * when a road name / ref / destination is missing (streets outside the pack's region) - the sentence without it:
     * the preposition before the road ("onto" / "auf" / "vào") goes too, and so does "towards" before a missing
     * destination ("In 300 metres turn right" instead of the whole sentence by Android TTS; the name is on screen).
     * Null if a fixed phrase itself is missing: then Android TTS says the whole sentence (never two voices in one).
     */
    fun sayable(parts: List<String>, has: (String) -> Boolean): List<String>? {
        if (parts.isEmpty()) return null
        if (parts.all(has)) return parts
        // Only a name may be left out: the first missing segment must follow "onto" / "auf" / "vào" or "towards".
        val first = parts.indexOfFirst { !has(it) }
        if (first == 0) return null
        val kept = parts.take(first).toMutableList()
        if (normalize(kept.last()) in TOWARDS) {
            kept.removeAt(kept.lastIndex)
            return kept.takeIf { it.isNotEmpty() }
        }
        val last = kept.last().trimEnd('.', '!')
        val prep = PREPOSITIONS.firstOrNull { normalize(last).endsWith(it) } ?: return null
        val bare = last.substring(0, last.length - prep.length).trimEnd()
        if (!has(bare)) return null
        kept[kept.lastIndex] = bare
        return kept
    }

    private val TOWARDS = setOf("richtung", "towards", "hướng")
    private val PREPOSITIONS = listOf(" auf", " onto", " vào")

    fun code(lang: AppLanguage) = when (lang) {
        AppLanguage.GERMAN -> "de"
        AppLanguage.ENGLISH -> "en"
        AppLanguage.VIETNAMESE -> "vi"
    }
}
