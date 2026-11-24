package dev.jdtech.jellyfin.player.local.domain

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/** #860: a failed trickplay sheet is left alone for a while, per sheet. */
class TrickplaySheetFailuresTest {

    private var now = 1_000L
    private val failures = TrickplaySheetFailures(nowMs = { now }, retryAfterMs = 30_000)

    @Test
    fun `a sheet that never failed may be tried`() {
        assertTrue(failures.mayTry(0))
    }

    @Test
    fun `a failed sheet waits for the window, then may be tried again`() {
        failures.failed(3)
        now += 29_999
        assertFalse(failures.mayTry(3))
        now += 1
        assertTrue(failures.mayTry(3))
    }

    @Test
    fun `one failed sheet does not hold back the others`() {
        failures.failed(3)
        assertTrue(failures.mayTry(2))
        assertTrue(failures.mayTry(4))
    }

    @Test
    fun `a sheet that loaded forgets its failure`() {
        failures.failed(3)
        failures.loaded(3)
        assertTrue(failures.mayTry(3))
    }
}
