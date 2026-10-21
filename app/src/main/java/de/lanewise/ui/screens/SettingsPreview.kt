package de.lanewise.ui.screens

import android.widget.FrameLayout
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import de.lanewise.core.geo.LatLon
import de.lanewise.core.geo.Polyline
import de.lanewise.core.model.BikeLaneSpan
import de.lanewise.core.model.FeatureKind
import de.lanewise.core.model.IncidentKind
import de.lanewise.core.model.LaneCountSpan
import de.lanewise.core.model.Maneuver
import de.lanewise.core.model.ManeuverType
import de.lanewise.core.model.RoadFeature
import de.lanewise.core.model.RoadSpan
import de.lanewise.core.model.Route
import de.lanewise.core.model.RouteStep
import de.lanewise.core.model.SpeedLimitSpan
import de.lanewise.core.model.TrafficIncident
import de.lanewise.lane.LaneDir
import de.lanewise.lane.LaneGuidance
import de.lanewise.lane.LaneSite
import de.lanewise.lane.RoadKind
import de.lanewise.location.Fix
import de.lanewise.nav.NavSnapshot
import de.lanewise.nav.SpeedStatus
import de.lanewise.settings.AppSettings
import de.lanewise.ui.map.CarOverlayView
import de.lanewise.ui.map.MapController
import kotlinx.coroutines.delay
import org.maplibre.android.maps.MapView
import de.lanewise.core.model.Modifier as TurnModifier

/**
 * Live preview at the top of Settings, drawn by the real map: its own small [MapController] drives a demo car in a loop
 * along a piece of Vahrenwalder Straße (three lanes, bike lane, a junction with turn lanes, traffic lights, a zebra
 * crossing, a closed lane) and gets every setting the navigation gets — tilt, dynamic / fixed zoom, lane ribbons,
 * opacity, fade, chevrons, 3D car / arrow and size, road name, poles and zebras, closed lanes, see-through buildings,
 * theme. What you see here is what you see while driving. Tap the title to fold it.
 */
@Composable
fun SettingsPreview(s: AppSettings, dark: Boolean, title: String) {
    var open by rememberSaveable { mutableStateOf(true) }
    Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        Text((if (open) "▾ " else "▸ ") + title, fontSize = 13.sp, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable { open = !open }.padding(vertical = 4.dp))
        if (open) PreviewMap(s, dark)
    }
}

@Composable
private fun PreviewMap(s: AppSettings, dark: Boolean) {
    val context = LocalContext.current
    val controller = remember { MapController(context).also { it.setAttributionTop((8 * context.resources.displayMetrics.density).toInt()) } }
    val mapView = remember { MapView(context).also { it.onCreate(null); controller.attach(it, dark) } }
    val container = remember {
        FrameLayout(context).also { f ->
            f.addView(mapView)
            val overlay = CarOverlayView(context)
            f.addView(overlay)
            controller.carOverlay = overlay
        }
    }
    DisposableEffect(Unit) {
        mapView.onStart(); mapView.onResume()
        onDispose { controller.stopTween(); mapView.onPause(); mapView.onStop(); mapView.onDestroy() }
    }
    LaunchedEffect(dark) { controller.setDark(dark) }
    LaunchedEffect(s.car3d, s.carScale) { controller.setCarAppearance(s.car3d, s.carScale.toDouble()) }
    LaunchedEffect(s.laneOpacity, dark) { controller.setStripOpacity(s.laneOpacity) }
    LaunchedEffect(s.bikeLaneColor) { controller.setBikeColor(s.bikeLaneColor) }
    LaunchedEffect(s.stripFade, s.laneChevrons, s.roadFurniture, s.closedLanes, s.softBuildings, s.roadNameChip) {
        controller.setLookOptions(s.stripFade, s.laneChevrons, s.roadFurniture, s.closedLanes, s.softBuildings, s.roadNameChip)
    }
    BoxWithConstraints(Modifier.fillMaxWidth().height(300.dp).clip(RoundedCornerShape(16.dp))) {
        // Same share of the view above the car as on the navigation screen (MainScreen: 42 % of the height).
        val topPad = with(LocalDensity.current) { (maxHeight * 0.42f).toPx().toDouble() }
        AndroidView(factory = { container }, modifier = Modifier.fillMaxSize())
        // The demo loop: a navigation update every 250 ms, like the demo, always with the latest settings (the car keeps
        // driving while you change them).
        val current by androidx.compose.runtime.rememberUpdatedState(s)
        LaunchedEffect(topPad) {
            val route = previewRoute
            val guidance = LaneGuidance()
            var along = 60.0
            while (true) {
                val s = current
                val st = guidance.update(route.laneSites, along, s.avoidLaneChanges, s.keepRight, route.laneChanges,
                    route::laneCountAt, { d -> route.roadSpanAt(d)?.kind })
                val p = route.geometry.pointAt(along)
                val next = route.steps.firstOrNull { it.distanceAlong > along }
                val snap = NavSnapshot(
                    route = route,
                    fix = Fix(p, SPEED.toFloat(), route.geometry.bearingAt(along).toFloat(), 3f, System.currentTimeMillis(), simulated = true),
                    distanceAlong = along, remainingDistance = route.distance - along, remainingSeconds = (route.distance - along) / SPEED,
                    nextStep = next, distanceToNextStep = (next?.distanceAlong ?: route.distance) - along, stepAfter = null,
                    speedLimit = 50, noSpeedLimit = false, speedStatus = SpeedStatus.OK, lanes = st, roadKind = RoadKind.URBAN,
                    offRoute = false, arrived = false,
                )
                controller.updateNavigation(snap, s.laneRibbonsOnMap, s.mapPitch, topPad,
                    closeUpDistance = if (s.junctionZoom) s.laneSceneDistance.toDouble() else 0.0,
                    showBikeLanes = s.showBikeLanes, immersive = s.immersiveView, streetsMode = false,
                    fixedZoom = s.fixedZoom.toDouble().takeUnless { s.autoZoom })
                delay(250)
                along += SPEED * 0.25
                if (along > route.distance - 80) { along = 60.0; guidance.reset() }
            }
        }
    }
}

/** Demo speed in the preview (m/s, 50 km/h). */
private const val SPEED = 13.9

/** Vahrenwalder Straße → Großer Kolonnenweg, Hannover (OSRM geometry, ~1.4 km), dressed with lanes for the preview. */
private val previewRoute: Route by lazy {
    val pts = doubleArrayOf(
        52.399709, 9.736136, 52.400412, 9.736047, 52.401060, 9.735944, 52.402035, 9.735783, 52.402399, 9.735723,
        52.403123, 9.735603, 52.403351, 9.735565, 52.403575, 9.735703, 52.403752, 9.736039, 52.403953, 9.736224,
        52.404246, 9.736473, 52.404468, 9.736665, 52.405485, 9.737517, 52.405841, 9.737813, 52.406447, 9.738331,
        52.406790, 9.738621, 52.407008, 9.738831, 52.407520, 9.739266, 52.407995, 9.739676, 52.408670, 9.740152,
        52.409040, 9.740336, 52.409435, 9.740505, 52.409927, 9.740674, 52.410485, 9.740821, 52.410767, 9.740885,
    )
    val line = Polyline((pts.indices step 2).map { LatLon(pts[it], pts[it + 1]) })
    val len = line.length
    val l = LaneDir.LEFT; val st = LaneDir.STRAIGHT; val r = LaneDir.RIGHT
    val sites = listOf(
        // Left lanes turn left only: the strip stays in the through lanes, with lights, a zebra and a closed lane.
        LaneSite(520.0, listOf(l, st, st), listOf(false, true, true), st, false, RoadKind.URBAN),
        LaneSite(1150.0, listOf(l, st, st or r), listOf(false, true, true), st, false, RoadKind.URBAN),
    )
    val closure = line.slice(250.0, 420.0)
    Route(
        provider = "preview", geometry = line, distance = len, duration = len / SPEED,
        steps = listOf(
            RouteStep(0.0, len, len / SPEED, Maneuver(ManeuverType.DEPART, TurnModifier.STRAIGHT, line.pointAt(0.0), 0.0, 0.0),
                "Vahrenwalder Straße", null, null, null),
            RouteStep(len, 0.0, 0.0, Maneuver(ManeuverType.ARRIVE, TurnModifier.STRAIGHT, line.pointAt(len), 0.0, 0.0),
                "Vahrenwalder Straße", null, null, null),
        ),
        laneSites = sites,
        speedLimits = listOf(SpeedLimitSpan(0.0, len, 50)),
        roadSpans = listOf(RoadSpan(0.0, len, RoadKind.URBAN, twoWay = false)),
        features = listOf(
            RoadFeature(FeatureKind.TRAFFIC_SIGNALS, line.pointAt(510.0), 510.0),
            RoadFeature(FeatureKind.CROSSING, line.pointAt(800.0), 800.0, zebra = true),
            RoadFeature(FeatureKind.TRAFFIC_SIGNALS, line.pointAt(1140.0), 1140.0),
        ),
        laneCounts = listOf(LaneCountSpan(0.0, len, 3)),
        // Bike lane on the right: a Radfahrstreifen (solid line) first, then a Schutzstreifen (dashed), so both looks and
        // the bike-lane colour show in the preview.
        bikeLanes = listOf(BikeLaneSpan(0.0, 650.0, exclusive = true), BikeLaneSpan(650.0, len, exclusive = false)),
        incidents = listOf(TrafficIncident("preview", "Autobahn GmbH", IncidentKind.ROADWORKS, "", "", closure.first(),
            geometry = closure, laneOpen = listOf(false, true, true))),
    )
}
