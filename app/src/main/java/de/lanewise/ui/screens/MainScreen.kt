package de.lanewise.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import de.lanewise.R
import de.lanewise.core.model.IncidentKind
import de.lanewise.core.model.Route
import de.lanewise.nav.NavSnapshot
import de.lanewise.nav.Phrases
import de.lanewise.nav.SpeedStatus
import de.lanewise.osm.OsmTags
import de.lanewise.search.Place
import de.lanewise.settings.AppSettings
import de.lanewise.settings.PlaceKind
import de.lanewise.settings.SavedPlace
import de.lanewise.ui.MainViewModel
import de.lanewise.ui.Mode
import de.lanewise.ui.UiState
import de.lanewise.ui.hud.BikeLaneChip
import de.lanewise.ui.hud.EtaBar
import de.lanewise.ui.hud.FeatureChip
import de.lanewise.ui.hud.GnssBadge
import de.lanewise.ui.hud.ManeuverBanner
import de.lanewise.ui.hud.SpeedBorderOverlay
import de.lanewise.ui.hud.SpeedLimitSign
import de.lanewise.ui.hud.SpeedPanel
import de.lanewise.ui.i18n.LocalStrings
import de.lanewise.ui.lane.LaneBar
import de.lanewise.ui.lane.LaneHint
import de.lanewise.ui.lane.LaneScene3D
import de.lanewise.ui.map.MapController
import de.lanewise.ui.theme.LocalLaneColors
import org.maplibre.android.maps.MapView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MainScreen(vm: MainViewModel, ui: UiState, settings: AppSettings, dark: Boolean, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val controller = remember { MapController(context) }
    var following by remember { mutableStateOf(true) }
    val phrases = remember(settings.language) { Phrases(settings.language) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val topPadPx = with(LocalDensity.current) { (maxHeight * 0.42f).toPx().toDouble() }

        MapLayer(controller, dark) { following = false }

        // Keep map data in sync with state.
        LaunchedEffect(dark) { controller.setDark(dark) }
        LaunchedEffect(settings.bikeLaneColor) { controller.setBikeColor(settings.bikeLaneColor) }
        LaunchedEffect(settings.satellite, dark) { controller.setSatellite(settings.satellite) }
        LaunchedEffect(ui.routes, ui.selected, ui.mode, ui.destination, settings.showSigns) {
            val main = ui.selectedRoute
            controller.showRoutes(main, ui.routes.filterIndexed { i, _ -> i != ui.selected }, ui.destination?.position)
            controller.showFeatures(main?.features.orEmpty(), settings.showSigns)
            if (ui.mode == Mode.PREVIEW && main != null) controller.overview(main, 420, 900)
        }
        LaunchedEffect(ui.incidents) { controller.showIncidents(ui.incidents) }
        // Without an active route: lanes and markings of all nearby streets (like Petal / AMAP).
        // While navigating only the route's lanes are drawn (updateNavigation).
        LaunchedEffect(ui.nearbyStreets, ui.mode, settings.laneRibbonsOnMap, settings.showBikeLanes, settings.aerialLanes) {
            if (ui.mode == Mode.NAVIGATING) return@LaunchedEffect
            if (!settings.laneRibbonsOnMap) { controller.clearLanes(); return@LaunchedEffect }
            val streets = if (settings.aerialLanes) vm.aerialLanes().let { a -> ui.nearbyStreets.map(a::apply) } else ui.nearbyStreets
            val shapes = de.lanewise.osm.StreetLanes.build(streets)
                .filter { settings.showBikeLanes || it.kind != de.lanewise.lane.LaneRibbonBuilder.Kind.BIKE }
            controller.setLaneShapes(shapes)
        }
        // AMAP-style traffic-light pop-up at the next signalised junction (within 250 m, while navigating).
        val sig = ui.nav?.signals?.takeIf { settings.showSigns && it.distance < 250 && ui.mode == Mode.NAVIGATING }
        LaunchedEffect(sig?.position, sig?.vehicleLights, sig?.inferredArrowSignals, sig?.greenArrow) { controller.showSignalPopup(sig) }
        LaunchedEffect(ui.nav) {
            ui.nav?.let {
                controller.updateNavigation(
                    it, settings.laneRibbonsOnMap, settings.mapPitch, topPadPx,
                    closeUpDistance = if (settings.junctionZoom) settings.laneSceneDistance.toDouble() else 0.0,
                    showBikeLanes = settings.showBikeLanes, immersive = settings.immersiveView,
                )
            }
        }
        LaunchedEffect(ui.fix, ui.mode) {
            val f = ui.fix ?: return@LaunchedEffect
            if (ui.mode == Mode.BROWSE) controller.followCar(f.position, f.bearing?.toDouble(), settings.mapPitch)
        }
        val currentMode by rememberUpdatedState(ui.mode)
        DisposableEffect(controller) {
            controller.onMapLongPress = { p -> if (currentMode == Mode.BROWSE) vm.chooseDestination(p) }
            onDispose { controller.onMapLongPress = null }
        }

        when (ui.mode) {
            Mode.BROWSE -> BrowseOverlay(vm, ui, settings, onOpenSettings, onRecenter = { controller.recenter(); following = true })
            Mode.PREVIEW -> PreviewOverlay(vm, ui, phrases)
            Mode.NAVIGATING -> ui.nav?.let { nav ->
                NavigationOverlay(vm, ui, nav, settings, phrases, following,
                    onRecenter = { controller.recenter(); following = true },
                    onOverview = { controller.overview(nav.route, 420, 500); following = false })
            }
        }

        SpeedBorderOverlay(ui.nav?.speedStatus ?: SpeedStatus.UNKNOWN)

        ui.message?.let { msg ->
            Box(
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp, 0.dp, 16.dp, 110.dp)
                    .clip(RoundedCornerShape(14.dp)).background(Color(0xEE14161C)).clickable { vm.clearMessage() }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) { Text(msg, color = Color.White, fontSize = 15.sp) }
            LaunchedEffect(msg) { kotlinx.coroutines.delay(5000); vm.clearMessage() }
        }
    }
}

@Composable
private fun MapLayer(controller: MapController, dark: Boolean, onPanned: () -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val context = LocalContext.current
    val mapView = remember {
        MapView(context).also { mv ->
            mv.onCreate(null)
            controller.attach(mv, dark)
            controller.onUserPannedMap = onPanned
        }
    }
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> {}
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
}

// --- Browse -------------------------------------------------------------------------------------

@Composable
private fun BrowseOverlay(vm: MainViewModel, ui: UiState, settings: AppSettings, onOpenSettings: () -> Unit, onRecenter: () -> Unit) {
    val c = LocalLaneColors.current
    val str = LocalStrings.current
    var showFavorites by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<String?>(null) }
    val home = ui.places.firstOrNull { it.kind == PlaceKind.HOME }
    val work = ui.places.firstOrNull { it.kind == PlaceKind.WORK }

    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(c.chip).padding(start = 16.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painterResource(R.drawable.ic_search), null, tint = c.onChip.copy(alpha = 0.7f))
                TextField(
                    value = ui.query, onValueChange = vm::onQuery, modifier = Modifier.weight(1f),
                    placeholder = { Text(str.searchHint) }, singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                        focusedTextColor = c.onChip, unfocusedTextColor = c.onChip,
                    ),
                )
                if (ui.searching) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                Box(
                    Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onOpenSettings),
                    contentAlignment = Alignment.Center,
                ) { Icon(painterResource(R.drawable.ic_tune), str.settings, tint = c.onChip) }
            }

            // Shortcuts: Home, Work, Favourites.
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ShortcutChip("⌂  " + str.home, set = home != null) {
                    if (home != null) vm.goTo(home) else hint = str.setHomeHint
                }
                ShortcutChip("▣  " + str.work, set = work != null) {
                    if (work != null) vm.goTo(work) else hint = str.setWorkHint
                }
                ShortcutChip("★  " + str.favorites, set = ui.places.any { it.kind == PlaceKind.FAVORITE }) { showFavorites = true }
            }

            if (ui.results.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                LazyColumn(
                    Modifier.fillMaxWidth().heightIn(max = 380.dp).clip(RoundedCornerShape(20.dp)).background(c.chip),
                ) {
                    itemsIndexed(ui.results) { i, p ->
                        Column(Modifier.fillMaxWidth().clickable { vm.chooseDestination(p) }.padding(horizontal = 18.dp, vertical = 12.dp)) {
                            Text(p.title, color = c.onChip, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            if (p.subtitle.isNotBlank()) Text(p.subtitle, color = c.onChip.copy(alpha = 0.65f), fontSize = 13.sp)
                        }
                        if (i < ui.results.size - 1) HorizontalDivider(color = c.onChip.copy(alpha = 0.08f))
                    }
                }
            } else {
                // Speed limit of the road you are on (top left), also without a route.
                Spacer(Modifier.height(10.dp))
                ui.browseLimit?.let { SpeedLimitSign(limit = it.takeIf { v -> v > 0 }, noLimit = it == OsmTags.NO_LIMIT) }
            }
        }
        GnssBadge(ui.gnss, ui.online, Modifier.align(Alignment.BottomStart).padding(16.dp))
        // Layer toggle: map ↔ official aerial imagery (LGLN DOP20).
        Box(
            Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 84.dp).size(56.dp).clip(CircleShape)
                .background(if (settings.satellite) MaterialTheme.colorScheme.primary else c.chip)
                .clickable { vm.updateSettings { it.copy(satellite = !it.satellite) } },
            contentAlignment = Alignment.Center,
        ) { Icon(painterResource(R.drawable.ic_layers), str.satellite, tint = if (settings.satellite) Color.White else c.onChip) }
        Box(
            Modifier.align(Alignment.BottomEnd).padding(16.dp).size(56.dp).clip(CircleShape).background(c.chip).clickable(onClick = onRecenter),
            contentAlignment = Alignment.Center,
        ) { Icon(painterResource(R.drawable.ic_locate), str.myPosition, tint = MaterialTheme.colorScheme.primary) }
        ui.offlineProgress?.let {
            Text(it, Modifier.align(Alignment.BottomCenter).padding(bottom = 84.dp).clip(RoundedCornerShape(12.dp))
                .background(c.chip).padding(10.dp), color = c.onChip, fontSize = 13.sp)
        }
    }

    hint?.let { h ->
        AlertDialog(onDismissRequest = { hint = null }, confirmButton = { TextButton({ hint = null }) { Text("OK") } }, text = { Text(h) })
    }
    if (showFavorites) FavoritesDialog(ui.places, onGo = { showFavorites = false; vm.goTo(it) }, onDelete = vm::deletePlace) { showFavorites = false }
}

@Composable
private fun ShortcutChip(label: String, set: Boolean, onClick: () -> Unit) {
    val c = LocalLaneColors.current
    Text(
        label,
        Modifier.clip(RoundedCornerShape(18.dp)).background(c.chip).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        color = if (set) c.onChip else c.onChip.copy(alpha = 0.55f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun FavoritesDialog(places: List<SavedPlace>, onGo: (SavedPlace) -> Unit, onDelete: (String) -> Unit, onDismiss: () -> Unit) {
    val str = LocalStrings.current
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onDismiss) { Text("OK") } },
        title = { Text(str.favorites) },
        text = {
            if (places.isEmpty()) Text(str.noFavorites)
            else LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(places.sortedBy { it.kind.ordinal }) { p ->
                    Row(Modifier.fillMaxWidth().clickable { onGo(p) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(when (p.kind) { PlaceKind.HOME -> "⌂"; PlaceKind.WORK -> "▣"; PlaceKind.FAVORITE -> "★" },
                            fontSize = 18.sp, modifier = Modifier.width(28.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(p.address, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton({ onDelete(p.id) }) { Text(str.delete) }
                    }
                }
            }
        },
    )
}

/** Save the current destination as Home, Work or a named favourite. */
@Composable
private fun SavePlaceDialog(place: Place, onSave: (PlaceKind, String) -> Unit, onDismiss: () -> Unit) {
    val str = LocalStrings.current
    var kind by remember { mutableStateOf(PlaceKind.FAVORITE) }
    var name by remember { mutableStateOf(place.title) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(str.savePlace) },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(kind == PlaceKind.HOME, { kind = PlaceKind.HOME }, { Text(str.home) })
                    FilterChip(kind == PlaceKind.WORK, { kind = PlaceKind.WORK }, { Text(str.work) })
                    FilterChip(kind == PlaceKind.FAVORITE, { kind = PlaceKind.FAVORITE }, { Text(str.favorite) })
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(name, { name = it }, label = { Text(str.name) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { Button({ onSave(kind, name) }) { Text(str.save) } },
        dismissButton = { TextButton(onDismiss) { Text(str.cancel) } },
    )
}

// --- Route preview ------------------------------------------------------------------------------

@Composable
private fun PreviewOverlay(vm: MainViewModel, ui: UiState, phrases: Phrases) {
    val c = LocalLaneColors.current
    val str = LocalStrings.current
    var saving by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(c.chip).padding(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(ui.destination?.title ?: "", color = c.onChip, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    ui.destination?.subtitle?.takeIf { it.isNotBlank() }?.let { Text(it, color = c.onChip.copy(alpha = 0.65f), fontSize = 13.sp) }
                }
                TextButton({ saving = true }) { Text("★ " + str.save) }
            }
            Spacer(Modifier.height(12.dp))
            if (ui.routing) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(str.computingRoute, color = c.onChip)
                }
            }
            ui.routes.forEachIndexed { i, r -> RouteRow(r, i == ui.selected, phrases) { vm.selectRoute(i) } }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = vm::cancelPreview) { Text(str.cancel) }
                OutlinedButton(onClick = { vm.startNavigation(simulate = true) }, enabled = ui.routes.isNotEmpty()) { Text(str.demo) }
                Button(
                    onClick = { vm.startNavigation(simulate = false) }, enabled = ui.routes.isNotEmpty(), modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                ) { Text(str.go, fontWeight = FontWeight.Bold) }
            }
        }
    }
    val dest = ui.destination
    if (saving && dest != null) {
        SavePlaceDialog(dest, onSave = { kind, name -> vm.savePlace(kind, name, dest); saving = false }) { saving = false }
    }
}

@Composable
private fun RouteRow(r: Route, selected: Boolean, phrases: Phrases, onClick: () -> Unit) {
    val c = LocalLaneColors.current
    val str = LocalStrings.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(16.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(formatDuration(r.effectiveDuration), color = c.onChip, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            val closures = r.incidents.count { it.blocked || it.kind == IncidentKind.CLOSURE }
            val works = r.incidents.count { it.kind == IncidentKind.ROADWORKS }
            val lights = r.features.count { it.kind == de.lanewise.core.model.FeatureKind.TRAFFIC_SIGNALS }
            val extra = buildList {
                add(phrases.distance(r.distance))
                add("${r.laneSites.size} ${str.laneNodes}")
                if (lights > 0) add("$lights × ${str.trafficLight}")
                if (works > 0) add("$works ${str.roadworks}")
                if (closures > 0) add("$closures ${str.closures}")
                add(r.provider.uppercase())
            }
            Text(extra.joinToString(" · "), color = c.onChip.copy(alpha = 0.7f), fontSize = 13.sp)
        }
        if (r.trafficPenalty > 60) Text("+${(r.trafficPenalty / 60).toInt()} min", color = c.overSpeed, fontWeight = FontWeight.Bold)
    }
}

// --- Navigation ---------------------------------------------------------------------------------

/**
 * Countdown for the manoeuvre banner: 1 when the countdown starts, 0 at the manoeuvre. It starts at the previous
 * step or at most 400 m before (1 km on trunk roads, 1.5 km on motorways), so the bar visibly moves in town too.
 */
private fun countdownFraction(nav: NavSnapshot): Float {
    val next = nav.nextStep ?: return 0f
    val steps = nav.route.steps
    val prev = steps.getOrNull(steps.indexOf(next) - 1)?.distanceAlong ?: 0.0
    val cap = when (nav.roadKind) {
        de.lanewise.lane.RoadKind.MOTORWAY -> 1500.0
        de.lanewise.lane.RoadKind.TRUNK -> 1000.0
        else -> 400.0
    }
    val start = (next.distanceAlong - prev).coerceIn(50.0, cap)
    return (nav.distanceToNextStep / start).toFloat().coerceIn(0f, 1f)
}

@Composable
private fun NavigationOverlay(
    vm: MainViewModel, ui: UiState, nav: NavSnapshot, settings: AppSettings, phrases: Phrases, following: Boolean,
    onRecenter: () -> Unit, onOverview: () -> Unit,
) {
    val str = LocalStrings.current
    val bikeColor = Color(settings.bikeLaneColor)
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 10.dp, vertical = 6.dp)) {
            val next = nav.nextStep
            val then = nav.stepAfter?.takeIf { next != null && it.distanceAlong - next.distanceAlong < 250 }
            Row(verticalAlignment = Alignment.Top) {
                // Top left: speed limit sign (if known) above the upcoming traffic lights / signs.
                if (nav.speedLimit != null || nav.noSpeedLimit || (settings.showSigns && (nav.upcoming.isNotEmpty() || nav.signals != null))) {
                    Column(Modifier.padding(end = 8.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        SpeedLimitSign(nav.speedLimit, nav.noSpeedLimit)
                        if (settings.showSigns) {
                            nav.signals?.let { de.lanewise.ui.hud.SignalChip(it, phrases.distance(it.distance)) }
                            nav.upcoming.filter { it.first.kind != de.lanewise.core.model.FeatureKind.TRAFFIC_SIGNALS }
                                .forEach { (f, d) -> FeatureChip(f, phrases.distance(d)) }
                            nav.solidLineIn?.let { de.lanewise.ui.hud.SolidLineChip(str.solidLineAhead, phrases.distance(it)) }
                        }
                    }
                }
                val advice = nav.lanes.nextAdvice
                val distToSite = advice?.let { it.site.distanceAlong - nav.distanceAlong } ?: Double.MAX_VALUE
                val showLanes = advice != null && distToSite < 900
                ManeuverBanner(
                    step = next,
                    distanceText = phrases.distance(nav.distanceToNextStep),
                    instruction = next?.let { phrases.instruction(it) } ?: "",
                    then = then,
                    thenText = then?.let { str.then },
                    modifier = Modifier.weight(1f),
                    countdown = next?.let { countdownFraction(nav) },
                    lanesBelow = showLanes,
                ) {
                    if (showLanes && advice != null) {
                        Spacer(Modifier.height(3.dp))
                        LaneBar(advice, bikeColor = nav.bikeLane.takeIf { settings.showBikeLanes }?.let { bikeColor },
                            onLaneTap = vm::overrideLane, docked = true)
                    }
                }
            }
            val advice = nav.lanes.nextAdvice
            val distToSite = advice?.let { it.site.distanceAlong - nav.distanceAlong } ?: Double.MAX_VALUE
            val bikeHere = nav.bikeLane.takeIf { settings.showBikeLanes }
            if (advice != null && distToSite < 900) {
                if (!settings.immersiveView && settings.showLaneScene && distToSite <= settings.laneSceneDistance && (advice.site.isManeuver || nav.lanes.pendingChange != null)) {
                    Spacer(Modifier.height(8.dp))
                    LaneScene3D(nav.lanes, phrases.distance(distToSite),
                        Modifier.fillMaxWidth(0.82f).height(140.dp).align(Alignment.CenterHorizontally),
                        bikeColor = bikeHere?.let { bikeColor })
                    Spacer(Modifier.height(8.dp))
                }
            }
            if (bikeHere != null) {
                Spacer(Modifier.height(6.dp))
                BikeLaneChip(bikeHere.exclusive, bikeColor, Modifier.align(Alignment.CenterHorizontally))
            }
            ui.laneHint?.let { Spacer(Modifier.height(8.dp)); LaneHint(it, countdown = ui.laneHintCountdown) }
        }

        SpeedPanel(nav.fix.speedKmh, nav.speedStatus, Modifier.align(Alignment.CenterStart).padding(start = 12.dp, top = 160.dp))
        if (!following) {
            OutlinedButton(
                onClick = onRecenter,
                modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 16.dp, bottom = 110.dp),
            ) { Text(str.recenter) }
        }
        if (ui.simulating) {
            Text(str.demoBadge, Modifier.align(Alignment.CenterEnd).padding(end = 12.dp).clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF6C4CF5)).padding(horizontal = 8.dp, vertical = 3.dp), color = Color.White, fontSize = 12.sp)
        }
        EtaBar(
            remainingText = formatDuration(nav.remainingSeconds),
            distanceText = phrases.distance(nav.remainingDistance),
            etaText = SimpleDateFormat("HH:mm", Locale.GERMANY).format(Date(System.currentTimeMillis() + (nav.remainingSeconds * 1000).toLong())),
            onClose = vm::stopNavigation, onOverview = onOverview,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
        )
    }
}

fun formatDuration(seconds: Double): String {
    val m = (seconds / 60).toInt()
    return if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min"
}
