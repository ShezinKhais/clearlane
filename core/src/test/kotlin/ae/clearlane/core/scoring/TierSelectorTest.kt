package ae.clearlane.core.scoring

import ae.clearlane.core.model.CandidateOrigin
import ae.clearlane.core.model.RoutePreferences
import ae.clearlane.core.model.TrafficTier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TierSelectorTest {

    /** Three roughly parallel ways across town, at three levels of misery. */
    private fun threeCorridors() = listOf(
        // The motorway everyone is already sitting on.
        TestRoutes.straight(
            id = "e11",
            congestion = TestRoutes.flat(0.62, 30),
            bearing = 45.0,
            roadRefs = listOf("E11"),
        ),
        // Parallel and a little longer, moving reasonably.
        TestRoutes.straight(
            id = "e44",
            congestion = TestRoutes.flat(0.28, 60),
            bearing = 47.0,
            start = TestRoutes.DUBAI.copy(lat = TestRoutes.DUBAI.lat + 0.03),
            roadRefs = listOf("E44"),
            origin = CandidateOrigin.CORRIDOR_VIA,
        ),
        // Way out on the edge of town, completely clear, and a long way round.
        TestRoutes.straight(
            id = "e611",
            congestion = TestRoutes.flat(0.03, 90),
            bearing = 50.0,
            start = TestRoutes.DUBAI.copy(lat = TestRoutes.DUBAI.lat + 0.08),
            roadRefs = listOf("E611"),
            origin = CandidateOrigin.CORRIDOR_VIA,
        ),
    )

    @Test
    fun `each tier gets the quickest route that fits under its ceiling`() {
        val plan = RoutePlanner.plan(threeCorridors(), RoutePreferences(maxExtraMinutes = 60))
        assertNotNull(plan)

        // The jammed motorway is still the quickest, which is exactly the
        // answer an ordinary maps app gives and the thing we are arguing with.
        assertEquals("e11", plan.fastest.id)

        assertEquals("e611", plan.picks[TrafficTier.NONE]?.id)
        assertEquals("e44", plan.picks[TrafficTier.MEDIUM]?.id)
    }

    @Test
    fun `the same road is never offered under two tiers`() {
        val plan = RoutePlanner.plan(threeCorridors(), RoutePreferences(maxExtraMinutes = 60))
        assertNotNull(plan)
        val ids = plan.picks.values.map { it.id }
        assertEquals(ids.size, ids.distinct().size, "duplicate roads in $ids")
    }

    @Test
    fun `a route that is slower without being calmer is not offered`() {
        val candidates = listOf(
            TestRoutes.straight("quick", TestRoutes.flat(0.30, 20), bearing = 45.0),
            // Same congestion, longer. Worse on both counts, so there is no
            // honest reason to put it on the list.
            TestRoutes.straight(
                id = "pointless",
                congestion = TestRoutes.flat(0.30, 30),
                bearing = 60.0,
                origin = CandidateOrigin.LATERAL_VIA,
            ),
        )
        val plan = RoutePlanner.plan(candidates, RoutePreferences(maxExtraMinutes = 60))
        assertNotNull(plan)
        assertFalse("pointless" in plan.picks.values.map { it.id }, "picks were ${plan.picks}")
    }

    @Test
    fun `when the fastest route is already calm it is offered as its own tier`() {
        val candidates = listOf(
            TestRoutes.straight("empty", TestRoutes.flat(0.02, 20), bearing = 45.0),
            TestRoutes.straight(
                id = "detour",
                congestion = TestRoutes.flat(0.01, 30),
                bearing = 70.0,
                origin = CandidateOrigin.LATERAL_VIA,
            ),
        )
        val plan = RoutePlanner.plan(candidates, RoutePreferences(maxExtraMinutes = 60))
        assertNotNull(plan)
        assertEquals("empty", plan.fastest.id)
        assertEquals("empty", plan.picks[TrafficTier.NONE]?.id)
        assertTrue(plan.fastestIsCalmest, "at 3am there is no calmer alternative to find")
        assertEquals(1, plan.picks.size, "nothing else should be offered, got ${plan.picks.keys}")
    }

    @Test
    fun `a detour past the time budget is still shown but flagged`() {
        val plan = RoutePlanner.plan(
            threeCorridors(),
            RoutePreferences(maxExtraMinutes = 2, maxExtraRatio = 0.05),
        )
        assertNotNull(plan)
        val clear = plan.picks[TrafficTier.NONE]
        assertNotNull(clear, "the clear route should still be listed")
        assertTrue(clear.overBudget, "and marked as costing too much")
        assertNull(plan.recommended, "so it cannot be the recommendation")
    }

    @Test
    fun `the recommendation is the best drive among the ones we can afford`() {
        val plan = RoutePlanner.plan(threeCorridors(), RoutePreferences(maxExtraMinutes = 60))
        assertNotNull(plan)
        val recommended = plan.recommendedRoute
        assertNotNull(recommended)
        assertFalse(recommended.overBudget)
        val best = plan.picks.values.filter { !it.overBudget }.maxOf { it.score.total }
        assertEquals(best, recommended.score.total)
    }

    @Test
    fun `avoiding tolls prefers the free road over the quick one`() {
        val candidates = listOf(
            TestRoutes.straight(
                id = "tolled",
                congestion = TestRoutes.flat(0.15, 20),
                bearing = 45.0,
                tollFils = 600,
            ),
            TestRoutes.straight(
                id = "free",
                congestion = TestRoutes.flat(0.15, 24),
                bearing = 62.0,
                origin = CandidateOrigin.CORRIDOR_VIA,
            ),
        )
        val withTolls = RoutePlanner.plan(candidates, RoutePreferences(maxExtraMinutes = 60))
        val without = RoutePlanner.plan(
            candidates,
            RoutePreferences(maxExtraMinutes = 60, avoidTolls = true),
        )
        assertNotNull(withTolls)
        assertNotNull(without)
        assertEquals("tolled", withTolls.picks[TrafficTier.LOW]?.id)
        assertEquals("free", without.picks[TrafficTier.LOW]?.id)
    }

    @Test
    fun `a trip with nothing clear says so`() {
        val plan = RoutePlanner.plan(
            listOf(
                TestRoutes.straight("a", TestRoutes.flat(0.50, 20), bearing = 45.0),
                TestRoutes.straight(
                    "b",
                    TestRoutes.flat(0.30, 26),
                    bearing = 65.0,
                    origin = CandidateOrigin.CORRIDOR_VIA,
                ),
            ),
            RoutePreferences(maxExtraMinutes = 60),
        )
        assertNotNull(plan)
        assertNull(plan.picks[TrafficTier.NONE])
        assertTrue(
            plan.notes.any { "completely clear" in it },
            "expected a note about it, got ${plan.notes}",
        )
    }

    @Test
    fun `an empty candidate list plans nothing rather than crashing`() {
        assertNull(RoutePlanner.plan(emptyList()))
    }
}
