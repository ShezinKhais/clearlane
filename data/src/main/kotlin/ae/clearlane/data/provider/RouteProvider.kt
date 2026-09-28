package ae.clearlane.data.provider

import ae.clearlane.core.model.LatLng
import ae.clearlane.core.model.RouteCandidate

/**
 * One request for ways of getting from A to B.
 *
 * [via] is how the candidate generator asks for a route down a particular
 * corridor: the same origin and destination, forced through a point on the road
 * it wants to try. Providers hand over two or three alternatives at most, which
 * is not enough to fill four traffic tiers, so most candidates are made this
 * way rather than asked for directly.
 */
data class RouteRequest(
    val origin: LatLng,
    val destination: LatLng,
    val via: List<LatLng> = emptyList(),
    val alternatives: Boolean = true,
    /** Departure time, epoch seconds, for providers that support it. */
    val departAt: Long? = null,
)

/**
 * A source of routes with traffic on them.
 *
 * Implementations own exactly one job: get routes from somewhere and express
 * their congestion on the project's 0 to 1 scale, where 0 is free flow and 1 is
 * stopped. Every provider's own scale is different, so each adapter documents
 * how it maps onto ours. Nothing downstream is allowed to care which provider
 * answered.
 */
interface RouteProvider {

    /** Short name, shown in the UI so the driver knows where the data came from. */
    val name: String

    /** False when the provider has no key configured, so callers can fall back. */
    val isConfigured: Boolean

    /**
     * Routes for [request], best first. An empty list means the provider had
     * nothing rather than that something broke; failures throw.
     */
    suspend fun routes(request: RouteRequest): List<RouteCandidate>
}

/** Anything that went wrong talking to a provider. */
class RouteProviderException(
    val provider: String,
    message: String,
    cause: Throwable? = null,
) : Exception("$provider: $message", cause)
