package ae.clearlane.core.nav

import ae.clearlane.core.scoring.TestRoutes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoutePlaybackTest {

    private fun route() = TestRoutes.straight("play", TestRoutes.flat(0.1, 20))

    @Test
    fun `playback starts at the beginning and ends at the end`() {
        val route = route()
        val start = RoutePlayback.at(route, 0.0)
        assertNotNull(start)
        assertEquals(0.0, start.at.let { RouteTracker.locate(route, Fix(it)).travelledMeters }, 5.0)

        assertNull(
            RoutePlayback.at(route, route.durationSeconds + 1.0),
            "past the end there is no position to give",
        )
    }

    @Test
    fun `the car gets further along as time passes`() {
        val route = route()
        var last = -1.0
        for (second in 0 until route.durationSeconds.toInt() step 20) {
            val fix = RoutePlayback.at(route, second.toDouble()) ?: break
            val travelled = RouteTracker.locate(route, fix).travelledMeters
            assertTrue(travelled >= last, "went backwards at ${second}s: $travelled after $last")
            last = travelled
        }
        assertTrue(last > route.meters * 0.9, "should have nearly finished, got $last")
    }

    @Test
    fun `time is spent in the jam, not spread evenly over the distance`() {
        // First half stopped, second half open.
        val route = TestRoutes.straight("mixed", TestRoutes.flat(0.85, 10) + TestRoutes.flat(0.0, 10))

        val halfway = RoutePlayback.at(route, route.durationSeconds / 2.0)
        assertNotNull(halfway)
        val travelled = RouteTracker.locate(route, halfway).travelledMeters

        // Half the time gone, but most of it went on the jammed half, so the car
        // is nowhere near halfway down the road.
        assertTrue(
            travelled < route.meters * 0.45,
            "half the time should not be half the distance here, got $travelled of ${route.meters}",
        )
    }

    @Test
    fun `speed reflects the congestion underneath the car`() {
        val route = TestRoutes.straight(
            "mixed",
            TestRoutes.flat(0.8, 10) + TestRoutes.flat(0.0, 10),
            speedLimitKph = 100,
        )
        val inJam = RoutePlayback.at(route, 30.0)
        val clear = RoutePlayback.at(route, route.durationSeconds * 0.95)

        assertNotNull(inJam)
        assertNotNull(clear)
        assertTrue(inJam.speedKph!! < 30.0, "a jam should crawl, got ${inJam.speedKph}")
        assertTrue(clear.speedKph!! > 90.0, "an open road should run, got ${clear.speedKph}")
    }

    @Test
    fun `heading points the way the route goes`() {
        // TestRoutes lays a straight line on a bearing of 45 degrees.
        val fix = RoutePlayback.at(route(), 60.0)
        assertNotNull(fix)
        assertEquals(45.0, fix.headingDeg ?: -1.0, 3.0)
    }

    @Test
    fun `a speed factor shortens the playback without changing the road`() {
        val route = route()
        assertEquals(
            route.durationSeconds / 8.0,
            RoutePlayback.durationSeconds(route, speedFactor = 8.0),
            0.001,
        )

        val realTime = RoutePlayback.at(route, 240.0)
        val fast = RoutePlayback.at(route, 30.0, speedFactor = 8.0)
        assertNotNull(realTime)
        assertNotNull(fast)
        assertEquals(realTime.at.lat, fast.at.lat, 1e-9)
        assertEquals(realTime.at.lon, fast.at.lon, 1e-9)
    }

    @Test
    fun `a route with no segment detail still plays back`() {
        val bare = route().copy(segments = emptyList())
        val fix = RoutePlayback.at(bare, bare.durationSeconds / 2.0)

        assertNotNull(fix)
        assertEquals(bare.meters / 2.0, RouteTracker.locate(bare, fix).travelledMeters, bare.meters * 0.05)
    }
}
