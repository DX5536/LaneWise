package de.lanewise.ui.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/**
 * The 3D car ([CarModel]) drawn as a picture seen from the navigation camera: behind and above, at the map's tilt,
 * always pointing up the screen (the camera turns with the car). Rendered once per size / tilt into a bitmap with a
 * small painter's-algorithm renderer — top faces lit, rear faces shaded, far faces first.
 */
object CarSprite {

    data class Sprite(val bitmap: Bitmap, val anchorX: Float, val anchorY: Float)

    /**
     * [pxPerMetre]: screen pixels per metre across the screen at the car; [tiltDeg]: map tilt (0 = top-down);
     * [scale]: car exaggeration (see [CarModel.DEFAULT_EXAGGERATION]); [headingDeg]: the car's heading relative to the
     * camera (0 = up the screen, clockwise).
     */
    fun render(pxPerMetre: Float, tiltDeg: Double, scale: Double, dark: Boolean, headingDeg: Double = 0.0): Sprite {
        val p = Math.toRadians(tiltDeg.coerceIn(0.0, 75.0))
        val cp = cos(p); val sp = sin(p)
        val hr = Math.toRadians(headingDeg)
        val ch = cos(hr); val sh = sin(hr)
        // Car coordinates (x right, y forward) → camera coordinates, turned by the heading (clockwise).
        fun rx(x: Double, y: Double) = x * ch + y * sh
        fun ry(x: Double, y: Double) = -x * sh + y * ch
        fun sx(x: Double) = (x * pxPerMetre).toFloat()
        fun sy(y: Double, z: Double) = (-(y * cp + z * sp) * pxPerMetre).toFloat()
        // Depth along the view direction (forward and down): larger = further away.
        fun depth(y: Double, z: Double) = y * sp - z * cp

        class Face(val pts: List<Pair<Float, Float>>, val color: Int, val depth: Double)
        val faces = ArrayList<Face>()
        for (b in CarModel.localBlocks(scale)) {
            val base = colorOf(b.part, dark)
            val ring = b.ring.dropLast(1).map { (x, y) -> rx(x, y) to ry(x, y) }
            if (b.part == CarModel.Part.SHADOW) {
                faces += Face(ring.map { (x, y) -> sx(x) to sy(y, 0.0) }, base, Double.MAX_VALUE)
                continue
            }
            // Wheels only peek out beside / under the body: draw them first, without a top.
            val wheel = b.part == CarModel.Part.WHEEL
            // Side faces that look towards the camera (outward normal pointing backwards).
            for (i in ring.indices) {
                val (x0, y0) = ring[i]; val (x1, y1) = ring[(i + 1) % ring.size]
                val nx = y1 - y0; val ny = -(x1 - x0)          // outward for counter-clockwise rings
                if (ny >= -1e-6) continue
                val shade = if (kotlin.math.abs(nx) > kotlin.math.abs(ny) * 2) 0.78f else 0.68f
                faces += Face(
                    listOf(sx(x0) to sy(y0, b.base), sx(x1) to sy(y1, b.base), sx(x1) to sy(y1, b.top), sx(x0) to sy(y0, b.top)),
                    shadeColor(base, shade), if (wheel) Double.MAX_VALUE / 2 else depth((y0 + y1) / 2, (b.base + b.top) / 2),
                )
            }
            if (wheel) continue
            // Top face.
            val cy = ring.map { it.second }.average()
            faces += Face(ring.map { (x, y) -> sx(x) to sy(y, b.top) }, base, depth(cy, b.top) - 0.01)
        }
        val minX = faces.minOf { f -> f.pts.minOf { it.first } }; val maxX = faces.maxOf { f -> f.pts.maxOf { it.first } }
        val minY = faces.minOf { f -> f.pts.minOf { it.second } }; val maxY = faces.maxOf { f -> f.pts.maxOf { it.second } }
        val pad = 4f
        val w = (maxX - minX + 2 * pad).toInt().coerceIn(8, 1200)
        val h = (maxY - minY + 2 * pad).toInt().coerceIn(8, 1200)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val ox = pad - minX; val oy = pad - minY
        for (f in faces.sortedByDescending { it.depth }) {
            paint.color = f.color
            val path = Path()
            f.pts.forEachIndexed { i, (x, y) -> if (i == 0) path.moveTo(x + ox, y + oy) else path.lineTo(x + ox, y + oy) }
            path.close()
            c.drawPath(path, paint)
        }
        return Sprite(bmp, ox, oy)
    }

    private fun colorOf(part: CarModel.Part, dark: Boolean): Int = when (part) {
        CarModel.Part.SHADOW -> if (dark) 0x8C6C4CF5.toInt() else 0x666C4CF5
        CarModel.Part.WHEEL -> 0xFF16161A.toInt()
        CarModel.Part.BODY -> 0xFFF4F4F8.toInt()
        CarModel.Part.GLASS -> 0xFF232A38.toInt()
        CarModel.Part.ROOF -> 0xFFFFFFFF.toInt()
        CarModel.Part.HEADLIGHT -> 0xFFFFF4C2.toInt()
        CarModel.Part.TAILLIGHT -> 0xFFE5484D.toInt()
    }

    private fun shadeColor(c: Int, f: Float) =
        Color.argb(Color.alpha(c), (Color.red(c) * f).toInt(), (Color.green(c) * f).toInt(), (Color.blue(c) * f).toInt())
}

/**
 * Transparent view on top of the map that draws the car (and the road name under it) at a fixed screen point while
 * the camera follows it. A map layer would have to be re-tiled every frame and lands a frame or two after the
 * camera — the car jittered against the map; on screen it simply stays where the camera keeps it.
 */
class CarOverlayView(context: Context) : View(context) {
    private var sprite: CarSprite.Sprite? = null
    private var x = 0f
    private var y = 0f
    private var label: String? = null
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; textSize = 13f * resources.displayMetrics.scaledDensity; isFakeBoldText = true
    }
    private val haloPaint = Paint(textPaint).apply { style = Paint.Style.STROKE; strokeWidth = 5f * resources.displayMetrics.density }

    init { setWillNotDraw(false) }

    /** Show [s] with its anchor at ([px], [py]); null hides the car. */
    fun show(s: CarSprite.Sprite?, px: Float, py: Float, text: String?, dark: Boolean) {
        if (s === sprite && kotlin.math.abs(px - x) < 0.3f && kotlin.math.abs(py - y) < 0.3f && text == label) return
        sprite = s; x = px; y = py; label = text
        textPaint.color = if (dark) Color.WHITE else 0xFF1C1F27.toInt()
        haloPaint.color = if (dark) 0xFF1C1F27.toInt() else Color.WHITE
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val s = sprite ?: return
        canvas.drawBitmap(s.bitmap, x - s.anchorX, y - s.anchorY, bmpPaint)
        val t = label ?: return
        val ty = y - s.anchorY + s.bitmap.height + textPaint.textSize * 1.2f
        canvas.drawText(t, x, ty, haloPaint)
        canvas.drawText(t, x, ty, textPaint)
    }
}
