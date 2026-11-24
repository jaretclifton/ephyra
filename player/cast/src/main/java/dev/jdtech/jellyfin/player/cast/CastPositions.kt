package dev.jdtech.jellyfin.player.cast

/**
 * How to read the positions a Cast receiver reports. The playback reports (`CastPlaybackReporter`)
 * and the segment skip (`CastSegmentSkipper`, #851) read them the same way.
 */
object CastPositions {
    /** A position this far from where the clock puts it is a seek. */
    const val SEEK_TOLERANCE_MS = 2_000L

    /** An item this close to its end that jumps to its start has run out. */
    const val END_WINDOW_MS = 10_000L

    /**
     * An item that runs out is at position 0 for a moment before the receiver moves on. That is not
     * a seek back to the start: true when [positionMs] is that moment, after [lastPositionMs] of an
     * item of [durationMs] (null or 0 when unknown).
     */
    fun ranOut(lastPositionMs: Long, positionMs: Long, durationMs: Long?): Boolean =
        durationMs != null &&
            durationMs > 0L &&
            lastPositionMs >= durationMs - END_WINDOW_MS &&
            positionMs < SEEK_TOLERANCE_MS
}
