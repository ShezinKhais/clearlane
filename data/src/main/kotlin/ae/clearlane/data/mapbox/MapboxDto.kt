package ae.clearlane.data.mapbox

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Just enough of the Mapbox Directions response to score a route.
 *
 * Every field is optional, because the shape of the response depends on which
 * annotations were asked for and on how much the provider knows about the road.
 * A missing speed limit on one segment of one leg is normal and must not take
 * the whole request down.
 */
@Serializable
internal data class MapboxDirectionsResponse(
    val code: String? = null,
    val message: String? = null,
    val routes: List<MapboxRoute> = emptyList(),
)

@Serializable
internal data class MapboxRoute(
    val duration: Double = 0.0,
    val distance: Double = 0.0,
    @SerialName("duration_typical") val durationTypical: Double? = null,
    val geometry: String? = null,
    val legs: List<MapboxLeg> = emptyList(),
)

@Serializable
internal data class MapboxLeg(
    val duration: Double = 0.0,
    val distance: Double = 0.0,
    val summary: String? = null,
    val annotation: MapboxAnnotation? = null,
    val steps: List<MapboxStep> = emptyList(),
)

/**
 * Parallel arrays, one entry per segment between consecutive geometry points.
 *
 * `congestion_numeric` runs 0 to 100 on Mapbox's own scale and can be null
 * where they have no probe coverage. See [MapboxRouteProvider] for how it is
 * mapped onto this project's 0 to 1 congestion.
 */
@Serializable
internal data class MapboxAnnotation(
    val distance: List<Double>? = null,
    /** Current speed in metres per second. */
    val speed: List<Double?>? = null,
    val maxspeed: List<MapboxMaxSpeed>? = null,
    @SerialName("congestion_numeric") val congestionNumeric: List<Int?>? = null,
)

@Serializable
internal data class MapboxMaxSpeed(
    val speed: Int? = null,
    /** "km/h" or "mph". */
    val unit: String? = null,
    val unknown: Boolean? = null,
    val none: Boolean? = null,
) {
    /** The limit in metres per second, or null when it is not known. */
    val metresPerSecond: Double?
        get() {
            if (unknown == true || none == true) return null
            val s = speed ?: return null
            if (s <= 0) return null
            return if (unit.equals("mph", ignoreCase = true)) s * 0.44704 else s * 1000.0 / 3600.0
        }
}

@Serializable
internal data class MapboxStep(
    val name: String? = null,
    val ref: String? = null,
    val distance: Double = 0.0,
    val maneuver: MapboxManeuver? = null,
    val intersections: List<MapboxIntersection> = emptyList(),
)

@Serializable
internal data class MapboxManeuver(
    val type: String? = null,
    val modifier: String? = null,
    val instruction: String? = null,
    /** [lon, lat], which is the opposite order to the rest of this project. */
    val location: List<Double> = emptyList(),
)

@Serializable
internal data class MapboxIntersection(
    val location: List<Double> = emptyList(),
    @SerialName("traffic_signal") val trafficSignal: Boolean? = null,
    @SerialName("stop_sign") val stopSign: Boolean? = null,
)
