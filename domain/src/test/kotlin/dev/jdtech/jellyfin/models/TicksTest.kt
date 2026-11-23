package dev.jdtech.jellyfin.models

import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class TicksTest {
    @Test
    fun `a tick is 100 nanoseconds`() {
        assertEquals(10_000L, Ticks.PER_MILLISECOND)
        assertEquals(1_000L * Ticks.PER_MILLISECOND, Ticks.PER_SECOND)
        assertEquals(60L * Ticks.PER_SECOND, Ticks.PER_MINUTE)
    }

    @Test
    fun `milliseconds are rounded toward zero`() {
        assertEquals(0L, Ticks.toMilliseconds(9_999L))
        assertEquals(1L, Ticks.toMilliseconds(10_000L))
        assertEquals(1L, Ticks.toMilliseconds(19_999L))
        assertEquals(-1L, Ticks.toMilliseconds(-19_999L))
        assertEquals(90_000L, Ticks.toMilliseconds(900_000_000L))
    }

    @Test
    fun `minutes are rounded toward zero`() {
        assertEquals(0L, Ticks.toMinutes(599_999_999L))
        assertEquals(1L, Ticks.toMinutes(600_000_000L))
        assertEquals(1L, Ticks.toMinutes(1_199_999_999L))
        assertEquals(120L, Ticks.toMinutes(72_000_000_000L))
    }

    @Test
    fun `seconds keep the fraction`() {
        assertEquals(0.0, Ticks.toSeconds(0L))
        assertEquals(1.0, Ticks.toSeconds(10_000_000L))
        assertEquals(1.5, Ticks.toSeconds(15_000_000L))
        assertEquals(60.0, Ticks.toSeconds(Ticks.PER_MINUTE))
    }

    @Test
    fun `milliseconds to ticks and back`() {
        assertEquals(0L, Ticks.fromMilliseconds(0L))
        assertEquals(10_000L, Ticks.fromMilliseconds(1L))
        assertEquals(600_000_000L, Ticks.fromMilliseconds(60_000L))
        assertEquals(86_390L, Ticks.toMilliseconds(Ticks.fromMilliseconds(86_390L)))
    }
}
