package dev.jdtech.jellyfin.server.jellyfin.mappers

import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.TrickplayInfoDto
import org.junit.Test

/**
 * CE (#860): which trickplay width of a version the player gets. The mappers took the largest key
 * as text, so of 320 and 1280 they took 320 by accident, and of 320 and 960 a sheet of 104 MB.
 */
class TrickplayWidthTest {
    private fun layout(width: Int, tiles: Int = 10) =
        TrickplayInfoDto(
            width = width,
            height = width * 9 / 16,
            tileWidth = tiles,
            tileHeight = tiles,
            thumbnailCount = 720,
            interval = 10_000,
            bandwidth = 0,
        )

    private fun preferredWidth(vararg widths: Pair<String, TrickplayInfoDto>): Int? =
        mapOf(*widths).preferredTrickplayInfo()?.width

    @Test
    fun `of 320 and 1280 on the default grid the 320 sheet is taken`() {
        // A 10 x 10 sheet of 1280 is 12800 x 7200: too big to decode whole.
        assertEquals(320, preferredWidth("320" to layout(320), "1280" to layout(1280)))
        assertEquals(320, preferredWidth("1280" to layout(1280), "320" to layout(320)))
    }

    @Test
    fun `widths are compared as numbers`() {
        // As text "960" is the larger; its 10 x 10 sheet is 9600 x 5400.
        assertEquals(320, preferredWidth("320" to layout(320), "960" to layout(960)))
        // A sheet of 2 x 2 tiles of 1280 is no bigger than the default: as text "320" won.
        assertEquals(1280, preferredWidth("320" to layout(320), "1280" to layout(1280, tiles = 2)))
    }

    @Test
    fun `the widest width whose sheet fits is taken`() {
        assertEquals(
            320,
            preferredWidth("160" to layout(160), "320" to layout(320), "640" to layout(640)),
        )
        // 640 on a 5 x 5 grid is a sheet of 3200 x 1800, as the default.
        assertEquals(640, preferredWidth("320" to layout(320), "640" to layout(640, tiles = 5)))
    }

    @Test
    fun `when no sheet fits the smallest is taken`() {
        assertEquals(640, preferredWidth("1280" to layout(1280), "640" to layout(640)))
    }

    @Test
    fun `a single width is taken whatever its size`() {
        assertEquals(320, preferredWidth("320" to layout(320)))
        assertEquals(1280, preferredWidth("1280" to layout(1280)))
    }

    @Test
    fun `a key that is not a number is passed over`() {
        assertEquals(320, preferredWidth("wide" to layout(640, tiles = 5), "320" to layout(320)))
        assertNull(preferredWidth("wide" to layout(320)))
    }

    @Test
    fun `each version gets its own width and one without a usable width is left out`() {
        val item =
            BaseItemDto(
                id = UUID.randomUUID(),
                type = BaseItemKind.MOVIE,
                trickplay =
                    mapOf(
                        "a" to mapOf("320" to layout(320), "1280" to layout(1280, tiles = 2)),
                        "b" to mapOf("320" to layout(320)),
                        "c" to mapOf("wide" to layout(320)),
                    ),
            )

        val info = item.toFindroidTrickplayInfo()

        assertEquals(mapOf("a" to 1280, "b" to 320), info?.mapValues { it.value.width })
    }
}
