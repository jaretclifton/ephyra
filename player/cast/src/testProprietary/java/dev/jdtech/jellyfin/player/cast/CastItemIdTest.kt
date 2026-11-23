package dev.jdtech.jellyfin.player.cast

import dev.jdtech.jellyfin.models.toItemId
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

/**
 * #773: the receiver's custom data names the item that plays. An empty or foreign id is no item, as
 * when the `UUID` parse rejected it: an IDLE status then clears the current item, and nothing is
 * restored or reported for it.
 */
class CastItemIdTest {
    @Test
    fun `an id this app sent is the item`() {
        assertEquals(ID.toItemId(), ID.castItemIdOrNull())
    }

    @Test
    fun `an empty or foreign id is no item`() {
        assertNull("".castItemIdOrNull())
        assertNull("not-an-id".castItemIdOrNull())
        assertNull("12345".castItemIdOrNull())
    }

    private companion object {
        const val ID = "0f8fad5b-d9cb-469f-a165-70867728950e"
    }
}
