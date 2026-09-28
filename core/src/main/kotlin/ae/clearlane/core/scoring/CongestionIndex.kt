package ae.clearlane.core.scoring

import ae.clearlane.core.model.CongestionProfile
import ae.clearlane.core.model.RouteCandidate
import ae.clearlane.core.model.RouteSegment

/**
 * Turns a route's per segment congestion into the handful of numbers the rest
 * of the app reasons about.
 *
 * The important choice in here is time weighting. A provider gives congestion
 * per stretch of road, and the obvious thing to do is average it by length.
 * That is wrong for this product: a route that crawls for two kilometres and
 * then runs clear for twenty scores well on a distance weighted average while
 * feeling like a bad drive, because almost all of the elapsed time was spent in
 * the bad two kilometres. So each segment is weighted by how long you are stuck
 * on it, which is what the driver actually experiences.
 */
object CongestionIndex {

    /**
     * The slowest a segment is assumed to move, as a fraction of free flow.
     * Congestion of exactly 1.0 means stopped, and a stopped car would take
     * infinitely long, so the weighting needs a floor. 0.12 puts a fully jammed
     * motorway segment at about 14 km/h, which is roughly what standstill
     * traffic averages once it starts creeping.
     */
    const val CRAWL_FLOOR = 0.12

    fun profile(route: RouteCandidate): CongestionProfile {
        val segments = route.segments
        if (segments.isEmpty()) {
            return CongestionProfile(
                timeWeighted = 0.0,
                distanceWeighted = 0.0,
                heavyShare = 0.0,
                severeShare = 0.0,
                stopGoPer10Km = 0.0,
                delayRatio = (route.durationSeconds / route.freeFlowSeconds) - 1.0,
                worstStretchMeters = 0.0,
                longestClearMeters = 0.0,
                source = route.source,
            )
        }

        val totalMeters = segments.sumOf { it.meters }
        if (totalMeters <= 0.0) {
            return CongestionProfile(
                0.0, 0.0, 0.0, 0.0, 0.0,
                (route.durationSeconds / route.freeFlowSeconds) - 1.0,
                0.0, 0.0, route.source,
            )
        }

        // Relative time on each segment. The absolute scale cancels out when we
        // divide, so there is no need to normalise against the route duration.
        val weights = DoubleArray(segments.size) { i ->
            val s = segments[i]
            s.meters / (1.0 - s.congestion).coerceAtLeast(CRAWL_FLOOR)
        }
        val weightSum = weights.sum()

        var timeWeighted = 0.0
        var distanceWeighted = 0.0
        var heavyMeters = 0.0
        var severeMeters = 0.0
        for (i in segments.indices) {
            val s = segments[i]
            timeWeighted += weights[i] * s.congestion
            distanceWeighted += s.meters * s.congestion
            if (s.congestion >= CongestionProfile.HEAVY) heavyMeters += s.meters
            if (s.congestion >= CongestionProfile.SEVERE) severeMeters += s.meters
        }

        val runs = measureRuns(segments)

        return CongestionProfile(
            timeWeighted = if (weightSum > 0.0) (timeWeighted / weightSum).coerceIn(0.0, 1.0) else 0.0,
            distanceWeighted = (distanceWeighted / totalMeters).coerceIn(0.0, 1.0),
            heavyShare = heavyMeters / totalMeters,
            severeShare = severeMeters / totalMeters,
            stopGoPer10Km = runs.jamEntries / (totalMeters / 10_000.0),
            delayRatio = ((route.durationSeconds / route.freeFlowSeconds) - 1.0).coerceAtLeast(0.0),
            worstStretchMeters = runs.longestJam,
            longestClearMeters = runs.longestClear,
            source = route.source,
        )
    }

    private class Runs(val jamEntries: Int, val longestJam: Double, val longestClear: Double)

    /**
     * Walks the route once counting how often it falls into a jam and how long
     * the good and bad runs are.
     *
     * The jam state uses two thresholds rather than one. With a single
     * threshold, a stretch hovering around it is counted as a dozen separate
     * jams and the stop and go figure becomes meaningless, which matters
     * because stop and go is the thing drivers hate most and so it carries real
     * weight in the score.
     */
    private fun measureRuns(segments: List<RouteSegment>): Runs {
        var inJam = false
        var entries = 0
        var jamRun = 0.0
        var longestJam = 0.0
        var clearRun = 0.0
        var longestClear = 0.0

        for (s in segments) {
            if (inJam) {
                if (s.congestion <= CongestionProfile.JAM_EXIT) {
                    inJam = false
                    longestJam = maxOf(longestJam, jamRun)
                    jamRun = 0.0
                } else {
                    jamRun += s.meters
                }
            } else {
                if (s.congestion >= CongestionProfile.JAM_ENTER) {
                    inJam = true
                    entries++
                    jamRun = s.meters
                }
            }

            if (s.congestion <= CongestionProfile.CLEAR) {
                clearRun += s.meters
            } else {
                longestClear = maxOf(longestClear, clearRun)
                clearRun = 0.0
            }
        }
        longestJam = maxOf(longestJam, jamRun)
        longestClear = maxOf(longestClear, clearRun)
        return Runs(entries, longestJam, longestClear)
    }
}
