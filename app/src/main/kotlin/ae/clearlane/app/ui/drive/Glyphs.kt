package ae.clearlane.app.ui.drive

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp

/**
 * The handful of marks this app needs, drawn rather than imported.
 *
 * Pulling in the extended icon set for four glyphs costs more in the APK than
 * the whole rest of the UI, and a material icon drawn at the size of a warning
 * card looks like a setting rather than a road sign. These are built to read at
 * a glance at arm's length, which is the only size that matters here.
 */
object Glyphs {

    /** An enforcement camera: a box on a pole, lens forward. */
    fun DrawScope.camera(color: Color) {
        val w = size.width
        val h = size.height
        val body = Size(w * 0.68f, h * 0.46f)
        val top = h * 0.16f

        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.06f, top),
            size = body,
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.08f),
        )
        // The lens, reading forward and to the right.
        drawCircle(
            color = color,
            radius = w * 0.15f,
            center = Offset(w * 0.82f, top + body.height * 0.5f),
        )
        // The pole.
        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.34f, top + body.height),
            size = Size(w * 0.1f, h * 0.36f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.05f),
        )
    }

    /** A solid chevron, pointing the way a turn goes. */
    fun DrawScope.chevron(color: Color, pointingLeft: Boolean = false) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            if (pointingLeft) {
                moveTo(w * 0.68f, h * 0.12f)
                lineTo(w * 0.28f, h * 0.5f)
                lineTo(w * 0.68f, h * 0.88f)
            } else {
                moveTo(w * 0.32f, h * 0.12f)
                lineTo(w * 0.72f, h * 0.5f)
                lineTo(w * 0.32f, h * 0.88f)
            }
        }
        drawPath(path, color, style = Stroke(width = w * 0.16f))
    }

    /** Straight on. */
    fun DrawScope.straightOn(color: Color) {
        val w = size.width
        val h = size.height
        drawLine(
            color = color,
            start = Offset(w * 0.5f, h * 0.9f),
            end = Offset(w * 0.5f, h * 0.22f),
            strokeWidth = w * 0.14f,
        )
        val head = Path().apply {
            moveTo(w * 0.5f, h * 0.08f)
            lineTo(w * 0.76f, h * 0.38f)
            lineTo(w * 0.24f, h * 0.38f)
            close()
        }
        drawPath(head, color)
    }

    /** A roundabout: a ring with a road running into it. */
    fun DrawScope.roundabout(color: Color) {
        val w = size.width
        val h = size.height
        drawCircle(
            color = color,
            radius = w * 0.24f,
            center = Offset(w * 0.5f, h * 0.42f),
            style = Stroke(width = w * 0.12f),
        )
        drawLine(
            color = color,
            start = Offset(w * 0.5f, h * 0.92f),
            end = Offset(w * 0.5f, h * 0.66f),
            strokeWidth = w * 0.12f,
        )
    }

    /** Traffic signals: a stack of three lamps. */
    fun DrawScope.signal(color: Color) {
        val w = size.width
        val h = size.height
        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.28f, h * 0.08f),
            size = Size(w * 0.44f, h * 0.7f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.12f),
            style = Stroke(width = w * 0.1f),
        )
        for (i in 0..2) {
            drawCircle(
                color = color,
                radius = w * 0.07f,
                center = Offset(w * 0.5f, h * (0.22f + i * 0.2f)),
            )
        }
    }

    /** Recentre: a crosshair around a dot. */
    fun DrawScope.recentre(color: Color) {
        val w = size.width
        val h = size.height
        val c = Offset(w * 0.5f, h * 0.5f)
        drawCircle(color, radius = w * 0.1f, center = c)
        drawCircle(color, radius = w * 0.28f, center = c, style = Stroke(width = w * 0.08f))
        for (angle in listOf(0f, 90f, 180f, 270f)) {
            val radians = Math.toRadians(angle.toDouble())
            val dx = kotlin.math.cos(radians).toFloat()
            val dy = kotlin.math.sin(radians).toFloat()
            drawLine(
                color = color,
                start = Offset(c.x + dx * w * 0.34f, c.y + dy * h * 0.34f),
                end = Offset(c.x + dx * w * 0.46f, c.y + dy * h * 0.46f),
                strokeWidth = w * 0.08f,
            )
        }
    }
}

/** Puts one of the [Glyphs] marks in a square box of [size]. */
@Composable
fun Glyph(size: Dp, modifier: Modifier = Modifier, draw: DrawScope.() -> Unit) {
    Canvas(modifier.size(size)) { draw() }
}
