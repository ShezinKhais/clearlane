package ae.clearlane.core.scoring

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CongestionIndexTest {

    @Test
    fun `free flowing route reads zero`() {
        val p = CongestionIndex.profile(TestRoutes.straight("clear", TestRoutes.flat(0.0, 20)))
        assertEquals(0.0, p.timeWeighted, 1e-9)
        assertEquals(0.0, p.heavyShare, 1e-9)
        assertEquals(0.0, p.stopGoPer10Km, 1e-9)
        assertEquals(0.0, p.delayRatio, 1e-6)
    }

    @Test
    fun `a short jam on a long clear route weighs more by time than by distance`() {
        // Two kilometres stopped, eighteen clear. The distance weighted average
        // barely notices; the time weighted one has to, because that is where
        // the driver spends their evening.
        val pattern = TestRoutes.flat(0.95, 4) + TestRoutes.flat(0.0, 36)
        val p = CongestionIndex.profile(TestRoutes.straight("jam", pattern))

        assertEquals(0.095, p.distanceWeighted, 0.005)
        assertTrue(
            p.timeWeighted > p.distanceWeighted * 4,
            "time weighted ${p.timeWeighted} should dwarf distance weighted ${p.distanceWeighted}",
        )
        assertTrue(p.timeWeighted > 0.4, "a four minute crawl should dominate, got ${p.timeWeighted}")
    }

    @Test
    fun `hysteresis stops a wobble around the threshold counting as many jams`() {
        // Congestion oscillating either side of the single jam threshold. With
        // one threshold this would count as five separate jams.
        val wobble = listOf(0.60, 0.50, 0.60, 0.50, 0.60, 0.50, 0.60, 0.50, 0.60, 0.50)
        val p = CongestionIndex.profile(TestRoutes.straight("wobble", wobble))
        assertEquals(1, jamCount(p.stopGoPer10Km, segments = wobble.size))
    }

    @Test
    fun `clearing fully between jams counts as two`() {
        val twoJams = TestRoutes.flat(0.8, 3) + TestRoutes.flat(0.05, 6) + TestRoutes.flat(0.8, 3)
        val p = CongestionIndex.profile(TestRoutes.straight("two", twoJams))
        assertEquals(2, jamCount(p.stopGoPer10Km, segments = twoJams.size))
    }

    @Test
    fun `longest clear run finds the open stretch and ignores the rest`() {
        val pattern = TestRoutes.flat(0.0, 4) + TestRoutes.flat(0.9, 2) + TestRoutes.flat(0.0, 10)
        val p = CongestionIndex.profile(TestRoutes.straight("mixed", pattern))
        // Ten segments of 500 m is the longer of the two clear runs.
        assertEquals(5_000.0, p.longestClearMeters, 60.0)
        assertEquals(1_000.0, p.worstStretchMeters, 30.0)
    }

    @Test
    fun `heavy and severe shares are measured against distance`() {
        val pattern = TestRoutes.flat(0.5, 5) + TestRoutes.flat(0.8, 5) + TestRoutes.flat(0.1, 10)
        val p = CongestionIndex.profile(TestRoutes.straight("shares", pattern))
        assertEquals(0.5, p.heavyShare, 0.01)
        assertEquals(0.25, p.severeShare, 0.01)
    }

    @Test
    fun `tier ceilings line up with the index`() {
        val calm = CongestionIndex.profile(TestRoutes.straight("calm", TestRoutes.flat(0.05, 20)))
        val busy = CongestionIndex.profile(TestRoutes.straight("busy", TestRoutes.flat(0.5, 20)))
        assertEquals(ae.clearlane.core.model.TrafficTier.NONE, calm.tier)
        assertEquals(ae.clearlane.core.model.TrafficTier.HIGH, busy.tier)
    }

    @Test
    fun `a route with no segment detail still reports its delay`() {
        val base = TestRoutes.straight("bare", TestRoutes.flat(0.4, 10))
        val stripped = base.copy(segments = emptyList())
        val p = CongestionIndex.profile(stripped)
        assertEquals(0.0, p.timeWeighted, 1e-9)
        assertTrue(p.delayRatio > 0.5, "delay should survive with no segments, got ${p.delayRatio}")
    }

    /** Undoes the per ten kilometre normalisation so the count can be asserted. */
    private fun jamCount(per10Km: Double, segments: Int, segmentMeters: Double = 500.0): Int =
        Math.round(per10Km * (segments * segmentMeters / 10_000.0)).toInt()
}
