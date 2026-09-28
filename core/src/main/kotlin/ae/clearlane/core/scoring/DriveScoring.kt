package ae.clearlane.core.scoring

import ae.clearlane.core.model.CongestionProfile
import ae.clearlane.core.model.DriveFactor
import ae.clearlane.core.model.DriveScore
import ae.clearlane.core.model.RouteCandidate
import ae.clearlane.core.model.circumRadius
import kotlin.math.roundToInt

/**
 * Scores how good a route is to drive, out of a hundred.
 *
 * Deliberately not in here: distance, travel time, and tolls. Someone who
 * wants the least congested route has already said they do not mind the
 * longer way, so folding time back into the score would quietly undo that
 * choice.
 * Those costs are reported next to the score instead, and it is the driver who
 * decides whether they are worth it.
 */
object DriveScoring {

    /**
     * Flow is a gate on everything else rather than just the largest weight.
     *
     * The first version of this added the five terms together, and a twenty
     * kilometre motorway crawl scored thirty nine out of a hundred: it has no
     * junctions, it never changes speed, and those terms happily paid out while
     * the driver sat still. That is the opposite of the truth. So the road
     * character terms are now multiplied by flow, which says that a good road
     * only counts for as much as it is actually moving. A stationary road
     * scores zero however well it is built.
     *
     * [W_FLOW] plus the four character weights come to one, so a clear,
     * interesting road still tops out at a hundred.
     */
    const val W_FLOW = 0.40
    const val W_STEADINESS = 0.20
    const val W_CRUISE = 0.15
    const val W_JUNCTIONS = 0.15
    const val W_SWEEP = 0.10

    /** Stop and go events per 10 km at which steadiness scores zero. */
    const val STOP_GO_FLOOR = 6.0

    /** Interrupting junctions per kilometre at which that term scores zero. */
    const val JUNCTIONS_FLOOR = 2.0

    /** A corner is interesting between these radii, in metres. */
    const val SWEEP_MIN_RADIUS = 120.0
    const val SWEEP_MAX_RADIUS = 900.0

    /** Above this congestion, a corner stops counting as fun. */
    const val SWEEP_CONGESTION_CAP = 0.30

    fun score(route: RouteCandidate, congestion: CongestionProfile): DriveScore {
        val km = route.meters / 1000.0

        val flow = (1.0 - congestion.timeWeighted).coerceIn(0.0, 1.0)

        val steadiness = (1.0 - (congestion.stopGoPer10Km / STOP_GO_FLOOR)).coerceIn(0.0, 1.0)

        // The longest run that stays clear, as a share of the route. This is the
        // difference between a drive and a commute: one uninterrupted half hour
        // beats the same clear distance chopped into thirty pieces.
        val cruise = if (route.meters > 0.0) {
            (congestion.longestClearMeters / route.meters).coerceIn(0.0, 1.0)
        } else {
            0.0
        }

        val interrupting = route.maneuvers.count { it.interrupts }
        val junctions = if (km > 0.0) {
            (1.0 - ((interrupting / km) / JUNCTIONS_FLOOR)).coerceIn(0.0, 1.0)
        } else {
            1.0
        }

        val sweep = sweepShare(route, congestion)

        val character = W_STEADINESS * steadiness +
            W_CRUISE * cruise +
            W_JUNCTIONS * junctions +
            W_SWEEP * sweep
        val total = flow * (W_FLOW + character)

        return DriveScore(
            total = (total * 100.0).roundToInt().coerceIn(0, 100),
            flow = flow,
            steadiness = steadiness,
            cruise = cruise,
            junctions = junctions,
            sweep = sweep,
            weakest = weakestOf(flow, steadiness, cruise, junctions, sweep),
        )
    }

    /** Whichever term is costing the total the most points. */
    private fun weakestOf(
        flow: Double,
        steadiness: Double,
        cruise: Double,
        junctions: Double,
        sweep: Double,
    ): DriveFactor = listOf(
        DriveFactor.FLOW to W_FLOW * (1.0 - flow),
        DriveFactor.STEADINESS to W_STEADINESS * (1.0 - steadiness),
        DriveFactor.CRUISE to W_CRUISE * (1.0 - cruise),
        DriveFactor.JUNCTIONS to W_JUNCTIONS * (1.0 - junctions),
        DriveFactor.SWEEP to W_SWEEP * (1.0 - sweep),
    ).maxBy { it.second }.first

    /**
     * Share of the route made up of bends worth taking: wide enough to hold
     * speed through, tight enough to be a corner rather than a straight, and
     * not sitting in traffic at the time.
     *
     * Motorway interchange loops fall below [SWEEP_MIN_RADIUS] and so score
     * nothing, which is correct. Nobody enjoys a cloverleaf.
     */
    private fun sweepShare(route: RouteCandidate, congestion: CongestionProfile): Double {
        val geometry = route.geometry
        if (geometry.size < 3 || route.segments.isEmpty()) return 0.0
        if (congestion.timeWeighted > SWEEP_CONGESTION_CAP) return 0.0

        var interesting = 0.0
        var measured = 0.0
        // Segment i sits between geometry[i] and geometry[i + 1], so the corner
        // at geometry[i] is described by the three points around it.
        for (i in 1 until geometry.size - 1) {
            val seg = route.segments.getOrNull(i) ?: continue
            if (seg.meters <= 0.0) continue
            measured += seg.meters
            if (seg.congestion > SWEEP_CONGESTION_CAP) continue
            val r = circumRadius(geometry[i - 1], geometry[i], geometry[i + 1])
            if (r in SWEEP_MIN_RADIUS..SWEEP_MAX_RADIUS) interesting += seg.meters
        }
        return if (measured > 0.0) (interesting / measured).coerceIn(0.0, 1.0) else 0.0
    }
}
