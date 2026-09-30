package ae.clearlane.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * The axis the congestion strips are drawn against.
 *
 * Drawn once above the set rather than once per card. A shared scale is only
 * worth having if the reader can tell there is one, and repeating the axis on
 * every route would say the opposite: that each card measures itself.
 */
@Composable
fun DistanceAxis(maxMeters: Double, modifier: Modifier = Modifier) {
    if (maxMeters <= 0.0) return
    val far = (maxMeters / 1_000.0).roundToInt()
    val mid = far / 2

    Column(modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxWidth().height(5.dp)) {
            val w = size.width
            val h = size.height
            // Ticks hang down from the rule at the quarters, so the eye can
            // halve a strip without a ruler.
            drawRect(Palette.line, Offset(0f, 0f), Size(w, 1f))
            for (fraction in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
                val x = (w - 1f) * fraction
                drawRect(Palette.line, Offset(x, 0f), Size(1f, h))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("0", color = Palette.textDim, style = Type.caption)
            // Only stated when halving the run gives a distinct figure. On a
            // short set "0, 1, 2 km" is three labels saying one thing.
            Text(if (mid > 0 && mid != far) "$mid km" else "", color = Palette.textDim, style = Type.caption)
            Text("$far km", color = Palette.textDim, style = Type.caption)
        }
    }
}
