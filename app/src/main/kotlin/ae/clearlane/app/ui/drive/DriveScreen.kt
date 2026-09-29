package ae.clearlane.app.ui.drive

import ae.clearlane.app.PositionSource
import ae.clearlane.app.RouteUiState
import ae.clearlane.app.RouteViewModel
import ae.clearlane.app.map.RouteMap
import ae.clearlane.app.ui.CongestionStrip
import ae.clearlane.app.ui.Palette
import ae.clearlane.app.ui.Type
import ae.clearlane.app.ui.drive.Glyphs.recentre
import ae.clearlane.core.nav.minutesLeft
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * The driving screen.
 *
 * Everything on it is placed by how urgent it is. What the driver must not miss
 * is at the bottom near the wheel and in the largest type: the speed, the limit,
 * and how long is left. What is useful but can wait is at the top: the next
 * manoeuvre, and a camera when there is one. Everything else is on the other
 * screen.
 *
 * The map fills the frame and the panels float over it, so the road is never
 * squeezed into a letterbox by the interface describing it.
 */
@Composable
fun DriveScreen(state: RouteUiState, model: RouteViewModel) {
    val nav = state.nav
    var following by remember { mutableStateOf(true) }

    Box(Modifier.fillMaxSize().background(Palette.background)) {
        RouteMap(
            routes = state.outcome?.plan?.considered.orEmpty(),
            highlighted = state.highlighted,
            onTap = { },
            onLongPress = { },
            hazards = state.hazardsOnRoute,
            nav = nav,
            following = following,
            onUserPan = { following = false },
            modifier = Modifier.fillMaxSize(),
        )

        Column(
            Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (nav != null && !nav.onRoute) {
                OffRouteBanner(nav.offRouteMeters, onReplan = model::replanFromHere)
            }

            // Only once it is close enough to be the next thing that happens.
            // A card announcing a set of lights twenty kilometres away is not a
            // driving instruction, it is a fact about the route.
            val maneuver = nav?.nextManeuver
            val maneuverMeters = nav?.maneuverMeters
            if (maneuver != null && maneuverMeters != null &&
                maneuverMeters <= MANEUVER_VISIBLE_METERS
            ) {
                ManeuverCard(maneuver, maneuverMeters, Modifier.fillMaxWidth())
            }

            // Cameras slide in from the top edge rather than appearing, which is
            // what tells a driver something is new without them having to read
            // it to find out.
            AnimatedVisibility(
                visible = nav?.warning != null,
                enter = slideInVertically { -it / 2 } + fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.End),
            ) {
                nav?.warning?.let { CameraWarning(it) }
            }
        }

        Column(
            Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
            ) {
                Speedometer(
                    speedKph = nav?.fix?.speedKph,
                    overLimit = nav?.overLimit == true,
                )
                Spacer(Modifier.width(10.dp))
                SpeedLimitSign(nav?.speedLimitKph)
                Spacer(Modifier.weight(1f))
                if (!following) {
                    RoundButton(onClick = { following = true }) { recentre(Palette.text) }
                }
            }

            ArrivalPanel(state, model)
        }
    }
}

/**
 * When the driver arrives, how long that is, and what the rest of the road
 * looks like.
 *
 * The arrival time is a clock time rather than only a countdown, because
 * "18:42" is the number someone reads out to whoever is waiting, and the
 * countdown beside it is the one they watch.
 */
@Composable
private fun ArrivalPanel(state: RouteUiState, model: RouteViewModel) {
    val nav = state.nav
    val route = state.highlighted

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Palette.panel.copy(alpha = 0.95f))
            .border(1.dp, Palette.panelEdge, RoundedCornerShape(20.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text(
                    text = nav?.let { "${it.minutesLeft()}" } ?: "--",
                    style = Type.figure,
                    color = Palette.text,
                )
                Text("min left", style = Type.unit, color = Palette.textDim)
            }
            Spacer(Modifier.width(22.dp))
            Column {
                Text(
                    text = nav?.let { arrivalClock(it.remainingSeconds) } ?: "--:--",
                    style = Type.figure,
                    color = Palette.text,
                )
                Text("arrive", style = Type.unit, color = Palette.textDim)
            }
            Spacer(Modifier.width(22.dp))
            Column {
                Text(
                    text = nav?.let { "%.1f".format(it.remainingMeters / 1_000.0) } ?: "--",
                    style = Type.figure,
                    color = Palette.text,
                )
                Text("km", style = Type.unit, color = Palette.textDim)
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = "End",
                style = Type.bodyStrong,
                color = Palette.textDim,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, Palette.panelEdge, RoundedCornerShape(12.dp))
                    .clickable(onClick = model::stopDrive)
                    .padding(horizontal = 14.dp, vertical = 9.dp),
            )
        }

        if (nav != null) {
            RoadAhead(nav.congestionAhead, nav.jamAheadMeters)
        }

        route?.let {
            CongestionStrip(
                route = it.candidate,
                height = 20.dp,
                progress = state.progress,
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = when (state.positionSource) {
                    // Said plainly, and always, while the drive is being
                    // played back. A speed that is not the car's must never be
                    // able to be mistaken for one that is.
                    PositionSource.SIMULATED -> "Simulated drive, not your position"
                    PositionSource.DEVICE -> route?.candidate?.viaLabel().orEmpty()
                    PositionSource.NONE -> "Waiting for a position"
                },
                style = Type.caption,
                color = if (state.positionSource == PositionSource.SIMULATED) {
                    Palette.light
                } else {
                    Palette.textDim
                },
            )
            Spacer(Modifier.weight(1f))
            if (state.refreshing) {
                Text("checking traffic", style = Type.caption, color = Palette.accent)
            }
        }
    }
}

/** A round icon button, sized to be hit without aiming. */
@Composable
private fun RoundButton(
    onClick: () -> Unit,
    content: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit,
) {
    Box(
        Modifier
            .size(52.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(Palette.panel.copy(alpha = 0.94f))
            .border(1.dp, Palette.panelEdge, RoundedCornerShape(26.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Glyph(22.dp, draw = content)
    }
}

/** Local clock time of arrival, from seconds remaining. */
private fun arrivalClock(remainingSeconds: Double): String {
    val at = Instant.now().plusSeconds(remainingSeconds.roundToInt().toLong())
    return CLOCK.format(at.atZone(ZoneId.systemDefault()))
}

private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/**
 * How close a manoeuvre has to be before it is worth the top of the screen.
 * Two kilometres is about a minute at motorway speed, which is when a driver
 * starts wanting to know which lane they are in.
 */
private const val MANEUVER_VISIBLE_METERS = 2_000.0
