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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
                Radio(when (m) { ThemeMode.SYSTEM -> str.themeSystem; ThemeMode.LIGHT -> str.themeLight; ThemeMode.DARK -> str.themeDark },
                    s.theme == m) { set { it.copy(theme = m) } }
            }
            Toggle(str.satellite, str.satelliteSub, s.satellite) { v -> set { it.copy(satellite = v) } }
            LabeledSlider(str.cameraPitch, s.mapPitch, 0f..70f, "${s.mapPitch.toInt()}°") { v -> set { it.copy(mapPitch = v) } }
        }

        Section(str.laneAssist) {
            Toggle(str.avoidChanges, str.avoidChangesSub, s.avoidLaneChanges) { v -> set { it.copy(avoidLaneChanges = v) } }
            Toggle(str.keepRight, str.keepRightSub, s.keepRight) { v -> set { it.copy(keepRight = v) } }
            Toggle(str.laneScene, str.laneSceneSub, s.showLaneScene) { v -> set { it.copy(showLaneScene = v) } }
            Toggle(str.junctionZoom, null, s.junctionZoom) { v -> set { it.copy(junctionZoom = v) } }
            LabeledSlider(str.closeUpFrom, s.laneSceneDistance.toFloat(), 20f..300f, "${s.laneSceneDistance} ${str.beforeJunction}") { v ->
                set { it.copy(laneSceneDistance = (v / 10).toInt() * 10) }
            }
            Toggle(str.laneRibbons, str.laneRibbonsSub, s.laneRibbonsOnMap) { v -> set { it.copy(laneRibbonsOnMap = v) } }
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
        }
        Spacer(Modifier.height(24.dp))
        Text(str.attribution, fontSize = 11.sp)
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
private fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, valueText: String, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = 14.sp)
        Text(valueText, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
    // Continuous slider (no tick dots); values are rounded here.
    Slider(value = value, onValueChange = { onChange(kotlin.math.round(it)) }, valueRange = range)
}

@Composable
private fun Field(label: String, value: String, secret: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}
