package ae.clearlane.core.model

/**
 * Something on the road worth being told about before you reach it.
 *
 * Only enforcement cameras for now, because they are the one class of hazard
 * with a published, verifiable position. Crash and obstruction reports need a
 * crowd to report them, and inventing that data would be worse than not having
 * it.
 */
enum class HazardKind {
    /** A fixed camera at a point, triggered by speed over it. */
    FIXED_CAMERA,

    /** Section enforcement: an average is taken between two points. */
    AVERAGE_SPEED,

    /** A camera on a junction, triggered by crossing on red. */
    RED_LIGHT_CAMERA,
}

/**
 * [bearingDeg] is the direction of travel the camera faces, where that is
 * known. A camera with no bearing is announced whichever way you approach it,
 * which is the cautious reading: a false warning costs a glance, a missed one
 * costs a fine.
 */
data class Hazard(
    val kind: HazardKind,
    val at: LatLng,
    val speedLimitKph: Int? = null,
    val bearingDeg: Double? = null,
    /** Where the record came from, so the UI can attribute it. */
    val source: String = "unknown",
) {
    /**
     * Whether a car travelling on [heading] is approaching this camera from the
     * side it looks at. True when the camera has no recorded bearing.
     */
    fun facing(heading: Double?, toleranceDeg: Double = FACING_TOLERANCE): Boolean {
        val facing = bearingDeg ?: return true
        if (heading == null) return true
        return kotlin.math.abs(bearingDelta(facing, heading)) <= toleranceDeg
    }

    companion object {
        /**
         * Generous, because the tagged bearing is the direction the housing
         * points and a road bends. Narrower than this starts dropping real
         * cameras on a curve.
         */
        const val FACING_TOLERANCE: Double = 65.0
    }
}

/** A hazard pinned to a distance along a particular route. */
data class RouteHazard(
    val hazard: Hazard,
    /** Metres from the start of the route to the point nearest the hazard. */
    val atMeters: Double,
    /** How far the hazard sits off the route line. */
    val offMeters: Double,
)
