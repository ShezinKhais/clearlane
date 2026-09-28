package ae.clearlane.data.provider

import ae.clearlane.core.model.LatLng
import kotlin.math.pow

/**
 * Encoded polyline, the format every routing API ships geometry in.
 *
 * Mapbox defaults to five decimal places and can be asked for six. Six is what
 * this project asks for: at five, a coordinate is only good to about a metre,
 * and rounding error at that scale shows up as a visible stagger when a route
 * is drawn zoomed in on a phone.
 */
object Polyline {

    /** Decodes an encoded polyline at the given precision. */
    fun decode(encoded: String, precision: Int = 6): List<LatLng> {
        if (encoded.isEmpty()) return emptyList()
        val factor = 10.0.pow(precision)
        val out = ArrayList<LatLng>(encoded.length / 4)
        var index = 0
        var lat = 0L
        var lon = 0L

        while (index < encoded.length) {
            val dLat = readValue(encoded, index) ?: break
            index = dLat.second
            lat += dLat.first
            val dLon = readValue(encoded, index) ?: break
            index = dLon.second
            lon += dLon.first
            val latDeg = lat / factor
            val lonDeg = lon / factor
            // A provider should never send us a coordinate off the planet, but
            // a truncated response can decode into one, and LatLng refuses to
            // hold it. Stopping is better than throwing from a draw call.
            if (latDeg < -90.0 || latDeg > 90.0 || lonDeg < -180.0 || lonDeg > 180.0) break
            out += LatLng(latDeg, lonDeg)
        }
        return out
    }

    /** One zigzag encoded varint, and the index just past it. */
    private fun readValue(encoded: String, start: Int): Pair<Long, Int>? {
        var index = start
        var shift = 0
        var result = 0L
        while (index < encoded.length) {
            val b = encoded[index].code - 63
            if (b < 0) return null
            index++
            result = result or ((b and 0x1f).toLong() shl shift)
            shift += 5
            if (b < 0x20) {
                val value = if (result and 1L == 1L) (result shr 1).inv() else (result shr 1)
                return value to index
            }
            if (shift > 60) return null
        }
        return null
    }

    /** Encodes a path, used by the fixtures and by tests. */
    fun encode(points: List<LatLng>, precision: Int = 6): String {
        val factor = 10.0.pow(precision)
        val sb = StringBuilder()
        var lastLat = 0L
        var lastLon = 0L
        for (p in points) {
            val lat = Math.round(p.lat * factor)
            val lon = Math.round(p.lon * factor)
            writeValue(sb, lat - lastLat)
            writeValue(sb, lon - lastLon)
            lastLat = lat
            lastLon = lon
        }
        return sb.toString()
    }

    private fun writeValue(sb: StringBuilder, value: Long) {
        var v = if (value < 0) (value shl 1).inv() else (value shl 1)
        while (v >= 0x20) {
            sb.append(((0x20 or (v and 0x1f).toInt()) + 63).toChar())
            v = v shr 5
        }
        sb.append((v.toInt() + 63).toChar())
    }
}
