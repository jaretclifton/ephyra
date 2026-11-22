package dev.jdtech.jellyfin.domain.ports

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BitrateCapTest {
    @Test
    fun `no cap for zero and negative values`() {
        assertNull(capMbpsToBitsPerSecond(0))
        assertNull(capMbpsToBitsPerSecond(-5))
    }

    @Test
    fun `ordinary values are megabits times a million, as before`() {
        assertEquals(1_000_000, capMbpsToBitsPerSecond(1))
        assertEquals(8_000_000, capMbpsToBitsPerSecond(8))
        assertEquals(120_000_000, capMbpsToBitsPerSecond(120))
        assertEquals(2_147_000_000, capMbpsToBitsPerSecond(2147))
    }

    @Test
    fun `a value just above the overflow point is clamped, not negative`() {
        assertEquals(Int.MAX_VALUE, capMbpsToBitsPerSecond(2148))
        assertEquals(Int.MAX_VALUE, capMbpsToBitsPerSecond(3000))
    }

    @Test
    fun `the largest value a user can enter is clamped`() {
        assertEquals(Int.MAX_VALUE, capMbpsToBitsPerSecond(Int.MAX_VALUE))
    }
}
