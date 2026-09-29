package ae.clearlane.app.ui

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp

/**
 * The type scale.
 *
 * This is an instrument panel before it is an app, so the hierarchy is built
 * around numbers. The readings are set heavy and tight, everything explaining
 * them is small and dim, and there is nothing in between competing for the
 * glance. A driver reads the speed in a fraction of a second or the screen has
 * failed, whatever else is on it.
 *
 * Tracking is pulled in on the large sizes because default letter spacing is
 * tuned for running text and leaves big numerals looking loose and slow.
 */
object Type {

    /** The speed, and nothing else at this size. */
    val reading = TextStyle(
        fontSize = 46.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-2.0).sp,
        lineHeight = 46.sp,
    )

    /** Minutes to arrival, distance left: the numbers under the speed. */
    val figure = TextStyle(
        fontSize = 26.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.8).sp,
        lineHeight = 28.sp,
    )

    /** The distance on a manoeuvre or a camera card. */
    val callout = TextStyle(
        fontSize = 20.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.4).sp,
    )

    /** Card headings on the planning screen. */
    val title = TextStyle(
        fontSize = 18.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.3).sp,
    )

    val body = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Normal)

    val bodyStrong = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium)

    /** Explaining a number, never competing with it. */
    val caption = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Normal, lineHeight = 15.sp)

    /** The unit beside a reading. Small enough to be ignored on a glance. */
    val unit = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.2.sp)

    /** The number on a speed limit sign, which is a shape people already know. */
    fun signNumber(size: Int) = TextStyle(
        fontSize = size.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-1.0).sp,
        color = Palette.signInk,
        textAlign = TextAlign.Center,
        fontFamily = FontFamily.Default,
    )
}
