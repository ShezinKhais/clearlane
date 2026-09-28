package ae.clearlane.data.uae

import java.time.DayOfWeek
import java.time.ZonedDateTime

/**
 * When the roads are bad, as a matter of the clock rather than of live data.
 *
 * Live traffic tells you what is happening now. It cannot tell you that you are
 * about to set off into the worst forty minutes of the week, or that the road
 * will have emptied by the time you reach the far end of it. In the UAE the
 * patterns are unusually sharp, so a sentence of warning is worth more than it
 * would be elsewhere:
 *
 *  - The working week runs Monday to Friday, with Saturday and Sunday off, so
 *    the commuter peaks are Monday to Friday and Sunday is quiet. This is also
 *    why Salik charges a flat rate on Sundays.
 *  - During Ramadan the evening rush collapses into the hour before iftar and
 *    then the roads empty completely. Gulf News reported the pre iftar window
 *    of roughly 17:00 to 19:15 as the single worst congestion of the month in
 *    Dubai and Sharjah for 2026.
 *
 * These are described patterns from reporting and from the published toll
 * windows, not a fitted model. They are used to add a line of context, never to
 * change a congestion figure, because inventing traffic that the live data does
 * not show would be worse than saying nothing.
 */
object TrafficCalendar {

    enum class Period {
        OVERNIGHT,
        MORNING_PEAK,
        MIDDAY,
        EVENING_PEAK,
        EVENING,
        WEEKEND,
        RAMADAN_PRE_IFTAR,
        RAMADAN_EVENING,
    }

    /** Which period [at] falls in. */
    fun periodAt(at: ZonedDateTime, ramadan: Boolean = false): Period {
        val local = at.withZoneSameInstant(SalikTariff.DUBAI)
        val hour = local.hour + local.minute / 60.0
        val weekend = local.dayOfWeek == DayOfWeek.SATURDAY || local.dayOfWeek == DayOfWeek.SUNDAY

        if (ramadan) {
            return when {
                hour >= 17.0 && hour < 19.25 -> Period.RAMADAN_PRE_IFTAR
                hour >= 19.25 || hour < 2.0 -> Period.RAMADAN_EVENING
                hour < 7.0 -> Period.OVERNIGHT
                hour < 10.5 -> Period.MORNING_PEAK
                else -> Period.MIDDAY
            }
        }

        return when {
            hour >= 1.0 && hour < 6.0 -> Period.OVERNIGHT
            weekend -> Period.WEEKEND
            hour >= 6.0 && hour < 10.0 -> Period.MORNING_PEAK
            hour >= 16.0 && hour < 20.0 -> Period.EVENING_PEAK
            hour >= 10.0 && hour < 16.0 -> Period.MIDDAY
            else -> Period.EVENING
        }
    }

    /**
     * One plain sentence of context, or null when there is nothing to say.
     *
     * Kept to one sentence on purpose. A driver reading their phone before
     * pulling out does not want a briefing.
     */
    fun advice(at: ZonedDateTime, ramadan: Boolean = false): String? =
        when (periodAt(at, ramadan)) {
            Period.MORNING_PEAK -> "Morning peak, so the inland roads usually move better than the coast."
            Period.EVENING_PEAK -> "Evening peak until 8pm, which is when the gap between corridors is widest."
            Period.RAMADAN_PRE_IFTAR ->
                "The hour before iftar is the worst of the month. Leaving after it is quicker than any detour."
            Period.RAMADAN_EVENING -> "Roads are usually clear after iftar."
            Period.OVERNIGHT -> "Overnight, so most routes will read the same."
            Period.WEEKEND -> null
            Period.MIDDAY -> null
            Period.EVENING -> null
        }

    /** True when the tier comparison is likely to be worth looking at. */
    fun corridorsDiverge(at: ZonedDateTime, ramadan: Boolean = false): Boolean =
        when (periodAt(at, ramadan)) {
            Period.MORNING_PEAK, Period.EVENING_PEAK, Period.RAMADAN_PRE_IFTAR -> true
            else -> false
        }
}
