package ae.clearlane.app

import android.app.Application
import ae.clearlane.core.model.LatLng
import ae.clearlane.core.model.RoutePreferences
import ae.clearlane.core.model.TrafficTier
import ae.clearlane.data.RoutingService
import ae.clearlane.data.fixture.FixtureRouteProvider
import ae.clearlane.data.fixture.FixtureTripSummary
import ae.clearlane.data.mapbox.MapboxRouteProvider
import ae.clearlane.data.provider.RouteProviderException
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
) {
    /** The route the map should draw in full colour. */
    val highlighted get() = selected?.let { outcome?.plan?.picks?.get(it) } ?: outcome?.plan?.fastest
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

    private val _state = MutableStateFlow(RouteUiState(trips = fixtures.tripSummaries()))
    val state: StateFlow<RouteUiState> = _state.asStateFlow()

    val providerName: String get() = service.activeProvider?.name ?: "none"

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
    }

    fun setMaxExtraMinutes(minutes: Int) {
        _state.update { it.copy(preferences = it.preferences.copy(maxExtraMinutes = minutes)) }
        plan()
    }

    fun setAvoidTolls(avoid: Boolean) {
        _state.update { it.copy(preferences = it.preferences.copy(avoidTolls = avoid)) }
        plan()
    }

    fun plan() {
        val current = _state.value
        val origin = current.origin ?: return
        val destination = current.destination ?: return

        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val outcome = service.plan(origin, destination, current.preferences)
                _state.update {
                    it.copy(
                        loading = false,
                        outcome = outcome,
                        // Land on whatever we would actually recommend, but
                        // keep the driver's own choice if they made one and it
                        // still exists.
                        selected = it.selected?.takeIf { tier ->
                            outcome?.plan?.picks?.containsKey(tier) == true
                        } ?: outcome?.plan?.recommended,
                        error = if (outcome == null) "No route between those two points." else null,
                    )
                }
            } catch (e: RouteProviderException) {
                _state.update { it.copy(loading = false, error = e.message) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "Something went wrong.") }
            }
        }
    }

    companion object {
        private const val FIXTURE_ASSET = "clearlane/fixtures.json"
    }
}
