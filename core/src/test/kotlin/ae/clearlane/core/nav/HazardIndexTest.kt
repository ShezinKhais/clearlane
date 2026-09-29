package ae.clearlane.core.nav

import ae.clearlane.core.model.Hazard
import ae.clearlane.core.model.HazardKind
import ae.clearlane.core.model.offset
import ae.clearlane.core.scoring.TestRoutes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HazardIndexTest {

    private fun route() = TestRoutes.straight("route", TestRoutes.flat(0.1, 20))

    private fun camera(at: ae.clearlane.core.model.LatLng) =
        Hazard(HazardKind.FIXED_CAMERA, at, source = "test")

    @Test
    fun `a camera beside the road is pinned at the right distance along it`() {
        val route = route()
        // Twenty metres to the side of the 6 km mark, which is where a camera
        // on a gantry actually sits.
        val beside = route.geometry[12].offset(20.0, 135.0)
        val pinned = HazardIndex.pin(route, listOf(camera(beside)))

        assertEquals(1, pinned.size)
        assertEquals(6_000.0, pinned.first().atMeters, 50.0)
        assertEquals(20.0, pinned.first().offMeters, 3.0)
    }

    @Test
    fun `a camera on a different road is left out`() {
        val route = route()
        // Three hundred metres off the line: a parallel service road, not this
        // route.
        val elsewhere = route.geometry[10].offset(300.0, 135.0)
        assertTrue(HazardIndex.pin(route, listOf(camera(elsewhere))).isEmpty())
    }

    @Test
    fun `cameras come back in the order they are reached`() {
        val route = route()
        val far = camera(route.geometry[16])
        val near = camera(route.geometry[4])
        val middle = camera(route.geometry[10])

        val pinned = HazardIndex.pin(route, listOf(far, middle, near))

        assertEquals(3, pinned.size)
        assertEquals(listOf(2_000.0, 5_000.0, 8_000.0), pinned.map { it.atMeters.roundTo(50.0) })
    }

    @Test
    fun `a camera is pinned once even though several segments are near it`() {
        val route = route()
        val pinned = HazardIndex.pin(route, listOf(camera(route.geometry[8])))

        // The shape points either side are both within the corridor, so a naive
        // pass would record the same camera twice.
        assertEquals(1, pinned.size)
    }

    @Test
    fun `nothing to pin is not an error`() {
        assertTrue(HazardIndex.pin(route(), emptyList()).isEmpty())
    }

    @Test
    fun `a country of cameras is narrowed to the few on the route`() {
        val route = route()
        // A grid of cameras across roughly the whole UAE, most nowhere near.
        val many = buildList {
            var lat = 22.5
            while (lat < 26.5) {
                var lon = 51.0
                while (lon < 56.5) {
                    add(camera(ae.clearlane.core.model.LatLng(lat, lon)))
                    lon += 0.05
                }
                lat += 0.05
            }
        }
        val pinned = HazardIndex.pin(route, many + camera(route.geometry[6]))

        // The planted one is found, and the grid is coarse enough that nothing
        // else lands within 55 m of a 10 km line.
        assertTrue(pinned.isNotEmpty(), "the camera on the route should be found")
        assertTrue(
            pinned.all { it.offMeters <= HazardIndex.CORRIDOR_METERS },
            "nothing outside the corridor should survive",
        )
    }

    private fun Double.roundTo(step: Double): Double = Math.round(this / step) * step
}
