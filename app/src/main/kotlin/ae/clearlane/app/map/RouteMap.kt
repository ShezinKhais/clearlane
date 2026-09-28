package ae.clearlane.app.map

import ae.clearlane.app.BuildConfig
import ae.clearlane.app.ui.Palette
import ae.clearlane.core.model.LatLng
import ae.clearlane.core.model.RouteCandidate
import ae.clearlane.core.model.ScoredRoute
import ae.clearlane.core.model.bounds
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource

/**
 * The routes on a map, coloured by how each part of them is flowing.
 *
 * Every candidate is drawn at once, dimmed, with the chosen one on top in full
 * colour. Seeing all of them together is the argument the app is making: four
 * lines fanning inland from the coast, three of them green and the short one
 * red.
 *
 * ### The basemap
 *
 * With no style configured this falls back to MapLibre's own demo tiles, which
 * need no account but only draw land and borders, so a route appears on an
 * empty background. Put a style URL in local.properties for streets. It is
 * deliberately not pointed at the public OpenStreetMap tile servers, whose
 * usage policy does not cover applications.
 */
private const val DEMO_STYLE = "https://demotiles.maplibre.org/style.json"

private const val SOURCE_DIM = "clearlane-dim"
private const val SOURCE_ACTIVE = "clearlane-active"
private const val LAYER_DIM = "clearlane-dim-line"
private const val LAYER_ACTIVE_CASING = "clearlane-active-casing"
private const val LAYER_ACTIVE = "clearlane-active-line"
private const val PROPERTY_BAND = "band"

@Composable
fun RouteMap(
    routes: List<ScoredRoute>,
    highlighted: ScoredRoute?,
    onLongPress: (LatLng) -> Unit,
    onTap: (LatLng) -> Unit,
    modifier: Modifier = Modifier,
) {
    val holder = remember { MapHolder() }

    DisposableEffect(Unit) {
        onDispose { holder.release() }
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            MapLibre.getInstance(context)
            MapView(context).also { view ->
                holder.attach(view)
                view.onCreate(null)
                view.onStart()
                view.onResume()
                view.getMapAsync { map ->
                    val style = BuildConfig.MAP_STYLE_URL.ifBlank { DEMO_STYLE }
                    map.setStyle(Style.Builder().fromUri(style)) { loaded ->
                        holder.onStyleReady(map, loaded)
                    }
                    map.addOnMapLongClickListener { point ->
                        onLongPress(LatLng(point.latitude, point.longitude))
                        true
                    }
                    map.addOnMapClickListener { point ->
                        onTap(LatLng(point.latitude, point.longitude))
                        true
                    }
                }
            }
        },
        update = { holder.render(routes, highlighted) },
    )
}

/**
 * Keeps hold of the map between recompositions.
 *
 * The style loads asynchronously, and Compose will have called update several
 * times before it is ready, so the latest routes are stashed and drawn once
 * there is somewhere to draw them.
 */
private class MapHolder {
    private var view: MapView? = null
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var pending: Pair<List<ScoredRoute>, ScoredRoute?>? = null
    private var framedFor: String? = null

    fun attach(mapView: MapView) {
        view = mapView
    }

    fun onStyleReady(ready: MapLibreMap, loaded: Style) {
        map = ready
        style = loaded

        loaded.addSource(GeoJsonSource(SOURCE_DIM, emptyCollection()))
        loaded.addSource(GeoJsonSource(SOURCE_ACTIVE, emptyCollection()))

        loaded.addLayer(
            LineLayer(LAYER_DIM, SOURCE_DIM).withProperties(
                PropertyFactory.lineColor(Palette.line.toArgb()),
                PropertyFactory.lineWidth(3.0f),
                PropertyFactory.lineOpacity(0.85f),
                PropertyFactory.lineCap("round"),
                PropertyFactory.lineJoin("round"),
            ),
        )
        // A dark casing under the active route, so a green line stays readable
        // where it crosses a pale patch of basemap.
        loaded.addLayer(
            LineLayer(LAYER_ACTIVE_CASING, SOURCE_ACTIVE).withProperties(
                PropertyFactory.lineColor(Palette.background.toArgb()),
                PropertyFactory.lineWidth(9.0f),
                PropertyFactory.lineCap("round"),
                PropertyFactory.lineJoin("round"),
            ),
        )
        loaded.addLayer(
            LineLayer(LAYER_ACTIVE, SOURCE_ACTIVE).withProperties(
                PropertyFactory.lineColor(bandExpression()),
                PropertyFactory.lineWidth(5.5f),
                PropertyFactory.lineCap("round"),
                PropertyFactory.lineJoin("round"),
            ),
        )

        pending?.let { (routes, highlighted) -> draw(routes, highlighted) }
    }

    fun render(routes: List<ScoredRoute>, highlighted: ScoredRoute?) {
        pending = routes to highlighted
        if (style != null) draw(routes, highlighted)
    }

    private fun draw(routes: List<ScoredRoute>, highlighted: ScoredRoute?) {
        val loaded = style ?: return
        val dim = routes.filter { it.id != highlighted?.id }

        (loaded.getSource(SOURCE_DIM) as? GeoJsonSource)
            ?.setGeoJson(plainCollection(dim.map { it.candidate }))
        (loaded.getSource(SOURCE_ACTIVE) as? GeoJsonSource)
            ?.setGeoJson(bandedCollection(highlighted?.candidate))

        // Frame the whole set once per origin and destination pair. Re-framing
        // on every tier change would yank the map about while the driver is
        // trying to compare two lines on it.
        val key = routes.joinToString("|") { it.id }
        if (key != framedFor && routes.isNotEmpty()) {
            framedFor = key
            frame(routes.flatMap { it.candidate.geometry })
        }
    }

    private fun frame(points: List<LatLng>) {
        val map = map ?: return
        val (southWest, northEast) = points.bounds(padMeters = 1_500.0) ?: return
        if (southWest.lat >= northEast.lat || southWest.lon >= northEast.lon) return
        val bounds = LatLngBounds.Builder()
            .include(org.maplibre.android.geometry.LatLng(southWest.lat, southWest.lon))
            .include(org.maplibre.android.geometry.LatLng(northEast.lat, northEast.lon))
            .build()
        map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 48))
    }

    fun release() {
        view?.let { v ->
            v.onPause()
            v.onStop()
            v.onDestroy()
        }
        view = null
        map = null
        style = null
    }
}

/** `match` on the band property, so one layer draws all four colours. */
private fun bandExpression(): Expression = Expression.match(
    Expression.get(PROPERTY_BAND),
    Expression.color(Palette.clear.toArgb()),
    Expression.stop(0, Expression.color(Palette.bandColors[0].toArgb())),
    Expression.stop(1, Expression.color(Palette.bandColors[1].toArgb())),
    Expression.stop(2, Expression.color(Palette.bandColors[2].toArgb())),
    Expression.stop(3, Expression.color(Palette.bandColors[3].toArgb())),
)

private fun emptyCollection(): String = """{"type":"FeatureCollection","features":[]}"""

/** One feature per route, no congestion detail. For the routes not chosen. */
private fun plainCollection(routes: List<RouteCandidate>): String {
    val features = routes.filter { it.geometry.size >= 2 }.joinToString(",") { route ->
        """{"type":"Feature","properties":{},"geometry":${lineString(route.geometry)}}"""
    }
    return """{"type":"FeatureCollection","features":[$features]}"""
}

/**
 * The chosen route split into runs of the same congestion band, one feature
 * each, so the line changes colour where the traffic does.
 */
private fun bandedCollection(route: RouteCandidate?): String {
    if (route == null || route.segments.isEmpty()) return emptyCollection()

    val features = mutableListOf<String>()
    var run = mutableListOf(route.segments.first().start)
    var band = Palette.bandOf(route.segments.first().congestion)

    for (segment in route.segments) {
        val segmentBand = Palette.bandOf(segment.congestion)
        if (segmentBand != band) {
            // Close the run on the shared point, so there is no gap in the line
            // where one colour hands over to the next.
            run += segment.start
            if (run.size >= 2) {
                features += """{"type":"Feature","properties":{"$PROPERTY_BAND":$band},"geometry":${lineString(run)}}"""
            }
            run = mutableListOf(segment.start)
            band = segmentBand
        }
        run += segment.end
    }
    if (run.size >= 2) {
        features += """{"type":"Feature","properties":{"$PROPERTY_BAND":$band},"geometry":${lineString(run)}}"""
    }
    return """{"type":"FeatureCollection","features":[${features.joinToString(",")}]}"""
}

private fun lineString(points: List<LatLng>): String {
    val coordinates = points.joinToString(",") { p ->
        "[${"%.6f".format(java.util.Locale.US, p.lon)},${"%.6f".format(java.util.Locale.US, p.lat)}]"
    }
    return """{"type":"LineString","coordinates":[$coordinates]}"""
}
