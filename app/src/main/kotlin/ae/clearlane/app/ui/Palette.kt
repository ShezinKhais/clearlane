package ae.clearlane.app.ui

import ae.clearlane.core.model.CongestionProfile
import ae.clearlane.core.model.TrafficTier
import androidx.compose.ui.graphics.Color

/**
 * One congestion scale, used by the map, the strip charts and the badges, so
 * that a colour means the same thing everywhere in the app.
 *
 * Four steps rather than a smooth gradient: a driver reads "is this bit moving"
 * off the colour, and a continuous ramp makes that a comparison instead of a
 * glance. The steps line up with the thresholds the scoring uses, so what you
 * see is what the score counted.
 *
 * The scale is not carried by hue alone. Congestion also reads as height in the
 * strip chart and as a number on every card, because a red and green route
 * comparison is no use to someone who cannot tell red from green.
 */
object Palette {

    val background = Color(0xFF0E1116)
    val surface = Color(0xFF161A21)
    val surfaceHigh = Color(0xFF1E242D)
    val line = Color(0xFF2A313C)
    val text = Color(0xFFE6E9EE)
    val textDim = Color(0xFF9AA4B2)
    val accent = Color(0xFF7AA2F7)

    val clear = Color(0xFF3FB984)
    val light = Color(0xFFE4B22E)
    val heavy = Color(0xFFE2773C)
    val severe = Color(0xFFD2453C)

    /** Colour for one segment's congestion. */
    fun forCongestion(value: Double): Color = when {
        value < CongestionProfile.CLEAR -> clear
        value < CongestionProfile.HEAVY -> light
        value < CongestionProfile.SEVERE -> heavy
        else -> severe
    }

    /** Which of the four bands a value falls in, for the map's match expression. */
    fun bandOf(value: Double): Int = when {
        value < CongestionProfile.CLEAR -> 0
        value < CongestionProfile.HEAVY -> 1
        value < CongestionProfile.SEVERE -> 2
        else -> 3
    }

    val bandColors = listOf(clear, light, heavy, severe)

    /** Colour of a tier's badge, taken from the worst traffic it allows. */
    fun forTier(tier: TrafficTier): Color = forCongestion(tier.ceiling * 0.9)

    fun forScore(score: Int): Color = when {
        score >= 75 -> clear
        score >= 50 -> light
        score >= 30 -> heavy
        else -> severe
    }
}
