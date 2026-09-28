package ae.clearlane.data.uae

import ae.clearlane.core.model.LatLng
import ae.clearlane.core.model.RouteCandidate
import ae.clearlane.core.model.TollCrossing
import ae.clearlane.core.model.distanceTo
import java.time.ZonedDateTime

/**
 * A toll gate. [location] is null until someone supplies a surveyed
 * coordinate, and pricing is skipped for any gate without one.
 *
 * That is deliberate. The gate names, the roads they sit on and the tariffs are
 * all published, but no official coordinate list is. Coordinates guessed from a
 * road name would put a gate on the wrong side of an interchange and quietly
 * add four dirhams to a route that never passes it, and a driver has no way to
 * tell that happened. An obviously empty toll figure is a better failure than a
 * plausible wrong one.
 */
data class TollGate(
    val name: String,
    val road: String,
    val operator: String,
    val location: LatLng? = null,
)

/**
 * Prices the tolls on a route.
 *
 * Salik charges per gate passed, so the cost of a route is the number of gates
 * it crosses times the rate for the time of day. There is no daily cap in
 * Dubai. Abu Dhabi's Darb does cap, and the bridge gates only charge in the
 * peaks, which is why the two are kept apart rather than lumped into one
 * "tolls" number.
 */
object TollModel {

    /**
     * How close the route has to pass for the gate to count. Wide enough to
     * cope with a gate coordinate taken from the middle of the carriageway and
     * a route geometry snapped to one side of it, narrow enough not to catch
     * the slip road that goes under the gantry without passing it.
     */
    const val GATE_RADIUS_METERS = 120.0

    /**
     * Dubai's Salik gates, ten of them as of November 2024 when Business Bay
     * Crossing and Al Safa South opened. Names and roads are from Salik's own
     * network pages; see [SalikTariff] for the tariff source.
     */
    val SALIK_GATES: List<TollGate> = listOf(
        TollGate("Al Barsha", "E11 Sheikh Zayed Road", "Salik"),
        TollGate("Al Safa", "E11 Sheikh Zayed Road", "Salik"),
        TollGate("Al Safa South", "E11 Sheikh Zayed Road", "Salik"),
        TollGate("Al Maktoum Bridge", "Al Maktoum Bridge", "Salik"),
        TollGate("Al Garhoud Bridge", "Al Garhoud Bridge", "Salik"),
        TollGate("Airport Tunnel", "Airport Tunnel", "Salik"),
        TollGate("Al Mamzar South", "E11 Al Ittihad Road", "Salik"),
        TollGate("Al Mamzar North", "E11 Al Ittihad Road", "Salik"),
        TollGate("Jebel Ali", "E11 Sheikh Zayed Road", "Salik"),
        TollGate("Business Bay Crossing", "E44 Al Khail Road", "Salik"),
    )

    /** True when no gate has a coordinate, so the UI can say tolls are not priced. */
    val isConfigured: Boolean get() = SALIK_GATES.any { it.location != null }

    /**
     * Gates [route] passes, priced for [at].
     *
     * Returns empty when no gate has a coordinate, which is the shipped state.
     */
    fun crossingsOn(
        route: RouteCandidate,
        at: ZonedDateTime,
        ramadan: Boolean = false,
        gates: List<TollGate> = SALIK_GATES,
    ): List<TollCrossing> {
        val priced = SalikTariff.filsAt(at, ramadan)
        val located = gates.filter { it.location != null }
        if (located.isEmpty()) return emptyList()

        return located.mapNotNull { gate ->
            val point = gate.location ?: return@mapNotNull null
            val passes = route.geometry.any { it.distanceTo(point) <= GATE_RADIUS_METERS }
            if (!passes) {
                null
            } else {
                TollCrossing(
                    name = gate.name,
                    operator = gate.operator,
                    fils = priced,
                    at = point,
                )
            }
        }
    }
}
