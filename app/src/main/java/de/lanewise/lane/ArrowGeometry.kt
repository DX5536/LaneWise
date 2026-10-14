package de.lanewise.lane

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Lane-arrow glyphs as filled polygons in a unit box (x 0..1 left→right, y 0..1 top→bottom; the tip points
 * to y = 0). Used to paint arrows onto the road surface in the 3D map, like real German lane markings.
 * The same shapes back the flat lane bar ([de.lanewise.ui.lane.LaneArrows]).
 */
object ArrowGeometry {
    data class P(val x: Double, val y: Double)

    const val SHAFT = 0.15
    private const val HEAD_LEN = 0.26
    private const val HEAD_HALF = 0.2

    private fun angleOf(bit: Int): Double = when (bit) {
        LaneDir.SLIGHT_RIGHT -> 45.0
        LaneDir.RIGHT -> 90.0
        LaneDir.SHARP_RIGHT -> 135.0
        LaneDir.SLIGHT_LEFT -> -45.0
        LaneDir.LEFT -> -90.0
        LaneDir.SHARP_LEFT -> -135.0
        else -> 0.0
    }

    /** Shaft centre line and arrow-head triangle of one arrow. */
    fun glyph(bit: Int): Pair<List<P>, List<P>> {
        val bottom = P(0.5, 0.96)
        if (bit == LaneDir.UTURN) {
            val pts = arrayListOf(bottom, P(0.5, 0.42))
            val cx = 0.32; val cy = 0.42; val r = 0.18
            for (i in 0..16) {
                val a = Math.PI * i / 16
                pts += P(cx + r * cos(a), cy - r * sin(a))
            }
            pts += P(cx - r, 0.62)
            return pts to head(P(cx - r, 0.62), 180.0)
        }
        if (bit == LaneDir.STRAIGHT) {
            val tip = P(0.5, 0.3)
            return listOf(bottom, tip) to head(tip, 0.0)
        }
        val a = angleOf(bit)
        val absA = abs(a)
        val bendY = when { absA < 60 -> 0.52; absA < 120 -> 0.44; else -> 0.36 }
        val len = if (absA < 60) 0.36 else 0.3
        val r = 0.14
        val rad = Math.toRadians(a)
        val dx = sin(rad); val dy = -cos(rad)
        val pts = arrayListOf(bottom, P(0.5, bendY + r))
        val out = P(0.5 + dx * r, bendY + dy * r)
        for (i in 1..10) {
            val t = i / 10.0
            val sy = bendY + r
            pts += P(
                (1 - t) * (1 - t) * 0.5 + 2 * (1 - t) * t * 0.5 + t * t * out.x,
                (1 - t) * (1 - t) * sy + 2 * (1 - t) * t * bendY + t * t * out.y,
            )
        }
        val tip = P(0.5 + dx * len, bendY + dy * len)
        pts += tip
        return pts to head(tip, a)
    }

    private fun head(base: P, dirDeg: Double): List<P> {
        val rad = Math.toRadians(dirDeg)
        val fx = sin(rad); val fy = -cos(rad)
        val px = -fy; val py = fx
        return listOf(
            P(base.x + fx * HEAD_LEN, base.y + fy * HEAD_LEN),
            P(base.x + px * HEAD_HALF, base.y + py * HEAD_HALF),
            P(base.x - px * HEAD_HALF, base.y - py * HEAD_HALF),
        )
    }

    /** Closed polygons (shaft outline + head) for every arrow bit in [mask]. */
    fun polygons(mask: Int): List<List<P>> {
        val m = if (mask and (LaneDir.MERGE_TO_LEFT or LaneDir.MERGE_TO_RIGHT).inv() == 0) LaneDir.STRAIGHT else mask
        val bits = listOf(LaneDir.UTURN, LaneDir.SHARP_LEFT, LaneDir.LEFT, LaneDir.SLIGHT_LEFT, LaneDir.STRAIGHT,
            LaneDir.SLIGHT_RIGHT, LaneDir.RIGHT, LaneDir.SHARP_RIGHT).filter { m and it != 0 }
        val out = ArrayList<List<P>>()
        for (bit in bits) {
            val (shaft, head) = glyph(bit)
            out += buffer(shaft, SHAFT / 2)
            out += head + head.first()
        }
        return out
    }

    /** Outline of a polyline thickened by [hw] on both sides (miter-free, using averaged normals). */
    private fun buffer(line: List<P>, hw: Double): List<P> {
        val left = ArrayList<P>(); val right = ArrayList<P>()
        for (i in line.indices) {
            val a = line[maxOf(0, i - 1)]; val b = line[minOf(line.size - 1, i + 1)]
            val dx = b.x - a.x; val dy = b.y - a.y
            val l = hypot(dx, dy).takeIf { it > 1e-9 } ?: 1.0
            val nx = -dy / l; val ny = dx / l
            left += P(line[i].x + nx * hw, line[i].y + ny * hw)
            right += P(line[i].x - nx * hw, line[i].y - ny * hw)
        }
        return left + right.reversed() + left.first()
    }
}
