package de.lanewise.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import de.lanewise.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class RoutingSource { AUTO, OSRM, TOMTOM, OFFLINE }
enum class VoiceLanguage { GERMAN, ENGLISH }

data class AppSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    // Lane assist
    val avoidLaneChanges: Boolean = true,
    val keepRight: Boolean = true,
    val showLaneScene: Boolean = true,
    val laneRibbonsOnMap: Boolean = true,
    // Speed warnings
    val overSpeedWarning: Boolean = true,
    /** Tolerance above the limit before the red border shows (km/h). */
    val overSpeedOffset: Int = 5,
    val underSpeedWarning: Boolean = false,
    /** How far below the limit counts as "too slow" (km/h); only above [underSpeedMinSpeed]. */
    val underSpeedOffset: Int = 20,
    val underSpeedMinSpeed: Int = 15,
    val overSpeedSound: Boolean = false,
    // Routing & data
    val routing: RoutingSource = RoutingSource.AUTO,
    val osrmUrl: String = "https://routing.openstreetmap.de/routed-car",
    val tomtomKey: String = BuildConfig.TOMTOM_API_KEY,
    val hereKey: String = BuildConfig.HERE_API_KEY,
    val useAutobahnApi: Boolean = true,
    val mobilithekUrl: String = "",
    val mobilithekCertPassword: String = "",
    val avoidTolls: Boolean = false,
    val avoidMotorways: Boolean = false,
    // Guidance
    val voice: Boolean = true,
    val voiceLanguage: VoiceLanguage = VoiceLanguage.GERMAN,
    val offlineRegion: String = "hannover",
    val mapPitch: Float = 60f,
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {

    private object K {
        val theme = stringPreferencesKey("theme")
        val avoid = booleanPreferencesKey("avoid_lane_changes")
        val keepRight = booleanPreferencesKey("keep_right")
        val laneScene = booleanPreferencesKey("lane_scene")
        val ribbons = booleanPreferencesKey("lane_ribbons")
        val over = booleanPreferencesKey("over_speed")
        val overOff = intPreferencesKey("over_speed_offset")
        val under = booleanPreferencesKey("under_speed")
        val underOff = intPreferencesKey("under_speed_offset")
        val underMin = intPreferencesKey("under_speed_min")
        val overSound = booleanPreferencesKey("over_speed_sound")
        val routing = stringPreferencesKey("routing")
        val osrm = stringPreferencesKey("osrm_url")
        val tomtom = stringPreferencesKey("tomtom_key")
        val here = stringPreferencesKey("here_key")
        val autobahn = booleanPreferencesKey("autobahn_api")
        val mobiUrl = stringPreferencesKey("mobilithek_url")
        val mobiPw = stringPreferencesKey("mobilithek_pw")
        val tolls = booleanPreferencesKey("avoid_tolls")
        val motorways = booleanPreferencesKey("avoid_motorways")
        val voice = booleanPreferencesKey("voice")
        val voiceLang = stringPreferencesKey("voice_lang")
        val region = stringPreferencesKey("offline_region")
        val pitch = floatPreferencesKey("map_pitch")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        val d = AppSettings()
        AppSettings(
            theme = p[K.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: d.theme,
            avoidLaneChanges = p[K.avoid] ?: d.avoidLaneChanges,
            keepRight = p[K.keepRight] ?: d.keepRight,
            showLaneScene = p[K.laneScene] ?: d.showLaneScene,
            laneRibbonsOnMap = p[K.ribbons] ?: d.laneRibbonsOnMap,
            overSpeedWarning = p[K.over] ?: d.overSpeedWarning,
            overSpeedOffset = p[K.overOff] ?: d.overSpeedOffset,
            underSpeedWarning = p[K.under] ?: d.underSpeedWarning,
            underSpeedOffset = p[K.underOff] ?: d.underSpeedOffset,
            underSpeedMinSpeed = p[K.underMin] ?: d.underSpeedMinSpeed,
            overSpeedSound = p[K.overSound] ?: d.overSpeedSound,
            routing = p[K.routing]?.let { runCatching { RoutingSource.valueOf(it) }.getOrNull() } ?: d.routing,
            osrmUrl = p[K.osrm]?.takeIf { it.isNotBlank() } ?: d.osrmUrl,
            tomtomKey = p[K.tomtom]?.takeIf { it.isNotBlank() } ?: d.tomtomKey,
            hereKey = p[K.here]?.takeIf { it.isNotBlank() } ?: d.hereKey,
            useAutobahnApi = p[K.autobahn] ?: d.useAutobahnApi,
            mobilithekUrl = p[K.mobiUrl] ?: d.mobilithekUrl,
            mobilithekCertPassword = p[K.mobiPw] ?: d.mobilithekCertPassword,
            avoidTolls = p[K.tolls] ?: d.avoidTolls,
            avoidMotorways = p[K.motorways] ?: d.avoidMotorways,
            voice = p[K.voice] ?: d.voice,
            voiceLanguage = p[K.voiceLang]?.let { runCatching { VoiceLanguage.valueOf(it) }.getOrNull() } ?: d.voiceLanguage,
            offlineRegion = p[K.region] ?: d.offlineRegion,
            mapPitch = p[K.pitch] ?: d.mapPitch,
        )
    }

    suspend fun update(transform: (AppSettings) -> AppSettings, current: AppSettings) {
        val s = transform(current)
        context.dataStore.edit { p ->
            p[K.theme] = s.theme.name
            p[K.avoid] = s.avoidLaneChanges
            p[K.keepRight] = s.keepRight
            p[K.laneScene] = s.showLaneScene
            p[K.ribbons] = s.laneRibbonsOnMap
            p[K.over] = s.overSpeedWarning
            p[K.overOff] = s.overSpeedOffset
            p[K.under] = s.underSpeedWarning
            p[K.underOff] = s.underSpeedOffset
            p[K.underMin] = s.underSpeedMinSpeed
            p[K.overSound] = s.overSpeedSound
            p[K.routing] = s.routing.name
            p[K.osrm] = s.osrmUrl
            p[K.tomtom] = s.tomtomKey
            p[K.here] = s.hereKey
            p[K.autobahn] = s.useAutobahnApi
            p[K.mobiUrl] = s.mobilithekUrl
            p[K.mobiPw] = s.mobilithekCertPassword
            p[K.tolls] = s.avoidTolls
            p[K.motorways] = s.avoidMotorways
            p[K.voice] = s.voice
            p[K.voiceLang] = s.voiceLanguage.name
            p[K.region] = s.offlineRegion
            p[K.pitch] = s.mapPitch
        }
    }
}
