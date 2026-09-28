package ae.clearlane.core.scoring

import ae.clearlane.core.model.RoutePreferences
import ae.clearlane.core.model.ScoredRoute
import ae.clearlane.core.model.TrafficTier

/**
 * Picks which route to offer at each traffic level.
 *
 * The rule for a tier is "the quickest route whose congestion fits under this
 * ceiling", not "the calmest route we can find". That is the whole point: a
 * driver asking for a low traffic route does not want the most scenic detour in
 * the emirate, they want the fastest way home that is not a car park.
 *
 * Two guards keep the list honest:
 *
 *  - A tier is dropped when its answer is the same road as another tier's, so
 *    the sheet never shows the same line twice under different names.
 *  - A tier is dropped when it is slower than the fastest route and neither
 *    calmer nor cheaper. Something worse on every count is padding.
 *
 * A consequence worth knowing about: when the fastest route is already calm,
 * it shows up as its own tier and the looser tiers disappear. That is correct.
 * At 3am there is no low traffic alternative to take, because there is no
 * traffic.
 */
object TierSelector {

    /**
     * How much calmer a detour has to be before it earns a place on the list,
     * as an absolute difference in the congestion index. Five points is about
     * the width of the band a driver can actually feel.
     */
    const val MIN_GAIN = 0.05

    data class Selection(
        val picks: Map<TrafficTier, ScoredRoute>,
        val recommended: TrafficTier?,
        val notes: List<String>,
    )

    fun select(
        candidates: List<ScoredRoute>,
        fastest: ScoredRoute,
        preferences: RoutePreferences = RoutePreferences(),
    ): Selection {
        val notes = mutableListOf<String>()
        val picks = LinkedHashMap<TrafficTier, ScoredRoute>()
        val usedRoutes = mutableSetOf<String>()

        // Tiers are visited tightest first so that when one route satisfies
        // several of them it is listed under the strictest one it qualifies
        // for, which is the most flattering true thing to say about it.
        for (tier in TrafficTier.entries) {
            val eligible = candidates.filter { it.congestion.timeWeighted <= tier.ceiling }
            if (eligible.isEmpty()) continue

            val pick = eligible.minWithOrNull(quickest(preferences)) ?: continue
            if (pick.id in usedRoutes) continue

            // Drop a route only when it is worse on every count that matters:
            // slower than the fastest, no calmer, and no cheaper. A toll free
            // road that takes a few minutes longer is a real offer, not padding.
            val isFastest = pick.id == fastest.id
            val calmer = fastest.congestion.timeWeighted - pick.congestion.timeWeighted >= MIN_GAIN
            val cheaper = pick.candidate.tollFils < fastest.candidate.tollFils
            if (!isFastest && !calmer && !cheaper) continue

            picks[tier] = pick
            usedRoutes += pick.id
        }

        if (TrafficTier.NONE !in picks && candidates.isNotEmpty()) {
            notes += "Nothing on this trip is completely clear right now."
        }

        val affordable = picks.filterValues { !it.overBudget }
        if (affordable.isEmpty() && picks.isNotEmpty()) {
            notes += "Every calmer route costs more time than you allowed, so they are " +
                "shown but not recommended."
        }

        // Recommend on drive quality, then break ties on time. The recommended
        // route has to be one the driver said they would accept, which is why
        // this looks at the affordable set and not all of the picks.
        val recommended = affordable.entries
            .sortedWith(
                compareByDescending<Map.Entry<TrafficTier, ScoredRoute>> { it.value.score.total }
                    .thenBy { it.value.durationSeconds },
            )
            .firstOrNull()
            ?.key

        val overBudget = picks.values.count { it.overBudget }
        if (overBudget > 0 && affordable.isNotEmpty()) {
            notes += if (overBudget == 1) {
                "One option is over your extra time limit."
            } else {
                "$overBudget options are over your extra time limit."
            }
        }

        return Selection(picks, recommended, notes)
    }

    /**
     * Quickest first. When the driver has asked to keep off the tolls, a
     * toll free route wins even if it is slower, because that preference is a
     * statement about money rather than a tie break.
     */
    private fun quickest(preferences: RoutePreferences): Comparator<ScoredRoute> =
        if (preferences.avoidTolls) {
            compareBy<ScoredRoute> { it.candidate.tollFils > 0 }
                .thenBy { it.durationSeconds }
        } else {
            compareBy { it.durationSeconds }
        }
}
