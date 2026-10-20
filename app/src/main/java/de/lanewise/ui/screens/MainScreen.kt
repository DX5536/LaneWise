package de.lanewise.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import androidx.compose.ui.layout.onGloballyPositioned
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
import de.lanewise.core.geo.LatLon
import androidx.compose.foundation.border
import de.lanewise.settings.AppSettings
import de.lanewise.settings.PlaceKind
import de.lanewise.ui.PlaceBadge
import de.lanewise.ui.PlaceIcons
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddLocationAlt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.Work
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
fun MainScreen(
    vm: MainViewModel, ui: UiState, settings: AppSettings, dark: Boolean, onOpenSettings: () -> Unit,
    /** Settings is open on top: the map's per-frame car / camera loop pauses. */
    covered: Boolean = false,
) {
    val context = LocalContext.current
    val controller = remember { MapController(context) }
    var following by remember { mutableStateOf(true) }
    // Height of the route preview panel (px), so the overview frames the route above it.
    var previewPanelPx by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val phrases = remember(settings.language) { Phrases(settings.language) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val topPadPx = with(LocalDensity.current) { (maxHeight * 0.42f).toPx().toDouble() }

        MapLayer(controller, dark) { following = false }

        // Demo / Let's go: leave the route overview and follow the car right away.
        // Leaving navigation (end, arrival, demo over) stops the per-frame car / camera loop right away — it used to run
        // on until the next real GPS fix, which indoors may never come, and kept the UI thread busy (Settings froze).
        LaunchedEffect(ui.mode) {
            if (ui.mode == Mode.NAVIGATING) { controller.recenter(); following = true } else controller.stopTween()
        }
        LaunchedEffect(covered) { controller.paused = covered }
        // Keep map data in sync with state.
        LaunchedEffect(dark) { controller.setDark(dark) }
        LaunchedEffect(settings.stripFade, settings.laneChevrons, settings.roadFurniture, settings.closedLanes, settings.softBuildings, settings.roadNameChip) {
            controller.setLookOptions(settings.stripFade, settings.laneChevrons, settings.roadFurniture, settings.closedLanes,
                settings.softBuildings, settings.roadNameChip)
        }
        LaunchedEffect(settings.abParking, settings.abCharging, settings.abWebcams, ui.selectedRoute) {
            val kinds = buildSet {
                if (settings.abParking) add(de.lanewise.core.model.AutobahnPoiKind.PARKING)
                if (settings.abCharging) add(de.lanewise.core.model.AutobahnPoiKind.CHARGING)
                if (settings.abWebcams) add(de.lanewise.core.model.AutobahnPoiKind.WEBCAM)
            }
            vm.loadAutobahnPois(kinds, ui.selectedRoute)
        }
        LaunchedEffect(ui.autobahnPois) { controller.showAutobahnPois(ui.autobahnPois) }
        LaunchedEffect(settings.laneOpacity, dark) { controller.setStripOpacity(settings.laneOpacity) }
        LaunchedEffect(settings.car3d, settings.carScale) { controller.setCarAppearance(settings.car3d, settings.carScale.toDouble()) }
        LaunchedEffect(settings.bikeLaneColor) { controller.setBikeColor(settings.bikeLaneColor) }
        LaunchedEffect(settings.satellite, dark) { controller.setSatellite(settings.satellite) }
        LaunchedEffect(ui.routes, ui.selected, ui.mode, ui.destination, settings.showSigns, ui.nearbyFeatures, previewPanelPx) {
            val main = ui.selectedRoute
            controller.showRoutes(main, ui.routes.filterIndexed { i, _ -> i != ui.selected }, ui.destination?.position,
                ui.stops.map { it.position })
            // Without a route: the lights / signs around the car (fetched with the nearby streets).
            controller.showFeatures(if (main != null) main.features else ui.nearbyFeatures, settings.showSigns)
            if (ui.mode == Mode.PREVIEW && main != null) controller.overview(main, 420, maxOf(900, previewPanelPx + 80))
        }
        LaunchedEffect(ui.incidents) { controller.showIncidents(ui.incidents) }
        // Without an active route: lanes and markings of all nearby streets (like Petal / AMAP).
        // While navigating only the route's lanes are drawn (updateNavigation).
        LaunchedEffect(ui.nearbyStreets, ui.mode, settings.laneRibbonsOnMap, settings.showBikeLanes, settings.aerialLanes,
            settings.streetsWhileDriving) {
            if (ui.mode == Mode.NAVIGATING && !settings.streetsWhileDriving) return@LaunchedEffect
            if (!settings.laneRibbonsOnMap) { controller.clearLanes(); return@LaunchedEffect }
            // Precedence: hand-checked patches > OSM > aerial counts (the patch is applied last, so it wins).
            val aerial = if (settings.aerialLanes) vm.aerialLanes().let { a -> ui.nearbyStreets.map(a::apply) } else ui.nearbyStreets
            val streets = vm.laneOverrides().let { o -> aerial.map(o::apply) }
            val shapes = de.lanewise.osm.StreetLanes.build(streets)
                .filter { settings.showBikeLanes || it.kind != de.lanewise.lane.LaneRibbonBuilder.Kind.BIKE }
            controller.setLaneShapes(shapes)
        }
        // AMAP-style traffic-light pop-up at the next signalised junction (within 250 m, while navigating).
        val sig = ui.nav?.signals?.takeIf { settings.showSigns && it.distance < 250 && ui.mode == Mode.NAVIGATING }
        // The signal pop-up is a HUD card now (left, above the speed); keep the old map pop-up empty.
        LaunchedEffect(Unit) { controller.showSignalPopup(null) }
        LaunchedEffect(ui.nav) {
            ui.nav?.let {
                controller.updateNavigation(
                    it, settings.laneRibbonsOnMap, settings.mapPitch, topPadPx,
                    closeUpDistance = if (settings.junctionZoom) settings.laneSceneDistance.toDouble() else 0.0,
                    showBikeLanes = settings.showBikeLanes, immersive = settings.immersiveView,
                    streetsMode = settings.streetsWhileDriving,
                    fixedZoom = settings.fixedZoom.toDouble().takeUnless { settings.autoZoom },
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
            // Tap on an Autobahn icon: its name and details as a message.
            controller.onPoiTap = { title, sub -> vm.showMessage(listOf(title, sub).filter { it.isNotBlank() }.joinToString(" · ")) }
            onDispose { controller.onMapLongPress = null; controller.onPoiTap = null }
        }

        when (ui.mode) {
            Mode.BROWSE -> BrowseOverlay(vm, ui, settings, onOpenSettings, onRecenter = { controller.recenter(); following = true })
            Mode.PREVIEW -> PreviewOverlay(vm, ui, phrases) { previewPanelPx = it }
            Mode.NAVIGATING -> ui.nav?.let { nav ->
                NavigationOverlay(vm, ui, nav, settings, phrases, following,
                    onRecenter = { controller.recenter(); following = true },
                    onOverview = { controller.overview(nav.route, 420, 500); following = false })
            }
        }

        SpeedBorderOverlay(ui.nav?.speedStatus ?: SpeedStatus.UNKNOWN, intensity = settings.speedGlow)

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
    val container = remember {
        android.widget.FrameLayout(context).also { f ->
            f.addView(mapView)
            val overlay = de.lanewise.ui.map.CarOverlayView(context)
            f.addView(overlay)
            controller.carOverlay = overlay
        }
    }
    AndroidView(factory = { container }, modifier = Modifier.fillMaxSize())
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
                    placeholder = { Text(str.searchHint, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }, singleLine = true,
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
                ShortcutChip(Icons.Rounded.Home, str.home, set = home != null) {
                    if (home != null) vm.goTo(home) else hint = str.setHomeHint
                }
                ShortcutChip(Icons.Rounded.Work, str.work, set = work != null) {
                    if (work != null) vm.goTo(work) else hint = str.setWorkHint
                }
                ShortcutChip(Icons.Rounded.Star, str.favorites, set = ui.places.any { it.kind == PlaceKind.FAVORITE },
                    tint = Color(PlaceIcons.STAR_YELLOW)) { showFavorites = true }
            }

            if (ui.results.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                LazyColumn(
                    Modifier.fillMaxWidth().heightIn(max = 380.dp).clip(RoundedCornerShape(20.dp)).background(c.chip),
                ) {
                    itemsIndexed(ui.results) { i, p ->
                        PlaceRow(p, ui.places, onClick = { vm.chooseDestination(p) })
                        if (i < ui.results.size - 1) HorizontalDivider(color = c.onChip.copy(alpha = 0.08f), modifier = Modifier.padding(start = 68.dp))
                    }
                }
            } else {
                // Speed limit of the road you are on (top left), also without a route.
                Spacer(Modifier.height(10.dp))
                ui.browseLimit?.let { SpeedLimitSign(limit = it.takeIf { v -> v > 0 }, noLimit = it == OsmTags.NO_LIMIT) }
            }
        }
        // The floating buttons give way to the result list (they were drawn on top of it).
        if (ui.results.isEmpty()) {
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
        }
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
private fun ShortcutChip(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, set: Boolean,
                         tint: Color? = null, onClick: () -> Unit) {
    val c = LocalLaneColors.current
    val fg = if (set) c.onChip else c.onChip.copy(alpha = 0.55f)
    Row(
        Modifier.clip(RoundedCornerShape(18.dp)).background(c.chip).clickable(onClick = onClick)
            .padding(start = 12.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint?.let { if (set) it else it.copy(alpha = 0.55f) } ?: fg, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * A search result: its kind as an icon (restaurant, gym, shop …), title and address. A place you saved shows its
 * badge next to it (yellow star, home, work or your own icon) and your name for it: "Am Muster 1 (Cousin)".
 */
@Composable
private fun PlaceRow(p: Place, saved: List<SavedPlace>, onClick: () -> Unit) {
    val c = LocalLaneColors.current
    val mine = saved.firstOrNull { de.lanewise.core.geo.Geo.distance(LatLon(it.lat, it.lon), p.position) < 40 }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val (icon, color) = if (mine != null && p.category == null) PlaceIcons.forSaved(mine) else PlaceIcons.forCategory(p.category)
        PlaceBadge(icon, color)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.title + (mine?.name?.takeIf { it != p.title }?.let { " ($it)" } ?: ""), color = c.onChip,
                    fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false))
                if (mine != null) {
                    val (si, sc) = PlaceIcons.forSaved(mine)
                    Spacer(Modifier.width(6.dp))
                    Icon(si, null, tint = Color(sc), modifier = Modifier.size(18.dp))
                }
            }
            if (p.subtitle.isNotBlank()) Text(p.subtitle, color = c.onChip.copy(alpha = 0.65f), fontSize = 13.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Saved places (Home, Work, then favourites) as ready-made picks while nothing is typed yet. */
@Composable
private fun SavedPresets(places: List<SavedPlace>, onPick: (SavedPlace) -> Unit) {
    places.sortedBy { it.kind.ordinal }.forEach { sp ->
        val (icon, color) = PlaceIcons.forSaved(sp)
        Row(Modifier.fillMaxWidth().clickable { onPick(sp) }.padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            PlaceBadge(icon, color, size = 32.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(sp.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(sp.address, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
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
                        val (icon, color) = PlaceIcons.forSaved(p)
                        PlaceBadge(icon, color, size = 34.dp)
                        Spacer(Modifier.width(12.dp))
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

/**
 * Save the current destination as Home, Work or a favourite — with your own name for it ("My cousin's home"), and an
 * icon and colour of your choice; the address stays with it.
 */
@Composable
private fun SavePlaceDialog(place: Place, onSave: (PlaceKind, String, String?, Long?) -> Unit, onDismiss: () -> Unit) {
    val str = LocalStrings.current
    var kind by remember { mutableStateOf(PlaceKind.FAVORITE) }
    var name by remember { mutableStateOf("") }
    // Icon: by category first (a restaurant keeps its fork and knife), changeable.
    var icon by remember { mutableStateOf<String?>(null) }
    var color by remember { mutableStateOf<Long?>(null) }
    val preview = de.lanewise.settings.SavedPlace(kind = kind, name = name, address = "", lat = 0.0, lon = 0.0, icon = icon, color = color)
    val (pi, pc) = PlaceIcons.forSaved(preview)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(str.savePlace) },
        text = {
            Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PlaceBadge(pi, pc, size = 44.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(place.title + (name.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""), fontWeight = FontWeight.Bold,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (place.subtitle.isNotBlank()) Text(place.subtitle, fontSize = 12.sp, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(kind == PlaceKind.HOME, { kind = PlaceKind.HOME }, { Text(str.home) },
                        leadingIcon = { Icon(Icons.Rounded.Home, null, Modifier.size(18.dp)) })
                    FilterChip(kind == PlaceKind.WORK, { kind = PlaceKind.WORK }, { Text(str.work) },
                        leadingIcon = { Icon(Icons.Rounded.Work, null, Modifier.size(18.dp)) })
                    FilterChip(kind == PlaceKind.FAVORITE, { kind = PlaceKind.FAVORITE }, { Text(str.favorite) },
                        leadingIcon = { Icon(Icons.Rounded.Star, null, Modifier.size(18.dp), tint = Color(PlaceIcons.STAR_YELLOW)) })
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(name, { name = it }, label = { Text(str.customName) }, placeholder = { Text(str.customNameHint) },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                Text(str.pickIcon, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                PlaceIcons.PICKABLE.chunked(6).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        row.forEach { (id, img) ->
                            val selected = icon == id || (icon == null && img == pi)
                            Box(
                                Modifier.size(40.dp).clip(CircleShape)
                                    .background(if (selected) Color(pc).copy(alpha = 0.25f) else Color.Transparent)
                                    .clickable { icon = id },
                                contentAlignment = Alignment.Center,
                            ) { Icon(img, id, tint = if (selected) Color(pc) else MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(str.pickColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    PlaceIcons.COLORS.forEach { argb ->
                        Box(
                            Modifier.size(26.dp).clip(CircleShape).background(Color(argb))
                                .border(if (pc == argb) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                .clickable { color = argb },
                        )
                    }
                }
            }
        },
        confirmButton = { Button({ onSave(kind, name, icon, color) }) { Text(str.save) } },
        dismissButton = { TextButton(onDismiss) { Text(str.cancel) } },
    )
}

// --- Route preview ------------------------------------------------------------------------------

@Composable
private fun PreviewOverlay(vm: MainViewModel, ui: UiState, phrases: Phrases, onPanelHeight: (Int) -> Unit = {}) {
    val c = LocalLaneColors.current
    val str = LocalStrings.current
    var saving by remember { mutableStateOf(false) }
    var pickingStart by remember { mutableStateOf(false) }
    var addingStop by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .onGloballyPositioned { onPanelHeight(it.size.height) }
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
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
            // Start point: GPS position by default, any searched place on tap (plan / demo a drive from elsewhere).
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(12.dp))
                    .background(c.onChip.copy(alpha = 0.06f)).clickable { pickingStart = true }.padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(str.from + ": ", color = c.onChip.copy(alpha = 0.65f), fontSize = 14.sp)
                Text(ui.origin?.title ?: str.myPosition, color = c.onChip, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text("✎", color = MaterialTheme.colorScheme.primary, fontSize = 16.sp)
            }
            // Stops on the way, in driving order (✕ removes one).
            ui.stops.forEachIndexed { i, stop ->
                Row(
                    Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(12.dp))
                        .background(c.onChip.copy(alpha = 0.06f)).padding(start = 12.dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${str.stop} ${i + 1}: ", color = c.onChip.copy(alpha = 0.65f), fontSize = 14.sp)
                    Text(stop.title, color = c.onChip, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    androidx.compose.material3.IconButton({ vm.removeStop(i) }) {
                        Icon(Icons.Rounded.Close, str.removeStop, tint = c.onChip.copy(alpha = 0.7f))
                    }
                }
            }
            // Swap start and destination, add a stop.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(vm::swapEnds, enabled = !ui.routing) {
                    Icon(Icons.Rounded.SwapVert, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(str.swapEnds, fontSize = 13.sp)
                }
                TextButton({ addingStop = true }, enabled = !ui.routing) {
                    Icon(Icons.Rounded.AddLocationAlt, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(str.addStop, fontSize = 13.sp)
                    Spacer(Modifier.width(6.dp)); HealthDot(ui.serverHealth)
                }
            }
            Spacer(Modifier.height(4.dp))
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
    if (pickingStart) StartPickerDialog(vm, ui) { pickingStart = false }
    if (addingStop) AddStopDialog(vm, ui, phrases) { addingStop = false }
    // Server load for the dot: on opening the preview, then every 30 s.
    LaunchedEffect(Unit) { while (true) { vm.probeServers(); kotlinx.coroutines.delay(30_000) } }
    val dest = ui.destination
    if (saving && dest != null) {
        SavePlaceDialog(dest, onSave = { kind, name, icon, color -> vm.savePlace(kind, name, dest, icon, color); saving = false }) { saving = false }
    }
}

/**
 * Add a stop: a normal place search, or — with "Best option along the route" — places matching a name, brand or
 * category along the route, sorted by the minutes they add (smart stops, [de.lanewise.search.StopFinder]).
 */
@Composable
private fun AddStopDialog(vm: MainViewModel, ui: UiState, phrases: Phrases, onDismiss: () -> Unit) {
    val str = LocalStrings.current
    var q by remember { mutableStateOf("") }
    var smart by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(true) }
    fun close() { vm.clearStopSearch(); onDismiss() }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = ::close,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(str.addStop, modifier = Modifier.weight(1f))
                HealthDot(ui.serverHealth, size = 12.dp)
                Spacer(Modifier.width(6.dp))
                Text(when (ui.serverHealth) {
                    de.lanewise.core.net.Health.FREE -> str.serverFree
                    de.lanewise.core.net.Health.MODERATE -> str.serverModerate
                    de.lanewise.core.net.Health.BUSY -> str.serverBusy
                    de.lanewise.core.net.Health.DOWN -> str.serverDown
                    null -> str.serverChecking
                }, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        text = {
            Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                androidx.compose.material3.OutlinedTextField(
                    value = q, onValueChange = { q = it; vm.onStopQuery(it, smart) }, singleLine = true,
                    placeholder = { Text(str.searchStop) }, modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    Modifier.fillMaxWidth().clickable { smart = !smart; vm.onStopQuery(q, smart) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.Checkbox(smart, { smart = it; vm.onStopQuery(q, it) })
                    Column {
                        Text(str.smartStop, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Text(str.smartStopHint, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    }
                }
                when {
                    ui.stopSearching == true -> Text(str.searching, fontSize = 13.sp, modifier = Modifier.padding(vertical = 6.dp))
                    ui.stopSearching == false && ui.stopPlaces.isEmpty() && ui.stopCandidates.isEmpty() ->
                        Text(if (smart) str.noStopsAlong else str.noResults, fontSize = 13.sp, modifier = Modifier.padding(vertical = 6.dp))
                }
                ui.stopCandidates.forEachIndexed { i, cand ->
                    Row(
                        Modifier.fillMaxWidth().clickable { vm.addStop(cand.place, cand.insertAt); onDismiss() }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val (ci, cc) = PlaceIcons.forCategory(cand.place.category)
                        PlaceBadge(ci, cc, size = 32.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text((if (i == 0) "★ " else "") + cand.place.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val sub = listOf(cand.place.subtitle, str.stopAfter.format(phrases.distance(cand.alongMeters)))
                                .filter { it.isNotBlank() }.joinToString(" · ")
                            Text(sub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text(str.stopDetour.format(kotlin.math.ceil(cand.detourSeconds / 60).toInt()),
                            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                }
                ui.stopPlaces.take(6).forEach { p -> DialogPlaceRow(p, ui.places) { vm.addStop(p); onDismiss() } }
                if (q.isBlank()) SavedPresets(ui.places) { sp -> vm.addStop(with(vm) { sp.asPlace() }); onDismiss() }
            }
        },
        confirmButton = { TextButton(::close) { Text(str.cancel) } },
    )
}

/**
 * Small dot for the load of the search / routing servers: green free, yellow moderately busy, red busy (searches may
 * fail or take long), grey unreachable or offline; outlined while the first check runs.
 */
@Composable
private fun HealthDot(h: de.lanewise.core.net.Health?, size: androidx.compose.ui.unit.Dp = 9.dp) {
    val color = when (h) {
        de.lanewise.core.net.Health.FREE -> Color(0xFF3DD68C)
        de.lanewise.core.net.Health.MODERATE -> Color(0xFFFFC83D)
        de.lanewise.core.net.Health.BUSY -> Color(0xFFE5484D)
        de.lanewise.core.net.Health.DOWN -> Color(0xFF8A93A6)
        null -> Color.Transparent
    }
    Box(Modifier.size(size).clip(CircleShape).background(color).border(1.dp, if (h == null) Color(0xFF8A93A6) else color, CircleShape))
}

/** A place in a picker dialog: kind icon, title, address, and the saved badge if it is one of yours. */
@Composable
private fun DialogPlaceRow(p: Place, saved: List<SavedPlace>, onClick: () -> Unit) {
    val mine = saved.firstOrNull { de.lanewise.core.geo.Geo.distance(LatLon(it.lat, it.lon), p.position) < 40 }
    val (icon, color) = if (mine != null && p.category == null) PlaceIcons.forSaved(mine) else PlaceIcons.forCategory(p.category)
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        PlaceBadge(icon, color, size = 32.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.title + (mine?.name?.takeIf { it != p.title }?.let { " ($it)" } ?: ""), fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (mine != null) {
                    val (si, sc) = PlaceIcons.forSaved(mine)
                    Spacer(Modifier.width(4.dp)); Icon(si, null, tint = Color(sc), modifier = Modifier.size(16.dp))
                }
            }
            if (p.subtitle.isNotBlank()) Text(p.subtitle, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Search a start point, or go back to the GPS position. */
@Composable
private fun StartPickerDialog(vm: MainViewModel, ui: UiState, onDismiss: () -> Unit) {
    val str = LocalStrings.current
    var q by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { vm.onOriginQuery(""); onDismiss() },
        title = { Text(str.chooseStart) },
        text = {
            Column {
                androidx.compose.material3.OutlinedTextField(
                    value = q, onValueChange = { q = it; vm.onOriginQuery(it) }, singleLine = true,
                    placeholder = { Text(str.searchStart) }, modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth().clickable { vm.chooseOrigin(null); onDismiss() }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    PlaceBadge(Icons.Rounded.MyLocation, 0xFF8C72FF, size = 32.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(str.myPosition, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
                // Saved places as presets until something is typed.
                if (q.isBlank()) SavedPresets(ui.places) { sp -> vm.chooseOrigin(with(vm) { sp.asPlace() }); onDismiss() }
                when {
                    ui.originSearching == true -> Text(str.searching, fontSize = 13.sp, modifier = Modifier.padding(vertical = 6.dp))
                    ui.originSearching == false && ui.originResults.isEmpty() ->
                        Text(str.noResults, fontSize = 13.sp, modifier = Modifier.padding(vertical = 6.dp))
                }
                if (q.isNotBlank()) ui.originResults.take(6).forEach { p ->
                    DialogPlaceRow(p, ui.places) { vm.chooseOrigin(p); onDismiss() }
                }
            }
        },
        confirmButton = { TextButton({ vm.onOriginQuery(""); onDismiss() }) { Text(str.cancel) } },
    )
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
            Text(formatDuration(r.etaDuration), color = c.onChip, fontSize = 20.sp, fontWeight = FontWeight.Bold)
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
        if (r.trafficDelay > 60) Text("+${(r.trafficDelay / 60).toInt()} min", color = c.overSpeed, fontWeight = FontWeight.Bold)
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

/**
 * Lane box countdown: share of the way from the previous lane site (or manoeuvre) to [site] still ahead, capped like
 * the banner's countdown so it starts moving only on the last stretch (400 m in town, 1 / 1.5 km on fast roads).
 */
private fun laneCountdown(nav: NavSnapshot, site: de.lanewise.lane.LaneSite): Float {
    val dist = site.distanceAlong - nav.distanceAlong
    val prev = nav.route.laneSites.lastOrNull { it.distanceAlong < site.distanceAlong - 1 }?.distanceAlong ?: 0.0
    val cap = when (site.roadKind) {
        de.lanewise.lane.RoadKind.MOTORWAY -> 1500.0
        de.lanewise.lane.RoadKind.TRUNK -> 1000.0
        else -> 400.0
    }
    val start = (site.distanceAlong - prev).coerceIn(50.0, cap)
    return (dist / start).toFloat().coerceIn(0f, 1f)
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
                // Only lanes that belong to the coming manoeuvre (or junctions before it): never the lanes of a junction
                // after the next turn (e.g. "turn left" here, lanes of a right turn 600 m later).
                // A junction the route only passes through, far before the manoeuvre in the banner, is shown just shortly
                // before it (owner's video: lanes of side junctions under "turn right in 2.3 km" looked like the turn's).
                val passThrough = advice != null && !advice.site.isManeuver &&
                    (next == null || next.distanceAlong - advice.site.distanceAlong > 80) &&
                    nav.lanes.pendingChange?.site != advice.site
                val fastRoad = advice?.site?.roadKind.let { it == de.lanewise.lane.RoadKind.MOTORWAY || it == de.lanewise.lane.RoadKind.TRUNK }
                val showLanes = advice != null && distToSite < (if (passThrough) (if (fastRoad) 600.0 else 250.0) else 900.0) &&
                    (next == null || advice.site.distanceAlong <= next.distanceAlong + 10)
                // Off the route: no turn / lanes of the old route (they are for another street); say what happens instead.
                val off = nav.offRoute
                ManeuverBanner(
                    step = if (off) null else next,
                    distanceText = if (off) "" else phrases.distance(nav.distanceToNextStep),
                    instruction = if (off) phrases.rerouting() else next?.let { phrases.instruction(it) } ?: "",
                    then = if (off) null else then,
                    thenText = then?.let { str.then },
                    modifier = Modifier.weight(1f),
                    countdown = next?.let { countdownFraction(nav) },
                    lanesBelow = showLanes && !off,
                    recalculating = off,
                    belowLeft = nav.signals?.takeIf { settings.showSigns && it.distance < 250 }?.let { sig -> { de.lanewise.ui.hud.SignalCard(sig) } },
                    signColors = settings.signColors,
                ) {
                    if (showLanes && advice != null && !off) {
                        Spacer(Modifier.height(3.dp))
                        LaneBar(advice, bikeColor = nav.bikeLane.takeIf { settings.showBikeLanes }?.let { bikeColor },
                            onLaneTap = vm::overrideLane, onLongPress = vm::reportLanes, docked = true,
                            // Lanes of a junction before the banner's manoeuvre get their own distance.
                            distanceLabel = if (passThrough) phrases.distance(distToSite) else null,
                            countdown = if (settings.laneBoxCountdown) laneCountdown(nav, advice.site) else null)
                    }
                }
            }
            val advice = nav.lanes.nextAdvice
            val distToSite = advice?.let { it.site.distanceAlong - nav.distanceAlong } ?: Double.MAX_VALUE
            val bikeHere = nav.bikeLane.takeIf { settings.showBikeLanes }
            // Junction close-up fades in and out (it popped in a single frame, which read as a stutter in the video).
            val showScene = advice != null && distToSite < 900 && !settings.immersiveView && settings.showLaneScene &&
                distToSite <= settings.laneSceneDistance && (advice.site.isManeuver || nav.lanes.pendingChange != null)
            androidx.compose.animation.AnimatedVisibility(
                visible = showScene,
                modifier = Modifier.align(Alignment.CenterHorizontally),
                enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(250)) +
                    androidx.compose.animation.expandVertically(androidx.compose.animation.core.tween(250)),
                exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(250)) +
                    androidx.compose.animation.shrinkVertically(androidx.compose.animation.core.tween(250)),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(8.dp))
                    LaneScene3D(nav.lanes, phrases.distance(distToSite.coerceAtMost(9_999.0)),
                        Modifier.fillMaxWidth(0.82f).height(140.dp),
                        bikeColor = bikeHere?.let { bikeColor })
                    Spacer(Modifier.height(8.dp))
                }
            }
            if (bikeHere != null) {
                Spacer(Modifier.height(6.dp))
                BikeLaneChip(bikeHere.exclusive, bikeColor, Modifier.align(Alignment.CenterHorizontally))
            }
            ui.laneHint?.let { Spacer(Modifier.height(8.dp)); LaneHint(it, countdown = ui.laneHintCountdown) }
            // Safety: no lane data for the next turn - say so instead of guessing a lane.
            if (ui.nav?.lanesUnknown == true && ui.laneHint == null) { Spacer(Modifier.height(8.dp)); LaneHint(str.lanesUnknown) }
        }

        SpeedPanel(nav.fix.speedKmh, nav.speedStatus, Modifier.align(Alignment.CenterStart).padding(start = 12.dp, top = 160.dp))
        if (!following) {
            OutlinedButton(
                onClick = onRecenter,
                modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 16.dp, bottom = 110.dp),
                border = androidx.compose.foundation.BorderStroke(2.dp, Color(0xFFB8F33A)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFB8F33A)),
            ) { Text(str.recenter, fontWeight = FontWeight.Bold) }
        }
        if (ui.simulating) {
            // Demo badge with playback speed: − / × / + (live, the simulated car speeds up or slows down at once).
            Column(
                Modifier.align(Alignment.CenterEnd).padding(end = 12.dp).clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF6C4CF5)).padding(horizontal = 4.dp, vertical = 3.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(str.demoBadge, color = Color.White, fontSize = 12.sp)
                val speed = ui.demoSpeed
                Text(if (speed < 1) "×${speed.toString().trimEnd('0')}" else "×${speed.toInt()}",
                    color = Color(0xFFB8F33A), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                // Play / pause, like a video player: the car stands still and the lanes there can be studied.
                Box(
                    Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).clickable { vm.toggleDemoPause() }
                        .semantics { contentDescription = if (ui.demoPaused) str.demoPlay else str.demoPause },
                    contentAlignment = Alignment.Center,
                ) { Icon(if (ui.demoPaused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, null, tint = Color.White) }
                Row {
                    for ((faster, label, desc) in listOf(Triple(false, "−", str.demoSlower), Triple(true, "+", str.demoFaster))) {
                        Box(
                            Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).clickable { vm.changeDemoSpeed(faster) }
                                .semantics { contentDescription = desc },
                            contentAlignment = Alignment.Center,
                        ) { Text(label, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }
        // Drive timer: ticks once a second on its own (fixes may pause in a tunnel).
        val now by androidx.compose.runtime.produceState(System.currentTimeMillis(), settings.showDriveTimer) {
            while (settings.showDriveTimer) { value = System.currentTimeMillis(); kotlinx.coroutines.delay(1000) }
        }
        EtaBar(
            remainingText = formatDuration(nav.remainingSeconds),
            elapsedText = if (settings.showDriveTimer && ui.navStartedAt > 0) formatElapsed((now - ui.navStartedAt) / 1000) else null,
            distanceText = phrases.distance(nav.remainingDistance),
            etaText = SimpleDateFormat("HH:mm", Locale.GERMANY).format(Date(System.currentTimeMillis() + (nav.remainingSeconds * 1000).toLong())),
            onClose = vm::stopNavigation, onOverview = onOverview,
            // Whole journey, across re-routes: driven on earlier routes + driven on this one, out of that + remaining.
            progress = if (ui.simulating) (nav.distanceAlong / nav.route.distance.coerceAtLeast(1.0)).toFloat()
            else (ui.journeyDriven + nav.distanceAlong).let { done -> (done / (done + nav.remainingDistance).coerceAtLeast(1.0)).toFloat() },
            // Demo: scrub bar (tap / drag to jump, also backwards) showing the distance from the start while dragging.
            onSeek = if (ui.simulating) vm::seekDemo else null,
            scrubLabel = { f -> phrases.distance(f * nav.route.distance) + " / " + phrases.distance(nav.route.distance) },
            online = ui.online, onlineText = str.online, offlineText = str.offlineBadge,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/** Stopwatch text: "12:34" or "1:02:03". */
fun formatElapsed(seconds: Long): String {
    val s = seconds.coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

fun formatDuration(seconds: Double): String {
    val m = (seconds / 60).toInt()
    return if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min"
}
