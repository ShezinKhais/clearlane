package ae.clearlane.data.mapbox

import ae.clearlane.core.model.CandidateOrigin
import ae.clearlane.core.model.CongestionSource
import ae.clearlane.core.model.LatLng
import ae.clearlane.core.model.Maneuver
import ae.clearlane.core.model.ManeuverKind
import ae.clearlane.core.model.RouteCandidate
import ae.clearlane.core.model.RouteSegment
import ae.clearlane.core.model.distanceTo
import ae.clearlane.data.provider.Polyline
import ae.clearlane.data.provider.RouteProvider
import ae.clearlane.data.provider.RouteProviderException
import ae.clearlane.data.provider.RouteRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Routes and live traffic from the Mapbox Directions API, on the
 * `driving-traffic` profile.
 *
 * Chosen over the alternatives for one reason: it is the only one of the big
 * three that reports congestion per segment of the route geometry rather than
 * per variable length "section". Our whole index is built on walking the route
 * segment by segment, so a provider that hands back three coarse sections
 * forces us to guess where the jam starts and ends.
 *
 * ### Mapping congestion onto our scale
 *
 * This project defines congestion as 0 for free flow and 1 for stopped, and
 * works out the rest from there. Mapbox gives two things that bear on it, and
 * they are used in this order:
 *
 *  1. Current speed against the posted limit, when both are known. This is the
 *     definition itself rather than an approximation of it, so it wins.
 *  2. `congestion_numeric`, divided by a hundred. Mapbox's own scale, used
 *     wherever there is no speed limit on record. It is a congestion level
 *     rather than a speed ratio, so treat it as indicative.
 *
 * Where neither is available the segment is recorded as free flowing and the
 * route is marked [CongestionSource.UNKNOWN], which the UI says out loud
 * instead of quietly showing an empty road.
 *
 * ### Terms
 *
 * Mapbox's product terms are worth reading before shipping this against a
 * non-Mapbox basemap. See the note in the README.
 */
class MapboxRouteProvider(
    private val accessToken: String,
    private val client: OkHttpClient = defaultClient(),
    private val baseUrl: String = "https://api.mapbox.com",
) : RouteProvider {

    override val name: String = "Mapbox"

    override val isConfigured: Boolean get() = accessToken.isNotBlank()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    override suspend fun routes(request: RouteRequest): List<RouteCandidate> {
        if (!isConfigured) return emptyList()
        val body = withContext(Dispatchers.IO) { fetch(request) }
        val parsed = try {
            json.decodeFromString<MapboxDirectionsResponse>(body)
        } catch (e: Exception) {
            throw RouteProviderException(name, "could not read the response", e)
        }

        if (parsed.code != null && parsed.code != "Ok") {
            // NoRoute and NoSegment are ordinary answers to an awkward via
            // point, not failures. The generator tries several and expects some
            // of them to come back empty.
            if (parsed.code == "NoRoute" || parsed.code == "NoSegment") return emptyList()
            throw RouteProviderException(name, parsed.message ?: parsed.code)
        }

        return parsed.routes.mapIndexedNotNull { index, route ->
            convert(route, index, request)
        }
    }

    private fun fetch(request: RouteRequest): String {
        val points = buildList {
            add(request.origin)
            addAll(request.via)
            add(request.destination)
        }
        val coordinates = points.joinToString(";") { "${fmt(it.lon)},${fmt(it.lat)}" }

        val url = StringBuilder("$baseUrl/directions/v5/mapbox/driving-traffic/$coordinates")
            .append("?geometries=polyline6")
            .append("&overview=full")
            .append("&steps=true")
            .append("&annotations=congestion_numeric,distance,speed,maxspeed")
            // Alternatives are only offered when there are no intermediate
            // points, so asking for both at once is wasted breath.
            .append("&alternatives=").append(request.alternatives && request.via.isEmpty())
            .apply { request.departAt?.let { append("&depart_at=").append(isoUtc(it)) } }
            .append("&access_token=").append(accessToken)
            .toString()

        val httpRequest = Request.Builder().url(url).header("Accept", "application/json").build()
        try {
            client.newCall(httpRequest).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    // The body carries Mapbox's own explanation, which is more
                    // use than the status code on its own.
                    val detail = runCatching {
                        json.decodeFromString<MapboxDirectionsResponse>(text).message
                    }.getOrNull()
                    throw RouteProviderException(
                        name,
                        detail ?: "HTTP ${response.code}",
                    )
                }
                return text
            }
        } catch (e: IOException) {
            throw RouteProviderException(name, "could not reach the routing service", e)
        }
    }

    private fun convert(
        route: MapboxRoute,
        index: Int,
        request: RouteRequest,
    ): RouteCandidate? {
        val geometry = Polyline.decode(route.geometry ?: return null, precision = 6)
        if (geometry.size < 2) return null
        if (route.duration <= 0.0 || route.distance <= 0.0) return null

        val segments = ArrayList<RouteSegment>(geometry.size - 1)
        var known = 0
        var cursor = 0
        var freeFlowSeconds = 0.0

        for (leg in route.legs) {
            val annotation = leg.annotation
            val count = annotation?.distance?.size
                ?: annotation?.congestionNumeric?.size
                ?: 0
            if (count == 0) {
                // No annotation for this leg. Skip its share of the geometry so
                // later legs still line up with the right coordinates.
                cursor += guessLegPointCount(leg, geometry, cursor)
                continue
            }

            for (i in 0 until count) {
                val a = cursor + i
                if (a + 1 >= geometry.size) break
                val start = geometry[a]
                val end = geometry[a + 1]
                val meters = annotation?.distance?.getOrNull(i) ?: start.distanceTo(end)
                if (meters <= 0.0) continue

                val limit = annotation?.maxspeed?.getOrNull(i)?.metresPerSecond
                val speed = annotation?.speed?.getOrNull(i)
                val numeric = annotation?.congestionNumeric?.getOrNull(i)

                val congestion: Double
                when {
                    limit != null && speed != null && speed >= 0.0 -> {
                        congestion = (1.0 - (speed / limit)).coerceIn(0.0, 1.0)
                        known++
                    }
                    numeric != null -> {
                        congestion = (numeric / 100.0).coerceIn(0.0, 1.0)
                        known++
                    }
                    else -> congestion = 0.0
                }

                // Free flow speed is the posted limit where we have it. Where we
                // only have a current speed and a congestion level, undo the
                // congestion to recover what the road runs at when it is empty.
                val freeFlowSpeed = limit
                    ?: speed?.let { it / (1.0 - congestion).coerceAtLeast(0.15) }
                    ?: (route.distance / route.duration)
                if (freeFlowSpeed > 0.0) freeFlowSeconds += meters / freeFlowSpeed

                segments += RouteSegment(
                    start = start,
                    end = end,
                    meters = meters,
                    congestion = congestion,
                    speedLimitKph = limit?.let { (it * 3.6).toInt() },
                    roadRef = null,
                )
            }
            cursor += count
        }

        val source = when {
            segments.isEmpty() -> CongestionSource.UNKNOWN
            known.toDouble() / segments.size >= 0.5 -> CongestionSource.LIVE
            known > 0 -> CongestionSource.TYPICAL
            else -> CongestionSource.UNKNOWN
        }

        // A route cannot take less time empty than it does in traffic. If the
        // annotations disagree with the route summary, trust the summary.
        val freeFlow = when {
            freeFlowSeconds <= 0.0 -> route.durationTypical ?: route.duration
            else -> freeFlowSeconds.coerceAtMost(route.duration)
        }

        return RouteCandidate(
            id = candidateId(request, index),
            geometry = geometry,
            segments = segments,
            durationSeconds = route.duration,
            freeFlowSeconds = freeFlow.coerceAtLeast(1.0),
            meters = route.distance,
            maneuvers = maneuvers(route),
            roadRefs = roadRefs(route),
            source = source,
            provider = name,
            origin = when {
                request.via.isNotEmpty() -> CandidateOrigin.CORRIDOR_VIA
                index == 0 -> CandidateOrigin.PROVIDER_PRIMARY
                else -> CandidateOrigin.PROVIDER_ALTERNATIVE
            },
        )
    }

    /**
     * How many geometry points a leg used, when it gave us no annotation to
     * count. Falls back to matching the leg's own distance against the
     * geometry, which is approximate but keeps the remaining legs aligned.
     */
    private fun guessLegPointCount(
        leg: MapboxLeg,
        geometry: List<LatLng>,
        from: Int,
    ): Int {
        if (leg.distance <= 0.0) return 0
        var travelled = 0.0
        var i = from
        while (i + 1 < geometry.size && travelled < leg.distance) {
            travelled += geometry[i].distanceTo(geometry[i + 1])
            i++
        }
        return i - from
    }

    private fun maneuvers(route: MapboxRoute): List<Maneuver> = buildList {
        for (leg in route.legs) {
            for (step in leg.steps) {
                step.maneuver?.let { m ->
                    val at = m.location.toLatLng()
                    if (at != null) {
                        add(Maneuver(kindOf(m.type, m.modifier), at, m.instruction.orEmpty()))
                    }
                }
                // Mapbox does not report signals as manoeuvres, but it does mark
                // them on intersections, and a light every three hundred metres
                // is the difference between a drive and a commute.
                for (intersection in step.intersections) {
                    if (intersection.trafficSignal == true || intersection.stopSign == true) {
                        intersection.location.toLatLng()?.let {
                            add(Maneuver(ManeuverKind.SIGNAL, it))
                        }
                    }
                }
            }
        }
    }

    private fun roadRefs(route: MapboxRoute): List<String> {
        val refs = LinkedHashSet<String>()
        for (leg in route.legs) {
            for (step in leg.steps) {
                step.ref?.split(';', ',')?.forEach { raw ->
                    val ref = raw.replace(" ", "").trim()
                    if (ref.isNotEmpty()) refs += ref
                }
            }
        }
        return refs.toList()
    }

    private fun kindOf(type: String?, modifier: String?): ManeuverKind = when (type) {
        "turn", "end of road", "new name" ->
            if (modifier == "straight" || modifier == null) ManeuverKind.CONTINUE else ManeuverKind.TURN
        "roundabout", "rotary", "roundabout turn" -> ManeuverKind.ROUNDABOUT
        "exit roundabout", "exit rotary", "off ramp" -> ManeuverKind.EXIT
        "merge", "on ramp" -> ManeuverKind.MERGE
        "fork" -> ManeuverKind.FORK
        "arrive" -> ManeuverKind.ARRIVE
        else -> ManeuverKind.CONTINUE
    }

    private fun List<Double>.toLatLng(): LatLng? {
        if (size < 2) return null
        val lon = this[0]
        val lat = this[1]
        if (lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) return null
        return LatLng(lat, lon)
    }

    private fun candidateId(request: RouteRequest, index: Int): String {
        val via = request.via.firstOrNull()
        return if (via == null) "mapbox-$index" else "mapbox-via-${fmt(via.lat)}-${fmt(via.lon)}-$index"
    }

    private fun fmt(v: Double): String = String.format(java.util.Locale.US, "%.6f", v)

    private fun isoUtc(epochSeconds: Long): String =
        java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")
            .withZone(java.time.ZoneOffset.UTC)
            .format(java.time.Instant.ofEpochSecond(epochSeconds))

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .build()
    }
}
