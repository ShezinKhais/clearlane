package ae.clearlane.data.provider

import ae.clearlane.core.model.CandidateOrigin
import ae.clearlane.core.model.LatLng
import ae.clearlane.core.model.RouteCandidate
import ae.clearlane.data.uae.UaeCorridors
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Finds enough routes to have an argument about.
 *
 * A routing API will hand over two or three alternatives, and they are all
 * variations on being quick, because that is what the service is for. It will
 * not volunteer the motorway twelve kilometres inland that is twenty minutes
 * longer and completely empty. So we ask for that one directly: same origin,
 * same destination, forced through a point on the corridor we want to try.
 *
 * In the UAE those corridors are known by name, which is the whole reason this
 * app can be precise here and merely useful elsewhere. Outside the service area
 * it falls back to offsetting either side of the direct line, which finds less
 * but costs nothing to try.
 *
 * Every extra corridor is another billed request, so the count is capped and
 * the calls go out together rather than one after another.
 */
class CandidateGenerator(
    private val provider: RouteProvider,
    /** Corridors to try beyond the provider's own answer. */
    private val maxCorridors: Int = 4,
) {

    suspend fun candidates(
        origin: LatLng,
        destination: LatLng,
        departAt: Long? = null,
    ): Result {
        val failures = mutableListOf<String>()

        val primary = try {
            provider.routes(
                RouteRequest(
                    origin = origin,
                    destination = destination,
                    alternatives = true,
                    departAt = departAt,
                ),
            )
        } catch (e: RouteProviderException) {
            failures += e.message ?: "routing failed"
            emptyList()
        }

        val viaPoints = corridorPoints(origin, destination)

        // The corridor lookups go out together, because each one is a round
        // trip and doing four in sequence is four times the wait. Each returns
        // its own error rather than writing to the shared list, since these run
        // concurrently and a mutable list does not survive that.
        val detours = coroutineScope {
            viaPoints.map { via ->
                async {
                    try {
                        provider.routes(
                            RouteRequest(
                                origin = origin,
                                destination = destination,
                                via = listOf(via.point),
                                alternatives = false,
                                departAt = departAt,
                            ),
                        ).map { it.labelled(via) } to null
                    } catch (e: RouteProviderException) {
                        // One awkward via point is not a failed request. Note
                        // it and carry on with the corridors that did answer.
                        emptyList<RouteCandidate>() to (e.message ?: "corridor lookup failed")
                    }
                }
            }.map { it.await() }
        }

        failures += detours.mapNotNull { it.second }
        val all = primary + detours.flatMap { it.first }
        if (all.isEmpty() && failures.isNotEmpty()) {
            throw RouteProviderException(provider.name, failures.first())
        }

        return Result(candidates = all, warnings = failures.distinct())
    }

    /**
     * Applies what we know about the corridor to the route that came back.
     *
     * The router is free to ignore a via point, or to reach it by a road we did
     * not intend, so the corridor's own name is only used as a label when the
     * provider did not give the route a road number of its own.
     */
    private fun RouteCandidate.labelled(via: CorridorPoint): RouteCandidate {
        val refs = if (roadRefs.isEmpty() && via.ref != null) listOf(via.ref) else roadRefs
        return copy(
            roadRefs = refs,
            origin = if (via.ref != null) CandidateOrigin.CORRIDOR_VIA else CandidateOrigin.LATERAL_VIA,
        )
    }

    private fun corridorPoints(origin: LatLng, destination: LatLng): List<CorridorPoint> {
        val corridors = UaeCorridors.viaPointsFor(origin, destination, limit = maxCorridors)
        if (corridors.isNotEmpty()) {
            return corridors.map { CorridorPoint(it.point, it.corridor.ref) }
        }
        return UaeCorridors.lateralOffsets(origin, destination)
            .take(maxCorridors)
            .map { CorridorPoint(it, null) }
    }

    private data class CorridorPoint(val point: LatLng, val ref: String?)

    data class Result(
        val candidates: List<RouteCandidate>,
        /** Things that went wrong but did not stop us answering. */
        val warnings: List<String>,
    )
}
