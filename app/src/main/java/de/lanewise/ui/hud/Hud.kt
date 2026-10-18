package de.lanewise.ui.hud

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.compose.foundation.layout.navigationBarsPadding
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
fun SpeedBorderOverlay(
    status: SpeedStatus, modifier: Modifier = Modifier,
    /** Glow strength from Settings (0.2 … 2): wider and more opaque with more. */
    intensity: Float = 1f,
) {
    if (status != SpeedStatus.OVER && status != SpeedStatus.UNDER) return
    val c = LocalLaneColors.current
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        0.55f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "pulseAnim",
    )
    val alpha = if (status == SpeedStatus.OVER) pulse else 0.6f
    val k = intensity.coerceIn(0.1f, 2f)
    val color = (if (status == SpeedStatus.OVER) c.overSpeed else c.underSpeed).copy(alpha = (0.8f * alpha * k).coerceAtMost(1f))
    Box(
        modifier.fillMaxSize().drawBehind {
            val t = 28.dp.toPx() * (0.5f + 0.5f * k)
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

/** A 3-lamp signal head; [arrow] = LaneDir bit for arrow-shaped lamps (Pfeilsignal), null = round lamps. */
@Composable
private fun SignalHead(arrow: Int?, inferred: Boolean) {
    val lamps = listOf(Color(0xFFE5484D), Color(0xFFF5A524), Color(0xFF2ECC71))
    Canvas(Modifier.size(width = 20.dp, height = 46.dp)) {
        val w = size.width; val h = size.height
        val corner = androidx.compose.ui.geometry.CornerRadius(w * 0.25f)
        drawRoundRect(Color.Black, cornerRadius = corner)
        drawRoundRect(if (inferred) Color(0xFF9AA0AE) else Color(0xFF5A5F6E), cornerRadius = corner,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = w * 0.07f,
                pathEffect = if (inferred) androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(w * 0.18f, w * 0.12f)) else null))
        val r = w * 0.32f
        lamps.forEachIndexed { i, col ->
            val c = Offset(w / 2, h * (0.18f + 0.32f * i))
            if (arrow == null) drawCircle(col, r, c)
            else {
                // Dark lamp with a coloured arrow, as German arrow signals look.
                drawCircle(Color(0xFF222429), r, c)
                val d = if (arrow and de.lanewise.lane.LaneDir.ANY_LEFT != 0) -1f else 1f
                val a = r * 0.75f
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(c.x + d * a, c.y); lineTo(c.x + d * 0.05f * a, c.y - 0.62f * a); lineTo(c.x + d * 0.05f * a, c.y - 0.22f * a)
                    lineTo(c.x - d * a, c.y - 0.22f * a); lineTo(c.x - d * a, c.y + 0.22f * a); lineTo(c.x + d * 0.05f * a, c.y + 0.22f * a)
                    lineTo(c.x + d * 0.05f * a, c.y + 0.62f * a); close()
                }
                drawPath(path, col)
            }
        }
    }
}

/**
 * Next traffic light as a vertical card on the left above the speed (owner's layout): the signal head, then the
 * separate arrow signals that are likely there (inferred from turn-only lanes: dashed outline), Grünpfeil (Zeichen 720)
 * and push button. No light count — only one signal applies to the driver's lane — and no pedestrian figure.
 */
@Composable
fun SignalCard(sig: de.lanewise.core.model.SignalCluster, modifier: Modifier = Modifier) {
    val green = Color(0xFF2ECC71)
    // Horizontal card under the banner, bold white border so it stands out on the map (owner's layout).
    Row(
        modifier.clip(RoundedCornerShape(14.dp)).background(Color(0xF01A1D24))
            .border(3.dp, Color.White, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // One signal head per lane group, in road order like the owner's photo (Hannover): a head with arrow lamps for
        // the left-turn lanes, the round head for straight on, an arrow head for right-turn lanes. Arrow heads are
        // inferred from turn-only lanes (OSM has no tag for signal heads) and get a dashed outline.
        val left = sig.inferredArrowSignals and de.lanewise.lane.LaneDir.ANY_LEFT != 0
        val right = sig.inferredArrowSignals and de.lanewise.lane.LaneDir.ANY_RIGHT != 0
        if (left) SignalHead(de.lanewise.lane.LaneDir.LEFT, inferred = true)
        SignalHead(null, inferred = false)
        if (right) SignalHead(de.lanewise.lane.LaneDir.RIGHT, inferred = true)
        if (sig.greenArrow) {
            Box(Modifier.size(width = 30.dp, height = 22.dp).clip(RoundedCornerShape(3.dp)).background(Color.Black), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(18.dp)) {
                    LaneArrows.draw(this, de.lanewise.lane.LaneDir.RIGHT, de.lanewise.lane.LaneDir.RIGHT, green, green,
                        size.width * LaneArrows.SHAFT) { p -> Offset(p.x * size.width, p.y * size.height) }
                }
            }
        }
        if (sig.onDemand) {
            Box(Modifier.size(24.dp).clip(RoundedCornerShape(5.dp)).background(Color(0xFFF5A524)), contentAlignment = Alignment.Center) {
                Box(Modifier.size(11.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Color.Black))
            }
        }
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
    /** Left of the "Then" row, under the banner / lane bar (the next traffic light). */
    belowLeft: (@Composable () -> Unit)? = null,
    /** Colour the banner like the German guide sign of the road after the manoeuvre (option). */
    signColors: Boolean = false,
    below: @Composable () -> Unit = {},
) {
    val base = LocalLaneColors.current
    val sign = if (signColors) guideSign(step) else null
    val c = if (sign == null) base else base.copy(banner = sign.first, bannerAccent = sign.second, onBanner = sign.third)
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
            ) { if (step != null) ManeuverIcon(step, if (sign != null) c.onBanner else Color.White, Modifier.size(46.dp)) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(distanceText, color = c.onBanner, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                Text(instruction, color = c.onBanner.copy(alpha = 0.9f), fontSize = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        below()
        val thenChip: @Composable (Modifier) -> Unit = { m ->
            if (then != null && thenText != null) Row(
                m.clip(RoundedCornerShape(12.dp)).background(c.banner.copy(alpha = 0.92f)).padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(thenText, color = c.onBanner, fontSize = 13.sp)
                Spacer(Modifier.width(6.dp))
                ManeuverIcon(then, c.onBanner, Modifier.size(20.dp))
            }
        }
        if (belowLeft != null) {
            // Owner's layout: traffic light left-aligned with the banner, "Then" on the right of the same row.
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                belowLeft()
                Spacer(Modifier.weight(1f))
                thenChip(Modifier)
            }
        } else thenChip(Modifier.padding(start = 12.dp, top = 4.dp))
    }
}

@Composable
fun ManeuverIcon(step: RouteStep, color: Color, modifier: Modifier = Modifier) {
    val bit = when (step.maneuver.type) {
        ManeuverType.ARRIVE, ManeuverType.DEPART, ManeuverType.WAYPOINT -> LaneDir.STRAIGHT
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
    /** Driving time so far (stopwatch), shown next to the remaining time; null = off. */
    elapsedText: String? = null,
    /** Share of the journey driven (0..1): violet bar along the top of the panel with a lime marker at the car. */
    progress: Float? = null,
    /**
     * Demo only: the bar becomes a scrub bar like in a video player — tap or drag it, the demo jumps there on release.
     * [scrubLabel] tells what to show above the thumb while dragging (e.g. the distance there).
     */
    onSeek: ((Float) -> Unit)? = null,
    scrubLabel: (Float) -> String = { "" },
) {
    val c = LocalLaneColors.current
    // The panel's colour runs down behind the system navigation bar, so it sits on the screen edge instead of hovering.
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)).background(c.chip)
        .navigationBarsPadding()) {
    if (progress != null) {
        var drag by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Float?>(null) }
        val animated by androidx.compose.animation.core.animateFloatAsState(progress.coerceIn(0f, 1f), label = "journey")
        val p = drag ?: animated
        if (drag != null) Text(scrubLabel(drag!!), color = c.onChip, fontSize = 13.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        val seek = onSeek
        val touch = if (seek == null) Modifier else Modifier
            .pointerInput(seek) {
                detectTapGestures { o -> seek((o.x / size.width).coerceIn(0f, 1f)) }
            }
            .pointerInput(seek) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> drag = (o.x / size.width).coerceIn(0f, 1f) },
                    onDragEnd = { drag?.let(seek); drag = null },
                    onDragCancel = { drag = null },
                ) { change, _ -> change.consume(); drag = (change.position.x / size.width).coerceIn(0f, 1f) }
            }
        Canvas(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = if (seek != null) 2.dp else 8.dp)
            .height(if (seek != null) 26.dp else 10.dp).then(touch)) {
            val h = (if (seek != null) 8 else 6).dp.toPx(); val y = (size.height - h) / 2
            val r = androidx.compose.ui.geometry.CornerRadius(h / 2)
            drawRoundRect(Color(0xFF3A3F4B), Offset(0f, y), Size(size.width, h), r)
            drawRoundRect(Color(0xFF6C4CF5), Offset(0f, y), Size(size.width * p, h), r)
            val x = (size.width * p).coerceIn(2.dp.toPx(), size.width - 2.dp.toPx())
            if (seek != null) drawCircle(Color(0xFFB8F33A), (if (drag != null) 11 else 8).dp.toPx(), Offset(x, size.height / 2))
            else drawLine(Color(0xFFB8F33A), Offset(x, 0f), Offset(x, size.height), 4.dp.toPx(), StrokeCap.Round)
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundAction(RoundIcon.CLOSE, onClose)
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(remainingText, color = c.onChip, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                if (elapsedText != null) {
                    Text("  ⏱ $elapsedText", color = c.onChip.copy(alpha = 0.85f), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }
            Text("$distanceText · $etaText", color = c.onChip.copy(alpha = 0.7f), fontSize = 15.sp)
        }
        RoundAction(RoundIcon.OVERVIEW, onOverview)
    }
    }
}

enum class RoundIcon { CLOSE, OVERVIEW }

/** Round outlined button with a vector icon drawn exactly in the centre (text glyphs sit off-centre). */
@Composable
fun RoundAction(icon: RoundIcon, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalLaneColors.current
    Box(
        modifier.size(48.dp).clip(CircleShape)
            .border(if (icon == RoundIcon.CLOSE) 2.5.dp else 1.5.dp,
                if (icon == RoundIcon.CLOSE) Color(0xFFE5484D) else c.onChip.copy(alpha = 0.6f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(20.dp)) {
            val w = size.width
            val stroke = w * 0.1f
            val col = c.onChip
            when (icon) {
                RoundIcon.CLOSE -> {
                    // End navigation: red and bold so it is found at a glance.
                    val red = Color(0xFFE5484D); val bold = w * 0.17f
                    drawLine(red, Offset(w * 0.15f, w * 0.15f), Offset(w * 0.85f, w * 0.85f), bold, StrokeCap.Round)
                    drawLine(red, Offset(w * 0.85f, w * 0.15f), Offset(w * 0.15f, w * 0.85f), bold, StrokeCap.Round)
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

/** "Solid line ahead" (AMAP 前方实线): lane changes stop being allowed in [distanceText]. */
@Composable
fun SolidLineChip(label: String, distanceText: String, modifier: Modifier = Modifier) {
    val c = LocalLaneColors.current
    Row(
        modifier.clip(RoundedCornerShape(14.dp)).background(c.chip).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Two lanes, dashed divider turning solid.
        Canvas(Modifier.size(width = 18.dp, height = 26.dp)) {
            val w = size.width; val h = size.height; val sw = w * 0.12f
            drawLine(c.onChip, Offset(sw, 0f), Offset(sw, h), sw)
            drawLine(c.onChip, Offset(w - sw, 0f), Offset(w - sw, h), sw)
            drawLine(c.onChip, Offset(w / 2, h * 0.55f), Offset(w / 2, h * 0.75f), sw)
            drawLine(c.onChip, Offset(w / 2, h * 0.85f), Offset(w / 2, h), sw)
            drawLine(Color(0xFFB8F33A), Offset(w / 2, 0f), Offset(w / 2, h * 0.45f), sw * 1.4f)
        }
        Spacer(Modifier.width(8.dp))
        Column {
            Text(label, color = c.onChip, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(distanceText, color = c.onChip, fontSize = 13.sp)
        }
    }
}

/**
 * German guide-sign colours for the road after [step] (Baidu shows its banner like a Chinese road sign): blue with white
 * text for an Autobahn (`ref` A …), yellow with black text for a Bundesstraße (B …). Null = the app's own colours.
 * Returns (background, icon box, text).
 */
fun guideSign(step: RouteStep?): Triple<Color, Color, Color>? {
    val ref = step?.ref?.split(';', ',')?.firstOrNull()?.trim()?.uppercase() ?: return null
    return when {
        Regex("""^A ?\d+""").containsMatchIn(ref) -> Triple(Color(0xFF1A5DAD), Color(0xFF0F427F), Color.White)
        Regex("""^B ?\d+""").containsMatchIn(ref) -> Triple(Color(0xFFF7C600), Color(0xFFDDAA00), Color(0xFF111111))
        else -> null
    }
}
