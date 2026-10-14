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

/** AUTO = dark from sunset to sunrise at the car's position ([de.lanewise.core.geo.Sun]). */
enum class ThemeMode { AUTO, SYSTEM, LIGHT, DARK }
enum class RoutingSource { AUTO, OSRM, TOMTOM, OFFLINE }
enum class AppLanguage { GERMAN, ENGLISH, VIETNAMESE }

data class AppSettings(
    val theme: ThemeMode = ThemeMode.AUTO,
    // Lane assist
    val avoidLaneChanges: Boolean = true,
    val keepRight: Boolean = true,
    val showLaneScene: Boolean = true,
    /** AMAP-style navigation view: no junction box, the camera zooms in at decisions and out on plain stretches. */
    val immersiveView: Boolean = true,
    /** Petal style: draw the real streets around the car while navigating too; only the route highlight on top. */
    val streetsWhileDriving: Boolean = false,
    /** Show the 3D junction close-up (and zoom the map in) only this close to the junction (m). */
    val laneSceneDistance: Int = 50,
    /** Zoom the map camera in when approaching a junction. */
    val junctionZoom: Boolean = true,
    val laneRibbonsOnMap: Boolean = true,
    val showBikeLanes: Boolean = true,
    /** ARGB colour of the on-street bike lane strip (default: German red bike-lane paint). */
    val bikeLaneColor: Int = BIKE_COLORS.first(),
    val showSigns: Boolean = true,
    /** Official aerial imagery (LGLN DOP20, Niedersachsen) under the lane layers. */
    val satellite: Boolean = false,
    /** Lane counts measured from aerial photos where OSM has none or disagrees (assets/aerial_lanes.json). */
    val aerialLanes: Boolean = true,
    /** Selected OmniVoice voice packs (ids, comma separated; at most one per language). Empty = Android TTS. */
    val voicePacks: String = "",
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
    val language: AppLanguage = AppLanguage.ENGLISH,
    val offlineRegion: String = "hannover",
    val mapPitch: Float = 60f,
    /** Navigation camera zooms out on open road and in before turns; off = [fixedZoom] all the time. */
    val autoZoom: Boolean = true,
    /** Map zoom level while navigating with [autoZoom] off (MapLibre zoom; 17.5 ≈ 300 m ahead visible). */
    val fixedZoom: Float = 17.5f,
    /** 3D car instead of the flat arrow puck. */
    val car3d: Boolean = true,
    /** Size of the car / arrow on the map (× default). */
    val carScale: Float = 1f,
)

/** Selectable bike-lane colours: red, orange, blue, green, magenta. */
val BIKE_COLORS = listOf(0xFFE5484D.toInt(), 0xFFF2A93B.toInt(), 0xFF2F80ED.toInt(), 0xFF2EB67D.toInt(), 0xFFD63AF9.toInt())

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {

    private object K {
        val theme = stringPreferencesKey("theme")
        val avoid = booleanPreferencesKey("avoid_lane_changes")
        val keepRight = booleanPreferencesKey("keep_right")
        val laneScene = booleanPreferencesKey("lane_scene")
        val laneSceneDist = intPreferencesKey("lane_scene_distance")
        val junctionZoom = booleanPreferencesKey("junction_zoom")
        val autoZoom = booleanPreferencesKey("auto_zoom")
        val fixedZoom = floatPreferencesKey("fixed_zoom")
        val car3d = booleanPreferencesKey("car_3d")
        val carScale = floatPreferencesKey("car_scale")
        val ribbons = booleanPreferencesKey("lane_ribbons")
        val bikes = booleanPreferencesKey("bike_lanes")
        val bikeColor = intPreferencesKey("bike_lane_color")
        val signs = booleanPreferencesKey("signs")
        val satellite = booleanPreferencesKey("satellite")
        val immersive = booleanPreferencesKey("immersive_view")
        val streetsDriving = booleanPreferencesKey("streets_while_driving")
        val aerialLanes = booleanPreferencesKey("aerial_lanes")
        val voicePacks = stringPreferencesKey("voice_packs")
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
            immersiveView = p[K.immersive] ?: d.immersiveView,
            streetsWhileDriving = p[K.streetsDriving] ?: d.streetsWhileDriving,
            laneSceneDistance = p[K.laneSceneDist] ?: d.laneSceneDistance,
            junctionZoom = p[K.junctionZoom] ?: d.junctionZoom,
            laneRibbonsOnMap = p[K.ribbons] ?: d.laneRibbonsOnMap,
            showBikeLanes = p[K.bikes] ?: d.showBikeLanes,
            bikeLaneColor = p[K.bikeColor] ?: d.bikeLaneColor,
            showSigns = p[K.signs] ?: d.showSigns,
            satellite = p[K.satellite] ?: d.satellite,
            aerialLanes = p[K.aerialLanes] ?: d.aerialLanes,
            voicePacks = p[K.voicePacks] ?: d.voicePacks,
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
            language = p[K.voiceLang]?.let { runCatching { AppLanguage.valueOf(it) }.getOrNull() } ?: d.language,
            offlineRegion = p[K.region] ?: d.offlineRegion,
            mapPitch = p[K.pitch] ?: d.mapPitch,
            autoZoom = p[K.autoZoom] ?: d.autoZoom,
            fixedZoom = p[K.fixedZoom] ?: d.fixedZoom,
            car3d = p[K.car3d] ?: d.car3d,
            carScale = p[K.carScale] ?: d.carScale,
        )
    }

    suspend fun update(transform: (AppSettings) -> AppSettings, current: AppSettings) {
        val s = transform(current)
        context.dataStore.edit { p ->
            p[K.theme] = s.theme.name
            p[K.avoid] = s.avoidLaneChanges
            p[K.keepRight] = s.keepRight
            p[K.laneScene] = s.showLaneScene
            p[K.immersive] = s.immersiveView
            p[K.streetsDriving] = s.streetsWhileDriving
            p[K.laneSceneDist] = s.laneSceneDistance
            p[K.junctionZoom] = s.junctionZoom
            p[K.ribbons] = s.laneRibbonsOnMap
            p[K.bikes] = s.showBikeLanes
            p[K.bikeColor] = s.bikeLaneColor
            p[K.signs] = s.showSigns
            p[K.satellite] = s.satellite
            p[K.aerialLanes] = s.aerialLanes
            p[K.voicePacks] = s.voicePacks
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
            p[K.voiceLang] = s.language.name
            p[K.region] = s.offlineRegion
            p[K.pitch] = s.mapPitch
            p[K.autoZoom] = s.autoZoom
            p[K.fixedZoom] = s.fixedZoom
            p[K.car3d] = s.car3d
            p[K.carScale] = s.carScale
        }
    }
}
