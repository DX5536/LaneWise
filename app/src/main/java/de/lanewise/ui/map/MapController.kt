package de.lanewise.ui.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.view.Choreographer
import de.lanewise.core.geo.Geo
import de.lanewise.core.geo.LatLon
import de.lanewise.core.model.FeatureKind
import de.lanewise.core.model.RoadFeature
import de.lanewise.core.model.Route
import de.lanewise.core.model.TrafficIncident
import de.lanewise.lane.LaneRibbonBuilder
import de.lanewise.lane.RoadKind
import de.lanewise.nav.NavSnapshot
import de.lanewise.ui.hud.RoadSignArt
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillExtrusionLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Owns the MapLibre map: style loading (light/dark), route + lane layers, traffic lights/signs, the car puck,
 * and the 3D follow camera. Navigation updates arrive at GNSS rate (≈1–4 Hz); between them the car and the
 * camera are tweened every display frame (Choreographer) along the route, so motion stays smooth.
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
    private var bikeColor: Int = 0xFFE5484D.toInt()
    private var lastFeatures: List<RoadFeature> = emptyList()
    private var featuresVisible = true
    private var satelliteOn = false

    // --- tween state --------------------------------------------------------------------------------
    private var tweenRoute: Route? = null
    /** Lane centre line from the last ribbon build; the car drives on it instead of the road's centre line. */
    private var ribbon: LaneRibbonBuilder.Ribbon? = null
    /** Route position and time of the last navigation update; the car is dead-reckoned from here at [speedMps]. */
    private var fixAlong = 0.0
    private var fixAt = 0L
    private var speedMps = 0.0
    private var lastFrameNanos = 0L
    private var shownAlong = Double.NaN
    private var shownBearing = Double.NaN
    private var shownZoom = Double.NaN
    private var shownPitch = Double.NaN
    private var targetZoom = 17.6
    private var tweenPitch = 60.0
    private var tweenTopPad = 0.0
    private var offRoutePos: LatLon? = null
    private var offRouteBearing = 0.0
    private var frameRunning = false
    /** Lane geometry is built off the UI thread (it was the demo's stutter); one build at a time, latest request wins. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var geomJob: Job? = null
    private var geomPending: (() -> Unit)? = null
    /** Bumped when lanes are cleared, so a build that finishes afterwards is dropped. */
    private var geomGeneration = 0
    private val choreographer by lazy { Choreographer.getInstance() }
    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (tweenRoute == null && offRoutePos == null) { frameRunning = false; lastFrameNanos = 0L; return }
            val dt = if (lastFrameNanos == 0L) 1.0 / 60 else ((frameTimeNanos - lastFrameNanos) / 1e9).coerceIn(0.001, 0.1)
            lastFrameNanos = frameTimeNanos
            stepTween(dt)
            choreographer.postFrameCallback(this)
        }
    }

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
            // New style = empty sources: force the next navigation update to hand over road + highlight again.
            geomKey = null; shownChunks = emptySet()
            setupLayers(s, isDark)
            showFeatures(lastFeatures, featuresVisible)
            setSatellite(satelliteOn)
        }
    }

    fun recenter() { following = true }

    // --- layers ---------------------------------------------------------------------------------

    private fun setupLayers(s: Style, isDark: Boolean) {
        s.addImage(IMG_CAR, carBitmap(if (isDark) 0xFF9A82FF.toInt() else 0xFF6C4CF5.toInt()))
        s.addImage(IMG_DEST, pinBitmap())
        FeatureKind.entries.forEach { s.addImage("lw-feat-${it.name}", RoadSignArt.feature(it, 64)) }
        listOf(SRC_ROUTE, SRC_ALT, SRC_LANES, SRC_LANES_DYN, SRC_CAR, SRC_CAR3D, SRC_DEST, SRC_INC, SRC_FEAT, SRC_SIGNAL).forEach { s.addSource(GeoJsonSource(it)) }

        val below = "building-3d"
        fun add(l: Layer) { if (s.getLayer(below) != null) s.addLayerBelow(l, below) else s.addLayer(l) }

        // Official aerial imagery of Niedersachsen (LGLN DOP20, 20 cm, CC BY 4.0) via WMS in web-mercator tiles.
        s.addSource(RasterSource(SRC_SAT, TileSet("2.2.0", DOP20_WMS).apply {
            attribution = "Luftbild: LGLN (2026), CC BY 4.0"
            minZoom = 10f; maxZoom = 20f
            // NOT setBounds(Float, Float, Float, Float): in MapLibre 11.13.5 that overload calls itself
            // (StackOverflowError in onDidFinishLoadingStyle -> app crashes on start). Array overload is fine.
            setBounds(arrayOf(6.6f, 51.29f, 11.6f, 53.9f))
        }, 256))
        add(RasterLayer("satellite", SRC_SAT).withProperties(PropertyFactory.visibility(Property.NONE)))

        add(LineLayer("alt-line", SRC_ALT).withProperties(
            PropertyFactory.lineColor(if (isDark) "#5A6175" else "#A3A9BA"),
            PropertyFactory.lineWidth(Expression.interpolate(Expression.exponential(1.5f), Expression.zoom(),
                Expression.stop(10, 3f), Expression.stop(18, 12f))),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        ))
        add(LineLayer("route-casing", SRC_ROUTE).withProperties(
            PropertyFactory.lineColor(if (isDark) "#4A33B8" else "#6C4CF5"),
            PropertyFactory.lineWidth(Expression.interpolate(Expression.exponential(1.5f), Expression.zoom(),
                Expression.stop(10, 6f), Expression.stop(18, 22f))),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        ))
        add(LineLayer("route-line", SRC_ROUTE).withProperties(
            // Light mode: lavender line so the violet lane highlight on top of it stands out.
            PropertyFactory.lineColor(if (isDark) "#9A82FF" else "#C9BEFF"),
            PropertyFactory.lineWidth(Expression.interpolate(Expression.exponential(1.5f), Expression.zoom(),
                Expression.stop(10, 4f), Expression.stop(18, 16f))),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        ))

        // Lane-level geometry: polygons in real metres + thin lines.
        fun fill(id: String, k: String, color: Int, opacity: Float, src: String = SRC_LANES) = add(FillLayer(id, src).withFilter(kind(k)).withProperties(
            PropertyFactory.fillColor(color), PropertyFactory.fillOpacity(opacity), PropertyFactory.fillAntialias(true),
        ))
        fill("lane-surface", "SURFACE", if (isDark) 0xFF1C1F27.toInt() else 0xFF2B2F38.toInt(), 0.97f)
        fill("lane-bike", "BIKE", bikeColor, 0.9f)
        // Highlight: lime on the dark map, violet on the light map (lime has too little contrast on light grey).
        val highlight = if (isDark) 0xFFB8F33A.toInt() else 0xFF6C4CF5.toInt()
        fill("lane-allowed", "ALLOWED", highlight, if (isDark) 0.28f else 0.3f, SRC_LANES_DYN)
        fill("lane-recommended", "RECOMMENDED", highlight, if (isDark) 0.85f else 0.95f, SRC_LANES_DYN)
        fill("lane-arrow", "ARROW", 0xFFFFFFFF.toInt(), 0.92f)
        add(LineLayer("lane-stop", SRC_LANES).withFilter(kind("STOP")).withProperties(
            PropertyFactory.lineColor("#FFFFFF"), PropertyFactory.lineWidth(metresToPixels()),
        ))
        add(LineLayer("lane-divider", SRC_LANES).withFilter(kind("DIVIDER")).withProperties(
            PropertyFactory.lineColor("#FFFFFF"),
            PropertyFactory.lineWidth(Expression.interpolate(Expression.linear(), Expression.zoom(),
                Expression.stop(15, 0.6f), Expression.stop(20, 2.5f))),
            PropertyFactory.lineDasharray(arrayOf(4f, 4f)),
        ))
        add(LineLayer("lane-divider-solid", SRC_LANES).withFilter(kind("DIVIDER_SOLID")).withProperties(
            PropertyFactory.lineColor("#FFFFFF"),
            PropertyFactory.lineWidth(Expression.interpolate(Expression.linear(), Expression.zoom(),
                Expression.stop(15, 0.6f), Expression.stop(20, 2.5f))),
        ))
        // Raised turn arrow with a violet glow (AMAP's turn guidance through the junction).
        add(LineLayer("lane-turn-arrow-glow", SRC_LANES_DYN).withFilter(kind("TURN_ARROW")).withProperties(
            PropertyFactory.lineColor(if (isDark) "#9A82FF" else "#6C4CF5"),
            PropertyFactory.lineWidth(Expression.interpolate(Expression.linear(), Expression.zoom(),
                Expression.stop(15, 1.5f), Expression.stop(20, 7f))),
            PropertyFactory.lineBlur(2f), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        ))
        add(FillExtrusionLayer("lane-turn-arrow", SRC_LANES_DYN).withFilter(kind("TURN_ARROW")).withProperties(
            PropertyFactory.fillExtrusionColor("#FFFFFF"), PropertyFactory.fillExtrusionBase(0.05f),
            PropertyFactory.fillExtrusionHeight(0.7f), PropertyFactory.fillExtrusionOpacity(0.95f),
        ))
        add(LineLayer("lane-edge", SRC_LANES).withFilter(kind("EDGE")).withProperties(
            PropertyFactory.lineColor(if (isDark) "#C9CDD8" else "#FFFFFF"),
            PropertyFactory.lineWidth(Expression.interpolate(Expression.linear(), Expression.zoom(),
                Expression.stop(15, 0.8f), Expression.stop(20, 3f))),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        ))
        s.addLayer(CircleLayer("incidents", SRC_INC).withProperties(
            PropertyFactory.circleRadius(7f),
            PropertyFactory.circleColor(Expression.match(Expression.get("kind"), col("#F2A93B"),
                Expression.stop("CLOSURE", col("#D7191C")), Expression.stop("ACCIDENT", col("#D7191C")),
                Expression.stop("JAM", col("#FF5A5F")), Expression.stop("ROADWORKS", col("#F2A93B")))),
            PropertyFactory.circleStrokeColor("#FFFFFF"), PropertyFactory.circleStrokeWidth(2f),
            PropertyFactory.circlePitchAlignment(Property.CIRCLE_PITCH_ALIGNMENT_MAP),
        ))
        s.addLayer(SymbolLayer("features", SRC_FEAT).withProperties(
            PropertyFactory.iconImage(Expression.concat(Expression.literal("lw-feat-"), Expression.get("kind"))),
            PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
            PropertyFactory.iconAllowOverlap(true), PropertyFactory.iconIgnorePlacement(true),
            PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_VIEWPORT),
            PropertyFactory.iconSize(Expression.interpolate(Expression.linear(), Expression.zoom(),
                Expression.stop(13, 0.35f), Expression.stop(17, 0.6f), Expression.stop(19, 0.85f))),
        ).apply { minZoom = 13f })
        // Traffic-light set-up of the next signalised junction (AMAP-style bubble above the junction).
        s.addLayer(SymbolLayer("signal-popup", SRC_SIGNAL).withProperties(
            PropertyFactory.iconImage(Expression.get("img")), PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
            PropertyFactory.iconAllowOverlap(true), PropertyFactory.iconIgnorePlacement(true),
            PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_VIEWPORT),
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
        ))
        // 3D car (setting): violet glow under it, then the extruded body, cabin, wheels and lights.
        val part = Expression.get("part")
        s.addLayer(FillLayer("car-shadow", SRC_CAR3D).withFilter(Expression.eq(part, Expression.literal("SHADOW"))).withProperties(
            PropertyFactory.fillColor(col("#6C4CF5")), PropertyFactory.fillOpacity(if (isDark) 0.55f else 0.4f),
            PropertyFactory.fillAntialias(true),
        ))
        s.addLayer(FillExtrusionLayer("car-3d", SRC_CAR3D).withFilter(Expression.neq(part, Expression.literal("SHADOW"))).withProperties(
            PropertyFactory.fillExtrusionColor(Expression.match(part, col("#F4F4F8"),
                Expression.stop("WHEEL", col("#16161A")), Expression.stop("GLASS", col("#232A38")),
                Expression.stop("ROOF", col("#FFFFFF")), Expression.stop("HEADLIGHT", col("#FFF4C2")),
                Expression.stop("TAILLIGHT", col("#E5484D")))),
            PropertyFactory.fillExtrusionBase(Expression.get("base")),
            PropertyFactory.fillExtrusionHeight(Expression.get("top")),
            PropertyFactory.fillExtrusionOpacity(1f),
            PropertyFactory.fillExtrusionVerticalGradient(true),
        ))
        applyCarAppearance(s)
    }

    // --- car appearance (setting): arrow puck or 3D car, size ------------------------------------------------------
    private var car3d = true
    private var carScale = 1.0
    private var lastCarPos: LatLon? = null
    private var lastCarBearing = 0.0

    /** Arrow puck or [threeD] car, drawn [scale] × the default size. */
    fun setCarAppearance(threeD: Boolean, scale: Double) {
        if (threeD == car3d && scale == carScale) return
        car3d = threeD; carScale = scale
        style?.let { applyCarAppearance(it) }
    }

    private fun applyCarAppearance(s: Style) {
        val sc = carScale.toFloat()
        (s.getLayer("car") as? SymbolLayer)?.setProperties(
            PropertyFactory.visibility(if (car3d) Property.NONE else Property.VISIBLE),
            PropertyFactory.iconSize(Expression.interpolate(Expression.linear(), Expression.zoom(),
                Expression.stop(12, 0.5f * sc), Expression.stop(18, 1.0f * sc))),
        )
        val v = if (car3d) Property.VISIBLE else Property.NONE
        s.getLayer("car-shadow")?.setProperties(PropertyFactory.visibility(v))
        s.getLayer("car-3d")?.setProperties(PropertyFactory.visibility(v))
        lastCarPos?.let { updateCar(it, lastCarBearing) }
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
        if (main == null) stopTween()
    }

    /** Traffic lights and road signs along the route. */
    fun showFeatures(list: List<RoadFeature>, visible: Boolean) {
        lastFeatures = list; featuresVisible = visible
        val s = style ?: return
        (s.getSource(SRC_FEAT) as? GeoJsonSource)?.setGeoJson(FeatureCollection.fromFeatures(
            if (!visible) emptyList() else list.map {
                Feature.fromGeometry(Point.fromLngLat(it.position.lon, it.position.lat)).apply { addStringProperty("kind", it.kind.name) }
            }))
    }

    /** Aerial imagery on/off; 3D buildings are hidden while it is on (they would cover the photo). */
    fun setSatellite(on: Boolean) {
        satelliteOn = on
        val s = style ?: return
        s.getLayer("satellite")?.setProperties(PropertyFactory.visibility(if (on) Property.VISIBLE else Property.NONE))
        s.getLayer("building-3d")?.setProperties(PropertyFactory.visibility(if (on) Property.NONE else Property.VISIBLE))
        // Lane surface becomes translucent so the real road shows through.
        (s.getLayer("lane-surface") as? FillLayer)?.setProperties(PropertyFactory.fillOpacity(if (on) 0.35f else 0.97f))
    }

    fun setBikeColor(argb: Int) {
        bikeColor = argb
        (style?.getLayer("lane-bike") as? FillLayer)?.setProperties(PropertyFactory.fillColor(argb))
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

    /** Lane-level shapes (route lanes while navigating, all nearby streets otherwise). */
    fun setLaneShapes(shapes: List<LaneRibbonBuilder.Shape>) = setShapes(SRC_LANES, shapes)

    private fun setShapes(src: String, shapes: List<LaneRibbonBuilder.Shape>) = setFeatures(src, features(shapes))

    private fun setFeatures(src: String, fc: FeatureCollection) {
        val s = style ?: return
        (s.getSource(src) as? GeoJsonSource)?.setGeoJson(fc)
    }

    /** Plain object building, safe off the UI thread. */
    private fun features(shapes: List<LaneRibbonBuilder.Shape>): FeatureCollection = FeatureCollection.fromFeatures(shapes.map { sh ->
        val geom = if (sh.polygon) Polygon.fromLngLats(listOf(sh.points.map { Point.fromLngLat(it.lon, it.lat) }))
        else line(sh.points)
        Feature.fromGeometry(geom).apply {
            addStringProperty("k", sh.kind.name)
            addNumberProperty("w", sh.widthM)
        }
    })

    /** Pop-up with the traffic-light set-up of [sig] at its junction, or none. */
    fun showSignalPopup(sig: de.lanewise.core.model.SignalCluster?) {
        val s = style ?: return
        val src = s.getSource(SRC_SIGNAL) as? GeoJsonSource ?: return
        val pos = sig?.position
        if (sig == null || pos == null) { src.setGeoJson(FeatureCollection.fromFeatures(emptyList())); return }
        val density = context.resources.displayMetrics.density
        val bmp = de.lanewise.ui.hud.RoadSignArt.signalPopup(sig, density)
        val id = "sig-${bmp.hashCode()}"
        if (s.getImage(id) == null) s.addImage(id, bmp)
        src.setGeoJson(Feature.fromGeometry(Point.fromLngLat(pos.lon, pos.lat)).apply { addStringProperty("img", id) })
    }

    fun clearLanes() {
        geomGeneration++
        clearLaneSources()
    }

    private fun clearLaneSources() {
        (style?.getSource(SRC_LANES) as? GeoJsonSource)?.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
        (style?.getSource(SRC_LANES_DYN) as? GeoJsonSource)?.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
        roadRoute = null; shownChunks = emptySet()
    }

    // The route's road, pre-built per [ROAD_CHUNK] and kept: rebuilt only when the route (or its OSM data) changes.
    @Volatile private var roadRoute: Route? = null
    private var roadBikes = true
    private val roadChunks = HashMap<Int, List<LaneRibbonBuilder.Shape>>()
    private var shownChunks: Set<Int> = emptySet()

    /**
     * Road chunks for [along] (runs on a worker thread; [roadChunks] is only touched by the single geometry job).
     * Returns the chunk set and its features if it differs from what is shown, else null.
     */
    private fun buildRoad(route: Route, along: Double, showBikeLanes: Boolean, shown: Set<Int>): Pair<Set<Int>, FeatureCollection>? {
        if (route !== roadRoute || showBikeLanes != roadBikes) {
            roadRoute = route; roadBikes = showBikeLanes; roadChunks.clear()
            return buildRoad(route, along, showBikeLanes, emptySet())
        }
        val last = (route.distance / ROAD_CHUNK).toInt()
        val first = ((along - 300) / ROAD_CHUNK).toInt().coerceIn(0, last)
        val want = (first..((along + 2500) / ROAD_CHUNK).toInt().coerceAtMost(last)).toSet()
        if (want == shown) return null
        for (c in want) roadChunks.getOrPut(c) {
            LaneRibbonBuilder.buildRoad(route, c * ROAD_CHUNK, minOf(route.distance, (c + 1) * ROAD_CHUNK), showBikeLanes)
        }
        roadChunks.keys.retainAll { it in (first - 1)..(want.max() + 1) }
        return want to features(want.sorted().flatMap { roadChunks[it].orEmpty() })
    }

    /** What the shown highlight was built for; a new build is needed only when this changes or the car moved on. */
    private var geomKey: List<Any?>? = null
    private var geomAlong = Double.NaN

    /**
     * Builds highlight (+ road) on a worker, then hands the result to the map on the UI thread. The highlight covers
     * 60 m behind to 1.2 km ahead of the car on a fixed grid, so it is rebuilt only when the lane plan changes or the
     * car has moved [GEOM_STEP] — not at every fix (the hand-over of ~1 km of polygons to MapLibre runs on the UI thread).
     */
    private fun requestGeometry(snap: NavSnapshot, along: Double, showRibbons: Boolean, showBikeLanes: Boolean, streetsMode: Boolean) {
        val key = listOf(snap.route, snap.lanes, showRibbons, showBikeLanes, streetsMode, geomGeneration)
        if (key == geomKey && abs(along - geomAlong) < GEOM_STEP) return
        geomKey = key; geomAlong = along
        val work = {
            val gen = geomGeneration
            val shown = if (streetsMode) emptySet() else shownChunks
            geomJob = scope.launch {
                val result = runCatching {
                    withContext(Dispatchers.Default) {
                        val r = LaneRibbonBuilder.build(snap.route, snap.lanes, along)
                        val road = if (showRibbons && !streetsMode) buildRoad(snap.route, along, showBikeLanes, shown) else null
                        Triple(r, if (showRibbons) features(r.shapes) else null, road)
                    }
                }.getOrNull()
                if (result != null && tweenRoute != null) {
                    val (r, live, road) = result
                    ribbon = r
                    if (live != null && gen == geomGeneration) {
                        if (streetsMode) shownChunks = emptySet()
                        road?.let { (set, fc) -> setFeatures(SRC_LANES, fc); shownChunks = set }
                        setFeatures(SRC_LANES_DYN, live)
                    }
                }
                val next = geomPending
                geomPending = null
                next?.invoke()
            }
        }
        if (geomJob?.isActive == true) geomPending = work else work()
    }

    fun updateCar(position: LatLon, bearing: Double) {
        val s = style ?: return
        lastCarPos = position; lastCarBearing = bearing
        if (car3d) {
            val blocks = CarModel.blocks(position, bearing, CarModel.DEFAULT_EXAGGERATION * carScale)
            (s.getSource(SRC_CAR3D) as? GeoJsonSource)?.setGeoJsonSync(FeatureCollection.fromFeatures(blocks.map { b ->
                Feature.fromGeometry(Polygon.fromLngLats(listOf(b.ring.map { Point.fromLngLat(it.lon, it.lat) }))).apply {
                    addStringProperty("part", b.part.name)
                    addNumberProperty("base", b.base)
                    addNumberProperty("top", b.top)
                }
            }))
            return
        }
        // Synchronous: the async update landed a frame or two after the camera moved, so the puck jittered sideways.
        (s.getSource(SRC_CAR) as? GeoJsonSource)?.setGeoJsonSync(
            Feature.fromGeometry(Point.fromLngLat(position.lon, position.lat)).apply { addNumberProperty("bearing", bearing) })
    }

    /**
     * New navigation state (GNSS rate). Rebuilds the lane geometry and re-targets the tween; the car and
     * camera then glide from where they are shown now to the new position over the time between fixes.
     */
    fun updateNavigation(
        snap: NavSnapshot, showRibbons: Boolean, pitch: Float, topPaddingPx: Double,
        closeUpDistance: Double = 50.0, showBikeLanes: Boolean = true, immersive: Boolean = false,
        streetsMode: Boolean = false,
        /** Fixed camera zoom (setting "Auto zoom" off); null = zoom with the situation. */
        fixedZoom: Double? = null,
    ) {
        val s = style ?: return
        val now = SystemClock.uptimeMillis()
        val route = snap.route
        val along = snap.distanceAlong

        if (snap.offRoute) {
            offRoutePos = snap.fix.position
            offRouteBearing = snap.fix.bearing?.toDouble() ?: offRouteBearing
            tweenRoute = null
        } else {
            offRoutePos = null
            val jump = shownAlong.isNaN() || tweenRoute !== route || abs(along - shownAlong) > 300
            val dt = (now - fixAt) / 1000.0
            if (jump) {
                shownAlong = along; tweenRoute = route; speedMps = snap.fix.speedKmh / 3.6
            } else if (dt > 0.05) {
                // Speed along the route from consecutive updates (the demo runs faster than the shown speed).
                val measured = ((along - fixAlong) / dt).coerceIn(0.0, 150.0)
                speedMps += (measured - speedMps) * 0.5
            }
            fixAlong = along
            fixAt = now
        }
        tweenPitch = pitch.toDouble()
        tweenTopPad = topPaddingPx
        if (fixedZoom != null) {
            targetZoom = fixedZoom
        } else if (immersive) {
            // AMAP-style: the map is the junction view. Far from any decision the camera rises to show ~1 km ahead;
            // approaching a turn or a lane change it closes in and looks down more steeply.
            val fast = snap.roadKind == RoadKind.MOTORWAY || snap.roadKind == RoadKind.TRUNK
            val lane = snap.lanes.pendingChange?.let { it.startAt - along }?.coerceAtLeast(0.0)
            val decision = minOf(snap.distanceToNextStep, lane ?: Double.MAX_VALUE)
            val far = if (fast) 1500.0 else 450.0
            val near = if (fast) 300.0 else 90.0
            val t = ((far - decision) / (far - near)).coerceIn(0.0, 1.0).let { it * it * (3 - 2 * it) }
            // Moderate zoom-out only: lanes must stay readable on open road (16.3 showed ~1.5 km and lost the lanes).
            val zFar = if (fast) 16.4 else 17.1
            val zNear = if (fast) 17.4 else 18.6
            targetZoom = zFar + (zNear - zFar) * t
            tweenPitch = pitch.toDouble() - 15.0 * t
        } else targetZoom = when {
            snap.distanceToNextStep < closeUpDistance -> 18.4
            snap.roadKind == RoadKind.MOTORWAY -> 16.8
            snap.roadKind == RoadKind.TRUNK || snap.fix.speedKmh > 70 -> 17.2
            else -> 17.6
        }

        val pos = route.geometry.pointAt(along)
        if (abs(pos.lat - lastLat) > 0.5) {
            lastLat = pos.lat
            (s.getLayer("lane-stop") as? LineLayer)?.setProperties(PropertyFactory.lineWidth(metresToPixels()))
        }

        if (snap.offRoute) {
            ribbon = null
            clearLanes()
        } else {
            if (!showRibbons) clearLaneSources()
            // Streets mode: the street layer (all streets around the car) stays, only the highlight is the route's.
            requestGeometry(snap, along, showRibbons, showBikeLanes, streetsMode)
        }

        if (!frameRunning) { frameRunning = true; choreographer.postFrameCallback(frame) }
    }

    fun stopTween() {
        geomGeneration++; geomPending = null
        tweenRoute = null; offRoutePos = null; ribbon = null
        (style?.getSource(SRC_LANES_DYN) as? GeoJsonSource)?.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
        roadRoute = null; shownChunks = emptySet(); geomKey = null
        shownAlong = Double.NaN; shownBearing = Double.NaN; shownZoom = Double.NaN; shownPitch = Double.NaN
    }

    /**
     * One display frame ([dt] s): the car moves on at the measured speed and is pulled gently towards where the last
     * update predicts it to be — no restart (and no speed jump or stop) at every update; bearing, zoom and tilt are
     * eased with time constants, so the motion is the same at 60 and 120 Hz.
     */
    private fun stepTween(dt: Double) {
        val m = map ?: return
        val off = offRoutePos
        val pos: LatLon
        val targetBearing: Double
        if (off != null) {
            pos = off; targetBearing = offRouteBearing
        } else {
            val route = tweenRoute ?: return
            val since = ((SystemClock.uptimeMillis() - fixAt) / 1000.0).coerceIn(0.0, 1.5)
            val predicted = fixAlong + speedMps * since
            val step = speedMps * dt + (predicted - shownAlong) * ease(dt, 0.6)
            shownAlong += step.coerceAtLeast(0.0)  // never backwards: an overshoot is absorbed by slowing down
            val line = route.geometry
            val lane = ribbon
            pos = lane?.pointAt(shownAlong) ?: line.pointAt(shownAlong)
            // Look-ahead bearing (≈25 m) so the camera turns before the car does and never jitters on short segments.
            val ahead = lane?.pointAt(shownAlong + 25) ?: line.pointAt(shownAlong + 25)
            targetBearing = if (ahead == pos) shownBearing.takeUnless { it.isNaN() } ?: 0.0 else Geo.bearing(pos, ahead)
        }
        shownBearing = if (shownBearing.isNaN()) targetBearing
        else (shownBearing + Geo.bearingDelta(shownBearing, targetBearing) * ease(dt, 0.15) + 360) % 360
        shownZoom = if (shownZoom.isNaN()) targetZoom else shownZoom + (targetZoom - shownZoom) * ease(dt, 0.8)
        shownPitch = if (shownPitch.isNaN()) tweenPitch else shownPitch + (tweenPitch - shownPitch) * ease(dt, 0.8)

        updateCar(pos, shownBearing)
        if (following) {
            val cam = CameraPosition.Builder()
                .target(LatLng(pos.lat, pos.lon))
                .bearing(shownBearing)
                .tilt(shownPitch)
                .zoom(shownZoom)
                .padding(0.0, tweenTopPad, 0.0, 0.0)
                .build()
            m.moveCamera(CameraUpdateFactory.newCameraPosition(cam))
        }
    }

    /** Share of the remaining distance covered in [dt] s by exponential easing with time constant [tau] s. */
    private fun ease(dt: Double, tau: Double) = 1.0 - kotlin.math.exp(-dt / tau)

    fun followCar(position: LatLon, bearing: Double?, pitch: Float) {
        val m = map ?: return
        stopTween()
        updateCar(position, bearing ?: 0.0)
        if (!following) return
        val builder = CameraPosition.Builder().target(LatLng(position.lat, position.lon))
            .zoom(16.0).tilt(if (bearing != null) pitch.toDouble() * 0.6 else 0.0)
        if (bearing != null) builder.bearing(bearing)
        m.easeCamera(CameraUpdateFactory.newCameraPosition(builder.build()), 900)
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
        /** Car movement (m) after which the live highlight is rebuilt even if the lane plan is unchanged. */
        private const val GEOM_STEP = 25.0

        private const val SRC_ROUTE = "lw-route"
        private const val SRC_ALT = "lw-alt"
        private const val SRC_LANES = "lw-lanes"
        /** Live highlight (recommended strip, usable-lane band, turn arrow); the road itself is in [SRC_LANES]. */
        private const val SRC_LANES_DYN = "lw-lanes-live"
        private const val SRC_SIGNAL = "lw-signal-popup"
        /** The route's road is built and kept in pieces of this length (m). */
        private const val ROAD_CHUNK = 1000.0
        private const val SRC_CAR = "lw-car"
        private const val SRC_CAR3D = "lw-car3d"
        private const val SRC_DEST = "lw-dest"
        private const val SRC_INC = "lw-incidents"
        private const val SRC_FEAT = "lw-features"
        private const val SRC_SAT = "lw-satellite"
        private const val DOP20_WMS = "https://opendata.lgln.niedersachsen.de/doorman/noauth/dop_wms?SERVICE=WMS&VERSION=1.3.0" +
            "&REQUEST=GetMap&LAYERS=ni_dop20&STYLES=&CRS=EPSG:3857&BBOX={bbox-epsg-3857}&WIDTH=256&HEIGHT=256&FORMAT=image/jpeg"
        private const val IMG_CAR = "lw-car-img"
        private const val IMG_DEST = "lw-dest-img"
    }
}
