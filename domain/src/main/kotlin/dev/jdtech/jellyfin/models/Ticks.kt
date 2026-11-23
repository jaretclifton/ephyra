package dev.jdtech.jellyfin.models

/**
 * Conversions between ticks and milliseconds, seconds and minutes.
 *
 * Jellyfin and Emby both count time in ticks of 100 nanoseconds, so 10,000 ticks are a millisecond.
 * Runtimes, resume positions, chapter and segment positions all arrive in ticks as a plain [Long]
 * (one helper object instead of a value class, so no model and no Room column changes).
 *
 * The conversions to milliseconds and minutes divide the `Long` as it is: the part of a unit that
 * is left over is dropped. [toSeconds] is the one that keeps it.
 */
object Ticks {
    const val PER_MILLISECOND = 10_000L
    const val PER_SECOND = 10_000_000L
    const val PER_MINUTE = 600_000_000L

    /** Whole milliseconds in [ticks], rounded toward zero. */
    fun toMilliseconds(ticks: Long): Long = ticks / PER_MILLISECOND

    /** Whole minutes in [ticks], rounded toward zero. */
    fun toMinutes(ticks: Long): Long = ticks / PER_MINUTE

    /** The seconds in [ticks], with the fraction. */
    fun toSeconds(ticks: Long): Double = ticks / PER_SECOND.toDouble()

    /** The ticks in [milliseconds]. */
    fun fromMilliseconds(milliseconds: Long): Long = milliseconds * PER_MILLISECOND
}
