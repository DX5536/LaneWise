package de.lanewise.ui.lane

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import de.lanewise.lane.LaneDir
import kotlin.math.cos
import kotlin.math.sin

/**
 * LaneWise lane-arrow glyphs, drawn as vector paths in a unit box (0..1, y down) and mapped through
 * an arbitrary projection — identity for the flat lane bar, a perspective transform for the 3D scene.
 */
object LaneArrows {

    private val ALL = intArrayOf(
        LaneDir.UTURN, LaneDir.SHARP_LEFT, LaneDir.LEFT, LaneDir.SLIGHT_LEFT, LaneDir.STRAIGHT,
        LaneDir.SLIGHT_RIGHT, LaneDir.RIGHT, LaneDir.SHARP_RIGHT,
    )

    private fun angleOf(bit: Int): Double = when (bit) {
        LaneDir.SLIGHT_RIGHT -> 40.0
        LaneDir.RIGHT -> 90.0
        LaneDir.SHARP_RIGHT -> 135.0
        LaneDir.SLIGHT_LEFT -> -40.0
        LaneDir.LEFT -> -90.0
        LaneDir.SHARP_LEFT -> -135.0
        else -> 0.0
    }

    /** Polyline (unit space) for one arrow shaft, and the arrow head as a triangle. */
    fun shape(bit: Int): Pair<List<Offset>, List<Offset>> {
        val pts = ArrayList<Offset>()
        val bottom = Offset(0.5f, 0.98f)
        if (bit == LaneDir.UTURN) {
            // Up, arc over to the left, then down (Germany: U-turns are made to the left).
            pts += bottom; pts += Offset(0.5f, 0.42f)
            for (i in 0..12) {
                val a = Math.PI * i / 12
                pts += Offset((0.35 + 0.15 * cos(a)).toFloat(), (0.42 - 0.15 * sin(a)).toFloat())
            }
            pts += Offset(0.2f, 0.62f)
            return pts to head(Offset(0.2f, 0.62f), 180.0)
        }
        val a = angleOf(bit)
        val knee = Offset(0.5f, if (bit == LaneDir.STRAIGHT) 0.3f else 0.55f)
        pts += bottom; pts += knee
        if (bit == LaneDir.STRAIGHT) {
            pts += Offset(0.5f, 0.16f)
            return pts to head(Offset(0.5f, 0.16f), 0.0)
        }
        val r = 0.3
        val rad = Math.toRadians(a)
        val end = Offset((0.5 + r * sin(rad)).toFloat(), (0.55 - r * cos(rad) * 0.9 - 0.05).toFloat())
        // Quadratic bend from the knee towards the end, sampled.
        val ctrl = Offset(0.5f, (end.y + knee.y) / 2 - 0.08f)
        for (i in 1..10) {
            val t = i / 10f
            val x = (1 - t) * (1 - t) * knee.x + 2 * (1 - t) * t * ctrl.x + t * t * end.x
            val y = (1 - t) * (1 - t) * knee.y + 2 * (1 - t) * t * ctrl.y + t * t * end.y
            pts += Offset(x, y)
        }
        val dir = Math.toDegrees(kotlin.math.atan2((end.x - ctrl.x).toDouble(), -(end.y - ctrl.y).toDouble()))
        return pts to head(end, dir)
    }

    private fun head(tip: Offset, dirDeg: Double): List<Offset> {
        val rad = Math.toRadians(dirDeg)
        val fx = sin(rad).toFloat(); val fy = -cos(rad).toFloat()   // forward unit vector
        val px = -fy; val py = fx                                     // perpendicular
        val len = 0.2f; val half = 0.13f
        val tipF = Offset(tip.x + fx * 0.06f, tip.y + fy * 0.06f)
        val base = Offset(tipF.x - fx * len, tipF.y - fy * len)
        return listOf(tipF, Offset(base.x + px * half, base.y + py * half), Offset(base.x - px * half, base.y - py * half))
    }

    /**
     * Draw every arrow of [mask]; arrows in [highlight] are drawn last in [onColor], others in [offColor].
     * [project] maps unit coordinates to canvas pixels; [stroke] is the shaft width in pixels.
     */
    fun draw(
        scope: DrawScope, mask: Int, highlight: Int, onColor: Color, offColor: Color,
        stroke: Float, project: (Offset) -> Offset,
    ) {
        val m = if (mask and (LaneDir.MERGE_TO_LEFT or LaneDir.MERGE_TO_RIGHT).inv() == 0) LaneDir.STRAIGHT else mask
        val bits = ALL.filter { m and it != 0 }
        val ordered = bits.filter { highlight and it == 0 } + bits.filter { highlight and it != 0 }
        for (bit in ordered) {
            val color = if (highlight and bit != 0) onColor else offColor
            val (shaft, head) = shape(bit)
            val path = Path().apply {
                shaft.forEachIndexed { i, p -> val q = project(p); if (i == 0) moveTo(q.x, q.y) else lineTo(q.x, q.y) }
            }
            scope.drawPath(path, color, style = Stroke(width = stroke, cap = StrokeCap.Butt, join = StrokeJoin.Round))
            val h = Path().apply {
                head.forEachIndexed { i, p -> val q = project(p); if (i == 0) moveTo(q.x, q.y) else lineTo(q.x, q.y) }
                close()
            }
            scope.drawPath(h, color)
        }
    }
}
