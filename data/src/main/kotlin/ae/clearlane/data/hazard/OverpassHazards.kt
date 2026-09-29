package ae.clearlane.data.hazard

import ae.clearlane.core.model.Hazard
import ae.clearlane.core.model.HazardKind
import ae.clearlane.core.model.LatLng
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.floor

/**
 * Cameras anywhere, straight out of OpenStreetMap through Overpass.
 *
 * ### Why this is off unless asked for
 *
 * Overpass is run on donated hardware for interactive and research use, and its
 * usage policy is explicit that it is not there to back an app's traffic. One
 * developer testing is fine; ten thousand phones asking on every route is
 * exactly what the policy asks people not to do. So this is disabled by
 * default, and the UAE, which is the case the app is actually built for, is
 * served by the bundled extract instead.
 *
 * Turning it on is the right move for a developer filling in another country.
 * Shipping it on is not: that needs either a self hosted Overpass instance or,
 * better, an extract built at release time the same way the bundled one was.
 */
class OverpassHazards(
    override val isConfigured: Boolean = false,
    private val endpoint: String = DEFAULT_ENDPOINT,
    private val userAgent: String = DEFAULT_USER_AGENT,
    private val client: OkHttpClient = defaultClient(),
) : HazardSource {

    override val name: String = "OpenStreetMap (Overpass)"

    override val attribution: String = "Cameras: OpenStreetMap contributors, ODbL"

    /**
     * Answers already fetched, keyed by the rounded box. Panning a map around
     * one city must not turn into one query per frame, and a camera does not
     * move, so anything already known is reused for the life of the process.
     */
    private val cache = LinkedHashMap<String, List<Hazard>>()

    override suspend fun near(southWest: LatLng, northEast: LatLng): List<Hazard> {
        if (!isConfigured) return emptyList()

        val box = Box.around(southWest, northEast)
        if (box.spanDegrees > MAX_SPAN_DEGREES) {
            throw HazardSourceException(
                "Area too large for a camera lookup: ${"%.1f".format(box.spanDegrees)} degrees across.",
            )
        }
        synchronized(cache) { cache[box.key] }?.let { return it }

        val body = query(box).toRequestBody(FORM)
        val request = Request.Builder()
            .url(endpoint)
            .header("User-Agent", userAgent)
            .post(body)
            .build()

        val text = withContext(Dispatchers.IO) {
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw HazardSourceException("Camera lookup failed: HTTP ${response.code}")
                    }
                    response.body?.string().orEmpty()
                }
            } catch (e: IOException) {
                throw HazardSourceException("Camera lookup could not reach the network.", e)
            }
        }

        val hazards = parse(text)
        synchronized(cache) {
            if (cache.size >= MAX_CACHED_BOXES) {
                cache.keys.firstOrNull()?.let(cache::remove)
            }
            cache[box.key] = hazards
        }
        return hazards
    }

    /**
     * Both the point cameras and the section enforcement nodes, in one request.
     * Written on one line because Overpass is sensitive about whitespace in a
     * form encoded body.
     */
    private fun query(box: Box): String {
        val bbox = "${box.south},${box.west},${box.north},${box.east}"
        return "data=" + buildString {
            append("[out:json][timeout:60];(")
            append("node[\"highway\"=\"speed_camera\"]($bbox);")
            append("node[\"enforcement\"=\"maxspeed\"]($bbox);")
            append(");out body;")
        }
    }

    private fun parse(text: String): List<Hazard> {
        val root = try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            throw HazardSourceException("Camera lookup returned something that was not JSON.", e)
        }
        val elements = root["elements"]?.jsonArray ?: return emptyList()

        return elements.mapNotNull { element ->
            val node = element.jsonObject
            val lat = node["lat"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            val lon = node["lon"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return@mapNotNull null
            val tags = node["tags"]?.jsonObject ?: JsonObject(emptyMap())

            val kind = when {
                tags.text("speed_camera") == "traffic_signals" -> HazardKind.RED_LIGHT_CAMERA
                tags.text("highway") == "speed_camera" -> HazardKind.FIXED_CAMERA
                tags.text("enforcement") == "maxspeed" -> HazardKind.AVERAGE_SPEED
                else -> return@mapNotNull null
            }

            Hazard(
                kind = kind,
                at = LatLng(lat, lon),
                speedLimitKph = tags.limit(),
                bearingDeg = tags.bearing(),
                source = "OpenStreetMap",
            )
        }
    }

    private fun JsonObject.text(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.limit(): Int? {
        for (key in listOf("maxspeed", "maxspeed:forward")) {
            // OSM writes "80", and also "80 mph" and "AE:urban". Only a plain
            // number is taken; a unit or a country code is left alone rather
            // than guessed at.
            val raw = text(key)?.trim()?.substringBefore(' ') ?: continue
            val value = raw.toIntOrNull() ?: continue
            if (value in 5..200) return value
        }
        return null
    }

    /**
     * Only a numeric bearing survives. "forward" and "backward" are relative to
     * the direction of the OSM way, which this query does not fetch, so they
     * cannot be resolved here and the camera is left facing both ways.
     */
    private fun JsonObject.bearing(): Double? {
        val value = text("direction")?.trim()?.toDoubleOrNull() ?: return null
        return ((value % 360.0) + 360.0) % 360.0
    }

    /** A query box, snapped outward to a grid so the cache actually hits. */
    private data class Box(
        val south: Double,
        val west: Double,
        val north: Double,
        val east: Double,
    ) {
        val spanDegrees: Double get() = maxOf(north - south, east - west)

        val key: String = "%.1f,%.1f,%.1f,%.1f".format(south, west, north, east)

        companion object {
            private const val GRID = 0.1

            fun around(southWest: LatLng, northEast: LatLng) = Box(
                south = floor(southWest.lat / GRID) * GRID,
                west = floor(southWest.lon / GRID) * GRID,
                north = (floor(northEast.lat / GRID) + 1) * GRID,
                east = (floor(northEast.lon / GRID) + 1) * GRID,
            )
        }
    }

    companion object {
        /**
         * A mirror rather than the main instance, which is the busier of the
         * two and the one the policy is most concerned about.
         */
        const val DEFAULT_ENDPOINT: String = "https://overpass.kumi.systems/api/interpreter"

        /** Overpass rejects requests with no user agent, and reasonably so. */
        const val DEFAULT_USER_AGENT: String = "clearlane/0.2 (+https://github.com/ShezinKhais/clearlane)"

        /** Two degrees is about 220 km. Past that, use an extract. */
        const val MAX_SPAN_DEGREES: Double = 2.0

        private const val MAX_CACHED_BOXES = 24

        private val FORM = "application/x-www-form-urlencoded".toMediaType()

        private val json = Json { ignoreUnknownKeys = true }

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
