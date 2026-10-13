package de.lanewise.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.lanewise.offline.OfflineGraphBuilder
import de.lanewise.settings.AppSettings
import de.lanewise.settings.RoutingSource
import de.lanewise.settings.ThemeMode
import de.lanewise.settings.VoiceLanguage
import de.lanewise.ui.MainViewModel
import de.lanewise.ui.UiState

@Composable
fun SettingsScreen(vm: MainViewModel, s: AppSettings, ui: UiState, onBack: () -> Unit) {
    val context = LocalContext.current
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
            TextButton(onClick = onBack) { Text("‹ Zurück") }
            Text("Einstellungen", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }

        Section("Darstellung") {
            ThemeMode.entries.forEach { m ->
                Radio(label = when (m) { ThemeMode.SYSTEM -> "System"; ThemeMode.LIGHT -> "Hell"; ThemeMode.DARK -> "Dunkel" },
                    selected = s.theme == m) { set { it.copy(theme = m) } }
            }
            LabeledSlider("Kamera-Neigung (3D)", s.mapPitch, 0f..70f, "${s.mapPitch.toInt()}°") { v -> set { it.copy(mapPitch = v) } }
        }

        Section("Spurassistent") {
            Toggle("Spurwechsel so weit wie möglich vermeiden",
                "Plant die Spur über mehrere Kreuzungen voraus, damit Sie seltener wechseln müssen.", s.avoidLaneChanges) { v -> set { it.copy(avoidLaneChanges = v) } }
            Toggle("Rechtsfahrgebot auf Autobahnen", "Bei gleichwertigen Spuren die rechte bevorzugen (§ 7 StVO).", s.keepRight) { v -> set { it.copy(keepRight = v) } }
            Toggle("3D-Kreuzungsansicht", "Perspektivische Spuransicht kurz vor Abbiegungen.", s.showLaneScene) { v -> set { it.copy(showLaneScene = v) } }
            Toggle("Karte vor Kreuzungen heranzoomen", null, s.junctionZoom) { v -> set { it.copy(junctionZoom = v) } }
            LabeledSlider("Nahansicht ab", s.laneSceneDistance.toFloat(), 20f..300f, "${s.laneSceneDistance} m vor der Kreuzung") { v ->
                set { it.copy(laneSceneDistance = (v / 10).toInt() * 10) }
            }
            Toggle("Spurbänder auf der Karte", "Fahrspuren und empfohlene Spur direkt in der 3D-Karte.", s.laneRibbonsOnMap) { v -> set { it.copy(laneRibbonsOnMap = v) } }
        }

        Section("Geschwindigkeit") {
            Toggle("Warnung bei zu schnell (roter Rand)", null, s.overSpeedWarning) { v -> set { it.copy(overSpeedWarning = v) } }
            LabeledSlider("Toleranz über Limit", s.overSpeedOffset.toFloat(), 0f..25f, "+${s.overSpeedOffset} km/h") { v -> set { it.copy(overSpeedOffset = v.toInt()) } }
            Toggle("Warnung bei zu langsam", "Nur oberhalb von ${s.underSpeedMinSpeed} km/h (z. B. nicht im Stau).", s.underSpeedWarning) { v -> set { it.copy(underSpeedWarning = v) } }
            LabeledSlider("Toleranz unter Limit", s.underSpeedOffset.toFloat(), 5f..50f, "−${s.underSpeedOffset} km/h") { v -> set { it.copy(underSpeedOffset = v.toInt()) } }
            Toggle("Warnton bei zu schnell", null, s.overSpeedSound) { v -> set { it.copy(overSpeedSound = v) } }
        }

        Section("Routing") {
            RoutingSource.entries.forEach { r ->
                Radio(when (r) {
                    RoutingSource.AUTO -> "Automatisch (online → TomTom/OSRM, offline → Gerät)"
                    RoutingSource.OSRM -> "OSRM / FOSSGIS (OpenStreetMap, kostenlos)"
                    RoutingSource.TOMTOM -> "TomTom (Live-Verkehr, API-Schlüssel nötig)"
                    RoutingSource.OFFLINE -> "Nur offline (auf dem Gerät, nur GNSS)"
                }, s.routing == r) { set { it.copy(routing = r) } }
            }
            Toggle("Mautstraßen vermeiden", null, s.avoidTolls) { v -> set { it.copy(avoidTolls = v) } }
            Toggle("Autobahnen vermeiden", null, s.avoidMotorways) { v -> set { it.copy(avoidMotorways = v) } }
            Field("OSRM-Server", s.osrmUrl) { v -> set { it.copy(osrmUrl = v) } }
        }

        Section("Verkehrsdaten") {
            Toggle("Autobahn GmbH (verkehr.autobahn.de)", "Baustellen, Sperrungen, Warnungen — offiziell, ohne Schlüssel.", s.useAutobahnApi) { v -> set { it.copy(useAutobahnApi = v) } }
            Field("TomTom API-Schlüssel", s.tomtomKey, secret = true) { v -> set { it.copy(tomtomKey = v) } }
            Field("HERE API-Schlüssel", s.hereKey, secret = true) { v -> set { it.copy(hereKey = v) } }
            Field("Mobilithek DATEX-II-URL (z. B. VMZ Niedersachsen)", s.mobilithekUrl) { v -> set { it.copy(mobilithekUrl = v) } }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { certPicker.launch(arrayOf("application/x-pkcs12", "application/octet-stream")) }) { Text("Zertifikat (.p12) importieren") }
                Spacer(Modifier.padding(6.dp))
                Text(if (vm.mobilithekCert().exists()) "✓ vorhanden" else "kein Zertifikat", fontSize = 13.sp)
            }
            Field("Zertifikat-Passwort", s.mobilithekCertPassword, secret = true) { v -> set { it.copy(mobilithekCertPassword = v) } }
        }

        Section("Offline (ohne Internet, nur Satellit)") {
            Text("Lädt Straßennetz (mit Spuren & Tempolimits) und Kartenkacheln für eine Region. " +
                "Danach funktionieren Karte, Routing und Spurassistent allein mit GNSS.", fontSize = 13.sp)
            OfflineGraphBuilder.REGIONS.forEach { r ->
                Radio(r.name, s.offlineRegion == r.id) { set { it.copy(offlineRegion = r.id) } }
            }
            Button(onClick = vm::downloadOfflineRegion, enabled = ui.offlineProgress == null) { Text("Region herunterladen") }
            Text(ui.offlineProgress ?: if (ui.offlineGraphReady) "Offline-Routing bereit ✓" else "Noch keine Offline-Daten", fontSize = 13.sp)
        }

        Section("Sprachansagen") {
            Toggle("Sprachansagen", null, s.voice) { v -> set { it.copy(voice = v) } }
            VoiceLanguage.entries.forEach { l ->
                Radio(if (l == VoiceLanguage.GERMAN) "Deutsch" else "English", s.voiceLanguage == l) { set { it.copy(voiceLanguage = l) } }
            }
        }
        Spacer(Modifier.height(24.dp))
        Text("Kartendaten © OpenStreetMap-Mitwirkende · Kacheln: OpenFreeMap · Routing: FOSSGIS OSRM · " +
            "Verkehr: Die Autobahn GmbH des Bundes, TomTom, HERE, Mobilithek", fontSize = 11.sp)
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
    // Continuous slider (no tick dots); values are rounded by the callers.
    Slider(value = value, onValueChange = { onChange(kotlin.math.round(it)) }, valueRange = range)
}

@Composable
private fun Field(label: String, value: String, secret: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}
