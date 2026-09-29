package ae.clearlane.core.model

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/** WGS84 coordinate. Order is lat then lon everywhere in this project. */
data class LatLng(val lat: Double, val lon: Double) {
    init {
        require(lat in -90.0..90.0) { "lat out of range: $lat" }
        require(lon in -180.0..180.0) { "lon out of range: $lon" }
    }

    override fun toString(): String = "%.5f,%.5f".format(lat, lon)
}

const val EARTH_RADIUS_M: Double = 6_371_008.8

private const val DEG = Math.PI / 180.0

/** Great circle distance in metres. */
fun LatLng.distanceTo(other: LatLng): Double {
    val dLat = (other.lat - lat) * DEG
    val dLon = (other.lon - lon) * DEG
    val a = sin(dLat / 2).let { it * it } +
        cos(lat * DEG) * cos(other.lat * DEG) * sin(dLon / 2).let { it * it }
    return 2 * EARTH_RADIUS_M * asin(sqrt(a.coerceIn(0.0, 1.0)))
}

/** Initial bearing in degrees, 0 = north, clockwise. */
fun LatLng.bearingTo(other: LatLng): Double {
    val dLon = (other.lon - lon) * DEG
    val y = sin(dLon) * cos(other.lat * DEG)
    val x = cos(lat * DEG) * sin(other.lat * DEG) -
        sin(lat * DEG) * cos(other.lat * DEG) * cos(dLon)
    return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
}

/** Point [meters] away on [bearingDeg]. Used to lay out detour anchors. */
fun LatLng.offset(meters: Double, bearingDeg: Double): LatLng {
    val d = meters / EARTH_RADIUS_M
    val b = bearingDeg * DEG
    val lat1 = lat * DEG
    val lon1 = lon * DEG
    val lat2 = asin(sin(lat1) * cos(d) + cos(lat1) * sin(d) * cos(b))
    val lon2 = lon1 + atan2(sin(b) * sin(d) * cos(lat1), cos(d) - sin(lat1) * sin(lat2))
    return LatLng(
        Math.toDegrees(lat2).coerceIn(-90.0, 90.0),
        ((Math.toDegrees(lon2) + 540.0) % 360.0) - 180.0,
    )
}

fun LatLng.midpointTo(other: LatLng): LatLng =
    LatLng((lat + other.lat) / 2.0, (lon + other.lon) / 2.0)

/** Signed difference between two bearings, in (-180, 180]. */
fun bearingDelta(from: Double, to: Double): Double {
    var d = (to - from + 540.0) % 360.0 - 180.0
    if (d == -180.0) d = 180.0
    return d
}

/** Total length of a polyline in metres. */
fun List<LatLng>.pathLength(): Double {
    if (size < 2) return 0.0
    var total = 0.0
    for (i in 1 until size) total += this[i - 1].distanceTo(this[i])
    return total
}

/**
 * Bounding box, grown by [padMeters] so a route drawn at the edge of the map
 * is not flush against the frame.
 */
fun List<LatLng>.bounds(padMeters: Double = 0.0): Pair<LatLng, LatLng>? {
    if (isEmpty()) return null
    var minLat = Double.MAX_VALUE
    var minLon = Double.MAX_VALUE
    var maxLat = -Double.MAX_VALUE
    var maxLon = -Double.MAX_VALUE
    for (p in this) {
        if (p.lat < minLat) minLat = p.lat
        if (p.lat > maxLat) maxLat = p.lat
        if (p.lon < minLon) minLon = p.lon
        if (p.lon > maxLon) maxLon = p.lon
    }
    if (padMeters <= 0.0) return LatLng(minLat, minLon) to LatLng(maxLat, maxLon)
    val dLat = Math.toDegrees(padMeters / EARTH_RADIUS_M)
    val cosLat = cos(((minLat + maxLat) / 2.0) * DEG).coerceAtLeast(1e-6)
    val dLon = dLat / cosLat
    return LatLng((minLat - dLat).coerceAtLeast(-90.0), (minLon - dLon).coerceAtLeast(-180.0)) to
        LatLng((maxLat + dLat).coerceAtMost(90.0), (maxLon + dLon).coerceAtMost(180.0))
}

/**
 * Radius of the circle through three consecutive points, in metres, or
 * [Double.MAX_VALUE] when they are effectively collinear. Small radius means a
 * tight corner, large means a straight. The band in between is what makes a
 * road worth driving, which is why [ae.clearlane.core.scoring.DriveScoring]
 * asks for it.
 */
fun circumRadius(a: LatLng, b: LatLng, c: LatLng): Double {
    // Project to a local metric frame. Over three points of a road this is
    // accurate to well under a percent, and far cheaper than doing it properly.
    val latRef = b.lat * DEG
    val mx = EARTH_RADIUS_M * cos(latRef) * DEG
    val my = EARTH_RADIUS_M * DEG
    val ax = (a.lon - b.lon) * mx
    val ay = (a.lat - b.lat) * my
    val cx = (c.lon - b.lon) * mx
    val cy = (c.lat - b.lat) * my

    val la = hypot(ax, ay)
    val lc = hypot(cx, cy)
    // Twice the triangle area; zero when the points line up.
    val cross = ax * cy - ay * cx
    if (abs(cross) < 1e-9) return Double.MAX_VALUE
    val lb = hypot(cx - ax, cy - ay)
    if (la < 1e-6 || lb < 1e-6 || lc < 1e-6) return Double.MAX_VALUE
    return (la * lb * lc) / (2.0 * abs(cross))
}

/** Where a point falls on a segment, and how far off it is. */
data class Projection(
    /** The point on the segment closest to the one asked about. */
    val at: LatLng,
    /** How far along the segment, 0 at the start and 1 at the end. */
    val fraction: Double,
    /** Perpendicular distance from the original point, in metres. */
    val offMeters: Double,
)

/**
 * Closest point on the segment [a]..[b] to [this], clamped to the ends.
 *
 * Used to put a moving car on a route: a GPS fix is never exactly on the line,
 * and the difference between "12 m off" and "400 m off" is the difference
 * between normal drift and having left the route.
 *
 * Flat maths in a local metric frame. Over a segment a few hundred metres long
 * the error is far below GPS noise, and the alternative costs trigonometry on
 * every fix.
 */
fun LatLng.projectOnto(a: LatLng, b: LatLng): Projection {
    val mx = EARTH_RADIUS_M * cos(a.lat * DEG) * DEG
    val my = EARTH_RADIUS_M * DEG
    val abx = (b.lon - a.lon) * mx
    val aby = (b.lat - a.lat) * my
    val apx = (lon - a.lon) * mx
    val apy = (lat - a.lat) * my

    val lenSq = abx * abx + aby * aby
    val t = if (lenSq < 1e-9) 0.0 else ((apx * abx + apy * aby) / lenSq).coerceIn(0.0, 1.0)
    val at = LatLng(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)
    return Projection(at = at, fraction = t, offMeters = distanceTo(at))
}
