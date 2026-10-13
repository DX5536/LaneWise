package de.lanewise.nav

import de.lanewise.core.model.ManeuverType
import de.lanewise.core.model.Modifier
import de.lanewise.core.model.RouteStep
import de.lanewise.lane.LaneChangeInstruction
import de.lanewise.settings.VoiceLanguage
import kotlin.math.abs
import kotlin.math.roundToInt

/** Human-readable instructions (banner + voice) in German or English. */
class Phrases(private val lang: VoiceLanguage) {
    private val de get() = lang == VoiceLanguage.GERMAN

    fun distance(m: Double): String = when {
        m >= 10_000 -> "${(m / 1000).roundToInt()} km"
        m >= 1000 -> String.format(if (de) java.util.Locale.GERMANY else java.util.Locale.UK, "%.1f km", m / 1000)
        m >= 100 -> "${((m / 50).roundToInt() * 50)} m"
        else -> "${((m / 10).roundToInt() * 10).coerceAtLeast(10)} m"
    }

    fun spokenDistance(m: Double): String = when {
        m >= 1000 -> if (de) "In ${distance(m).replace(" km", " Kilometern")}" else "In ${distance(m).replace(" km", " kilometres")}"
        else -> if (de) "In ${distance(m).replace(" m", " Metern")}" else "In ${distance(m).replace(" m", " metres")}"
    }

    private fun dir(m: Modifier): String = if (de) when (m) {
        Modifier.UTURN -> "wenden"
        Modifier.SHARP_RIGHT -> "scharf rechts abbiegen"
        Modifier.RIGHT -> "rechts abbiegen"
        Modifier.SLIGHT_RIGHT -> "leicht rechts abbiegen"
        Modifier.STRAIGHT -> "geradeaus fahren"
        Modifier.SLIGHT_LEFT -> "leicht links abbiegen"
        Modifier.LEFT -> "links abbiegen"
        Modifier.SHARP_LEFT -> "scharf links abbiegen"
    } else when (m) {
        Modifier.UTURN -> "make a U-turn"
        Modifier.SHARP_RIGHT -> "turn sharp right"
        Modifier.RIGHT -> "turn right"
        Modifier.SLIGHT_RIGHT -> "bear right"
        Modifier.STRAIGHT -> "continue straight"
        Modifier.SLIGHT_LEFT -> "bear left"
        Modifier.LEFT -> "turn left"
        Modifier.SHARP_LEFT -> "turn sharp left"
    }

    private fun side(m: Modifier) = when (m) {
        Modifier.SLIGHT_LEFT, Modifier.LEFT, Modifier.SHARP_LEFT, Modifier.UTURN -> if (de) "links" else "left"
        else -> if (de) "rechts" else "right"
    }

    /** Main instruction for a step, e.g. "Rechts abbiegen auf Celler Straße". */
    fun instruction(step: RouteStep): String {
        val m = step.maneuver
        val road = listOfNotNull(step.ref, step.name.takeIf { it.isNotBlank() }).joinToString(" ").ifBlank { null }
        val onto = road?.let { if (de) " auf $it" else " onto $it" } ?: ""
        val toward = step.destinations?.let { if (de) " Richtung $it" else " towards $it" } ?: ""
        return when (m.type) {
            ManeuverType.DEPART -> if (de) "Starten$onto" else "Head out$onto"
            ManeuverType.ARRIVE -> if (de) "Sie haben Ihr Ziel erreicht" else "You have arrived"
            ManeuverType.ON_RAMP -> (if (de) "Auffahren$onto" else "Take the ramp$onto") + toward
            ManeuverType.OFF_RAMP -> {
                val exit = step.exitNumber?.let { if (de) " Ausfahrt $it" else " exit $it" } ?: ""
                (if (de) "${side(m.modifier).replaceFirstChar { it.uppercase() }} halten zur$exit" else "Take$exit on the ${side(m.modifier)}") + toward
            }
            ManeuverType.FORK -> (if (de) "An der Gabelung ${side(m.modifier)} halten" else "Keep ${side(m.modifier)} at the fork") + onto + toward
            ManeuverType.MERGE -> if (de) "Einfädeln$onto" else "Merge$onto"
            ManeuverType.ROUNDABOUT -> {
                val n = m.exit
                if (de) "Im Kreisverkehr ${n?.let { "die $it. Ausfahrt" } ?: "die Ausfahrt"} nehmen$onto"
                else "At the roundabout take the ${n?.let { ordinal(it) } ?: ""} exit$onto"
            }
            ManeuverType.EXIT_ROUNDABOUT -> if (de) "Kreisverkehr verlassen$onto" else "Exit the roundabout$onto"
            ManeuverType.END_OF_ROAD -> if (de) "Am Ende der Straße ${dir(m.modifier)}$onto" else "At the end of the road ${dir(m.modifier)}$onto"
            ManeuverType.NEW_NAME, ManeuverType.CONTINUE ->
                if (m.modifier == Modifier.STRAIGHT) (if (de) "Weiter$onto" else "Continue$onto")
                else dir(m.modifier).replaceFirstChar { it.uppercase() } + onto
            else -> dir(m.modifier).replaceFirstChar { it.uppercase() } + onto
        }
    }

    fun spoken(step: RouteStep, distM: Double?): String {
        val core = instruction(step)
        return if (distM == null) core else "${spokenDistance(distM)} ${core.replaceFirstChar { it.lowercase() }}"
    }

    fun laneChange(c: LaneChangeInstruction, laneCount: Int): String {
        val n = abs(c.lanes)
        val toLeft = c.lanes > 0
        val target = c.targetLane
        val fromRight = laneCount - target
        val pos = if (de) {
            when {
                target == 0 -> "die linke Spur"
                target == laneCount - 1 -> "die rechte Spur"
                target + 1 <= fromRight -> "die ${target + 1}. Spur von links"
                else -> "die $fromRight. Spur von rechts"
            }
        } else {
            when {
                target == 0 -> "the left lane"
                target == laneCount - 1 -> "the right lane"
                target + 1 <= fromRight -> "the ${ordinal(target + 1)} lane from the left"
                else -> "the ${ordinal(fromRight)} lane from the right"
            }
        }
        return if (de) {
            val how = if (n == 1) (if (toLeft) "eine Spur nach links" else "eine Spur nach rechts") else "$n Spuren nach ${if (toLeft) "links" else "rechts"}"
            "Wechseln Sie $how auf $pos"
        } else {
            val how = if (n == 1) "one lane to the ${if (toLeft) "left" else "right"}" else "$n lanes to the ${if (toLeft) "left" else "right"}"
            "Move $how into $pos"
        }
    }

    fun stayInLane(): String = if (de) "Bleiben Sie auf Ihrer Spur" else "Stay in your lane"

    fun rerouting(): String = if (de) "Route wird neu berechnet" else "Recalculating route"

    fun speedWarning(): String = if (de) "Achtung, Geschwindigkeit" else "Watch your speed"

    private fun ordinal(n: Int) = when {
        n % 100 in 11..13 -> "${n}th"
        n % 10 == 1 -> "${n}st"
        n % 10 == 2 -> "${n}nd"
        n % 10 == 3 -> "${n}rd"
        else -> "${n}th"
    }
}
