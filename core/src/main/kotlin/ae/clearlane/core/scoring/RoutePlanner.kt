package ae.clearlane.core.scoring

import ae.clearlane.core.model.CongestionSource
import ae.clearlane.core.model.RouteCandidate
import ae.clearlane.core.model.RoutePlan
import ae.clearlane.core.model.RoutePreferences
import ae.clearlane.core.model.ScoredRoute
import kotlin.math.roundToInt

/**
 * Turns a bag of candidate routes into the thing the app shows: the route a
 * normal maps app would give you, and the calmer ones worth considering
 * instead.
 *
 * Pure, synchronous and free of any provider or Android types, so the whole
 * decision can be tested against fixtures on the JVM.
 */
object RoutePlanner {

    fun plan(
        candidates: List<RouteCandidate>,
        preferences: RoutePreferences = RoutePreferences(),
    ): RoutePlan? {
        if (candidates.isEmpty()) return null

        // The provider's own routes are listed first so that when a route we
        // invented turns out to be the same road, it is the invented one that
        // gets dropped and the provider's labelling survives.
        val ordered = candidates.sortedBy { it.origin.ordinal }
        val unique = RouteDeduper.dedupe(ordered)

        val fastestCandidate = unique.minBy { it.durationSeconds }
        val shortestMeters = unique.minOf { it.meters }

        val scored = unique.map { candidate ->
            val congestion = CongestionIndex.profile(candidate)
            val extraSeconds = candidate.durationSeconds - fastestCandidate.durationSeconds
            ScoredRoute(
                candidate = candidate,
                congestion = congestion,
                score = DriveScoring.score(candidate, congestion),
                extraSeconds = extraSeconds,
                extraMeters = candidate.meters - shortestMeters,
                overBudget = !preferences.withinBudget(
                    extraSeconds = extraSeconds,
                    fastestSeconds = fastestCandidate.durationSeconds,
                ),
            )
        }

        val fastest = scored.first { it.id == fastestCandidate.id }
        val selection = TierSelector.select(scored, fastest, preferences)

        val notes = buildList {
            addAll(selection.notes)
            addAll(dataNotes(scored, preferences))
        }

        return RoutePlan(
            fastest = fastest,
            picks = selection.picks,
            recommended = selection.recommended,
            considered = scored.sortedWith(
                compareByDescending<ScoredRoute> { it.score.total }.thenBy { it.durationSeconds },
            ),
            notes = notes,
        )
    }

    /**
     * Things the driver should know that are not about any one route: thin
     * traffic coverage, and tolls big enough to change someone's mind.
     */
    private fun dataNotes(
        scored: List<ScoredRoute>,
        preferences: RoutePreferences,
    ): List<String> = buildList {
        if (scored.all { it.congestion.source != CongestionSource.LIVE }) {
            add("No live traffic for this area, so these are typical conditions for the time of day.")
        }
        val dearest = scored.maxByOrNull { it.candidate.tollFils }
        if (dearest != null && dearest.candidate.tollFils >= preferences.tollAlertFils) {
            val dh = (dearest.candidate.tollFils / 100.0).roundToInt()
            add("One option passes AED $dh of tolls.")
        }
    }
}
