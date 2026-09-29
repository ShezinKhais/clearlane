package ae.clearlane.app.ui

import ae.clearlane.core.model.RouteCandidate
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The whole drive as one bar: distance left to right, congestion as colour and
 * as height.
 *
 * This is the thing a list of numbers cannot say. Two routes can both be "a
 * third congested" while one of them is a steady crawl and the other is twenty
 * clear kilometres with a four kilometre car park in the middle, and those are
 * completely different evenings. Seen as a strip, the difference is obvious
 * before you have read anything.
 *
 * Height doubles up the colour on purpose, so the chart still works for a
 * driver who cannot separate the red from the green.
 */
@Composable
fun CongestionStrip(
    route: RouteCandidate,
    modifier: Modifier = Modifier,
    height: Dp = 22.dp,
    /**
     * Where the car is along the route, 0 to 1, or null when planning rather
     * than driving. While driving, the part already covered is dimmed and the
     * position marked, which turns the chart from a description of the route
     * into a picture of what is left of it.
     */
    progress: Float? = null,
) {
    Box(modifier.fillMaxWidth().height(height)) {
        Canvas(Modifier.fillMaxWidth().height(height)) {
            drawStrip(route, progress)
        }
    }
}

private fun DrawScope.drawStrip(route: RouteCandidate, progress: Float?) {
    val total = route.segments.sumOf { it.meters }
    if (total <= 0.0) return

    val w = size.width
    val h = size.height
    val baseline = h * 0.30f

    // A faint floor, so a completely clear route still reads as a route rather
    // than as an empty box.
    drawRect(
        color = Palette.line,
        topLeft = Offset(0f, h - baseline * 0.5f),
        size = Size(w, baseline * 0.5f),
    )

    var x = 0f
    for (segment in route.segments) {
        val width = (segment.meters / total * w).toFloat()
        if (width <= 0f) continue
        val bar = baseline + (h - baseline) * segment.congestion.toFloat()
        drawRect(
            color = Palette.forCongestion(segment.congestion),
            topLeft = Offset(x, h - bar),
            // A hair of overlap, because hundreds of sub pixel wide rectangles
            // laid edge to edge leave visible seams.
            size = Size(width + 0.6f, bar),
        )
        x += width
    }

    val at = progress?.coerceIn(0f, 1f) ?: return

    // Behind the car: still readable, but plainly the past.
    drawRect(
        color = Palette.background.copy(alpha = 0.62f),
        topLeft = Offset(0f, 0f),
        size = Size(w * at, h),
    )

    // The car itself. Full height so it is found instantly, and drawn last so
    // nothing covers it.
    val marker = w * at
    drawRect(
        color = Palette.text,
        topLeft = Offset((marker - 1.2f).coerceIn(0f, w - 2.4f), 0f),
        size = Size(2.4f, h),
    )
}
