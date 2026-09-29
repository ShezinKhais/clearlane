package ae.clearlane.app

import android.app.Application
import ae.clearlane.app.location.Positions
import ae.clearlane.core.model.LatLng
import ae.clearlane.core.model.RouteHazard
import ae.clearlane.core.model.RoutePreferences
import ae.clearlane.core.model.TrafficTier
import ae.clearlane.core.nav.Fix
import ae.clearlane.core.nav.NavState
import ae.clearlane.core.nav.RoutePlayback
import ae.clearlane.core.nav.RouteTracker
import ae.clearlane.data.RoutingService
import ae.clearlane.data.fixture.FixtureRouteProvider
import ae.clearlane.data.fixture.FixtureTripSummary
import ae.clearlane.data.hazard.BundledHazards
import ae.clearlane.data.hazard.HazardService
import ae.clearlane.data.hazard.OverpassHazards
import ae.clearlane.data.mapbox.MapboxRouteProvider
import ae.clearlane.data.provider.RouteProviderException
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Which of the two screens the app is on. */
enum class Screen {
    /** Comparing routes, standing still. */
    PLAN,

    /** Following one of them. */
    DRIVE,
}

/** Where the positions on the driving screen are coming from. */
enum class PositionSource {
    /** The phone's own GPS. */
    DEVICE,

    /**
     * The chosen route played back at the speed its congestion implies. Always
     * labelled on screen, because a speed the car is not doing must never be
     * mistaken for one it is.
     */
    SIMULATED,

    /** Nothing yet: no permission, or no fix. */
    NONE,
}

data class RouteUiState(
    val origin: LatLng? = null,
    val destination: LatLng? = null,
    val outcome: RoutingService.Outcome? = null,
    val selected: TrafficTier? = null,
    val preferences: RoutePreferences = RoutePreferences(),
    val trips: List<FixtureTripSummary> = emptyList(),
    val activeTripId: String? = null,
    val loading: Boolean = false,
    val error: String? = null,

    val screen: Screen = Screen.PLAN,
    val nav: NavState? = null,
    val positionSource: PositionSource = PositionSource.NONE,
    /** Cameras pinned to each route, keyed by route id. */
    val hazards: Map<String, List<RouteHazard>> = emptyMap(),
    val hazardAttribution: String? = null,
    /** Set while a traffic refresh is in flight, so the header can say so. */
    val refreshing: Boolean = false,
    /** Wall clock of the last successful plan, for "updated 2 min ago". */
    val plannedAtMillis: Long? = null,
) {
    /** The route the map should draw in full colour. */
    val highlighted get() = selected?.let { outcome?.plan?.picks?.get(it) } ?: outcome?.plan?.fastest

    /** Cameras on whichever route is being followed. */
    val hazardsOnRoute: List<RouteHazard>
        get() = highlighted?.let { hazards[it.id] }.orEmpty()

    /** How far through the drive, by time, for the strip marker. */
    val progress: Float?
        get() {
            val route = highlighted ?: return null
            val state = nav ?: return null
            if (screen != Screen.DRIVE) return null
            val done = route.durationSeconds - state.remainingSeconds
            return (done / route.durationSeconds).toFloat().coerceIn(0f, 1f)
        }
}

class RouteViewModel(app: Application) : AndroidViewModel(app) {

    private val fixtures = FixtureRouteProvider {
        app.assets.open(FIXTURE_ASSET).bufferedReader().use { it.readText() }
    }

    /**
     * Mapbox first when a token is configured, fixtures otherwise. The fixture
     * provider is always last and always ready, so the app never has nothing
     * to show.
     */
    private val service = RoutingService(
        providers = listOf(
            MapboxRouteProvider(BuildConfig.MAPBOX_TOKEN),
            fixtures,
        ),
    )

    /**
     * The bundled UAE extract first, since it needs no network. Overpass is
     * constructed but left off: see [OverpassHazards] for why shipping it on
     * would be an abuse of a volunteer run service.
     */
    private val hazardService = HazardService(
        sources = listOf(
            BundledHazards { app.assets.open(HAZARD_ASSET).bufferedReader().use { it.readText() } },
            OverpassHazards(isConfigured = BuildConfig.LIVE_CAMERA_LOOKUP),
        ),
    )

    private val positions = Positions(app)

    private val _state = MutableStateFlow(RouteUiState(trips = fixtures.tripSummaries()))
    val state: StateFlow<RouteUiState> = _state.asStateFlow()

    val providerName: String get() = service.activeProvider?.name ?: "none"

    /** The position feed and the traffic ticker, cancelled when the drive ends. */
    private var positionJob: Job? = null
    private var refreshJob: Job? = null

    init {
        // Open on the scripted evening run, because an empty map with two
        // pickers on it does not explain what the app is for.
        _state.value.trips.firstOrNull()?.let(::selectTrip)
    }

    fun selectTrip(trip: FixtureTripSummary) {
        _state.update {
            it.copy(origin = trip.origin, destination = trip.destination, activeTripId = trip.id)
        }
        plan()
    }

    fun setDestination(point: LatLng) {
        _state.update { it.copy(destination = point, activeTripId = null) }
        plan()
    }

    fun setOrigin(point: LatLng) {
        _state.update { it.copy(origin = point, activeTripId = null) }
        plan()
    }

    fun select(tier: TrafficTier?) {
        _state.update { it.copy(selected = tier) }
        // The route being followed has changed, so the cameras on it have too.
        if (_state.value.screen == Screen.DRIVE) refreshNav()
    }

    fun setMaxExtraMinutes(minutes: Int) {
        _state.update { it.copy(preferences = it.preferences.copy(maxExtraMinutes = minutes)) }
        plan()
    }

    fun setAvoidTolls(avoid: Boolean) {
        _state.update { it.copy(preferences = it.preferences.copy(avoidTolls = avoid)) }
        plan()
    }

    fun plan(refresh: Boolean = false) {
        val current = _state.value
        val origin = current.origin ?: return
        val destination = current.destination ?: return

        _state.update {
            if (refresh) it.copy(refreshing = true) else it.copy(loading = true, error = null)
        }
        viewModelScope.launch {
            try {
                val outcome = service.plan(origin, destination, current.preferences)
                val hazards = outcome?.let {
                    runCatching { hazardService.forRoutes(it.plan.considered.map { r -> r.candidate }) }
                        .getOrNull()
                }
                _state.update {
                    it.copy(
                        loading = false,
                        refreshing = false,
                        outcome = outcome,
                        plannedAtMillis = if (outcome != null) System.currentTimeMillis() else it.plannedAtMillis,
                        hazards = hazards?.byRoute ?: it.hazards,
                        hazardAttribution = hazards?.attribution ?: it.hazardAttribution,
                        // Land on whatever we would actually recommend, but
                        // keep the driver's own choice if they made one and it
                        // still exists.
                        selected = it.selected?.takeIf { tier ->
                            outcome?.plan?.picks?.containsKey(tier) == true
                        } ?: outcome?.plan?.recommended,
                        error = if (outcome == null) "No route between those two points." else null,
                    )
                }
                refreshNav()
            } catch (e: RouteProviderException) {
                _state.update { it.copy(loading = false, refreshing = false, error = e.message) }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        loading = false,
                        refreshing = false,
                        error = e.message ?: "Something went wrong.",
                    )
                }
            }
        }
    }

    // ---------------------------------------------------------------- driving

    /**
     * Starts following the chosen route.
     *
     * [simulate] plays the route back instead of using the GPS. That is how the
     * driving screen is demonstrated and tested anywhere other than in a moving
     * car, and it is labelled as such the whole time it is running.
     */
    fun startDrive(simulate: Boolean = false) {
        val route = _state.value.highlighted?.candidate ?: return
        stopFeeds()

        val useSimulation = simulate || !positions.granted || !positions.enabled
        _state.update {
            it.copy(
                screen = Screen.DRIVE,
                positionSource = if (useSimulation) PositionSource.SIMULATED else PositionSource.DEVICE,
            )
        }

        positionJob = viewModelScope.launch {
            if (useSimulation) {
                var elapsed = 0.0
                while (isActive) {
                    val fix = RoutePlayback.at(route, elapsed, SIMULATION_SPEED_FACTOR)
                        ?: break
                    apply(fix)
                    delay(TICK_MS)
                    elapsed += TICK_MS / 1000.0
                }
                // Arrived. Leave the last state on screen rather than blanking
                // it, and stop pretending to move.
                _state.update { it.copy(positionSource = PositionSource.NONE) }
            } else {
                positions.fixes().collect(::apply)
            }
        }

        startRefreshTicker()
    }

    fun stopDrive() {
        stopFeeds()
        _state.update {
            it.copy(screen = Screen.PLAN, nav = null, positionSource = PositionSource.NONE)
        }
    }

    /** Re-plans from where the car actually is, after leaving the route. */
    fun replanFromHere() {
        val here = _state.value.nav?.fix?.at ?: return
        _state.update { it.copy(origin = here, activeTripId = null) }
        plan()
    }

    private fun apply(fix: Fix) {
        val current = _state.value
        val route = current.highlighted ?: return
        _state.update {
            it.copy(nav = RouteTracker.locate(route.candidate, fix, it.hazardsOnRoute))
        }
    }

    /** Recomputes the driving state against whatever route is now selected. */
    private fun refreshNav() {
        val current = _state.value
        if (current.screen != Screen.DRIVE) return
        val fix = current.nav?.fix ?: return
        apply(fix)
    }

    /**
     * Asks for traffic again while the drive is running.
     *
     * Live traffic is not pushed to the app, so the only way to notice that the
     * road ahead has gone bad is to ask again. Every ask is a billed routing
     * request per corridor, so the interval is a cost decision as much as a
     * freshness one: two minutes is often enough to catch a jam forming and
     * slow enough that a long drive costs tens of requests rather than
     * thousands.
     *
     * There is nothing to refresh on the bundled fixtures, so the ticker does
     * not run on them.
     */
    private fun startRefreshTicker() {
        if (_state.value.outcome?.isDemoData != false) return
        refreshJob = viewModelScope.launch {
            while (isActive) {
                delay(REFRESH_INTERVAL_MS)
                // Re-plan from where the car is now, not from where the drive
                // began, otherwise the route behind the car keeps being
                // recalculated and paid for.
                val here = _state.value.nav?.snapped
                if (here != null) _state.update { it.copy(origin = here) }
                plan(refresh = true)
            }
        }
    }

    private fun stopFeeds() {
        positionJob?.cancel()
        positionJob = null
        refreshJob?.cancel()
        refreshJob = null
    }

    override fun onCleared() {
        stopFeeds()
        super.onCleared()
    }

    /** Called once the permission dialog has been answered. */
    fun onLocationPermissionResult(granted: Boolean) {
        if (granted && _state.value.screen == Screen.DRIVE &&
            _state.value.positionSource == PositionSource.SIMULATED
        ) {
            startDrive(simulate = false)
        }
    }

    val locationGranted: Boolean get() = positions.granted

    companion object {
        private const val FIXTURE_ASSET = "clearlane/fixtures.json"
        private const val HAZARD_ASSET = "clearlane/hazards.json"

        /** One frame of the simulated drive. */
        private const val TICK_MS = 1_000L

        /**
         * A half hour route watched at eight times speed is four minutes, which
         * is long enough to see the traffic change and short enough to sit
         * through.
         */
        private const val SIMULATION_SPEED_FACTOR = 8.0

        private const val REFRESH_INTERVAL_MS = 120_000L
    }
}
