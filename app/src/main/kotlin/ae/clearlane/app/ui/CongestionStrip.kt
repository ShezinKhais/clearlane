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
) {
    Box(modifier.fillMaxWidth().height(height)) {
        Canvas(Modifier.fillMaxWidth().height(height)) {
            drawStrip(route)
        }
    }
}

private fun DrawScope.drawStrip(route: RouteCandidate) {
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
}
