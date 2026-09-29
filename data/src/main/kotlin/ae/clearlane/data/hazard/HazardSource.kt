package ae.clearlane.data.hazard

import ae.clearlane.core.model.Hazard
import ae.clearlane.core.model.LatLng

/**
 * Somewhere camera positions come from.
 *
 * Deliberately a lookup by box rather than by route: a source can then be a
 * bundled file, a tile server or an API without the caller caring, and pinning
 * the results to a route is [ae.clearlane.core.nav.HazardIndex]'s job.
 */
interface HazardSource {
    val name: String

    /** How the data should be credited on screen, or null when it need not be. */
    val attribution: String?

    val isConfigured: Boolean

    /**
     * Cameras inside the box. Implementations are expected to be cheap to call
     * repeatedly: the driving screen asks once per route, but the route can
     * change every time traffic is refreshed.
     */
    suspend fun near(southWest: LatLng, northEast: LatLng): List<Hazard>
}

/** Thrown when a source cannot answer. Never fatal: cameras are an extra. */
class HazardSourceException(message: String, cause: Throwable? = null) :
    Exception(message, cause)
