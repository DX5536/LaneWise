package de.lanewise.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.lanewise.offline.OfflineGraphBuilder
import de.lanewise.settings.AppLanguage
import de.lanewise.settings.AppSettings
import de.lanewise.settings.BIKE_COLORS
import de.lanewise.settings.RoutingSource
import de.lanewise.settings.ThemeMode
import de.lanewise.ui.MainViewModel
import de.lanewise.ui.UiState
import de.lanewise.ui.i18n.LocalStrings

@Composable
fun SettingsScreen(vm: MainViewModel, s: AppSettings, ui: UiState, onBack: () -> Unit) {
    val context = LocalContext.current
    val str = LocalStrings.current
    val certPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            context.contentResolver.openInputStream(uri)?.use { input ->
                vm.mobilithekCert().outputStream().use { input.copyTo(it) }
            }
        }
    }
    fun set(t: (AppSettings) -> AppSettings) = vm.updateSettings(t)

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(str.back) }
            Text(str.settings, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }

        Section(str.language) {
            AppLanguage.entries.forEach { l ->
                Radio(when (l) {
                    AppLanguage.GERMAN -> "Deutsch"
                    AppLanguage.ENGLISH -> "English"
                    AppLanguage.VIETNAMESE -> "Tiếng Việt"
                }, s.language == l) { set { it.copy(language = l) } }
            }
        }

        Section(str.appearance) {
            ThemeMode.entries.forEach { m ->
                Radio(when (m) { ThemeMode.AUTO -> str.themeAuto; ThemeMode.SYSTEM -> str.themeSystem; ThemeMode.LIGHT -> str.themeLight; ThemeMode.DARK -> str.themeDark },
                    s.theme == m) { set { it.copy(theme = m) } }
            }
            Toggle(str.satellite, str.satelliteSub, s.satellite) { v -> set { it.copy(satellite = v) } }
            LabeledSlider(str.cameraPitch, s.mapPitch, 0f..70f, "${s.mapPitch.toInt()}°") { v -> set { it.copy(mapPitch = v) } }
            Toggle(str.car3d, str.car3dSub, s.car3d) { v -> set { it.copy(car3d = v) } }
            LabeledSlider(str.carSize, s.carScale, 0.5f..2f, "×%.1f".format(s.carScale), step = 0.1f) { v ->
                set { it.copy(carScale = Math.round(v * 10) / 10f) }
            }
            Toggle(str.autoZoom, str.autoZoomSub, s.autoZoom) { v -> set { it.copy(autoZoom = v) } }
            if (!s.autoZoom) {
                LabeledSlider(str.fixedZoom, s.fixedZoom, 15.5f..19f, "%.1f".format(s.fixedZoom), step = 0.1f) { v ->
                    set { it.copy(fixedZoom = Math.round(v * 10) / 10f) }
                }
            }
        }

        Section(str.laneAssist) {
            Toggle(str.avoidChanges, str.avoidChangesSub, s.avoidLaneChanges) { v -> set { it.copy(avoidLaneChanges = v) } }
            Toggle(str.keepRight, str.keepRightSub, s.keepRight) { v -> set { it.copy(keepRight = v) } }
            Toggle(str.immersiveView, str.immersiveViewSub, s.immersiveView) { v -> set { it.copy(immersiveView = v) } }
            Toggle(str.streetsWhileDriving, str.streetsWhileDrivingSub, s.streetsWhileDriving) { v -> set { it.copy(streetsWhileDriving = v) } }
            if (!s.immersiveView) {
                Toggle(str.laneScene, str.laneSceneSub, s.showLaneScene) { v -> set { it.copy(showLaneScene = v) } }
                Toggle(str.junctionZoom, null, s.junctionZoom) { v -> set { it.copy(junctionZoom = v) } }
                LabeledSlider(str.closeUpFrom, s.laneSceneDistance.toFloat(), 20f..300f, "${s.laneSceneDistance} ${str.beforeJunction}") { v ->
                    set { it.copy(laneSceneDistance = (v / 10).toInt() * 10) }
                }
            }
            Toggle(str.laneRibbons, str.laneRibbonsSub, s.laneRibbonsOnMap) { v -> set { it.copy(laneRibbonsOnMap = v) } }
        }

        Section(str.lookExtras) {
            Toggle(str.stripFade, str.stripFadeSub, s.stripFade) { v -> set { it.copy(stripFade = v) } }
            Toggle(str.laneChevrons, str.laneChevronsSub, s.laneChevrons) { v -> set { it.copy(laneChevrons = v) } }
            Toggle(str.signColors, str.signColorsSub, s.signColors) { v -> set { it.copy(signColors = v) } }
            Toggle(str.roadNameChip, str.roadNameChipSub, s.roadNameChip) { v -> set { it.copy(roadNameChip = v) } }
            Toggle(str.closedLanes, str.closedLanesSub, s.closedLanes) { v -> set { it.copy(closedLanes = v) } }
            Toggle(str.roadFurniture, str.roadFurnitureSub, s.roadFurniture) { v -> set { it.copy(roadFurniture = v) } }
            Toggle(str.softBuildings, str.softBuildingsSub, s.softBuildings) { v -> set { it.copy(softBuildings = v) } }
        }

        Section(str.abIcons) {
            Toggle(str.abParking, null, s.abParking) { v -> set { it.copy(abParking = v) } }
            Toggle(str.abCharging, null, s.abCharging) { v -> set { it.copy(abCharging = v) } }
            Toggle(str.abWebcams, str.abWebcamsSub, s.abWebcams) { v -> set { it.copy(abWebcams = v) } }
        }

        Section(str.bikeLanes) {
            Toggle(str.showBikeLanes, str.showBikeLanesSub, s.showBikeLanes) { v -> set { it.copy(showBikeLanes = v) } }
            Toggle(str.aerialLanes, str.aerialLanesSub, s.aerialLanes) { v -> set { it.copy(aerialLanes = v) } }
            Text(str.bikeLaneColor, fontSize = 14.sp)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BIKE_COLORS.forEach { argb ->
                    val selected = s.bikeLaneColor == argb
                    Box(
                        Modifier.size(36.dp).clip(CircleShape).background(Color(argb))
                            .border(if (selected) 3.dp else 0.dp, MaterialTheme.colorScheme.onBackground, CircleShape)
                            .clickable { set { it.copy(bikeLaneColor = argb) } },
                    )
                }
            }
        }

        Section(str.roadSigns) {
            Toggle(str.showSignsOnMap, str.showSignsSub, s.showSigns) { v -> set { it.copy(showSigns = v) } }
        }

        Section(str.speed) {
            Toggle(str.overSpeed, null, s.overSpeedWarning) { v -> set { it.copy(overSpeedWarning = v) } }
            LabeledSlider(str.overTolerance, s.overSpeedOffset.toFloat(), 0f..25f, "+${s.overSpeedOffset} km/h") { v -> set { it.copy(overSpeedOffset = v.toInt()) } }
            Toggle(str.underSpeed, str.underSpeedSub, s.underSpeedWarning) { v -> set { it.copy(underSpeedWarning = v) } }
            LabeledSlider(str.underTolerance, s.underSpeedOffset.toFloat(), 5f..50f, "−${s.underSpeedOffset} km/h") { v -> set { it.copy(underSpeedOffset = v.toInt()) } }
            Toggle(str.overSound, null, s.overSpeedSound) { v -> set { it.copy(overSpeedSound = v) } }
        }

        Section(str.routing) {
            RoutingSource.entries.forEach { r ->
                Radio(when (r) {
                    RoutingSource.AUTO -> str.routingAuto
                    RoutingSource.OSRM -> str.routingOsrm
                    RoutingSource.TOMTOM -> str.routingTomTom
                    RoutingSource.OFFLINE -> str.routingOffline
                }, s.routing == r) { set { it.copy(routing = r) } }
            }
            Toggle(str.avoidTolls, null, s.avoidTolls) { v -> set { it.copy(avoidTolls = v) } }
            Toggle(str.avoidMotorways, null, s.avoidMotorways) { v -> set { it.copy(avoidMotorways = v) } }
            Field(str.osrmServer, s.osrmUrl) { v -> set { it.copy(osrmUrl = v) } }
        }

        Section(str.trafficData) {
            Toggle(str.autobahnApi, str.autobahnApiSub, s.useAutobahnApi) { v -> set { it.copy(useAutobahnApi = v) } }
            Field(str.tomtomKey, s.tomtomKey, secret = true) { v -> set { it.copy(tomtomKey = v) } }
            Field(str.hereKey, s.hereKey, secret = true) { v -> set { it.copy(hereKey = v) } }
            Field(str.mobilithekUrl, s.mobilithekUrl) { v -> set { it.copy(mobilithekUrl = v) } }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { certPicker.launch(arrayOf("application/x-pkcs12", "application/octet-stream")) }) { Text(str.importCert) }
                Spacer(Modifier.padding(6.dp))
                Text(if (vm.mobilithekCert().exists()) str.certPresent else str.certMissing, fontSize = 13.sp)
            }
            Field(str.certPassword, s.mobilithekCertPassword, secret = true) { v -> set { it.copy(mobilithekCertPassword = v) } }
        }

        Section(str.offlineTitle) {
            Text(str.offlineText, fontSize = 13.sp)
            OfflineGraphBuilder.REGIONS.forEach { r ->
                Radio(r.name, s.offlineRegion == r.id) { set { it.copy(offlineRegion = r.id) } }
            }
            Button(onClick = vm::downloadOfflineRegion, enabled = ui.offlineProgress == null) { Text(str.downloadRegion) }
            Text(ui.offlineProgress ?: if (ui.offlineGraphReady) str.offlineReady else str.offlineNone, fontSize = 13.sp)
        }

        Section(str.voice) {
            Toggle(str.voiceEnabled, null, s.voice) { v -> set { it.copy(voice = v) } }
            // Voice for the current app language: Android TTS or an OmniVoice pack (installed or downloadable).
            androidx.compose.runtime.LaunchedEffect(Unit) { vm.reloadVoicePacks() }
            val langCode = de.lanewise.nav.VoiceInventory.code(s.language)
            val selectedIds = s.voicePacks.split(',').toSet()
            val installed = ui.voicePacks.filter { it.language == langCode }
            Text(str.voicePacksText, fontSize = 13.sp)
            Radio(str.voiceSystem, installed.none { it.id in selectedIds }) { vm.selectVoicePack(langCode, null) }
            installed.forEach { p ->
                Radio("${p.name} (${p.clipCount} ${str.voiceInstalledClips})", p.id in selectedIds) { vm.selectVoicePack(langCode, p.id) }
            }
            OutlinedButton(onClick = vm::testVoice) { Text(str.voiceTest) }
            de.lanewise.nav.VoicePack.CATALOG.filter { a -> a.language == langCode && installed.none { it.id == a.id } }.forEach { a ->
                val dl = ui.voiceDownload
                Button(onClick = { vm.downloadVoicePack(a.id) }, enabled = dl == null) {
                    Text(if (dl?.first == a.id) "${str.voiceDownloading} ${a.name}: ${dl.second} %" else "${str.voiceDownload}: ${a.name}")
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(str.attribution, fontSize = 11.sp)
        // Which build this is (CI run number + commit), so screenshots and bug reports can be matched to the code.
        Text("LaneWise ${de.lanewise.BuildConfig.VERSION_NAME} · ${de.lanewise.BuildConfig.GIT_SHA}" +
            if (de.lanewise.BuildConfig.DEBUG) " · debug" else "", fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Spacer(Modifier.height(16.dp))
    Text(title, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
    Spacer(Modifier.height(6.dp))
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surface).padding(12.dp),
    ) { content() }
}

@Composable
private fun Toggle(title: String, subtitle: String?, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!value) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp)
            subtitle?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked = value, onCheckedChange = onChange)
    }
}

@Composable
private fun Radio(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, fontSize = 14.sp)
    }
}

@Composable
private fun LabeledSlider(
    label: String, value: Float, range: ClosedFloatingPointRange<Float>, valueText: String,
    /** Rounding step of the value (1 = whole numbers; 0.1 for zoom level and car size). */
    step: Float = 1f,
    onChange: (Float) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = 14.sp)
        Text(valueText, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
    // Continuous slider (no tick dots); values are rounded here.
    Slider(value = value, onValueChange = { onChange(kotlin.math.round(it / step) * step) }, valueRange = range)
}

@Composable
private fun Field(label: String, value: String, secret: Boolean = false, onChange: (String) -> Unit) {
    // Typing edits a local copy; it is written to DataStore 600 ms after the last keystroke (and when the field leaves
    // the screen), instead of one disk write per character.
    var text by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(value) }
    val latest by androidx.compose.runtime.rememberUpdatedState(onChange)
    val stored by androidx.compose.runtime.rememberUpdatedState(value)
    androidx.compose.runtime.LaunchedEffect(text) {
        kotlinx.coroutines.delay(600)
        if (text != stored) latest(text)
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { if (text != stored) latest(text) }
    }
    OutlinedTextField(
        value = text, onValueChange = { text = it }, label = { Text(label) }, singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}
