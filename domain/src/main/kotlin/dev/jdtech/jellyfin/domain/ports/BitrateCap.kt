package dev.jdtech.jellyfin.domain.ports

private const val BITS_PER_MEGABIT = 1_000_000L

/**
 * A cap in megabits per second as [PlaybackRequest.maxBitrate] (bits per second), null for no cap
 * (0 or less). The server field is an `Int`, so a cap above what it holds (about 2147 Mbps) is sent
 * as its maximum: the user asked for more than any link carries, and a clamp keeps it a cap (no cap
 * would fall back to the device profile's own, possibly lower, limit). #828.
 */
fun capMbpsToBitsPerSecond(mbps: Int): Int? =
    mbps
        .takeIf { it > 0 }
        ?.let { (it * BITS_PER_MEGABIT).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() }
