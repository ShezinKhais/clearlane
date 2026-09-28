package ae.clearlane.core.model

/**
 * One stretch of road between two consecutive shape points, carrying whatever
 * the provider told us about how it is flowing right now.
 *
 * [congestion] is normalised so that 0.0 is free flow and 1.0 is stopped, which
 * is the one number the whole app is built on. Every provider adapter is
 * responsible for mapping its own scale onto this one, and for saying so in
 * [ae.clearlane.core.model.CongestionSource].
 */
data class RouteSegment(
    val start: LatLng,
    val end: LatLng,
    val meters: Double,
    val congestion: Double,
    val speedLimitKph: Int? = null,
    val roadRef: String? = null,
) {
    init {
        require(meters >= 0.0) { "segment length cannot be negative" }
        require(congestion in 0.0..1.0) { "congestion must be 0..1, was $congestion" }
    }
}

/** Where a segment's congestion figure came from, so the UI can be honest. */
enum class CongestionSource {
    /** Live probe data, mapped from the provider's own congestion scale. */
    LIVE,

    /** Typical conditions for this time of week, no live coverage. */
    TYPICAL,

    /** Nothing known. Treated as free flowing but reported as unknown. */
    UNKNOWN,
}

enum class ManeuverKind {
    TURN,
    ROUNDABOUT,
    SIGNAL,
    MERGE,
    FORK,
    EXIT,
    CONTINUE,
    ARRIVE,
}

data class Maneuver(
    val kind: ManeuverKind,
    val at: LatLng,
    val instruction: String = "",
) {
    /**
     * Whether this is the kind of thing that makes you stop or slow right down.
     * Roundabouts count: they interrupt the drive even though nobody queues at
     * an empty one.
     */
    val interrupts: Boolean
        get() = kind == ManeuverKind.SIGNAL ||
            kind == ManeuverKind.ROUNDABOUT ||
            kind == ManeuverKind.TURN
}

/** A toll the route passes through, priced for the time of the request. */
data class TollCrossing(
    val name: String,
    val operator: String,
    val fils: Int,
    val at: LatLng?,
) {
    val dirhams: Double get() = fils / 100.0
}

/**
 * One way of getting from A to B, as handed back by a provider and before we
 * have had an opinion about it.
 */
data class RouteCandidate(
    val id: String,
    val geometry: List<LatLng>,
    val segments: List<RouteSegment>,
    /** Travel time under the conditions on the road right now. */
    val durationSeconds: Double,
    /** Travel time the same route would take with nothing in the way. */
    val freeFlowSeconds: Double,
    val meters: Double,
    val maneuvers: List<Maneuver> = emptyList(),
    /** Road numbers in the order they are used, e.g. ["E11", "E311"]. */
    val roadRefs: List<String> = emptyList(),
    val tolls: List<TollCrossing> = emptyList(),
    val source: CongestionSource = CongestionSource.LIVE,
    val provider: String = "unknown",
    /** How this candidate was asked for, kept for debugging the generator. */
    val origin: CandidateOrigin = CandidateOrigin.PROVIDER_PRIMARY,
) {
    init {
        require(durationSeconds > 0.0) { "duration must be positive" }
        require(freeFlowSeconds > 0.0) { "free flow duration must be positive" }
        require(meters > 0.0) { "route length must be positive" }
    }

    val tollFils: Int get() = tolls.sumOf { it.fils }

    val from: LatLng get() = geometry.first()
    val to: LatLng get() = geometry.last()

    /** Label for the route sheet, e.g. "via E611, E311". */
    fun viaLabel(maxRefs: Int = 2): String {
        val refs = roadRefs.distinct().take(maxRefs)
        return if (refs.isEmpty()) "direct" else "via " + refs.joinToString(", ")
    }
}

/** How a candidate came to exist. Useful when tuning the generator. */
enum class CandidateOrigin {
    /** The provider's own best answer, which is the fastest-with-traffic route. */
    PROVIDER_PRIMARY,

    /** One of the provider's own alternatives. */
    PROVIDER_ALTERNATIVE,

    /** Routed through a named corridor we know runs parallel. */
    CORRIDOR_VIA,

    /** Routed through a blind lateral offset, for places we have no corridors for. */
    LATERAL_VIA,

    /** Loaded from a bundled fixture. */
    FIXTURE,
}
