package ae.clearlane.core.model

/**
 * How much traffic the driver is willing to sit in. The ceilings are on the
 * time weighted congestion index, so they read as "the share of your drive
 * spent below free flowing speed".
 *
 * [HIGH] exists because someone in a hurry still deserves better than the
 * default answer, so a tier is only ever offered when it actually beats the
 * fastest route on congestion. See [ae.clearlane.core.scoring.TierSelector].
 */
enum class TrafficTier(val ceiling: Double, val label: String) {
    NONE(0.10, "None"),
    LOW(0.22, "Low"),
    MEDIUM(0.38, "Medium"),
    HIGH(0.55, "High"),
    ;

    companion object {
        /** The loosest tier a given congestion figure still satisfies. */
        fun of(congestion: Double): TrafficTier? =
            entries.firstOrNull { congestion <= it.ceiling }
    }
}

/**
 * What the driver is prepared to trade. Nothing here changes the drive score,
 * which is purely about how good the drive is. These only decide what gets
 * flagged as costing too much.
 */
data class RoutePreferences(
    /** Hard ceiling on extra time against the fastest route. */
    val maxExtraMinutes: Int = 20,
    /** And a proportional one, so short hops do not get silly detours. */
    val maxExtraRatio: Double = 0.75,
    /** Above this, a toll is worth mentioning in the summary. */
    val tollAlertFils: Int = 1_000,
    val avoidTolls: Boolean = false,
) {
    fun withinBudget(extraSeconds: Double, fastestSeconds: Double): Boolean =
        extraSeconds <= maxExtraMinutes * 60.0 &&
            extraSeconds <= fastestSeconds * maxExtraRatio
}

/**
 * How a route is flowing, measured over its whole length.
 *
 * [timeWeighted] is the headline number. It weights each segment by the time
 * you spend on it rather than its length, because two kilometres of car park
 * is worse than twenty of open motorway and the distance weighted figure says
 * the opposite.
 */
data class CongestionProfile(
    val timeWeighted: Double,
    val distanceWeighted: Double,
    /** Share of the distance at [HEAVY] or worse. */
    val heavyShare: Double,
    /** Share of the distance at [SEVERE] or worse. */
    val severeShare: Double,
    /** Times the route drops into and back out of a jam, per ten kilometres. */
    val stopGoPer10Km: Double,
    /** [durationSeconds] over [freeFlowSeconds], minus one. */
    val delayRatio: Double,
    /** Longest single jammed run, in metres. */
    val worstStretchMeters: Double,
    /** Longest run that stays clear, in metres. */
    val longestClearMeters: Double,
    val source: CongestionSource,
) {
    val tier: TrafficTier? get() = TrafficTier.of(timeWeighted)

    companion object {
        /** A segment at or above this is "heavy". */
        const val HEAVY = 0.45

        /** And at or above this, "severe". Below [CLEAR] counts as flowing. */
        const val SEVERE = 0.70
        const val CLEAR = 0.20

        /** Hysteresis band for counting stop and go, so noise does not inflate it. */
        const val JAM_ENTER = 0.55
        const val JAM_EXIT = 0.35
    }
}

/** The five things the drive score is made of. */
enum class DriveFactor {
    FLOW,
    STEADINESS,
    CRUISE,
    JUNCTIONS,
    SWEEP,
}

/**
 * The parts of the drive score, kept separate so the UI can explain itself
 * instead of showing one unexplained number out of a hundred.
 *
 * [weakest] is decided by which term costs the total the most points, not by
 * which term is numerically lowest. On a jammed motorway the corner and open
 * stretch terms both read zero, but they read zero *because* of the traffic,
 * and telling the driver their problem is a shortage of corners would be
 * useless.
 */
data class DriveScore(
    val total: Int,
    val flow: Double,
    val steadiness: Double,
    val cruise: Double,
    val junctions: Double,
    val sweep: Double,
    val weakest: DriveFactor,
)

/** A candidate once we have had an opinion about it. */
data class ScoredRoute(
    val candidate: RouteCandidate,
    val congestion: CongestionProfile,
    val score: DriveScore,
    /** Seconds slower than the fastest candidate. Zero for the fastest itself. */
    val extraSeconds: Double,
    /** Metres longer than the shortest candidate. May be negative. */
    val extraMeters: Double,
    /** True when [extraSeconds] breaches [RoutePreferences]. Still shown, just flagged. */
    val overBudget: Boolean,
) {
    val id: String get() = candidate.id
    val durationSeconds: Double get() = candidate.durationSeconds
    val meters: Double get() = candidate.meters
}

/**
 * The answer to one routing request: the route a normal maps app would give
 * you, and the calmer ones we found instead.
 */
data class RoutePlan(
    /** Quickest route given the traffic, which is what other apps show first. */
    val fastest: ScoredRoute,
    /** The quickest route we found at each traffic level, where one exists. */
    val picks: Map<TrafficTier, ScoredRoute>,
    /** Which tier we would actually pick, on drive score. */
    val recommended: TrafficTier?,
    /** Every candidate considered, best drive score first. For the "all routes" list. */
    val considered: List<ScoredRoute>,
    /** Plain sentences about anything the driver should know. */
    val notes: List<String> = emptyList(),
) {
    val recommendedRoute: ScoredRoute? get() = recommended?.let { picks[it] }

    /**
     * True when the quick way happens to also be the calm way, which does
     * happen at four in the morning and is worth saying out loud.
     */
    val fastestIsCalmest: Boolean
        get() = picks.values.none { it.congestion.timeWeighted < fastest.congestion.timeWeighted - 1e-9 }
}
