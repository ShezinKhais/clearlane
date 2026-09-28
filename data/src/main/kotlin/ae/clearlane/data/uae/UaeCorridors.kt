package ae.clearlane.data.uae

import ae.clearlane.core.model.LatLng
import ae.clearlane.core.model.bearingDelta
import ae.clearlane.core.model.bearingTo
import ae.clearlane.core.model.distanceTo
import ae.clearlane.core.model.midpointTo
import ae.clearlane.core.model.offset
import kotlin.math.abs

/**
 * The parallel highways of the northern emirates, which are the reason this
 * app can work at all.
 *
 * Most cities give you one sensible way across and a tangle of back streets.
 * Dubai gives you five motorways running the same direction, a few kilometres
 * apart, and on a bad evening they are in wildly different states: Sheikh Zayed
 * Road can be stationary while Emirates Road, twelve kilometres inland, is
 * doing 110. A routing API asked for "alternatives" will not offer you that,
 * because it is twenty minutes longer and its job is to be quick. Asking it
 * directly for a route through each corridor is how we find them.
 *
 * ### About the coordinates
 *
 * The anchors below are approximate points on each road, and they are only
 * used to bias a routing request. The router snaps them to the real road
 * network, so an anchor a few hundred metres off still produces a route down
 * the right corridor. They are not presented to the driver as fact and nothing
 * is measured against them. They should still be replaced with surveyed points
 * before anyone relies on the corridor labelling.
 */
data class Corridor(
    /** Road number as it appears on the signs, e.g. "E311". */
    val ref: String,
    val name: String,
    /** Approximate points along the road, ordered south west to north east. */
    val anchors: List<LatLng>,
    /** True when the road has toll gates on it. */
    val tolled: Boolean = false,
) {
    /** Overall direction of the road, from one end to the other. */
    val bearing: Double
        get() = anchors.first().bearingTo(anchors.last())

    /** The anchor nearest [point]. */
    fun nearestAnchor(point: LatLng): LatLng = anchors.minBy { it.distanceTo(point) }
}

/** A corridor and the point to route through to get onto it. */
data class CorridorVia(
    val corridor: Corridor,
    val point: LatLng,
    /** How far the corridor sits off the direct line, in metres. */
    val offsetMeters: Double,
)

object UaeCorridors {

    /**
     * Roughly the UAE plus a margin, used to decide whether the corridor list
     * applies at all. Outside it the generator falls back to blind lateral
     * offsets, which work anywhere but find less.
     */
    fun inServiceArea(point: LatLng): Boolean =
        point.lat in 22.4..26.6 && point.lon in 51.0..56.6

    /**
     * The Dubai to Abu Dhabi and Dubai to Sharjah corridors, inland to coastal.
     *
     * Ordered as they sit on the ground, coast first, because that is the order
     * a driver thinks about them in.
     */
    val ALL: List<Corridor> = listOf(
        Corridor(
            ref = "E11",
            name = "Sheikh Zayed Road",
            anchors = listOf(
                LatLng(24.99, 55.08),
                LatLng(25.07, 55.14),
                LatLng(25.13, 55.20),
                LatLng(25.19, 55.26),
                LatLng(25.23, 55.29),
            ),
            tolled = true,
        ),
        Corridor(
            ref = "E44",
            name = "Al Khail Road",
            anchors = listOf(
                LatLng(25.01, 55.13),
                LatLng(25.08, 55.19),
                LatLng(25.14, 55.25),
                LatLng(25.20, 55.32),
            ),
            tolled = true,
        ),
        Corridor(
            ref = "E311",
            name = "Sheikh Mohammed Bin Zayed Road",
            anchors = listOf(
                LatLng(24.96, 55.15),
                LatLng(25.03, 55.22),
                LatLng(25.10, 55.29),
                LatLng(25.17, 55.37),
                LatLng(25.25, 55.45),
            ),
        ),
        Corridor(
            ref = "E611",
            name = "Emirates Road",
            anchors = listOf(
                LatLng(24.94, 55.21),
                LatLng(25.02, 55.30),
                LatLng(25.10, 55.38),
                LatLng(25.19, 55.47),
                LatLng(25.28, 55.53),
            ),
        ),
        Corridor(
            ref = "E66",
            name = "Dubai Al Ain Road",
            anchors = listOf(
                LatLng(25.16, 55.29),
                LatLng(25.08, 55.40),
                LatLng(24.99, 55.52),
            ),
        ),
        Corridor(
            ref = "E10",
            name = "Al Ittihad Road",
            anchors = listOf(
                LatLng(25.25, 55.32),
                LatLng(25.29, 55.36),
                LatLng(25.33, 55.40),
            ),
        ),
    )

    /** Beyond this from the direct line, a corridor is a different journey. */
    const val MAX_OFFSET_METERS = 30_000.0

    /** A corridor pointing more than this away from the trip is no use. */
    const val MAX_BEARING_DIFFERENCE = 55.0

    /**
     * Corridors worth trying for a trip, nearest to the direct line first.
     *
     * A corridor qualifies when it runs roughly the same way as the trip and
     * passes somewhere near the middle of it. Both tests matter: without the
     * bearing test a trip across town would be offered the road that runs at
     * right angles to it, and without the distance test a trip to the airport
     * would be offered a road in Abu Dhabi.
     */
    fun viaPointsFor(
        origin: LatLng,
        destination: LatLng,
        limit: Int = 4,
    ): List<CorridorVia> {
        if (!inServiceArea(origin) && !inServiceArea(destination)) return emptyList()
        val tripBearing = origin.bearingTo(destination)
        val midpoint = origin.midpointTo(destination)
        val tripLength = origin.distanceTo(destination)

        return ALL.mapNotNull { corridor ->
            // A road is useful whichever way round it is drawn, so compare
            // against both its own direction and the reverse of it.
            val aligned = minOf(
                abs(bearingDelta(tripBearing, corridor.bearing)),
                abs(bearingDelta(tripBearing, (corridor.bearing + 180.0) % 360.0)),
            )
            if (aligned > MAX_BEARING_DIFFERENCE) return@mapNotNull null

            val anchor = corridor.nearestAnchor(midpoint)
            val offset = anchor.distanceTo(midpoint)
            if (offset > MAX_OFFSET_METERS) return@mapNotNull null
            // On a short hop, a corridor further away than the trip itself is
            // a detour rather than an alternative.
            if (tripLength > 0 && offset > tripLength) return@mapNotNull null

            CorridorVia(corridor, anchor, offset)
        }
            .sortedBy { it.offsetMeters }
            .take(limit)
    }

    /**
     * Detour points either side of the direct line, for anywhere we have no
     * corridors for. Cruder than the corridor list and it finds less, but it
     * costs nothing to try and it works outside the UAE.
     */
    fun lateralOffsets(
        origin: LatLng,
        destination: LatLng,
        distancesMeters: List<Double> = listOf(3_000.0, 8_000.0),
    ): List<LatLng> {
        val midpoint = origin.midpointTo(destination)
        val perpendicular = (origin.bearingTo(destination) + 90.0) % 360.0
        val tripLength = origin.distanceTo(destination)
        return distancesMeters
            .filter { it < tripLength / 2.0 }
            .flatMap { d ->
                listOf(
                    midpoint.offset(d, perpendicular),
                    midpoint.offset(d, (perpendicular + 180.0) % 360.0),
                )
            }
    }
}
