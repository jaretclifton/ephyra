package dev.jdtech.jellyfin.player.local.engine.mpv

import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

/**
 * #869: while mpv moves to the next entry, the player still names the item it leaves, and must
 * report that item's last position, not the next item's start.
 */
class MpvItemLeftPositionTest {

    @Test
    fun `the item left keeps its last position until the index moves`() {
        val left = MpvItemLeftPosition()
        left.onLoad(newIndex = 2, currentIndex = 1, positionMs = 719_958L)

        assertEquals(719_958L, left.positionMs(currentIndex = 1))
    }

    @Test
    fun `the next item reads mpv's position`() {
        val left = MpvItemLeftPosition()
        left.onLoad(newIndex = 2, currentIndex = 1, positionMs = 719_958L)

        assertNull(left.positionMs(currentIndex = 2))
    }

    @Test
    fun `cleared once mpv names the next item`() {
        val left = MpvItemLeftPosition()
        left.onLoad(newIndex = 2, currentIndex = 1, positionMs = 719_958L)
        left.clear()

        assertNull(left.positionMs(currentIndex = 1))
    }

    @Test
    fun `a reload of the current entry keeps nothing`() {
        val left = MpvItemLeftPosition()
        left.onLoad(newIndex = 1, currentIndex = 1, positionMs = 300_000L)

        assertNull(left.positionMs(currentIndex = 1))
    }

    @Test
    fun `an item mpv never had a position for keeps nothing`() {
        val left = MpvItemLeftPosition()
        left.onLoad(newIndex = 2, currentIndex = 1, positionMs = null)

        assertNull(left.positionMs(currentIndex = 1))
    }

    @Test
    fun `a second skip before the index moves keeps the position of the item still named`() {
        val left = MpvItemLeftPosition()
        left.onLoad(newIndex = 2, currentIndex = 1, positionMs = 719_958L)
        left.onLoad(newIndex = 3, currentIndex = 1, positionMs = left.positionMs(1))

        assertEquals(719_958L, left.positionMs(currentIndex = 1))
    }
}
