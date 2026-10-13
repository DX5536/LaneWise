package de.lanewise.ui.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import de.lanewise.core.geo.Geo
import de.lanewise.core.geo.LatLon
import de.lanewise.core.model.IncidentKind
import de.lanewise.core.model.Route
import de.lanewise.core.model.TrafficIncident
import de.lanewise.lane.LaneRibbonBuilder
import de.lanewise.lane.RoadKind
import de.lanewise.nav.NavSnapshot
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/**
 * Owns the MapLibre map: style loading (light/dark), the 3D follow camera, route + lane layers,
 * the car puck and traffic incident markers.
 */
class MapController(private val context: Context) {
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var dark: Boolean? = null
    var following = true
        private set
    var onUserPannedMap: (() -> Unit)? = null
    var onMapLongPress: ((LatLon) -> Unit)? = null
    private var lastLat = 52.37

    fun attach(view: MapView, darkMode: Boolean) {
        view.getMapAsync { m ->
            map = m
            m.uiSettings.isCompassEnabled = false
            m.uiSettings.isAttributionEnabled = true
            m.uiSettings.isLogoEnabled = false
            m.uiSettings.isRotateGesturesEnabled = true
            m.uiSettings.isTiltGesturesEnabled = true
            m.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                    following = false
                    onUserPannedMap?.invoke()
                }
            }
            m.addOnMapLongClickListener { ll ->
                onMapLongPress?.invoke(LatLon(ll.latitude, ll.longitude)); true
            }
            m.cameraPosition = CameraPosition.Builder().target(LatLng(52.3759, 9.7320)).zoom(12.5).tilt(0.0).build()
            setDark(darkMode)
        }
    }

    fun setDark(isDark: Boolean) {
        val m = map ?: return
        if (dark == isDark && style != null) return
        dark = isDark
        style = null
        m.setStyle(Style.Builder().fromUri(styleUri(isDark))) { s ->
            style = s
            setupLayers(s, isDark)
        }
    }

    fun recenter() { following = true }

    // --- layers ---------------------------------------------------------------------------------

    private fun setupLayers(s: Style, isDark: Boolean) {
        s.addImage(IMG_CAR, carBitmap(if (isDark) 0xFF9A82FF.toInt() else 0xFF6C4CF5.toInt()))
        s.addImage(IMG_DEST, pinBitmap())
        listOf(SRC_ROUTE, SRC_ALT, SRC_LANES, SRC_CAR, SRC_DEST, SRC_INC).forEach { s.addSource(GeoJsonSource(it)) }

        val below = "building-3d"
        fun add(l: Layer) { if (s.getLayer(below) != null) s.addLayerBelow(l, below) else s.addLayer(l) }

        add(LineLayer("alt-line", SRC_ALT).withProperties(
            PropertyFactory.lineColor(if (isDark) "#5A6175" else "#A3A9BA"),
            PropertyFactory.lineWidth(Expression.interpolate(Expression.exponential(1.5f), Expression.zoom(),
                Expression.stop(10, 3f), Expression.stop(18, 12f))),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        ))
        add(LineLayer("route-casing", SRC_ROUTE).withProperties(
            PropertyFactory.lineColor("#4A33B8"),
            PropertyFactory.lineWidth(Expression.interpolate(Expression.exponential(1.5f), Expression.zoom(),
                Expression.stop(10, 6f), Expression.stop(18, 22f))),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        ))
        add(LineLayer("route-line", SRC_ROUTE).withProperties(
            PropertyFactory.lineColor(if (isDark) "#9A82FF" else "#6C4CF5"),
            PropertyFactory.lineWidth(Expression.interpolate(Expression.exponential(1.5f), Expression.zoom(),
                Expression.stop(10, 4f), Expression.stop(18, 16f))),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        ))
        // Lane-level layers (metre-accurate widths via the "w" property).
        val metres = metresToPixels()
        add(LineLayer("lane-surface", SRC_LANES).withFilter(kind("SURFACE")).withProperties(
            PropertyFactory.lineColor(if (isDark) "#1C1F27" else "#2B2F38"),
            PropertyFactory.lineWidth(metres), PropertyFactory.lineOpacity(0.96f),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        ))
        add(LineLayer("lane-allowed", SRC_LANES).withFilter(kind("ALLOWED")).withProperties(
            PropertyFactory.lineColor("#B8F33A"), PropertyFactory.lineOpacity(0.28f),
            PropertyFactory.lineWidth(metres), PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
        ))
        add(LineLayer("lane-recommended", SRC_LANES).withFilter(kind("RECOMMENDED")).withProperties(
            PropertyFactory.lineColor("#B8F33A"), PropertyFactory.lineOpacity(0.9f),
            PropertyFactory.lineWidth(metres), PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        ))
        add(LineLayer("lane-divider", SRC_LANES).withFilter(kind("DIVIDER")).withProperties(
            PropertyFactory.lineColor("#FFFFFF"),
            PropertyFactory.lineWidth(Expression.interpolate(Expression.linear(), Expression.zoom(),
                Expression.stop(15, 0.6f), Expression.stop(20, 2.5f))),
            PropertyFactory.lineDasharray(arrayOf(4f, 4f)),
        ))
        add(LineLayer("lane-edge", SRC_LANES).withFilter(kind("EDGE")).withProperties(
            PropertyFactory.lineColor(if (isDark) "#C9CDD8" else "#FFFFFF"),
            PropertyFactory.lineWidth(Expression.interpolate(Expression.linear(), Expression.zoom(),
                Expression.stop(15, 0.8f), Expression.stop(20, 3f))),
        ))
        s.addLayer(CircleLayer("incidents", SRC_INC).withProperties(
            PropertyFactory.circleRadius(7f),
            PropertyFactory.circleColor(Expression.match(Expression.get("kind"), col("#F2A93B"),
                Expression.stop("CLOSURE", col("#D7191C")), Expression.stop("ACCIDENT", col("#D7191C")),
                Expression.stop("JAM", col("#FF5A5F")), Expression.stop("ROADWORKS", col("#F2A93B")))),
            PropertyFactory.circleStrokeColor("#FFFFFF"), PropertyFactory.circleStrokeWidth(2f),
            PropertyFactory.circlePitchAlignment(Property.CIRCLE_PITCH_ALIGNMENT_MAP),
        ))
        s.addLayer(SymbolLayer("dest", SRC_DEST).withProperties(
            PropertyFactory.iconImage(IMG_DEST), PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
            PropertyFactory.iconAllowOverlap(true), PropertyFactory.iconIgnorePlacement(true),
        ))
        s.addLayer(SymbolLayer("car", SRC_CAR).withProperties(
            PropertyFactory.iconImage(IMG_CAR), PropertyFactory.iconRotate(Expression.get("bearing")),
            PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
            PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_MAP),
            PropertyFactory.iconAllowOverlap(true), PropertyFactory.iconIgnorePlacement(true),
            PropertyFactory.iconSize(Expression.interpolate(Expression.linear(), Expression.zoom(),
                Expression.stop(12, 0.5f), Expression.stop(18, 1.0f))),
        ))
    }

    private fun col(hex: String) = Expression.color(android.graphics.Color.parseColor(hex))

    private fun kind(k: String) = Expression.eq(Expression.get("k"), Expression.literal(k))

    /** line-width in pixels for a feature property "w" in metres, exact at the current latitude. */
    private fun metresToPixels(): Expression {
        val z1 = 10.0
        val z2 = 24.0
        val k1 = 1.0 / Geo.metersPerPixel(lastLat, z1)
        val k2 = 1.0 / Geo.metersPerPixel(lastLat, z2)
        return Expression.interpolate(
            Expression.exponential(2f), Expression.zoom(),
            Expression.stop(z1, Expression.product(Expression.get("w"), Expression.literal(k1))),
            Expression.stop(z2, Expression.product(Expression.get("w"), Expression.literal(k2))),
        )
    }

    // --- data updates ---------------------------------------------------------------------------

    fun showRoutes(main: Route?, alternatives: List<Route>, destination: LatLon?) {
        val s = style ?: return
        (s.getSource(SRC_ROUTE) as? GeoJsonSource)?.setGeoJson(
            FeatureCollection.fromFeatures(listOfNotNull(main?.let { Feature.fromGeometry(line(it.geometry.points)) })))
        (s.getSource(SRC_ALT) as? GeoJsonSource)?.setGeoJson(
            FeatureCollection.fromFeatures(alternatives.map { Feature.fromGeometry(line(it.geometry.points)) }))
        (s.getSource(SRC_DEST) as? GeoJsonSource)?.setGeoJson(
            FeatureCollection.fromFeatures(listOfNotNull(destination?.let { Feature.fromGeometry(Point.fromLngLat(it.lon, it.lat)) })))
        if (main == null) clearLanes()
    }

    fun showIncidents(list: List<TrafficIncident>) {
        val s = style ?: return
        (s.getSource(SRC_INC) as? GeoJsonSource)?.setGeoJson(FeatureCollection.fromFeatures(list.map {
            Feature.fromGeometry(Point.fromLngLat(it.location.lon, it.location.lat)).apply {
                addStringProperty("kind", it.kind.name)
                addStringProperty("title", it.title)
            }
        }))
    }

    fun overview(route: Route, topPx: Int, bottomPx: Int) {
        val m = map ?: return
        following = false
        val b = LatLngBounds.Builder()
        route.geometry.points.forEach { b.include(LatLng(it.lat, it.lon)) }
        m.animateCamera(CameraUpdateFactory.newLatLngBounds(b.build(), 80, topPx, 80, bottomPx), 800)
    }

    fun clearLanes() {
        (style?.getSource(SRC_LANES) as? GeoJsonSource)?.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
    }

    fun updateCar(position: LatLon, bearing: Double) {
        val s = style ?: return
        (s.getSource(SRC_CAR) as? GeoJsonSource)?.setGeoJson(
            Feature.fromGeometry(Point.fromLngLat(position.lon, position.lat)).apply { addNumberProperty("bearing", bearing) })
    }

    fun updateNavigation(snap: NavSnapshot, showRibbons: Boolean, pitch: Float, topPaddingPx: Double) {
        val s = style ?: return
        val along = snap.distanceAlong
        val pos = if (snap.offRoute) snap.fix.position else snap.route.geometry.pointAt(along)
        val bearing = if (snap.offRoute) (snap.fix.bearing?.toDouble() ?: 0.0) else snap.route.geometry.bearingAt(along + 8)
        updateCar(pos, bearing)

        if (kotlin.math.abs(pos.lat - lastLat) > 0.5) {
            lastLat = pos.lat
            listOf("lane-surface", "lane-allowed", "lane-recommended").forEach {
                (s.getLayer(it) as? LineLayer)?.setProperties(PropertyFactory.lineWidth(metresToPixels()))
            }
        }

        if (showRibbons && !snap.offRoute) {
            val lines = LaneRibbonBuilder.build(snap.route, snap.lanes, along)
            (s.getSource(SRC_LANES) as? GeoJsonSource)?.setGeoJson(FeatureCollection.fromFeatures(lines.map { l ->
                Feature.fromGeometry(line(l.points)).apply {
                    addStringProperty("k", l.kind.name)
                    addNumberProperty("w", l.widthM)
                }
            }))
        } else clearLanes()

        if (following) {
            val m = map ?: return
            val zoom = when {
                snap.distanceToNextStep < 250 -> 18.4
                snap.roadKind == RoadKind.MOTORWAY -> 16.6
                snap.roadKind == RoadKind.TRUNK -> 17.0
                snap.fix.speedKmh > 70 -> 17.0
                else -> 17.6
            }
            val cam = CameraPosition.Builder()
                .target(LatLng(pos.lat, pos.lon))
                .bearing(bearing)
                .tilt(pitch.toDouble())
                .zoom(zoom)
                .padding(0.0, topPaddingPx, 0.0, 0.0)
                .build()
            m.easeCamera(CameraUpdateFactory.newCameraPosition(cam), 900)
        }
    }

    fun followCar(position: LatLon, bearing: Double?, pitch: Float) {
        val m = map ?: return
        updateCar(position, bearing ?: 0.0)
        if (!following) return
        val builder = CameraPosition.Builder().target(LatLng(position.lat, position.lon))
            .zoom(16.0).tilt(if (bearing != null) pitch.toDouble() * 0.6 else 0.0)
        if (bearing != null) builder.bearing(bearing)
        val cam = builder.build()
        m.easeCamera(CameraUpdateFactory.newCameraPosition(cam), 900)
    }

    private fun line(points: List<LatLon>) = LineString.fromLngLats(points.map { Point.fromLngLat(it.lon, it.lat) })

    // --- bitmaps ----------------------------------------------------------------------------------

    private fun carBitmap(color: Int): Bitmap {
        val size = 96
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = 0x55000000
        c.drawCircle(48f, 52f, 34f, p)
        p.color = 0xFFFFFFFF.toInt()
        c.drawCircle(48f, 48f, 34f, p)
        val arrow = Path().apply {
            moveTo(48f, 16f); lineTo(74f, 74f); lineTo(48f, 60f); lineTo(22f, 74f); close()
        }
        p.color = color
        c.drawPath(arrow, p)
        return bmp
    }

    private fun pinBitmap(): Bitmap {
        val bmp = Bitmap.createBitmap(72, 96, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = 0xFF6C4CF5.toInt()
        val path = Path().apply {
            moveTo(36f, 94f); cubicTo(36f, 94f, 4f, 56f, 4f, 36f); cubicTo(4f, 16f, 18f, 4f, 36f, 4f)
            cubicTo(54f, 4f, 68f, 16f, 68f, 36f); cubicTo(68f, 56f, 36f, 94f, 36f, 94f); close()
        }
        c.drawPath(path, p)
        p.color = 0xFFB8F33A.toInt()
        c.drawCircle(36f, 36f, 13f, p)
        return bmp
    }

    companion object {
        fun styleUri(dark: Boolean) = if (dark) "asset://styles/lanewise_dark.json" else "asset://styles/lanewise_light.json"

        private const val SRC_ROUTE = "lw-route"
        private const val SRC_ALT = "lw-alt"
        private const val SRC_LANES = "lw-lanes"
        private const val SRC_CAR = "lw-car"
        private const val SRC_DEST = "lw-dest"
        private const val SRC_INC = "lw-incidents"
        private const val IMG_CAR = "lw-car-img"
        private const val IMG_DEST = "lw-dest-img"
    }
}
