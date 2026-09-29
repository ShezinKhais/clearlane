package ae.clearlane.data.hazard

import ae.clearlane.core.model.LatLng
import ae.clearlane.core.model.RouteCandidate
import ae.clearlane.core.model.RouteHazard
import ae.clearlane.core.model.bounds
import ae.clearlane.core.nav.HazardIndex

/**
 * Finds the cameras on a set of routes.
 *
 * Sources are tried in order and the first one that answers wins, which is the
 * same rule the routing providers follow: the bundled UAE extract first because
 * it needs no network, anything live after it.
 *
 * Failing to find cameras is never allowed to fail a route. A driver with no
 * camera warnings still has a working maps app; a driver with no route has
 * nothing.
 */
class HazardService(private val sources: List<HazardSource>) {

    data class Result(
        /** Cameras pinned to each route, keyed by route id. */
        val byRoute: Map<String, List<RouteHazard>>,
        /** Credit line for whichever source answered, or null. */
        val attribution: String?,
        val warnings: List<String>,
    ) {
        companion object {
            val EMPTY = Result(emptyMap(), null, emptyList())
        }
    }

    suspend fun forRoutes(routes: List<RouteCandidate>): Result {
        if (routes.isEmpty()) return Result.EMPTY

        val all = routes.flatMap { it.geometry }
        val (southWest, northEast) = all.bounds(padMeters = PAD_METERS) ?: return Result.EMPTY

        val warnings = mutableListOf<String>()
        for (source in sources) {
            if (!source.isConfigured) continue
            val found = try {
                source.near(southWest, northEast)
            } catch (e: HazardSourceException) {
                warnings += e.message ?: "Camera positions could not be loaded."
                continue
            }
            if (found.isEmpty()) continue

            return Result(
                byRoute = routes.associate { it.id to HazardIndex.pin(it, found) },
                attribution = source.attribution,
                warnings = warnings,
            )
        }
        return Result(emptyMap(), null, warnings)
    }

    private companion object {
        /**
         * The box is grown a little so a camera tagged just outside the tightest
         * bounding box of the route, which happens whenever a route runs along
         * the edge of one, is still fetched.
         */
        const val PAD_METERS = 1_000.0
    }
}
