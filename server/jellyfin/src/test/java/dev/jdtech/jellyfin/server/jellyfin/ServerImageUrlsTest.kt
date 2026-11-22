package dev.jdtech.jellyfin.server.jellyfin

import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

/**
 * The chapter and season backdrop URLs, as the player, Cast and the TV hero built them before they
 * moved behind the repository (#749).
 */
class ServerImageUrlsTest {
    private val itemId = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val seasonId = UUID.fromString("22222222-2222-2222-2222-222222222222")

    @Test
    fun `a chapter image is the item's chapter picture with its tag`() {
        assertEquals(
            "https://jf.example/Items/$itemId/Images/Chapter/3?tag=abc",
            chapterImageUrl("https://jf.example", itemId, 3, "abc"),
        )
    }

    @Test
    fun `a chapter image drops the trailing slash and keeps a path`() {
        assertEquals(
            "https://jf.example/stable/Items/$itemId/Images/Chapter/0?tag=t",
            chapterImageUrl("https://jf.example/stable//", itemId, 0, "t"),
        )
    }

    @Test
    fun `a chapter image needs a server`() {
        assertNull(chapterImageUrl("", itemId, 1, "t"))
        assertNull(chapterImageUrl("/", itemId, 1, "t"))
    }

    @Test
    fun `a season backdrop is the first backdrop without a tag`() {
        assertEquals(
            "https://jf.example/items/$seasonId/Images/Backdrop/0",
            seasonBackdropUrl("https://jf.example", seasonId),
        )
    }

    @Test
    fun `a season backdrop drops the trailing slash and keeps a path`() {
        assertEquals(
            "https://jf.example/jf/items/$seasonId/Images/Backdrop/0",
            seasonBackdropUrl("https://jf.example/jf/", seasonId),
        )
    }

    @Test
    fun `a season backdrop needs a server`() {
        assertNull(seasonBackdropUrl("", seasonId))
    }
}
