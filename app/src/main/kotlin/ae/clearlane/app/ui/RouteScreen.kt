package ae.clearlane.app.ui

import ae.clearlane.app.RouteUiState
import ae.clearlane.app.RouteViewModel
import ae.clearlane.app.map.RouteMap
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.roundToInt

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
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 14.dp, bottom = 8.dp)) {
        Text("Clearlane", color = Palette.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Text(
            text = buildString {
                append(state.outcome?.providerName ?: model.providerName)
                if (state.outcome?.isDemoData == true) append(" · demo data, no live traffic")
            },
            color = Palette.textDim,
            fontSize = 11.sp,
        )
        state.outcome?.timeAdvice?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, color = Palette.accent, fontSize = 12.sp)
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
        Text(
            "Tap the map to move the destination, hold to move the start.",
            color = Palette.textDim,
            fontSize = 10.sp,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun Sheet(state: RouteUiState, model: RouteViewModel) {
    val plan = state.outcome?.plan
    Column(
        Modifier
            .fillMaxWidth()
            // Capped so the map keeps a usable share of the screen. Without
            // this the sheet grows to fit its content and squeezes the map
            // down to a strip, which rather defeats a maps app.
            .heightIn(max = 340.dp)
            .background(Palette.surface)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        state.error?.let {
            Text(it, color = Palette.severe, fontSize = 13.sp)
            Spacer(Modifier.height(10.dp))
        }
        if (plan == null) {
            Text("Pick a trip to compare routes.", color = Palette.textDim, fontSize = 13.sp)
            return@Column
        }

        FastestCard(plan, selected = state.selected == null, onClick = { model.select(null) })

        Spacer(Modifier.height(12.dp))
        Text(
            text = if (plan.fastestIsCalmest) {
                "Nothing calmer to find right now, so the quick way is the good way."
            } else {
                "Calmer ways home"
            },
            color = Palette.textDim,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
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

        for (note in plan.notes) {
            Text("· $note", color = Palette.textDim, fontSize = 11.sp)
        }
        for (warning in state.outcome.warnings) {
            Text("· $warning", color = Palette.textDim, fontSize = 11.sp)
        }

        Spacer(Modifier.height(12.dp))
        Preferences(state, model)
    }
}

@Composable
private fun FastestCard(plan: RoutePlan, selected: Boolean, onClick: () -> Unit) {
    val route = plan.fastest
    Card(selected = selected, onClick = onClick) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Quickest route",
                    color = Palette.textDim,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    "${minutes(route.durationSeconds)} min · ${km(route.meters)} km",
                    color = Palette.text,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(route.candidate.viaLabel(), color = Palette.textDim, fontSize = 11.sp)
            }
            Column(horizontalAlignment = Alignment.End) {
                CongestionBadge(route)
                Spacer(Modifier.height(4.dp))
                ScoreBadge(route)
            }
        }
        Spacer(Modifier.height(8.dp))
        CongestionStrip(route.candidate)
        Spacer(Modifier.height(6.dp))
        Text(
            text = "What another maps app would give you. " + weakness(route),
            color = Palette.textDim,
            fontSize = 11.sp,
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
    Card(selected = selected, onClick = onClick) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .width(4.dp)
                    .height(38.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Palette.forTier(tier)),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${tier.label} traffic",
                        color = Palette.text,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (recommended) {
                        Spacer(Modifier.width(6.dp))
                        Tag("pick", Palette.accent)
                    }
                    if (route.overBudget) {
                        Spacer(Modifier.width(6.dp))
                        Tag("over budget", Palette.heavy)
                    }
                }
                Text(
                    text = buildString {
                        append("${minutes(route.durationSeconds)} min")
                        append(" · ")
                        append(delta(route.extraSeconds))
                        append(" · ")
                        append("${km(route.meters)} km")
                    },
                    color = Palette.text,
                    fontSize = 12.sp,
                )
                Text(
                    text = if (isSameRoad) {
                        "The quickest route, and calm enough to count."
                    } else {
                        route.candidate.viaLabel()
                    },
                    color = Palette.textDim,
                    fontSize = 11.sp,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                CongestionBadge(route)
                Spacer(Modifier.height(4.dp))
                ScoreBadge(route)
            }
        }
        Spacer(Modifier.height(8.dp))
        CongestionStrip(route.candidate)
        if (route.candidate.tollFils > 0) {
            Spacer(Modifier.height(6.dp))
            Text(
                "AED ${"%.0f".format(route.candidate.tollFils / 100.0)} of tolls",
                color = Palette.textDim,
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun CongestionBadge(route: ScoredRoute) {
    val percent = (route.congestion.timeWeighted * 100).roundToInt()
    Tag("$percent% congested", Palette.forCongestion(route.congestion.timeWeighted))
}

@Composable
private fun ScoreBadge(route: ScoredRoute) {
    Tag("drive ${route.score.total}", Palette.forScore(route.score.total))
}

@Composable
private fun Tag(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        text = text,
        color = color,
        fontSize = 10.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .border(1.dp, color.copy(alpha = 0.45f), RoundedCornerShape(3.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        color = if (selected) Palette.background else Palette.text,
        fontSize = 11.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .clip(RoundedCornerShape(3.dp))
            .background(if (selected) Palette.accent else Palette.surfaceHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

@Composable
private fun Card(selected: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = if (selected) Palette.accent else Palette.line,
                shape = RoundedCornerShape(4.dp),
            )
            .clip(RoundedCornerShape(4.dp))
            .background(Palette.surfaceHigh)
            .clickable(onClick = onClick)
            .padding(12.dp),
    ) {
        content()
    }
}

@Composable
private fun Preferences(state: RouteUiState, model: RouteViewModel) {
    Column {
        Text(
            "How much longer will you accept",
            color = Palette.textDim,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (option in listOf(5, 10, 20, 40)) {
                Chip(
                    label = "+$option min",
                    selected = state.preferences.maxExtraMinutes == option,
                    onClick = { model.setMaxExtraMinutes(option) },
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Keep off the tolls", color = Palette.textDim, fontSize = 11.sp)
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
