package ae.clearlane.data.hazard

import ae.clearlane.core.model.Hazard
import ae.clearlane.core.model.HazardKind
import ae.clearlane.core.model.LatLng
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The UAE camera list that ships inside the app.
 *
 * Every position in it is a real OpenStreetMap node tagged as an enforcement
 * camera, extracted once and committed, rather than anything this project
 * worked out for itself. That is the same rule the Salik gates follow: publish
 * what is on record and leave out what is not. A guessed camera is worse than a
 * missing one, because a driver who is warned about nothing learns to trust the
 * warnings.
 *
 * It is bundled rather than fetched because it is small, it changes slowly, and
 * a camera warning that depends on having signal is no use in the one place you
 * want it. The trade is that the list is only as fresh as the build.
 */
class BundledHazards(
    /** Reads the asset. Injected so the parser can be tested off Android. */
    private val read: () -> String,
) : HazardSource {

    override val name: String = "OpenStreetMap"

    override val attribution: String = "Cameras: OpenStreetMap contributors, ODbL"

    override val isConfigured: Boolean = true

    /** Parsed on first use, then kept. Roughly 1,500 records. */
    private val all: List<Hazard> by lazy { parse() }

    /** The snapshot date of the bundled extract, for the about line. */
    val snapshot: String by lazy { document.snapshot }

    private val document: Document by lazy {
        json.decodeFromString(Document.serializer(), read())
    }

    override suspend fun near(southWest: LatLng, northEast: LatLng): List<Hazard> =
        all.filter {
            it.at.lat >= southWest.lat && it.at.lat <= northEast.lat &&
                it.at.lon >= southWest.lon && it.at.lon <= northEast.lon
        }

    /** Everything in the extract, for counting and for the about screen. */
    fun count(): Int = all.size

    private fun parse(): List<Hazard> = document.cameras.mapNotNull { record ->
        val kind = when (record.kind) {
            "FIXED" -> HazardKind.FIXED_CAMERA
            "AVERAGE_SPEED" -> HazardKind.AVERAGE_SPEED
            "RED_LIGHT" -> HazardKind.RED_LIGHT_CAMERA
            // An unknown kind in a newer extract is skipped rather than
            // guessed at, so an older build cannot invent a warning.
            else -> null
        } ?: return@mapNotNull null

        if (record.lat !in -90.0..90.0 || record.lon !in -180.0..180.0) return@mapNotNull null

        Hazard(
            kind = kind,
            at = LatLng(record.lat, record.lon),
            speedLimitKph = record.limitKph?.takeIf { it in 5..200 },
            bearingDeg = record.bearing?.let { ((it % 360.0) + 360.0) % 360.0 },
            source = name,
        )
    }

    @Serializable
    private data class Document(
        val area: String = "",
        val source: String = "",
        val license: String = "",
        val snapshot: String = "",
        val cameras: List<Record> = emptyList(),
    )

    /**
     * Short field names because there are about fifteen hundred of these and
     * the file goes into the APK. The schema is spelled out in the asset's own
     * note field.
     */
    @Serializable
    private data class Record(
        @SerialName("y") val lat: Double,
        @SerialName("x") val lon: Double,
        @SerialName("k") val kind: String,
        @SerialName("l") val limitKph: Int? = null,
        @SerialName("b") val bearing: Double? = null,
    )

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
