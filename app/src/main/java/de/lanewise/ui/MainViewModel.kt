package de.lanewise.ui

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.lanewise.core.geo.LatLon
import de.lanewise.core.model.Route
import de.lanewise.core.model.TrafficIncident
import de.lanewise.lane.LaneGuidance
import de.lanewise.location.Fix
import de.lanewise.location.GnssInfo
import de.lanewise.location.GnssLocationSource
import de.lanewise.location.SimulatedLocationSource
import de.lanewise.nav.NavSnapshot
import de.lanewise.nav.NavigationEngine
import de.lanewise.nav.Phrases
import de.lanewise.nav.SpeedStatus
import de.lanewise.nav.VoiceGuide
import de.lanewise.offline.OfflineGraph
import de.lanewise.offline.OfflineGraphBuilder
import de.lanewise.offline.OfflineRouter
import de.lanewise.offline.OfflineTiles
import de.lanewise.routing.OsrmRoutingProvider
import de.lanewise.routing.RouteRequest
import de.lanewise.routing.RouteScorer
import de.lanewise.routing.RoutingProvider
import de.lanewise.routing.TomTomRoutingProvider
import de.lanewise.search.Geocoder
import de.lanewise.search.Place
import de.lanewise.settings.AppSettings
import de.lanewise.settings.RoutingSource
import de.lanewise.settings.SettingsRepository
import de.lanewise.speed.SpeedLimitProvider
import de.lanewise.traffic.AutobahnApiSource
import de.lanewise.traffic.BBox
import de.lanewise.traffic.HereTrafficSource
import de.lanewise.traffic.MobilithekDatexSource
import de.lanewise.traffic.TomTomTrafficSource
import de.lanewise.traffic.TrafficService
import de.lanewise.traffic.TrafficSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class Mode { BROWSE, PREVIEW, NAVIGATING }

data class UiState(
    val mode: Mode = Mode.BROWSE,
    val query: String = "",
    val results: List<Place> = emptyList(),
    val searching: Boolean = false,
    val destination: Place? = null,
    val routes: List<Route> = emptyList(),
    val selected: Int = 0,
    val routing: Boolean = false,
    val nav: NavSnapshot? = null,
    val fix: Fix? = null,
    val gnss: GnssInfo = GnssInfo(),
    val online: Boolean = true,
    val simulating: Boolean = false,
    val message: String? = null,
    val incidents: List<TrafficIncident> = emptyList(),
    val offlineGraphReady: Boolean = false,
    val offlineProgress: String? = null,
    val laneHint: String? = null,
) {
    val selectedRoute get() = routes.getOrNull(selected)
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx: Context get() = getApplication()
    private val settingsRepo = SettingsRepository(app)
    val settings: StateFlow<AppSettings> = settingsRepo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val gnss = GnssLocationSource(app)
    private val geocoder = Geocoder()
    private val speedLimits = SpeedLimitProvider()
    private val voice = VoiceGuide(app)
    private var graph: OfflineGraph? = null
    private val offlineRouter = OfflineRouter { graph }

    private var locationJob: Job? = null
    private var navJob: Job? = null
    private var searchJob: Job? = null
    private var trafficJob: Job? = null
    private var engine: NavigationEngine? = null
    private var lastReroute = 0L
    private var speedFetchedUntil = 0.0
    private val laneGuidance = LaneGuidance()
    private var currentRoute: Route? = null

    init {
        viewModelScope.launch { loadGraph() }
        viewModelScope.launch {
            while (isActive) { _ui.update { it.copy(online = isOnline()) }; delay(10_000) }
        }
    }

    // --- settings -------------------------------------------------------------------------------
    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { settingsRepo.update(transform, settings.value) }
    }

    // --- location -------------------------------------------------------------------------------
    fun onLocationPermissionGranted() {
        if (locationJob != null) return
        locationJob = viewModelScope.launch {
            launch { gnss.gnss().collect { info -> _ui.update { it.copy(gnss = info) } } }
            gnss.fixes().collect { fix -> if (!_ui.value.simulating) onFix(fix) }
        }
    }

    private fun onFix(fix: Fix) {
        _ui.update { it.copy(fix = fix) }
        val e = engine ?: return
        if (_ui.value.mode != Mode.NAVIGATING) return
        val s = settings.value
        voice.setLanguage(s.voiceLanguage)
        val (snap, ann) = e.update(fix, s, Phrases(s.voiceLanguage))
        val phrases = Phrases(s.voiceLanguage)
        val hint = snap.lanes.pendingChange?.takeIf { snap.distanceAlong >= it.startAt - 150 }
            ?.let { phrases.laneChange(it, it.site.laneCount) }
        _ui.update { it.copy(nav = snap, laneHint = hint) }
        if (s.voice) ann.sortedByDescending { it.priority }.forEach { voice.speak(it) }
        if (s.overSpeedSound && snap.speedStatus == SpeedStatus.OVER && (fix.timeMs / 1000) % 5 == 0L) voice.beep()
        if (snap.arrived) { stopNavigation(); _ui.update { it.copy(message = phrases.instruction(snap.route.steps.last())) }; return }
        if (snap.offRoute && !fix.simulated) reroute(fix)
        if (snap.route.provider == "osrm" && snap.distanceAlong > speedFetchedUntil - 8_000) fetchSpeedLimits(snap.route, snap.distanceAlong)
    }

    // --- search ---------------------------------------------------------------------------------
    fun onQuery(q: String) {
        _ui.update { it.copy(query = q) }
        searchJob?.cancel()
        if (q.length < 3) { _ui.update { it.copy(results = emptyList(), searching = false) }; return }
        searchJob = viewModelScope.launch {
            delay(350)
            _ui.update { it.copy(searching = true) }
            val res = runCatching { geocoder.search(q, _ui.value.fix?.position) }
            _ui.update { it.copy(results = res.getOrDefault(emptyList()), searching = false,
                message = res.exceptionOrNull()?.let { e -> "Suche fehlgeschlagen: ${e.message}" }) }
        }
    }

    fun chooseDestination(place: Place) {
        _ui.update { it.copy(destination = place, results = emptyList(), query = place.title, mode = Mode.PREVIEW) }
        computeRoutes()
    }

    fun chooseDestination(p: LatLon) = chooseDestination(Place("Markierter Ort", String.format("%.5f, %.5f", p.lat, p.lon), p))

    fun selectRoute(i: Int) = _ui.update { it.copy(selected = i) }

    fun cancelPreview() = _ui.update { it.copy(mode = Mode.BROWSE, routes = emptyList(), destination = null, query = "") }

    fun clearMessage() = _ui.update { it.copy(message = null) }

    // --- routing --------------------------------------------------------------------------------
    private fun providers(s: AppSettings, online: Boolean): List<RoutingProvider> {
        val osrm = OsrmRoutingProvider(s.osrmUrl)
        val tomtom = TomTomRoutingProvider(s.tomtomKey)
        return when (s.routing) {
            RoutingSource.OFFLINE -> listOf(offlineRouter)
            RoutingSource.OSRM -> listOf(osrm, offlineRouter)
            RoutingSource.TOMTOM -> listOf(tomtom, osrm, offlineRouter)
            RoutingSource.AUTO -> when {
                !online -> listOf(offlineRouter)
                s.tomtomKey.isNotBlank() -> listOf(tomtom, osrm, offlineRouter)
                else -> listOf(osrm, offlineRouter)
            }
        }
    }

    private fun trafficSources(s: AppSettings): List<TrafficSource> = buildList {
        if (s.useAutobahnApi) add(AutobahnApiSource())
        if (s.tomtomKey.isNotBlank()) add(TomTomTrafficSource(s.tomtomKey))
        if (s.hereKey.isNotBlank()) add(HereTrafficSource(s.hereKey))
        if (s.mobilithekUrl.isNotBlank()) add(MobilithekDatexSource(s.mobilithekUrl, mobilithekCert(), s.mobilithekCertPassword))
    }

    fun mobilithekCert(): File = File(ctx.filesDir, "mobilithek.p12")

    fun computeRoutes(origin: LatLon? = null, heading: Double? = null) {
        val dest = _ui.value.destination ?: return
        val from = origin ?: _ui.value.fix?.position ?: run {
            _ui.update { it.copy(message = "Warte auf GPS-Position …") }; return
        }
        val s = settings.value
        viewModelScope.launch {
            _ui.update { it.copy(routing = true) }
            val online = isOnline()
            val req = RouteRequest(from, dest.position, heading, alternatives = true, avoidTolls = s.avoidTolls, avoidMotorways = s.avoidMotorways)
            var routes: List<Route> = emptyList()
            var lastError: String? = null
            for (p in providers(s, online)) {
                if (p.requiresNetwork && !online) continue
                val r = runCatching { p.route(req) }
                if (r.isSuccess && r.getOrThrow().isNotEmpty()) { routes = r.getOrThrow(); break }
                lastError = r.exceptionOrNull()?.message
            }
            if (routes.isEmpty()) {
                _ui.update { it.copy(routing = false, message = lastError ?: "Keine Route gefunden") }
                return@launch
            }
            // Traffic-aware ranking with official German data.
            val incidents = if (online) runCatching {
                TrafficService { trafficSources(s) }.incidents(
                    BBox.around(routes.flatMap { listOf(it.geometry.points.first(), it.geometry.points.last()) } +
                        routes.flatMap { r -> r.geometry.points.filterIndexed { i, _ -> i % 50 == 0 } }),
                    routes.flatMap { r -> r.steps.mapNotNull { it.ref } }.flatMap { it.split(";", " ") }.toSet(),
                )
            }.getOrDefault(emptyList()) else emptyList()
            val ranked = withContext(Dispatchers.Default) { RouteScorer.score(routes, incidents, s.avoidLaneChanges) }
            _ui.update { it.copy(routes = ranked, selected = 0, routing = false, incidents = incidents) }
        }
    }

    private fun fetchSpeedLimits(route: Route, from: Double) {
        val to = minOf(route.distance, from + 40_000)
        speedFetchedUntil = to
        viewModelScope.launch {
            val spans = runCatching { speedLimits.limitsFor(route.geometry, from, to) }.getOrNull() ?: return@launch
            val merged = (route.speedLimits.filter { it.to <= from } + spans).sortedBy { it.from }
            val updated = route.copy(speedLimits = merged)
            engine = NavigationEngine(updated, laneGuidance).also { it.rehydrate(engine) }
            _ui.update { st -> st.copy(routes = st.routes.map { if (it === route) updated else it }) }
            currentRoute = updated
        }
    }

    // --- navigation -----------------------------------------------------------------------------

    fun startNavigation(simulate: Boolean) {
        val route = _ui.value.selectedRoute ?: return
        laneGuidance.reset()
        currentRoute = route
        engine = NavigationEngine(route, laneGuidance)
        speedFetchedUntil = 0.0
        _ui.update { it.copy(mode = Mode.NAVIGATING, simulating = simulate, routes = listOf(route), selected = 0) }
        if (route.provider == "osrm" && isOnline()) fetchSpeedLimits(route, 0.0)
        navJob?.cancel()
        if (simulate) {
            navJob = viewModelScope.launch {
                SimulatedLocationSource(route.geometry, { d -> (currentRoute?.speedLimitAt(d)?.kmh ?: 50).toDouble() }).fixes()
                    .collect { onFix(it) }
            }
        } else {
            onLocationPermissionGranted()
        }
        trafficJob?.cancel()
        trafficJob = viewModelScope.launch {
            while (isActive) {
                delay(5 * 60_000L)
                val r = currentRoute ?: break
                if (!isOnline()) continue
                val inc = runCatching { TrafficService { trafficSources(settings.value) }.incidents(BBox.around(r.geometry.points)) }
                    .getOrDefault(emptyList())
                _ui.update { it.copy(incidents = RouteScorer.score(listOf(r), inc, false).first().incidents) }
            }
        }
    }

    fun stopNavigation() {
        navJob?.cancel(); trafficJob?.cancel()
        engine = null; currentRoute = null
        _ui.update { it.copy(mode = Mode.BROWSE, nav = null, simulating = false, routes = emptyList(), destination = null, laneHint = null) }
    }

    fun overrideLane(index: Int) {
        val advice = _ui.value.nav?.lanes?.nextAdvice ?: return
        laneGuidance.overrideCurrentLane(advice.site, index)
    }

    private fun reroute(fix: Fix) {
        val now = System.currentTimeMillis()
        if (now - lastReroute < 10_000) return
        lastReroute = now
        val s = settings.value
        if (s.voice) voice.speak(de.lanewise.nav.Announcement("reroute-$now", Phrases(s.voiceLanguage).rerouting(), 2))
        val dest = _ui.value.destination ?: return
        viewModelScope.launch {
            val online = isOnline()
            val req = RouteRequest(fix.position, dest.position, fix.bearing?.toDouble(), alternatives = false,
                avoidTolls = s.avoidTolls, avoidMotorways = s.avoidMotorways)
            for (p in providers(s, online)) {
                if (p.requiresNetwork && !online) continue
                val r = runCatching { p.route(req) }.getOrNull()?.firstOrNull() ?: continue
                laneGuidance.reset()
                currentRoute = r
                engine = NavigationEngine(r, laneGuidance)
                speedFetchedUntil = 0.0
                if (r.provider == "osrm" && online) fetchSpeedLimits(r, 0.0)
                _ui.update { it.copy(routes = listOf(r), selected = 0) }
                break
            }
        }
    }

    // --- offline data ---------------------------------------------------------------------------
    private fun graphFile(region: String) = File(ctx.filesDir, "graph_$region.lwg")

    private suspend fun loadGraph() {
        val f = graphFile(settings.value.offlineRegion)
        if (!f.exists()) return
        graph = withContext(Dispatchers.IO) { runCatching { OfflineGraph.load(f) }.getOrNull() }
        _ui.update { it.copy(offlineGraphReady = graph != null) }
    }

    fun downloadOfflineRegion() {
        val s = settings.value
        val region = OfflineGraphBuilder.REGIONS.firstOrNull { it.id == s.offlineRegion } ?: OfflineGraphBuilder.REGIONS.first()
        viewModelScope.launch {
            try {
                _ui.update { it.copy(offlineProgress = "Starte Download …") }
                graph = OfflineGraphBuilder().build(region, graphFile(region.id)) { msg -> _ui.update { it.copy(offlineProgress = msg) } }
                _ui.update { it.copy(offlineGraphReady = true, offlineProgress = "Lade Kartenkacheln …") }
                for (dark in listOf(false, true)) {
                    OfflineTiles(ctx).download(region, dark) { pct ->
                        if (pct >= 0) _ui.update { it.copy(offlineProgress = "Kartenkacheln ${if (dark) "(dunkel)" else "(hell)"}: $pct %") }
                    }
                }
                _ui.update { it.copy(offlineProgress = null, message = "Offline-Region „${region.name}“ bereit") }
            } catch (e: Exception) {
                _ui.update { it.copy(offlineProgress = null, message = "Offline-Download fehlgeschlagen: ${e.message}") }
            }
        }
    }

    private fun isOnline(): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    override fun onCleared() {
        voice.shutdown()
        super.onCleared()
    }
}
