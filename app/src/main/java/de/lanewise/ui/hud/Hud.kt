package de.lanewise.ui.hud

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.lanewise.core.model.ManeuverType
import de.lanewise.core.model.RouteStep
import de.lanewise.lane.LaneDir
import de.lanewise.location.GnssInfo
import de.lanewise.nav.SpeedStatus
import de.lanewise.routing.toLaneDir
import de.lanewise.ui.lane.LaneArrows
import de.lanewise.ui.theme.LocalLaneColors

/**
 * Light-red glow around the whole screen when driving faster than limit + offset (pulsing) or slower
 * than limit − offset (steady, softer). Offsets are configurable in Settings.
 */
@Composable
fun SpeedBorderOverlay(status: SpeedStatus, modifier: Modifier = Modifier) {
    if (status != SpeedStatus.OVER && status != SpeedStatus.UNDER) return
    val c = LocalLaneColors.current
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        0.55f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "pulseAnim",
    )
    val alpha = if (status == SpeedStatus.OVER) pulse else 0.6f
    val color = (if (status == SpeedStatus.OVER) c.overSpeed else c.underSpeed).copy(alpha = 0.8f * alpha)
    Box(
        modifier.fillMaxSize().drawBehind {
            val t = 28.dp.toPx()
            val clear = Color.Transparent
            drawRect(Brush.verticalGradient(listOf(color, clear), 0f, t), Offset.Zero, Size(size.width, t))
            drawRect(Brush.verticalGradient(listOf(clear, color), size.height - t, size.height), Offset(0f, size.height - t), Size(size.width, t))
            drawRect(Brush.horizontalGradient(listOf(color, clear), 0f, t), Offset.Zero, Size(t, size.height))
            drawRect(Brush.horizontalGradient(listOf(clear, color), size.width - t, size.width), Offset(size.width - t, 0f), Size(t, size.height))
        },
    )
}

/** German-style round speed limit sign plus the current speed underneath. */
@Composable
fun SpeedPanel(limit: Int?, noLimit: Boolean, speedKmh: Float, status: SpeedStatus, modifier: Modifier = Modifier) {
    val c = LocalLaneColors.current
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        if (limit != null || noLimit) {
            Box(
                Modifier.size(64.dp).clip(CircleShape).background(Color.White)
                    .border(if (noLimit) 0.dp else 7.dp, c.speedRing, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (noLimit) {
                    // End-of-limit sign (Zeichen 282): white disc, black diagonal stripes.
                    Canvas(Modifier.fillMaxSize()) {
                        for (k in -2..2) {
                            val o = k * 5.dp.toPx()
                            drawLine(Color(0xFF222222), Offset(size.width * 0.2f + o, size.height * 0.85f + o),
                                Offset(size.width * 0.85f + o, size.height * 0.2f + o), strokeWidth = 2.dp.toPx())
                        }
                    }
                } else {
                    Text("$limit", color = Color(0xFF111111), fontSize = if (limit!! >= 100) 22.sp else 26.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(6.dp))
        }
        val bg = when (status) {
            SpeedStatus.OVER -> c.overSpeed
            SpeedStatus.UNDER -> c.underSpeed
            else -> c.chip
        }
        val fg = if (status == SpeedStatus.OVER || status == SpeedStatus.UNDER) Color.White else c.onChip
        Column(
            Modifier.clip(RoundedCornerShape(16.dp)).background(bg).padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("${speedKmh.toInt()}", color = fg, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text("km/h", color = fg.copy(alpha = 0.8f), fontSize = 11.sp)
        }
    }
}

/** Top banner: manoeuvre arrow, distance, road; optional "then" chip for close-following steps. */
@Composable
fun ManeuverBanner(step: RouteStep?, distanceText: String, instruction: String, then: RouteStep?, thenText: String?, modifier: Modifier = Modifier) {
    val c = LocalLaneColors.current
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(c.banner).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(64.dp).clip(RoundedCornerShape(16.dp)).background(c.bannerAccent),
                contentAlignment = Alignment.Center,
            ) { if (step != null) ManeuverIcon(step, Color.White, Modifier.size(46.dp)) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(distanceText, color = c.onBanner, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                Text(instruction, color = c.onBanner.copy(alpha = 0.9f), fontSize = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (then != null && thenText != null) {
            Row(
                Modifier.padding(start = 12.dp, top = 4.dp).clip(RoundedCornerShape(12.dp)).background(c.banner.copy(alpha = 0.92f))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(thenText, color = c.onBanner, fontSize = 13.sp)
                Spacer(Modifier.width(6.dp))
                ManeuverIcon(then, c.onBanner, Modifier.size(20.dp))
            }
        }
    }
}

@Composable
fun ManeuverIcon(step: RouteStep, color: Color, modifier: Modifier = Modifier) {
    val bit = when (step.maneuver.type) {
        ManeuverType.ARRIVE, ManeuverType.DEPART -> LaneDir.STRAIGHT
        else -> step.maneuver.modifier.toLaneDir()
    }
    Canvas(modifier) {
        LaneArrows.draw(this, bit, bit, color, color, size.width * 0.14f) { p -> Offset(p.x * size.width, p.y * size.height) }
        if (step.maneuver.type == ManeuverType.ARRIVE) {
            drawCircle(color, size.width * 0.16f, Offset(size.width / 2, size.height * 0.2f))
        }
    }
}

/** Bottom sheet line: remaining time, distance, ETA, plus actions. */
@Composable
fun EtaBar(
    remainingText: String, distanceText: String, etaText: String,
    onClose: () -> Unit, onOverview: () -> Unit, modifier: Modifier = Modifier,
) {
    val c = LocalLaneColors.current
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)).background(c.chip)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundAction("✕", onClose)
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(remainingText, color = c.onChip, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text("$distanceText · $etaText", color = c.onChip.copy(alpha = 0.7f), fontSize = 15.sp)
        }
        RoundAction("⤢", onOverview)
    }
}

@Composable
fun RoundAction(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalLaneColors.current
    Box(
        modifier.size(48.dp).clip(CircleShape).border(1.5.dp, c.onChip.copy(alpha = 0.6f), CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = c.onChip, fontSize = 20.sp) }
}

/** Satellite status chip — shows that positioning works from GNSS alone. */
@Composable
fun GnssBadge(info: GnssInfo, online: Boolean, modifier: Modifier = Modifier) {
    val c = LocalLaneColors.current
    Row(
        modifier.clip(RoundedCornerShape(12.dp)).background(c.chip.copy(alpha = 0.92f)).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val good = info.satellitesUsed >= 6
        Box(Modifier.size(9.dp).clip(CircleShape).background(if (good) Color(0xFF5E8C00) else Color(0xFFE0A400)))
        Text("${info.satellitesUsed}/${info.satellitesVisible} Sat", color = c.onChip, fontSize = 12.sp)
        if (!online) Text("offline", color = c.onChip.copy(alpha = 0.7f), fontSize = 12.sp)
    }
}
