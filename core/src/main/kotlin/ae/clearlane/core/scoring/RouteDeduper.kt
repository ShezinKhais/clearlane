package ae.clearlane.core.scoring

import ae.clearlane.core.model.LatLng
import ae.clearlane.core.model.RouteCandidate
import kotlin.math.floor

/**
 * Throws away candidates that are really the same road as one we already have.
 *
 * This matters because of how candidates are generated. To get more than the
 * two or three alternatives a routing API will hand over, we re-route through
 * detour points, and most of those come back as the original route with a
 * pointless wiggle in the middle. Showing the driver four tiers that are all
 * the same motorway would make the whole app look like a lie.
 *
 * Overlap is measured on a coarse grid rather than by comparing geometry
 * directly, so two routes that use the same road but were snapped to slightly
 * different shape points still count as the same road.
 */
object RouteDeduper {

    /** Grid cell size. Roughly 60 m of latitude, which is about a city block. */
    const val CELL_DEGREES = 0.00055

    /** Above this shared length, two routes are the same road. */
    const val SAME_ROUTE_OVERLAP = 0.80

    /** Cells of longitude in the world at [CELL_DEGREES]. Fits in 21 bits. */
    private const val LON_CELLS = 21

    /**
     * Keeps [candidates] in the order given, dropping any that overlap an
     * already kept route by more than [threshold].
     *
     * Order matters, so callers should pass the routes they most want to keep
     * first. [RoutePlanner] passes the provider's own routes ahead of the ones
     * we invented.
     */
    fun dedupe(
        candidates: List<RouteCandidate>,
        threshold: Double = SAME_ROUTE_OVERLAP,
    ): List<RouteCandidate> {
        val kept = mutableListOf<RouteCandidate>()
        val keptCells = mutableListOf<Map<Long, Double>>()
        for (c in candidates) {
            val cells = occupancy(c)
            val duplicate = keptCells.any { overlap(cells, it) > threshold }
            if (!duplicate) {
                kept += c
                keptCells += cells
            }
        }
        return kept
    }

    /**
     * Share of the shorter route's length that also lies on the longer one.
     *
     * Using the shorter route as the denominator is deliberate: a short route
     * that runs entirely inside a long one is a duplicate of it for our
     * purposes, even though it only covers part of it.
     *
     * The longer route's cells are widened by one cell in every direction
     * before comparing. Without that, a route shifted sideways by twenty metres
     * lands in a different cell for about a third of its length purely because
     * of where the grid lines happen to fall, and a re-route that came back as
     * the same road with a nudge in it would survive as a separate option. The
     * cost is that the two carriageways of a dual carriageway now read as one
     * road, which is what a driver would call them anyway.
     */
    fun overlap(a: Map<Long, Double>, b: Map<Long, Double>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val (small, large) = if (a.values.sum() <= b.values.sum()) a to b else b to a
        val smallTotal = small.values.sum()
        if (smallTotal <= 0.0) return 0.0
        val cover = dilate(large.keys)
        var shared = 0.0
        for ((cell, meters) in small) {
            if (cell in cover) shared += meters
        }
        return shared / smallTotal
    }

    /** Metres of a route falling in each grid cell. */
    fun occupancy(route: RouteCandidate): Map<Long, Double> {
        val out = HashMap<Long, Double>(route.segments.size * 2)
        if (route.segments.isNotEmpty()) {
            for (s in route.segments) {
                if (s.meters <= 0.0) continue
                val key = cellOf(s.start)
                out[key] = (out[key] ?: 0.0) + s.meters
            }
        } else {
            // A provider that gave geometry but no per segment detail. Spread
            // the length evenly so the comparison still means something.
            val pts = route.geometry
            if (pts.size < 2) return out
            val each = route.meters / (pts.size - 1)
            for (i in 0 until pts.size - 1) {
                val key = cellOf(pts[i])
                out[key] = (out[key] ?: 0.0) + each
            }
        }
        return out
    }

    /** Every cell in [cells] plus its eight neighbours. */
    fun dilate(cells: Set<Long>): Set<Long> {
        val out = HashSet<Long>(cells.size * 6)
        for (key in cells) {
            val y = key ushr LON_CELLS
            val x = key and ((1L shl LON_CELLS) - 1)
            for (dy in -1..1) {
                for (dx in -1..1) {
                    out += encode(y + dy, x + dx)
                }
            }
        }
        return out
    }

    /**
     * Packs a coordinate into one long. Longitude takes the low 21 bits, which
     * is enough for the 654,546 cells around the equator at this cell size, so
     * the two halves never bleed into each other.
     */
    fun cellOf(p: LatLng): Long {
        val y = floor((p.lat + 90.0) / CELL_DEGREES).toLong()
        val x = floor((p.lon + 180.0) / CELL_DEGREES).toLong()
        return encode(y, x)
    }

    private fun encode(y: Long, x: Long): Long = (y shl LON_CELLS) or (x and ((1L shl LON_CELLS) - 1))
}
