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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * LaneWise lane-arrow glyphs: a thick shaft, a rounded bend and a wide head, defined in a unit box
 * (0..1, y down). Glyphs are automatically centred in the box, then mapped through an arbitrary
 * projection — identity for the lane bar / banner, a perspective transform for the 3D scene.
 */
object LaneArrows {

    private val ALL = intArrayOf(
        LaneDir.UTURN, LaneDir.SHARP_LEFT, LaneDir.LEFT, LaneDir.SLIGHT_LEFT, LaneDir.STRAIGHT,
        LaneDir.SLIGHT_RIGHT, LaneDir.RIGHT, LaneDir.SHARP_RIGHT,
    )

    /** Shaft width as a fraction of the box; callers pass box size × this. */
    const val SHAFT = 0.15f
    private const val HEAD_LEN = 0.26f
    private const val HEAD_HALF = 0.2f

    private class Glyph(val shaft: List<Offset>, val head: List<Offset>)

    private fun angleOf(bit: Int): Double = when (bit) {
        LaneDir.SLIGHT_RIGHT -> 45.0
        LaneDir.RIGHT -> 90.0
        LaneDir.SHARP_RIGHT -> 135.0
        LaneDir.SLIGHT_LEFT -> -45.0
        LaneDir.LEFT -> -90.0
        LaneDir.SHARP_LEFT -> -135.0
        else -> 0.0
    }

    private fun glyph(bit: Int): Glyph {
        val bottom = Offset(0.5f, 0.96f)
        if (bit == LaneDir.UTURN) {
            // Up, round over to the left, down (Germany: U-turns go left).
            val pts = arrayListOf(bottom, Offset(0.5f, 0.42f))
            val cx = 0.32f; val cy = 0.42f; val r = 0.18f
            for (i in 0..16) {
                val a = Math.PI * i / 16
                pts += Offset((cx + r * cos(a)).toFloat(), (cy - r * sin(a)).toFloat())
            }
            pts += Offset(cx - r, 0.62f)
            return Glyph(pts, head(Offset(cx - r, 0.62f), 180.0))
        }
        if (bit == LaneDir.STRAIGHT) {
            val tip = Offset(0.5f, 0.3f)
            return Glyph(listOf(bottom, tip), head(tip, 0.0))
        }
        val a = angleOf(bit)
        val absA = kotlin.math.abs(a)
        val bendY = when { absA < 60 -> 0.52f; absA < 120 -> 0.44f; else -> 0.36f }
        val len = if (absA < 60) 0.36f else 0.3f
        val r = 0.14f
        val rad = Math.toRadians(a)
        val dx = sin(rad).toFloat(); val dy = -cos(rad).toFloat()
        val bend = Offset(0.5f, bendY)
        val pts = arrayListOf(bottom, Offset(0.5f, bendY + r))
        val out = Offset(bend.x + dx * r, bend.y + dy * r)
        for (i in 1..10) {          // rounded corner: quadratic from (bend + down r) via bend to (bend + dir r)
            val t = i / 10f
            val sx = 0.5f; val sy = bendY + r
            pts += Offset(
                (1 - t) * (1 - t) * sx + 2 * (1 - t) * t * bend.x + t * t * out.x,
                (1 - t) * (1 - t) * sy + 2 * (1 - t) * t * bend.y + t * t * out.y,
            )
        }
        val tip = Offset(bend.x + dx * len, bend.y + dy * len)
        pts += tip
        return Glyph(pts, head(tip, a))
    }

    private fun head(base: Offset, dirDeg: Double): List<Offset> {
        val rad = Math.toRadians(dirDeg)
        val fx = sin(rad).toFloat(); val fy = -cos(rad).toFloat()
        val px = -fy; val py = fx
        val tip = Offset(base.x + fx * HEAD_LEN, base.y + fy * HEAD_LEN)
        return listOf(tip, Offset(base.x + px * HEAD_HALF, base.y + py * HEAD_HALF), Offset(base.x - px * HEAD_HALF, base.y - py * HEAD_HALF))
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
        if (bits.isEmpty()) return
        val ordered = bits.filter { highlight and it == 0 } + bits.filter { highlight and it != 0 }
        val glyphs = ordered.map { it to glyph(it) }

        // Centre the combined glyph in the unit box (fixes off-centre turn arrows) and fit it to 86 %.
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for ((_, g) in glyphs) for (p in g.shaft + g.head) {
            minX = min(minX, p.x); maxX = max(maxX, p.x); minY = min(minY, p.y); maxY = max(maxY, p.y)
        }
        val pad = SHAFT / 2
        minX -= pad; maxX += pad
        val scale = min(0.86f / (maxX - minX), 0.86f / (maxY - minY)).coerceAtMost(1.15f)
        val cx = (minX + maxX) / 2; val cy = (minY + maxY) / 2
        fun fit(p: Offset) = project(Offset(0.5f + (p.x - cx) * scale, 0.5f + (p.y - cy) * scale))

        for ((bit, g) in glyphs) {
            val color = if (highlight and bit != 0) onColor else offColor
            val path = Path().apply {
                g.shaft.forEachIndexed { i, p -> val q = fit(p); if (i == 0) moveTo(q.x, q.y) else lineTo(q.x, q.y) }
            }
            scope.drawPath(path, color, style = Stroke(width = stroke * scale, cap = StrokeCap.Butt, join = StrokeJoin.Round))
            val h = Path().apply {
                g.head.forEachIndexed { i, p -> val q = fit(p); if (i == 0) moveTo(q.x, q.y) else lineTo(q.x, q.y) }
                close()
            }
            scope.drawPath(h, color)
        }
    }
}
