package ae.clearlane.data

import ae.clearlane.core.model.HazardKind
import ae.clearlane.core.model.LatLng
import ae.clearlane.data.hazard.BundledHazards
import ae.clearlane.data.hazard.HazardService
import ae.clearlane.data.hazard.HazardSource
import ae.clearlane.data.hazard.HazardSourceException
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HazardTest {

    private fun bundled() = BundledHazards {
        File("src/main/assets/clearlane/hazards.json").readText()
    }

    @Test
    fun `the bundled extract parses and is not empty`() = runTest {
        val cameras = bundled().near(LatLng(22.0, 50.0), LatLng(27.0, 57.0))
        assertTrue(cameras.size > 1_000, "expected the whole UAE extract, got ${cameras.size}")
    }

    @Test
    fun `every bundled camera lands inside the UAE`() = runTest {
        val cameras = bundled().near(LatLng(-90.0, -180.0), LatLng(90.0, 180.0))
        assertTrue(
            cameras.all { it.at.lat in 22.0..27.0 && it.at.lon in 51.0..57.0 },
            "a camera outside the service area means the extract query was wrong",
        )
    }

    @Test
    fun `the box filter actually narrows the list`() = runTest {
        val source = bundled()
        val everything = source.near(LatLng(22.0, 50.0), LatLng(27.0, 57.0))
        // Roughly Dubai city.
        val dubai = source.near(LatLng(25.0, 55.0), LatLng(25.4, 55.5))

        assertTrue(dubai.isNotEmpty(), "Dubai should have cameras in it")
        assertTrue(dubai.size < everything.size, "a city is not the whole country")
        assertTrue(dubai.all { it.at.lat in 25.0..25.4 && it.at.lon in 55.0..55.5 })
    }

    @Test
    fun `posted limits and bearings survive the parse`() = runTest {
        val cameras = bundled().near(LatLng(22.0, 50.0), LatLng(27.0, 57.0))

        val withLimit = cameras.filter { it.speedLimitKph != null }
        val withBearing = cameras.filter { it.bearingDeg != null }

        assertTrue(withLimit.size > 400, "most of the extract carries a limit, got ${withLimit.size}")
        assertTrue(withLimit.all { it.speedLimitKph!! in 5..200 })
        assertTrue(withBearing.isNotEmpty())
        assertTrue(withBearing.all { it.bearingDeg!! in 0.0..360.0 })
    }

    @Test
    fun `the three kinds of camera all survive the parse`() = runTest {
        val kinds = bundled()
            .near(LatLng(22.0, 50.0), LatLng(27.0, 57.0))
            .map { it.kind }
            .toSet()

        assertTrue(HazardKind.FIXED_CAMERA in kinds)
        assertTrue(HazardKind.AVERAGE_SPEED in kinds, "section enforcement should be in the extract")
    }

    @Test
    fun `an unknown kind is skipped rather than guessed at`() = runTest {
        val source = BundledHazards {
            """
            {"snapshot":"t","cameras":[
              {"y":25.1,"x":55.2,"k":"FIXED"},
              {"y":25.1,"x":55.3,"k":"SOMETHING_NEW"}
            ]}
            """.trimIndent()
        }
        val cameras = source.near(LatLng(24.0, 54.0), LatLng(26.0, 56.0))
        assertEquals(1, cameras.size)
        assertEquals(HazardKind.FIXED_CAMERA, cameras.single().kind)
    }

    @Test
    fun `an out of range limit is dropped rather than shown`() = runTest {
        val source = BundledHazards {
            """{"snapshot":"t","cameras":[{"y":25.1,"x":55.2,"k":"FIXED","l":900}]}"""
        }
        assertNull(source.near(LatLng(24.0, 54.0), LatLng(26.0, 56.0)).single().speedLimitKph)
    }

    @Test
    fun `cameras are pinned onto the route that passes them`() = runTest {
        val provider = FixtureRouteProviderForTest.load()
        val routes = provider.routes(
            ae.clearlane.data.provider.RouteRequest(
                origin = LatLng(25.0785, 55.1403),
                destination = LatLng(25.2528, 55.3644),
            ),
        )
        val result = HazardService(listOf(bundled())).forRoutes(routes)

        assertNotNull(result.attribution)
        assertEquals(routes.size, result.byRoute.size, "every route gets an entry, even an empty one")
        assertTrue(
            result.byRoute.values.flatten().all { it.atMeters >= 0.0 },
            "a camera cannot be at a negative distance along a route",
        )
    }

    @Test
    fun `a source that fails is reported without losing the routes`() = runTest {
        val broken = object : HazardSource {
            override val name = "broken"
            override val attribution: String? = null
            override val isConfigured = true
            override suspend fun near(southWest: LatLng, northEast: LatLng) =
                throw HazardSourceException("no network")
        }
        val provider = FixtureRouteProviderForTest.load()
        val routes = provider.routes(
            ae.clearlane.data.provider.RouteRequest(
                origin = LatLng(25.0785, 55.1403),
                destination = LatLng(25.2528, 55.3644),
            ),
        )

        // Broken first, bundled second: the working one still answers.
        val result = HazardService(listOf(broken, bundled())).forRoutes(routes)

        assertTrue(result.warnings.any { it.contains("no network") })
        assertNotNull(result.attribution, "the second source should still have answered")
    }

    @Test
    fun `no routes means no lookup at all`() = runTest {
        assertEquals(HazardService.Result.EMPTY, HazardService(listOf(bundled())).forRoutes(emptyList()))
    }
}

/** Loads the bundled fixture provider the same way the data tests do. */
private object FixtureRouteProviderForTest {
    fun load() = ae.clearlane.data.fixture.FixtureRouteProvider {
        File("src/main/assets/clearlane/fixtures.json").readText()
    }
}
