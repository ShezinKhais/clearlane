package ae.clearlane.data

import ae.clearlane.core.model.LatLng
import ae.clearlane.core.model.RoutePlan
import ae.clearlane.core.model.RoutePreferences
import ae.clearlane.core.scoring.RoutePlanner
import ae.clearlane.data.provider.CandidateGenerator
import ae.clearlane.data.provider.RouteProvider
import ae.clearlane.data.uae.TollModel
import ae.clearlane.data.uae.TrafficCalendar
import java.time.ZonedDateTime

/**
 * The one thing the UI talks to: give it two points, get back the fastest route
 * and the calmer ones.
 *
 * It holds providers in preference order and uses the first one that has a key,
 * which is how the app works with no account at all: the fixture provider is
 * always last and always configured.
 */
class RoutingService(
    private val providers: List<RouteProvider>,
    private val clock: () -> ZonedDateTime = { ZonedDateTime.now() },
) {

    /** The provider that would answer a request right now. */
    val activeProvider: RouteProvider?
        get() = providers.firstOrNull { it.isConfigured }

    data class Outcome(
        val plan: RoutePlan,
        val providerName: String,
        /** True when the answer came from bundled data rather than live traffic. */
        val isDemoData: Boolean,
        /** One line about the time of day, or null. */
        val timeAdvice: String?,
        /** Anything that went wrong without stopping the answer. */
        val warnings: List<String>,
    )

    suspend fun plan(
        origin: LatLng,
        destination: LatLng,
        preferences: RoutePreferences = RoutePreferences(),
        ramadan: Boolean = false,
    ): Outcome? {
        val provider = activeProvider ?: return null
        val now = clock()

        val generated = CandidateGenerator(provider).candidates(
            origin = origin,
            destination = destination,
            departAt = null,
        )
        if (generated.candidates.isEmpty()) return null

        // Tolls are priced here rather than in the provider, because the rate
        // depends on the clock and no routing API knows the UAE schedule.
        val priced = generated.candidates.map { candidate ->
            val crossings = TollModel.crossingsOn(candidate, now, ramadan)
            if (crossings.isEmpty()) candidate else candidate.copy(tolls = crossings)
        }

        val plan = RoutePlanner.plan(priced, preferences) ?: return null

        val warnings = buildList {
            addAll(generated.warnings)
            if (!TollModel.isConfigured) {
                add("Toll costs are not priced: the gate coordinates are not in this build.")
            }
        }

        return Outcome(
            plan = plan,
            providerName = provider.name,
            isDemoData = provider.name == "Fixtures",
            timeAdvice = TrafficCalendar.advice(now, ramadan),
            warnings = warnings,
        )
    }
}
