package ae.clearlane.core.scoring

import ae.clearlane.core.model.CandidateOrigin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RouteDeduperTest {

    @Test
    fun `the same road twice is kept once`() {
        val a = TestRoutes.straight("a", TestRoutes.flat(0.3, 30))
        val b = TestRoutes.straight("b", TestRoutes.flat(0.3, 30))
        assertEquals(listOf("a"), RouteDeduper.dedupe(listOf(a, b)).map { it.id })
    }

    @Test
    fun `a pointless wiggle in the middle of the same road is still the same road`() {
        // This is the common case from the candidate generator: re-routing via
        // a detour point that the router ignores, giving back the original road
        // with a slightly different shape.
        val straight = TestRoutes.straight("straight", TestRoutes.flat(0.3, 40))
        val nudged = TestRoutes.straight(
            id = "nudged",
            congestion = TestRoutes.flat(0.3, 40),
            start = TestRoutes.DUBAI.copy(lat = TestRoutes.DUBAI.lat + 0.0002),
            origin = CandidateOrigin.LATERAL_VIA,
        )
        val kept = RouteDeduper.dedupe(listOf(straight, nudged))
        assertEquals(listOf("straight"), kept.map { it.id })
    }

    @Test
    fun `genuinely parallel corridors are both kept`() {
        // Three kilometres apart is a different road, not the same one drawn
        // twice, and the whole product depends on telling those two apart.
        val e11 = TestRoutes.straight("e11", TestRoutes.flat(0.6, 30))
        val e311 = TestRoutes.straight(
            id = "e311",
            congestion = TestRoutes.flat(0.1, 30),
            start = TestRoutes.DUBAI.copy(lat = TestRoutes.DUBAI.lat + 0.03),
            origin = CandidateOrigin.CORRIDOR_VIA,
        )
        assertEquals(2, RouteDeduper.dedupe(listOf(e11, e311)).size)
    }

    @Test
    fun `order decides which of two duplicates survives`() {
        val provider = TestRoutes.straight("provider", TestRoutes.flat(0.3, 30))
        val invented = TestRoutes.straight(
            id = "invented",
            congestion = TestRoutes.flat(0.3, 30),
            origin = CandidateOrigin.LATERAL_VIA,
        )
        assertEquals(listOf("provider"), RouteDeduper.dedupe(listOf(provider, invented)).map { it.id })
        assertEquals(listOf("invented"), RouteDeduper.dedupe(listOf(invented, provider)).map { it.id })
    }

    @Test
    fun `a short route running entirely inside a long one counts as a duplicate`() {
        val long = TestRoutes.straight("long", TestRoutes.flat(0.3, 40))
        val short = TestRoutes.straight("short", TestRoutes.flat(0.3, 10))
        assertEquals(listOf("long"), RouteDeduper.dedupe(listOf(long, short)).map { it.id })
    }

    @Test
    fun `overlap of a route with itself is total`() {
        val a = TestRoutes.straight("a", TestRoutes.flat(0.2, 20))
        val cells = RouteDeduper.occupancy(a)
        assertEquals(1.0, RouteDeduper.overlap(cells, cells), 1e-9)
    }

    @Test
    fun `cell keys do not collide across the globe`() {
        val seen = mutableSetOf<Long>()
        var lat = -89.0
        while (lat <= 89.0) {
            var lon = -179.0
            while (lon <= 179.0) {
                val key = RouteDeduper.cellOf(ae.clearlane.core.model.LatLng(lat, lon))
                assertTrue(seen.add(key), "collision at $lat,$lon")
                lon += 7.3
            }
            lat += 3.7
        }
    }
}
