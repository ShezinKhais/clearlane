package ae.clearlane.app.map

import ae.clearlane.app.BuildConfig
import ae.clearlane.app.ui.Palette
import ae.clearlane.core.model.LatLng
import ae.clearlane.core.model.RouteCandidate
import ae.clearlane.core.model.RouteHazard
import ae.clearlane.core.model.ScoredRoute
import ae.clearlane.core.model.bounds
import ae.clearlane.core.nav.NavState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import java.util.Locale

/**
 * The routes on a map, coloured by how each part of them is flowing.
 *
 * Every candidate is drawn at once, dimmed, with the chosen one on top in full
 * colour. Seeing all of them together is the argument the app is making: four
 * lines fanning inland from the coast, three of them green and the short one
 * red.
 *
 * ### Two behaviours
 *
 * Planning: the whole set framed, north up, flat. The driver is comparing lines
 * on a map and wants to see all of them.
 *
 * Driving: locked to the car, turned so the road ahead runs up the screen, and
 * tilted. That is not decoration. A flat north up map means a driver has to
 * rotate it in their head at the moment they least want to, and every
 * navigation app that has ever been used in anger works this way for that
 * reason.
 *
 * ### The basemap
 *
 * With no style configured this falls back to a bundled style over MapLibre's
 * own demo tiles. Those need no account but only carry land and borders, so
 * there are no streets on it: a route appears on an empty field. The bundled
 * style exists because the demo tiles' own styling is a bright cartographic
 * one, and a dark app that opens onto a yellow map looks broken before a single
 * route has been read. Same data, this app's palette.
 *
 * Put a style URL in local.properties for real streets. It is deliberately not
 * pointed at the public OpenStreetMap tile servers, whose usage policy does not
 * cover applications.
 */
private const val FALLBACK_STYLE = "asset://clearlane/basemap-dark.json"

private const val SOURCE_DIM = "clearlane-dim"
private const val SOURCE_ACTIVE = "clearlane-active"
private const val SOURCE_CAMERAS = "clearlane-cameras"
private const val SOURCE_CAR = "clearlane-car"
private const val SOURCE_DONE = "clearlane-done"
private const val SOURCE_ENDS = "clearlane-ends"

private const val LAYER_DIM_CASING = "clearlane-dim-casing"
private const val LAYER_DIM = "clearlane-dim-line"
private const val LAYER_ACTIVE_CASING = "clearlane-active-casing"
private const val LAYER_ACTIVE = "clearlane-active-line"
private const val LAYER_ACTIVE_ARROWS = "clearlane-active-arrows"
private const val LAYER_ENDS = "clearlane-ends-symbol"
private const val LAYER_CAMERAS = "clearlane-cameras-symbol"
private const val LAYER_CAR = "clearlane-car-symbol"
private const val LAYER_DONE = "clearlane-done-line"

private const val PROPERTY_BAND = "band"
private const val PROPERTY_BEARING = "bearing"
private const val PROPERTY_ICON = "icon"

@Composable
fun RouteMap(
    routes: List<ScoredRoute>,
    highlighted: ScoredRoute?,
    onLongPress: (LatLng) -> Unit,
    onTap: (LatLng) -> Unit,
    modifier: Modifier = Modifier,
    /** Cameras on the highlighted route, drawn only while driving. */
    hazards: List<RouteHazard> = emptyList(),
    /** Non null while driving: the map then follows the car. */
    nav: NavState? = null,
    /** False once the driver has panned away, so the map stops fighting them. */
    following: Boolean = true,
    /**
     * Called when the driver moves the map themselves. Following has to stop at
     * that point: a map that snaps back to the car half a second after someone
     * drags it to look ahead is a map that cannot be looked at.
     */
    onUserPan: () -> Unit = {},
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
                holder.attach(view, context.resources.displayMetrics.density)
                view.onCreate(null)
                view.onStart()
                view.onResume()
                view.getMapAsync { map ->
                    map.uiSettings.isRotateGesturesEnabled = true
                    map.uiSettings.isTiltGesturesEnabled = false
                    map.uiSettings.isAttributionEnabled = true
                    map.uiSettings.isLogoEnabled = false

                    val style = BuildConfig.MAP_STYLE_URL.ifBlank { FALLBACK_STYLE }
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
                    map.addOnCameraMoveStartedListener { reason ->
                        if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                            onUserPan()
                        }
                    }
                }
            }
        },
        update = { holder.render(routes, highlighted, hazards, nav, following) },
    )
}

/** Everything the map needs to draw, so one update call carries one picture. */
private data class Frame(
    val routes: List<ScoredRoute>,
    val highlighted: ScoredRoute?,
    val hazards: List<RouteHazard>,
    val nav: NavState?,
    val following: Boolean,
)

/**
 * Keeps hold of the map between recompositions.
 *
 * The style loads asynchronously, and Compose will have called update several
 * times before it is ready, so the latest frame is stashed and drawn once there
 * is somewhere to draw it.
 */
private class MapHolder {
    private var view: MapView? = null
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var density: Float = 2.0f
    private var pending: Frame? = null
    private var framedFor: String? = null
    private var wasDriving = false

    fun attach(mapView: MapView, screenDensity: Float) {
        view = mapView
        density = screenDensity
    }

    fun onStyleReady(ready: MapLibreMap, loaded: Style) {
        map = ready
        style = loaded

        loaded.addImage(MapIcons.CAMERA, MapIcons.camera(density))
        loaded.addImage(MapIcons.PUCK, MapIcons.puck(density))
        loaded.addImage(MapIcons.ARROW, MapIcons.arrow(density))
        loaded.addImage(MapIcons.PIN_START, MapIcons.pin(density, Palette.textDim.toArgb()))
        loaded.addImage(MapIcons.PIN_END, MapIcons.pin(density, Palette.accent.toArgb()))

        loaded.addSource(GeoJsonSource(SOURCE_DIM, emptyCollection()))
        loaded.addSource(GeoJsonSource(SOURCE_ACTIVE, emptyCollection()))
        loaded.addSource(GeoJsonSource(SOURCE_ENDS, emptyCollection()))
        loaded.addSource(GeoJsonSource(SOURCE_CAMERAS, emptyCollection()))
        loaded.addSource(GeoJsonSource(SOURCE_CAR, emptyCollection()))
        loaded.addSource(GeoJsonSource(SOURCE_DONE, emptyCollection()))

        // The routes not chosen get a casing too, or they disappear against a
        // dark basemap and the comparison the app is making goes with them.
        loaded.addLayer(
            LineLayer(LAYER_DIM_CASING, SOURCE_DIM).withProperties(
                PropertyFactory.lineColor(Palette.background.toArgb()),
                PropertyFactory.lineWidth(7.0f),
                PropertyFactory.lineOpacity(0.7f),
                PropertyFactory.lineCap("round"),
                PropertyFactory.lineJoin("round"),
            ),
        )
        loaded.addLayer(
            LineLayer(LAYER_DIM, SOURCE_DIM).withProperties(
                PropertyFactory.lineColor(Palette.panelEdge.toArgb()),
                PropertyFactory.lineWidth(3.0f),
                PropertyFactory.lineOpacity(0.95f),
                PropertyFactory.lineCap("round"),
                PropertyFactory.lineJoin("round"),
            ),
        )
        // The road already driven. Kept on the map rather than erased, because
        // it is the answer to "did I come past that junction", but drained of
        // colour so the eye goes to the part that is still to come.
        loaded.addLayer(
            LineLayer(LAYER_DONE, SOURCE_DONE).withProperties(
                PropertyFactory.lineColor(Palette.panelEdge.toArgb()),
                PropertyFactory.lineWidth(zoomWidth(5.5f, 10.0f)),
                PropertyFactory.lineOpacity(0.55f),
                PropertyFactory.lineCap("round"),
                PropertyFactory.lineJoin("round"),
            ),
        )
        // A dark casing under the active route, so a green line stays readable
        // where it crosses a pale patch of basemap.
        loaded.addLayer(
            LineLayer(LAYER_ACTIVE_CASING, SOURCE_ACTIVE).withProperties(
                PropertyFactory.lineColor(Palette.background.toArgb()),
                PropertyFactory.lineWidth(zoomWidth(9.0f, 15.0f)),
                PropertyFactory.lineCap("round"),
                PropertyFactory.lineJoin("round"),
            ),
        )
        loaded.addLayer(
            LineLayer(LAYER_ACTIVE, SOURCE_ACTIVE).withProperties(
                PropertyFactory.lineColor(bandExpression()),
                PropertyFactory.lineWidth(zoomWidth(5.5f, 10.0f)),
                PropertyFactory.lineCap("round"),
                PropertyFactory.lineJoin("round"),
            ),
        )
        // Direction of travel, repeated along the line. Only appears once
        // zoomed in far enough for the line to be wide enough to carry it.
        loaded.addLayer(
            SymbolLayer(LAYER_ACTIVE_ARROWS, SOURCE_ACTIVE).withProperties(
                PropertyFactory.iconImage(MapIcons.ARROW),
                PropertyFactory.symbolPlacement("line"),
                PropertyFactory.symbolSpacing(70.0f),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
                PropertyFactory.iconRotationAlignment("map"),
                PropertyFactory.iconOpacity(
                    Expression.interpolate(
                        Expression.linear(),
                        Expression.zoom(),
                        Expression.stop(10.5, 0.0f),
                        Expression.stop(12.0, 0.9f),
                    ),
                ),
            ),
        )
        loaded.addLayer(
            SymbolLayer(LAYER_ENDS, SOURCE_ENDS).withProperties(
                PropertyFactory.iconImage(Expression.get(PROPERTY_ICON)),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
            ),
        )
        loaded.addLayer(
            SymbolLayer(LAYER_CAMERAS, SOURCE_CAMERAS).withProperties(
                PropertyFactory.iconImage(MapIcons.CAMERA),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
                // Hidden when zoomed out: a hundred camera discs over a whole
                // emirate is noise, not information.
                PropertyFactory.iconOpacity(
                    Expression.interpolate(
                        Expression.linear(),
                        Expression.zoom(),
                        Expression.stop(9.0, 0.0f),
                        Expression.stop(11.0, 1.0f),
                    ),
                ),
            ),
        )
        loaded.addLayer(
            SymbolLayer(LAYER_CAR, SOURCE_CAR).withProperties(
                PropertyFactory.iconImage(MapIcons.PUCK),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
                PropertyFactory.iconRotate(Expression.get(PROPERTY_BEARING)),
                PropertyFactory.iconRotationAlignment("map"),
            ),
        )

        pending?.let(::draw)
    }

    fun render(
        routes: List<ScoredRoute>,
        highlighted: ScoredRoute?,
        hazards: List<RouteHazard>,
        nav: NavState?,
        following: Boolean,
    ) {
        val frame = Frame(routes, highlighted, hazards, nav, following)
        pending = frame
        if (style != null) draw(frame)
    }

    private fun draw(frame: Frame) {
        val loaded = style ?: return
        val dim = frame.routes.filter { it.id != frame.highlighted?.id }

        loaded.source(SOURCE_DIM)?.setGeoJson(plainCollection(dim.map { it.candidate }))
        // While driving, the line is split at the car: colour ahead, grey
        // behind. When planning there is no car, so the whole route is ahead.
        val travelled = frame.nav?.travelledMeters ?: 0.0
        loaded.source(SOURCE_ACTIVE)
            ?.setGeoJson(bandedCollection(frame.highlighted?.candidate, fromMeters = travelled))
        loaded.source(SOURCE_DONE)?.setGeoJson(
            if (frame.nav == null) {
                emptyCollection()
            } else {
                drivenCollection(frame.highlighted?.candidate, travelled)
            },
        )
        loaded.source(SOURCE_ENDS)?.setGeoJson(endsCollection(frame.highlighted?.candidate))

        // Cameras only while driving. On the planning screen they would crowd
        // out the one thing that screen is for, which is comparing four lines.
        val driving = frame.nav != null
        loaded.source(SOURCE_CAMERAS)?.setGeoJson(
            if (driving) cameraCollection(frame.hazards) else emptyCollection(),
        )
        loaded.source(SOURCE_CAR)?.setGeoJson(carCollection(frame.nav))

        when {
            driving && frame.following -> followCar(frame.nav!!)
            !driving -> frameAll(frame.routes)
        }
        wasDriving = driving
    }

    /**
     * Puts the car near the bottom of the screen looking up the road.
     *
     * The heading is only used when the fix has one. A stationary car reports no
     * course, and taking zero for it would swing the map to face north every
     * time the traffic stopped.
     */
    private fun followCar(nav: NavState) {
        val map = map ?: return
        val heading = nav.fix.headingDeg ?: map.cameraPosition.bearing
        val position = CameraPosition.Builder()
            .target(org.maplibre.android.geometry.LatLng(nav.snapped.lat, nav.snapped.lon))
            .bearing(heading)
            .tilt(DRIVE_TILT)
            .zoom(DRIVE_ZOOM)
            .build()

        // A short animation rather than a jump, so the map reads as moving with
        // the car instead of being redrawn once a second.
        if (wasDriving) {
            map.easeCamera(CameraUpdateFactory.newCameraPosition(position), TICK_MS)
        } else {
            map.animateCamera(CameraUpdateFactory.newCameraPosition(position), 600)
        }
    }

    /**
     * Frames the whole set once per origin and destination pair. Re-framing on
     * every tier change would yank the map about while the driver is trying to
     * compare two lines on it.
     */
    private fun frameAll(routes: List<ScoredRoute>) {
        if (routes.isEmpty()) return
        val key = routes.joinToString("|") { it.id }
        // Coming back from the driving screen has to re-frame even though the
        // routes have not changed, because the camera was left tilted and
        // locked to a car that is no longer being followed.
        if (key == framedFor && !wasDriving) return
        framedFor = key

        val map = map ?: return
        val points = routes.flatMap { it.candidate.geometry }
        val (southWest, northEast) = points.bounds(padMeters = 1_500.0) ?: return
        if (southWest.lat >= northEast.lat || southWest.lon >= northEast.lon) return

        val bounds = LatLngBounds.Builder()
            .include(org.maplibre.android.geometry.LatLng(southWest.lat, southWest.lon))
            .include(org.maplibre.android.geometry.LatLng(northEast.lat, northEast.lon))
            .build()

        // Worked out as one camera position rather than issued as three
        // animations. Three would each cancel the one before it, and the map
        // would arrive framed but still tilted from the drive.
        val framed = map.getCameraForLatLngBounds(bounds, intArrayOf(48, 48, 48, 48)) ?: return
        val flat = CameraPosition.Builder(framed)
            .bearing(0.0)
            .tilt(0.0)
            .build()
        map.animateCamera(CameraUpdateFactory.newCameraPosition(flat), 500)
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

    private fun Style.source(id: String): GeoJsonSource? = getSource(id) as? GeoJsonSource

    private companion object {
        /**
         * Enough to see the shape of the junction coming up without the screen
         * becoming a street plan.
         */
        const val DRIVE_ZOOM = 15.5
        const val DRIVE_TILT = 48.0
        const val TICK_MS = 900
    }
}

/** A line that thickens as you zoom in, so it stays a road rather than a hair. */
private fun zoomWidth(far: Float, near: Float): Expression = Expression.interpolate(
    Expression.linear(),
    Expression.zoom(),
    Expression.stop(9.0, far),
    Expression.stop(16.0, near),
)

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
private fun bandedCollection(route: RouteCandidate?, fromMeters: Double = 0.0): String {
    if (route == null || route.segments.isEmpty()) return emptyCollection()

    val ahead = segmentsAfter(route, fromMeters)
    if (ahead.isEmpty()) return emptyCollection()

    val features = mutableListOf<String>()
    var run = mutableListOf(ahead.first().start)
    var band = Palette.bandOf(ahead.first().congestion)

    for (segment in ahead) {
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

/**
 * The part of the route already behind the car, as one plain line.
 *
 * It carries no congestion banding: what the traffic was doing on a road that
 * has been driven is not information, it is history.
 */
private fun drivenCollection(route: RouteCandidate?, toMeters: Double): String {
    if (route == null || route.segments.isEmpty() || toMeters <= 0.0) return emptyCollection()

    val points = mutableListOf(route.segments.first().start)
    var walked = 0.0
    for (segment in route.segments) {
        val end = walked + segment.meters
        if (end <= toMeters) {
            points += segment.end
            walked = end
            continue
        }
        // The segment the car is on: stop at the car rather than at its end.
        if (walked < toMeters && segment.meters > 0.0) {
            points += interpolate(segment.start, segment.end, (toMeters - walked) / segment.meters)
        }
        break
    }
    if (points.size < 2) return emptyCollection()
    return """{"type":"FeatureCollection","features":[{"type":"Feature","properties":{},"geometry":${
        lineString(points)
    }}]}"""
}

/**
 * Segments from [fromMeters] onward, with the one the car is standing on
 * clipped so the coloured line starts at the car and not behind it.
 */
private fun segmentsAfter(
    route: RouteCandidate,
    fromMeters: Double,
): List<ae.clearlane.core.model.RouteSegment> {
    if (fromMeters <= 0.0) return route.segments
    val out = mutableListOf<ae.clearlane.core.model.RouteSegment>()
    var walked = 0.0
    for (segment in route.segments) {
        val end = walked + segment.meters
        when {
            end <= fromMeters -> Unit
            walked >= fromMeters -> out += segment
            segment.meters > 0.0 -> {
                val into = (fromMeters - walked) / segment.meters
                out += segment.copy(
                    start = interpolate(segment.start, segment.end, into),
                    meters = segment.meters * (1.0 - into),
                )
            }
        }
        walked = end
    }
    return out
}

private fun interpolate(a: LatLng, b: LatLng, t: Double): LatLng {
    val f = t.coerceIn(0.0, 1.0)
    return LatLng(a.lat + (b.lat - a.lat) * f, a.lon + (b.lon - a.lon) * f)
}

private fun endsCollection(route: RouteCandidate?): String {
    if (route == null || route.geometry.size < 2) return emptyCollection()
    val start = point(route.from, """"$PROPERTY_ICON":"${MapIcons.PIN_START}"""")
    val end = point(route.to, """"$PROPERTY_ICON":"${MapIcons.PIN_END}"""")
    return """{"type":"FeatureCollection","features":[$start,$end]}"""
}

private fun cameraCollection(hazards: List<RouteHazard>): String {
    if (hazards.isEmpty()) return emptyCollection()
    val features = hazards.joinToString(",") { point(it.hazard.at, null) }
    return """{"type":"FeatureCollection","features":[$features]}"""
}

private fun carCollection(nav: NavState?): String {
    if (nav == null) return emptyCollection()
    val bearing = nav.fix.headingDeg ?: 0.0
    return """{"type":"FeatureCollection","features":[${
        point(nav.snapped, """"$PROPERTY_BEARING":${format(bearing)}""")
    }]}"""
}

private fun point(at: LatLng, properties: String?): String =
    """{"type":"Feature","properties":{${properties.orEmpty()}},"geometry":{"type":"Point","coordinates":[${
        format(at.lon)
    },${format(at.lat)}]}}"""

private fun lineString(points: List<LatLng>): String {
    val coordinates = points.joinToString(",") { p ->
        "[${format(p.lon)},${format(p.lat)}]"
    }
    return """{"type":"LineString","coordinates":[$coordinates]}"""
}

/** Six decimal places is about 10 cm, and always a dot regardless of locale. */
private fun format(value: Double): String = String.format(Locale.US, "%.6f", value)
