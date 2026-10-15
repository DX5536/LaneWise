package de.lanewise.ui.map

import de.lanewise.core.geo.Geo
import de.lanewise.core.geo.LatLon
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * A low-poly 3D car for MapLibre `fill-extrusion` (MapLibre Native has no glTF model layer): body, glass cabin,
 * roof, wheels and lights are footprint polygons extruded between a base and a top height. Rotates with the bearing,
 * so in the tilted navigation view it reads as a real car on the lane, like Baidu Maps / AMAP's lane-level car.
 *
 * Sizes are those of a ~4.6 m hatchback, exaggerated by [scale] (default [DEFAULT_EXAGGERATION]): at zoom 17 a real
 * car is only ~6 dp long. Pure Kotlin (testable).
 */
object CarModel {

    enum class Part { SHADOW, WHEEL, BODY, GLASS, ROOF, HEADLIGHT, TAILLIGHT }

    /** One extruded block: footprint (closed ring), base and top height (m). */
    data class Block(val part: Part, val ring: List<LatLon>, val base: Double, val top: Double)

    /** How much bigger than life the car is drawn at size ×1 (it must stay visible next to the lanes). */
    const val DEFAULT_EXAGGERATION = 2.4

    /** Local box: x to the right, y forward (m), both relative to the car centre. */
    private data class Box(val part: Part, val x0: Double, val x1: Double, val y0: Double, val y1: Double,
                           val base: Double, val top: Double, val chamfer: Double = 0.0)

    private val boxes = listOf(
        // Soft shadow under the car (flat, drawn as a fill).
        Box(Part.SHADOW, -1.35, 1.35, -2.8, 2.8, 0.0, 0.0, chamfer = 1.0),
        // Wheels stick out a little beside the body.
        Box(Part.WHEEL, -1.0, -0.72, 1.05, 1.75, 0.0, 0.62),
        Box(Part.WHEEL, 0.72, 1.0, 1.05, 1.75, 0.0, 0.62),
        Box(Part.WHEEL, -1.0, -0.72, -1.75, -1.05, 0.0, 0.62),
        Box(Part.WHEEL, 0.72, 1.0, -1.75, -1.05, 0.0, 0.62),
        // Body with rounded (chamfered) corners; the nose is narrower.
        Box(Part.BODY, -0.93, 0.93, -2.3, 2.3, 0.28, 0.92, chamfer = 0.42),
        // Glass cabin set back from the nose, and the roof on top.
        Box(Part.GLASS, -0.8, 0.8, -1.55, 0.75, 0.92, 1.36, chamfer = 0.3),
        Box(Part.ROOF, -0.74, 0.74, -1.4, 0.45, 1.36, 1.46, chamfer = 0.28),
        // Lights: thin slabs on the nose and tail.
        Box(Part.HEADLIGHT, -0.78, -0.4, 2.22, 2.36, 0.62, 0.78),
        Box(Part.HEADLIGHT, 0.4, 0.78, 2.22, 2.36, 0.62, 0.78),
        Box(Part.TAILLIGHT, -0.82, -0.36, -2.36, -2.22, 0.66, 0.84),
        Box(Part.TAILLIGHT, 0.36, 0.82, -2.36, -2.22, 0.66, 0.84),
    )

    /** The car at [centre] heading [bearing] (deg), drawn [scale] × life size (all dimensions, heights too). */
    fun blocks(centre: LatLon, bearing: Double, scale: Double = DEFAULT_EXAGGERATION): List<Block> = boxes.map { b ->
        val local = ring(b)
        Block(b.part, local.map { (x, y) -> place(centre, bearing, x * scale, y * scale) }, b.base * scale, b.top * scale)
    }

    /** One block in car coordinates (m): x right, y forward; [ring] closed; base / top heights. */
    data class LocalBlock(val part: Part, val ring: List<Pair<Double, Double>>, val base: Double, val top: Double)

    /** The car in its own coordinates, [scale] × life size — for drawing it as a screen sprite ([CarSprite]). */
    fun localBlocks(scale: Double = DEFAULT_EXAGGERATION): List<LocalBlock> = boxes.map { b ->
        LocalBlock(b.part, ring(b).map { (x, y) -> x * scale to y * scale }, b.base * scale, b.top * scale)
    }

    /** Footprint corners (x, y), counter-clockwise, closed; corners cut by [Box.chamfer]. */
    private fun ring(b: Box): List<Pair<Double, Double>> {
        val c = b.chamfer.coerceAtMost(minOf(b.x1 - b.x0, b.y1 - b.y0) / 2 - 0.01)
        val pts = if (c <= 0) listOf(b.x0 to b.y0, b.x1 to b.y0, b.x1 to b.y1, b.x0 to b.y1)
        else listOf(
            b.x0 + c to b.y0, b.x1 - c to b.y0, b.x1 to b.y0 + c, b.x1 to b.y1 - c,
            b.x1 - c to b.y1, b.x0 + c to b.y1, b.x0 to b.y1 - c, b.x0 to b.y0 + c,
        )
        return pts + pts.first()
    }

    /** Local metres (x right, y forward) → map position. */
    private fun place(centre: LatLon, bearing: Double, x: Double, y: Double): LatLon {
        if (x == 0.0 && y == 0.0) return centre
        val dist = hypot(x, y)
        val angle = Geo.toDeg(atan2(x, y))   // clockwise from forward
        return Geo.destination(centre, bearing + angle, dist)
    }
}
