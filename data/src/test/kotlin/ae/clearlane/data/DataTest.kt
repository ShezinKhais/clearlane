package ae.clearlane.data

import ae.clearlane.core.model.LatLng
import ae.clearlane.core.model.RouteCandidate
import ae.clearlane.core.model.RouteSegment
import ae.clearlane.core.model.TrafficTier
import ae.clearlane.core.model.distanceTo
import ae.clearlane.core.model.offset
import ae.clearlane.data.fixture.FixtureRouteProvider
import ae.clearlane.data.provider.Polyline
import ae.clearlane.data.provider.RouteRequest
import ae.clearlane.data.uae.SalikTariff
import ae.clearlane.data.uae.TollGate
import ae.clearlane.data.uae.TollModel
import ae.clearlane.data.uae.TrafficCalendar
import ae.clearlane.data.uae.UaeCorridors
import kotlinx.coroutines.test.runTest
import java.io.File
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PolylineTest {

    @Test
    fun `round trips a path at six decimal places`() {
        val path = listOf(
            LatLng(25.078500, 55.140300),
            LatLng(25.132211, 55.200914),
            LatLng(25.252800, 55.364400),
        )
        val decoded = Polyline.decode(Polyline.encode(path, 6), 6)
        assertEquals(path.size, decoded.size)
        for (i in path.indices) {
            assertTrue(
                path[i].distanceTo(decoded[i]) < 0.5,
                "point $i moved ${path[i].distanceTo(decoded[i])} m",
            )
        }
    }

    @Test
    fun `decodes the reference five decimal example`() {
        // The example from the Google polyline specification, which Mapbox's
        // default precision follows.
        val decoded = Polyline.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@", precision = 5)
        assertEquals(3, decoded.size)
        assertEquals(38.5, decoded[0].lat, 1e-6)
        assertEquals(-120.2, decoded[0].lon, 1e-6)
        assertEquals(43.252, decoded[2].lat, 1e-6)
        assertEquals(-126.453, decoded[2].lon, 1e-6)
    }

    @Test
    fun `a truncated polyline stops instead of throwing`() {
        val full = Polyline.encode(
            listOf(LatLng(25.0, 55.0), LatLng(25.1, 55.1), LatLng(25.2, 55.2)),
            6,
        )
        val decoded = Polyline.decode(full.substring(0, full.length - 3), 6)
        assertTrue(decoded.size < 3, "expected a short read, got ${decoded.size}")
    }

    @Test
    fun `an empty string decodes to nothing`() {
        assertTrue(Polyline.decode("").isEmpty())
    }
}

class UaeCorridorsTest {

    private val marina = LatLng(25.0785, 55.1403)
    private val airport = LatLng(25.2528, 55.3644)

    @Test
    fun `a trip across Dubai is offered the parallel corridors`() {
        val vias = UaeCorridors.viaPointsFor(marina, airport)
        assertTrue(vias.isNotEmpty(), "expected corridors for a Marina to airport run")
        val refs = vias.map { it.corridor.ref }
        assertTrue("E311" in refs || "E44" in refs, "expected an inland corridor, got $refs")
    }

    @Test
    fun `corridors come back nearest to the direct line first`() {
        val offsets = UaeCorridors.viaPointsFor(marina, airport).map { it.offsetMeters }
        assertEquals(offsets.sorted(), offsets, "expected ascending offsets, got $offsets")
    }

    @Test
    fun `a road running across the trip is not offered`() {
        // North west to south east across the grain of the coastal corridors.
        val vias = UaeCorridors.viaPointsFor(LatLng(25.26, 55.30), LatLng(24.99, 55.52))
        assertTrue(
            "E11" !in vias.map { it.corridor.ref },
            "the coast road runs the wrong way for this trip",
        )
    }

    @Test
    fun `nowhere near the UAE gets no corridors`() {
        val vias = UaeCorridors.viaPointsFor(LatLng(51.5, -0.12), LatLng(51.45, -0.05))
        assertTrue(vias.isEmpty())
    }

    @Test
    fun `lateral offsets fall either side of the direct line`() {
        val offsets = UaeCorridors.lateralOffsets(marina, airport)
        assertEquals(4, offsets.size)
        val midpoint = LatLng((marina.lat + airport.lat) / 2, (marina.lon + airport.lon) / 2)
        // Two at three kilometres and two at eight, one each side.
        val distances = offsets.map { midpoint.distanceTo(it) }.sorted()
        assertEquals(3_000.0, distances[0], 60.0)
        assertEquals(3_000.0, distances[1], 60.0)
        assertEquals(8_000.0, distances[2], 120.0)
    }

    @Test
    fun `a short hop is not sent halfway across the emirate`() {
        // Two kilometres apart. Every corridor is further away than the trip is
        // long, so none of them is an alternative.
        val vias = UaeCorridors.viaPointsFor(LatLng(25.20, 55.27), LatLng(25.21, 55.29))
        assertTrue(vias.isEmpty(), "got $vias")
    }
}

class SalikTariffTest {

    private fun at(day: String, time: String) = ZonedDateTime.parse("${day}T$time+04:00[Asia/Dubai]")

    @Test
    fun `morning and evening peaks cost six dirhams`() {
        // 2026-09-28 is a Monday, a working day.
        assertEquals(600, SalikTariff.filsAt(at("2026-09-28", "07:30:00")))
        assertEquals(600, SalikTariff.filsAt(at("2026-09-28", "18:00:00")))
    }

    @Test
    fun `the middle of the day costs four`() {
        assertEquals(400, SalikTariff.filsAt(at("2026-09-28", "12:00:00")))
        assertEquals(400, SalikTariff.filsAt(at("2026-09-28", "21:30:00")))
    }

    @Test
    fun `overnight is free`() {
        assertEquals(0, SalikTariff.filsAt(at("2026-09-28", "03:00:00")))
        assertEquals(0, SalikTariff.filsAt(at("2026-09-28", "05:59:00")))
        assertEquals(600, SalikTariff.filsAt(at("2026-09-28", "06:00:00")))
    }

    @Test
    fun `sunday has no peak`() {
        // 2026-09-27 is a Sunday, which is a weekend day in the UAE.
        assertEquals(400, SalikTariff.filsAt(at("2026-09-27", "07:30:00")))
        assertEquals(400, SalikTariff.filsAt(at("2026-09-27", "18:00:00")))
        assertEquals(0, SalikTariff.filsAt(at("2026-09-27", "03:00:00")))
    }

    @Test
    fun `ramadan shifts the peak into the afternoon`() {
        assertEquals(400, SalikTariff.filsAt(at("2026-09-28", "08:00:00"), ramadan = true))
        assertEquals(600, SalikTariff.filsAt(at("2026-09-28", "12:00:00"), ramadan = true))
        assertEquals(400, SalikTariff.filsAt(at("2026-09-28", "18:00:00"), ramadan = true))
        assertEquals(0, SalikTariff.filsAt(at("2026-09-28", "04:00:00"), ramadan = true))
    }

    @Test
    fun `every minute of the day falls in exactly one band`() {
        // A gap in the windows would silently price a gate at the default rate.
        for (minute in 0 until 24 * 60) {
            val t = at("2026-09-28", "%02d:%02d:00".format(minute / 60, minute % 60))
            assertNotNull(SalikTariff.bandAt(t), "no band at $t")
            assertNotNull(SalikTariff.bandAt(t, ramadan = true), "no ramadan band at $t")
        }
    }
}

/** A straight route across Dubai, for the toll tests. */
internal object TestRoute {
    fun straightDubai(): RouteCandidate {
        val start = LatLng(25.0785, 55.1403)
        val points = (0..40).map { i -> start.offset(i * 500.0, 45.0) }
        val segments = (0 until points.size - 1).map { i ->
            RouteSegment(
                start = points[i],
                end = points[i + 1],
                meters = points[i].distanceTo(points[i + 1]),
                congestion = 0.3,
                speedLimitKph = 100,
            )
        }
        val meters = segments.sumOf { it.meters }
        val freeFlow = meters / (100 * 1000.0 / 3600.0)
        return RouteCandidate(
            id = "toll-test",
            geometry = points,
            segments = segments,
            durationSeconds = freeFlow / 0.7,
            freeFlowSeconds = freeFlow,
            meters = meters,
            provider = "test",
        )
    }
}

class TollModelTest {

    @Test
    fun `no gate coordinates means no toll is invented`() {
        assertTrue(!TollModel.isConfigured, "this build ships without gate coordinates")
        val route = TestRoute.straightDubai()
        val crossings = TollModel.crossingsOn(route, ZonedDateTime.parse("2026-09-28T18:00:00+04:00"))
        assertTrue(crossings.isEmpty())
    }

    @Test
    fun `a gate on the route is charged at the rate for the time`() {
        val route = TestRoute.straightDubai()
        val onRoute = route.geometry[route.geometry.size / 2]
        val gates = listOf(
            TollGate("Test gate", "E11", "Salik", onRoute),
            TollGate("Far gate", "E311", "Salik", LatLng(25.9, 56.2)),
        )
        val peak = TollModel.crossingsOn(
            route,
            ZonedDateTime.parse("2026-09-28T18:00:00+04:00"),
            gates = gates,
        )
        assertEquals(1, peak.size)
        assertEquals(600, peak.first().fils)

        val night = TollModel.crossingsOn(
            route,
            ZonedDateTime.parse("2026-09-28T03:00:00+04:00"),
            gates = gates,
        )
        assertEquals(0, night.first().fils)
    }
}

class TrafficCalendarTest {

    private fun at(day: String, time: String) = ZonedDateTime.parse("${day}T$time+04:00[Asia/Dubai]")

    @Test
    fun `the evening peak on a working day is flagged`() {
        assertEquals(
            TrafficCalendar.Period.EVENING_PEAK,
            TrafficCalendar.periodAt(at("2026-09-28", "17:30:00")),
        )
        assertNotNull(TrafficCalendar.advice(at("2026-09-28", "17:30:00")))
        assertTrue(TrafficCalendar.corridorsDiverge(at("2026-09-28", "17:30:00")))
    }

    @Test
    fun `the weekend has no commuter peak`() {
        assertEquals(
            TrafficCalendar.Period.WEEKEND,
            TrafficCalendar.periodAt(at("2026-09-27", "08:00:00")),
        )
        assertNull(TrafficCalendar.advice(at("2026-09-27", "08:00:00")))
    }

    @Test
    fun `the hour before iftar is called out`() {
        val period = TrafficCalendar.periodAt(at("2026-09-28", "18:00:00"), ramadan = true)
        assertEquals(TrafficCalendar.Period.RAMADAN_PRE_IFTAR, period)
        val advice = TrafficCalendar.advice(at("2026-09-28", "18:00:00"), ramadan = true)
        assertNotNull(advice)
        assertTrue("iftar" in advice)
    }
}

class FixtureRouteProviderTest {

    private fun provider() = FixtureRouteProvider {
        File("src/main/assets/clearlane/fixtures.json").readText()
    }

    @Test
    fun `the scripted evening run offers four corridors`() = runTest {
        val routes = provider().routes(
            RouteRequest(LatLng(25.0785, 55.1403), LatLng(25.2528, 55.3644)),
        )
        assertEquals(4, routes.size)
        assertTrue(routes.all { it.segments.isNotEmpty() })
        assertTrue(routes.all { it.durationSeconds >= it.freeFlowSeconds })
    }

    @Test
    fun `the scripted evening run reproduces the case the app exists for`() = runTest {
        val service = RoutingService(
            providers = listOf(provider()),
            clock = { ZonedDateTime.parse("2026-09-28T18:00:00+04:00[Asia/Dubai]") },
        )
        val outcome = service.plan(LatLng(25.0785, 55.1403), LatLng(25.2528, 55.3644))
        assertNotNull(outcome)
        val plan = outcome.plan

        // Al Khail is the quickest way at half past six and it is a third
        // congested, which is exactly the answer an ordinary maps app gives.
        assertTrue("e44" in plan.fastest.id, "expected Al Khail to be quickest, got ${plan.fastest.id}")
        assertTrue(
            plan.fastest.congestion.timeWeighted in 0.30..0.45,
            "read ${plan.fastest.congestion.timeWeighted}",
        )

        val clear = plan.picks[TrafficTier.NONE]
        assertNotNull(clear, "expected a clear corridor, picks were ${plan.picks.keys}")
        assertTrue(clear.congestion.timeWeighted < 0.10)
        assertTrue(clear.extraSeconds > 0, "the clear route should cost time, that is the trade")
        assertTrue(
            clear.extraSeconds < 10 * 60,
            "and should not cost the evening: ${clear.extraSeconds / 60} min",
        )
        assertTrue(
            clear.score.total > plan.fastest.score.total + 20,
            "and should drive far better: ${clear.score.total} against ${plan.fastest.score.total}",
        )

        // Three distinct roads at three traffic levels, none of them repeated.
        assertEquals(
            setOf(TrafficTier.NONE, TrafficTier.LOW, TrafficTier.MEDIUM),
            plan.picks.keys,
        )
        assertEquals(3, plan.picks.values.map { it.id }.distinct().size)
        assertFalse(plan.fastestIsCalmest)

        // The coast road is in the list and is offered as nothing, because at
        // this hour it is both the slowest and the worst drive of the four.
        val coast = plan.considered.first { "e11" in it.id }
        assertTrue(coast.congestion.timeWeighted > 0.55)
        assertTrue(coast.id !in plan.picks.values.map { it.id })
        assertTrue(coast.durationSeconds > plan.fastest.durationSeconds + 10 * 60)
    }

    @Test
    fun `at midday the quick way is also the calm way and we say so`() = runTest {
        val service = RoutingService(
            providers = listOf(provider()),
            clock = { ZonedDateTime.parse("2026-09-28T12:00:00+04:00[Asia/Dubai]") },
        )
        val outcome = service.plan(LatLng(24.4539, 54.3773), LatLng(25.1972, 55.2744))
        assertNotNull(outcome)
        val plan = outcome.plan

        assertTrue(plan.fastestIsCalmest, "the inland road is both quickest and clearest")
        assertEquals(TrafficTier.NONE, plan.picks.keys.single())
        assertEquals(plan.fastest.id, plan.picks[TrafficTier.NONE]?.id)
        assertEquals(0.0, plan.fastest.extraSeconds, 1e-9)
    }

    @Test
    fun `an unscripted trip still gets something to compare`() = runTest {
        val routes = provider().routes(
            RouteRequest(LatLng(24.20, 55.70), LatLng(24.35, 55.85)),
        )
        assertEquals(3, routes.size)
        assertTrue(routes.map { it.meters }.distinct().size > 1)
    }

    @Test
    fun `a via point is left to the scripted corridors`() = runTest {
        val routes = provider().routes(
            RouteRequest(
                origin = LatLng(25.0785, 55.1403),
                destination = LatLng(25.2528, 55.3644),
                via = listOf(LatLng(25.10, 55.29)),
            ),
        )
        assertTrue(routes.isEmpty())
    }

    @Test
    fun `the demo picker lists the scripted trips`() {
        val summaries = provider().tripSummaries()
        assertEquals(3, summaries.size)
        assertTrue(summaries.all { it.label.isNotBlank() })
    }

}
