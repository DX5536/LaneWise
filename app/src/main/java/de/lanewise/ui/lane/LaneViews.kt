package de.lanewise.ui.lane

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.lanewise.lane.LaneAdvice
import de.lanewise.lane.LaneDir
import de.lanewise.lane.LaneState
import de.lanewise.ui.theme.LocalLaneColors
import kotlin.math.max
import kotlin.math.min

/** Which arrow bit(s) of a lane serve the route's direction at the site. */
private fun usedBits(mask: Int, turn: Int): Int {
    val direct = mask and turn
    if (direct != 0) return direct
    val m = if (mask == LaneDir.NONE) LaneDir.STRAIGHT else mask
    return if (LaneDir.permits(m, turn)) m else 0
}

/**
 * Flat lane bar (one cell per lane, left → right). Lime = recommended lane, soft lime = also optimal,
 * white = works but costs an extra lane change later, grey = wrong lane. Tap a lane to tell LaneWise
 * which lane you are in.
 */
@Composable
fun LaneBar(
    advice: LaneAdvice, modifier: Modifier = Modifier, bikeColor: Color? = null, onLaneTap: (Int) -> Unit = {},
    docked: Boolean = false,
) {
    val c = LocalLaneColors.current
    val site = advice.site
    // Docked: full width under the manoeuvre banner, lanes share the width, same colour as the banner.
    Row(
        modifier
            .then(if (docked) Modifier.fillMaxWidth() else Modifier)
            .clip(if (docked) RoundedCornerShape(6.dp, 6.dp, 18.dp, 18.dp) else RoundedCornerShape(16.dp))
            .background(if (docked) c.banner else Color(0xF0101216))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        site.lanes.forEachIndexed { i, mask ->
            val recommended = i == advice.recommended
            val optimal = advice.optimal.getOrElse(i) { false }
            val allowed = advice.allowed.getOrElse(i) { false }
            val on = when {
                recommended -> c.laneRecommended
                optimal -> c.laneRecommended.copy(alpha = 0.55f)
                allowed -> Color.White
                else -> c.laneArrowIdle
            }
            val highlight = if (allowed) usedBits(mask, site.turn) else 0
            Box(
                (if (docked) Modifier.weight(1f).height(52.dp) else Modifier.size(width = 44.dp, height = 52.dp))
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (recommended) Color(0x33B8F33A) else Color.Transparent)
                    .clickable { onLaneTap(i) },
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.size(34.dp)) {
                    val w = size.width; val h = size.height
                    LaneArrows.draw(this, mask, highlight, on, c.laneArrowIdle, w * LaneArrows.SHAFT) { p -> Offset(p.x * w, p.y * h) }
                }
            }
            if (i < site.laneCount - 1) {
                Canvas(Modifier.width(2.dp).height(40.dp)) {
                    drawLine(Color(0x55FFFFFF), Offset(size.width / 2, 0f), Offset(size.width / 2, size.height),
                        strokeWidth = size.width, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(8f, 6f)))
                }
            }
        }
        if (bikeColor != null) {
            // Painted bike lane right of the car lanes.
            Canvas(Modifier.width(2.dp).height(40.dp)) {
                drawLine(Color.White, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), strokeWidth = size.width)
            }
            val bikeImg = androidx.compose.runtime.remember(bikeColor) {
                de.lanewise.ui.hud.RoadSignArt.bike(72, bikeColor.toArgb()).asImageBitmap()
            }
            Box(
                Modifier.size(width = 30.dp, height = 52.dp).clip(RoundedCornerShape(8.dp)).background(bikeColor),
                contentAlignment = Alignment.Center,
            ) { androidx.compose.foundation.Image(bikeImg, null, Modifier.size(24.dp)) }
        }
    }
}

/**
 * Synthesised 3D view of the next lane site — LaneWise's own rendering of a junction close-up
 * (like Petal's lane guide card), drawn in perspective with Compose Canvas so it needs no extra map data.
 * The lime ribbon starts in the lane you are (assumed to be) in and bends into the recommended lane,
 * then into the turn; flowing chevrons show the direction of travel.
 */
@Composable
fun LaneScene3D(state: LaneState, distanceText: String, modifier: Modifier = Modifier, bikeColor: Color? = null) {
    val advice = state.nextAdvice ?: return
    val c = LocalLaneColors.current
    val flow by rememberInfiniteTransition(label = "flow").animateFloat(
        0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart), label = "flowAnim",
    )
    Box(modifier.clip(RoundedCornerShape(20.dp)).background(if (c.isDark) Color(0xFF0E1015) else Color(0xFFDDE3D2))) {
        Canvas(Modifier.fillMaxSize()) { drawScene(advice, state, flow, bikeColor) }
        Text(
            distanceText,
            Modifier.align(Alignment.TopStart).padding(12.dp)
                .clip(RoundedCornerShape(10.dp)).background(Color(0xCC101216)).padding(horizontal = 10.dp, vertical = 4.dp),
            color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold,
        )
    }
}

private fun DrawScope.drawScene(advice: LaneAdvice, state: LaneState, flow: Float, bikeColor: Color?) {
    val site = advice.site
    val n = site.laneCount.coerceAtLeast(1)
    val w = size.width
    val h = size.height
    val cx = w / 2
    val yStop = h * 0.30f            // stop line
    val yTop = h * 0.04f             // where the lanes vanish into the cross street
    val yBot = h * 1.02f
    val laneBot = min(w * 0.9f / n, w * 0.24f)
    val laneTop = laneBot * 0.32f
    fun laneWidthAt(y: Float): Float = laneTop + (laneBot - laneTop) * ((y - yTop) / (yBot - yTop))
    fun xAt(boundary: Float, y: Float): Float = cx + (boundary - n / 2f) * laneWidthAt(y)

    val asphalt = Color(0xFF2B2F38)
    // Cross street band and approach road.
    drawRect(asphalt, Offset(0f, yTop - h * 0.02f), androidx.compose.ui.geometry.Size(w, yStop - yTop + h * 0.02f))
    val road = Path().apply {
        moveTo(xAt(0f, yStop), yStop); lineTo(xAt(n.toFloat(), yStop), yStop)
        lineTo(xAt(n.toFloat(), yBot), yBot); lineTo(xAt(0f, yBot), yBot); close()
    }
    drawPath(road, asphalt)
    if (bikeColor != null) {
        // Bike lane strip right of the outermost car lane (≈ 0.45 lane widths).
        val bike = Path().apply {
            moveTo(xAt(n.toFloat(), yStop), yStop); lineTo(xAt(n + 0.45f, yStop), yStop)
            lineTo(xAt(n + 0.45f, yBot), yBot); lineTo(xAt(n.toFloat(), yBot), yBot); close()
        }
        drawPath(bike, bikeColor.copy(alpha = 0.9f))
    }

    // Recommended-lane ribbon: from the car's lane at the bottom into the recommended lane, then the turn.
    val rec = advice.recommended
    val curLat = state.assumedLateral
    val startLane = curLat?.let { ((n - 1 - site.rightPockets()) - it).coerceIn(0, n - 1) } ?: rec
    val ribbon = ArrayList<Offset>()
    val steps = 24
    for (i in 0..steps) {
        val t = i / steps.toFloat()
        val y = yBot - (yBot - yStop) * t
        val blend = smooth(((t - 0.15f) / 0.45f).coerceIn(0f, 1f))
        val laneCenter = startLane + 0.5f + (rec - startLane) * blend
        ribbon += Offset(xAt(laneCenter, y), y)
    }
    // Continue through the junction in the turn direction.
    val turn = site.turn
    val exitDx = when {
        turn and LaneDir.ANY_LEFT != 0 -> -w * 0.55f
        turn and LaneDir.ANY_RIGHT != 0 -> w * 0.55f
        else -> 0f
    }
    val last = ribbon.last()
    val turnEnd = Offset(last.x + exitDx, if (exitDx == 0f) yTop else (yTop + yStop) / 2.3f)
    for (i in 1..10) {
        val t = i / 10f
        val ctrl = Offset(last.x, (yTop + yStop) / 2)
        ribbon += Offset(
            (1 - t) * (1 - t) * last.x + 2 * (1 - t) * t * ctrl.x + t * t * turnEnd.x,
            (1 - t) * (1 - t) * last.y + 2 * (1 - t) * t * ctrl.y + t * t * turnEnd.y,
        )
    }
    val ribbonPath = Path().apply { ribbon.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) } }
    drawPath(ribbonPath, Color(0x55B8F33A), style = Stroke(width = laneBot * 0.85f, cap = StrokeCap.Round))
    drawPath(ribbonPath, Color(0xCCB8F33A), style = Stroke(width = laneBot * 0.5f, cap = StrokeCap.Round))

    // Lane markings.
    for (b in 0..n) {
        val edge = b == 0 || b == n
        val x0 = xAt(b.toFloat(), yBot); val x1 = xAt(b.toFloat(), yStop)
        if (edge) {
            drawLine(Color.White, Offset(x0, yBot), Offset(x1, yStop), strokeWidth = 4f)
        } else {
            // Germany: solid line in the last metres before a junction (Leitlinie → Fahrstreifenbegrenzung).
            val ySolid = yStop + (yBot - yStop) * 0.45f
            drawLine(Color.White, Offset(xAt(b.toFloat(), ySolid), ySolid), Offset(x1, yStop), strokeWidth = 3f)
            val dash = 0.09f
            var t = 0.55f
            while (t < 1f) {
                val ya = yStop + (yBot - yStop) * t
                val yb = yStop + (yBot - yStop) * min(1f, t + dash * 0.55f)
                drawLine(Color.White, Offset(xAt(b.toFloat(), ya), ya), Offset(xAt(b.toFloat(), yb), yb), strokeWidth = 3f)
                t += dash
            }
        }
    }
    drawLine(Color.White, Offset(xAt(0f, yStop), yStop), Offset(xAt(n.toFloat(), yStop), yStop), strokeWidth = 5f)

    // Painted arrows (perspective-mapped) near the stop line.
    site.lanes.forEachIndexed { i, mask ->
        val allowed = advice.allowed.getOrElse(i) { false }
        val on = when {
            i == rec -> Color(0xFF1A2400)
            allowed -> Color.White
            else -> Color(0x88FFFFFF)
        }
        val highlight = if (allowed) usedBits(mask, turn) else 0
        val vTop = yStop + (yBot - yStop) * 0.08f
        val vBot = yStop + (yBot - yStop) * 0.42f
        val strokePx = laneWidthAt((vTop + vBot) / 2) * 0.6f * LaneArrows.SHAFT * 1.3f
        LaneArrows.draw(this, mask, highlight, on, Color(0x99FFFFFF), strokePx) { p ->
            val y = vTop + (vBot - vTop) * p.y
            val lw = laneWidthAt(y)
            Offset(xAt(i.toFloat(), y) + lw * (0.2f + 0.6f * p.x), y)
        }
    }

    // Flowing chevrons along the ribbon.
    val count = 4
    for (k in 0 until count) {
        val t = ((k + flow) / count) * 0.85f
        val idx = (t * (ribbon.size - 2)).toInt().coerceIn(0, ribbon.size - 2)
        val p = ribbon[idx]
        val q = ribbon[idx + 1]
        val dx = q.x - p.x; val dy = q.y - p.y
        val len = max(1e-3f, kotlin.math.sqrt(dx * dx + dy * dy))
        val fx = dx / len; val fy = dy / len
        val s = laneWidthAt(p.y.coerceIn(yTop, yBot)) * 0.22f
        val chev = Path().apply {
            moveTo(p.x - fy * s - fx * s * 0.6f, p.y + fx * s - fy * s * 0.6f)
            lineTo(p.x, p.y)
            lineTo(p.x + fy * s - fx * s * 0.6f, p.y - fx * s - fy * s * 0.6f)
        }
        drawPath(chev, Color.White.copy(alpha = 0.9f), style = Stroke(width = s * 0.35f, cap = StrokeCap.Round))
    }

    // Car marker at the bottom of its lane.
    val carX = xAt(startLane + 0.5f, h * 0.9f)
    drawCircle(Color.White, laneBot * 0.2f, Offset(carX, h * 0.9f))
    drawCircle(Color(0xFF6C4CF5), laneBot * 0.14f, Offset(carX, h * 0.9f))
}

private fun smooth(t: Float) = t * t * (3 - 2 * t)

@Composable
fun LaneHint(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFFB8F33A))
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(text, color = Color(0xFF1A2400), fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}
