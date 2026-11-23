package dev.jdtech.jellyfin.player.local.engine.mpv

import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

/**
 * #869: while mpv moves to the next entry, the player still names the item it leaves, and must
 * report that item's last position, not the next item's start.
 */
class MpvItemLeftPositionTest {

    /** A playlist entry; compared by identity, like a MediaItem in the player. */
    private class Entry(val name: String)

    private val e2 = Entry("E2")
    private val e3 = Entry("E3")
    private val e4 = Entry("E4")

    @Test
    fun `the item left keeps its last position until the index moves`() {
        val left = MpvItemLeftPosition<Entry>()
        left.onLoad(newItem = e3, currentItem = e2, positionMs = 719_958L)

        assertEquals(719_958L, left.positionMs(currentItem = e2))
    }

    @Test
    fun `the next item reads mpv's position`() {
        val left = MpvItemLeftPosition<Entry>()
        left.onLoad(newItem = e3, currentItem = e2, positionMs = 719_958L)

        assertNull(left.positionMs(currentItem = e3))
    }

    @Test
    fun `cleared once mpv names the next item`() {
        val left = MpvItemLeftPosition<Entry>()
        left.onLoad(newItem = e3, currentItem = e2, positionMs = 719_958L)
        left.clear()

        assertNull(left.positionMs(currentItem = e2))
    }

    @Test
    fun `a reload of the current entry keeps nothing`() {
        val left = MpvItemLeftPosition<Entry>()
        left.onLoad(newItem = e2, currentItem = e2, positionMs = 300_000L)

        assertNull(left.positionMs(currentItem = e2))
    }

    @Test
    fun `an item mpv never had a position for keeps nothing`() {
        val left = MpvItemLeftPosition<Entry>()
        left.onLoad(newItem = e3, currentItem = e2, positionMs = null)

        assertNull(left.positionMs(currentItem = e2))
    }

    @Test
    fun `a second skip before the index moves keeps the position of the item still named`() {
        val left = MpvItemLeftPosition<Entry>()
        left.onLoad(newItem = e3, currentItem = e2, positionMs = 719_958L)
        left.onLoad(newItem = e4, currentItem = e2, positionMs = left.positionMs(e2))

        assertEquals(719_958L, left.positionMs(currentItem = e2))
    }

    @Test
    fun `an equal but other entry does not get the position`() {
        val left = MpvItemLeftPosition<String>()
        left.onLoad(newItem = "E3", currentItem = String(charArrayOf('E', '2')), positionMs = 1L)

        assertNull(left.positionMs(currentItem = String(charArrayOf('E', '2'))))
    }
}
