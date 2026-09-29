package ae.clearlane.core.nav

import ae.clearlane.core.model.CongestionProfile
import ae.clearlane.core.model.LatLng
import ae.clearlane.core.model.Maneuver
import ae.clearlane.core.model.RouteCandidate
import ae.clearlane.core.model.RouteHazard
import ae.clearlane.core.model.RouteSegment
import ae.clearlane.core.model.distanceTo
import ae.clearlane.core.model.projectOnto
import ae.clearlane.core.scoring.CongestionIndex
import kotlin.math.roundToInt

/** A GPS fix, as much of one as the phone managed to produce. */
data class Fix(
    val at: LatLng,
    /** Ground speed in km/h, or null when the fix carries no speed. */
    val speedKph: Double? = null,
    /** Course over ground in degrees, or null when standing still. */
    val headingDeg: Double? = null,
    /** Horizontal accuracy in metres, or null when not reported. */
    val accuracyMeters: Double? = null,
)

/** A camera coming up, and how far away it is. */
data class HazardWarning(val hazard: RouteHazard, val meters: Double)

/** One segment of road ahead, with how far away it starts. */
private data class Ahead(val offset: Double, val segment: RouteSegment)

/**
 * Everything the driving screen needs, worked out from one fix against one
 * route. No Android in here, so all of it is testable.
 */
data class NavState(
    val fix: Fix,
    /** The fix pulled onto the route line. */
    val snapped: LatLng,
    /** Perpendicular distance from the route. */
    val offRouteMeters: Double,
    /** False once the fix is further off than [RouteTracker.OFF_ROUTE_METERS]. */
    val onRoute: Boolean,
    val travelledMeters: Double,
    val remainingMeters: Double,
    /** Time left, apportioned from the route's own duration by congestion. */
    val remainingSeconds: Double,
    /** Posted limit where the route carries one. */
    val speedLimitKph: Int?,
    /** Congestion over the next stretch, on the same 0..1 scale as everywhere. */
    val congestionAhead: Double,
    /** Distance to where the next heavy stretch starts, or null. */
    val jamAheadMeters: Double?,
    val nextManeuver: Maneuver?,
    val maneuverMeters: Double?,
    val warning: HazardWarning?,
) {
    /** Over the posted limit, when both numbers are known. */
    val overLimit: Boolean
        get() {
            val limit = speedLimitKph ?: return false
            val speed = fix.speedKph ?: return false
            return speed > limit + SPEEDING_TOLERANCE_KPH
        }

    companion object {
        /**
         * GPS speed wanders by a couple of km/h at a steady throttle, so
         * lighting the warning at the sign exactly would make it flicker on a
         * driver who is holding the limit.
         */
        const val SPEEDING_TOLERANCE_KPH: Int = 4
    }
}

/**
 * Turns a position into a picture of the road ahead.
 *
 * Nothing here is stateful, so a fix can be replayed and the answer does not
 * depend on what happened before it. That makes the whole driving screen
 * reproducible from a list of positions, which is the only practical way to
 * test navigation without a car.
 */
object RouteTracker {

    /** Past this far off the line, the driver is somewhere else. */
    const val OFF_ROUTE_METERS: Double = 80.0

    /** How far ahead the congestion figure looks. */
    const val LOOKAHEAD_METERS: Double = 3_000.0

    /** How far out a camera starts being announced. */
    const val WARN_METERS: Double = 900.0

    /**
     * A camera stays on screen for a moment after it is passed. At 120 km/h a
     * fix every second means a camera can go from 40 m ahead to behind between
     * one frame and the next, and a warning that disappears before it has been
     * read is worse than no warning.
     */
    private const val MISSED_METERS = 60.0

    fun locate(
        route: RouteCandidate,
        fix: Fix,
        hazards: List<RouteHazard> = emptyList(),
    ): NavState {
        val snap = snap(route.geometry, fix.at)
        val travelled = snap.meters.coerceIn(0.0, route.meters)
        val ahead = segmentsFrom(route, travelled)
        val maneuver = nextManeuver(route, travelled)

        return NavState(
            fix = fix,
            snapped = snap.at,
            offRouteMeters = snap.off,
            onRoute = snap.off <= OFF_ROUTE_METERS,
            travelledMeters = travelled,
            remainingMeters = (route.meters - travelled).coerceAtLeast(0.0),
            remainingSeconds = remainingSeconds(route, travelled),
            speedLimitKph = limitAt(route, travelled),
            congestionAhead = congestionOver(ahead, LOOKAHEAD_METERS),
            jamAheadMeters = jamAhead(ahead),
            nextManeuver = maneuver?.first,
            maneuverMeters = maneuver?.second,
            warning = nextWarning(hazards, travelled, fix.headingDeg),
        )
    }

    private data class Snap(val at: LatLng, val meters: Double, val off: Double)

    /** Nearest point on the line, and how far along it that is. */
    private fun snap(points: List<LatLng>, at: LatLng): Snap {
        if (points.size < 2) {
            val only = points.firstOrNull() ?: at
            return Snap(only, 0.0, at.distanceTo(only))
        }
        var best = Snap(points.first(), 0.0, Double.MAX_VALUE)
        var travelled = 0.0
        for (i in 0 until points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            val length = a.distanceTo(b)
            val projection = at.projectOnto(a, b)
            if (projection.offMeters < best.off) {
                best = Snap(
                    at = projection.at,
                    meters = travelled + length * projection.fraction,
                    off = projection.offMeters,
                )
            }
            travelled += length
        }
        return best
    }

    /**
     * Time left, taken as the route's own duration less the share already used.
     *
     * The share is measured with the same time weighting the congestion index
     * uses, so a driver who has cleared the jam and reached an open stretch
     * sees the arrival time drop. Apportioning by distance instead would price
     * every kilometre the same and hold the estimate up long after the queue
     * that caused it has gone.
     */
    private fun remainingSeconds(route: RouteCandidate, travelled: Double): Double {
        if (route.segments.isEmpty()) {
            val left = 1.0 - (travelled / route.meters).coerceIn(0.0, 1.0)
            return route.durationSeconds * left
        }
        var total = 0.0
        var left = 0.0
        var walked = 0.0
        for (segment in route.segments) {
            val weight = segment.meters /
                (1.0 - segment.congestion).coerceAtLeast(CongestionIndex.CRAWL_FLOOR)
            total += weight
            val end = walked + segment.meters
            left += when {
                end <= travelled -> 0.0
                walked >= travelled -> weight
                // Part done. Bill only the remainder of this segment.
                segment.meters > 0.0 -> weight * ((end - travelled) / segment.meters)
                else -> 0.0
            }
            walked = end
        }
        if (total <= 0.0) return 0.0
        return route.durationSeconds * (left / total).coerceIn(0.0, 1.0)
    }

    /** Segments from [travelled] onward, each with how far ahead it starts. */
    private fun segmentsFrom(route: RouteCandidate, travelled: Double): List<Ahead> {
        val out = mutableListOf<Ahead>()
        var walked = 0.0
        for (segment in route.segments) {
            val end = walked + segment.meters
            if (end > travelled) out += Ahead((walked - travelled).coerceAtLeast(0.0), segment)
            walked = end
        }
        return out
    }

    /** The posted limit on the segment the car is on. */
    private fun limitAt(route: RouteCandidate, travelled: Double): Int? {
        var walked = 0.0
        for (segment in route.segments) {
            walked += segment.meters
            if (walked >= travelled) return segment.speedLimitKph
        }
        return route.segments.lastOrNull()?.speedLimitKph
    }

    /**
     * Time weighted congestion over the next [window] metres, so the figure on
     * the driving screen means the same thing as the one on the route cards.
     */
    private fun congestionOver(ahead: List<Ahead>, window: Double): Double {
        var weighted = 0.0
        var weights = 0.0
        for ((offset, segment) in ahead) {
            if (offset >= window) break
            // Only the part of the segment inside the window counts.
            val meters = minOf(segment.meters, window - offset)
            if (meters <= 0.0) continue
            val weight = meters / (1.0 - segment.congestion).coerceAtLeast(CongestionIndex.CRAWL_FLOOR)
            weighted += weight * segment.congestion
            weights += weight
        }
        return if (weights <= 0.0) 0.0 else weighted / weights
    }

    /**
     * Distance to the next heavy stretch, or null when there is none ahead or
     * the car is already in it. Telling someone about the jam they are sitting
     * in is not news.
     */
    private fun jamAhead(ahead: List<Ahead>): Double? {
        val first = ahead.firstOrNull() ?: return null
        if (first.segment.congestion >= CongestionProfile.HEAVY) return null
        for ((offset, segment) in ahead) {
            if (segment.congestion >= CongestionProfile.HEAVY) return offset
        }
        return null
    }

    /** The next manoeuvre and how far off it is, matched along the line. */
    private fun nextManeuver(route: RouteCandidate, travelled: Double): Pair<Maneuver, Double>? {
        if (route.maneuvers.isEmpty()) return null
        var best: Pair<Maneuver, Double>? = null
        for (maneuver in route.maneuvers) {
            val at = snap(route.geometry, maneuver.at).meters
            // A metre of slack, so the manoeuvre the car is on top of is not
            // announced as though it were still coming.
            if (at <= travelled + 1.0) continue
            val distance = at - travelled
            if (best == null || distance < best.second) best = maneuver to distance
        }
        return best
    }

    /** The next camera within range that faces the way the car is going. */
    private fun nextWarning(
        hazards: List<RouteHazard>,
        travelled: Double,
        heading: Double?,
    ): HazardWarning? {
        for (pinned in hazards) {
            val distance = pinned.atMeters - travelled
            if (distance < -MISSED_METERS) continue
            if (distance > WARN_METERS) return null
            if (!pinned.hazard.facing(heading)) continue
            return HazardWarning(pinned, distance.coerceAtLeast(0.0))
        }
        return null
    }
}

/** Rounded to the nearest minute, never below one while there is road left. */
fun NavState.minutesLeft(): Int =
    if (remainingMeters <= 1.0) 0 else (remainingSeconds / 60.0).roundToInt().coerceAtLeast(1)
