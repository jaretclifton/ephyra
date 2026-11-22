package dev.jdtech.jellyfin.utils

import dev.jdtech.jellyfin.models.FindroidTrickplayInfo
import kotlin.test.assertEquals
import org.junit.Test

/** CE (#849): how many sheets a download of an item's trickplay asks the server for. */
class TrickplaySheetCountTest {
    private fun count(thumbnails: Int, tileWidth: Int = 10, tileHeight: Int = 10) =
        trickplaySheetCount(
            FindroidTrickplayInfo(
                width = 320,
                height = 180,
                tileWidth = tileWidth,
                tileHeight = tileHeight,
                thumbnailCount = thumbnails,
                interval = 10_000,
                bandwidth = 0,
            )
        )

    @Test
    fun `no thumbnails is no sheet`() {
        assertEquals(0, count(0))
        assertEquals(0, count(-1))
    }

    @Test
    fun `a partly filled last sheet is a sheet`() {
        assertEquals(1, count(1))
        assertEquals(1, count(99))
        assertEquals(2, count(101))
        assertEquals(3, count(250))
        assertEquals(4, count(31, tileWidth = 3, tileHeight = 3))
    }

    @Test
    fun `an exact multiple fills its last sheet`() {
        assertEquals(1, count(100))
        assertEquals(2, count(200))
        assertEquals(3, count(27, tileWidth = 3, tileHeight = 3))
        assertEquals(5, count(5, tileWidth = 1, tileHeight = 1))
    }

    @Test
    fun `a layout without tiles counts one thumbnail to a sheet`() {
        assertEquals(5, count(5, tileWidth = 0, tileHeight = 10))
    }
}
