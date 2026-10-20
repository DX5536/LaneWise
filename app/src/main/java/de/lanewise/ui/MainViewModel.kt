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
import de.lanewise.core.net.Http
import de.lanewise.routing.OsrmRoutingProvider
import de.lanewise.routing.RouteRequest
import de.lanewise.routing.RouteScorer
import de.lanewise.routing.RoutingProvider
import de.lanewise.routing.TomTomRoutingProvider
import de.lanewise.search.Geocoder
import de.lanewise.search.Place
import de.lanewise.settings.AppSettings
import de.lanewise.settings.PlaceKind
import de.lanewise.settings.PlacesRepository
import de.lanewise.settings.SavedPlace
import de.lanewise.ui.i18n.Strings
import de.lanewise.core.geo.Geo
import de.lanewise.osm.AerialLanes
import de.lanewise.osm.LaneOverrides
import de.lanewise.osm.OsmTags
import de.lanewise.settings.RoutingSource
import de.lanewise.settings.SettingsRepository
import de.lanewise.speed.SpeedLimitProvider
import de.lanewise.traffic.AutobahnApiSource
import de.lanewise.traffic.BBox
import de.lanewise.traffic.HereTrafficSource
import de.lanewise.traffic.MobilithekClient
import de.lanewise.traffic.TomTomTrafficSource
import de.lanewise.traffic.TrafficService
import de.lanewise.traffic.TrafficSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
    /** Demo playback speed (×1 = real driving time). */
    val demoSpeed: Double = 1.0,
    val demoPaused: Boolean = false,
    val message: String? = null,
    val incidents: List<TrafficIncident> = emptyList(),
    val offlineGraphReady: Boolean = false,
    val offlineProgress: String? = null,
    /** Installed OmniVoice packs and the download in progress (pack id to percent). */
    val voicePacks: List<de.lanewise.nav.VoicePack> = emptyList(),
    val voiceDownload: Pair<String, Int>? = null,
    val laneHint: String? = null,
    /** Countdown of the lane-change hint: 1 when it appears, 0 at the last point the change can be made. */
    val laneHintCountdown: Float? = null,
    /** Wall-clock start of the current navigation (ms), kept across re-routes: the drive timer counts from here. */
    val navStartedAt: Long = 0L,
    /** Speed limit of the current road while not navigating (km/h, 0 = no limit, null = unknown). */
    val browseLimit: Int? = null,
    val places: List<SavedPlace> = emptyList(),
    /** Streets around the car with lane data, shown while not navigating. */
    val nearbyStreets: List<de.lanewise.osm.OsmStreet> = emptyList(),
    /** Start of the route when not the current position (route preview "From"); null = GPS position. */
    val origin: Place? = null,
    /** Stops on the way, in driving order (route preview "Add stop"). */
    val stops: List<Place> = emptyList(),
    /** Add-stop search: places (plain search) or candidates along the route (smart), and whether it is running. */
    val stopPlaces: List<Place> = emptyList(),
    val stopCandidates: List<de.lanewise.search.StopCandidate> = emptyList(),
    val stopSearching: Boolean? = null,
    /** Load of the search / routing servers (dot in "Add stop"); null = not checked yet. */
    val serverHealth: de.lanewise.core.net.Health? = null,
    /** Metres driven on earlier routes of this journey (re-routes), so the journey progress bar keeps counting. */
    val journeyDriven: Double = 0.0,
    val originResults: List<Place> = emptyList(),
    /** Start search: null = idle, true = running, false = done (empty results → "no results"). */
    val originSearching: Boolean? = null,
    /** Traffic lights / signs around the car for the browse map (a route brings its own). */
    val nearbyFeatures: List<de.lanewise.core.model.RoadFeature> = emptyList(),
    /** Autobahn GmbH rest/parking areas, chargers, webcams (kinds enabled in Settings). */
    val autobahnPois: List<de.lanewise.core.model.AutobahnPoi> = emptyList(),
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
    private val offlineRouter = OfflineRouter({ graph }, { strings })
    private val placesRepo = PlacesRepository(app)
    private val strings: Strings get() = Strings.of(settings.value.language)

    private var locationJob: Job? = null
    private var navJob: Job? = null
    private var searchJob: Job? = null
    private var trafficJob: Job? = null
    private var engine: NavigationEngine? = null
    private var lastReroute = 0L
    private var rerouteJob: Job? = null
    /** "Recalculating" was spoken for the current off-route episode (not again every 10 s while offline). */
    private var rerouteAnnounced = false
    private var speedFetchedUntil = 0.0
    private var browseLimitJob: Job? = null
    private var browseLimitAt: Fix? = null
    private var streetsCenter: LatLon? = null
    private var streetsJob: Job? = null
    private val laneGuidance = LaneGuidance()
    private var currentRoute: Route? = null

    init {
        // The offline map of the selected region - once the saved settings are read (at start `settings.value` is
        // still the default region, so a downloaded "Hannover (Stadt)" was never loaded after a restart) and again
        // whenever the region changes.
        viewModelScope.launch { settings.map { it.offlineRegion }.distinctUntilChanged().collect { loadGraph(it) } }
        viewModelScope.launch { placesRepo.places.collect { p -> _ui.update { it.copy(places = p) } } }
        viewModelScope.launch { refreshVoicePacks() }
        viewModelScope.launch { settings.collect { s -> voice.setPacks(selectedPacks(s)) } }
        viewModelScope.launch {
            while (isActive) { _ui.update { it.copy(online = isOnline()) }; delay(10_000) }
        }
        watchNetwork()
    }

    /**
     * Drive started on Wi-Fi, then no internet, then mobile data (owner's test drive 2026-10-18): the reroute never came
     * back. On every network change the HTTP pool is dropped (its connections belonged to the Wi-Fi) and, while off the
     * route, a reroute starts at once instead of waiting for the next fix and the 10 s throttle.
     */
    private fun watchNetwork() {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        val cb = object : ConnectivityManager.NetworkCallback() {
            private var validated: android.net.Network? = null
            override fun onCapabilitiesChanged(network: android.net.Network, caps: NetworkCapabilities) {
                val ok = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                if (!ok || network == validated) return
                validated = network
                main.post { onNetworkBack() }
            }
            override fun onLost(network: android.net.Network) {
                if (network == validated) validated = null
                main.post { Http.onNetworkChanged(); _ui.update { it.copy(online = isOnline()) } }
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(cb) }
        addCloseable { runCatching { cm.unregisterNetworkCallback(cb) } }
    }

    private fun onNetworkBack() {
        Http.onNetworkChanged()
        _ui.update { it.copy(online = true) }
        val st = _ui.value
        val fix = st.fix ?: return
        if (st.mode == Mode.NAVIGATING && !st.simulating && st.nav?.offRoute == true) reroute(fix, force = true)
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
        if (_ui.value.mode != Mode.NAVIGATING) { updateBrowseLimit(fix); updateNearbyStreets(fix.position); return }
        if (settings.value.streetsWhileDriving) updateNearbyStreets(fix.position)
        val e = engine ?: return
        val s = settings.value
        voice.setLanguage(s.language)
        val (snap, ann) = e.update(fix, s, Phrases(s.language))
        val phrases = Phrases(s.language)
        val pending = snap.lanes.pendingChange?.takeIf { snap.distanceAlong >= it.startAt - 150 }
        val hint = pending?.let { phrases.laneChange(it, it.site.laneCount) }
        val countdown = pending?.let {
            val from = it.startAt - 150
            ((it.deadline - snap.distanceAlong) / (it.deadline - from).coerceAtLeast(1.0)).toFloat().coerceIn(0f, 1f)
        }
        _ui.update { it.copy(nav = snap, laneHint = hint, laneHintCountdown = countdown) }
        updateNavNotification(snap, phrases)
        if (s.voice) ann.sortedByDescending { it.priority }.forEach { voice.speak(it) }
        if (s.overSpeedSound && snap.speedStatus == SpeedStatus.OVER && (fix.timeMs / 1000) % 5 == 0L) voice.beep()
        if (snap.arrived) { stopNavigation(); _ui.update { it.copy(message = phrases.instruction(snap.route.steps.last())) }; return }
        if (snap.offRoute && (!fix.simulated || demoDrivePath != null)) reroute(fix)
        // Next stretch of OSM data 8 km before the fetched one ends - only if there is more route to fetch, one fetch at
        // a time and not again within 30 s of a failure. Short routes (< 8 km) were refetched on every fix (4 / s),
        // and each answer replaced the route: the lanes shifted and broke constantly (owner's video).
        if (snap.route.provider != "offline" && snap.distanceAlong > speedFetchedUntil - 8_000 &&
            speedFetchedUntil < snap.route.distance - 1 && corridorJob?.isActive != true &&
            System.currentTimeMillis() - corridorFailedAt > 30_000) fetchCorridor(snap.route, snap.distanceAlong)
    }

    private var aerial: AerialLanes? = null

    /** Lane counts from aerial photos (assets/aerial_lanes.json, built by tools/aerial_lanes); loaded once. */
    suspend fun aerialLanes(): AerialLanes = aerial ?: withContext(Dispatchers.IO) {
        runCatching {
            ctx.assets.open("aerial_lanes.json").bufferedReader().use { AerialLanes.parse(it.readText()) }
        }.getOrDefault(AerialLanes.EMPTY)
    }.also { aerial = it }

    /**
     * Hand-checked lane patches: `assets/lane_overrides.json` with `lane_overrides.json` next to `lane_reports.geojson`
     * on top (patch a junction on the phone right away: `adb push`, then restart). Rejected entries go to logcat.
     */
    suspend fun laneOverrides(): LaneOverrides = overrides ?: withContext(Dispatchers.IO) {
        fun load(text: () -> String, name: String) = runCatching { LaneOverrides.parse(text()) }
            .onFailure { if (it !is java.io.FileNotFoundException) android.util.Log.w("LaneWise", "$name: $it") }
            .getOrDefault(LaneOverrides.EMPTY)
        val asset = load({ ctx.assets.open("lane_overrides.json").bufferedReader().use { it.readText() } }, "assets/lane_overrides.json")
        val local = laneOverrideFile().let { f -> if (f.exists()) load({ f.readText() }, f.path) else LaneOverrides.EMPTY }
        LaneOverrides.merge(asset, local).also { o ->
            o.rejected.forEach { android.util.Log.w("LaneWise", "lane override rejected: $it") }
            speedLimits.overrides = o
        }
    }.also { overrides = it }
    private var overrides: LaneOverrides? = null

    fun laneOverrideFile() = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "lane_overrides.json")

    /** Lane-level street data around the car (refetched after moving 250 m), for the map when no route is active. */
    private fun updateNearbyStreets(p: LatLon) {
        val c = streetsCenter
        if (c != null && Geo.distance(c, p) < 250) return
        if (streetsJob?.isActive == true || !isOnline()) return
        streetsCenter = p
        streetsJob = viewModelScope.launch {
            // Healthiest Overpass mirror first (some answer every map query with HTTP 500 for hours).
            if (System.currentTimeMillis() - de.lanewise.core.net.ServerHealth.lastProbeMs > 120_000) {
                runCatching { de.lanewise.core.net.ServerHealth.probe(settings.value.osrmUrl) }.getOrNull()
                    ?.let { r -> _ui.update { it.copy(serverHealth = r.overall) } }
            }
            val res = runCatching { de.lanewise.osm.StreetLanes.fetchNearby(p) }
            res.exceptionOrNull()?.let { if (it !is kotlinx.coroutines.CancellationException) android.util.Log.w("LaneWise", "nearby streets: $it") }
            val nearby = res.getOrNull()
            val streets = nearby?.streets
            if (streets == null) { streetsCenter = c; return@launch }   // retry on next fix
            _ui.update { it.copy(nearbyStreets = streets, nearbyFeatures = nearby?.features.orEmpty()) }
        }
    }

    /** Speed limit of the road we are on while just driving (no route): offline graph, else Overpass. */
    private fun updateBrowseLimit(fix: Fix) {
        val heading = fix.bearing?.toDouble() ?: return   // only when moving
        val last = browseLimitAt
        if (last != null && Geo.distance(last.position, fix.position) < 120 && fix.timeMs - last.timeMs < 30_000) return
        if (browseLimitJob?.isActive == true) return
        browseLimitAt = fix
        browseLimitJob = viewModelScope.launch {
            val g = graph
            val limit = if (g != null && g.contains(fix.position)) graphLimit(g, fix.position, heading)
            else if (isOnline()) runCatching { speedLimits.currentLimit(fix.position, heading) }.getOrNull()
            else null
            _ui.update { it.copy(browseLimit = limit) }
        }
    }

    private fun graphLimit(g: OfflineGraph, p: LatLon, heading: Double): Int? {
        val n = g.nearestNode(p)
        if (n < 0) return null
        var best: Int? = null
        var bestDiff = 60.0
        for (e in g.edgeStart[n] until g.edgeStart[n + 1]) {
            val diff = kotlin.math.abs(Geo.bearingDelta(heading, Geo.bearing(g.latLon(n), g.latLon(g.edgeTo[e]))))
            if (diff < bestDiff) {
                val w = g.edgeWay[e]
                val m = if (g.edgeForward[e].toInt() == 1) g.ways.maxForward[w] else g.ways.maxBackward[w]
                if (m != OsmTags.UNKNOWN) { bestDiff = diff; best = m }
            }
        }
        return best
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
                message = res.exceptionOrNull()?.let { e -> "${strings.searchFailed}: ${e.message}" }) }
        }
    }

    private var originJob: Job? = null

    /** Search for a start point (route preview "From"). */
    fun onOriginQuery(q: String) {
        originJob?.cancel()
        if (q.length < 3) { _ui.update { it.copy(originResults = emptyList(), originSearching = null) }; return }
        originJob = viewModelScope.launch {
            delay(350)
            _ui.update { it.copy(originSearching = true) }
            val res = runCatching { geocoder.search(q, _ui.value.fix?.position) }
            _ui.update { it.copy(originResults = res.getOrDefault(emptyList()), originSearching = false,
                message = res.exceptionOrNull()?.let { e -> "${strings.searchFailed}: ${e.message}" }) }
        }
    }

    /**
     * Swap start and destination (route preview ⇅). Starting from the GPS position, the position becomes the
     * destination. The stops are driven in reverse order.
     */
    fun swapEnds() {
        val st = _ui.value
        val dest = st.destination ?: return
        val from = st.origin ?: st.fix?.position?.let { Place(strings.myPosition, "", it) }
            ?: run { _ui.update { it.copy(message = strings.waitingForGps) }; return }
        _ui.update { it.copy(origin = dest, destination = from, query = from.title, stops = it.stops.reversed()) }
        computeRoutes()
    }

    private var stopJob: Job? = null

    /**
     * Add-stop search. [smart]: places matching a name / brand / category along the selected route, ranked by the time
     * they add ([de.lanewise.search.StopFinder]); else a normal address / place search.
     */
    fun onStopQuery(q: String, smart: Boolean) {
        stopJob?.cancel()
        if (q.trim().length < 2) { _ui.update { it.copy(stopPlaces = emptyList(), stopCandidates = emptyList(), stopSearching = null) }; return }
        stopJob = viewModelScope.launch {
            delay(if (smart) 700 else 350)
            _ui.update { it.copy(stopSearching = true) }
            val st = _ui.value
            val route = st.selectedRoute
            if (smart && route != null) {
                val from = st.origin?.position ?: route.geometry.points.first()
                val to = st.destination?.position ?: route.geometry.points.last()
                val osrm = OsrmRoutingProvider(settings.value.osrmUrl)
                val res = runCatching {
                    de.lanewise.search.StopFinder { pts -> osrm.durations(pts) }
                        .find(q, route.geometry, listOf(from) + st.stops.map { it.position } + to)
                }
                _ui.update { it.copy(stopCandidates = res.getOrDefault(emptyList()), stopPlaces = emptyList(), stopSearching = false,
                    message = res.exceptionOrNull()?.let { e -> "${strings.searchFailed}: ${e.message}" }) }
            } else {
                val res = runCatching { geocoder.search(q, st.fix?.position ?: st.destination?.position) }
                _ui.update { it.copy(stopPlaces = res.getOrDefault(emptyList()), stopCandidates = emptyList(), stopSearching = false,
                    message = res.exceptionOrNull()?.let { e -> "${strings.searchFailed}: ${e.message}" }) }
            }
        }
    }

    /** Add [place] as a stop: at [insertAt] (smart: the leg where it adds least), else as the last stop. */
    fun addStop(place: Place, insertAt: Int? = null) {
        stopJob?.cancel()
        _ui.update { st ->
            val i = (insertAt ?: st.stops.size).coerceIn(0, st.stops.size)
            st.copy(stops = st.stops.toMutableList().apply { add(i, place) }, stopPlaces = emptyList(),
                stopCandidates = emptyList(), stopSearching = null)
        }
        computeRoutes()
    }

    fun removeStop(index: Int) {
        _ui.update { st -> st.copy(stops = st.stops.filterIndexed { i, _ -> i != index }) }
        computeRoutes()
    }

    private var healthJob: Job? = null

    /** Checks how busy the search and routing servers are (offline = unreachable). Skipped while a check runs. */
    fun probeServers() {
        if (healthJob?.isActive == true) return
        healthJob = viewModelScope.launch {
            if (!isOnline()) { _ui.update { it.copy(serverHealth = de.lanewise.core.net.Health.DOWN) }; return@launch }
            val r = runCatching { de.lanewise.core.net.ServerHealth.probe(settings.value.osrmUrl) }.getOrNull()
            _ui.update { it.copy(serverHealth = r?.overall ?: de.lanewise.core.net.Health.DOWN) }
        }
    }

    fun clearStopSearch() {
        stopJob?.cancel()
        _ui.update { it.copy(stopPlaces = emptyList(), stopCandidates = emptyList(), stopSearching = null) }
    }

    /** Route from [place] instead of the current position; null = back to the GPS position. Recomputes the routes. */
    fun chooseOrigin(place: Place?) {
        _ui.update { it.copy(origin = place, originResults = emptyList(), originSearching = null) }
        if (_ui.value.destination != null) computeRoutes()
    }

    private var testDrivePending = false

    /**
     * Built-in test drive (Settings → Test drive): Hannover Hbf → B 3 Messeschnellweg (trunk road with a roundabout) →
     * A 37 → A 7 → exit Großburgwedel → L 381 / L 383 → roundabout → rural L 383, 25 km. The demo starts by itself on the
     * route option that really uses the A 7 and the roundabout.
     */
    fun startTestDrive() = startDemo(TEST_FROM, TEST_TO, strings.testStart, strings.testEnd, preferTestRoute = true)

    /** Demo from [from] to [to] (test drive; debug builds also accept it from adb, see MainActivity). */
    fun startDemo(from: LatLon, to: LatLon, fromName: String = "A", toName: String = "B", preferTestRoute: Boolean = false, speed: Double? = null,
                  via: List<LatLon> = emptyList(), detour: LatLon? = null) {
        testDrivePending = true
        demoDetour = detour
        demoDrivePath = null
        testPreferA7 = preferTestRoute
        speed?.let { s -> _ui.update { it.copy(demoSpeed = s) } }
        _ui.update {
            it.copy(origin = Place(fromName, "", from), destination = Place(toName, "", to),
                stops = via.mapIndexed { i, p -> Place("${strings.stop} ${i + 1}", "", p) },
                results = emptyList(), query = toName, mode = Mode.PREVIEW)
        }
        computeRoutes()
    }

    private var testPreferA7 = false

    /**
     * Debug demo (adb, see MainActivity): the simulated car drives [demoDrivePath] - the route through [demoDetour] -
     * instead of the navigated route, and its fixes count for re-routing like real ones. [debugOffline]: once navigating,
     * the app acts as if there were no internet (offline routing only), without touching the phone's network.
     */
    private var demoDetour: LatLon? = null
    private var demoDrivePath: de.lanewise.core.geo.Polyline? = null
    var debugOffline = false

    fun chooseDestination(place: Place) {
        _ui.update { it.copy(destination = place, results = emptyList(), query = place.title, mode = Mode.PREVIEW) }
        computeRoutes()
    }

    fun chooseDestination(p: LatLon) = chooseDestination(Place(strings.markedPlace, String.format(java.util.Locale.US, "%.5f, %.5f", p.lat, p.lon), p))

    // --- saved places ---------------------------------------------------------------------------
    fun goTo(place: SavedPlace) = chooseDestination(place.asPlace())

    fun savePlace(kind: PlaceKind, name: String, place: Place, icon: String? = null, color: Long? = null) {
        viewModelScope.launch {
            // Address: street and city; for a named place (restaurant) its name and street, so "Pizzeria (Mum's
            // favourite)" keeps both.
            val address = listOf(place.title, place.subtitle).filter { it.isNotBlank() }.distinct().joinToString(", ")
            placesRepo.save(SavedPlace(kind = kind, name = name.ifBlank { place.title }, address = address,
                lat = place.position.lat, lon = place.position.lon, icon = icon, color = color, category = place.category))
        }
    }

    /** Saved place at [p] (within 40 m), for the star / home / work badge on search results. */
    fun savedAt(p: LatLon): SavedPlace? =
        _ui.value.places.firstOrNull { Geo.distance(LatLon(it.lat, it.lon), p) < 40 }

    /** A saved place as a search result (start, stop or destination preset). */
    fun SavedPlace.asPlace() = Place(name, address, LatLon(lat, lon), category)

    fun deletePlace(id: String) { viewModelScope.launch { placesRepo.delete(id) } }

    fun selectRoute(i: Int) = _ui.update { it.copy(selected = i) }

    fun cancelPreview() = _ui.update { it.copy(mode = Mode.BROWSE, routes = emptyList(), destination = null, query = "", origin = null,
        originResults = emptyList(), stops = emptyList()) }

    fun clearMessage() = _ui.update { it.copy(message = null) }

    fun showMessage(text: String) = _ui.update { it.copy(message = text) }

    private var poiJob: kotlinx.coroutines.Job? = null
    private var poiKey: Pair<Set<de.lanewise.core.model.AutobahnPoiKind>, Set<String>>? = null

    /**
     * Loads the enabled Autobahn icon kinds for the motorways around Hannover plus those on [route] (refs "A 2" → A2).
     * Only reloads when the kinds or the motorways change.
     */
    fun loadAutobahnPois(kinds: Set<de.lanewise.core.model.AutobahnPoiKind>, route: Route?) {
        val roads = (de.lanewise.traffic.AutobahnApiSource.HANNOVER_AUTOBAHNS + route?.steps.orEmpty()
            .mapNotNull { it.ref }.flatMap { it.split(";", ",") }.map { it.replace(" ", "").trim() }
            .filter { it.matches(Regex("A\\d{1,3}")) }).toSet()
        val key = kinds to roads
        if (key == poiKey) return
        poiKey = key
        poiJob?.cancel()
        if (kinds.isEmpty()) { _ui.update { it.copy(autobahnPois = emptyList()) }; return }
        if (!isOnline()) { poiKey = null; return }
        poiJob = viewModelScope.launch {
            val pois = runCatching { de.lanewise.traffic.AutobahnApiSource().pois(roads, kinds) }.getOrNull()
            if (pois == null) { poiKey = null; return@launch }
            _ui.update { it.copy(autobahnPois = pois) }
        }
    }

    // --- routing --------------------------------------------------------------------------------
    private fun providers(s: AppSettings, online: Boolean): List<RoutingProvider> {
        val osrm = OsrmRoutingProvider(s.osrmUrl)
        val tomtom = TomTomRoutingProvider(s.tomtomKey)
        return when (s.routing) {
            RoutingSource.OFFLINE -> listOf(offlineRouter)
            RoutingSource.OSRM -> listOf(osrm, offlineRouter)
            RoutingSource.TOMTOM -> listOf(tomtom, osrm, offlineRouter)
            // Auto: lanes from OSM (OSRM) — they beat TomTom's on the owner's test route (17 vs 6 lane junctions) and can be
            // fixed in OSM — then TomTom adds live traffic to that same route (withTrafficFromTomTom). TomTom routes only
            // if OSRM fails.
            RoutingSource.AUTO -> when {
                !online -> listOf(offlineRouter)
                s.tomtomKey.isNotBlank() -> listOf(osrm, tomtom, offlineRouter)
                else -> listOf(osrm, offlineRouter)
            }
        }
    }

    private fun trafficSources(s: AppSettings): List<TrafficSource> = buildList {
        if (s.useAutobahnApi) add(AutobahnApiSource())
        if (s.tomtomKey.isNotBlank()) add(TomTomTrafficSource(s.tomtomKey))
        if (s.hereKey.isNotBlank()) add(HereTrafficSource(s.hereKey))
        if (s.mobilithekUrl.isNotBlank()) add(MobilithekClient(s.mobilithekUrl, mobilithekCert(), s.mobilithekCertPassword))
    }

    fun mobilithekCert(): File = File(ctx.filesDir, "mobilithek.p12")

    /** Auto mode with a TomTom key: OSM routes get TomTom's live-traffic ETA and solid/dashed lines (one call each). */
    private suspend fun withTrafficFromTomTom(routes: List<Route>, s: AppSettings, online: Boolean): List<Route> {
        if (s.routing != RoutingSource.AUTO || s.tomtomKey.isBlank() || !online) return routes
        val tt = TomTomRoutingProvider(s.tomtomKey)
        return kotlinx.coroutines.coroutineScope {
            routes.map { r -> async { if (r.provider == "osrm") tt.withTraffic(r) ?: r else r } }.map { it.await() }
        }
    }

    fun computeRoutes(origin: LatLon? = null, heading: Double? = null) {
        val dest = _ui.value.destination ?: return
        val custom = _ui.value.origin?.position
        val from = origin ?: custom ?: _ui.value.fix?.position ?: run {
            _ui.update { it.copy(message = strings.waitingForGps) }; return
        }
        val s = settings.value
        viewModelScope.launch {
            _ui.update { it.copy(routing = true) }
            val online = isOnline()
            val via = _ui.value.stops.map { it.position }
            val req = RouteRequest(from, dest.position, heading.takeIf { origin != null || custom == null }, alternatives = true,
                avoidTolls = s.avoidTolls, avoidMotorways = s.avoidMotorways, via = via)
            var routes: List<Route> = emptyList()
            var lastError: String? = null
            for (p in providers(s, online)) {
                if (p.requiresNetwork && !online) continue
                if (via.isNotEmpty() && !p.supportsVia) { lastError = lastError ?: strings.stopsNeedOnline; continue }
                val r = runCatching { p.route(req) }
                if (r.isSuccess && r.getOrThrow().isNotEmpty()) { routes = r.getOrThrow(); break }
                lastError = r.exceptionOrNull()?.message
            }
            if (routes.isEmpty()) {
                _ui.update { it.copy(routing = false, message = lastError ?: strings.noRoute) }
                return@launch
            }
            routes = withTrafficFromTomTom(routes, s, online)
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
            if (testDrivePending && ranked.isNotEmpty()) {
                testDrivePending = false
                // Detour demo: the path the car really drives (start → detour point → destination), fetched now while the
                // route is planned (before any simulated "offline").
                demoDrivePath = demoDetour?.let { d ->
                    val st = _ui.value
                    val o = st.origin?.position ?: return@let null
                    val dest = st.destination?.position ?: return@let null
                    providers(s, online).firstNotNullOfOrNull { p ->
                        runCatching { p.route(RouteRequest(o, dest, null, alternatives = false, via = listOf(d))).firstOrNull() }.getOrNull()
                    }?.geometry
                }
                val i = if (!testPreferA7) 0 else ranked.indexOfFirst { r ->
                    r.steps.any { it.ref.orEmpty().replace(" ", "").contains("A7") } &&
                        r.steps.any { it.maneuver.type == de.lanewise.core.model.ManeuverType.ROUNDABOUT }
                }.coerceAtLeast(0)
                _ui.update { it.copy(selected = i) }
                startNavigation(simulate = true)
            }
            // Preview: fetch signs/bike lanes for the first stretch of the best route so they show on the map.
            if (online && ranked.first().provider != "offline") previewCorridor(ranked.first())
        }
    }

    /** Speed limits (OSRM only; TomTom/offline bring their own), signs and bike lanes for the next 40 km. */
    private var corridorJob: Job? = null
    private var corridorFailedAt = 0L

    private fun fetchCorridor(route: Route, from: Double) {
        val to = minOf(route.distance, from + 40_000)
        val before = speedFetchedUntil
        speedFetchedUntil = to
        corridorJob?.cancel()
        corridorJob = viewModelScope.launch {
            laneOverrides()
            val data = runCatching { speedLimits.corridor(route.geometry, from, to, route.osmNodes) }.getOrNull()
            if (data == null) {
                // Try again later (from where the car is then), not on the next fix.
                speedFetchedUntil = before
                corridorFailedAt = System.currentTimeMillis()
                return@launch
            }
            val base = currentRoute ?: route
            val updated = base.copy(
                speedLimits = if (base.provider == "osrm") (base.speedLimits.filter { it.to <= from } + data.limits).sortedBy { it.from }
                else base.speedLimits,
                features = (base.features.filter { it.distanceAlong < from } + data.features).sortedBy { it.distanceAlong },
                bikeLanes = (base.bikeLanes.filter { it.to <= from } + data.bikeLanes).sortedBy { it.from },
                laneChanges = de.lanewise.lane.ChangeSpan.merge(
                    (base.laneChanges.filter { it.to <= from && it !in base.tomtomLaneLines } + data.laneChanges).sortedBy { it.from }, base.tomtomLaneLines),
                sideRoads = base.sideRoads + data.sideRoads,
            ).withLaneOverrides(data.laneOverrides).withOsmTurnLanes(data.turnLanes.map { it.distanceAlong to it.lanes })
                .withLaneAccess(data.laneAccess.map { it.distanceAlong to it.carLanes })
                .withLaneCounts((base.laneCounts.filter { it.to <= from } + data.laneCounts).sortedBy { it.from }).withConnectivity(data.connectivity)
            if (_ui.value.mode == Mode.NAVIGATING) {
                engine = NavigationEngine(updated, laneGuidance).also { it.rehydrate(engine) }
                currentRoute = updated
            }
            _ui.update { st -> st.copy(routes = st.routes.map { if (it === base || it === route) updated else it }) }
        }
    }

    private fun previewCorridor(route: Route) {
        viewModelScope.launch {
            laneOverrides()
            val data = runCatching { speedLimits.corridor(route.geometry, 0.0, minOf(route.distance, 15_000.0), route.osmNodes) }.getOrNull()
                ?: return@launch
            val updated = route.copy(
                speedLimits = if (route.provider == "osrm") data.limits else route.speedLimits,
                features = data.features, bikeLanes = data.bikeLanes,
                laneChanges = de.lanewise.lane.ChangeSpan.merge(data.laneChanges, route.tomtomLaneLines),
                sideRoads = data.sideRoads,
            ).withLaneOverrides(data.laneOverrides).withOsmTurnLanes(data.turnLanes.map { it.distanceAlong to it.lanes })
                .withLaneAccess(data.laneAccess.map { it.distanceAlong to it.carLanes }).withLaneCounts(data.laneCounts)
                .withConnectivity(data.connectivity)
            _ui.update { st -> st.copy(routes = st.routes.map { if (it === route) updated else it }) }
        }
    }

    // --- navigation -----------------------------------------------------------------------------

    fun startNavigation(simulate: Boolean) {
        val route = _ui.value.selectedRoute ?: return
        laneGuidance.reset()
        currentRoute = route
        engine = NavigationEngine(route, laneGuidance)
        speedFetchedUntil = 0.0
        _ui.update { it.copy(mode = Mode.NAVIGATING, simulating = simulate, routes = listOf(route), selected = 0, journeyDriven = 0.0,
            navStartedAt = System.currentTimeMillis()) }
        notifText = ""
        de.lanewise.nav.NavigationService.start(ctx, "LaneWise", route.steps.firstOrNull()?.name.orEmpty(),
            Strings.of(settings.value.language).navChannel)
        if (route.provider != "offline" && isOnline()) fetchCorridor(route, 0.0)
        navJob?.cancel()
        if (simulate) {
            navJob = viewModelScope.launch {
                demoControl = de.lanewise.location.DemoControl()
                _ui.update { it.copy(demoPaused = false) }
                SimulatedLocationSource(demoDrivePath ?: route.geometry, { d -> (currentRoute?.speedLimitAt(d)?.kmh ?: 50).toDouble() },
                    { _ui.value.demoSpeed }, demoControl).fixes()
                    .collect { onFix(it) }
                // The demo drove to the end of the route line: arrived, even if the last fix snapped a few metres short
                // (it stayed at "50 m" with an empty banner).
                if (_ui.value.mode == Mode.NAVIGATING && _ui.value.simulating) {
                    val last = currentRoute?.steps?.lastOrNull()
                    stopNavigation()
                    last?.let { _ui.update { st -> st.copy(message = Phrases(settings.value.language).instruction(it)) } }
                }
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

    private var notifText = ""

    /** Ongoing notification (screen off / app in background): next manoeuvre, distance rounded like the banner. */
    private fun updateNavNotification(snap: NavSnapshot, phrases: Phrases) {
        val step = snap.nextStep ?: return
        val title = phrases.distance(snap.distanceToNextStep) + " · " + phrases.instruction(step)
        val text = phrases.distance(snap.remainingDistance)
        if (title + text == notifText) return
        notifText = title + text
        de.lanewise.nav.NavigationService.update(ctx, title, text, Strings.of(settings.value.language).navChannel)
    }

    private var demoControl: de.lanewise.location.DemoControl? = null

    /** Demo only: pause / play, like a video player. */
    fun toggleDemoPause() {
        val c = demoControl ?: return
        c.paused = !c.paused
        _ui.update { it.copy(demoPaused = c.paused) }
    }

    /** Demo only: jump to [fraction] (0..1) of the route, from the scrub bar. */
    fun seekDemo(fraction: Float) {
        val c = demoControl ?: return
        val r = currentRoute ?: return
        val d = (fraction.coerceIn(0f, 1f) * r.distance).coerceAtMost(r.distance - 30)
        engine?.seek(d)
        c.seek(d)
    }

    /** Demo only: one step slower / faster through [DEMO_SPEEDS]. */
    fun changeDemoSpeed(faster: Boolean) {
        _ui.update { st ->
            val i = DEMO_SPEEDS.indexOfFirst { it >= st.demoSpeed }.let { if (it < 0) DEMO_SPEEDS.lastIndex else it }
            st.copy(demoSpeed = DEMO_SPEEDS[(i + if (faster) 1 else -1).coerceIn(0, DEMO_SPEEDS.lastIndex)])
        }
    }

    fun stopNavigation() {
        navJob?.cancel(); trafficJob?.cancel()
        de.lanewise.nav.NavigationService.stop(ctx)
        engine = null; currentRoute = null
        _ui.update { it.copy(mode = Mode.BROWSE, nav = null, simulating = false, routes = emptyList(), destination = null, laneHint = null, laneHintCountdown = null,
            origin = null, originResults = emptyList(), stops = emptyList(), query = "", results = emptyList()) }
    }

    /** Long-press on the lane bar: remember this spot as "lanes wrong here" (GeoJSON for fixing OSM later). */
    fun reportLanes() {
        val nav = _ui.value.nav ?: return
        val advice = nav.lanes.nextAdvice ?: return
        val strings = Strings.of(settings.value.language)
        val road = nav.route.steps.lastOrNull { it.distanceAlong <= nav.distanceAlong }?.name.orEmpty()
        val report = de.lanewise.osm.LaneReports.Report(nav.fix.position, System.currentTimeMillis(), road,
            de.lanewise.osm.LaneReports.turnLanes(advice.site.lanes), advice.site.distanceAlong.toInt())
        viewModelScope.launch {
            val n = withContext(Dispatchers.IO) {
                runCatching { de.lanewise.osm.LaneReports.append(laneReportFile(), report) }.getOrNull()
            }
            _ui.update { it.copy(message = if (n != null) String.format(strings.laneReported, n) else strings.laneReportFailed) }
        }
    }

    fun laneReportFile() = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "lane_reports.geojson")

    /** Number of saved lane reports (Settings). */
    fun laneReportCount(): Int = runCatching { de.lanewise.osm.LaneReports.read(laneReportFile()).size }.getOrDefault(0)

    /** Share sheet for lane_reports.geojson (FileProvider), or null if there is nothing to share. */
    fun laneReportShareIntent(): android.content.Intent? {
        val f = laneReportFile()
        if (!f.exists() || laneReportCount() == 0) return null
        val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "application/geo+json"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            putExtra(android.content.Intent.EXTRA_SUBJECT, "LaneWise lane reports")
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return android.content.Intent.createChooser(send, null).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun overrideLane(index: Int) {
        val advice = _ui.value.nav?.lanes?.nextAdvice ?: return
        laneGuidance.overrideCurrentLane(advice.site, index)
    }

    /**
     * New route from [fix] after leaving the route. One attempt at a time (a newer one replaces a hung one), each
     * provider limited to [REROUTE_TIMEOUT_MS]; a provider is asked again without the GPS heading if it failed with it.
     * If nothing answers (offline without an offline map, server down) the old route stays, the driver is told once, and
     * the next attempt follows 10 s later or right when the network is back ([onNetworkBack]).
     */
    private fun reroute(fix: Fix, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && (now - lastReroute < 10_000 || (rerouteJob?.isActive == true && now - lastReroute < 30_000))) return
        lastReroute = now
        val s = settings.value
        if (s.voice && !rerouteAnnounced) voice.speak(de.lanewise.nav.Announcement("reroute-$now", Phrases(s.language).rerouting(), 2))
        rerouteAnnounced = true
        val dest = _ui.value.destination ?: return
        // Stops still ahead: the ones whose "stop reached" point on the current route is not passed yet.
        val along = _ui.value.nav?.distanceAlong ?: 0.0
        val reached = currentRoute?.steps.orEmpty().filter { it.maneuver.type == de.lanewise.core.model.ManeuverType.WAYPOINT }
            .count { it.distanceAlong <= along + 30 }
        val ahead = _ui.value.stops.drop(reached)
        _ui.update { it.copy(stops = ahead) }
        rerouteJob?.cancel()
        rerouteJob = viewModelScope.launch {
            val online = isOnline()
            val req = RouteRequest(fix.position, dest.position, fix.bearing?.toDouble(), alternatives = false,
                avoidTolls = s.avoidTolls, avoidMotorways = s.avoidMotorways, via = ahead.map { it.position })
            var route: Route? = null
            for (p in providers(s, online)) {
                if (p.requiresNetwork && !online) continue
                if (ahead.isNotEmpty() && !p.supportsVia) continue
                route = attempt(p, req) ?: req.heading?.let { attempt(p, req.copy(heading = null)) }
                if (route != null) break
            }
            val r = route ?: run {
                android.util.Log.w("LaneWise", "reroute failed (online=$online)")
                _ui.update { it.copy(message = strings.rerouteWaiting) }
                return@launch
            }
            val withTraffic = runCatching { withTrafficFromTomTom(listOf(r), s, online).first() }.getOrDefault(r)
            if (_ui.value.mode != Mode.NAVIGATING) return@launch
            laneGuidance.reset()
            // Keep the journey progress: what was driven on the old route stays driven.
            val drivenOnOld = _ui.value.nav?.distanceAlong ?: 0.0
            _ui.update { it.copy(journeyDriven = it.journeyDriven + drivenOnOld) }
            currentRoute = withTraffic
            engine = NavigationEngine(withTraffic, laneGuidance)
            rerouteAnnounced = false
            speedFetchedUntil = 0.0
            corridorJob?.cancel()
            if (withTraffic.provider != "offline" && online) fetchCorridor(withTraffic, 0.0)
            _ui.update { it.copy(routes = listOf(withTraffic), selected = 0, message = null) }
        }
    }

    private suspend fun attempt(p: RoutingProvider, req: RouteRequest): Route? =
        kotlinx.coroutines.withTimeoutOrNull(REROUTE_TIMEOUT_MS) {
            try {
                p.route(req).firstOrNull()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("LaneWise", "reroute ${p.javaClass.simpleName}: ${e.message}")
                null
            }
        }

    // --- offline data ---------------------------------------------------------------------------
    private fun graphFile(region: String) = File(ctx.filesDir, "graph_$region.lwg")

    private suspend fun loadGraph(region: String) {
        val f = graphFile(region)
        if (!f.exists()) { graph = null; _ui.update { it.copy(offlineGraphReady = false) }; return }
        graph = withContext(Dispatchers.IO) {
            runCatching { OfflineGraph.load(f) }.onFailure { android.util.Log.w("LaneWise", "offline map ${f.name}: $it") }.getOrNull()
        }
        _ui.update { it.copy(offlineGraphReady = graph != null) }
    }

    /** Rescan installed packs (e.g. after `tools/voice_pack/04_push.py`). */
    fun reloadVoicePacks() { viewModelScope.launch { refreshVoicePacks() } }

    private suspend fun refreshVoicePacks() {
        val packs = withContext(Dispatchers.IO) { de.lanewise.nav.VoicePack.installed(ctx) }
        _ui.update { it.copy(voicePacks = packs) }
        voice.setPacks(selectedPacks(settings.value))
    }

    private fun selectedPacks(s: AppSettings): List<de.lanewise.nav.VoicePack> {
        val ids = s.voicePacks.split(',').filter { it.isNotBlank() }.toSet()
        return _ui.value.voicePacks.filter { it.id in ids }
    }

    /** Select an installed pack for its language, or `null` to use Android TTS for [language] ("de", "en", "vi"). */
    fun selectVoicePack(language: String, id: String?) = updateSettings { st ->
        val others = st.voicePacks.split(',').filter { it.isNotBlank() }
            .filter { pid -> _ui.value.voicePacks.firstOrNull { it.id == pid }?.language != language }
        st.copy(voicePacks = (others + listOfNotNull(id)).joinToString(","))
    }

    /** Plays a sample announcement with the selected voice (pack or Android TTS). */
    fun testVoice() {
        val s = settings.value
        val p = Phrases(s.language)
        val step = de.lanewise.core.model.RouteStep(0.0, 0.0, 0.0,
            de.lanewise.core.model.Maneuver(de.lanewise.core.model.ManeuverType.TURN, de.lanewise.core.model.Modifier.RIGHT,
                LatLon(0.0, 0.0), 0.0, 0.0), "Celler Straße", null, null, null)
        val parts = p.spokenParts(step, 300.0)
        voice.setLanguage(s.language)
        voice.speak(de.lanewise.nav.Announcement("test-${System.currentTimeMillis()}", parts.joinToString(" "), 2, parts))
    }

    fun downloadVoicePack(id: String) {
        if (_ui.value.voiceDownload != null) return
        viewModelScope.launch {
            try {
                _ui.update { it.copy(voiceDownload = id to 0) }
                val pack = de.lanewise.nav.VoicePack.download(ctx, id) { pct -> _ui.update { it.copy(voiceDownload = id to pct) } }
                _ui.update { it.copy(voiceDownload = null) }
                refreshVoicePacks()
                selectVoicePack(pack.language, pack.id)
            } catch (e: Exception) {
                val msg = if (e.message?.contains("404") == true) strings.voiceNotPublished else "${strings.dlFailed}: ${e.message}"
                _ui.update { it.copy(voiceDownload = null, message = msg) }
            }
        }
    }

    fun downloadOfflineRegion() {
        val s = settings.value
        val region = OfflineGraphBuilder.REGIONS.firstOrNull { it.id == s.offlineRegion } ?: OfflineGraphBuilder.REGIONS.first()
        viewModelScope.launch {
            try {
                val st = strings
                _ui.update { it.copy(offlineProgress = st.dlStart) }
                graph = OfflineGraphBuilder(laneOverrides()).build(region, graphFile(region.id), st) { msg -> _ui.update { it.copy(offlineProgress = msg) } }
                _ui.update { it.copy(offlineGraphReady = true, offlineProgress = st.dlTiles + " …") }
                // One download: light and dark styles use the same tiles (see OfflineTiles).
                OfflineTiles(ctx).download(region) { pct ->
                    if (pct >= 0) _ui.update { it.copy(offlineProgress = "${st.dlTiles}: $pct %") }
                }
                _ui.update { it.copy(offlineProgress = null, message = "${st.dlReady}: ${region.name}") }
            } catch (e: Exception) {
                _ui.update { it.copy(offlineProgress = null, message = "${strings.dlFailed}: ${e.message}") }
            }
        }
    }

    private fun isOnline(): Boolean {
        // Debug demo "offline": the route is planned online, then the drive runs as if the network were gone.
        if (de.lanewise.BuildConfig.DEBUG && debugOffline && _ui.value.mode == Mode.NAVIGATING) {
            if (_ui.value.online) _ui.update { it.copy(online = false) }
            return false
        }
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    override fun onCleared() {
        de.lanewise.nav.NavigationService.stop(ctx)
        voice.shutdown()
        super.onCleared()
    }

    companion object {
        /** Demo playback speeds (× real driving time). */
        val DEMO_SPEEDS = listOf(0.25, 0.5, 1.0, 2.0, 4.0, 8.0)
        /** Longest wait for one routing provider while off the route. */
        const val REROUTE_TIMEOUT_MS = 20_000L
    }
}

/** Test drive start (Lavesstraße near Hannover Hbf) and end (L 383 west of Großburgwedel, after the roundabout). */
val TEST_FROM = LatLon(52.37390, 9.74662)
val TEST_TO = LatLon(52.50796, 9.81688)
