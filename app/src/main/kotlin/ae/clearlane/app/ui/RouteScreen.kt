package ae.clearlane.app.ui

import ae.clearlane.app.RouteUiState
import ae.clearlane.app.RouteViewModel
import ae.clearlane.app.map.RouteMap
import ae.clearlane.app.ui.drive.PillButton
import ae.clearlane.core.model.DriveFactor
import ae.clearlane.core.model.RoutePlan
import ae.clearlane.core.model.ScoredRoute
import ae.clearlane.core.model.TrafficTier
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The planning screen: every route the app found, side by side, with what each
 * one costs.
 *
 * The map keeps the upper half and the comparison sits under it, because the
 * argument the app is making is a visual one. Four lines fanning inland with
 * three of them green says more than any of the numbers below it, and the
 * numbers are there to confirm what the picture already showed.
 */
@Composable
fun RouteScreen(state: RouteUiState, model: RouteViewModel) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Header(state, model)

        Box(Modifier.fillMaxWidth().weight(1f).heightIn(min = 200.dp)) {
            RouteMap(
                routes = state.outcome?.plan?.considered.orEmpty(),
                highlighted = state.highlighted,
                onTap = model::setDestination,
                onLongPress = model::setOrigin,
                modifier = Modifier.fillMaxSize(),
            )
            if (state.loading) {
                Box(
                    Modifier.fillMaxSize().background(Palette.background.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = Palette.accent)
                }
            }
        }

        Sheet(state, model)
    }
}

@Composable
private fun Header(state: RouteUiState, model: RouteViewModel) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 12.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Clearlane", color = Palette.text, style = Type.title)
            Spacer(Modifier.weight(1f))
            Text(
                text = buildString {
                    append(state.outcome?.providerName ?: model.providerName)
                    if (state.outcome?.isDemoData == true) append(" · demo data")
                },
                color = Palette.textDim,
                style = Type.caption,
            )
        }

        state.outcome?.timeAdvice?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, color = Palette.accent, style = Type.caption)
        }

        if (state.trips.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (trip in state.trips) {
                    Chip(
                        label = trip.label,
                        selected = trip.id == state.activeTripId,
                        onClick = { model.selectTrip(trip) },
                    )
                }
            }
        }
    }
}

@Composable
private fun Sheet(state: RouteUiState, model: RouteViewModel) {
    Column(Modifier.fillMaxWidth().background(Palette.surface)) {
        SheetContent(state, model)
        // Pinned under the scrolling list rather than at the end of it. It is
        // the one thing on this screen the driver is heading for, and having to
        // scroll four cards to find it would be a poor joke in a car.
        DriveActions(state, model)
    }
}

@Composable
private fun ColumnScope.SheetContent(state: RouteUiState, model: RouteViewModel) {
    val plan = state.outcome?.plan
    Column(
        Modifier
            .fillMaxWidth()
            // Capped so the map keeps a usable share of the screen. Without
            // this the sheet grows to fit its content and squeezes the map
            // down to a strip, which rather defeats a maps app.
            .heightIn(max = 320.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        state.error?.let {
            Text(it, color = Palette.severe, style = Type.body)
            Spacer(Modifier.height(10.dp))
        }
        if (plan == null) {
            Text("Pick a trip to compare routes.", color = Palette.textDim, style = Type.body)
            return@Column
        }

        FastestCard(plan, selected = state.selected == null, onClick = { model.select(null) })

        Spacer(Modifier.height(14.dp))
        Text(
            text = if (plan.fastestIsCalmest) {
                "Nothing calmer to find right now, so the quick way is the good way."
            } else {
                "Calmer ways home"
            },
            color = Palette.textDim,
            style = Type.bodyStrong,
        )
        Spacer(Modifier.height(8.dp))

        for ((tier, route) in plan.picks) {
            if (route.id == plan.fastest.id && plan.picks.size == 1) continue
            TierCard(
                tier = tier,
                route = route,
                fastest = plan.fastest,
                recommended = tier == plan.recommended,
                selected = state.selected == tier,
                onClick = { model.select(tier) },
            )
            Spacer(Modifier.height(8.dp))
        }

        Spacer(Modifier.height(6.dp))
        for (note in plan.notes) {
            Text("· $note", color = Palette.textDim, style = Type.caption)
        }
        for (warning in state.outcome.warnings) {
            Text("· $warning", color = Palette.textDim, style = Type.caption)
        }
        state.hazardAttribution?.let {
            Text("· $it", color = Palette.textDim, style = Type.caption)
        }

        Spacer(Modifier.height(14.dp))
        Preferences(state, model)
    }
}

/**
 * Starting the drive.
 *
 * Two buttons rather than one, because without a car the driving screen cannot
 * otherwise be seen at all. The simulated option is labelled here and again on
 * the screen itself the whole time it is running.
 */
@Composable
private fun DriveActions(state: RouteUiState, model: RouteViewModel) {
    val route = state.highlighted ?: return
    Column(
        Modifier
            .fillMaxWidth()
            .background(Palette.surfaceHigh)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton(
                label = "Drive this route",
                colour = Palette.accent,
                onClick = { model.startDrive(simulate = false) },
                modifier = Modifier.weight(1f),
            )
            PillButton(
                label = "Simulate",
                colour = Palette.surface,
                onClick = { model.startDrive(simulate = true) },
                labelColour = Palette.text,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Following ${route.candidate.viaLabel()}, ${minutes(route.durationSeconds)} min.",
            color = Palette.textDim,
            style = Type.caption,
        )
    }
}

@Composable
private fun FastestCard(plan: RoutePlan, selected: Boolean, onClick: () -> Unit) {
    val route = plan.fastest
    Card(
        selected = selected,
        accent = Palette.forCongestion(route.congestion.timeWeighted),
        onClick = onClick,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text("Quickest route", color = Palette.textDim, style = Type.caption)
                Spacer(Modifier.height(2.dp))
                Text("${minutes(route.durationSeconds)} min", color = Palette.text, style = Type.figure)
                Text(
                    "${km(route.meters)} km · ${route.candidate.viaLabel()}",
                    color = Palette.textDim,
                    style = Type.caption,
                )
            }
            Readings(route)
        }
        Spacer(Modifier.height(10.dp))
        CongestionStrip(route.candidate)
        Spacer(Modifier.height(8.dp))
        Text(
            text = "What another maps app would give you. " + weakness(route),
            color = Palette.textDim,
            style = Type.caption,
        )
    }
}

@Composable
private fun TierCard(
    tier: TrafficTier,
    route: ScoredRoute,
    fastest: ScoredRoute,
    recommended: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val isSameRoad = route.id == fastest.id
    Card(selected = selected, accent = Palette.forTier(tier), onClick = onClick) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${tier.label} traffic", color = Palette.text, style = Type.bodyStrong)
                    if (recommended) {
                        Spacer(Modifier.width(6.dp))
                        Tag("our pick", Palette.accent)
                    }
                    if (route.overBudget) {
                        Spacer(Modifier.width(6.dp))
                        Tag("over budget", Palette.heavy)
                    }
                }
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        "${minutes(route.durationSeconds)} min",
                        color = Palette.text,
                        style = Type.figure,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = delta(route.extraSeconds),
                        color = if (route.extraSeconds > 0) Palette.textDim else Palette.clear,
                        style = Type.caption,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                Text(
                    text = if (isSameRoad) {
                        "The quickest route, and calm enough to count."
                    } else {
                        "${km(route.meters)} km · ${route.candidate.viaLabel()}"
                    },
                    color = Palette.textDim,
                    style = Type.caption,
                )
            }
            Readings(route)
        }
        Spacer(Modifier.height(10.dp))
        CongestionStrip(route.candidate)
        if (route.candidate.tollFils > 0) {
            Spacer(Modifier.height(8.dp))
            Text(
                "AED ${"%.0f".format(route.candidate.tollFils / 100.0)} of tolls",
                color = Palette.textDim,
                style = Type.caption,
            )
        }
    }
}

/**
 * The two figures every route carries, in the same place on every card.
 *
 * Right aligned and always in this order, so comparing four routes is reading
 * down a column rather than hunting for the number on each one.
 */
@Composable
private fun Readings(route: ScoredRoute) {
    val congestion = (route.congestion.timeWeighted * 100).roundToInt()
    Column(horizontalAlignment = Alignment.End) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(7.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Palette.forCongestion(route.congestion.timeWeighted)),
            )
            Spacer(Modifier.width(5.dp))
            Text("$congestion%", color = Palette.text, style = Type.bodyStrong)
        }
        Text("congested", color = Palette.textDim, style = Type.caption)
        Spacer(Modifier.height(6.dp))
        Text(
            text = route.score.total.toString(),
            color = Palette.forScore(route.score.total),
            style = Type.bodyStrong,
        )
        Text("drive", color = Palette.textDim, style = Type.caption)
    }
}

@Composable
private fun Tag(text: String, color: Color) {
    Text(
        text = text,
        color = color,
        style = Type.caption,
        modifier = Modifier
            .border(1.dp, color.copy(alpha = 0.45f), RoundedCornerShape(7.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        color = if (selected) Palette.background else Palette.text,
        style = Type.caption,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Palette.accent else Palette.surfaceHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 7.dp),
    )
}

/**
 * A route card.
 *
 * The congestion colour is carried on a bar down the leading edge rather than in
 * the border, so the cards read as a column of coloured tabs before any of the
 * text on them has been looked at. Selection is the border and the lighter fill,
 * which keeps two different signals doing two different jobs.
 */
@Composable
private fun Card(
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) Palette.surfaceHigh else Palette.panel)
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) Palette.accent else Palette.line,
                shape = RoundedCornerShape(16.dp),
            )
            .clickable(onClick = onClick),
    ) {
        Box(
            Modifier
                .width(4.dp)
                .heightIn(min = 60.dp)
                .background(accent),
        )
        Column(
            Modifier
                .weight(1f)
                .padding(start = 12.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun Preferences(state: RouteUiState, model: RouteViewModel) {
    Column {
        Text("How much longer will you accept", color = Palette.textDim, style = Type.bodyStrong)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (option in listOf(5, 10, 20, 40)) {
                Chip(
                    label = "+$option min",
                    selected = state.preferences.maxExtraMinutes == option,
                    onClick = { model.setMaxExtraMinutes(option) },
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Keep off the tolls", color = Palette.textDim, style = Type.body)
            Spacer(Modifier.weight(1f))
            Switch(
                checked = state.preferences.avoidTolls,
                onCheckedChange = model::setAvoidTolls,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Palette.background,
                    checkedTrackColor = Palette.accent,
                    uncheckedThumbColor = Palette.textDim,
                    uncheckedTrackColor = Palette.surface,
                ),
            )
        }
    }
}

private fun weakness(route: ScoredRoute): String = when (route.score.weakest) {
    DriveFactor.FLOW -> "Mostly stop and go."
    DriveFactor.STEADINESS -> "You will be on and off the brakes the whole way."
    DriveFactor.CRUISE -> "Never clear for long enough to settle into it."
    DriveFactor.JUNCTIONS -> "Too many lights and junctions."
    DriveFactor.SWEEP -> "Straight and dull, but moving."
}

private fun minutes(seconds: Double): Int = (seconds / 60.0).roundToInt()

private fun km(meters: Double): String = "%.1f".format(meters / 1000.0)

private fun delta(extraSeconds: Double): String {
    val m = (extraSeconds / 60.0).roundToInt()
    return when {
        m == 0 -> "same time"
        m > 0 -> "+$m min"
        else -> "${abs(m)} min quicker"
    }
}
