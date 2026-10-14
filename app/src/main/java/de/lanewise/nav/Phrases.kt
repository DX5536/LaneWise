package de.lanewise.nav

import de.lanewise.core.model.ManeuverType
import de.lanewise.core.model.Modifier
import de.lanewise.core.model.RouteStep
import de.lanewise.lane.LaneChangeInstruction
import de.lanewise.settings.AppLanguage
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Human-readable instructions (banner + voice) in German, English or Vietnamese. */
class Phrases(private val lang: AppLanguage) {

    /** Pick the text for the current language. */
    private fun t(de: String, en: String, vi: String) = when (lang) {
        AppLanguage.GERMAN -> de
        AppLanguage.ENGLISH -> en
        AppLanguage.VIETNAMESE -> vi
    }

    private val locale: Locale = when (lang) {
        AppLanguage.GERMAN -> Locale.GERMANY
        AppLanguage.ENGLISH -> Locale.UK
        AppLanguage.VIETNAMESE -> Locale.forLanguageTag("vi-VN")
    }

    fun distance(m: Double): String = when {
        m >= 10_000 -> "${(m / 1000).roundToInt()} km"
        m >= 1000 -> String.format(locale, "%.1f km", m / 1000)
        m >= 100 -> "${((m / 50).roundToInt() * 50)} m"
        else -> "${((m / 10).roundToInt() * 10).coerceAtLeast(10)} m"
    }

    fun spokenDistance(m: Double): String {
        val d = distance(m)
        return if (m >= 1000) {
            val n = d.removeSuffix(" km")
            t("In $n Kilometern", "In $n kilometres", "Sau $n ki-lô-mét")
        } else {
            val n = d.removeSuffix(" m")
            t("In $n Metern", "In $n metres", "Sau $n mét")
        }
    }

    private fun dir(m: Modifier): String = when (m) {
        Modifier.UTURN -> t("wenden", "make a U-turn", "quay đầu")
        Modifier.SHARP_RIGHT -> t("scharf rechts abbiegen", "turn sharp right", "rẽ gắt sang phải")
        Modifier.RIGHT -> t("rechts abbiegen", "turn right", "rẽ phải")
        Modifier.SLIGHT_RIGHT -> t("leicht rechts abbiegen", "bear right", "chếch sang phải")
        Modifier.STRAIGHT -> t("geradeaus fahren", "continue straight", "đi thẳng")
        Modifier.SLIGHT_LEFT -> t("leicht links abbiegen", "bear left", "chếch sang trái")
        Modifier.LEFT -> t("links abbiegen", "turn left", "rẽ trái")
        Modifier.SHARP_LEFT -> t("scharf links abbiegen", "turn sharp left", "rẽ gắt sang trái")
    }

    private fun isLeft(m: Modifier) = m == Modifier.SLIGHT_LEFT || m == Modifier.LEFT || m == Modifier.SHARP_LEFT || m == Modifier.UTURN
    private fun side(m: Modifier) = if (isLeft(m)) t("links", "left", "trái") else t("rechts", "right", "phải")

    private fun cap(s: String) = s.replaceFirstChar { it.uppercase(locale) }

    /** Main instruction for a step, e.g. "Rechts abbiegen auf Celler Straße". */
    fun instruction(step: RouteStep): String = instructionParts(step).joinToString(" ")

    /**
     * The instruction as voice segments: a fixed phrase (with "auf"/"onto"/"vào" when a road follows), then the
     * road ref and name, then "Richtung" + destination. Each segment is one clip in an OmniVoice voice pack
     * (see tools/voice_pack); [de.lanewise.nav.VoiceGuide] chains them.
     */
    fun instructionParts(step: RouteStep): List<String> {
        val m = step.maneuver
        val road = listOfNotNull(step.ref?.takeIf { it.isNotBlank() }, step.name.takeIf { it.isNotBlank() })
        val toward = step.destinations?.takeIf { it.isNotBlank() }?.let { listOf(t("Richtung", "towards", "hướng"), it) } ?: emptyList()
        return when (m.type) {
            ManeuverType.ARRIVE -> listOf(core(step, false))
            ManeuverType.OFF_RAMP -> listOf(core(step, false)) + listOfNotNull(step.exitNumber?.takeIf { it.isNotBlank() }) + toward
            ManeuverType.ON_RAMP, ManeuverType.FORK -> listOf(core(step, road.isNotEmpty())) + road + toward
            else -> listOf(core(step, road.isNotEmpty())) + road
        }
    }

    /** The fixed phrase of a manoeuvre; [onto] appends the preposition before a road name. */
    fun core(step: RouteStep, onto: Boolean): String {
        val m = step.maneuver
        val on = if (onto) t(" auf", " onto", " vào") else ""
        return when (m.type) {
            ManeuverType.DEPART -> t("Starten", "Head out", "Xuất phát") + on
            ManeuverType.ARRIVE -> t("Sie haben Ihr Ziel erreicht", "You have arrived", "Bạn đã đến nơi")
            ManeuverType.ON_RAMP -> t("Auffahren", "Take the ramp", "Đi vào đường dẫn") + on
            ManeuverType.OFF_RAMP -> t(
                "${cap(side(m.modifier))} halten zur Ausfahrt",
                "Take the exit on the ${side(m.modifier)}",
                "Đi theo lối ra bên ${side(m.modifier)}",
            )
            ManeuverType.FORK -> t(
                "An der Gabelung ${side(m.modifier)} halten",
                "Keep ${side(m.modifier)} at the fork",
                "Tại ngã ba, đi về bên ${side(m.modifier)}",
            ) + on
            ManeuverType.MERGE -> t("Einfädeln", "Merge", "Nhập làn") + on
            ManeuverType.ROUNDABOUT -> {
                val n = m.exit
                t(
                    "Im Kreisverkehr ${n?.let { "die $it. Ausfahrt" } ?: "die Ausfahrt"} nehmen",
                    "At the roundabout take the ${n?.let { ordinal(it) + " " } ?: ""}exit",
                    "Tại vòng xuyến, đi lối ra${n?.let { " thứ $it" } ?: ""}",
                ) + on
            }
            ManeuverType.EXIT_ROUNDABOUT -> t("Kreisverkehr verlassen", "Exit the roundabout", "Ra khỏi vòng xuyến") + on
            ManeuverType.END_OF_ROAD -> t(
                "Am Ende der Straße ${dir(m.modifier)}",
                "At the end of the road ${dir(m.modifier)}",
                "Cuối đường, ${dir(m.modifier)}",
            ) + on
            ManeuverType.NEW_NAME, ManeuverType.CONTINUE ->
                (if (m.modifier == Modifier.STRAIGHT) t("Weiter", "Continue", "Tiếp tục") else cap(dir(m.modifier))) + on
            else -> cap(dir(m.modifier)) + on
        }
    }

    fun spoken(step: RouteStep, distM: Double?): String = spokenParts(step, distM).joinToString(" ")

    /** Voice segments of an announcement: distance phrase (Vietnamese adds the comma there) + instruction parts. */
    fun spokenParts(step: RouteStep, distM: Double?): List<String> {
        val parts = instructionParts(step)
        if (distM == null) return parts
        val dist = spokenDistance(distM) + if (lang == AppLanguage.VIETNAMESE) "," else ""
        return listOf(dist, parts.first().replaceFirstChar { it.lowercase(locale) }) + parts.drop(1)
    }

    /** Every distance phrase [spokenDistance] can produce (for voice packs). */
    fun allSpokenDistances(): List<String> {
        val metres = (1..9).map { it * 10.0 } + (2..20).map { it * 50.0 }
        val km = (10..99).map { it * 100.0 } + (10..60).map { it * 1000.0 }
        return (metres + km).map { spokenDistance(it) + if (lang == AppLanguage.VIETNAMESE) "," else "" }.distinct()
    }

    fun laneChange(c: LaneChangeInstruction, laneCount: Int): String {
        val n = abs(c.lanes)
        val toLeft = c.lanes > 0
        val target = c.targetLane
        val fromRight = laneCount - target
        val fromLeftFirst = target + 1 <= fromRight
        val pos = when {
            target == 0 -> t("die linke Spur", "the left lane", "làn ngoài cùng bên trái")
            target == laneCount - 1 -> t("die rechte Spur", "the right lane", "làn ngoài cùng bên phải")
            fromLeftFirst -> t("die ${target + 1}. Spur von links", "the ${ordinal(target + 1)} lane from the left", "làn thứ ${target + 1} từ bên trái")
            else -> t("die $fromRight. Spur von rechts", "the ${ordinal(fromRight)} lane from the right", "làn thứ $fromRight từ bên phải")
        }
        val dirWord = if (toLeft) t("links", "left", "trái") else t("rechts", "right", "phải")
        return t(
            "Wechseln Sie ${if (n == 1) "eine Spur" else "$n Spuren"} nach $dirWord auf $pos",
            "Move ${if (n == 1) "one lane" else "$n lanes"} to the $dirWord into $pos",
            "Chuyển $n làn sang $dirWord, vào $pos",
        )
    }

    fun stayInLane(): String = t("Bleiben Sie auf Ihrer Spur", "Stay in your lane", "Giữ nguyên làn đường")

    fun rerouting(): String = t("Route wird neu berechnet", "Recalculating route", "Đang tính lại lộ trình")

    fun speedWarning(): String = t("Achtung, Geschwindigkeit", "Watch your speed", "Chú ý tốc độ")

    private fun ordinal(n: Int) = when {
        n % 100 in 11..13 -> "${n}th"
        n % 10 == 1 -> "${n}st"
        n % 10 == 2 -> "${n}nd"
        n % 10 == 3 -> "${n}rd"
        else -> "${n}th"
    }
}
