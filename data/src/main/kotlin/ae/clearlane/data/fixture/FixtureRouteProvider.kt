package ae.clearlane.data.fixture

import ae.clearlane.core.model.CandidateOrigin
import ae.clearlane.core.model.CongestionSource
import ae.clearlane.core.model.LatLng
import ae.clearlane.core.model.Maneuver
import ae.clearlane.core.model.ManeuverKind
import ae.clearlane.core.model.RouteCandidate
import ae.clearlane.core.model.RouteSegment
import ae.clearlane.core.model.bearingTo
import ae.clearlane.core.model.distanceTo
import ae.clearlane.core.model.midpointTo
import ae.clearlane.core.model.offset
import ae.clearlane.core.scoring.CongestionIndex
import ae.clearlane.data.provider.RouteProvider
import ae.clearlane.data.provider.RouteRequest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Routes from a bundled file instead of a network call, so the app is fully
 * usable with no account and no key.
 *
 * This is not a stub. It is the thing that makes the comparison testable: the
 * fixtures describe evenings where the coast road is stationary and the inland
 * one is not, which is the case the whole product exists for and which you
 * cannot summon on demand from a live API. The tier logic, the map, the strip
 * chart and the sheet all run against this exactly as they do against Mapbox.
 *
 * The geometry is synthetic. Routes are drawn as smooth paths through
 * approximate points on the real corridors, not traced from the road network,
 * so they will not sit perfectly on the basemap. Congestion patterns are made
 * up to be representative rather than measured from anything.
 */
class FixtureRouteProvider(
    private val loadJson: () -> String,
) : RouteProvider {

    override val name: String = "Fixtures"

    override val isConfigured: Boolean = true

    private val json = Json { ignoreUnknownKeys = true }

    private val trips: List<FixtureTrip> by lazy {
        runCatching { json.decodeFromString<FixtureFile>(loadJson()).trips }
            .getOrElse { emptyList() }
    }

    override suspend fun routes(request: RouteRequest): List<RouteCandidate> {
        // A via point means the candidate generator is asking for a specific
        // corridor. The fixtures already contain every corridor for a trip, so
        // there is nothing extra to give and answering again would just create
        // duplicates for the deduper to throw away.
        if (request.via.isNotEmpty()) return emptyList()

        val trip = nearestTrip(request.origin, request.destination)
        return if (trip != null) {
            trip.routes.mapIndexed { index, route -> build(trip, route, index) }
        } else {
            improvise(request.origin, request.destination)
        }
    }

    /** The named trips, for the demo picker. */
    fun tripSummaries(): List<FixtureTripSummary> = trips.map {
        FixtureTripSummary(it.id, it.label, it.origin.toLatLng(), it.destination.toLatLng())
    }

    private fun nearestTrip(origin: LatLng, destination: LatLng): FixtureTrip? {
        if (trips.isEmpty()) return null
        val best = trips.minBy {
            it.origin.toLatLng().distanceTo(origin) + it.destination.toLatLng().distanceTo(destination)
        }
        val error = best.origin.toLatLng().distanceTo(origin) +
            best.destination.toLatLng().distanceTo(destination)
        return if (error <= MATCH_TOLERANCE_METERS) best else null
    }

    private fun build(trip: FixtureTrip, route: FixtureRoute, index: Int): RouteCandidate {
        val anchors = buildList {
            add(trip.origin.toLatLng())
            addAll(route.via.map { it.toLatLng() })
            add(trip.destination.toLatLng())
        }
        val path = densify(anchors, SPACING_METERS)
        return assemble(
            id = "fixture-${trip.id}-${route.id}",
            path = path,
            runs = route.congestion.map { it[0] to it[1] },
            speedLimitKph = route.speedLimitKph,
            roadRefs = route.refs,
            signals = route.signals,
            roundabouts = route.roundabouts,
            origin = if (index == 0) {
                CandidateOrigin.PROVIDER_PRIMARY
            } else {
                CandidateOrigin.FIXTURE
            },
        )
    }

    /**
     * Three plausible routes between any two points, for when the tap is not
     * near a scripted trip. Keeps the demo usable anywhere on the map without
     * pretending to know anything about the roads there.
     */
    private fun improvise(origin: LatLng, destination: LatLng): List<RouteCandidate> {
        val straight = origin.distanceTo(destination)
        if (straight < 800.0) return emptyList()
        val midpoint = origin.midpointTo(destination)
        val perpendicular = (origin.bearingTo(destination) + 90.0) % 360.0
        val swing = (straight * 0.12).coerceIn(600.0, 6_000.0)

        val direct = densify(listOf(origin, destination), SPACING_METERS)
        val near = densify(
            listOf(origin, midpoint.offset(swing, perpendicular), destination),
            SPACING_METERS,
        )
        val far = densify(
            listOf(origin, midpoint.offset(swing * 2.2, perpendicular), destination),
            SPACING_METERS,
        )

        return listOf(
            assemble(
                id = "fixture-improvised-direct",
                path = direct,
                runs = listOf(0.18 to 0.35, 0.74 to 0.30, 0.22 to 0.35),
                speedLimitKph = 80,
                roadRefs = listOf("direct"),
                signals = 4,
                roundabouts = 0,
                origin = CandidateOrigin.PROVIDER_PRIMARY,
            ),
            assemble(
                id = "fixture-improvised-near",
                path = near,
                runs = listOf(0.24 to 0.4, 0.30 to 0.6),
                speedLimitKph = 80,
                roadRefs = listOf("alternative"),
                signals = 3,
                roundabouts = 1,
                origin = CandidateOrigin.FIXTURE,
            ),
            assemble(
                id = "fixture-improvised-far",
                path = far,
                runs = listOf(0.05 to 1.0),
                speedLimitKph = 100,
                roadRefs = listOf("bypass"),
                signals = 0,
                roundabouts = 0,
                origin = CandidateOrigin.FIXTURE,
            ),
        )
    }

    /**
     * Builds a candidate from a path and a congestion pattern.
     *
     * Durations are derived from the pattern using the same relation the
     * congestion index assumes, so a fixture route always takes the time its
     * own congestion implies. Hand written durations drifted out of step with
     * the patterns almost immediately.
     */
    private fun assemble(
        id: String,
        path: List<LatLng>,
        runs: List<Pair<Double, Double>>,
        speedLimitKph: Int,
        roadRefs: List<String>,
        signals: Int,
        roundabouts: Int,
        origin: CandidateOrigin,
    ): RouteCandidate {
        val limitMs = speedLimitKph * 1000.0 / 3600.0
        val total = path.zipWithNext().sumOf { (a, b) -> a.distanceTo(b) }

        // Runs are declared either in metres or as fractions of the whole,
        // and normalising by the declared total handles both: metres get
        // rescaled to the real path length, fractions get multiplied out. That
        // also means a pattern can never run off the end of a route or stop
        // short of it, whichever way it was written.
        val declared = runs.sumOf { it.second }
        val scaledRuns = if (runs.isEmpty() || declared <= 0.0) {
            listOf(0.0 to total)
        } else {
            runs.map { (value, extent) -> value to extent * total / declared }
        }

        val segments = ArrayList<RouteSegment>(path.size - 1)
        var travelled = 0.0
        var freeFlow = 0.0
        var withTraffic = 0.0

        for (i in 0 until path.size - 1) {
            val meters = path[i].distanceTo(path[i + 1])
            if (meters <= 0.0) continue
            val congestion = congestionAt(scaledRuns, travelled + meters / 2.0)
            segments += RouteSegment(
                start = path[i],
                end = path[i + 1],
                meters = meters,
                congestion = congestion,
                speedLimitKph = speedLimitKph,
                roadRef = roadRefs.firstOrNull(),
            )
            travelled += meters
            val clear = meters / limitMs
            freeFlow += clear
            withTraffic += clear / (1.0 - congestion).coerceAtLeast(CongestionIndex.CRAWL_FLOOR)
        }

        val maneuvers = buildList {
            repeat(signals) { add(Maneuver(ManeuverKind.SIGNAL, path[spread(it, signals, path.size)])) }
            repeat(roundabouts) {
                add(Maneuver(ManeuverKind.ROUNDABOUT, path[spread(it, roundabouts, path.size)]))
            }
            add(Maneuver(ManeuverKind.ARRIVE, path.last()))
        }

        return RouteCandidate(
            id = id,
            geometry = path,
            segments = segments,
            durationSeconds = withTraffic.coerceAtLeast(1.0),
            freeFlowSeconds = freeFlow.coerceAtLeast(1.0),
            meters = total.coerceAtLeast(1.0),
            maneuvers = maneuvers,
            roadRefs = roadRefs,
            source = CongestionSource.TYPICAL,
            provider = name,
            origin = origin,
        )
    }

    private fun congestionAt(runs: List<Pair<Double, Double>>, distance: Double): Double {
        var edge = 0.0
        for ((value, extent) in runs) {
            edge += extent
            if (distance <= edge) return value.coerceIn(0.0, 1.0)
        }
        return runs.lastOrNull()?.first?.coerceIn(0.0, 1.0) ?: 0.0
    }

    private fun spread(index: Int, count: Int, size: Int): Int {
        if (count <= 0 || size <= 1) return 0
        return ((index + 1).toDouble() / (count + 1).toDouble() * (size - 1)).toInt().coerceIn(0, size - 1)
    }

    /** Fills in points between [anchors] so the path has a drawable shape. */
    private fun densify(anchors: List<LatLng>, spacing: Double): List<LatLng> {
        if (anchors.size < 2) return anchors
        val out = mutableListOf(anchors.first())
        for (i in 0 until anchors.size - 1) {
            val a = anchors[i]
            val b = anchors[i + 1]
            val length = a.distanceTo(b)
            val steps = (length / spacing).toInt().coerceAtLeast(1)
            for (s in 1..steps) {
                val t = s.toDouble() / steps
                out += LatLng(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)
            }
        }
        return out
    }

    companion object {
        /** Distance between synthesised shape points. */
        const val SPACING_METERS = 180.0

        /** How far a request can be from a scripted trip and still match it. */
        const val MATCH_TOLERANCE_METERS = 6_000.0
    }
}

data class FixtureTripSummary(
    val id: String,
    val label: String,
    val origin: LatLng,
    val destination: LatLng,
)

@Serializable
internal data class FixtureFile(
    val note: String = "",
    val trips: List<FixtureTrip> = emptyList(),
)

@Serializable
internal data class FixtureTrip(
    val id: String,
    val label: String,
    val origin: FixturePoint,
    val destination: FixturePoint,
    val routes: List<FixtureRoute> = emptyList(),
)

@Serializable
internal data class FixtureRoute(
    val id: String,
    val refs: List<String> = emptyList(),
    val via: List<FixturePoint> = emptyList(),
    val speedLimitKph: Int = 100,
    val signals: Int = 0,
    val roundabouts: Int = 0,
    /** Runs of [congestion, metres] along the route. */
    val congestion: List<List<Double>> = emptyList(),
)

@Serializable
internal data class FixturePoint(val lat: Double, val lon: Double) {
    fun toLatLng(): LatLng = LatLng(lat, lon)
}
