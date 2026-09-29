package ae.clearlane.app.ui.drive

import ae.clearlane.app.ui.Palette
import ae.clearlane.app.ui.Type
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * How fast the car is going, in the largest type on the screen.
 *
 * The number turns amber when it is over the posted limit rather than the whole
 * panel flashing, because a driving screen that changes shape in the corner of
 * the eye is a driving screen that pulls the eye. There is a tolerance on the
 * comparison, so holding the limit does not make it flicker.
 *
 * With no fix yet the panel shows dashes rather than a zero. A zero is a
 * reading; dashes are an admission that there is not one.
 */
@Composable
fun Speedometer(
    speedKph: Double?,
    overLimit: Boolean,
    modifier: Modifier = Modifier,
) {
    val colour by animateColorAsState(
        targetValue = if (overLimit) Palette.light else Palette.text,
        animationSpec = tween(220),
        label = "speed-colour",
    )

    Column(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Palette.panel.copy(alpha = 0.92f))
            .border(1.dp, Palette.panelEdge, RoundedCornerShape(18.dp))
            .padding(horizontal = 18.dp, vertical = 10.dp)
            .semantics {
                contentDescription = speedKph
                    ?.let { "Travelling at ${it.roundToInt()} kilometres per hour" }
                    ?: "Waiting for a position"
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = speedKph?.roundToInt()?.toString() ?: "--",
            style = Type.reading,
            color = colour,
        )
        Text("km/h", style = Type.unit, color = Palette.textDim)
    }
}

/**
 * The posted limit, drawn as the sign it is.
 *
 * A circle with a heavy red ring is a shape drivers already read without
 * thinking, so it is worth copying exactly rather than restyling into something
 * that matches the rest of the app. Shown only when the route actually carries
 * a limit: an invented number here is the one thing on the screen that could
 * cost somebody a fine.
 */
@Composable
fun SpeedLimitSign(
    limitKph: Int?,
    modifier: Modifier = Modifier,
    size: Dp = 62.dp,
) {
    if (limitKph == null) return

    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(Palette.signFace)
            .border(width = size * 0.13f, color = Palette.signRed, shape = CircleShape)
            .semantics { contentDescription = "Speed limit $limitKph" },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = limitKph.toString(),
            style = Type.signNumber(if (limitKph >= 100) 20 else 23),
        )
    }
}

/**
 * A thin bar showing how far through the drive the car is.
 *
 * It fills by time rather than distance, which on a route with a jam in it are
 * two very different numbers, and the arrival estimate above it is a time.
 */
@Composable
fun DriveProgress(fraction: Float, modifier: Modifier = Modifier) {
    val filled by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = tween(600),
        label = "drive-progress",
    )
    Box(
        modifier
            .clip(RoundedCornerShape(2.dp))
            .background(Palette.line),
    ) {
        if (filled > 0f) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(filled)
                    .background(Palette.accent),
            )
        }
    }
}
