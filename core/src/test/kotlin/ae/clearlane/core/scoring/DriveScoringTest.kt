package ae.clearlane.core.scoring

import ae.clearlane.core.model.DriveFactor
import ae.clearlane.core.model.LatLng
import ae.clearlane.core.model.offset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DriveScoringTest {

    private fun scoreOf(route: ae.clearlane.core.model.RouteCandidate) =
        DriveScoring.score(route, CongestionIndex.profile(route))

    @Test
    fun `an empty motorway scores near the top`() {
        val s = scoreOf(TestRoutes.straight("open", TestRoutes.flat(0.0, 40)))
        assertEquals(1.0, s.flow, 1e-9)
        assertEquals(1.0, s.steadiness, 1e-9)
        assertEquals(1.0, s.cruise, 0.02)
        assertEquals(1.0, s.junctions, 1e-9)
        // Only sweep is missing, and a dead straight motorway has none by
        // definition, so the ceiling here is ninety.
        assertEquals(90, s.total)
    }

    @Test
    fun `a jammed road scores near the bottom`() {
        val s = scoreOf(TestRoutes.straight("jam", TestRoutes.flat(0.85, 40)))
        assertTrue(s.total < 15, "expected a miserable score, got ${s.total}")
        assertEquals(DriveFactor.FLOW, s.weakest)
    }

    @Test
    fun `traffic lights every few hundred metres cost the junction term`() {
        val clear = TestRoutes.flat(0.0, 20)
        val open = scoreOf(TestRoutes.straight("open", clear))
        val urban = scoreOf(TestRoutes.straight("urban", clear, signals = 20))
        assertEquals(1.0, open.junctions, 1e-9)
        assertEquals(0.0, urban.junctions, 1e-9)
        assertTrue(urban.total < open.total)
        assertEquals(DriveFactor.JUNCTIONS, urban.weakest)
    }

    @Test
    fun `the same clear distance scores better in one run than chopped up`() {
        val oneRun = TestRoutes.flat(0.0, 10) + TestRoutes.flat(0.6, 10)
        val chopped = List(20) { if (it % 2 == 0) 0.0 else 0.6 }
        val whole = scoreOf(TestRoutes.straight("whole", oneRun))
        val diced = scoreOf(TestRoutes.straight("diced", chopped))
        assertTrue(
            whole.cruise > diced.cruise,
            "one long clear run ${whole.cruise} should beat ${diced.cruise}",
        )
        assertTrue(
            whole.steadiness > diced.steadiness,
            "and should not be constantly slowing down",
        )
        assertTrue(whole.total > diced.total)
    }

    @Test
    fun `sweeping bends count and hairpins do not`() {
        val sweeper = arc(radiusMeters = 400.0, points = 40)
        val hairpin = arc(radiusMeters = 40.0, points = 40)
        val sweeping = scoreOf(
            TestRoutes.fromPoints("sweeper", sweeper, TestRoutes.flat(0.02, sweeper.size - 1)),
        )
        val tight = scoreOf(
            TestRoutes.fromPoints("hairpin", hairpin, TestRoutes.flat(0.02, hairpin.size - 1)),
        )
        assertTrue(sweeping.sweep > 0.8, "a 400 m radius sweeper should count, got ${sweeping.sweep}")
        assertEquals(0.0, tight.sweep, 1e-9)
    }

    @Test
    fun `a good corner in a queue is still a queue`() {
        val bend = arc(radiusMeters = 400.0, points = 40)
        val jammed = scoreOf(
            TestRoutes.fromPoints("jammed bend", bend, TestRoutes.flat(0.75, bend.size - 1)),
        )
        assertEquals(0.0, jammed.sweep, 1e-9)
    }

    @Test
    fun `flow carries more weight than anything else`() {
        // Two routes that each give up one term entirely. Losing flow has to
        // hurt more than losing every corner on the route.
        val noSweep = scoreOf(TestRoutes.straight("straight", TestRoutes.flat(0.0, 30)))
        val noFlow = scoreOf(TestRoutes.straight("crawl", TestRoutes.flat(1.0, 30)))
        assertTrue(noSweep.total - noFlow.total > 50)
    }

    /**
     * Points along a circular arc of the given radius. Sweeping the same angle
     * whatever the radius keeps the arc a single continuous bend instead of
     * wrapping round the circle several times on the tight one.
     */
    private fun arc(
        radiusMeters: Double,
        points: Int,
        sweepDegrees: Double = 120.0,
    ): List<LatLng> {
        val centre = TestRoutes.DUBAI
        return (0 until points).map { i ->
            centre.offset(radiusMeters, sweepDegrees * i / (points - 1))
        }
    }
}
