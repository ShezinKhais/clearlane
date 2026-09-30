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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
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
 *
 * The comparison is built to be read down rather than across. Every route puts
 * its three figures at the same three positions and draws its strip against one
 * shared distance axis, so choosing between four routes is scanning a column
 * instead of re-finding the numbers on each card.
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
                // Says which of the two waits this is. A bare spinner over a
                // map leaves the driver unable to tell a first plan from a
                // traffic recheck, and they are worth waiting for differently.
                Column(
                    Modifier.fillMaxSize().background(Palette.background.copy(alpha = 0.45f)),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator(color = Palette.accent)
                    Spacer(Modifier.height(10.dp))
                    Text("Comparing routes", color = Palette.text, style = Type.bodyStrong)
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
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = buildString {
                        append(state.outcome?.providerName ?: model.providerName)
                        if (state.outcome?.isDemoData == true) append(" · demo data")
                    },
                    color = Palette.textDim,
                    style = Type.caption,
                )
                Freshness(state)
            }
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

        // The map's two gestures are otherwise undiscoverable: nothing on
        // screen suggests a tap sets a destination or that holding moves the
        // start, so without this line the pickers might as well not exist.
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (state.activeTripId != null) {
                "Tap the map for a different destination, hold to move the start."
            } else {
                "Your own start and destination. Tap the map to change where you are going."
            },
            color = Palette.textDim,
            style = Type.caption,
        )
    }
}

/**
 * How old the traffic on screen is.
 *
 * Nothing pushes traffic to a phone, so what is being compared is always a
 * reading taken at some point in the past. Saying when turns a stale plan from
 * something that misleads into something the driver can decide about, and the
 * app already records the time; it simply never showed it.
 */
@Composable
private fun Freshness(state: RouteUiState) {
    if (state.refreshing) {
        Text("checking traffic", color = Palette.accent, style = Type.caption)
        return
    }
    val at = state.plannedAtMillis ?: return

    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(at) {
        while (true) {
            now = System.currentTimeMillis()
            delay(FRESHNESS_TICK_MS)
        }
    }

    val minutes = ((now - at) / 60_000L).toInt()
    Text(
        text = when {
            minutes <= 0 -> "traffic just now"
            minutes == 1 -> "traffic 1 min old"
            else -> "traffic $minutes min old"
        },
        color = if (minutes >= STALE_MINUTES) Palette.light else Palette.textDim,
        style = Type.caption,
    )
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
            .heightIn(max = 340.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        state.error?.let {
            Text("Could not plan this", color = Palette.severe, style = Type.bodyStrong)
            Spacer(Modifier.height(2.dp))
            Text(it, color = Palette.textDim, style = Type.body)
            Spacer(Modifier.height(12.dp))
        }
        if (plan == null) {
            if (state.error == null) {
                Text("Nothing to compare yet", color = Palette.text, style = Type.bodyStrong)
                Spacer(Modifier.height(2.dp))
                Text(
                    "Pick a trip above, or tap the map to set where you are going.",
                    color = Palette.textDim,
                    style = Type.body,
                )
            }
            return@Column
        }

        // One scale for every strip on the screen, taken from the longest route
        // the plan considered rather than from the ones it chose to show, so
        // switching the tier filter does not silently rescale the picture.
        val scaleMeters = plan.considered.maxOfOrNull { it.meters } ?: plan.fastest.meters

        // Inset to exactly where the strips inside the cards begin and end: the
        // 4 dp colour bar plus the card's own padding. An axis a few pixels
        // wider than the thing it measures is worse than no axis, because it
        // reads as a route falling short of a distance it actually covers.
        DistanceAxis(
            maxMeters = scaleMeters,
            modifier = Modifier.padding(start = CARD_CONTENT_START, end = CARD_CONTENT_END),
        )
        Spacer(Modifier.height(10.dp))

        FastestCard(
            plan = plan,
            scaleMeters = scaleMeters,
            selected = state.selected == null,
            onClick = { model.select(null) },
        )

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
                scaleMeters = scaleMeters,
                recommended = tier == plan.recommended,
                selected = state.selected == tier,
                onClick = { model.select(tier) },
            )
            Spacer(Modifier.height(8.dp))
        }

        Notes(plan.notes + state.outcome.warnings + listOfNotNull(state.hazardAttribution))

        Spacer(Modifier.height(14.dp))
        Preferences(state, model)
    }
}

/**
 * Whatever the plan wants to say for itself: assumptions it made, sources it
 * used, things it could not do.
 *
 * Set below a rule rather than prefixed with a bullet character. These are
 * footnotes to the comparison, and a rule says that; a dot pretending to be a
 * list marker only adds noise to the start of every line.
 */
@Composable
private fun Notes(lines: List<String>) {
    if (lines.isEmpty()) return
    Spacer(Modifier.height(12.dp))
    Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.line))
    Spacer(Modifier.height(8.dp))
    for (line in lines) {
        Text(line, color = Palette.textDim, style = Type.caption)
        Spacer(Modifier.height(4.dp))
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
private fun FastestCard(
    plan: RoutePlan,
    scaleMeters: Double,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val route = plan.fastest
    Card(
        selected = selected,
        accent = Palette.forCongestion(route.congestion.timeWeighted),
        onClick = onClick,
    ) {
        Text("Quickest route", color = Palette.text, style = Type.bodyStrong)
        Spacer(Modifier.height(1.dp))
        Text(
            "${km(route.meters)} km · ${route.candidate.viaLabel()}",
            color = Palette.textDim,
            style = Type.caption,
        )
        Spacer(Modifier.height(8.dp))
        Readings(route)
        Spacer(Modifier.height(10.dp))
        CongestionStrip(route.candidate, scaleMeters = scaleMeters)
        if (selected) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "What another maps app would give you. " + weakness(route),
                color = Palette.textDim,
                style = Type.caption,
            )
        }
    }
}

@Composable
private fun TierCard(
    tier: TrafficTier,
    route: ScoredRoute,
    fastest: ScoredRoute,
    scaleMeters: Double,
    recommended: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val isSameRoad = route.id == fastest.id
    Card(selected = selected, accent = Palette.forTier(tier), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tierWords(tier), color = Palette.text, style = Type.bodyStrong)
            if (recommended) {
                Spacer(Modifier.width(6.dp))
                // A recommendation is filled and a breach is outlined, because
                // they are opposite kinds of news. Given the same treatment,
                // as they were, a driver has to read both to tell them apart.
                Tag("our pick", Palette.accent, filled = true)
            }
            if (route.overBudget) {
                Spacer(Modifier.width(6.dp))
                Tag("over budget", Palette.heavy)
            }
        }
        Spacer(Modifier.height(1.dp))
        Text(
            text = if (isSameRoad) {
                "The quickest route, and calm enough to count."
            } else {
                "${km(route.meters)} km · ${route.candidate.viaLabel()}"
            },
            color = Palette.textDim,
            style = Type.caption,
        )
        Spacer(Modifier.height(8.dp))
        Readings(route, extraSeconds = route.extraSeconds)
        Spacer(Modifier.height(10.dp))
        CongestionStrip(route.candidate, scaleMeters = scaleMeters)
        if (selected && route.candidate.tollFils > 0) {
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
 * The three figures every route carries, at the same three positions on every
 * card.
 *
 * Minutes and congestion are set at the same size on purpose. The whole premise
 * of the app is that those two are a trade against each other, and the earlier
 * layout gave minutes two and a half times the type size of congestion, which
 * argued the opposite of what the app is for.
 *
 * Fixed column widths rather than spacing, so the values line up down the list
 * whatever their number of digits. Comparing four routes should be reading a
 * column, not hunting each card for where its congestion figure ended up.
 */
@Composable
private fun Readings(route: ScoredRoute, extraSeconds: Double? = null) {
    val congestion = (route.congestion.timeWeighted * 100).roundToInt()
    Row(verticalAlignment = Alignment.Bottom) {
        Cell(
            value = "${minutes(route.durationSeconds)}",
            label = "min",
            colour = Palette.text,
        )
        Cell(
            value = "$congestion%",
            label = "congested",
            colour = Palette.forCongestion(route.congestion.timeWeighted),
            // The congestion colour also appears as the bar down the card edge
            // and as height in the strip, so the figure itself carries the
            // number and the dot only confirms which band it fell in.
            dot = true,
        )
        Cell(
            value = route.score.total.toString(),
            label = "drive",
            colour = Palette.forScore(route.score.total),
        )
        if (extraSeconds != null) {
            Spacer(Modifier.weight(1f))
            Text(
                text = delta(extraSeconds),
                color = if (extraSeconds > 0) Palette.textDim else Palette.clear,
                style = Type.caption,
                modifier = Modifier.padding(bottom = 3.dp),
            )
        }
    }
}

/** One figure and what it measures, in a fixed column. */
@Composable
private fun Cell(value: String, label: String, colour: Color, dot: Boolean = false) {
    Column(Modifier.width(CELL_WIDTH)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (dot) {
                Box(
                    Modifier
                        .size(7.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(colour),
                )
                Spacer(Modifier.width(4.dp))
            }
            Text(value, color = colour, style = Type.callout)
        }
        Text(label, color = Palette.textDim, style = Type.caption)
    }
}

@Composable
private fun Tag(text: String, color: Color, filled: Boolean = false) {
    Text(
        text = text,
        color = if (filled) Palette.background else color,
        style = Type.caption,
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(if (filled) color else Color.Transparent)
            .border(
                width = 1.dp,
                color = if (filled) Color.Transparent else color.copy(alpha = 0.45f),
                shape = RoundedCornerShape(7.dp),
            )
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
 *
 * Only the selected card carries its prose. Four routes each explaining
 * themselves at once is four paragraphs nobody reads and a list too tall to
 * compare; the explanation belongs on the one being considered.
 */
@Composable
private fun Card(
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
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
                .width(CARD_BAR)
                .heightIn(min = 60.dp)
                .background(accent),
        )
        Column(
            Modifier
                .weight(1f)
                .padding(
                    start = CARD_CONTENT_START - CARD_BAR,
                    end = CARD_CONTENT_END,
                    top = 12.dp,
                    bottom = 12.dp,
                ),
            content = content,
        )
    }
}

@Composable
private fun Preferences(state: RouteUiState, model: RouteViewModel) {
    Column {
        Text("How much longer will you accept", color = Palette.textDim, style = Type.bodyStrong)
        Spacer(Modifier.height(8.dp))
        // A connected run rather than four separate chips. These are four points
        // on one scale, and four detached pills say they are four unrelated
        // choices, which is the wrong thing to say about an ordered range.
        Row(
            Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(Palette.surfaceHigh),
        ) {
            for (option in EXTRA_MINUTE_OPTIONS) {
                val on = state.preferences.maxExtraMinutes == option
                Text(
                    text = "+$option",
                    color = if (on) Palette.background else Palette.text,
                    style = Type.bodyStrong,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .background(if (on) Palette.accent else Color.Transparent)
                        .clickable { model.setMaxExtraMinutes(option) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            Text(
                text = "min",
                color = Palette.textDim,
                style = Type.unit,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 11.dp),
            )
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

/**
 * How much traffic a tier allows, said as a phrase.
 *
 * The tier labels are levels rather than adjectives, so pasting "traffic" onto
 * the end of one gives "None traffic". The reading is the point of the card, so
 * it is worth writing out rather than assembling.
 */
private fun tierWords(tier: TrafficTier): String = when (tier) {
    TrafficTier.NONE -> "Almost no traffic"
    TrafficTier.LOW -> "Light traffic"
    TrafficTier.MEDIUM -> "Moderate traffic"
    TrafficTier.HIGH -> "Heavy traffic"
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

/**
 * Wide enough for three digits and a per cent sign at [Type.callout], so no
 * value in the column can push the next one out of line.
 */
private val CELL_WIDTH = 74.dp

/** The colour bar down a card's leading edge. */
private val CARD_BAR = 4.dp

/**
 * Where a card's content starts and ends, measured from the card's own edges.
 * The distance axis is drawn to the same inset so it lines up with the strips.
 */
private val CARD_CONTENT_START = 16.dp
private val CARD_CONTENT_END = 14.dp

private val EXTRA_MINUTE_OPTIONS = listOf(5, 10, 20, 40)

/** Coarse on purpose: the label is in whole minutes, so nothing finer shows. */
private const val FRESHNESS_TICK_MS = 15_000L

/** When a reading stops being worth trusting without a recheck. */
private const val STALE_MINUTES = 10
