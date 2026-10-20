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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.DirectionsBike
import androidx.compose.material.icons.rounded.AltRoute
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Traffic
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.vector.ImageVector

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
    // Messages from actions on this screen (voice download failed, offline region …) - the map screen's message
    // banner is not visible here, so a failed download looked like nothing happened (owner's video).
    androidx.compose.runtime.LaunchedEffect(ui.message) {
        ui.message?.let { android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show(); vm.clearMessage() }
    }

    val dark = MaterialTheme.colorScheme.background.let { (it.red + it.green + it.blue) / 3f < 0.5f }
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 16.dp),
    ) {
        Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, str.back) }
            Text(str.settings, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        }
        // Pinned live preview: every look option shows here at once, no demo needed.
        SettingsPreview(s, dark, str.preview)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {

        Section(str.language, Icons.Rounded.Translate) {
            Segmented(AppLanguage.entries, s.language, { l ->
                when (l) { AppLanguage.GERMAN -> "Deutsch"; AppLanguage.ENGLISH -> "English"; AppLanguage.VIETNAMESE -> "Tiếng Việt" }
            }) { l -> set { it.copy(language = l) } }
        }

        Section(str.appearance, Icons.Rounded.Palette) {
            Segmented(ThemeMode.entries, s.theme, { m ->
                when (m) { ThemeMode.AUTO -> str.themeAutoShort; ThemeMode.SYSTEM -> str.themeSystem
                    ThemeMode.LIGHT -> str.themeLight; ThemeMode.DARK -> str.themeDark }
            }, showIcon = false) { m -> set { it.copy(theme = m) } }
            if (s.theme == ThemeMode.AUTO) Text(str.themeAuto, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(6.dp))
            Toggle(str.satellite, str.satelliteSub, s.satellite) { v -> set { it.copy(satellite = v) } }
            LabeledSlider(str.cameraPitch, s.mapPitch, 0f..70f, "${s.mapPitch.toInt()}°") { v -> set { it.copy(mapPitch = v) } }
            Toggle(str.car3d, str.car3dSub, s.car3d) { v -> set { it.copy(car3d = v) } }
            LabeledSlider(str.carSize, s.carScale, 0.5f..2f, "×%.1f".format(s.carScale), step = 0.1f) { v ->
                set { it.copy(carScale = Math.round(v * 10) / 10f) }
            }
            Toggle(str.autoZoom, str.autoZoomSub, s.autoZoom) { v -> set { it.copy(autoZoom = v) } }
            if (!s.autoZoom) {
                LabeledSlider(str.fixedZoom, s.fixedZoom, 15.5f..21f, "%.1f".format(s.fixedZoom), step = 0.1f) { v ->
                    set { it.copy(fixedZoom = Math.round(v * 10) / 10f) }
                }
            }
        }

        Section(str.laneAssist, Icons.Rounded.AltRoute) {
            Toggle(str.avoidChanges, str.avoidChangesSub, s.avoidLaneChanges) { v -> set { it.copy(avoidLaneChanges = v) } }
            Toggle(str.keepRight, str.keepRightSub, s.keepRight) { v -> set { it.copy(keepRight = v) } }
            Toggle(str.immersiveView, str.immersiveViewSub, s.immersiveView) { v -> set { it.copy(immersiveView = v) } }
            Toggle(str.streetsWhileDriving, str.streetsWhileDrivingSub, s.streetsWhileDriving) { v -> set { it.copy(streetsWhileDriving = v) } }
            Toggle(str.laneBoxCountdown, str.laneBoxCountdownSub, s.laneBoxCountdown) { v -> set { it.copy(laneBoxCountdown = v) } }
            Toggle(str.driveTimer, str.driveTimerSub, s.showDriveTimer) { v -> set { it.copy(showDriveTimer = v) } }
            if (!s.immersiveView) {
                Toggle(str.laneScene, str.laneSceneSub, s.showLaneScene) { v -> set { it.copy(showLaneScene = v) } }
                Toggle(str.junctionZoom, null, s.junctionZoom) { v -> set { it.copy(junctionZoom = v) } }
                LabeledSlider(str.closeUpFrom, s.laneSceneDistance.toFloat(), 20f..300f, "${s.laneSceneDistance} ${str.beforeJunction}") { v ->
                    set { it.copy(laneSceneDistance = (v / 10).toInt() * 10) }
                }
            }
            Toggle(str.laneRibbons, str.laneRibbonsSub, s.laneRibbonsOnMap) { v -> set { it.copy(laneRibbonsOnMap = v) } }
            LabeledSlider(str.laneOpacity, s.laneOpacity, 0.2f..1f, "${(s.laneOpacity * 100).toInt()} %", step = 0.05f) { v ->
                set { it.copy(laneOpacity = v) }
            }
            // Lane reports (long-press on the lane bar): count + share sheet instead of adb pull.
            val reports = remember { vm.laneReportCount() }
            Text(str.laneReports, fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp))
            Text(String.format(str.laneReportsSub, reports), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // Hand-checked lane patches (asset + the phone's own lane_overrides.json): how many apply, how many were dropped.
            val patches by produceState<Pair<Int, Int>?>(null) { value = vm.laneOverrides().let { it.entries.size to it.rejected.size } }
            patches?.let { (ok, bad) ->
                Text(String.format(str.lanePatches, ok, bad), fontSize = 12.sp,
                    color = if (bad > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedButton(onClick = { vm.laneReportShareIntent()?.let { context.startActivity(it) } }, enabled = reports > 0,
                modifier = Modifier.padding(top = 4.dp)) { ButtonIcon(Icons.Rounded.Share); Text(str.shareReports) }
        }

        Section(str.testDrive, Icons.Rounded.DirectionsCar) {
            Text(str.testDriveSub, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = { vm.startTestDrive(); onBack() }, modifier = Modifier.padding(top = 6.dp)) {
                ButtonIcon(Icons.Rounded.PlayArrow); Text(str.testDriveButton)
            }
        }

        Section(str.lookExtras, Icons.Rounded.AutoAwesome) {
            Toggle(str.stripFade, str.stripFadeSub, s.stripFade) { v -> set { it.copy(stripFade = v) } }
            Toggle(str.laneChevrons, str.laneChevronsSub, s.laneChevrons) { v -> set { it.copy(laneChevrons = v) } }
            Toggle(str.signColors, str.signColorsSub, s.signColors) { v -> set { it.copy(signColors = v) } }
            Toggle(str.roadNameChip, str.roadNameChipSub, s.roadNameChip) { v -> set { it.copy(roadNameChip = v) } }
            Toggle(str.closedLanes, str.closedLanesSub, s.closedLanes) { v -> set { it.copy(closedLanes = v) } }
            Toggle(str.roadFurniture, str.roadFurnitureSub, s.roadFurniture) { v -> set { it.copy(roadFurniture = v) } }
            Toggle(str.softBuildings, str.softBuildingsSub, s.softBuildings) { v -> set { it.copy(softBuildings = v) } }
        }

        Section(str.abIcons, Icons.Rounded.Map) {
            Toggle(str.abParking, null, s.abParking) { v -> set { it.copy(abParking = v) } }
            Toggle(str.abCharging, null, s.abCharging) { v -> set { it.copy(abCharging = v) } }
            Toggle(str.abWebcams, str.abWebcamsSub, s.abWebcams) { v -> set { it.copy(abWebcams = v) } }
        }

        Section(str.bikeLanes, Icons.AutoMirrored.Rounded.DirectionsBike) {
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

        Section(str.roadSigns, Icons.Rounded.Warning) {
            Toggle(str.showSignsOnMap, str.showSignsSub, s.showSigns) { v -> set { it.copy(showSigns = v) } }
        }

        Section(str.speed, Icons.Rounded.Speed) {
            Toggle(str.overSpeed, null, s.overSpeedWarning) { v -> set { it.copy(overSpeedWarning = v) } }
            // Glow strength with a live sample of the warning border.
            LabeledSlider(str.speedGlow, s.speedGlow, 0.2f..2f, "${(s.speedGlow * 100).toInt()} %", step = 0.05f) { v ->
                set { it.copy(speedGlow = v) }
            }
            Box(Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.background)) {
                de.lanewise.ui.hud.SpeedBorderOverlay(de.lanewise.nav.SpeedStatus.OVER, intensity = s.speedGlow)
            }
            LabeledSlider(str.overTolerance, s.overSpeedOffset.toFloat(), 0f..25f, "+${s.overSpeedOffset} km/h") { v -> set { it.copy(overSpeedOffset = v.toInt()) } }
            Toggle(str.underSpeed, str.underSpeedSub, s.underSpeedWarning) { v -> set { it.copy(underSpeedWarning = v) } }
            LabeledSlider(str.underTolerance, s.underSpeedOffset.toFloat(), 5f..50f, "−${s.underSpeedOffset} km/h") { v -> set { it.copy(underSpeedOffset = v.toInt()) } }
            Toggle(str.overSound, null, s.overSpeedSound) { v -> set { it.copy(overSpeedSound = v) } }
        }

        Section(str.routing, Icons.Rounded.Route) {
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

        Section(str.trafficData, Icons.Rounded.Traffic) {
            Toggle(str.autobahnApi, str.autobahnApiSub, s.useAutobahnApi) { v -> set { it.copy(useAutobahnApi = v) } }
            Field(str.tomtomKey, s.tomtomKey, secret = true) { v -> set { it.copy(tomtomKey = v) } }
            Field(str.hereKey, s.hereKey, secret = true) { v -> set { it.copy(hereKey = v) } }
            Field(str.mobilithekUrl, s.mobilithekUrl) { v -> set { it.copy(mobilithekUrl = v) } }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { certPicker.launch(arrayOf("application/x-pkcs12", "application/octet-stream")) }) {
                    ButtonIcon(Icons.Rounded.UploadFile); Text(str.importCert)
                }
                Spacer(Modifier.padding(6.dp))
                Text(if (vm.mobilithekCert().exists()) str.certPresent else str.certMissing, fontSize = 13.sp)
            }
            Field(str.certPassword, s.mobilithekCertPassword, secret = true) { v -> set { it.copy(mobilithekCertPassword = v) } }
        }

        Section(str.offlineTitle, Icons.Rounded.CloudOff) {
            Text(str.offlineText, fontSize = 13.sp)
            OfflineGraphBuilder.REGIONS.forEach { r ->
                Radio(r.name, s.offlineRegion == r.id) { set { it.copy(offlineRegion = r.id) } }
            }
            Button(onClick = vm::downloadOfflineRegion, enabled = ui.offlineProgress == null) { ButtonIcon(Icons.Rounded.Download); Text(str.downloadRegion) }
            Text(ui.offlineProgress ?: if (ui.offlineGraphReady) str.offlineReady else str.offlineNone, fontSize = 13.sp)
        }

        Section(str.voice, Icons.Rounded.RecordVoiceOver) {
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
            OutlinedButton(onClick = vm::testVoice) { ButtonIcon(Icons.Rounded.PlayArrow); Text(str.voiceTest) }
            de.lanewise.nav.VoicePack.CATALOG.filter { a -> a.language == langCode && installed.none { it.id == a.id } }.forEach { a ->
                val dl = ui.voiceDownload
                Button(onClick = { vm.downloadVoicePack(a.id) }, enabled = dl == null) {
                    ButtonIcon(Icons.Rounded.Download)
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
}

/** A settings group: icon tile + title, then a rounded card (Material 3 / Android 16 settings style). */
@Composable
private fun Section(title: String, icon: ImageVector, content: @Composable () -> Unit) {
    Spacer(Modifier.height(18.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(30.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp)) }
        Spacer(Modifier.width(10.dp))
        Text(title, color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
    Spacer(Modifier.height(8.dp))
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) { content() }
}

/** Icon at the start of a button label. */
@Composable
private fun ButtonIcon(icon: ImageVector) {
    Icon(icon, null, Modifier.size(18.dp))
    Spacer(Modifier.width(8.dp))
}

/** Segmented single choice (language, theme) instead of a column of radio buttons. */
@Composable
private fun <T> Segmented(
    options: List<T>, selected: T, label: (T) -> String, icon: ((T) -> ImageVector)? = null,
    /** false: label only - four segments with icon and label overlapped on a phone (owner's screenshot). */
    showIcon: Boolean = true,
    onSelect: (T) -> Unit,
) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, o ->
            SegmentedButton(
                selected = o == selected, onClick = { onSelect(o) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
                icon = {
                    when {
                        !showIcon -> {}
                        icon != null -> Icon(icon(o), null, Modifier.size(18.dp))
                        else -> SegmentedButtonDefaults.Icon(o == selected)
                    }
                },
            ) { Text(label(o), fontSize = 12.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
        }
    }
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
