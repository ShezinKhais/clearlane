package ae.clearlane.core.nav

import ae.clearlane.core.model.Hazard
import ae.clearlane.core.model.LatLng
import ae.clearlane.core.model.RouteCandidate
import ae.clearlane.core.model.RouteHazard
import ae.clearlane.core.model.distanceTo
import ae.clearlane.core.model.projectOnto
import kotlin.math.floor

/**
 * Works out which cameras a route actually passes, and how far along it each
 * one is.
 *
 * This runs once when a route is chosen, not on every GPS fix. A country's
 * worth of cameras against a route's worth of shape points is a few million
 * distance calculations done the obvious way, which is fine once and hopeless
 * at one hertz. Pinning them to a distance up front turns the per fix question
 * into "which of these numbers is the next one bigger than mine".
 *
 * Candidates are narrowed with a coarse grid first. Cells are about a kilometre,
 * and only the nine cells around each route point are consulted, so the work is
 * proportional to the route rather than to the country.
 */
object HazardIndex {

    /** Roughly one kilometre at these latitudes. Only a bucket size. */
    private const val CELL_DEGREES = 0.01

    /**
     * How far off the line a camera may sit and still count as being on this
     * route. A camera is tagged beside the carriageway, the route line runs
     * down the middle of it, and a wide motorway with a service road either
     * side is already 40 m across.
     */
    const val CORRIDOR_METERS: Double = 55.0

    /**
     * Cameras on [route], in the order they are met.
     *
     * A camera near a point where the route doubles back on itself matches the
     * first pass, which is the one the driver reaches first.
     */
    fun pin(
        route: RouteCandidate,
        hazards: List<Hazard>,
        corridorMeters: Double = CORRIDOR_METERS,
    ): List<RouteHazard> {
        if (hazards.isEmpty() || route.geometry.size < 2) return emptyList()

        val buckets = HashMap<Long, MutableList<Hazard>>()
        for (hazard in hazards) {
            buckets.getOrPut(cellOf(hazard.at)) { mutableListOf() } += hazard
        }

        // Best match per hazard: nearest approach wins, so a camera is pinned
        // where the route actually passes it rather than at the first shape
        // point that happened to fall within the corridor.
        val best = HashMap<Hazard, RouteHazard>()
        var travelled = 0.0
        val points = route.geometry

        for (i in 0 until points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            val length = a.distanceTo(b)

            for (hazard in nearby(buckets, a, b)) {
                val projection = hazard.at.projectOnto(a, b)
                if (projection.offMeters > corridorMeters) continue
                val existing = best[hazard]
                if (existing != null && existing.offMeters <= projection.offMeters) continue
                best[hazard] = RouteHazard(
                    hazard = hazard,
                    atMeters = travelled + length * projection.fraction,
                    offMeters = projection.offMeters,
                )
            }
            travelled += length
        }

        return best.values.sortedBy { it.atMeters }
    }

    /** Hazards in the nine cells around each end of the segment. */
    private fun nearby(
        buckets: Map<Long, MutableList<Hazard>>,
        a: LatLng,
        b: LatLng,
    ): Set<Hazard> {
        if (buckets.isEmpty()) return emptySet()
        val found = LinkedHashSet<Hazard>()
        for (point in listOf(a, b)) {
            val row = floor(point.lat / CELL_DEGREES).toLong()
            val col = floor(point.lon / CELL_DEGREES).toLong()
            for (dr in -1..1) {
                for (dc in -1..1) {
                    buckets[encode(row + dr, col + dc)]?.let(found::addAll)
                }
            }
        }
        return found
    }

    private fun cellOf(point: LatLng): Long =
        encode(floor(point.lat / CELL_DEGREES).toLong(), floor(point.lon / CELL_DEGREES).toLong())

    private fun encode(row: Long, col: Long): Long = (row shl 20) or (col and 0xFFFFF)
}
