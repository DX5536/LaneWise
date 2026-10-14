package de.lanewise.ui.hud

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import de.lanewise.core.model.FeatureKind
import kotlin.math.cos
import kotlin.math.sin

/**
 * Simplified German road-sign artwork drawn with android.graphics, shared by the MapLibre icons and the
 * Compose HUD (via asImageBitmap) so both look identical. Shapes follow StVO signs; drawn from scratch.
 */
object RoadSignArt {
    private const val RED = 0xFFD7191C.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val BLACK = 0xFF1A1A1A.toInt()
    private const val BLUE = 0xFF1F5FBF.toInt()
    private const val AMBER = 0xFFF6B100.toInt()
    private const val GREEN = 0xFF27AE60.toInt()

    private val cache = HashMap<String, Bitmap>()

    fun feature(kind: FeatureKind, sizePx: Int): Bitmap = cache.getOrPut("${kind.name}-$sizePx") {
        val b = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val s = sizePx.toFloat()
        when (kind) {
            FeatureKind.TRAFFIC_SIGNALS -> trafficLight(c, s)
            FeatureKind.STOP -> stop(c, s)
            FeatureKind.GIVE_WAY -> giveWay(c, s)
            FeatureKind.CROSSING -> crossing(c, s)
            FeatureKind.RAILWAY_CROSSING -> andreasCross(c, s)
        }
        b
    }

    /** Blue round "Radweg" disc (Zeichen 237) with a white bicycle; [ring] lets it match the user's lane colour. */
    fun bike(sizePx: Int, ring: Int): Bitmap = cache.getOrPut("bike-$sizePx-$ring") {
        val b = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val s = sizePx.toFloat()
        val p = paint(ring)
        c.drawCircle(s / 2, s / 2, s * 0.48f, p)
        p.color = BLUE
        c.drawCircle(s / 2, s / 2, s * 0.40f, p)
        val st = stroke(WHITE, s * 0.06f)
        c.drawCircle(s * 0.32f, s * 0.6f, s * 0.12f, st)
        c.drawCircle(s * 0.68f, s * 0.6f, s * 0.12f, st)
        val frame = Path().apply {
            moveTo(s * 0.32f, s * 0.6f); lineTo(s * 0.45f, s * 0.42f); lineTo(s * 0.62f, s * 0.42f)
            lineTo(s * 0.68f, s * 0.6f); moveTo(s * 0.45f, s * 0.42f); lineTo(s * 0.52f, s * 0.6f); lineTo(s * 0.62f, s * 0.42f)
            moveTo(s * 0.42f, s * 0.36f); lineTo(s * 0.5f, s * 0.36f)
        }
        c.drawPath(frame, st)
        b
    }

    /**
     * AMAP-style pop-up for a signalised junction, shown on the map at the junction: the main signal (× count),
     * separate arrow signals (inferred from turn-only lanes, drawn with a dashed outline because OSM cannot confirm
     * them), the green-arrow sign (Zeichen 720, "bei Rot rechts abbiegen"), pedestrian lights and a push button.
     * [density] scales it to the screen; bubble with a pointer at the bottom centre.
     */
    fun signalPopup(sig: de.lanewise.core.model.SignalCluster, density: Float): Bitmap {
        val key = "sig-${sig.vehicleLights}-${sig.pedestrianLights}-${sig.greenArrow}-${sig.onDemand}-${sig.inferredArrowSignals}-$density"
        return cache.getOrPut(key) {
            val u = 12f * density                                   // layout unit
            val items = ArrayList<(Canvas, Float) -> Unit>()       // each draws at x, returns via width list
            val widths = ArrayList<Float>()
            fun item(w: Float, f: (Canvas, Float) -> Unit) { items += f; widths += w }
            val h = 5f * u
            // Main signal.
            item(2.2f * u) { c, x ->
                val body = RectF(x, 0.5f * u, x + 2.2f * u, 4.5f * u)
                c.drawRoundRect(body, 0.5f * u, 0.5f * u, paint(BLACK))
                c.drawRoundRect(body, 0.5f * u, 0.5f * u, stroke(0xFF5A5F6E.toInt(), 0.12f * u))
                val cx = x + 1.1f * u; val r = 0.48f * u
                c.drawCircle(cx, 1.35f * u, r, paint(RED)); c.drawCircle(cx, 2.5f * u, r, paint(AMBER)); c.drawCircle(cx, 3.65f * u, r, paint(GREEN))
            }
            if (sig.vehicleLights > 1) item(2.0f * u) { c, x ->
                c.drawText("×${sig.vehicleLights}", x + 0.2f * u, 3.0f * u, text(WHITE, 1.4f * u))
            }
            // Separate arrow signals (inferred).
            for (bit in listOf(de.lanewise.lane.LaneDir.ANY_LEFT, de.lanewise.lane.LaneDir.ANY_RIGHT)) {
                if (sig.inferredArrowSignals and bit == 0) continue
                val left = bit == de.lanewise.lane.LaneDir.ANY_LEFT
                item(2.0f * u) { c, x ->
                    val body = RectF(x, 1.4f * u, x + 2.0f * u, 3.6f * u)
                    c.drawRoundRect(body, 0.4f * u, 0.4f * u, paint(BLACK))
                    c.drawRoundRect(body, 0.4f * u, 0.4f * u, stroke(0xFF9AA0AE.toInt(), 0.1f * u).apply {
                        pathEffect = android.graphics.DashPathEffect(floatArrayOf(0.3f * u, 0.2f * u), 0f)
                    })
                    arrow(c, x + 1.0f * u, 2.5f * u, 0.75f * u, left, GREEN)
                }
            }
            // Green-arrow sign (Zeichen 720).
            if (sig.greenArrow) item(2.4f * u) { c, x ->
                val plate = RectF(x, 1.5f * u, x + 2.4f * u, 3.5f * u)
                c.drawRoundRect(plate, 0.2f * u, 0.2f * u, paint(BLACK))
                arrow(c, x + 1.2f * u, 2.5f * u, 0.85f * u, false, GREEN)
            }
            // Pedestrian lights.
            if (sig.pedestrianLights > 0) item(3.2f * u) { c, x ->
                val g = stroke(GREEN, 0.22f * u)
                val cx = x + 0.8f * u
                c.drawCircle(cx, 1.6f * u, 0.35f * u, paint(GREEN))
                c.drawLine(cx, 2.0f * u, cx, 3.0f * u, g)
                c.drawLine(cx, 2.3f * u, cx - 0.5f * u, 2.8f * u, g); c.drawLine(cx, 2.3f * u, cx + 0.5f * u, 2.8f * u, g)
                c.drawLine(cx, 3.0f * u, cx - 0.45f * u, 3.9f * u, g); c.drawLine(cx, 3.0f * u, cx + 0.45f * u, 3.9f * u, g)
                c.drawText("×${sig.pedestrianLights}", x + 1.5f * u, 3.0f * u, text(WHITE, 1.2f * u))
            }
            // Push button (Anforderungstaster).
            if (sig.onDemand) item(1.8f * u) { c, x ->
                val box = RectF(x, 1.6f * u, x + 1.8f * u, 3.4f * u)
                c.drawRoundRect(box, 0.3f * u, 0.3f * u, paint(AMBER))
                c.drawCircle(x + 0.9f * u, 2.5f * u, 0.45f * u, paint(BLACK))
            }
            val gap = 0.6f * u; val pad = 0.8f * u
            val w = pad * 2 + widths.sum() + gap * (widths.size - 1)
            val pointer = 0.9f * u
            val b = Bitmap.createBitmap(w.toInt() + 1, (h + pointer).toInt() + 1, Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            val bubble = RectF(0f, 0f, w, h)
            val bg = paint(0xF01A1D24.toInt())
            c.drawRoundRect(bubble, 1.0f * u, 1.0f * u, bg)
            c.drawPath(Path().apply { moveTo(w / 2 - pointer, h - 1); lineTo(w / 2, h + pointer); lineTo(w / 2 + pointer, h - 1); close() }, bg)
            c.drawRoundRect(bubble, 1.0f * u, 1.0f * u, stroke(0xFF6C4CF5.toInt(), 0.12f * u))
            var x = pad
            items.forEachIndexed { i, f -> f(c, x); x += widths[i] + gap }
            b
        }
    }

    private fun arrow(c: Canvas, cx: Float, cy: Float, r: Float, left: Boolean, color: Int) {
        val d = if (left) -1f else 1f
        val p = Path().apply {
            moveTo(cx - d * r, cy - 0.22f * r); lineTo(cx + d * 0.1f * r, cy - 0.22f * r); lineTo(cx + d * 0.1f * r, cy - 0.65f * r)
            lineTo(cx + d * r, cy); lineTo(cx + d * 0.1f * r, cy + 0.65f * r); lineTo(cx + d * 0.1f * r, cy + 0.22f * r)
            lineTo(cx - d * r, cy + 0.22f * r); close()
        }
        c.drawPath(p, paint(color))
    }

    private fun text(color: Int, size: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; textSize = size; typeface = Typeface.DEFAULT_BOLD
    }

    private fun paint(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }
    private fun stroke(color: Int, w: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; style = Paint.Style.STROKE; strokeWidth = w; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }

    private fun trafficLight(c: Canvas, s: Float) {
        val body = RectF(s * 0.28f, s * 0.04f, s * 0.72f, s * 0.96f)
        c.drawRoundRect(body, s * 0.12f, s * 0.12f, paint(BLACK))
        c.drawRoundRect(body, s * 0.12f, s * 0.12f, stroke(WHITE, s * 0.04f))
        val r = s * 0.11f
        c.drawCircle(s / 2, s * 0.22f, r, paint(RED))
        c.drawCircle(s / 2, s * 0.5f, r, paint(AMBER))
        c.drawCircle(s / 2, s * 0.78f, r, paint(GREEN))
    }

    private fun stop(c: Canvas, s: Float) {
        fun octagon(rad: Float) = Path().apply {
            for (i in 0 until 8) {
                val a = Math.toRadians(22.5 + 45.0 * i)
                val x = s / 2 + rad * cos(a).toFloat(); val y = s / 2 + rad * sin(a).toFloat()
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        }
        c.drawPath(octagon(s * 0.48f), paint(WHITE))
        c.drawPath(octagon(s * 0.43f), paint(RED))
        val t = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = WHITE; textSize = s * 0.27f; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
        }
        c.drawText("STOP", s / 2, s / 2 + s * 0.1f, t)
    }

    private fun giveWay(c: Canvas, s: Float) {
        fun tri(inset: Float) = Path().apply {
            moveTo(s * 0.04f + inset * 1.7f, s * 0.1f + inset); lineTo(s * 0.96f - inset * 1.7f, s * 0.1f + inset)
            lineTo(s / 2, s * 0.92f - inset * 2f); close()
        }
        c.drawPath(tri(0f), paint(WHITE))
        c.drawPath(tri(s * 0.03f), paint(RED))
        c.drawPath(tri(s * 0.12f), paint(WHITE))
    }

    private fun crossing(c: Canvas, s: Float) {
        c.drawRoundRect(RectF(s * 0.04f, s * 0.04f, s * 0.96f, s * 0.96f), s * 0.1f, s * 0.1f, paint(WHITE))
        c.drawRoundRect(RectF(s * 0.08f, s * 0.08f, s * 0.92f, s * 0.92f), s * 0.08f, s * 0.08f, paint(BLUE))
        val tri = Path().apply { moveTo(s / 2, s * 0.14f); lineTo(s * 0.86f, s * 0.82f); lineTo(s * 0.14f, s * 0.82f); close() }
        c.drawPath(tri, paint(WHITE))
        val bar = paint(BLACK)
        for (k in 0 until 4) {
            val x = s * (0.3f + 0.11f * k)
            c.drawRect(x, s * 0.7f, x + s * 0.06f, s * 0.78f, bar)
        }
        // Pedestrian
        c.drawCircle(s * 0.5f, s * 0.36f, s * 0.05f, bar)
        c.drawPath(Path().apply {
            moveTo(s * 0.5f, s * 0.42f); lineTo(s * 0.47f, s * 0.56f); lineTo(s * 0.42f, s * 0.66f)
            moveTo(s * 0.47f, s * 0.56f); lineTo(s * 0.55f, s * 0.66f)
            moveTo(s * 0.43f, s * 0.48f); lineTo(s * 0.57f, s * 0.5f)
        }, stroke(BLACK, s * 0.04f))
    }

    private fun andreasCross(c: Canvas, s: Float) {
        c.save()
        c.rotate(45f, s / 2, s / 2)
        for (k in 0 until 2) {
            val bar = RectF(s * 0.04f, s * 0.42f, s * 0.96f, s * 0.58f)
            c.drawRect(bar, paint(RED))
            c.drawRect(bar, stroke(WHITE, s * 0.025f))
            c.drawRect(s * 0.3f, s * 0.42f, s * 0.42f, s * 0.58f, paint(WHITE))
            c.drawRect(s * 0.58f, s * 0.42f, s * 0.7f, s * 0.58f, paint(WHITE))
            c.rotate(90f, s / 2, s / 2)
        }
        c.restore()
    }
}
