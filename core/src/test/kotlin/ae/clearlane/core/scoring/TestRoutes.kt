package ae.clearlane.core.scoring

import ae.clearlane.core.model.CandidateOrigin
import ae.clearlane.core.model.LatLng
import ae.clearlane.core.model.Maneuver
import ae.clearlane.core.model.ManeuverKind
import ae.clearlane.core.model.RouteCandidate
import ae.clearlane.core.model.RouteSegment
import ae.clearlane.core.model.distanceTo
import ae.clearlane.core.model.offset

/**
 * Route builders for the scoring tests.
 *
 * Durations are derived from the congestion pattern rather than passed in, so a
 * test route is always internally consistent: a route described as half jammed
 * really does take the extra time that implies. Getting that wrong would let
 * the tests pass on routes that could not exist.
 */
object TestRoutes {

    /** Roughly Dubai, so the latitude scaling in the geometry maths is realistic. */
    val DUBAI = LatLng(25.0760, 55.1400)

    /**
     * A straight route heading [bearing], one segment per entry in
     * [congestion], each [segmentMeters] long.
     */
    fun straight(
        id: String,
        congestion: List<Double>,
        segmentMeters: Double = 500.0,
        speedLimitKph: Int = 100,
        bearing: Double = 45.0,
        start: LatLng = DUBAI,
        signals: Int = 0,
        roundabouts: Int = 0,
        roadRefs: List<String> = listOf("E11"),
        origin: CandidateOrigin = CandidateOrigin.PROVIDER_PRIMARY,
        tollFils: Int = 0,
    ): RouteCandidate {
        val points = buildList {
            add(start)
            var cursor = start
            repeat(congestion.size) {
                cursor = cursor.offset(segmentMeters, bearing)
                add(cursor)
            }
        }
        return fromPoints(
            id = id,
            points = points,
            congestion = congestion,
            speedLimitKph = speedLimitKph,
            signals = signals,
            roundabouts = roundabouts,
            roadRefs = roadRefs,
            origin = origin,
            tollFils = tollFils,
        )
    }

    /** A route built from explicit geometry, for the corner tests. */
    fun fromPoints(
        id: String,
        points: List<LatLng>,
        congestion: List<Double>,
        speedLimitKph: Int = 100,
        signals: Int = 0,
        roundabouts: Int = 0,
        roadRefs: List<String> = listOf("E11"),
        origin: CandidateOrigin = CandidateOrigin.PROVIDER_PRIMARY,
        tollFils: Int = 0,
    ): RouteCandidate {
        require(points.size == congestion.size + 1) {
            "need one congestion value per segment: ${points.size} points, ${congestion.size} values"
        }
        val limitMs = speedLimitKph * 1000.0 / 3600.0
        val segments = mutableListOf<RouteSegment>()
        var meters = 0.0
        var freeFlow = 0.0
        var withTraffic = 0.0
        for (i in congestion.indices) {
            val len = points[i].distanceTo(points[i + 1])
            val c = congestion[i]
            segments += RouteSegment(
                start = points[i],
                end = points[i + 1],
                meters = len,
                congestion = c,
                speedLimitKph = speedLimitKph,
                roadRef = roadRefs.firstOrNull(),
            )
            meters += len
            val clear = len / limitMs
            freeFlow += clear
            withTraffic += clear / (1.0 - c).coerceAtLeast(CongestionIndex.CRAWL_FLOOR)
        }

        val maneuvers = buildList {
            repeat(signals) { add(Maneuver(ManeuverKind.SIGNAL, points[it % points.size])) }
            repeat(roundabouts) { add(Maneuver(ManeuverKind.ROUNDABOUT, points[it % points.size])) }
            add(Maneuver(ManeuverKind.ARRIVE, points.last()))
        }

        return RouteCandidate(
            id = id,
            geometry = points,
            segments = segments,
            durationSeconds = withTraffic,
            freeFlowSeconds = freeFlow,
            meters = meters,
            maneuvers = maneuvers,
            roadRefs = roadRefs,
            tolls = if (tollFils > 0) {
                listOf(
                    ae.clearlane.core.model.TollCrossing(
                        name = "test gate",
                        operator = "test",
                        fils = tollFils,
                        at = points[points.size / 2],
                    ),
                )
            } else {
                emptyList()
            },
            provider = "test",
            origin = origin,
        )
    }

    /** [count] segments all at the same congestion. */
    fun flat(value: Double, count: Int): List<Double> = List(count) { value }
}
