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
import androidx.compose.runtime.remember
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import de.lanewise.core.model.RoadFeature
import de.lanewise.ui.i18n.LocalStrings
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
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

/** German round speed-limit sign (Zeichen 274), or the end-of-limits sign (Zeichen 282) on de-restricted Autobahn. */
@Composable
fun SpeedLimitSign(limit: Int?, noLimit: Boolean, modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 60.dp) {
    if (limit == null && !noLimit) return
    val c = LocalLaneColors.current
    Box(
        modifier.size(size).clip(CircleShape).background(Color.White)
            .border(if (noLimit) 1.dp else size * 0.11f, if (noLimit) Color(0xFF9AA0AE) else c.speedRing, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (noLimit) {
            Canvas(Modifier.fillMaxSize()) {
                for (k in -2..2) {
                    val o = k * size.toPx() * 0.08f
                    drawLine(Color(0xFF222222), Offset(size.toPx() * 0.2f + o, size.toPx() * 0.85f + o),
                        Offset(size.toPx() * 0.85f + o, size.toPx() * 0.2f + o), strokeWidth = size.toPx() * 0.035f)
                }
            }
        } else {
            val v = limit ?: 0
            Text("$v", color = Color(0xFF111111), fontWeight = FontWeight.Bold,
                fontSize = (size.value * if (v >= 100) 0.34f else 0.42f).sp)
        }
    }
}

/** Current speed bubble; turns light red when over/under the limit. */
@Composable
fun SpeedPanel(speedKmh: Float, status: SpeedStatus, modifier: Modifier = Modifier) {
    val c = LocalLaneColors.current
    val str = LocalStrings.current
    val bg = when (status) {
        SpeedStatus.OVER -> c.overSpeed
        SpeedStatus.UNDER -> c.underSpeed
        else -> c.chip
    }
    val fg = if (status == SpeedStatus.OVER || status == SpeedStatus.UNDER) Color.White else c.onChip
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(bg).padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("${speedKmh.toInt()}", color = fg, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text(str.kmh, color = fg.copy(alpha = 0.8f), fontSize = 11.sp)
    }
}

/** Upcoming traffic light / road sign with distance. */
@Composable
fun FeatureChip(feature: RoadFeature, distanceText: String, modifier: Modifier = Modifier) {
    val c = LocalLaneColors.current
    val str = LocalStrings.current
    val img = remember(feature.kind) { RoadSignArt.feature(feature.kind, 96).asImageBitmap() }
    Row(
        modifier.clip(RoundedCornerShape(14.dp)).background(c.chip.copy(alpha = 0.95f)).padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(img, str.feature(feature.kind), Modifier.size(26.dp))
        Spacer(Modifier.width(6.dp))
        Text(distanceText, color = c.onChip, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

/**
 * Next signalised junction: number of vehicle lights, pedestrian lights, Grünpfeil and the turn-arrow signals
 * that are likely there (inferred from exclusive turn lanes; drawn faded with a dotted border).
 */
@Composable
fun SignalChip(sig: de.lanewise.core.model.SignalCluster, distanceText: String, modifier: Modifier = Modifier) {
    val c = LocalLaneColors.current
    val light = remember { RoadSignArt.feature(de.lanewise.core.model.FeatureKind.TRAFFIC_SIGNALS, 96).asImageBitmap() }
    val walker = remember { RoadSignArt.feature(de.lanewise.core.model.FeatureKind.CROSSING, 96).asImageBitmap() }
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(c.chip.copy(alpha = 0.95f)).padding(horizontal = 8.dp, vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(light, null, Modifier.size(24.dp))
            if (sig.vehicleLights > 1) Text("×${sig.vehicleLights}", color = c.onChip, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(6.dp))
            Text(distanceText, color = c.onChip, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        if (sig.pedestrianLights > 0 || sig.greenArrow || sig.inferredArrowSignals != 0) {
            Spacer(Modifier.height(3.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (sig.pedestrianLights > 0) {
                    Image(walker, null, Modifier.size(18.dp))
                    if (sig.pedestrianLights > 1) Text("×${sig.pedestrianLights}", color = c.onChip, fontSize = 11.sp)
                }
                if (sig.greenArrow) {
                    // Grünpfeil sign (Zeichen 720): green arrow on black.
                    Box(Modifier.size(18.dp).clip(RoundedCornerShape(3.dp)).background(Color.Black), contentAlignment = Alignment.Center) {
                        Canvas(Modifier.size(14.dp)) {
                            LaneArrows.draw(this, de.lanewise.lane.LaneDir.RIGHT, de.lanewise.lane.LaneDir.RIGHT,
                                Color(0xFF27AE60), Color(0xFF27AE60), size.width * LaneArrows.SHAFT) { p -> Offset(p.x * size.width, p.y * size.height) }
                        }
                    }
                }
                listOf(de.lanewise.lane.LaneDir.LEFT, de.lanewise.lane.LaneDir.SLIGHT_LEFT, de.lanewise.lane.LaneDir.RIGHT,
                    de.lanewise.lane.LaneDir.SLIGHT_RIGHT, de.lanewise.lane.LaneDir.UTURN)
                    .filter { sig.inferredArrowSignals and it != 0 }.forEach { bit ->
                        // Likely separate arrow signal head (inferred): faded, dotted outline.
                        Box(
                            Modifier.size(width = 14.dp, height = 22.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF111111))
                                .border(1.dp, c.onChip.copy(alpha = 0.4f), RoundedCornerShape(4.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Canvas(Modifier.size(10.dp)) {
                                LaneArrows.draw(this, bit, bit, Color(0xAA27AE60), Color(0xAA27AE60), size.width * LaneArrows.SHAFT) { p ->
                                    Offset(p.x * size.width, p.y * size.height)
                                }
                            }
                        }
                    }
            }
        }
    }
}

/** Shown while a painted bike lane runs next to the car lanes. */
@Composable
fun BikeLaneChip(exclusive: Boolean, color: Color, modifier: Modifier = Modifier) {
    val str = LocalStrings.current
    val argb = color.toArgb()
    val img = remember(argb) { RoadSignArt.bike(96, argb).asImageBitmap() }
    Row(
        modifier.clip(RoundedCornerShape(14.dp)).background(color).padding(start = 4.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(img, null, Modifier.size(26.dp))
        Spacer(Modifier.width(6.dp))
        Text(if (exclusive) str.bikeLaneExclusive else str.bikeLane, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

/**
 * Top banner: manoeuvre arrow, distance, road; optional "then" chip for close-following steps.
 * The whole box doubles as a countdown: a violet fill shrinks with [countdown] (1 = start, 0 = manoeuvre).
 * [lanesBelow] squares off the bottom corners so the lane bar can dock underneath as one card.
 * [below] is drawn directly under the banner (lane bar), above the "then" chip.
 */
@Composable
fun ManeuverBanner(
    step: RouteStep?, distanceText: String, instruction: String, then: RouteStep?, thenText: String?,
    modifier: Modifier = Modifier, countdown: Float? = null, lanesBelow: Boolean = false,
    below: @Composable () -> Unit = {},
) {
    val c = LocalLaneColors.current
    val fill by androidx.compose.animation.core.animateFloatAsState(
        countdown?.coerceIn(0f, 1f) ?: 0f, androidx.compose.animation.core.tween(900, easing = androidx.compose.animation.core.LinearEasing),
        label = "countdown",
    )
    val shape = if (lanesBelow) RoundedCornerShape(22.dp, 22.dp, 6.dp, 6.dp) else RoundedCornerShape(22.dp)
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().clip(shape).background(c.banner)
                .drawBehind { if (countdown != null) drawRect(c.bannerAccent.copy(alpha = 0.38f), size = Size(size.width * fill, size.height)) }
                .padding(14.dp),
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
        below()
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
        LaneArrows.draw(this, bit, bit, color, color, size.width * LaneArrows.SHAFT) { p -> Offset(p.x * size.width, p.y * size.height) }
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
        RoundAction(RoundIcon.CLOSE, onClose)
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(remainingText, color = c.onChip, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text("$distanceText · $etaText", color = c.onChip.copy(alpha = 0.7f), fontSize = 15.sp)
        }
        RoundAction(RoundIcon.OVERVIEW, onOverview)
    }
}

enum class RoundIcon { CLOSE, OVERVIEW }

/** Round outlined button with a vector icon drawn exactly in the centre (text glyphs sit off-centre). */
@Composable
fun RoundAction(icon: RoundIcon, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalLaneColors.current
    Box(
        modifier.size(48.dp).clip(CircleShape).border(1.5.dp, c.onChip.copy(alpha = 0.6f), CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(20.dp)) {
            val w = size.width
            val stroke = w * 0.1f
            val col = c.onChip
            when (icon) {
                RoundIcon.CLOSE -> {
                    drawLine(col, Offset(w * 0.15f, w * 0.15f), Offset(w * 0.85f, w * 0.85f), stroke, StrokeCap.Round)
                    drawLine(col, Offset(w * 0.85f, w * 0.15f), Offset(w * 0.15f, w * 0.85f), stroke, StrokeCap.Round)
                }
                RoundIcon.OVERVIEW -> {
                    // Four corner brackets: "show whole route".
                    val a = w * 0.08f; val b = w * 0.36f; val e = w * 0.92f
                    for ((x, y, dx, dy) in listOf(listOf(a, a, 1f, 1f), listOf(e, a, -1f, 1f), listOf(a, e, 1f, -1f), listOf(e, e, -1f, -1f))) {
                        drawLine(col, Offset(x, y), Offset(x + dx * (b - a), y), stroke, StrokeCap.Round)
                        drawLine(col, Offset(x, y), Offset(x, y + dy * (b - a)), stroke, StrokeCap.Round)
                    }
                }
            }
        }
    }
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
        Text("${info.satellitesUsed}/${info.satellitesVisible} ${LocalStrings.current.satellites}", color = c.onChip, fontSize = 12.sp)
        if (!online) Text(LocalStrings.current.offline, color = c.onChip.copy(alpha = 0.7f), fontSize = 12.sp)
    }
}
