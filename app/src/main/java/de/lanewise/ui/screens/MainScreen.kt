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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import de.lanewise.settings.AppSettings
import de.lanewise.ui.MainViewModel
import de.lanewise.ui.Mode
import de.lanewise.ui.UiState
import de.lanewise.ui.hud.EtaBar
import de.lanewise.ui.hud.GnssBadge
import de.lanewise.ui.hud.ManeuverBanner
import de.lanewise.ui.hud.SpeedBorderOverlay
import de.lanewise.ui.hud.SpeedPanel
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
    val phrases = remember(settings.voiceLanguage) { Phrases(settings.voiceLanguage) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val topPadPx = with(LocalDensity.current) { (maxHeight * 0.42f).toPx().toDouble() }

        MapLayer(controller, dark) { following = false }

        // Keep map data in sync with state.
        LaunchedEffect(dark) { controller.setDark(dark) }
        LaunchedEffect(ui.routes, ui.selected, ui.mode, ui.destination) {
            val main = ui.selectedRoute
            controller.showRoutes(main, ui.routes.filterIndexed { i, _ -> i != ui.selected }, ui.destination?.position)
            if (ui.mode == Mode.PREVIEW && main != null) controller.overview(main, 420, 900)
        }
        LaunchedEffect(ui.incidents) { controller.showIncidents(ui.incidents) }
        LaunchedEffect(ui.nav) {
            ui.nav?.let { controller.updateNavigation(it, settings.laneRibbonsOnMap, settings.mapPitch, topPadPx) }
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
            Mode.BROWSE -> BrowseOverlay(vm, ui, onOpenSettings, onRecenter = { controller.recenter(); following = true })
            Mode.PREVIEW -> PreviewOverlay(vm, ui, phrases)
            Mode.NAVIGATING -> ui.nav?.let { nav ->
                NavigationOverlay(vm, ui, nav, settings, phrases, following,
                    onRecenter = { controller.recenter(); following = true },
                    onOverview = { controller.overview(nav.route, 420, 500); following = false })
            }
        }

        SpeedBorderOverlay(ui.nav?.speedStatus ?: de.lanewise.nav.SpeedStatus.UNKNOWN)

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
private fun BrowseOverlay(vm: MainViewModel, ui: UiState, onOpenSettings: () -> Unit, onRecenter: () -> Unit) {
    val c = LocalLaneColors.current
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(c.chip).padding(start = 16.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painterResource(R.drawable.ic_search), null, tint = c.onChip.copy(alpha = 0.7f))
                TextField(
                    value = ui.query, onValueChange = vm::onQuery, modifier = Modifier.weight(1f),
                    placeholder = { Text("Wohin? (z. B. Hannover Messe)") }, singleLine = true,
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
                ) { Icon(painterResource(R.drawable.ic_tune), "Einstellungen", tint = c.onChip) }
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
            }
        }
        GnssBadge(ui.gnss, ui.online, Modifier.align(Alignment.BottomStart).padding(16.dp))
        Box(
            Modifier.align(Alignment.BottomEnd).padding(16.dp).size(56.dp).clip(CircleShape).background(c.chip).clickable(onClick = onRecenter),
            contentAlignment = Alignment.Center,
        ) { Icon(painterResource(R.drawable.ic_locate), "Position", tint = MaterialTheme.colorScheme.primary) }
        ui.offlineProgress?.let {
            Text(it, Modifier.align(Alignment.BottomCenter).padding(bottom = 84.dp).clip(RoundedCornerShape(12.dp))
                .background(c.chip).padding(10.dp), color = c.onChip, fontSize = 13.sp)
        }
    }
}

// --- Route preview ------------------------------------------------------------------------------

@Composable
private fun PreviewOverlay(vm: MainViewModel, ui: UiState, phrases: Phrases) {
    val c = LocalLaneColors.current
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(c.chip).padding(18.dp),
        ) {
            Text(ui.destination?.title ?: "", color = c.onChip, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            ui.destination?.subtitle?.takeIf { it.isNotBlank() }?.let { Text(it, color = c.onChip.copy(alpha = 0.65f), fontSize = 13.sp) }
            Spacer(Modifier.height(12.dp))
            if (ui.routing) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Berechne Route mit Spurdaten …", color = c.onChip)
                }
            }
            ui.routes.forEachIndexed { i, r -> RouteRow(r, i == ui.selected, phrases) { vm.selectRoute(i) } }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = vm::cancelPreview) { Text("Abbrechen") }
                OutlinedButton(onClick = { vm.startNavigation(simulate = true) }, enabled = ui.routes.isNotEmpty()) { Text("Demo") }
                Button(
                    onClick = { vm.startNavigation(simulate = false) }, enabled = ui.routes.isNotEmpty(), modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                ) { Text("Los geht's", fontWeight = FontWeight.Bold) }
            }
        }
    }
}

@Composable
private fun RouteRow(r: Route, selected: Boolean, phrases: Phrases, onClick: () -> Unit) {
    val c = LocalLaneColors.current
    val laneSites = r.laneSites.size
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
            val extra = buildList {
                add(phrases.distance(r.distance))
                add("$laneSites Spur-Knoten")
                if (works > 0) add("$works Baustellen")
                if (closures > 0) add("$closures Sperrungen")
                add(r.provider.uppercase())
            }
            Text(extra.joinToString(" · "), color = c.onChip.copy(alpha = 0.7f), fontSize = 13.sp)
        }
        if (r.trafficPenalty > 60) Text("+${(r.trafficPenalty / 60).toInt()} min", color = c.overSpeed, fontWeight = FontWeight.Bold)
    }
}

// --- Navigation ---------------------------------------------------------------------------------

@Composable
private fun NavigationOverlay(
    vm: MainViewModel, ui: UiState, nav: NavSnapshot, settings: AppSettings, phrases: Phrases, following: Boolean,
    onRecenter: () -> Unit, onOverview: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 10.dp, vertical = 6.dp)) {
            val next = nav.nextStep
            val then = nav.stepAfter?.takeIf { next != null && it.distanceAlong - next.distanceAlong < 250 }
            ManeuverBanner(
                step = next,
                distanceText = phrases.distance(nav.distanceToNextStep),
                instruction = next?.let { phrases.instruction(it) } ?: "",
                then = then,
                thenText = then?.let { "Dann" },
            )
            val advice = nav.lanes.nextAdvice
            val distToSite = advice?.let { it.site.distanceAlong - nav.distanceAlong } ?: Double.MAX_VALUE
            if (advice != null && distToSite < 900) {
                Spacer(Modifier.height(8.dp))
                if (settings.showLaneScene && distToSite < 350 && (advice.site.isManeuver || nav.lanes.pendingChange != null)) {
                    LaneScene3D(nav.lanes, phrases.distance(distToSite), Modifier.fillMaxWidth().height(220.dp))
                    Spacer(Modifier.height(8.dp))
                }
                LaneBar(advice, Modifier.align(Alignment.CenterHorizontally), onLaneTap = vm::overrideLane)
            }
            ui.laneHint?.let { Spacer(Modifier.height(8.dp)); LaneHint(it) }
        }

        SpeedPanel(
            nav.speedLimit, nav.noSpeedLimit, nav.fix.speedKmh, nav.speedStatus,
            Modifier.align(Alignment.CenterStart).padding(start = 12.dp, top = 120.dp),
        )
        if (!following) {
            OutlinedButton(
                onClick = onRecenter,
                modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 16.dp, bottom = 110.dp),
            ) { Text("Zentrieren") }
        }
        if (ui.simulating) {
            Text("DEMO", Modifier.align(Alignment.CenterEnd).padding(end = 12.dp).clip(RoundedCornerShape(8.dp))
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
