package de.lanewise.lane

import kotlin.math.abs

/**
 * Lane arrow directions as a bitmask.
 *
 * The layout (clockwise from straight) mirrors the scheme observed in Petal Maps' lane assets
 * (see docs/REVERSE_ENGINEERING.md): one bit per arrow so a lane with several markings is a
 * simple OR, and "is this lane usable for my manoeuvre" is a single AND.
 */
object LaneDir {
    const val NONE = 0
    const val STRAIGHT = 1
    const val SLIGHT_RIGHT = 2
    const val RIGHT = 4
    const val SHARP_RIGHT = 8
    const val UTURN = 16
    const val SHARP_LEFT = 32
    const val LEFT = 64
    const val SLIGHT_LEFT = 128
    const val MERGE_TO_LEFT = 256
    const val MERGE_TO_RIGHT = 512

    const val ANY_RIGHT = SLIGHT_RIGHT or RIGHT or SHARP_RIGHT
    const val ANY_LEFT = SLIGHT_LEFT or LEFT or SHARP_LEFT or UTURN

    /** OSM `turn:lanes` token or OSRM `indications` entry → bit. Unknown tokens map to NONE. */
    fun fromToken(token: String): Int = when (token.trim().lowercase().replace(' ', '_')) {
        "through", "straight" -> STRAIGHT
        "slight_right" -> SLIGHT_RIGHT
        "right" -> RIGHT
        "sharp_right" -> SHARP_RIGHT
        "reverse", "uturn" -> UTURN
        "sharp_left" -> SHARP_LEFT
        "left" -> LEFT
        "slight_left" -> SLIGHT_LEFT
        "merge_to_left" -> MERGE_TO_LEFT
        "merge_to_right" -> MERGE_TO_RIGHT
        else -> NONE // "none", "", unknown
    }

    /** Parse one lane value like "left;through". */
    fun parseLane(value: String): Int = value.split(';').fold(NONE) { acc, t -> acc or fromToken(t) }

    /** Parse a full OSM `turn:lanes` value like "left|through|through;right" (left-to-right). */
    fun parseTurnLanes(value: String): List<Int> = value.split('|').map { parseLane(it) }

    /** Classify a turn angle (deg, + = right) into the arrow bit drivers expect. */
    fun fromTurnAngle(delta: Double): Int {
        val a = abs(delta)
        val right = delta > 0
        return when {
            a < 20 -> STRAIGHT
            a < 60 -> if (right) SLIGHT_RIGHT else SLIGHT_LEFT
            a < 120 -> if (right) RIGHT else LEFT
            a < 165 -> if (right) SHARP_RIGHT else SHARP_LEFT
            else -> UTURN
        }
    }

    /**
     * Whether a lane with arrows [laneMask] can be used to perform [wanted].
     * Unmarked lanes (NONE) are treated as through lanes, which matches German practice
     * and how OSRM resolves "none".
     */
    fun permits(laneMask: Int, wanted: Int): Boolean {
        val m = if (laneMask and (MERGE_TO_LEFT or MERGE_TO_RIGHT).inv() == NONE) STRAIGHT else laneMask
        if (m and wanted != 0) return true
        // Tolerate slight vs. normal mismatches between map tagging and geometry-derived angles.
        return when (wanted) {
            SLIGHT_RIGHT -> m and (RIGHT or STRAIGHT) != 0 && m and LEFT == 0
            SLIGHT_LEFT -> m and (LEFT or STRAIGHT) != 0 && m and RIGHT == 0
            RIGHT -> m and (SLIGHT_RIGHT or SHARP_RIGHT) != 0
            LEFT -> m and (SLIGHT_LEFT or SHARP_LEFT) != 0
            SHARP_RIGHT -> m and RIGHT != 0
            SHARP_LEFT -> m and LEFT != 0
            UTURN -> m and SHARP_LEFT != 0
            else -> false
        }
    }
}

/** Road category used for distance thresholds and the German keep-right rule. */
enum class RoadKind { MOTORWAY, TRUNK, URBAN, RURAL }

/**
 * A point along the route where lane markings are known (an intersection, a fork, an exit).
 * Lanes are ordered left → right in driving direction, like OSM `turn:lanes`.
 */
data class LaneSite(
    /** Distance from route start (m) of the junction itself. */
    val distanceAlong: Double,
    /** Arrow bitmask per lane, left → right. */
    val lanes: List<Int>,
    /**
     * Lanes the provider says can be used for the route here (OSRM `valid`, TomTom `follow`).
     * If null, the planner derives them from [lanes] and [turn].
     */
    val providerValid: List<Boolean>?,
    /** Direction the route takes at this site (LaneDir bit). */
    val turn: Int,
    /** True if the route makes an actual manoeuvre here, false if it only passes through. */
    val isManeuver: Boolean,
    val roadKind: RoadKind,
) {
    val laneCount get() = lanes.size

    fun allowed(): BooleanArray {
        val pv = providerValid
        val arr = if (pv != null && pv.size == lanes.size && pv.any { it }) {
            pv.toBooleanArray()
        } else {
            BooleanArray(lanes.size) { LaneDir.permits(lanes[it], turn) }
        }
        // Never return an empty set: if nothing matches, the data is inconsistent — allow all.
        if (arr.none { it }) arr.fill(true)
        return arr
    }

    /** Number of turn-pocket lanes on the left edge that only serve left turns / U-turns. */
    fun leftPockets(): Int {
        var n = 0
        while (n < lanes.size - 1) {
            val m = lanes[n]
            if (m != LaneDir.NONE && m and LaneDir.ANY_LEFT.inv() == 0) n++ else break
        }
        return n
    }

    /** Number of turn-pocket lanes on the right edge that only serve right turns. */
    fun rightPockets(): Int {
        var n = 0
        while (n < lanes.size - 1 - leftPockets()) {
            val m = lanes[lanes.size - 1 - n]
            if (m != LaneDir.NONE && m and LaneDir.ANY_RIGHT.inv() == 0) n++ else break
        }
        return n
    }

    /**
     * Lateral coordinate of lane [i] in "lane units", where 0 is the rightmost *through* (core) lane,
     * positive is further left. Right turn pockets get negative values; left pockets extend beyond the core.
     * This lets lanes of consecutive sites with different lane counts be compared: a lane change is a
     * change in this coordinate.
     */
    fun lateral(i: Int): Int = (laneCount - 1 - rightPockets()) - i
}

/** Planner output for one site. */
data class LaneAdvice(
    val site: LaneSite,
    /** The single best lane index (left → right). */
    val recommended: Int,
    /** All lanes that work for the route at this site. */
    val allowed: BooleanArray,
    /** Lanes that work AND keep the total number of lane changes minimal (subset of allowed). */
    val optimal: BooleanArray,
    /** Lane changes needed between the previous site's recommended lane and this one (signed, + = to the left). */
    val changeFromPrevious: Int,
) {
    override fun equals(other: Any?) = other is LaneAdvice && other.site == site &&
        other.recommended == recommended && other.allowed.contentEquals(allowed) &&
        other.optimal.contentEquals(optimal) && other.changeFromPrevious == changeFromPrevious

    override fun hashCode() = site.hashCode() * 31 + recommended
}
