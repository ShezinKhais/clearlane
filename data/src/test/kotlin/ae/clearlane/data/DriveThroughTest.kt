package ae.clearlane.data

import ae.clearlane.core.nav.RoutePlayback
import ae.clearlane.core.nav.RouteTracker
import ae.clearlane.data.fixture.FixtureRouteProvider
import ae.clearlane.data.hazard.BundledHazards
import ae.clearlane.data.hazard.HazardService
import ae.clearlane.data.provider.RouteRequest
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Drives the demo trips end to end, the way the app does.
 *
 * This is the test that would have caught the thing most likely to go wrong
 * quietly: cameras that load, parse and pin perfectly well, and then never
 * appear on screen because none of them happen to lie on any route the demo
 * can produce. Everything below the UI can be right and the feature still be
 * invisible.
 */
class DriveThroughTest {

    private fun fixtures() = FixtureRouteProvider {
        File("src/main/assets/clearlane/fixtures.json").readText()
    }

    private fun bundled() = BundledHazards {
        File("src/main/assets/clearlane/hazards.json").readText()
    }

    @Test
    fun `the demo trips pass real cameras`() = runTest {
        val provider = fixtures()
        val service = HazardService(listOf(bundled()))

        var total = 0
        for (trip in provider.tripSummaries()) {
            val routes = provider.routes(RouteRequest(trip.origin, trip.destination))
            total += service.forRoutes(routes).byRoute.values.sumOf { it.size }
        }

        assertTrue(
            total > 0,
            "no camera lies on any demo route, so the warning can never be seen in the demo",
        )
    }

    @Test
    fun `driving the recommended evening route raises a camera warning`() = runTest {
        val provider = fixtures()
        val trip = provider.tripSummaries().first { it.id == "marina-airport-evening" }
        val routes = provider.routes(RouteRequest(trip.origin, trip.destination))
        val pinned = HazardService(listOf(bundled())).forRoutes(routes)

        // Whichever route has cameras on it: the point is that driving one of
        // them produces a warning, not which one it is.
        val route = routes.first { pinned.byRoute[it.id].orEmpty().isNotEmpty() }
        val hazards = pinned.byRoute.getValue(route.id)

        var seen = 0
        var second = 0.0
        while (second < route.durationSeconds) {
            val fix = RoutePlayback.at(route, second) ?: break
            if (RouteTracker.locate(route, fix, hazards).warning != null) seen++
            second += 5.0
        }

        assertTrue(seen > 0, "drove past ${hazards.size} cameras without one warning")
    }

    @Test
    fun `the route ahead stays on the road the whole way`() = runTest {
        val provider = fixtures()
        val trip = provider.tripSummaries().first()
        val route = provider.routes(RouteRequest(trip.origin, trip.destination)).first()

        var worst = 0.0
        var last = -1.0
        var second = 0.0
        while (second < route.durationSeconds) {
            val fix = RoutePlayback.at(route, second) ?: break
            val state = RouteTracker.locate(route, fix)

            worst = maxOf(worst, state.offRouteMeters)
            assertTrue(
                state.travelledMeters >= last - 1.0,
                "went backwards at ${second}s: ${state.travelledMeters} after $last",
            )
            last = state.travelledMeters
            second += 10.0
        }

        // A replayed position is generated from the line itself, so anything
        // more than a metre off means the snapping is wrong rather than that
        // the driver has wandered.
        assertTrue(worst < 1.0, "playback drifted ${worst} m off its own route")
        assertTrue(last > route.meters * 0.9, "did not reach the end, stopped at $last")
    }

    @Test
    fun `the time remaining falls all the way to nothing`() = runTest {
        val provider = fixtures()
        val trip = provider.tripSummaries().first()
        val route = provider.routes(RouteRequest(trip.origin, trip.destination)).first()

        val start = RouteTracker.locate(route, RoutePlayback.at(route, 0.0)!!)
        val nearEnd = RoutePlayback.at(route, route.durationSeconds * 0.99)
        assertNotNull(nearEnd)
        val end = RouteTracker.locate(route, nearEnd)

        assertTrue(start.remainingSeconds > end.remainingSeconds)
        assertTrue(
            end.remainingSeconds < route.durationSeconds * 0.05,
            "nearly there should read as nearly there, got ${end.remainingSeconds}s",
        )
    }

    @Test
    fun `the posted limit is available to the driving screen`() = runTest {
        val provider = fixtures()
        val trip = provider.tripSummaries().first()
        val route = provider.routes(RouteRequest(trip.origin, trip.destination)).first()

        val state = RouteTracker.locate(route, RoutePlayback.at(route, 120.0)!!)
        assertNotNull(state.speedLimitKph, "the demo routes carry a limit, so the sign should show")
        assertTrue(state.speedLimitKph in 40..160)
    }
}
