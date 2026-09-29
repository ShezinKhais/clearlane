package ae.clearlane.core.nav

import ae.clearlane.core.model.Hazard
import ae.clearlane.core.model.HazardKind
import ae.clearlane.core.model.offset
import ae.clearlane.core.scoring.TestRoutes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RouteTrackerTest {

    /** Twenty segments of 500 m, clear, heading north east from Dubai. */
    private fun clearRoute() = TestRoutes.straight("clear", TestRoutes.flat(0.05, 20))

    @Test
    fun `a fix on the line reads as on route with no offset`() {
        val route = clearRoute()
        val onLine = route.geometry[4]
        val state = RouteTracker.locate(route, Fix(onLine))

        assertTrue(state.onRoute)
        assertTrue(state.offRouteMeters < 1.0, "expected no offset, got ${state.offRouteMeters}")
        assertEquals(2_000.0, state.travelledMeters, 5.0)
        assertEquals(route.meters - 2_000.0, state.remainingMeters, 5.0)
    }

    @Test
    fun `a fix beside the line still snaps to it`() {
        val route = clearRoute()
        // Thirty metres to one side of a shape point, which is ordinary drift
        // on a motorway.
        val beside = route.geometry[6].offset(30.0, 135.0)
        val state = RouteTracker.locate(route, Fix(beside))

        assertTrue(state.onRoute, "30 m off should still be on the route")
        assertEquals(30.0, state.offRouteMeters, 2.0)
        assertEquals(3_000.0, state.travelledMeters, 40.0)
    }

    @Test
    fun `a fix a few hundred metres away is off route`() {
        val route = clearRoute()
        val away = route.geometry[6].offset(400.0, 135.0)
        val state = RouteTracker.locate(route, Fix(away))

        assertFalse(state.onRoute)
        assertEquals(400.0, state.offRouteMeters, 20.0)
    }

    @Test
    fun `clearing the jam drops the arrival time faster than the distance does`() {
        // Jammed for the first half, open for the second.
        val pattern = TestRoutes.flat(0.8, 10) + TestRoutes.flat(0.0, 10)
        val route = TestRoutes.straight("mixed", pattern)

        val atJamEnd = RouteTracker.locate(route, Fix(route.geometry[10]))
        val distanceLeft = atJamEnd.remainingMeters / route.meters
        val timeLeft = atJamEnd.remainingSeconds / route.durationSeconds

        assertEquals(0.5, distanceLeft, 0.02)
        // Half the distance, but the expensive half is behind: the time left has
        // to be a much smaller share than the distance left.
        assertTrue(
            timeLeft < 0.25,
            "half way by distance should be well past half way by time, got $timeLeft",
        )
    }

    @Test
    fun `congestion ahead ignores the jam already behind`() {
        val pattern = TestRoutes.flat(0.85, 6) + TestRoutes.flat(0.02, 14)
        val route = TestRoutes.straight("behind", pattern)

        val atStart = RouteTracker.locate(route, Fix(route.geometry.first()))
        val pastJam = RouteTracker.locate(route, Fix(route.geometry[6]))

        assertTrue(atStart.congestionAhead > 0.6, "start is in the jam, got ${atStart.congestionAhead}")
        assertTrue(pastJam.congestionAhead < 0.1, "past the jam it is clear, got ${pastJam.congestionAhead}")
    }

    @Test
    fun `a jam further on is announced with the distance to it`() {
        val pattern = TestRoutes.flat(0.02, 8) + TestRoutes.flat(0.8, 6) + TestRoutes.flat(0.02, 6)
        val route = TestRoutes.straight("ahead", pattern)
        val state = RouteTracker.locate(route, Fix(route.geometry.first()))

        assertEquals(4_000.0, state.jamAheadMeters ?: -1.0, 60.0)
    }

    @Test
    fun `sitting in the jam is not reported as a jam ahead`() {
        val route = TestRoutes.straight("inside", TestRoutes.flat(0.8, 10))
        val state = RouteTracker.locate(route, Fix(route.geometry[2]))

        assertNull(state.jamAheadMeters, "already stopped in it, so there is nothing to warn about")
    }

    @Test
    fun `the posted limit comes off the segment the car is on`() {
        val route = TestRoutes.straight("limit", TestRoutes.flat(0.1, 10), speedLimitKph = 120)
        val state = RouteTracker.locate(route, Fix(route.geometry[3]))

        assertEquals(120, state.speedLimitKph)
    }

    @Test
    fun `speeding needs a tolerance before it lights up`() {
        val route = TestRoutes.straight("limit", TestRoutes.flat(0.1, 10), speedLimitKph = 100)
        val at = route.geometry[3]

        val holding = RouteTracker.locate(route, Fix(at, speedKph = 102.0))
        val over = RouteTracker.locate(route, Fix(at, speedKph = 118.0))

        assertFalse(holding.overLimit, "a couple of km/h of GPS noise is not speeding")
        assertTrue(over.overLimit)
    }

    @Test
    fun `no limit on the road means no speeding verdict`() {
        val route = TestRoutes.straight("nolimit", TestRoutes.flat(0.1, 10), speedLimitKph = 0)
            .let { it.copy(segments = it.segments.map { s -> s.copy(speedLimitKph = null) }) }
        val state = RouteTracker.locate(route, Fix(route.geometry[3], speedKph = 180.0))

        assertNull(state.speedLimitKph)
        assertFalse(state.overLimit, "with nothing to compare against, say nothing")
    }

    @Test
    fun `a camera ahead is warned about and one behind is not`() {
        val route = clearRoute()
        val camera = Hazard(HazardKind.FIXED_CAMERA, route.geometry[8], speedLimitKph = 100)
        val pinned = HazardIndex.pin(route, listOf(camera))

        // 3.5 km in: the camera at 4 km is 500 m ahead.
        val approaching = RouteTracker.locate(route, Fix(midway(route, 3_500.0)), pinned)
        assertNotNull(approaching.warning)
        assertEquals(500.0, approaching.warning.meters, 60.0)

        // 6 km in: long past it.
        val past = RouteTracker.locate(route, Fix(route.geometry[12]), pinned)
        assertNull(past.warning, "a camera two kilometres back is not a warning")
    }

    @Test
    fun `a camera facing the other way is not warned about`() {
        val route = clearRoute()
        // The route runs north east, so a camera facing south west watches the
        // other carriageway.
        val oncoming = Hazard(HazardKind.FIXED_CAMERA, route.geometry[8], bearingDeg = 225.0)
        val pinned = HazardIndex.pin(route, listOf(oncoming))

        val state = RouteTracker.locate(route, Fix(midway(route, 3_500.0), headingDeg = 45.0), pinned)
        assertNull(state.warning)
    }

    @Test
    fun `a camera with no recorded bearing is warned about either way`() {
        val route = clearRoute()
        val unknown = Hazard(HazardKind.FIXED_CAMERA, route.geometry[8])
        val pinned = HazardIndex.pin(route, listOf(unknown))

        val state = RouteTracker.locate(route, Fix(midway(route, 3_500.0), headingDeg = 225.0), pinned)
        assertNotNull(state.warning, "no bearing means take the cautious reading")
    }

    @Test
    fun `the nearest camera wins when two are on the road ahead`() {
        val route = clearRoute()
        val near = Hazard(HazardKind.FIXED_CAMERA, route.geometry[8], speedLimitKph = 100)
        val far = Hazard(HazardKind.FIXED_CAMERA, route.geometry[14], speedLimitKph = 80)
        val pinned = HazardIndex.pin(route, listOf(far, near))

        val state = RouteTracker.locate(route, Fix(midway(route, 3_500.0)), pinned)

        assertEquals(100, state.warning?.hazard?.hazard?.speedLimitKph)
    }

    @Test
    fun `a route with no segment detail still gives a time remaining`() {
        val bare = clearRoute().copy(segments = emptyList())
        val state = RouteTracker.locate(bare, Fix(bare.geometry[10]))

        assertEquals(bare.durationSeconds / 2.0, state.remainingSeconds, bare.durationSeconds * 0.05)
        assertTrue(state.minutesLeft() >= 1)
    }

    @Test
    fun `arriving reads as no distance and no minutes`() {
        val route = clearRoute()
        val state = RouteTracker.locate(route, Fix(route.geometry.last()))

        assertEquals(0.0, state.remainingMeters, 1.0)
        assertEquals(0, state.minutesLeft())
    }

    /** A point [meters] along the route, for fixes that are not on a vertex. */
    private fun midway(route: ae.clearlane.core.model.RouteCandidate, meters: Double) =
        route.geometry.first().offset(meters, 45.0)
}
