package ae.clearlane.core.nav

import ae.clearlane.core.model.LatLng
import ae.clearlane.core.model.RouteCandidate
import ae.clearlane.core.model.bearingTo
import ae.clearlane.core.model.distanceTo
import ae.clearlane.core.scoring.CongestionIndex

/**
 * Drives a route on paper, so the navigation screen can be seen working without
 * a car.
 *
 * This is not a toy. A driving UI that has only ever been looked at standing
 * still is a driving UI nobody has tested: the arrival time never falls, the
 * camera warnings never fire, and the map never turns. Replaying a route at the
 * speed its own congestion implies exercises all of that, and because
 * [RouteTracker] holds no state, a position produced here goes through exactly
 * the same path as one from the GPS.
 *
 * The speed comes from the route's own numbers rather than a constant: the car
 * crawls through the jam and opens up on the clear stretch, which is the thing
 * worth watching.
 */
object RoutePlayback {

    /**
     * Where the car is [elapsedSeconds] into the drive, at [speedFactor] times
     * real time.
     *
     * Returns null once the route has been driven, which is how a caller knows
     * to stop.
     */
    fun at(
        route: RouteCandidate,
        elapsedSeconds: Double,
        speedFactor: Double = 1.0,
    ): Fix? {
        require(speedFactor > 0.0) { "speed factor must be positive" }
        val played = elapsedSeconds * speedFactor
        if (played < 0.0) return null
        if (played >= route.durationSeconds) return null

        val meters = metersAt(route, played)
        val here = pointAt(route.geometry, meters) ?: return null
        // Looking a little ahead gives a heading that is steady through a bend,
        // where two adjacent shape points give one that jitters.
        val ahead = pointAt(route.geometry, meters + HEADING_LOOKAHEAD_METERS)
            ?: route.geometry.last()

        return Fix(
            at = here,
            speedKph = speedAt(route, meters),
            headingDeg = if (here == ahead) null else here.bearingTo(ahead),
            accuracyMeters = 5.0,
        )
    }

    /** How long a full playback runs for, at [speedFactor]. */
    fun durationSeconds(route: RouteCandidate, speedFactor: Double = 1.0): Double =
        route.durationSeconds / speedFactor

    /**
     * Distance covered after [seconds] of driving.
     *
     * Time is apportioned across segments by the same weighting the congestion
     * index uses, so the car spends its time where the route says the time
     * goes.
     */
    private fun metersAt(route: RouteCandidate, seconds: Double): Double {
        if (route.segments.isEmpty()) {
            return route.meters * (seconds / route.durationSeconds).coerceIn(0.0, 1.0)
        }
        val weights = route.segments.map { weightOf(it.meters, it.congestion) }
        val total = weights.sum()
        if (total <= 0.0) return 0.0

        var spent = 0.0
        var meters = 0.0
        for ((i, segment) in route.segments.withIndex()) {
            val cost = route.durationSeconds * (weights[i] / total)
            if (spent + cost >= seconds) {
                val into = if (cost > 0.0) (seconds - spent) / cost else 0.0
                return meters + segment.meters * into.coerceIn(0.0, 1.0)
            }
            spent += cost
            meters += segment.meters
        }
        return route.meters
    }

    /** Speed on the segment at [meters], from its congestion and its limit. */
    private fun speedAt(route: RouteCandidate, meters: Double): Double {
        var walked = 0.0
        for (segment in route.segments) {
            walked += segment.meters
            if (walked >= meters) {
                val free = (segment.speedLimitKph ?: DEFAULT_FREE_KPH).toDouble()
                return free * (1.0 - segment.congestion).coerceAtLeast(CongestionIndex.CRAWL_FLOOR)
            }
        }
        // No segments, so fall back to the route's own average.
        return (route.meters / route.durationSeconds) * 3.6
    }

    /** The point [meters] along a polyline, interpolated between shape points. */
    private fun pointAt(points: List<LatLng>, meters: Double): LatLng? {
        if (points.isEmpty()) return null
        if (meters <= 0.0) return points.first()
        var walked = 0.0
        for (i in 0 until points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            val length = a.distanceTo(b)
            if (walked + length >= meters) {
                val t = if (length <= 0.0) 0.0 else (meters - walked) / length
                return LatLng(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)
            }
            walked += length
        }
        return points.last()
    }

    private fun weightOf(meters: Double, congestion: Double): Double =
        meters / (1.0 - congestion).coerceAtLeast(CongestionIndex.CRAWL_FLOOR)

    /** Far enough ahead to smooth a bend, short enough to still be the road. */
    private const val HEADING_LOOKAHEAD_METERS = 40.0

    /** Used only where the provider gave no posted limit. */
    private const val DEFAULT_FREE_KPH = 100
}
