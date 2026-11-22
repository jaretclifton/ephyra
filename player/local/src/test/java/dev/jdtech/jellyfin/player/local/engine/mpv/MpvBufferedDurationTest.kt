package dev.jdtech.jellyfin.player.local.engine.mpv

import kotlin.test.assertEquals
import org.junit.Test

/**
 * #847: mpv's buffered position is an absolute time, and Media3's total buffered duration is the
 * time buffered ahead of the current position.
 */
class MpvBufferedDurationTest {

    @Test
    fun `the duration is the buffered position minus the position`() {
        assertEquals(
            30_000L,
            bufferedDurationAheadMs(bufferedPositionMs = 930_000L, positionMs = 900_000L),
        )
    }

    @Test
    fun `the same amount buffered gives the same duration at any position`() {
        assertEquals(
            30_000L,
            bufferedDurationAheadMs(bufferedPositionMs = 30_000L, positionMs = 0L),
        )
        assertEquals(
            30_000L,
            bufferedDurationAheadMs(bufferedPositionMs = 3_630_000L, positionMs = 3_600_000L),
        )
    }

    @Test
    fun `nothing buffered ahead is zero`() {
        assertEquals(
            0L,
            bufferedDurationAheadMs(bufferedPositionMs = 900_000L, positionMs = 900_000L),
        )
    }

    @Test
    fun `a buffered position behind the position is zero, never negative`() {
        // After a seek mpv's cache time can lag the new position until it reports again.
        assertEquals(
            0L,
            bufferedDurationAheadMs(bufferedPositionMs = 10_000L, positionMs = 900_000L),
        )
    }
}
