package ae.clearlane.app.ui.drive

import ae.clearlane.app.ui.Palette
import ae.clearlane.app.ui.Type
import ae.clearlane.core.model.HazardKind
import ae.clearlane.core.model.Maneuver
import ae.clearlane.core.model.ManeuverKind
import ae.clearlane.core.nav.HazardWarning
import ae.clearlane.app.ui.drive.Glyphs.camera
import ae.clearlane.app.ui.drive.Glyphs.chevron
import ae.clearlane.app.ui.drive.Glyphs.roundabout
import ae.clearlane.app.ui.drive.Glyphs.signal
import ae.clearlane.app.ui.drive.Glyphs.straightOn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * A camera coming up.
 *
 * Deliberately quiet. Every driver has used an app that shouts, and a warning
 * that shouts a hundred times a journey stops being a warning. This states the
 * thing and the distance, carries the posted limit where the camera records
 * one, and gets out of the way.
 *
 * The distance counts down, which is the part that is actually useful: knowing
 * a camera exists is worth less than knowing it is four hundred metres off.
 */
@Composable
fun CameraWarning(warning: HazardWarning, modifier: Modifier = Modifier) {
    val hazard = warning.hazard.hazard
    val label = when (hazard.kind) {
        HazardKind.FIXED_CAMERA -> "Speed camera"
        HazardKind.AVERAGE_SPEED -> "Average speed check"
        HazardKind.RED_LIGHT_CAMERA -> "Red light camera"
    }

    Row(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Palette.panel.copy(alpha = 0.94f))
            .border(1.dp, Palette.panelEdge, RoundedCornerShape(16.dp))
            .padding(start = 12.dp, end = 14.dp, top = 10.dp, bottom = 10.dp)
            .semantics {
                contentDescription = "$label in ${distanceWords(warning.meters)}"
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(26.dp) { camera(Palette.text) }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(label, style = Type.bodyStrong, color = Palette.text)
            Text(distanceWords(warning.meters), style = Type.caption, color = Palette.textDim)
        }
        if (hazard.speedLimitKph != null) {
            Spacer(Modifier.width(12.dp))
            SpeedLimitSign(hazard.speedLimitKph, size = 42.dp)
        }
    }
}

/**
 * The next thing the driver has to do.
 *
 * Only shown when the provider gave a manoeuvre worth showing. The fixtures do
 * not, so on demo data this stays empty rather than inventing a turn.
 */
@Composable
fun ManeuverCard(maneuver: Maneuver, meters: Double, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Palette.panel.copy(alpha = 0.94f))
            .border(1.dp, Palette.panelEdge, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(34.dp) {
            when (maneuver.kind) {
                ManeuverKind.ROUNDABOUT -> roundabout(Palette.accent)
                ManeuverKind.SIGNAL -> signal(Palette.accent)
                ManeuverKind.TURN -> chevron(Palette.accent)
                ManeuverKind.MERGE, ManeuverKind.FORK, ManeuverKind.EXIT ->
                    chevron(Palette.accent)
                ManeuverKind.CONTINUE, ManeuverKind.ARRIVE -> straightOn(Palette.accent)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(distanceWords(meters), style = Type.callout, color = Palette.text)
            if (maneuver.instruction.isNotBlank()) {
                Text(
                    maneuver.instruction,
                    style = Type.caption,
                    color = Palette.textDim,
                    maxLines = 2,
                )
            }
        }
    }
}

/**
 * The traffic the driver is about to reach.
 *
 * This is the app's whole argument made small: the point of the thing is to
 * know what the road ahead is doing, so on the driving screen that is stated
 * outright rather than left to be read off the colour of a line.
 */
@Composable
fun RoadAhead(
    congestion: Double,
    jamAheadMeters: Double?,
    modifier: Modifier = Modifier,
) {
    val colour = Palette.forCongestion(congestion)
    val words = when {
        jamAheadMeters != null -> "Slow traffic in ${distanceWords(jamAheadMeters)}"
        congestion < 0.12 -> "Clear for the next few kilometres"
        congestion < 0.30 -> "Moving well ahead"
        congestion < 0.50 -> "Busy ahead"
        else -> "Heavy ahead"
    }

    Row(
        modifier.semantics { contentDescription = words },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(colour),
        )
        Spacer(Modifier.width(8.dp))
        Text(words, style = Type.caption, color = Palette.textDim)
    }
}

/** Shown when the car is nowhere near the route it was given. */
@Composable
fun OffRouteBanner(offMeters: Double, onReplan: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Palette.panel.copy(alpha = 0.94f))
            .border(1.dp, Palette.heavy.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Off the route", style = Type.bodyStrong, color = Palette.text)
        Text(
            "You are ${distanceWords(offMeters)} from the road this plan follows.",
            style = Type.caption,
            color = Palette.textDim,
        )
        PillButton("Plan again from here", Palette.accent, onReplan)
    }
}

/**
 * A plain, large enough tap target.
 *
 * [labelColour] defaults to the dark background, which is right on the accent
 * fill and wrong on a dark one, so a quiet button has to say what its label
 * should be.
 */
@Composable
fun PillButton(
    label: String,
    colour: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    labelColour: Color = Palette.background,
) {
    Text(
        text = label,
        style = Type.bodyStrong,
        color = labelColour,
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(colour)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp),
    )
}

/**
 * Distances the way a driver says them: metres rounded to something readable
 * up close, kilometres once the exact figure has stopped mattering.
 */
fun distanceWords(meters: Double): String = when {
    meters < 20 -> "now"
    meters < 1_000 -> "${(meters / 10.0).roundToInt() * 10} m"
    meters < 10_000 -> "%.1f km".format(meters / 1_000.0)
    else -> "${(meters / 1_000.0).roundToInt()} km"
}
