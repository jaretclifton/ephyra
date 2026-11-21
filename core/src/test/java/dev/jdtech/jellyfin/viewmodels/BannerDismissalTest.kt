package dev.jdtech.jellyfin.viewmodels

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * CE (#775): the unreachable banner, put away by the user, stays away until the server has answered
 * again, and comes back with the next outage.
 */
class BannerDismissalTest {
    private val dismissal = BannerDismissal()

    @Test
    fun `dismissing while the server is away puts the banner away`() {
        dismissal.dismiss(unreachable = true)

        assertTrue(dismissal.dismissed.value)
    }

    @Test
    fun `it holds while the server stays away`() {
        dismissal.dismiss(unreachable = true)

        // The same state again, as every probe that fails reports it.
        repeat(3) { dismissal.onReachability(unreachable = true) }

        assertTrue(dismissal.dismissed.value)
    }

    @Test
    fun `the server answering clears it for the next outage`() {
        dismissal.dismiss(unreachable = true)

        dismissal.onReachability(unreachable = false)
        assertFalse(dismissal.dismissed.value)

        // The next outage shows the banner again.
        dismissal.onReachability(unreachable = true)
        assertFalse(dismissal.dismissed.value)
    }

    @Test
    fun `a dismissal that lands after the server answered does nothing`() {
        dismissal.dismiss(unreachable = false)

        assertFalse(dismissal.dismissed.value)
    }

    @Test
    fun `the indicator brings the banner back`() {
        dismissal.dismiss(unreachable = true)

        dismissal.show()

        assertFalse(dismissal.dismissed.value)
    }
}
