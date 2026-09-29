package ae.clearlane.app.map

import ae.clearlane.app.ui.Palette
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.compose.ui.graphics.toArgb

/**
 * The marks that go on the map itself, built as bitmaps at runtime.
 *
 * A MapLibre symbol layer wants a bitmap registered with the style, and drawing
 * these here rather than shipping PNGs means one definition per mark instead of
 * five density buckets each, and the same palette the rest of the app uses
 * rather than a colour baked into a file.
 *
 * Everything is sized from [density] so the marks are the same physical size on
 * a phone and on a tablet.
 */
object MapIcons {

    const val CAMERA = "clearlane-camera"
    const val PUCK = "clearlane-puck"
    const val ARROW = "clearlane-arrow"
    const val PIN_START = "clearlane-pin-start"
    const val PIN_END = "clearlane-pin-end"

    /**
     * An enforcement camera: a dark disc with a light camera on it.
     *
     * A disc rather than a pin, because a pin's point claims a precision the
     * data does not have. The camera is somewhere within a few metres of that
     * spot, not at its tip.
     */
    fun camera(density: Float): Bitmap {
        val size = (26 * density).toInt().coerceAtLeast(20)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val s = size.toFloat()
        val fill = paint(Palette.panel.toArgb())
        val ring = paint(Palette.signFace.toArgb()).apply {
            style = Paint.Style.STROKE
            strokeWidth = s * 0.08f
        }
        val ink = paint(Palette.signFace.toArgb())

        canvas.drawCircle(s / 2f, s / 2f, s * 0.44f, fill)
        canvas.drawCircle(s / 2f, s / 2f, s * 0.44f, ring)

        // Body and lens, small enough to stay legible at the size a map draws it.
        canvas.drawRoundRect(
            RectF(s * 0.24f, s * 0.38f, s * 0.60f, s * 0.62f),
            s * 0.04f,
            s * 0.04f,
            ink,
        )
        canvas.drawCircle(s * 0.68f, s * 0.50f, s * 0.10f, ink)
        canvas.drawRect(RectF(s * 0.30f, s * 0.30f, s * 0.44f, s * 0.38f), ink)
        return bitmap
    }

    /**
     * The car: a chevron rather than a dot, because the direction it points is
     * half the information. Rotated by the layer, so this always points up.
     */
    fun puck(density: Float): Bitmap {
        val size = (34 * density).toInt().coerceAtLeast(24)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val s = size.toFloat()

        // A dark disc under it, so the chevron holds up over a pale basemap.
        canvas.drawCircle(s / 2f, s / 2f, s * 0.46f, paint(Palette.background.toArgb()))
        canvas.drawCircle(
            s / 2f,
            s / 2f,
            s * 0.46f,
            paint(Palette.accent.toArgb()).apply {
                style = Paint.Style.STROKE
                strokeWidth = s * 0.06f
            },
        )

        val chevron = Path().apply {
            moveTo(s * 0.5f, s * 0.20f)
            lineTo(s * 0.76f, s * 0.76f)
            lineTo(s * 0.5f, s * 0.62f)
            lineTo(s * 0.24f, s * 0.76f)
            close()
        }
        canvas.drawPath(chevron, paint(Palette.accent.toArgb()))
        return bitmap
    }

    /**
     * A small chevron repeated along the active route to show its direction.
     *
     * Drawn pointing right, not up. A symbol on a line placement is rotated to
     * the bearing of the line with zero meaning along it, so an arrow drawn
     * pointing up comes out ninety degrees off and reads as pointing back the
     * way the driver came.
     */
    fun arrow(density: Float): Bitmap {
        val size = (14 * density).toInt().coerceAtLeast(10)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val s = size.toFloat()
        val stroke = paint(Palette.background.toArgb()).apply {
            style = Paint.Style.STROKE
            strokeWidth = s * 0.16f
            strokeCap = Paint.Cap.ROUND
        }
        val path = Path().apply {
            moveTo(s * 0.38f, s * 0.28f)
            lineTo(s * 0.64f, s * 0.5f)
            lineTo(s * 0.38f, s * 0.72f)
        }
        canvas.drawPath(path, stroke)
        return bitmap
    }

    /** Start and end of the trip. Filled discs with a ring, no pin point. */
    fun pin(density: Float, fillArgb: Int): Bitmap {
        val size = (18 * density).toInt().coerceAtLeast(14)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val s = size.toFloat()
        canvas.drawCircle(s / 2f, s / 2f, s * 0.42f, paint(Palette.background.toArgb()))
        canvas.drawCircle(s / 2f, s / 2f, s * 0.30f, paint(fillArgb))
        return bitmap
    }

    private fun paint(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.FILL
    }
}
