package ae.clearlane.data.uae

import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * What a Salik gate costs at a given moment.
 *
 * Dubai moved from a flat AED 4 per gate to variable pricing on 31 January
 * 2025: AED 6 in the peaks, AED 4 off peak, nothing overnight, and a flat AED 4
 * all day Sunday. Ramadan runs a different clock again. Announced by Salik
 * here, which is the source these windows come from:
 * https://www.salik.ae/en/news/salik-announces-implementation-of-variable-toll-pricing-effective-january-31-2025
 *
 * This matters to routing and not just to the bill. The peak windows are the
 * congested windows, by construction, so the same schedule tells you when the
 * road you are being sent down is likely to be full.
 *
 * Verify the current rates against salik.ae before shipping. Tariffs change by
 * decree and this file will not notice.
 */
object SalikTariff {

    val DUBAI: ZoneId = ZoneId.of("Asia/Dubai")

    const val PEAK_FILS = 600
    const val OFF_PEAK_FILS = 400
    const val NIGHT_FILS = 0

    /** Sunday is a weekend day in the UAE, and charged flat. */
    const val SUNDAY_FILS = 400

    enum class Band { PEAK, OFF_PEAK, FREE }

    data class Window(val from: LocalTime, val until: LocalTime, val band: Band)

    /** Ordinary schedule, Monday to Saturday. */
    val STANDARD: List<Window> = listOf(
        Window(LocalTime.of(1, 0), LocalTime.of(6, 0), Band.FREE),
        Window(LocalTime.of(6, 0), LocalTime.of(10, 0), Band.PEAK),
        Window(LocalTime.of(10, 0), LocalTime.of(16, 0), Band.OFF_PEAK),
        Window(LocalTime.of(16, 0), LocalTime.of(20, 0), Band.PEAK),
        // Runs past midnight into the free window.
        Window(LocalTime.of(20, 0), LocalTime.of(1, 0), Band.OFF_PEAK),
    )

    /** Ramadan schedule, which shifts the whole day later. */
    val RAMADAN: List<Window> = listOf(
        Window(LocalTime.of(2, 0), LocalTime.of(7, 0), Band.FREE),
        Window(LocalTime.of(7, 0), LocalTime.of(9, 0), Band.OFF_PEAK),
        Window(LocalTime.of(9, 0), LocalTime.of(17, 0), Band.PEAK),
        Window(LocalTime.of(17, 0), LocalTime.of(2, 0), Band.OFF_PEAK),
    )

    /**
     * Which band [at] falls in.
     *
     * [ramadan] has to be passed in. Working out the Islamic calendar from a
     * timestamp is a separate problem with its own wrong answers, and guessing
     * it here would put a wrong price on the screen with no way for the driver
     * to tell.
     */
    fun bandAt(at: ZonedDateTime, ramadan: Boolean = false): Band {
        val local = at.withZoneSameInstant(DUBAI)
        val time = local.toLocalTime()
        val schedule = if (ramadan) RAMADAN else STANDARD
        val band = schedule.firstOrNull { it.contains(time) }?.band ?: Band.OFF_PEAK
        // Sunday is flat rated, so there is no peak on it. The overnight free
        // window is left in place: the announcement gives both rules without
        // saying which wins, and charging nothing overnight is the reading that
        // cannot overcharge anyone.
        if (!ramadan && local.dayOfWeek == DayOfWeek.SUNDAY && band == Band.PEAK) {
            return Band.OFF_PEAK
        }
        return band
    }

    /** What one gate costs at [at], in fils. */
    fun filsAt(at: ZonedDateTime, ramadan: Boolean = false): Int =
        when (bandAt(at, ramadan)) {
            Band.PEAK -> PEAK_FILS
            Band.OFF_PEAK -> OFF_PEAK_FILS
            Band.FREE -> NIGHT_FILS
        }

    private fun Window.contains(time: LocalTime): Boolean =
        if (from <= until) {
            time >= from && time < until
        } else {
            // Wraps midnight.
            time >= from || time < until
        }
}
